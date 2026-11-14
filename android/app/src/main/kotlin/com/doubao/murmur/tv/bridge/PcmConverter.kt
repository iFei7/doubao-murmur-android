package com.doubao.murmur.tv.bridge

import kotlin.math.roundToInt

/**
 * Converts whatever PCM the Binder client pushes into the one format the Doubao endpoint accepts:
 * **16 kHz / mono / Int16 little endian, headerless**.
 *
 * Gboard's recogniser is opened by the host app, and hosts are free to ask for 8 kHz telephony
 * audio, 44.1 kHz music-rate audio or stereo. Feeding any of those to the endpoint unmodified does
 * not fail loudly — it transcribes garbage — so the bridge converts instead of rejecting.
 *
 * The algorithm is deliberately minimal: per-frame channel averaging (downmix) followed by linear
 * interpolation (resample). It is not an anti-aliasing resampler; for speech captured at 48 kHz
 * down to 16 kHz the decimation gain is negligible and the recogniser is robust to it. Keeping it
 * allocation-light matters because [convert] runs on the Binder thread for every 100 ms chunk.
 */
class PcmConverter(
    private val inputSampleRate: Int,
    private val inputChannels: Int,
    private val outputSampleRate: Int = ENGINE_SAMPLE_RATE,
) {

    /** True when the input already is 16 kHz mono; [convert] then returns the array untouched. */
    val isPassthrough: Boolean =
        inputSampleRate == outputSampleRate && inputChannels == IN_MONO

    /**
     * Returns [pcm] as 16 kHz mono Int16LE. A trailing partial frame is ignored rather than
     * rejected: a Binder client that splits an odd-length chunk must not kill the utterance.
     */
    fun convert(pcm: ByteArray): ByteArray {
        if (isPassthrough) return pcm

        val frameCount = pcm.size / (BYTES_PER_SAMPLE * inputChannels)
        if (frameCount <= 0) return ByteArray(0)

        val mono = downmixToMono(pcm, frameCount)
        if (inputSampleRate == outputSampleRate) return mono

        return resample(mono)
    }

    /** Averages all channels of every frame; a mono stream is copied byte-for-byte. */
    private fun downmixToMono(pcm: ByteArray, frameCount: Int): ByteArray {
        if (inputChannels == IN_MONO) {
            return pcm.copyOf(frameCount * BYTES_PER_SAMPLE)
        }
        val out = ByteArray(frameCount * BYTES_PER_SAMPLE)
        for (frame in 0 until frameCount) {
            var sum = 0
            for (channel in 0 until inputChannels) {
                sum += readSample(pcm, (frame * inputChannels + channel) * BYTES_PER_SAMPLE)
            }
            writeSample(out, frame, sum / inputChannels)
        }
        return out
    }

    /** Linear interpolation onto [outputSampleRate]; also used for the mono downmix output. */
    private fun resample(mono: ByteArray): ByteArray {
        val inputFrames = mono.size / BYTES_PER_SAMPLE
        val outputFrames = ((inputFrames.toLong() * outputSampleRate) / inputSampleRate).toInt()
        if (outputFrames <= 0) return ByteArray(0)

        val out = ByteArray(outputFrames * BYTES_PER_SAMPLE)
        val step = inputSampleRate.toDouble() / outputSampleRate
        for (frame in 0 until outputFrames) {
            val source = frame * step
            val left = source.toInt().coerceIn(0, inputFrames - 1)
            val right = (left + 1).coerceAtMost(inputFrames - 1)
            val fraction = source - left
            val value = readSample(mono, left * BYTES_PER_SAMPLE) * (1.0 - fraction) +
                readSample(mono, right * BYTES_PER_SAMPLE) * fraction
            writeSample(out, frame, value.roundToInt().coerceIn(MIN_SAMPLE, MAX_SAMPLE))
        }
        return out
    }

    /** Reads one signed 16 bit little-endian sample; the high byte keeps its sign. */
    private fun readSample(bytes: ByteArray, offset: Int): Int {
        val low = bytes[offset].toInt() and 0xFF
        val high = bytes[offset + 1].toInt() shl 8
        return low or high
    }

    private fun writeSample(bytes: ByteArray, frame: Int, value: Int) {
        bytes[frame * BYTES_PER_SAMPLE] = (value and 0xFF).toByte()
        bytes[frame * BYTES_PER_SAMPLE + 1] = ((value shr 8) and 0xFF).toByte()
    }

    companion object {
        /** The only rate the doubao endpoint accepts. */
        const val ENGINE_SAMPLE_RATE = 16_000

        const val IN_MONO = 1

        private const val BYTES_PER_SAMPLE = 2
        private const val MIN_SAMPLE = -32_768
        private const val MAX_SAMPLE = 32_767

        /** Rates a host can realistically open a recogniser with; anything else is rejected. */
        val SUPPORTED_SAMPLE_RATES = 8_000..192_000
        val SUPPORTED_CHANNELS = 1..2
    }
}
