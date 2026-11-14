package com.doubao.murmur.tv.ime

import android.content.Context
import android.util.Log
import com.doubao.murmur.tv.asr.AsrCredentials
import com.doubao.murmur.tv.asr.AsrEngine
import com.doubao.murmur.tv.asr.AsrEvent
import com.doubao.murmur.tv.asr.AsrFailure
import com.doubao.murmur.tv.audio.MicCapability
import com.doubao.murmur.tv.audio.MicPermission
import com.doubao.murmur.tv.audio.MicProbe
import com.doubao.murmur.tv.audio.PcmRecorder
import com.doubao.murmur.tv.auth.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * Orchestrates one utterance: credentials → microphone → WebSocket → host text field.
 *
 * ## Why not a ViewModel
 * The owner of this object is [DoubaoVoiceInputMethodService], whose lifetime is exactly the
 * lifetime of the process' IME binding. A `ViewModel` would need a `ViewModelStoreOwner` that
 * survives the input view, and an IME input view is destroyed and recreated every time the
 * keyboard is hidden — the ViewModel would either outlive nothing or be recreated with every
 * show, both of which are worse than a plain object with an explicit [release].
 *
 * ## Threading contract
 * - Every public method must be called on the main thread (the service callbacks and the Compose
 *   callbacks already are).
 * - All state mutation happens inside [scope] (`Dispatchers.Main.immediate`), so [state] can be
 *   collected from Compose with `collectAsState()` and the UI never sees a torn snapshot.
 * - The microphone callback runs on the capture thread and is forwarded into the engine directly;
 *   it never touches [state]. The level meter is driven by a poller inside [scope] instead, so a
 *   slow frame can never block audio capture.
 *
 * ## Privacy
 * The transcript is never logged. Log lines contain the state name and the failure *type* only.
 */
