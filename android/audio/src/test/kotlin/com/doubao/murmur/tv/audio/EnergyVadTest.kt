package com.doubao.murmur.tv.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM tests for the energy detector; [EnergyVad] has no `android.*` dependency. */
class EnergyVadTest {

    @Test
    fun `an all zero frame is not speech`() {
        val vad = EnergyVad()

        assertFalse(vad.isSpeech(ShortArray(FRAME_SIZE)))
    }

    @Test
    fun `a quiet frame below the threshold is not speech`() {
        val vad = EnergyVad()

        assertFalse(vad.isSpeech(ShortArray(FRAME_SIZE) { if (it % 2 == 0) 40 else -40 }))
    }

    @Test
    fun `a full scale square wave is speech`() {
        val vad = EnergyVad()

        assertTrue(vad.isSpeech(loudFrame()))
    }

    @Test
    fun `hangover keeps speech alive for the configured number of silent frames`() {
        val hangoverFrames = 12
        val vad = EnergyVad(thresholdDbfs = -45f, hangoverFrames = hangoverFrames)
        val silence = ShortArray(FRAME_SIZE)

        assertTrue(vad.isSpeech(loudFrame()))
        repeat(hangoverFrames) { index ->
            assertTrue("frame ${index + 1} must still count as speech", vad.isSpeech(silence))
        }
        assertFalse("speech must end once the hangover is used up", vad.isSpeech(silence))
    }

    @Test
    fun `speech ratio counts voiced frames`() {
        val vad = EnergyVad(hangoverFrames = 0)
        vad.isSpeech(loudFrame())
        vad.isSpeech(ShortArray(FRAME_SIZE))
        vad.isSpeech(ShortArray(FRAME_SIZE))
        vad.isSpeech(ShortArray(FRAME_SIZE))

        assertEquals(0.25f, vad.speechRatio(), 0.001f)
    }

    @Test
    fun `reset clears counters and hysteresis`() {
        val vad = EnergyVad(hangoverFrames = 4)
        vad.isSpeech(loudFrame())
        vad.reset()

        assertFalse(vad.isSpeech(ShortArray(FRAME_SIZE)))
        assertEquals(0f, vad.speechRatio(), 0.001f)
    }

    private fun loudFrame(): ShortArray =
        ShortArray(FRAME_SIZE) { if (it % 2 == 0) Short.MAX_VALUE.toInt().toShort() else Short.MIN_VALUE }

    private companion object {
        /** 100 ms at 16 kHz, the frame size used by the capture loop. */
        const val FRAME_SIZE = 1_600
    }
}
