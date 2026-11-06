package com.doubao.murmur.tv.ime

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.doubao.murmur.tv.ui.component.KeyHintBar
import com.doubao.murmur.tv.ui.component.TranscriptPreview
import com.doubao.murmur.tv.ui.component.TvActionButton
import com.doubao.murmur.tv.ui.component.TvChipTone
import com.doubao.murmur.tv.ui.component.TvStatusChip
import com.doubao.murmur.tv.ui.component.VoiceLevelMeter
import com.doubao.murmur.tv.ui.theme.MurmurColors
import com.doubao.murmur.tv.ui.theme.TvDimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long OK must be held before the press counts as "talking" instead of "clicking".
 *
 * 400 ms is the Android long-press constant: shorter and a normal, slightly slow button press
 * starts a recording the viewer did not want; longer and push-to-talk feels laggy.
 */
private const val LONG_PRESS_THRESHOLD_MS = 400L

/**
 * The entire keyboard. Pure UI: no service, no repository, no microphone — every side effect is
 * delegated to a callback so that this screen can be rendered in a preview or a screenshot test.
 *
 * ## Layout
 * ```
 * ┌ status chips ───────────────────────────────────────── [设置] ┐
 * │ ┌ transcript (3 lines, auto-scroll) ┐ ┌ talk ┐ ┌ 清空 换行 ┐ │
 * │ │                                  │ │  ◉   │ │ 删除 收起 │ │
 * │ │                                  │ │ ▁▃▅▇  │ │ 系统键盘 ─┤ │
 * │ └──────────────────────────────────┘ └──────┘ └───────────┘ │
 * └ 长按 OK 说话 · BACK 收起 · SEARCH 开始/结束 ──────────────────┘
 * ```
 * The panel is bottom-anchored and about half the screen tall: a TV host app keeps its content in
 * the upper two thirds, so a heavier keyboard would cover the very thing the user is typing into.
 *
 * ## Focus
 * - Default focus is the primary control (talk / sign in / grant microphone). It is the only thing
 *   a viewer wants 95% of the time, and it means "press OK twice" never happens by accident.
 * - The transcript is not focusable, so DPAD-Up/Left from the talk button reaches the settings
 *   button in the header, and DPAD-Right enters the action grid.
 * - Disabled items (清空/换行 while recording) are marked `canFocus = false` by
 *   [TvActionButton]'s `enabled` flag, so the D-pad skips them instead of parking focus on a dead
 *   control and making the whole keyboard feel stuck.
 */
@Composable
fun MurmurKeyboardScreen(
    state: ImeUiState,
    onMicDown: () -> Unit,
    onMicUp: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onNewline: () -> Unit,
    onHide: () -> Unit,
    onSwitchSystemIme: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestLogin: () -> Unit,
    onRequestMicPermission: () -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }

    // Re-assert focus on the primary control whenever the primary control or the action grid
    // changes shape (talk <-> sign in <-> grant microphone, and 清空 <-> 取消 while recording).
    // A focused node that disappears takes the focus with it; on a TV that reads as "the remote
    // stopped working", so we put it back deterministically instead of relying on the framework's
    // focus search to recover on the next key press.
    LaunchedEffect(state.needsLogin, state.needsMicPermission, state.isCapturing) {
        runCatching { primaryFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MurmurColors.Background)
            .padding(
                start = TvDimens.overscanHorizontal,
                end = TvDimens.overscanHorizontal,
                top = 14.dp,
                bottom = TvDimens.overscanVertical,
            ),
    ) {
        StatusHeader(state = state, onOpenSettings = onOpenSettings)

        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                TranscriptPreview(
                    text = state.preview,
                    placeholder = stringResource(R.string.ime_preview_placeholder),
                    isListening = state.isListening,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorLine(state.errorMessage)
            }

            Spacer(Modifier.width(20.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(150.dp)) {
                PrimaryControl(
                    state = state,
                    focusRequester = primaryFocus,
                    onMicDown = onMicDown,
                    onMicUp = onMicUp,
                    onRequestLogin = onRequestLogin,
                    onRequestMicPermission = onRequestMicPermission,
                )
                Spacer(Modifier.height(8.dp))
                VoiceLevelMeter(level = state.level, active = state.isListening)
            }

            Spacer(Modifier.width(20.dp))

            ActionGrid(
                state = state,
                onCancel = onCancel,
                onClear = onClear,
                onDelete = onDelete,
                onNewline = onNewline,
                onHide = onHide,
                onSwitchSystemIme = onSwitchSystemIme,
            )
        }

        Spacer(Modifier.height(10.dp))

        // Hint wording follows the state: while recording, "how to start" is useless information
        // and "how to stop" is the only thing that matters.
        val hints = if (state.isCapturing) {
            listOf(
                stringResource(R.string.ime_hint_release_to_finish),
                stringResource(R.string.ime_hint_back_cancel),
            )
        } else {
            listOf(
                stringResource(R.string.ime_hint_hold_to_talk),
                stringResource(R.string.ime_hint_back_hide),
                stringResource(R.string.ime_hint_search_toggle),
                stringResource(R.string.ime_hint_menu_settings),
            )
        }
        KeyHintBar(hints = hints)
    }
}

