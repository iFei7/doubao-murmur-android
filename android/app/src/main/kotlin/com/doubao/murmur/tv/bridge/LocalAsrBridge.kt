package com.doubao.murmur.tv.bridge

import android.content.Context
import android.util.Log
import com.doubao.murmur.tv.app.AppGraph
import com.doubao.murmur.tv.asr.DoubaoAsrEngine
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Process-wide owner of the localhost socket bridge.
 *
 * Started from `MurmurApplication.onCreate` so the listener exists for as long as the doubao process
 * does — Gboard cannot start an activity on our behalf, and its first connection attempt has to find
 * a listening port or the dictation button stays dead.
 *
 * A single instance is created lazily and kept forever: the server binds a fixed port range, and a
 * second instance would only fight the first for the port. Failures are logged, never thrown into
 * the app's startup path.
 */
object LocalAsrBridge {

    private val lock = Any()

    private var server: LocalAsrSocketServer? = null

    /** Owns the credential cleanup; outlives every connection so a rejected cookie is always purged. */
    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Bound port, or [LocalAsrSocketServer.NO_PORT] while the bridge is not listening. */
    @Volatile
    var port: Int = LocalAsrSocketServer.NO_PORT
        private set

    /**
     * Starts the bridge if it is not running yet and returns the port it listens on.
     *
     * @return the bound port, or [LocalAsrSocketServer.NO_PORT] when no candidate port was free.
     */
    fun start(context: Context): Int = synchronized(lock) {
        server?.let { return port }

        val appContext = context.applicationContext
        val repository = AppGraph.sessionRepository(appContext)
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            // The WebSocket idles between utterances; the engine owns the inactivity policy.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        val invalidator = BridgeCredentialInvalidator(
            repository = repository,
            scope = cleanupScope,
            log = { line -> Log.d(TAG, line) },
        )

        val candidate = LocalAsrSocketServer(
            engineFactory = {
                // The session manager calls this on a background dispatcher, once per utterance.
                val credentials = withContext(Dispatchers.IO) { repository.current() }
                if (credentials == null) null else DoubaoAsrEngine(credentials, httpClient)
            },
            onCredentialsRejected = invalidator::invalidate,
            log = { line -> Log.d(TAG, line) },
        )

        val bound = candidate.start()
        if (bound == LocalAsrSocketServer.NO_PORT) {
            Log.w(TAG, "no free port in ${LocalAsrSocketServer.DEFAULT_PORTS}; bridge disabled")
            return LocalAsrSocketServer.NO_PORT
        }
        server = candidate
        port = bound
        bound
    }

    private const val TAG = "LocalAsrBridge"
}
