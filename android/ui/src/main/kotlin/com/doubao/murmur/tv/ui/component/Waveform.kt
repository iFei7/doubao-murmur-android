package com.doubao.murmur.tv.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.doubao.murmur.tv.ui.theme.MurmurColors

/** Resting heights of the 7 bars, symmetric around the centre bar. */
private val WAVEFORM_BASE_HEIGHTS = listOf(10.dp, 18.dp, 26.dp, 32.dp, 26.dp, 18.dp, 10.dp)

/** Staggered phase offsets (ms) so neighbouring bars never move in lockstep. */
private const val WAVEFORM_PHASE_STEP_MS = 90

/**
 * Voice level waveform: 7 rounded vertical bars whose heights track [level] (0..1, already
 * smoothed by the controller's 60 ms amplitude poll) plus a per-bar phase-shifted pulse.
 *
 * ## Performance contract
 * The only animation here is the `rememberInfiniteTransition` below, which runs **exactly while
 * this composable is in the composition**. The caller must therefore compose it only during
 * listening (`if (state.isListening) Waveform(level = state.level)`), which the IME panel does:
 * in Idle/Committed states no animation of any kind is running. This is the fix for the old
 * keyboard's always-on pulse, which kept the whole device janky after installation.
 */
@Composable
fun Waveform(level: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "waveform")
    // Amplitude factor from the live mic level: quiet input shrinks the whole row instead of
    // stopping it, so the bars still signal "recording".
    val levelFactor = 0.25f + 0.75f * level.coerceIn(0f, 1f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in WAVEFORM_BASE_HEIGHTS.indices) {
            val phase by transition.animateFloat(
                initialValue = 0.45f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * WAVEFORM_PHASE_STEP_MS),
                ),
                label = "waveBar$index",
            )
            val height: Dp = (WAVEFORM_BASE_HEIGHTS[index] * phase * levelFactor).coerceAtLeast(5.dp)
            Box(
                modifier = Modifier
                    .size(width = 5.dp, height = height)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MurmurColors.Listening),
            )
        }
    }
}
