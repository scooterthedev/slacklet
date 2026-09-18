package com.scooter.slackwear.feature.conversation

import androidx.lifecycle.ViewModelStore
import com.scooter.slackwear.core.model.DeliveryState
import com.scooter.slackwear.core.model.Message
import com.scooter.slackwear.core.model.SlackUser
import com.scooter.slackwear.core.model.repository.ActivityItem
import com.scooter.slackwear.core.model.repository.ConversationRepository
import com.scooter.slackwear.core.model.repository.ConversationSummary
import com.scooter.slackwear.core.model.repository.EmojiCount
import com.scooter.slackwear.core.model.repository.EmojiRepository
import com.scooter.slackwear.core.model.repository.HuddleRepository
import com.scooter.slackwear.core.model.repository.HuddleState
import com.scooter.slackwear.core.model.repository.MessageRepository
import com.scooter.slackwear.core.model.repository.PreferenceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
class ConversationReadViewModelTest {
    private val store = ViewModelStore()
    private val messages = ReadMessages()
    private val marks = mutableListOf<String>()
    private var markResult = Result.success(Unit)
    private var markGate: CompletableDeferred<Unit>? = null
    private val conversations = object : ConversationRepository {
        override fun observeConversations() = flowOf(emptyList<ConversationSummary>())
        override fun observeActivity() = flowOf(emptyList<ActivityItem>())
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun markRead(conversationId: String, ts: String): Result<Unit> {
            assertEquals("C1", conversationId)
            marks += ts
            markGate?.await()
            return markResult
        }
    }

