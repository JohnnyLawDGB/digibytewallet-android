package io.digibyte.core.digiscope

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persistence for the Hub session JWT.
 *
 * The token used to sit as plaintext in the `dgb_digiscope` SharedPreferences. It is a bearer
 * credential for the user's Hub identity and, unlike the seed / PIN / DB-key blobs, was not
 * Keystore-wrapped — the one secret in the app that a backup or a root shell could lift and
 * replay. It now lives in the hardware-keyed `dgb_hub_session` EncryptedSharedPreferences,
 * built the same way `PinManager` builds `dgb_pin_store`.
 *
 * An existing plaintext token is migrated exactly once on first [load]: copied into the
 * encrypted store and removed from the plaintext one, so an upgrade does not log the user out
 * of the Hub and does not leave the old copy behind.
 *
 * Nothing here may throw. [load] runs from `DigiScopeClient`'s init, which Hilt executes while
 * injecting `MainActivity`; EncryptedSharedPreferences throws when its Tink keyset or a stored
 * value fails AEAD (a known failure class after OS upgrades and Keystore hiccups), and a throw
 * at that point is a crash before `onCreate` that no wiper clears — a permanent crash loop for
 * a wallet holder over the least valuable secret in the app. A broken store reads as
 * logged-out and is deleted so the next launch starts clean.
 */
class HubTokenStore internal constructor(
    private val legacy: SharedPreferences,
    secureProvider: () -> SharedPreferences,
    private val resetSecure: () -> Unit = {},
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE),
        { encryptedPrefs(context.applicationContext) },
        { context.applicationContext.deleteSharedPreferences(SECURE_PREFS_NAME) },
    )

    private val secure: SharedPreferences by lazy(secureProvider)

    fun load(): String? = guarded("load") {
        val plaintext = legacy.getString(KEY_JWT, null)
        if (plaintext != null) {
            // An encrypted copy already present is the newer session and is never overwritten
            // by the leftover. The plaintext copy is dropped only once the encrypted store
            // actually holds a token — a failed first write must not lose the session from both.
            val migrated = secure.contains(KEY_JWT) || secure.edit().putString(KEY_JWT, plaintext).commit()
            if (migrated) legacy.edit().remove(KEY_JWT).commit()
        }
        secure.getString(KEY_JWT, null)
    }

    fun save(token: String) {
        guarded("save") { secure.edit().putString(KEY_JWT, token).commit() }
    }

    fun clear() {
        guarded("clear") { secure.edit().remove(KEY_JWT).commit() }
        legacy.edit().remove(KEY_JWT).commit()
    }

    /**
     * Runs [block] against the encrypted store; a failure to open it or to read/write a value
     * is logged by exception class only and the store is discarded. The Hub session is
     * re-established by logging in again; wallet access must never depend on it.
     */
    private fun <T> guarded(op: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "$op: encrypted Hub session store unusable (${e.javaClass.simpleName}); discarding it")
        runCatching { resetSecure() }
        null
    }

    companion object {
        private const val TAG = "HubTokenStore"

        /** Pre-migration plaintext store; kept only so the one-time migration can find it. */
        const val LEGACY_PREFS_NAME = "dgb_digiscope"
        const val SECURE_PREFS_NAME = "dgb_hub_session"
        const val KEY_JWT = "dgb_jwt"

        private fun encryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
