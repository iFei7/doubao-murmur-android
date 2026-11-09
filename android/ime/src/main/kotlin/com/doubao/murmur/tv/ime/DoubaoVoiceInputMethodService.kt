package com.doubao.murmur.tv.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.util.Log
import android.view.KeyEvent
import android.view.View
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
import okhttp3.OkHttpClient

/**
 * The voice keyboard itself.
 *
 * ## Key handling, and the one key that cannot work
 * `KEYCODE_VOICE_ASSIST` (231, the microphone button found on most TV remotes) is **never
 * delivered to applications**. The platform consumes it in `PhoneWindowManager` before the input
 * pipeline is reached; the framework documentation states plainly that the key is "not delivered
 * to applications". An IME therefore cannot listen for it, and trying to would only add dead code.
 * The working paths, in priority order, are:
 *
 * | Input | Behaviour |
 * |---|---|
 * | Hold OK (`DPAD_CENTER`) >= 400 ms | push-to-talk: records while held, inserts on release |
 * | `KEYCODE_SEARCH` (84) | toggles recording (AOSP does deliver this key to the foreground app) |
 * | The on-screen "hold to talk" button | same as hold OK, for viewers who prefer the UI |
 *
 * ## Why `onKeyDown` returns `false` so often
 * Returning `true` from `onKeyDown`/`onKeyUp` for an unrelated key would stop the platform from
 * performing D-pad focus navigation inside our own Compose tree. Every branch below either handles
 * the key completely (returns `true` for both down and up) or declines it (`false`), never half of
 * each — a half-declined key is the classic cause of "focus jumps two steps" bugs on TV.
 *
 * ## Insets
 * See [onComputeInsets]. The keyboard is a bottom overlay: it must never resize or pan the host
 * app, because TV apps lay out for a fixed 16:9 canvas and a resized `Leanback` fragment loses its
 * focus state.
 */
class DoubaoVoiceInputMethodService : InputMethodService() {

    private lateinit var preferences: ImePreferences
    private lateinit var committer: TextCommitter
    private lateinit var controller: ImeController
    private lateinit var httpClient: OkHttpClient

    private var composeView: ImeComposeView? = null

    /** Outer container returned from onCreateInputView; the authoritative rect for onComputeInsets. */
    private var inputContainer: ImeComposeContainer? = null

    /** `eventTime` of the current DPAD_CENTER press, used for the 400 ms push-to-talk threshold. */
    private var centerDownAtMs = 0L

