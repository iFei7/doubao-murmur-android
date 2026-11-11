package com.doubao.murmur.tv.auth

import android.webkit.CookieManager
import android.webkit.WebView
import com.doubao.murmur.tv.asr.AsrCredentials

/**
 * Facade over the credential store plus the WebView extraction, shared by `:app` (login flow) and
 * `:ime` (needs the session every time the microphone key is pressed).
 *
 * Keeping the two halves together means the IME never has to know how the credentials were
 * obtained, and the login activity never touches `EncryptedSharedPreferences` directly.
 *
 * @param store where the session is persisted; [EncryptedCredentialStore] in production.
 */
class SessionRepository(private val store: CredentialStore) {

    /** Current session, or null when the user has to log in again. */
    suspend fun current(): AsrCredentials? = store.load()

    /**
     * Extracts the session from a logged-in [webView] and persists it.
     *
     * The result carries the same credentials that were stored, so the caller can start recording
     * immediately instead of reading them back. A failed [Result] leaves any previously stored
     * session untouched.
     */
    suspend fun saveFromWebView(webView: WebView): Result<AsrCredentials> {
        val extracted = WebViewCredentialExtractor.extract(webView)
        val credentials = extracted.getOrElse { return Result.failure(it) }
        return try {
            store.save(credentials)
            Result.success(credentials)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Forgets the session; called on logout and after the server reports an expired login. */
    suspend fun clear() = store.clear()

    /**
     * Clears the **global** WebView cookie jar, in addition to [clear].
     *
     * The encrypted store and the cookie jar are two copies of the same session: wiping only the
     * store leaves the old cookies inside `CookieManager`, so the very next login attempt finds a
     * stale (pre-expiry) `sessionid` in the jar and the extractor reports success instantly - a
     * re-login that never actually asked the user for anything. Logout and expiry therefore must
     * clear both; the login screen itself must **not** call this before showing the page, because
     * a WebView opened for re-authentication may legitimately rely on a still-valid session.
     *
     * Never throws: `CookieManager.getInstance()` fails on ROMs without a WebView provider, and a
     * failed cleanup must not take down the IME that is merely reporting an expired session.
     */
    fun clearWebViewState() {
        try {
            val manager = CookieManager.getInstance()
            manager.removeAllCookies(null)
            manager.flush()
        } catch (e: RuntimeException) {
            // No WebView provider on this ROM; nothing else to clear.
        }
    }

    /** True when a session is stored and still decryptable. */
    suspend fun isLoggedIn(): Boolean = store.isLoggedIn()
}
