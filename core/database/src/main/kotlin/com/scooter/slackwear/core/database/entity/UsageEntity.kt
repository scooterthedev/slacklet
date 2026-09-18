package com.scooter.slackwear.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage_stats")
data class UsageEntity(

    @PrimaryKey val id: String,
    val kind: UsageKind,
    val value: String,
    val useCount: Int,
    val lastUsedMillis: Long,
)

enum class UsageKind {
    REACTION,
    QUICK_REPLY,
}
