package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.ActivityDao
import com.scooter.slackwear.core.database.dao.ConversationDao
import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.database.entity.UserEntity
import com.scooter.slackwear.core.model.toSafeFailure
import com.scooter.slackwear.core.model.repository.FeedPageState
import com.scooter.slackwear.core.model.repository.ActivityEntryInput
import com.scooter.slackwear.core.model.repository.ActivityItem
import com.scooter.slackwear.core.model.repository.ActivityMarkReadRequest
import com.scooter.slackwear.core.model.repository.ActivityMutationGateway
import com.scooter.slackwear.core.model.repository.ActivityRepository
import com.scooter.slackwear.core.model.repository.ActivityViewFilter
import com.scooter.slackwear.core.model.repository.BadgeCounts
import com.scooter.slackwear.core.network.ClientApi
import com.scooter.slackwear.core.network.SlackApi
import com.scooter.slackwear.core.network.unwrap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class InternalActivityRepository(
    private val clientApi: ClientApi,
    private val slackApi: SlackApi,
    private val activityDao: ActivityDao,
    private val conversationDao: ConversationDao,
    private val userDao: UserDao,
    private val mutations: ActivityMutationGateway? = null,
) : ActivityRepository {

    private val views = MutableStateFlow<List<ActivityViewFilter>?>(null)
    private val badgeCounts = MutableStateFlow<BadgeCounts?>(null)
    private val mutex = Mutex()
    private val resolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nextCursor: String? = null
    private val usedCursors = mutableSetOf<String>()

    private val pageState = MutableStateFlow(FeedPageState())
    override fun observePageState(): Flow<FeedPageState> = pageState

    override fun observeFeed(): Flow<List<ActivityItem>> =
        combine(
            activityDao.observeRecent(),
            userDao.observeAll(),
            conversationDao.observeForDisplay(),
        ) { rows, users, conversations ->
            val byId = users.associate { it.id to it.toDomain() }
            val counterparts = conversations.mapNotNull { row ->
                row.counterpartUserId?.takeIf(String::isNotBlank)?.let { row.id to it }
            }.toMap()
            rows.map { it.toActivityItem(byId, counterparts) }
                .filterNot { it.kind == ActivityItem.Kind.CHANNEL }
        }.flowOn(Dispatchers.Default)

    override suspend fun views(): Result<List<ActivityViewFilter>> = unguarded {
        views.value ?: fetchViews().also { views.value = it }
    }

    override suspend fun badgeCounts(): Result<BadgeCounts> = unguarded {
        badgeCounts.value ?: fetchBadgeCounts().also { badgeCounts.value = it }
    }

    override suspend fun refresh(): Result<Unit> = pageOperation {
        fetchPage(null)
        usedCursors.clear()
        views.value = null
        badgeCounts.value = null
    }

    override suspend fun loadMore(): Result<Unit> = pageOperation {
        val cursor = nextCursor ?: return@pageOperation
        check(cursor !in usedCursors) { "Activity cursor repeated" }
        fetchPage(cursor)
        usedCursors += cursor
    }

    private suspend fun pageOperation(block: suspend () -> Unit): Result<Unit> = operation {
        pageState.value = pageState.value.copy(isLoading = true, error = null)
        try {
            block()
            pageState.value = FeedPageState(initialized = true, hasMore = nextCursor != null)
        } catch (error: Exception) {
            if (error !is CancellationException) {
                pageState.value = pageState.value.copy(error = error.toSafeFailure())
            }
            throw error
        } finally {
            pageState.value = pageState.value.copy(isLoading = false)
        }
    }

    private suspend fun fetchPage(cursor: String?) {
        val names = conversationDao.namesById().associate { it.id to it.name }
        val response = clientApi.activityFeed(limit = FEED_LIMIT, cursor = cursor).unwrap()
        val entries = response.items.mapNotNull { it.decodeActivityEntry(names) }
        activityDao.upsert(entries)

        val authorIds = entries.map { it.authorId }.filter(String::isNotBlank)
        resolutionScope.launch {
            runCatching { resolveUsers(api = slackApi, userDao = userDao, ids = authorIds) }
        }
        nextCursor = response.responseMetadata?.nextCursor?.takeIf(String::isNotBlank)
        pageState.value = pageState.value.copy(initialized = true, hasMore = nextCursor != null)
        cursor?.let { usedCursors += it }
        check(cursor == null || nextCursor == null || (nextCursor != cursor && nextCursor !in usedCursors)) {
            "Activity cursor repeated"
        }
    }

    override suspend fun markRead(request: ActivityMarkReadRequest): Result<Long?> = operation {
        val gateway = checkNotNull(mutations) { "Activity mutations are not wired" }
        val undo = gateway.markRead(request)
        val entries = request.entries.orEmpty() + listOfNotNull(
            request.key?.let { key ->
                request.feedTs?.let { ActivityEntryInput(request.type.orEmpty(), key, it) }
            },
        )
        entries.forEach { activityDao.markEntryRead(it.key, it.feedTs) }
        badgeCounts.value = null
        undo
    }

    override suspend fun markUnread(entries: List<ActivityEntryInput>): Result<Unit> = operation {
        require(entries.isNotEmpty())
        checkNotNull(mutations) { "Activity mutations are not wired" }.markUnread(entries)
        entries.forEach { activityDao.markEntryUnread(it.key, it.feedTs) }
        badgeCounts.value = null
    }

    private suspend fun fetchViews(): List<ActivityViewFilter> {
        val response = clientApi.activityViews().unwrap()
        return response.views.mapNotNull { it.decodeActivityView(response.prefs) }
    }

    private suspend fun fetchBadgeCounts(): BadgeCounts {
        val badges = clientApi.activityBadgeCounts(includeHasAnyUnreads = true).unwrap()
        return BadgeCounts(badges.hasAnyUnreads, badges.activityUnreadCountByEntryType)
    }

    private suspend fun <T> operation(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { block() }.onFailure { if (it is CancellationException) throw it }
        }
    }

    private suspend fun <T> unguarded(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching { block() }.onFailure { if (it is CancellationException) throw it }
    }

    private companion object {
        const val FEED_LIMIT = 50
    }
}

fun ActivityMarkReadRequest.toActivityJson(): JsonObject = buildJsonObject {
    type?.let { put("type", it) }
    ts?.let { put("ts", it) }
    threadTs?.let { put("thread_ts", it) }
    channel?.let { put("channel", it) }
    key?.let { put("key", it) }
    feedTs?.let { put("feed_ts", it) }
    entries?.let { put("entries", JsonArray(it.map(ActivityEntryInput::toActivityJson))) }
    undoKey?.let { put("undo_key", it) }
}

fun List<ActivityEntryInput>.toActivityUnreadJson(): JsonObject = buildJsonObject {
    put("entries", JsonArray(map(ActivityEntryInput::toActivityJson)))
}

private fun ActivityEntryInput.toActivityJson(): JsonObject = buildJsonObject {
    put("type", type)
    put("key", key)
    put("feed_ts", feedTs)
}
