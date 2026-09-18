package com.scooter.slackwear.feature.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import com.scooter.slackwear.core.model.PaginationState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator
import com.scooter.slackwear.core.designsystem.component.HapticScrollEffect
import com.scooter.slackwear.core.designsystem.theme.SlackTokens
import com.scooter.slackwear.core.model.Message
import com.scooter.slackwear.core.model.repository.HuddleState

@Composable
fun ConversationScreen(
    state: ConversationUiState,
    onCompose: () -> Unit,
    onAttach: () -> Unit,
    onReact: (Message) -> Unit,
    onOpenThread: (Message) -> Unit,
    modifier: Modifier = Modifier,
    customEmoji: Map<String, String> = emptyMap(),
    upload: UploadState = UploadState.Idle,
    huddle: HuddleState? = null,
    knocked: Boolean = false,
    onKnock: () -> Unit = {},
    onCancelKnock: () -> Unit = {},
    onToggleReaction: (Message, String) -> Unit = { message, _ -> onReact(message) },
    reaction: ReactionState = ReactionState.Idle,
    pagination: PaginationState = PaginationState(),
    onLoadOlder: () -> Unit = {},
    quoted: Map<String, QuotedMessage> = emptyMap(),
    onOpenQuoted: (QuotedMessage) -> Unit = {},
    highlightTs: String? = null,
) {
    val listState = rememberTransformingLazyColumnState()
    HapticScrollEffect(listState)

    PaginationEffect(listState, pagination, !state.isLoading, onLoadOlder)

    val leadingItems = listOf(
        state.readError != null,
        reaction is ReactionState.Failed,
        huddle != null,
        upload is UploadState.Failed,
    ).count { it }

    val ordered = state.messages.asReversed()
    val highlightIndex = remember(ordered, highlightTs) {
        highlightTs?.let { ts -> ordered.indexOfFirst { it.ts == ts } }?.takeIf { it >= 0 }
    }

    LaunchedEffect(highlightIndex, leadingItems) {
        highlightIndex?.let { listState.scrollToItem(leadingItems + it) }
    }

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier.fillMaxSize(),
        timeText = {
            TimeText { time ->
                timeTextCurvedText(state.title.ifBlank { "Slacklet" })
                timeTextSeparator()
                timeTextCurvedText(time)
            }
        },
        edgeButton = {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                    .fillMaxWidth().clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = "Attach a file", onClick = onAttach),
                    contentAlignment = Alignment.Center,
                ) { Text("Attach", color = MaterialTheme.colorScheme.onPrimaryContainer) }
                Box(
                    Modifier.weight(1.3f).heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = "Write a message", onClick = onCompose),
                    contentAlignment = Alignment.Center,
                ) { Text("Message", color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(
                top = TopInset,
                bottom = contentPadding.calculateBottomPadding(),
                start = SideInset,
                end = SideInset,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.readError?.let { error ->
                item(key = "readError") {
                    Button(onClick = state.retryRead, enabled = !state.isLoading) {
                        Text(error)
                    }
                }
            }

            if (reaction is ReactionState.Failed) {
                item(key = "reactionError") {
                    Text(reaction.message, color = SlackTokens.RaspberryRed)
                }
            }

            if (huddle != null) {
                item(key = "huddle") {
                    HuddleStrip(
                        huddle = huddle,
                        knocked = knocked,
                        onKnock = onKnock,
                        onCancelKnock = onCancelKnock,
                    )
                }
            }

            if (upload is UploadState.Failed) {
                item(key = "uploadError") { Text(upload.message, color = SlackTokens.RaspberryRed) }
            }

            if (state.messages.isEmpty()) {
                item { EmptyConversation(isLoading = state.isLoading) }
            } else {
                itemsIndexed(ordered, key = { _, message -> message.ts }) { index, message ->
                    MessageRow(
                        message = message,
                        author = state.authors[message.authorId],
                        showHeader = state.messages.startsNewGroupAt(state.messages.lastIndex - index),
                        onReact = { onReact(message) },
                        onOpenThread = { onOpenThread(message) },
                        customEmoji = customEmoji,
                        onToggleReaction = { emoji -> onToggleReaction(message, emoji) },
                        reactionsEnabled = reaction != ReactionState.Pending,
                        quoted = quoted,
                        onOpenQuoted = onOpenQuoted,

                        highlighted = message.ts == highlightTs,
                    )
                }
            }
            item(key = "pagination") { PaginationRow(pagination, onLoadOlder) }
        }
    }
}

@Composable
internal fun PaginationEffect(
    listState: TransformingLazyColumnState,
    pagination: PaginationState,
    enabled: Boolean,
    onLoad: () -> Unit,
) {
    val latestPage by rememberUpdatedState(pagination)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestLoad by rememberUpdatedState(onLoad)
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val nearEnd = layout.visibleItems.any { it.index >= layout.totalItemsCount - 3 }
            Triple(nearEnd, latestEnabled, latestPage)
        }.collect { (nearEnd, active, page) ->
            if (nearEnd && active && page.canLoadNext) latestLoad()
        }
    }
}

@Composable
internal fun PaginationRow(page: PaginationState, onLoad: () -> Unit) {
    if (page.error != null) {
        Button(onClick = onLoad, enabled = !page.isLoading) { Text("Retry - ${page.error?.reason}") }
    } else if (page.isLoading) {
        Text("Loading…")
    } else if (page.initialized && !page.endReached) {
        Button(onClick = onLoad) { Text("Load more") }
    } else if (page.initialized && page.endReached) {
        Text("All messages loaded", color = SlackTokens.ContentSecondary)
    }
}

@Composable
private fun EmptyConversation(isLoading: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (isLoading) "Loading…" else "No messages yet",
            style = MaterialTheme.typography.bodyLarge,
            color = SlackTokens.ContentSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HuddleStrip(
    huddle: HuddleState,
    knocked: Boolean,
    onKnock: () -> Unit,
    onCancelKnock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlackTokens.ContainerElevated),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Column {
            Text(
                text = when {
                    huddle.participantCount > 1 -> "${huddle.participantCount} people in a huddle"
                    huddle.participantCount == 1 -> "1 person in a huddle"
                    else -> "A huddle is live"
                },
                style = MaterialTheme.typography.titleSmall,
                color = SlackTokens.ContentPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (huddle.participantNames.isNotEmpty()) {
                Text(
                    text = huddle.participantNames.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = SlackTokens.ContentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = if (knocked) onCancelKnock else onKnock,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Text(if (knocked) "Cancel knock" else "Knock")
            }
        }
    }
}

private fun List<Message>.startsNewGroupAt(index: Int): Boolean {
    if (index == 0) return true
    val previous = this[index - 1]
    val current = this[index]
    if (previous.authorId != current.authorId) return true
    return current.epochSeconds - previous.epochSeconds > GROUPING_WINDOW_SECONDS
}

private const val GROUPING_WINDOW_SECONDS = 5 * 60

private val TopInset = 36.dp
private val SideInset = 18.dp
