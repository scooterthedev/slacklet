package com.scooter.slackwear.feature.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scooter.slackwear.core.model.DeliveryState
import com.scooter.slackwear.core.model.SafeFailure
import com.scooter.slackwear.core.model.toSafeFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.scooter.slackwear.core.model.Message
import com.scooter.slackwear.core.model.SlackUser
import com.scooter.slackwear.core.model.repository.ConversationRepository
import com.scooter.slackwear.core.model.repository.MessageRepository
import com.scooter.slackwear.core.model.repository.PreferenceRepository
import com.scooter.slackwear.core.model.repository.ThreadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ThreadViewModel(
    private val conversationId: String,
    private val threadTs: String,
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
    private val preferences: PreferenceRepository,
    private val threads: ThreadRepository? = null,
    private val failureMapper: (Throwable) -> SafeFailure = { it.toSafeFailure() },
    initialHighlightTs: String? = null,
) : ViewModel() {

    private val highlighted = MutableStateFlow(initialHighlightTs)

    val highlight: StateFlow<String?> = highlighted.asStateFlow()

    fun highlight(ts: String) {
        highlighted.value = ts
    }

    private val loading = MutableStateFlow(true)
    private val error = MutableStateFlow<ThreadFailure?>(null)
    private var operation: Job? = null
    private var pendingReadTs: String? = null
    private var pageJob: Job? = null
    val pagination = messages.threadPagination(conversationId, threadTs)

    fun loadMoreReplies() {
        if (operation?.isActive == true || pageJob?.isActive == true) return
        if (!pagination.value.initialized) {
            load()
            return
        }
        pageJob = viewModelScope.launch { messages.loadMoreReplies(conversationId, threadTs) }
    }

    private val livePolling: Flow<List<Message>> = flow {
        while (true) {
            delay(LIVE_REFRESH_INTERVAL_MILLIS)
            if (operation?.isActive == true || pageJob?.isActive == true) continue
            runCatching { messages.loadThread(conversationId, threadTs) }
        }
    }

    val uiState: StateFlow<ThreadUiState> =
        combine(
            merge(messages.observeThread(conversationId, threadTs), livePolling),
            messages.observeAuthors(),
            loading,
            error,
        ) { thread, authors, isLoading, loadError ->
            ThreadUiState(
                parent = thread.firstOrNull { it.ts == threadTs },
                replies = thread.filter { it.ts != threadTs },
                authors = authors,
                isLoading = isLoading,
                error = loadError,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThreadUiState(isLoading = true),
        )

    private val quotedMessages = QuotedMessageResolver(messages, conversations, viewModelScope)

    val quoted: StateFlow<Map<String, QuotedMessage>> = quotedMessages.quoted

    val quickReplies: StateFlow<List<String>> = preferences.observeQuickReplies()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val customEmoji: StateFlow<Map<String, String>> = preferences.observeCustomEmoji()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        load()
        viewModelScope.launch {
            messages.observeThread(conversationId, threadTs).collect { list ->
                quotedMessages.resolveIn(list.map { it.text })
            }
        }
    }

    fun retry() {
        if (operation?.isActive == true || error.value == null) return
        if (pendingReadTs != null) markRead() else load()
    }

    private fun load() {
        loading.value = true
        error.value = null
        operation = viewModelScope.launch {
            try {
                messages.loadThread(conversationId, threadTs).getOrThrow()
                currentCoroutineContext().ensureActive()
                if (threads == null) return@launch
                val loaded = messages.observeThread(conversationId, threadTs).first()
                val initialTimestamps = pagination.value.initialPageTimestamps ?: return@launch
                val root = loaded.firstOrNull {
                    it.ts == threadTs && it.conversationId == conversationId &&
                        it.ts in initialTimestamps
                } ?: return@launch
                val lastRead = root.lastReadTs?.toBigDecimalOrNull() ?: return@launch
                val latestReply = root.latestReplyTs?.toBigDecimalOrNull() ?: return@launch
                if (root.isSubscribed != true || (root.threadReplyCount ?: 0) <= 0 || latestReply <= lastRead) return@launch
                pendingReadTs = loaded.filter {
                    it.conversationId == conversationId && it.deliveryState == DeliveryState.SENT &&
                        it.ts != threadTs && it.threadTs == threadTs &&
                        it.ts in initialTimestamps
                }.mapNotNull { message -> message.ts.toBigDecimalOrNull()?.let { it to message.ts } }
                    .filter { it.first > lastRead && it.first <= latestReply }
                    .maxByOrNull { it.first }?.second
                acknowledgeRead()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error.value = ThreadFailure(ThreadFailure.Operation.LOAD, failureMapper(failure))
            } finally {
                loading.value = false
            }
        }
    }

    private fun markRead() {
        loading.value = true
        error.value = null
        operation = viewModelScope.launch {
            try {
                acknowledgeRead()
            } finally {
                loading.value = false
            }
        }
    }

    private suspend fun acknowledgeRead() {
        val timestamp = pendingReadTs ?: return
        val repository = threads ?: return
        try {
            currentCoroutineContext().ensureActive()
            repository.markRead(conversationId, threadTs, timestamp).getOrThrow()
            currentCoroutineContext().ensureActive()
            pendingReadTs = null
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error.value = ThreadFailure(ThreadFailure.Operation.MARK_READ, failureMapper(failure))
        }
    }

    fun reply(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            messages.sendMessage(conversationId, trimmed, threadTs = threadTs)
            if (trimmed.length <= 40) preferences.recordQuickReply(trimmed)
        }
    }
}

private const val LIVE_REFRESH_INTERVAL_MILLIS = 6_000L

data class ThreadUiState(
    val parent: Message? = null,
    val replies: List<Message> = emptyList(),
    val authors: Map<String, SlackUser> = emptyMap(),
    val isLoading: Boolean = false,
    val error: ThreadFailure? = null,
)

data class ThreadFailure(val operation: Operation, val failure: SafeFailure) {
    enum class Operation { LOAD, MARK_READ }

    val message: String
        get() = when (operation) {
            Operation.LOAD -> "Couldn't load thread - ${failure.reason}"
            Operation.MARK_READ -> "Couldn't mark thread read - ${failure.reason}"
        }
}
