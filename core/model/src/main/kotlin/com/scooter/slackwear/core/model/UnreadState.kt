package com.scooter.slackwear.core.model

data class UnreadState(
    val conversationId: String,
    val count: Int,
    val mentionCount: Int,
    val lastActivityTs: String?,
    val confidence: Confidence,
) {
    val hasMention: Boolean get() = mentionCount > 0

    enum class Confidence {

        EXACT,

        DERIVED,
    }

    companion object {
        fun none(conversationId: String) = UnreadState(
            conversationId = conversationId,
            count = 0,
            mentionCount = 0,
            lastActivityTs = null,
            confidence = Confidence.DERIVED,
        )
    }
}
