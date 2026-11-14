package com.doubao.murmur.tv.app.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
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
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
import com.doubao.murmur.tv.auth.WebViewCredentialExtractor
import com.doubao.murmur.tv.auth.parseBlankProbePayload
import com.doubao.murmur.tv.ui.theme.MurmurColors
import com.doubao.murmur.tv.ui.theme.MurmurTvTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

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

    /**
     * Timeout for the blank-probe JS round trip: a `evaluateJavascript` callback that never runs
     * is the *no-callback* verdict - the render process is not executing JS at all. Posted when
     * the probe is sent, removed by its callback; re-armed per page in [onPageFinished].
     */
    private val blankProbeTimeout = Runnable { diagBlankProbe = BLANK_PROBE_NO_CALLBACK }

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

    /**
     * Render self-test stage, or `null` when no self-test is running.
     *
     * After the blank-page watchdog finds the login page empty, the screen no longer gives up
     * straight away: it probes [RENDER_TEST_URL] to find out *why* the page is blank. The stage
     * routes the probe results through the branches described in [onBlankPageDetected] and
     * [onRenderTestPageOk]. It is cancelled by any main-frame transport error ([showLoadError])
     * and by the manual retry ([retryLoad]).
     */
    private var renderTestStage: RenderTestStage? = null

    /**
     * True once the self-test proved that hardware rendering is broken on this ROM and switched
     * the WebView to [View.LAYER_TYPE_SOFTWARE]. Applied to every WebView (re)built by
     * [attachWebView], so the fix survives a render-process rebuild for the whole session.
     */
    private var softwareRendering = false

    //region Custom Tabs login route (see [openInCustomTabs]).
    // The route runs fully in parallel with the in-app WebView flow above: its collection loop
    // lives in its own coroutine, reads only the system-wide cookie jar and never touches the
    // in-app poller / blank check / render self-test. Whichever route first sees a signed-in
    // session calls [onSignedIn] (single completion, first-wins).

    /** Browser-side client once the binding to the Custom Tabs provider is established. */
    private var customTabsClient: CustomTabsClient? = null

    /** True while the provider binding is alive (or still connecting); cleared on unbind. */
    private var customTabsBound = false

    /** Set when the entry button was pressed while the binding was still connecting. */
    private var customTabLaunchPending = false

    /**
     * Set once a Custom Tab actually launched; consumed by [onResume] to reload the in-app login
     * page once the user comes back (see there), then reset so a resume never reloads twice.
     */
    private var customTabLaunched = false

    /** Collection loop polling the cookie jar while the user is inside the Custom Tab. */
    private var customTabsCollectionJob: Job? = null

    /**
     * Offscreen 1x1 WebView that shares this app's data directory - and with it the session the
     * Custom Tab wrote there. It exists only to load a doubao.com page whose JS writes the tea
     * cache into localStorage so [SessionRepository.saveFromWebView] can read the ids back.
     */
    private var offscreenWebView: WebView? = null

    private val customTabsConnection = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
            customTabsClient = client
            runCatching { client.warmup(0L) }
            // The button may have been pressed while the bind was still in flight.
            launchPendingCustomTab()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            customTabsClient = null
        }
    }
    //endregion

    //region On-screen diagnostics panel (read-only evidence, see DiagnosticsPanel).
    // Mutable Compose state so the panel recomposes as the counters move. Updated on the main
    // thread only (WebView callbacks all arrive there). No cookie or credential values ever land
    // here - the URL is stripped down to host+path by [sanitizeUrlForDiagnostics].
    /** Times [WebViewClient.onPageFinished] fired - "page loaded but blank" evidence. */
    private var diagPageFinishedCount by mutableIntStateOf(0)

    /** Times [WebViewClient.onRenderProcessGone] fired - silent Chromium crash-loop evidence. */
    private var diagRenderGoneCount by mutableIntStateOf(0)

    /** Last page-finished URL, query/fragment stripped (may carry tokens) and truncated. */
    private var diagLastFinishedUrl by mutableStateOf("")

    /**
     * JS blank-probe verdict, e.g. "pending" / "returned(ready=complete,len=1234)" /
     * "no-callback"; [BLANK_PROBE_NOT_RUN] upfront. The decisive blank-screen evidence: the
     * probe is a plain `evaluateJavascript` round trip, so **no-callback** means the Chromium
     * render process never executes JS (dead renderer), while **returned** means JS runs but
     * the page still paints nothing (compositing / page-side failure).
     */
    private var diagBlankProbe by mutableStateOf(BLANK_PROBE_NOT_RUN)

    /** Render self-test verdict, e.g. "baidu ok" / "sw blank"; [RENDER_TEST_NOT_RUN] upfront. */
    private var diagRenderTestVerdict by mutableStateOf(RENDER_TEST_NOT_RUN)
    //endregion

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
                    diagFinishedCount = diagPageFinishedCount,
                    diagRenderGoneCount = diagRenderGoneCount,
                    diagLastUrl = diagLastFinishedUrl,
                    diagBlankProbe = diagBlankProbe,
                    diagRenderTestVerdict = diagRenderTestVerdict,
                    // Reads the live field at each call, so the panel always sees the current
                    // WebView (rebuilt instances included) without storing a view in Compose
                    // state - writing state from the AndroidView factory can re-enter composition.
                    webViewProvider = { webView },
                    backLabel = stringResource(R.string.action_back),
                    titleLabel = stringResource(R.string.login_title),
                    retryLabel = stringResource(R.string.login_action_retry),
                    openInBrowserLabel = stringResource(R.string.login_action_open_in_browser),
                    openInBrowserNote = stringResource(R.string.login_open_in_browser_note),
                    systemBrowserLabel = stringResource(R.string.login_action_open_system_browser),
                    systemBrowserNote = stringResource(R.string.login_customtabs_note),
                    onBack = { finish() },
                    onRetry = ::onRetryClicked,
                    onOpenInBrowser = ::openInBrowser,
                    onOpenSystemBrowser = ::openInCustomTabs,
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
                // Default (Android) UA on purpose. The v0.2.3 desktop-UA experiment is reverted:
                // the v0.2.6 on-device panel showed the page's JS/DOM working under the desktop
                // UA while the screen still stayed blank for the user, and the user's own mobile
                // browser (Android 14, Mobile Chrome) is verified to open the Doubao mobile OAuth
                // login page and sign in successfully - so stay on the verified mobile path.
                // The desktop UA is the upstream desktop client's (pywebview) untested route.
            }

            // If the render self-test proved hardware compositing broken on this ROM (GPU
            // compositing blank-screen is common on some vendor ROMs), keep every WebView - a
            // rebuilt one included - on the software layer for this session. Applied after the
            // settings block so a fresh instance inherits the verdict immediately.
            if (softwareRendering) {
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
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
                    diagPageFinishedCount += 1
                    diagLastFinishedUrl = sanitizeUrlForDiagnostics(url)
                    // The history shape changed; BACK may gain or lose its page-navigation role.
                    updateBackState()
                    // Start the localStorage poll on every navigation; the script is idempotent.
                    view.evaluateJavascript(LOGIN_POLL_JS, null)

                    // Re-arm the blank-page watchdog (once per page; see [blankCheck]).
                    blankCheckDone = false
                    mainHandler.removeCallbacks(blankCheck)
                    mainHandler.removeCallbacks(blankProbeTimeout)
                    diagBlankProbe = BLANK_PROBE_NOT_RUN
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
                    diagRenderGoneCount += 1
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
        // a poller that is still running against a page that never loaded in the first place -
        // and neither may a render self-test that is currently probing another page.
        if (signedIn || phase != LoginPhase.Waiting || renderTestStage != null) {
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
        // A transport failure during a render self-test (e.g. baidu.com unreachable) ends the
        // test: the probe result would say nothing about rendering anyway.
        cancelRenderSelfTest()
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
        // Record that the probe went out; if the callback below never runs (timeout), the panel
        // ends up with "no-callback" - the renderer does not even execute JS.
        diagBlankProbe = BLANK_PROBE_PENDING
        mainHandler.removeCallbacks(blankProbeTimeout)
        mainHandler.postDelayed(blankProbeTimeout, BLANK_PROBE_TIMEOUT_MS)
        view.evaluateJavascript(BLANK_CHECK_JS) { raw ->
            // The callback runs on the UI thread; removing the timeout here marks the probe as
            // answered no matter how the payload decodes below.
            mainHandler.removeCallbacks(blankProbeTimeout)
            // The callback receives the JSON-encoded JavaScript return value: the object arrives
            // as a quoted, escaped string literal, so the payload must be unwrapped twice (see
            // parseBlankProbePayload). Until v0.2.6 the second layer was missed, which made every
            // probe read "unreadable" and kept the blank-screen detection from ever firing.
            val reading = parseBlankProbePayload(raw)
            // The callback running at all is itself the evidence: JS executed inside the
            // renderer. Only the decode quality varies.
            diagBlankProbe = if (reading == null) {
                "$BLANK_PROBE_RETURNED_UNREADABLE_PREFIX" +
                    "${raw?.take(BLANK_PROBE_UNREADABLE_SNIPPET_LENGTH) ?: "null"})"
            } else {
                "$BLANK_PROBE_RETURNED_PREFIX(" +
                    "ready=${reading.ready},len=${reading.len})"
            }
            if (reading == null) {
                Log.w(TAG, "Blank check: no readable result (raw=${raw?.take(80)})")
                return@evaluateJavascript
            }
            val summary = "ready=${reading.ready},len=${reading.len},url=${reading.url}"
            Log.i(TAG, "Blank check: $summary")
            val ready = reading.ready
            val textLength = reading.len
            // No body text at all, or the document never finished - neither recovers on its own.
            if (textLength < BLANK_PAGE_TEXT_MIN_LENGTH || ready != "complete") {
                onBlankPageDetected(summary)
            } else if (renderTestStage != null) {
                // A self-test probe page rendered fine - that is exactly the evidence the
                // branches in [onRenderTestPageOk] need.
                Log.i(TAG, "RENDER-TEST: probe page ok ($summary)")
                onRenderTestPageOk()
            }
        }
    }

    /**
     * Blank-page verdict. Without a running self-test this now *starts* one instead of giving up:
     * the login page is blank either because this ROM's WebView cannot render at all (hardware
     * compositing blank-screen, common on vendor ROMs - fixable by switching to the software
     * layer) or because the Doubao page itself refuses to render. Probing [RENDER_TEST_URL]
     * tells the two apart; see [onBlankPageDetected]'s stage branches and [onRenderTestPageOk].
     *
     * With a self-test already running, the blank verdict belongs to the probe page: baidu.com
     * blank under hardware rendering moves the test to its software-rendering round, blank under
     * software rendering too ends the test with the "WebView itself is broken" verdict.
     */
    private fun onBlankPageDetected(summary: String) {
        if (signedIn || phase != LoginPhase.Waiting) {
            return
        }
        Log.w(TAG, "Blank page detected: $summary")
        when (renderTestStage) {
            null -> startRenderSelfTest(summary)
            RenderTestStage.Baidu -> {
                Log.w(TAG, "RENDER-TEST: baidu blank under hardware rendering; retrying with " +
                    "software rendering")
                diagRenderTestVerdict = RENDER_TEST_BAIDU_BLANK
                val view = webView
                if (view == null) {
                    cancelRenderSelfTest()
                    showBlankPageError(summary)
                    return
                }
                renderTestStage = RenderTestStage.Software
                softwareRendering = true
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                view.loadUrl(RENDER_TEST_URL)
            }
            RenderTestStage.Software -> {
                Log.w(TAG, "RENDER-TEST: baidu blank under software rendering too; WebView " +
                    "itself cannot render")
                diagRenderTestVerdict = RENDER_TEST_SW_BLANK
                renderTestStage = null
                errorHint = getString(R.string.login_render_test_webview_broken)
                phase = LoginPhase.LoadFailed
                switchBackToLoginUrl()
            }
        }
    }

    /**
     * Starts the render self-test: the login page just turned out blank, so load
     * [RENDER_TEST_URL] and let the existing blank-page watchdog (re-armed by every
     * [android.webkit.WebViewClient.onPageFinished]) deliver the verdict.
     *
     * The toolbar stays in [LoginPhase.Waiting] (progress bar) - the test takes about two probe
     * windows, and the hint bar explains what is happening. Credential collection cannot be
     * triggered by the probe page: [DoubaoLoginUrl.isSessionCheckCandidate] rejects every
     * non-doubao URL and the localStorage login flag is origin-scoped, so the poller on
     * baidu.com can never see `flow_web_has_login`.
     */
    private fun startRenderSelfTest(summary: String) {
        val view = webView
        if (view == null) {
            showBlankPageError(summary)
            return
        }
        Log.w(TAG, "RENDER-TEST: login page blank, probing $RENDER_TEST_URL")
        renderTestStage = RenderTestStage.Baidu
        errorHint = getString(R.string.login_render_test_running)
        // Belt and braces: a pending credential check from the blank login page must not fire
        // while the probe page is up (the flag gate would reject it anyway).
        mainHandler.removeCallbacks(saveCheck)
        view.loadUrl(RENDER_TEST_URL)
    }

    /**
     * A self-test probe page rendered fine, which pins down the blank-screen cause:
     *
     * - [RenderTestStage.Baidu]: the WebView renders correctly, so the Doubao page itself refuses
     *   to render. Back to the LoadFailed screen (retry + browser escape hatch) and reload the
     *   login URL to restore the poller semantics.
     * - [RenderTestStage.Software]: switching to the software layer fixed rendering - keep it
     *   ([softwareRendering] stays true, so [attachWebView] applies it to any rebuilt WebView)
     *   and reload the login URL, where the user signs in again as usual.
     */
    private fun onRenderTestPageOk() {
        val stage = renderTestStage ?: return
        val view = webView ?: run {
            cancelRenderSelfTest()
            return
        }
        renderTestStage = null
        when (stage) {
            RenderTestStage.Baidu -> {
                Log.w(TAG, "RENDER-TEST: baidu ok; the Doubao page itself failed to render")
                diagRenderTestVerdict = RENDER_TEST_BAIDU_OK
                errorHint = getString(R.string.login_render_test_page_rejected)
                phase = LoginPhase.LoadFailed
            }
            RenderTestStage.Software -> {
                Log.w(TAG, "RENDER-TEST: hw blank -> sw ok; keeping the software layer")
                diagRenderTestVerdict = RENDER_TEST_SW_OK
                errorHint = getString(R.string.login_render_test_software_fix)
                // Back to the normal waiting flow: the login page reloads under the software
                // layer, the poller re-arms and the user signs in as usual.
                phase = LoginPhase.Waiting
            }
        }
        switchBackToLoginUrl()
    }

    /** Cancels a running self-test without touching the current hint/phase state. */
    private fun cancelRenderSelfTest() {
        if (renderTestStage != null) {
            Log.w(TAG, "RENDER-TEST: cancelled")
            renderTestStage = null
        }
    }

    /** Reloads the login URL after a self-test verdict, restoring the poller semantics. */
    private fun switchBackToLoginUrl() {
        val view = webView ?: return
        view.loadUrl(DoubaoLoginUrl.LOGIN_URL)
        updateBackState()
    }

    /**
     * The pre-self-test failure screen: the v0.2.1 LoadFailed screen with a dedicated hint naming
     * the empty-content cause plus the probe summary, so a bug report carries the
     * readyState/length/url evidence inline.
     */
    private fun showBlankPageError(summary: String) {
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

    //region Custom Tabs login route.

    /**
     * Opens the login page in a Custom Tab and starts the credential collection.
     *
     * The tab is launched with a session created through a binding to the system browser
     * (`CustomTabsClient.bindCustomTabsService`). Because this app declares its own
     * [LoginCustomTabsService], a supporting browser stores that tab's cookies and localStorage
     * in a profile backed by this app's data directory - the exact data
     * [SessionRepository.saveFromWebView] reads. The in-app WebView keeps running the normal
     * flow meanwhile; whichever route first sees a signed-in session finishes the screen.
     */
    private fun openInCustomTabs() {
        if (signedIn) {
            return
        }
        val client = customTabsClient
        if (client != null) {
            launchCustomTab(client)
            return
        }
        if (customTabsBound) {
            // Bind still connecting; the launch happens in the connection callback.
            customTabLaunchPending = true
            return
        }
        val provider = runCatching { CustomTabsClient.getPackageName(this, null) }.getOrNull()
        if (provider == null) {
            Log.w(TAG, "No Custom Tabs provider available")
            errorHint = getString(R.string.login_customtabs_open_failed)
            return
        }
        customTabLaunchPending = true
        customTabsBound = runCatching {
            CustomTabsClient.bindCustomTabsService(this, provider, customTabsConnection)
        }.getOrDefault(false)
        if (!customTabsBound) {
            customTabLaunchPending = false
            Log.w(TAG, "Binding to the Custom Tabs provider failed")
            errorHint = getString(R.string.login_customtabs_open_failed)
        }
    }

    private fun launchPendingCustomTab() {
        val client = customTabsClient ?: return
        if (!customTabLaunchPending) {
            return
        }
        customTabLaunchPending = false
        launchCustomTab(client)
    }

    private fun launchCustomTab(client: CustomTabsClient) {
        val session = runCatching { client.newSession(null) }.getOrNull()
        val intent = CustomTabsIntent.Builder(session).build()
        runCatching { intent.launchUrl(this, Uri.parse(DoubaoLoginUrl.LOGIN_URL)) }
            .onSuccess {
                customTabLaunched = true
                // Visible feedback that the route is live; the collection loop takes over from
                // here. Only claimed while the screen is still in a quiet wait, so a load-error
                // hint or a render self-test wording is never overwritten.
                if (!signedIn && phase == LoginPhase.Waiting && errorHint.isBlank()) {
                    errorHint = getString(R.string.login_customtabs_opened)
                }
                startCustomTabsCollection()
            }
            .onFailure {
                Log.w(TAG, "Custom Tab launch failed")
                errorHint = getString(R.string.login_customtabs_open_failed)
            }
    }

    /**
     * Polls the system-wide cookie jar for the Doubao session cookies while the user is inside
     * the Custom Tab. The jar is global and shared with the tab (see [LoginCustomTabsService]),
     * so the `sessionid` family appears here as soon as the user signed in on the tab - no
     * in-app page involvement needed.
     *
     * On a confirmed session the offscreen WebView takes over: it loads a doubao.com page so
     * the tea SDK writes the device ids into localStorage, then the exact same extraction the
     * in-app route uses produces the credentials. A missing tea cache (page JS not done yet) is
     * retried on later ticks - the loop only stops on success or when its 10 min window ends.
     *
     * Two diagnostics wrap the wait. Every tick logs the cookie *names* in the jar (values are
     * dropped, presence-only) so a bug report shows whether the tab writes into this app's
     * profile at all. And when not a single doubao cookie has appeared after
     * [CUSTOMTABS_NO_COOKIE_TICKS], the browser clearly ignored the CustomTabsService-backed
     * profile and kept the session in its own data directory - polling further cannot succeed,
     * so the loop stops early with a hint instead of burning the full 10 min window.
     *
     * Cookie handling is presence-only: the header itself is parsed and dropped, no cookie
     * value is ever logged or stored in state.
     */
    private fun startCustomTabsCollection() {
        if (customTabsCollectionJob?.isActive == true || signedIn) {
            return
        }
        customTabsCollectionJob = lifecycleScope.launch {
            var anyCookieSeen = false
            repeat(CUSTOMTABS_COLLECT_MAX_TICKS) { tick ->
                delay(CUSTOMTABS_COLLECT_INTERVAL_MS)
                if (signedIn) {
                    return@launch
                }
                val header = runCatching {
                    CookieManager.getInstance().getCookie(DoubaoLoginUrl.COOKIE_URL)
                }.getOrNull()
                // Presence-only: split the header into names, values are dropped.
                val names = header
                    ?.split(';')
                    ?.map { it.trim().substringBefore('=') }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                Log.i(TAG, "Custom Tabs route tick $tick: cookies=[${names.joinToString(", ")}]")
                if (names.isNotEmpty()) {
                    anyCookieSeen = true
                } else if (tick + 1 >= CUSTOMTABS_NO_COOKIE_TICKS) {
                    Log.w(
                        TAG,
                        "Custom Tabs route: no doubao cookie after 30 s; the browser did not " +
                            "share the session",
                    )
                    if (phase == LoginPhase.Waiting && renderTestStage == null) {
                        errorHint = getString(R.string.login_customtabs_no_session)
                    }
                    return@launch
                }
                val hasSession = header != null &&
                    WebViewCredentialExtractor.hasSessionCookieInHeader(header)
                if (!hasSession || saveInFlight) {
                    return@repeat
                }
                Log.i(TAG, "Custom Tabs route: session cookies present, collecting credentials")
                if (saveFromOffscreenWebView()) {
                    return@launch
                }
            }
            if (!signedIn) {
                Log.w(TAG, "Custom Tabs collection window expired without a session")
                if (phase == LoginPhase.Waiting && errorHint.isBlank()) {
                    errorHint = getString(R.string.login_not_detected)
                }
            }
        }
    }

    private fun obtainOffscreenWebView(): WebView? {
        offscreenWebView?.let { return it }
        return try {
            WebView(this).apply {
                settings.javaScriptEnabled = true
                // The ids this route needs live in localStorage.
                settings.domStorageEnabled = true
                // Never attached to a window: the page has no visible surface, but the DOM,
                // the JS and the localStorage it writes all work - that is all this route needs.
            }.also { offscreenWebView = it }
        } catch (e: RuntimeException) {
            // No usable WebView provider on this ROM.
            Log.w(TAG, "Offscreen WebView unavailable")
            null
        }
    }

    /**
     * Loads the Doubao origin in the offscreen WebView so the page JS writes the tea cache
     * (`__tea_cache_tokens_497858`), then runs the shared extraction. Returns true only when
     * the credentials were persisted; a failed attempt keeps the collection loop alive.
     */
    private suspend fun saveFromOffscreenWebView(): Boolean {
        val view = obtainOffscreenWebView() ?: return false
        saveInFlight = true
        try {
            val loaded = awaitOffscreenPage(view, OFFSCREEN_PAGE_LOAD_TIMEOUT_MS)
            if (!loaded) {
                Log.w(TAG, "Offscreen page load timed out")
                return false
            }
            return sessionRepository.saveFromWebView(view).isSuccess
        } finally {
            saveInFlight = false
        }
    }

    /** Resolves once the offscreen page finished loading, or null after [timeoutMs]. */
    private suspend fun awaitOffscreenPage(view: WebView, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String?) {
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                }
                view.loadUrl(DoubaoLoginUrl.COOKIE_URL)
            }
        } != null

    //endregion

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
        // The user-driven recovery path always starts a clean attempt; a stale self-test stage
        // must not interpret the fresh page's blank check as a probe verdict.
        cancelRenderSelfTest()
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

    /**
     * Strips everything after the path from [url] for the on-screen panel: the login round trip
     * carries tokens in the query (e.g. `from_login`, SSO parameters) that must never be shown
     * on screen. Only `host + path` survives, truncated to fit the panel line.
     */
    private fun sanitizeUrlForDiagnostics(url: String?): String {
        if (url.isNullOrBlank()) {
            return ""
        }
        val clean = runCatching { Uri.parse(url) }.getOrNull()
            ?.takeIf { !it.host.isNullOrBlank() }
            ?.let { it.host + it.path }
            // Parse failed / opaque URI: still drop the query rather than risk leaking tokens.
            ?: url.substringBefore('?')
        return clean.take(DIAG_URL_MAX_LENGTH)
    }

    /**
     * Coming back from a Custom Tab: if the browser honoured the CustomTabsService-backed
     * profile, the login session now sits in this app's cookie jar and the in-app login page -
     * once reloaded - sees it and flips to signed-in on its own (the page-finished gate and the
     * JS poller then trigger the save). If the browser kept the session in its own data
     * directory, the page simply reloads signed-out and the probe/panel report it as before.
     *
     * Fires once per Custom Tab visit ([customTabLaunched] is consumed here, so an immediate
     * second resume - e.g. a permission prompt - never reloads the page twice).
     */
    override fun onResume() {
        super.onResume()
        if (customTabLaunched && !signedIn) {
            customTabLaunched = false
            Log.i(TAG, "Returned from Custom Tab; reloading the in-app login page")
            retryLoad()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (customTabsBound) {
            runCatching { unbindService(customTabsConnection) }
            customTabsBound = false
        }
        customTabsClient = null
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.webViewClient = WebViewClient()
            view.destroy()
        }
        webView = null
        offscreenWebView?.let { view ->
            view.stopLoading()
            view.webViewClient = WebViewClient()
            view.destroy()
        }
        offscreenWebView = null
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

        // Blank-probe verdicts shown by the diagnostics panel (see [diagBlankProbe]). The
        // distinction is the root-cause evidence: "no-callback" = the Chromium render process
        // never executes JS (dead renderer); "returned(...)" = JS runs but the page still paints
        // nothing (compositing failure or page-side refusal).

        /** Probe not sent for the current page yet. */
        private const val BLANK_PROBE_NOT_RUN = "not-run"

        /** Probe sent, callback not received yet. */
        private const val BLANK_PROBE_PENDING = "pending"

        /** Prefix for a probe whose callback ran; carries readyState and body-text length. */
        private const val BLANK_PROBE_RETURNED_PREFIX = "returned"

        /** Prefix for a callback that ran but whose payload could not be decoded. */
        private const val BLANK_PROBE_RETURNED_UNREADABLE_PREFIX = "returned(unreadable:"

        /** How much of an undecodable callback payload the panel shows for diagnosis. */
        private const val BLANK_PROBE_UNREADABLE_SNIPPET_LENGTH = 40

        /** Probe sent, but the JS callback never came back within [BLANK_PROBE_TIMEOUT_MS]. */
        private const val BLANK_PROBE_NO_CALLBACK = "no-callback"

        /** How long the probe may take to answer before being declared no-callback. */
        private const val BLANK_PROBE_TIMEOUT_MS = 8_000L

        /**
         * Probe page for the render self-test: a plain, cache-friendly page the WebView must be
         * able to render. Blank here means the WebView itself cannot render (layer switch or a
         * WebView update is needed); rendering fine means the blank login page is the page's own
         * doing. Never carries credentials - the probe only reads readyState and text length.
         */
        private const val RENDER_TEST_URL = "https://www.baidu.com"

        // Custom Tabs collection loop (see [startCustomTabsCollection]).

        /** Poll interval for the system cookie jar while the user is inside the Custom Tab. */
        private const val CUSTOMTABS_COLLECT_INTERVAL_MS = 2_000L

        /** Ticks the collection loop may run: 300 x 2 s = a 10 minute window. */
        private const val CUSTOMTABS_COLLECT_MAX_TICKS = 300

        /** Ticks with zero doubao cookies before the browser is declared not to share the
         * session: 15 x 2 s = 30 s. */
        private const val CUSTOMTABS_NO_COOKIE_TICKS = 15

        /** How long the offscreen WebView may take to load the Doubao origin before a retry. */
        private const val OFFSCREEN_PAGE_LOAD_TIMEOUT_MS = 15_000L

        /** A page whose rendered body text is shorter than this counts as blank. */
        private const val BLANK_PAGE_TEXT_MIN_LENGTH = 10

        /**
         * Beyond this many WebView history entries BACK stops stepping back and closes the screen
         * instead, so a cyclic redirect chain can never trap the user.
         */
        private const val MAX_BACK_HISTORY = 15

        // On-screen diagnostics panel (see DiagnosticsPanel).

        /** Self-test verdict before the self-test ever produced a result. */
        private const val RENDER_TEST_NOT_RUN = "not run"

        private const val RENDER_TEST_BAIDU_OK = "baidu ok"

        private const val RENDER_TEST_SW_OK = "sw ok"

        private const val RENDER_TEST_BAIDU_BLANK = "baidu blank"

        private const val RENDER_TEST_SW_BLANK = "sw blank"

        /** Intent for the login flow.
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

// On-screen diagnostics panel constants (see DiagnosticsPanel). File-level so both the activity
// and the panel composable can read them.

/** Panel URL line cap (host + path, query stripped). */
private const val DIAG_URL_MAX_LENGTH = 60

/** Panel UA line cap. */
private const val DIAG_UA_MAX_LENGTH = 40

/** How often the panel re-reads the WebView size/layer. */
private const val DIAG_REFRESH_INTERVAL_MS = 500L

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
 * Stage of the automatic render self-test (see [LoginActivity.onBlankPageDetected]).
 *
 * The test always runs the two stages in order: first baidu.com under the current (hardware)
 * rendering, then - only if that came back blank - baidu.com again after switching to the
 * software layer.
 */
private enum class RenderTestStage {

    /** First probe: can the WebView render anything at all (hardware compositing). */
    Baidu,

    /** Second probe: baidu.com again, after the WebView moved to [View.LAYER_TYPE_SOFTWARE]. */
    Software,
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
    diagFinishedCount: Int,
    diagRenderGoneCount: Int,
    diagLastUrl: String,
    diagBlankProbe: String,
    diagRenderTestVerdict: String,
    webViewProvider: () -> WebView?,
    backLabel: String,
    titleLabel: String,
    retryLabel: String,
    openInBrowserLabel: String,
    openInBrowserNote: String,
    systemBrowserLabel: String,
    systemBrowserNote: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onOpenSystemBrowser: () -> Unit,
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
        // Hidden in a plain wait (the scan hint would only add noise), but shown while a render
        // self-test or its software-rendering recovery needs to explain itself in Waiting phase.
        if (phase != LoginPhase.Waiting || errorHint.isNotBlank()) {
            Text(
                text = phaseHint(phase, errorHint),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MurmurColors.OnSurfaceMuted,
                fontSize = 13.sp,
            )
        }
        // On-screen diagnostics: the blank-screen evidence a bug-report screenshot needs. Read
        // only - it never changes phase logic - and shown for every non-Waiting phase (Expired
        // included) or whenever a hint is up; a plain quiet wait adds noise, not evidence.
        if (phase != LoginPhase.Waiting || errorHint.isNotBlank()) {
            DiagnosticsPanel(
                finishedCount = diagFinishedCount,
                renderGoneCount = diagRenderGoneCount,
                lastUrl = diagLastUrl,
                blankProbe = diagBlankProbe,
                renderTestVerdict = diagRenderTestVerdict,
                webViewProvider = webViewProvider,
            )
        }
        // Custom Tabs route: opens the login page with the system-level WebView stack. The
        // in-app WebView above keeps running the normal flow in parallel - the first route that
        // sees a signed-in session collects the credentials and closes the screen (first-wins).
        // Hidden only when there is no WebView provider at all, since the credential collection
        // needs CookieManager either way.
        if (phase != LoginPhase.WebViewUnavailable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenSystemBrowser) {
                    Text(
                        text = systemBrowserLabel,
                        color = MurmurColors.Accent,
                        fontSize = 14.sp,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = systemBrowserNote,
                    modifier = Modifier.weight(1f),
                    color = MurmurColors.OnSurfaceMuted,
                    fontSize = 12.sp,
                )
            }
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

/**
 * Compact, read-only evidence panel shown under the hint bar while the failure state is up.
 *
 * A user screenshot of this panel answers the two open blank-screen questions directly:
 * - the WebView's actual measured size (0x0 would mean a collapsed layout) and its layer type;
 * - whether pages ever finished loading and whether the Chromium render process has been dying
 *   (`renderGone` counting a silent rebuild loop) plus the render self-test verdict.
 *
 * The size line refreshes every [DIAG_REFRESH_INTERVAL_MS] while the composable is
 * alive: [webViewProvider] reads the activity's live field at each tick, so rebuilt WebViews
 * (epoch bumps) and render-process-gone detachments are picked up without storing a view in
 * Compose state. No cookie or credential value is ever displayed - see
 * [LoginActivity.sanitizeUrlForDiagnostics].
 */
@Composable
private fun DiagnosticsPanel(
    finishedCount: Int,
    renderGoneCount: Int,
    lastUrl: String,
    blankProbe: String,
    renderTestVerdict: String,
    webViewProvider: () -> WebView?,
) {
    // Size/layer/UA are not Compose state upstream; poll them into local state on the UI thread.
    var sizeLine by remember { mutableStateOf("WebView: not attached") }
    var uaLine by remember { mutableStateOf("ua=-") }
    // rememberUpdatedState keeps the loop reading the latest provider lambda; the loop itself
    // lives until this panel leaves composition (failure state dismissed / activity finishing).
    val provider by rememberUpdatedState(webViewProvider)
    LaunchedEffect(Unit) {
        while (true) {
            val view = provider()
            if (view == null) {
                sizeLine = "WebView: not attached"
                uaLine = "ua=-"
            } else {
                val layer = if (view.layerType == View.LAYER_TYPE_SOFTWARE) "SW" else "HW"
                sizeLine = "WebView: ${view.width}x${view.height}px @ $layer layer"
                uaLine = "ua=" + (view.settings.userAgentString ?: "")
                    .take(DIAG_UA_MAX_LENGTH)
            }
            delay(DIAG_REFRESH_INTERVAL_MS)
        }
    }
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .background(
                color = MurmurColors.SurfaceElevated,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = sizeLine,
            color = MurmurColors.OnSurfaceMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "finished=$finishedCount renderGone=$renderGoneCount",
            color = MurmurColors.OnSurfaceMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "probe: $blankProbe",
            color = MurmurColors.OnSurfaceMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Text(
            text = "last=$lastUrl",
            color = MurmurColors.OnSurfaceMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Text(
            text = "render-test: $renderTestVerdict",
            color = MurmurColors.OnSurfaceMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = uaLine,
            color = MurmurColors.OnSurfaceMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@Composable
private fun phaseHint(phase: LoginPhase, errorHint: String): String = when (phase) {
    // Waiting normally shows nothing (the row is hidden); a non-blank hint here carries the
    // render self-test wording, which takes precedence over the scan hint.
    LoginPhase.Waiting -> if (errorHint.isNotBlank()) {
        errorHint
    } else {
        stringResource(R.string.login_hint_scan)
    }
    LoginPhase.Expired -> stringResource(R.string.login_not_detected)
    LoginPhase.LoadFailed -> errorHint
    LoginPhase.WebViewUnavailable -> stringResource(R.string.login_webview_unavailable)
}
