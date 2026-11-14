package com.doubao.murmur.tv.app

import android.content.Context
import com.doubao.murmur.tv.auth.EncryptedCredentialStore
import com.doubao.murmur.tv.auth.SessionRepository

/**
 * Hand-rolled dependency container for the single-APK app module.
 *
 * A DI framework would buy nothing here: the graph has one node, the whole app lives in one
 * process, and the IME service pulls the same instance through this object so that credentials
 * are read from one place only.
 *
 * Everything is created lazily and kept for the lifetime of the process; only the application
 * context is ever retained, so a configuration change or a finished activity cannot leak.
 */
object AppGraph {

    @Volatile
    private var repository: SessionRepository? = null

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
}
