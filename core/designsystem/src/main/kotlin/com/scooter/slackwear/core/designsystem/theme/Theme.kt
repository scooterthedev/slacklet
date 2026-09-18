package com.scooter.slackwear.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

private val SlackColorScheme = ColorScheme(
    primary = SlackTokens.SkyBlue,
    onPrimary = SlackTokens.OnSkyBlue,
    primaryContainer = SlackTokens.SkyBlueWash,
    onPrimaryContainer = SlackTokens.ContentPrimary,

    secondary = SlackTokens.LilypadGreen,
    onSecondary = SlackTokens.ContentPrimary,

    surfaceContainerLow = SlackTokens.BaseSecondary,
    surfaceContainer = SlackTokens.ContainerPrimary,
    surfaceContainerHigh = SlackTokens.ContainerElevated,

    background = SlackTokens.BasePrimary,
    onBackground = SlackTokens.ContentPrimary,
    onSurface = SlackTokens.ContentPrimary,
    onSurfaceVariant = SlackTokens.ContentSecondary,

    error = SlackTokens.RaspberryRed,
    onError = SlackTokens.ContentPrimary,

    outline = SlackTokens.ContentTertiary,
    outlineVariant = SlackTokens.Divider,
)

@Composable
fun SlackWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SlackColorScheme,
        typography = SlackWearTypography,
        content = content,
    )
}
