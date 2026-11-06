package com.doubao.murmur.tv.app

import android.content.Context
import com.doubao.murmur.tv.asr.AsrCredentials
import com.doubao.murmur.tv.asr.AsrEngine
import com.doubao.murmur.tv.asr.DoubaoAsrEngine
import com.doubao.murmur.tv.auth.EncryptedCredentialStore
import com.doubao.murmur.tv.auth.SessionRepository
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Hand-rolled dependency container for the single-APK app module.
 *
 * A DI framework would buy nothing here: the graph has three nodes, the whole app lives in one
 * process, and the IME service pulls the same instances through this object so that credentials
 * are read from one place only.
 *
 * Everything is created lazily and kept for the lifetime of the process; only the application
 * context is ever retained, so a configuration change or a finished activity cannot leak.
 */
object AppGraph {

    /** Connecting to the ASR edge should fail fast when the box is offline or DNS is broken. */
    private const val CONNECT_TIMEOUT_SECONDS = 5L

    /**
     * ASR runs over a long-lived WebSocket: the socket idles between partial results while the
     * user keeps the talk key pressed, so a read timeout would kill healthy sessions. Zero means
     * "wait forever" in OkHttp; liveness is enforced by the protocol and by our own timeouts.
     */
    private const val READ_TIMEOUT_MILLIS = 0L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile
    private var repository: SessionRepository? = null

    /** Shared HTTP client. Always the same instance so the connection pool is reused. */
    fun okHttpClient(): OkHttpClient = client

    /**
     * Credential repository backed by the Android Keystore.
     *
     * The store is built from [Context.getApplicationContext] because the repository outlives the
     * activity that first asked for it; passing an activity here would keep it alive forever.
     */
    fun sessionRepository(context: Context): SessionRepository {
        val appContext = context.applicationContext
        return repository ?: synchronized(this) {
            repository ?: SessionRepository(EncryptedCredentialStore(appContext)).also {
                repository = it
            }
        }
    }

    /**
     * Factory used by the diagnostics self-test: one engine per run, closed afterwards.
     *
     * Keeping it a factory instead of a singleton makes it obvious that [AsrEngine] is
     * single-session: the IME creates a new engine for every utterance.
     */
    fun asrEngineFactory(): (AsrCredentials) -> AsrEngine = { credentials ->
        DoubaoAsrEngine(credentials, okHttpClient())
    }
}
