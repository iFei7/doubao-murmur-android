package com.doubao.murmur.tv.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `parseBlankProbePayload` is the pure half of the app-side blank-page probe. The payload shape
 * - a double-encoded JSON string literal - is what the WebView `evaluateJavascript` callback
 * really delivers; missing the second unwrapping layer kept the blank-screen detection dead
 * from v0.2.2 to v0.2.6 (every probe read "returned(unreadable)").
 */
class BlankProbePayloadTest {

    @Test
    fun `a double-encoded object unwraps into a reading`() {
        // What the callback really receives: the JS return value JSON-encoded as a string
        // literal, i.e. the inner JSON text is quoted and escaped one more time. Built with
        // JSONObject.quote so the literal is exactly what JSON.stringify produces.
        val inner = """{"ready":"complete","len":123,"url":"https://www.doubao.com/chat"}"""
        val raw = JSONObject.quote(inner)
        assertEquals(
            BlankProbeReading(ready = "complete", len = 123, url = "https://www.doubao.com/chat"),
            parseBlankProbePayload(raw),
        )
    }

    @Test
    fun `a directly encoded object also parses`() {
        val reading = parseBlankProbePayload("""{"ready":"interactive","len":0}""")
        assertEquals(BlankProbeReading(ready = "interactive", len = 0, url = ""), reading)
    }

    @Test
    fun `missing fields decode to their defaults`() {
        val reading = parseBlankProbePayload("""{"ready":"complete"}""")
        assertEquals(-1, reading?.len)
        assertEquals("", reading?.url)
    }

    @Test
    fun `null and empty payloads decode to null`() {
        assertNull(parseBlankProbePayload(null))
        assertNull(parseBlankProbePayload(""))
        assertNull(parseBlankProbePayload("null"))
        // The JSON-encoded null the probe script's catch branch delivers.
        assertNull(parseBlankProbePayload("\"null\""))
    }

    @Test
    fun `garbage decodes to null instead of throwing`() {
        assertNull(parseBlankProbePayload("not-json"))
        assertNull(parseBlankProbePayload("42"))
        assertNull(parseBlankProbePayload("""["ready"]"""))
    }
}
