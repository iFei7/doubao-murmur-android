package com.doubao.murmur.tv.bridge

/**
 * Error codes passed to [IDoubaoAsrCallback.onError].
 *
 * These live here rather than in the AIDL file because the two AIDL signatures are frozen (the
 * patched Gboard client hardcodes the transaction order); the codes travel as plain `int`s and the
 * client keeps its own copy of this list. Never renumber: they are part of the wire contract.
 */
object BridgeErrorCodes {

    /** No stored doubao session; the client should call `openLoginActivity`. */
    const val NOT_SIGNED_IN = 1

    /** `startRecognition` was called with an unusable sample rate or channel count. */
    const val BAD_AUDIO_FORMAT = 2

    /** `finishAudio` arrived before a single audio byte was pushed. */
    const val EMPTY_AUDIO = 3

    /** The recognition backend failed (network, timeout, rejected credentials, protocol). */
    const val ENGINE_FAILURE = 4

    /** The stream ended without any transcript (silence, or nearly silent audio). */
    const val NO_SPEECH = 5

    /** The client's callback died (its process exited mid-session). */
    const val CALLBACK_FAILED = 6

    /** Socket transport only: the START frame carried the wrong (or no) shared token. */
    const val UNAUTHORIZED = 7

    /** Socket transport only: the frame stream itself is unusable (bad length, unknown type). */
    const val PROTOCOL_ERROR = 8

    /**
     * The stored doubao cookie was rejected by the server; the viewer has to sign in again.
     * Kept separate from [ENGINE_FAILURE] because it is the only failure the client can recover
     * from by opening the login screen.
     */
    const val LOGIN_EXPIRED = 9
}
