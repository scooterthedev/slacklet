package com.scooter.slackwear.core.model.repository

import kotlinx.coroutines.flow.Flow

interface PreferenceRepository {

    fun observeFrequentReactions(): Flow<List<String>>

    fun observeQuickReplies(): Flow<List<String>>

    suspend fun recordReaction(emoji: String)

    suspend fun recordQuickReply(text: String)

    suspend fun searchEmoji(query: String): List<String>

    fun observeCustomEmoji(): Flow<Map<String, String>>

    suspend fun syncCustomEmoji(): Result<Unit>
}
