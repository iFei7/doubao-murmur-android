package com.doubao.murmur.tv.auth

import com.doubao.murmur.tv.asr.AsrCredentials

/**
 * Persistence boundary for the Doubao session material.
 *
 * A stored [AsrCredentials] is equivalent to a logged-in browser session: the cookies alone are
 * enough to open the WSS endpoint as the user. Implementations must therefore never write the
 * values to a log, to an analytics event, or to a backup, and must never store them in plaintext.
 *
 * All methods suspend because the real implementation talks to the Android Keystore, which can be
 * slow enough on TV hardware (200+ ms on a cold Keystore) to matter on the main thread.
 */
interface CredentialStore {

    /**
     * Returns the stored credentials, or null when nothing is stored or the stored blob can no
     * longer be decrypted (Keystore reset, restore onto another device).
     */
    suspend fun load(): AsrCredentials?

    /** Replaces the stored credentials. Throws only when persistence really failed. */
    suspend fun save(credentials: AsrCredentials)

    /** Removes the stored credentials. Must be safe to call when nothing is stored. */
    suspend fun clear()

    /** Convenience for gating the "log in" screen; equivalent to `load() != null`. */
    suspend fun isLoggedIn(): Boolean
}
