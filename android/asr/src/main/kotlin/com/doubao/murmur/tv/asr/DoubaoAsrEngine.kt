package com.doubao.murmur.tv.asr

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * OkHttp transport for doubao's streaming ASR.
 *
 * ## Trailing silence
 *
 * [stopAndAwaitFinal] appends [AsrProtocol.TRAILING_SILENCE_BYTES] (200 ms) of digital silence
 * before it waits for the transcript. The service emits exactly one result per audio frame it
 * receives and withholds the last word until further audio arrives, so a stream that simply stops
 * never gets its tail transcribed. Upstream measured the effect: losing about 150 ms of tail audio
 * costs the final two characters, and about 100 ms of silence recovers them, so 200 ms leaves
 * margin. The padding is queued at once instead of being paced in real time, hence it costs no
 * wall-clock time.
 *
 * ## Auth failures
 *
 * An expired login passes the handshake and is then closed by the server before it sends anything,
 * so "no message received + server-initiated close" is the only reliable signal — the close reason
 * is useless because healthy sessions also end with `1000` / `"1000-"`.
 *
 * @param client caller-owned; [close] never shuts down its dispatcher or connection pool.
 * @param baseUrl overridable so tests can point at a local `ws://` server.
 * @param quietPeriodMs how long the result stream must stay silent before the transcript is
 *   accepted as final. After the audio ends the server replays pending partials before sending the
 *   one carrying the final word; that gap measures about 150 ms upstream.
 */
