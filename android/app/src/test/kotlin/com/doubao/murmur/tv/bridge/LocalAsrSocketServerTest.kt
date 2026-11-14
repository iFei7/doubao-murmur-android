package com.doubao.murmur.tv.bridge

import com.doubao.murmur.tv.asr.AsrEngine
import com.doubao.murmur.tv.asr.AsrEvent
import com.doubao.murmur.tv.asr.AsrFailure
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end coverage of the localhost protocol: a real socket, the real frame splitter and the
 * real session manager, with only the network engine faked. This is the test that would have caught
 * a wrong length prefix or a READY emitted before the engine actually started.
 */
class LocalAsrSocketServerTest {

    private var server: LocalAsrSocketServer? = null
    private lateinit var engine: TestEngine

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    @Test
    fun `a valid session reports ready then partial then final`() {
        val port = startServer()
        connect(port).use { socket ->
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            send(output, BridgeFrameTypes.START, START_JSON.toByteArray())

            assertEquals(BridgeFrameTypes.READY, readFrame(input).first)

            send(output, BridgeFrameTypes.AUDIO, ByteArray(320))
            engine.emit(AsrEvent.Partial("你好"))
            val partial = readFrame(input)
            assertEquals(BridgeFrameTypes.PARTIAL, partial.first)
            assertEquals("你好", String(partial.second, Charsets.UTF_8))

            send(output, BridgeFrameTypes.FINISH)
            val finalFrame = readFrame(input)
            assertEquals(BridgeFrameTypes.FINAL, finalFrame.first)
            assertEquals("你好世界", String(finalFrame.second, Charsets.UTF_8))

            assertEquals(1, engine.feeds.size)
            assertEquals(320, engine.feeds.single().size)
        }
    }

    @Test
    fun `audio is converted to 16 kHz mono before it reaches the engine`() {
        val port = startServer()
        connect(port).use { socket ->
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            send(
                output,
                BridgeFrameTypes.START,
                """{"token":"dba-voice-1","sampleRate":48000,"channels":2}""".toByteArray(),
            )
            assertEquals(BridgeFrameTypes.READY, readFrame(input).first)

            // 480 stereo frames at 48 kHz = 10 ms = 160 mono frames at 16 kHz = 320 bytes.
            send(output, BridgeFrameTypes.AUDIO, ByteArray(480 * 4))
            send(output, BridgeFrameTypes.FINISH)
            readFrame(input)

            assertEquals(320, engine.feeds.single().size)
        }
    }

