package com.doubao.murmur.tv.asr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Downstream message. Every field is optional because the endpoint omits them freely: a `finish`
 * event carries no `result`, and an error reply carries only `code`/`message`.
 */
@Serializable
data class ServerMessage(
    val event: String? = null,
    val result: ResultBody? = null,
    val code: Int = 0,
    val message: String? = null,
)

/**
 * Result payload. The wire name really is capitalised `Text`, and the contract keeps that spelling
 * on the Kotlin side too; [SerialName] states it once more so the mapping cannot drift. The name
 * breaks detekt's constructor-parameter pattern on purpose, hence the suppression.
 */
@Serializable
@Suppress("ConstructorParameterNaming")
data class ResultBody(
    @SerialName("Text") val Text: String? = null,
)

/** JSON codec for the downstream channel. Malformed input is never thrown at the caller. */
object AsrMessages {

    private const val EVENT_RESULT = "result"
    private const val EVENT_FINISH = "finish"

    /** Unknown keys are ignored so a server-side field addition cannot break the parser. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Decodes a message, returning `null` for anything that is not a well-formed [ServerMessage]. */
    fun decode(raw: String): ServerMessage? {
        if (raw.isBlank()) return null
        return try {
            json.decodeFromString(ServerMessage.serializer(), raw)
        } catch (ignored: SerializationException) {
            null
        } catch (expected: IllegalArgumentException) {
            null
        }
    }

    /**
     * Maps one raw message to an [AsrEvent].
     *
     * Returns `null` for messages that carry no action: empty transcripts (the server sends a
     * `result` with `Text: ""` for every silent frame) and unknown events.
     */
    fun parse(raw: String): AsrEvent? {
        val message = decode(raw) ?: return null
        val failure = AsrFailures.classifyCode(message.code, message.message)
        if (failure != null) return AsrEvent.Failed(failure)
        return when (message.event) {
            EVENT_RESULT -> partialOrNull(message.result?.Text)
            EVENT_FINISH -> AsrEvent.Finished
            else -> null
        }
    }

    private fun partialOrNull(text: String?): AsrEvent? =
        if (text.isNullOrEmpty()) null else AsrEvent.Partial(text)
}
