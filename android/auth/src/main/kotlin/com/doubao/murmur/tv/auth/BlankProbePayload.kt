package com.doubao.murmur.tv.auth

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Decoded blank-page probe payload (see `LoginActivity.runBlankPageCheck` in :app).
 *
 * Lives here next to [WebViewCredentialExtractor]'s snapshot decoding because both decode the
 * same `evaluateJavascript` channel, which delivers a **JSON-encoded JavaScript value**: an
 * object the page returns arrives as a quoted, escaped string literal, i.e. the callback
 * receives `"{\"ready\":...}"` and the literal has to be unwrapped once more before the inner
 * object parses. The app-side blank-page check missed that second layer from v0.2.2 to v0.2.6,
 * which made every probe report "unreadable" and silently disabled the blank-screen detection.
 */
data class BlankProbeReading(
    /** `document.readyState` of the probed page, e.g. "complete". */
    val ready: String,
    /** Rendered body-text length (`document.body.innerText.length`), -1 when missing. */
    val len: Int,
    /** Final URL of the probed page (redirect target included), "" when missing. */
    val url: String,
)

/**
 * Parses an `evaluateJavascript` callback payload into a [BlankProbeReading].
 *
 * Accepts both shapes the callback can deliver - the (correct) double-encoded string literal and
 * a directly encoded object - and returns null for null, empty input, JSON null and any payload
 * that does not decode into an object. Never throws.
 */
fun parseBlankProbePayload(raw: String?): BlankProbeReading? {
    if (raw.isNullOrEmpty() || raw == "null") {
        return null
    }
    return try {
        val outer = JSONTokener(raw).nextValue()
        val value = if (outer is String) JSONTokener(outer).nextValue() else outer
        val obj = value as? JSONObject ?: return null
        BlankProbeReading(
            ready = obj.optString("ready"),
            len = obj.optInt("len", -1),
            url = obj.optString("url"),
        )
    } catch (e: JSONException) {
        null
    }
}
