package com.scooter.slackwear.feature.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.scooter.slackwear.core.designsystem.component.HapticScrollEffect
import com.scooter.slackwear.core.designsystem.theme.SlackTokens

@Composable
fun ReactionPicker(
    frequent: List<String>,
    searchResults: List<String>,
    query: String,
    customEmoji: Map<String, String>,
    onSearch: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    reaction: ReactionState = ReactionState.Idle,
    onSuccess: () -> Unit = {},
) {
    LaunchedEffect(reaction) {
        if (reaction == ReactionState.Succeeded) onSuccess()
    }
    val pending = reaction == ReactionState.Pending
    val listState = rememberTransformingLazyColumnState()
    HapticScrollEffect(listState)

    val showingSearch = query.isNotBlank()
    val rows = (if (showingSearch) searchResults else frequent).chunked(COLUMNS)

    ScreenScaffold(scrollState = listState, modifier = modifier.fillMaxSize()) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
                start = 14.dp,
                end = 14.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {

                Button(
                    onClick = onSearch,
                    enabled = !pending,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = if (showingSearch) "“$query”" else "Search emoji",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (pending || reaction is ReactionState.Failed) {
                item {
                    Text(
                        text = if (reaction is ReactionState.Failed) reaction.message else "Updating reaction…",
                        color = if (reaction is ReactionState.Failed) SlackTokens.RaspberryRed else SlackTokens.ContentSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                ListHeader {
                    Text(if (showingSearch) "Results" else "Most used")
                }
            }

            if (rows.isEmpty()) {
                item {
                    Text(
                        text = "Nothing matches “$query”",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlackTokens.ContentTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )
                }
            } else {
                items(rows, key = { row -> row.joinToString(",") }) { row ->
                    EmojiRow(names = row, customEmoji = customEmoji, onPick = onPick, enabled = !pending)
                }
            }
        }
    }
}

@Composable
private fun EmojiRow(
    names: List<String>,
    customEmoji: Map<String, String>,
    onPick: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        names.forEach { name ->
            EmojiCell(
                name = name,
                customEmoji = customEmoji,
                onPick = onPick,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }

        repeat(COLUMNS - names.size) {
            Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun EmojiCell(
    name: String,
    customEmoji: Map<String, String>,
    onPick: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(SlackTokens.ContainerPrimary)
            .clickable(enabled = enabled) { onPick(name) },
        contentAlignment = Alignment.Center,
    ) {
        EmojiGlyph(name = name, customEmoji = customEmoji, size = 22.dp)
    }
}

private const val COLUMNS = 4
