package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the seed-taking JNI entry points do not mutate the caller's array.
 *
 * WHAT THIS EXISTS TO CATCH
 * -------------------------
 * Before the v4.0.58 security cycle, every seed-taking function in `jni_derive.c` derived
 * straight from the pointer `GetByteArrayElements` returned and then called
 * `secure_zero(seedRaw, seedLen)` on it before releasing with `JNI_ABORT`. `isCopy` was
 * passed `NULL`, so the code never learned whether that pointer addressed a private copy or
 * the live Java array. `JNI_ABORT` discards writes made to a *copy*; it cannot take back
 * writes that already landed on the Java heap.
 *
 * `LegacySweepService` is what turns that into lost funds rather than trivia: it loads the
 * seed ONCE and calls one sweep per derivation profile in a loop, passing the same array
 * every time. If the VM hands back a direct pointer, profile #1 empties the shared array and
 * every later profile derives from 64 zero bytes — signing with the wrong keys, silently, on
 * a funds path.
 *
 * WHY IT HAS TO RUN ON A DEVICE
 * -----------------------------
 * The host KAT (`native/src/test/host/seed_buffer_ownership_kat`) proves the fix is correct
 * under the adverse assumption. It cannot prove what a real ART actually does, because it has
 * no ART in it. Only this test can, and the answer is per-runtime — which is exactly why the
 * production code must not depend on it either way.
 *
 * Run against a PRE-FIX build, this test tells you whether the bug was live on that device.
 * Run against a fixed build it must pass on every device, because the fix removes the
 * dependency on the answer rather than betting on it.
 *
 * MEASURED, 2026-08-26 — Galaxy Note 8 (SM-N950U), Android 9, API 28:
 * all three tests PASS against the **pre-fix** binary. On that runtime
 * `GetByteArrayElements` returns a COPY, so the pre-fix code was wiping a copy and the bug
 * was NOT live there. That is a measurement of one ART on one device, not a property of the
 * platform: it may differ by API level, GC configuration, array size, or heap state, and the
 * S25 Ultra (API 35) has not been measured. The fix is worth having precisely because it
 * makes the answer irrelevant — but nobody should read this file as saying the pre-fix code
 * was harmless.
 *
 * A consequence worth being explicit about: on a runtime that copies, this test passes both
 * before and after the fix, so it is NOT the regression gate. The host KAT is — it fails
 * closed on the pre-fix shape by construction. This test's job is to measure the runtime and
 * to catch a future ART that starts handing back direct pointers.
 *
 * The seed is the BIP39 Trezor test vector, never funded on mainnet — same convention as
 * [LegacySweepDerivationTest], so nothing here can touch real money.
 *
 * DO NOT run without a connected device or emulator.
 * Build check only: ./gradlew :native:assembleMainnetDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SeedBufferOwnershipTest {

    private val HARD = 0x80000000.toInt()

    private val testMnemonic =
        "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    /** A fresh 64-byte seed from the fixed vector. Each caller gets its own array. */
    private fun freshSeed(): ByteArray {
        val seed = NativeBridge.mnemonicToSeed(testMnemonic.toByteArray(), null)
        assertNotNull("mnemonicToSeed must not return null", seed)
        assertEquals("BIP39 seed must be 64 bytes", 64, seed!!.size)
        return seed
    }

    private fun deriveLegacy(seed: ByteArray): Array<String>? =
        NativeBridge.deriveAddresses(
            seedBytes = seed,
            hmacKey = "DigiByte seed",
            prefixPath = intArrayOf(0 or HARD),
            gapExternal = 3,
            gapInternal = 0,
            addressFormat = 0,
        )

    @Test
    fun deriveAddresses_leavesTheCallersSeedIntact() {
        val seed = freshSeed()
        val before = seed.copyOf()
        try {
            val addrs = deriveLegacy(seed)
            assertNotNull("deriveAddresses must not return null", addrs)
            assertTrue("must derive at least one address", addrs!!.isNotEmpty())

            // The property under test. assertArrayEquals reports the first differing index,
            // which distinguishes "wiped" from "corrupted" if this ever fails.
            assertArrayEquals(
                "deriveAddresses mutated the caller's seed array — native code wrote through " +
                    "a JNI buffer it does not own (see jni_seed_buffer.h)",
                before, seed,
            )
            // A positive assertion too: a seed of all zeros would satisfy "unchanged" if the
            // vector itself were somehow empty, and then this test would be proving nothing.
            assertTrue("the test vector must not be all zeros", seed.any { it != 0.toByte() })
        } finally {
            seed.fill(0)
            before.fill(0)
        }
    }

    /**
     * The LegacySweepService shape: one seed array, one derivation per profile, in a loop.
     * Profile #1 is never the interesting one — #2 and #3 are.
     */
    @Test
    fun repeatedDerivationsFromOneArrayAgree() {
        val seed = freshSeed()
        try {
            val runs = (1..3).map { deriveLegacy(seed) }

            runs.forEachIndexed { i, addrs ->
                assertNotNull("derivation ${i + 1} returned null", addrs)
                assertTrue("derivation ${i + 1} returned no addresses", addrs!!.isNotEmpty())
            }

            val first = runs.first()!!
            runs.drop(1).forEachIndexed { i, addrs ->
                assertArrayEquals(
                    "derivation ${i + 2} disagreed with derivation 1 — the shared seed array " +
                        "was consumed by an earlier call, so this profile derived from a " +
                        "different (probably zeroed) seed",
                    first, addrs,
                )
            }

            // Positive check: legacy DGB P2PKH addresses start with 'D'. Without this, an
            // implementation that returned three identical empty arrays would pass above.
            assertTrue(
                "derived addresses must look like legacy DGB P2PKH, got: ${first.toList()}",
                first.all { it.isEmpty() || it.startsWith("D") },
            )
            assertTrue("at least one non-empty address expected", first.any { it.isNotEmpty() })
        } finally {
            seed.fill(0)
        }
    }

    @Test
    fun derivePrivateKeyWIF_leavesTheCallersSeedIntact() {
        val seed = freshSeed()
        val before = seed.copyOf()
        try {
            val wif1 = NativeBridge.derivePrivateKeyWIF(
                seedBytes = seed,
                hmacKey = "DigiByte seed",
                fullPath = intArrayOf(0 or HARD, 0, 0),
            )
            assertNotNull("derivePrivateKeyWIF must not return null", wif1)
            assertTrue("WIF must be non-empty", wif1!!.isNotEmpty())

            assertArrayEquals(
                "derivePrivateKeyWIF mutated the caller's seed array", before, seed,
            )

            // Same array, second call — must produce the same key.
            val wif2 = NativeBridge.derivePrivateKeyWIF(
                seedBytes = seed,
                hmacKey = "DigiByte seed",
                fullPath = intArrayOf(0 or HARD, 0, 0),
            )
            assertEquals(
                "a second derivation from the same array produced a different key — the seed " +
                    "was consumed by the first call",
                wif1, wif2,
            )
        } finally {
            seed.fill(0)
            before.fill(0)
        }
    }
}
