package com.doubao.murmur.tv.bridge

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.doubao.murmur.tv.app.AppGraph
import com.doubao.murmur.tv.asr.AsrEngine
import com.doubao.murmur.tv.asr.DoubaoAsrEngine
import com.doubao.murmur.tv.auth.SessionRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Exposes doubao's streaming recogniser to the patched Gboard over Binder.
 *
 * Gboard's own `AsrClient` runs inside Gboard's process; it cannot read our Keystore-backed cookie,
 * and it must not embed the login flow. This service is the seam: Gboard pushes PCM and receives
 * partial/final text, all credential handling stays here.
 *
 * ## Security
 * The service is exported (a different app has to bind to it) but **not** permission-protected:
 * `android:permission` would need a signature-level permission, and Gboard is re-signed with a
 * different key, so a signature check can never pass. The caller is therefore authenticated by
 * package name, resolved from `Binder.getCallingUid()` in **every transaction**. Anything that is
 * not [BridgeCallerVerifier.GBOARD_PACKAGE] gets a `SecurityException`, which means an
 * unauthorized app can obtain the stub from [onBind] but cannot make it do any work. Nothing but
 * the transcript crosses the Binder boundary: no cookie, no device id, no token.
 *
 * ## Lifetime
 * The service is bound-only; no foreground notification is needed because the utterance lasts
 * seconds and the binding keeps the process alive. [onUnbind] aborts an in-flight session so a
 * client that goes away cannot leave the stream (and the quota) open; a max-duration guard in
 * [AsrSessionManager] covers the rest.
 */
class DoubaoAsrBridgeService : Service() {

    /** All recognition work; cancelled in [onDestroy]. */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var sessionRepository: SessionRepository
    private lateinit var httpClient: OkHttpClient
    private lateinit var sessions: AsrSessionManager

    /** The single Binder object handed to every (already authorized) client. */
    private val bridge: IDoubaoAsrBridge.Stub = object : IDoubaoAsrBridge.Stub() {

        override fun isSignedIn(): Boolean {
            enforceCaller()
            // A Binder thread is never the main thread, so blocking here is safe; the store read
            // still hops to IO because it unseals the Keystore master key on first use.
            return runCatching {
                runBlocking { withContext(Dispatchers.IO) { sessionRepository.isLoggedIn() } }
            }.getOrDefault(false)
        }

        override fun startRecognition(
            sampleRate: Int,
            channels: Int,
            callback: IDoubaoAsrCallback?,
        ): Boolean {
            enforceCaller()
            if (callback == null) {
                Log.w(TAG, "startRecognition rejected: no callback")
                return false
            }
            val accepted = sessions.start(sampleRate, channels, BinderCallbacks(callback))
            if (!accepted) {
                Log.w(TAG, "startRecognition rejected: ${sampleRate}Hz/${channels}ch")
            }
            return accepted
        }

        override fun pushAudio(pcm: ByteArray?) {
            enforceCaller()
            if (pcm == null) return
            sessions.pushAudio(pcm)
        }

        override fun finishAudio() {
            enforceCaller()
            sessions.finish()
        }

        override fun cancel() {
            enforceCaller()
            sessions.cancel()
        }

        override fun openLoginActivity() {
            enforceCaller()
            // The service lives in the app process, so `packageName` is the release id
            // (com.doubao.murmur.tv) on release builds and the .debug variant locally; the FQCN of
            // the activity never changes between variants.
            val intent = Intent()
                .setClassName(packageName, LOGIN_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(intent) }
                .onFailure { Log.w(TAG, "cannot open the login activity", it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessionRepository = AppGraph.sessionRepository(this)
        httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // 0 = no read timeout: a WebSocket that idles between utterances must not be killed by
            // the transport; the engine implements its own inactivity policy.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            // Retrying would multiply the login-expired failures we surface to the client.
            .retryOnConnectionFailure(false)
            .build()
        val invalidator = BridgeCredentialInvalidator(
            repository = sessionRepository,
            scope = scope,
            log = { line -> Log.d(TAG, line) },
        )
        sessions = AsrSessionManager(
            scope = scope,
            engineFactory = ::createEngine,
            onCredentialsRejected = invalidator::invalidate,
            log = { line -> Log.d(TAG, line) },
        )
    }

    /**
     * Hands out the stub unconditionally; the caller is checked in every transaction instead.
     *
     * Checking here would be wrong *and* useless: `Service.onBind` runs on the main thread, outside
     * any Binder transaction, so `Binder.getCallingUid()` reports this process' own uid and the
     * check would reject Gboard. Lint's `BinderGetCallingInMainThread` encodes exactly that trap.
     */
    override fun onBind(intent: Intent?): IBinder = bridge

    override fun onUnbind(intent: Intent?): Boolean {
        // A client that unbinds mid-utterance is gone; never leave the stream running for it.
        sessions.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        sessions.cancel()
        scope.cancel()
        runCatching { httpClient.dispatcher.executorService.shutdown() }
        runCatching { httpClient.connectionPool.evictAll() }
        super.onDestroy()
    }

    /**
     * Builds one engine per utterance from the stored session, or null when the viewer is not
     * signed in. Called from [AsrSessionManager]'s background scope.
     */
    private suspend fun createEngine(): AsrEngine? {
        val credentials = try {
            withContext(Dispatchers.IO) { sessionRepository.current() }
        } catch (t: Throwable) {
            Log.w(TAG, "reading doubao credentials failed: ${t::class.java.simpleName}")
            return null
        } ?: return null
        return DoubaoAsrEngine(credentials, httpClient)
    }

    private fun enforceCaller() {
        if (isCallerAllowed()) return
        Log.w(TAG, "bridge call rejected for uid ${Binder.getCallingUid()}")
        throw SecurityException("bridge access is limited to ${BridgeCallerVerifier.GBOARD_PACKAGE}")
    }

    private fun isCallerAllowed(): Boolean =
        BridgeCallerVerifier.isAllowed(packageManager.getPackagesForUid(Binder.getCallingUid()))

    /**
     * Adapts [AsrSessionManager.Callbacks] to the remote proxy.
     *
     * A dead client (its process exited) throws `DeadObjectException` on every call; swallowing it
     * keeps the service alive and lets the max-duration guard close the session.
     */
    private class BinderCallbacks(
        private val callback: IDoubaoAsrCallback,
    ) : AsrSessionManager.Callbacks {

        override fun onReady() = deliver("onReady") { callback.onReady() }

        override fun onPartial(text: String) = deliver("onPartial") { callback.onPartial(text) }

        override fun onFinal(text: String) = deliver("onFinal") { callback.onFinal(text) }

        override fun onError(code: Int, message: String) =
            deliver("onError") { callback.onError(code, message) }

        private inline fun deliver(what: String, block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "$what failed: ${t::class.java.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "DoubaoAsrBridge"

        /**
         * FQCN of the login screen in `:app`. The manifest fixes it, and `:app` depends on this
         * module (never the other way around), so the class cannot be referenced directly.
         */
        const val LOGIN_ACTIVITY = "com.doubao.murmur.tv.app.login.LoginActivity"
    }
}
