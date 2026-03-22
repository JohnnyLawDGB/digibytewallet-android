package io.digibyte.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyStoreManagerTest {
    private lateinit var ksm: KeyStoreManager

    @Before
    fun setup() {
        ksm = KeyStoreManager()
        ksm.deleteKey() // clean slate
    }

    @After
    fun teardown() {
        ksm.deleteKey()
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
