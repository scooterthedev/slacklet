package com.scooter.slackwear.core.model.repository

interface EmojiRepository {

    suspend fun mostUsed(): Result<List<EmojiCount>>
}

data class EmojiCount(
    val symbol: String,
    val count: Long?,
)
