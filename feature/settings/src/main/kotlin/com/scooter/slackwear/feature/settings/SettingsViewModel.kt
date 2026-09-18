package com.scooter.slackwear.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scooter.slackwear.core.model.repository.DndState
import com.scooter.slackwear.core.model.repository.NotificationSettings
import com.scooter.slackwear.core.model.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> =
        combine(
            repository.observeNotificationSettings(),
            repository.observeDnd(),
        ) { notifications, dnd ->
            SettingsUiState(notifications = notifications, dnd = dnd)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    init {
        viewModelScope.launch { repository.refreshDnd() }
    }

    fun update(transform: (NotificationSettings) -> NotificationSettings) {
        viewModelScope.launch {
            repository.setNotificationSettings(transform(uiState.value.notifications))
        }
    }

    fun toggleSnooze() {
        viewModelScope.launch {
            if (uiState.value.dnd.snoozeEnabled) {
                repository.endSnooze()
            } else {
                repository.snooze(DEFAULT_SNOOZE_MINUTES)
            }
        }
    }

    fun clearLearnedReactions() {
        viewModelScope.launch { repository.clearLearnedReactions() }
    }

    fun signOut() {
        viewModelScope.launch { repository.signOut() }
    }

    private companion object {

        const val DEFAULT_SNOOZE_MINUTES = 60
    }
}

data class SettingsUiState(
    val notifications: NotificationSettings = NotificationSettings(),
    val dnd: DndState = DndState(),
)
