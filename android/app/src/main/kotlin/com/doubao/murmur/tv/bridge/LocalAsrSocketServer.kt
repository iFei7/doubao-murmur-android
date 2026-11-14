package com.doubao.murmur.tv.bridge

import com.doubao.murmur.tv.asr.AsrEngine
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Localhost TCP front end for the recognition bridge.
 *
 * ## Why a socket and not only Binder
 * The patched Gboard targets SDK 37, where package visibility hides our service from `bindService`
 * unless the client declares a `<queries>` entry — which a re-signed Gboard cannot rely on. A
 * loopback socket has no such gate: it needs no permission, no manifest entry and no AIDL stub.
 * The Binder service is kept as a secondary channel for clients that can see the package.
 *
 * ## Protocol (version 1, frozen)
 * ```
 * 4 bytes BE N | 1 byte type | (N - 1) bytes payload
 * ```
 * Client → service: `0x01 START` (JSON token/sampleRate/channels), `0x02 AUDIO` (raw Int16LE PCM),
 * `0x03 FINISH`, `0x04 CANCEL`. Service → client: `0x81 READY`, `0x82 PARTIAL` (UTF-8 text),
 * `0x83 FINAL` (UTF-8 text), `0x84 ERROR` (`{"code":..,"message":".."}`).
 *
 * ## Threading
 * One accept thread plus one reader thread per connection; both are daemons so they can never keep
 * a dying process alive. Coroutine work (credentials, WebSocket, transcript) lives in [scope].
 * Per-connection writes are serialized on a monitor, because the engine calls back from arbitrary
 * threads.
 *
 * ## Session lifetime
 * A new connection supersedes the previous one: the old socket is closed and its session cancelled
 * before the new session may start. A dropped connection is an implicit CANCEL. The class is
 * single-use: [stop] cancels [scope], so a stopped instance cannot be restarted.
 */
