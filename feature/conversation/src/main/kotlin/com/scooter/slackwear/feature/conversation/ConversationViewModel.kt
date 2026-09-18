package com.scooter.slackwear.feature.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scooter.slackwear.core.model.DeliveryState
import com.scooter.slackwear.core.model.Message
import com.scooter.slackwear.core.model.SlackUser
import com.scooter.slackwear.core.model.repository.ConversationRepository
import com.scooter.slackwear.core.model.repository.EmojiRepository
import com.scooter.slackwear.core.model.repository.HuddleRepository
import com.scooter.slackwear.core.model.repository.HuddleState
import com.scooter.slackwear.core.model.repository.MessageRepository
import com.scooter.slackwear.core.model.repository.PreferenceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SLACK_TIMESTAMP = Regex("[0-9]{1,10}[.][0-9]{6}")

class ConversationViewModel(
    private val conversationId: String,
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
    private val preferences: PreferenceRepository,
    private val emojiRepository: EmojiRepository,
    private val huddles: HuddleRepository,
    private val loadOnInit: Boolean = true,
    initialHighlightTs: String? = null,
) : ViewModel() {

    val quickReplies: StateFlow<List<String>> = preferences.observeQuickReplies()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val serverFrequent = MutableStateFlow<List<String>>(emptyList())

    val frequentReactions: StateFlow<List<String>> =
        combine(preferences.observeFrequentReactions(), serverFrequent) { local, server ->
            (server + local).distinct().take(FREQUENT_ROW_SIZE)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val customEmoji: StateFlow<Map<String, String>> = preferences.observeCustomEmoji()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _upload = MutableStateFlow<UploadState>(UploadState.Idle)
    val upload: StateFlow<UploadState> = _upload.asStateFlow()

    private val _reaction = MutableStateFlow<ReactionState>(ReactionState.Idle)
    val reaction: StateFlow<ReactionState> = _reaction.asStateFlow()

    private val _emojiSearch = MutableStateFlow(EmojiSearch())
    val emojiSearch: StateFlow<EmojiSearch> = _emojiSearch.asStateFlow()

    val huddle: StateFlow<HuddleState?> = huddles.observeHuddle(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _knocked = MutableStateFlow(false)
    val knocked: StateFlow<Boolean> = _knocked.asStateFlow()

    private val loading = MutableStateFlow(true)
    private val readError = MutableStateFlow<String?>(null)
    private var refreshJob: Job? = null
    private var pendingReadTs: String? = null
    private var pageJob: Job? = null
    val pagination = messages.historyPagination(conversationId)

    fun loadOlderHistory() {
        if (refreshJob?.isActive == true || pageJob?.isActive == true) return
        if (!pagination.value.initialized) {
            refresh()
            return
        }
        pageJob = viewModelScope.launch { messages.loadOlderHistory(conversationId) }
    }

    private val title: Flow<String> =
        conversations.observeConversations()
            .map { summaries ->
                summaries.firstOrNull { it.conversation.id == conversationId }?.title.orEmpty()
            }
            .distinctUntilChanged()

    private val livePolling: Flow<List<Message>> = flow {
        while (true) {
            delay(LIVE_REFRESH_INTERVAL_MILLIS)

            if (refreshJob?.isActive == true || pageJob?.isActive == true) continue
            runCatching { messages.loadHistory(conversationId) }
        }
    }

    val uiState: StateFlow<ConversationUiState> =
        combine(
            merge(messages.observeMessages(conversationId), livePolling),
            messages.observeAuthors(),
            title,
            loading,
            readError,
        ) { messageList, authors, conversationTitle, isLoading, error ->
            ConversationUiState(
                title = conversationTitle,
                messages = messageList,
                authors = authors,
                isLoading = isLoading,
                readError = error,
                retryRead = ::retryRead,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ConversationUiState(isLoading = true),
        )

    private val quotedMessages = QuotedMessageResolver(messages, conversations, viewModelScope)

    private val highlighted = MutableStateFlow(initialHighlightTs)

    val highlight: StateFlow<String?> = highlighted.asStateFlow()

    fun highlight(ts: String) {
        highlighted.value = ts
    }

    val quoted: StateFlow<Map<String, QuotedMessage>> = quotedMessages.quoted

    init {
        if (loadOnInit) refresh() else loading.value = false

        viewModelScope.launch {
            messages.observeMessages(conversationId).collect { list ->
                quotedMessages.resolveIn(list.map { it.text })
            }
        }
        viewModelScope.launch { preferences.syncCustomEmoji() }

        viewModelScope.launch { huddles.refresh(conversationId) }

        viewModelScope.launch {
            emojiRepository.mostUsed().onSuccess { list ->
                serverFrequent.value = list.map { it.symbol }
            }
        }
    }

    fun refresh() {
        startReadSync(reload = true)
    }

    fun retryRead() {
        startReadSync(reload = pendingReadTs == null)
    }

    private fun startReadSync(reload: Boolean) {
        if (refreshJob?.isActive == true) return
        loading.value = true
        readError.value = null
        refreshJob = viewModelScope.launch {
            var historyLoaded = !reload
            try {
                if (reload) {
                    pendingReadTs = null
                    messages.loadHistory(conversationId).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    historyLoaded = true
                    val initialTimestamps = pagination.value.initialPageTimestamps
                    pendingReadTs = messages.observeMessages(conversationId).first()
                        .filter { initialTimestamps != null && it.ts in initialTimestamps }
                        .filter {
                            it.conversationId == conversationId && it.deliveryState == DeliveryState.SENT &&
                                !it.isThreadReply && SLACK_TIMESTAMP.matches(it.ts)
                        }
                        .filter { it.ts.toBigDecimal().signum() > 0 }
                        .maxByOrNull { it.ts.toBigDecimal() }?.ts
                }
                pendingReadTs?.let { ts ->
                    currentCoroutineContext().ensureActive()
                    conversations.markRead(conversationId, ts).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    pendingReadTs = null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                readError.value = if (historyLoaded) "Couldn't sync read status. Retry."
                    else "Couldn't load messages. Retry."
            } finally {
                loading.value = false
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            messages.sendMessage(conversationId, trimmed)

            if (trimmed.length <= MAX_QUICK_REPLY_LENGTH) preferences.recordQuickReply(trimmed)
        }
    }

    fun sendFile(filename: String, bytes: ByteArray) {
        viewModelScope.launch {
            _upload.value = UploadState.Uploading(filename)
            _upload.value = messages.sendFile(conversationId, filename, bytes).fold(
                onSuccess = { UploadState.Idle },
                onFailure = { UploadState.Failed(it.uploadMessage()) },
            )
        }
    }

    fun searchEmoji(query: String) {
        viewModelScope.launch {
            _emojiSearch.value = EmojiSearch(query = query, results = preferences.searchEmoji(query))
        }
    }

    fun react(messageTs: String, emoji: String) {
        if (_reaction.value is ReactionState.Pending) return
        _reaction.value = ReactionState.Pending
        viewModelScope.launch {
            try {
                val message = messages.findMessage(conversationId, messageTs)
                if (message == null) {
                    _reaction.value = ReactionState.Failed("Message unavailable. Reopen it and try again.")
                    return@launch
                }
                val alreadyReacted = message.reactions.any { it.name == emoji && it.includesMe }
                messages.toggleReaction(conversationId, messageTs, emoji, add = !alreadyReacted)
                    .getOrThrow()
                if (!alreadyReacted) {
                    try {
                        preferences.recordReaction(emoji)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        Unit
                    }
                }
                _reaction.value = ReactionState.Succeeded
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _reaction.value = ReactionState.Failed("Couldn't update reaction. Tap an emoji to retry.")
            }
        }
    }

    fun knock() {
        viewModelScope.launch {
            _knocked.value = true
            huddles.knock(conversationId).onFailure { _knocked.value = false }
        }
    }

    fun cancelKnock() {
        viewModelScope.launch {
            huddles.cancelKnock(conversationId)
            _knocked.value = false
        }
    }

}

sealed interface ReactionState {
    data object Idle : ReactionState
    data object Pending : ReactionState
    data object Succeeded : ReactionState
    data class Failed(val message: String) : ReactionState
}

sealed interface UploadState {
    data object Idle : UploadState
    data class Uploading(val filename: String) : UploadState
    data class Failed(val message: String) : UploadState
}

private fun Throwable.uploadMessage(): String = when {
    message?.contains("missing_scope") == true -> "This app can't upload files yet"
    message?.contains("file_too_large") == true -> "That file is too big for Slack"
    message?.contains("ratelimited") == true -> "Too many uploads - try again shortly"
    else -> "Couldn't send that file"
}

data class EmojiSearch(
    val query: String = "",
    val results: List<String> = emptyList(),
)

private const val MAX_QUICK_REPLY_LENGTH = 40

private const val LIVE_REFRESH_INTERVAL_MILLIS = 6_000L

private const val FREQUENT_ROW_SIZE = 12

data class ConversationUiState(
    val title: String = "",
    val messages: List<Message> = emptyList(),
    val authors: Map<String, SlackUser> = emptyMap(),
    val isLoading: Boolean = false,
    val readError: String? = null,
    val retryRead: () -> Unit = {},
)
