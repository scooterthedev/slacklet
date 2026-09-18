package com.scooter.slackwear.core.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.scooter.slackwear.core.database.dao.UsageDao
import com.scooter.slackwear.core.database.entity.UsageKind
import com.scooter.slackwear.core.model.ChannelSectionConfig
import com.scooter.slackwear.core.model.repository.DndState
import com.scooter.slackwear.core.model.repository.NotificationSettings
import com.scooter.slackwear.core.model.repository.SettingsRepository
import com.scooter.slackwear.core.network.SlackApi
import com.scooter.slackwear.core.network.unwrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class LocalSettingsRepository(
    private val context: Context,
    private val api: SlackApi,
    private val usageDao: UsageDao,
    private val onSignOut: suspend () -> Unit,
) : SettingsRepository {

    private val _dnd = MutableStateFlow(DndState())
    private val json = Json { ignoreUnknownKeys = true }

    override fun observeNotificationSettings(): Flow<NotificationSettings> =
        context.settingsDataStore.data

            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map(::toSettings)

    override suspend fun setNotificationSettings(settings: NotificationSettings) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.PUSH_TO_WATCH] = settings.pushToWatch
            preferences[Keys.MENTIONS] = settings.mentions
            preferences[Keys.DIRECT_MESSAGES] = settings.directMessages
            preferences[Keys.THREAD_REPLIES] = settings.threadReplies
            preferences[Keys.ALL_ACTIVITY] = settings.allActivity
            preferences[Keys.FOLLOW_SLACK_DND] = settings.followSlackDnd
        }
    }

    override fun observeSections(): Flow<ChannelSectionConfig> =
        context.settingsDataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { preferences ->
                preferences[Keys.SECTIONS]
                    ?.let { runCatching { json.decodeFromString<ChannelSectionConfig>(it) }.getOrNull() }
                    ?: ChannelSectionConfig()
            }

    override suspend fun importSections(json: String): Result<Unit> = runCatching {

        val config = this.json.decodeFromString<ChannelSectionConfig>(json)
        context.settingsDataStore.edit { it[Keys.SECTIONS] = this.json.encodeToString(config) }
    }

    override fun observeDnd(): Flow<DndState> = _dnd.asStateFlow()

    override suspend fun refreshDnd() = withContext(Dispatchers.IO) {
        runCatching {
            val info = api.dndInfo().unwrap()
            _dnd.value = DndState(
                snoozeEnabled = info.snoozeEnabled,
                snoozeEndsAtEpochSeconds = info.snoozeEndTime,
            )
        }
        Unit
    }

    override suspend fun snooze(minutes: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.setSnooze(minutes).unwrap()
            refreshDnd()
        }
    }

    override suspend fun endSnooze(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.endSnooze().unwrap()
            refreshDnd()
        }
    }

    override suspend fun clearLearnedReactions() = usageDao.clear(UsageKind.REACTION)

    override suspend fun signOut() = onSignOut()

    private fun toSettings(preferences: Preferences) = NotificationSettings(
        pushToWatch = preferences[Keys.PUSH_TO_WATCH] ?: true,
        mentions = preferences[Keys.MENTIONS] ?: true,
        directMessages = preferences[Keys.DIRECT_MESSAGES] ?: true,
        threadReplies = preferences[Keys.THREAD_REPLIES] ?: true,

        allActivity = preferences[Keys.ALL_ACTIVITY] ?: false,
        followSlackDnd = preferences[Keys.FOLLOW_SLACK_DND] ?: true,
    )

    private object Keys {
        val PUSH_TO_WATCH = booleanPreferencesKey("push_to_watch")
        val MENTIONS = booleanPreferencesKey("mentions")
        val DIRECT_MESSAGES = booleanPreferencesKey("direct_messages")
        val THREAD_REPLIES = booleanPreferencesKey("thread_replies")
        val ALL_ACTIVITY = booleanPreferencesKey("all_activity")
        val FOLLOW_SLACK_DND = booleanPreferencesKey("follow_slack_dnd")
        val SECTIONS = stringPreferencesKey("channel_sections")
    }
}
