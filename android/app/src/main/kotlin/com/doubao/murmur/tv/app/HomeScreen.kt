package com.doubao.murmur.tv.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
// `androidx.tv.material3` does not ship an AlertDialog (tv-material 1.0.0 has no such component),
// so the one dialog in the app stays on material3 and takes its colours from the material3 theme
// domain that MurmurTvTheme nests inside the tv-material theme. Text/Surface, by contrast, must come
// from tv-material: they read `LocalColorScheme` of the tv theme, and a material3 Text would fall
// back to the light material3 scheme - dark text on our near-black TV background.
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.doubao.murmur.tv.app.ime.ImeHelper
import com.doubao.murmur.tv.ui.component.TvActionButton
import com.doubao.murmur.tv.ui.component.TvChipTone
import com.doubao.murmur.tv.ui.component.TvFocusableSurface
import com.doubao.murmur.tv.ui.component.TvStatusChip
import com.doubao.murmur.tv.ui.theme.MurmurColors
import com.doubao.murmur.tv.ui.theme.TvDimens

/**
 * Everything the home screen renders, captured as one immutable snapshot.
 *
 * The launcher re-probes on every `onResume` because the user routinely leaves for the system
 * settings app to grant the microphone permission or to enable the keyboard, then comes back. All
 * fields are plain booleans so the snapshot can be compared cheaply by Compose.
 */
data class HomeUiState(
    val loggedIn: Boolean = false,
    val micGranted: Boolean = false,
    val micAvailable: Boolean = true,
    val imeEnabled: Boolean = false,
    val imeSelected: Boolean = false,
    val loading: Boolean = true,
) {

    /** Steps are login, microphone, keyboard - the three things without which nothing works. */
    val completedSteps: Int
        get() = listOf(loggedIn, micGranted, imeEnabled && imeSelected).count { it }

    val remainingSteps: Int
        get() = TOTAL_STEPS - completedSteps

    val ready: Boolean
        get() = remainingSteps == 0

    private companion object {
        const val TOTAL_STEPS = 3
    }
}

/**
 * One-screen TV launcher.
 *
 * Deliberately not a scrolling list: a 10-foot UI is read at a distance, and a vertical list of
 * cards forces the user to scroll with a remote before they can even see what is missing. Two
 * columns - status on the left, actions on the right - keep everything visible at once and give
 * the D-pad a predictable left/right path.
 *
 * The composable is pure: no Android services, no coroutines, no permissions. [MainActivity] owns
 * all of that and only hands down a snapshot plus callbacks. The one exception is the about
 * dialog, which is static text and would not justify another activity.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onLogin: () -> Unit,
    onMicPermission: () -> Unit,
    onImeAction: () -> Unit,
    onDiagnostics: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAbout by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MurmurColors.Background)
            // 5% overscan safe area, see docs/tv-adaptation.md section 6.1.
            .padding(TvDimens.overscanHorizontal, TvDimens.overscanVertical),
    ) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            StatusColumn(
                state = state,
                onLogin = onLogin,
                onMicPermission = onMicPermission,
                onImeAction = onImeAction,
                modifier = Modifier.fillMaxHeight().weight(1.35f),
            )
            Spacer(modifier = Modifier.width(TvDimens.keyGap * 4))
            ActionColumn(
                state = state,
                onLogin = onLogin,
                onMicPermission = onMicPermission,
                onImeAction = onImeAction,
                onDiagnostics = onDiagnostics,
                onAbout = { showAbout = true },
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxHeight().weight(1f),
            )
        }
        VersionFooter()

        if (showAbout) {
            AboutDialog(
                onDismiss = { showAbout = false },
            )
        }
    }
}

@Composable
private fun StatusColumn(
    state: HomeUiState,
    onLogin: () -> Unit,
    onMicPermission: () -> Unit,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.main_title),
            color = MurmurColors.OnBackground,
            fontSize = 38.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.main_subtitle),
            color = MurmurColors.OnSurfaceMuted,
            fontSize = 17.sp,
        )
        Spacer(modifier = Modifier.height(18.dp))

        // While the first probe is running, claiming "not configured" would be a lie.
        when {
            state.loading -> TvStatusChip(
                text = stringResource(R.string.login_progress),
                tone = TvChipTone.Neutral,
            )
            state.ready -> TvStatusChip(
                text = stringResource(R.string.home_all_ready),
                tone = TvChipTone.Active,
            )
            else -> TvStatusChip(
                text = stringResource(R.string.home_remaining_steps, state.remainingSteps),
                tone = TvChipTone.Warning,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        StatusCard(
            done = state.loggedIn,
            title = stringResource(R.string.login_title),
            stateText = if (state.loggedIn) {
                stringResource(R.string.login_success)
            } else {
                stringResource(R.string.state_todo)
            },
            actionText = if (state.loggedIn) null else stringResource(R.string.main_action_login),
            onAction = onLogin,
        )
        Spacer(modifier = Modifier.height(TvDimens.keyGap))
        StatusCard(
            done = state.micGranted && state.micAvailable,
            title = stringResource(R.string.mic_title),
            stateText = when {
                !state.micAvailable -> stringResource(R.string.mic_state_missing)
                state.micGranted -> stringResource(R.string.mic_state_granted)
                else -> stringResource(R.string.mic_state_not_granted)
            },
            actionText = if (state.micGranted) null else stringResource(R.string.mic_action_grant),
            onAction = onMicPermission,
        )
        Spacer(modifier = Modifier.height(TvDimens.keyGap))
        StatusCard(
            done = state.imeEnabled && state.imeSelected,
            title = stringResource(R.string.enable_ime_title),
            stateText = when {
                !state.imeEnabled -> stringResource(R.string.ime_state_off)
                !state.imeSelected -> stringResource(R.string.ime_state_not_selected)
                else -> stringResource(R.string.enable_ime_state_enabled)
            },
            actionText = when {
                !state.imeEnabled -> stringResource(R.string.enable_ime_action_open_settings)
                !state.imeSelected -> stringResource(R.string.ime_action_pick)
                else -> null
            },
            onAction = onImeAction,
        )
    }
}

/**
 * A single readiness row: colour dot, what it is, what state it is in, and - only while it is not
 * finished - the button that fixes it.
 *
 * The button disappears once the step is done, which keeps the focus path short: a finished step
 * must not compete with the remaining work. The container itself is not focusable, so the D-pad
 * only ever stops on actionable controls.
 */
