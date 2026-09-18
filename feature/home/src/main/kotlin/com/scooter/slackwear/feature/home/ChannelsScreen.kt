package com.scooter.slackwear.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
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
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.scooter.slackwear.core.designsystem.component.HapticScrollEffect
import com.scooter.slackwear.core.designsystem.theme.SlackTokens
import com.scooter.slackwear.core.model.ChannelSectionConfig
import com.scooter.slackwear.core.model.repository.ConversationSummary

@Composable
fun ChannelsScreen(
    conversations: List<ConversationSummary>,
    onConversationClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    sectionConfig: ChannelSectionConfig = ChannelSectionConfig(),
    totalUnread: Long = 0L,
    refreshFailures: Map<String, com.scooter.slackwear.core.model.SafeFailure> = emptyMap(),
    isLoading: Boolean = false,
    onRefresh: () -> Unit = {},
    customEmoji: Map<String, String> = emptyMap(),
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    HapticScrollEffect(listState)

    val sections = remember(conversations, sectionConfig) {
        conversations.sectionsOf(sectionConfig)
    }

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier.fillMaxSize(),
        timeText = pageTimeText("Slacklet", totalUnread),
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val warnings = listOf("roster", "sidebar", "unread").mapNotNull { key ->
                refreshFailures[key]?.let { "$key: ${it.reason}. Unreads may be incomplete." }
            }
            if (isLoading) item { Text("Refreshing chats...") }
            if (warnings.isNotEmpty()) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        warnings.forEach { Text(it, textAlign = TextAlign.Center) }
                        androidx.wear.compose.material3.Button(onClick = onRefresh, enabled = !isLoading) { Text("Retry") }
                    }
                }
            }
            if (sections.isEmpty() && warnings.isEmpty() && !isLoading) {
                item { CaughtUpState() }
            } else {
                sections.forEach { (title, summaries) ->

                    if (sections.size > 1) {
                        item(key = "header-$title") {
                            ListHeader { Text(title) }
                        }
                    }

                    items(summaries, key = { it.conversation.id }) { summary ->
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
}

@Composable
private fun CaughtUpState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "You're all caught up",
            style = MaterialTheme.typography.titleMedium,
            color = SlackTokens.ContentPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Nothing unread",
            style = MaterialTheme.typography.bodySmall,
            color = SlackTokens.ContentTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun pageTimeText(label: String, unread: Long = 0L): @Composable () -> Unit = {
    TimeText { time ->
        timeTextCurvedText(if (unread > 0) "$label · $unread" else label)
        timeTextSeparator()
        timeTextCurvedText(time)
    }
}
