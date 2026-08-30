package io.digibyte.core.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

data class EncryptedData(val ciphertext: ByteArray, val iv: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedData) return false
        return ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        return result
    }
}

/**
 * @param alias Keystore alias of the seed-wrapping key. Production always uses [KEY_ALIAS];
 *   instrumented tests pass their own so their create/delete cycles can never touch the key that
 *   wraps a real wallet on the test device (`KeyStoreManagerTest` used to delete `dgb_wallet_master`
 *   in `@Before`, which bricks any wallet already on that device).
 */
class KeyStoreManager(
    private val context: Context? = null,
    private val alias: String = KEY_ALIAS,
) {
    companion object {
        private const val TAG = "KeyStoreManager"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "dgb_wallet_master"
        private const val GCM_TAG_LENGTH = 128
    }

    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    fun createKey() {
        if (keyStore.containsAlias(alias)) return

        // No setUserAuthenticationRequired — the key must be usable at any
        // point during app lifecycle without requiring a recent device unlock.
        // Android Keystore auth binding (10-second window) has caused crashes
        // on API 28 (UserNotAuthenticatedException), API 33 (no lock screen),
        // and API 35 (auth state inconsistency). The app enforces its own
        // PIN lock for access control. The seed is still AES-256-GCM encrypted
        // with a hardware-backed key — just not bound to device unlock timing.
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            .apply { init(spec) }
            .generateKey()
        logHardwareBacking(key)
    }

    // THREAT_MODEL asserts the seed key is hardware-backed; until now nothing checked. A device
    // whose Keystore silently fell back to software gets the same code path, so the only way to
    // know is to ask the key. Diagnostic only: the probe must never block wallet creation.
    private fun logHardwareBacking(key: SecretKey) {
        runCatching {
            val info = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE_PROVIDER)
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                " securityLevel=${info.securityLevel}"
            } else ""
            Log.i(TAG, "master key created: isInsideSecureHardware=${info.isInsideSecureHardware}$level")
        }.onFailure { e ->
            Log.w(TAG, "master key created: hardware-backing probe failed (${e::class.java.simpleName})")
        }
    }

    fun encrypt(data: ByteArray): EncryptedData {
        val key = keyStore.getKey(alias, null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(data)
        return EncryptedData(ciphertext, cipher.iv)
    }

    fun decrypt(encryptedData: EncryptedData): ByteArray {
        val key = keyStore.getKey(alias, null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(encryptedData.ciphertext)
    }

    fun deleteKey() {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    fun isKeyValid(): Boolean {
        return try {
            keyStore.containsAlias(alias) && keyStore.getKey(alias, null) != null
        } catch (e: Exception) {
            false // Key invalidated (e.g., new biometric enrolled)
        }
    }
}
