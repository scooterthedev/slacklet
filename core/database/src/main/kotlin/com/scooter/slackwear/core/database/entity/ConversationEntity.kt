package com.scooter.slackwear.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.model.UnreadState

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: ConversationKind,
    val topic: String?,
    val isMuted: Boolean,
    val isArchived: Boolean,
    val counterpartUserId: String?,

    val isOpen: Boolean,

    val latestPreview: String?,
    val latestTs: String?,

    val lastSeenTs: String?,

    val unreadCount: Int,
    val mentionCount: Int,
    val unreadConfidence: UnreadState.Confidence,

    val refreshedAtMillis: Long,
)

fun ConversationEntity.mergeRoster(
    incoming: ConversationEntity,
    explicitOpen: Boolean?,
): ConversationEntity {
    val incomingLatest = incoming.latestTs?.takeIf { it.isNotBlank() }
    val latestAdvanced = incomingLatest != null &&
        (latestTs == null || incomingLatest > latestTs)
    val readAdvanced = incoming.lastSeenTs != null &&
        (lastSeenTs == null || incoming.lastSeenTs > lastSeenTs)
    val snapshotIsCurrent = (latestTs == null || incomingLatest != null && incomingLatest >= latestTs) &&
        (lastSeenTs == null || incoming.lastSeenTs != null && incoming.lastSeenTs >= lastSeenTs)
    val hasExactDisplay = incoming.kind in setOf(ConversationKind.DIRECT_MESSAGE, ConversationKind.GROUP_MESSAGE) &&
        incoming.unreadConfidence == UnreadState.Confidence.EXACT && snapshotIsCurrent
    val mergedLatest = if (latestAdvanced) incomingLatest else latestTs
    val mergedLastSeen = if (readAdvanced) incoming.lastSeenTs else lastSeenTs
    val caughtUp = mergedLatest != null && mergedLastSeen != null && mergedLastSeen >= mergedLatest
    return incoming.copy(
        name = incoming.name.ifBlank { name },
        counterpartUserId = incoming.counterpartUserId ?: counterpartUserId,
        isOpen = explicitOpen ?: isOpen,
        latestTs = mergedLatest,
        latestPreview = when {
            latestAdvanced -> incoming.latestPreview
            incomingLatest == latestTs -> incoming.latestPreview ?: latestPreview
            else -> latestPreview
        },
        lastSeenTs = mergedLastSeen,
        unreadCount = when {
            hasExactDisplay -> incoming.unreadCount
            caughtUp -> 0
            latestAdvanced || readAdvanced -> if (unreadCount > 0) 1 else 0
            else -> unreadCount
        },
        mentionCount = if (caughtUp) 0 else mentionCount,
        unreadConfidence = when {
            hasExactDisplay || caughtUp -> UnreadState.Confidence.EXACT
            latestAdvanced || readAdvanced -> UnreadState.Confidence.DERIVED
            else -> unreadConfidence
        },
        refreshedAtMillis = if (hasExactDisplay || caughtUp) incoming.refreshedAtMillis else refreshedAtMillis,
    )
}
