package com.doubao.murmur.tv.app.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.doubao.murmur.tv.audio.MicPermission

/**
 * Invisible activity whose only job is to ask for `RECORD_AUDIO`.
 *
 * Why a separate activity exists: `InputMethodService` is a *Service*, and
 * `Activity.requestPermissions()` / `registerForActivityResult()` are only available on an
 * Activity. The IME therefore cannot trigger the system permission dialog itself; it starts this
 * translucent activity with `FLAG_ACTIVITY_NEW_TASK`, the dialog appears on top of whatever is on
 * screen, and the answer is published back through three channels:
 *
 * 1. `setResult(RESULT_OK / RESULT_CANCELED)` for callers that launch it with a result contract;
 * 2. [EXTRA_PERMANENTLY_DENIED] so the caller can route the user to the app settings page instead
 *    of re-prompting (a permanent denial never shows a dialog again);
 * 3. [PREFS_NAME] flags, because the IME has no activity result plumbing and TV back stacks are
 *    unreliable: it simply re-reads `MicPermission.hasRecordPermission()` when it resumes.
 *
 * The window is translucent (`Theme.MurmurTv.Translucent`) and it finishes immediately, so the
 * user never sees a black flash between pressing the mic key and the permission dialog.
 */
class MicPermissionActivity : ComponentActivity() {

    private var resultPublished = false

    /**
     * Registered as a field initializer, i.e. before `onCreate`. Registering after `onStart` would
     * throw `IllegalStateException: LifecycleOwner is attempting to register while current state is
     * RESUMED`, which is why this must not move into `onCreate`'s body.
     */
    private val requestRecordAudio =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            publishResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MicPermission.hasRecordPermission(this)) {
            publishResult(granted = true)
            return
        }
        requestRecordAudio.launch(MicPermission.recordAudioPermission())
    }

    private fun publishResult(granted: Boolean) {
        if (resultPublished) {
            // The contract callback and a re-created activity can both land here.
            return
        }
        resultPublished = true

        val permanentlyDenied =
            !granted && !shouldShowRequestPermissionRationale(MicPermission.recordAudioPermission())

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MIC_GRANTED, granted)
            .putBoolean(KEY_MIC_PERMANENTLY_DENIED, permanentlyDenied)
            .apply()

        setResult(
            if (granted) RESULT_OK else RESULT_CANCELED,
            Intent().putExtra(EXTRA_PERMANENTLY_DENIED, permanentlyDenied),
        )
        finish()
    }

    companion object {

        /** Extra read by the launcher: the user refused and the system will not ask again. */
        const val EXTRA_PERMANENTLY_DENIED: String = "permanently_denied"

        /** Shared with the IME side, which cannot receive an activity result. */
        const val PREFS_NAME: String = "murmur_permission_state"

        /** Last known answer, usable by the IME without a permission check. */
        const val KEY_MIC_GRANTED: String = "mic_granted"

        /** True when only the system settings page can still grant the permission. */
        const val KEY_MIC_PERMANENTLY_DENIED: String = "mic_permanently_denied"

        /**
         * Builds the intent to start from an activity *or* from the IME service.
         *
         * `FLAG_ACTIVITY_NEW_TASK` is added only for non-activity contexts: passing it from an
         * activity would push the dialog into a new task and the caller would never see the result.
         */
        fun intent(context: Context): Intent {
            val intent = Intent(context, MicPermissionActivity::class.java)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return intent
        }
    }
}
