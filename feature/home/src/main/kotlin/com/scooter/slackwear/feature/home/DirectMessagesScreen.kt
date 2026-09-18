package com.scooter.slackwear.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator
import com.scooter.slackwear.core.designsystem.component.HapticScrollEffect
import com.scooter.slackwear.core.designsystem.theme.SlackTokens
import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.model.repository.ConversationSummary

@Composable
fun DirectMessagesScreen(
    conversations: List<ConversationSummary>,
    onConversationClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    customEmoji: Map<String, String> = emptyMap(),
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    HapticScrollEffect(listState)

    val directMessages = remember(conversations) { conversations.directMessages() }

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier.fillMaxSize(),
        timeText = {
            TimeText { time ->
                timeTextCurvedText("DMs")
                timeTextSeparator()
                timeTextCurvedText(time)
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
                start = 10.dp,
                end = 10.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (directMessages.isEmpty()) {
                item { NoDirectMessages() }
            } else {
                items(directMessages, key = { it.conversation.id }) { summary ->
                    ConversationRow(
                        summary = summary,
                        onClick = { onConversationClick(summary.conversation.id) },
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        customEmoji = customEmoji,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoDirectMessages(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No direct messages",
            style = MaterialTheme.typography.titleMedium,
            color = SlackTokens.ContentPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "They'll appear here once someone writes",
            style = MaterialTheme.typography.bodySmall,
            color = SlackTokens.ContentTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

fun List<ConversationSummary>.directMessages(): List<ConversationSummary> =
    filter {
        it.conversation.kind == ConversationKind.DIRECT_MESSAGE ||
            it.conversation.kind == ConversationKind.GROUP_MESSAGE
    }
        .filter {
            it.conversation.isOpen || it.unread.count > 0 || it.unread.hasMention
        }

        .sortedByDescending { it.unread.lastActivityTs.orEmpty().padStart(TS_WIDTH, '0') }

private const val TS_WIDTH = 17
