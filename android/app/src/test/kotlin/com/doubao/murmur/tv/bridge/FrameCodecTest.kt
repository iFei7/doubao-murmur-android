package com.doubao.murmur.tv.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame splitter is the only place where a hostile or merely unlucky TCP stream can hurt the
 * service, so everything is pinned here: big-endian length, sticky packets, half packets, and the
 * two rejection paths (bad length / frame over 1 MiB).
 */
class FrameCodecTest {

    @Test
    fun `encode writes a big-endian length that counts the type byte`() {
        val encoded = FrameCodec.encode(BridgeFrameTypes.START, byteArrayOf(0x41, 0x42))

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 3, BridgeFrameTypes.START, 0x41, 0x42),
            encoded,
        )
    }

    @Test
    fun `encode of an empty payload counts only the type byte`() {
        val encoded = FrameCodec.encode(BridgeFrameTypes.FINISH)

        assertArrayEquals(byteArrayOf(0, 0, 0, 1, BridgeFrameTypes.FINISH), encoded)
    }

    @Test
    fun `encode rejects a payload over the frame limit`() {
        val tooLarge = ByteArray(FrameCodec.MAX_FRAME_BYTES)

        assertTrue(
            runCatching { FrameCodec.encode(BridgeFrameTypes.AUDIO, tooLarge) }.isFailure,
        )
    }

    @Test
    fun `decode returns one complete frame`() {
        val decoder = FrameDecoder()
        val frames = decoder.offer(FrameCodec.encode(BridgeFrameTypes.AUDIO, byteArrayOf(1, 2, 3)))

        assertEquals(1, frames.size)
        assertEquals(BridgeFrameTypes.AUDIO, frames[0].type)
        assertArrayEquals(byteArrayOf(1, 2, 3), frames[0].payload)
        assertEquals(0, decoder.pendingBytes)
    }

    @Test
    fun `decode returns two sticky frames from a single read`() {
        val decoder = FrameDecoder()
        val first = FrameCodec.encode(BridgeFrameTypes.START, byteArrayOf(0x7B, 0x7D))
        val second = FrameCodec.encode(BridgeFrameTypes.CANCEL)
        val chunk = first + second

        val frames = decoder.offer(chunk)

        assertEquals(2, frames.size)
        assertEquals(BridgeFrameTypes.START, frames[0].type)
        assertArrayEquals(byteArrayOf(0x7B, 0x7D), frames[0].payload)
        assertEquals(BridgeFrameTypes.CANCEL, frames[1].type)
        assertEquals(0, frames[1].payload.size)
        assertEquals(0, decoder.pendingBytes)
    }

    @Test
    fun `a half header yields nothing until the rest arrives`() {
        val decoder = FrameDecoder()
        val frame = FrameCodec.encode(BridgeFrameTypes.PARTIAL, byteArrayOf(0x68, 0x69))

        assertTrue(decoder.offer(frame, 0, 2).isEmpty())
        assertEquals(2, decoder.pendingBytes)
        assertTrue(decoder.offer(frame, 2, 1).isEmpty())

        val frames = decoder.offer(frame, 3, frame.size - 3)
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x68, 0x69), frames[0].payload)
        assertEquals(0, decoder.pendingBytes)
    }

    @Test
    fun `a frame split in the middle of its payload is reassembled`() {
        val decoder = FrameDecoder()
        val payload = ByteArray(200) { (it and 0xFF).toByte() }
        val frame = FrameCodec.encode(BridgeFrameTypes.AUDIO, payload)
        // Header + type + 10 payload bytes first, the remainder second.
        val splitAt = FrameCodec.HEADER_BYTES + 1 + 10

        assertTrue(decoder.offer(frame, 0, splitAt).isEmpty())
        val frames = decoder.offer(frame, splitAt, frame.size - splitAt)

        assertEquals(1, frames.size)
        assertArrayEquals(payload, frames[0].payload)
    }

    @Test
    fun `feeding one byte at a time still produces the frames`() {
        val decoder = FrameDecoder()
        val stream = FrameCodec.encode(BridgeFrameTypes.START, byteArrayOf(1)) +
            FrameCodec.encode(BridgeFrameTypes.FINISH)

        val frames = mutableListOf<Frame>()
        stream.forEach { byte -> frames += decoder.offer(byteArrayOf(byte)) }

        assertEquals(2, frames.size)
        assertEquals(BridgeFrameTypes.START, frames[0].type)
        assertEquals(BridgeFrameTypes.FINISH, frames[1].type)
    }

    @Test
    fun `an oversized declared length is rejected as soon as the header is complete`() {
        val decoder = FrameDecoder(maxFrameBytes = 1024)

        val failure = runCatching {
            decoder.offer(byteArrayOf(0, 0, 0x10, 0x00)) // 4096
        }.exceptionOrNull()

        assertTrue(failure is FrameTooLargeException)
    }

    @Test
    fun `a zero length frame is rejected`() {
        val decoder = FrameDecoder()

        val failure = runCatching { decoder.offer(byteArrayOf(0, 0, 0, 0)) }.exceptionOrNull()

        assertTrue(failure is MalformedFrameException)
    }

    @Test
    fun `a negative length frame is rejected`() {
        val decoder = FrameDecoder()

        val failure = runCatching {
            decoder.offer(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        }.exceptionOrNull()

        assertTrue(failure is MalformedFrameException)
    }

    @Test
    fun `an unknown type is surfaced verbatim for the caller to reject`() {
        val decoder = FrameDecoder(maxFrameBytes = 16)
        val frames = decoder.offer(FrameCodec.encode(0x55, byteArrayOf(9)))

        assertEquals(1, frames.size)
        assertEquals(0x55.toByte(), frames[0].type)
    }

    @Test
    fun `an empty chunk changes nothing`() {
        val decoder = FrameDecoder()

        assertTrue(decoder.offer(ByteArray(0)).isEmpty())
        assertEquals(0, decoder.pendingBytes)
    }
}
