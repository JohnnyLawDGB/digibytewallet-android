package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offline, deterministic proof of the native amount-provenance fee-sanity
 * guard in buildAndSignLegacySweep (bug #2, defense c).
 *
 * All three cases share the SAME two real legacy-DGB-seed inputs
 * (m/0'/0/0 and m/0'/0/1 under the BIP39 Trezor test vector — never funded on
 * mainnet) with their real matching P2PKH scriptPubKeys, differing ONLY in the
 * reported amounts. That isolates the guard: a refusal or a successful sign can
 * only be the amounts, not the keys/scripts. txids are synthetic (never
 * validated offline).
 *
 * Requires a booted AVD (dgb-test-api33). Build check only:
 *   ./gradlew :native:assembleMainnetDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class LegacySweepAmountGuardTest {

    private val HARD = 0x80000000.toInt()
    private val testMnemonic =
        "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"
    private val hmac = "DigiByte seed"
    private val prefix = intArrayOf(0 or HARD)
    private val feePerKb = 100_000L // matches LegacySweepService default

    private class Fixture(
        val seed: ByteArray,
        val txids: Array<String>,
        val vouts: IntArray,
        val chains: IntArray,
        val indices: IntArray,
        val scripts: Array<String>,
        val dest: String,
    )

    /** Derive 3 legacy addrs: [0],[1] are the two inputs; [2] is the dest. */
    private fun fixture(): Fixture {
        val seed = NativeBridge.mnemonicToSeed(testMnemonic.toByteArray(), null)!!
        val addrs = NativeBridge.deriveAddresses(seed, hmac, prefix, 3, 0, 0)!!
        fun spk(a: String) =
            NativeBridge.addressToScriptPubKey(a)!!.joinToString("") { "%02x".format(it) }
        return Fixture(
            seed = seed,
            txids = arrayOf("11".repeat(32), "22".repeat(32)),
            vouts = intArrayOf(0, 0),
            chains = intArrayOf(0, 0),
            indices = intArrayOf(0, 1),
            scripts = arrayOf(spk(addrs[0]), spk(addrs[1])),
            dest = addrs[2],
        )
    }

    private fun build(f: Fixture, amounts: LongArray): String? =
        NativeBridge.buildAndSignLegacySweep(
            seedBytes = f.seed, hmacKey = hmac, prefixPath = prefix,
            txidsHex = f.txids, vouts = f.vouts, amounts = amounts,
            chainIndices = f.chains, addressIndices = f.indices,
            scriptPubKeysHex = f.scripts, destAddress = f.dest, feePerKb = feePerKb,
        )

    @Test
    fun feeSanityGuard_underReportedMultiInput_refuses() {
        val f = fixture()
        // 2 inputs => estSize=364B => fee=36_400 sat => fee*20=728_000.
        // totalIn=600_000 <= 728_000 trips the guard; 600_000 clears the dust
        // floor (fee+546=36_946), so ONLY the fee-sanity guard rejects this.
        val hex = build(f, longArrayOf(300_000L, 300_000L))
        f.seed.fill(0)
        assertNull("under-reported multi-input sweep must be refused", hex)
    }

    @Test
    fun feeSanityGuard_normalAmounts_signs() {
        val f = fixture()
        // totalIn=60_000_000 >> fee*20=728_000 => guard passes; real keys +
        // matching scripts => BRTransactionSign succeeds => non-null hex.
        val hex = build(f, longArrayOf(30_000_000L, 30_000_000L))
        f.seed.fill(0)
        assertNotNull("realistic-amount sweep must sign", hex)
    }

    @Test
    fun overReportedAmounts_signLocally_proveGuardCannotCatch() {
        val f = fixture()
        // Over-report is the case the on-device guard CANNOT catch (inflated
        // total makes fee a tiny fraction). It signs locally; only the network
        // (testmempoolaccept, defense d) rejects outputs>inputs.
        val hex = build(f, longArrayOf(5_000_000_000L, 5_000_000_000L))
        f.seed.fill(0)
        assertNotNull("over-reported sweep signs locally — network must reject it", hex)
        android.util.Log.i(
            "LegacySweepAmountGuard",
            "over-reported signed hex (feed to scripts/overreport-rejection-check.sh " +
                "against a REAL prevout during the mainnet proof) = $hex",
        )
    }
}
