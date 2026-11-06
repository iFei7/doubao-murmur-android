package com.doubao.murmur.tv.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Frame level energy detector used to decide *when to stop*, not *what to send*.
 *
 * Pure Kotlin (no `android.*`) so it is covered by a plain JUnit test.
 *
 * ## Scope
 * This is an aid for the "auto stop after the user stops talking" behaviour only. Audio is never
 * trimmed with it: cutting the first frames that sit below the threshold swallows the initial
 * consonant (the "sh" of "上海"), and the Doubao endpoint produces better text when it receives the
 * leading silence too. The server itself is fine with silence - only trailing silence handling
 * needs the 200 ms pad that the transport layer already appends.
 *
 * ## Hysteresis
 * A single loud frame flips the detector to speech and keeps it there for [hangoverFrames]
 * subsequent frames. Without the hangover the brief energy dips between syllables would be read as
 * end-of-utterance and the recording would stop mid sentence.
 *
 * @param thresholdDbfs RMS level above which a frame counts as speech. -45 dBFS sits above the
 *   noise floor of a quiet room and below normal speech at TV viewing distance (two to three
 *   metres from a far-field microphone).
 * @param hangoverFrames how many silent frames keep the detector in the speech state.
 */
class EnergyVad(
    private val thresholdDbfs: Float = DEFAULT_THRESHOLD_DBFS,
    private val hangoverFrames: Int = DEFAULT_HANGOVER_FRAMES,
) {

    private var hangoverLeft = 0
    private var totalFrames = 0L
    private var speechFrames = 0L

    /**
     * Classifies one frame (mono Int16) and returns true while it is considered speech.
     *
     * An empty frame is treated as silence but still counts as a frame, which keeps
     * [speechRatio] meaningful for callers that skip buffers.
     */
    fun isSpeech(frame: ShortArray): Boolean {
        totalFrames++
        val loud = dbfs(frame) >= thresholdDbfs

        if (loud) {
            hangoverLeft = hangoverFrames
        }
        val speech = loud || hangoverLeft > 0
        if (!loud && hangoverLeft > 0) {
            hangoverLeft--
        }
        if (speech) {
            speechFrames++
        }
        return speech
    }

    /** Fraction of frames classified as speech since the last [reset], in 0f..1f. */
    fun speechRatio(): Float {
        if (totalFrames == 0L) return 0f
        return (speechFrames.toDouble() / totalFrames).toFloat()
    }

    /** Clears counters and hysteresis, e.g. before the next utterance. */
    fun reset() {
        hangoverLeft = 0
        totalFrames = 0L
        speechFrames = 0L
    }

    private fun dbfs(frame: ShortArray): Float {
        if (frame.isEmpty()) return SILENCE_DBFS
        var sum = 0.0
        for (sample in frame) {
            val value = sample.toDouble()
            sum += value * value
        }
        val rms = sqrt(sum / frame.size)
        if (rms < 1.0) return SILENCE_DBFS
        return (20.0 * log10(rms / FULL_SCALE)).toFloat()
    }

    companion object {
        /** RMS level in dBFS above which a frame counts as speech. */
        const val DEFAULT_THRESHOLD_DBFS: Float = -45f

        /** Frames of silence tolerated before the detector reports end of speech. */
        const val DEFAULT_HANGOVER_FRAMES: Int = 12

        private const val FULL_SCALE = 32768.0
        private const val SILENCE_DBFS = -120f
    }
}
