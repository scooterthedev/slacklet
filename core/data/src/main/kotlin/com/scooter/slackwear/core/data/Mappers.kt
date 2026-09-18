package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.entity.ConversationEntity
import com.scooter.slackwear.core.database.entity.MessageEntity
import com.scooter.slackwear.core.database.entity.UserEntity
import com.scooter.slackwear.core.model.Conversation
import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.model.DeliveryState
import com.scooter.slackwear.core.model.Message
import com.scooter.slackwear.core.model.Reaction
import com.scooter.slackwear.core.model.SlackUser
import com.scooter.slackwear.core.model.UnreadState
import com.scooter.slackwear.core.network.model.ConversationDto
import com.scooter.slackwear.core.network.model.MessageDto
import com.scooter.slackwear.core.network.model.UserDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun ConversationDto.toEntity(now: Long): ConversationEntity = ConversationEntity(
    id = id,
    name = name ?: user.orEmpty(),
    kind = kind(),
    topic = topic?.value?.takeIf(String::isNotBlank),
    isMuted = false,
    isArchived = isArchived,
    counterpartUserId = user,
    isOpen = isOpen ?: false,
    latestPreview = latest?.text?.let(::flatten),
    latestTs = latest?.ts,
    lastSeenTs = lastRead,
    unreadCount = unreadCountDisplay ?: 0,
    mentionCount = 0,
    unreadConfidence = if (unreadCountDisplay != null) {
        UnreadState.Confidence.EXACT
    } else {
        UnreadState.Confidence.DERIVED
    },
    refreshedAtMillis = now,
)

private fun ConversationDto.kind(): ConversationKind = when {
    isIm -> ConversationKind.DIRECT_MESSAGE
    isMpim -> ConversationKind.GROUP_MESSAGE
    isPrivate || isGroup -> ConversationKind.PRIVATE_CHANNEL
    else -> ConversationKind.PUBLIC_CHANNEL
}

fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    name = name,
    kind = kind,
    topic = topic,
    isMuted = isMuted,
    isArchived = isArchived,
    counterpartUserId = counterpartUserId,
    isOpen = isOpen,
)

fun ConversationEntity.toUnreadState(): UnreadState = UnreadState(
    conversationId = id,
    count = unreadCount,
    mentionCount = mentionCount,
    lastActivityTs = latestTs,
    confidence = unreadConfidence,
)

fun UserDto.toEntity(): UserEntity = UserEntity(
    id = id,
    displayName = profile?.displayName?.takeIf(String::isNotBlank)
        ?: name
        ?: realName
        ?: id,
    realName = profile?.realName ?: realName.orEmpty(),
    avatarUrl = profile?.image192 ?: profile?.image72,
    isBot = isBot,
)

fun UserEntity.toDomain(): SlackUser = SlackUser(
    id = id,
    displayName = displayName,
    realName = realName,
    avatarUrl = avatarUrl,
    isBot = isBot,
)

fun mentionedUserIds(text: String): List<String> =
    MENTION_PATTERN.findAll(text).map { it.groupValues[1] }.distinct().toList()

private val MENTION_PATTERN = Regex("<@([A-Z0-9]+)(?:\\|[^>]*)?>")

fun MessageDto.toEntity(
    conversationId: String,
    names: MentionNames = MentionNames.None,
): MessageEntity = MessageEntity(
    conversationId = conversationId,
    ts = ts,
    authorId = user.orEmpty(),
    text = flattenBody(text, names),
    threadTs = threadTs?.takeIf { it != ts },
    replyCount = replyCount ?: 0,
    reactionsJson = json.encodeToString(
        ListSerializer(com.scooter.slackwear.core.network.model.ReactionDto.serializer()),
        reactions,
    ),
    isEdited = edited != null,
    deliveryState = DeliveryState.SENT,
)

