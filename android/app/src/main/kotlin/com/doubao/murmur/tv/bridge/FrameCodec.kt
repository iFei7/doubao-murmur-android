package com.doubao.murmur.tv.bridge

/**
 * Wire format of the localhost bridge protocol (version 1, frozen by the Gboard client):
 *
 * ```
 * +----------------+------------+-------------------+
 * | 4 bytes BE N   | 1 byte type| (N - 1) bytes body|
 * +----------------+------------+-------------------+
 * ```
 *
 * `N` counts the type byte, so an empty body is encoded as `N = 1`. Big-endian is not negotiable:
 * the Gboard client hardcodes `DataInputStream.readInt()`, which is big-endian by definition.
 *
 * Everything here is pure and JVM-testable; the socket plumbing lives in [LocalAsrSocketServer].
 */
object FrameCodec {

    /** Length prefix: a 4 byte big-endian frame size. */
    const val HEADER_BYTES = 4

    /** Upper bound for `N`, body included. One MiB is far above a 100 ms PCM chunk. */
    const val MAX_FRAME_BYTES = 1 shl 20

    /** Prefixes `payload` with its type and length. */
    fun encode(type: Byte, payload: ByteArray = EMPTY): ByteArray {
        val frameLength = payload.size + 1
        require(frameLength <= MAX_FRAME_BYTES) {
            "frame of $frameLength bytes exceeds the $MAX_FRAME_BYTES byte limit"
        }
        val out = ByteArray(HEADER_BYTES + frameLength)
        out[0] = (frameLength ushr 24).toByte()
        out[1] = (frameLength ushr 16).toByte()
        out[2] = (frameLength ushr 8).toByte()
        out[3] = frameLength.toByte()
        out[HEADER_BYTES] = type
        payload.copyInto(out, HEADER_BYTES + 1)
        return out
    }

    private val EMPTY = ByteArray(0)
}

/** One decoded frame. [type] is kept raw so an unknown type can be rejected by the caller. */
class Frame(val type: Byte, val payload: ByteArray)

/** Base class for a frame stream that cannot be decoded; the connection must be dropped. */
sealed class FrameFormatException(message: String) : Exception(message)

/** `N` was zero or negative: the client is not speaking this protocol. */
class MalformedFrameException(val frameLength: Int) :
    FrameFormatException("invalid frame length $frameLength")

/** `N` exceeded [FrameCodec.MAX_FRAME_BYTES]; rejecting it is the memory guard. */
class FrameTooLargeException(val frameLength: Int) :
    FrameFormatException("frame length $frameLength exceeds ${FrameCodec.MAX_FRAME_BYTES}")

/**
 * Incremental frame splitter.
 *
 * A TCP read returns whatever happened to arrive: half a header, two frames glued together, or one
 * frame split across three reads. [offer] therefore buffers the remainder and returns only complete
 * frames, in order. The instance is confined to one connection's reader thread.
 */
class FrameDecoder(private val maxFrameBytes: Int = FrameCodec.MAX_FRAME_BYTES) {

    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0

    /**
     * Appends [length] bytes of [data] (from [offset]) and returns every frame completed by them.
     *
     * @throws FrameFormatException the stream is unusable; the caller must close the connection.
     */
    fun offer(data: ByteArray, offset: Int = 0, length: Int = data.size): List<Frame> {
        append(data, offset, length)
        val frames = mutableListOf<Frame>()
        while (size >= FrameCodec.HEADER_BYTES) {
            val frameLength = readFrameLength()
            if (frameLength <= 0) throw MalformedFrameException(frameLength)
            if (frameLength > maxFrameBytes) throw FrameTooLargeException(frameLength)

            val total = FrameCodec.HEADER_BYTES + frameLength
            if (size < total) break

            val type = buffer[FrameCodec.HEADER_BYTES]
            val payload = buffer.copyOfRange(FrameCodec.HEADER_BYTES + 1, total)
            frames.add(Frame(type, payload))
            consume(total)
        }
        return frames
    }

    /** Bytes buffered but not yet part of a complete frame; handy for diagnostics and tests. */
    val pendingBytes: Int
        get() = size

    private fun readFrameLength(): Int {
        val b0 = (buffer[0].toInt() and 0xFF) shl 24
        val b1 = (buffer[1].toInt() and 0xFF) shl 16
        val b2 = (buffer[2].toInt() and 0xFF) shl 8
        val b3 = buffer[3].toInt() and 0xFF
        return b0 or b1 or b2 or b3
    }

    private fun append(data: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "range [$offset, ${offset + length}) is outside the ${data.size} byte chunk"
        }
        if (size + length > buffer.size) {
            var capacity = buffer.size
            while (capacity < size + length) capacity = capacity shl 1
            buffer = buffer.copyOf(capacity)
        }
        System.arraycopy(data, offset, buffer, size, length)
        size += length
    }

    /** Drops [count] bytes from the front, keeping the incomplete tail in place. */
    private fun consume(count: Int) {
        val remaining = size - count
        if (remaining > 0) {
            System.arraycopy(buffer, count, buffer, 0, remaining)
        }
        size = remaining
    }

    private companion object {
        const val INITIAL_CAPACITY = 16 * 1024
    }
}