    @Before
    fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun waitsForSuccessfulLoadAndSelectsOnlyValidSentConversationMessages() = runTest {
        messages.gate = CompletableDeferred()
        messages.rows.value = listOf(
            message("1700000000.000001"),
            message("1700000000.000002"),
            message("1700000000.000003").copy(deliveryState = DeliveryState.PENDING),
            message("1700000000.000004").copy(deliveryState = DeliveryState.FAILED),
            message("1700000000.000005").copy(conversationId = "C2"),
            message("1700000000.000006").copy(threadTs = "1700000000.000001"),
            message("local:1"), message("1e99"),
        )
        val vm = viewModel()
        vm.refresh()
        runCurrent()
        assertTrue(marks.isEmpty())
        assertEquals(1, messages.loads)
        messages.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("1700000000.000002"), marks)
    }

    @Test
    fun failedLoadDoesNotMarkCachedMessagesAndExposesSanitizedRetry() = runTest {
        messages.rows.value = listOf(message("1700000000.000001"))
        messages.result = Result.failure(IllegalStateException("private token details"))
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect() }
        runCurrent()
        assertTrue(marks.isEmpty())
        assertEquals("Couldn't load messages. Retry.", vm.uiState.value.readError)
        messages.result = Result.success(Unit)
        vm.uiState.value.retryRead()
        runCurrent()
        assertEquals(listOf("1700000000.000001"), marks)
        assertNull(vm.uiState.value.readError)
    }

    @Test
    fun failedMarkRetriesOriginalTimestampNotNewerArrivalAndIgnoresDuplicateTaps() = runTest {
        messages.rows.value = listOf(message("1700000000.000001"))
        markResult = Result.failure(IllegalStateException("private server payload"))
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect() }
        runCurrent()
        assertEquals("Couldn't sync read status. Retry.", vm.uiState.value.readError)
        messages.rows.value += message("1700000000.000002")
        markResult = Result.success(Unit)
        markGate = CompletableDeferred()
        vm.retryRead()
        vm.retryRead()
        vm.refresh()
        runCurrent()
        assertEquals(List(2) { "1700000000.000001" }, marks)
        assertEquals(1, messages.loads)
        markGate!!.complete(Unit)
        runCurrent()
        assertNull(vm.uiState.value.readError)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun pendingOnlyOrEmptyHistoryDoesNotMarkRead() = runTest {
        messages.rows.value = listOf(message("1700000000.000001").copy(deliveryState = DeliveryState.PENDING))
        val vm = viewModel()
        runCurrent()
        messages.rows.value = emptyList()
        vm.refresh()
        runCurrent()
        assertTrue(marks.isEmpty())
    }

    @Test
    fun cancellationDuringLoadDoesNotMarkOrReportFailure() = runTest {
        messages.rows.value = listOf(message("1700000000.000001"))
        messages.result = Result.failure(CancellationException("cancelled"))
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect() }
        runCurrent()
        assertTrue(marks.isEmpty())
        assertNull(vm.uiState.value.readError)
    }

    @Test
    fun clearingViewModelCancelsInFlightLoad() = runTest {
        messages.gate = CompletableDeferred()
        messages.rows.value = listOf(message("1700000000.000001"))
        viewModel()
        runCurrent()
        store.clear()
        messages.gate!!.complete(Unit)
        runCurrent()
        assertTrue(marks.isEmpty())
    }

    @Test
    fun reactionPickerRepositoryOptOutRemainsEffective() = runTest {
        messages.rows.value = listOf(message("1700000000.000001"))
        viewModel(object : ConversationRepository by conversations {
            override suspend fun markRead(conversationId: String, ts: String) = Result.success(Unit)
        })
        runCurrent()
        assertTrue(marks.isEmpty())
    }

    @Test
    fun cachedNewerMessagesAndOlderPagesDoNotAdvanceReadPosition() = runTest {
        messages.initialTimestamps = setOf("1700000000.000001")
        messages.rows.value = listOf(message("1700000000.000001"), message("1800000000.000001"))
        val vm = viewModel()
        runCurrent()
        assertEquals(listOf("1700000000.000001"), marks)
        vm.loadOlderHistory()
        runCurrent()
        assertEquals(1, messages.olderLoads)
        assertEquals(listOf("1700000000.000001"), marks)
    }

    private fun message(ts: String) = Message(ts, "C1", "U1", "synthetic")

    private fun viewModel(repository: ConversationRepository = conversations) = ConversationViewModel(
        conversationId = "C1",
        messages = messages,
        conversations = repository,
        preferences = object : PreferenceRepository {
            override fun observeQuickReplies() = flowOf(emptyList<String>())
            override fun observeFrequentReactions() = flowOf(emptyList<String>())
            override fun observeCustomEmoji() = flowOf(emptyMap<String, String>())
            override suspend fun recordQuickReply(text: String) = Unit
            override suspend fun recordReaction(emoji: String) = Unit
            override suspend fun syncCustomEmoji() = Result.success(Unit)
            override suspend fun searchEmoji(query: String) = emptyList<String>()
        },
        emojiRepository = object : EmojiRepository {
            override suspend fun mostUsed() = Result.success(emptyList<EmojiCount>())
        },
        huddles = object : HuddleRepository {
            override fun observeHuddle(conversationId: String) = flowOf<HuddleState?>(null)
            override suspend fun refresh(conversationId: String) = Result.success(Unit)
            override suspend fun knock(conversationId: String) = Result.success(Unit)
            override suspend fun cancelKnock(conversationId: String) = Result.success(Unit)
        },
    ).also { store.put("read", it) }

    private class ReadMessages : MessageRepository {
        val rows = MutableStateFlow(emptyList<Message>())
        var result = Result.success(Unit)
        var gate: CompletableDeferred<Unit>? = null
        var loads = 0
        val pagination = MutableStateFlow(com.scooter.slackwear.core.model.PaginationState())
        var initialTimestamps: Set<String>? = null
        var olderLoads = 0
        override fun historyPagination(conversationId: String) = pagination
        override suspend fun loadOlderHistory(conversationId: String): Result<Unit> {
            olderLoads++
            rows.value = listOf(Message("1600000000.000001", conversationId, "U1", "older")) + rows.value
            return Result.success(Unit)
        }
        override fun observeMessages(conversationId: String) = rows
        override fun observeAuthors() = flowOf(emptyMap<String, SlackUser>())
        override fun observeThread(conversationId: String, threadTs: String) = flowOf(emptyList<Message>())
        override suspend fun findMessage(conversationId: String, ts: String): Message? = null
        override suspend fun loadHistory(conversationId: String): Result<Unit> {
            loads++
            gate?.await()
            return result.onSuccess {
                pagination.value = com.scooter.slackwear.core.model.PaginationState(
                    initialized = true,
                    initialPageTimestamps = initialTimestamps ?: rows.value.map { it.ts }.toSet(),
                )
            }
        }
        override suspend fun loadThread(conversationId: String, threadTs: String) = Result.success(Unit)
        override suspend fun sendMessage(conversationId: String, text: String, threadTs: String?) = Result.success(Unit)
        override suspend fun toggleReaction(conversationId: String, ts: String, emoji: String, add: Boolean) = Result.success(Unit)
        override suspend fun sendFile(conversationId: String, filename: String, bytes: ByteArray, comment: String?, threadTs: String?) = Result.success(Unit)
    }
}
