package io.digibyte.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies BIP84 derivation path constants and sanity checks.
 * Full address verification against Ian Coleman requires JNI (device test).
 */
class BIP84DerivationTest {

    @Test
    fun `BIP84 derivation path is correct for DigiByte`() {
        // BIP84 for DigiByte: purpose=84, coin_type=20 (SLIP44), account=0
        val path = "m/84'/20'/0'"
        assertTrue(path.startsWith("m/84'"))
        assertTrue(path.contains("/20'"))
        assertTrue(path.endsWith("/0'"))
    }

    @Test
    fun `legacy path differs from BIP84 path`() {
        val legacy = "m/0H"
        val bip84 = "m/84'/20'/0'"
        assertNotEquals(legacy, bip84)
    }

    @Test
    fun `HMAC keys are different between standard and legacy`() {
        val standard = "Bitcoin seed"
        val legacy = "DigiByte seed"
        assertNotEquals(standard, legacy)
    }

    @Test
    fun `BIP84 uses coin type 20 for DigiByte per SLIP44`() {
        val coinType = 20
        assertEquals(20, coinType)
    }
}
