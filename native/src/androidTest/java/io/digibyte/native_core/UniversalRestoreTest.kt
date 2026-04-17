package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Self-contained validation of Universal Restore primitives.
 *
 * Strategy: use the BIP39 Trezor test vector ("abandon x11 + about") and
 * cross-validate the NEW universal derivation functions against the EXISTING
 * ones that the wallet already uses in production. If the new functions agree
 * with the old ones on overlapping paths, the new machinery is correct. New
 * paths (BIP44 DGB, BIP44 wrong-coin, BIP49) are sanity-checked for
 * non-emptiness and correct address prefix.
 */
@RunWith(AndroidJUnit4::class)
class UniversalRestoreTest {

    companion object {
        // BIP39 Trezor test vector #1 — 12-word mnemonic.
        const val TEST_MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        // Known seed hex prefix (first 16 chars) — from the BIP39 spec.
        const val EXPECTED_SEED_PREFIX = "5eb00bbddcf06908"
        // BIP32 hardened flag.
        const val HARD = 0x80000000.toInt()
    }

    @Test
    fun mnemonicToSeed_producesKnownSeed() {
        val phraseBytes = TEST_MNEMONIC.toByteArray(Charsets.UTF_8)
        val seed = NativeBridge.mnemonicToSeed(phraseBytes, null)
        assertNotNull("mnemonicToSeed should return non-null", seed)
        assertEquals("BIP39 seed is 64 bytes", 64, seed!!.size)
        val seedHex = seed.joinToString("") { "%02x".format(it) }
        assertTrue(
            "seed should start with $EXPECTED_SEED_PREFIX",
            seedHex.startsWith(EXPECTED_SEED_PREFIX)
        )
        seed.fill(0) // clean up
    }

    @Test
    fun crossValidate_bip84_newVsOld() {
        // Create a wallet from the test mnemonic via the old code path.
        val phraseBytes = TEST_MNEMONIC.toByteArray(Charsets.UTF_8)
        val created = NativeBridge.createWalletFromBytes(phraseBytes)
        assertTrue("wallet should create successfully", created)

        // Get the first BIP84 address via the old code path.
        val oldAddr = NativeBridge.getReceiveAddress(0, format = 2) // bech32
        assertNotNull("old-path BIP84 address should be non-null", oldAddr)
        assertTrue("BIP84 address should start with dgb1", oldAddr!!.startsWith("dgb1"))

        // Derive via the NEW path (same parameters).
        val seed = NativeBridge.mnemonicToSeed(phraseBytes, null)!!
        val newAddrs = NativeBridge.deriveAddresses(
            seedBytes = seed,
            hmacKey = "Bitcoin seed",
            prefixPath = intArrayOf(84 or HARD, 20 or HARD, 0 or HARD),
            gapExternal = 5,
            gapInternal = 2,
            addressFormat = 1 // P2WPKH
        )
        seed.fill(0)

        assertNotNull("new-path deriveAddresses should return non-null", newAddrs)
        assertTrue("should have >= 5 addresses", newAddrs!!.size >= 5)
        assertEquals(
            "first external address must match old-path BIP84",
            oldAddr, newAddrs[0]
        )
    }

    /** (label, hmacKey, prefixPath, addressFormat, expectedPrefix) */
    data class TestProfile(
        val label: String,
        val hmac: String,
        val path: IntArray,
        val addrFmt: Int,
        val addrPrefix: String,
    )

    private val profiles = listOf(
        TestProfile("BIP84 DGB",         "Bitcoin seed",  intArrayOf(84 or HARD, 20 or HARD, 0 or HARD), 1, "dgb1"),
        TestProfile("Legacy DGB-seed",   "DigiByte seed", intArrayOf(0 or HARD),                         0, "D"),
        TestProfile("Legacy BTC-seed",   "Bitcoin seed",  intArrayOf(0 or HARD),                         0, "D"),
        TestProfile("BIP44 DGB",         "Bitcoin seed",  intArrayOf(44 or HARD, 20 or HARD, 0 or HARD), 0, "D"),
        TestProfile("BIP44 wrong-coin",  "Bitcoin seed",  intArrayOf(44 or HARD, 0 or HARD, 0 or HARD),  0, "D"),
        TestProfile("BIP49 DGB",         "Bitcoin seed",  intArrayOf(49 or HARD, 20 or HARD, 0 or HARD), 2, "S"),
    )

    @Test
    fun allProfiles_deriveNonEmptyAddresses() {
        val phraseBytes = TEST_MNEMONIC.toByteArray(Charsets.UTF_8)
        val seed = NativeBridge.mnemonicToSeed(phraseBytes, null)!!

        for (p in profiles) {
            val addrs = NativeBridge.deriveAddresses(
                seedBytes = seed,
                hmacKey = p.hmac,
                prefixPath = p.path,
                gapExternal = 5,
                gapInternal = 2,
                addressFormat = p.addrFmt,
            )
            assertNotNull("${p.label}: deriveAddresses must not return null", addrs)
            assertTrue("${p.label}: must have >= 5 addresses", addrs!!.size >= 5)
            val first = addrs[0]
            assertTrue(
                "${p.label}: first address '$first' should start with '${p.addrPrefix}'",
                first.startsWith(p.addrPrefix)
            )
        }
        seed.fill(0)
    }

    @Test
    fun differentProfiles_produceDifferentAddresses() {
        val phraseBytes = TEST_MNEMONIC.toByteArray(Charsets.UTF_8)
        val seed = NativeBridge.mnemonicToSeed(phraseBytes, null)!!

        val firstAddrs = mutableMapOf<String, String>()
        for (p in profiles) {
            val addrs = NativeBridge.deriveAddresses(
                seedBytes = seed,
                hmacKey = p.hmac,
                prefixPath = p.path,
                gapExternal = 1,
                gapInternal = 0,
                addressFormat = p.addrFmt,
            )!!
            firstAddrs[p.label] = addrs[0]
        }
        seed.fill(0)

        val unique = firstAddrs.values.toSet()
        assertEquals(
            "Each profile should produce a unique first address. Got: $firstAddrs",
            firstAddrs.size, unique.size
        )
    }

    @Test
    fun derivePrivateKeyWIF_returnsValidWIF() {
        val phraseBytes = TEST_MNEMONIC.toByteArray(Charsets.UTF_8)
        val seed = NativeBridge.mnemonicToSeed(phraseBytes, null)!!

        val wif = NativeBridge.derivePrivateKeyWIF(
            seedBytes = seed,
            hmacKey = "Bitcoin seed",
            fullPath = intArrayOf(84 or HARD, 20 or HARD, 0 or HARD, 0, 0),
        )
        seed.fill(0)

        assertNotNull("WIF should not be null", wif)
        assertTrue("DGB WIF starts with Q or K (compressed mainnet)", wif!!.startsWith("Q") || wif.startsWith("K") || wif.startsWith("L"))
        assertTrue("WIF should be 51-52 chars", wif.length in 51..52)
    }
}
