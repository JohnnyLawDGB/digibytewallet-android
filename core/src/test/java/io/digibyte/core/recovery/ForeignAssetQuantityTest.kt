package io.digibyte.core.recovery

import io.digibyte.core.asset.DigiAssetEncoder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How many asset units sit on one output of a FOREIGN transaction.
 *
 * ## Why this is separate from the wallet's own detection
 *
 * [io.digibyte.core.asset.AssetManager] answers the same question, but only for transactions
 * `BRWallet` has registered — which a wallet the user is migrating away from has not. The rule
 * itself is shared ([io.digibyte.core.asset.AssetTxQuantity]); what differs is where the outputs
 * come from. Here they are parsed out of raw bytes fetched during the recovery scan.
 *
 * ## It under-counts on purpose
 *
 * `percent` instructions need per-input balances we do not have for a foreign seed, and the
 * implicit-change remainder needs the same. Both resolve to zero rather than to a guess. That is
 * safe ONLY because [ForeignAssetTransferPlan] puts every output in the destination wallet, so
 * units this misses still arrive. It would be catastrophic in a design that sent change back.
 */
class ForeignAssetQuantityTest {

    private fun out(vout: Int, sats: Long, script: ByteArray) =
        ForeignAssetQuantity.Output(vout, sats, script)

    private fun opReturn(vararg inst: DigiAssetEncoder.TransferInstruction) =
        DigiAssetEncoder.encodeTransferScript(version = 3, instructions = inst.toList())

    private val p2pkh = ByteArray(25) { 0x11 }

    private fun transfer(outputIndex: Int, amount: Long, range: Boolean = false, percent: Boolean = false) =
        DigiAssetEncoder.TransferInstruction(
            skip = false, range = range, percent = percent, outputIndex = outputIndex, amount = amount,
        )

    @Test fun `a transfer credits the output its instruction names`() {
        val outputs = listOf(
            out(0, 6_000L, p2pkh),
            out(1, 0L, opReturn(transfer(outputIndex = 0, amount = 25L))),
            out(2, 90_000L, p2pkh),
        )
        assertEquals(25L, ForeignAssetQuantity.unitsOn(outputs, vout = 0))
        assertEquals("an output no instruction names gets nothing", 0L, ForeignAssetQuantity.unitsOn(outputs, vout = 2))
    }

    @Test fun `a range instruction credits every output up to its index`() {
        val outputs = listOf(
            out(0, 6_000L, p2pkh),
            out(1, 6_000L, p2pkh),
            out(2, 0L, opReturn(transfer(outputIndex = 1, amount = 7L, range = true))),
        )
        assertEquals(7L, ForeignAssetQuantity.unitsOn(outputs, vout = 0))
        assertEquals(7L, ForeignAssetQuantity.unitsOn(outputs, vout = 1))
    }

    /** A transaction with no DigiAsset marker carries no units, on any output. */
    @Test fun `a plain transaction credits nothing`() {
        val outputs = listOf(out(0, 500_000L, p2pkh), out(1, 90_000L, p2pkh))
        assertEquals(0L, ForeignAssetQuantity.unitsOn(outputs, vout = 0))
    }

    @Test fun `an unparseable marker credits nothing rather than throwing`() {
        val junk = byteArrayOf(0x6a, 0x04, 0x00, 0x01, 0x02, 0x03)
        val outputs = listOf(out(0, 6_000L, p2pkh), out(1, 0L, junk))
        assertEquals(0L, ForeignAssetQuantity.unitsOn(outputs, vout = 0))
    }

    /**
     * The deliberate under-count. A percentage needs the per-input balances a foreign seed does
     * not give us, so it resolves to zero — never to a number we made up.
     */
    @Test fun `a percent instruction resolves to zero, not a guess`() {
        val outputs = listOf(
            out(0, 6_000L, p2pkh),
            out(1, 0L, opReturn(transfer(outputIndex = 0, amount = 50L, percent = true))),
        )
        assertEquals(0L, ForeignAssetQuantity.unitsOn(outputs, vout = 0))
    }

    @Test fun `an output index outside the transaction credits nothing`() {
        val outputs = listOf(out(0, 6_000L, p2pkh), out(1, 0L, opReturn(transfer(0, 25L))))
        assertEquals(0L, ForeignAssetQuantity.unitsOn(outputs, vout = 9))
    }

    @Test fun `an empty output list credits nothing`() {
        assertEquals(0L, ForeignAssetQuantity.unitsOn(emptyList(), vout = 0))
    }

    /** Two instructions can target the same output; both count. */
    @Test fun `instructions targeting one output are summed`() {
        val outputs = listOf(
            out(0, 6_000L, p2pkh),
            out(1, 0L, opReturn(transfer(0, 4L), transfer(0, 6L))),
        )
        assertEquals(10L, ForeignAssetQuantity.unitsOn(outputs, vout = 0))
    }
}
