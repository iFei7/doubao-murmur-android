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
import androidx.activity.compose.setContent
import androidx.annotation.Keep
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import androidx.lifecycle.lifecycleScope
import com.doubao.murmur.tv.app.AppGraph
import com.doubao.murmur.tv.app.R
import com.doubao.murmur.tv.auth.DoubaoLoginUrl
import com.doubao.murmur.tv.auth.SessionRepository
import com.doubao.murmur.tv.ui.component.TvActionButton
import com.doubao.murmur.tv.ui.theme.MurmurColors
import com.doubao.murmur.tv.ui.theme.MurmurTvTheme
import kotlinx.coroutines.launch

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
 * hint bar offers an explicit retry instead of leaving the viewer on a page that will never close.
 * A TV viewer who scanned a QR code has no other way to tell "still working" from "broken".
 *
 * Security: cookies and localStorage ids are passed straight to [SessionRepository] and encrypted
 * with an Android Keystore master key on this device. They are never logged, never sent anywhere
 * except the Doubao domains that issued them, and never leave the TV.
 */
class LoginActivity : ComponentActivity() {

    private lateinit var sessionRepository: SessionRepository

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Guarded by [saveInFlight] so the page-finished timer and the JS bridge cannot race. */
    private val saveCheck = Runnable { attemptSave() }

    private var webView: WebView? = null
    private var saveInFlight = false
    private var signedIn = false

    /** Drives the hint bar: the retry affordance only exists once the poller gave up. */
    private var phase by mutableStateOf(LoginPhase.Waiting)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionRepository = AppGraph.sessionRepository(this)

        // Default outcome. Every way out of this screen except a successful extraction - BACK, the
        // on-screen back button, HOME from the remote - therefore reports "not signed in" without
        // anybody having to intercept the back key.
        setResult(RESULT_CANCELED)

        setContent {
            MurmurTvTheme {
                LoginScreen(
                    phase = phase,
                    backLabel = stringResource(R.string.action_back),
                    retryLabel = stringResource(R.string.login_action_retry),
                    onBack = { finish() },
                    onRetry = ::retrySave,
                    onWebViewReady = ::attachWebView,
                    onWebViewUnavailable = ::markWebViewUnavailable,
                )
            }
        }
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
                    // Start the localStorage poll on every navigation; the script is idempotent.
                    view.evaluateJavascript(LOGIN_POLL_JS, null)

                    // passport.doubao.com and the SSO hops still carry pre-login cookies, so
                    // reading the jar there would store a dead session. Only Doubao pages that are
                    // not part of the login round trip are worth a check.
                    if (url == null || !DoubaoLoginUrl.isLoginPage(url)) {
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
        } catch (e: RuntimeException) {
            // A ROM without a usable WebView provider throws on the first settings or
            // CookieManager call as well, not only on construction. Drop the half-built view so
            // a later attempt cannot touch it.
            webView = null
            markWebViewUnavailable()
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
 * Full screen WebView plus a TV friendly hint bar.
 *
 * The hint bar is drawn on top of the page because the Doubao web login is designed for a phone:
 * on a TV nobody knows whether to scan or type, there is no obvious "back" affordance in the page,
 * and a stuck poll has to become visible somewhere.
 */
@Composable
private fun LoginScreen(
    phase: LoginPhase,
    backLabel: String,
    retryLabel: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onWebViewUnavailable: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MurmurColors.Background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvActionButton(text = backLabel, onClick = onBack)
            Spacer(modifier = Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = phaseHint(phase),
                    color = MurmurColors.OnSurface,
                    fontSize = 16.sp,
                )
                if (phase == LoginPhase.WebViewUnavailable) {
                    // Concrete next step for the operator; a TV has no paste UI, so this is meant
                    // to be read off the screen.
                    Text(
                        text = "adb shell dumpsys webviewupdate",
                        color = MurmurColors.OnSurfaceMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            if (phase == LoginPhase.Expired) {
                // Only after the poller gave up, and never auto-hidden again.
                Spacer(modifier = Modifier.width(16.dp))
                TvActionButton(text = retryLabel, onClick = onRetry)
            }
        }
        AndroidView(
            // The factory runs once; configuration and the initial loadUrl happen inside
            // attachWebView. update{} must never call loadUrl or every recomposition reloads the
            // page and throws away whatever the user typed.
            factory = { context ->
                try {
                    WebView(context).also(onWebViewReady)
                } catch (e: RuntimeException) {
                    // No WebView provider: show the hint bar over a blank surface instead of a
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