    @Test
    fun `a wrong token is answered with an error and the connection is closed`() {
        val port = startServer()
        connect(port).use { socket ->
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            send(
                output,
                BridgeFrameTypes.START,
                """{"token":"not-the-token","sampleRate":16000,"channels":1}""".toByteArray(),
            )

            val error = readFrame(input)
            assertEquals(BridgeFrameTypes.ERROR, error.first)
            assertEquals(BridgeErrorCodes.UNAUTHORIZED, BridgeProtocols.parseError(error.second)?.code)
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun `a malformed START payload is rejected`() {
        val port = startServer()
        connect(port).use { socket ->
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            send(output, BridgeFrameTypes.START, "not json".toByteArray())

            val error = readFrame(input)
            assertEquals(BridgeFrameTypes.ERROR, error.first)
            assertEquals(BridgeErrorCodes.PROTOCOL_ERROR, BridgeProtocols.parseError(error.second)?.code)
        }
    }

    @Test
    fun `three sequential connections each finish cleanly`() {
        val port = startServer()
        repeat(3) { round ->
            connect(port).use { socket ->
                val output = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

                send(output, BridgeFrameTypes.START, START_JSON.toByteArray())
                assertEquals(BridgeFrameTypes.READY, readFrame(input).first)
                send(output, BridgeFrameTypes.AUDIO, ByteArray(320))
                send(output, BridgeFrameTypes.FINISH)

                val finalFrame = readFrame(input)
                assertEquals(BridgeFrameTypes.FINAL, finalFrame.first)
                assertEquals("你好世界", String(finalFrame.second, Charsets.UTF_8))
            }
            assertTrue("round $round left the port closed", server!!.port > 0)
        }
        // One forwarded chunk per round: nothing accumulated across connections.
        assertEquals(3, engine.feeds.size)
    }

    @Test
    fun `an expired cookie reports code 9, purges the session and closes the connection`() {
        val rejected = AtomicInteger()
        engine = TestEngine()
        val local = LocalAsrSocketServer(
            engineFactory = { engine },
            onCredentialsRejected = { rejected.incrementAndGet() },
            portRange = 0..0,
        )
        server = local
        val port = local.start()

        connect(port).use { socket ->
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            send(output, BridgeFrameTypes.START, START_JSON.toByteArray())
            assertEquals(BridgeFrameTypes.READY, readFrame(input).first)

            engine.emit(AsrEvent.Failed(AsrFailure.LoginExpired))

            val error = readFrame(input)
            assertEquals(BridgeFrameTypes.ERROR, error.first)
            assertEquals(BridgeErrorCodes.LOGIN_EXPIRED, BridgeProtocols.parseError(error.second)?.code)
            // The error is flushed before the close, so the client can show "sign in again".
            assertEquals(-1, input.read())
            assertEquals(1, rejected.get())
        }
    }

    @Test
    fun `an unknown frame type is rejected`() {
        val port = startServer()
        connect(port).use { socket ->
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            send(output, 0x55, ByteArray(0))

            val error = readFrame(input)
            assertEquals(BridgeFrameTypes.ERROR, error.first)
            assertEquals(BridgeErrorCodes.PROTOCOL_ERROR, BridgeProtocols.parseError(error.second)?.code)
        }
    }

    @Test
    fun `an over-large frame is refused instead of buffered`() {
        val port = startServer()
        connect(port).use { socket ->
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            // Header only: 2 MiB declared, which is over FrameCodec.MAX_FRAME_BYTES.
            output.writeInt(2 shl 20)
            output.flush()

            val error = readFrame(input)
            assertEquals(BridgeFrameTypes.ERROR, error.first)
            assertEquals(BridgeErrorCodes.PROTOCOL_ERROR, BridgeProtocols.parseError(error.second)?.code)
        }
    }

    @Test
    fun `a cancelled session never reports a final`() {
        val port = startServer()
        connect(port).use { socket ->
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            send(output, BridgeFrameTypes.START, START_JSON.toByteArray())
            assertEquals(BridgeFrameTypes.READY, readFrame(input).first)

            send(output, BridgeFrameTypes.CANCEL)
            // A second session on the same connection must work; the cancelled one stays silent.
            send(output, BridgeFrameTypes.START, START_JSON.toByteArray())
            assertEquals(BridgeFrameTypes.READY, readFrame(input).first)
        }
    }

    @Test
    fun `a busy port is skipped`() {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { blocker ->
            val busy = blocker.localPort
            assumeTrue("ephemeral port at the top of the range", busy < 65_535)

            engine = TestEngine()
            val local = LocalAsrSocketServer(engineFactory = { engine }, portRange = busy..busy + 1)
            server = local
            val bound = local.start()

            assertTrue(bound > 0)
            assertNotEquals(busy, bound)
        }
    }

    private fun startServer(): Int {
        engine = TestEngine()
        val local = LocalAsrSocketServer(engineFactory = { engine }, portRange = 0..0)
        server = local
        val port = local.start()
        assertTrue("server did not bind", port > 0)
        return port
    }

    private fun connect(port: Int): Socket = Socket().apply {
        connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS)
        soTimeout = READ_TIMEOUT_MS
    }

    private fun send(output: DataOutputStream, type: Byte, payload: ByteArray = ByteArray(0)) {
        output.write(FrameCodec.encode(type, payload))
        output.flush()
    }

    private fun readFrame(input: DataInputStream): Pair<Byte, ByteArray> {
        val frameLength = input.readInt()
        val type = input.readByte()
        val payload = ByteArray(frameLength - 1)
        input.readFully(payload)
        return type to payload
    }

    /** Minimal engine: only the events the protocol exposes are simulated. */
    private class TestEngine(private val finalText: String = "你好世界") : AsrEngine {

        private val sink = MutableSharedFlow<AsrEvent>(extraBufferCapacity = 32)
        override val events: Flow<AsrEvent> = sink

        val feeds = CopyOnWriteArrayList<ByteArray>()

        override suspend fun start() = Unit

        override fun feed(pcm: ByteArray) {
            feeds.add(pcm)
        }

        override suspend fun stopAndAwaitFinal(timeoutMs: Long): String = finalText

        override fun cancel() = Unit

        override fun close() = Unit

        fun emit(event: AsrEvent) {
            sink.tryEmit(event)
        }
    }

    private companion object {
        const val START_JSON = """{"token":"dba-voice-1","sampleRate":16000,"channels":1}"""
        const val CONNECT_TIMEOUT_MS = 2_000
        const val READ_TIMEOUT_MS = 5_000
    }
}
