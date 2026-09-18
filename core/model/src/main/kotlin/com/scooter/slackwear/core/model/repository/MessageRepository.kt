package com.scooter.slackwear.core.model.repository

import com.scooter.slackwear.core.model.Message
import com.scooter.slackwear.core.model.SlackUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import com.scooter.slackwear.core.model.PaginationState

interface MessageRepository {

    fun observeMessages(conversationId: String): Flow<List<Message>>

    fun observeThread(conversationId: String, threadTs: String): Flow<List<Message>>

    fun observeAuthors(): Flow<Map<String, SlackUser>>

    suspend fun findMessage(conversationId: String, ts: String): Message?

    suspend fun lookupMessage(conversationId: String, ts: String): Result<Message?> =
        Result.success(findMessage(conversationId, ts))

    suspend fun loadHistory(conversationId: String): Result<Unit>

    suspend fun loadThread(conversationId: String, threadTs: String): Result<Unit>

    fun historyPagination(conversationId: String): StateFlow<PaginationState> =
        MutableStateFlow(PaginationState(initialized = true, endReached = true))

    fun threadPagination(conversationId: String, threadTs: String): StateFlow<PaginationState> =
        MutableStateFlow(PaginationState(initialized = true, endReached = true))

    suspend fun loadOlderHistory(conversationId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("History pagination unavailable"))

    suspend fun loadMoreReplies(conversationId: String, threadTs: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Thread pagination unavailable"))

    suspend fun sendMessage(conversationId: String, text: String, threadTs: String? = null): Result<Unit>

    suspend fun toggleReaction(conversationId: String, ts: String, emoji: String, add: Boolean): Result<Unit>

    suspend fun sendFile(
        conversationId: String,
        filename: String,
        bytes: ByteArray,
        comment: String? = null,
        threadTs: String? = null,
    ): Result<Unit>
}
