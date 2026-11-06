package com.doubao.murmur.tv.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * `AudioRecord` wrapper that hands out exactly the byte stream the Doubao endpoint wants:
 * 16 kHz / mono / Int16 little endian, no frame headers (`docs/doubao-wss-asr-spec.md` 4.1 + 4.4).
 *
 * ## Source choice
 * [MediaRecorder.AudioSource.VOICE_RECOGNITION] rather than `MIC`: on TV hardware the speakers sit
 * centimetres from the microphone, so the platform's acoustic echo cancellation and noise
 * suppression are not a nicety - without them the recogniser transcribes the TV's own audio. It
 * also disables the automatic gain control that would otherwise pump the level between words.
 *
 * ## Threading
 * [start] returns immediately; capture runs on a dedicated thread promoted to
 * `THREAD_PRIORITY_AUDIO`. [onChunk] is therefore invoked **on the capture thread** and must not
 * block: forward the bytes to a queue or a socket, never do disk I/O or UI work in it.
 *
 * Exactly one of [onChunk] invocations and [onError] can be in flight at a time, and neither is
 * called after [stop] returns.
 *
 * The instance is reusable: [stop] then [start] again is supported.
 */
class PcmRecorder(
    private val context: Context,
    private val capability: MicCapability,
) {

    /**
     * Invoked on the capture thread when the loop dies unexpectedly (record error, device removed
     * mid-utterance). Not invoked for the normal [stop] path.
     */
    var onError: ((Throwable) -> Unit)? = null

    /**
     * Level of the most recent frame, 0f..1f, for the volume meter. Linear in RMS, so the UI should
     * apply its own curve - speech at TV distance usually lands around 0.05-0.2.
     */
    @Volatile
    var amplitude: Float = 0f
        private set

    @Volatile
    private var running = false

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val lifecycleLock = Any()

    /** True from a successful [start] until the loop exits or [stop] is called. */
    val isRecording: Boolean
        get() = running

    /**
     * Opens the microphone and starts streaming 100 ms chunks into [onChunk].
     *
     * Returns a failed [Result] - never throws - for a missing permission, an unusable device or a
     * failed `startRecording()`. Calling it while already recording is a no-op success.
     */
    @SuppressLint("MissingPermission") // guarded below through MicPermission
    fun start(onChunk: (ByteArray) -> Unit): Result<Unit> {
        synchronized(lifecycleLock) {
            if (running) return Result.success(Unit)

            if (!MicPermission.hasRecordPermission(context)) {
                return Result.failure(SecurityException("RECORD_AUDIO is not granted"))
            }

            val sampleRate = capability.captureSampleRate
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                return Result.failure(
                    IllegalStateException("cannot capture at $sampleRate Hz (getMinBufferSize=$minBuffer)"),
                )
            }
            // Twice the minimum plus ~100 ms: the minimum is a bare "will not underrun" figure and
            // underruns show up as dropped words rather than as an audible glitch.
            val bufferSize = max(minBuffer * 2, sampleRate * 2 / 10)

            val record = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } catch (e: RuntimeException) {
                return Result.failure(e)
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return Result.failure(
                    IllegalStateException("AudioRecord is not initialized (state=${record.state})"),
                )
            }

            try {
                record.startRecording()
            } catch (e: RuntimeException) {
                record.release()
                return Result.failure(e)
            }

            audioRecord = record
            running = true
            captureThread = Thread(
                { captureLoop(record, sampleRate, onChunk) },
                CAPTURE_THREAD_NAME,
            ).also { it.start() }

            return Result.success(Unit)
        }
    }

    /**
     * Stops the loop and releases the microphone. Safe to call twice, safe from any thread, and
     * safe from inside [onChunk].
     */
    fun stop() {
        running = false
        val thread = captureThread
        captureThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(STOP_JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        // If join timed out, stop() unblocks the pending read below; the loop then notices that
        // `running` is false and exits. Releasing twice is harmless because releaseRecord() is
        // idempotent.
        releaseRecord()
        amplitude = 0f
    }

    private fun captureLoop(record: AudioRecord, sampleRate: Int, onChunk: (ByteArray) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val framesPerRead = sampleRate / 10
        val shortBuffer = ShortArray(framesPerRead)
        // Created on the capture thread so every session starts with a clean filter state.
        val resampler = if (capability.needsResampling) LinearResampler(sampleRate) else null
        try {
            while (running) {
                val read = record.read(shortBuffer, 0, shortBuffer.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION) {
                        throw IllegalStateException("AudioRecord.read returned $read")
                    }
                    // A negative value here is transient (e.g. ERROR during a device switch).
                    continue
                }
                amplitude = rmsToAmplitude(shortBuffer, read)

                val frame = shortBuffer.copyOf(read)
                val pcm = resampler?.process(frame) ?: frame
                if (pcm.isEmpty() || !running) continue
                onChunk(toLittleEndian(pcm))
            }
        } catch (e: RuntimeException) {
            // Only report while a recording session is still supposed to be live; a read failure
            // caused by our own stop() is expected and must stay silent.
            if (running) onError?.invoke(e)
        } finally {
            running = false
            amplitude = 0f
            releaseRecord()
        }
    }

    private fun releaseRecord() {
        val record = synchronized(lifecycleLock) {
            val current = audioRecord
            audioRecord = null
            current
        } ?: return

        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (e: IllegalStateException) {
            // Already stopped by the platform (device unplugged); releasing is all that is left.
        }
        try {
            record.release()
        } catch (e: IllegalStateException) {
            // Nothing left to release safely.
        }
    }

    private fun rmsToAmplitude(frame: ShortArray, length: Int): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        for (i in 0 until length) {
            val value = frame[i].toDouble()
            sum += value * value
        }
        val rms = sqrt(sum / length)
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    private fun toLittleEndian(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            buffer.putShort(sample)
        }
        return bytes
    }

    companion object {
        /** 100 ms of audio at 16 kHz: small enough for low latency, large enough to be efficient. */
        private const val CAPTURE_THREAD_NAME = "murmur-pcm-capture"
        private const val STOP_JOIN_TIMEOUT_MS = 300L
    }
}
