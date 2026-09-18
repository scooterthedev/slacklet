package com.scooter.slackwear.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scooter.slackwear.core.model.ChannelSectionConfig
import com.scooter.slackwear.core.model.SafeFailure
import com.scooter.slackwear.core.model.toSafeFailure
import com.scooter.slackwear.core.model.repository.FeedPageState
import com.scooter.slackwear.core.model.repository.ActivityItem
import com.scooter.slackwear.core.model.repository.ActivityEntryInput
import com.scooter.slackwear.core.model.repository.ActivityMarkReadRequest
import com.scooter.slackwear.core.model.repository.entryInput
import com.scooter.slackwear.core.model.repository.ActivityRepository
import com.scooter.slackwear.core.model.repository.ActivityViewFilter
import com.scooter.slackwear.core.model.repository.BadgeCounts
import com.scooter.slackwear.core.model.SlackUser
import com.scooter.slackwear.core.model.repository.ConversationRepository
import com.scooter.slackwear.core.model.repository.ConversationSummary
import com.scooter.slackwear.core.model.repository.ThreadRepository
import com.scooter.slackwear.core.model.repository.SettingsRepository
import com.scooter.slackwear.core.model.repository.SidebarRepository
import com.scooter.slackwear.core.model.repository.UnreadRepository
import com.scooter.slackwear.core.model.repository.UnreadTotals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: ConversationRepository,
    private val settings: SettingsRepository,
    private val sidebarRepository: SidebarRepository? = null,
    private val unreadRepository: UnreadRepository? = null,
    private val activityRepository: ActivityRepository? = null,
    private val threadRepository: ThreadRepository? = null,
    private val failureMapper: (Throwable) -> SafeFailure = { it.toSafeFailure() },
) : ViewModel() {

    private val liveSections = MutableStateFlow<ChannelSectionConfig?>(null)

    val sectionConfig: StateFlow<ChannelSectionConfig> =
        combine(settings.observeSections(), liveSections) { stored, live ->
            live ?: stored
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelSectionConfig())

    private val unreadTotals = MutableStateFlow<UnreadTotals?>(null)
    private val activityViews = MutableStateFlow<List<ActivityViewFilter>>(emptyList())
    private val activityBadges = MutableStateFlow<BadgeCounts?>(null)

    private val livePolling: Flow<List<ConversationSummary>> = flow {
        refresh()
        while (true) {
            delay(HOME_REFRESH_INTERVAL_MILLIS)
            refresh()
        }
    }

    val conversations: StateFlow<List<ConversationSummary>> =
        merge(repository.observeConversations(), livePolling)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activity: StateFlow<List<ActivityItem>> =
        combine(
            activityRepository?.observeFeed() ?: repository.observeActivity(),
            threadRepository?.observeThreads() ?: flowOf(emptyList()),
            repository.observeConversations(),
            if (activityRepository != null) repository.observeActivity() else flowOf(emptyList()),
        ) { feed, threads, summaries, local ->
            val titles = summaries.associate { it.conversation.id to it.title }
            val activity = (feed + local.filter { candidate ->
                candidate.entryInput() == null && feed.none { it.coversTarget(candidate) }
            }).filterNot { it.kind == ActivityItem.Kind.CHANNEL }

            val threadItems = threads.map { thread ->
                ActivityItem(
                    id = "thread:${thread.channelId}:${thread.threadTs}",
                    kind = ActivityItem.Kind.THREAD_REPLY,
                    conversationId = thread.channelId,
                    conversationName = titles[thread.channelId] ?: thread.channelId,
                    author = SlackUser(thread.channelId, "Thread", "Thread", null),
                    preview = "New replies",
                    timestamp = thread.latestActivityTs,
                    unreadCount = if (thread.unread) 1 else 0,
                    messageTs = thread.latestActivityTs,
                    threadTs = thread.threadTs,
                )
            }.filter { candidate -> activity.none { it.coversTarget(candidate) } }

            (activity + threadItems).distinctBy { it.id }.sortedByDescending { it.occurredAt() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activityPageState = (activityRepository?.observePageState() ?: flowOf(FeedPageState()))
        .stateIn(viewModelScope, SharingStarted.Eagerly, FeedPageState())
    val threadPageState = (threadRepository?.observePageState() ?: flowOf(FeedPageState()))
        .stateIn(viewModelScope, SharingStarted.Eagerly, FeedPageState())
    private var loadMoreJob: Job? = null

    fun loadMoreActivity() {
        if (isLoading.value || loadMoreJob?.isActive == true) return
        loadMoreJob = viewModelScope.launch {
            if (activityPageState.value.hasMore && !activityPageState.value.isLoading) {
                activityRepository?.let { refreshSurface("activityPaging") { it.loadMore() } }
            }
            if (threadPageState.value.hasMore) {
                threadRepository?.let { refreshSurface("threadPaging") { it.loadMore() } }
            }
        }
    }

    private var busy = setOf(REFRESH_SURFACE)

    private val loading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = loading.asStateFlow()

    private val activityLoading = MutableStateFlow(true)
    val isActivityLoading: StateFlow<Boolean> = activityLoading.asStateFlow()

    @Synchronized
    private fun markBusy(key: String, active: Boolean) {
        busy = if (active) busy + key else busy - key
        loading.value = busy.isNotEmpty()
        activityLoading.value = ACTIVITY_SURFACES.any(busy::contains)
    }
    private val pendingReads = MutableStateFlow<Set<ActivityEntryInput>>(emptySet())
    val activityReadPending: StateFlow<Set<ActivityEntryInput>> = pendingReads.asStateFlow()
    private val readFailures = MutableStateFlow<Map<ActivityEntryInput, SafeFailure>>(emptyMap())
    val activityReadFailures: StateFlow<Map<ActivityEntryInput, SafeFailure>> = readFailures.asStateFlow()

    private val unreadOnly = MutableStateFlow(false)
    val activityUnreadOnly: StateFlow<Boolean> = unreadOnly.asStateFlow()

    fun toggleActivityUnreadOnly() {
        unreadOnly.value = !unreadOnly.value
    }

    val activityViewFilters: StateFlow<List<ActivityViewFilter>> = activityViews.asStateFlow()

    val badges: StateFlow<BadgeCounts?> = activityBadges.asStateFlow()

    val unreadTotal: StateFlow<Long> =
        unreadTotals
            .map { totals -> totals?.let { it.channels + it.dms + it.threadMentions } ?: 0L }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private var refreshJob: Job? = null
    private val failures = MutableStateFlow<Map<String, SafeFailure>>(emptyMap())
    val refreshFailures: StateFlow<Map<String, SafeFailure>> = failures.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        markBusy(REFRESH_SURFACE, active = true)
        refreshJob = viewModelScope.launch {
            try {
                refreshAll()
            } finally {
                markBusy(REFRESH_SURFACE, active = false)
            }
        }
    }

    private suspend fun refreshAll() = coroutineScope {
        buildList {
            add(async { refreshSurface("roster") { repository.refresh() } })

            sidebarRepository?.let { sidebar ->
                add(
                    async {
                        refreshSurface("sidebar") { sidebar.refresh() }
                        refreshSurface("sections") {
                            sidebar.sections().onSuccess { liveSections.value = it }
                        }
                    },
                )
            }

            unreadRepository?.let { unread ->
                add(
                    async {
                        refreshSurface("unread") { unread.refresh() }
                        refreshSurface("unreadTotals") {
                            unread.totals().onSuccess { unreadTotals.value = it }
                        }
                    },
                )
            }

            activityRepository?.let { activity ->
                add(
                    async {
                        refreshSurface("activity") { activity.refresh() }
                        listOf(
                            async {
                                refreshSurface("activityViews") {
                                    activity.views().onSuccess { activityViews.value = it }
                                }
                            },
                            async {
                                refreshSurface("activityBadges") {
                                    activity.badgeCounts().onSuccess { activityBadges.value = it }
                                }
                            },
                        ).awaitAll()
                    },
                )
            }

            threadRepository?.let { threads ->
                add(async { refreshSurface("threads") { threads.refresh() } })
            }
        }.awaitAll()
        Unit
    }

    fun markActivityRead(item: ActivityItem) {
        if (item.unreadCount <= 0) return
        item.entryInput()?.takeIf { it.key.isNotBlank() }?.let(::markActivityEntryRead)
    }

    fun retryActivityReads() {
        readFailures.value.keys.toList().forEach(::markActivityEntryRead)
    }

    private fun markActivityEntryRead(entry: ActivityEntryInput) {
        val activity = activityRepository ?: return
        if (entry in pendingReads.value) return
        pendingReads.value += entry
        readFailures.value -= entry
        viewModelScope.launch {
            try {
                activity.markRead(ActivityMarkReadRequest(entries = listOf(entry))).getOrThrow()
                refreshSurface("activityBadges") {
                    activity.badgeCounts().onSuccess { activityBadges.value = it }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                readFailures.value += entry to failureMapper(error)
            } finally {
                pendingReads.value -= entry
            }
        }
    }

    private suspend fun <T> refreshSurface(key: String, block: suspend () -> Result<T>) {
        markBusy(key, active = true)
        try {
            block().getOrThrow()
            failures.value = failures.value - key
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failures.value = failures.value + (key to failureMapper(error))
        } finally {
            markBusy(key, active = false)
        }
    }

}

internal fun Map<String, SafeFailure>.activityWarnings(): List<String> =
    listOf("activity" to "Activity feed", "threads" to "Followed threads").mapNotNull { (key, label) ->
        get(key)?.let { "$label: ${it.reason}. Activity may be incomplete." }
    }

private fun ActivityItem.occurredAt(): java.math.BigDecimal =
    (messageTs ?: timestamp).toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO

private fun ActivityItem.coversTarget(candidate: ActivityItem): Boolean {
    val existing = target ?: return false
    val other = candidate.target ?: return false
    if (existing.conversationId != other.conversationId) return false
    if (candidate.unreadCount > 0) {
        if (unreadCount < candidate.unreadCount) return false
        val latest = messageTs?.toBigDecimalOrNull() ?: return false
        val candidateLatest = (candidate.messageTs ?: candidate.timestamp).toBigDecimalOrNull() ?: return false
        if (latest < candidateLatest) return false
    }
    return when {
        other.threadTs != null -> existing.threadTs == other.threadTs
        candidate.kind == ActivityItem.Kind.DIRECT_MESSAGE || candidate.kind == ActivityItem.Kind.CHANNEL ->
            existing.threadTs == null && kind == candidate.kind
        else -> existing == other
    }
}

private const val HOME_REFRESH_INTERVAL_MILLIS = 20_000L

private const val REFRESH_SURFACE = "refresh"

private val ACTIVITY_SURFACES = setOf("activity", "activityViews", "activityBadges", "threads")
