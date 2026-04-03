package io.digibyte.core.security

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
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

class KeyStoreManager(private val context: Context? = null) {
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "dgb_wallet_master"
        private const val GCM_TAG_LENGTH = 128
    }

    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    /** Returns true if the device has a secure lock screen (PIN/pattern/password/biometric). */
    private fun hasSecureLockScreen(): Boolean {
        val ctx = context ?: return false
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isDeviceSecure == true
    }

    fun createKey() {
        if (keyStore.containsAlias(KEY_ALIAS)) return

        val deviceSecure = hasSecureLockScreen()

        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        if (deviceSecure) {
            builder.setInvalidatedByBiometricEnrollment(true)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(10)
        }
        // If no secure lock screen, create key without user authentication.
        // Less secure, but the app remains functional. The seed is still
        // AES-256-GCM encrypted — just not bound to device unlock.

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            .apply { init(builder.build()) }
            .generateKey()
    }

    fun encrypt(data: ByteArray): EncryptedData {
        val key = keyStore.getKey(KEY_ALIAS, null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(data)
        return EncryptedData(ciphertext, cipher.iv)
    }

    fun decrypt(encryptedData: EncryptedData): ByteArray {
        val key = keyStore.getKey(KEY_ALIAS, null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(encryptedData.ciphertext)
    }

    fun deleteKey() {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    fun isKeyValid(): Boolean {
        return try {
            keyStore.containsAlias(KEY_ALIAS) && keyStore.getKey(KEY_ALIAS, null) != null
        } catch (e: Exception) {
            false // Key invalidated (e.g., new biometric enrolled)
        }
    }
}
