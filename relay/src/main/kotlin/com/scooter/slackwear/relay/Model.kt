package com.scooter.slackwear.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val slackUserId: String,
    val teamId: String,
    val fcmToken: String,
    val settings: NotificationSettings = NotificationSettings(),
    val registrationId: String = java.util.UUID.randomUUID().toString(),
    val deviceId: String = java.util.UUID.randomUUID().toString(),
    val capabilityHash: String = "",
    val revoked: Boolean = false,
    val tokenGeneration: String = registrationId + ":" + notificationDigest(fcmToken),
    val teamIds: Set<String> = emptySet(),
) {
    fun answersTo(candidate: String): Boolean = candidate == teamId || candidate in teamIds
}

@Serializable
data class NotificationSettings(
    val pushToWatch: Boolean = true,
    val mentions: Boolean = true,
    val directMessages: Boolean = true,
    val threadReplies: Boolean = true,
    val allActivity: Boolean = false,
    val followSlackDnd: Boolean = true,
)

@Serializable
data class RegisterRequest(
    val slackUserId: String,
    val teamId: String,
    val fcmToken: String,
    val slackUserToken: String? = null,
)

@Serializable
data class SettingsRequest(
    val slackUserId: String,
    val settings: NotificationSettings,
)

@Serializable
data class SlackEventEnvelope(
    val type: String,
    val challenge: String? = null,
    @SerialName("team_id") val teamId: String? = null,
    @SerialName("enterprise_id") val enterpriseId: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    val event: SlackEvent? = null,
    val authorizations: List<Authorization> = emptyList(),
)

@Serializable
data class Authorization(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("team_id") val teamId: String? = null,
    @SerialName("is_bot") val isBot: Boolean? = null,
    @SerialName("enterprise_id") val enterpriseId: String? = null,
)

@Serializable
data class SlackEvent(
    val type: String,
    val subtype: String? = null,
    val user: String? = null,
    val text: String? = null,
    val ts: String? = null,
    val channel: String? = null,
    @SerialName("channel_type") val channelType: String? = null,
    @SerialName("thread_ts") val threadTs: String? = null,
    @SerialName("bot_id") val botId: String? = null,
)

enum class PushKind {
    MENTION,
    DIRECT_MESSAGE,
    THREAD_REPLY,
    CHANNEL_ACTIVITY,
}
