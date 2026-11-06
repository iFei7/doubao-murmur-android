package com.doubao.murmur.tv.asr

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsrFailuresTest {

    @Test
    fun `login codes map to login expired`() {
        assertEquals(
            AsrFailure.LoginExpired,
            AsrFailures.classifyCode(710012001, "login invalid"),
        )
        assertEquals(
            AsrFailure.LoginExpired,
            AsrFailures.classifyCode(710022013, "tourist reach limited"),
        )
    }

    @Test
    fun `invalid request code maps to token invalid`() {
        assertEquals(AsrFailure.TokenInvalid, AsrFailures.classifyCode(709599054, "Invalid"))
        assertEquals(AsrFailure.TokenInvalid, AsrFailures.classifyCode(709599054, null))
    }

    @Test
    fun `unknown non zero code maps to a server failure`() {
        assertEquals(AsrFailure.Server(500, "boom"), AsrFailures.classifyCode(500, "boom"))
        assertEquals(AsrFailure.Server(709599053, ""), AsrFailures.classifyCode(709599053, null))
    }

    @Test
    fun `zero code is not a failure`() {
        assertNull(AsrFailures.classifyCode(0, ""))
        assertNull(AsrFailures.classifyCode(0, "ok"))
        assertNull(AsrFailures.classifyCode(0, null))
    }

    @Test
    fun `messages mentioning the login are treated as login problems`() {
        assertEquals(
            AsrFailure.LoginExpired,
            AsrFailures.classifyCode(500, "user login expired"),
        )
        assertEquals(AsrFailure.LoginExpired, AsrFailures.classifyCode(1, "SESSION expired"))
        assertEquals(AsrFailure.LoginExpired, AsrFailures.classifyCode(1, "unauthorized"))
        assertEquals(AsrFailure.LoginExpired, AsrFailures.classifyCode(1, "bad cookie"))
    }

    @Test
    fun `handshake 401 and 403 mean the login expired`() {
        assertEquals(AsrFailure.LoginExpired, AsrFailures.classifyHandshake(401, null))
        assertEquals(AsrFailure.LoginExpired, AsrFailures.classifyHandshake(403, ""))
        assertEquals(AsrFailure.LoginExpired, AsrFailures.classifyHandshake(403, "<html>"))
    }

    @Test
    fun `handshake 200 with an auth body is a login failure`() {
        val body = """{"code":710012001,"message":"login invalid"}"""
        assertEquals(AsrFailure.LoginExpired, AsrFailures.classifyHandshake(200, body))
        assertEquals(
            AsrFailure.TokenInvalid,
            AsrFailures.classifyHandshake(200, """{"code":709599054,"message":"Invalid"}"""),
        )
    }

    @Test
    fun `handshake without an auth signal is left to the transport classifier`() {
        assertNull(AsrFailures.classifyHandshake(200, "<html>"))
        assertNull(AsrFailures.classifyHandshake(200, null))
        assertNull(AsrFailures.classifyHandshake(200, """{"code":0,"message":""}"""))
        assertNull(AsrFailures.classifyHandshake(200, """{"code":500,"message":"boom"}"""))
        assertNull(AsrFailures.classifyHandshake(500, null))
        assertNull(AsrFailures.classifyHandshake(302, null))
    }

    @Test
    fun `throwables split into timeout and network`() {
        assertEquals(AsrFailure.Timeout, AsrFailures.classifyThrowable(SocketTimeoutException("slow")))
        assertEquals(AsrFailure.Timeout, AsrFailures.classifyThrowable(InterruptedIOException("interrupted")))
        assertEquals(AsrFailure.Network, AsrFailures.classifyThrowable(IOException("connection reset")))
        assertEquals(AsrFailure.Network, AsrFailures.classifyThrowable(IllegalStateException("weird")))
        assertEquals(AsrFailure.Network, AsrFailures.classifyThrowable(java.net.ProtocolException("rejected")))
    }
}
