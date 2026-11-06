package com.doubao.murmur.tv.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Streaming sample-rate converter that turns whatever the device captures into the exact format the
 * Doubao endpoint accepts: **16 kHz, mono, Int16** (`docs/doubao-wss-asr-spec.md` section 4.1).
 *
 * Pure Kotlin on purpose - no `android.*` import - so the resampling maths is covered by a plain
 * JUnit test instead of an instrumented one.
 *
 * ## Why this matters
 * Most Android TV boxes refuse to open `AudioRecord` at 16 kHz and hand out 48 kHz instead. The
 * server does not resample: feeding it 48 kHz audio at a 16 kHz declared rate plays everything back
 * three times too slow and recognition collapses to gibberish. Dropping the high bands properly
 * (instead of naive decimation) is what keeps the recognised text readable.
 *
 * ## Filter
 * Windowed-sinc low pass, 31 taps, cutoff at 0.9 x the output Nyquist, evaluated at a fractional
 * delay so that 44100 -> 16000 (a non-integer ratio of 441/160) works as well as 48000 -> 16000
 * (3:1). Coefficients are precomputed per phase in the constructor; the filter is causal, so it
 * delays the signal by [HALF] input samples (~0.3 ms at 48 kHz) instead of needing look-ahead.
 *
 * ## Streaming contract
 * All state - the fractional phase, the retained history, the output counter - survives across
 * [process] calls. Resampling each 100 ms chunk independently would reset the phase at every chunk
 * boundary and produce a click every 100 ms, which the recogniser hears as noise.
 *
 * The instance is **not** thread safe; it is meant to be owned by the single capture thread.
 *
 * @param inputRate rate delivered by the capture device, e.g. 48000 or 44100.
 * @param outputRate rate required by the ASR endpoint, 16000 by default.
 */
class LinearResampler(
    private val inputRate: Int,
    private val outputRate: Int = OUTPUT_RATE,
) {

    private val greatestCommonDivisor: Int = gcd(inputRate, outputRate)

    /** Input samples advanced per output sample, numerator of the reduced ratio. */
    private val ratioNum: Long = (inputRate / greatestCommonDivisor).toLong()

    /** Distinct fractional phases, denominator of the reduced ratio. */
    private val phases: Int = outputRate / greatestCommonDivisor

    init {
        require(inputRate > 0 && outputRate > 0) {
            "sample rates must be positive but were input=$inputRate output=$outputRate"
        }
        require(phases <= MAX_PHASES) {
            "unsupported rate pair $inputRate -> $outputRate (needs $phases phases)"
        }
    }

    /** Cutoff in cycles per input sample: 0.9 x output Nyquist, never above input Nyquist. */
    private val cutoff: Double = 0.45 * minOf(1.0, outputRate.toDouble() / inputRate)

    private val coefficients: Array<FloatArray> = buildCoefficients()

    private var history = FloatArray(INITIAL_HISTORY_CAPACITY)

    /** Absolute index of `history[0]` in the (conceptually infinite) input stream. */
    private var historyStart = 0L
    private var historyLength = 0

    /** Absolute index of the next output sample to emit. */
    private var outputIndex = 0L

    /**
     * Converts one chunk and returns the samples that became complete because of it.
     *
     * The returned array can be shorter than `input.size / inputRate * outputRate` on the first
     * calls, because the filter needs [HALF] samples of history before it can produce a sample with
     * full gain; that startup is zero padded. In steady state the total output length matches
     * `totalInput * outputRate / inputRate` within one sample regardless of how the input was
     * chunked.
     */
    fun process(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        if (inputRate == outputRate) return input.copyOf()

        append(input)

        val room = input.size * outputRate / inputRate + OUTPUT_SLACK
        val out = ShortArray(room)
        var produced = 0
        val lastAvailable = historyStart + historyLength - 1

        while (produced < room) {
            val position = outputIndex * ratioNum
            val base = position / phases
            if (base > lastAvailable) break

            val phase = (position % phases).toInt()
            val taps = coefficients[phase]
            var index = base - (TAPS - 1)
            var acc = 0f
            for (tap in 0 until TAPS) {
                acc += sampleAt(index) * taps[tap]
                index++
            }
            out[produced++] = acc.roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            outputIndex++
        }

        trim()
        return if (produced == out.size) out else out.copyOf(produced)
    }

    /** Drops all buffered state so the next [process] starts a fresh stream. */
    fun reset() {
        historyStart = 0L
        historyLength = 0
        outputIndex = 0L
    }

    private fun append(input: ShortArray) {
        val required = historyLength + input.size
        if (required > history.size) {
            var capacity = history.size
            while (capacity < required) capacity *= 2
            history = history.copyOf(capacity)
        }
        for (i in input.indices) {
            history[historyLength + i] = input[i].toFloat()
        }
        historyLength += input.size
    }

    private fun sampleAt(index: Long): Float {
        val offset = index - historyStart
        if (offset < 0L || offset >= historyLength) return 0f
        return history[offset.toInt()]
    }

    /** Drops history that no future output can reach any more: memory stays bounded. */
    private fun trim() {
        val nextBase = (outputIndex * ratioNum) / phases
        val keepFrom = nextBase - (TAPS - 1)
        if (keepFrom <= historyStart) return

        val drop = (keepFrom - historyStart).toInt().coerceAtMost(historyLength)
        if (drop <= 0) return
        System.arraycopy(history, drop, history, 0, historyLength - drop)
        historyLength -= drop
        historyStart += drop
    }

    private fun buildCoefficients(): Array<FloatArray> {
        val table = Array(phases) { FloatArray(TAPS) }
        // The window is centred on the sinc peak at u = 0 and is slightly wider than the tap span
        // so that no tap is wasted on a zero weight at the edges.
        val windowHalf = HALF + 1.0
        for (phase in 0 until phases) {
            val fraction = phase.toDouble() / phases
            var sum = 0.0
            for (tap in 0 until TAPS) {
                val u = fraction + HALF - tap
                val x = 2.0 * cutoff * u
                val sinc = if (abs(x) < SINC_EPSILON) 1.0 else sin(PI * x) / (PI * x)
                val window = 0.5 + 0.5 * cos(PI * u / windowHalf)
                val value = sinc * window
                table[phase][tap] = value.toFloat()
                sum += value
            }
            // Unity DC gain: without this every phase would attenuate by a few percent and the
            // level would wobble at the phase rate.
            if (abs(sum) > SINC_EPSILON) {
                for (tap in 0 until TAPS) {
                    table[phase][tap] = (table[phase][tap] / sum).toFloat()
                }
            }
        }
        return table
    }

    companion object {
        /** Output rate required by the Doubao ASR endpoint. */
        const val OUTPUT_RATE: Int = 16_000

        /** Tap count of the anti-aliasing filter; odd so the kernel has a centre. */
        private const val TAPS = 31
        private const val HALF = TAPS / 2

        private const val MAX_PHASES = 1024
        private const val INITIAL_HISTORY_CAPACITY = 8192
        private const val OUTPUT_SLACK = 2
        private const val SINC_EPSILON = 1e-9

        private fun gcd(a: Int, b: Int): Int {
            var x = a
            var y = b
            while (y != 0) {
                val t = x % y
                x = y
                y = t
            }
            return if (x == 0) 1 else x
        }
    }
}
