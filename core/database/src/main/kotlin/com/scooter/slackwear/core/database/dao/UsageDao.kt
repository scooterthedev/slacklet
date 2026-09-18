package com.scooter.slackwear.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.scooter.slackwear.core.database.entity.UsageEntity
import com.scooter.slackwear.core.database.entity.UsageKind
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageDao {

    @Query("SELECT * FROM usage_stats WHERE kind = :kind")
    fun observe(kind: UsageKind): Flow<List<UsageEntity>>

    @Query("SELECT * FROM usage_stats WHERE id = :id")
    suspend fun find(id: String): UsageEntity?

    @Upsert
    suspend fun upsert(entity: UsageEntity)

    @Query("DELETE FROM usage_stats WHERE kind = :kind")
    suspend fun clear(kind: UsageKind)
}
