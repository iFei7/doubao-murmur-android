package com.doubao.murmur.tv.auth

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

    /** True when a session is stored and still decryptable. */
    suspend fun isLoggedIn(): Boolean = store.isLoggedIn()
}
