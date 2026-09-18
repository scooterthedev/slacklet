package com.scooter.slackwear.core.model.repository

import com.scooter.slackwear.core.model.ChannelSectionConfig
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {

    fun observeNotificationSettings(): Flow<NotificationSettings>

    suspend fun setNotificationSettings(settings: NotificationSettings)

    fun observeDnd(): Flow<DndState>

    suspend fun refreshDnd()

    suspend fun snooze(minutes: Int): Result<Unit>

    suspend fun endSnooze(): Result<Unit>

    fun observeSections(): Flow<ChannelSectionConfig>

    suspend fun importSections(json: String): Result<Unit>

    suspend fun clearLearnedReactions()

    suspend fun signOut()
}

data class NotificationSettings(
    val pushToWatch: Boolean = true,
    val mentions: Boolean = true,
    val directMessages: Boolean = true,
    val threadReplies: Boolean = true,
    val allActivity: Boolean = false,
    val followSlackDnd: Boolean = true,
)

data class DndState(
    val snoozeEnabled: Boolean = false,
    val snoozeEndsAtEpochSeconds: Long? = null,
)
