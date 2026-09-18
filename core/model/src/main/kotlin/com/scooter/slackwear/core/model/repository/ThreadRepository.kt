package com.scooter.slackwear.core.model.repository

import kotlinx.coroutines.flow.Flow

interface ThreadRepository {

    fun observeThreads(): Flow<List<ThreadItem>>
    fun observePageState(): Flow<FeedPageState> = kotlinx.coroutines.flow.flowOf(FeedPageState())
    suspend fun loadMore(): Result<Unit> = Result.success(Unit)

    suspend fun refresh(): Result<Unit>

    suspend fun markRead(channelId: String, threadTs: String, latestReadTs: String): Result<Unit>

    suspend fun unfollow(channelId: String, threadTs: String): Result<Unit>
}

data class ThreadItem(
    val channelId: String,
    val threadTs: String,
    val unread: Boolean = false,
    val rootMessage: ThreadMessage? = null,
    val latestReplies: List<ThreadMessage> = emptyList(),
    val unreadReplies: List<ThreadMessage> = emptyList(),
    val priority: Map<String, String> = emptyMap(),
    val latestReplyTs: String? = null,
) {
    val latestActivityTs: String
        get() = (latestReplies + unreadReplies).map { it.ts }.plus(listOfNotNull(latestReplyTs))
            .maxByOrNull { it.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO }
            ?: rootMessage?.ts ?: threadTs
}

data class ThreadMessage(
    val ts: String,
    val authorId: String? = null,
    val text: String? = null,
)
