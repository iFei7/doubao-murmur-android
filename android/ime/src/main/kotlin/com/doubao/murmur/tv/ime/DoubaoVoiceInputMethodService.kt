package com.doubao.murmur.tv.ime

import android.content.Intent
import android.content.res.Resources
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.doubao.murmur.tv.asr.DoubaoAsrEngine
import com.doubao.murmur.tv.audio.PcmRecorder
import com.doubao.murmur.tv.auth.EncryptedCredentialStore
import com.doubao.murmur.tv.auth.SessionRepository
import com.doubao.murmur.tv.ime.settings.ImePreferences
import com.doubao.murmur.tv.ime.settings.ImeSettingsActivity
import com.doubao.murmur.tv.ui.theme.MurmurTvTheme
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * The voice keyboard — phone-only single form factor (all TV / D-pad paths removed).
 *
 * ## Key handling
 * Phones have no OK / SEARCH / MENU / DPAD hardware keys; the on-screen panel handles everything.
 * Only **BACK** reaches this override, and its semantics are "never lose what was said":
 *
 * | Input | Behaviour |
 * |---|---|
 * | BACK while capturing (Connecting/Listening) | `endListening`: the recognised text is committed, not discarded |
 * | BACK otherwise (Idle / Transcribing / Committed) | hide the keyboard |
 *
 * There is deliberately **no** `onBackPressed()` override: `InputMethodService` is a `Service`,
 * not an `Activity`, so it has no such public member. In an IME window BACK always arrives as a
 * key event, handled in [onKeyDown].
 *
 * ## Insets
 * See [onComputeInsets]. The keyboard is a bottom overlay that must not resize or pan the host
 * app, and its touchable region must stop above the gesture-navigation strip, otherwise edge
 * swipes (back gesture) are swallowed while the keyboard is up.
 */
class DoubaoVoiceInputMethodService : InputMethodService() {

    private lateinit var preferences: ImePreferences
    private lateinit var committer: TextCommitter
    private lateinit var controller: ImeController
    private lateinit var httpClient: OkHttpClient

    private var composeView: ImeComposeView? = null

    /** Outer container returned from onCreateInputView; the authoritative rect for onComputeInsets. */
    private var inputContainer: ImeComposeContainer? = null

    /**
     * One-shot warm-up scope. `EncryptedSharedPreferences.create` unseals the Keystore master key
     * and can block for hundreds of milliseconds on low-end devices; running it here, while the
     * service binds, means the first `refreshStatus()` (input view shown) reads the cached
     * preferences wrapper instead of freezing the IME window mid-animation. Cancelled in
     * [onDestroy] so the read never touches the context after the service is gone.
     */
    private val warmUpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        preferences = ImePreferences(this)
        // The connection is resolved lazily on every call: the host may swap it at any time and a
        // captured instance would silently drop text into a field that no longer exists.
        committer = TextCommitter { currentInputConnection }

        val sessionRepository = SessionRepository(EncryptedCredentialStore(this))

        httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // 0 = no read timeout. A WebSocket that idles between two utterances must not be
            // killed by the transport; the engine implements its own inactivity policy.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            // Retrying a rejected session would multiply the login-expired failures we surface.
            .retryOnConnectionFailure(false)
            .build()

        controller = ImeController(
            context = this,
            sessionRepository = sessionRepository,
            // The recorder needs the probe result to pick a capture rate the device actually
            // opens (many devices only accept 48 kHz, the endpoint only accepts 16 kHz).
            recorderFactory = { context, capability -> PcmRecorder(context, capability) },
            engineFactory = { credentials -> DoubaoAsrEngine(credentials, httpClient) },
            committer = committer,
            finalTimeoutMsProvider = { preferences.finalTimeoutMs },
        )
        // A rejected session is *not* escalated to an activity here: the controller clears the
        // stored credentials and flips the keyboard into the "not signed in" state, whose primary
        // button is 「登录豆包账号」. Yanking the sign-in screen to the front while the user is
        // typing in a host app is exactly the kind of focus theft apps do not recover from.
        controller.onNeedLogin = { openAppActivity(LOGIN_ACTIVITY) }
        controller.onNeedMicPermission = { openAppActivity(MIC_PERMISSION_ACTIVITY) }

