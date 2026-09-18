package com.scooter.slackwear.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import com.scooter.slackwear.core.model.repository.FeedPageState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
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
import com.scooter.slackwear.core.designsystem.component.Avatar
import com.scooter.slackwear.core.designsystem.component.HapticScrollEffect
import com.scooter.slackwear.core.designsystem.component.UnreadBadge
import com.scooter.slackwear.core.designsystem.theme.SlackTokens
import com.scooter.slackwear.core.model.SafeFailure
import com.scooter.slackwear.core.model.repository.ActivityItem
import com.scooter.slackwear.core.model.repository.ActivityViewFilter
import com.scooter.slackwear.core.model.repository.BadgeCounts

@Composable
fun ActivityScreen(
    activity: List<ActivityItem>,
    onItemClick: (ActivityItem) -> Unit,
    modifier: Modifier = Modifier,
    views: List<ActivityViewFilter> = emptyList(),
    badges: BadgeCounts? = null,
    isLoading: Boolean = false,
    refreshFailures: Map<String, SafeFailure> = emptyMap(),
    isMarkingRead: Boolean = false,
    readFailures: List<SafeFailure> = emptyList(),
    onRefresh: () -> Unit = {},
    onRetryRead: () -> Unit = {},
    activityPageState: FeedPageState = FeedPageState(),
    threadPageState: FeedPageState = FeedPageState(),
    onLoadMore: () -> Unit = {},
    unreadOnly: Boolean = false,
    onToggleUnreadOnly: () -> Unit = {},
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    HapticScrollEffect(listState)

    val refreshWarnings = refreshFailures.activityWarnings()
    val hasRefreshFailure = refreshWarnings.isNotEmpty()
    val hasReadFailure = readFailures.isNotEmpty()
    val activeView = ActivityViewFilter(key = "activity", title = "Activity")
    val visible = remember(activity, unreadOnly) {
        if (unreadOnly) activity.filter { it.unreadCount > 0 } else activity
    }
    val badgeTotal = badges?.unreadByEntryType?.values?.sum() ?: 0L
    val paging = activityPageState.isLoading || threadPageState.isLoading
    val hasMore = activityPageState.hasMore || threadPageState.hasMore
    val pageErrors = listOfNotNull(
        activityPageState.error?.let { "Activity feed: ${it.reason}" },
        threadPageState.error?.let { "Followed threads: ${it.reason}" },
    )

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier.fillMaxSize(),
        timeText = pageTimeText("Activity"),
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item(key = "activity-header") {
                ActivityHeader(
                    view = activeView,
                    totalUnread = badgeTotal,
                    unreadOnly = unreadOnly,
                    onToggleUnreadOnly = onToggleUnreadOnly,
                )
            }

            if (isLoading && visible.isEmpty()) {
                item(key = "activity-loading") { Text("Loading activity…") }
            }
            if (hasRefreshFailure) {
                item(key = "activity-refresh-error") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        refreshWarnings.forEach { warning -> Text(warning, textAlign = TextAlign.Center) }
                        Button(onClick = onRefresh, enabled = !isLoading) { Text("Retry refresh") }
                    }
                }
            }
            if (isMarkingRead) {
                item(key = "activity-marking") { Text("Marking activity read…") }
            }
            if (hasReadFailure) {
                item(key = "activity-read-error") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        readFailures.map { it.reason }.distinct().forEach { reason ->
                            Text("Couldn't mark activity read - $reason", textAlign = TextAlign.Center)
                        }
                        Button(onClick = onRetryRead, enabled = !isMarkingRead) { Text("Retry mark read") }
                    }
                }
            }
            if (visible.isEmpty() && !isLoading && !hasRefreshFailure && !hasReadFailure && !isMarkingRead && (unreadOnly || (!hasMore && pageErrors.isEmpty()))) {
                item { CaughtUp(unreadOnly = unreadOnly) }
            } else {
                items(visible, key = { it.id }) { item ->
                    ActivityRow(
                        item = item,
                        onClick = { if (item.target != null) onItemClick(item) },
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }
            }
            item(key = "activity-pagination") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        paging -> Text("Loading more…")
                        pageErrors.isNotEmpty() -> {
                            pageErrors.forEach { Text("$it. Activity is incomplete.", textAlign = TextAlign.Center) }
                            Button(onClick = if (hasMore) onLoadMore else onRefresh, enabled = !isLoading) { Text("Retry") }
                        }
                        hasMore -> {
                            LaunchedEffect(visible.size, activityPageState, threadPageState, isLoading) {
                                if (!isLoading) onLoadMore()
                            }
                            Button(onClick = onLoadMore, enabled = !isLoading) { Text("Load more") }
                        }
                        activityPageState.initialized || threadPageState.initialized -> Text("End of activity")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityHeader(
    view: ActivityViewFilter,
    totalUnread: Long,
    unreadOnly: Boolean,
    onToggleUnreadOnly: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListHeader(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = view.title,
                style = MaterialTheme.typography.titleSmall,
                color = SlackTokens.ContentPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                label = if (unreadOnly) "Unread" else "All",
                selected = unreadOnly,
                onClick = onToggleUnreadOnly,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (totalUnread > 0) {
                Spacer(Modifier.width(6.dp))
                UnreadBadge(count = totalUnread.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), isMention = false)
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) SlackTokens.OnSkyBlue else SlackTokens.SkyBlue,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) SlackTokens.SkyBlue else SlackTokens.ContainerElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun ActivityRow(
    item: ActivityItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation? = null,
) {

    val named = item.author.displayName.isNotBlank()
    val headline = if (named) item.author.displayName else item.conversationName

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlackTokens.ContainerPrimary),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        transformation = transformation,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(
                id = if (named) item.author.id else item.conversationId,
                initials = headline.take(2),
                imageUrl = item.author.avatarUrl,
                size = 26.dp,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = SlackTokens.ContentPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = SlackTokens.ContentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                activityContext(item).takeIf { it.isNotBlank() && named }?.let { context ->
                    Text(
                        text = context,
                        style = MaterialTheme.typography.bodySmall,
                        color = SlackTokens.ContentTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item.unreadCount > 0) {
                Spacer(Modifier.width(6.dp))
                if (item.unreadCountIsExact) {
                    UnreadBadge(
                        count = item.unreadCount,
                        isMention = item.kind == ActivityItem.Kind.MENTION,
                    )
                } else {
                    Text("Unread", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun activityContext(item: ActivityItem): String = when (item.kind) {
    ActivityItem.Kind.DIRECT_MESSAGE -> "Direct message"
    ActivityItem.Kind.BOT_DIRECT_MESSAGE -> "App message"
    else -> item.conversationName.takeIf(String::isNotBlank)?.let { "in $it" }.orEmpty()
}

@Composable
private fun CaughtUp(unreadOnly: Boolean = false, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "You're all caught up",
            style = MaterialTheme.typography.titleMedium,
            color = SlackTokens.ContentPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (unreadOnly) "Nothing unread - tap All to see everything" else "Nothing is waiting on you",
            style = MaterialTheme.typography.bodySmall,
            color = SlackTokens.ContentTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun pageTimeText(label: String): @Composable () -> Unit = {
    TimeText { time ->
        timeTextCurvedText(label)
        timeTextSeparator()
        timeTextCurvedText(time)
    }
}
