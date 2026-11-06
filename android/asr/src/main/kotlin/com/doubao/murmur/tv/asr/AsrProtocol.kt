package com.doubao.murmur.tv.asr

import java.net.URLEncoder

/**
 * Wire format of doubao's streaming ASR endpoint, transcribed from a live browser session
 * (`docs/doubao-wss-asr-spec.md`). No handshake message and no sub-protocol: once the socket is open
 * the client writes raw 16 kHz mono little-endian PCM frames, and the server answers with JSON text.
 */
object AsrProtocol {

    const val WSS_ENDPOINT: String = "wss://ws-samantha.doubao.com/samantha/audio/asr"

    /** The endpoint rejects handshakes whose `Origin` is not the web client. */
    const val ORIGIN: String = "https://www.doubao.com"

    /** Sample rate the service expects; PCM is 16 bit mono, hence two bytes per sample. */
    const val SAMPLE_RATE: Int = 16_000

    /** Digital silence flushed before a stop; see `DoubaoAsrEngine.stopAndAwaitFinal`. */
    const val TRAILING_SILENCE_MS: Int = 200

    /** 16 000 samples/s * 2 bytes * 0.2 s = 6 400 bytes. */
    const val TRAILING_SILENCE_BYTES: Int = SAMPLE_RATE * 2 * TRAILING_SILENCE_MS / 1000

    /**
     * Query parameters the web client shares across API calls. Insertion order is meaningful only
     * for readability, but it is kept stable so URLs can be compared in tests and logs.
     */
    val FIXED_QUERY_PARAMS: Map<String, String> = linkedMapOf(
        "version_code" to "20800",
        "language" to "zh",
        "device_platform" to "web",
        "aid" to "497858",
        "real_aid" to "497858",
        "pkg_type" to "release_version",
        "pc_version" to "3.12.3",
        "region" to "",
        "sys_region" to "",
        "samantha_web" to "1",
        "use-olympus-account" to "1",
        "format" to "pcm",
    )

    /**
     * Builds the handshake URL: fixed parameters first, then `device_id`, `web_id`, `tea_uuid` and
     * `web_tab_id` in that order.
     *
     * Empty values must survive as `region=` — dropping the `=` makes the server reject the query,
     * which is why every value goes through the encoder (the empty string encodes to the empty
     * string) instead of being filtered out. Values are encoded because identifiers come from
     * `localStorage` and are not guaranteed to be plain digits.
     *
     * @param baseUrl overridable so tests can point at a local `ws://` server.
     */
    fun buildUrl(
        credentials: AsrCredentials,
        webTabId: String,
        baseUrl: String = WSS_ENDPOINT,
    ): String {
        val params = LinkedHashMap<String, String>(FIXED_QUERY_PARAMS)
        params["device_id"] = credentials.deviceId
        params["web_id"] = credentials.webId
        params["tea_uuid"] = credentials.webId
        params["web_tab_id"] = webTabId
        val query = params.entries.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        return "$baseUrl?$query"
    }

    /** Parses one downstream JSON message; see [AsrMessages.parse]. */
    fun parse(raw: String): AsrEvent? = AsrMessages.parse(raw)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