@Composable
private fun StatusCard(
    done: Boolean,
    title: String,
    stateText: String,
    actionText: String?,
    onAction: () -> Unit,
) {
    // A static, non-clickable card: tv-material's `Surface` in 1.0.0 takes a `SurfaceColors`
    // object (not a plain `color`), so a plain Box with a background shape is simpler and exact.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MurmurColors.Surface, RoundedCornerShape(TvDimens.cornerLarge)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (done) MurmurColors.Listening else MurmurColors.Warning),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MurmurColors.OnSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stateText,
                    color = if (done) MurmurColors.Listening else MurmurColors.OnSurfaceMuted,
                    fontSize = 14.sp,
                )
            }
            if (actionText != null) {
                Spacer(modifier = Modifier.width(TvDimens.keyGap))
                TvActionButton(text = actionText, onClick = onAction)
            }
        }
    }
}

@Composable
private fun ActionColumn(
    state: HomeUiState,
    onLogin: () -> Unit,
    onMicPermission: () -> Unit,
    onImeAction: () -> Unit,
    onDiagnostics: () -> Unit,
    onAbout: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_section_actions),
            color = MurmurColors.OnSurfaceMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(6.dp))
        ActionEntry(stringResource(R.string.main_action_login), onLogin)
        ActionEntry(stringResource(R.string.main_action_mic), onMicPermission)
        ActionEntry(stringResource(R.string.main_action_enable_ime), onImeAction)
        ActionEntry(stringResource(R.string.main_action_diagnostics), onDiagnostics)
        ActionEntry(stringResource(R.string.main_action_about), onAbout)
        ActionEntry(stringResource(R.string.action_refresh), onRefresh)

        Spacer(modifier = Modifier.weight(1f))

        // Boxes without a keyboard settings screen are common; surfacing the two adb lines here is
        // often faster than talking a user through a hidden OEM menu.
        Text(
            text = stringResource(R.string.ime_adb_hint_title),
            color = MurmurColors.OnSurfaceMuted,
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = ImeHelper.adbHint(),
            color = MurmurColors.OnSurfaceMuted.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (!state.imeEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.enable_ime_description),
                color = MurmurColors.OnSurfaceMuted.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ActionEntry(label: String, onClick: () -> Unit) {
    TvFocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = TvDimens.keyGap / 2),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = MurmurColors.OnSurface, fontSize = 17.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "\u203A", color = MurmurColors.OnSurfaceMuted, fontSize = 17.sp)
        }
    }
}

@Composable
private fun VersionFooter() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = stringResource(
                R.string.home_version_line,
                BuildConfig.VERSION_NAME,
                BuildConfig.GIT_SHA,
                BuildConfig.BUILD_CHANNEL,
            ),
            color = MurmurColors.OnSurfaceMuted.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MurmurColors.Surface,
        title = {
            Text(
                text = stringResource(R.string.about_title),
                color = MurmurColors.OnSurface,
                fontSize = 22.sp,
            )
        },
        text = {
            Text(
                text = stringResource(
                    R.string.about_body,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.GIT_SHA,
                ),
                color = MurmurColors.OnSurfaceMuted,
                fontSize = 15.sp,
            )
        },
        confirmButton = {
            TvActionButton(
                text = stringResource(R.string.action_close),
                onClick = onDismiss,
            )
        },
    )
}
