package com.doubao.murmur.tv.ime.settings

import android.content.Context
import com.doubao.murmur.tv.ime.ImeController

/**
 * Small persisted settings of the IME.
 *
 * Plain `SharedPreferences` on purpose: `:ime` must not grow a DataStore dependency, and the whole
 * state is two scalars written at human speed from a settings screen.
 */
internal class ImePreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Whether partial transcripts are previewed in the host field via `setComposingText`.
     *
     * On by default: the live preview is the main reason voice input feels responsive. Turned off
     * for the editors that corrupt their text when a composing region is present (see
     * [com.doubao.murmur.tv.ime.TextCommitter]).
     */
    var composingEnabled: Boolean
        get() = prefs.getBoolean(KEY_COMPOSING, true)
        set(value) = prefs.edit().putBoolean(KEY_COMPOSING, value).apply()

    /**
     * How long to wait for the server's final transcript after the viewer releases OK.
     *
     * The protocol sends a `finish` event after 250 ms of silence on its side; 3 s is the safe
     * default for a slow mobile-tethered network.
     */
    var finalTimeoutMs: Long
        get() = prefs.getLong(KEY_FINAL_TIMEOUT, DEFAULT_FINAL_TIMEOUT_MS)
        set(value) = prefs.edit().putLong(KEY_FINAL_TIMEOUT, value).apply()

    companion object {
        private const val FILE_NAME = "ime_settings"
        private const val KEY_COMPOSING = "composing_enabled"
        private const val KEY_FINAL_TIMEOUT = "final_timeout_ms"

        /** Single source of truth for the default: the same constant the controller falls back to. */
        const val DEFAULT_FINAL_TIMEOUT_MS = ImeController.DEFAULT_FINAL_TIMEOUT_MS

        /** The three options offered on the settings screen, in milliseconds. */
        val FINAL_TIMEOUT_OPTIONS_MS = longArrayOf(1500L, 2000L, 3000L)
    }
}
