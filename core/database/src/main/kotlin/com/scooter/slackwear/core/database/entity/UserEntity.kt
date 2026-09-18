package com.scooter.slackwear.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val realName: String,
    val avatarUrl: String?,
    val isBot: Boolean,
)
