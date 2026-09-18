package com.scooter.slackwear.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_emoji")
data class CustomEmojiEntity(
    @PrimaryKey val name: String,
    val imageUrl: String,
)
