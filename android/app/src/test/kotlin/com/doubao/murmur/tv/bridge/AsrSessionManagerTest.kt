package com.doubao.murmur.tv.bridge

import com.doubao.murmur.tv.asr.AsrEngine
import com.doubao.murmur.tv.asr.AsrEvent
import com.doubao.murmur.tv.asr.AsrFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State machine of one bridge session. Every case here is something a flaky Binder client does in
 * practice: it starts twice, it cancels mid-utterance, it disappears without finishing, or it never
 * finishes at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AsrSessionManagerTest {

    @Test
    fun `start opens the engine and reports ready`() = runTest {
        val engine = FakeEngine()
        val manager = AsrSessionManager(scope = this, engineFactory = { engine })
        val callbacks = RecordingCallbacks()

        assertTrue(manager.start(16_000, 1, callbacks))
        runCurrent()

        assertEquals(listOf("ready"), callbacks.events)
        assertEquals(1, engine.startCount)
        assertTrue(manager.isSessionActive)
    }

    @Test
    fun `audio is forwarded to the engine once ready`() = runTest {
        val engine = FakeEngine()
        val manager = AsrSessionManager(scope = this, engineFactory = { engine })
        manager.start(16_000, 1, RecordingCallbacks())
        runCurrent()

        manager.pushAudio(byteArrayOf(1, 0, 2, 0))

        assertEquals(1, engine.feeds.size)
        assertEquals(4, engine.feeds.single().size)
    }

    @Test
    fun `audio pushed while the credentials are still loading is buffered, not dropped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine()
        val manager = AsrSessionManager(
            scope = this,
            engineFactory = {
                gate.await()
                engine
            },
        )
        manager.start(16_000, 1, RecordingCallbacks())
        runCurrent()

        manager.pushAudio(byteArrayOf(1, 0))
        manager.pushAudio(byteArrayOf(2, 0))
        gate.complete(Unit)
        runCurrent()
        // Audio pushed after READY must line up behind the buffered chunks, never overtake them.
        manager.pushAudio(byteArrayOf(3, 0))

        assertEquals(3, engine.feeds.size)
        assertEquals(listOf<Byte>(1, 2, 3), engine.feeds.map { it[0] })
        assertEquals(listOf(2, 2, 2), engine.feeds.map { it.size })
    }

    @Test
    fun `duplicate start cancels the previous session silently`() = runTest {
        val first = FakeEngine()
        val second = FakeEngine()
        val engines = ArrayDeque(listOf(first, second))
        val manager = AsrSessionManager(scope = this, engineFactory = { engines.removeFirst() })
        val firstCallbacks = RecordingCallbacks()
        val secondCallbacks = RecordingCallbacks()

        manager.start(16_000, 1, firstCallbacks)
        runCurrent()
        manager.start(16_000, 1, secondCallbacks)
        runCurrent()

        assertTrue(first.cancelled)
        assertTrue(first.closed)
        assertEquals(listOf("ready"), firstCallbacks.events)
        assertEquals(listOf("ready"), secondCallbacks.events)
        assertEquals(1, second.startCount)
    }

    @Test
    fun `cancel tears the engine down and never reports a final`() = runTest {
        val engine = FakeEngine()
        val manager = AsrSessionManager(scope = this, engineFactory = { engine })
        val callbacks = RecordingCallbacks()
        manager.start(16_000, 1, callbacks)
        runCurrent()

        manager.cancel()
        engine.emit(AsrEvent.Final("late"))
        advanceUntilIdle()

        assertTrue(engine.cancelled)
        assertTrue(engine.closed)
        assertEquals(listOf("ready"), callbacks.events)
        assertFalse(manager.isSessionActive)
    }

    @Test
    fun `finish reports the final transcript exactly once`() = runTest {
        val engine = FakeEngine(finalText = "你好世界")
        val manager = AsrSessionManager(scope = this, engineFactory = { engine })
        val callbacks = RecordingCallbacks()
        manager.start(16_000, 1, callbacks)
        runCurrent()

        manager.finish()
        runCurrent()

        assertEquals(listOf("ready", "final:你好世界"), callbacks.events)
        assertEquals(1, engine.finishCount)
        assertFalse(manager.isSessionActive)

        // A second finish (Gboard retries on a slow bind) must not run the engine again.
        manager.finish()
        runCurrent()
        assertEquals(1, engine.finishCount)
    }

    @Test
    fun `finish without any transcript reports no speech`() = runTest {
        val engine = FakeEngine(finalText = "")
        val manager = AsrSessionManager(scope = this, engineFactory = { engine })
        val callbacks = RecordingCallbacks()
        manager.start(16_000, 1, callbacks)
        runCurrent()

        manager.finish()
        runCurrent()

        assertEquals(1, callbacks.events.count { it.startsWith("error:${BridgeErrorCodes.NO_SPEECH}:") })
        assertTrue(callbacks.events.none { it.startsWith("final:") })
    }

    @Test
    fun `an engine failure keeps the stored session`() = runTest {
        val engine = FakeEngine()
        var rejected = 0
        val manager = AsrSessionManager(
            scope = this,
            engineFactory = { engine },
            onCredentialsRejected = { rejected++ },
        )
        val callbacks = RecordingCallbacks()
        manager.start(16_000, 1, callbacks)
        runCurrent()

        engine.emit(AsrEvent.Failed(AsrFailure.Network))
        runCurrent()

        assertTrue(callbacks.events.any { it.startsWith("error:${BridgeErrorCodes.ENGINE_FAILURE}:") })
        // A network hiccup must never log the viewer out.
        assertEquals(0, rejected)
        assertTrue(engine.cancelled)
        assertFalse(manager.isSessionActive)
    }

    @Test
    fun `a rejected cookie is reported as login expired and the stored session is dropped`() = runTest {
        val engine = FakeEngine()
        var rejected = 0
        val manager = AsrSessionManager(
            scope = this,
            engineFactory = { engine },
            onCredentialsRejected = { rejected++ },
        )
        val callbacks = RecordingCallbacks()
        manager.start(16_000, 1, callbacks)
        runCurrent()

        engine.emit(AsrEvent.Failed(AsrFailure.LoginExpired))
        runCurrent()

        assertTrue(callbacks.events.any { it.startsWith("error:${BridgeErrorCodes.LOGIN_EXPIRED}:") })
        assertTrue(callbacks.events.any { it.contains("expired") })
        assertEquals(1, rejected)
        assertFalse(manager.isSessionActive)
    }

    @Test
    fun `missing credentials report not signed in without starting the engine`() = runTest {
        val manager = AsrSessionManager(scope = this, engineFactory = { null })
        val callbacks = RecordingCallbacks()

        manager.start(16_000, 1, callbacks)
        runCurrent()

        assertTrue(callbacks.events.any { it.startsWith("error:${BridgeErrorCodes.NOT_SIGNED_IN}:") })
        assertFalse(manager.isSessionActive)
    }

    @Test
    fun `invalid audio arguments are rejected synchronously`() = runTest {
        val manager = AsrSessionManager(scope = this, engineFactory = { FakeEngine() })

        assertFalse(manager.start(0, 1, RecordingCallbacks()))
        assertFalse(manager.start(16_000, 0, RecordingCallbacks()))
        assertFalse(manager.start(16_000, 3, RecordingCallbacks()))
        assertFalse(manager.isSessionActive)
    }

    @Test
    fun `an over-large chunk is dropped instead of being sent`() = runTest {
        val engine = FakeEngine()
        val manager = AsrSessionManager(
            scope = this,
            engineFactory = { engine },
            config = AsrSessionManager.Config(maxChunkBytes = 8),
        )
        manager.start(16_000, 1, RecordingCallbacks())
        runCurrent()

        manager.pushAudio(ByteArray(9))
        manager.pushAudio(ByteArray(8))

        assertEquals(1, engine.feeds.size)
        assertEquals(8, engine.feeds.single().size)
    }

    @Test
    fun `a 48 kHz stereo chunk reaches the engine as 16 kHz mono`() = runTest {
        val engine = FakeEngine()
        val manager = AsrSessionManager(scope = this, engineFactory = { engine })
        manager.start(48_000, 2, RecordingCallbacks())
        runCurrent()

        // 480 stereo frames at 48 kHz = 10 ms = 160 mono frames at 16 kHz = 320 bytes.
        manager.pushAudio(ByteArray(480 * 4))

        assertEquals(320, engine.feeds.single().size)
    }

    @Test
    fun `the session is auto-finished after the maximum duration`() = runTest {
        val engine = FakeEngine(finalText = "自动结束")
        val manager = AsrSessionManager(
            scope = this,
            engineFactory = { engine },
            config = AsrSessionManager.Config(maxSessionMs = 1_000),
        )
        val callbacks = RecordingCallbacks()
        manager.start(16_000, 1, callbacks)
        runCurrent()

        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(1, engine.finishCount)
        assertEquals("final:自动结束", callbacks.events.last())
        assertFalse(manager.isSessionActive)
    }

    @Test
    fun `cancel during finish suppresses the pending final`() = runTest {
        val engine = FakeEngine(finalText = "来不及了", finishDelayMs = 1_000)
        val manager = AsrSessionManager(scope = this, engineFactory = { engine })
        val callbacks = RecordingCallbacks()
        manager.start(16_000, 1, callbacks)
        runCurrent()

        manager.finish()
        runCurrent()
        manager.cancel()
        advanceUntilIdle()

        assertEquals(listOf("ready"), callbacks.events)
        assertTrue(engine.cancelled)
    }

    @Test
    fun `pushAudio without a session is a no-op`() = runTest {
        val manager = AsrSessionManager(scope = this, engineFactory = { FakeEngine() })

        manager.pushAudio(byteArrayOf(1, 2))
        manager.finish()
        manager.cancel()
        runCurrent()

        assertFalse(manager.isSessionActive)
    }

    private class FakeEngine(
        private val finalText: String = "text",
        private val finishDelayMs: Long = 0,
    ) : AsrEngine {

        private val sink = MutableSharedFlow<AsrEvent>(extraBufferCapacity = 32)
        override val events: Flow<AsrEvent> = sink

        val feeds = mutableListOf<ByteArray>()
        var startCount = 0
            private set
        var finishCount = 0
            private set
        var cancelled = false
            private set
        var closed = false
            private set

        override suspend fun start() {
            startCount++
        }

        override fun feed(pcm: ByteArray) {
            feeds.add(pcm)
        }

        override suspend fun stopAndAwaitFinal(timeoutMs: Long): String {
            finishCount++
            if (finishDelayMs > 0) delay(finishDelayMs)
            if (finalText.isNotEmpty()) sink.tryEmit(AsrEvent.Final(finalText))
            return finalText
        }

        override fun cancel() {
            cancelled = true
        }

        override fun close() {
            closed = true
        }

        fun emit(event: AsrEvent) {
            sink.tryEmit(event)
        }
    }

    private class RecordingCallbacks : AsrSessionManager.Callbacks {
        val events = mutableListOf<String>()

        override fun onReady() {
            events.add("ready")
        }

        override fun onPartial(text: String) {
            events.add("partial:$text")
        }

        override fun onFinal(text: String) {
            events.add("final:$text")
        }

        override fun onError(code: Int, message: String) {
            events.add("error:$code:$message")
        }
    }
}
