package com.biketrailplanner.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Where the rider's DeepSeek key and model choice live (D27, docs/AI-KEY.md).
 *
 * This is the first secret this app has ever held: D17 deliberately kept the app key-free. The key
 * is therefore stored ONLY in [EncryptedAiKeyStore] (Android Keystore-backed `EncryptedSharedPreferences`,
 * file `"secure_settings"`, `MasterKey` `AES256_GCM`), never in source, never in `local.properties`,
 * never in `assets/`, and it is never logged and never printed. The UI reads it back through
 * [maskKey], so the screen shows a few characters and nothing more.
 *
 * The interface exists for one concrete reason: this project's unit tests are plain JVM tests, and
 * Android's encrypted preferences cannot run there. [InMemoryAiKeyStore] is what a test uses.
 */
interface AiKeyStore {

    /** The DeepSeek key, or `""` when the rider has not set one. Trimmed on every write. */
    var deepSeekKey: String

    /** The model id. [DEFAULT_MODEL] when the rider has never changed it. */
    var model: String

    /** False when this phone's secure storage could not be opened, so nothing can be stored. */
    val available: Boolean get() = true

    fun hasKey(): Boolean = deepSeekKey.isNotBlank()

    /** What the UI is allowed to show: a few trailing characters, never the whole key. */
    fun maskedKey(): String = maskKey(deepSeekKey)

    /** Forget the key. Called by the Settings "Clear" action. */
    fun clearKey()

    companion object {
        /**
         * The CANONICAL DeepSeek model id, checked against the API on 2026-09-16: `GET /models`
         * lists exactly `deepseek-flash` and `deepseek-v4-pro`. The reference implementation ships
         * `deepseek-v4-flash`, which the API still ACCEPTS as an alias and resolves to
         * `deepseek-flash` (a live call returns `"model": "deepseek-flash"`), but an alias is not a
         * name to build on, so the app uses the canonical one. `deepseek-v4-pro` is the higher tier
         * and is offered by the model field for anyone who wants it.
         */
        const val DEFAULT_MODEL = "deepseek-flash"

        /** How many trailing characters [maskKey] leaves visible. */
        const val MASK_VISIBLE_CHARS = 4

        /**
         * A key reduced to something safe to paint on a screen: the last
         * [MASK_VISIBLE_CHARS] characters after eight dots, or nothing when there is no key. The
         * visible tail is what the reference settings screen shows (SettingsScreen.kt:148-152).
         */
        fun maskKey(key: String): String {
            val trimmed = key.trim()
            if (trimmed.isEmpty()) return ""
            val visible = trimmed.takeLast(MASK_VISIBLE_CHARS)
            return "\u2022".repeat(MASK_VISIBLE_CHARS * 2) + visible
        }
    }
}

/**
 * The real store: Android Keystore-backed encrypted preferences.
 *
 * `EncryptedSharedPreferences` is created eagerly, but a failure to create it (a phone without a
 * usable keystore, a corrupted pref file) is caught rather than thrown: a Settings screen must not
 * crash because the key cannot be stored. When that happens [available] is false, every read answers
 * "no key" and every write is dropped. There is deliberately NO fallback to an unencrypted file.
 */
class EncryptedAiKeyStore(context: Context, fileName: String = FILE) : AiKeyStore {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    override val available: Boolean get() = prefs != null

    override var deepSeekKey: String
        get() = prefs?.getString(KEY_API, null) ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_API, value.trim())?.apply()
        }

    override var model: String
        get() = prefs?.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: AiKeyStore.DEFAULT_MODEL
        set(value) {
            prefs?.edit()?.putString(KEY_MODEL, value.trim())?.apply()
        }

    override fun clearKey() {
        prefs?.edit()?.remove(KEY_API)?.apply()
    }

    companion object {
        /** The file name the reference implementation uses (docs/research/deepseek-android.md section 3). */
        const val FILE = "secure_settings"
        private const val KEY_API = "deepseek_api_key"
        private const val KEY_MODEL = "deepseek_model"
    }
}

/** The store a test (or any caller with no Android crypto) uses. Holds nothing on disk. */
class InMemoryAiKeyStore(
    initialKey: String = "",
    initialModel: String = AiKeyStore.DEFAULT_MODEL,
) : AiKeyStore {

    private var storedKey: String = initialKey.trim()
    private var storedModel: String = initialModel.trim().ifBlank { AiKeyStore.DEFAULT_MODEL }

    override var deepSeekKey: String
        get() = storedKey
        set(value) {
            storedKey = value.trim()
        }

    override var model: String
        get() = storedModel
        set(value) {
            storedModel = value.trim().ifBlank { AiKeyStore.DEFAULT_MODEL }
        }

    override fun clearKey() {
        storedKey = ""
    }
}
