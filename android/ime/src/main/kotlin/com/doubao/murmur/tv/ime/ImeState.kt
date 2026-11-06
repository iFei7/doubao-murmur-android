package com.doubao.murmur.tv.ime

/**
 * The recording state machine of the IME.
 *
 * Deliberately a closed set: the keyboard UI renders exactly these five situations, and every
 * transition below is driven by [ImeController] only. Keeping it sealed (instead of a bag of
 * booleans) is what makes "you cannot show a level meter while idle" a compile-time property.
 */
sealed interface ImeState {

    /** Nothing is happening; the keyboard is waiting for the viewer. */
    data object Idle : ImeState

    /** Credentials checked, WebSocket opening. Recording has not started yet. */
    data object Connecting : ImeState

    /** Microphone is capturing and partial results may stream in. */
    data object Listening : ImeState

    /** Recording stopped, waiting for the server's final transcript. */
    data class Transcribing(val text: String) : ImeState

    /** Final text has been inserted into the host editor. */
    data class Committed(val text: String) : ImeState
}

/**
 * Everything the Compose keyboard needs, in one immutable snapshot.
 *
 * [errorMessage] is **already localised** by [ImeController], unlike the static labels that the UI
 * resolves itself with `stringResource`. The reason is that it is derived from domain events (a
 * failure classification) that happen outside composition, and keeping `Context` out of this type
 * makes the snapshot cheap to compare, log and unit test. There is deliberately no second free-form
 * "hint" channel: the hint bar is a pure function of [state], so it can never contradict it.
 */
data class ImeUiState(
    val state: ImeState = ImeState.Idle,
    val level: Float = 0f,
    val preview: String = "",
    val loggedIn: Boolean = false,
    val micGranted: Boolean = false,
    val errorMessage: String? = null,
    /**
     * `false` until the first [ImeController.refreshStatus] round trip finished.
     *
     * The keyboard uses it to stay *optimistic*: before the session is read from the encrypted
     * store (a suspend call over the Keystore) the primary button still looks like a talk button
     * instead of flickering through "去登录" for one frame. The action itself re-validates, so an
     * optimistic UI can never start a recording the user is not entitled to.
     */
    val statusChecked: Boolean = false,
) {

    /** True while the microphone is open (either starting up or capturing). */
    val isCapturing: Boolean
        get() = state is ImeState.Connecting || state is ImeState.Listening

    /** True when the level meter and the pulsing dot should animate. */
    val isListening: Boolean
        get() = state is ImeState.Listening

    /** The keyboard must offer "去登录" instead of the talk button. */
    val needsLogin: Boolean
        get() = statusChecked && !loggedIn

    /** The keyboard must offer "授予麦克风" instead of the talk button. */
    val needsMicPermission: Boolean
        get() = statusChecked && loggedIn && !micGranted
}

