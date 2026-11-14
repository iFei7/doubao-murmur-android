package com.doubao.murmur.tv.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * START parsing and the shared-token rule. The localhost socket can be reached by any app on the
 * device, so a malformed or unauthenticated START must never reach [AsrSessionManager].
 */
class BridgeProtocolsTest {

    @Test
    fun `parses the documented START payload`() {
        val request = BridgeProtocols.parseStart(
            """{"token":"dba-voice-1","sampleRate":16000,"channels":1}""".toByteArray(),
        )

        assertNotNull(request)
        assertEquals("dba-voice-1", request!!.token)
        assertEquals(16_000, request.sampleRate)
        assertEquals(1, request.channels)
    }

    @Test
    fun `ignores unknown fields so the client can grow`() {
        val request = BridgeProtocols.parseStart(
            """{"token":"dba-voice-1","sampleRate":48000,"channels":2,"future":true}""".toByteArray(),
        )

        assertEquals(48_000, request?.sampleRate)
        assertEquals(2, request?.channels)
    }

    @Test
    fun `fills defaults for a minimal payload`() {
        val request = BridgeProtocols.parseStart("""{"token":"dba-voice-1"}""".toByteArray())

        assertEquals(16_000, request?.sampleRate)
        assertEquals(1, request?.channels)
    }

    @Test
    fun `rejects bytes that are not a JSON object`() {
        assertNull(BridgeProtocols.parseStart("not json".toByteArray()))
        assertNull(BridgeProtocols.parseStart(ByteArray(0)))
    }

    @Test
    fun `accepts exactly the shared token`() {
        assertTrue(BridgeProtocols.isTokenValid(StartRequest(token = BridgeProtocols.TOKEN)))
    }

    @Test
    fun `rejects a missing or different token`() {
        assertFalse(BridgeProtocols.isTokenValid(StartRequest(token = "")))
        assertFalse(BridgeProtocols.isTokenValid(StartRequest(token = "dba-voice-2")))
        assertFalse(BridgeProtocols.isTokenValid(StartRequest(token = " DBA-VOICE-1 ")))
    }

    @Test
    fun `encodes an error payload the client can parse`() {
        val payload = BridgeProtocols.encodeError(BridgeErrorCodes.NOT_SIGNED_IN, "no doubao session")
        val decoded = BridgeProtocols.parseError(payload)

        assertEquals(BridgeErrorCodes.NOT_SIGNED_IN, decoded?.code)
        assertEquals("no doubao session", decoded?.message)
    }

    @Test
    fun `an error message containing quotes stays valid JSON`() {
        val payload = BridgeProtocols.encodeError(BridgeErrorCodes.ENGINE_FAILURE, "say \"hi\"")

        assertEquals("say \"hi\"", BridgeProtocols.parseError(payload)?.message)
    }
}
