package com.doubao.murmur.tv.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `decodePayload` is the pure half of [EncryptedCredentialStore]: the encrypted-preferences
 * plumbing needs an Android Keystore and cannot run on the JVM, but the decode/validation rules -
 * what counts as a usable stored session - are exactly what deserves unit coverage.
 *
 * The JSON shape mirrors what [EncryptedCredentialStore.save] writes.
 */
class EncryptedCredentialStoreTest {

    @Test
    fun `a full payload decodes into credentials`() {
        val payload = """
            {"cookies":{"sessionid":"abc","ttwid":"1xxx"},"deviceId":"dev-1","webId":"web-1"}
        """.trimIndent()
        val credentials = EncryptedCredentialStore.decodePayload(payload)
        assertEquals(
            com.doubao.murmur.tv.asr.AsrCredentials(
                cookies = linkedMapOf("sessionid" to "abc", "ttwid" to "1xxx"),
                deviceId = "dev-1",
                webId = "web-1",
            ),
            credentials,
        )
    }

    @Test
    fun `an empty cookie value is dropped from the decoded jar`() {
        // doubao.com clears the sessionid value on logout; the persisted blob can carry an empty
        // value, which is not a session and must not survive the decode.
        val payload = """
            {"cookies":{"sessionid":"","ttwid":"1xxx"},"deviceId":"dev-1","webId":"web-1"}
        """.trimIndent()
        val credentials = EncryptedCredentialStore.decodePayload(payload)
        assertEquals(setOf("ttwid"), credentials?.cookies?.keys)
    }

    @Test
    fun `an all-empty cookie jar decodes to null`() {
        val payload = """
            {"cookies":{"sessionid":"","sid_tt":""},"deviceId":"dev-1","webId":"web-1"}
        """.trimIndent()
        assertNull(EncryptedCredentialStore.decodePayload(payload))
    }

    @Test
    fun `a payload without cookies decodes to null`() {
        assertNull(
            EncryptedCredentialStore.decodePayload(
                """{"deviceId":"dev-1","webId":"web-1"}""",
            ),
        )
    }

    @Test
    fun `an empty cookie jar decodes to null`() {
        assertNull(
            EncryptedCredentialStore.decodePayload(
                """{"cookies":{},"deviceId":"dev-1","webId":"web-1"}""",
            ),
        )
    }

    @Test
    fun `missing device or web id decodes to null`() {
        assertNull(
            EncryptedCredentialStore.decodePayload(
                """{"cookies":{"sessionid":"abc"},"deviceId":"","webId":"web-1"}""",
            ),
        )
        assertNull(
            EncryptedCredentialStore.decodePayload(
                """{"cookies":{"sessionid":"abc"},"deviceId":"dev-1","webId":""}""",
            ),
        )
    }
}
