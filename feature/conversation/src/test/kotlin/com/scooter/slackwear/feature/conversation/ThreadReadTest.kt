package com.scooter.slackwear.feature.conversation

import androidx.lifecycle.ViewModelStore
import com.scooter.slackwear.core.model.DeliveryState
import com.scooter.slackwear.core.model.Message
import com.scooter.slackwear.core.model.SlackUser
import com.scooter.slackwear.core.model.SlackFailureCode
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertNull
import com.scooter.slackwear.core.model.repository.MessageRepository
import com.scooter.slackwear.core.model.repository.PreferenceRepository
import com.scooter.slackwear.core.model.repository.ThreadItem
import com.scooter.slackwear.core.model.repository.ThreadRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import com.scooter.slackwear.core.model.repository.ConversationRepository
import com.scooter.slackwear.core.model.repository.ConversationSummary
import com.scooter.slackwear.core.model.repository.ActivityItem
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadReadTest {
    private val store = ViewModelStore()
    private val rows = MutableStateFlow(emptyList<Message>())
    private val loaded = CompletableDeferred<Result<Unit>>()
    private val marks = mutableListOf<List<String>>()
    private var markResult: Result<Unit> = Result.success(Unit)
    private var loadCalls = 0
    private val pagination = MutableStateFlow(com.scooter.slackwear.core.model.PaginationState())
    private var initialTimestamps: Set<String>? = setOf("9", "10", "100", "200")
    private var moreCalls = 0
    private var moreGate: CompletableDeferred<Unit>? = null
    private var loadBlock: suspend () -> Result<Unit> = { loaded.await() }
    private val messages = stub<MessageRepository> { name, _ ->
        when (name) {
            "threadPagination" -> pagination
            "observeThread" -> rows
            "observeAuthors" -> flowOf(emptyMap<String, SlackUser>())
            else -> error(name)
        }
    }
    private val threads = object : ThreadRepository {
        override fun observeThreads() = flowOf(emptyList<ThreadItem>())
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun markRead(channelId: String, threadTs: String, latestReadTs: String): Result<Unit> {
            marks += listOf(channelId, threadTs, latestReadTs)
            return markResult
        }
        override suspend fun unfollow(channelId: String, threadTs: String) = Result.success(Unit)
    }
    private val preferences = stub<PreferenceRepository> { name, _ ->
        when (name) {
            "observeQuickReplies" -> flowOf(emptyList<String>())
            "observeCustomEmoji" -> flowOf(emptyMap<String, String>())
            else -> error(name)
        }
    }

    @Before
    fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private val noConversations = object : ConversationRepository {
        override fun observeConversations() = flowOf(emptyList<ConversationSummary>())
        override fun observeActivity() = flowOf(emptyList<ActivityItem>())
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun markRead(conversationId: String, ts: String) = Result.success(Unit)
    }

    @Test
    fun openingMarksOnlyAfterLoadAtLatestAcknowledgedReply() = runTest {
        viewModel()
        runCurrent()
        assertTrue(marks.isEmpty())
        rows.value = listOf(
            message("9"), message("10", "9"),
            message("100", "9").copy(deliveryState = DeliveryState.PENDING),
            message("200", "other"),
        )
        loaded.complete(Result.success(Unit))
        advanceUntilIdle()
        assertEquals(listOf(listOf("C1", "9", "10")), marks)
    }

    @Test
    fun failedLoadDoesNotMarkCachedReplies() = runTest {
        rows.value = listOf(message("9"), message("10", "9"))
        viewModel()
        loaded.complete(Result.failure(IllegalStateException("synthetic")))
        advanceUntilIdle()
        assertTrue(marks.isEmpty())
    }

    @Test
    fun missingRootDoesNotInventReadPosition() = runTest {
        rows.value = listOf(message("10", "9"))
        viewModel()
        loaded.complete(Result.success(Unit))
        advanceUntilIdle()
        assertTrue(marks.isEmpty())
    }

    @Test
    fun retryRetainsFailedReadTimestampAndSafeCodeWithoutReloading() = runTest {
        markResult = Result.failure(object : Exception("private token U123"), SlackFailureCode {
            override val slackFailureCode = "not_allowed"
        })
        rows.value = listOf(message("9"), message("10", "9"))
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        loaded.complete(Result.success(Unit))
        runCurrent()
        assertEquals("not_allowed", vm.uiState.value.error?.failure?.code)
        assertEquals("Couldn't mark thread read - Slack: not_allowed", vm.uiState.value.error?.message)
        rows.value += message("20", "9")
        markResult = Result.success(Unit)
        vm.retry()
        vm.retry()
        runCurrent()
        assertEquals(listOf(listOf("C1", "9", "10"), listOf("C1", "9", "10")), marks)
        assertEquals(1, loadCalls)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun retryLoadDoesNotMarkUntilSuccessfulLoad() = runTest {
        loadBlock = { Result.failure(java.io.IOException("private query")) }
        rows.value = listOf(message("9"), message("10", "9"))
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        runCurrent()
        assertEquals(ThreadFailure.Operation.LOAD, vm.uiState.value.error?.operation)
        assertTrue(marks.isEmpty())
        loadBlock = { Result.success(Unit) }
        vm.retry()
        runCurrent()
        assertEquals(2, loadCalls)
        assertEquals(listOf(listOf("C1", "9", "10")), marks)
        assertNull(vm.uiState.value.error)
    }

    private fun viewModel(conversationId: String = "C1"): ThreadViewModel {
        val repository = object : MessageRepository by messages {
            override suspend fun loadThread(conversationId: String, threadTs: String): Result<Unit> {
                loadCalls++
                return loadBlock().onSuccess {
                    pagination.value = com.scooter.slackwear.core.model.PaginationState(
                        initialized = true, initialPageTimestamps = initialTimestamps,
                    )
                }
            }
            override suspend fun loadMoreReplies(conversationId: String, threadTs: String): Result<Unit> {
                moreCalls++
                moreGate?.await()
                rows.value += message("20", "9")
                return Result.success(Unit)
            }
        }
        return ThreadViewModel(conversationId, "9", repository, noConversations, preferences, threads)
            .also { store.put("thread", it) }
    }

    @Test
    fun unsubscribedRootDoesNotMarkRead() = runTest {
        rows.value = listOf(message("9").copy(isSubscribed = false), message("10", "9"))
        viewModel()
        loaded.complete(Result.success(Unit))
        advanceUntilIdle()
        assertTrue(marks.isEmpty())
    }

    @Test
    fun unknownSubscriptionDoesNotMarkRead() = runTest {
        rows.value = listOf(message("9").copy(isSubscribed = null), message("10", "9"))
        viewModel()
        loaded.complete(Result.success(Unit))
        advanceUntilIdle()
        assertTrue(marks.isEmpty())
    }

    @Test
    fun noLoadedRepliesDoesNotMarkRootRead() = runTest {
        rows.value = listOf(message("9"))
        viewModel()
        loaded.complete(Result.success(Unit))
        advanceUntilIdle()
        assertTrue(marks.isEmpty())
    }

    @Test
    fun cachedAndLaterPagesAreNotAcknowledged() = runTest {
        initialTimestamps = setOf("9", "10")
        rows.value = listOf(message("9"), message("10", "9"), message("19", "9"))
        val vm = viewModel()
        loaded.complete(Result.success(Unit))
        runCurrent()
        assertEquals(listOf(listOf("C1", "9", "10")), marks)
        vm.loadMoreReplies()
        runCurrent()
        assertEquals(1, moreCalls)
        assertEquals(listOf(listOf("C1", "9", "10")), marks)
    }

    @Test
    fun unknownPageProvenanceDoesNotAcknowledgeCachedReplies() = runTest {
        initialTimestamps = null
        rows.value = listOf(message("9"), message("10", "9"))
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        loaded.complete(Result.success(Unit))
        runCurrent()
        assertTrue(marks.isEmpty())
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun missingOrZeroReplyCountAndReadPositionsDoNotMark() = runTest {
        val roots = listOf(
            message("9").copy(threadReplyCount = null),
            message("9").copy(threadReplyCount = 0),
            message("9").copy(lastReadTs = null),
            message("9").copy(latestReplyTs = null),
            message("9").copy(lastReadTs = "20"),
        )
        loadBlock = { Result.success(Unit) }
        roots.forEach { root ->
            rows.value = listOf(root, message("10", "9"))
            viewModel()
            runCurrent()
        }
        assertTrue(marks.isEmpty())
    }

    @Test
    fun cachedRootCannotAuthorizeRead() = runTest {
        initialTimestamps = setOf("10")
        rows.value = listOf(message("9"), message("10", "9"))
        viewModel()
        loaded.complete(Result.success(Unit))
        runCurrent()
        assertTrue(marks.isEmpty())
    }

    @Test
    fun clearingViewModelCancelsLoadAndPaging() = runTest {
        rows.value = listOf(message("9"), message("10", "9"))
        viewModel()
        runCurrent()
        store.clear()
        loaded.complete(Result.success(Unit))
        runCurrent()
        assertTrue(marks.isEmpty())
        val vm = viewModel()
        runCurrent()
        moreGate = CompletableDeferred()
        vm.loadMoreReplies()
        vm.loadMoreReplies()
        runCurrent()
        assertEquals(1, moreCalls)
        store.clear()
        moreGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("9", "10"), rows.value.map { it.ts })
    }

    @Test
    fun eligibleDirectMessageUsesItsChannelAndRootTimestamp() = runTest {
        rows.value = listOf(message("9"), message("10", "9")).map { it.copy(conversationId = "D1") }
        viewModel("D1")
        loaded.complete(Result.success(Unit))
        runCurrent()
        assertEquals(listOf(listOf("D1", "9", "10")), marks)
    }

    private fun message(ts: String, threadTs: String? = null) = Message(
        ts, "C1", "U1", "text", threadTs,
        isSubscribed = true, lastReadTs = "9", latestReplyTs = "20", threadReplyCount = 2,
    )
    private inline fun <reified T> stub(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args.orEmpty())
        } as T
}
