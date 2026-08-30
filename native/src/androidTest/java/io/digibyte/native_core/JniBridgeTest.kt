package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Instrumented tests for the clean JNI bridge (Task 3).
 * Verifies mnemonic generation, address validation, and basic
 * JNI function dispatch on Samsung Galaxy Note 8 (Android 9, API 28).
 */
@RunWith(AndroidJUnit4::class)
class JniBridgeTest {

    @Test
    fun generateMnemonic_12words() {
        val mnemonic = NativeBridge.generateMnemonic(128)
        assertNotNull("Mnemonic should not be null", mnemonic)
        assertEquals("Should be 12 words", 12, mnemonic!!.split(" ").size)
    }

    @Test
    fun generateMnemonic_24words() {
        val mnemonic = NativeBridge.generateMnemonic(256)
        assertNotNull(mnemonic)
        assertEquals("Should be 24 words", 24, mnemonic!!.split(" ").size)
    }

    @Test
    fun generateMnemonic_invalidEntropy_returnsNull() {
        val mnemonic = NativeBridge.generateMnemonic(64) // too small
        assertNull("Invalid entropy should return null", mnemonic)
    }

    @Test
    fun generateMnemonic_uniqueEachCall() {
        val m1 = NativeBridge.generateMnemonic(128)
        val m2 = NativeBridge.generateMnemonic(128)
        assertNotNull(m1)
        assertNotNull(m2)
        assertNotEquals("Two consecutive mnemonics should differ", m1, m2)
    }

    @Test
    fun isValidAddress_validFromWallet() {
        // Generate a mnemonic and create a wallet to get a real valid address
        val mnemonic = NativeBridge.generateMnemonic(128)
        assertNotNull(mnemonic)
        val created = NativeBridge.createWalletFromBytes(mnemonic!!.toByteArray(), null)
        assertTrue("Wallet should be created", created)

        val addr = NativeBridge.getReceiveAddress(0, 0)
        assertNotNull("Receive address should not be null", addr)
        assertTrue("Address from wallet should be valid", NativeBridge.isValidAddress(addr!!))

        // Verify address starts with D (DigiByte legacy prefix)
        assertTrue("Legacy address should start with D", addr.startsWith("D"))
    }

    @Test
    fun isValidAddress_invalidAddress() {
        assertFalse(NativeBridge.isValidAddress("invalid_address"))
        assertFalse(NativeBridge.isValidAddress(""))
    }

    @Test
    fun getBalance_withoutWallet_returnsZero() {
        assertEquals(0L, NativeBridge.getBalance())
    }

    @Test
    fun getEstimatedFee_returnsPositive() {
        val highFee = NativeBridge.getEstimatedFee(0)
        val lowFee = NativeBridge.getEstimatedFee(2)
        assertTrue("High fee should be positive", highFee > 0)
        assertTrue("Low fee should be positive", lowFee > 0)
        assertTrue("High fee should exceed low fee", highFee > lowFee)
    }

    @Test
    fun getPeerCount_withoutSync_returnsZero() {
        assertEquals(0, NativeBridge.getPeerCount())
    }

    // getSyncProgress was retired in the lock-free status-reads refactor
    // (bridge mirrors + Kotlin-side StateFlow/compute); progress is no longer a
    // native pull. isStatusStale is the mirror-freshness channel; before any
    // refresh it reads stale (never-refreshed → last == 0).
    @Test
    fun isStatusStale_withoutSync_returnsTrue() {
        assertEquals(true, NativeBridge.isStatusStale())
    }
}
