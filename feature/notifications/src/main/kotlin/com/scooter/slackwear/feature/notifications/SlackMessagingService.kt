package com.scooter.slackwear.feature.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

abstract class SlackMessagingService : FirebaseMessagingService() {
    abstract val notifier: PushNotifier
    abstract fun onTokenRotated(token: String)
    open val policy: NotificationPolicy? get() = null
    open fun onPushDelivered(payload: PushPayload) = Unit

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = parsePushPayload(message.data) ?: return
        if (policy?.accept(payload) == false) return
        notifier.show(payload)
        onPushDelivered(payload)
    }

    override fun onNewToken(token: String) = onTokenRotated(token)
}

internal fun parsePushPayload(data: Map<String, String>, now: Long = System.currentTimeMillis()): PushPayload? {
    if (data["v"] != "2") return null
    val kind = PushKind.entries.firstOrNull { it.name == data["kind"] } ?: return null
    val channel = data["channelId"]?.takeIf(::validPushId) ?: return null
    val author = data["authorId"]?.takeIf(::validPushId) ?: return null
    val team = data["teamId"]?.takeIf(::validPushId) ?: return null
    val user = data["slackUserId"]?.takeIf(::validPushId) ?: return null
    val event = data["eventId"]?.takeIf(::validPushId) ?: return null
    val registration = data["registrationId"]?.takeIf {
        runCatching { java.util.UUID.fromString(it).toString() == it }.getOrDefault(false)
    } ?: return null
    val ts = data["ts"]?.takeIf(::validPushTimestamp) ?: return null
    val thread = data["threadTs"]
    if (thread != null && !validPushTimestamp(thread)) return null
    val expires = data["expiresAt"]?.toLongOrNull() ?: return null
    if (!freshEnough(expires, now)) return null
    val preview = data["preview"]?.takeIf { it.length <= 1000 } ?: return null
    return PushPayload(kind, channel, ts, author, thread, preview, event, registration, team, user, expires)
}

internal const val MAX_PUSH_LIFETIME_MILLIS = 3_600_000L
internal const val CLOCK_SKEW_ALLOWANCE_MILLIS = 300_000L

internal fun freshEnough(expiresAt: Long, now: Long): Boolean =
    expiresAt + CLOCK_SKEW_ALLOWANCE_MILLIS > now &&
        expiresAt - now <= MAX_PUSH_LIFETIME_MILLIS + CLOCK_SKEW_ALLOWANCE_MILLIS

private val PUSH_ID = Regex("[A-Za-z][A-Za-z0-9_-]{1,127}")
private val PUSH_TIMESTAMP = Regex("[0-9]{1,16}\\.[0-9]{1,6}")

internal fun validPushId(value: String): Boolean = value.matches(PUSH_ID)
internal fun validPushTimestamp(value: String): Boolean = value.matches(PUSH_TIMESTAMP)

data class PushPayload(
    val kind: PushKind,
    val channelId: String,
    val ts: String,
    val authorId: String,
    val threadTs: String?,
    val preview: String,
    val eventId: String = "",
    val registrationId: String = "",
    val teamId: String = "",
    val slackUserId: String = "",
    val expiresAt: Long = 0,
) {
    fun identity(): String = listOf(teamId, slackUserId, registrationId, channelId, threadTs.orEmpty()).joinToString("/")
    fun notificationId(): Int = identity().hashCode()
}

enum class PushKind { MENTION, DIRECT_MESSAGE, THREAD_REPLY, CHANNEL_ACTIVITY }
