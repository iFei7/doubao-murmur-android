package com.doubao.murmur.tv.bridge

import com.doubao.murmur.tv.auth.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drops the stored doubao session after the server rejected it.
 *
 * ## Why the bridge must clear it
 * The bridge reports [BridgeErrorCodes.LOGIN_EXPIRED] to Gboard, but a dead cookie left in the
 * Keystore produces a "signed in but every utterance fails" loop: `isSignedIn` keeps answering
 * true because it only checks that a decryptable session exists. Clearing on the rejection is what
 * makes the recovery path ("sign in again") actually reachable, and it matches the desktop client,
 * which drops its cookie on 401.
 *
 * Only a rejected cookie triggers this — a network hiccup or a server error must never log the
 * viewer out.
 *
 * The cleanup is asynchronous: [invalidate] is called from an engine callback thread and returns
 * immediately, so a slow Keystore can never stall the transcript delivery.
 *
 * @param repository the same repository `:ime` and the login screen use, so the IME flips back to
 *   the "not signed in" state as well. That is intended.
 * @param scope owns the cleanup coroutine; it should outlive the individual session.
 */
class BridgeCredentialInvalidator(
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {

    /** Clears the stored session; safe to call repeatedly and from any thread. */
    fun invalidate() {
        scope.launch {
            runCatching { repository.clear() }
                .onFailure { log("clearing the rejected session failed: ${it::class.java.simpleName}") }
            // The global WebView cookie jar holds the same dead session; leaving it in place would
            // let the next login "succeed" instantly off the stale cookies without the viewer
            // actually signing in again.
            repository.clearWebViewState()
        }
    }
}
