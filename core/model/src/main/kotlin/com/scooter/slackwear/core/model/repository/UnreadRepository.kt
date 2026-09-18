package com.scooter.slackwear.core.model.repository

import kotlinx.coroutines.flow.Flow

interface UnreadRepository {

    suspend fun unreadChannels(): Result<List<UnreadChannel>>

    suspend fun totals(): Result<UnreadTotals>

    suspend fun badgeCounts(): Result<BadgeCounts>

    suspend fun refresh(): Result<Unit>
}

data class UnreadChannel(
    val conversationId: String,
    val hasUnreads: Boolean,
    val mentionCount: Long,
    val lastRead: String?,
)

data class UnreadTotals(
    val channels: Long,
    val dms: Long,
    val threadMentions: Long,
    val threadUnreads: Long,
    val hasAnyUnreads: Boolean,
)

data class BadgeCounts(
    val hasAnyUnreads: Boolean,
    val unreadByEntryType: Map<String, Long> = emptyMap(),
)
