package com.scooter.slackwear.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.scooter.slackwear.core.database.entity.CustomEmojiEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomEmojiDao {

    @Query("SELECT * FROM custom_emoji")
    fun observeAll(): Flow<List<CustomEmojiEntity>>

    @Query("SELECT name FROM custom_emoji WHERE name LIKE :pattern ORDER BY LENGTH(name) LIMIT 40")
    suspend fun namesMatching(pattern: String): List<String>

    @Query("SELECT COUNT(*) FROM custom_emoji")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(emoji: List<CustomEmojiEntity>)
}
