package com.doubao.murmur.tv.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Frame type bytes of the localhost bridge protocol (version 1).
 *
 * Values are frozen: the Gboard client hardcodes them. Client-to-service types are `0x01`-`0x04`,
 * service-to-client types keep bit 7 set (`0x81`-`0x84`), so a stray echo can never be mistaken for
 * a request.
 */
object BridgeFrameTypes {

    const val START: Byte = 0x01
    const val AUDIO: Byte = 0x02
    const val FINISH: Byte = 0x03
    const val CANCEL: Byte = 0x04

    val READY: Byte = 0x81.toByte()
    val PARTIAL: Byte = 0x82.toByte()
    val FINAL: Byte = 0x83.toByte()
    val ERROR: Byte = 0x84.toByte()
}

/** `{"token":"dba-voice-1","sampleRate":16000,"channels":1}` — the START payload. */
@Serializable
data class StartRequest(
    val token: String = "",
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
)

/** `{"code":4,"message":"..."}` — the ERROR payload. */
@Serializable
data class ErrorPayload(val code: Int, val message: String)

/**
 * JSON payloads of the bridge protocol.
 *
 * The localhost socket is reachable by any app on the device, so START must carry the shared token;
 * that token is a speed bump, not a real secret (it is in the APK), which is why the service also
 * does not expose credentials over the socket — only transcripts.
 *
 * Pure functions, so the parsing and the token rule are covered by JVM tests.
 */
object BridgeProtocols {

    /** Shared token the patched Gboard sends in START. */
    const val TOKEN = "dba-voice-1"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Parses a START payload; null when it is not the expected JSON object. */
    fun parseStart(payload: ByteArray): StartRequest? = runCatching {
        json.decodeFromString(StartRequest.serializer(), payload.toString(Charsets.UTF_8))
    }.getOrNull()

    /** True when [request] carries [TOKEN] exactly. */
    fun isTokenValid(request: StartRequest): Boolean = request.token == TOKEN

    /** Serializes an ERROR payload. */
    fun encodeError(code: Int, message: String): ByteArray = json
        .encodeToString(ErrorPayload.serializer(), ErrorPayload(code, message))
        .toByteArray(Charsets.UTF_8)

    /** Test/diagnostic helper: decodes an ERROR payload. */
    fun parseError(payload: ByteArray): ErrorPayload? = runCatching {
        json.decodeFromString(ErrorPayload.serializer(), payload.toString(Charsets.UTF_8))
    }.getOrNull()
}
