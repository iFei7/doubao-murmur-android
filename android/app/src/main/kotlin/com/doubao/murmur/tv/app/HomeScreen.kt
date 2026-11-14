package com.doubao.murmur.tv.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.doubao.murmur.tv.ui.theme.LocalDimens
import com.doubao.murmur.tv.ui.theme.MurmurColors

/**
 * Everything the home screen renders, captured as one immutable snapshot.
 *
 * The launcher re-probes on every `onResume` (throttled by [MainActivity]) because the user
 * routinely leaves for the system settings app to grant the microphone permission or to enable
 * the keyboard, then comes back. All fields are plain booleans so the snapshot can be compared
 * cheaply by Compose.
 */
data class HomeUiState(
    val loggedIn: Boolean = false,
    val micGranted: Boolean = false,
    val micAvailable: Boolean = true,
    val imeEnabled: Boolean = false,
    val imeSelected: Boolean = false,
    val loading: Boolean = true,
) {
    /** Ready only when login, microphone and the keyboard setup are all complete. */
    val ready: Boolean
        get() = loggedIn && micGranted && micAvailable && imeEnabled && imeSelected
}

/**
 * Phone home screen: either a three step setup guide or a "ready" confirmation.
 *
 * The composable is pure: no Android services, no coroutines, no permissions. [MainActivity] owns
 * all of that and only hands down a snapshot plus callbacks.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onLogin: () -> Unit,
    onMicPermission: () -> Unit,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current

    if (state.ready) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MurmurColors.Background)
                .safeDrawingPadding()
                .padding(horizontal = dimens.contentEdge)
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            ReadyHero()
            Spacer(modifier = Modifier.height(32.dp))
            ReadyListRow(
                label = stringResource(R.string.home_row_settings),
                onClick = onImeAction,
            )
            Spacer(modifier = Modifier.height(dimens.keyGap))
            ReadyListRow(
                label = stringResource(R.string.home_row_login_status) +
                    " · " + stringResource(R.string.login_success),
                onClick = onLogin,
            )
            Spacer(modifier = Modifier.weight(1f))
            VersionFooter()
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MurmurColors.Background)
                .safeDrawingPadding()
                .padding(horizontal = dimens.contentEdge, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.main_title),
                color = MurmurColors.OnBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_subtitle_setup),
                color = MurmurColors.OnSurfaceMuted,
                fontSize = 13.sp,
            )
            Spacer(modifier = Modifier.height(20.dp))

            // While the first probe is running the states are unknown; showing "not done" would
            // be a lie, so the cards wait for the snapshot.
            if (!state.loading) {
                StepCard(
                    index = "1",
                    title = stringResource(R.string.home_step_login_title),
                    description = stringResource(R.string.home_step_login_desc),
                    done = state.loggedIn,
                    actionText = stringResource(R.string.home_step_login_action),
                    onClick = onLogin,
                )
                Spacer(modifier = Modifier.height(dimens.keyGap))
                StepCard(
                    index = "2",
                    title = stringResource(R.string.home_step_mic_title),
                    description = stringResource(R.string.home_step_mic_desc),
                    done = state.micGranted && state.micAvailable,
                    actionText = stringResource(R.string.home_step_mic_action),
                    onClick = onMicPermission,
                )
                Spacer(modifier = Modifier.height(dimens.keyGap))
                StepCard(
                    index = "3",
                    title = stringResource(R.string.home_step_ime_title),
                    description = stringResource(R.string.home_step_ime_desc),
                    done = state.imeEnabled && state.imeSelected,
                    actionText = stringResource(R.string.home_step_ime_action),
                    onClick = onImeAction,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            VersionFooter()
        }
    }
}

/** One setup step: numbered badge on the left, texts in the middle, action on the right. */
@Composable
private fun StepCard(
    index: String,
    title: String,
    description: String,
    done: Boolean,
    actionText: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MurmurColors.Surface)
            .border(0.5.dp, MurmurColors.Outline, RoundedCornerShape(10.dp))
            .clickable(enabled = !done, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MurmurColors.Accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MurmurColors.Listening,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text(
                        text = index,
                        color = MurmurColors.Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MurmurColors.OnSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    color = MurmurColors.OnSurfaceMuted,
                    fontSize = 12.sp,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (done) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MurmurColors.Listening,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text(
                    text = actionText,
                    color = MurmurColors.Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** Centered confirmation shown once every setup step is complete. */
@Composable
private fun ReadyHero() {
    val dimens = LocalDimens.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(dimens.micButtonSize)
                .clip(CircleShape)
                .background(MurmurColors.Listening.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = MurmurColors.Listening,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.home_ready_title),
            color = MurmurColors.OnBackground,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_ready_hint),
            color = MurmurColors.OnSurfaceMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** A simple list entry of the ready state: label on the left, chevron on the right. */
@Composable
private fun ReadyListRow(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MurmurColors.Surface)
            .border(0.5.dp, MurmurColors.Outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = MurmurColors.OnSurface,
                fontSize = 15.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "\u203A",
                color = MurmurColors.OnSurfaceMuted,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun VersionFooter() {
    Text(
        text = stringResource(R.string.home_version, BuildConfig.VERSION_NAME),
        color = MurmurColors.OnSurfaceMuted.copy(alpha = 0.6f),
        fontSize = 12.sp,
    )
}
