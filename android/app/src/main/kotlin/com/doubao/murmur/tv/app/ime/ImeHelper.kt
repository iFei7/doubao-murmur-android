package com.doubao.murmur.tv.app.ime

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.doubao.murmur.tv.ime.settings.ImeSettingsActivity

/**
 * Everything the launcher needs to know about our own input method.
 *
 * The component name is the contract with `:ime`: the service FQCN is frozen in the merged
 * manifest, so this object is the only place that spells it out.
 */
object ImeHelper {

    /** Release applicationId. The manifest in `:ime` declares the service under this prefix. */
    const val IME_PACKAGE: String = "com.doubao.murmur.tv"

    /** Fixed FQCN of the voice IME, see `plans/omo-android-tv-ime-2026-11-06.md` section 4. */
    const val IME_CLASS: String = "$IME_PACKAGE.ime.DoubaoVoiceInputMethodService"

    /** `package/class` form used by `Settings.Secure` and by `adb shell ime`. */
    const val IME_ID: String = "$IME_PACKAGE/$IME_CLASS"

    /**
     * Component id for the *currently installed* build.
     *
     * `debug` builds get an `applicationIdSuffix` of `.debug`, so the service is registered under
     * `com.doubao.murmur.tv.debug` there. Hardcoding [IME_ID] when talking to the system would make
     * the check silently fail on debug builds, which is exactly the build QA installs.
     */
    fun imeId(context: Context): String = "${context.packageName}/$IME_CLASS"

    /**
     * True when the user has ticked our keyboard in Settings → Keyboards.
     *
     * A freshly installed IME is present in [InputMethodManager.getInputMethodList] but absent from
     * `ENABLED_INPUT_METHODS` until the user enables it, and it cannot be selected before that.
     */
    fun isImeEnabled(context: Context): Boolean {
        val enabled = readSecure(context, Settings.Secure.ENABLED_INPUT_METHODS) ?: return false
        val self = imeId(context)
        return enabled.split(':').any { it.trim() == self }
    }

    /** True when our IME is the input method that will actually pop up. */
    fun isImeSelected(context: Context): Boolean =
        readSecure(context, Settings.Secure.DEFAULT_INPUT_METHOD)?.trim() == imeId(context)

    /**
     * Opens our own input-method settings screen, which lives in `:ime` in the same APK.
     *
     * Preferred over the system screens: it explains both setup steps, exposes the "switch back
     * to the system keyboard" escape hatch and offers sign-out. A good share of ROMs either lack
     * `ACTION_INPUT_METHOD_SETTINGS` entirely or bury the keyboard list somewhere a user will
     * never find, so this page is the reliable entry point and the system intents are the
     * fallback, not the other way round.
     */
    fun openOwnImeSettings(context: Context): Boolean =
        startSafely(context, Intent(context, ImeSettingsActivity::class.java))

    /**
     * Opens the system keyboard settings screen.
     *
     * `ACTION_INPUT_METHOD_SETTINGS` is missing on a fair number of ROMs (it is an optional
     * AOSP screen), so falling back to the root settings entry keeps the user unblocked. The
     * returned flag lets the caller show an error hint instead of pretending something opened.
     */
    fun openImeSettings(context: Context): Boolean =
        startSafely(context, Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) ||
            startSafely(context, Intent(Settings.ACTION_SETTINGS))

    /**
     * Shows the system IME picker, which is the only public, token-free way for a regular app to
     * change the active keyboard.
     */
    fun showImePicker(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // API 23 TV boxes are rare and their picker dialog is not reliably hosted; the settings
            // screen is the safer way to give the viewer something they can act on.
            return openImeSettings(context)
        }
        val manager = inputMethodManager(context) ?: return false
        return try {
            manager.showInputMethodPicker()
            true
        } catch (e: RuntimeException) {
            // Some vendor ROMs throw when the picker dialog cannot be hosted.
            false
        }
    }

    /**
     * Ids of every installed IME that is not ours, best candidate first.
     *
     * The IME side needs this to implement "switch back to the system keyboard": only the IME
     * itself holds the token required by `InputMethodService.switchInputMethod`, so this list is
     * the shared piece of the logic and the switching stays on the `:ime` side.
     */
    fun systemImeCandidates(context: Context): List<String> {
        val manager = inputMethodManager(context) ?: return emptyList()
        val self = imeId(context)
        return manager.inputMethodList.mapNotNull { it.id }.filter { it != self }
    }

    /**
     * Best effort switch away from our IME from launcher code.
     *
     * Without an IME token the framework offers no silent switch, so this surfaces the system
     * picker over [systemImeCandidates]. Returns false when no alternative keyboard exists at all
     * (a freshly reset TV may only have ours enabled), in which case the caller should keep the
     * user in the current IME instead of showing an empty picker.
     */
    fun switchToSystemIme(context: Context): Boolean =
        systemImeCandidates(context).isNotEmpty() && showImePicker(context)

    private fun readSecure(context: Context, key: String): String? = try {
        Settings.Secure.getString(context.contentResolver, key)
    } catch (e: RuntimeException) {
        // A few locked-down ROMs deny reading secure settings to third party apps.
        null
    }

    private fun inputMethodManager(context: Context): InputMethodManager? =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun startSafely(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) {
            // Called from the IME service, which has no task of its own.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
