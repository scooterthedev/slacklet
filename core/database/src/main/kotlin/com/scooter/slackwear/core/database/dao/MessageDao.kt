package com.scooter.slackwear.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.scooter.slackwear.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND threadTs IS NULL
        ORDER BY ts ASC
        """,
    )
    fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND (ts = :threadTs OR threadTs = :threadTs)
        ORDER BY ts ASC
        """,
    )
    fun observeThread(conversationId: String, threadTs: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND ts = :ts")
    suspend fun find(conversationId: String, ts: String): MessageEntity?

    @Query(
        """
        UPDATE messages SET reactionsJson = :updated
        WHERE conversationId = :conversationId AND ts = :ts AND reactionsJson = :previous
        """,
    )
    suspend fun updateReactions(conversationId: String, ts: String, previous: String, updated: String): Int

    @Upsert
    suspend fun upsert(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND ts = :ts")
    suspend fun delete(conversationId: String, ts: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
