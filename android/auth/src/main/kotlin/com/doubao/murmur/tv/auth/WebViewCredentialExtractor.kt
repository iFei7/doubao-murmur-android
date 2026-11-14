package com.doubao.murmur.tv.auth

import android.webkit.CookieManager
import android.webkit.WebView
import com.doubao.murmur.tv.asr.AsrCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Pulls the Doubao session out of a logged-in [WebView].
 *
 * ## What is read, and from where
 * | Field | Source | Notes |
 * |---|---|---|
 * | cookies | `CookieManager.getCookie("https://www.doubao.com")` | includes `httpOnly` cookies |
 * | `deviceId` | `samantha_web_web_id.web_id` → `__tea_cache_tokens_497858.user_unique_id` → `tea_cache.web_id` | the server's `device_id` query param; see the fallback note below |
 * | `webId` | `localStorage['__tea_cache_tokens_497858'].web_id` | the `web_id` / `tea_uuid` param |
 *
 * ## `deviceId` fallback chain
 * `samantha_web_web_id` is **not** guaranteed to exist: doubao.com dropped it when it renamed the
 * `localStorage` keys to the `flow_*` scheme. The `:asr` client only needs `device_id` to be a
 * stable identifier (it is copied into the query string verbatim, never cross-checked against
 * `web_id`), so when the primary key is gone the value is taken from the tea SDK cache, which still
 * carries the same id: `user_unique_id` first, then `web_id`. This mirrors
 * `linux/src/doubao_murmur/ui/login_window.py:231-239`, where the same gap once caused "logged in
 * but no params ever saved".
 *
 * The `sessionid` / `sid_tt` cookies are `httpOnly`, so `document.cookie` cannot see them. That is
 * exactly why the Android path is easier than the iOS one: `CookieManager.getCookie(url)` returns
 * the httpOnly cookies too, whereas iOS needs an explicit `WKHTTPCookieStore` round trip. The URL
 * argument also does the domain filtering for us - only cookies that would be sent to
 * `www.doubao.com` come back, so no separate `.doubao.com` matching is needed here.
 *
 * ## `evaluateJavascript` result shape
 * The callback receives a **JSON-encoded JavaScript value**, i.e. a quoted, escaped string, not the
 * raw payload: the script returns `{"device":...}`, the callback delivers `"{\"device\":...}"`. The
 * string literal therefore has to be unwrapped with [JSONTokener] before the inner object can be
 * parsed. Skipping that step is the classic way this code fails with "not logged in" on a WebView
 * that is in fact logged in.
 *
 * ## Success criterion
 * `deviceId` and `webId` non-empty **and** the cookie set contains at least one of the upstream
 * session-cookie names (see [SESSION_COOKIE_NAMES]: the `sessionid` family plus `uid_tt` and
 * `passport_auth_status`, matching the upstream Windows client's `SESSION_COOKIE_HINTS`) with a
 * non-empty value. The tea SDK cache (`deviceId` / `webId`) and pre-login cookies such as
 * `ttwid` exist as soon as the page loads, so requiring *any* cookie would report success for a
 * page the user has not logged in on yet. The session cookies are httpOnly, and
 * `CookieManager.getCookie(url)` returns httpOnly cookies too (unlike `document.cookie`), so
 * "cookie jar not synced yet" would equally hide them - and a device-id-only credential is
 * rejected by the server anyway (`AsrFailure.LoginExpired`).
 */
object WebViewCredentialExtractor {

    /** How long the page may take to answer the localStorage query before we give up. */
    private const val EVALUATE_TIMEOUT_MS = 5_000L

    private const val FIELD_WEB_ID = "web_id"
    private const val FIELD_USER_UNIQUE_ID = "user_unique_id"
    private const val FIELD_DEVICE = "device"
    private const val FIELD_TEA = "tea"
    private const val FIELD_LOGGED_IN = "loggedIn"

    /**
     * Cookie names that only exist once the user actually signed in. The login flow is judged on
     * these; pre-login cookies (`ttwid` etc.) are not proof of a session. Any one hit is enough.
     *
     * Aligned with the upstream Windows client (`windows/src-tauri/src/login.rs`,
     * `SESSION_COOKIE_HINTS`): `uid_tt` and `passport_auth_status` complete the set - some
     * accounts land post-login URLs that carry those variants before the `sessionid` family
     * appears in the jar, and the upstream desktop clients treat a single hit as login proof.
     */
    internal val SESSION_COOKIE_NAMES: Set<String> = setOf(
        "sessionid",
        "sessionid_ss",
        "sid_tt",
        "uid_tt",
        "passport_auth_status",
    )

    private val SCRIPT = """
        (function(){try{return JSON.stringify({
          device: localStorage.getItem('samantha_web_web_id'),
          tea: localStorage.getItem('__tea_cache_tokens_497858'),
          loggedIn: localStorage.getItem('flow_web_has_login')
        });}catch(e){return '';}})()
    """.trimIndent()

    /**
     * Reads the session material from [webView] and wraps it in [AsrCredentials].
     *
     * Must be called after the page reached a Doubao page (see
     * [DoubaoLoginUrl.isSessionCheckCandidate]) so the login cookies are already in the jar.
     * Never throws; every failure - including a WebView that never answers - is reported in the
     * [Result].
     */
    suspend fun extract(webView: WebView): Result<AsrCredentials> = withContext(Dispatchers.Main) {
        try {
            val raw = withTimeoutOrNull(EVALUATE_TIMEOUT_MS) { evaluate(webView, SCRIPT) }
                ?: return@withContext Result.failure(
                    IllegalStateException("WebView did not answer the localStorage query in time"),
                )

            val snapshot = decodeSnapshot(raw)
                ?: return@withContext Result.failure(
                    IllegalStateException("empty localStorage snapshot, the page is not loaded"),
                )

            val deviceRaw = snapshot.optString(FIELD_DEVICE, "")
            val teaRaw = snapshot.optString(FIELD_TEA, "")

            val teaCache = parseObject(teaRaw)
            val webId = fieldOf(teaCache, FIELD_WEB_ID)
            // doubao.com dropped `samantha_web_web_id` when it renamed the localStorage keys to the
            // `flow_*` scheme, so the primary source can legitimately be empty. The tea SDK cache
            // still carries the same id, which is what the upstream desktop client falls back to
            // (linux/src/doubao_murmur/ui/login_window.py:231-239). Without this chain, every
            // account whose key was migrated would fail the login flow forever.
            val deviceId = fieldOf(parseObject(deviceRaw), FIELD_WEB_ID)
                ?: fieldOf(teaCache, FIELD_USER_UNIQUE_ID)
                ?: webId
            val cookieHeader = readCookieHeader()
            val cookies = parseCookies(cookieHeader)

            if (deviceId == null || webId == null || !hasSessionCookie(cookies)) {
                val loggedIn = snapshot.optString(FIELD_LOGGED_IN, "")
                return@withContext Result.failure(
                    IllegalStateException(
                        "not logged in (deviceId=${presence(deviceId)}, webId=${presence(webId)}, " +
                            "cookies=${cookies.size}, sessionCookie=${hasSessionCookie(cookies)}, " +
                            "flow_web_has_login=$loggedIn)",
                    ),
                )
            }

            Result.success(
                AsrCredentials(cookies = cookies, deviceId = deviceId, webId = webId),
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun evaluate(webView: WebView, script: String): String? =
        suspendCancellableCoroutine { continuation ->
            // evaluateJavascript must run on the thread that created the WebView; extract() already
            // switched to Dispatchers.Main for this reason.
            webView.evaluateJavascript(script) { value ->
                if (continuation.isActive) {
                    continuation.resume(value)
                }
            }
        }

    private fun readCookieHeader(): String? = try {
        CookieManager.getInstance().getCookie(DoubaoLoginUrl.COOKIE_URL)
    } catch (e: RuntimeException) {
        // CookieManager throws when called before WebView has been initialised on some ROMs.
        null
    }

    /** Unwraps the JSON string literal returned by `evaluateJavascript`. */
    private fun decodeSnapshot(raw: String?): JSONObject? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return try {
            val payload = JSONTokener(raw).nextValue() as? String
            if (payload.isNullOrEmpty()) null else JSONObject(payload)
        } catch (e: JSONException) {
            null
        }
    }

    /** Reads a field out of a localStorage JSON blob; treats absent, empty and `null` alike. */
    private fun fieldOf(json: JSONObject?, field: String): String? {
        if (json == null) return null
        val value = json.optString(field, "")
        return if (isAbsent(value)) null else value
    }

    private fun parseObject(raw: String?): JSONObject? {
        if (isAbsent(raw)) return null
        return try {
            JSONObject(raw)
        } catch (e: JSONException) {
            null
        }
    }

    /** `localStorage.getItem` returns null, which `JSON.stringify` renders as the string "null". */
    private fun isAbsent(raw: String?): Boolean = raw.isNullOrEmpty() || raw == "null"

    /**
     * Splits a `Cookie` header into name/value pairs.
     *
     * Only the **first** `=` is treated as the separator: values such as
     * `sid_guard=abc|1234|5184000|Mon, 01 Jun 2026` or base64 payloads with `=` padding would
     * otherwise be truncated and the server would reject the session.
     */
    internal fun parseCookies(header: String?): Map<String, String> {
        if (header.isNullOrEmpty()) return emptyMap()
        val cookies = LinkedHashMap<String, String>()
        for (entry in header.split(";")) {
            val pair = entry.trim()
            if (pair.isEmpty()) continue
            val separator = pair.indexOf('=')
            if (separator <= 0) continue
            val name = pair.substring(0, separator).trim()
            if (name.isEmpty()) continue
            cookies[name] = pair.substring(separator + 1)
        }
        return cookies
    }

    /**
     * True when [cookies] contains at least one [SESSION_COOKIE_NAMES] entry with a non-empty
     * value. A present-but-empty `sessionid` (doubao.com clears the value during logout) counts
     * as "not logged in", matching what the server would decide anyway.
     */
    internal fun hasSessionCookie(cookies: Map<String, String>): Boolean =
        SESSION_COOKIE_NAMES.any { name -> !cookies[name].isNullOrEmpty() }

    /**
     * True when the raw cookie header - as returned by `CookieManager.getCookie(url)` - contains
     * a session cookie. Same criterion as [hasSessionCookie], exposed for callers that only have
     * the header (e.g. the Custom Tabs login route polling the global jar without a live page).
     * Presence-only: callers must not log the header, only this boolean.
     */
    fun hasSessionCookieInHeader(header: String?): Boolean =
        hasSessionCookie(parseCookies(header))

    private fun presence(value: String?): String = if (value == null) "missing" else "ok"
}
