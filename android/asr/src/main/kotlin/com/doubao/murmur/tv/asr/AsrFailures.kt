package com.doubao.murmur.tv.asr

import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Turns wire-level and transport-level failures into [AsrFailure] values.
 *
 * All three entry points are pure functions on purpose: the login-vs-server-vs-network split is the
 * part of the protocol that is easiest to get wrong, and it must be testable without a socket.
 * The code and keyword sets mirror the reference client in `linux/src/doubao_murmur/config.py`.
 */
object AsrFailures {

    /** `login invalid`: the cookie is not a login at all. */
    private const val CODE_LOGIN_INVALID = 710012001

    /** `tourist reach limited`: sent right before the server drops an expired session. */
    private const val CODE_TOURIST_LIMITED = 710022013

    /** `Invalid`: the request itself was refused. */
    private const val CODE_TOKEN_INVALID = 709599054

    private val LOGIN_CODES = setOf(CODE_LOGIN_INVALID, CODE_TOURIST_LIMITED)

    private val AUTH_KEYWORDS = listOf("cookie", "auth", "login", "session", "expired", "unauthorized")

    /**
     * Classifies a message `code`. `0` means success and yields `null`; a non-zero code with no
     * known meaning becomes [AsrFailure.Server] so the caller can surface the raw code.
     */
    fun classifyCode(code: Int, message: String?): AsrFailure? {
        if (code == 0) return null
        if (code in LOGIN_CODES) return AsrFailure.LoginExpired
        if (code == CODE_TOKEN_INVALID) return AsrFailure.TokenInvalid
        val lower = message?.lowercase().orEmpty()
        if (AUTH_KEYWORDS.any { lower.contains(it) }) return AsrFailure.LoginExpired
        return AsrFailure.Server(code, message.orEmpty())
    }

    /**
     * Classifies a refused WebSocket handshake.
     *
     * A cookie that is not a login gets **HTTP 200** with a JSON body such as
     * `{"code":710012001,"message":"login invalid"}`, so a 200 is not proof of success here. Only
     * auth-flavoured codes are reported: an unrelated non-zero code is left to the transport
     * classifier, which keeps "the handshake was refused because of the login" from being claimed
     * on a coincidental server error.
     */
    fun classifyHandshake(statusCode: Int, body: String?): AsrFailure? {
        if (statusCode == 401 || statusCode == 403) return AsrFailure.LoginExpired
        if (statusCode != 200 || body == null) return null
        val message = AsrMessages.decode(body) ?: return null
        return when (val failure = classifyCode(message.code, message.message)) {
            is AsrFailure.LoginExpired -> failure
            is AsrFailure.TokenInvalid -> failure
            else -> null
        }
    }

    /** Maps a thrown transport exception; only timeouts are distinguishable from generic failures. */
    fun classifyThrowable(t: Throwable): AsrFailure =
        if (t is SocketTimeoutException || t is InterruptedIOException) {
            AsrFailure.Timeout
        } else {
            AsrFailure.Network
        }
}
