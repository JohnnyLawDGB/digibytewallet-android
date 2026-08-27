package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps enough DGB behind to pay for moving the assets the sweep refused to touch.
 *
 * ## Why holding assets back was not enough
 *
 * [SweepPartition] stops an asset being spent as plain DGB and destroyed. Proven on mainnet. But
 * it left the asset with only its own marker output — 6,000 sats — while a DigiAsset transfer
 * costs roughly 40,000. The asset was safe and **could not pay to move itself**: stranded in a
 * wallet the user was in the middle of abandoning, recoverable only by sending DGB back into it.
 *
 * Not burning it is not the goal. Getting it to the new wallet is, and the DGB that pays for
 * that is exactly what the sweep was about to take.
 *
 * ## Whole UTXOs only
 *
 * A UTXO cannot be split, so the reserve is met by holding back whole ones. It takes the
 * SMALLEST that cover the target, because everything held back is money the user does not get
 * today — a reserve met with a 5 DGB output when a 0.5 DGB one would do is a worse answer even
 * though both "work".
 */
class AssetFeeReserveTest {

    private fun utxo(id: String, sats: Long) = UtxoEntry(
        txid = id, vout = 0, amountSatoshi = sats,
        address = "D7Vx$id", blockHeight = 24_000_000L, scriptPubKeyHex = "76a91488ac",
    )

    private val perAsset = 40_000L

    // ---- nothing to protect -----------------------------------------------------------------

    @Test fun `no assets means no reserve`() {
        val sweepable = listOf(utxo("a", 500_000L), utxo("b", 100_000L))
        val r = AssetFeeReserve.reserve(sweepable, assetCount = 0, feePerAsset = perAsset)

        assertEquals(sweepable, r.stillSweepable)
        assertTrue(r.reserved.isEmpty())
        assertTrue(r.shortfall == 0L)
    }

    // ---- the ordinary case ------------------------------------------------------------------

    @Test fun `one asset holds back enough to move it`() {
        val sweepable = listOf(utxo("big", 500_000_000L), utxo("small", 50_000L))
        val r = AssetFeeReserve.reserve(sweepable, assetCount = 1, feePerAsset = perAsset)

        assertEquals("the small one covers 40k and costs the user least", listOf(utxo("small", 50_000L)), r.reserved)
        assertEquals(listOf(utxo("big", 500_000_000L)), r.stillSweepable)
        assertEquals(0L, r.shortfall)
    }

    @Test fun `two assets reserve twice the fee`() {
        val sweepable = listOf(utxo("a", 45_000L), utxo("b", 45_000L), utxo("c", 900_000L))
        val r = AssetFeeReserve.reserve(sweepable, assetCount = 2, feePerAsset = perAsset)

        assertTrue("must cover 80k", r.reserved.sumOf { it.amountSatoshi } >= 80_000L)
        assertEquals(0L, r.shortfall)
    }

    /** Smallest-first, so the user keeps as much as possible today. */
    @Test fun `the cheapest sufficient utxos are chosen`() {
        val sweepable = listOf(utxo("huge", 900_000_000L), utxo("ok", 60_000L), utxo("tiny", 1_000L))
        val r = AssetFeeReserve.reserve(sweepable, assetCount = 1, feePerAsset = perAsset)

        assertTrue("must not strand the huge one", r.stillSweepable.any { it.txid == "huge" })
        assertTrue(r.reserved.sumOf { it.amountSatoshi } >= perAsset)
    }

    // ---- not enough to reserve --------------------------------------------------------------

    /**
     * The decision this test pins: when the wallet cannot cover the fees, hold EVERYTHING and
     * report a shortfall rather than sweeping. Sweeping here would take the last coins that could
     * ever move the asset, which is precisely the outcome the reserve exists to prevent — and
     * doing it while reporting success would be the worst of both.
     */
    @Test fun `too little to reserve sweeps nothing and reports the shortfall`() {
        val sweepable = listOf(utxo("a", 5_000L), utxo("b", 4_000L))
        val r = AssetFeeReserve.reserve(sweepable, assetCount = 1, feePerAsset = perAsset)

        assertTrue("nothing may be swept", r.stillSweepable.isEmpty())
        assertEquals(2, r.reserved.size)
        assertEquals("40000 needed, 9000 available", 31_000L, r.shortfall)
    }

    @Test fun `no spendable dgb at all is a shortfall, not a crash`() {
        val r = AssetFeeReserve.reserve(emptyList(), assetCount = 1, feePerAsset = perAsset)
        assertTrue(r.stillSweepable.isEmpty())
        assertEquals(perAsset, r.shortfall)
    }

    // ---- accounting -------------------------------------------------------------------------

    @Test fun `every utxo is either swept or reserved, never both, never lost`() {
        val all = listOf(utxo("a", 500_000L), utxo("b", 60_000L), utxo("c", 1_000L))
        val r = AssetFeeReserve.reserve(all, assetCount = 1, feePerAsset = perAsset)

        val union = r.stillSweepable + r.reserved
        assertEquals(all.size, union.size)
        assertEquals(all.toSet(), union.toSet())
    }

    @Test fun `an exact match reserves exactly one utxo`() {
        val sweepable = listOf(utxo("exact", 40_000L), utxo("other", 700_000L))
        val r = AssetFeeReserve.reserve(sweepable, assetCount = 1, feePerAsset = perAsset)

        assertEquals(listOf(utxo("exact", 40_000L)), r.reserved)
        assertEquals(0L, r.shortfall)
    }
}
