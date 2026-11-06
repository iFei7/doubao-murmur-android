package com.doubao.murmur.tv.asr

import kotlinx.coroutines.flow.Flow

/**
 * One streaming recognition session. Not reusable as-is for concurrent utterances: callers drive a
 * session with [start], stream audio through [feed], and finish with [stopAndAwaitFinal].
 */
interface AsrEngine : java.io.Closeable {

    /** Cold stream of session events, hot upstream; a late collector misses earlier events. */
    val events: Flow<AsrEvent>

    /** Opens the WebSocket. Idempotent: calling it on a live session is a no-op. */
    suspend fun start()

    /**
     * Hands one PCM chunk (16 kHz, mono, little-endian, headerless) to the socket. Non-blocking and
     * safe to call from an audio thread; chunks arriving before the socket opens are buffered and
     * flushed in order on connect.
     */
    fun feed(pcm: ByteArray)

    /**
     * Stops the utterance: flushes trailing silence, waits for the transcript to settle, and returns
     * it. Returns the best text received so far when [timeoutMs] elapses first.
     */
    suspend fun stopAndAwaitFinal(timeoutMs: Long = 3000L): String

    /** Aborts the session immediately without reporting a failure. */
    fun cancel()
}
