package io.digibyte.ui.asset

import io.digibyte.core.asset.send.AssetTransferPlan
import io.digibyte.core.asset.send.AssetTransferPlanner
import io.digibyte.core.asset.send.DA_MARKER_SATS
import io.digibyte.core.asset.send.PlannedOutput
import io.digibyte.core.db.entity.UtxoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The confirmation's markers and total are the planned transfer's, run through the real planner.
 *
 * The cases are the two ways the displayed balance and the plan part: a coin holding exactly the
 * amount sent while the balance is larger (no change marker, though the balance says units stay),
 * and a "whole balance" send whose coins hold more than the balance shows (a change marker the
 * balance would not predict).
 */
class AssetSendCostTest {

    private val feePerKb = 100_000L
    private val assetId = "La2ih1bm2u4dVcWGNHKesrY132xTDtKShnYQch"

    private fun assetCoin(txid: Char, qty: Long) = UtxoEntity(
        txid = txid.toString().repeat(64), vout = 0, scriptPubKey = ByteArray(0), satoshis = DA_MARKER_SATS,
        blockHeight = 100L, isAsset = true, assetId = assetId, assetQuantity = qty,
    )

    private fun feeCoin(txid: Char, sats: Long) = UtxoEntity(
        txid = txid.toString().repeat(64), vout = 0, scriptPubKey = ByteArray(0), satoshis = sats, blockHeight = 100L,
    )

    private fun plan(assetCoins: List<UtxoEntity>, units: Long): AssetTransferPlan =
        when (val r = AssetTransferPlanner.plan(assetCoins, listOf(feeCoin('f', 10_000_000L)), units, feePerKb)) {
            is AssetTransferPlanner.Result.Ready -> r.plan
            is AssetTransferPlanner.Result.Refused -> { fail("refused: ${r.message}"); throw AssertionError() }
        }

    @Test fun `a coin holding exactly the amount sent plans no change marker, whatever the balance says`() {
        // The displayed balance is 2 (two coins of 1); the send of 1 spends one whole coin.
        val plan = plan(listOf(assetCoin('a', 1), assetCoin('b', 1)), units = 1)
        val cost = AssetSendCost.of(plan)

        assertFalse("a change marker for a coin spent whole", cost.hasChangeMarker)
        assertEquals(0L, cost.changeMarkerSats)
        assertEquals(DA_MARKER_SATS, cost.recipientMarkerSats)
        assertEquals(plan.paidFeeSats, cost.feeSats)
        assertEquals(DA_MARKER_SATS + plan.paidFeeSats, cost.totalSats)
    }

    @Test fun `coins holding more than the balance shows plan the change marker and count it in the total`() {
        // The balance on screen says 1, so the user sends "all" of it; the planner's coin holds 5.
        val plan = plan(listOf(assetCoin('c', 5)), units = 1)
        val cost = AssetSendCost.of(plan)

        assertTrue("no change marker though 4 units come back", cost.hasChangeMarker)
        assertEquals(DA_MARKER_SATS, cost.changeMarkerSats)
        assertEquals(2 * DA_MARKER_SATS + plan.paidFeeSats, cost.totalSats)
    }

    @Test fun `the cost is the plan's outputs and fee, line for line`() {
        val plan = plan(listOf(assetCoin('d', 3), assetCoin('e', 2)), units = 4)
        val cost = AssetSendCost.of(plan)

        val markers = plan.outputs.filter {
            it.role == PlannedOutput.Role.RECIPIENT_MARKER || it.role == PlannedOutput.Role.ASSET_CHANGE_MARKER
        }.sumOf { it.sats }
        assertEquals(markers + plan.paidFeeSats, cost.totalSats)
        assertEquals(plan.outputs.any { it.role == PlannedOutput.Role.ASSET_CHANGE_MARKER }, cost.hasChangeMarker)
    }
}
