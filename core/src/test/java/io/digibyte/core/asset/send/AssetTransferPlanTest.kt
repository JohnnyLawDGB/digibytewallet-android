package io.digibyte.core.asset.send

import io.digibyte.core.db.entity.UtxoEntity
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

    /** The whole balance of an asset held in one output: no units come back, so one marker is
     *  emitted, and inputs minus that marker minus the DGB change is the fee. */
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
            listOf(PlannedOutput.Role.RECIPIENT_MARKER, PlannedOutput.Role.ASSET_DATA, PlannedOutput.Role.DGB_CHANGE),
            plan.roles(),
        )
        val inputs = plan.inputs.sumOf { it.satoshis }
        val change = plan.sats(PlannedOutput.Role.DGB_CHANGE)
        assertEquals(
            "inputs - one marker - change must equal the fee",
            plan.estimatedFeeSats,
            inputs - DA_MARKER_SATS - change,
        )
        assertEquals(plan.estimatedFeeSats, plan.paidFeeSats)
    }

    /** The asset input alone covers the fee and the marker, so no fee input is pulled; the
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

    /** Two fee coins that cover the fee for their own size, with a remainder below the change
     *  dust threshold. The remainder goes to the fee — and it is the only thing the send pays
     *  beyond its estimate. At 100 sat/byte the shape (one asset input, two fee inputs, no asset
     *  change) estimates 69,600 sat, so 60,000 + 10,000 leaves 400; a second marker budgeted on
     *  top would need 75,600 and the send would be refused. */
    @Test fun an_exact_send_whose_remainder_is_dust_pays_only_that_remainder() {
        val plan = ready(
            AssetTransferPlanner.plan(
                assetUtxos = listOf(assetUtxo("a", DA_MARKER_SATS, 100)),
                dgbUtxos = listOf(dgbUtxo("d1", 60_000), dgbUtxo("d2", 10_000)),
                quantity = 100,
                feePerKb = feePerKb,
            )
        )

        assertEquals(2, plan.dgbInputs.size)
        assertEquals(listOf(PlannedOutput.Role.RECIPIENT_MARKER, PlannedOutput.Role.ASSET_DATA), plan.roles())
        val remainder = plan.inputs.sumOf { it.satoshis } - DA_MARKER_SATS - plan.estimatedFeeSats
        assertTrue("remainder $remainder", remainder in 0..DGB_CHANGE_DUST_THRESHOLD)
        assertEquals(plan.estimatedFeeSats + remainder, plan.paidFeeSats)
    }

    /** GUARD (passes before and after): a partial send emits the recipient marker and the
     *  asset-change marker, budgets both, and pays the fee it estimated. */
    @Test fun guard_a_partial_send_emits_and_budgets_both_markers() {
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
                PlannedOutput.Role.ASSET_CHANGE_MARKER, PlannedOutput.Role.DGB_CHANGE,
            ),
            plan.roles(),
        )
        assertEquals(DA_MARKER_SATS, plan.sats(PlannedOutput.Role.ASSET_CHANGE_MARKER))
        assertEquals(plan.estimatedFeeSats, plan.paidFeeSats)
    }
}
