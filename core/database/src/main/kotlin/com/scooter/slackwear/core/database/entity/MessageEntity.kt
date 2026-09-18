package com.scooter.slackwear.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import com.scooter.slackwear.core.model.DeliveryState

@Entity(
    tableName = "messages",
    primaryKeys = ["conversationId", "ts"],
    indices = [Index(value = ["conversationId", "ts"]), Index(value = ["threadTs"])],
)
data class MessageEntity(
    val conversationId: String,

    val ts: String,
    val authorId: String,
    val text: String,
    val threadTs: String?,
    val replyCount: Int,

    val reactionsJson: String,
    val isEdited: Boolean,
    val deliveryState: DeliveryState,
)
