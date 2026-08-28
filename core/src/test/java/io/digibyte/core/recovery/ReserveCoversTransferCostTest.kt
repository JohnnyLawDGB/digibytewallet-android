package io.digibyte.core.recovery

import io.digibyte.core.asset.send.DA_MARKER_SATS
import io.digibyte.core.reconcile.UtxoEntry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reserve has to cover what the transfer actually costs.
 *
 * ## Two numbers that were never checked against each other
 *
 * [AssetFeeReserve] holds back [AssetFeeReserve.DEFAULT_FEE_PER_ASSET] per asset and its own
 * comment calls that "deliberately an over-estimate". [ForeignAssetTransferPlan] then prices the
 * transfer from its real shape via `AssetFeeEstimator`, and needs enough left over for a
 * non-dust change output on top — without one, the OP_RETURN is the last output and residual
 * units are burned.
 *
 * Nothing connected the two. When the reserve was written there was no transfer to price against,
 * so "enough" was a guess. If it guesses low, the wallet holds coins back, tells the user they
 * were kept so the assets could move, and then cannot move them — the exact failure the reserve
 * exists to prevent, dressed as the fix for it.
 *
 * This is the seam test: whatever either side is changed to, the reserve must fund the plan.
 */
class ReserveCoversTransferCostTest {

    private val dest = "dgb1qgapugthjpsqnh80jn7un0f34u2qusl8y7gg76f"
    private val feePerKb = 100_000L
    private val script = "76a914aabbccddeeff00112233445566778899aabbccdd88ac"

    private fun utxo(id: String, sats: Long) = UtxoEntry(
        txid = id, vout = 0, amountSatoshi = sats, address = "D7Vx$id",
        blockHeight = 24_000_000L, scriptPubKeyHex = script,
    )

    private fun spend(u: UtxoEntry) = ForeignAssetTransferPlan.Spend(
        txid = u.txid, vout = u.vout, amountSat = u.amountSatoshi,
        scriptPubKeyHex = script, chain = 0, index = 0,
    )

    private val assetSpend = ForeignAssetTransferPlan.Spend(
        txid = "a55e7", vout = 0, amountSat = DA_MARKER_SATS,
        scriptPubKeyHex = script, chain = 0, index = 0,
    )

    /**
     * The tight case the reserve is sized for: DGB sitting in outputs just big enough that the
     * reserve stops as soon as it clears its target. If the target is short, this is where it
     * shows.
     */
    @Test fun `what the reserve holds back funds the transfer it was held back for`() {
        // Plain DGB in 0.5 DGB pieces: individually economic to spend, but small enough that
        // the reserve stops as soon as it clears its target rather than sweeping up one big
        // output that would hide a shortfall.
        val sweepable = (1..40).map { utxo("dgb$it", 50_000_000L) }

        val reserve = AssetFeeReserve.reserve(sweepable = sweepable, assetCount = 1)
        assertTrue("the reserve held something back", reserve.reserved.isNotEmpty())

        val result = ForeignAssetTransferPlan.build(
            assetInput = assetSpend,
            assetUnits = 10L,
            feeInputs = reserve.reserved.map(::spend),
            destAddress = dest,
            feePerKb = feePerKb,
        )
        assertTrue(
            "the reserve held ${reserve.reserved.sumOf { it.amountSatoshi }} sats and the " +
                "transfer refused it: $result",
            result is ForeignAssetTransferPlan.Result.Ok,
        )
    }

    /** Same seam with several assets: each transfer must be fundable from the shared pool. */
    @Test fun `the reserve funds every asset it was sized for`() {
        val sweepable = (1..60).map { utxo("dgb$it", 50_000_000L) }
        val reserve = AssetFeeReserve.reserve(sweepable = sweepable, assetCount = 3)

        val assets = (1..3).map {
            ForeignAssetTransferBatch.AssetItem(assetSpend.copy(txid = "asset$it"), 10L)
        }
        val planned = ForeignAssetTransferBatch.plan(
            assets, reserve.reserved.map(::spend), dest, feePerKb,
        )
        val funded = planned.count { it.result is ForeignAssetTransferPlan.Result.Ok }
        assertTrue(
            "reserved ${reserve.reserved.sumOf { it.amountSatoshi }} sats for 3 assets but only " +
                "funded $funded: $planned",
            funded == 3,
        )
    }

    /**
     * The reserve rounds up to whole UTXOs, so a wallet whose DGB sits in ONE large output has
     * plenty. The failure only appears when the pieces are small, which is why the cases above
     * use small ones — a test built on a single fat UTXO would pass while the seam was broken.
     */
    /**
     * The honest limit. At 100 sat/byte an input costs about 15,000 sats to spend, so a wallet
     * whose DGB sits only in 10,000-sat pieces cannot fund a transfer out of them at any input
     * count — each one added costs more than it brings. That is economics, not a defect, and the
     * wallet must say so plainly rather than appear to have kept enough back.
     */
    @Test fun `a wallet of uneconomic dust is refused with a reason, not stranded silently`() {
        val dust = (1..40).map { utxo("dust$it", 10_000L) }
        val reserve = AssetFeeReserve.reserve(sweepable = dust, assetCount = 1)
        val result = ForeignAssetTransferPlan.build(
            assetInput = assetSpend, assetUnits = 10L,
            feeInputs = reserve.reserved.map(::spend), destAddress = dest, feePerKb = feePerKb,
        )
        val refused = result as? ForeignAssetTransferPlan.Result.Refused
        assertTrue("expected a stated refusal, got $result", refused != null)
        assertTrue(
            "the refusal must say how much was needed",
            refused!!.detail.contains("need"),
        )
    }

    @Test fun `one large output is comfortably enough`() {
        val reserve = AssetFeeReserve.reserve(listOf(utxo("fat", 100_000_000L)), assetCount = 1)
        val result = ForeignAssetTransferPlan.build(
            assetInput = assetSpend, assetUnits = 10L,
            feeInputs = reserve.reserved.map(::spend), destAddress = dest, feePerKb = feePerKb,
        )
        assertTrue(result is ForeignAssetTransferPlan.Result.Ok)
    }
}
