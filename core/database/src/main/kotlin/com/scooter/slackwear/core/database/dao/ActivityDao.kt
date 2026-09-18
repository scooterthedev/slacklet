package com.scooter.slackwear.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.scooter.slackwear.core.database.entity.ActivityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {

    @Query("SELECT * FROM activity ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int = -1): Flow<List<ActivityEntity>>

    @Upsert
    suspend fun upsert(items: List<ActivityEntity>)

    @Query("DELETE FROM activity WHERE id NOT IN (SELECT id FROM activity ORDER BY ts DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("UPDATE activity SET isRead = 1 WHERE conversationId = :conversationId")
    suspend fun markConversationRead(conversationId: String)

    @Query("UPDATE activity SET isRead = 1 WHERE entryKey = :key AND ts = :feedTs")
    suspend fun markEntryRead(key: String, feedTs: String)

    @Query("UPDATE activity SET isRead = 0 WHERE entryKey = :key AND ts = :feedTs")
    suspend fun markEntryUnread(key: String, feedTs: String)
}
