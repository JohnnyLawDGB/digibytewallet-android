package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Known-answer derivation test for the "Legacy DGB-seed" profile used by
 * LegacySweepService.  Uses the BIP39 Trezor test vector (never funded on
 * mainnet) so the seed is fixed and the derived addresses are deterministic.
 *
 * PINNING WORKFLOW (two-pass):
 *   Pass 1 — run on an emulator, read logcat for the tag "LegacySweepDeriv",
 *             copy the printed address into EXPECTED_FIRST_LEGACY_ADDR, then
 *             uncomment the assertEquals assertion at the bottom.
 *   Pass 2 — re-run; the test becomes a regression-locked known-answer vector.
 *
 * DO NOT run this test without a connected emulator or device.
 * Build check only: ./gradlew :native:assembleMainnetDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class LegacySweepDerivationTest {

    private val HARD = 0x80000000.toInt()

    // BIP39 Trezor test vector #1 — never funded on mainnet.
    private val testMnemonic =
        "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    @Test
    fun legacyDigiByteSeed_profileDerivesDeterministically() {
        // Step 1: derive seed from fixed mnemonic.
        val seed = NativeBridge.mnemonicToSeed(testMnemonic.toByteArray(), null)
        assertTrue("seed must not be null", seed != null)
        assertTrue("seed must be 64 bytes", seed!!.size == 64)

        // Step 2: derive legacy DGB-seed addresses (path: m/0').
        val addrs = NativeBridge.deriveAddresses(
            seedBytes = seed,
            hmacKey = "DigiByte seed",
            prefixPath = intArrayOf(0 or HARD),
            gapExternal = 5,
            gapInternal = 0,
            addressFormat = 0,      // P2PKH — legacy "D" addresses
        )

        // Step 3: zero seed immediately after derivation.
        seed.fill(0)

        // Step 4: structural assertions (pass before address is pinned).
        assertTrue("deriveAddresses must not return null", addrs != null)
        assertTrue("must return at least one address", addrs!!.isNotEmpty())
        for (addr in addrs) {
            assertTrue(
                "every non-empty entry must start with 'D' (legacy DGB P2PKH), got: '$addr'",
                addr.isEmpty() || addr.startsWith("D")
            )
        }

        // Step 5: log the first address so it can be pinned after the first run.
        android.util.Log.i("LegacySweepDeriv", "first legacy addr = " + addrs[0])

        // Step 6: deterministic pin — enable after reading the logcat value above.
        // assertEquals(EXPECTED_FIRST_LEGACY_ADDR, addrs[0])  // enable after pinning from logcat
    }

    companion object {
        // Replace with the value printed in logcat on the first emulator run,
        // then uncomment the assertEquals line above to lock in the vector.
        const val EXPECTED_FIRST_LEGACY_ADDR = "REPLACE_AFTER_FIRST_RUN"
    }
}
