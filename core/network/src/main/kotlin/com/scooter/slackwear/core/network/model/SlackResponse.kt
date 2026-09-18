package com.scooter.slackwear.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface SlackEnvelope {
    val ok: Boolean
    val error: String?
    val responseMetadata: ResponseMetadata?
}

@Serializable
data class ResponseMetadata(
    @SerialName("next_cursor") val nextCursor: String? = null,
    val messages: List<String>? = null,
)

@Serializable
data class AuthTestResponse(
    override val ok: Boolean,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    @SerialName("user_id") val userId: String? = null,
    val user: String? = null,
    @SerialName("team_id") val teamId: String? = null,
    val team: String? = null,
    val url: String? = null,
) : SlackEnvelope

@Serializable
data class ConversationsListResponse(
    override val ok: Boolean,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val channels: List<ConversationDto> = emptyList(),
) : SlackEnvelope

@Serializable
data class ConversationInfoResponse(
    override val ok: Boolean,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val channel: ConversationDto? = null,
) : SlackEnvelope

@Serializable
data class ConversationHistoryResponse(
    override val ok: Boolean,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val messages: List<MessageDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
) : SlackEnvelope

@Serializable
data class UsersInfoResponse(
    override val ok: Boolean,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val user: UserDto? = null,
) : SlackEnvelope

@Serializable
data class EmojiListResponse(
    override val ok: Boolean,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,

    val emoji: Map<String, String> = emptyMap(),
) : SlackEnvelope

@Serializable
data class DndInfoResponse(
    override val ok: Boolean,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    @SerialName("dnd_enabled") val dndEnabled: Boolean = false,
    @SerialName("snooze_enabled") val snoozeEnabled: Boolean = false,
    @SerialName("snooze_endtime") val snoozeEndTime: Long? = null,
) : SlackEnvelope

@Serializable
data class PostMessageResponse(
    override val ok: Boolean,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val ts: String? = null,
    val channel: String? = null,
    val message: MessageDto? = null,
) : SlackEnvelope

@Serializable
data class UploadUrlResponse(
    override val ok: Boolean,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    @SerialName("upload_url") val uploadUrl: String? = null,
    @SerialName("file_id") val fileId: String? = null,
) : SlackEnvelope

@Serializable
data class SimpleResponse(
    override val ok: Boolean,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
) : SlackEnvelope