class ImeController(
    private val context: Context,
    private val sessionRepository: SessionRepository,
    private val recorderFactory: (Context, MicCapability) -> PcmRecorder,
    private val engineFactory: (AsrCredentials) -> AsrEngine,
    private val committer: TextCommitter,
    private val finalTimeoutMsProvider: () -> Long = { DEFAULT_FINAL_TIMEOUT_MS },
) {

    /**
     * Catches any exception that escapes a child coroutine of [scope].
     *
     * Event collectors (`collect`) and the state-update loops run in [scope]; without a handler
     * an uncaught exception would reach the main thread's uncaught-exception handler and kill the
     * IME process (seen by the user as a bind/unbind "freeze"). Degrade to a recoverable Idle
     * state with the generic error instead. Only the failure *type* is logged, never the
     * transcript or a credential. [reportUncaughtFailure] is a function (not an init-block
     * reference) so it can safely touch [_state], which is declared below [scope].
     */
    private val scope = CoroutineScope(
        Dispatchers.Main.immediate + SupervisorJob() +
            CoroutineExceptionHandler { _, throwable ->
                Log.w(TAG, "uncaught coroutine failure: ${throwable::class.java.simpleName}")
                reportUncaughtFailure()
            }
    )

    /** Keeps an uncaught failure from reaching the main-thread uncaught handler; see [scope]. */
    private fun reportUncaughtFailure() {
        _state.update {
            it.copy(
                state = ImeState.Idle,
                level = 0f,
                errorMessage = context.getString(R.string.ime_error_generic),
            )
        }
    }

    /**
     * Separate scope for microphone teardown.
     *
     * `PcmRecorder.stop()` joins the capture thread and may block for up to ~300 ms; the main
     * thread must never do that (the IME window would freeze mid-animation). It cannot live in
     * [scope] either, because [release] cancels that scope right after asking for the stop — the
     * AudioRecord would then stay open until the process died, and on a TV that shows up as the
     * privacy indicator staying lit.
     */
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow(ImeUiState())
    val state: StateFlow<ImeUiState> = _state.asStateFlow()

    /**
     * Invoked when the viewer has no usable session. The service responds by opening the sign-in
     * screen of `:app`; the controller must not try to start activities itself.
     */
    var onNeedLogin: (() -> Unit)? = null

    /** Invoked when `RECORD_AUDIO` is missing; the service opens the permission screen. */
    var onNeedMicPermission: (() -> Unit)? = null

    private var engine: AsrEngine? = null
    private var recorder: PcmRecorder? = null
    private var eventsJob: Job? = null
    private var amplitudeJob: Job? = null
    private var maxDurationJob: Job? = null

    /** Last partial snapshot, used as the fallback when no final transcript arrives. */
    private var lastPartial: String = ""

    /** Guards `beginListening` against a double activation (remote key + Compose button). */
    private var beginRequested = false

    /** One-shot log guard for the audio thread; touched from two threads. */
    @Volatile
    private var feedFailureLogged = false

    /**
     * Set when the viewer released OK while the engine was still connecting.
     *
     * Without it a quick "press, hold 300 ms, release" would leave the microphone open forever:
     * `endListening` is ignored in `Connecting` state and nothing else would ever stop it.
     */
    private var stopRequested = false

    /**
     * Monotonic counter of "utterances started or cancelled".
     *
     * A finishing utterance spends up to [FINAL_TIMEOUT_GRACE_MS] + timeout waiting for the final
     * transcript. If the viewer cancels (or starts a new utterance) during that window, the
     * pending `endListening` coroutine must not commit its text into whatever field is focused by
     * then. The epoch is compared after the wait; a mismatch means "this result is stale, drop it".
     */
    private var epoch = 0

    // ---------------------------------------------------------------- public API

    /**
     * Starts an utterance. Safe to call twice: the second call is ignored, which is what makes it
     * possible to listen both on the service's key path and on the Compose button path without
     * the two racing.
     *
     * Sign-in and microphone permission are *not* checked here — they are checked inside the
     * coroutine, because both require a `suspend` round trip and the UI must not flicker a
     * disabled button while that happens.
     */
    fun beginListening() {
        if (beginRequested) return
        val snapshot = _state.value
        if (snapshot.isCapturing || snapshot.state is ImeState.Transcribing) return
        beginRequested = true
        stopRequested = false
        epoch++
        scope.launch {
            try {
                startInternal()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "beginListening failed", t)
                abortSession()
                _state.update {
                    it.copy(
                        state = ImeState.Idle,
                        level = 0f,
                        errorMessage = context.getString(R.string.ime_error_generic),
                    )
                }
            } finally {
                beginRequested = false
            }
        }
    }

    /**
     * Stops capturing, waits for the server's final transcript and inserts it.
     *
     * Falls back to the last partial snapshot when the server never sends a final: on a slow
     * connection the alternative would be to throw away what the viewer just said.
     */
    fun endListening() {
        val currentEngine = engine ?: return
        val snapshot = _state.value
        if (snapshot.state is ImeState.Connecting) {
            // Still opening the socket: honour the release as soon as it lands (see stopRequested).
            stopRequested = true
            return
        }
        if (snapshot.state !is ImeState.Listening) return

        val currentRecorder = recorder
        recorder = null
        stopAmplitudeLoop()
        stopMaxDurationGuard()
        _state.update { it.copy(state = ImeState.Transcribing(it.preview), level = 0f) }

        val timeoutMs = finalTimeoutMsProvider()
        val myEpoch = epoch
        scope.launch {
            // Drain the microphone *before* asking the server for the final transcript, otherwise
            // the last ~100 ms of the sentence (one capture chunk) is cut off. `stop()` joins the
            // capture thread and may block for ~300 ms, so it runs on the IO dispatcher: doing it
            // here on the main thread would freeze the keyboard exactly when the user is watching
            // the "识别中" state.
            if (currentRecorder != null) {
                withContext(Dispatchers.IO) {
                    runCatching { currentRecorder.stop() }
                        .onFailure { Log.w(TAG, "recorder.stop failed", it) }
                }
            }
            val finalText = try {
                withTimeoutOrNull(timeoutMs + FINAL_TIMEOUT_GRACE_MS) {
                    currentEngine.stopAndAwaitFinal(timeoutMs)
                }.orEmpty()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "stopAndAwaitFinal failed", t)
                ""
            }
            val text = finalText.ifBlank { lastPartial }.trim()

            // Detach the engine before touching the host field: a late `Failed` event must not
            // overwrite the committed state.
            detachEngine(currentEngine)

            if (myEpoch != epoch) {
                // Cancelled or superseded while we were waiting: the text belongs to a field that
                // is no longer being edited.
                Log.d(TAG, "dropping stale transcript")
                return@launch
            }

            if (text.isEmpty()) {
                committer.abandonComposing()
                _state.update {
                    it.copy(
                        state = ImeState.Idle,
                        level = 0f,
                        preview = "",
                        errorMessage = context.getString(R.string.ime_error_no_speech),
                    )
                }
            } else {
                committer.commitFinal(text)
                lastPartial = ""
                _state.update {
                    it.copy(state = ImeState.Committed(text), level = 0f, preview = text, errorMessage = null)
                }
            }
        }
    }

    /**
     * Abandons the current utterance without inserting anything.
     *
     * Used by BACK while recording, by `onFinishInput` (the editor is gone) and by
     * `onWindowHidden` (HOME pressed). Committing a half sentence there would be worse than
     * losing it.
     */
    fun cancelListening() {
        val currentRecorder = recorder
        val currentEngine = engine
        recorder = null
        engine = null
        eventsJob?.cancel()
        eventsJob = null
        stopAmplitudeLoop()
        stopMaxDurationGuard()
        lastPartial = ""
        stopRequested = false
        epoch++

        // Closes the microphone *and* the privacy indicator; must not block the main thread.
        stopRecorderAsync(currentRecorder)
        runCatching { currentEngine?.cancel() }
            .onFailure { Log.w(TAG, "engine.cancel failed", it) }
        runCatching { currentEngine?.close() }
            .onFailure { Log.w(TAG, "engine.close failed", it) }

        committer.abandonComposing()

        if (currentRecorder != null || currentEngine != null) {
            _state.update { it.copy(state = ImeState.Idle, level = 0f, preview = "") }
        }
    }

    /** Refreshes the sign-in / microphone chips, e.g. right after the input view is shown. */
    fun refreshStatus() {
        scope.launch {
            val loggedIn = runCatching { sessionRepository.isLoggedIn() }.getOrDefault(false)
            val micGranted = MicPermission.hasRecordPermission(context)
            _state.update {
                it.copy(loggedIn = loggedIn, micGranted = micGranted, statusChecked = true)
            }
        }
    }

    /** Releases everything. Called from `InputMethodService.onDestroy`. */
    fun release() {
        cancelListening()
        scope.cancel()
    }

    // ---------------------------------------------------------------- internals

    private suspend fun startInternal() {
        // `runCatching` must not swallow cancellation: if the service is destroyed mid-read we want
        // the coroutine to die quietly, not to report "not signed in" and open the login screen.
        val credentials = try {
            sessionRepository.current()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "reading credentials failed", t)
            null
        }
        if (credentials == null) {
            _state.update {
                it.copy(
                    state = ImeState.Idle,
                    loggedIn = false,
                    statusChecked = true,
                    errorMessage = context.getString(R.string.ime_error_not_signed_in),
                )
            }
            onNeedLogin?.invoke()
            return
        }

        if (!MicPermission.hasRecordPermission(context)) {
            _state.update {
                it.copy(
                    state = ImeState.Idle,
                    micGranted = false,
                    loggedIn = true,
                    statusChecked = true,
                    errorMessage = context.getString(R.string.error_mic_permission_denied),
                )
            }
            onNeedMicPermission?.invoke()
            return
        }

        // Probing the audio hardware touches AudioManager and can take a few milliseconds; keep it
        // off the main thread so the IME never janks while the window is animating in.
        val capability = withContext(Dispatchers.Default) {
            try {
                MicProbe.probe(context)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "microphone probe failed", t)
                null
            }
        }
        if (capability == null || !capability.hasMicrophone) {
            _state.update {
                it.copy(
                    state = ImeState.Idle,
                    micGranted = true,
                    errorMessage = context.getString(R.string.ime_error_no_microphone),
                )
            }
            return
        }

        _state.update {
            it.copy(
                state = ImeState.Connecting,
                level = 0f,
                preview = "",
                errorMessage = null,
                loggedIn = true,
                micGranted = true,
                statusChecked = true,
            )
        }
        lastPartial = ""

        val newEngine = engineFactory(credentials)
        engine = newEngine
        // Subscribe before start(): the engine's first emission is `Connected`, and a broadcast
        // flow would drop it if the collector attached afterwards.
        eventsJob = scope.launch {
            newEngine.events.collect { event -> handleEvent(event) }
        }

        val newRecorder = recorderFactory(context, capability)
        recorder = newRecorder
        newRecorder.onError = { throwable -> onRecorderError(throwable) }

        try {
            newEngine.start()
            newRecorder.start { pcm ->
                // Runs on the capture thread. An uncaught throw here would kill the process, and
                // after cancel()/close() the engine legitimately refuses more audio, so failures
                // are swallowed (logged once) instead of propagating.
                try {
                    newEngine.feed(pcm)
                } catch (t: Throwable) {
                    if (!feedFailureLogged) {
                        feedFailureLogged = true
                        Log.w(TAG, "engine.feed failed", t)
                    }
                }
            }.getOrThrow()
        } catch (t: Throwable) {
            // The half-built session must not leak a socket or an open AudioRecord.
            abortSession()
            throw t
        }

        _state.update { it.copy(state = ImeState.Listening) }
        startAmplitudeLoop()
        startMaxDurationGuard()

        if (stopRequested) {
            stopRequested = false
            endListening()
        }
    }

    private suspend fun handleEvent(event: AsrEvent) {
        when (event) {
            AsrEvent.Connected -> Unit
            is AsrEvent.Partial -> {
                lastPartial = event.text
                committer.showComposing(event.text)
                _state.update { it.copy(state = ImeState.Listening, preview = event.text) }
            }
            is AsrEvent.Final -> {
                lastPartial = event.text
                committer.showComposing(event.text)
                _state.update { it.copy(state = ImeState.Transcribing(event.text), preview = event.text) }
            }
            AsrEvent.Finished -> Unit
            is AsrEvent.Failed -> handleFailure(event)
        }
    }

    /**
     * Turns a failed session into a *recoverable* state.
     *
     * Two very different situations hide behind one event:
     * - **The credentials are dead** (`LoginExpired` / `TokenInvalid`, or the server closing right
     *   after the handshake). They must be wiped from the encrypted store, because a credential that
     *   is half-synced (the WebView cookie jar can hand over a partial cookie right after the login
     *   redirect) fails on *every* attempt — without the wipe the viewer could never self-heal: each
     *   press would fail once and look like a broken microphone. The UI drops to "not signed in" and
     *   the primary button becomes "去登录"; we deliberately do **not** yank the login activity to
     *   the front, that would steal the host app's screen mid-typing.
     * - **The network hiccuped** (`Network` / `Timeout` / server error). The session stays valid, so
     *   nothing is cleared — logging the viewer out because their Wi-Fi blinked would be worse than
     *   the hiccup itself.
     */
    private suspend fun handleFailure(event: AsrEvent.Failed) {
        Log.w(TAG, "recognition failed: ${event.failure}")
        val failure = event.failure
        val credentialsRejected = failure == AsrFailure.LoginExpired ||
            failure == AsrFailure.TokenInvalid
        val message = when (failure) {
            AsrFailure.LoginExpired, AsrFailure.TokenInvalid ->
                context.getString(R.string.error_login_expired)
            AsrFailure.Network -> context.getString(R.string.error_network_unavailable)
            AsrFailure.Timeout -> context.getString(R.string.ime_error_timeout)
            is AsrFailure.Server -> context.getString(R.string.ime_error_server, failure.code)
        }

        // Wipe the rejected credentials *before* tearing the session down: `abortSession()` cancels
        // the very coroutine that delivered this event, and a suspending store call after that
        // would be cancelled before it ran.
        if (credentialsRejected) {
            withContext(Dispatchers.IO) {
                runCatching { sessionRepository.clear() }
                    .onFailure { Log.w(TAG, "clearing the rejected session failed", it) }
                // The WebView cookie jar holds the same expired session; leaving it in place would
                // let the next login "succeed" instantly off the stale cookies without the user
                // signing in again.
                sessionRepository.clearWebViewState()
            }
        }

        // Stop the microphone: a rejected session must never leave the privacy indicator lit.
        // Nothing below may suspend — this coroutine is cancelled by now.
        abortSession()

        _state.update {
            it.copy(
                state = ImeState.Idle,
                level = 0f,
                preview = "",
                errorMessage = message,
                loggedIn = if (credentialsRejected) false else it.loggedIn,
                statusChecked = true,
            )
        }
    }

    /**
     * Called from the capture thread when `AudioRecord` dies (USB microphone unplugged, device
     * taken by another app). Hop to the main dispatcher before touching state.
     */
    private fun onRecorderError(throwable: Throwable) {
        Log.w(TAG, "microphone error", throwable)
        scope.launch {
            abortSession()
            _state.update {
                it.copy(
                    state = ImeState.Idle,
                    level = 0f,
                    errorMessage = context.getString(R.string.ime_error_microphone),
                )
            }
        }
    }

    /** Stops capture and drops the engine without committing anything. */
    private fun abortSession() {
        val currentRecorder = recorder
        val currentEngine = engine
        recorder = null
        engine = null
        eventsJob?.cancel()
        eventsJob = null
        stopAmplitudeLoop()
        stopMaxDurationGuard()
        lastPartial = ""

        stopRecorderAsync(currentRecorder)
        runCatching { currentEngine?.cancel() }
            .onFailure { Log.w(TAG, "engine.cancel failed", it) }
        runCatching { currentEngine?.close() }
            .onFailure { Log.w(TAG, "engine.close failed", it) }
    }

    /**
     * Stops the recorder off the main thread.
     *
     * `PcmRecorder.stop()` joins the capture thread (bounded at ~300 ms by `AudioRecord.read`
     * returning), which is far too long to block the IME's UI thread on BACK/onFinishInput.
     */
    private fun stopRecorderAsync(target: PcmRecorder?) {
        if (target == null) return
        ioScope.launch {
            runCatching { target.stop() }
                .onFailure { Log.w(TAG, "recorder.stop failed", it) }
        }
    }

    /** Cancels event collection and closes [target] if it is still the active engine. */
    private fun detachEngine(target: AsrEngine) {
        eventsJob?.cancel()
        eventsJob = null
        if (engine === target) {
            engine = null
        }
        runCatching { target.close() }
            .onFailure { Log.w(TAG, "engine.close failed", it) }
    }

    private fun startAmplitudeLoop() {
        stopAmplitudeLoop()
        amplitudeJob = scope.launch {
            while (isActive) {
                val level = recorder?.amplitude ?: 0f
                _state.update { snapshot ->
                    if (snapshot.state is ImeState.Listening) {
                        snapshot.copy(level = level.coerceIn(0f, 1f))
                    } else {
                        snapshot
                    }
                }
                delay(AMPLITUDE_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopAmplitudeLoop() {
        amplitudeJob?.cancel()
        amplitudeJob = null
    }

    /**
     * Latched recordings (short tap on the mic button) have no natural end, and a stuck key must
     * not keep the microphone — and the upload — open indefinitely. After [MAX_UTTERANCE_MS] the
     * utterance is finished the normal way, so the viewer still gets the text.
     */
    private fun startMaxDurationGuard() {
        maxDurationJob?.cancel()
        maxDurationJob = scope.launch {
            delay(MAX_UTTERANCE_MS)
            if (_state.value.state is ImeState.Listening) {
                Log.d(TAG, "max utterance duration reached, finishing")
                endListening()
            }
        }
    }

    private fun stopMaxDurationGuard() {
        maxDurationJob?.cancel()
        maxDurationJob = null
    }

    companion object {
        private const val TAG = "ImeController"

        /** Default wait for the final transcript after the viewer releases OK. */
        const val DEFAULT_FINAL_TIMEOUT_MS = 3000L

        /**
         * Extra head room on top of the engine timeout: the engine itself has to flush 200 ms of
         * digital silence and wait for the server's `finish`, so the outer guard must be looser or
         * it would cancel a healthy finish.
         */
        private const val FINAL_TIMEOUT_GRACE_MS = 2000L

        /** 60 ms ≈ 16 frames of meter animation per second, cheap and smooth enough. */
        private const val AMPLITUDE_POLL_INTERVAL_MS = 60L

        /**
         * Hard cap on one utterance. The protocol has no "stop" from the server side, and a latched
         * recording (short tap) or a stuck OK key would otherwise stream until the app is killed.
         */
        private const val MAX_UTTERANCE_MS = 30_000L
    }
}
