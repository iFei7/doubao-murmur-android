package com.doubao.murmur.tv.bridge

import com.doubao.murmur.tv.asr.AsrEngine
import com.doubao.murmur.tv.asr.AsrEvent
import com.doubao.murmur.tv.asr.AsrFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the single recognition session behind [IDoubaoAsrBridge] and enforces its state machine.
 *
 * ## Why a separate class
 * Gboard drives the bridge from Binder threads, so the interesting behaviour is not "how do we
 * talk to doubao" (that is `:asr`) but "what happens when the client lies about its lifecycle":
 * it calls `startRecognition` twice, it cancels while the final is in flight, it dies without
 * calling `finishAudio`, or never finishes at all. All of that is pure logic and is covered by
 * JVM tests against a fake [AsrEngine].
 *
 * ## Contract
 * - At most one session exists. A new [start] silently abandons the previous one (no final, no
 *   error to the old callback), which is what lets Gboard recover from a lost `onFinal`.
 * - [cancel] never reports a final; [finish] reports exactly one terminal event
 *   (`onFinal` or `onError`).
 * - Callbacks are serialized on one monitor, so a client never sees `onFinal` interleaved with a
 *   late `onPartial`.
 * - Audio that arrives while the credentials are still being read is buffered (bounded) instead of
 *   being dropped: Gboard starts pushing as soon as the recogniser is open, which can be before the
 *   encrypted store has answered.
 *
 * @param scope lifetime owner; all engine work runs here and is cancelled with it.
 * @param engineFactory creates the engine, or returns null when there is no usable session. Called
 *   on a background dispatcher.
 * @param onCredentialsRejected invoked once per session when the server rejects the stored cookie
 *   (error code [BridgeErrorCodes.LOGIN_EXPIRED]); it must hand the suspend store cleanup to a
 *   scope and return immediately.
 * @param log receives lifecycle lines only — never a transcript, never a credential.
 */
