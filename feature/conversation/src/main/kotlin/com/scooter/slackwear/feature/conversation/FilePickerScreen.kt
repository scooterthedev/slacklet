package com.scooter.slackwear.feature.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.scooter.slackwear.core.designsystem.component.HapticScrollEffect
import com.scooter.slackwear.core.designsystem.theme.SlackTokens

@Composable
fun FilePickerScreen(
    state: FilePickerUiState,
    onPick: (WatchMediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = state.items
    val listState = rememberTransformingLazyColumnState()
    HapticScrollEffect(listState)

    ScreenScaffold(scrollState = listState, modifier = modifier.fillMaxSize()) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
                start = 12.dp,
                end = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { ListHeader { Text("Send a file") } }

            state.error?.let { error -> item { Notice(error) } }
            state.sending?.let { name -> item { Notice("Sending $name…") } }

            when {
                state.isLoading -> item { Notice("Looking…") }
                items.isEmpty() -> item {
                    Notice(
                        "Nothing on this watch to send.\n" +
                            "Photos and recordings stored here will show up.",
                    )
                }
                else -> items(items, key = { it.uri.toString() }) { item ->
                    MediaRow(item = item, onClick = { onPick(item) })
                }
            }
        }
    }
}

@Composable
private fun MediaRow(
    item: WatchMediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlackTokens.ContainerPrimary),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.kind == WatchMediaItem.Kind.IMAGE) {
                AsyncImage(
                    model = item.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Text(
                    text = if (item.kind == WatchMediaItem.Kind.AUDIO) "🎙" else "🎬",
                    modifier = Modifier.size(32.dp),
                    textAlign = TextAlign.Center,
                )
            }

            Column(Modifier.padding(start = 8.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = SlackTokens.ContentPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.sizeBytes.asReadableSize(),
                    style = MaterialTheme.typography.bodySmall,
                    color = SlackTokens.ContentTertiary,
                )
            }
        }
    }
}

@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = SlackTokens.ContentTertiary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
    )
}

private fun Long.asReadableSize(): String = when {
    this >= 1024 * 1024 -> "%.1f MB".format(this / (1024.0 * 1024.0))
    this >= 1024 -> "${this / 1024} KB"
    else -> "$this bytes"
}
