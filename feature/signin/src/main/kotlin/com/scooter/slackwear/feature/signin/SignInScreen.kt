package com.scooter.slackwear.feature.signin

import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.scooter.slackwear.core.designsystem.theme.SlackTokens

@Composable
fun SignInScreen(
    state: SignInState,
    domain: String,
    onDomainChanged: (String) -> Unit,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlackTokens.BasePrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SideInset, vertical = VerticalInset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            SignInState.Starting -> Working(text = "Connecting…")
            is SignInState.Waiting -> Waiting(
                code = state.code,
                signInUrl = state.signInUrl,
                onCancel = onCancel,
            )

            is SignInState.Failed -> SignInError(message = state.message, onRetry = onDismissError)
            else -> SignInPrompt(
                domain = domain,
                onSignIn = onSignIn,
                onEditWorkspace = rememberWorkspaceInput(onText = onDomainChanged),
            )
        }
    }
}

@Composable
private fun SignInPrompt(
    domain: String,
    onSignIn: () -> Unit,
    onEditWorkspace: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BrandMark()
        Text(
            text = "Sign in to Slack",
            style = MaterialTheme.typography.titleMedium,
            color = SlackTokens.ContentPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "One short sign-in on your computer. Only needed once.",
            style = MaterialTheme.typography.bodySmall,
            color = SlackTokens.ContentSecondary,
            textAlign = TextAlign.Center,
        )

        WorkspaceChip(domain = domain, onClick = onEditWorkspace)

        Button(
            onClick = onSignIn,
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun WorkspaceChip(
    domain: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(SlackTokens.ContainerPrimary)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = "$domain.slack.com",
            style = MaterialTheme.typography.bodySmall,
            color = SlackTokens.ContentPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = "✎",
            style = MaterialTheme.typography.bodySmall,
            color = SlackTokens.SkyBlue,
        )
    }
}

@Composable
private fun rememberWorkspaceInput(onText: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult

        RemoteInput.getResultsFromIntent(data)
            ?.getCharSequence(WORKSPACE_KEY)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let(onText)
    }

    val intent = remember {
        val input = RemoteInput.Builder(WORKSPACE_KEY)
            .setLabel("Workspace")
            .build()
        RemoteInputIntentHelper.putRemoteInputsExtra(
            RemoteInputIntentHelper.createActionRemoteInputIntent(),
            listOf(input),
        )
    }

    return remember(intent, launcher) { { launcher.launch(intent) } }
}

@Composable
private fun Working(text: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = SlackTokens.ContentPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Waiting(code: String, signInUrl: String, onCancel: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Finish signing in on your computer",
            style = MaterialTheme.typography.titleMedium,
            color = SlackTokens.ContentPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Code",
            style = MaterialTheme.typography.labelMedium,
            color = SlackTokens.ContentSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = code,
            style = MaterialTheme.typography.displayMedium,
            color = SlackTokens.ContentPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = signInUrl,
            style = MaterialTheme.typography.bodySmall,
            color = SlackTokens.SkyBlue,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "Open that address on any device and follow the prompts.",
            style = MaterialTheme.typography.labelSmall,
            color = SlackTokens.ContentSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "This screen finishes by itself.",
            style = MaterialTheme.typography.labelSmall,
            color = SlackTokens.ContentSecondary,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onCancel, modifier = Modifier.padding(top = 2.dp)) {
            Text("Cancel")
        }
    }
}

@Composable
private fun SignInError(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Couldn't sign in",
            style = MaterialTheme.typography.titleMedium,
            color = SlackTokens.ContentPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = SlackTokens.ContentSecondary,
            textAlign = TextAlign.Center,

            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) {
            Text("Try again")
        }
    }
}

@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(
            com.scooter.slackwear.core.designsystem.R.drawable.ic_slacklet_mark,
        ),
        contentDescription = null,
        modifier = modifier.size(40.dp),
    )
}

private const val WORKSPACE_KEY = "slack_workspace"

private val SideInset = 24.dp
private val VerticalInset = 26.dp
