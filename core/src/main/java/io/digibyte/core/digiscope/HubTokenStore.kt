package io.digibyte.core.digiscope

import android.content.Context
import android.content.SharedPreferences
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
 */
class HubTokenStore internal constructor(
    private val legacy: SharedPreferences,
    private val secure: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE),
        encryptedPrefs(context.applicationContext),
    )

    fun load(): String? {
        val plaintext = legacy.getString(KEY_JWT, null)
        if (plaintext != null) {
            // An encrypted copy already present is the newer session; the plaintext leftover
            // must be removed either way, never promoted over it.
            if (!secure.contains(KEY_JWT)) secure.edit().putString(KEY_JWT, plaintext).commit()
            legacy.edit().remove(KEY_JWT).commit()
        }
        return secure.getString(KEY_JWT, null)
    }

    fun save(token: String) {
        secure.edit().putString(KEY_JWT, token).commit()
    }

    fun clear() {
        secure.edit().remove(KEY_JWT).commit()
        legacy.edit().remove(KEY_JWT).commit()
    }

    companion object {
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
