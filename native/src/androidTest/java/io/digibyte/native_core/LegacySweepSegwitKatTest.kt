package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer-B known-answer vector for the fund-MOVING sweep signer
 * (buildAndSignLegacySweep) when the INPUT is a native BIP84 P2WPKH (segwit)
 * UTXO. This is the input type the foreign-seed sweep newly exercises and which
 * no other KAT covers (LegacySweepSignedTxKatTest pins the legacy P2PKH-input
 * shape).
 *
 * Offline, no funds, no wallet state: feeds the fixed BIP39 Trezor test seed +
 * a SYNTHETIC P2WPKH UTXO that pays the seed's OWN native m/84'/20'/0'/0/0
 * address (dgb1q…). The scriptPubKey is computed in-test from that address via
 * BRAddressScriptPubKey (0014<hash160>), so it necessarily matches the signing
 * key and the ECDSA signature is self-consistent. The test asserts the returned
 * tx is fully signed (BRTransactionIsSigned, re-parsed in the C core) and pins
 * the exact signed hex. RFC6979 deterministic ECDSA makes the hex stable, so the
 * pin is a regression lock on the consensus shape of the swept segwit tx.
 *
 * PINNING WORKFLOW (two-pass — see the plan):
 *   Pass 1 — EXPECTED_SIGNED_TX_HEX == "" and the assertEquals below is
 *            commented out. Run on the booted dgb-test-api33 AVD, read logcat
 *            tag "SegwitKat", copy the emitted signed hex.
 *   Pass 2 — paste the hex into EXPECTED_SIGNED_TX_HEX, uncomment the
 *            assertEquals, re-run green. The vector is now regression-locked.
 *
 * Requires a connected emulator/device. Build-only check:
 *   ./gradlew :native:assembleMainnetDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class LegacySweepSegwitKatTest {

    private val HARD = 0x80000000.toInt()

    // BIP39 Trezor test vector #1 — never funded on mainnet.
    private val testMnemonic =
        "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    @Test
    fun sweep_signsSyntheticP2wpkhNativeUtxo_deterministically() {
        // Fixed seed from the fixed mnemonic.
        val seed = NativeBridge.mnemonicToSeed(testMnemonic.toByteArray(), null)
        assertTrue("seed must be 64 bytes", seed != null && seed.size == 64)

        // Derive the seed's OWN native BIP84 external address at m/84'/20'/0'/0/0
        // (a dgb1q… P2WPKH address) under the "Bitcoin seed" HMAC. The synthetic
        // UTXO we sweep pays THIS address, so chain=0,index=0 selects the exact
        // matching private key.
        val nativeAddr = NativeBridge.deriveAddresses(
            seed!!,
            "Bitcoin seed",
            intArrayOf(84 or HARD, 20 or HARD, 0 or HARD),  // m/84'/20'/0'
            1,   // one external (0/0) address
            0,   // no internal addresses
            1,   // P2WPKH
        )?.get(0)
        assertNotNull("derived native P2WPKH address must be non-null", nativeAddr)
        assertTrue("derived address must be bech32 native", nativeAddr!!.startsWith("dgb1q"))

        // Compute the 0014<hash160> witness-v0 scriptPubKey for that address via
        // BRAddressScriptPubKey. Because it is derived from the address itself it
        // is guaranteed to match the signing key — no manual pinning needed.
        val scriptBytes = NativeBridge.addressToScriptPubKey(nativeAddr)
        assertNotNull("native address scriptPubKey must be non-null", scriptBytes)
        val scriptHex = scriptBytes!!.joinToString("") { "%02x".format(it) }
        assertTrue("script must be a 22-byte witness-v0 (0014…) program",
            scriptHex.startsWith("0014") && scriptHex.length == 44)

        // Emit the synthetic prevout so the exact vector is captured on run 1.
        android.util.Log.i("SegwitKat", "native P2WPKH address = $nativeAddr")
        android.util.Log.i("SegwitKat", "synthetic P2WPKH scriptPubKey = $scriptHex")

        // Build + sign a sweep of the ONE synthetic P2WPKH UTXO.
        val signedHex = NativeBridge.buildAndSignLegacySweep(
            seedBytes = seed,
            hmacKey = "Bitcoin seed",
            prefixPath = intArrayOf(84 or HARD, 20 or HARD, 0 or HARD),  // m/84'/20'/0'
            txidsHex = arrayOf(SYNTHETIC_TXID),
            vouts = intArrayOf(SYNTHETIC_VOUT),
            amounts = longArrayOf(SYNTHETIC_AMOUNT_SAT),
            chainIndices = intArrayOf(0),            // external chain
            addressIndices = intArrayOf(0),          // index 0 -> m/84'/20'/0'/0/0
            scriptPubKeysHex = arrayOf(scriptHex),   // 0014<hash160> segwit program
            destAddress = DEST_ADDRESS,
            feePerKb = FEE_PER_KB,
        )

        // Zero the seed immediately.
        seed.fill(0)

        // The fund-moving path must produce a non-null, non-empty result. A null
        // here would mean segwit-input signing does NOT work end-to-end.
        assertTrue("buildAndSignLegacySweep must return a signed hex", signedHex != null)
        assertTrue("signed hex must be non-empty", signedHex!!.isNotEmpty())

        // Authoritative signed-ness: re-parse via the C core BRTransactionIsSigned.
        assertTrue(
            "re-parsed segwit sweep tx must satisfy BRTransactionIsSigned",
            NativeBridge.isRawTransactionSigned(signedHex),
        )

        // Emit the hex so it can be pinned on the first run.
        android.util.Log.i("SegwitKat", "signed p2wpkh-input sweep hex = $signedHex")

        // Deterministic known-answer pin (RFC6979 => stable). Enable in Pass 2.
        assertEquals(EXPECTED_SIGNED_TX_HEX, signedHex)
    }

    companion object {
        // Synthetic prevout paying the seed's native m/84'/20'/0'/0/0 address.
        // Palindromic txid so decoderawtransaction display == this literal.
        const val SYNTHETIC_TXID =
            "2222222222222222222222222222222222222222222222222222222222222222"
        const val SYNTHETIC_VOUT = 0
        const val SYNTHETIC_AMOUNT_SAT = 500_000_000L    // 5 DGB
        // Fixed valid DGB P2PKH sweep destination (hash160 = 0x42 * 20).
        const val DEST_ADDRESS = "DBBSWfQdrDxq7S7YwZ6vi67BXZMvNKkAxe"
        const val FEE_PER_KB = 100_000L

        // Pass 1: leave "" and keep the assertEquals above commented.
        // Pass 2: paste the logcat hex here and uncomment. A later change to
        // this value means the segwit signer output changed — treat as a red flag.
        const val EXPECTED_SIGNED_TX_HEX =
            "0100000000010122222222222222222222222222222222222222222222" +
            "222222222222222222220000000000ffffffff015015cd1d0000000019" +
            "76a914424242424242424242424242424242424242424288ac02483045" +
            "022100e9f5b167afa20f6d11bbbe6d292adb21177b9ab186a90605f1b2" +
            "6932da34b5a9022039d1f68d963aae6504766651a112bd7478dee3eeac" +
            "c7a462726f40633693346c0121038c33be7fcf1b24783fe6bb6fbee5c9" +
            "3b36d618ef17e96f51052db7d3faed46de00000000"
    }
}