fun MessageEntity.toDomain(currentUserId: String): Message = Message(
    ts = ts,
    conversationId = conversationId,
    authorId = authorId,
    text = text,
    threadTs = threadTs,
    replyCount = replyCount,
    reactions = decodeReactions(reactionsJson, currentUserId),
    isEdited = isEdited,
    deliveryState = deliveryState,
)

fun Message.withThreadMetadata(dto: MessageDto?): Message = copy(
    isSubscribed = dto?.subscribed,
    lastReadTs = dto?.lastRead,
    latestReplyTs = dto?.latestReply,
    threadReplyCount = dto?.replyCount,
)

private fun decodeReactions(raw: String, currentUserId: String): List<Reaction> =
    runCatching {
        json.decodeFromString(
            ListSerializer(com.scooter.slackwear.core.network.model.ReactionDto.serializer()),
            raw,
        ).map { dto ->
            Reaction(
                name = dto.name,
                count = dto.count,
                includesMe = currentUserId in dto.users,
            )
        }
    }.getOrDefault(emptyList())

private val MENTION_MARKUP = Regex("<@([A-Z0-9]+)(\\|([^>]*))?>")
private val CHANNEL_MARKUP = Regex("<#[A-Z0-9]+\\|([^>]*)>")
private val BROADCAST_MARKUP = Regex("<!(channel|here|everyone)>")
private val LABELLED_LINK = Regex("<(https?://[^|>]+)\\|([^>]*)>")
private val BARE_LINK = Regex("<(https?://[^>]+)>")
private val WHITESPACE_RUN = Regex("\\s+")
private val SPACES_AND_TABS = Regex("[ \\t]+")
private val BLANK_LINE_RUN = Regex("\\n{3,}")
private val CODE_BLOCK = Regex("```([\\s\\S]*?)```")
private val INLINE_CODE = Regex("`([^`\\n]+?)`")
private val BOLD = Regex("\\*([^*\\n]+?)\\*")
private val STRIKE = Regex("~([^~\\n]+?)~")
private val ITALIC = Regex("(?<![\\w`])_([^_\\n]+?)_(?![\\w`])")
private val QUOTE_PREFIX = Regex("^\\s*>\\s?", RegexOption.MULTILINE)

private fun flattenMarkup(text: String, names: MentionNames = MentionNames.None): String = text
    .replace(MENTION_MARKUP) { match ->
        val id = match.groupValues[1]
        val inline = match.groupValues[3].takeIf(String::isNotBlank)

        "@" + (inline ?: names.resolve(id))
    }
    .replace(CHANNEL_MARKUP) { "#${it.groupValues[1]}" }
    .replace(BROADCAST_MARKUP) { "@${it.groupValues[1]}" }
    .replace(LABELLED_LINK) { it.groupValues[2] }
    .replace(BARE_LINK) { it.groupValues[1] }
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")

fun flatten(text: String, names: MentionNames = MentionNames.None): String =
    stripMarkdown(flattenMarkup(Emoji.resolve(text), names))
        .replace(WHITESPACE_RUN, " ")
        .trim()

private fun stripMarkdown(text: String): String = text
    .replace(CODE_BLOCK) { it.groupValues[1].trim() }
    .replace(INLINE_CODE) { it.groupValues[1] }
    .replace(BOLD) { it.groupValues[1] }
    .replace(STRIKE) { it.groupValues[1] }
    .replace(ITALIC) { it.groupValues[1] }
    .replace(QUOTE_PREFIX, "")

fun flattenBody(text: String, names: MentionNames = MentionNames.None): String =
    flattenMarkup(Emoji.resolve(text), names)
        .replace(SPACES_AND_TABS, " ")
        .replace(BLANK_LINE_RUN, "\n\n")
        .trim()

class MentionNames(
    private val currentUserId: String = "",
    private val displayNames: Map<String, String> = emptyMap(),
) {
    fun resolve(id: String): String = when {
        id == currentUserId -> "you"
        else -> displayNames[id] ?: "someone"
    }

    companion object {
        val None = MentionNames()
    }
}
