package io.digibyte.ui.asset

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level WIRING gate for one rule: the asset confirmation is drawn from an
 * `ApprovedSend.Asset`, and that same object is what the asset send receives.
 *
 * What the approval holds, how it spells its quantity and which approval a send may be made for
 * are behaviour, and are tested as behaviour in `core` (`AssetQuantityTest`,
 * `AssetSendBindingTest`). This gate covers the part no behaviour test can reach — no `app` unit
 * test builds a ViewModel or a composable — which is that the screen and the view model actually
 * use the object. A confirmation fed from the quantity field again, or a send that reads the
 * field again after the credential prompt, would compile and look right.
 *
 * `SendAmountBindingWiringTest` is the same gate for the DGB and DigiDollar sends.
 */
class AssetSendBindingWiringTest {

    private val uiRoot = File("src/main/java/io/digibyte/ui")

    private val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    /** The file as code: block comments and line comments say nothing about what it does. */
    private fun source(rel: String): String =
        File(uiRoot, rel).let {
            assertTrue("$rel is missing — this gate is watching a file that moved", it.exists())
            blockComment.replace(it.readText(), "").lines().joinToString("\n") { l -> l.substringBefore("//") }
        }

    private val screen get() = source("asset/AssetSendScreen.kt")
    private val viewModel get() = source("asset/AssetViewModel.kt")

    /** The text from [from] up to the next occurrence of [until]. */
    private fun String.section(from: String, until: String): String {
        val start = indexOf(from)
        assertTrue("cannot find `$from` — the gate is blind, not clean", start >= 0)
        val end = indexOf(until, start + from.length)
        assertTrue("cannot find `$until` after `$from` — the gate is blind, not clean", end > start)
        return substring(start, end)
    }

    /** The Review button's click handler: from its `onClick` to the rule check that enables it. */
    private fun String.reviewClick(): String {
        val end = indexOf("enabled = ruleCheck.allowsSend")
        assertTrue("cannot find the Review button's rule check — the gate is blind, not clean", end >= 0)
        val start = lastIndexOf("onClick = {", end)
        assertTrue("cannot find the Review button's click handler — the gate is blind, not clean", start >= 0)
        return substring(start, end)
    }

    /** The confirmation as the screen calls it (the call comes before the declaration in the file). */
    private fun confirmationCall() = screen.section("AssetSendConfirmDialog(", "onCancel")

    private fun confirmationDialog() = screen.section("private fun AssetSendConfirmDialog(", "private fun SendResultBanner(")

    private fun send() = viewModel.section("fun sendAssetTransfer(", "sealed class SendState")

    @Test fun `the asset confirmation is drawn from the approved object`() {
        val call = confirmationCall()
        assertTrue("the confirmation's quantity is not the approved object's text", call.contains("quantityText = approved.amountText"))
        assertTrue("the confirmation's cost rows are not worked out from the approved units", call.contains("quantityUnits = approved.units"))
        assertTrue("the confirmation's address is not the approved object's", call.contains("recipientAddress = approved.address"))
        assertTrue("the confirmation's fee is not the approved object's", call.contains("feeSats = approved.feeEstimateSats"))
        assertTrue("the send is not handed the approved object", call.contains("viewModel.sendAssetTransfer(approved)"))
        for (field in listOf("quantityInput", "recipientAddress = recipientAddress", "feeRatePerKb", "estimatedFeeSat")) {
            assertFalse("the confirmation reads `$field` instead of what was approved", call.contains(field))
        }
        assertEquals(
            "the confirmation is declared once and called once — from the approval",
            2, Regex(Regex.escape("AssetSendConfirmDialog(")).findAll(screen).count(),
        )
    }

    @Test fun `the confirmation dialog reads no quantity text`() {
        val dialog = confirmationDialog()
        for (reRead in listOf("quantityInput", "AssetQuantity.parse(", "parseQuantityToInternal(", "toBigDecimal")) {
            assertFalse("the dialog reads `$reRead`: what it shows must come from the approval", dialog.contains(reRead))
        }
        assertTrue("the dialog's quantity row is not the approved text", dialog.contains("quantityText"))
        assertTrue("the dialog's cost rows are not worked out from the approved units", dialog.contains("units = quantityUnits"))
    }

