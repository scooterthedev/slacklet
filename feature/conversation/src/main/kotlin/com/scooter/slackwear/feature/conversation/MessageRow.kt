package com.scooter.slackwear.feature.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.scooter.slackwear.core.designsystem.component.Avatar
import com.scooter.slackwear.core.designsystem.theme.SlackTokens
import com.scooter.slackwear.core.model.DeliveryState
import com.scooter.slackwear.core.model.Message
import com.scooter.slackwear.core.model.Reaction
import com.scooter.slackwear.core.model.SlackUser
import com.scooter.slackwear.core.model.findSlackPermalinks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MESSAGE_SPACES_AND_TABS = Regex("[ \\t]+")
private val MESSAGE_BLANK_LINE_RUN = Regex("\\n{3,}")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    message: Message,
    author: SlackUser?,
    showHeader: Boolean,
    onReact: () -> Unit,
    onOpenThread: () -> Unit,
    modifier: Modifier = Modifier,
    customEmoji: Map<String, String> = emptyMap(),
    onToggleReaction: (String) -> Unit = { onReact() },
    reactionsEnabled: Boolean = true,
    quoted: Map<String, QuotedMessage> = emptyMap(),
    onOpenQuoted: (QuotedMessage) -> Unit = {},
    highlighted: Boolean = false,
) {

    val links = remember(message.text, quoted) {
        findSlackPermalinks(message.text).mapNotNull { quoted[it.url]?.let { card -> it.url to card } }
    }
    val body = remember(message.text, links) {
        links.fold(message.text) { text, (url, _) -> text.replace(url, "") }
            .replace(MESSAGE_SPACES_AND_TABS, " ")
            .replace(MESSAGE_BLANK_LINE_RUN, "\n\n")
            .trim()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (highlighted) {
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlackTokens.SkyBlueWash)
                        .padding(4.dp)
                } else {
                    Modifier
                }
            ),
    ) {
        if (showHeader) {
            Avatar(
                id = message.authorId,
                initials = author?.initials ?: "?",
                imageUrl = author?.avatarUrl,
                size = 24.dp,
            )
        } else {
            Spacer(Modifier.width(24.dp))
        }
        Spacer(Modifier.width(8.dp))

        Column(Modifier.weight(1f)) {
            if (showHeader) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = author?.displayName ?: message.authorId,
                        style = MaterialTheme.typography.titleSmall,
                        color = SlackTokens.ContentPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = message.timeLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = SlackTokens.ContentTertiary,
                    )
                }
            }

            if (body.isNotEmpty()) EmojiText(
                text = body,
                customEmoji = customEmoji,
                style = MaterialTheme.typography.bodyLarge,
                color = when (message.deliveryState) {
                    DeliveryState.PENDING -> SlackTokens.ContentTertiary
                    DeliveryState.FAILED -> SlackTokens.RaspberryRed
                    DeliveryState.SENT -> SlackTokens.ContentPrimary
                },

                modifier = Modifier.combinedClickable(
                    onClick = onOpenThread,
                    onLongClick = onReact,
                    onLongClickLabel = "React",
                ),
            )

            links.forEach { (_, card) ->
                QuotedMessageCard(
                    quoted = card,
                    customEmoji = customEmoji,
                    onClick = { onOpenQuoted(card) },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (message.deliveryState == DeliveryState.FAILED) {
                Text(
                    text = "Not sent - tap to retry",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlackTokens.RaspberryRed,
                )
            }

            if (message.reactions.isNotEmpty()) {
                ReactionRow(
                    reactions = message.reactions,
                    customEmoji = customEmoji,
                    onToggleReaction = onToggleReaction,
                    enabled = reactionsEnabled,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (message.replyCount > 0) {
                Text(
                    text = "${message.replyCount} ${if (message.replyCount == 1) "reply" else "replies"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SlackTokens.SkyBlue,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable(onClick = onOpenThread),
                )
            }
        }
    }
}

@Composable
private fun QuotedMessageCard(
    quoted: QuotedMessage,
    customEmoji: Map<String, String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, SlackTokens.Divider, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(
                id = quoted.author?.id ?: quoted.conversationId,
                initials = quoted.author?.initials ?: "?",
                imageUrl = quoted.author?.avatarUrl,
                size = 18.dp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = quoted.author?.displayName.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = SlackTokens.ContentPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = quoted.timeLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = SlackTokens.ContentTertiary,
                maxLines = 1,
            )
        }

        if (quoted.conversationLabel.isNotBlank()) {
            Text(
                text = quoted.conversationLabel,
                style = MaterialTheme.typography.bodySmall,
                color = SlackTokens.ContentTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        EmojiText(
            text = quoted.text,
            customEmoji = customEmoji,
            style = MaterialTheme.typography.bodyMedium,
            color = SlackTokens.ContentSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun QuotedMessage.timeLabel(): String =
    timeFormat.format(Date((ts.substringBefore('.').toLongOrNull() ?: 0L) * 1_000))

@Composable
private fun ReactionRow(
    reactions: List<Reaction>,
    customEmoji: Map<String, String>,
    onToggleReaction: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {

        reactions.take(3).forEach { reaction ->
            ReactionChip(
                reaction = reaction,
                customEmoji = customEmoji,
                onClick = { onToggleReaction(reaction.name) },
                enabled = enabled,
            )
        }
        if (reactions.size > 3) {
            Text(
                text = "+${reactions.size - 3}",
                style = MaterialTheme.typography.bodySmall,
                color = SlackTokens.ContentTertiary,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

@Composable
private fun ReactionChip(
    reaction: Reaction,
    customEmoji: Map<String, String>,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(ReactionChipHeight)
            .clip(ReactionChipShape)
            .background(
                if (reaction.includesMe) SlackTokens.SkyBlueWash else SlackTokens.ContainerElevated,
            )
            .border(
                width = 1.dp,
                color = if (reaction.includesMe) SlackTokens.SkyBlue else SlackTokens.Divider,
                shape = ReactionChipShape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EmojiGlyph(
            name = reaction.name,
            customEmoji = customEmoji,
            size = 14.dp,
            modifier = Modifier.widthIn(max = 56.dp),
        )
        Text(
            text = reaction.count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (reaction.includesMe) SlackTokens.SkyBlue else SlackTokens.ContentSecondary,
            maxLines = 1,
        )
    }
}

private val ReactionChipShape = RoundedCornerShape(9.dp)
private val ReactionChipHeight = 24.dp

private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

private fun Message.timeLabel(): String = timeFormat.format(Date(epochSeconds * 1_000))
