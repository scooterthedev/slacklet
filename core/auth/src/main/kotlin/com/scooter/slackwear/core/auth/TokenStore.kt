package com.scooter.slackwear.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TokenStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<SlackSession?> = _session.asStateFlow()

    fun currentToken(): String? = _session.value?.accessToken

    fun currentSecondaryToken(): String? = _session.value?.secondaryToken

    fun save(session: SlackSession) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_TEAM_ID, session.teamId)
            .putString(KEY_TEAM_NAME, session.teamName)
            .putString(KEY_TOKEN_TYPE, session.tokenType.name)
            .putString(KEY_SECONDARY_TOKEN, session.secondaryToken)
            .putString(KEY_TEAM_DOMAIN, session.teamDomain)
            .apply()
        _session.value = session
    }

    fun clear() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    private fun readSession(): SlackSession? {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        return SlackSession(
            accessToken = token,
            userId = prefs.getString(KEY_USER_ID, null).orEmpty(),
            teamId = prefs.getString(KEY_TEAM_ID, null).orEmpty(),
            teamName = prefs.getString(KEY_TEAM_NAME, null).orEmpty(),
            tokenType = prefs.getString(KEY_TOKEN_TYPE, null)
                ?.let { runCatching { SlackTokenType.valueOf(it) }.getOrNull() }
                ?: SlackTokenType.USER,
            secondaryToken = prefs.getString(KEY_SECONDARY_TOKEN, null),
            teamDomain = prefs.getString(KEY_TEAM_DOMAIN, null),
        )
    }

    private companion object {
        const val FILE_NAME = "slack_session"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_TEAM_ID = "team_id"
        const val KEY_TEAM_NAME = "team_name"
        const val KEY_TOKEN_TYPE = "token_type"
        const val KEY_SECONDARY_TOKEN = "secondary_token"
        const val KEY_TEAM_DOMAIN = "team_domain"
    }
}

enum class SlackTokenType {

    USER,

    CLIENT,
}

data class SlackSession(
    val accessToken: String,
    val userId: String,
    val teamId: String,
    val teamName: String,
    val tokenType: SlackTokenType = SlackTokenType.USER,

    val secondaryToken: String? = null,

    val teamDomain: String? = null,
)
