package com.doubao.murmur.tv.asr

import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Socket-level tests against [MockWebServer]. They exercise the real OkHttp WebSocket stack, so the
 * assertions are about event sequences and byte counts, never about exact timings.
 */
class DoubaoAsrEngineTest {

    private val client = OkHttpClient()
    private val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val events: MutableList<AsrEvent> = Collections.synchronizedList(mutableListOf<AsrEvent>())

    private lateinit var server: MockWebServer
    private lateinit var engine: DoubaoAsrEngine
    private var collector: Job? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        collector?.cancel()
        if (this::engine.isInitialized) engine.close()
        server.shutdown()
        testScope.cancel()
    }

    private fun newEngine(quietPeriodMs: Long = 60L): DoubaoAsrEngine = DoubaoAsrEngine(
        credentials = AsrCredentials(
            cookies = linkedMapOf("sessionid" to "test-session"),
            deviceId = "device-123",
            webId = "web-456",
        ),
        client = client,
        baseUrl = "ws://${server.hostName}:${server.port}/samantha/audio/asr",
        quietPeriodMs = quietPeriodMs,
    )

    /**
     * Subscribes before the engine can emit anything. The engine's flow has `replay = 0`, so a
     * collector that starts late would miss `Connected`; `UNDISPATCHED` runs the body until the
     * shared flow has registered it, then continues on [Dispatchers.Default].
     */
    private fun startCollecting() {
        collector = testScope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { events.add(it) }
        }
    }

    private fun snapshot(): List<AsrEvent> = synchronized(events) { events.toList() }

    private fun awaitEvent(timeoutMs: Long = 5_000, matches: (AsrEvent) -> Boolean): AsrEvent {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = synchronized(events) { events.firstOrNull(matches) }
            if (found != null) return found
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("no matching event within ${timeoutMs}ms, saw ${snapshot()}")
    }

    private fun awaitFailure(timeoutMs: Long = 5_000): AsrEvent.Failed =
        awaitEvent(timeoutMs) { it is AsrEvent.Failed } as AsrEvent.Failed

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    private fun connect() {
        startCollecting()
        runBlocking { engine.start() }
        awaitEvent { it is AsrEvent.Connected }
    }

    @Test
    fun `handshake carries the cookie, the origin and the query parameters`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ServerListener() {}))
        engine = newEngine()
        runBlocking { engine.start() }

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "no handshake request" }
        assertEquals("sessionid=test-session", request.getHeader("Cookie"))
        assertEquals("https://www.doubao.com", request.getHeader("Origin"))
        val path = checkNotNull(request.path) { "handshake request had no path" }
        assertTrue(path, path.startsWith("/samantha/audio/asr?"))
        assertTrue(path, path.contains("format=pcm"))
        assertTrue(path, path.contains("device_id=device-123"))
        assertTrue(path, path.contains("web_id=web-456"))
        assertTrue(path, path.contains("tea_uuid=web-456"))
        assertTrue(path, path.contains("web_tab_id="))
        assertTrue(path, path.contains("&region=&"))
        assertTrue(path, path.contains("&sys_region=&"))
    }

    @Test
    fun `fed audio reaches the server byte for byte`() {
        val frames = LinkedBlockingQueue<ByteString>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : ServerListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    frames.add(bytes)
                }
            }),
        )
        engine = newEngine()
        connect()

        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(5, 6, 7, 8)
        engine.feed(first)
        engine.feed(second)

        val received = listOf(
            checkNotNull(frames.poll(5, TimeUnit.SECONDS)) { "first frame missing" },
            checkNotNull(frames.poll(5, TimeUnit.SECONDS)) { "second frame missing" },
        )
        assertArrayEquals(first, received[0].toByteArray())
        assertArrayEquals(second, received[1].toByteArray())
    }

    @Test
    fun `stop appends exactly the trailing silence`() {
        val total = AtomicInteger()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : ServerListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    total.addAndGet(bytes.size)
                }
            }),
        )
        engine = newEngine()
        connect()

        val audio = ByteArray(3_200) { 7 }
        engine.feed(audio)
        waitUntil { total.get() >= audio.size }
        val beforeStop = total.get()

        runBlocking { engine.stopAndAwaitFinal(2_000) }

        val expected = beforeStop + AsrProtocol.TRAILING_SILENCE_BYTES
        waitUntil { total.get() >= expected }
        assertEquals(expected, total.get())
    }

    @Test
    fun `stop returns the newest partial once the stream goes quiet`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : ServerListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"event":"result","result":{"Text":"你"},"code":0,"message":""}""")
                    webSocket.send("""{"event":"result","result":{"Text":"你好"},"code":0,"message":""}""")
                }
            }),
        )
        engine = newEngine()
        connect()

        awaitEvent { it == AsrEvent.Partial("你好") }
        val finalText = runBlocking { engine.stopAndAwaitFinal(2_000) }

        assertEquals("你好", finalText)
        assertTrue(snapshot().contains(AsrEvent.Partial("你")))
        assertTrue(snapshot().contains(AsrEvent.Partial("你好")))
    }

    @Test
    fun `a server close before any message means the login expired`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : ServerListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(1000, "1000-")
                }
            }),
        )
        engine = newEngine()
        connect()

        val failed = awaitFailure()
        assertEquals(AsrFailure.LoginExpired, failed.failure)
        assertNull(failed.cause)
    }

    /**
     * Server side of the socket. OkHttp's contract is that the peer which receives a close frame must
     * answer it, otherwise the closing handshake never completes and MockWebServer keeps the
     * connection (and its task queue) alive; the real doubao server answers on its own.
     */
    private open class ServerListener : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
    }

    private companion object {
        const val POLL_MS = 10L
    }
}