    @Test fun `the asset send takes the approved object and reads no field`() {
        val send = send()
        val reReads = listOf(
            "quantityInput", "scaleToInternalUnits(", "AssetQuantity.parse(", "toBigDecimal",
            "feeRatePerKb.value", "estimatedFeeSat.value", "customFeeInput.value", "isCustomFee.value",
            "metadata?.decimals",
        )
        for (field in reReads) {
            assertFalse("sendAssetTransfer() reads `$field` again instead of using what was approved", send.contains(field))
        }
        assertTrue(
            "sendAssetTransfer() does not take the approved object",
            send.contains("fun sendAssetTransfer(approved: ApprovedSend.Asset)"),
        )
        val handedOn = listOf(
            "assetId = approved.assetId", "quantity = approved.units",
            "toAddress = approved.address", "feePerKb = approved.feePerKb",
        )
        for (use in handedOn) {
            assertTrue("sendAssetTransfer() does not hand on `$use`", send.contains(use))
        }
        assertTrue(
            "sendAssetTransfer() does not hold the asset's divisibility against the one the approval was read and shown at",
            send.contains("divisibilityOf(asset) != approved.divisibility"),
        )
    }

    /** Only the approval on screen may be sent, and only once — decided before anything else is looked at. */
    @Test fun `the asset send first asks whether this approval is the one on screen`() {
        val send = send()
        val claim = send.indexOf("confirmation.claim(approved)")
        assertTrue("sendAssetTransfer() does not ask whether this approval is the one on screen", claim >= 0)
        assertTrue(
            "sendAssetTransfer() does not stop when this approval is not the one on screen",
            Regex("""if \(!confirmation\.claim\(approved\)\) \{[^}]*\breturn\b""").containsMatchIn(send),
        )
        for (later in listOf("selectedAsset.value", "_ruleCheck.value", "_sendState.value = SendState.Sending", "assetManager.sendAsset(")) {
            val at = send.indexOf(later)
            assertTrue("cannot find `$later` in sendAssetTransfer() — the gate is blind, not clean", at >= 0)
            assertTrue("sendAssetTransfer() reaches `$later` before it has asked whether this approval is the one on screen", claim < at)
        }
    }

    @Test fun `Review is the one place the quantity text becomes a send`() {
        val click = screen.reviewClick()
        assertTrue(
            "Review does not hand the form to the view model to be approved",
            click.contains("viewModel.requestConfirm(recipientAddress, quantityInput)"),
        )
        for (own in listOf("AssetQuantity.parse(", "parseQuantityToInternal(", "toBigDecimal", "showConfirmDialog")) {
            assertFalse("Review decides with `$own` instead of the approval", click.contains(own))
        }
        val vm = viewModel
        assertTrue(
            "requestConfirm does not build the approved object",
            vm.contains("fun requestConfirm(") &&
                vm.section("fun requestConfirm(", "fun cancelConfirm(").contains("ApprovedSend.asset("),
        )
        assertTrue(
            "the confirmation is not the view model's approval",
            screen.contains("viewModel.approval.collectAsStateWithLifecycle()"),
        )
    }

    /**
     * One reading of quantity text in the whole wallet: `AssetQuantity` in `core`, which shares its
     * separator rule with the DGB and DigiDollar fields. Every place that reads a quantity — the
     * cost preview as it is typed, MAX, Review — then reads the same number from the same text.
     */
    @Test fun `neither file reads quantity text with a parser of its own`() {
        val ownParsing = Regex("""toBigDecimal(OrNull)?\(|movePointRight\(|\.to(Long|Int)OrNull\(""")
        val offenders = listOf("asset/AssetSendScreen.kt", "asset/AssetViewModel.kt").flatMap { rel ->
            source(rel).lines()
                .filter { ownParsing.containsMatchIn(it) }
                .map { "$rel: ${it.trim()}" }
        }
        assertTrue("quantity text read outside AssetQuantity:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /**
     * GUARD. The two gates in front of an asset send stay where they are: the credential prompt
     * before the view model is called, and the transfer-rule check before the asset manager is.
     */
    @Test fun `the credential prompt and the transfer-rule check still come before the send`() {
        val call = confirmationCall()
        val prompt = call.indexOf("spendAuth.authorize(")
        val handOff = call.indexOf("viewModel.sendAssetTransfer(")
        assertTrue("cannot find the credential prompt or the send in the confirmation — the gate is blind, not clean", prompt >= 0 && handOff >= 0)
        assertTrue("the send is called before the credential prompt", prompt < handOff)

        val send = send()
        val ruleCheck = send.indexOf("_ruleCheck.value.allowsSend")
        val manager = send.indexOf("assetManager.sendAsset(")
        assertTrue("cannot find the transfer-rule check or the asset manager call — the gate is blind, not clean", ruleCheck >= 0 && manager >= 0)
        assertTrue("the asset manager is called before the transfer-rule check", ruleCheck < manager)
        assertTrue("Review is no longer held back by the transfer-rule check", screen.contains("enabled = ruleCheck.allowsSend"))
    }

    @Test fun `the gate can see the files it reads`() {
        assertTrue(screen.contains("fun AssetSendScreen(") && screen.contains("AssetSendConfirmDialog("))
        assertTrue(viewModel.contains("class AssetViewModel") && viewModel.contains("fun sendAssetTransfer("))
    }
}
