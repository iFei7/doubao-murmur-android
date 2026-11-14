package com.doubao.murmur.tv.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doubao.murmur.tv.ui.component.Waveform
import com.doubao.murmur.tv.ui.theme.MurmurColors
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * Press-and-hold at or above this duration is push-to-talk: releasing commits the final text.
 * A quicker tap (< 400 ms) starts a hands-free latched session instead — the recording keeps
 * running until the mic button is tapped again, or 「完成」 is pressed, or the controller's
 * 30 s hard cap fires. 400 ms is the Android long-press constant.
 */
private const val MIC_LATCH_THRESHOLD_MS = 400L

/** Height of the single-row idle panel content (per the phone panel spec). */
private val IDLE_ROW_HEIGHT = 72.dp

/** Diameter of the round microphone button. */
private val MIC_BUTTON_SIZE = 56.dp

/**
 * The phone keyboard panel: a single form factor, no TV branch.
 *
 * Structure is deliberately **state-stable around the mic button**: the bottom row is always the
 * same [Row] with the mic button in the centre slot, and only the flanking slots and the content
 * above the row change with [ImeUiState]. If the mic node were torn down mid-press (Idle row ->
 * expanded recording panel), `pointerInput` would cancel the gesture and push-to-talk could never
 * survive the Idle -> Connecting recomposition.
 *
 * Panel states (per the spec):
 * - **Idle**: one 72 dp row — switch-IME icon | mic | settings icon.
 * - **Recording** (Connecting/Listening/Transcribing): live transcript above the row, waveform,
 *   and 取消/完成 flanking the mic. Latched (hands-free) sessions show 「免提中」.
 * - **Committed**: a single 「已上屏」 line above the row; the panel hides itself after 800 ms
 *   (说完即走). Pressing the mic again during that window cancels the auto-hide.
 *
 * Interaction model on the mic button:
 * - Press -> `onMicDown` (the controller waits through Connecting; the host field shows live
 *   composing text via the existing `TextCommitter` mechanism).
 * - Release >= 400 ms -> `onMicUp`: final result is committed.
 * - Release < 400 ms -> hands-free latch: recording continues; the next mic tap ends it.
 */
