package com.doubao.murmur.tv.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Doubao endpoint only accepts 16 kHz / mono / Int16LE. Gboard may open the recogniser at the
 * microphone's native rate (44.1 or 48 kHz) or in stereo, so the bridge converts instead of
 * rejecting: a rejected utterance is a silently dead dictation button.
 */
class PcmConverterTest {

    @Test
    fun `16 kHz mono is passed through untouched`() {
        val pcm = samplesToBytes(1, -2, 3, -4)
        val converter = PcmConverter(inputSampleRate = 16_000, inputChannels = 1)

        assertTrue(converter.isPassthrough)
        assertSame(pcm, converter.convert(pcm))
    }

    @Test
    fun `48 kHz mono is downsampled to one third of the frames`() {
        // 480 frames at 48 kHz = 10 ms; at 16 kHz that is 160 frames = 320 bytes.
        val pcm = flatSamples(frames = 480, value = 1_000)
        val converted = PcmConverter(inputSampleRate = 48_000, inputChannels = 1).convert(pcm)

        assertEquals(160, converted.size / 2)
        // A constant signal must stay constant through linear interpolation.
        assertTrue(bytesToSamples(converted).all { it == 1_000 })
    }

    @Test
    fun `8 kHz mono is upsampled to twice the frames`() {
        val pcm = flatSamples(frames = 80, value = -500)
        val converted = PcmConverter(inputSampleRate = 8_000, inputChannels = 1).convert(pcm)

        assertEquals(160, converted.size / 2)
        assertTrue(bytesToSamples(converted).all { it == -500 })
    }

    @Test
    fun `stereo frames are averaged into mono before resampling`() {
        // 160 stereo frames at 16 kHz -> 160 mono frames; (1000 + 3000) / 2 = 2000.
        val pcm = interleavedSamples(frames = 160, left = 1_000, right = 3_000)
        val converted = PcmConverter(inputSampleRate = 16_000, inputChannels = 2).convert(pcm)

        assertEquals(160, converted.size / 2)
        assertTrue(bytesToSamples(converted).all { it == 2_000 })
    }

    @Test
    fun `stereo 48 kHz is downmixed and downsampled`() {
        // 480 stereo frames at 48 kHz -> 480 mono frames -> 160 frames at 16 kHz.
        val pcm = interleavedSamples(frames = 480, left = 1_000, right = 3_000)
        val converted = PcmConverter(inputSampleRate = 48_000, inputChannels = 2).convert(pcm)

        assertEquals(320, converted.size)
        assertTrue(bytesToSamples(converted).all { it == 2_000 })
    }

    @Test
    fun `negative samples keep their sign through the downmix`() {
        val pcm = interleavedSamples(frames = 2, left = -10_000, right = -20_000)
        val converted = PcmConverter(inputSampleRate = 16_000, inputChannels = 2).convert(pcm)

        assertArrayEquals(intArrayOf(-15_000, -15_000), bytesToSamples(converted))
    }

    @Test
    fun `empty input yields empty output`() {
        val converted = PcmConverter(inputSampleRate = 48_000, inputChannels = 1).convert(ByteArray(0))

        assertEquals(0, converted.size)
    }

    @Test
    fun `a truncated trailing frame is ignored instead of throwing`() {
        // One complete mono frame plus one stray byte (cannot be a frame at 16 bit).
        val pcm = samplesToBytes(500) + byteArrayOf(0x7F)
        val converted = PcmConverter(inputSampleRate = 16_000, inputChannels = 2).convert(pcm)

        assertEquals(0, converted.size)
    }

    private fun samplesToBytes(vararg values: Int): ByteArray =
        ByteArray(values.size * 2).also { bytes ->
            values.forEachIndexed { index, value -> writeSample(bytes, index, value) }
        }

    private fun flatSamples(frames: Int, value: Int): ByteArray =
        ByteArray(frames * 2).also { bytes ->
            repeat(frames) { index -> writeSample(bytes, index, value) }
        }

    private fun interleavedSamples(frames: Int, left: Int, right: Int): ByteArray =
        ByteArray(frames * 4).also { bytes ->
            repeat(frames) { frame ->
                writeSample(bytes, frame * 2, left)
                writeSample(bytes, frame * 2 + 1, right)
            }
        }

    private fun writeSample(bytes: ByteArray, index: Int, value: Int) {
        bytes[index * 2] = (value and 0xFF).toByte()
        bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun bytesToSamples(bytes: ByteArray): IntArray =
        IntArray(bytes.size / 2) { index ->
            val low = bytes[index * 2].toInt() and 0xFF
            val high = (bytes[index * 2 + 1].toInt() shl 8)
            low or high
        }
}
