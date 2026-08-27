package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a user is told when the fee reserve holds back everything.
 *
 * ## The wording bug
 *
 * A UTXO cannot be split, so the reserve holds back whole ones. A wallet whose funds sit in a
 * SINGLE output therefore has that whole output reserved — correct, if over-generous — and the
 * sweep is left with nothing to spend.
 *
 * The empty-inputs branch then reported `FAILED` with **"no mappable UTXOs"**, which is false on
 * both counts. The UTXOs mapped fine. Nothing failed. The wallet deliberately kept them so the
 * user's DigiAsset would still be movable — and then described that as a malfunction, without
 * passing along the reserve fields that would have explained it.
 *
 * Someone reading "failed — no mappable UTXOs" about a wallet they can see has coins in it
 * reasonably concludes the wallet is broken and their funds are at risk. The opposite is true.
 *
 * ## The rule
 *
 * "Everything was kept back on purpose" is a distinct outcome from "nothing could be used", and
 * only the second is a failure.
 */
class ReserveOutcomeWordingTest {

    private fun utxo(id: String, sats: Long) = UtxoEntry(
        txid = id, vout = 0, amountSatoshi = sats,
        address = "D7Vx$id", blockHeight = 24_000_000L, scriptPubKeyHex = "76a91488ac",
    )

    /** One output, one asset: the whole balance is reserved and nothing is left to sweep. */
    @Test fun `a single utxo wallet reserves everything`() {
        val only = listOf(utxo("solo", 500_000_000L))
        val r = AssetFeeReserve.reserve(only, assetCount = 1)

        assertTrue("nothing left to sweep", r.stillSweepable.isEmpty())
        assertEquals("the whole output is held", only, r.reserved)
        assertEquals("this is NOT a shortfall — there was plenty", 0L, r.shortfall)
    }

    /**
     * The distinction the wording has to carry. Reserved-everything and genuinely-nothing-usable
     * both leave the sweep with no inputs, and they mean opposite things: the first is the wallet
     * protecting an asset, the second is a problem.
     */
    @Test fun `reserved-everything is distinguishable from nothing-usable`() {
        val reservedAll = AssetFeeReserve.reserve(listOf(utxo("solo", 500_000_000L)), assetCount = 1)
        val nothingThere = AssetFeeReserve.reserve(emptyList(), assetCount = 1)

        // Both end with an empty sweep set...
        assertTrue(reservedAll.stillSweepable.isEmpty())
        assertTrue(nothingThere.stillSweepable.isEmpty())

        // ...but only one of them held anything, which is what separates
        // "kept on purpose" from "there was nothing to keep".
        assertTrue("reserved-everything holds coins", reservedAll.reserved.isNotEmpty())
        assertTrue("nothing-usable holds none", nothingThere.reserved.isEmpty())
        assertEquals(0L, reservedAll.shortfall)
        assertTrue("only the empty case is short", nothingThere.shortfall > 0L)
    }

    @Test fun `no assets means an empty sweep set really is nothing usable`() {
        val r = AssetFeeReserve.reserve(emptyList(), assetCount = 0)
        assertTrue(r.reserved.isEmpty())
        assertEquals(0L, r.shortfall)
    }
}