@Composable
fun MurmurKeyboardScreen(
    state: ImeUiState,
    onMicDown: () -> Unit,
    onMicUp: () -> Unit,
    onCancel: () -> Unit,
    onHide: () -> Unit,
    onSwitchSystemIme: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestLogin: () -> Unit,
    onRequestMicPermission: () -> Unit,
) {
    // Hands-free latch: set by a quick tap on the mic, cleared whenever the utterance ends
    // (commit, cancel, failure — anything that leaves the capturing states).
    var latched by remember { mutableStateOf(false) }
    LaunchedEffect(state.isCapturing) {
        if (!state.isCapturing) latched = false
    }

    // Auto-hide after Committed. Pressing the mic during the window flips [pendingHide] and the
    // new session's Connecting state restarts this effect, cancelling the delay.
    var pendingHide by remember { mutableStateOf(true) }
    LaunchedEffect(state.state) {
        if (state.state is ImeState.Committed) {
            pendingHide = true
            delay(800)
            if (pendingHide) onHide()
        }
    }

    val pressAt = remember { mutableStateOf(0L) }
    val handleMicPress: () -> Unit = {
        pressAt.value = SystemClock.elapsedRealtime()
        pendingHide = false
        onMicDown()
    }
    val handleMicRelease: (Boolean) -> Unit = { released ->
        if (released) {
            val heldMs = SystemClock.elapsedRealtime() - pressAt.value
            if (state.isCapturing) {
                when {
                    latched -> {
                        latched = false
                        onMicUp()
                    }
                    heldMs < MIC_LATCH_THRESHOLD_MS -> latched = true
                    else -> onMicUp()
                }
            } else {
                onMicUp()
            }
        }
        // !released: the gesture was cancelled (node torn down mid-hold). The recording keeps
        // running; BACK or the panel buttons remain as exits.
    }

    val recording = state.isCapturing || state.state is ImeState.Transcribing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(MurmurColors.Surface)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        state.errorMessage?.let { ErrorLine(it) }

        when {
            state.state is ImeState.Committed -> CommittedLine()
            recording -> RecordingContent(state = state, latched = latched)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (recording) 64.dp else IDLE_ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left slot: cancel while recording, switch-IME while idle.
            if (recording) {
                TextButton(onClick = onCancel) {
                    Text(
                        text = stringResource(R.string.ime_action_cancel),
                        color = MurmurColors.OnSurfaceMuted,
                        fontSize = 15.sp,
                    )
                }
            } else {
                IconButton(onClick = onSwitchSystemIme) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardAlt,
                        contentDescription = stringResource(R.string.ime_settings_action_pick_ime),
                        tint = MurmurColors.OnSurfaceMuted,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Centre slot: the mic button keeps the same call position in every state, so its
            // node — and the in-flight press gesture — survives Idle -> recording recomposition.
            if (state.needsLogin) {
                ActionPill(
                    label = stringResource(R.string.ime_action_login),
                    onClick = onRequestLogin,
                )
            } else if (state.needsMicPermission) {
                ActionPill(
                    label = stringResource(R.string.mic_action_grant),
                    onClick = onRequestMicPermission,
                )
            } else {
                MicButton(
                    state = state,
                    onPress = handleMicPress,
                    onRelease = handleMicRelease,
                )
            }

            Spacer(Modifier.weight(1f))

            // Right slot: finish while recording, settings while idle.
            if (recording) {
                TextButton(onClick = onMicUp) {
                    Text(
                        text = stringResource(R.string.ime_action_finish),
                        color = MurmurColors.Listening,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.ime_action_settings),
                        tint = MurmurColors.OnSurfaceMuted,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/** Single red error line at the top of the panel; cleared by the controller on the next session. */
@Composable
private fun ErrorLine(message: String) {
    Text(
        text = message,
        color = MurmurColors.Error,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** The expanded recording content: live transcript pinned to its tail, status, waveform. */
@Composable
private fun RecordingContent(state: ImeUiState, latched: Boolean) {
    val scroll = rememberScrollState()
    // 跟随尾部: as the partial grows past the visible box, keep the latest words on screen.
    LaunchedEffect(state.preview) {
        if (scroll.maxValue > 0) scroll.animateScrollTo(scroll.maxValue)
    }
    val statusText = when {
        state.state is ImeState.Connecting -> stringResource(R.string.ime_state_connecting)
        latched -> stringResource(R.string.ime_status_hands_free)
        state.isListening -> stringResource(R.string.ime_state_listening)
        else -> stringResource(R.string.ime_state_transcribing)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp, max = 96.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                text = state.preview,
                color = MurmurColors.OnSurface,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(vertical = 4.dp),
            )
        }
        Text(
            text = statusText,
            color = if (state.isListening) MurmurColors.Listening else MurmurColors.OnSurfaceMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Box(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Animation lives only inside Waveform, composed exclusively while listening:
            // the Idle / Committed panel runs zero animations (the old always-on pulse was the
            // post-install jank root cause).
            if (state.isListening) {
                Waveform(level = state.level)
            }
        }
    }
}

/** The 「已上屏 ✓」 line shown after a successful commit, above the idle row. */
@Composable
private fun CommittedLine() {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MurmurColors.Listening,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.ime_state_committed),
            color = MurmurColors.Listening,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Rounded pill for the "cannot talk yet" states (sign in / grant microphone). */
@Composable
private fun ActionPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MurmurColors.Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The round push-to-talk button.
 *
 * The press handler is a raw `pointerInput` + `detectTapGestures(onPress)` so that the down/up
 * pair maps exactly onto `beginListening`/`endListening`. `tryAwaitRelease` distinguishes a real
 * release from a cancelled gesture (view torn down mid-hold): only a real release runs the
 * release semantics. The callbacks are read through [rememberUpdatedState] because
 * `pointerInput(Unit)` does not restart on recomposition and would otherwise capture stale state.
 */
@Composable
private fun MicButton(
    state: ImeUiState,
    onPress: () -> Unit,
    onRelease: (Boolean) -> Unit,
) {
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    Box(
        modifier = Modifier
            .size(MIC_BUTTON_SIZE)
            .clip(CircleShape)
            .background(if (state.isListening) MurmurColors.Listening else MurmurColors.Accent)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        currentOnPress()
                        var released = false
                        try {
                            released = tryAwaitRelease()
                        } finally {
                            currentOnRelease(released)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = stringResource(R.string.ime_action_hold_to_talk),
            tint = Color.White,
            modifier = Modifier.size(26.dp),
        )
    }
}
