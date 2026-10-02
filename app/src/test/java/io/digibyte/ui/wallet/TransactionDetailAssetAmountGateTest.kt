package io.digibyte.ui.wallet

import io.digibyte.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the details of an asset transaction lead with the asset and its quantity.
 *
 * The details screen showed only the DGB amount, which for an asset send is the marker value
 * ("-0.00006000 DGB") and says nothing about what was sent. The screen shares the activity list's
 * view model, which already holds each row's kind and asset amount; it now reads them, shows the
 * asset amount as the headline and the DGB amount under it. The choice is a pure function
 * ([assetAmountForDetail]), tested in TransactionDetailAssetAmountTest.
 */
class TransactionDetailAssetAmountGateTest {

    private val gate = KotlinSourceGate.of(
        File("src/main/java/io/digibyte/ui/wallet/TransactionDetailScreen.kt").readText()
    )

    @Test fun `the screen reads the list's kinds and asset amounts`() {
        val screen = gate.range("fun TransactionDetailScreen(", "fun TransactionDetailContent(")
        assertTrue("cannot find TransactionDetailScreen — the gate is blind, not clean", screen != null)
        val code = gate.code.substring(screen!!)
        assertTrue("the details screen does not read txKinds", code.contains("viewModel.txKinds"))
        assertTrue("the details screen does not read txTypedAmounts", code.contains("viewModel.txTypedAmounts"))
    }

    @Test fun `the content is handed the asset amount the list shows`() {
        val calls = gate.calls("TransactionDetailContent")
        assertEquals("expected one TransactionDetailContent( call", 1, calls.size)
        val asset = calls.single().named["assetAmount"].orEmpty()
        assertTrue("the content is not handed the asset amount: `$asset`", asset.startsWith("assetAmountForDetail("))
    }
}
