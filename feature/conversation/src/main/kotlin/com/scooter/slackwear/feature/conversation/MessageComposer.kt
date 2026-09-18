package com.scooter.slackwear.feature.conversation

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.wear.input.RemoteInputIntentHelper

private const val RESULT_KEY = "slack_message"

@Composable
fun rememberMessageComposer(
    title: String,
    quickReplies: List<String>,
    onMessage: (String) -> Unit,
): () -> Unit = rememberTextInput(title = title, choices = quickReplies, onText = onMessage)

@Composable
fun rememberTextInput(
    title: String,
    choices: List<String> = emptyList(),
    onText: (String) -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult

        RemoteInput.getResultsFromIntent(data)
            ?.getCharSequence(RESULT_KEY)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let(onText)
    }

    val intent = remember(title, choices) {
        buildRemoteInputIntent(title = title, choices = choices)
    }

    return remember(intent, launcher) { { launcher.launch(intent) } }
}

private fun buildRemoteInputIntent(title: String, choices: List<String>): Intent {
    val remoteInput = RemoteInput.Builder(RESULT_KEY)
        .setLabel(title)
        .setChoices(choices.toTypedArray())
        .build()

    return RemoteInputIntentHelper.putRemoteInputsExtra(
        RemoteInputIntentHelper.createActionRemoteInputIntent(),
        listOf(remoteInput),
    )
}
