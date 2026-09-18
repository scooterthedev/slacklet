package com.scooter.slackwear.core.model.repository

import com.scooter.slackwear.core.model.Conversation
import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.model.SlackUser
import com.scooter.slackwear.core.model.UnreadState
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {

    fun observeConversations(): Flow<List<ConversationSummary>>

    fun observeActivity(): Flow<List<ActivityItem>>

    suspend fun refresh(): Result<Unit>

    suspend fun markRead(conversationId: String, ts: String): Result<Unit>
}

data class ConversationSummary(
    val conversation: Conversation,
    val unread: UnreadState,
    val latestPreview: String?,
    val counterpart: SlackUser?,
) {

    val title: String
        get() = when (conversation.kind) {
            ConversationKind.DIRECT_MESSAGE ->
                counterpart?.displayName?.takeIf(String::isNotBlank)
                    ?: counterpart?.realName?.takeIf(String::isNotBlank)
                    ?: conversation.displayName

            else -> conversation.displayName
        }
}

data class ActivityItem(
    val id: String,
    val kind: Kind,
    val conversationId: String,
    val conversationName: String,
    val author: SlackUser,
    val preview: String,
    val timestamp: String,
    val unreadCount: Int,
    val messageTs: String? = null,
    val threadTs: String? = null,
    val entryType: String = "",
    val entryKey: String? = null,
    val unreadCountIsExact: Boolean = false,
) {
    val feedTs: String get() = timestamp
    val target: ActivityTarget?
        get() = conversationId.takeIf(String::isNotBlank)?.let {
            ActivityTarget(it, messageTs, threadTs)
        }

    enum class Kind {
        MENTION, USER_GROUP_MENTION, CHANNEL_MENTION, EVERYONE_MENTION,
        DIRECT_MESSAGE, BOT_DIRECT_MESSAGE, THREAD_REPLY, REACTION, CHANNEL, KEYWORD, UNKNOWN,
    }
}

data class ActivityTarget(
    val conversationId: String,
    val messageTs: String?,
    val threadTs: String?,
)
