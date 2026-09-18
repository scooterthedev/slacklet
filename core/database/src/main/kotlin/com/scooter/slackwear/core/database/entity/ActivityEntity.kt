package com.scooter.slackwear.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activity")
data class ActivityEntity(

    @PrimaryKey val id: String,
    val conversationId: String,
    val conversationName: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val ts: String,
    val isRead: Boolean,
    @ColumnInfo(defaultValue = "''") val entryType: String = "",
    val messageTs: String? = null,
    val threadTs: String? = null,
    val entryKey: String? = null,
    @ColumnInfo(defaultValue = "1") val unreadCount: Int = 1,
)
