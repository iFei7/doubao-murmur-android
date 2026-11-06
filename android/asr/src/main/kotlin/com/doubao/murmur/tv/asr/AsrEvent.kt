package com.doubao.murmur.tv.asr

/**
 * One event of an ASR session.
 *
 * [Partial] carries the whole transcript so far (the server overwrites rather than appends) and is
 * emitted once per audio frame received, so consumers must treat it as replaceable state.
 */
sealed interface AsrEvent {

    /** The WebSocket handshake completed; audio may be fed. */
    data object Connected : AsrEvent

    /** In-progress transcript, replacing any previous [Partial]. */
    data class Partial(val text: String) : AsrEvent

    /** Final transcript of the session. */
    data class Final(val text: String) : AsrEvent

    /** The server declared the session over (`{"event":"finish"}`). */
    data object Finished : AsrEvent

    /** The session cannot deliver a transcript; [cause] is the transport exception when there is one. */
    data class Failed(val failure: AsrFailure, val cause: Throwable? = null) : AsrEvent
}

/**
 * Why an ASR session failed.
 *
 * The two login failures are kept apart because the recovery differs: an expired login needs a new
 * WebView sign-in, an invalid token usually means the cookie jar was captured from a stale page.
 */
sealed interface AsrFailure {

    /** Cookie no longer represents a logged-in user (server code 710012001 / 710022013). */
    data object LoginExpired : AsrFailure

    /** The request was rejected as invalid (server code 709599054). */
    data object TokenInvalid : AsrFailure

    /** Any other server-side rejection. */
    data class Server(val code: Int, val message: String) : AsrFailure

    /** Transport-level failure: DNS, reset, TLS, proxy. */
    data object Network : AsrFailure

    /** Connect or read timed out. */
    data object Timeout : AsrFailure
}
