package com.doubao.murmur.tv.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrProtocolTest {

    private val credentials = AsrCredentials(
        cookies = linkedMapOf("sessionid" to "test-session", "sid_tt" to "test-sid"),
        deviceId = "device-123",
        webId = "web-456",
    )

    private fun queryOf(url: String): Map<String, String> {
        val query = url.substringAfter("?", "")
        if (query.isEmpty()) return emptyMap()
        return query.split("&").associate { part ->
            part.substringBefore("=") to part.substringAfter("=", "")
        }
    }

    @Test
    fun `buildUrl carries every fixed query parameter`() {
        val url = AsrProtocol.buildUrl(credentials, "tab-1")
        assertTrue(url, url.startsWith(AsrProtocol.WSS_ENDPOINT + "?"))
        for ((name, value) in AsrProtocol.FIXED_QUERY_PARAMS) {
            assertTrue("missing $name=$value in $url", url.contains("$name=$value"))
        }
        assertEquals(12, AsrProtocol.FIXED_QUERY_PARAMS.size)
        assertEquals("pcm", queryOf(url)["format"])
    }

    @Test
    fun `fixed query parameters survive the round trip`() {
        val query = queryOf(AsrProtocol.buildUrl(credentials, "tab-1"))
        for ((name, value) in AsrProtocol.FIXED_QUERY_PARAMS) {
            assertEquals("parameter $name", value, query[name])
        }
    }

    @Test
    fun `dynamic parameters use the captured identifiers and keep protocol order`() {
        val url = AsrProtocol.buildUrl(credentials, "tab-1")
        assertEquals("device-123", queryOf(url)["device_id"])
        assertEquals("web-456", queryOf(url)["web_id"])
        assertEquals("tab-1", queryOf(url)["web_tab_id"])
        assertEquals("tea_uuid mirrors web_id", queryOf(url)["web_id"], queryOf(url)["tea_uuid"])

        val markers = listOf("&device_id=", "&web_id=", "&tea_uuid=", "&web_tab_id=")
        val indexes = markers.map { url.indexOf(it) }
        assertTrue("all dynamic parameters present in $url", indexes.all { it >= 0 })
        assertEquals("device_id, web_id, tea_uuid, web_tab_id must stay in order", indexes.sorted(), indexes)
        assertTrue("dynamic parameters follow the fixed block", indexes.first() > url.indexOf("&format=pcm"))
    }

    @Test
    fun `empty query values keep their equals sign`() {
        val url = AsrProtocol.buildUrl(credentials, "tab-1")
        assertTrue("region/sys_region must not collapse to bare keys: $url", url.contains("region=&sys_region=&"))
        assertEquals("", queryOf(url)["region"])
        assertEquals("", queryOf(url)["sys_region"])
    }

    @Test
    fun `dynamic values are percent encoded`() {
        val odd = AsrCredentials(emptyMap(), "device id", "we&b")
        val url = AsrProtocol.buildUrl(odd, "tab 1")
        val query = queryOf(url)
        assertEquals("device+id", query["device_id"])
        assertEquals("we%26b", query["web_id"])
        assertEquals("we%26b", query["tea_uuid"])
        assertEquals("tab+1", query["web_tab_id"])
        assertFalse("raw ampersand must not leak into the query: $url", url.contains("web_id=we&b"))
    }

    @Test
    fun `cookieHeader joins the jar in iteration order`() {
        assertEquals("sessionid=test-session; sid_tt=test-sid", credentials.cookieHeader())
        assertEquals("", AsrCredentials(emptyMap(), "device", "web").cookieHeader())
        assertEquals("single=value", AsrCredentials(mapOf("single" to "value"), "d", "w").cookieHeader())
    }

    @Test
    fun `protocol constants match the captured client`() {
        assertEquals(16_000, AsrProtocol.SAMPLE_RATE)
        assertEquals(200, AsrProtocol.TRAILING_SILENCE_MS)
        assertEquals(6_400, AsrProtocol.TRAILING_SILENCE_BYTES)
        assertEquals("wss://ws-samantha.doubao.com/samantha/audio/asr", AsrProtocol.WSS_ENDPOINT)
        assertEquals("https://www.doubao.com", AsrProtocol.ORIGIN)
    }

    @Test
    fun `parse delegates to the message codec`() {
        assertEquals(
            AsrEvent.Partial("hi"),
            AsrProtocol.parse("""{"event":"result","result":{"Text":"hi"},"code":0}"""),
        )
        assertNull(AsrProtocol.parse("not json"))
    }
}