    /** True once a DPAD_CENTER long press started a recording, so KeyUp knows it must finish it. */
    private var centerHoldStarted = false

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
            // The recorder needs the probe result to pick a capture rate the box actually opens
            // (many TV devices only accept 48 kHz, the endpoint only accepts 16 kHz).
            recorderFactory = { context, capability -> PcmRecorder(context, capability) },
            engineFactory = { credentials -> DoubaoAsrEngine(credentials, httpClient) },
            committer = committer,
            finalTimeoutMsProvider = { preferences.finalTimeoutMs },
        )
        // A rejected session is *not* escalated to an activity here: the controller clears the
        // stored credentials and flips the keyboard into the "not signed in" state, whose primary
        // button is "去登录". Yanking the sign-in screen to the front while the viewer is typing in
        // a host app is exactly the kind of focus theft TV apps do not recover from.
        controller.onNeedLogin = { openAppActivity(LOGIN_ACTIVITY) }
        controller.onNeedMicPermission = { openAppActivity(MIC_PERMISSION_ACTIVITY) }
    }

    // ------------------------------------------------------------------ view creation

    override fun onCreateInputView(): View {
        // The framework only calls this when it needs a brand new input view (first show, theme or
        // configuration change). The previous instance is dead at this point, so its lifecycle is
        // torn down here instead of leaking a RESUMED owner until the service dies.
        composeView?.dispose()
        val imeView = ImeComposeView(this)
        imeView.content = {
            val state by controller.state.collectAsState()
            MurmurTvTheme {
                MurmurKeyboardScreen(
                    state = state,
                    onMicDown = { guarded("beginListening") { controller.beginListening() } },
                    onMicUp = { guarded("endListening") { controller.endListening() } },
                    onCancel = { guarded("cancelListening") { controller.cancelListening() } },
                    onClear = { guarded("clearTransient") { controller.clearTransient() } },
                    onDelete = { guarded("deleteBackward") { committer.deleteBackward(1) } },
                    onNewline = { guarded("newline") { committer.newline() } },
                    onHide = { requestHideSelf(0) },
                    onSwitchSystemIme = { guarded("switchIme") { switchToAnotherIme() } },
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

    /** TV has no room for a candidate bar; the transcript card replaces it. */
    override fun onCreateCandidatesView(): View? = null

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        committer.updateEditor(info)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        committer.updateEditor(info)
        committer.setComposingEnabled(preferences.composingEnabled && !isConservativeEditor(info))
        setCandidatesViewShown(false)
        // The input view must own the key focus, otherwise the Compose focus tree never gets the
        // D-pad events and the keyboard looks frozen.
        composeView?.requestFocus()
        guarded("refreshStatus") { controller.refreshStatus() }
    }

    /** TV screens are large and landscape; the fullscreen extract view would only hide the host. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean = true

    /**
     * Tells the framework where the keyboard actually is.
     *
     * - `contentTopInsets = 0`: "my content does not occlude the host's content". This is what
     *   keeps the host from resizing or panning; the keyboard behaves as a bottom overlay.
     * - `visibleTopInsets`: top of the *visible* part, in window coordinates. It is 0 when the IME
     *   window is exactly as tall as the keyboard, and equal to the keyboard's offset when a ROM
     *   gives the window the whole screen. Computing it from the view's real position works for
     *   both window layouts; hard-coding either 0 or the view height breaks on the other one.
     * - The touchable region is derived from the same rectangle: with `TOUCHABLE_INSETS_CONTENT`
     *   the framework derives the region from `contentTopInsets`, which we just set to 0 — that
     *   would make the whole (possibly full-screen) window swallow every touch. An explicit
     *   region is the only value that is correct under both window sizes.
     *
     * Until the container has a real layout we must NOT fall back to the framework defaults: the
     * default `TOUCHABLE_INSETS_VISIBLE` lets the whole IME window swallow touches and keys, which
     * is the "keyboard is up but nothing responds" symptom. The pre-attach placeholder measure
     * reports only a 1 px height, so [MIN_LAID_OUT_HEIGHT_DP] is the threshold that separates that
     * frame from a real layout:
     * - **real layout**: positive width and a height above [MIN_LAID_OUT_HEIGHT_DP] - publish the
     *   container's actual rectangle;
     * - **placeholder**: container missing, or width/height still 0 or the 1 px measure - publish
     *   a conservative bottom strip (at most 1/3 of the screen).
     * Both branches set an explicit `TOUCHABLE_INSETS_REGION`, so the host keeps receiving input
     * above the keyboard under either window size.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        outInsets.contentTopInsets = 0
        val metrics = resources.displayMetrics
        val minLaidOutHeightPx = (MIN_LAID_OUT_HEIGHT_DP * metrics.density).toInt().coerceAtLeast(1)
        val container = inputContainer
        if (container != null && container.width > 0 && container.height > minLaidOutHeightPx) {
            val location = IntArray(2)
            container.getLocationInWindow(location)
            val top = location[1].coerceAtLeast(0)
            outInsets.visibleTopInsets = top
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(
                location[0],
                top,
                location[0] + container.width,
                top + container.height,
            )
        } else {
            // Placeholder: the container is missing or still reports the 1 px pre-attach measure.
            // Publish an explicit, conservative bottom strip rather than the framework default
            // (which would cover the whole screen). A TV keyboard is ~240 dp tall; clamp to at
            // most 1/3 of the screen so the region never covers the top area where the host's
            // input lives.
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            val height = minOf(
                (PLACEHOLDER_KEYBOARD_HEIGHT_DP * metrics.density).toInt(),
                screenHeight / 3,
            ).coerceAtLeast(1)
            val top = (screenHeight - height).coerceAtLeast(0)
            outInsets.visibleTopInsets = top
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(0, top, screenWidth, screenHeight)
        }
    }

    // ------------------------------------------------------------------ keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (event.repeatCount == 0) {
                    centerDownAtMs = event.eventTime
                    centerHoldStarted = false
                    // Not consumed: if the Compose tree owns the focus it handles the press itself
                    // (and this callback would not even be reached).
                    return false
                }
                if (!centerHoldStarted && event.eventTime - centerDownAtMs >= HOLD_THRESHOLD_MS) {
                    centerHoldStarted = true
                    guarded("holdToTalk") { controller.beginListening() }
                    return true
                }
                return false
            }

            // AOSP delivers SEARCH to the foreground app, which makes it the only *dedicated*
            // voice key available to a third-party IME on a TV remote.
            KeyEvent.KEYCODE_SEARCH -> {
                if (event.repeatCount == 0) {
                    guarded("toggleListening") { controller.toggleListening() }
                }
                return true
            }

            KeyEvent.KEYCODE_DEL -> {
                // Deleting on every repeat is what makes "hold DEL" erase continuously; the cost
                // is one commit per repeat, which the host coalesces.
                guarded("deleteBackward") { committer.deleteBackward(1) }
                return true
            }

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) {
                    guarded("enter") { handleEnter() }
                }
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                guarded("back") { handleBack() }
                return true
            }

            KeyEvent.KEYCODE_MENU -> {
                if (event.repeatCount == 0) {
                    switchToImeSettings()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && centerHoldStarted) {
            centerHoldStarted = false
            guarded("endListening") { controller.endListening() }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * BACK for the IME window.
     *
     * There is deliberately **no** `onBackPressed()` override: `InputMethodService` is a `Service`,
     * not an `Activity`, so it has no such public member (the framework method is hidden and is not
     * part of the SDK stubs) and no `OnBackInvokedDispatcher` either. In an IME window BACK always
     * arrives as a key event, which is handled in [onKeyDown].
     */
    private fun handleBack() {
        if (controller.state.value.isCapturing) {
            controller.cancelListening()
        } else {
            requestHideSelf(0)
        }
    }

    private fun handleEnter() {
        val composing = committer.composingSnapshot
        if (composing.isNotEmpty()) {
            // The viewer is looking at a preview and pressed ENTER: insert it instead of sending a
            // newline, otherwise the preview would stay in the field and a blank line appear.
            committer.commitFinal(composing)
            return
        }
        if (!sendDefaultEditorAction(true)) {
            // Most TV search fields declare no IME action; a newline would corrupt a single-line
            // field, so the key is simply swallowed.
            Log.d(TAG, "ENTER ignored: editor declares no default action")
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
        composeView?.dispose()
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
     * Heuristic for editors that are known to mishandle a composing region.
     *
     * `IME_FLAG_NO_FULLSCREEN` is set by several TV search widgets (they want to keep control of
     * their own overlay), and empirically those are the ones that also ignore
     * `setComposingText`. When we see it we fall back to "insert the final text only".
     */
    private fun isConservativeEditor(info: EditorInfo?): Boolean {
        if (info == null) return false
        return (info.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0
    }

    /**
     * Switches to the previously used IME, i.e. normally the system keyboard.
     *
     * A third-party IME steals the input focus of some TV apps (notably WebView-based players), so
     * there must always be a one-button escape hatch. When no other IME is enabled the framework
     * refuses to switch; the system picker is then shown instead of leaving the viewer stuck.
     */
    @Suppress("DEPRECATION")
    private fun switchToAnotherIme() {
        val switched = switchToNextInputMethod(false)
        if (!switched) {
            runCatching {
                getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            }.onFailure { Log.w(TAG, "cannot show the input method picker", it) }
        }
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
        // start is silently dropped on several TV ROMs.
        startActivitySafely(Intent().setClassName(packageName, className))
    }

    private fun startActivitySafely(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "cannot start $intent", it) }
    }

    /**
     * The IME is a system component: an uncaught exception here can tear down the whole input
     * pipeline and leave the TV without a keyboard. Every entry point is therefore guarded, and a
     * failure degrades to "this key did nothing" instead of a crash dialog over the host app.
     */
    private inline fun guarded(what: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "$what failed", it) }
    }

    private companion object {
        const val TAG = "DoubaoVoiceIme"

        /** Matches `androidx.compose.foundation`'s long-press constant, i.e. what viewers expect. */
        const val HOLD_THRESHOLD_MS = 400L

        /**
         * Conservative expected keyboard height (dp) for the touchable region published before the
         * input container has been laid out. Clamped to at most 1/3 of the screen in
         * [onComputeInsets] so the placeholder region never covers the whole window.
         */
        const val PLACEHOLDER_KEYBOARD_HEIGHT_DP = 240

        /**
         * Minimum container height (dp) treated as a real layout. The pre-attach placeholder
         * measure reports only 1 px, so a height at or below this threshold must not be used to
         * build the touchable region from an essentially empty view rectangle.
         */
        const val MIN_LAID_OUT_HEIGHT_DP = 8

        const val LOGIN_ACTIVITY = "com.doubao.murmur.tv.app.login.LoginActivity"
        const val MIC_PERMISSION_ACTIVITY = "com.doubao.murmur.tv.app.permission.MicPermissionActivity"
    }
}
