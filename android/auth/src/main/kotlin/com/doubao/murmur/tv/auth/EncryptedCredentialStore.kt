package com.doubao.murmur.tv.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.doubao.murmur.tv.asr.AsrCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [CredentialStore] backed by `EncryptedSharedPreferences` with an AES-256-GCM Keystore master key.
 *
 * ## Security contract
 * The cookies are a session credential: whoever holds `sessionid` can talk to the ASR endpoint as
 * the user. Therefore:
 * - values are only ever written encrypted, keyed by the hardware-backed Keystore master key;
 * - the preference file [PREF_FILE_NAME] is excluded from cloud backup and device transfer in
 *   `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` - **the file name must
 *   stay byte-identical to the `path` attribute there**, otherwise the exclusion silently stops
 *   matching and a restorable copy of the session cookie leaves the device;
 * - nothing here logs anything. If diagnostics are ever needed, log cookie *names*, never values.
 *
 * ## Failure handling
 * Decryption fails legitimately: the Keystore key is wiped by a factory reset, by a lock-screen
 * change on some ROMs, or when the app data is restored onto another device (the ciphertext travels
 * but the key does not). In every such case the stored blob is unusable, so it is deleted and
 * `load()` reports "not logged in" instead of throwing - a crash loop on startup would be worse
 * than asking the user to log in again.
 *
 * Preferences are opened per operation rather than cached: after [clear] the on-disk file is
 * rewritten behind the scenes, and a cached `SharedPreferences` instance would keep serving the
 * previous in-memory values.
 */
class EncryptedCredentialStore(private val context: Context) : CredentialStore {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    override suspend fun load(): AsrCredentials? = withContext(Dispatchers.IO) {
        try {
            val payload = openPreferences().getString(KEY_PAYLOAD, null)
                ?: return@withContext null
            decode(payload)
        } catch (e: Exception) {
            // Unreadable or undecryptable: the blob can never become valid again.
            wipe()
            null
        }
    }

    override suspend fun save(credentials: AsrCredentials) = withContext(Dispatchers.IO) {
        val cookies = JSONObject()
        for ((name, value) in credentials.cookies) {
            cookies.put(name, value)
        }
        val payload = JSONObject()
            .put(FIELD_COOKIES, cookies)
            .put(FIELD_DEVICE_ID, credentials.deviceId)
            .put(FIELD_WEB_ID, credentials.webId)

        val stored = openPreferences().edit()
            .putString(KEY_PAYLOAD, payload.toString())
            .commit()
        if (!stored) {
            throw IllegalStateException("failed to persist the credential blob")
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        wipe()
    }

    override suspend fun isLoggedIn(): Boolean = load() != null

    private fun openPreferences(): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREF_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun decode(payload: String): AsrCredentials? {
        val root = JSONObject(payload)
        val cookiesJson = root.optJSONObject(FIELD_COOKIES) ?: return null
        val cookies = LinkedHashMap<String, String>(cookiesJson.length())
        val names = cookiesJson.keys()
        while (names.hasNext()) {
            val name = names.next()
            cookies[name] = cookiesJson.getString(name)
        }
        val deviceId = root.optString(FIELD_DEVICE_ID, "")
        val webId = root.optString(FIELD_WEB_ID, "")
        if (cookies.isEmpty() || deviceId.isEmpty() || webId.isEmpty()) return null
        return AsrCredentials(cookies = cookies, deviceId = deviceId, webId = webId)
    }

    /**
     * Deletes the stored blob through a plain (non-encrypted) handle on the same file.
     *
     * Deliberately bypasses the encrypted wrapper: the reason we are wiping is usually that the
     * Keystore key is gone, and the encrypted wrapper would fail on the very same key.
     */
    private fun wipe() {
        try {
            context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        } catch (e: RuntimeException) {
            // Nothing else can be done here; the next load() retries the wipe.
        }
    }

    companion object {
        /**
         * SharedPreferences file name, without the `.xml` suffix.
         * Must match `path="murmur_secure_credentials.xml"` in the backup exclusion rules.
         */
        const val PREF_FILE_NAME: String = "murmur_secure_credentials"

        private const val KEY_PAYLOAD = "credentials"
        private const val FIELD_COOKIES = "cookies"
        private const val FIELD_DEVICE_ID = "deviceId"
        private const val FIELD_WEB_ID = "webId"
    }
}
