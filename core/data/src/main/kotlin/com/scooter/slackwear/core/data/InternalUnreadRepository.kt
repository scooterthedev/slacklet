package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.ConversationDao
import com.scooter.slackwear.core.database.dao.UnreadPatch
import com.scooter.slackwear.core.database.entity.ConversationEntity
import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.network.model.ClientCountsChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.scooter.slackwear.core.model.UnreadState
import com.scooter.slackwear.core.model.repository.BadgeCounts
import com.scooter.slackwear.core.model.repository.UnreadChannel
import com.scooter.slackwear.core.model.repository.UnreadRepository
import com.scooter.slackwear.core.model.repository.UnreadTotals
import com.scooter.slackwear.core.network.ClientApi
import com.scooter.slackwear.core.network.model.ClientBadgeCountsResponse
import com.scooter.slackwear.core.network.model.ClientCountsBadges
import com.scooter.slackwear.core.network.model.ClientCountsResponse
import com.scooter.slackwear.core.network.model.ClientCountsSummaryResponse
import com.scooter.slackwear.core.network.model.CountsRequest
import com.scooter.slackwear.core.network.unwrap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class InternalUnreadRepository(
    private val clientApi: ClientApi,
    private val conversationDao: ConversationDao,
    private val slackApi: com.scooter.slackwear.core.network.SlackApi? = null,
) : UnreadRepository {

    private val refreshMutex = Mutex()
    private val _counts = MutableStateFlow<ClientCountsResponse?>(null)
    private val _summary = MutableStateFlow<ClientCountsSummaryResponse?>(null)
    private val _badgeCounts = MutableStateFlow<ClientBadgeCountsResponse?>(null)

    override suspend fun unreadChannels(): Result<List<UnreadChannel>> = runCatching {
        val counts = refreshMutex.withLock { _counts.value ?: fetchCounts() }
        (counts.channels + counts.ims + counts.mpims).map { row ->
            UnreadChannel(
                conversationId = row.id,
                hasUnreads = row.hasUnreads == true,
                mentionCount = (row.mentionCount ?: 0L).coerceAtLeast(0L),
                lastRead = row.lastRead,
            )
        }
    }

    override suspend fun totals(): Result<UnreadTotals> = runCatching {
        val summary = _summary.value ?: fetchSummary()
        val badges = summary.channelBadges ?: ClientCountsBadges()
        UnreadTotals(
            channels = badges.channels,
            dms = badges.dms,
            threadMentions = badges.threadMentions,
            threadUnreads = badges.threadUnreads,
            hasAnyUnreads = summary.hasUnreads,
        )
    }

    override suspend fun badgeCounts(): Result<BadgeCounts> = runCatching {
        val badges = _badgeCounts.value ?: fetchBadgeCounts()
        BadgeCounts(
            hasAnyUnreads = badges.hasAnyUnreads,
            unreadByEntryType = badges.activityUnreadCountByEntryType,
        )
    }

    override suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val outcomes = coroutineScope {
                listOf(
                    async { runCatching { fetchCounts() } },
                    async { runCatching { _summary.value = fetchSummary() } },
                    async { runCatching { _badgeCounts.value = fetchBadgeCounts() } },
                ).awaitAll()
            }
            outcomes.forEach { if (it.exceptionOrNull() is CancellationException) throw it.exceptionOrNull()!! }
            val failures = outcomes.mapNotNull { it.exceptionOrNull() }
            if (failures.isEmpty()) Result.success(Unit)
            else Result.failure(failures.first().apply {
                failures.drop(1).forEach(::addSuppressed)
            })
        }
    }

    private suspend fun fetchCounts(): ClientCountsResponse {
        val counts = clientApi.counts(CountsRequest(includeAllUnreads = true)).unwrap()
        val rows = counts.channels + counts.ims + counts.mpims
        val stored = rows.map { it.id }.distinct().chunked(900)
            .flatMap { conversationDao.findByIds(it) }.associateBy { it.id }.toMutableMap()
        val now = System.currentTimeMillis()
        val missing = rows.filter { it.id !in stored && (it.hasUnreads == true || (it.mentionCount ?: 0) > 0) }
            .distinctBy { it.id }
        val permits = Semaphore(4)
        val resolutions = coroutineScope {
            missing.map { row ->
                async {
                    permits.withPermit {
                        runCatching {
                            val metadata = checkNotNull(slackApi) { "Unread metadata resolver is not wired" }
                                .conversationInfo(row.id).unwrap().channel
                            requireNotNull(metadata).also {
                                require(it.id == row.id)
                                require(it.isChannel || it.isGroup || it.isPrivate || it.isIm || it.isMpim)
                            }.toEntity(now)
                        }.onFailure { if (it is CancellationException) throw it }
                    }
                }
            }.awaitAll()
        }
        val resolved = resolutions.mapNotNull { it.getOrNull() }
        if (resolved.isNotEmpty()) {
            conversationDao.insertIgnoring(resolved)
            resolved.map { it.id }.chunked(900).flatMap { conversationDao.findByIds(it) }
                .forEach { stored[it.id] = it }
        }
        conversationDao.updateUnreadBatch(rows.mapNotNull { row ->
            stored[row.id]?.let { mergeUnreadCount(it, row, now) }
        })
        _counts.value = counts
        if (resolutions.any { it.isFailure }) {
            throw com.scooter.slackwear.core.network.SlackApiException("unread_metadata_incomplete")
        }
        return counts
    }

    private suspend fun fetchSummary(): ClientCountsSummaryResponse =
        clientApi.countsSummary().unwrap()

    private suspend fun fetchBadgeCounts(): ClientBadgeCountsResponse =
        clientApi.badgeCounts().unwrap()
}

internal fun mergeUnreadCount(
    stored: ConversationEntity,
    row: ClientCountsChannel,
    now: Long,
): UnreadPatch {
    val latest = row.latest?.takeIf(String::isNotBlank)
    val lastRead = row.lastRead?.takeIf(String::isNotBlank)
    val storedLatest = stored.latestTs
    val storedLastRead = stored.lastSeenTs
    val stale = (latest != null && storedLatest != null && latest < storedLatest) ||
        (lastRead != null && storedLastRead != null && lastRead < storedLastRead)
    val sameWindow = latest != null && lastRead != null &&
        latest == stored.latestTs && lastRead == stored.lastSeenTs
    val direct = stored.kind == ConversationKind.DIRECT_MESSAGE || stored.kind == ConversationKind.GROUP_MESSAGE
    val keepExact = direct && stored.unreadConfidence == UnreadState.Confidence.EXACT &&
        stored.unreadCount > 0 && sameWindow && row.hasUnreads != false
    val count = when {
        stale -> stored.unreadCount
        row.hasUnreads == false -> 0
        keepExact -> stored.unreadCount
        row.hasUnreads == true -> 1
        sameWindow -> stored.unreadCount
        else -> stored.unreadCount.coerceAtMost(1)
    }
    return UnreadPatch(
        id = stored.id,
        count = count,
        mentions = if (stale) stored.mentionCount else row.mentionCount
            ?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: stored.mentionCount,
        confidence = when {
            stale -> stored.unreadConfidence
            row.hasUnreads == false || keepExact -> UnreadState.Confidence.EXACT
            else -> UnreadState.Confidence.DERIVED
        },
        lastSeen = if (stale) null else lastRead,
        refreshedAtMillis = if (stale) stored.refreshedAtMillis else now,
        latestTs = if (stale) null else latest,
    )
}
