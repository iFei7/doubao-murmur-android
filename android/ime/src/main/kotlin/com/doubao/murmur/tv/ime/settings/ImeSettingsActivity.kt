package com.doubao.murmur.tv.ime.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Logout
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.doubao.murmur.tv.auth.EncryptedCredentialStore
import com.doubao.murmur.tv.auth.SessionRepository
import com.doubao.murmur.tv.ime.DoubaoVoiceInputMethodService
import com.doubao.murmur.tv.ime.R
import com.doubao.murmur.tv.ui.component.TvActionButton
import com.doubao.murmur.tv.ui.component.TvChipTone
import com.doubao.murmur.tv.ui.component.TvStatusChip
import com.doubao.murmur.tv.ui.theme.MurmurColors
import com.doubao.murmur.tv.ui.theme.MurmurTvTheme
import com.doubao.murmur.tv.ui.theme.TvDimens
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Settings screen of the input method.
 *
 * Reachable three ways, all of them remote friendly: the MENU key on the keyboard, the "设置"
 * button in the header, and the system's "input method settings" entry (declared as
 * `android:settingsActivity` in `res/xml/method.xml`).
 *
 * It exists mostly because two behaviours cannot be decided for the viewer up front: whether the
 * host editor tolerates a composing region, and how long the recogniser may take to produce a
 * final transcript on a slow network. Both are exposed here instead of being hidden in code.
 */
class ImeSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = ImePreferences(this)
        val sessionRepository = SessionRepository(EncryptedCredentialStore(this))

        setContent {
            MurmurTvTheme {
                ImeSettingsScreen(
                    preferences = preferences,
                    sessionRepository = sessionRepository,
                    lifecycleOwner = this@ImeSettingsActivity,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
private fun ImeSettingsScreen(
    preferences: ImePreferences,
    sessionRepository: SessionRepository,
    lifecycleOwner: LifecycleOwner,
    onBack: () -> Unit,
) {
    var composingEnabled by remember { mutableStateOf(preferences.composingEnabled) }
    var timeoutMs by remember { mutableStateOf(preferences.finalTimeoutMs) }
    var loggedIn by remember { mutableStateOf<Boolean?>(null) }
    var signOutDone by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loggedIn = runCatching { sessionRepository.isLoggedIn() }.getOrDefault(false)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = TvDimens.overscanHorizontal,
                end = TvDimens.overscanHorizontal,
                top = TvDimens.overscanVertical,
                bottom = TvDimens.overscanVertical,
            )
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(TvDimens.keyGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.ime_settings_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MurmurColors.OnBackground,
            )
            Spacer(Modifier.width(TvDimens.keyGap))
            when (loggedIn) {
                true -> TvStatusChip(
                    text = stringResource(R.string.login_success),
                    tone = TvChipTone.Neutral,
                )
                false -> TvStatusChip(
                    text = stringResource(R.string.ime_status_not_signed_in),
                    tone = TvChipTone.Warning,
                )
                null -> Unit
            }
            if (signOutDone) {
                TvStatusChip(
                    text = stringResource(R.string.ime_settings_sign_out_done),
                    tone = TvChipTone.Active,
                )
            }
        }

        SectionTitle(stringResource(R.string.ime_settings_section_system))
        SystemStatusSection(lifecycleOwner)

        SectionTitle(stringResource(R.string.ime_settings_section_commit))
        Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.keyGap)) {
            TvActionButton(
                text = stringResource(R.string.ime_settings_commit_composing),
                onClick = {
                    composingEnabled = true
                    preferences.composingEnabled = true
                },
                modifier = Modifier.height(TvDimens.touchTargetMin),
                selected = composingEnabled,
            )
            TvActionButton(
                text = stringResource(R.string.ime_settings_commit_direct),
                onClick = {
                    composingEnabled = false
                    preferences.composingEnabled = false
                },
                modifier = Modifier.height(TvDimens.touchTargetMin),
                selected = !composingEnabled,
            )
        }
        HintText(stringResource(R.string.ime_settings_commit_hint))

        SectionTitle(stringResource(R.string.ime_settings_section_timeout))
        Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.keyGap)) {
            ImePreferences.FINAL_TIMEOUT_OPTIONS_MS.forEach { optionMs ->
                TvActionButton(
                    text = stringResource(
                        R.string.ime_settings_timeout_seconds,
                        formatSeconds(optionMs),
                    ),
                    onClick = {
                        timeoutMs = optionMs
                        preferences.finalTimeoutMs = optionMs
                    },
                    modifier = Modifier.height(TvDimens.touchTargetMin),
                    selected = timeoutMs == optionMs,
                )
            }
        }
        HintText(stringResource(R.string.ime_settings_timeout_hint))

        SectionTitle(stringResource(R.string.ime_settings_section_privacy))
        HintText(stringResource(R.string.ime_settings_privacy_text))

        Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.keyGap)) {
            TvActionButton(
                text = stringResource(R.string.ime_settings_action_sign_out),
                onClick = {
                    scope.launch {
                        runCatching { sessionRepository.clear() }
                        loggedIn = false
                        signOutDone = true
                    }
                },
                modifier = Modifier.height(TvDimens.touchTargetMin),
                icon = Icons.Filled.Logout,
                enabled = loggedIn == true,
            )
            TvActionButton(
                text = stringResource(R.string.action_back),
                onClick = onBack,
                modifier = Modifier.height(TvDimens.touchTargetMin),
                icon = Icons.Filled.KeyboardArrowLeft,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MurmurColors.Accent,
    )
}

