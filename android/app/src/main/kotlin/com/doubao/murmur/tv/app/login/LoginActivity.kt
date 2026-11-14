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
import android.os.Message
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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.lifecycle.lifecycleScope
import com.doubao.murmur.tv.app.AppGraph
import com.doubao.murmur.tv.app.R
import com.doubao.murmur.tv.auth.DoubaoLoginUrl
import com.doubao.murmur.tv.auth.SessionRepository
import com.doubao.murmur.tv.auth.parseBlankProbePayload
import com.doubao.murmur.tv.ui.theme.MurmurColors
import com.doubao.murmur.tv.ui.theme.MurmurTvTheme
import kotlinx.coroutines.delay
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

    /**
     * Route 2 of the login detection (upstream macOS `decidePolicyFor`): extraction straight
     * from the `from_login=1` URL marker, without the [checkLoginFlagThenSave] localStorage
     * gate. The upstream desktop clients treat the marker as login proof on its own - and
     * `flow_web_has_login` is a key none of them ever read. The extractor's own session-cookie
     * criterion still keeps a pre-login cookie jar out.
     */
    private val directSaveCheck = Runnable { attemptSave() }

    /** Route 3: DOM fallback probe, scheduled [DOM_LOGIN_CHECK_DELAY_MS] after page-finished. */
    private val domLoginCheck = Runnable { runDomLoginFallback() }

    /**
     * The login WebView, **pre-created** as an activity field and only *wrapped* by the Compose
     * `AndroidView` factory (see [LoginScreen]): on some devices - OnePlus KB2003 / Android 14
     * verified on v0.2.7 - a WebView constructed inside the `AndroidView` factory never paints,
     * while the same instance inside a plain `FrameLayout` renders fine. That is the community
     * workaround for the Compose `AndroidView` white-screen bug (SO #78482022), the same shape
     * the IME's Compose+FrameLayout container already uses.
     *
     * The instance is fully prepared before it is ever attached: settings, mobile UA, clients,
     * the document-start injected login detector and the initial `loadUrl` all happen in
     * [configureLoginWebView]; the factory only adds the finished view to a `FrameLayout`.
     */
    private var webView: WebView? = null

    /** Set once [ensureLoginWebView] proved there is no usable WebView provider on this ROM. */
    private var webViewUnavailable = false

    /**
     * Throwaway WebViews created by the popup fallback ([WebChromeClient.onCreateWindow]) to
     * surface a `window.open` target URL. They are never attached to a window; they are
     * destroyed as soon as the URL has been handed to the main WebView, and anything left over
     * is destroyed in [onDestroy].
     */
    private val popupWebViews = mutableListOf<WebView>()

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
     * (factory -> [ensureLoginWebView] -> fresh, fully configured instance) through
     * recomposition.
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
     * [configureLoginWebView], so the fix survives a render-process rebuild for the whole session.
     */
    private var softwareRendering = false

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
                    // The pre-created-instance accessor (see [ensureLoginWebView]): the
                    // factory wraps whatever this returns in a FrameLayout, it never builds a
                    // WebView itself (white-screen workaround).
                    provideWebView = ::ensureLoginWebView,
                    backLabel = stringResource(R.string.action_back),
                    titleLabel = stringResource(R.string.login_title),
                    retryLabel = stringResource(R.string.login_action_retry),
                    openInBrowserLabel = stringResource(R.string.login_action_open_in_browser),
                    openInBrowserNote = stringResource(R.string.login_open_in_browser_note),
                    onBack = { finish() },
                    onRetry = ::onRetryClicked,
                    onOpenInBrowser = ::openInBrowser,
                )
            }
        }

        // The back gesture/gesture bar walks back through the WebView history (e.g. out of a wrong
        // QR page, back to the entry page) - but only while there is history to walk AND the login
        // has not succeeded yet (see [updateBackState]). Whenever the callback is disabled the
        // system default applies, which finishes the activity. Assigned *before* the WebView is
        // created: configureLoginWebView calls updateBackState, which reads the callback.
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                webView?.goBack()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        // Pre-create the login WebView *outside* composition: a WebView built inside an
        // AndroidView factory never paints on some devices (OnePlus KB2003 / Android 14
        // verified on v0.2.7), while the same pre-created instance wrapped in a FrameLayout
        // does - see [ensureLoginWebView] and the LoginScreen factory comment. The call also
        // surfaces a missing WebView provider before the first frame is composed.
        ensureLoginWebView()
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

    /**
     * Returns the pre-created login WebView, creating it on first use. Called once from
     * [onCreate] (outside composition - the white-screen workaround) and from the LoginScreen
     * factory, so a render-process rebuild ([webViewEpoch]) also reaches a fresh, fully
     * configured instance through the same path.
     *
     * Returns `null` - and flips the screen to [LoginPhase.WebViewUnavailable] - on a ROM
     * without a usable WebView provider; the verdict is sticky, a provider cannot appear
     * mid-session.
     */
    private fun ensureLoginWebView(): WebView? {
        webView?.let { return it }
        if (webViewUnavailable) {
            return null
        }
        val view = try {
            WebView(this)
        } catch (e: RuntimeException) {
            Log.w(TAG, "WebView provider unavailable (construction failed)")
            webViewUnavailable = true
            markWebViewUnavailable()
            return null
        }
        // Assign before configuring: the clients below read [webView] (updateBackState et al.).
        webView = view
        return try {
            configureLoginWebView(view)
            view
        } catch (e: RuntimeException) {
            // A ROM without a usable WebView provider also throws on the first settings or
            // CookieManager call, not only on construction. Drop the half-built view so a
            // later attempt cannot touch it.
            runCatching { view.destroy() }
            webView = null
            webViewUnavailable = true
            markWebViewUnavailable()
            null
        }
    }

    /**
     * The UA the login WebView sends: the device's own WebView UA, i.e. the mobile Chrome-on-
     * Android form the system browser uses. Preferred over a hard-coded string so the login page
     * is served the mobile variant the browser gets on *this* device (Doubao branches on the UA:
     * mobile UA -> mobile OAuth login page, desktop UA -> PC layout).
     *
     * [MOBILE_LOGIN_UA] is only a fallback for a ROM whose WebView refuses to report a UA; it is
     * the mobile shape verified against the Doubao mobile OAuth login page.
     */
    private fun resolveLoginUserAgent(view: WebView): String {
        val deviceUa = runCatching { WebSettings.getDefaultUserAgent(this) }.getOrNull()
            ?: runCatching { view.settings.userAgentString }.getOrNull()
        return deviceUa?.takeIf { it.isNotBlank() } ?: MOBILE_LOGIN_UA
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureLoginWebView(view: WebView) {
        // Document-start injection (upstream parity: all three desktop clients inject their
        // login detector at document start - macOS/Linux WKUserScript .atDocumentStart, Windows
        // Tauri initialization_script). androidx.webkit backports the API to the Chrome WebView
        // provider; when the provider predates it, the injection degrades to onPageStarted
        // below (slightly later, same semantics).
        val documentStartActive = runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                    view,
                    DOCUMENT_START_LOGIN_JS,
                    setOf(DOCUMENT_START_ORIGIN_RULE),
                )
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (!documentStartActive) {
            Log.w(TAG, "Document-start injection unsupported; degrading to onPageStarted")
        }

        view.settings.apply {
            // Without JS the Doubao login page does not render at all.
            javaScriptEnabled = true
            // device_id / web_id live in localStorage, the socket URL needs them.
            domStorageEnabled = true
            databaseEnabled = true
            // Nothing in this flow needs local files or content providers; keep it minimal.
            allowFileAccess = false
            allowContentAccess = false
            // Login popups (doubao.com opens its login helpers with window.open) must reach
            // onCreateWindow, which loads them into this WebView - the upstream macOS route
            // (WKUIDelegate.createWebViewWith: load the request into the current WebView).
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            setGeolocationEnabled(false)
            // Mobile UA, deliberately: under a mobile UA Doubao serves its mobile OAuth login
            // page (what a phone browser shows), under a desktop UA it serves the PC layout.
            // v0.2.8 shipped the desktop UA and real-device testing showed the PC-style page;
            // the v0.2.7 white screen was the AndroidView factory bug above, not the UA.
            // See [resolveLoginUserAgent].
            userAgentString = resolveLoginUserAgent(view)
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

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                // Popup fallback, upstream macOS parity (WKUIDelegate.createWebViewWith):
                // doubao.com opens its login helpers with window.open, which Android would
                // otherwise turn into a second window this TV flow has nowhere to show. The
                // upstream loads the popup request into the *current* WebView; Android does not
                // hand out the popup URL here, so a throwaway WebView accepts the transport
                // just long enough to surface the target URL.
                val transport = (resultMsg.obj as? WebView.WebViewTransport) ?: return false
                var redirected = false
                val popup = WebView(view.context)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        if (!redirected) {
                            redirected = true
                            loadPopupRequestIntoMainWebView(request.url?.toString(), popup)
                        }
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(v, url, favicon)
                        // Some providers skip shouldOverrideUrlLoading for the very first
                        // navigation of a transport WebView; the page-started callback still
                        // carries the URL.
                        if (!redirected) {
                            redirected = true
                            loadPopupRequestIntoMainWebView(url, popup)
                        }
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                popupWebViews.add(popup)
                return true
            }
        }

        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.i(TAG, "Page started: $url")
                if (!documentStartActive && url != null &&
                    DoubaoLoginUrl.isSessionCheckCandidate(url)
                ) {
                    // Degraded injection: the WebView provider predates document-start scripts,
                    // so the login detector lands here instead - slightly later, same script,
                    // same semantics. Calls the page fired before this point are missed, which
                    // only delays detection (the DOM fallback and the URL marker still fire).
                    Log.i(TAG, "Injecting the login detector via onPageStarted (degraded)")
                    view.evaluateJavascript(DOCUMENT_START_LOGIN_JS, null)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "Page finished: $url")
                diagPageFinishedCount += 1
                diagLastFinishedUrl = sanitizeUrlForDiagnostics(url)
                // The history shape changed; BACK may gain or lose its page-navigation role.
                updateBackState()
                // Auxiliary localStorage poll on every navigation (kept from v0.2.7 as a
                // safety net; upstream never read `flow_web_has_login`, so it is no longer the
                // primary criterion - the document-start profile-API interception is).
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
                // not part of the login round trip are worth a check.
                if (url == null || !DoubaoLoginUrl.isSessionCheckCandidate(url)) {
                    return
                }
                mainHandler.removeCallbacks(saveCheck)
                mainHandler.removeCallbacks(directSaveCheck)
                mainHandler.removeCallbacks(domLoginCheck)
                if (url.contains(FROM_LOGIN_MARKER)) {
                    // Route 2 (upstream macOS decidePolicyFor): the URL marker alone proves the
                    // login round trip came back - no localStorage gate in front of it.
                    mainHandler.postDelayed(directSaveCheck, 0L)
                } else {
                    mainHandler.postDelayed(saveCheck, PAGE_SETTLE_DELAY_MS)
                }
                // Route 3 (upstream macOS checkLoginState): if the "to login" button is still
                // in the DOM after the page settled, nobody is signed in yet - surface it.
                mainHandler.postDelayed(domLoginCheck, DOM_LOGIN_CHECK_DELAY_MS)
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
                // recreated, its factory calls [ensureLoginWebView] again, and the login
                // URL is loaded from scratch. Returning true tells the system the app
                // handled it.
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
                onLoginState = { loggedIn -> runOnUiThread { onLoginStateEvent(loggedIn) } },
            ),
            BRIDGE_NAME,
        )
        view.loadUrl(DoubaoLoginUrl.LOGIN_URL)
        updateBackState()
    }

    /**
     * Popup fallback (upstream macOS `WKUIDelegate.createWebViewWith` semantics): the
     * `window.open` target is loaded into the *current* login WebView instead of a new window.
     * The throwaway transport WebView is destroyed right after the handover; it was never
     * attached to a window, so nothing was ever visible on it.
     */
    private fun loadPopupRequestIntoMainWebView(url: String?, popup: WebView) {
        if (signedIn) {
            return
        }
        if (url.isNullOrBlank()) {
            Log.w(TAG, "Popup opened without a usable URL; ignoring")
            return
        }
        Log.i(TAG, "Popup request loaded into the login WebView: ${sanitizeUrlForDiagnostics(url)}")
        webView?.loadUrl(url)
        updateBackState()
        // Destroying the popup mid-callback would upset the navigation it is still reporting;
        // a posted destroy is enough - the view was never attached, nothing on screen owns it.
        mainHandler.post {
            popupWebViews.remove(popup)
            runCatching {
                popup.stopLoading()
                popup.webViewClient = WebViewClient()
                popup.destroy()
            }
        }
    }

    /**
     * Route 1 of the login detection: the verdict from the document-start interception of
     * `/alice/profile/self` (upstream parity - macOS/Linux `inject-websocket.js`, Windows
     * `login.rs` all classify exactly this API). `true` means the profile API answered with an
     * authenticated profile, the strongest signal of the three routes, so the extraction starts
     * immediately. `false` only logs: the page re-fires the API after each navigation, and the
     * other routes own the user-visible wait states.
     */
    private fun onLoginStateEvent(loggedIn: Boolean) {
        if (loggedIn) {
            Log.i(TAG, "Profile API reports signed in; extracting credentials")
            attemptSave()
        } else {
            Log.i(TAG, "Profile API reports signed out")
        }
    }

    /**
     * Route 3 of the login detection (upstream macOS `checkLoginState`): a `button
     * [data-testid="to_login_button"]` still present in the DOM two seconds after the page
     * settled is the page saying nobody is signed in. Only the hint is touched, and only while
     * the screen is in a quiet wait - the user may simply not have started signing in yet, and
     * a retry button that blinks in and out is worse than a progress bar on a 3 m screen.
     */
    private fun runDomLoginFallback() {
        val view = webView ?: return
        if (signedIn) {
            return
        }
        view.evaluateJavascript(DOM_LOGIN_BUTTON_QUERY_JS) { raw ->
            val loginButtonPresent = raw == "true"
            Log.i(TAG, "DOM fallback: login button present=$loginButtonPresent")
            if (loginButtonPresent && !signedIn && phase == LoginPhase.Waiting &&
                errorHint.isBlank()
            ) {
                errorHint = getString(R.string.login_not_detected)
            }
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
     * Blank-page verdict. **v0.2.8: the two-stage render self-test (baidu probe / software
     * layer) is demoted to diagnostics-only** - it no longer drives any user-visible state.
     * The blank screen it was built to diagnose turned out to be the Compose `AndroidView`
     * factory bug, fixed by the pre-created-WebView-inside-FrameLayout structure (see
     * [LoginScreen]); the probe's own navigations would now only add confusion. The verdict is
     * reported the plain v0.2.1 way (LoadFailed hint + retry) and the machine stays behind
     * [RENDER_SELF_TEST_ENABLED] for on-device diagnosis.
     */
    private fun onBlankPageDetected(summary: String) {
        if (signedIn || phase != LoginPhase.Waiting) {
            return
        }
        Log.w(TAG, "Blank page detected: $summary")
        if (!RENDER_SELF_TEST_ENABLED) {
            showBlankPageError(summary)
            return
        }
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
     *   ([softwareRendering] stays true, so [configureLoginWebView] applies it to any rebuilt WebView)
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

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        popupWebViews.forEach { popup ->
            runCatching {
                popup.stopLoading()
                popup.webViewClient = WebViewClient()
                popup.destroy()
            }
        }
        popupWebViews.clear()
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

        /** Log tag for the WebView error paths. Only error codes and descriptions are logged. */
        private const val TAG = "LoginActivity"

        /**
         * Fallback mobile UA, used only when [resolveLoginUserAgent] cannot read the device's
         * own WebView UA. Mobile Chrome-on-Android shape, verified on the OnePlus KB2003 /
         * Android 14 (the same form the device browser sends): under a mobile UA Doubao serves
         * the mobile OAuth login page, under a desktop UA the PC layout.
         */
        private const val MOBILE_LOGIN_UA =
            "Mozilla/5.0 (Linux; Android 14; KB2003 Build/UKQ1.230924.001) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.8103.38 Mobile " +
                "Safari/537.36"

        /**
         * Origin rule for the document-start injection: the script runs on every doubao.com
         * page (the login round trip also bounces through `passport.doubao.com`, whose pages
         * need the visibility override as much as the main origin does).
         */
        private const val DOCUMENT_START_ORIGIN_RULE = "https://*.doubao.com"

        /** HTTP status codes at or above this value turn the main frame into a load failure. */
        private const val HTTP_ERROR_MIN = 400

        /** Cap on the raw error description appended to the load-failure hint. */
        private const val ERROR_DETAIL_MAX_LENGTH = 40

        /** Provider package whose version is logged for WebView diagnostics. */
        private const val WEBVIEW_PROVIDER_PACKAGE = "com.google.android.webview"

        /** Delay between page-finished and the blank-page probe. */
        private const val BLANK_CHECK_DELAY_MS = 4_000L

        /**
         * Delay between page-finished and the DOM login-button fallback probe (route 3,
         * upstream macOS `checkLoginState` runs it 2 s after `didFinish` too).
         */
        private const val DOM_LOGIN_CHECK_DELAY_MS = 2_000L

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
         *
         * v0.2.8: the self-test is demoted to diagnostics-only (see [onBlankPageDetected]) and
         * disabled by default via [RENDER_SELF_TEST_ENABLED]; the probe never navigates the
         * user's WebView unless the flag is switched on for a debugging session.
         */
        private const val RENDER_TEST_URL = "https://www.baidu.com"

        /**
         * v0.2.8 demotion switch for the two-stage render self-test: `false` keeps the machine
         * (stages, verdicts, probe page) compiled and loggable for diagnosis, but the blank-page
         * verdict goes straight to the plain LoadFailed screen and the probe never runs. The
         * white screen the machine was built for turned out to be the Compose AndroidView
         * factory bug, fixed by the FrameLayout wrapper.
         */
        private const val RENDER_SELF_TEST_ENABLED = false

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
    private val onLoginState: (Boolean) -> Unit,
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
     * Called by the document-start script once the intercepted `/alice/profile/self` response
     * has been classified (upstream parity: macOS/Linux `inject-websocket.js`, Windows
     * `login.rs`). Carries a bare boolean - no nickname, no cookie value ever crosses the
     * bridge; the session material itself is read by the repository straight from the cookie
     * jar and localStorage. Invoked on a WebView JavaBridge thread.
     */
    @Keep
    @JavascriptInterface
    fun onLoginState(loggedIn: Boolean) {
        onLoginState.invoke(loggedIn)
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
 * Document-start login detector, ported line-by-line from the upstream desktop clients'
 * `inject-websocket.js` (macOS `doubao-murmur/Resources/`, Linux
 * `linux/src/doubao_murmur/resources/`; Windows `login.rs` inlines the same logic):
 *
 * 1. Page Visibility override - verbatim upstream; keeps the page from throttling its JS
 *    timers and session keep-alive requests while it is occluded.
 * 2. `fetch` + `XMLHttpRequest` interception of `/alice/profile/self` - verbatim upstream;
 *    the page's own profile call is classified into a signed-in / signed-out verdict.
 *
 * The only delta against the upstream source is the notification channel: the desktops post
 * to `window.webkit.messageHandlers.asr_handler`, here the verdict crosses the existing
 * [LoginBridge] (`window.MurmurBridge.onLoginState(bool)`) as a bare boolean (nickname
 * dropped - the extractor never needed it). The script adds no network behaviour of its own;
 * it only wraps calls the page makes anyway, and it is scoped to the doubao.com wildcard
 * origin rule [LoginActivity.DOCUMENT_START_ORIGIN_RULE].
 *
 * Injected via `WebViewCompat.addDocumentStartJavaScript` (upstream parity: all three desktop
 * clients run this at document start); on a provider without that feature it degrades to
 * `onPageStarted` injection (see [LoginActivity.configureLoginWebView]).
 */
private const val DOCUMENT_START_LOGIN_JS = """
(function() {
    'use strict';

    // --- Page Visibility API override (upstream inject-websocket.js, verbatim) ---
    // Prevent the page from knowing it's hidden so that JS timers and
    // session keep-alive requests continue running in the background.
    Object.defineProperty(document, 'visibilityState', {
        get: function() { return 'visible'; },
        configurable: true
    });
    Object.defineProperty(document, 'hidden', {
        get: function() { return false; },
        configurable: true
    });
    // Suppress visibilitychange events so page logic never enters a "hidden" branch
    document.addEventListener('visibilitychange', function(e) {
        e.stopImmediatePropagation();
    }, true);

    // --- Fetch interception: detect profile API for login status ---
    var OriginalFetch = window.fetch;
    window.fetch = function() {
        var url = arguments[0];
        var urlStr = (typeof url === 'string') ? url : (url && url.url) || '';

        var promise = OriginalFetch.apply(this, arguments);

        if (urlStr.includes('/alice/profile/self')) {
            promise.then(function(response) {
                var cloned = response.clone();
                cloned.json().then(function(data) {
                    if (data && data.code === 0 && data.data && data.data.profile_brief) {
                        notifyLoginState(true);
                    } else {
                        notifyLoginState(false);
                    }
                }).catch(function() {
                    notifyLoginState(false);
                });
            }).catch(function() {
                // Network error - can't determine login
            });
        }

        return promise;
    };

    // --- XHR interception: fallback for profile API ---
    var OriginalXHROpen = XMLHttpRequest.prototype.open;
    var OriginalXHRSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url) {
        this.__doubaoMurmurUrl = url;
        return OriginalXHROpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function() {
        if (this.__doubaoMurmurUrl && this.__doubaoMurmurUrl.includes('/alice/profile/self')) {
            this.addEventListener('load', function() {
                try {
                    var data = JSON.parse(this.responseText);
                    if (data && data.code === 0 && data.data && data.data.profile_brief) {
                        notifyLoginState(true);
                    }
                } catch(e) {}
            });
        }
        return OriginalXHRSend.apply(this, arguments);
    };

    // Android notification channel: the desktop clients post to their native message
    // handlers (window.webkit.messageHandlers.asr_handler), here the verdict crosses the
    // existing JavascriptInterface bridge as a bare boolean.
    function notifyLoginState(loggedIn) {
        try {
            if (window.MurmurBridge) { window.MurmurBridge.onLoginState(loggedIn); }
        } catch (e) { }
    }
})();
"""

/**
 * Route 3 DOM query (upstream `inject-dom.js` / `checkLoginState`): the "to login" button is
 * the page's own signed-out marker. One-shot, reads a boolean only.
 */
private const val DOM_LOGIN_BUTTON_QUERY_JS =
    "try { !!document.querySelector('button[data-testid=\"to_login_button\"]') } " +
        "catch (e) { false }"

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
    provideWebView: () -> WebView?,
    backLabel: String,
    titleLabel: String,
    retryLabel: String,
    openInBrowserLabel: String,
    openInBrowserNote: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenInBrowser: () -> Unit,
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
                // The factory never constructs the WebView itself: the instance is pre-created
                // and fully configured by the activity (see [LoginActivity.ensureLoginWebView])
                // and only wrapped here, matching the community workaround for the Compose
                // AndroidView white-screen bug (SO #78482022, OnePlus KB2003 / Android 14
                // verified on v0.2.7): a WebView returned straight from the factory never
                // paints, while the same instance inside a plain FrameLayout renders fine -
                // the same shape the IME's Compose+FrameLayout container already uses.
                // update{} must never call loadUrl or every recomposition reloads the page and
                // throws away whatever the user typed.
                factory = { context ->
                    val loginWebView = provideWebView()
                    if (loginWebView == null) {
                        // No WebView provider: show the toolbar over a blank surface instead of
                        // a white screen or a crash (provideWebView already posted the
                        // WebViewUnavailable state).
                        FrameLayout(context)
                    } else {
                        FrameLayout(context).apply {
                            setBackgroundColor(webViewBackground)
                            // Same themed background on the WebView itself: it only shows
                            // before the page paints and after a load failure, but it must
                            // follow the theme or a light-mode failure renders as an
                            // unexplained black rectangle.
                            loginWebView.setBackgroundColor(webViewBackground)
                            addView(
                                loginWebView,
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    }
                },
                update = { view ->
                    view.setBackgroundColor(webViewBackground)
                    (view as? ViewGroup)?.getChildAt(0)?.setBackgroundColor(webViewBackground)
                },
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
