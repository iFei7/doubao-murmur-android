package com.doubao.murmur.tv.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.doubao.murmur.tv.ui.theme.MurmurColors
import com.doubao.murmur.tv.ui.theme.TvDimens

/**
 * The transcript card: shows what the recogniser currently believes it heard.
 *
 * Three TV-specific decisions:
 * 1. **Fixed height, internal scroll.** The recogniser sends full-text snapshots (every message
 *    replaces the whole sentence), so the text grows to the right while the user speaks. If the
 *    card grew, the IME window would resize mid-utterance and the whole keyboard would jump
 *    under the viewer's eyes. The height is therefore locked to ~3 lines and the content
 *    auto-scrolls to the tail — the newest words are always the visible ones.
 * 2. **`liveRegion = Polite`.** With TalkBack on, a screen reader would otherwise announce nothing
 *    while text streams in, or announce every partial and talk over itself. `Polite` lets the
 *    accessibility service batch the updates and speak the delta when the user pauses.
 * 3. **The card is not focusable.** It is a display surface; stealing D-pad focus here would make
 *    the keyboard feel broken.
 *
 * @param text the current transcript (may be empty).
 * @param placeholder shown when [text] is empty.
 * @param isListening drives the recording dot; when false and [text] is empty the card reads as
 *   "ready", not as "still listening".
 */
@Composable
fun TranscriptPreview(
    text: String,
    placeholder: String,
    isListening: Boolean,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(text) {
        if (text.isEmpty()) {
            scrollState.scrollTo(0)
        } else {
            // Wait one frame so the new text has been laid out and `maxValue` is up to date,
            // otherwise we would always scroll to the *previous* bottom.
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Box(
        modifier = modifier
            .height(TvDimens.transcriptHeight)
            .clip(RoundedCornerShape(TvDimens.cornerLarge))
            .background(MurmurColors.SurfaceElevated)
            .padding(horizontal = TvDimens.contentPadding, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        if (text.isEmpty()) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isListening) {
                    RecordingDot()
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MurmurColors.OnSurfaceMuted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Column(modifier = Modifier.verticalScroll(scrollState)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MurmurColors.OnBackground,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Soft pulsing dot: a static dot is invisible in peripheral vision from the sofa, a pulsing one
 * is noticed. Decorative only, cleared from the semantics tree via the parent's live region.
 */
@Composable
private fun RecordingDot() {
    val transition = rememberInfiniteTransition(label = "recordingDot")
    val pulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recordingDotAlpha",
    )
    Box(
        Modifier
            .size(12.dp)
            .alpha(pulse)
            .clip(CircleShape)
            .background(MurmurColors.Listening),
    )
}
