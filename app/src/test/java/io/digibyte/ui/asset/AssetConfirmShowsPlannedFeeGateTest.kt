package io.digibyte.ui.asset

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate (B231): the asset confirmation shows the fee of the transfer as planned, and the
 * send pays no more than that. Before, the confirmation showed a flat 613 vB estimate (61,300 sat)
 * while a send consolidating nine coins paid 161,200 sat (Note 8, 2026-09-23). Planning needs the
 * native wallet, so the wiring is pinned here; the planner and the fee rule are covered on the JVM
 * by AssetTransferPlanTest, and the whole path on the device.
 */
class AssetConfirmShowsPlannedFeeGateTest {

    private val vm = File("src/main/java/io/digibyte/ui/asset/AssetViewModel.kt").readText()
    private val mgr = File("../core/src/main/java/io/digibyte/core/asset/AssetManager.kt").readText()

    private fun String.body(start: String, end: String): String {
        val i = indexOf(start); val j = indexOf(end, i + 1)
        assertTrue("cannot find `$start` — the gate is blind, not clean", i >= 0 && j > i)
        return substring(i, j)
    }

    @Test fun `review opens the confirmation with the planned fee, never the flat estimate`() {
        val confirm = vm.body("fun requestConfirm(", "fun cancelConfirm(")
        assertTrue(confirm.contains("assetManager.previewAssetTransferFee("))
        assertTrue(confirm.contains("confirmation.open(approved.withPlannedFee(planned.plan.paidFeeSats))"))
        assertTrue("the confirmation is opened with an unplanned approval", !confirm.contains("confirmation.open(approved)"))
    }

    @Test fun `the send is capped at the approved fee`() {
        val send = vm.body("fun sendAssetTransfer(", "sealed class SendState")
        assertTrue(send.contains("maxFeeSats = approved.feeEstimateSats"))
    }

    @Test fun `sendAsset signs only a plan within the approved fee`() {
        val send = mgr.body("suspend fun sendAsset(", "NativeBridge.buildAndSignAssetTransferTx(")
        assertTrue("sendAsset does not plan through planAssetTransfer", send.contains("planAssetTransfer(assetId, quantity, toAddress, feePerKb)"))
        assertTrue("sendAsset signs without checking the approved fee",
            send.contains("AssetTransferPlanner.feeWithinApproval(plan.paidFeeSats, maxFeeSats)"))
        val preview = mgr.body("suspend fun previewAssetTransferFee(", "suspend fun sendAsset(")
        assertTrue("the preview plans some other way than the send does", preview.contains("planAssetTransfer(assetId, quantity, toAddress, feePerKb)"))
    }
}
