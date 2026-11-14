package com.doubao.murmur.tv.bridge

import com.doubao.murmur.tv.asr.AsrCredentials
import com.doubao.murmur.tv.auth.CredentialStore
import com.doubao.murmur.tv.auth.SessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Expired means signed out" is a product decision: the bridge clears the stored session when the
 * server rejects the cookie, so `isSignedIn` cannot keep answering true for a dead login. These
 * tests pin both halves: a successful purge, and the no-false-logout guarantee when the purge
 * itself fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeCredentialInvalidatorTest {

    @Test
    fun `invalidating drops the stored session so isSignedIn turns false`() = runTest {
        val store = InMemoryCredentialStore()
        val repository = SessionRepository(store)
        assertTrue(repository.isLoggedIn())

        BridgeCredentialInvalidator(repository, this).invalidate()
        runCurrent()

        assertNull(store.credentials)
        assertFalse(repository.isLoggedIn())
    }

    @Test
    fun `a failing store is reported and does not crash the caller`() = runTest {
        val store = InMemoryCredentialStore(failOnClear = true)
        val repository = SessionRepository(store)
        val lines = mutableListOf<String>()

        BridgeCredentialInvalidator(repository, this, log = { lines.add(it) }).invalidate()
        runCurrent()

        assertTrue(lines.any { it.contains("clearing the rejected session failed") })
        // Nothing was deleted, so the store still holds the session; the client still gets code 9
        // and the viewer can sign in again, which overwrites it.
        assertTrue(repository.isLoggedIn())
    }

    private class InMemoryCredentialStore(
        private val failOnClear: Boolean = false,
    ) : CredentialStore {

        var credentials: AsrCredentials? = AsrCredentials(
            cookies = linkedMapOf("sessionid" to "abc"),
            deviceId = "dev-1",
            webId = "web-1",
        )
            private set

        override suspend fun load(): AsrCredentials? = credentials

        override suspend fun save(credentials: AsrCredentials) {
            this.credentials = credentials
        }

        override suspend fun clear() {
            if (failOnClear) error("keystore unavailable")
            credentials = null
        }

        override suspend fun isLoggedIn(): Boolean = credentials != null
    }
}
