package com.doubao.murmur.tv.ime.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.doubao.murmur.tv.auth.EncryptedCredentialStore
import com.doubao.murmur.tv.auth.SessionRepository
import com.doubao.murmur.tv.ime.DoubaoVoiceInputMethodService
import com.doubao.murmur.tv.ime.R
import com.doubao.murmur.tv.ui.theme.LocalDimens
import com.doubao.murmur.tv.ui.theme.MurmurColors
import com.doubao.murmur.tv.ui.theme.MurmurTvTheme
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Settings screen of the input method.
 *
 * Reachable three ways: the MENU key on the keyboard, the "设置" button in the header, and the
 * system's "input method settings" entry (declared as `android:settingsActivity` in
 * `res/xml/method.xml`).
 *
 * It exists mostly because two behaviours cannot be decided for the user up front: whether the
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
) {
    val dimens = LocalDimens.current
    val context = LocalContext.current
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
            .fillMaxSize()
            .background(MurmurColors.Background)
            .safeDrawingPadding()
            .padding(horizontal = dimens.contentEdge, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(dimens.keyGap),
    ) {
        Text(
            text = stringResource(R.string.ime_settings_title),
            color = MurmurColors.OnBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )

        // Login state row: the matching action sits on the right (sign out while signed in,
        // sign in otherwise).
        SettingsCard {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (loggedIn) {
                            true -> stringResource(R.string.login_success)
                            else -> stringResource(R.string.ime_status_not_signed_in)
                        },
                        color = MurmurColors.OnSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (signOutDone) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.ime_settings_sign_out_done),
                            color = MurmurColors.Listening,
                            fontSize = 13.sp,
                        )
                    }
                }
                when (loggedIn) {
                    true -> CardAction(
                        label = stringResource(R.string.ime_settings_action_sign_out),
                        onClick = {
                            scope.launch {
                                runCatching { sessionRepository.clear() }
                                loggedIn = false
                                signOutDone = true
                            }
                        },
                    )
                    false -> CardAction(
                        label = stringResource(R.string.ime_action_login),
                        onClick = { openLogin(context) },
                    )
                    null -> Unit
                }
            }
        }

        SectionTitle(stringResource(R.string.ime_settings_section_commit))
        SettingsCard {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ime_settings_commit_composing),
                        color = MurmurColors.OnSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.ime_settings_commit_hint),
                        color = MurmurColors.OnSurfaceMuted,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = composingEnabled,
                    onCheckedChange = {
                        composingEnabled = it
                        preferences.composingEnabled = it
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MurmurColors.Accent,
                    ),
                )
            }
        }

        SectionTitle(stringResource(R.string.ime_settings_section_timeout))
        SettingsCard {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                ImePreferences.FINAL_TIMEOUT_OPTIONS_MS.forEach { optionMs ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                timeoutMs = optionMs
                                preferences.finalTimeoutMs = optionMs
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = timeoutMs == optionMs,
                            onClick = {
                                timeoutMs = optionMs
                                preferences.finalTimeoutMs = optionMs
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MurmurColors.Accent,
                            ),
                        )
                        Text(
                            text = stringResource(
                                R.string.ime_settings_timeout_seconds,
                                formatSeconds(optionMs),
                            ),
                            color = MurmurColors.OnSurface,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
        HintText(stringResource(R.string.ime_settings_timeout_hint))

        // System status is *always* visible: hiding the rows once both checks pass reads as
        // "the keyboard cannot tell whether it is enabled". The action cards below are the only
        // conditional part — a dead "already done" row would just be noise.
        val system = rememberImeSystemStatus(lifecycleOwner)
        SectionTitle(stringResource(R.string.ime_settings_section_system))
        SettingsCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(
                        if (system.enabled) {
                            R.string.ime_settings_ime_enabled
                        } else {
                            R.string.ime_settings_ime_disabled
                        },
                    ),
                    color = MurmurColors.OnSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (system.selected) {
                            R.string.ime_settings_ime_selected
                        } else {
                            R.string.ime_settings_ime_not_selected
                        },
                    ),
                    color = MurmurColors.OnSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (!system.enabled) {
            SettingsCard {
                Row(
                    modifier = Modifier
                        .clickable { openSystemImeSettings(context) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ime_settings_action_enable_system),
                            color = MurmurColors.Accent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.ime_settings_ime_disabled),
                            color = MurmurColors.OnSurfaceMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        if (!system.selected) {
            SettingsCard {
                Row(
                    modifier = Modifier
                        .clickable { showImePicker(context) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ime_settings_action_pick_ime),
                            color = MurmurColors.Accent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.ime_settings_ime_not_selected),
                            color = MurmurColors.OnSurfaceMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        SectionTitle(stringResource(R.string.ime_settings_section_privacy))
        HintText(stringResource(R.string.ime_settings_privacy_text))

        Spacer(modifier = Modifier.height(dimens.keyGap))
        VersionFooter()
    }
}

/** Card container shared by every list row of the settings screen. */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MurmurColors.Surface)
            .border(0.5.dp, MurmurColors.Outline, RoundedCornerShape(10.dp)),
    ) {
        content()
    }
}

/** Trailing text action inside a card (sign out / sign in). */
@Composable
private fun CardAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = MurmurColors.Accent,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = MurmurColors.OnSurfaceMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
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
        color = MurmurColors.OnSurfaceMuted,
        fontSize = 12.sp,
    )
}

@Composable
private fun VersionFooter() {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    if (versionName != null) {
        Text(
            text = versionName,
            color = MurmurColors.OnSurfaceMuted.copy(alpha = 0.6f),
            fontSize = 12.sp,
        )
    }
}

/**
 * Opens the login screen of `:app`.
 *
 * `:ime` cannot depend on `:app` code, so the activity is addressed by class name; the class name
 * is stable across debug/release because only the application id gets a suffix, not the class
 * package. Failures (build without :app, ROM quirk) are silently ignored.
 */
private fun openLogin(context: Context) {
    runCatching {
        context.startActivity(
            Intent()
                .setClassName(context, "com.doubao.murmur.tv.app.login.LoginActivity"),
        )
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

    // Channel 1: Settings.Secure. Works on stock Android but is restricted/stale on some ROMs.
    val enabledList = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
    }.getOrNull()
    val settingsEnabled = enabledList
        .orEmpty()
        .split(':')
        .mapNotNull { ComponentName.unflattenFromString(it) }
        .any { it == self }

    // Channel 2: InputMethodManager's own list (standard framework API, no new dependency).
    // Authoritative where the Settings.Secure table cannot be read back reliably; a ROM that
    // blocks either channel must not flip the status to "disabled" while the other one still
    // sees the IME, so a channel failure only contributes "false" when both agree.
    val managerEnabled = runCatching {
        context.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.any { ComponentName.unflattenFromString(it.id) == self }
    }.getOrNull() == true

    val enabled = settingsEnabled || managerEnabled

    val defaultId = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    }.getOrNull()
    val selected = ComponentName.unflattenFromString(defaultId.orEmpty()) == self

    return ImeSystemStatus(enabled = enabled, selected = selected)
}

/**
 * Opens the keyboard list of the system settings.
 *
 * `ACTION_INPUT_METHOD_SETTINGS` is missing on a fair number of ROMs (and on some it is present
 * but empty), so falling back to the root settings screen is the only other option that exists on
 * every device.
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
