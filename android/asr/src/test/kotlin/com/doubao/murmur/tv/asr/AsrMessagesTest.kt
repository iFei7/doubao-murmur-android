package com.doubao.murmur.tv.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsrMessagesTest {

    @Test
    fun `result event yields the whole transcript`() {
        val raw = """{"event":"result","result":{"Text":"你好"},"code":0,"message":""}"""
        assertEquals(AsrEvent.Partial("你好"), AsrMessages.parse(raw))
    }

    @Test
    fun `repeated results replace rather than append`() {
        val first = """{"event":"result","result":{"Text":"你"},"code":0}"""
        val second = """{"event":"result","result":{"Text":"你好"},"code":0}"""
        assertEquals(AsrEvent.Partial("你"), AsrMessages.parse(first))
        assertEquals(AsrEvent.Partial("你好"), AsrMessages.parse(second))
    }

    @Test
    fun `empty transcript yields no event`() {
        assertNull(AsrMessages.parse("""{"event":"result","result":{"Text":""},"code":0}"""))
        assertNull(AsrMessages.parse("""{"event":"result","result":{},"code":0}"""))
        assertNull(AsrMessages.parse("""{"event":"result","result":null,"code":0}"""))
        assertNull(AsrMessages.parse("""{"event":"result","code":0}"""))
    }

    @Test
    fun `finish event maps to finished`() {
        assertEquals(AsrEvent.Finished, AsrMessages.parse("""{"event":"finish","result":null,"code":0,"message":""}"""))
        assertEquals(AsrEvent.Finished, AsrMessages.parse("""{"event":"finish"}"""))
    }

    @Test
    fun `login error code maps to a failed event`() {
        val raw = """{"code":710012001,"message":"login invalid"}"""
        assertEquals(AsrEvent.Failed(AsrFailure.LoginExpired), AsrMessages.parse(raw))
    }

    @Test
    fun `server error code keeps the code and message`() {
        val raw = """{"event":"result","result":{"Text":"你好"},"code":500,"message":"boom"}"""
        assertEquals(AsrEvent.Failed(AsrFailure.Server(500, "boom")), AsrMessages.parse(raw))
    }

    @Test
    fun `malformed payloads are ignored`() {
        assertNull(AsrMessages.parse("{not json"))
        assertNull(AsrMessages.parse(""))
        assertNull(AsrMessages.parse("   "))
        assertNull(AsrMessages.parse("[]"))
        assertNull(AsrMessages.parse("""{"event":"unknown","code":0}"""))
    }

    @Test
    fun `unknown fields are ignored`() {
        val raw = """
            {"event":"result","result":{"Text":"你好","Extra":1},"code":0,"message":"",
             "seq":12,"trace_id":"abc"}
        """.trimIndent()
        assertEquals(AsrEvent.Partial("你好"), AsrMessages.parse(raw))
    }

    @Test
    fun `decode exposes the raw message`() {
        val decoded = AsrMessages.decode("""{"event":"result","result":{"Text":"hi"},"code":7,"message":"m"}""")
        assertEquals("result", decoded?.event)
        assertEquals("hi", decoded?.result?.Text)
        assertEquals(7, decoded?.code)
        assertEquals("m", decoded?.message)
    }
}
