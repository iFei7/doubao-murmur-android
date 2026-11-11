package com.doubao.murmur.tv.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure cookie logic behind the login success criterion. `WebViewCredentialExtractor
 * .extract` itself needs a live `WebView` + `CookieManager` and therefore cannot run on the JVM;
 * the parsing and the session-cookie decision are the parts that carry the bug-prone rules.
 */
class WebViewCredentialExtractorTest {

    // region parseCookies

    @Test
    fun `cookie header splits into name value pairs`() {
        val cookies = WebViewCredentialExtractor.parseCookies(
            "ttwid=abc123; sessionid=xyz; sid_guard=sid%7C123%7C456",
        )
        assertEquals(3, cookies.size)
        assertEquals("abc123", cookies["ttwid"])
        assertEquals("xyz", cookies["sessionid"])
        // Only the first '=' separates: pipes and later '=' survive in the value.
        assertEquals("sid%7C123%7C456", cookies["sid_guard"])
    }

    @Test
    fun `cookie header tolerates whitespace and empty entries`() {
        val cookies = WebViewCredentialExtractor.parseCookies("  a=1 ;  ;  ;b=2")
        assertEquals("1", cookies["a"])
        assertEquals("2", cookies["b"])
    }

    @Test
    fun `cookie entries without a separator are dropped`() {
        assertTrue(WebViewCredentialExtractor.parseCookies("garbage; =novalue; ok=1").containsKey("ok"))
        assertTrue(WebViewCredentialExtractor.parseCookies("garbage; =novalue").isEmpty())
    }

    @Test
    fun `null and empty cookie headers yield an empty jar`() {
        assertTrue(WebViewCredentialExtractor.parseCookies(null).isEmpty())
        assertTrue(WebViewCredentialExtractor.parseCookies("").isEmpty())
    }

    // endregion

    // region hasSessionCookie - the login success criterion

    @Test
    fun `pre login jar with only ttwid is not a session`() {
        // The state a freshly loaded www.doubao.com/chat carries before the user signs in: the tea
        // cache ids exist in localStorage and ttwid is already in the jar. This is the jar that
        // used to fake a "login success".
        val preLogin = WebViewCredentialExtractor.parseCookies(
            "ttwid=1xxx; _ga=GA1.1.2; s_v_web_id=verify_x; msToken=abc",
        )
        assertFalse(WebViewCredentialExtractor.hasSessionCookie(preLogin))
    }

    @Test
    fun `sessionid counts as logged in`() {
        val jar = WebViewCredentialExtractor.parseCookies("ttwid=1xxx; sessionid=real-session")
        assertTrue(WebViewCredentialExtractor.hasSessionCookie(jar))
    }

    @Test
    fun `sid_tt counts as logged in`() {
        val jar = WebViewCredentialExtractor.parseCookies("sid_tt=another-real-session")
        assertTrue(WebViewCredentialExtractor.hasSessionCookie(jar))
    }

    @Test
    fun `sessionid_ss counts as logged in`() {
        val jar = WebViewCredentialExtractor.parseCookies("sessionid_ss=third-variant")
        assertTrue(WebViewCredentialExtractor.hasSessionCookie(jar))
    }

    @Test
    fun `present but empty session cookie is not a session`() {
        // doubao.com clears the value during logout; a leftover empty entry must not pass.
        val jar = WebViewCredentialExtractor.parseCookies("sessionid=; ttwid=1xxx")
        assertFalse(WebViewCredentialExtractor.hasSessionCookie(jar))
    }

    @Test
    fun `empty jar is never a session`() {
        assertFalse(WebViewCredentialExtractor.hasSessionCookie(emptyMap()))
    }

    @Test
    fun `session cookie names cover the sessionid family`() {
        assertEquals(
            setOf("sessionid", "sid_tt", "sessionid_ss"),
            WebViewCredentialExtractor.SESSION_COOKIE_NAMES,
        )
    }

    // endregion
}
