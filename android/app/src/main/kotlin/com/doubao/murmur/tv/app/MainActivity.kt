package com.doubao.murmur.tv.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.doubao.murmur.tv.app.diagnostics.DiagnosticsActivity
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
 * Leanback launcher entry point: one screen that says what is still missing and how to fix it.
 *
 * Setup on a TV is a three step affair the user cannot be expected to remember - sign in, grant the
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
            refreshState()
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
            refreshState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars so the compact (phone) branch's `safeDrawingPadding` gets
        // real, live insets. On a TV there are no status/nav bars, so this is a no-op there.
        // The theme's deprecated windowTranslucentStatus/Navigation flags are left untouched:
        // they already draw behind the bars and compose fine with this API on API 29+.
        enableEdgeToEdge()
        sessionRepository = AppGraph.sessionRepository(this)

        setContent {
            MurmurTvTheme {
                HomeScreen(
                    state = state,
                    onLogin = ::startLogin,
                    onMicPermission = ::requestMicrophone,
                    onImeAction = ::handleImeAction,
                    onDiagnostics = { startActivity(Intent(this, DiagnosticsActivity::class.java)) },
                    onRefresh = ::refreshState,
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
     * One button, three different destinations depending on how far the setup got.
     *
     * Not enabled yet: our own settings page in `:ime` is the primary target. It walks through both
     * steps and prints the adb lines, and it exists on every build - unlike
     * `ACTION_INPUT_METHOD_SETTINGS`, which a good share of TV ROMs do not have (and whose root
     * `ACTION_SETTINGS` fallback hides the keyboard list several levels deep).
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
            toast(getString(R.string.ime_adb_hint_title))
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
            // Not reachable on a Play-certified TV, and there is no useful fallback if it happens.
        }
    }

    /**
     * Re-reads the three states.
     *
     * The microphone probe opens `AudioRecord` to learn which sample rate the device really
     * supports, which is far too slow for the main thread, so it runs on the IO dispatcher. Nothing
     * here touches credentials beyond a boolean.
     */
    private fun refreshState() {
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
                // A missing capability object only means "not probed yet"; do not claim the TV has
                // no microphone in that case.
                micAvailable = capability?.hasMicrophone ?: true,
                imeEnabled = ImeHelper.isImeEnabled(this@MainActivity),
                imeSelected = ImeHelper.isImeSelected(this@MainActivity),
                loading = false,
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
