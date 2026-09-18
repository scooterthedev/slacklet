package com.scooter.slackwear.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.scooter.slackwear.core.designsystem.component.HapticScrollEffect
import com.scooter.slackwear.core.designsystem.theme.SlackTokens

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onUpdate: ((com.scooter.slackwear.core.model.repository.NotificationSettings) -> com.scooter.slackwear.core.model.repository.NotificationSettings) -> Unit,
    onToggleSnooze: () -> Unit,
    onClearLearnedReactions: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    delivery: DeliveryUiState = DeliveryUiState(),
    onRetryDelivery: () -> Unit = {},
) {
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
            item { ListHeader { Text("Notifications") } }

            item {
                Button(
                    onClick = onRetryDelivery,
                    enabled = delivery.canRetry,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Column {
                        Text(delivery.title)
                        Text(
                            text = delivery.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = SlackTokens.ContentTertiary,
                        )
                    }
                }
            }

            item {
                Toggle(
                    label = "Push to watch",
                    secondary = "Direct, not via phone",
                    checked = state.notifications.pushToWatch,
                    onCheckedChange = { value -> onUpdate { it.copy(pushToWatch = value) } },
                )
            }
            item {
                Toggle(
                    label = "Mentions",
                    checked = state.notifications.mentions,
                    onCheckedChange = { value -> onUpdate { it.copy(mentions = value) } },
                )
            }
            item {
                Toggle(
                    label = "Direct messages",
                    checked = state.notifications.directMessages,
                    onCheckedChange = { value -> onUpdate { it.copy(directMessages = value) } },
                )
            }
            item {
                Toggle(
                    label = "Thread replies",
                    checked = state.notifications.threadReplies,
                    onCheckedChange = { value -> onUpdate { it.copy(threadReplies = value) } },
                )
            }
            item {
                Toggle(
                    label = "All channel activity",
                    secondary = "Everything, everywhere",
                    checked = state.notifications.allActivity,
                    onCheckedChange = { value -> onUpdate { it.copy(allActivity = value) } },
                )
            }

            item { ListHeader { Text("Do Not Disturb") } }

            item {
                Toggle(
                    label = "Follow Slack DND",
                    checked = state.notifications.followSlackDnd,
                    onCheckedChange = { value -> onUpdate { it.copy(followSlackDnd = value) } },
                )
            }
            item {
                Button(onClick = onToggleSnooze, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.dnd.snoozeEnabled) "End snooze" else "Snooze for 1 hour")
                }
            }

            item { ListHeader { Text("Reactions") } }

            item {
                Button(onClick = onClearLearnedReactions, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset most used")
                }
            }

            item { ListHeader { Text("Account") } }

            item {
                Button(
                    onClick = onSignOut,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SlackTokens.RaspberryRed,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sign out")
                }
            }
        }
    }
}

@Composable
private fun Toggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        secondaryLabel = secondary?.let { { Text(it) } },
    )
}

data class DeliveryUiState(
    val title: String = "Notification delivery",
    val summary: String = "Connecting\u2026",
    val canRetry: Boolean = false,
)
