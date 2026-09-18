package com.scooter.slackwear.feature.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator
import com.scooter.slackwear.core.designsystem.component.HapticScrollEffect
import com.scooter.slackwear.core.designsystem.theme.SlackTokens
import com.scooter.slackwear.core.model.Message

@Composable
fun ThreadScreen(
    state: ThreadUiState,
    onReply: () -> Unit,
    onReact: (Message) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    customEmoji: Map<String, String> = emptyMap(),
    pagination: com.scooter.slackwear.core.model.PaginationState = com.scooter.slackwear.core.model.PaginationState(),
    onLoadMore: () -> Unit = {},
    quoted: Map<String, QuotedMessage> = emptyMap(),
    highlightTs: String? = null,
) {
    val listState = rememberTransformingLazyColumnState()
    HapticScrollEffect(listState)
    PaginationEffect(listState, pagination, !state.isLoading, onLoadMore)

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier.fillMaxSize(),
        timeText = {
            TimeText { time ->
                timeTextCurvedText("Thread")
                timeTextSeparator()
                timeTextCurvedText(time)
            }
        },
        edgeButton = {
            EdgeButton(onClick = onReply) { Text("Reply") }
        },
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = ThreadTopInset,
                bottom = contentPadding.calculateBottomPadding(),
                start = ThreadSideInset,
                end = ThreadSideInset,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.parent?.let { parent ->
                item(key = "parent") {
                    ParentMessage(
                        message = parent,
                        authorName = state.authors[parent.authorId]?.displayName,
                    )
                }
            }

            item(key = "divider") {
                ListHeader {
                    Text(
                        when {
                            state.error != null -> state.error.message
                            state.isLoading && state.replies.isEmpty() -> "Loading…"
                            state.replies.isEmpty() -> "Start a thread"
                            state.replies.size == 1 -> "1 reply"
                            else -> "${state.replies.size} replies"
                        },
                    )
                }
            }

            if (state.error != null) {
                item(key = "retry") {
                    androidx.wear.compose.material3.Button(onClick = onRetry, enabled = !state.isLoading) {
                        Text(if (state.error.operation == ThreadFailure.Operation.MARK_READ) "Retry mark read" else "Retry load")
                    }
                }
            }

            itemsIndexed(state.replies, key = { _, reply -> reply.ts }) { index, reply ->
                MessageRow(
                    message = reply,
                    author = state.authors[reply.authorId],
                    showHeader = state.replies.startsNewReplyGroupAt(index),
                    onReact = { onReact(reply) },
                    onOpenThread = {},
                    customEmoji = customEmoji,
                    quoted = quoted,
                    highlighted = reply.ts == highlightTs,
                )
            }
            item(key = "pagination") { PaginationRow(pagination, onLoadMore) }
        }
    }
}

@Composable
private fun ParentMessage(
    message: Message,
    authorName: String?,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(SlackTokens.Divider),
        )
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.layout.Column {
            Text(
                text = authorName ?: message.authorId,
                style = MaterialTheme.typography.titleSmall,
                color = SlackTokens.ContentSecondary,
            )
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = SlackTokens.ContentPrimary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun List<Message>.startsNewReplyGroupAt(index: Int): Boolean {
    if (index == 0) return true
    return this[index - 1].authorId != this[index].authorId
}

private val ThreadTopInset = 36.dp
private val ThreadSideInset = 18.dp
