package com.scooter.slackwear.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.scooter.slackwear.core.database.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Query("SELECT * FROM users")
    fun observeAll(): Flow<List<UserEntity>>

    @Query(
        """
        SELECT * FROM users
        WHERE id IN (
            SELECT counterpartUserId FROM conversations
            WHERE counterpartUserId IS NOT NULL AND isArchived = 0
        )
        """,
    )
    fun observeConversationCounterparts(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users")
    suspend fun allNames(): List<UserEntity>

    @Query("SELECT displayName FROM users WHERE id = :id")
    fun displayNameOfBlocking(id: String): String?

    @Query("SELECT * FROM users WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<UserEntity>

    @Query("SELECT id FROM users WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    @Upsert
    suspend fun upsert(users: List<UserEntity>)

    @Query("DELETE FROM users")
    suspend fun deleteAll()
}
