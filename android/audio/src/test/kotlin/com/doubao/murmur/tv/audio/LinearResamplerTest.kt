package com.doubao.murmur.tv.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plain JVM tests for the resampler - the class has no `android.*` dependency, so these run on a
 * bare CI runner (`gradle :audio:test`).
 *
 * The assertions target the three things that actually break on real TV hardware: output length
 * (a mismatch desynchronises the server), level (a low-pass that also kills speech makes the
 * recogniser silent) and chunk-boundary continuity (a per-chunk reset produces a click every
 * 100 ms).
 */
class LinearResamplerTest {

    @Test
    fun `48000 to 16000 keeps the expected sample count`() {
        val input = sine(frequency = 1_000.0, amplitude = 16_000.0, count = 4_800)
        val output = LinearResampler(48_000).process(input)

        assertTrue(
            "expected 1600 +/- 1 samples but got ${output.size}",
            abs(output.size - 1_600) <= 1,
        )
    }

    @Test
    fun `48000 to 16000 does not attenuate the signal`() {
        val input = sine(frequency = 1_000.0, amplitude = 16_000.0, count = 4_800)
        val output = LinearResampler(48_000).process(input)

        val gain = rms(output) / rms(input)
        assertTrue("gain must stay above 0.5 but was $gain", gain > 0.5)
    }

    @Test
    fun `chunked processing yields the same signal as one shot`() {
        val input = sine(frequency = 1_000.0, amplitude = 16_000.0, count = 4_800)

        val oneShot = LinearResampler(48_000).process(input)

        val chunked = LinearResampler(48_000)
        val first = chunked.process(input.copyOfRange(0, 2_400))
        val second = chunked.process(input.copyOfRange(2_400, 4_800))
        val joined = first + second

        assertEquals(
            "chunking must not change the number of samples",
            oneShot.size,
            joined.size,
        )
        var maxDifference = 0
        for (i in joined.indices) {
            maxDifference = maxOf(maxDifference, abs(joined[i] - oneShot[i]))
        }
        assertTrue(
            "chunk boundaries must not reset the phase, max difference was $maxDifference",
            maxDifference <= 2,
        )
    }

    @Test
    fun `silence stays silent`() {
        val output = LinearResampler(48_000).process(ShortArray(4_800))

        assertTrue("silence must stay silent", output.all { abs(it.toInt()) < 32 })
    }

    @Test
    fun `44100 to 16000 keeps the expected sample count`() {
        val input = sine(frequency = 1_000.0, amplitude = 16_000.0, count = 4_410, rate = 44_100)
        val output = LinearResampler(44_100).process(input)

        assertTrue(
            "expected ~1600 samples but got ${output.size}",
            abs(output.size - 1_600) <= 1,
        )
    }

    @Test
    fun `44100 to 16000 does not attenuate the signal`() {
        val input = sine(frequency = 1_000.0, amplitude = 16_000.0, count = 4_410, rate = 44_100)
        val output = LinearResampler(44_100).process(input)

        val gain = rms(output) / rms(input)
        assertTrue("gain must stay above 0.5 but was $gain", gain > 0.5)
    }

    @Test
    fun `reset starts a new stream`() {
        val resampler = LinearResampler(48_000)
        resampler.process(ShortArray(4_800))
        resampler.reset()

        val output = resampler.process(ShortArray(4_800))
        assertTrue(
            "a reset resampler must emit the same amount again but emitted ${output.size}",
            abs(output.size - 1_600) <= 1,
        )
    }

    private fun sine(
        frequency: Double,
        amplitude: Double,
        count: Int,
        rate: Int = 48_000,
    ): ShortArray = ShortArray(count) { index ->
        (amplitude * sin(2.0 * PI * frequency * index / rate)).toInt().toShort()
    }

    private fun rms(data: ShortArray): Double {
        if (data.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in data) {
            val value = sample.toDouble()
            sum += value * value
        }
        return sqrt(sum / data.size)
    }
}
