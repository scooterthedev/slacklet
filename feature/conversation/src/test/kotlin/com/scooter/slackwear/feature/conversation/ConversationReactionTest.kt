package com.scooter.slackwear.feature.conversation

import androidx.lifecycle.ViewModelStore
import com.scooter.slackwear.core.model.Message
import com.scooter.slackwear.core.model.Reaction
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationReactionTest {
    private val messages = ReactionMessages()
    private val preferences = ReactionPreferences()
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun reactsWithoutUiStateSubscriber() = runTest {
        val vm = viewModel()
        vm.react(TS, "party_parrot")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.messages.isEmpty())
        assertEquals(listOf(ReactionCall("C1", TS, "party_parrot", true)), messages.calls)
        assertEquals(listOf("party_parrot"), preferences.recorded)
        assertEquals(ReactionState.Succeeded, vm.reaction.value)
    }

    @Test
    fun targetsReplyTimestampRatherThanRoot() = runTest {
        messages.target = messages.target!!.copy(threadTs = "1700000000.000001")
        val vm = viewModel()
        vm.react(TS, "+1")
        advanceUntilIdle()

        assertEquals(TS, messages.calls.single().ts)
        assertEquals(ReactionState.Succeeded, vm.reaction.value)
    }

    @Test
    fun existingReactionRemovesWithoutRecordingUsage() = runTest {
        messages.target = messages.target!!.copy(reactions = listOf(Reaction("+1", 4, true)))
        val vm = viewModel()
        vm.react(TS, "+1")
        advanceUntilIdle()

        assertFalse(messages.calls.single().add)
        assertTrue(preferences.recorded.isEmpty())
        assertEquals(ReactionState.Succeeded, vm.reaction.value)
    }

    @Test
    fun failureKeepsPickerStateAndSearchForRetryWithoutUsage() = runTest {
        messages.result = Result.failure(IllegalStateException("not_reactable"))
        val vm = viewModel()
        vm.searchEmoji("parrot")
        vm.react(TS, "party_parrot")
        advanceUntilIdle()

        assertTrue(vm.reaction.value is ReactionState.Failed)
        assertTrue((vm.reaction.value as ReactionState.Failed).message.isNotBlank())
        assertEquals("parrot", vm.emojiSearch.value.query)
        assertTrue(preferences.recorded.isEmpty())

        messages.result = Result.success(Unit)
        vm.react(TS, "party_parrot")
        advanceUntilIdle()
        assertEquals(ReactionState.Succeeded, vm.reaction.value)
        assertEquals(listOf("party_parrot"), preferences.recorded)
    }

    @Test
    fun missingTargetReportsErrorWithoutMutationOrUsage() = runTest {
        messages.target = null
        val vm = viewModel()
        vm.react(TS, "+1")
        advanceUntilIdle()

        assertTrue(vm.reaction.value is ReactionState.Failed)
        assertTrue(messages.calls.isEmpty())
        assertTrue(preferences.recorded.isEmpty())
    }

    @Test
    fun successDismissalCannotCancelPendingRequestAndDuplicateTapsAreIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        messages.gate = gate
        val vm = viewModel()
        var dismissed = false
        backgroundScope.launch {
            vm.reaction.first { it == ReactionState.Succeeded }
            assertTrue(messages.completed)
            store.clear()
            dismissed = true
        }
        vm.react(TS, "+1")
        vm.react(TS, "eyes")
        runCurrent()

        assertEquals(ReactionState.Pending, vm.reaction.value)
        assertFalse(dismissed)
        assertFalse(messages.completed)
        assertEquals(1, messages.calls.size)

        gate.complete(Unit)
        runCurrent()
        assertTrue(dismissed)
        assertTrue(messages.completed)
        assertEquals(listOf("+1"), preferences.recorded)
    }

    @Test
    fun optionalUsageFailureDoesNotTurnAcceptedMutationIntoFailure() = runTest {
        preferences.failRecording = true
        val vm = viewModel()
        vm.react(TS, "+1")
        advanceUntilIdle()

        assertTrue(messages.completed)
        assertEquals(ReactionState.Succeeded, vm.reaction.value)
    }

    private fun viewModel() = ConversationViewModel(
        conversationId = "C1",
        messages = messages,
        preferences = preferences,
        conversations = object : ConversationRepository {
            override fun observeConversations(): Flow<List<ConversationSummary>> = flowOf(emptyList())
            override fun observeActivity(): Flow<List<ActivityItem>> = flowOf(emptyList())
            override suspend fun refresh() = Result.success(Unit)
            override suspend fun markRead(conversationId: String, ts: String) = Result.success(Unit)
        },
        emojiRepository = object : EmojiRepository {
            override suspend fun mostUsed(): Result<List<EmojiCount>> = Result.success(emptyList())
        },
        huddles = object : HuddleRepository {
            override fun observeHuddle(conversationId: String): Flow<HuddleState?> = flowOf(null)
            override suspend fun refresh(conversationId: String) = Result.success(Unit)
            override suspend fun knock(conversationId: String) = Result.success(Unit)
            override suspend fun cancelKnock(conversationId: String) = Result.success(Unit)
        },
    ).also { store.put("reaction", it) }

    private companion object {
        const val TS = "1700000001.123456"
    }

    private class ReactionMessages : MessageRepository {
        var target: Message? = Message(TS, "C1", "U1", "hello")
        var result = Result.success(Unit)
        var gate: CompletableDeferred<Unit>? = null
        var completed = false
        val calls = mutableListOf<ReactionCall>()

        override suspend fun findMessage(conversationId: String, ts: String) =
            target?.takeIf { it.conversationId == conversationId && it.ts == ts }
        override fun observeMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())
        override fun observeThread(conversationId: String, threadTs: String): Flow<List<Message>> =
            flowOf(listOfNotNull(target))
        override fun observeAuthors(): Flow<Map<String, SlackUser>> = flowOf(emptyMap())
        override suspend fun loadHistory(conversationId: String) = Result.success(Unit)
        override suspend fun loadThread(conversationId: String, threadTs: String) = Result.success(Unit)
        override suspend fun sendMessage(conversationId: String, text: String, threadTs: String?) =
            Result.success(Unit)
        override suspend fun sendFile(
            conversationId: String,
            filename: String,
            bytes: ByteArray,
            comment: String?,
            threadTs: String?,
        ) = Result.success(Unit)
        override suspend fun toggleReaction(conversationId: String, ts: String, emoji: String, add: Boolean): Result<Unit> {
            calls += ReactionCall(conversationId, ts, emoji, add)
            gate?.await()
            completed = true
            return result
        }
    }

    private data class ReactionCall(val conversationId: String, val ts: String, val emoji: String, val add: Boolean)

    private class ReactionPreferences : PreferenceRepository {
        val recorded = mutableListOf<String>()
        var failRecording = false
        override fun observeFrequentReactions() = flowOf(listOf("+1"))
        override fun observeQuickReplies() = flowOf(emptyList<String>())
        override fun observeCustomEmoji() = flowOf(emptyMap<String, String>())
        override suspend fun syncCustomEmoji() = Result.success(Unit)
        override suspend fun searchEmoji(query: String) = listOf("party_parrot")
        override suspend fun recordQuickReply(text: String) = Unit
        override suspend fun recordReaction(emoji: String) {
            if (failRecording) error("Local usage unavailable")
            recorded += emoji
        }
    }
}
