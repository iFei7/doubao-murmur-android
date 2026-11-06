package com.doubao.murmur.tv.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * The only place in the IME that talks to the host application's text field.
 *
 * ## Why this class exists at all
 * On a TV the "host" is usually a Leanback `SearchBar`, a WebView-backed search box, or a
 * `VideoView` overlay whose editor is little more than a stub. Those editors differ wildly in how
 * much of the `InputConnection` contract they honour, so the insertion strategy has to be
 * centralised, cheap to change and impossible to call by accident from the UI layer.
 *
 * ## Composing vs. direct insertion
 * The recogniser returns **full-text snapshots** (`{"Text":"今天天气"}` replaces the previous
 * sentence, it is not a delta). That matches `setComposingText(text, 1)` exactly: each call
 * replaces the whole composing region with the new snapshot.
 *
 * Some TV editors, however, accept `commitText` but silently ignore `setComposingText` (or draw
 * the composing underline over the wrong range and corrupt the field). For those the caller can
 * switch this class to **direct mode** via [setComposingEnabled]`(false)`:
 * - partial results are then not shown in the field at all (they are still shown in our own
 *   transcript card, so nothing is lost visually),
 * - only the final transcript is inserted, with `commitText`.
 *
 * Direct mode deliberately does **not** `commitText` every partial: because the snapshots are
 * full-text, committing each one would append "今天" "今天天气" "今天天气不错" and produce garbage.
 *
 * ## Safety
 * Every method is a no-op when
 * - the host gave us no [InputConnection] (the editor went away between two callbacks), or
 * - the editor declares `inputType == TYPE_NULL`, which means "I do not accept text" — committing
 *   into such a field throws on some ROMs.
 *
 * All calls are wrapped in `beginBatchEdit`/`endBatchEdit` so that the host does not run its
 * text watchers once per primitive operation while a sentence streams in.
 */
class TextCommitter(
    private val connectionProvider: () -> InputConnection?,
) {

    private var editorInfo: EditorInfo? = null
    private var composingEnabled = true
    private var composingText: String = ""

    /** The current composing text, empty when nothing is being previewed. */
    val composingSnapshot: String
        get() = composingText

    /** True while a composing region is present in the host field. */
    val hasComposingText: Boolean
        get() = composingText.isNotEmpty()

    /**
     * Called from `onStartInputView` / `onStartInput` with the current editor description.
     * Resets the composing bookkeeping: the editor is a different field now.
     */
    fun updateEditor(info: EditorInfo?) {
        editorInfo = info
        composingText = ""
    }

    /**
     * Enables or disables the composing preview (see the class KDoc). Turning it off immediately
     * abandons any composing region so that the host does not keep a dangling underline.
     */
    fun setComposingEnabled(enabled: Boolean) {
        if (composingEnabled == enabled) return
        composingEnabled = enabled
        if (!enabled) abandonComposing()
    }

    /**
     * Replaces the composing region with a full-text snapshot.
     *
     * No-op in direct mode: callers keep showing the snapshot in the transcript card instead.
     */
    fun showComposing(text: String) {
        if (!composingEnabled) return
        if (text.isEmpty()) {
            abandonComposing()
            return
        }
        val connection = connection() ?: return
        composingText = text
        connection.beginBatchEdit()
        try {
            connection.setComposingText(text, 1)
        } finally {
            connection.endBatchEdit()
        }
    }

    /**
     * Inserts the final transcript and clears the composing region.
     *
     * `finishComposingText()` runs first so that the paragraph the user already sees is not
     * duplicated: `commitText` on top of a composing region would insert the text *after* it and
     * the viewer would end up with the sentence twice.
     */
    fun commitFinal(text: String) {
        if (text.isEmpty()) {
            abandonComposing()
            return
        }
        val connection = connection() ?: return
        connection.beginBatchEdit()
        try {
            if (composingText.isNotEmpty()) {
                connection.finishComposingText()
            }
            composingText = ""
            connection.commitText(text, 1)
        } finally {
            connection.endBatchEdit()
        }
    }

    /** Deletes [count] characters before the cursor (backspace). */
    fun deleteBackward(count: Int) {
        if (count <= 0) return
        val connection = connection() ?: return
        connection.beginBatchEdit()
        try {
            // While a composing region exists, deleteSurroundingText would target the host text
            // *behind* the preview and leave the preview intact. Finish it first so that the
            // backspace deletes the character the viewer is actually looking at.
            if (composingText.isNotEmpty()) {
                connection.finishComposingText()
                composingText = ""
            }
            connection.deleteSurroundingText(count, 0)
        } finally {
            connection.endBatchEdit()
        }
    }

    /** Inserts a line break, which is what the ENTER key does on a multi-line field. */
    fun newline() {
        val connection = connection() ?: return
        connection.beginBatchEdit()
        try {
            if (composingText.isNotEmpty()) {
                connection.finishComposingText()
                composingText = ""
            }
            connection.commitText("\n", 1)
        } finally {
            connection.endBatchEdit()
        }
    }

    /**
     * Gives the composing region back to the host without inserting anything.
     *
     * Used when the utterance is cancelled (BACK while recording, the input view was hidden,
     * recognition failed) so the half-finished sentence does not stay in the field as an
     * un-committed underline.
     */
    fun abandonComposing() {
        composingText = ""
        val connection = connection() ?: return
        connection.beginBatchEdit()
        try {
            connection.finishComposingText()
        } finally {
            connection.endBatchEdit()
        }
    }

    /** `false` when the editor explicitly refuses text so that every write can bail out early. */
    private fun acceptsInput(): Boolean {
        val info = editorInfo ?: return true
        return (info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_NULL
    }

    private fun connection(): InputConnection? {
        if (!acceptsInput()) return null
        return connectionProvider()
    }
}
