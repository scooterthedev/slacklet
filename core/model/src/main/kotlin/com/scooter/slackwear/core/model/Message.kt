package com.scooter.slackwear.core.model

data class Message(

    val ts: String,
    val conversationId: String,
    val authorId: String,
    val text: String,
    val threadTs: String? = null,
    val replyCount: Int = 0,
    val reactions: List<Reaction> = emptyList(),
    val isEdited: Boolean = false,
    val deliveryState: DeliveryState = DeliveryState.SENT,
    val isSubscribed: Boolean? = null,
    val lastReadTs: String? = null,
    val latestReplyTs: String? = null,
    val threadReplyCount: Int? = null,
) {
    val isThreadReply: Boolean get() = threadTs != null && threadTs != ts

    val epochSeconds: Long get() = ts.substringBefore('.').toLongOrNull() ?: 0L
}

enum class DeliveryState {
    PENDING,
    SENT,
    FAILED,
}

data class Reaction(
    val name: String,
    val count: Int,
    val includesMe: Boolean,

    val imageUrl: String? = null,
)