class DoubaoAsrEngine(
    private val credentials: AsrCredentials,
    private val client: OkHttpClient,
    private val baseUrl: String = AsrProtocol.WSS_ENDPOINT,
    private val quietPeriodMs: Long = 250L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : AsrEngine {

    private val eventSink = MutableSharedFlow<AsrEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<AsrEvent> = eventSink.asSharedFlow()

    private val startMutex = Mutex()
    private val stopMutex = Mutex()
    private val pending = ConcurrentLinkedQueue<ByteArray>()

    /** Guards [quietTimer] and [finalText] only; everything else uses volatile fields. */
    private val gate = Any()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var sessionStarted = false
    @Volatile private var connected = false
    @Volatile private var stopping = false
    @Volatile private var closedByUs = false
    @Volatile private var receivedAny = false
    @Volatile private var failureReported = false
    @Volatile private var latestText = ""
    @Volatile private var lastResultAtMs = 0L
    @Volatile private var disposed = false

    private var quietTimer: Job? = null
    private var finalText: CompletableDeferred<String>? = null

    override suspend fun start() {
        startMutex.withLock {
            if (disposed || sessionStarted) return@withLock
            resetSession()
            sessionStarted = true
            val url = AsrProtocol.buildUrl(credentials, UUID.randomUUID().toString(), baseUrl)
            val request = Request.Builder()
                .url(url)
                .addHeader("Cookie", credentials.cookieHeader())
                .addHeader("Origin", AsrProtocol.ORIGIN)
                .build()
            socket = client.newWebSocket(request, Listener())
        }
    }

    override fun feed(pcm: ByteArray) {
        if (stopping || pcm.isEmpty()) return
        val ws = socket
        if (connected && ws != null) {
            ws.send(pcm.toByteString())
        } else {
            pending.add(pcm.copyOf())
        }
    }

    override suspend fun stopAndAwaitFinal(timeoutMs: Long): String = stopMutex.withLock {
        val deferred = CompletableDeferred<String>()
        synchronized(gate) {
            stopping = true
            pending.clear()
            finalText = deferred
        }
        sendTrailingSilence()
        scheduleQuietCompletion()
        val text = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: latestText
        finishUp(text)
        text
    }

    override fun cancel() {
        closedByUs = true
        stopping = true
        connected = false
        sessionStarted = false
        pending.clear()
        val deferred = synchronized(gate) {
            quietTimer?.cancel()
            quietTimer = null
            finalText
        }
        deferred?.complete(latestText)
        val ws = socket
        socket = null
        ws?.cancel()
    }

    override fun close() {
        if (disposed) return
        disposed = true
        cancel()
        scope.cancel()
    }

    private fun resetSession() {
        pending.clear()
        connected = false
        stopping = false
        closedByUs = false
        receivedAny = false
        failureReported = false
        latestText = ""
        lastResultAtMs = 0L
        synchronized(gate) {
            quietTimer?.cancel()
            quietTimer = null
            finalText = null
        }
    }

    /**
     * Ends the session politely: the socket is closed with the code the web client uses, and the
     * reference is kept so [cancel] / [close] can still force the transport down if the server never
     * answers the close handshake (otherwise the connection would linger until the process exits).
     */
    private fun finishUp(text: String) {
        synchronized(gate) {
            quietTimer?.cancel()
            quietTimer = null
            finalText = null
        }
        stopping = true
        connected = false
        sessionStarted = false
        closedByUs = true
        if (!failureReported && text.isNotEmpty()) {
            eventSink.tryEmit(AsrEvent.Final(text))
        }
        socket?.close(CLOSE_CODE, CLOSE_REASON)
    }

    private fun sendTrailingSilence() {
        val ws = socket ?: return
        if (!connected) return
        var remaining = AsrProtocol.TRAILING_SILENCE_BYTES
        while (remaining > 0) {
            val size = minOf(remaining, SILENCE_CHUNK_BYTES)
            ws.send(ByteArray(size).toByteString())
            remaining -= size
        }
    }

    /**
     * Arms the quiet-period timer. It fires once the stream has been silent for [quietPeriodMs],
     * measured from the last partial, and completes the pending [stopAndAwaitFinal] with the newest
     * text. Re-armed every time a partial arrives, so a burst of replays cannot cut the tail off.
     */
    private fun scheduleQuietCompletion() {
        synchronized(gate) {
            quietTimer?.cancel()
            val last = lastResultAtMs
            val idleMs = if (last == 0L) 0L else System.currentTimeMillis() - last
            val remaining = quietPeriodMs - idleMs
            quietTimer = scope.launch {
                if (remaining > 0) delay(remaining)
                completeFinal()
            }
        }
    }

    private fun completeFinal() {
        val deferred = synchronized(gate) { finalText }
        deferred?.complete(latestText)
    }

    private fun dispatch(event: AsrEvent?) {
        when (event) {
            null -> Unit
            is AsrEvent.Partial -> onPartial(event.text)
            is AsrEvent.Failed -> failOnce(event.failure, event.cause)
            AsrEvent.Finished -> {
                completeFinal()
                eventSink.tryEmit(AsrEvent.Finished)
            }
            else -> eventSink.tryEmit(event)
        }
    }

    private fun onPartial(text: String) {
        latestText = text
        lastResultAtMs = System.currentTimeMillis()
        eventSink.tryEmit(AsrEvent.Partial(text))
        if (stopping) scheduleQuietCompletion()
    }

    private fun failOnce(failure: AsrFailure, cause: Throwable? = null) {
        if (failureReported) return
        failureReported = true
        connected = false
        eventSink.tryEmit(AsrEvent.Failed(failure, cause))
        completeFinal()
    }

    private fun flushPending(ws: WebSocket) {
        var chunk = pending.poll()
        while (chunk != null) {
            ws.send(chunk.toByteString())
            chunk = pending.poll()
        }
    }

    private fun classifyFailure(response: Response?, cause: Throwable): AsrFailure {
        if (response != null) {
            val refused = AsrFailures.classifyHandshake(response.code, readBody(response))
            if (refused != null) return refused
        }
        return AsrFailures.classifyThrowable(cause)
    }

    /**
     * Best-effort body of a refused handshake. OkHttp hands the response over unread, so the JSON
     * payload carrying the auth code is still available; a body that cannot be read simply means the
     * failure is classified from the exception instead.
     */
    private fun readBody(response: Response): String? =
        runCatching { response.body?.string() }.getOrNull()

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closedByUs) {
                webSocket.close(CLOSE_CODE, CLOSE_REASON)
                return
            }
            socket = webSocket
            connected = true
            flushPending(webSocket)
            eventSink.tryEmit(AsrEvent.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            receivedAny = true
            dispatch(AsrProtocol.parse(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            receivedAny = true
            dispatch(AsrProtocol.parse(bytes.utf8()))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            handleClose(webSocket)
            webSocket.close(CLOSE_CODE, CLOSE_REASON)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleClose(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) {
                connected = false
                sessionStarted = false
            }
            if (closedByUs) return
            failOnce(classifyFailure(response, t), t)
        }

        private fun handleClose(webSocket: WebSocket) {
            if (socket === webSocket) {
                connected = false
                sessionStarted = false
            }
            // Close codes and reasons carry no signal: healthy sessions also end with 1000 and
            // "1000-". Only "closed before sending anything" separates an expired login out.
            if (!closedByUs && !receivedAny) failOnce(AsrFailure.LoginExpired)
            completeFinal()
        }
    }

    private companion object {
        const val EVENT_BUFFER = 64
        const val SILENCE_CHUNK_BYTES = 3_200
        const val CLOSE_CODE = 1000
        const val CLOSE_REASON = "1000-"
    }
}
