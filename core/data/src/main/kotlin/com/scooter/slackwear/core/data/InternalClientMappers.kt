package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.entity.ActivityEntity
import com.scooter.slackwear.core.database.entity.ConversationEntity
import com.scooter.slackwear.core.model.ChannelSectionConfig
import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.model.SectionDefinition
import com.scooter.slackwear.core.model.SlackUser
import com.scooter.slackwear.core.model.UnreadState
import com.scooter.slackwear.core.model.repository.ActivityItem
import com.scooter.slackwear.core.model.repository.ActivityViewFilter
import com.scooter.slackwear.core.model.repository.ActivityFilters
import com.scooter.slackwear.core.model.repository.EmojiCount
import com.scooter.slackwear.core.network.model.ClientSections
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.lng(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.arr(key: String): List<JsonElement>? = this[key] as? JsonArray
private fun JsonObject.strings(key: String): List<String>? = arr(key)?.mapNotNull {
    (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
}

internal fun JsonObject.decodeClientConversation(now: Long): ConversationEntity? {
    val id = str("id") ?: return null
    val counterpart = str("user") ?: str("target_user_id")
    val kind = when {
        bool("is_im") == true -> ConversationKind.DIRECT_MESSAGE
        bool("is_mpim") == true -> ConversationKind.GROUP_MESSAGE
        bool("is_private") == true || bool("is_group") == true -> ConversationKind.PRIVATE_CHANNEL
        else -> ConversationKind.PUBLIC_CHANNEL
    }
    val unreadDisplay = lng("unread_count_display")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt()
    return ConversationEntity(
        id = id,
        name = str("name") ?: counterpart.orEmpty(),
        kind = kind,
        topic = null,
        isMuted = false,
        isArchived = bool("is_archived") == true,
        counterpartUserId = counterpart,
        isOpen = bool("is_open") ?: false,
        latestPreview = null,
        latestTs = str("latest_ts") ?: obj("latest")?.str("ts") ?: str("latest"),
        lastSeenTs = str("last_read"),
        unreadCount = unreadDisplay ?: 0,
        mentionCount = 0,
        unreadConfidence = if (unreadDisplay != null) UnreadState.Confidence.EXACT else UnreadState.Confidence.DERIVED,
        refreshedAtMillis = now,
    )
}

internal fun decodeSections(sections: ClientSections?, roster: List<ConversationEntity>): ChannelSectionConfig {
    val idToName = roster.associate { it.id to it.name }
    val definitions = sections?.channelSections.orEmpty().mapNotNull { section ->
        val id = section.str("channel_section_id")
        val title = section.str("name") ?: id ?: return@mapNotNull null
        val ids = section.strings("channel_ids").orEmpty()
        SectionDefinition(
            title = title,
            channels = ids.map { idToName[it] ?: it }.map { it.removePrefix("#") }.filter(String::isNotBlank),
            id = id,
            channelIds = ids,
            isHidden = section.bool("is_hidden") == true,
            isRedacted = section.bool("is_redacted") == true,
        )
    }
    return ChannelSectionConfig(sections = definitions, isExplicit = sections != null)
}

private fun activityKind(type: String): ActivityItem.Kind = when (type) {
    "at_user" -> ActivityItem.Kind.MENTION
    "at_user_group" -> ActivityItem.Kind.USER_GROUP_MENTION
    "at_channel" -> ActivityItem.Kind.CHANNEL_MENTION
    "at_everyone" -> ActivityItem.Kind.EVERYONE_MENTION
    "dm" -> ActivityItem.Kind.DIRECT_MESSAGE
    "bot_dm_bundle" -> ActivityItem.Kind.BOT_DIRECT_MESSAGE
    "thread_v2" -> ActivityItem.Kind.THREAD_REPLY
    "message_reaction" -> ActivityItem.Kind.REACTION
    "channel" -> ActivityItem.Kind.CHANNEL
    "keyword" -> ActivityItem.Kind.KEYWORD
    else -> ActivityItem.Kind.UNKNOWN
}

private fun activityLabel(type: String): String = when (activityKind(type)) {
    ActivityItem.Kind.MENTION -> "Mentioned you"
    ActivityItem.Kind.USER_GROUP_MENTION -> "Mentioned your group"
    ActivityItem.Kind.CHANNEL_MENTION -> "Mentioned a channel"
    ActivityItem.Kind.EVERYONE_MENTION -> "Mentioned everyone"
    ActivityItem.Kind.DIRECT_MESSAGE, ActivityItem.Kind.BOT_DIRECT_MESSAGE -> "Sent you a message"
    ActivityItem.Kind.THREAD_REPLY -> "Replied in a thread"
    ActivityItem.Kind.REACTION -> "Reacted to your message"
    ActivityItem.Kind.KEYWORD -> "Matches a keyword you follow"
    ActivityItem.Kind.CHANNEL, ActivityItem.Kind.UNKNOWN -> "New activity"
}

private fun JsonObject.activityConversationId(): String? =
    str("channel") ?: str("channel_id") ?: obj("channel")?.str("id")
        ?: obj("channel")?.obj("latest_message")?.str("channel")

internal fun JsonObject.decodeActivityEntry(conversationNames: Map<String, String>): ActivityEntity? {
    val item = obj("item") ?: return null
    val type = item.str("type") ?: return null
    val feedTs = str("feed_ts") ?: return null
    val bundle = item.obj("bundle_info")
    val payload = bundle?.obj("payload")
    val thread = payload?.obj("thread_entry")
    val channel = payload?.obj("channel_entry")
    val message = item.obj("message") ?: payload?.obj("message")
        ?: payload?.obj("dm_entry")?.obj("latest_message") ?: channel?.obj("latest_message")
    val conversationId = thread?.str("channel_id") ?: message?.activityConversationId()
        ?: item.activityConversationId().orEmpty()
    val messageTs = message?.str("ts") ?: thread?.str("latest_ts")
    val threadTs = thread?.str("thread_ts") ?: message?.str("thread_ts")
    val key = str("key")
    val label = activityLabel(type)
    val count = (thread?.lng("unread_msg_count") ?: channel?.lng("unread_msg_count")
        ?: bundle?.lng("unread_count") ?: 1).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    return ActivityEntity(
        id = key ?: listOf(type, conversationId, threadTs.orEmpty(), feedTs).joinToString(":"),
        conversationId = conversationId,
        conversationName = conversationNames[conversationId]?.takeIf(String::isNotBlank)
            ?: conversationId.ifBlank { "Slack" },
        authorId = item.obj("reaction")?.str("user") ?: message?.str("user").orEmpty(),

        authorName = "",
        text = label,
        ts = feedTs,
        isRead = bool("is_unread") != true,
        entryType = type,
        messageTs = messageTs,
        threadTs = threadTs,
        entryKey = key,
        unreadCount = count,
    )
}

internal fun ActivityEntity.toActivityItem(
    users: Map<String, SlackUser> = emptyMap(),
    counterparts: Map<String, String> = emptyMap(),
): ActivityItem = ActivityItem(
    id = id,
    kind = activityKind(entryType),
    conversationId = conversationId,
    conversationName = conversationName,
    author = resolveAuthor(users, counterparts),
    preview = text,
    timestamp = ts,
    unreadCount = if (isRead) 0 else unreadCount,
    messageTs = messageTs,
    threadTs = threadTs,
    entryType = entryType,
    entryKey = entryKey,
)

private fun ActivityEntity.resolveAuthor(
    users: Map<String, SlackUser>,
    counterparts: Map<String, String>,
): SlackUser {
    val id = authorId.takeIf(String::isNotBlank) ?: counterparts[conversationId].orEmpty()
    users[id]?.let { return it }
    return SlackUser(
        id = id.ifBlank { conversationId },

        displayName = authorName,
        realName = "",
        avatarUrl = null,
    )
}

internal fun JsonObject.decodeActivityView(prefs: JsonObject? = null): ActivityViewFilter? {
    val key = str("id") ?: return null
    val filters = obj("filters")
    return ActivityViewFilter(
        key = key,
        title = str("name") ?: key,
        sort = str("sort") ?: prefs?.str("sort"),
        density = str("density") ?: prefs?.str("density"),
        filters = ActivityFilters(
            entryTypes = filters?.strings("entry_types"),
            channelIds = filters?.strings("channel_ids"),
            channelSectionIds = filters?.strings("channel_section_ids"),
            unreadOnly = filters?.bool("unread_only"),
            archiveOnly = filters?.bool("archive_only"),
            priorityOnly = filters?.bool("priority_only"),
            readState = filters?.str("read_state"),
            onlySalesforceChannels = filters?.bool("only_salesforce_channels"),
            automationsOnly = filters?.bool("automations_only"),
            excludeAutomations = filters?.bool("exclude_automations"),
        ),
    )
}

internal fun List<String>.toEmojiCounts(): List<EmojiCount> =
    map { EmojiCount(symbol = Emoji.canonicalName(it) ?: it, count = null) }
