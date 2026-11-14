package com.doubao.murmur.tv.app.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.annotation.Keep
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import androidx.lifecycle.lifecycleScope
import com.doubao.murmur.tv.app.AppGraph
import com.doubao.murmur.tv.app.R
import com.doubao.murmur.tv.auth.DoubaoLoginUrl
import com.doubao.murmur.tv.auth.SessionRepository
import com.doubao.murmur.tv.ui.theme.MurmurColors
import com.doubao.murmur.tv.ui.theme.MurmurTvTheme
import kotlinx.coroutines.launch
import org.json.JSONTokener

/**
 * WebView host that harvests the doubao.com session so the ASR socket can be authenticated.
 *
 * The Doubao ASR edge accepts `Cookie` + `Origin` headers only (see
 * `docs/doubao-wss-asr-spec.md` section 3): the httpOnly `sessionid` family cannot be produced by
 * our own HTTP calls, it has to come from a real browser context. This activity is that context.
 *
 * ## Waiting is bounded
 * A failed extraction is silent - the user is usually still typing - but it is not *unbounded*.
 * The injected poller gives up after ~48 s and reports back through [LoginBridge]; from then on the
 * toolbar offers an explicit retry instead of leaving the user on a page that will never close.
 *
 * Security: cookies and localStorage ids are passed straight to [SessionRepository] and encrypted
 * with an Android Keystore master key on this device. They are never logged, never sent anywhere
 * except the Doubao domains that issued them, and never leave the device.
 */
class LoginActivity : ComponentActivity() {

    private lateinit var sessionRepository: SessionRepository

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Guarded by [saveInFlight] so the page-finished timer and the JS bridge cannot race.
     *
     * The page-finished path only fires once the page itself says the user is signed in (see
     * [checkLoginFlagThenSave]); the JS poller path ([LoginBridge]) is gated the same way by the
     * script, so both roads into [attemptSave] carry the same proof.
     */
    private val saveCheck = Runnable { checkLoginFlagThenSave() }

    private var webView: WebView? = null
    private var saveInFlight = false
    private var signedIn = false

    /** Drives the hint bar: the retry affordance only exists once the poller gave up. */
    private var phase by mutableStateOf(LoginPhase.Waiting)

    /** Back handler that walks the WebView history; enabled/disabled by [updateBackState]. */
    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionRepository = AppGraph.sessionRepository(this)

        // Default outcome. Every way out of this screen except a successful extraction - BACK (once
        // the WebView history is exhausted), the on-screen back button, HOME - therefore reports
        // "not signed in".
        setResult(RESULT_CANCELED)

        setContent {
            MurmurTvTheme {
                LoginScreen(
                    phase = phase,
                    backLabel = stringResource(R.string.action_back),
                    titleLabel = stringResource(R.string.login_title),
                    retryLabel = stringResource(R.string.login_action_retry),
                    onBack = { finish() },
                    onRetry = ::retrySave,
                    onWebViewReady = ::attachWebView,
                    onWebViewUnavailable = ::markWebViewUnavailable,
                )
            }
        }

