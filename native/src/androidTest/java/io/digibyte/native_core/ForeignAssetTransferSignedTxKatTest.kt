package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The DigiAsset transfer signer for a FOREIGN seed, through real JNI.
 *
 * Offline, no funds, no wallet state. Feeds the fixed BIP39 Trezor test seed and two synthetic
 * UTXOs — an asset marker and the DGB [io.digibyte.core.recovery.AssetFeeReserve] would have
 * kept back — in exactly the shape ForeignAssetTransferPlan produces, and asserts the result
 * re-parses as fully signed in the C core.
 *
 * ## What only this layer can prove
 *
 * The JVM tests prove the plan is arithmetically right and the host KAT proves the fee band is
 * right. Neither touches JNI, so neither can catch a marshalling fault: an output whose empty
 * address should select the raw OP_RETURN script and does not, an amount array read at the wrong
 * width, a seed copied wrong. Those only appear when the real bridge runs.
 *
 * ## The refusals matter more than the signature
 *
 * A signature that is merely wrong produces a transaction the network rejects — annoying, not
 * expensive. A fee guard that fails open hands the difference to a miner, silently and forever.
 * So the burn cases are asserted here too, against the live guard rather than the host copy.
 *
 * Requires a connected emulator/device. Build-only check:
 *   ./gradlew :native:assembleMainnetDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ForeignAssetTransferSignedTxKatTest {

    private val HARD = 0x80000000.toInt()

    // BIP39 Trezor test vector #1 — never funded on mainnet.
    private val testMnemonic =
        "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    private fun seed(): ByteArray {
        val s = NativeBridge.mnemonicToSeed(testMnemonic.toByteArray(), null)
        assertTrue("seed must be 64 bytes", s != null && s.size == 64)
        return s!!
    }

    /**
     * @param outAmounts marker, OP_RETURN, change — the plan's layout.
     */
    private fun sign(outAmounts: LongArray, outAddresses: Array<String>): String? {
        val s = seed()
        return try {
            NativeBridge.buildAndSignForeignAssetTransfer(
                seedBytes = s,
                hmacKey = "DigiByte seed",
                prefixPath = intArrayOf(0 or HARD),               // m/0'
                txidsHex = arrayOf(ASSET_TXID, FEE_TXID),
                vouts = intArrayOf(0, 0),
                amounts = longArrayOf(ASSET_MARKER_SAT, FEE_UTXO_SAT),
                chainIndices = intArrayOf(0, 0),
                addressIndices = intArrayOf(0, 0),                // both pay m/0'/0/0
                scriptPubKeysHex = arrayOf(SCRIPT_PUBKEY, SCRIPT_PUBKEY),
                outputAddresses = outAddresses,
                outputAmounts = outAmounts,
                outputScriptsHex = arrayOf("", OP_RETURN_SCRIPT, ""),
                feePerKb = FEE_PER_KB,
            )
        } finally {
            s.fill(0)
        }
    }

    private val planLayout = arrayOf(DEST_ADDRESS, "", DEST_ADDRESS)

    /** The shape the planner emits: marker, OP_RETURN, change — all value outputs to the user. */
    @Test
    fun foreignAssetTransfer_signsThePlannedShape() {
        val signedHex = sign(longArrayOf(ASSET_MARKER_SAT, 0L, CHANGE_SAT), planLayout)

        assertTrue("must return a signed hex", signedHex != null && signedHex.isNotEmpty())
        assertTrue(
            "re-parsed transfer must satisfy BRTransactionIsSigned",
            NativeBridge.isRawTransactionSigned(signedHex!!),
        )

        // The OP_RETURN went on the wire as a raw script, not as an address. If the empty-address
        // branch were broken the marker would be missing and the asset would not move.
        assertTrue(
            "the DigiAsset marker must appear in the signed transaction",
            signedHex.contains(OP_RETURN_SCRIPT),
        )

        android.util.Log.i("ForeignAssetKat", "signed transfer hex = $signedHex")

        // Deterministic known-answer pin. RFC6979 makes ECDSA output stable, so this is a
        // regression lock on the consensus shape of the transfer: two signed inputs, a 6,000-sat
        // marker, the raw OP_RETURN, and 245,100 sats of change. A change to this value means
        // the signer's output changed — treat it as a red flag, not a value to re-pin.
        assertEquals(EXPECTED_SIGNED_TX_HEX, signedHex)
    }

    /**
     * The whole reason the guard exists. Drop the change output and the reserved DGB becomes a
     * ~300,000-sat fee on a transaction that should cost ~55,000. Signing it would be valid, and
     * the user would simply never see that money again.
     */
    @Test
    fun foreignAssetTransfer_refusesToBurnAForgottenChangeOutput() {
        val signedHex = NativeBridge.buildAndSignForeignAssetTransfer(
            seedBytes = seed(),
            hmacKey = "DigiByte seed",
            prefixPath = intArrayOf(0 or HARD),
            txidsHex = arrayOf(ASSET_TXID, FEE_TXID),
            vouts = intArrayOf(0, 0),
            amounts = longArrayOf(ASSET_MARKER_SAT, FEE_UTXO_SAT),
            chainIndices = intArrayOf(0, 0),
            addressIndices = intArrayOf(0, 0),
            scriptPubKeysHex = arrayOf(SCRIPT_PUBKEY, SCRIPT_PUBKEY),
            outputAddresses = arrayOf(DEST_ADDRESS, ""),
            outputAmounts = longArrayOf(ASSET_MARKER_SAT, 0L),
            outputScriptsHex = arrayOf("", OP_RETURN_SCRIPT),
            feePerKb = FEE_PER_KB,
        )
        assertNull("a fee that large must be refused, not signed", signedHex)
    }

    /** A fee under the relay minimum produces a transaction that never confirms. */
    @Test
    fun foreignAssetTransfer_refusesAFeeBelowRelay() {
        // Leaves 1,000 sats of fee against a ~43,200-sat expectation.
        val change = ASSET_MARKER_SAT + FEE_UTXO_SAT - ASSET_MARKER_SAT - 1_000L
        assertNull(sign(longArrayOf(ASSET_MARKER_SAT, 0L, change), planLayout))
    }

    /** Outputs claiming more than the inputs hold is not a fee question; it must not sign. */
    @Test
    fun foreignAssetTransfer_refusesOverspend() {
        assertNull(sign(longArrayOf(ASSET_MARKER_SAT, 0L, FEE_UTXO_SAT * 2), planLayout))
    }

    /** An unparseable destination must fail closed rather than produce a burn output. */
    @Test
    fun foreignAssetTransfer_refusesAnInvalidDestination() {
        assertNull(sign(longArrayOf(ASSET_MARKER_SAT, 0L, CHANGE_SAT),
            arrayOf("not-a-digibyte-address", "", DEST_ADDRESS)))
    }

    companion object {
        // Synthetic prevouts paying the seed's legacy m/0'/0/0 address
        // (DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn, hash160 78f4f1ef...).
        const val ASSET_TXID =
            "1111111111111111111111111111111111111111111111111111111111111111"
        const val FEE_TXID =
            "2222222222222222222222222222222222222222222222222222222222222222"
        const val SCRIPT_PUBKEY = "76a91478f4f1ef3b18bc8b3cdf90d6c6e98bf6336f6f6988ac"

        /** DigiAsset convention: the marker output that carries the units. */
        const val ASSET_MARKER_SAT = 6_000L
        /** What AssetFeeReserve would have held back to pay for this move. */
        const val FEE_UTXO_SAT = 300_000L
        /** 306,000 in, 6,000 to the marker, 54,900 fee — the planner's arithmetic. */
        const val CHANGE_SAT = 245_100L

        /**
         * A real mainnet DigiAsset transfer marker: one instruction, 10 units to vout 0, taken
         * from tx 6aa6d5c92b2bf0d2368aaf718e596e84764a52ba7eaabbcd336b17a483d5a04f. Real bytes
         * rather than bytes this test invented, so a change in how markers are framed shows up
         * here as a difference from the chain rather than as agreement with itself.
         */
        const val OP_RETURN_SCRIPT = "6a0644410115000a"

        const val DEST_ADDRESS = "DBBSWfQdrDxq7S7YwZ6vi67BXZMvNKkAxe"
        const val FEE_PER_KB = 100_000L

        const val EXPECTED_SIGNED_TX_HEX =
            "010000000211111111111111111111111111111111111111111111111111111111111111" +
            "11000000006a47304402207c148571430d8a33a79866ff27227f2c8ca2d5271e1b8471ea" +
            "e84a1cdb1c23ed0220401ec9a71676fab92478ca1c0f5c646dc870b216eb8e95f44c5d57" +
            "a9e01d48d801210208dd15cbb7af1394d7b565d52ef4480687a1a8774875d5e906758acd" +
            "ea24b150ffffffff22222222222222222222222222222222222222222222222222222222" +
            "22222222000000006a47304402207bd1f97cb0f0eaa8095765aca258806cdb3ffe5c6992" +
            "e0e703294c212c1f155a02203be9ae2d6535dee3944612a709eeabbf79f42e493f591545" +
            "4e8cd5f90a72340c01210208dd15cbb7af1394d7b565d52ef4480687a1a8774875d5e906" +
            "758acdea24b150ffffffff0370170000000000001976a914424242424242424242424242" +
            "424242424242424288ac0000000000000000086a0644410115000a6cbd03000000000019" +
            "76a914424242424242424242424242424242424242424288ac00000000"
    }
}
