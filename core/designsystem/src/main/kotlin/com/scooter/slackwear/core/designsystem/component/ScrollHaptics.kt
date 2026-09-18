package com.scooter.slackwear.core.designsystem.component

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

class ScrollHaptics(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    }.getOrNull()?.takeIf { it.hasVibrator() }

    private val detent: VibrationEffect? = runCatching {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    }.getOrNull()

    fun detent() {
        val vibrator = vibrator ?: return
        val effect = detent ?: return
        vibrator.vibrate(effect)
    }
}

@Composable
fun rememberScrollHaptics(): ScrollHaptics {
    val context = LocalContext.current
    return remember(context) { ScrollHaptics(context.applicationContext) }
}

@Composable
fun HapticScrollEffect(state: TransformingLazyColumnState) {
    val haptics = rememberScrollHaptics()
    LaunchedEffect(state, haptics) {
        snapshotFlow { state.anchorItemIndex }
            .distinctUntilChanged()
            .drop(1)
            .collect { haptics.detent() }
    }
}
