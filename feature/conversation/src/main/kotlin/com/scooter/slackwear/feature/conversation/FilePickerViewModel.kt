package com.scooter.slackwear.feature.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scooter.slackwear.core.model.repository.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FilePickerViewModel(
    private val conversationId: String,
    private val threadTs: String?,
    private val media: WatchMediaStore,
    private val messages: MessageRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FilePickerUiState(isLoading = true))
    val state: StateFlow<FilePickerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = FilePickerUiState(items = media.list(MAX_UPLOAD_BYTES), isLoading = false)
        }
    }

    fun send(item: WatchMediaItem, onSent: () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = item.name)

            val bytes = media.read(item)
            if (bytes == null) {
                _state.value = _state.value.copy(sending = null, error = "Couldn't read that file")
                return@launch
            }

            messages.sendFile(conversationId, item.name, bytes, threadTs = threadTs)
                .onSuccess {
                    _state.value = _state.value.copy(sending = null)
                    onSent()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        sending = null,
                        error = when {
                            it.message?.contains("missing_scope") == true ->
                                "The app needs the files:write scope"
                            else -> "Couldn't send that file"
                        },
                    )
                }
        }
    }

    private companion object {

        const val MAX_UPLOAD_BYTES = 8L * 1024 * 1024
    }
}

data class FilePickerUiState(
    val items: List<WatchMediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val sending: String? = null,
    val error: String? = null,
)
