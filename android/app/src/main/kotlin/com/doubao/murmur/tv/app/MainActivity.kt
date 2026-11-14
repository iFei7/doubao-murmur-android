package com.doubao.murmur.tv.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.doubao.murmur.tv.app.ime.ImeHelper
import com.doubao.murmur.tv.app.login.LoginActivity
import com.doubao.murmur.tv.app.permission.MicPermissionActivity
import com.doubao.murmur.tv.audio.MicPermission
import com.doubao.murmur.tv.audio.MicProbe
import com.doubao.murmur.tv.auth.SessionRepository
import com.doubao.murmur.tv.ui.theme.MurmurTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launcher entry point: one screen that says what is still missing and how to fix it.
 *
 * Setup is a three step affair the user cannot be expected to remember - sign in, grant the
 * microphone, enable the keyboard - so the only job of this activity is to keep an honest snapshot
 * of those three states and to offer the matching shortcut for each one.
 */
class MainActivity : ComponentActivity() {

    private lateinit var sessionRepository: SessionRepository

    private var state by mutableStateOf(HomeUiState())

    /**
     * Activity result contracts must be registered before `onCreate` returns and, practically, as
     * field initialisers - registering after STARTED throws `IllegalStateException`.
     */
    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshState(force = true)
        }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val permanentlyDenied = result.data
                ?.getBooleanExtra(MicPermissionActivity.EXTRA_PERMANENTLY_DENIED, false)
                ?: false
            if (permanentlyDenied) {
                // The system will never show the dialog again, so send the user to the only screen
                // that can still grant it.
                toast(getString(R.string.mic_permanently_denied_hint))
                openAppSettings()
            }
            refreshState(force = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars; the screens add `safeDrawingPadding` so content stays clear
        // of the status bar and the gesture strip.
        enableEdgeToEdge()
        sessionRepository = AppGraph.sessionRepository(this)

        setContent {
            MurmurTvTheme {
                HomeScreen(
                    state = state,
                    onLogin = ::startLogin,
                    onMicPermission = ::requestMicrophone,
                    onImeAction = ::handleImeAction,
                )
            }
        }

        // Every return to the foreground is a chance that the user just changed something in the
        // system settings, so the snapshot is recomputed per RESUMED period rather than once.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                refreshState()
            }
        }
    }

    private fun startLogin() {
        loginLauncher.launch(LoginActivity.intent(this))
    }

    private fun requestMicrophone() {
        micLauncher.launch(MicPermissionActivity.intent(this))
    }

    /**
     * One entry, three different destinations depending on how far the setup got.
     *
     * Not enabled yet: our own settings page in `:ime` is the primary target. It walks through both
     * steps, and it exists on every build - unlike `ACTION_INPUT_METHOD_SETTINGS`, which some ROMs
     * do not have (and whose root `ACTION_SETTINGS` fallback hides the keyboard list several
     * levels deep).
     *
     * Enabled but not current: the system picker is the shortest path. Enabled and current: the
     * settings page again, because that is where sign-out and the commit-mode options live.
     */
    private fun handleImeAction() {
        val opened = when {
            !ImeHelper.isImeEnabled(this) ->
                ImeHelper.openOwnImeSettings(this) || ImeHelper.openImeSettings(this)
            !ImeHelper.isImeSelected(this) ->
                ImeHelper.showImePicker(this) || ImeHelper.openOwnImeSettings(this)
            else -> ImeHelper.openOwnImeSettings(this)
        }
        if (!opened) {
            toast(getString(R.string.home_ime_open_failed))
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Not reachable on a certified device, and there is no useful fallback if it happens.
        }
    }

    /**
     * Re-reads the setup states.
     *
     * Probing is not free: the login check decrypts the credential store, the microphone probe
     * opens `AudioRecord` on the IO dispatcher, and the IME flags round-trip through system
     * settings. Since [refreshState] fires on every RESUMED period, the expensive probes are
     * throttled to [STATE_CACHE_MS]; callers that just returned from a screen whose only purpose
     * was to change one of these states (login, permission dialog) pass `force = true` to bypass
     * the throttle.
     */
    private fun refreshState(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastProbeAtMs < STATE_CACHE_MS) {
            return
        }
        lastProbeAtMs = now
        lifecycleScope.launch {
            val micGranted = MicPermission.hasRecordPermission(this@MainActivity)
            val capability = if (micGranted) {
                withContext(Dispatchers.IO) {
                    runCatching { MicProbe.probe(this@MainActivity) }.getOrNull()
                }
            } else {
                null
            }
            val loggedIn = runCatching { sessionRepository.isLoggedIn() }.getOrDefault(false)

            state = HomeUiState(
                loggedIn = loggedIn,
                micGranted = micGranted,
                // A missing capability object only means "not probed yet"; do not claim the device
                // has no microphone in that case.
                micAvailable = capability?.hasMicrophone ?: true,
                imeEnabled = ImeHelper.isImeEnabled(this@MainActivity),
                imeSelected = ImeHelper.isImeSelected(this@MainActivity),
                loading = false,
            )
        }
    }

    private var lastProbeAtMs = 0L

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {

        /** Cheap cached-state window; probes older than this are re-run on the next resume. */
        const val STATE_CACHE_MS = 5_000L
    }
}