        // The back gesture/gesture bar walks back through the WebView history (e.g. out of a wrong
        // QR page, back to the entry page) - but only while there is history to walk AND the login
        // has not succeeded yet (see [updateBackState]). Whenever the callback is disabled the
        // system default applies, which finishes the activity.
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                webView?.goBack()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    /**
     * Recomputes whether BACK should navigate the WebView instead of closing the screen.
     *
     * The callback is enabled only when all of these hold:
     * - the login has not succeeded yet - after a success the activity is on its way out and BACK
     *   must never be swallowed by stale page history;
     * - the WebView can go back - otherwise there is nothing to walk and the screen should close;
     * - the history is shallow enough. Login redirects can build deep, cyclic histories (SSO
     *   round trips re-enter the same pages), so stepping back one entry at a time would trap the
     *   user in a redirect loop they can never escape. Past [MAX_BACK_HISTORY] entries the
     *   callback stays disabled and BACK finishes the activity instead.
     */
    private fun updateBackState() {
        val view = webView
        backCallback.isEnabled = !signedIn && view != null && view.canGoBack() &&
            view.copyBackForwardList().size <= MAX_BACK_HISTORY
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun attachWebView(view: WebView) {
        try {
            webView = view
            view.setBackgroundColor(WEBVIEW_BACKGROUND)

            view.settings.apply {
                // Without JS the Doubao login page does not render at all.
                javaScriptEnabled = true
                // device_id / web_id live in localStorage, the socket URL needs them.
                domStorageEnabled = true
                databaseEnabled = true
                // Nothing in this flow needs local files or content providers; keep it minimal.
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setGeolocationEnabled(false)
                // userAgentString is deliberately left at the default: Doubao serves different
                // pages (and different login flows) when the UA does not look like a WebView.
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                // API 21+; the login round trip bounces through passport.doubao.com, so third
                // party cookies are mandatory for the session to survive the redirect chain.
                setAcceptThirdPartyCookies(view, true)
            }

            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    // The history shape changed; BACK may gain or lose its page-navigation role.
                    updateBackState()
                    // Start the localStorage poll on every navigation; the script is idempotent.
                    view.evaluateJavascript(LOGIN_POLL_JS, null)

                    // passport.doubao.com and the SSO hops still carry pre-login cookies, so
                    // reading the jar there would store a dead session. Only Doubao pages that are
                    // not part of the login round trip are worth a check - and even then the
                    // extraction only runs once the page itself says the user signed in (see
                    // [checkLoginFlagThenSave]).
                    if (url == null || !DoubaoLoginUrl.isSessionCheckCandidate(url)) {
                        return
                    }
                    mainHandler.removeCallbacks(saveCheck)
                    val delay = if (url.contains(FROM_LOGIN_MARKER)) 0L else PAGE_SETTLE_DELAY_MS
                    mainHandler.postDelayed(saveCheck, delay)
                }
            }

            view.addJavascriptInterface(
                LoginBridge(
                    onDetected = { runOnUiThread { attemptSave() } },
                    onExpired = { runOnUiThread { onLoginPollExpired() } },
                ),
                BRIDGE_NAME,
            )
            view.loadUrl(DoubaoLoginUrl.LOGIN_URL)
            updateBackState()
        } catch (e: RuntimeException) {
            // A ROM without a usable WebView provider throws on the first settings or
            // CookieManager call as well, not only on construction. Drop the half-built view so
            // a later attempt cannot touch it.
            webView = null
            markWebViewUnavailable()
        }
    }

    /**
     * Page-finished gate: Doubao writes `localStorage['flow_web_has_login'] = "true"` only after
     * the user actually completed the login. A page that merely *loaded* also carries pre-login
     * state (tea-cache ids, `ttwid`), so extracting credentials from it would either store a dead
     * session or - before the cookie checks were tightened - fake a success. The flag query keeps
     * that path closed; only a genuine "logged in" flag proceeds to [attemptSave].
     *
     * The JS poller ([LOGIN_POLL_JS]) reads the same flag and reaches the same verdict through
     * [LoginBridge]; this function exists so the page-finished path cannot shortcut it.
     */
    private fun checkLoginFlagThenSave() {
        val view = webView ?: return
        if (signedIn || saveInFlight) {
            return
        }
        view.evaluateJavascript(LOGIN_FLAG_QUERY_JS) { value ->
            // The callback receives a JSON-encoded JavaScript value: the string `"true"` (quoted)
            // when the flag is set, the JSON `null` otherwise. Unwrap the literal first.
            val flag = value?.let { raw ->
                runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            }
            if (flag == "true") {
                attemptSave()
            }
        }
    }

    /**
     * Tries to lift the session out of the WebView.
     *
     * Failure is intentionally silent: a missing cookie usually just means the user is still
     * typing, and an error banner on a TV screen heaps pressure on them. The only failure types
     * left after the device-id fallback in `:auth` are "cookie jar not synced yet" and "page not
     * loaded yet", both of which a later attempt can fix - so we wait for the next page-finish
     * event or the JS bridge callback instead of showing an error. The exit is
     * [onLoginPollExpired],
     * which flips the hint bar to an explicit retry.
     */
    private fun attemptSave() {
        val view = webView ?: return
        if (signedIn || saveInFlight) {
            return
        }
        saveInFlight = true
        lifecycleScope.launch {
            try {
                // The repository reads cookies through CookieManager and localStorage through
                // evaluateJavascript, both of which must run on the main thread - this coroutine
                // already is on it (lifecycleScope uses Dispatchers.Main.immediate).
                val result = sessionRepository.saveFromWebView(view)
                if (result.isSuccess) {
                    onSignedIn()
                }
            } finally {
                saveInFlight = false
            }
        }
    }

    private fun onSignedIn() {
        if (signedIn) {
            return
        }
        signedIn = true
        // BACK must never be swallowed by the page history once the login succeeded.
        backCallback.isEnabled = false
        setResult(RESULT_OK)
        finish()
    }

    /**
     * The poller ran out of attempts without seeing a login.
     *
     * Not a terminal state: the user may simply be slow with the QR code, or the cookie jar may
     * have been mid-write. The bar switches to an explicit retry and stays there - it is never
     * dismissed automatically, because a button that blinks in and out between page navigations is
     * worse than no button at all on a 3 m screen.
     */
    private fun onLoginPollExpired() {
        if (signedIn) {
            return
        }
        phase = LoginPhase.Expired
    }

    /**
     * Manual retry from the hint bar: one immediate extraction plus a fresh poll window.
     *
     * The window is the same ~48 s as the first one - a second, shorter budget would fail on the
     * same slow QR scan the first one already warned about.
     */
    private fun retrySave() {
        val view = webView ?: return
        if (signedIn) {
            return
        }
        phase = LoginPhase.Waiting
        mainHandler.removeCallbacks(saveCheck)
        // The poller guards itself with a window flag; clear it so the script can arm again.
        view.evaluateJavascript(POLL_RESET_JS, null)
        // One immediate extraction, then a fresh poll window. attemptSave() returns as soon as it
        // suspends, so the poller is re-armed before the extraction can finish the activity.
        attemptSave()
        view.evaluateJavascript(LOGIN_POLL_JS, null)
        updateBackState()
    }

    /**
     * No WebView provider on this box (some TV ROMs ship without the Android System WebView).
     *
     * Posted rather than applied inline: the call originates from the `AndroidView` factory, which
     * runs during layout, and writing Compose state from there can re-enter the composition.
     */
    private fun markWebViewUnavailable() {
        mainHandler.post {
            if (!signedIn) {
                phase = LoginPhase.WebViewUnavailable
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.webViewClient = WebViewClient()
            view.destroy()
        }
        webView = null
        try {
            // Persist what the page just wrote; the ASR socket reads it back from the encrypted
            // store. Throws on a ROM whose WebView provider is missing.
            CookieManager.getInstance().flush()
        } catch (e: RuntimeException) {
            // Nothing left to flush if the cookie store could not be created in the first place.
        }
        super.onDestroy()
    }

    companion object {

        private const val BRIDGE_NAME = "MurmurBridge"

        private const val PAGE_SETTLE_DELAY_MS = 1_500L

        /** Doubao appends this marker when the login round-trip comes back. */
        private const val FROM_LOGIN_MARKER = "from_login=1"

        /**
         * Beyond this many WebView history entries BACK stops stepping back and closes the screen
         * instead, so a cyclic redirect chain can never trap the user.
         */
        private const val MAX_BACK_HISTORY = 15

        /**
         * Matches [MurmurColors.Background] so the WebView does not flash white before the page
         * paints. `WebView.setBackgroundColor` takes a packed ARGB int, not a Compose colour.
         */
        private val WEBVIEW_BACKGROUND: Int = AndroidColor.rgb(0x0B, 0x0B, 0x0F)

        /**
         * Intent for the login flow.
         *
         * `FLAG_ACTIVITY_NEW_TASK` only for non-activity callers (the IME fallback path); an
         * activity caller needs the result in its own task.
         */
        fun intent(context: Context): Intent {
            val intent = Intent(context, LoginActivity::class.java)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return intent
        }
    }
}