class LocalAsrSocketServer(
    engineFactory: suspend () -> AsrEngine?,
    onCredentialsRejected: () -> Unit = {},
    private val log: (String) -> Unit = {},
    private val portRange: IntRange = DEFAULT_PORTS,
) {

    /** Coroutine home of the recognition engine; cancelled by [stop]. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sessions =
        AsrSessionManager(scope, engineFactory, onCredentialsRejected, log = log)

    private val stateLock = Any()
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false
    private var connection: Connection? = null

    /** The bound port, or [NO_PORT] before [start] / after [stop]. */
    val port: Int
        get() = synchronized(stateLock) { serverSocket?.localPort ?: NO_PORT }

    /**
     * Binds the first free port in [portRange] and starts accepting. Returns the bound port, or
     * [NO_PORT] when every candidate is taken. Idempotent: a second call returns the same port.
     */
    fun start(): Int = synchronized(stateLock) {
        if (running) return serverSocket?.localPort ?: NO_PORT
        val socket = bind() ?: return NO_PORT
        serverSocket = socket
        running = true
        Thread({ acceptLoop(socket) }, ACCEPT_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
        log("bridge listening on 127.0.0.1:${socket.localPort}")
        socket.localPort
    }

    /** Stops listening, drops the active connection (implicit cancel) and shuts the engine scope. */
    fun stop() {
        val socket: ServerSocket?
        val active: Connection?
        synchronized(stateLock) {
            if (!running) return
            running = false
            socket = serverSocket
            active = connection
            serverSocket = null
            connection = null
        }
        runCatching { socket?.close() }
        active?.close()
        sessions.cancel()
        scope.cancel()
    }

    // ---------------------------------------------------------------- accept path

    private fun bind(): ServerSocket? {
        for (candidate in portRange) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), candidate), BACKLOG)
                return socket
            } catch (e: IOException) {
                runCatching { socket.close() }
                log("port $candidate unavailable: ${e::class.java.simpleName}")
            }
        }
        log("no free port in ${portRange.first}..${portRange.last}")
        return null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (running) log("accept failed: ${e::class.java.simpleName}")
                return
            }
            startConnection(socket)
        }
    }

    private fun startConnection(socket: Socket) {
        val fresh = Connection(socket)
        val previous = synchronized(stateLock) {
            if (!running) {
                fresh.close()
                null
            } else {
                connection.also { connection = fresh }
            }
        }
        if (!fresh.isOpen) return

        // Supersede: closing the old socket makes its reader exit, and cancelling its session
        // guarantees no late FINAL can be delivered to the previous client.
        previous?.close()
        sessions.cancel()

        Thread({ serve(fresh) }, CONNECTION_THREAD_NAME).apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- connection path

    private fun serve(target: Connection) {
        val socket = target.socket
        try {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val writeLock = Any()
            val decoder = FrameDecoder()
            val buffer = ByteArray(READ_BUFFER_BYTES)

            // One iteration per read: `pump` returns false once the peer is gone or misbehaved.
            while (running && target.isOpen && pump(target, input, output, writeLock, decoder, buffer)) {
                // keep reading
            }
        } catch (e: IOException) {
            if (target.isOpen) log("connection dropped: ${e::class.java.simpleName}")
        } finally {
            val wasActive = synchronized(stateLock) {
                if (connection === target) {
                    connection = null
                    true
                } else {
                    false
                }
            }
            // Only the connection that still owns the session may cancel it: a superseded reader
            // exiting late must not kill the session of the client that replaced it.
            if (wasActive) sessions.cancel()
            target.close()
        }
    }

    /**
     * Reads one chunk, decodes every frame it completed and handles them in order.
     *
     * @return false when the connection is finished: EOF, an IO error, an unusable frame stream, or
     *   a frame that asked for a close.
     */
    private fun pump(
        target: Connection,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        writeLock: Any,
        decoder: FrameDecoder,
        buffer: ByteArray,
    ): Boolean {
        val count = try {
            input.read(buffer)
        } catch (e: IOException) {
            if (target.isOpen) log("connection dropped: ${e::class.java.simpleName}")
            return false
        }
        if (count < 0) return false

        val frames = try {
            decoder.offer(buffer, 0, count)
        } catch (e: FrameFormatException) {
            log("closing a connection with a malformed frame stream: ${e.message}")
            writeError(output, writeLock, BridgeErrorCodes.PROTOCOL_ERROR, e.message.orEmpty())
            return false
        }

        for (frame in frames) {
            if (!target.isOpen || !handleFrame(target, frame, output, writeLock)) return false
        }
        return true
    }

    /** Handles one frame; false means "close this connection". */
    private fun handleFrame(
        target: Connection,
        frame: Frame,
        output: BufferedOutputStream,
        writeLock: Any,
    ): Boolean = when (frame.type) {
        BridgeFrameTypes.START -> handleStart(target, frame.payload, output, writeLock)
        BridgeFrameTypes.AUDIO -> {
            sessions.pushAudio(frame.payload)
            true
        }

        BridgeFrameTypes.FINISH -> {
            sessions.finish()
            true
        }

        BridgeFrameTypes.CANCEL -> {
            sessions.cancel()
            true
        }

        else -> {
            log("rejecting unknown frame type 0x${frame.type.toUByte().toString(16)}")
            writeError(output, writeLock, BridgeErrorCodes.PROTOCOL_ERROR, "unknown frame type")
            false
        }
    }

    private fun handleStart(
        target: Connection,
        payload: ByteArray,
        output: BufferedOutputStream,
        writeLock: Any,
    ): Boolean {
        val request = BridgeProtocols.parseStart(payload)
        if (request == null) {
            log("rejecting START: malformed payload")
            writeError(output, writeLock, BridgeErrorCodes.PROTOCOL_ERROR, "malformed START payload")
            return false
        }
        if (!BridgeProtocols.isTokenValid(request)) {
            log("rejecting START: wrong token")
            writeError(output, writeLock, BridgeErrorCodes.UNAUTHORIZED, "invalid token")
            return false
        }

        val accepted = sessions.start(
            sampleRate = request.sampleRate,
            channels = request.channels,
            callbacks = SocketCallbacks(target, output, writeLock),
        )
        if (!accepted) {
            log("rejecting START: unsupported audio format")
            writeError(
                output,
                writeLock,
                BridgeErrorCodes.BAD_AUDIO_FORMAT,
                "unsupported audio format",
            )
            return false
        }
        return true
    }

    /** Adapts [AsrSessionManager.Callbacks] to protocol frames on one connection. */
    private inner class SocketCallbacks(
        private val target: Connection,
        private val output: BufferedOutputStream,
        private val writeLock: Any,
    ) : AsrSessionManager.Callbacks {

        override fun onReady() = writeFrame(output, writeLock, BridgeFrameTypes.READY)

        override fun onPartial(text: String) =
            writeFrame(output, writeLock, BridgeFrameTypes.PARTIAL, text.toByteArray(Charsets.UTF_8))

        override fun onFinal(text: String) =
            writeFrame(output, writeLock, BridgeFrameTypes.FINAL, text.toByteArray(Charsets.UTF_8))

        override fun onError(code: Int, message: String) {
            writeError(output, writeLock, code, message)
            // Nothing the client can do on this connection: it has to open the app to sign in
            // (or sign in again), or it asked for an audio format we cannot convert. The error is
            // written first, then the connection is closed.
            if (code == BridgeErrorCodes.NOT_SIGNED_IN ||
                code == BridgeErrorCodes.LOGIN_EXPIRED ||
                code == BridgeErrorCodes.BAD_AUDIO_FORMAT
            ) {
                target.close()
            }
        }
    }

    // ---------------------------------------------------------------- io helpers

    private fun writeFrame(
        output: OutputStream,
        writeLock: Any,
        type: Byte,
        payload: ByteArray = EMPTY_PAYLOAD,
    ) {
        synchronized(writeLock) {
            try {
                output.write(FrameCodec.encode(type, payload))
                output.flush()
            } catch (e: IOException) {
                // The client is gone; its reader loop notices and tears the session down.
                log("frame write failed: ${e::class.java.simpleName}")
            }
        }
    }

    private fun writeError(output: OutputStream, writeLock: Any, code: Int, message: String) {
        writeFrame(output, writeLock, BridgeFrameTypes.ERROR, BridgeProtocols.encodeError(code, message))
    }

    /** One accepted client. [close] is idempotent and visible across the reader/callback threads. */
    private class Connection(val socket: Socket) {

        @Volatile
        var isOpen: Boolean = true
            private set

        fun close() {
            isOpen = false
            runCatching { socket.close() }
        }
    }

    companion object {
        /** First candidate port; the Gboard client hardcodes this one. */
        const val DEFAULT_PORT = 17321

        /** Candidates tried in order when the port is taken (another instance, adb forward…). */
        val DEFAULT_PORTS: IntRange = DEFAULT_PORT..17330

        /** Returned by [start] when no port could be bound. */
        const val NO_PORT = -1

        private const val BACKLOG = 4
        private const val READ_BUFFER_BYTES = 16 * 1024
        private const val ACCEPT_THREAD_NAME = "doubao-asr-accept"
        private const val CONNECTION_THREAD_NAME = "doubao-asr-connection"
        private val EMPTY_PAYLOAD = ByteArray(0)
    }
}
