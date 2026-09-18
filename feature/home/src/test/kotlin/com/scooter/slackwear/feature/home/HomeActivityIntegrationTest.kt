package com.scooter.slackwear.feature.home

import androidx.lifecycle.ViewModelStore
import com.scooter.slackwear.core.model.ChannelSectionConfig
import com.scooter.slackwear.core.model.SlackUser
import com.scooter.slackwear.core.model.repository.ActivityEntryInput
import com.scooter.slackwear.core.model.repository.ActivityItem
import com.scooter.slackwear.core.model.repository.ActivityMarkReadRequest
import com.scooter.slackwear.core.model.repository.ActivityRepository
import com.scooter.slackwear.core.model.repository.ActivityViewFilter
import com.scooter.slackwear.core.model.repository.BadgeCounts
import com.scooter.slackwear.core.model.repository.ConversationRepository
import com.scooter.slackwear.core.model.repository.ConversationSummary
import com.scooter.slackwear.core.model.repository.SettingsRepository
import com.scooter.slackwear.core.model.repository.ThreadItem
import com.scooter.slackwear.core.model.repository.ThreadRepository
import com.scooter.slackwear.core.model.repository.entryInput
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeActivityIntegrationTest {
    private val store = ViewModelStore()
    private val feed = MutableStateFlow(emptyList<ActivityItem>())
    private val local = MutableStateFlow(emptyList<ActivityItem>())
    private val threads = MutableStateFlow(emptyList<ThreadItem>())
    private val summaries = MutableStateFlow(emptyList<ConversationSummary>())
    private val marks = mutableListOf<ActivityMarkReadRequest>()
    private var refreshBlock: suspend () -> Result<Unit> = { Result.success(Unit) }
    private var markBlock: suspend () -> Result<Long?> = { Result.success(7L) }
    private var conversationMarks = 0
    private var rosterResult: Result<Unit> = Result.success(Unit)
    private var viewsResult: Result<List<ActivityViewFilter>> = Result.success(emptyList())
    private var badgesResult: Result<BadgeCounts> = Result.success(BadgeCounts(false))
    private var threadsResult: Result<Unit> = Result.success(Unit)
    private val conversations = object : ConversationRepository {
        override fun observeConversations() = summaries
        override fun observeActivity() = local
        override suspend fun refresh() = rosterResult
        override suspend fun markRead(conversationId: String, ts: String): Result<Unit> {
            conversationMarks++
            return Result.success(Unit)
        }
    }
    private val pages = MutableStateFlow(com.scooter.slackwear.core.model.repository.FeedPageState())
    private var moreCalls = 0
    private val activity = object : ActivityRepository {
        override fun observeFeed() = feed
        override fun observePageState() = pages
        override suspend fun refresh() = refreshBlock()
        override suspend fun loadMore(): Result<Unit> {
            moreCalls++
            pages.value = pages.value.copy(hasMore = false)
            return Result.success(Unit)
        }
        override suspend fun views() = viewsResult
        override suspend fun badgeCounts() = badgesResult
        override suspend fun markRead(request: ActivityMarkReadRequest): Result<Long?> {
            marks += request
            return markBlock().onSuccess {
                feed.value = feed.value.map { item ->
                    if (item.entryInput() in request.entries.orEmpty()) item.copy(unreadCount = 0) else item
                }
            }
        }
        override suspend fun markUnread(entries: List<ActivityEntryInput>) = Result.success(Unit)
    }
    private val threadRepository = object : ThreadRepository {
        override fun observeThreads() = threads
        override suspend fun refresh() = threadsResult
        override suspend fun markRead(channelId: String, threadTs: String, latestReadTs: String) = Result.success(Unit)
        override suspend fun unfollow(channelId: String, threadTs: String) = Result.success(Unit)
    }
    private val settings = Proxy.newProxyInstance(
        SettingsRepository::class.java.classLoader,
        arrayOf(SettingsRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "observeSections" -> flowOf(ChannelSectionConfig())
            else -> error(method.name)
        }
    } as SettingsRepository

    @Before
    fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun feedWinsOverSyntheticTargetsAndRetainsDistinctThreadRoots() = runTest {
        val thread = item("feed-thread", "C1", "10").copy(messageTs = "20")
        val dm = item("feed-dm", "D1").copy(kind = ActivityItem.Kind.DIRECT_MESSAGE, messageTs = "30")
        feed.value = listOf(thread, dm)
        local.value = listOf(thread, dm.copy(id = "dm:D1", entryKey = null), item("dm:D2", "D2").copy(entryKey = null))
        threads.value = listOf(ThreadItem("C1", "10", true), ThreadItem("C1", "11", true))
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.activity.collect() }
        runCurrent()
        assertEquals(setOf("feed-thread", "feed-dm", "dm:D2", "thread:C1:11"), vm.activity.value.map { it.id }.toSet())
        assertEquals("10", vm.activity.value.first { it.id == thread.id }.target?.threadTs)
        val synthetic = vm.activity.value.first { it.id == "thread:C1:11" }
        assertEquals("11", synthetic.target?.threadTs)
        assertNull(synthetic.entryInput())
        assertTrue(marks.isEmpty())
        assertEquals(0, conversationMarks)
    }

    @Test
    fun missingActivityRepositoryPreservesLocalFallback() = runTest {
        local.value = listOf(item("local", "D1").copy(entryKey = null))
        val vm = viewModel(withActivity = false)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.activity.collect() }
        runCurrent()
        assertEquals(local.value, vm.activity.value)
        vm.markActivityRead(local.value.single())
        runCurrent()
        assertTrue(marks.isEmpty())
    }

    @Test
    fun activityHidesGenericChannelRowsButRetainsNotificationsAndAllUnreadChannels() = runTest {
        val channel = item("channel", "C1").copy(kind = ActivityItem.Kind.CHANNEL, entryType = "channel")
        val notifications = listOf(
            ActivityItem.Kind.MENTION,
            ActivityItem.Kind.USER_GROUP_MENTION,
            ActivityItem.Kind.CHANNEL_MENTION,
            ActivityItem.Kind.EVERYONE_MENTION,
            ActivityItem.Kind.DIRECT_MESSAGE,
            ActivityItem.Kind.BOT_DIRECT_MESSAGE,
            ActivityItem.Kind.THREAD_REPLY,
            ActivityItem.Kind.REACTION,
            ActivityItem.Kind.KEYWORD,
            ActivityItem.Kind.UNKNOWN,
        ).map { kind -> item(kind.name, "C1").copy(kind = kind) }
        val localDm = item("unread:D1", "D1").copy(kind = ActivityItem.Kind.DIRECT_MESSAGE, entryKey = null)
        feed.value = listOf(channel) + notifications
        local.value = listOf(channel.copy(id = "unread:C1", entryKey = null), localDm)
        threads.value = listOf(ThreadItem("C1", "10", true))
        summaries.value = (1..2).map {
            ConversationSummary(
                com.scooter.slackwear.core.model.Conversation("C$it", "channel$it", com.scooter.slackwear.core.model.ConversationKind.PUBLIC_CHANNEL),
                com.scooter.slackwear.core.model.UnreadState("C$it", 5, 0, null, com.scooter.slackwear.core.model.UnreadState.Confidence.EXACT),
                null, null,
            )
        }
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.activity.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.conversations.collect() }
        runCurrent()
        assertEquals(notifications.map { it.id }.toSet() + setOf(localDm.id, "thread:C1:10"), vm.activity.value.map { it.id }.toSet())
        assertEquals(summaries.value, vm.conversations.value)
        assertEquals(setOf("C1", "C2"), vm.conversations.value.unreadForChannelList().sectionsOf()
            .flatMap { it.conversations }.map { it.conversation.id }.toSet())
        assertTrue(vm.conversations.value.all { it.unread.count == 5 })
        assertEquals(0, conversationMarks)
    }

    @Test
    fun localFallbackHidesGenericChannelUnreadsButKeepsMentionsAndDms() = runTest {
        val channel = item("unread:C1", "C1").copy(kind = ActivityItem.Kind.CHANNEL, entryKey = null)
        val mention = item("mention", "C1").copy(kind = ActivityItem.Kind.MENTION, entryType = "at_user")
        val dm = item("unread:D1", "D1").copy(kind = ActivityItem.Kind.DIRECT_MESSAGE, entryKey = null)
        local.value = listOf(channel, mention, dm)
        val vm = viewModel(withActivity = false)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.activity.collect() }
        runCurrent()
        assertEquals(setOf(mention.id, dm.id), vm.activity.value.map { it.id }.toSet())
    }

    @Test
    fun refreshRemainsLoadingUntilCompletionAndFailureSurvivesUntilSuccessfulRetry() = runTest {
        val pending = CompletableDeferred<Result<Unit>>()
        refreshBlock = { pending.await() }
        val vm = viewModel()
        runCurrent()
        assertTrue(vm.isLoading.value)
        pending.complete(Result.failure(IllegalStateException("synthetic")))
        runCurrent()
        assertFalse(vm.isLoading.value)
        assertEquals(setOf("activity"), vm.refreshFailures.value.keys)
        refreshBlock = { Result.success(Unit) }
        vm.refresh()
        assertTrue(vm.isLoading.value)
        runCurrent()
        assertFalse(vm.isLoading.value)
        assertTrue(vm.refreshFailures.value.isEmpty())
    }

    @Test
    fun openingOnlyGenuineUnreadEntriesPreservesIdentityAndRetriesFailedAcknowledgement() = runTest {
        val genuine = item("feed", "C1", "10").copy(messageTs = "20")
        feed.value = listOf(genuine)
        val pending = CompletableDeferred<Result<Long?>>()
        markBlock = { pending.await() }
        val vm = viewModel()
        runCurrent()
        vm.markActivityRead(genuine.copy(entryKey = null))
        vm.markActivityRead(genuine.copy(entryKey = ""))
        vm.markActivityRead(genuine.copy(unreadCount = 0))
        runCurrent()
        assertTrue(marks.isEmpty())
        vm.markActivityRead(genuine)
        vm.markActivityRead(genuine)
        runCurrent()
        assertEquals(setOf(genuine.entryInput()), vm.activityReadPending.value)
        assertEquals(listOf(ActivityMarkReadRequest(entries = listOf(genuine.entryInput()!!))), marks)
        assertEquals(1, feed.value.single().unreadCount)
        pending.complete(Result.failure(IllegalStateException("synthetic")))
        runCurrent()
        assertTrue(vm.activityReadPending.value.isEmpty())
        assertEquals(setOf(genuine.entryInput()), vm.activityReadFailures.value.keys)
        assertEquals(1, feed.value.single().unreadCount)
        markBlock = { Result.success(7L) }
        vm.retryActivityReads()
        runCurrent()
        assertEquals(2, marks.size)
        assertEquals(marks.first(), marks.last())
        assertTrue(vm.activityReadFailures.value.isEmpty())
        assertTrue(vm.activityReadPending.value.isEmpty())
        assertEquals(0, feed.value.single().unreadCount)
        assertEquals(0, conversationMarks)
    }

    @Test
    fun unrelatedFailuresDoNotClaimActivityIsIncomplete() = runTest {
        rosterResult = Result.failure(IllegalStateException("private roster"))
        viewsResult = Result.failure(IllegalStateException("private views"))
        badgesResult = Result.failure(IllegalStateException("private badges"))
        val vm = viewModel()
        runCurrent()
        assertEquals(setOf("roster", "activityViews", "activityBadges"), vm.refreshFailures.value.keys)
        assertTrue(vm.refreshFailures.value.activityWarnings().isEmpty())
        val allUnrelated = listOf("roster", "sidebar", "sections", "unread", "unreadTotals", "activityViews", "activityBadges")
            .associateWith { com.scooter.slackwear.core.model.SafeFailure.http(403) }
        assertTrue(allUnrelated.activityWarnings().isEmpty())
    }

    @Test
    fun feedAndThreadFailuresAreAccuratelyAttributedAndSanitized() = runTest {
        refreshBlock = { Result.failure(java.io.IOException("private feed")) }
        threadsResult = Result.failure(object : Exception("private thread"), com.scooter.slackwear.core.model.SlackFailureCode {
            override val slackFailureCode = "not_allowed"
        })
        val vm = viewModel()
        runCurrent()
        assertEquals(listOf(
            "Activity feed: Connection error. Activity may be incomplete.",
            "Followed threads: Slack: not_allowed. Activity may be incomplete.",
        ), vm.refreshFailures.value.activityWarnings())
    }

    @Test
    fun readOrOlderFeedCannotHideFreshUnreadAndThreadSortUsesLatestReply() = runTest {
        val stale = item("old", "D1").copy(kind = ActivityItem.Kind.DIRECT_MESSAGE, unreadCount = 0, messageTs = "10")
        feed.value = listOf(stale)
        local.value = (1..250).map {
            item("unread:$it", "D$it").copy(kind = ActivityItem.Kind.DIRECT_MESSAGE, entryKey = null, messageTs = "40")
        }
        threads.value = listOf(ThreadItem("C1", "1", true, latestReplyTs = "50"))
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.activity.collect() }
        runCurrent()
        assertEquals(252, vm.activity.value.size)
        assertEquals("thread:C1:1", vm.activity.value.first().id)
        feed.value = listOf(stale.copy(unreadCount = 1))
        runCurrent()
        assertTrue(vm.activity.value.any { it.id == "unread:1" })
        feed.value = listOf(stale.copy(unreadCount = 1, messageTs = "40"))
        runCurrent()
        assertFalse(vm.activity.value.any { it.id == "unread:1" })
        assertEquals("key:old", vm.activity.value.first { it.id == "old" }.entryInput()?.key)
    }

    @Test
    fun homeLoadsMoreOnlyWhileRepositoryHasMore() = runTest {
        pages.value = com.scooter.slackwear.core.model.repository.FeedPageState(initialized = true, hasMore = true)
        val vm = viewModel()
        runCurrent()
        vm.loadMoreActivity()
        vm.loadMoreActivity()
        runCurrent()
        assertEquals(1, moreCalls)
        assertFalse(vm.activityPageState.value.hasMore)
        vm.loadMoreActivity()
        runCurrent()
        assertEquals(1, moreCalls)
    }

    @Test
    fun mutedAndMentionOnlyConversationsRemainInAllUnreads() {
        val rows = (1..250).map {
            ConversationSummary(
                com.scooter.slackwear.core.model.Conversation("C$it", "channel", com.scooter.slackwear.core.model.ConversationKind.PUBLIC_CHANNEL, isMuted = true),
                com.scooter.slackwear.core.model.UnreadState("C$it", if (it == 250) 0 else 1, if (it == 250) 1 else 0, null, com.scooter.slackwear.core.model.UnreadState.Confidence.DERIVED),
                null, null,
            )
        }
        assertEquals(250, rows.unreadForChannelList().size)
        assertEquals(250, rows.unreadForChannelList().sectionsOf().sumOf { it.conversations.size })
    }

    private fun viewModel(withActivity: Boolean = true) = HomeViewModel(
        repository = conversations,
        settings = settings,
        activityRepository = if (withActivity) activity else null,
        threadRepository = threadRepository,
    ).also { store.put("home", it) }

    private fun item(id: String, channel: String, threadTs: String? = null) = ActivityItem(
        id = id,
        kind = ActivityItem.Kind.THREAD_REPLY,
        conversationId = channel,
        conversationName = channel,
        author = SlackUser("U1", "Test", "Test", null),
        preview = "Synthetic",
        timestamp = "30",
        unreadCount = 1,
        threadTs = threadTs,
        entryType = "thread_v2",
        entryKey = "key:$id",
    )
}
