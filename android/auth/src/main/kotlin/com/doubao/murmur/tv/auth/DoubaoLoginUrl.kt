package com.doubao.murmur.tv.auth

/**
 * URLs the login screen needs, and the rule for deciding when the credential extraction can run.
 *
 * Kept as plain constants (no `android.*`) so the URL matching logic is unit-testable and so the
 * `:app` module and this module cannot drift apart on the origin string: cookies must be read from
 * exactly the origin they belong to, otherwise `CookieManager` hands back an empty header.
 */
object DoubaoLoginUrl {

    /** Landing page loaded into the login WebView. */
    const val LOGIN_URL: String = "https://www.doubao.com/chat"

    /** Origin used for `CookieManager.getCookie`; must match the pages the user logs in on. */
    const val COOKIE_URL: String = "https://www.doubao.com"

    private const val HOST = "doubao.com"

    /**
     * True when [url] is a Doubao page whose **cookie jar is worth a credential check**.
     *
     * The name is deliberately *not* `isLoggedIn`: a `www.doubao.com/chat` URL passes this check
     * both before and after the user actually signed in - the only thing the URL carries is the
     * origin, not the login state. Whether the session really exists is decided later, by
     * [WebViewCredentialExtractor] (a `sessionid` family cookie must be present) and by the
     * `flow_web_has_login` localStorage flag.
     *
     * The account flow bounces through `passport.doubao.com` and an SSO callback; reading the cookie
     * jar while still on those pages yields pre-login state, so they are rejected. Callers should
     * re-check on every `onPageFinished` rather than once, because the redirect chain finishes on
     * different URLs depending on whether the user logged in with a password, a QR code or SSO.
     */
    fun isSessionCheckCandidate(url: String): Boolean {
        if (url.isEmpty()) return false
        val lower = url.lowercase()
        if (!lower.contains(HOST)) return false
        if (lower.contains("passport")) return false
        if (lower.contains("sso")) return false
        return true
    }
}
