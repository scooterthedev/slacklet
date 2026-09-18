package com.scooter.slackwear.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.scooter.slackwear.core.database.entity.ConversationEntity
import com.scooter.slackwear.core.database.entity.mergeRoster
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY latestTs DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT * FROM conversations
        WHERE isArchived = 0
          AND (
              unreadCount > 0
              OR mentionCount > 0
              OR isOpen = 1
              OR latestTs IS NOT NULL
          )
        ORDER BY latestTs DESC
        """,
    )
    fun observeForDisplay(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<ConversationEntity>

    @Query("SELECT id, name FROM conversations")
    suspend fun namesById(): List<ConversationName>

    @Query("SELECT name FROM conversations WHERE id = :id")
    fun nameOfBlocking(id: String): String?

    @Upsert
    suspend fun upsert(conversations: List<ConversationEntity>)


    @Transaction
    suspend fun upsertRoster(incoming: List<ConversationEntity>, openStates: Map<String, Boolean>) {
        val existing = incoming.map { it.id }.distinct().chunked(900)
            .flatMap { findByIds(it) }.associateBy { it.id }
        upsert(incoming.map { row ->
            existing[row.id]?.mergeRoster(row, openStates[row.id])
                ?: row.copy(isOpen = openStates[row.id] ?: row.isOpen)
        })
        openStates.forEach { (id, open) -> updateIsOpen(id, open) }
    }

    @Query(
        """
        UPDATE conversations
        SET unreadCount = :count,
            mentionCount = :mentions,
            unreadConfidence = :confidence,
            refreshedAtMillis = :refreshedAtMillis
        WHERE id = :id
        """,
    )
    suspend fun updateUnread(
        id: String,
        count: Int,
        mentions: Int,
        confidence: com.scooter.slackwear.core.model.UnreadState.Confidence,
        refreshedAtMillis: Long,
    )

    @Query(
        """
        UPDATE conversations
        SET latestPreview = CASE WHEN latestTs = :ts THEN latestPreview ELSE NULL END,
            latestTs = :ts
        WHERE id = :id AND (latestTs IS NULL OR latestTs <= :ts)
        """,
    )
    suspend fun updateLatestTimestamp(id: String, ts: String)

    @Query(
        """
        UPDATE conversations
        SET latestPreview = :preview,
            unreadCount = CASE WHEN latestTs IS NULL OR latestTs < :ts THEN MIN(unreadCount, 1) ELSE unreadCount END,
            unreadConfidence = CASE WHEN latestTs IS NULL OR latestTs < :ts THEN 'DERIVED' ELSE unreadConfidence END,
            latestTs = CASE WHEN latestTs IS NULL OR latestTs < :ts THEN :ts ELSE latestTs END
        WHERE id = :id AND kind IN ('DIRECT_MESSAGE', 'GROUP_MESSAGE')
        """,
    )
    suspend fun updateDirectPreview(id: String, preview: String?, ts: String)

    @Transaction
    suspend fun updateDirectPreviews(updates: List<ConversationPreviewPatch>) {
        updates.forEach { updateDirectPreview(it.id, it.preview, it.ts) }
    }

    @Query("UPDATE conversations SET isOpen = :isOpen WHERE id = :id")
    suspend fun updateIsOpen(id: String, isOpen: Boolean)

    @Query("UPDATE conversations SET lastSeenTs = :ts WHERE id = :id")
    suspend fun updateLastSeen(id: String, ts: String?)

    @Transaction
    suspend fun updateUnreadBatch(updates: List<UnreadPatch>) {
        updates.forEach { patch ->
            updateUnread(patch.id, patch.count, patch.mentions, patch.confidence, patch.refreshedAtMillis)
            patch.lastSeen?.let { updateLastSeen(patch.id, it) }
            patch.latestTs?.let { updateLatestTimestamp(patch.id, it) }
        }
    }

    @Query(ACKNOWLEDGE_READ_SQL)
    suspend fun acknowledgeRead(id: String, ts: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(conversations: List<ConversationEntity>)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

internal const val ACKNOWLEDGE_READ_SQL = """
    UPDATE conversations
    SET unreadCount = CASE WHEN latestTs IS NOT NULL
            AND CAST(REPLACE(latestTs, '.', '') AS INTEGER) <= CAST(REPLACE(:ts, '.', '') AS INTEGER)
            THEN 0 ELSE unreadCount END,
        mentionCount = CASE WHEN latestTs IS NOT NULL
            AND CAST(REPLACE(latestTs, '.', '') AS INTEGER) <= CAST(REPLACE(:ts, '.', '') AS INTEGER)
            THEN 0 ELSE mentionCount END,
        unreadConfidence = 'DERIVED',
        lastSeenTs = :ts
    WHERE id = :id AND (lastSeenTs IS NULL
        OR CAST(REPLACE(lastSeenTs, '.', '') AS INTEGER) < CAST(REPLACE(:ts, '.', '') AS INTEGER))
"""

data class ConversationName(val id: String, val name: String)

data class UnreadPatch(
    val id: String,
    val count: Int,
    val mentions: Int,
    val confidence: com.scooter.slackwear.core.model.UnreadState.Confidence,
    val lastSeen: String?,
    val refreshedAtMillis: Long,
    val latestTs: String? = null,
)

data class ConversationPreviewPatch(val id: String, val preview: String?, val ts: String)
