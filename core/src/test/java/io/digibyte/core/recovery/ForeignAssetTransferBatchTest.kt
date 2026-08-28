package io.digibyte.core.recovery

import io.digibyte.core.asset.send.DA_MARKER_SATS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sharing one pool of reserved DGB across several DigiAssets, each of which needs its own
 * transaction.
 *
 * ## Why one transaction per asset
 *
 * Mixing two assets into a single transfer is legal on the wire, but it makes one failure take
 * both assets down with it and it makes the resulting transaction much harder to read back.
 * [AssetFeeReserve] already reserves per-outpoint — 40,000 sats each — so per-outpoint is also
 * the shape the money was set aside in.
 *
 * ## Why allocation needs its own rules
 *
 * A UTXO cannot be split, so the reserve is a handful of whole outputs of assorted sizes, and
 * each transfer has to be given enough of them to clear its fee AND leave a non-dust change
 * output. Getting this wrong in the generous direction strands the last asset with nothing left
 * to pay with — the exact failure the reserve exists to prevent, reintroduced one layer up.
 */
class ForeignAssetTransferBatchTest {

    private val dest = "dgb1qgapugthjpsqnh80jn7un0f34u2qusl8y7gg76f"
    private val feePerKb = 100_000L

    private fun spend(id: String, sats: Long) = ForeignAssetTransferPlan.Spend(
        txid = id, vout = 0, amountSat = sats,
        scriptPubKeyHex = "76a914aabbccddeeff00112233445566778899aabbccdd88ac",
        chain = 0, index = 0,
    )

    private fun asset(id: String, units: Long) =
        ForeignAssetTransferBatch.AssetItem(spend(id, DA_MARKER_SATS), units)

    private fun plan(assets: List<ForeignAssetTransferBatch.AssetItem>, pool: List<ForeignAssetTransferPlan.Spend>) =
        ForeignAssetTransferBatch.plan(assets, pool, dest, feePerKb)

    // ---- the ordinary case -------------------------------------------------------------------

    @Test fun `two assets get a transaction each`() {
        val out = plan(
            listOf(asset("asset1", 10L), asset("asset2", 5L)),
            listOf(spend("f1", 100_000L), spend("f2", 100_000L)),
        )
        assertEquals(2, out.size)
        assertTrue("both planned, got $out", out.all { it.result is ForeignAssetTransferPlan.Result.Ok })
    }

    /** No fee UTXO may be spent by two transactions — that is a double-spend, not a saving. */
    @Test fun `a fee input is never handed to two transfers`() {
        val out = plan(
            listOf(asset("asset1", 10L), asset("asset2", 5L)),
            listOf(spend("f1", 100_000L), spend("f2", 100_000L)),
        )
        val used = out.flatMap { (it.result as ForeignAssetTransferPlan.Result.Ok).plan.inputs }
            .filter { it.txid.startsWith("f") }
            .map { it.txid }
        assertEquals("each fee input used at most once", used.size, used.toSet().size)
    }

    @Test fun `the asset outpoint is reported alongside its outcome so the UI can name it`() {
        val out = plan(listOf(asset("asset1", 10L)), listOf(spend("f1", 100_000L)))
        assertEquals("asset1:0", out.single().outpoint)
    }

    // ---- running out -------------------------------------------------------------------------

    /**
     * The failure the reserve exists to prevent, one layer up: spend the pool too freely on the
     * first asset and the second has nothing left. Whatever the allocator does, it must not
     * report success for an asset it did not fund.
     */
    @Test fun `an asset with nothing left to pay with is refused, not silently dropped`() {
        val out = plan(
            listOf(asset("asset1", 10L), asset("asset2", 5L)),
            listOf(spend("f1", 100_000L)),   // enough for exactly one transfer
        )
        assertEquals("every asset is accounted for", 2, out.size)
        assertTrue(out[0].result is ForeignAssetTransferPlan.Result.Ok)
        val refused = out[1].result as ForeignAssetTransferPlan.Result.Refused
        assertEquals(ForeignAssetTransferPlan.Reason.INSUFFICIENT_FEE_FUNDS, refused.reason)
    }

    /** A refused transfer must release its fee inputs — otherwise one unfundable asset
     *  swallows the pool and takes every asset after it down too. */
    @Test fun `a refusal does not consume the pool`() {
        // asset1 needs more than the pool holds; asset2 is identical. Neither can be funded,
        // but the second must fail for the same honest reason, not because the first ate it.
        val out = plan(
            listOf(asset("asset1", 10L), asset("asset2", 5L)),
            listOf(spend("tiny", 20_000L)),
        )
        assertTrue(out.all { it.result is ForeignAssetTransferPlan.Result.Refused })
    }

    @Test fun `no assets is an empty batch, not an error`() {
        assertTrue(plan(emptyList(), listOf(spend("f1", 100_000L))).isEmpty())
    }

    @Test fun `no fee pool refuses every asset`() {
        val out = plan(listOf(asset("asset1", 10L), asset("asset2", 5L)), emptyList())
        assertEquals(2, out.size)
        assertTrue(out.all { it.result is ForeignAssetTransferPlan.Result.Refused })
    }

    // ---- how the pool is spent ---------------------------------------------------------------

    /**
     * Smallest-first, same as [AssetFeeReserve]. Paying a 55,000-sat fee by pulling in a 5 DGB
     * output when a 0.5 DGB one would do leaves the next asset facing a pool that looks full and
     * is not.
     */
    @Test fun `the smallest sufficient inputs are spent first`() {
        val out = plan(
            listOf(asset("asset1", 10L)),
            listOf(spend("big", 5_000_000L), spend("small", 100_000L)),
        )
        val inputs = (out.single().result as ForeignAssetTransferPlan.Result.Ok).plan.inputs
        assertTrue("the small input funded it", inputs.any { it.txid == "small" })
        assertTrue("the big one was left for the next asset", inputs.none { it.txid == "big" })
    }

    @Test fun `several small inputs are combined when no single one suffices`() {
        val out = plan(
            listOf(asset("asset1", 10L)),
            listOf(spend("a", 40_000L), spend("b", 40_000L), spend("c", 40_000L)),
        )
        val inputs = (out.single().result as ForeignAssetTransferPlan.Result.Ok).plan.inputs
        assertTrue("more than one fee input was needed", inputs.size >= 3)
    }

    /** An asset whose quantity we could not read is refused before it reaches the pool, so it
     *  cannot spend fee money on a transfer that would be built around a guess. */
    @Test fun `an unknown quantity is refused without touching the pool`() {
        val out = plan(
            listOf(asset("unknown", 0L), asset("asset2", 5L)),
            listOf(spend("f1", 100_000L)),
        )
        val first = out[0].result as ForeignAssetTransferPlan.Result.Refused
        assertEquals(ForeignAssetTransferPlan.Reason.UNKNOWN_QUANTITY, first.reason)
        assertTrue("the funded asset still got its money", out[1].result is ForeignAssetTransferPlan.Result.Ok)
    }
}