/** What the hint bar is saying, and whether it offers a retry. */
private enum class LoginPhase {

    /** Waiting for the page (and the poller) to see a login. */
    Waiting,

    /** The poller gave up; the viewer gets an explicit retry. */
    Expired,

    /** No WebView provider on this device; nothing can be done inside the app. */
    WebViewUnavailable,
}

/**
 * JS bridge used by [LoginActivity].
 *
 * A top-level, public class on purpose: `WebView` resolves `@JavascriptInterface` methods by
 * reflection, and non-public inner classes are not reliably reachable that way. The methods must
 * stay `@Keep`ed (release builds enable R8), must not be overloaded, and must not take arguments
 * the bridge cannot convert, because the JS side can only address them by name.
 */
class LoginBridge(
    private val onDetected: () -> Unit,
    private val onExpired: () -> Unit,
) {

    /**
     * Called by the injected poller once `localStorage['flow_web_has_login']` flips to `"true"`.
     * Invoked on a WebView JavaBridge thread, so the callback posts back to the main thread.
     */
    @Keep
    @JavascriptInterface
    fun onLoginDetected() {
        onDetected.invoke()
    }

    /**
     * Called when the poller exhausted its attempts without seeing a login. This is what turns an
     * indefinite wait into a screen the viewer can actually act on.
     */
    @Keep
    @JavascriptInterface
    fun onLoginPollExpired() {
        onExpired.invoke()
    }
}

