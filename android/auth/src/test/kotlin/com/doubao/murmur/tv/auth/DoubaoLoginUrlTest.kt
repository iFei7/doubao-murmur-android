package com.doubao.murmur.tv.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `isSessionCheckCandidate` decides whether an `onPageFinished` URL is worth a credential check.
 * It encodes **origin trust only**: a passing URL can still be a logged-out page. The actual
 * logged-in verdict is made later by the `sessionid` cookie check and the `flow_web_has_login`
 * flag - these tests pin the URL rules, not the login state.
 */
class DoubaoLoginUrlTest {

    @Test
    fun `doubao pages are candidates`() {
        assertTrue(DoubaoLoginUrl.isSessionCheckCandidate(DoubaoLoginUrl.LOGIN_URL))
        assertTrue(DoubaoLoginUrl.isSessionCheckCandidate("https://www.doubao.com/chat/123456"))
        assertTrue(DoubaoLoginUrl.isSessionCheckCandidate("https://www.doubao.com/?from_login=1"))
    }

    @Test
    fun `passport hosts are rejected - pre login cookie jar`() {
        assertFalse(
            DoubaoLoginUrl.isSessionCheckCandidate("https://passport.doubao.com/accounts/page/login"),
        )
        assertFalse(
            DoubaoLoginUrl.isSessionCheckCandidate("https://passport-fe.doubao.com/again"),
        )
    }

    @Test
    fun `sso hops are rejected`() {
        assertFalse(
            DoubaoLoginUrl.isSessionCheckCandidate("https://sso.doubao.com/sso/login?ticket=abc"),
        )
        assertFalse(
            DoubaoLoginUrl.isSessionCheckCandidate("https://www.doubao.com/sso/callback?code=1"),
        )
    }

    @Test
    fun `foreign origins are rejected`() {
        assertFalse(DoubaoLoginUrl.isSessionCheckCandidate("https://www.example.com/chat"))
        // Known limitation, pinned on purpose: the match is a plain substring search, so a host
        // that merely *contains* "doubao.com" (doubao.com.evil.example) slips through. The login
        // WebView only ever loads DoubaoLoginUrl.LOGIN_URL and follows its own redirects, so the
        // exposure is limited to that surface; if that ever changes, switch to host parsing here.
        assertTrue(DoubaoLoginUrl.isSessionCheckCandidate("https://doubao.com.evil.example/"))
    }

    @Test
    fun `host matching is case insensitive`() {
        assertTrue(DoubaoLoginUrl.isSessionCheckCandidate("https://WWW.DOUBAO.COM/chat"))
        assertFalse(DoubaoLoginUrl.isSessionCheckCandidate("https://SSO.DOUBAO.COM/x"))
    }

    @Test
    fun `empty url is rejected`() {
        assertFalse(DoubaoLoginUrl.isSessionCheckCandidate(""))
    }
}