        // Pre-unseal the Keystore-backed credential store off the main thread (see warmUpScope).
        // Concurrent access with later main-thread reads is safe: the store's openPreferences()
        // uses a synchronized double-check and caches the wrapper after the first open.
        warmUpScope.launch {
            runCatching { sessionRepository.isLoggedIn() }
        }
    }

    // ------------------------------------------------------------------ view creation

    override fun onCreateInputView(): View {
        // The framework only calls this when it needs a brand new input view (first show, theme or
        // configuration change). The previous instance is dead at this point, so its lifecycle is
        // torn down here instead of leaking a RESUMED owner until the service dies.
        // disposeComposition() releases the Compose composition/recomposer itself, not just the
        // view-tree owners — without it the old composition lingers until GC on the next
        // onCreateInputView cycle.
        composeView?.dispose()
        composeView?.disposeComposition()
        val imeView = ImeComposeView(this)
        imeView.content = {
            val state by controller.state.collectAsState()
            MurmurTvTheme {
                MurmurKeyboardScreen(
                    state = state,
                    onMicDown = { guarded("beginListening") { controller.beginListening() } },
                    onMicUp = { guarded("endListening") { controller.endListening() } },
                    onCancel = { guarded("cancelListening") { controller.cancelListening() } },
                    onHide = { requestHideSelf(0) },
                    onSwitchSystemIme = { guarded("switchIme") { showImePicker() } },
                    onOpenSettings = { switchToImeSettings() },
                    onRequestLogin = { openAppActivity(LOGIN_ACTIVITY) },
                    onRequestMicPermission = { openAppActivity(MIC_PERMISSION_ACTIVITY) },
                )
            }
        }
        composeView = imeView
        // Wrap the compose view in a plain FrameLayout: the IME framework measures the input
        // view before `WindowManager.addView` completes, and AbstractComposeView.onMeasure
        // (final, compose-ui 1.7.5) crashes when detached because it cannot resolve a window
        // recomposer. The container short-circuits that pre-attach pass and resumes real
        // measurement once attached (see ImeComposeContainer's KDoc).
        val container = ImeComposeContainer(this).apply {
            addView(
                imeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        inputContainer = container
        return container
    }

    override fun onCreateCandidatesView(): View? = null

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        committer.updateEditor(info)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        committer.updateEditor(info)
        committer.setComposingEnabled(preferences.composingEnabled)
        setCandidatesViewShown(false)
        guarded("refreshStatus") { controller.refreshStatus() }
    }

    // Fullscreen extract mode is a landscape-keyboard concept; a phone panel is always compact.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean = true

    /**
     * Tells the framework where the keyboard is and which part of it takes touches.
     *
     * - `contentTopInsets = 0`: "my content does not occlude the host's content". This is what
     *   keeps the host from resizing or panning; the keyboard behaves as a bottom overlay.
     * - The touchable rectangle is the panel's own rectangle in **IME-window coordinates**, minus
     *   the gesture-navigation strip along its bottom edge. A touch outside that rectangle counts
     *   as an "outside the keyboard" tap, which is exactly what dismisses the IME — so the
     *   rectangle must never be *smaller* than the panel. An underestimated region turns every
     *   tap on the panel into a dismiss (the device bug this method is written against), while an
     *   overestimated one only swallows the gesture strip while the keyboard is up.
     *
     * Three rules keep the estimate on the safe side of that trade-off:
     * - the region's bottom edge is the **panel's** bottom edge, never a screen-derived offset:
     *   [panelTouchableRegion] derives everything from the window (`decorView`) whose coordinate
     *   space the region lives in, so a ROM that sizes the IME window differently cannot push the
     *   rectangle outside it. A region that lands outside its own window is clipped away and
     *   leaves the window with an empty touchable region — every tap then dismisses the keyboard;
     * - the gesture strip is only cut when the panel can spare it: at least
     *   [PANEL_CONTENT_HEIGHT_DP] of panel content (the 72 dp button row plus the panel's vertical
     *   padding) always stays touchable, so a ROM reporting an oversized nav-bar inset (or a
     *   window that does *not* extend behind the nav bar at all) cannot eat into the row;
     * - when the panel has no usable geometry yet the **whole window** is declared touchable
     *   ([Insets.TOUCHABLE_INSETS_FRAME]) instead of a guessed strip. The framework computes
     *   insets right after [onCreateInputView], before the input view is attached — our container
     *   answers that pre-attach measure with 1 px (see [ImeComposeContainer]) — and an IME window
     *   hugs its input view, so the frame *is* the panel.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        outInsets.contentTopInsets = 0
        val region = panelTouchableRegion()
        if (region != null) {
            outInsets.visibleTopInsets = region.top
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(region)
            Log.d(
                TAG,
                "onComputeInsets region=$region panelHeight=${inputContainer?.height} " +
                    "window=${window?.window?.decorView?.width}x${window?.window?.decorView?.height}",
            )
        } else {
            // No panel geometry yet (pre-attach measure, or the window has not been laid out).
            // The window frame is the only rectangle guaranteed to be inside the window and to
            // cover whatever the panel will be; publishing nothing would let the platform fall
            // back to TOUCHABLE_INSETS_VISIBLE, which is equivalent here.
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
            Log.d(
                TAG,
                "onComputeInsets(frame) panelHeight=${inputContainer?.height} " +
                    "window=${window?.window?.decorView?.width}x${window?.window?.decorView?.height}",
            )
        }
    }

    /**
     * The panel's touchable rectangle in IME-window coordinates, or `null` when there is not
     * enough information to place it.
     *
     * Height is the only dimension that survives the framework's pre-attach measure (the container
     * answers that pass with 1 px, see [ImeComposeContainer.onMeasure]) once the panel has been
     * laid out at least once, so it is the gate for "the panel has a real size". The top edge is
     * the panel's real window position when the view is attached to a laid-out window; otherwise
     * it falls back to the bottom-anchored assumption every IME window satisfies
     * (`Gravity.BOTTOM`). `getLocationInWindow` on a detached view returns (0, 0), which would
     * place the region above the panel instead of over it.
     */
    private fun panelTouchableRegion(): Rect? {
        val container = inputContainer ?: return null
        val decor = window?.window?.decorView ?: return null
        val windowWidth = decor.width
        val windowHeight = decor.height
        if (windowWidth <= 0 || windowHeight <= 0) return null
        if (container.width <= 0) return null

        val density = resources.displayMetrics.density
        val minLaidOutHeightPx = (MIN_LAID_OUT_HEIGHT_DP * density).toInt().coerceAtLeast(1)
        val panelHeight = container.height
        if (panelHeight <= minLaidOutHeightPx) return null

        val top = if (container.isAttachedToWindow) {
            val location = IntArray(2)
            container.getLocationInWindow(location)
            location[1].takeIf { it in 0 until windowHeight }
        } else {
            null
        } ?: (windowHeight - panelHeight).coerceAtLeast(0)

        // The panel is bottom-anchored, so its bottom edge is the window's bottom edge; clamp to
        // the window so a stale panel height can never overshoot it.
        val panelBottom = (top + panelHeight).coerceAtMost(windowHeight)
        // Cut the gesture strip, but never more than the panel can spare: the button row and its
        // padding stay touchable even when the reported inset is larger than the panel itself.
        val minContentPx = (PANEL_CONTENT_HEIGHT_DP * density).toInt()
        val maxCut = (panelHeight - minContentPx).coerceAtLeast(0)
        val cut = navigationBarBottomInsetPx().coerceIn(0, maxCut)
        val regionBottom = (panelBottom - cut).coerceAtLeast(top + 1)

        return Rect(0, top, windowWidth, regionBottom)
    }

    /**
     * Height in px of the bottom navigation-bar inset of the IME window.
     *
     * `WindowInsets.Type.navigationBars()` needs API 30; API 23-29 fall back to the (deprecated
     * but accurate for this purpose) `systemWindowInsetBottom`, which on an IME window is the
     * navigation-bar height. A **positive** reported value is trusted as-is, but several ROMs
     * report `bottom = 0` for the IME window even with gesture navigation — in that case (and
     * when no insets object is available) we fall back to the platform `navigation_bar_height`
     * dimension, then to a density-scaled 48 px floor. Overestimating is harmless: the caller caps
     * the cut at what the panel can spare ([PANEL_CONTENT_HEIGHT_DP]), so a too large value only
     * shortens the cut and never reaches the button row. Underestimating makes the IME swallow the
     * system back gesture, which is the bug this guard exists for.
     */
    @Suppress("DEPRECATION")
    private fun navigationBarBottomInsetPx(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return fallbackNavBarBottomInsetPx()
        val insets = window?.window?.decorView?.rootWindowInsets
            ?: return fallbackNavBarBottomInsetPx()
        val reported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            insets.systemWindowInsetBottom
        }.coerceAtLeast(0)
        return if (reported > 0) reported else fallbackNavBarBottomInsetPx()
    }

    /**
     * Conservative gesture-strip height when the window reports nothing usable: the platform
     * `navigation_bar_height` dimension first (it matches the actual ROM value, e.g. 24dp or
     * 48dp), falling back to a density-scaled 48 px floor.
     */
    private fun fallbackNavBarBottomInsetPx(): Int {
        val dimenId = runCatching {
            Resources.getSystem().getIdentifier("navigation_bar_height", "dimen", "android")
        }.getOrNull() ?: 0
        if (dimenId > 0) {
            val fromRes = runCatching { resources.getDimensionPixelSize(dimenId) }.getOrDefault(0)
            if (fromRes > 0) return fromRes
        }
        return (FALLBACK_NAV_BAR_HEIGHT_DP * resources.displayMetrics.density).toInt()
            .coerceAtLeast(1)
    }

    // ------------------------------------------------------------------ keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            guarded("back") { handleBack() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * BACK: while recording keep the words (commit the recognised text), otherwise hide.
     * The old TV behaviour cancelled the utterance outright, which threw away everything the
     * user had just said — on a phone BACK during dictation must mean "I'm done here".
     */
    private fun handleBack() {
        if (controller.state.value.isCapturing) {
            controller.endListening()
        } else {
            requestHideSelf(0)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** The editor is gone (user moved to another field): never commit a half sentence there. */
    override fun onFinishInput() {
        guarded("cancelOnFinishInput") { controller.cancelListening() }
        super.onFinishInput()
    }

    /** The window was hidden (HOME pressed, host switched away): same rule. */
    override fun onWindowHidden() {
        guarded("cancelOnWindowHidden") { controller.cancelListening() }
        super.onWindowHidden()
    }

    override fun onDestroy() {
        guarded("release") { controller.release() }
        warmUpScope.cancel()
        composeView?.dispose()
        composeView?.disposeComposition()
        composeView = null
        inputContainer = null
        runCatching {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Opens the system input-method picker so the user can switch back to another keyboard.
     */
    private fun showImePicker() {
        runCatching {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }.onFailure { Log.w(TAG, "cannot show the input method picker", it) }
    }

    private fun switchToImeSettings() {
        startActivitySafely(Intent(this, ImeSettingsActivity::class.java))
    }

    /**
     * Opens a screen of `:app`.
     *
     * `:app` depends on `:ime`, never the other way around, so the activity classes are not on
     * this module's compile classpath and cannot be referenced directly. The manifest fixes their
     * FQCNs precisely so that this string-based lookup is a contract and not a guess.
     */
    private fun openAppActivity(className: String) {
        Log.d(TAG, "opening $className")
        // NEW_TASK is mandatory from a service context; without it the intent resolves but the
        // start is silently dropped on several ROMs.
        startActivitySafely(Intent().setClassName(packageName, className))
    }

    private fun startActivitySafely(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "cannot start $intent", it) }
    }

    /**
     * The IME is a system component: an uncaught exception here can tear down the whole input
     * pipeline and leave the device without a keyboard. Every entry point is therefore guarded,
     * and a failure degrades to "this key did nothing" instead of a crash dialog over the host
     * app.
     */
    private inline fun guarded(what: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "$what failed", it) }
    }

    private companion object {
        const val TAG = "DoubaoVoiceIme"

        /**
         * Panel content (dp) that must always stay inside the touchable region: the 72 dp button
         * row plus the panel's 10 dp vertical padding (both from [MurmurKeyboardScreen]). The
         * gesture-strip cut in [panelTouchableRegion] is capped so it can never reach the row —
         * an oversized nav-bar inset must not turn the buttons into "outside the keyboard" taps.
         */
        const val PANEL_CONTENT_HEIGHT_DP = 92

        /**
         * Minimum container height (dp) treated as a real layout. The pre-attach placeholder
         * measure reports only 1 px, so a height at or below this threshold must not be used to
         * build the touchable region from an essentially empty view rectangle.
         */
        const val MIN_LAID_OUT_HEIGHT_DP = 8

        /**
         * Navigation-bar height floor (dp) used when the IME window reports no usable inset
         * (no insets object, or `bottom = 0` on ROMs that zero it out for IME windows). Applied
         * as `48 * density` in [fallbackNavBarBottomInsetPx].
         */
        const val FALLBACK_NAV_BAR_HEIGHT_DP = 48

        const val LOGIN_ACTIVITY = "com.doubao.murmur.tv.app.login.LoginActivity"
        const val MIC_PERMISSION_ACTIVITY = "com.doubao.murmur.tv.app.permission.MicPermissionActivity"
    }
}
