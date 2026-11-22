package app.aroundtheblock.wallet.core.digiscope

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.aroundtheblock.wallet.core.wallets.ActiveWallet
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.wallets.WalletStores
import app.aroundtheblock.wallet.core.wallets.prefsFileOnDisk

/**
 * Persistence for the Hub session JWT.
 *
 * The token used to sit as plaintext in the `dgb_digiscope` SharedPreferences. It is a bearer
 * credential for the user's Hub identity and, unlike the seed / PIN / DB-key blobs, was not
 * Keystore-wrapped — the one secret in the app that a backup or a root shell could lift and
 * replay. It now lives in the hardware-keyed `dgb_hub_session` EncryptedSharedPreferences,
 * built the way `PinManager` built `dgb_pin_store` before PIN-STORE moved the PIN to a sealed file.
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
 *
 * Opening the encrypted store writes its file (the keyset), so it is opened only when it can hold
 * something or is about to: a store that was never written is not created by a [load], a [stored],
 * a [clear] or a [clearConfirmed], only by a [save] or by the one-time move of a plaintext token.
 * A wipe asks all four, and a wipe creates no file (WIPE-WRITES-NOTHING).
 */
class HubTokenStore internal constructor(
    private val legacy: SharedPreferences,
    secureProvider: () -> SharedPreferences,
    private val resetSecure: () -> Unit = {},
    /** Whether the encrypted store's file is on the device. What cannot be looked for counts as there. */
    private val secureOnDisk: () -> Boolean = { true },
) {
    /** One wallet's Hub session: its own plain and encrypted stores, never another wallet's. */
    constructor(context: Context, scope: WalletScope) : this(
        context,
        scope,
        { name -> encryptedPrefs(context.applicationContext, name) },
    )

    internal constructor(
        context: Context,
        scope: WalletScope,
        secureFactory: (String) -> SharedPreferences,
    ) : this(
        context.applicationContext.getSharedPreferences(
            WalletStores.prefsName(LEGACY_PREFS_NAME, scope), Context.MODE_PRIVATE,
        ),
        { secureFactory(WalletStores.prefsName(SECURE_PREFS_NAME, scope)) },
        { context.applicationContext.deleteSharedPreferences(WalletStores.prefsName(SECURE_PREFS_NAME, scope)) },
        { prefsFileOnDisk(context.applicationContext, WalletStores.prefsName(SECURE_PREFS_NAME, scope)) },
    )

    constructor(context: Context) : this(context, ActiveWallet.scope(context))

    private val secureOpened = lazy(secureProvider)
    private val secure: SharedPreferences by secureOpened

    /**
     * The encrypted store holds nothing and need not be asked: this object has not opened it and its
     * file is not on the device. Asking it would open it, and opening it writes its file.
     */
    private fun secureNeverWritten(): Boolean =
        !secureOpened.isInitialized() && !runCatching(secureOnDisk).getOrDefault(true)

    fun load(): String? = guarded("load") {
        val plaintext = legacy.getString(KEY_JWT, null)
        if (plaintext == null && secureNeverWritten()) return@guarded null
        if (plaintext != null) {
            // An encrypted copy already present is the newer session and is never overwritten
            // by the leftover. The plaintext copy is dropped only once the encrypted store
            // actually holds a token — a failed first write must not lose the session from both.
            val migrated = secure.contains(KEY_JWT) || secure.edit().putString(KEY_JWT, plaintext).commit()
            if (migrated) legacy.edit().remove(KEY_JWT).commit()
        }
        secure.getString(KEY_JWT, null)
    }

    /**
     * The token this wallet's stores hold, read where it is: nothing is moved, written or reset.
     * For the wipe's revoke, which only needs the token and must not make a file on its way
     * ([load] moves a plaintext token into the encrypted store, which creates that store's file).
     * The encrypted copy is the newer session, as in [load]. Never throws.
     */
    fun stored(): String? {
        val sealed = try {
            if (secureNeverWritten()) null else secure.getString(KEY_JWT, null)
        } catch (e: Exception) {
            Log.w(TAG, "stored: encrypted Hub session store unusable (${e.javaClass.simpleName})")
            null
        }
        return sealed ?: runCatching { legacy.getString(KEY_JWT, null) }.getOrNull()
    }

    fun save(token: String) {
        guarded("save") { secure.edit().putString(KEY_JWT, token).commit() }
    }

    /**
     * A store without the token is not written: a commit to a store whose file is gone (a wipe
     * deleted it) writes an empty file back under that wallet's name.
     */
    fun clear() {
        guarded("clear") { if (!secureNeverWritten() && secure.contains(KEY_JWT)) secure.edit().remove(KEY_JWT).commit() }
        if (legacy.contains(KEY_JWT)) legacy.edit().remove(KEY_JWT).commit()
    }

    /**
     * [clear] for a caller that has to know: true only when the token is gone for certain. Both
     * stores are always attempted.
     *
     * An encrypted store that cannot be used is discarded as everywhere else in this class — the
     * whole `dgb_hub_session` file is removed — so that case is the token at its most thoroughly
     * gone and it counts as cleared. Reporting it as "not confirmed" would be a whole class of
     * device, the one this class exists to keep working, on which a wipe could never say it
     * finished. "Not confirmed" is kept for what it describes: a store that answered and whose
     * removal did not land.
     */
    fun clearConfirmed(): Boolean {
        // As [clear]: a store without the token is not written, and one never written is not opened.
        val secureCleared = guarded("clear") {
            secureNeverWritten() || ((!secure.contains(KEY_JWT) || secure.edit().remove(KEY_JWT).commit()) && !secure.contains(KEY_JWT))
        } ?: runCatching { !secure.contains(KEY_JWT) }.getOrDefault(true)
        val legacyCleared = runCatching {
            (!legacy.contains(KEY_JWT) || legacy.edit().remove(KEY_JWT).commit()) && !legacy.contains(KEY_JWT)
        }.getOrDefault(false)
        return secureCleared && legacyCleared
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

        private fun encryptedPrefs(context: Context, fileName: String): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