/**
 * Poller injected after every page load.
 *
 * Reads only a boolean - the ids themselves are read by the repository through the same
 * `localStorage` API - so no credential ever travels through this channel. The window covers
 * ~48 s (60 x 800 ms), which is longer than a QR scan usually takes, and the script re-arms on each
 * navigation because `window` is a fresh object per document. Once it expires it reports back
 * instead of dying silently.
 */
private const val LOGIN_POLL_JS = """
(function () {
  try {
    if (window.__murmurLoginPoll) { return; }
    window.__murmurLoginPoll = true;
    var attempts = 0;
    var timer = setInterval(function () {
      attempts = attempts + 1;
      var logged = false;
      try {
        logged = localStorage.getItem('flow_web_has_login') === 'true';
      } catch (e) {
        logged = false;
      }
      if (logged) {
        clearInterval(timer);
        try {
          if (window.MurmurBridge) { window.MurmurBridge.onLoginDetected(); }
        } catch (e) { }
      } else if (attempts >= 60) {
        clearInterval(timer);
        try {
          if (window.MurmurBridge) { window.MurmurBridge.onLoginPollExpired(); }
        } catch (e) { }
      }
    }, 800);
  } catch (e) { }
})();
"""

/** Re-arms the poller: the script refuses to install itself twice per document. */
private const val POLL_RESET_JS = "try { window.__murmurLoginPoll = false; } catch (e) { }"

/**
 * One-shot read of the login flag used by the page-finished gate in [LoginActivity]. Returns the
 * raw localStorage value (`"true"`, or JSON `null` when unset), so the Kotlin side can decide on
 * the exact string instead of trusting the page load itself.
 */
private const val LOGIN_FLAG_QUERY_JS =
    "try { localStorage.getItem('flow_web_has_login') } catch (e) { null }"

/**
 * Full screen WebView plus a slim toolbar.
 *
 * The toolbar keeps the affordances a web page cannot provide: an explicit back button, a visible
 * "still working" progress bar while the poller is waiting, and a retry button once the poller
 * gave up.
 */
@Composable
private fun LoginScreen(
    phase: LoginPhase,
    backLabel: String,
    titleLabel: String,
    retryLabel: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onWebViewUnavailable: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MurmurColors.Background)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(text = backLabel, color = MurmurColors.Accent, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = titleLabel,
                color = MurmurColors.OnBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.weight(1f))
            when (phase) {
                LoginPhase.Waiting -> LinearProgressIndicator(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .width(48.dp),
                    color = MurmurColors.Accent,
                    trackColor = MurmurColors.Outline,
                )
                LoginPhase.Expired -> TextButton(onClick = onRetry) {
                    Text(text = retryLabel, color = MurmurColors.Accent, fontSize = 14.sp)
                }
                LoginPhase.WebViewUnavailable -> Spacer(modifier = Modifier.width(48.dp))
            }
        }
        if (phase == LoginPhase.Expired || phase == LoginPhase.WebViewUnavailable) {
            Text(
                text = phaseHint(phase),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MurmurColors.OnSurfaceMuted,
                fontSize = 13.sp,
            )
        }
        AndroidView(
            // The factory runs once; configuration and the initial loadUrl happen inside
            // attachWebView. update{} must never call loadUrl or every recomposition reloads the
            // page and throws away whatever the user typed.
            factory = { context ->
                try {
                    WebView(context).also(onWebViewReady)
                } catch (e: RuntimeException) {
                    // No WebView provider: show the toolbar over a blank surface instead of a
                    // white screen or a crash.
                    onWebViewUnavailable()
                    View(context)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun phaseHint(phase: LoginPhase): String = when (phase) {
    LoginPhase.Waiting -> stringResource(R.string.login_hint_scan)
    LoginPhase.Expired -> stringResource(R.string.login_not_detected)
    LoginPhase.WebViewUnavailable -> stringResource(R.string.login_webview_unavailable)
}
