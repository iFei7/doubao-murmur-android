package com.doubao.murmur.tv.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Single place that answers "may we record right now?".
 *
 * `RECORD_AUDIO` is a runtime permission on every supported API level (minSdk 23), so the answer
 * has to be re-evaluated on each start rather than cached: the user can revoke it from the TV
 * settings while the app keeps running.
 */
object MicPermission {

    /** The runtime permission string, exposed so activities can request it without re-importing. */
    fun recordAudioPermission(): String = Manifest.permission.RECORD_AUDIO

    /** True when the app currently holds `RECORD_AUDIO`. Never throws. */
    fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