@Composable
private fun StatusHeader(state: ImeUiState, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvDimens.keyGap),
    ) {
        if (state.statusChecked) {
            TvStatusChip(
                text = if (state.loggedIn) {
                    stringResource(R.string.login_success)
                } else {
                    stringResource(R.string.ime_status_not_signed_in)
                },
                tone = if (state.loggedIn) TvChipTone.Neutral else TvChipTone.Warning,
            )
            if (!state.micGranted) {
                TvStatusChip(
                    text = stringResource(R.string.ime_status_mic_denied),
                    tone = TvChipTone.Warning,
                )
            }
        }
        if (state.state !is ImeState.Idle) {
            // One chip carries the whole state machine: "连接中 / 正在聆听 / 识别中 / 已上屏".
            // It lives in the header rather than under the level meter so the panel stays short.
            TvStatusChip(
                text = statusLabel(state),
                tone = if (state.isListening) TvChipTone.Active else TvChipTone.Neutral,
            )
        }
        Spacer(Modifier.weight(1f))
        TvActionButton(
            text = stringResource(R.string.ime_action_settings),
            onClick = onOpenSettings,
            modifier = Modifier.height(TvDimens.actionButtonHeight),
            icon = Icons.Filled.Settings,
        )
    }
}

@Composable
private fun ErrorLine(message: String?) {
    if (message.isNullOrBlank()) return
    Text(
        text = message,
        style = MaterialTheme.typography.labelLarge,
        color = MurmurColors.Error,
        maxLines = 1,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * The primary control: it is one of three things depending on state, and exactly one of them is
 * on screen at a time so that [FocusRequester] always has a single target.
 */
@Composable
private fun PrimaryControl(
    state: ImeUiState,
    focusRequester: FocusRequester,
    onMicDown: () -> Unit,
    onMicUp: () -> Unit,
    onRequestLogin: () -> Unit,
    onRequestMicPermission: () -> Unit,
) {
    when {
        state.needsLogin -> PrimaryPill(
            label = stringResource(R.string.ime_action_login),
            icon = Icons.Filled.Login,
            onClick = onRequestLogin,
            focusRequester = focusRequester,
        )

        state.needsMicPermission -> PrimaryPill(
            label = stringResource(R.string.mic_action_grant),
            icon = Icons.Filled.MicOff,
            onClick = onRequestMicPermission,
            focusRequester = focusRequester,
        )

        else -> HoldToTalkButton(
            state = state,
            focusRequester = focusRequester,
            onMicDown = onMicDown,
            onMicUp = onMicUp,
        )
    }
}

/** Fallback primary control for the "cannot talk yet" states: a plain, wide action button. */
@Composable
private fun PrimaryPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
) {
    TvActionButton(
        text = label,
        onClick = onClick,
        modifier = Modifier
            .height(TvDimens.touchTargetMin)
            .focusRequester(focusRequester),
        icon = icon,
        containerColor = MurmurColors.Accent,
    )
}

/**
 * Push-to-talk button.
 *
 * Interaction model (identical for the remote and for a USB mouse):
 * - **Hold >= 400 ms** → talks while held, finishes on release. This is the primary path and the
 *   only one that works on every remote, because `KEYCODE_VOICE_ASSIST` is consumed by the system
 *   and never reaches an IME (see [DoubaoVoiceInputMethodService]).
 * - **Tap < 400 ms** → starts a latched session that ends on the next press. Gives the viewer a
 *   way to dictate a long sentence without holding the remote in the air.
 *
 * The delayed start cannot live in the key handler alone: `KeyEvent.repeatCount` is unreliable
 * across TV ROMs, so a coroutine armed on KeyDown and cancelled on KeyUp is used instead.
 */
@Composable
private fun HoldToTalkButton(
    state: ImeUiState,
    focusRequester: FocusRequester,
    onMicDown: () -> Unit,
    onMicUp: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var longPressStarted by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { holdJob?.cancel() }
    }

    fun startHold() {
        if (holding) return
        holding = true
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(LONG_PRESS_THRESHOLD_MS)
            if (holding) {
                longPressStarted = true
                onMicDown()
            }
        }
    }

    fun endHold() {
        if (!holding) return
        holding = false
        holdJob?.cancel()
        holdJob = null
        if (longPressStarted) {
            longPressStarted = false
            onMicUp()
        } else {
            // Short press: latch on, or release a previously latched session.
            if (state.isCapturing) onMicUp() else onMicDown()
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) TvDimens.focusScale else 1f,
        animationSpec = spring(),
        label = "talkButtonScale",
    )
    val pulse = rememberInfiniteTransition(label = "talkPulse")
    val recordingAlpha by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "talkPulseAlpha",
    )

    Box(
        modifier = Modifier
            .size(TvDimens.micButtonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (isFocused) {
                    Modifier.shadow(24.dp, CircleShape, clip = false, ambientColor = MurmurColors.Focus, spotColor = MurmurColors.Focus)
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .background(
                if (state.isListening) {
                    MurmurColors.Listening.copy(alpha = 0.18f)
                } else {
                    MurmurColors.SurfaceFocused
                },
            )
            .border(
                width = if (isFocused) TvDimens.focusBorder else 1.dp,
                color = when {
                    isFocused -> MurmurColors.Focus
                    state.isListening -> MurmurColors.Listening.copy(alpha = recordingAlpha)
                    else -> MurmurColors.Outline
                },
                shape = CircleShape,
            )
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event ->
                val isActivation = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (!isActivation) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        startHold()
                        true
                    }
                    KeyEventType.KeyUp -> {
                        endHold()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) {
                // Mouse / touchscreen: hold to talk, release to finish.
                detectTapGestures(
                    onPress = {
                        onMicDown()
                        tryAwaitRelease()
                        onMicUp()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (state.isListening) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = if (state.isListening) MurmurColors.Listening else MurmurColors.OnBackground,
        )
    }
}

@Composable
private fun ActionGrid(
    state: ImeUiState,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onNewline: () -> Unit,
    onHide: () -> Unit,
    onSwitchSystemIme: () -> Unit,
) {
    Column(
        modifier = Modifier.width(342.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // While capturing, "clear" is replaced by "cancel": the only useful destructive action
            // during an utterance is to throw it away, and it must be reachable without the remote
            // (the BACK key does the same thing).
            if (state.isCapturing) {
                TvActionButton(
                    text = stringResource(R.string.ime_action_cancel),
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(TvDimens.actionButtonHeight),
                    icon = Icons.Filled.Clear,
                )
            } else {
                TvActionButton(
                    text = stringResource(R.string.ime_action_clear),
                    onClick = onClear,
                    modifier = Modifier
                        .weight(1f)
                        .height(TvDimens.actionButtonHeight),
                    icon = Icons.Filled.Clear,
                )
            }
            TvActionButton(
                text = stringResource(R.string.ime_action_newline),
                onClick = onNewline,
                modifier = Modifier
                    .weight(1f)
                    .height(TvDimens.actionButtonHeight),
                icon = Icons.Filled.KeyboardReturn,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TvActionButton(
                text = stringResource(R.string.ime_action_delete),
                onClick = onDelete,
                modifier = Modifier
                    .weight(1f)
                    .height(TvDimens.actionButtonHeight),
                icon = Icons.Filled.Backspace,
            )
            TvActionButton(
                text = stringResource(R.string.ime_action_hide),
                onClick = onHide,
                modifier = Modifier
                    .weight(1f)
                    .height(TvDimens.actionButtonHeight),
                icon = Icons.Filled.KeyboardArrowDown,
            )
        }
        Row {
            TvActionButton(
                text = stringResource(R.string.ime_key_fallback_system),
                onClick = onSwitchSystemIme,
                modifier = Modifier
                    .weight(1f)
                    .height(TvDimens.actionButtonHeight),
                icon = Icons.Filled.SwapHoriz,
            )
        }
    }
}

@Composable
private fun statusLabel(state: ImeUiState): String = when (state.state) {
    ImeState.Idle -> stringResource(R.string.ime_state_idle)
    ImeState.Connecting -> stringResource(R.string.ime_state_connecting)
    ImeState.Listening -> stringResource(R.string.ime_state_listening)
    is ImeState.Transcribing -> stringResource(R.string.ime_state_transcribing)
    is ImeState.Committed -> stringResource(R.string.ime_state_committed)
}
