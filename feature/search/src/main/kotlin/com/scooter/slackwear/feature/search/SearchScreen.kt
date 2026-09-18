package com.scooter.slackwear.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
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
import com.scooter.slackwear.core.model.repository.SearchHit
import com.scooter.slackwear.core.model.repository.SearchHitKind

@Composable
fun SearchScreen(
    state: SearchUiState,
    onStartSearch: () -> Unit,
    onCycleSort: () -> Unit,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    HapticScrollEffect(listState)

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier.fillMaxSize(),
        timeText = {
            TimeText { time ->
                timeTextCurvedText("Search")
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
            item(key = "search-field") {
                SearchField(query = state.query, onClick = onStartSearch)
            }

            when {
                state.isSearching -> item(key = "searching") { Notice("Searching…") }

                state.failure != null -> item(key = "failed") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Notice(state.error.orEmpty())
                        Button(onClick = onStartSearch) { Text("Try again") }
                    }
                }

                state.query.isBlank() -> item(key = "prompt") {
                    Notice("Say or type what you're looking for")
                }

                state.hits.isEmpty() -> item(key = "empty") {
                    Notice("Nothing matches “${state.query}”")
                }

                else -> {
                    item(key = "results-header") {
                        ResultsHeader(
                            count = state.hits.size,
                            sortLabel = state.sort.label,
                            onCycleSort = onCycleSort,
                        )
                    }
                    items(state.hits, key = { "${it.kind}:${it.id}" }) { hit ->
                        SearchResultRow(
                            hit = hit,

                            onClick = hit.conversationId
                                .takeIf(String::isNotBlank)
                                ?.let { id -> { onOpenConversation(id) } },
                            modifier = Modifier.transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(FieldWidthFraction)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(SlackTokens.ContainerElevated)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = query.ifBlank { "Search Slack" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (query.isBlank()) SlackTokens.ContentTertiary else SlackTokens.ContentPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val FieldWidthFraction = 0.74f

@Composable
private fun ResultsHeader(
    count: Int,
    sortLabel: String,
    onCycleSort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count == 1) "1 result" else "$count results",
            style = MaterialTheme.typography.labelSmall,
            color = SlackTokens.ContentTertiary,
        )
        Text(
            text = sortLabel,
            style = MaterialTheme.typography.labelSmall,
            color = SlackTokens.SkyBlue,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(SlackTokens.ContainerElevated)
                .clickable(onClick = onCycleSort)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SearchResultRow(
    hit: SearchHit,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation? = null,
) {
    val colors = CardDefaults.cardColors(containerColor = SlackTokens.ContainerPrimary)
    val contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            colors = colors,
            contentPadding = contentPadding,
            transformation = transformation,
        ) {
            HitContent(hit)
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = colors,
            contentPadding = contentPadding,
            transformation = transformation,
        ) {
            HitContent(hit)
        }
    }
}

@Composable
private fun HitContent(hit: SearchHit) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = hit.kind.glyph(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color = SlackTokens.ContentTertiary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            hit.attribution()?.let { attribution ->
                Text(
                    text = attribution,
                    style = MaterialTheme.typography.labelSmall,
                    color = SlackTokens.ContentTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = hit.text,
                style = MaterialTheme.typography.bodyMedium,
                color = SlackTokens.ContentPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun SearchHitKind.glyph(): String = when (this) {
    SearchHitKind.MESSAGE -> "❝"
    SearchHitKind.CHANNEL -> "#"
    SearchHitKind.PERSON -> "@"
    SearchHitKind.FILE -> "◫"
}

private fun SearchHit.attribution(): String? = when (kind) {
    SearchHitKind.MESSAGE -> listOfNotNull(
        authorName.takeIf(String::isNotBlank),
        conversationName.takeIf(String::isNotBlank),
    ).joinToString(" · ").takeIf(String::isNotBlank)

    else -> null
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
            .padding(horizontal = 8.dp, vertical = 20.dp),
    )
}