/** `1500 -> "1.5"`, `2000 -> "2"`. The unit is part of the localised format string. */
private fun formatSeconds(ms: Long): String =
    if (ms % 1000L == 0L) {
        (ms / 1000L).toString()
    } else {
        String.format(Locale.getDefault(), "%.1f", ms / 1000.0)
    }

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MurmurColors.OnSurfaceMuted,
    )
}

/**
 * "Is this keyboard enabled / selected?" plus the two actions that fix it.
 *
 * This section exists because `:app` routes its "启用语音输入法" entry point here: a viewer who has
 * not enabled the keyboard yet cannot see the keyboard itself, so this screen is the only place
 * where the two required system steps can be explained. Without it the entry point would dead-end.
 *
 * The state is re-read on every `ON_RESUME`, which is exactly when the viewer comes back from the
 * system settings screen or from the input-method picker.
 */
@Composable
private fun SystemStatusSection(lifecycleOwner: LifecycleOwner) {
    val system = rememberImeSystemStatus(lifecycleOwner)
    val context = LocalContext.current

    Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.keyGap)) {
        TvStatusChip(
            text = stringResource(
                if (system.enabled) {
                    R.string.ime_settings_ime_enabled
                } else {
                    R.string.ime_settings_ime_disabled
                },
            ),
            tone = if (system.enabled) TvChipTone.Active else TvChipTone.Warning,
        )
        TvStatusChip(
            text = stringResource(
                if (system.selected) {
                    R.string.ime_settings_ime_selected
                } else {
                    R.string.ime_settings_ime_not_selected
                },
            ),
            tone = if (system.selected) TvChipTone.Active else TvChipTone.Warning,
        )
    }

    // Only the missing step is offered: a dead "already done" button would just be another
    // focusable stop the viewer has to walk past.
    if (!system.enabled || !system.selected) {
        Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.keyGap)) {
            if (!system.enabled) {
                TvActionButton(
                    text = stringResource(R.string.ime_settings_action_enable_system),
                    onClick = { openSystemImeSettings(context) },
                    modifier = Modifier.height(TvDimens.touchTargetMin),
                )
            }
            if (!system.selected) {
                TvActionButton(
                    text = stringResource(R.string.ime_settings_action_pick_ime),
                    onClick = { showImePicker(context) },
                    modifier = Modifier.height(TvDimens.touchTargetMin),
                )
            }
        }
    }
}

/** Enabled/selected flags of *this* keyboard, re-read on every resume. */
private data class ImeSystemStatus(val enabled: Boolean, val selected: Boolean)

@Composable
private fun rememberImeSystemStatus(lifecycleOwner: LifecycleOwner): ImeSystemStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(readImeSystemStatus(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = readImeSystemStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return status
}

private fun readImeSystemStatus(context: Context): ImeSystemStatus {
    val self = ComponentName(context, DoubaoVoiceInputMethodService::class.java)
    val enabledList = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
    }.getOrNull()
    val enabled = enabledList
        .orEmpty()
        .split(':')
        .mapNotNull { ComponentName.unflattenFromString(it) }
        .any { it == self }

    val defaultId = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    }.getOrNull()
    val selected = ComponentName.unflattenFromString(defaultId.orEmpty()) == self

    return ImeSystemStatus(enabled = enabled, selected = selected)
}

/**
 * Opens the keyboard list of the system settings.
 *
 * `ACTION_INPUT_METHOD_SETTINGS` is missing on a fair number of TV ROMs (and on some it is
 * present but empty), so falling back to the root settings screen is the only other option that
 * exists on every device.
 */
private fun openSystemImeSettings(context: Context) {
    val opened = runCatching {
        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }.isSuccess
    if (!opened) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}

private fun showImePicker(context: Context) {
    runCatching {
        context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }
}
