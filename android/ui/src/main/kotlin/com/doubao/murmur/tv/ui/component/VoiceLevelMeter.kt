package com.doubao.murmur.tv.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.doubao.murmur.tv.ui.theme.MurmurColors
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A 7-bar level meter for the live microphone signal.
 *
 * Behaviour that matters on TV:
 * - It is **display only**. It is not focusable and cannot be clicked; a viewer who lands focus
 *   on a decoration loses orientation.
 * - `active = false` drives every bar to zero instead of freezing the last frame, so "recording
 *   stopped" is unambiguous even if the viewer looked away.
 * - Bars are weighted so the middle ones are taller (a symmetric meter reads as "sound" from a
 *   distance far better than a flat row).
 * - Fully cleared semantics: the meter is redundant with the "正在聆听" status chip, so it must
 *   not add seven unlabelled nodes to TalkBack.
 *
 * @param level microphone amplitude, expected in `0f..1f` **linear RMS** (that is what
 *   `PcmRecorder.amplitude` reports). Speech at TV distance sits around 0.05-0.2, so a raw mapping
 *   would leave the meter visually dead; [applyMeterCurve] expands that range.
 * @param active whether capture is running right now.
 */
@Composable
fun VoiceLevelMeter(
    level: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 7,
) {
    val safeLevel = if (active) applyMeterCurve(level) else 0f
    Row(
        modifier = modifier
            .clearAndSetSemantics { }
            .height(48.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val mid = (barCount - 1) / 2f
        repeat(barCount) { index ->
            // 1.0 at the centre bar, ~0.45 at the outermost ones.
            val shapeWeight = 1f - (abs(index - mid) / (mid.coerceAtLeast(1f))) * 0.55f
            val target = (safeLevel * shapeWeight).coerceIn(0f, 1f)
            val animated by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(durationMillis = 90),
                label = "voiceBar$index",
            )
            val barHeight = 6.dp + (36.dp * animated)
            Box(
                Modifier
                    .width(10.dp)
                    .height(barHeight)
                    .background(
                        color = if (active) {
                            MurmurColors.Listening.copy(alpha = 0.45f + 0.55f * animated)
                        } else {
                            MurmurColors.OnSurfaceMuted.copy(alpha = 0.25f)
                        },
                        shape = RoundedCornerShape(5.dp),
                    ),
            )
        }
    }
}

/**
 * Maps linear RMS to something a viewer can read from the sofa.
 *
 * A volume meter is logarithmic by convention; `sqrt(rms * 4)` is the cheap stand-in that expands
 * exactly the band normal speech falls into (0.05 → 0.45, 0.2 → 0.89) while still saturating at 1
 * instead of clipping into a permanently full bar.
 */
private fun applyMeterCurve(level: Float): Float =
    sqrt((level.coerceIn(0f, 1f) * METER_GAIN).coerceIn(0f, 1f))

private const val METER_GAIN = 4f
