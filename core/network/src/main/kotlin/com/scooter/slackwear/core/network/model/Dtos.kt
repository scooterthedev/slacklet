package com.scooter.slackwear.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConversationDto(
    val id: String,
    val name: String? = null,
    @SerialName("is_channel") val isChannel: Boolean = false,
    @SerialName("is_group") val isGroup: Boolean = false,
    @SerialName("is_im") val isIm: Boolean = false,
    @SerialName("is_mpim") val isMpim: Boolean = false,
    @SerialName("is_private") val isPrivate: Boolean = false,
    @SerialName("is_archived") val isArchived: Boolean = false,

    val user: String? = null,
    val topic: TopicDto? = null,

    @SerialName("last_read") val lastRead: String? = null,
    @SerialName("unread_count_display") val unreadCountDisplay: Int? = null,

    @SerialName("is_open") val isOpen: Boolean? = null,
    val latest: MessageDto? = null,
)

@Serializable
data class TopicDto(val value: String = "")

@Serializable
data class MessageDto(
    val ts: String,
    val type: String? = null,
    val subtype: String? = null,
    val user: String? = null,
    val text: String = "",
    @SerialName("thread_ts") val threadTs: String? = null,
    @SerialName("reply_count") val replyCount: Int? = null,
    val subscribed: Boolean? = null,
    @SerialName("last_read") val lastRead: String? = null,
    @SerialName("latest_reply") val latestReply: String? = null,
    val reactions: List<ReactionDto> = emptyList(),
    val edited: EditedDto? = null,
)

@Serializable
data class EditedDto(val user: String? = null, val ts: String? = null)

@Serializable
data class ReactionDto(
    val name: String,
    val count: Int = 0,
    val users: List<String> = emptyList(),
)

@Serializable
data class UserDto(
    val id: String,
    val name: String? = null,
    @SerialName("real_name") val realName: String? = null,
    @SerialName("is_bot") val isBot: Boolean = false,
    val profile: ProfileDto? = null,
)

@Serializable
data class ProfileDto(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("real_name") val realName: String? = null,
    @SerialName("image_72") val image72: String? = null,
    @SerialName("image_192") val image192: String? = null,
)
