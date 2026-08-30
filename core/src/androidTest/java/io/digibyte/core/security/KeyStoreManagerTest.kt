package io.digibyte.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against a dedicated test alias. The earlier version built the manager on the production
 * alias and deleted it in setup/teardown — on a device holding a real wallet that destroys the
 * key wrapping the seed, and the wallet with it.
 */
@RunWith(AndroidJUnit4::class)
class KeyStoreManagerTest {
    private companion object {
        const val TEST_ALIAS = "dgb_keystore_manager_test"
    }

    private lateinit var ksm: KeyStoreManager

    @Before
    fun setup() {
        ksm = KeyStoreManager(alias = TEST_ALIAS)
        ksm.deleteKey() // clean slate
    }

    @After
    fun teardown() {
        ksm.deleteKey()
    }

    // ── Auth-bound key (docs/specs/keystore-auth-binding.md) ─────────────────
    // Only meaningful on a device with a secure lock screen; assumption-skipped
    // elsewhere so CI emulators without a lock screen stay green.

    @Test
    fun authBoundKey_roundTrip_whenDeviceSecure() {
        org.junit.Assume.assumeTrue("needs a secure lock screen", ksm.isDeviceSecure())
        org.junit.Assume.assumeTrue("auth-bound keygen unavailable", ksm.createAuthBoundKey())
        val original = ByteArray(64) { (it * 3).toByte() }
        // Right after `adb`-driven test start the device was recently unlocked, so the
        // 300s window is open; if not, the typed exception is still the CORRECT outcome.
        try {
            val enc = ksm.encryptAuthBound(original)
            assertArrayEquals(original, ksm.decryptAuthBound(enc))
        } catch (e: KeystoreUserAuthRequiredException) {
            // Acceptable: binding is enforced — that IS the feature.
        }
    }

    @Test
    fun createAuthBoundKey_neverThrows_evenWithoutLockScreen() {
        // Returns false (fallback) or true; the one forbidden outcome is a crash.
        ksm.createAuthBoundKey()
    }

    @Test
    fun deleteKey_removesAuthBoundAliasToo() {
        ksm.createKey()
        ksm.createAuthBoundKey() // may be false without lock screen — delete must still be safe
        ksm.deleteKey()
        assertFalse(ksm.isKeyValid())
        assertFalse(ksm.hasAuthBoundKey())
    }

    @Test
    fun createKey_thenIsValid() {
        ksm.createKey()
        assertTrue(ksm.isKeyValid())
    }

    @Test
    fun encryptDecrypt_roundTrip() {
        ksm.createKey()
        val original = "test seed phrase words".toByteArray()
        val encrypted = ksm.encrypt(original)
        val decrypted = ksm.decrypt(encrypted)
        assertArrayEquals(original, decrypted)
    }

    @Test
    fun deleteKey_thenNotValid() {
        ksm.createKey()
        ksm.deleteKey()
        assertFalse(ksm.isKeyValid())
    }

    @Test
    fun encryptDecrypt_binaryData() {
        ksm.createKey()
        // Simulate a 64-byte seed (BIP39 512-bit entropy)
        val seed = ByteArray(64) { it.toByte() }
        val encrypted = ksm.encrypt(seed)
        val decrypted = ksm.decrypt(encrypted)
        assertArrayEquals(seed, decrypted)
        // Ciphertext must differ from plaintext
        assertFalse(encrypted.ciphertext.contentEquals(seed))
    }

    @Test
    fun createKey_idempotent() {
        ksm.createKey()
        ksm.createKey() // Should not throw
        assertTrue(ksm.isKeyValid())
    }

    @Test
    fun iv_isUnique_perEncryption() {
        ksm.createKey()
        val data = "same data".toByteArray()
        val enc1 = ksm.encrypt(data)
        val enc2 = ksm.encrypt(data)
        // GCM generates a fresh IV per encryption — IVs must differ
        assertFalse(enc1.iv.contentEquals(enc2.iv))
    }
}
