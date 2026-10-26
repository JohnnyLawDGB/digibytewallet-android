package app.aroundtheblock.wallet.core

import android.content.Context
import android.content.SharedPreferences
import app.aroundtheblock.wallet.core.security.EncryptedData
import app.aroundtheblock.wallet.core.security.KeyStoreManager
import app.aroundtheblock.wallet.core.security.KeyVault
import app.aroundtheblock.wallet.core.security.KeyVaultKeyAbsentException
import io.mockk.every
import io.mockk.mockk
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A Keystore in memory: real AES-256-GCM with software keys, so a record wrapped under a key that
 * was deleted really cannot be read back, and a record read with the wrong key really fails its tag.
 *
 * Every call is logged in [events] as `op:alias`. [failure] can fail any call (`data` is the
 * plaintext for encrypt, the ciphertext for decrypt); [tamper] can change what a decrypt returns.
 */
internal class FakeVault(val events: MutableList<String> = mutableListOf()) : KeyVault {
    val keys = LinkedHashMap<String, SecretKey>()
    var failure: (op: String, alias: String, data: ByteArray?) -> Throwable? = { _, _, _ -> null }
    var tamper: (alias: String, plain: ByteArray) -> ByteArray = { _, plain -> plain }

    private fun call(op: String, alias: String, data: ByteArray? = null) {
        events += "$op:$alias"
        failure(op, alias, data)?.let { throw it }
    }

    override fun contains(alias: String): Boolean {
        call("contains", alias)
        return alias in keys
    }

    override fun createUnboundKey(alias: String) {
        call("create", alias)
        if (alias !in keys) keys[alias] = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    override fun encrypt(alias: String, data: ByteArray): EncryptedData {
        call("encrypt", alias, data)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys[alias] ?: throw KeyVaultKeyAbsentException(alias))
        return EncryptedData(cipher.doFinal(data), cipher.iv)
    }

    override fun decrypt(alias: String, data: EncryptedData): ByteArray {
        call("decrypt", alias, data.ciphertext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keys[alias] ?: throw KeyVaultKeyAbsentException(alias), GCMParameterSpec(128, data.iv))
        return tamper(alias, cipher.doFinal(data.ciphertext))
    }

    override fun delete(alias: String) {
        call("delete", alias)
        keys.remove(alias)
    }
}

/** A Keystore failure that says nothing about any key: the Keystore could not be asked just now. */
internal class KeystoreHiccup : java.security.ProviderException("keystore busy")

internal const val LEGACY_ALIAS = KeyStoreManager.KEY_ALIAS
internal const val BOUND_ALIAS = KeyStoreManager.KEY_ALIAS + KeyStoreManager.AUTH_ALIAS_SUFFIX
internal const val DB_ALIAS = "dgb_db_passphrase"

/**
 * A [KeyStoreManager] whose keys live in [vault]: the legacy seed key under [LEGACY_ALIAS], the
 * auth-bound one under [BOUND_ALIAS]. [authBound] false is a device where binding is unavailable.
 */
internal fun keyStoreManagerOver(vault: FakeVault, authBound: Boolean = true): KeyStoreManager {
    val ksm = mockk<KeyStoreManager>()
    every { ksm.createKey() } answers { vault.createUnboundKey(LEGACY_ALIAS) }
    every { ksm.encrypt(any()) } answers { vault.encrypt(LEGACY_ALIAS, firstArg()) }
    every { ksm.decrypt(any()) } answers { vault.decrypt(LEGACY_ALIAS, firstArg()) }
    every { ksm.createAuthBoundKey() } answers { if (authBound) { vault.createUnboundKey(BOUND_ALIAS); true } else false }
    every { ksm.hasAuthBoundKey() } answers { BOUND_ALIAS in vault.keys }
    every { ksm.encryptAuthBound(any()) } answers { vault.encrypt(BOUND_ALIAS, firstArg()) }
    every { ksm.decryptAuthBound(any()) } answers { vault.decrypt(BOUND_ALIAS, firstArg()) }
    every { ksm.deleteLegacyKey() } answers { vault.delete(LEGACY_ALIAS) }
    every { ksm.deleteKey() } answers { vault.delete(LEGACY_ALIAS); vault.delete(BOUND_ALIAS) }
    every { ksm.isDeviceSecure() } returns authBound
    every { ksm.isKeyValid() } answers { LEGACY_ALIAS in vault.keys }
    return ksm
}

/**
 * [InMemoryPrefs] that records every write: whether it was a checked `commit()` or an unchecked
 * `apply()`, which keys it touched, and whether it landed.
 */
internal class RecordingPrefs(
    val inner: InMemoryPrefs = InMemoryPrefs(),
    /** Shared with other fakes to order events: each write appends `write:<keys>:<landed>`. */
    val log: MutableList<String> = mutableListOf(),
) : SharedPreferences by inner {
    class Write(val checked: Boolean, val keys: Set<String>, val landed: Boolean)

    val writes = mutableListOf<Write>()

    override fun edit(): SharedPreferences.Editor {
        val editor = inner.edit()
        val touched = LinkedHashSet<String>()
        return object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { touched += key; editor.putString(key, value) }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { touched += key; editor.putStringSet(key, values) }
            override fun putInt(key: String, value: Int) = apply { touched += key; editor.putInt(key, value) }
            override fun putLong(key: String, value: Long) = apply { touched += key; editor.putLong(key, value) }
            override fun putFloat(key: String, value: Float) = apply { touched += key; editor.putFloat(key, value) }
            override fun putBoolean(key: String, value: Boolean) = apply { touched += key; editor.putBoolean(key, value) }
            override fun remove(key: String) = apply { touched += key; editor.remove(key) }
            override fun clear() = apply { touched += "*clear*"; editor.clear() }
            override fun commit(): Boolean = editor.commit().also {
                writes += Write(checked = true, keys = touched.toSet(), landed = it)
                log += "write:${touched.sorted()}:$it"
            }
            override fun apply() {
                // InMemoryPrefs.apply() is its own commit(); the platform's reports nothing.
                editor.apply()
                writes += Write(checked = false, keys = touched.toSet(), landed = !inner.refuseWrites)
                log += "write:${touched.sorted()}:${!inner.refuseWrites}"
            }
        }
    }
}

/** A new view of what [prefs] holds on disk — what the next process would read. */
internal fun reopened(prefs: InMemoryPrefs): InMemoryPrefs =
    InMemoryPrefs().also { fresh -> prefs.durable.forEach { (k, v) -> fresh.seed(k, v) } }

/** A context over [prefs] (with [seedStore] standing in for `dgb_wallet_seed` when given) and [root]. */
internal fun keyTestContext(prefs: InMemoryPrefsFactory, root: File, seedStore: SharedPreferences? = null): Context {
    val ctx = wipeTestContext(prefs, root)
    if (seedStore != null) every { ctx.getSharedPreferences("dgb_wallet_seed", any()) } returns seedStore
    return ctx
}

internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
