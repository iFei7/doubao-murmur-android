package com.doubao.murmur.tv.asr

/**
 * Everything the WSS handshake needs that cannot be hardcoded: the doubao.com cookie jar (the only
 * authentication the endpoint accepts), plus the two identifiers the web client reads out of
 * `localStorage`.
 *
 * @param cookies cookie name/value pairs captured from a logged-in WebView; order is preserved so
 *   the emitted `Cookie` header is stable and diffable.
 * @param deviceId `localStorage['samantha_web_web_id'].web_id`.
 * @param webId `localStorage['__tea_cache_tokens_497858'].web_id`, also sent as `tea_uuid`.
 */
data class AsrCredentials(
    val cookies: Map<String, String>,
    val deviceId: String,
    val webId: String,
) {

    /** Renders the jar as an HTTP `Cookie` header value; an empty jar yields an empty string. */
    fun cookieHeader(): String = cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}
