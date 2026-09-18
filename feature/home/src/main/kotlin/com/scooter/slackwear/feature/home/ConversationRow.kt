package com.scooter.slackwear.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.scooter.slackwear.core.designsystem.component.Avatar
import com.scooter.slackwear.core.designsystem.component.EmojiLabel
import com.scooter.slackwear.core.designsystem.component.UnreadBadge
import com.scooter.slackwear.core.designsystem.theme.SlackTokens
import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.model.repository.ConversationSummary

private val RowContentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
private val LeadingSize = 26.dp

@Composable
fun ConversationRow(
    summary: ConversationSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation? = null,
    customEmoji: Map<String, String> = emptyMap(),
) {
    val unread = summary.unread.count > 0 || summary.unread.hasMention

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlackTokens.ContainerPrimary),
        contentPadding = RowContentPadding,
        transformation = transformation,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Leading(summary = summary, unread = unread)
            Spacer(Modifier.width(8.dp))

            Column(Modifier.weight(1f)) {
                EmojiLabel(
                    text = summary.title,
                    customEmoji = customEmoji,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = if (unread) FontWeight.Black else FontWeight.Normal,
                    ),
                    color = if (unread) SlackTokens.ContentPrimary else SlackTokens.ContentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                summary.latestPreview?.takeIf(String::isNotBlank)?.let { preview ->
                    EmojiLabel(
                        text = preview,
                        customEmoji = customEmoji,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (unread) {
                            SlackTokens.ContentSecondary
                        } else {
                            SlackTokens.ContentTertiary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }

            if (unread) {
                Spacer(Modifier.width(6.dp))
                if (summary.unread.confidence == com.scooter.slackwear.core.model.UnreadState.Confidence.EXACT ||
                    summary.unread.hasMention
                ) {
                    UnreadBadge(
                        count = if (summary.unread.hasMention) summary.unread.mentionCount else summary.unread.count,
                        isMention = summary.unread.hasMention,
                    )
                } else {
                    Box(Modifier.size(6.dp).background(SlackTokens.ContentSecondary, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun Leading(
    summary: ConversationSummary,
    unread: Boolean,
    modifier: Modifier = Modifier,
) {
    when (summary.conversation.kind) {
        ConversationKind.DIRECT_MESSAGE -> Avatar(
            id = summary.conversation.counterpartUserId ?: summary.conversation.id,
            initials = summary.counterpart?.initials ?: "?",
            imageUrl = summary.counterpart?.avatarUrl,
            size = LeadingSize,
            modifier = modifier,
        )

        ConversationKind.GROUP_MESSAGE -> Box(
            modifier = modifier
                .size(LeadingSize)
                .clip(RoundedCornerShape(LeadingSize / 4))
                .background(SlackTokens.ContainerElevated),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = summary.conversation.groupMembers?.size?.toString() ?: "@",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color = if (unread) SlackTokens.ContentPrimary else SlackTokens.ContentSecondary,
            )
        }

        else -> Box(
            modifier = modifier.size(LeadingSize),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "#",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = if (unread) SlackTokens.ContentPrimary else SlackTokens.ContentTertiary,
            )
        }
    }
}
