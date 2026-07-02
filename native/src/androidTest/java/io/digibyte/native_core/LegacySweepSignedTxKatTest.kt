package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer-B known-answer vector for the fund-MOVING sweep signer
 * (buildAndSignLegacySweep). Offline, no funds, no wallet state: feeds the
 * fixed BIP39 Trezor test seed + a SYNTHETIC P2PKH UTXO that pays the seed's
 * own legacy m/0'/0/0 address, asserts the returned tx is fully signed
 * (BRTransactionIsSigned, re-parsed in the C core), and pins the exact signed
 * hex. RFC6979 deterministic ECDSA makes the hex stable, so the pin is a
 * regression lock on the consensus shape of the swept transaction.
 *
 * PINNING WORKFLOW (two-pass — see the plan):
 *   Pass 1 — EXPECTED_SIGNED_TX_HEX == "" and the assertEquals below is
 *            commented out. Run on a booted dgb-test-api33 AVD, read logcat
 *            tag "LegacySweepKat", copy the emitted hex.
 *   Pass 2 — paste the hex into EXPECTED_SIGNED_TX_HEX, uncomment the
 *            assertEquals, re-run green. The vector is now regression-locked.
 *
 * Requires a connected emulator/device. Build-only check:
 *   ./gradlew :native:assembleMainnetDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class LegacySweepSignedTxKatTest {

    private val HARD = 0x80000000.toInt()

    // BIP39 Trezor test vector #1 — never funded on mainnet.
    private val testMnemonic =
        "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    @Test
    fun legacySweep_signsSyntheticP2pkhUtxo_deterministically() {
        // Fixed seed from the fixed mnemonic.
        val seed = NativeBridge.mnemonicToSeed(testMnemonic.toByteArray(), null)
        assertTrue("seed must be 64 bytes", seed != null && seed.size == 64)

        // Build + sign a sweep of ONE synthetic P2PKH UTXO paying the legacy
        // m/0'/0/0 address (DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn) of this seed
        // under the "DigiByte seed" HMAC. chain=0,index=0 selects the matching
        // private key, so the ECDSA signature is self-consistent with the
        // scriptPubKey being spent.
        val signedHex = NativeBridge.buildAndSignLegacySweep(
            seedBytes = seed!!,
            hmacKey = "DigiByte seed",
            prefixPath = intArrayOf(0 or HARD),      // m/0'
            txidsHex = arrayOf(SYNTHETIC_TXID),
            vouts = intArrayOf(SYNTHETIC_VOUT),
            amounts = longArrayOf(SYNTHETIC_AMOUNT_SAT),
            chainIndices = intArrayOf(0),            // external chain
            addressIndices = intArrayOf(0),          // index 0 -> DGAf4Mmt...
            scriptPubKeysHex = arrayOf(SYNTHETIC_SCRIPT_PUBKEY),
            destAddress = DEST_ADDRESS,
            feePerKb = FEE_PER_KB,
        )

        // Zero the seed immediately.
        seed.fill(0)

        // The fund-moving path must produce a non-null, non-empty result.
        assertTrue("buildAndSignLegacySweep must return a signed hex", signedHex != null)
        assertTrue("signed hex must be non-empty", signedHex!!.isNotEmpty())

        // Authoritative signed-ness: re-parse via the C core BRTransactionIsSigned.
        assertTrue(
            "re-parsed sweep tx must satisfy BRTransactionIsSigned",
            NativeBridge.isRawTransactionSigned(signedHex),
        )

        // Emit the hex so it can be pinned on the first run.
        android.util.Log.i("LegacySweepKat", "signed sweep tx hex = $signedHex")

        // Deterministic known-answer pin (RFC6979 => stable). Enable in Pass 2.
        assertEquals(EXPECTED_SIGNED_TX_HEX, signedHex)
    }

    companion object {
        // Synthetic prevout paying the seed's legacy m/0'/0/0 address.
        // Palindromic txid so decoderawtransaction display == this literal.
        const val SYNTHETIC_TXID =
            "1111111111111111111111111111111111111111111111111111111111111111"
        const val SYNTHETIC_VOUT = 0
        const val SYNTHETIC_AMOUNT_SAT = 500_000_000L    // 5 DGB
        // P2PKH scriptPubKey for DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn
        // (hash160 78f4f1ef3b18bc8b3cdf90d6c6e98bf6336f6f69).
        const val SYNTHETIC_SCRIPT_PUBKEY =
            "76a91478f4f1ef3b18bc8b3cdf90d6c6e98bf6336f6f6988ac"
        // Fixed valid DGB P2PKH sweep destination (hash160 = 0x42 * 20).
        const val DEST_ADDRESS = "DBBSWfQdrDxq7S7YwZ6vi67BXZMvNKkAxe"
        const val FEE_PER_KB = 100_000L

        // Pass 1: leave "" and keep the assertEquals above commented.
        // Pass 2: paste the logcat hex here and uncomment. A later change to
        // this value means the signer output changed — treat as a red flag.
        const val EXPECTED_SIGNED_TX_HEX =
            "01000000011111111111111111111111111111111111111111111111111111111111111111" +
            "000000006a47304402206b176e0b47dd319c6439a280457480e8c30fd865e94bd260ad718a" +
            "c60e32cfbb022030503bbaeee82039abb7b7437725989d81e51045b449da7f76e17c209263" +
            "67f201210208dd15cbb7af1394d7b565d52ef4480687a1a8774875d5e906758acdea24b150" +
            "ffffffff015015cd1d000000001976a914424242424242424242424242424242424242424288ac00000000"
    }
}
