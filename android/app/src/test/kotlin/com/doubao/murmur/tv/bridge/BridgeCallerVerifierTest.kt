package com.doubao.murmur.tv.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge service is exported and spends the viewer's doubao quota, so only the patched Gboard
 * may bind to it. These tests pin the allow-list semantics: null/empty/mismatched package lists
 * are all rejected, and the Gboard package is accepted case-sensitively.
 */
class BridgeCallerVerifierTest {

    @Test
    fun `accepts the patched gboard package`() {
        assertTrue(BridgeCallerVerifier.isAllowed(arrayOf("com.google.android.inputmethod.latin")))
    }

    @Test
    fun `accepts gboard among other shared-uid packages`() {
        assertTrue(
            BridgeCallerVerifier.isAllowed(
                arrayOf("com.example.shared", "com.google.android.inputmethod.latin"),
            ),
        )
    }

    @Test
    fun `rejects an unresolved uid`() {
        assertFalse(BridgeCallerVerifier.isAllowed(null))
    }

    @Test
    fun `rejects a uid without packages`() {
        assertFalse(BridgeCallerVerifier.isAllowed(emptyArray()))
    }

    @Test
    fun `rejects any other app`() {
        assertFalse(BridgeCallerVerifier.isAllowed(arrayOf("com.example.attacker")))
    }

    @Test
    fun `does not accept a package name that merely contains gboard`() {
        assertFalse(
            BridgeCallerVerifier.isAllowed(
                arrayOf("com.google.android.inputmethod.latin.evil", "com.evil.latin"),
            ),
        )
    }
}
