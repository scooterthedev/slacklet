package com.scooter.slackwear.feature.notifications

import android.content.Context
import com.scooter.slackwear.core.model.repository.NotificationSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface DeliveryLog {
    fun entries(): Map<String, Long>
    fun record(key: String, expiresAt: Long)
    fun forget(keys: Collection<String>)
    fun clear()
}

class SharedPreferencesDeliveryLog(context: Context) : DeliveryLog {
    private val preferences = context.getSharedPreferences("notification_dedup_v2", Context.MODE_PRIVATE)

    override fun entries(): Map<String, Long> =
        preferences.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()

    override fun record(key: String, expiresAt: Long) {
        preferences.edit().putLong(key, expiresAt).apply()
    }

    override fun forget(keys: Collection<String>) {
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        editor.apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }
}

class NotificationPolicy(
    private val log: DeliveryLog,
    private val binding: () -> RelayBinding?,
    private val account: () -> NotificationAccount?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val delivered = HashMap<String, Long>().apply { putAll(log.entries()) }
    @Volatile private var settings = NotificationSettings()
    private val mutableStatus = MutableStateFlow("Notifications on")
    val status = mutableStatus.asStateFlow()

    fun settings(value: NotificationSettings) {
        settings = value
        updateStatus()
    }

    fun matches(teamId: String?, userId: String?, registrationId: String?): Boolean {
        val current = binding() ?: return false
        return current.matches(account()) && current.registrationId != null && current.registrationId == registrationId &&
            current.teamId == teamId && current.userId == userId
    }

    @Synchronized
    fun accept(payload: PushPayload): Boolean {
        if (!matches(payload.teamId, payload.slackUserId, payload.registrationId) ||
            !freshEnough(payload.expiresAt, now()) || !settings.pushToWatch
        ) return false
        val allowed = when (payload.kind) {
            PushKind.MENTION -> settings.mentions
            PushKind.DIRECT_MESSAGE -> settings.directMessages
            PushKind.THREAD_REPLY -> settings.threadReplies
            PushKind.CHANNEL_ACTIVITY -> settings.allActivity
        }
        if (!allowed) return false
        val key = capabilityDigest("${payload.teamId}/${payload.slackUserId}/${payload.registrationId}/${payload.eventId}")
        val moment = now()
        if (delivered[key]?.let { it > moment } == true) return false
        if (delivered.size >= DEDUP_CAPACITY) prune(moment)
        if (delivered.size >= DEDUP_CAPACITY) return false
        delivered[key] = payload.expiresAt
        log.record(key, payload.expiresAt)
        return true
    }

    private fun prune(moment: Long) {
        val expired = delivered.filterValues { it <= moment }.keys
        if (expired.isEmpty()) return
        delivered.keys.removeAll(expired)
        log.forget(expired)
    }

    @Synchronized
    fun clear() {
        delivered.clear()
        log.clear()
        updateStatus()
    }

    private fun updateStatus() {
        mutableStatus.value = when {
            !settings.pushToWatch -> "Notifications disabled"
            settings.followSlackDnd -> "Notifications on, paused during Slack DND"
            else -> "Notifications on"
        }
    }
}

private const val DEDUP_CAPACITY = 1024
