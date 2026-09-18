package com.scooter.slackwear.feature.notifications

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

@Serializable
data class RelayBinding(
    val teamId: String,
    val userId: String,
    val deviceId: String,
    val capability: String,
    val registrationId: String? = null,
)

data class NotificationAccount(val teamId: String, val userId: String)

interface RelayBindingStore {
    fun current(): RelayBinding?
    fun save(binding: RelayBinding)
    fun clear()
}

class EncryptedRelayBindingStore(context: Context) : RelayBindingStore {
    private val preferences = EncryptedSharedPreferences.create(
        context, "notification_relay_v2",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Synchronized
    override fun current(): RelayBinding? = preferences.getString("binding", null)?.let {
        Json.decodeFromString<RelayBinding>(it)
    }

    @Synchronized
    override fun save(binding: RelayBinding) {
        check(preferences.edit().putString("binding", Json.encodeToString(binding)).commit())
    }

    @Synchronized
    override fun clear() {
        check(preferences.edit().clear().commit())
    }
}

internal fun newRelayBinding(account: NotificationAccount) = RelayBinding(
    account.teamId, account.userId, UUID.randomUUID().toString(),
    ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) },
)

internal fun capabilityDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun RelayBinding.matches(account: NotificationAccount?): Boolean = account != null && teamId == account.teamId && userId == account.userId
