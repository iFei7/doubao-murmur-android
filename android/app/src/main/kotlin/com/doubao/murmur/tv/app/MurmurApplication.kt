package com.doubao.murmur.tv.app

import android.app.Application
import android.util.Log
import com.doubao.murmur.tv.bridge.LocalAsrBridge

/**
 * Application entry point whose only job is to bring the localhost ASR bridge up.
 *
 * The bridge must be listening for the whole life of the process: Gboard talks to it over
 * `127.0.0.1` and has no other way to reach doubao's recogniser (no activity start, no provider).
 * If the user never opened the app, the socket is absent by design and Gboard shows "open doubao
 * first" — that is the only failure mode a socket bridge cannot hide.
 *
 * Starting on a background thread keeps the launcher's cold start untouched; binding a loopback
 * socket is fast, but a slow ROM or an occupied port must not block `onCreate`.
 */
class MurmurApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread(
            {
                runCatching { LocalAsrBridge.start(this@MurmurApplication) }
                    .onFailure { Log.w(TAG, "cannot start the asr bridge", it) }
            },
            "doubao-asr-bridge-boot",
        ).apply { isDaemon = true }.start()
    }

    private companion object {
        const val TAG = "MurmurApplication"
    }
}