class AsrSessionManager(
    private val scope: CoroutineScope,
    private val engineFactory: suspend () -> AsrEngine?,
    private val onCredentialsRejected: () -> Unit = {},
    private val config: Config = Config(),
    private val log: (String) -> Unit = {},
) {

    /** Tunables; the defaults are the production values. */
    data class Config(
        /** Largest single `pushAudio` payload; anything bigger is dropped to bound Binder memory. */
        val maxChunkBytes: Int = MAX_CHUNK_BYTES,
        /** Bound for audio buffered before the engine exists (4 MiB ≈ 130 s of 16 kHz mono). */
        val maxPendingBytes: Int = MAX_PENDING_BYTES,
        /** Hard cap on one utterance; a client that never calls finish must not stream forever. */
        val maxSessionMs: Long = MAX_SESSION_MS,
        /** How long the engine may wait for the server's final transcript. */
        val finishTimeoutMs: Long = FINISH_TIMEOUT_MS,
    )

    /** The four callback directions of [IDoubaoAsrCallback], as plain Kotlin. */
    interface Callbacks {
        fun onReady()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(code: Int, message: String)
    }

    private enum class Phase { STARTING, ACTIVE, FINISHING }

    private class Session(val callbacks: Callbacks, val converter: PcmConverter) {
        @Volatile var phase: Phase = Phase.STARTING
        @Volatile var engine: AsrEngine? = null

        /** Set by [cancel]/[teardown]; suppresses every later callback of this session. */
        @Volatile var cancelled: Boolean = false

        /** A terminal event was already delivered, so [complete] must not add another one. */
        @Volatile var errorReported: Boolean = false

        @Volatile var oversizedChunkLogged: Boolean = false
        @Volatile var feedFailureLogged: Boolean = false
        @Volatile var overflowLogged: Boolean = false

        // Guarded by the manager's lock.
        var eventsJob: Job? = null
        var guardJob: Job? = null
        var finishJob: Job? = null
        val pending = ArrayDeque<ByteArray>()
        var pendingBytes: Int = 0
    }

    /** Guards [session] and the mutable fields of [Session]. */
    private val lock = Any()

    /** Serializes callback delivery; Binder callbacks must never interleave. */
    private val callbackLock = Any()

    private var session: Session? = null

    /** True while a session exists (starting, streaming or finishing). */
    val isSessionActive: Boolean
        get() = synchronized(lock) { session != null }

    /**
     * Starts a session, abandoning any previous one.
     *
     * Returns false for an audio format that cannot be converted; the callback is not invoked in
     * that case because the caller sees the boolean immediately.
     */
    fun start(sampleRate: Int, channels: Int, callbacks: Callbacks): Boolean {
        if (sampleRate !in PcmConverter.SUPPORTED_SAMPLE_RATES ||
            channels !in PcmConverter.SUPPORTED_CHANNELS
        ) {
            log("rejecting audio format: ${sampleRate}Hz/${channels}ch")
            return false
        }

        val converter = PcmConverter(sampleRate, channels)
        if (!converter.isPassthrough) {
            log("converting ${sampleRate}Hz/${channels}ch audio to 16kHz mono")
        }

        val newSession = Session(callbacks, converter)
        val stale = synchronized(lock) {
            val previous = session
            session = newSession
            previous
        }
        if (stale != null) teardown(stale)

        synchronized(lock) {
            newSession.guardJob = scope.launch {
                delay(config.maxSessionMs)
                if (isCurrent(newSession)) {
                    log("session reached the maximum duration")
                    finish()
                }
            }
        }
        scope.launch { open(newSession) }
        return true
    }

    /**
     * Forwards one PCM chunk. Chunks over [Config.maxChunkBytes] are dropped: a hostile or buggy
     * client must not be able to OOM the process through a single transaction.
     */
    fun pushAudio(pcm: ByteArray) {
        val active = synchronized(lock) { session } ?: return
        if (!isChunkUsable(active, pcm)) return
        val converted = convert(active, pcm) ?: return

        // Route by phase, not by "engine exists": audio must be buffered until the transition to
        // ACTIVE has drained everything that preceded it, otherwise the first word can be handed
        // to the engine out of order.
        val engine = synchronized(lock) {
            if (active.cancelled || session !== active) return
            if (active.phase == Phase.ACTIVE) active.engine else null
        }
        if (engine != null) {
            feed(active, engine, converted)
        } else {
            buffer(active, converted)
        }
    }

    /** Ends the utterance politely: the engine flushes and the final transcript is delivered. */
    fun finish() {
        val target = synchronized(lock) {
            val current = session ?: return
            if (current.phase == Phase.FINISHING) return
            current.phase = Phase.FINISHING
            current
        }
        synchronized(lock) {
            target.finishJob = scope.launch { complete(target) }
        }
    }

    /** Aborts the utterance; the client gets no terminal callback for it. */
    fun cancel() {
        val target = synchronized(lock) {
            val current = session ?: return
            current.cancelled = true
            session = null
            current
        }
        teardown(target)
    }

    // ---------------------------------------------------------------- internals

    private suspend fun open(target: Session) {
        val engine = try {
            engineFactory()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            log("engine creation failed: ${t::class.java.simpleName}")
            null
        }
        if (engine == null) {
            fail(target, BridgeErrorCodes.NOT_SIGNED_IN, "no doubao session")
            return
        }
        // The client may have cancelled while the credentials were being read.
        if (!isCurrent(target)) {
            closeQuietly(engine)
            return
        }

        target.engine = engine
        // Subscribe before start(), and undispatched: the engine's flow is hot (replay = 0), so a
        // collector that is merely *scheduled* would miss the first events.
        synchronized(lock) {
            target.eventsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.events.collect { event -> onEvent(target, event) }
            }
        }

        try {
            engine.start()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            log("engine start failed: ${t::class.java.simpleName}")
            fail(target, BridgeErrorCodes.ENGINE_FAILURE, "cannot start recognition")
            return
        }

        synchronized(lock) {
            if (session !== target || target.cancelled || target.phase != Phase.STARTING) return
            // Drain inside the same critical section that flips the phase: pushAudio only starts
            // feeding the engine directly once ACTIVE is visible, so buffered audio can never be
            // overtaken by newer chunks.
            drainPendingLocked(target)
            target.phase = Phase.ACTIVE
        }

        emit(target) { it.onReady() }
    }

    private fun onEvent(target: Session, event: AsrEvent) {
        if (!isCurrent(target)) return
        when (event) {
            is AsrEvent.Partial -> emit(target) { it.onPartial(event.text) }
            is AsrEvent.Failed -> {
                // An expired cookie is the one "engine failure" the client can act on: the stored
                // session is dead, so it is dropped before the client is told to sign in again.
                // Keeping it would leave the viewer in a "signed in but every utterance fails"
                // loop until they happen to open the app themselves.
                val expired = event.failure == AsrFailure.LoginExpired ||
                    event.failure == AsrFailure.TokenInvalid
                if (expired) {
                    rejectCredentials()
                    fail(target, BridgeErrorCodes.LOGIN_EXPIRED, describe(event.failure))
                } else {
                    fail(target, BridgeErrorCodes.ENGINE_FAILURE, describe(event.failure))
                }
            }
            // Connected / Finished / Final are folded into onReady() and complete().
            else -> Unit
        }
    }

    /**
     * Drops the stored session after the server rejected it. Called for [AsrFailure.LoginExpired]
     * and [AsrFailure.TokenInvalid] only; a network hiccup must never log the viewer out.
     *
     * The callback is expected to be non-blocking (it hands the suspend store call to a scope):
     * this runs on an engine/Binder thread.
     */
    private fun rejectCredentials() {
        try {
            onCredentialsRejected()
        } catch (t: Throwable) {
            log("dropping the rejected session failed: ${t::class.java.simpleName}")
        }
    }

    private suspend fun complete(target: Session) {
        val engine = target.engine
        if (engine == null) {
            fail(target, BridgeErrorCodes.EMPTY_AUDIO, "no audio was captured")
            return
        }

        val text = try {
            engine.stopAndAwaitFinal(config.finishTimeoutMs)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            log("engine finish failed: ${t::class.java.simpleName}")
            ""
        }

        stopBackgroundJobs(target)
        closeQuietly(engine)
        synchronized(lock) {
            if (session === target) session = null
            target.finishJob = null
        }

        if (target.errorReported) return
        if (text.isNotBlank()) {
            emit(target) { it.onFinal(text) }
        } else {
            target.errorReported = true
            emit(target) { it.onError(BridgeErrorCodes.NO_SPEECH, "no speech was recognised") }
        }
    }

    /** Reports a terminal failure and tears the session down. Idempotent per session. */
    private fun fail(target: Session, code: Int, message: String) {
        if (target.errorReported) return
        target.errorReported = true
        synchronized(lock) {
            if (session === target) session = null
        }
        // The error must reach the client, so the session is torn down *after* it is delivered:
        // teardown() marks the session cancelled, which suppresses every further callback.
        stopBackgroundJobs(target)
        synchronized(lock) {
            target.finishJob?.cancel()
            target.finishJob = null
            target.pending.clear()
            target.pendingBytes = 0
        }
        emit(target) { it.onError(code, message) }
        closeQuietly(target.engine)
    }

    private fun isChunkUsable(active: Session, pcm: ByteArray): Boolean {
        if (pcm.isEmpty()) return false
        if (pcm.size <= config.maxChunkBytes) return true
        if (!active.oversizedChunkLogged) {
            active.oversizedChunkLogged = true
            log("dropping an over-large chunk of ${pcm.size} bytes")
        }
        return false
    }

    /** Converts to 16 kHz mono; null when the chunk cannot be used. */
    private fun convert(active: Session, pcm: ByteArray): ByteArray? {
        val converted = try {
            active.converter.convert(pcm)
        } catch (t: Throwable) {
            log("audio conversion failed: ${t::class.java.simpleName}")
            return null
        }
        return converted.takeIf { it.isNotEmpty() }
    }

    /** Queues audio that arrived before the engine existed (credentials still loading). */
    private fun buffer(active: Session, converted: ByteArray) {
        synchronized(lock) {
            if (active.cancelled || session !== active) return
            if (active.pendingBytes + converted.size > config.maxPendingBytes) {
                if (!active.overflowLogged) {
                    active.overflowLogged = true
                    log("dropping audio: the pending buffer is full")
                }
                return
            }
            active.pending.addLast(converted)
            active.pendingBytes += converted.size
        }
    }

    /** Feeds every chunk buffered before the engine was usable. The caller must hold [lock]. */
    private fun drainPendingLocked(target: Session) {
        val engine = target.engine ?: return
        while (true) {
            val chunk = target.pending.removeFirstOrNull() ?: return
            target.pendingBytes -= chunk.size
            feed(target, engine, chunk)
        }
    }

    private fun feed(target: Session, engine: AsrEngine, pcm: ByteArray) {
        try {
            engine.feed(pcm)
        } catch (t: Throwable) {
            // The engine legitimately refuses audio after cancel(); log once and keep going.
            if (!target.feedFailureLogged) {
                target.feedFailureLogged = true
                log("engine refused audio: ${t::class.java.simpleName}")
            }
        }
    }

    /** Stops a session without reporting anything to the client. Idempotent. */
    private fun teardown(target: Session) {
        target.cancelled = true
        stopBackgroundJobs(target)
        synchronized(lock) {
            target.finishJob?.cancel()
            target.finishJob = null
            target.pending.clear()
            target.pendingBytes = 0
        }
        closeQuietly(target.engine)
    }

    private fun stopBackgroundJobs(target: Session) {
        synchronized(lock) {
            target.eventsJob?.cancel()
            target.eventsJob = null
            target.guardJob?.cancel()
            target.guardJob = null
        }
    }

    private fun closeQuietly(engine: AsrEngine?) {
        if (engine == null) return
        runCatching { engine.cancel() }
        runCatching { engine.close() }
    }

    private fun isCurrent(target: Session): Boolean =
        synchronized(lock) { session === target && !target.cancelled }

    /**
     * Delivers one callback under [callbackLock]. A dead client (its process exited, so the Binder
     * proxy throws `DeadObjectException`) must not take down the service; the session is left to
     * the max-duration guard, which closes it.
     */
    private inline fun emit(target: Session, block: (Callbacks) -> Unit) {
        synchronized(callbackLock) {
            if (target.cancelled) return
            try {
                block(target.callbacks)
            } catch (t: Throwable) {
                log("callback failed: ${t::class.java.simpleName}")
            }
        }
    }

    private fun describe(failure: AsrFailure): String = when (failure) {
        AsrFailure.LoginExpired, AsrFailure.TokenInvalid -> "doubao session expired"
        AsrFailure.Network -> "network unavailable"
        AsrFailure.Timeout -> "recognition timed out"
        is AsrFailure.Server -> "server error ${failure.code}"
    }

    companion object {
        /** 1 MiB per Binder transaction: way above a 100 ms chunk, low enough to bound memory. */
        const val MAX_CHUNK_BYTES = 1 shl 20

        /** 4 MiB ≈ 130 s of 16 kHz mono 16 bit audio buffered before the engine exists. */
        const val MAX_PENDING_BYTES = 4 shl 20

        /** A stuck Gboard session must not keep the socket (and the quota) open forever. */
        const val MAX_SESSION_MS = 60_000L

        /** Matches the IME's own wait for the server's final transcript. */
        const val FINISH_TIMEOUT_MS = 3_000L
    }
}
