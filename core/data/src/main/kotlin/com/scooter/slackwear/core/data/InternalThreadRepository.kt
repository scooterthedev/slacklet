package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.model.toSafeFailure
import com.scooter.slackwear.core.model.repository.FeedPageState
import com.scooter.slackwear.core.model.repository.ThreadItem
import com.scooter.slackwear.core.model.repository.ThreadMessage
import com.scooter.slackwear.core.model.repository.ThreadRepository
import com.scooter.slackwear.core.network.ClientApi
import com.scooter.slackwear.core.network.SlackApiException
import com.scooter.slackwear.core.network.unwrap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

class InternalThreadRepository(
    private val clientApi: ClientApi,
) : ThreadRepository {

    private val threads = MutableStateFlow<List<ThreadItem>>(emptyList())
    private val mutex = Mutex()

    override fun observeThreads(): Flow<List<ThreadItem>> = threads

    private val pageState = MutableStateFlow(FeedPageState())
    private var nextBoundary: String? = null
    private val usedBoundaries = mutableSetOf<String>()

    override fun observePageState(): Flow<FeedPageState> = pageState
    override suspend fun refresh(): Result<Unit> = fetchPage(refresh = true)
    override suspend fun loadMore(): Result<Unit> = fetchPage(refresh = false)

    private suspend fun fetchPage(refresh: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!refresh && !pageState.value.hasMore) return@withLock Result.success(Unit)
            pageState.value = pageState.value.copy(isLoading = true, error = null)
            try {
                val boundary = if (refresh) null else checkNotNull(nextBoundary)
                check(boundary == null || boundary !in usedBoundaries) { "Thread boundary repeated" }
                val response = clientApi.threadsGetView(
                    currentTs = boundary,
                    limit = THREAD_VIEW_LIMIT,
                    fetchThreadsState = true,
                    priorityMode = "all",
                ).requireThreadOk()
                val rows = response.decodeThreadView()
                val hasMore = (response["has_more"] as? JsonPrimitive)?.booleanOrNull
                    ?: error("Thread pagination state missing")
                val next = rows.lastOrNull()?.let {
                    it.latestReplyTs ?: it.unreadReplies.lastOrNull()?.ts ?: it.latestReplies.lastOrNull()?.ts
                }
                if (refresh) usedBoundaries.clear()
                boundary?.let { usedBoundaries += it }
                threads.value = ((if (refresh) emptyList() else threads.value)
                    .associateBy { it.channelId to it.threadTs } + rows.associateBy { it.channelId to it.threadTs })
                    .values.sortedByDescending { it.latestActivityTs.toBigDecimalOrNull() }
                nextBoundary = next
                pageState.value = FeedPageState(initialized = true, hasMore = hasMore)
                check(!hasMore || (next != null && next !in usedBoundaries)) { "Thread pagination incomplete" }
                Result.success(Unit)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                pageState.value = pageState.value.copy(error = error.toSafeFailure())
                Result.failure(error)
            } finally {
                pageState.value = pageState.value.copy(isLoading = false)
            }
        }
    }

    override suspend fun markRead(channelId: String, threadTs: String, latestReadTs: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    require(latestReadTs.toBigDecimalOrNull() != null)
                    clientApi.threadMark(channel = channelId, threadTs = threadTs, ts = latestReadTs, read = true).unwrap()
                    threads.value = threads.value.map { item ->
                        if (item.channelId != channelId || item.threadTs != threadTs) item
                        else {
                            val remaining = item.unreadReplies.filter {
                                val ts = it.ts.toBigDecimalOrNull()
                                ts == null || ts > latestReadTs.toBigDecimal()
                            }
                            item.copy(unread = remaining.isNotEmpty(), unreadReplies = remaining)
                        }
                    }
                }.onFailure { if (it is CancellationException) throw it }
            }
        }

    override suspend fun unfollow(channelId: String, threadTs: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    clientApi.threadRemove(channel = channelId, threadTs = threadTs).unwrap()
                    threads.value = threads.value.filterNot { it.channelId == channelId && it.threadTs == threadTs }
                }.onFailure { if (it is CancellationException) throw it }
            }
        }

    private companion object {
        const val THREAD_VIEW_LIMIT = 200L
    }
}

private fun JsonObject.requireThreadOk(): JsonObject {
    if ((this["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
        throw SlackApiException(str("error") ?: "invalid_thread_view_response")
    }
    return this
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

internal fun JsonObject.decodeThreadView(): List<ThreadItem> {
    val rows = this["threads"] as? JsonArray ?: error("Thread view is missing threads")
    return rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val root = row["root_msg"] as? JsonObject ?: return@mapNotNull null
        val channel = root.str("channel") ?: return@mapNotNull null
        val rootMessage = root.decodeThreadMessage() ?: return@mapNotNull null
        val unread = row.decodeReplies("unread_replies")
        ThreadItem(
            channelId = channel,
            threadTs = root.str("thread_ts") ?: rootMessage.ts,
            latestReplyTs = root.str("latest_reply"),
            unread = unread.isNotEmpty(),
            rootMessage = rootMessage,
            latestReplies = row.decodeReplies("latest_replies"),
            unreadReplies = unread,
            priority = (row["priority"] as? JsonObject)?.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
            }?.toMap().orEmpty(),
        )
    }.distinctBy { it.channelId to it.threadTs }
}

private fun JsonObject.decodeReplies(key: String): List<ThreadMessage> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.decodeThreadMessage() }

private fun JsonObject.decodeThreadMessage(): ThreadMessage? = str("ts")?.let {
    ThreadMessage(ts = it, authorId = str("user"), text = str("text"))
}
