package io.digibyte.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.signal.argon2.Argon2
import org.signal.argon2.Type
import org.signal.argon2.Version
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinManager(context: Context) {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "dgb_pin_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun setPin(pin: String) {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val (hashHex, method) = tryArgon2Hash(pin, salt) ?: run {
            Pair(hashWithPbkdf2(pin, salt).toHex(), "pbkdf2")
        }
        prefs.edit()
            .putString("pin_hash", hashHex)
            .putString("pin_salt", salt.toHex())
            .putString("pin_method", method)
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val storedHash = prefs.getString("pin_hash", null) ?: return false
        val salt = prefs.getString("pin_salt", null)?.hexToBytes() ?: return false
        val method = prefs.getString("pin_method", "pbkdf2") ?: "pbkdf2"

        return when (method) {
            "argon2id" -> {
                val result = tryArgon2Hash(pin, salt) ?: return false
                constantTimeEquals(result.first.hexToBytes(), storedHash.hexToBytes())
            }
            else -> {
                val computedHash = hashWithPbkdf2(pin, salt)
                constantTimeEquals(computedHash, storedHash.hexToBytes())
            }
        }
    }

    fun hasPin(): Boolean = prefs.contains("pin_hash")

    fun clearPin() {
        prefs.edit().clear().apply()
    }

    /**
     * Attempts to hash with Argon2id (Signal library). Returns (hashHex, "argon2id") on success,
     * null if the native library fails (e.g., unsupported ABI).
     *
     * Parameters per OWASP recommendations for interactive login (t=3, m=64MiB, p=4):
     * - iterations: 3
     * - memory: 65536 KiB (64 MiB)
     * - parallelism: 4
     * - hashLength: 32 bytes
     */
    private fun tryArgon2Hash(pin: String, salt: ByteArray): Pair<String, String>? {
        return try {
            val argon2 = Argon2.Builder(Version.V13)
                .type(Type.Argon2id)
                .iterations(3)
                .memoryCostKiB(65536)
                .parallelism(4)
                .hashLength(32)
                .build()
            val result = argon2.hash(pin.toByteArray(Charsets.UTF_8), salt)
            Pair(result.hash.toHex(), "argon2id")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * PBKDF2-HMAC-SHA256 fallback with 600,000 iterations (OWASP 2023 recommendation).
     */
    private fun hashWithPbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 600_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
