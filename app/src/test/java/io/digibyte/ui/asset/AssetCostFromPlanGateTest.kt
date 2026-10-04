package io.digibyte.ui.asset

import io.digibyte.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the asset-change marker and the total on the confirmation come from the transfer as
 * planned — the same plan whose fee the confirmation shows and the send signs — never from the
 * displayed balance.
 *
 * Comparing the amount with the displayed balance (a sum over every coin) once decided the
 * asset-change marker; the planner's own outputs decide it. The planner now emits that marker on
 * every send, so before a plan exists the form shows both markers. The arithmetic is tested on the
 * JVM in AssetSendCostTest against the real planner; this pins that the screen uses it.
 */
class AssetCostFromPlanGateTest {

    private val screen = KotlinSourceGate.of(File("src/main/java/io/digibyte/ui/asset/AssetSendScreen.kt").readText())
    private val vm = KotlinSourceGate.of(File("src/main/java/io/digibyte/ui/asset/AssetViewModel.kt").readText())

    private fun KotlinSourceGate.function(name: String, next: String): IntRange {
        val range = range("fun $name(", next)
        assertTrue("cannot find `fun $name(` — the gate is blind, not clean", range != null)
        return range!!
    }

    @Test fun `no marker is decided by comparing the amount with the displayed balance`() {
        val card = screen.function("CostPreviewCard", "fun CostRow(")
        val decided = Regex("""until\s+ownedAsset\s*\.\s*quantity|<\s*ownedAsset\s*\.\s*quantity""")
            .containsMatchIn(screen.code.substring(card))
        assertTrue("the cost card decides the change marker from the displayed balance", !decided)
    }

    @Test fun `before a plan exists the card shows the markers every send emits`() {
        // The planner emits the asset-change marker on every send (last output, to the source), so
        // the form's estimate names it too rather than claiming none.
        val card = screen.function("CostPreviewCard", "fun CostRow(")
        val code = screen.code.substring(card)
        assertTrue("the form's cost card does not fall back to the every-send markers",
            code.contains("cost ?: AssetSendCost.beforePlan(feeSats)"))
        assertTrue("the form's cost card still claims no change marker before a plan",
            !Regex("""changeMarkerSats\s*\?:\s*0L""").containsMatchIn(code))
    }

    @Test fun `the confirmation draws its cost card from the planned transfer`() {
        val dialog = screen.function("AssetSendConfirmDialog", "fun SendResultBanner(")
        val cards = screen.calls("CostPreviewCard", dialog)
        assertEquals("expected one cost card in the confirmation", 1, cards.size)
        val cost = cards.single().named["cost"]
        assertTrue("the confirmation's cost card is given no planned cost", cost != null && cost != "null")
    }

    @Test fun `the planned cost is read from the plan the confirmation opens with`() {
        val confirm = vm.function("requestConfirm", "fun cancelConfirm(")
        val code = vm.code.substring(confirm)
        assertTrue("requestConfirm does not take the cost from the plan",
            code.contains("AssetSendCost.of(planned.plan)"))
        // The fee half of the same plan, as AssetConfirmShowsPlannedFeeGateTest pins it.
        assertTrue(code.contains("confirmation.open(approved.withPlannedFee(planned.plan.paidFeeSats))"))
    }
}
