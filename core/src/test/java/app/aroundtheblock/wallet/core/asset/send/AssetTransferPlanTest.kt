package app.aroundtheblock.wallet.core.asset.send

import app.aroundtheblock.wallet.core.db.entity.UtxoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * An asset send budgets exactly the markers it emits: every input's value minus every output's
 * value is the fee the size estimate asked for, plus at most a DGB remainder too small to be an
 * output of its own.
 *
 * [AssetTransferPlanner.plan] is the half of AssetManager.sendAsset that decides the numbers:
 * the send signs [AssetTransferPlan.inputs] and turns [AssetTransferPlan.outputs] into addresses
 * in that order, with these values. Nothing here is a copy of it.
 */
class AssetTransferPlanTest {

    private val feePerKb = 100_000L

    private fun assetUtxo(txid: String, sats: Long, qty: Long) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = ByteArray(0), satoshis = sats, blockHeight = 100L,
        isAsset = true, assetId = "La2ih1bm2u4dVcWGNHKesrY132xTDtKShnYQch", assetQuantity = qty,
    )

    private fun dgbUtxo(txid: String, sats: Long) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = ByteArray(0), satoshis = sats, blockHeight = 100L,
    )

    private fun ready(r: AssetTransferPlanner.Result): AssetTransferPlan = when (r) {
        is AssetTransferPlanner.Result.Ready -> r.plan
        is AssetTransferPlanner.Result.Refused -> { fail("refused: ${r.message}"); throw AssertionError() }
    }

    private fun AssetTransferPlan.roles() = outputs.map { it.role }
    private fun AssetTransferPlan.sats(role: PlannedOutput.Role) = outputs.single { it.role == role }.sats

    /** The whole balance of an asset held in one output: no units come back, but both markers are
     *  emitted (the recipient's, and the last output back to the source), and inputs minus both
     *  markers minus the DGB change is the fee. */
    @Test fun an_exact_send_pays_the_fee_it_estimated() {
        val plan = ready(
            AssetTransferPlanner.plan(
                assetUtxos = listOf(assetUtxo("a", DA_MARKER_SATS, 100)),
                dgbUtxos = listOf(dgbUtxo("d", 1_000_000)),
                quantity = 100,
                feePerKb = feePerKb,
            )
        )

        assertEquals(
            listOf(
                PlannedOutput.Role.RECIPIENT_MARKER, PlannedOutput.Role.ASSET_DATA,
                PlannedOutput.Role.DGB_CHANGE, PlannedOutput.Role.ASSET_CHANGE_MARKER,
            ),
            plan.roles(),
        )
        val inputs = plan.inputs.sumOf { it.satoshis }
        val change = plan.sats(PlannedOutput.Role.DGB_CHANGE)
        assertEquals(
            "inputs - two markers - change must equal the fee",
            plan.estimatedFeeSats,
            inputs - 2 * DA_MARKER_SATS - change,
        )
        assertEquals(plan.estimatedFeeSats, plan.paidFeeSats)
    }

    /** The asset input alone covers the fee and the markers, so no fee input is pulled; the
     *  change still carries everything but the fee. */
    @Test fun an_exact_send_with_no_fee_input_pays_the_fee_it_estimated() {
        val plan = ready(
            AssetTransferPlanner.plan(
                assetUtxos = listOf(assetUtxo("a", 200_000, 100)),
                dgbUtxos = listOf(dgbUtxo("d", 1_000_000)),
                quantity = 100,
                feePerKb = feePerKb,
            )
        )

        assertTrue("no fee input is needed", plan.dgbInputs.isEmpty())
        assertEquals(plan.estimatedFeeSats, plan.paidFeeSats)
    }

    /** A fee coin that covers the fee and both markers with a remainder below the change dust
     *  threshold. The remainder goes to the fee, it is the only thing the send pays beyond its
     *  estimate, and no DGB change output is emitted. The coin is found by search (the planner's
     *  bootstrap estimate is more conservative than its final one, so a hand-sized coin is
     *  fragile). */
    @Test fun an_exact_send_whose_remainder_is_dust_pays_only_that_remainder() {
        // Two fee coins, as in the original shape: the planner's bootstrap estimate sits above
        // its final one by more than the dust threshold, so a single coin always leaves change.
        val plan = (50_000L..120_000L step 50L).asSequence().mapNotNull { total ->
            (AssetTransferPlanner.plan(
                assetUtxos = listOf(assetUtxo("a", DA_MARKER_SATS, 100)),
                dgbUtxos = listOf(dgbUtxo("d1", total - 10_000L), dgbUtxo("d2", 10_000L)),
                quantity = 100,
                feePerKb = feePerKb,
            ) as? AssetTransferPlanner.Result.Ready)?.plan
        }.firstOrNull { p -> p.outputs.none { it.role == PlannedOutput.Role.DGB_CHANGE } }
            ?: run { fail("no coin value gave a dust remainder"); throw AssertionError() }

        assertEquals(
            listOf(PlannedOutput.Role.RECIPIENT_MARKER, PlannedOutput.Role.ASSET_DATA, PlannedOutput.Role.ASSET_CHANGE_MARKER),
            plan.roles(),
        )
        val remainder = plan.paidFeeSats - plan.estimatedFeeSats
        assertTrue("remainder $remainder", remainder in 0..DGB_CHANGE_DUST_THRESHOLD)
    }

    /** A partial send emits the recipient marker and the last output back to the source, budgets
     *  both, and pays the fee it estimated. */
    @Test fun a_partial_send_emits_and_budgets_both_markers() {
        val plan = ready(
            AssetTransferPlanner.plan(
                assetUtxos = listOf(assetUtxo("a", DA_MARKER_SATS, 100)),
                dgbUtxos = listOf(dgbUtxo("d", 1_000_000)),
                quantity = 40,
                feePerKb = feePerKb,
            )
        )

        assertEquals(
            listOf(
                PlannedOutput.Role.RECIPIENT_MARKER, PlannedOutput.Role.ASSET_DATA,
                PlannedOutput.Role.DGB_CHANGE, PlannedOutput.Role.ASSET_CHANGE_MARKER,
            ),
            plan.roles(),
        )
        assertEquals(DA_MARKER_SATS, plan.sats(PlannedOutput.Role.ASSET_CHANGE_MARKER))
        assertEquals(plan.estimatedFeeSats, plan.paidFeeSats)
    }

    /** B231, the Note 8 shape: a whole balance held across eight outputs is sent by consolidating
     *  them, and that send pays for every input — far above the flat 613 vB (61,300 sat) the
     *  confirmation used to show. The plan's own fee is the one the confirmation must show. */
    @Test fun a_consolidating_send_pays_far_more_than_the_flat_estimate() {
        val carriers = (1..8).map { assetUtxo("a$it", 600L, 1L) }
        val plan = ready(AssetTransferPlanner.plan(carriers, listOf(dgbUtxo("f", 5_000_000L)), 8L, feePerKb))
        val flatEstimate = 613L * feePerKb / 1000
        assertTrue("paid ${plan.paidFeeSats} vs flat $flatEstimate", plan.paidFeeSats > 2 * flatEstimate)
        assertEquals(plan.estimatedFeeSats, plan.paidFeeSats)
    }

    /** B231: a plan may be signed under an approval only if it pays no more than was approved. */
    @Test fun a_plan_is_signed_only_at_or_under_the_approved_fee() {
        assertTrue(AssetTransferPlanner.feeWithinApproval(paidFeeSats = 161_200L, approvedFeeSats = 161_200L))
        assertTrue(AssetTransferPlanner.feeWithinApproval(paidFeeSats = 61_300L, approvedFeeSats = 161_200L))
        assertTrue(!AssetTransferPlanner.feeWithinApproval(paidFeeSats = 161_200L, approvedFeeSats = 61_300L))
        assertTrue(!AssetTransferPlanner.feeWithinApproval(paidFeeSats = 1L, approvedFeeSats = 0L))
        assertTrue(!AssetTransferPlanner.feeWithinApproval(paidFeeSats = -1L, approvedFeeSats = 100L))
    }
}
