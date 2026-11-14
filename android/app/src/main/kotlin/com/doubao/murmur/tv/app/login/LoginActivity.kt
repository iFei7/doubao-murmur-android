package com.doubao.murmur.tv.app.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
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
import org.json.JSONObject
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

    /**
     * Blank-page watchdog, armed on every [WebViewClient.onPageFinished] (flag reset there, so it
     * runs once per page). The WebView error callbacks only fire on transport failures; a page
     * that arrives with HTTP 200 but renders nothing (SPA scripts dying, a UA-based landing
     * page) would otherwise sit as a white rectangle for the whole poller window.
     */
    private val blankCheck = Runnable { runBlankPageCheck() }

    /** Set once [blankCheck] has fired for the current page; reset in onPageFinished. */
    private var blankCheckDone = false

    /** Drives the hint bar: the retry affordance only exists once the poller gave up. */
    private var phase by mutableStateOf(LoginPhase.Waiting)

    /**
     * One-line failure description for [LoginPhase.LoadFailed]: a generic "page failed to load"
     * plus the cause, or the dedicated certificate-error text. Always set before the phase flips,
     * so the hint bar never shows a stale line.
     */
    private var errorHint by mutableStateOf("")

    /**
     * Bumped when the current WebView died (render process gone): the AndroidView node is keyed
     * on this, so a bump tears down the dead instance and recreates the WebView from scratch
     * (factory -> attachWebView -> loadUrl) through recomposition.
     */
    private var webViewEpoch by mutableIntStateOf(0)

    /** Back handler that walks the WebView history; enabled/disabled by [updateBackState]. */
    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars; LoginScreen adds `safeDrawingPadding` so the toolbar stays
        // clear of the status bar (same behaviour as MainActivity).
        enableEdgeToEdge()
        sessionRepository = AppGraph.sessionRepository(this)

        // Default outcome. Every way out of this screen except a successful extraction - BACK (once
        // the WebView history is exhausted), the on-screen back button, HOME - therefore reports
        // "not signed in".
        setResult(RESULT_CANCELED)

        setContent {
            MurmurTvTheme {
                LoginScreen(
                    phase = phase,
                    errorHint = errorHint,
                    webViewEpoch = webViewEpoch,
                    backLabel = stringResource(R.string.action_back),
                    titleLabel = stringResource(R.string.login_title),
                    retryLabel = stringResource(R.string.login_action_retry),
                    openInBrowserLabel = stringResource(R.string.login_action_open_in_browser),
                    openInBrowserNote = stringResource(R.string.login_open_in_browser_note),
                    onBack = { finish() },
                    onRetry = ::onRetryClicked,
                    onOpenInBrowser = ::openInBrowser,
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
                // Desktop UA on purpose. The upstream desktop client logs in through a desktop
                // WebView (pywebview) with a non-mobile UA, and that flow is verified to work;
                // v0.2.2 showed the default Android WebView UA (which carries the "Android; wv"
                // tokens) reaching an HTTP-200 page that renders blank - evidence that Doubao
                // serves a different, TV-unfriendly page when the UA looks like a mobile WebView.
                // The v0.2.2 diagnostics (Page finished URL + JS console) verify the branch
                // hypothesis either way; if the logs show Doubao rejecting the desktop UA
                // (e.g. passport refusing it), revisit this line before falling back.
                userAgentString = DESKTOP_LOGIN_UA
            }

            // Diagnostics: the provider build and the actual UA the WebView will send. Logged
            // after the settings block so the line shows the UA that is actually in effect.
            runCatching {
                val provider = packageManager.getPackageInfo(WEBVIEW_PROVIDER_PACKAGE, 0)
                Log.i(
                    TAG,
                    "WebView provider: $WEBVIEW_PROVIDER_PACKAGE ${provider.versionName}; " +
                        "UA=${view.settings.userAgentString}",
                )
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                // API 21+; the login round trip bounces through passport.doubao.com, so third
                // party cookies are mandatory for the session to survive the redirect chain.
                setAcceptThirdPartyCookies(view, true)
            }

            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    // A blank page is usually a JS failure that never reaches any error callback;
                    // the console is the only window into it.
                    val line = "${consoleMessage.message()} " +
                        "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                    when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.w(TAG, "JS console error: $line")
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "JS console warn: $line")
                        else -> Log.d(TAG, "JS console: $line")
                    }
                    return false
                }
            }

            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.i(TAG, "Page started: $url")
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    Log.i(TAG, "Page finished: $url")
                    // The history shape changed; BACK may gain or lose its page-navigation role.
                    updateBackState()
                    // Start the localStorage poll on every navigation; the script is idempotent.
                    view.evaluateJavascript(LOGIN_POLL_JS, null)

                    // Re-arm the blank-page watchdog (once per page; see [blankCheck]).
                    blankCheckDone = false
                    mainHandler.removeCallbacks(blankCheck)
                    if (!signedIn) {
                        mainHandler.postDelayed(blankCheck, BLANK_CHECK_DELAY_MS)
                    }

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

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    super.onReceivedError(view, request, error)
                    if (!request.isForMainFrame) {
                        // A broken script, image or XHR must not block the login flow.
                        Log.w(TAG, "Subresource error: ${error.description}")
                        return
                    }
                    Log.w(TAG, "Main frame error: ${error.description}")
                    showLoadError(error.description?.toString())
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (!request.isForMainFrame) {
                        return
                    }
                    val code = errorResponse.statusCode
                    if (code < HTTP_ERROR_MIN) {
                        // Redirects (3xx) are followed transparently by the WebView.
                        return
                    }
                    Log.w(TAG, "Main frame HTTP error $code")
                    showLoadError("HTTP $code")
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError,
                ) {
                    // Never proceed past an invalid certificate: this flow harvests the session
                    // cookies, so a MITM must not be waved through. The callback carries no
                    // request info, so whether the failing resource is the main frame or a
                    // subresource cannot be told here - treat it as a page failure either way.
                    Log.w(TAG, "SSL error: ${error.primaryError}")
                    runCatching { handler.cancel() }
                    if (!signedIn) {
                        errorHint = getString(R.string.login_error_ssl)
                        phase = LoginPhase.LoadFailed
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    Log.w(TAG, "WebView render process gone; rebuilding")
                    // The framework requires the dead WebView to be torn down rather than used.
                    // A fresh one is created by bumping [webViewEpoch]: the AndroidView node is
                    // recreated, its factory runs attachWebView again, and the login URL is
                    // loaded from scratch. Returning true tells the system the app handled it.
                    runCatching {
                        view.stopLoading()
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.webViewClient = WebViewClient()
                        view.destroy()
                    }
                    webView = null
                    webViewEpoch += 1
                    return true
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
        // Only a quiet wait may flip to "expired": a load-failure state must not be overwritten by
        // a poller that is still running against a page that never loaded in the first place.
        if (signedIn || phase != LoginPhase.Waiting) {
            return
        }
        phase = LoginPhase.Expired
    }

    /**
     * Flips the screen into the explicit load-failure state instead of leaving the user staring at
     * a blank (theme-dark) WebView: no network, DNS failure, HTTP >= 400 on the main frame... all
     * used to be silent until the poller expired 48 s later. The hint bar names the cause and the
     * toolbar offers a retry that reloads the page (see [retryLoad]).
     */
    private fun showLoadError(detail: String?) {
        if (signedIn) {
            return
        }
        val base = getString(R.string.login_error_load_failed)
        val detailText = detail?.trim()?.take(ERROR_DETAIL_MAX_LENGTH)
        errorHint = if (detailText.isNullOrBlank()) base else "$base ($detailText)"
        phase = LoginPhase.LoadFailed
    }

    /**
     * Delayed blank-page probe: [BLANK_CHECK_DELAY_MS] after page-finished, ask the DOM what it
     * actually rendered. Pages the transport callbacks consider healthy (HTTP 200, no error) can
     * still be empty - a SPA whose bootstrap script died renders nothing at all - and this is the
     * safety net that turns that white rectangle into the normal retry screen. The injected
     * summary travels through the same evaluateJavascript channel as the login flag; it contains
     * no credentials, only readyState, a text length and the URL.
     */
    private fun runBlankPageCheck() {
        val view = webView ?: return
        if (signedIn || blankCheckDone) {
            return
        }
        blankCheckDone = true
        view.evaluateJavascript(BLANK_CHECK_JS) { raw ->
            val summary = runCatching {
                (JSONTokener(raw ?: "null").nextValue() as? JSONObject)?.toString()
            }.getOrNull()
            if (summary == null) {
                Log.w(TAG, "Blank check: no readable result (raw=${raw?.take(80)})")
                return@evaluateJavascript
            }
            Log.i(TAG, "Blank check: $summary")
            val obj = runCatching { JSONObject(summary) }.getOrNull()
            val ready = obj?.optString("ready").orEmpty()
            val textLength = obj?.optInt("len", -1) ?: -1
            // No body text at all, or the document never finished - neither recovers on its own.
            if (textLength < BLANK_PAGE_TEXT_MIN_LENGTH || ready != "complete") {
                onBlankPageDetected(summary)
            }
        }
    }

    /**
     * Blank-page verdict: reuse the v0.2.1 LoadFailed screen (progress bar -> retry button) with
     * a dedicated hint naming the empty-content cause plus the probe summary, so a bug report
     * carries the readyState/length/url evidence inline.
     */
    private fun onBlankPageDetected(summary: String) {
        if (signedIn || phase != LoginPhase.Waiting) {
            return
        }
        Log.w(TAG, "Blank page detected: $summary")
        val base = getString(R.string.login_error_blank_page)
        val detail = summary.take(ERROR_DETAIL_MAX_LENGTH)
        errorHint = if (detail.isBlank()) base else "$base ($detail)"
        phase = LoginPhase.LoadFailed
    }

    /**
     * Escape hatch: hand the login URL to a real browser. The session still has to be collected
     * inside this WebView (the cookies never leave it), so this only helps diagnose what the
     * network / a full browser makes of the same URL.
     */
    private fun openInBrowser() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DoubaoLoginUrl.LOGIN_URL)))
        }.onFailure {
            Log.w(TAG, "No browser available for the login URL")
        }
    }

    /**
     * Toolbar retry: a load failure reloads the page from scratch (there is nothing on it worth
     * keeping), while a poller expiry keeps the page - the user may have just signed in - and only
     * re-runs the detection window.
     */
    private fun onRetryClicked() {
        if (phase == LoginPhase.LoadFailed) {
            retryLoad()
        } else {
            retrySave()
        }
    }

    /** Reloads the login page after a main-frame load failure; the poller re-arms itself in
     * [onPageFinished] once the fresh page settles. */
    private fun retryLoad() {
        val view = webView ?: return
        if (signedIn) {
            return
        }
        phase = LoginPhase.Waiting
        errorHint = ""
        mainHandler.removeCallbacks(saveCheck)
        // The poller guards itself with a window flag; clear it so the fresh page can arm again.
        view.evaluateJavascript(POLL_RESET_JS, null)
        view.loadUrl(DoubaoLoginUrl.LOGIN_URL)
        updateBackState()
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

        /**
         * Desktop Chrome UA for the login WebView. Real desktop Chrome 131 string: no
         * Android/wv/Mobile tokens, so Doubao serves the same desktop login page the upstream
         * desktop client (pywebview) is verified against. See the settings block in
         * [attachWebView] for the reasoning and the fallback plan.
         */
        private const val DESKTOP_LOGIN_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val PAGE_SETTLE_DELAY_MS = 1_500L

        /** Doubao appends this marker when the login round-trip comes back. */
        private const val FROM_LOGIN_MARKER = "from_login=1"

        /** Log tag for the WebView error paths. Only error codes and descriptions are logged. */
        private const val TAG = "LoginActivity"

        /** HTTP status codes at or above this value turn the main frame into a load failure. */
        private const val HTTP_ERROR_MIN = 400

        /** Cap on the raw error description appended to the load-failure hint. */
        private const val ERROR_DETAIL_MAX_LENGTH = 40

        /** Provider package whose version is logged for WebView diagnostics. */
        private const val WEBVIEW_PROVIDER_PACKAGE = "com.google.android.webview"

        /** Delay between page-finished and the blank-page probe. */
        private const val BLANK_CHECK_DELAY_MS = 4_000L

        /** A page whose rendered body text is shorter than this counts as blank. */
        private const val BLANK_PAGE_TEXT_MIN_LENGTH = 10

        /**
         * Beyond this many WebView history entries BACK stops stepping back and closes the screen
         * instead, so a cyclic redirect chain can never trap the user.
         */
        private const val MAX_BACK_HISTORY = 15

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

    /**
     * The main frame failed to load (network, DNS, HTTP >= 400, bad certificate). The hint bar
     * names the cause and the toolbar offers a retry that reloads the page.
     */
    LoadFailed,

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
 * One-shot blank-page probe injected [LoginActivity.BLANK_CHECK_DELAY_MS] after page-finished.
 * Reports what the DOM actually contains so the Kotlin side can tell a healthy page from a white
 * rectangle: readyState, visible text length and the final URL (redirect target included).
 */
private const val BLANK_CHECK_JS =
    """try {
  JSON.stringify({
    ready: document.readyState,
    len: (document.body && document.body.innerText || '').length,
    url: location.href
  })
} catch (e) { null }"""

/**
 * Full screen WebView plus a slim toolbar.
 *
 * The toolbar keeps the affordances a web page cannot provide: an explicit back button, a visible
 * "still working" progress bar while the poller is waiting, and a retry button once the poller
 * gave up or the page failed to load.
 */
@Composable
private fun LoginScreen(
    phase: LoginPhase,
    errorHint: String,
    webViewEpoch: Int,
    backLabel: String,
    titleLabel: String,
    retryLabel: String,
    openInBrowserLabel: String,
    openInBrowserNote: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onWebViewUnavailable: () -> Unit,
) {
    // The WebView background only shows before the page paints and after a load failure - the
    // page's own rendering is unaffected - but it must follow the theme, or a light-mode failure
    // renders as an unexplained black rectangle under a white toolbar.
    val webViewBackground = MurmurColors.Background.toArgb()
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
                LoginPhase.Expired, LoginPhase.LoadFailed -> TextButton(onClick = onRetry) {
                    Text(text = retryLabel, color = MurmurColors.Accent, fontSize = 14.sp)
                }
                LoginPhase.WebViewUnavailable -> Spacer(modifier = Modifier.width(48.dp))
            }
        }
        if (phase != LoginPhase.Waiting) {
            Text(
                text = phaseHint(phase, errorHint),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MurmurColors.OnSurfaceMuted,
                fontSize = 13.sp,
            )
        }
        // Escape hatch for the failure states: the credentials can only be collected inside this
        // WebView, so opening a browser does not substitute for the login - it is a diagnostic
        // affordance to check what the network makes of the same URL.
        if (phase == LoginPhase.LoadFailed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenInBrowser) {
                    Text(text = openInBrowserLabel, color = MurmurColors.Accent, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = openInBrowserNote,
                    modifier = Modifier.weight(1f),
                    color = MurmurColors.OnSurfaceMuted,
                    fontSize = 12.sp,
                )
            }
        }
        // Keyed on webViewEpoch: when the render process dies the dead WebView is destroyed and
        // the epoch bumped, which recreates this node (and through it the WebView) from scratch.
        key(webViewEpoch) {
            AndroidView(
                // The factory runs once; configuration and the initial loadUrl happen inside
                // attachWebView. update{} must never call loadUrl or every recomposition reloads
                // the page and throws away whatever the user typed.
                factory = { context ->
                    try {
                        WebView(context).also { view ->
                            view.setBackgroundColor(webViewBackground)
                            onWebViewReady(view)
                        }
                    } catch (e: RuntimeException) {
                        // No WebView provider: show the toolbar over a blank surface instead of a
                        // white screen or a crash.
                        onWebViewUnavailable()
                        View(context)
                    }
                },
                update = { view -> (view as? WebView)?.setBackgroundColor(webViewBackground) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun phaseHint(phase: LoginPhase, errorHint: String): String = when (phase) {
    LoginPhase.Waiting -> stringResource(R.string.login_hint_scan)
    LoginPhase.Expired -> stringResource(R.string.login_not_detected)
    LoginPhase.LoadFailed -> errorHint
    LoginPhase.WebViewUnavailable -> stringResource(R.string.login_webview_unavailable)
}
