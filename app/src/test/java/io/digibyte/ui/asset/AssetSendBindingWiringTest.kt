package io.digibyte.ui.asset

import io.digibyte.ui.KotlinSourceGate
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
 * Each value it pins is compared WHOLE, in code and not in comments or literals: an argument is
 * the approved object's value and nothing more, a condition is the one check and nothing more,
 * and a call the gate reads is the only call of its kind in the file.
 *
 * `SendAmountBindingWiringTest` is the same gate for the DGB and DigiDollar sends.
 */
class AssetSendBindingWiringTest {

    private val uiRoot = File("src/main/java/io/digibyte/ui")

    /** The file as code: comments and the text inside literals say nothing about what it does. */
    private fun gate(rel: String): KotlinSourceGate =
        File(uiRoot, rel).let {
            assertTrue("$rel is missing — this gate is watching a file that moved", it.exists())
            KotlinSourceGate.of(it.readText())
        }

    private fun source(rel: String): String = gate(rel).code

    private val screenGate get() = gate("asset/AssetSendScreen.kt")
    private val viewModelGate get() = gate("asset/AssetViewModel.kt")
    private val screen get() = screenGate.code
    private val viewModel get() = viewModelGate.code

    /** The one call of [callee] in the file. None, or a second one, and the gate says so. */
    private fun KotlinSourceGate.theCall(callee: String): KotlinSourceGate.Call {
        val found = calls(callee)
        assertEquals("`$callee(` is called ${found.size} times — the gate reads one call, and it has to be the only one", 1, found.size)
        return found.single()
    }

    /** The one `if` inside [within] whose whole condition is [condition]: its branch. */
    private fun KotlinSourceGate.theBranchOn(condition: String, within: IntRange): IntRange {
        val found = branchesOn(condition, within)
        assertEquals("`if ($condition)` stands ${found.size} times as a whole condition — the gate reads exactly one", 1, found.size)
        return found.single()
    }

    private fun KotlinSourceGate.sendRange(): IntRange {
        val found = range("fun sendAssetTransfer(", "sealed class SendState")
        assertTrue("cannot find sendAssetTransfer() — the gate is blind, not clean", found != null)
        return found!!
    }

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

    private fun confirmationDialog() = screen.section("private fun AssetSendConfirmDialog(", "private fun SendResultBanner(")

    private fun send() = viewModel.section("fun sendAssetTransfer(", "sealed class SendState")

    @Test fun `the asset confirmation is drawn from the approved object`() {
        val confirmation = screenGate.theCall("AssetSendConfirmDialog")
        val shown = confirmation.named
        assertEquals("the confirmation's quantity is not the approved object's text", "approved.amountText", shown["quantityText"])
        assertEquals("the confirmation's cost rows are not worked out from the approved units", "approved.units", shown["quantityUnits"])
        assertEquals("the confirmation's address is not the approved object's", "approved.address", shown["recipientAddress"])
        assertEquals("the confirmation's fee is not the approved object's", "approved.feeEstimateSats", shown["feeSats"])
        val handOff = screenGate.theCall("viewModel.sendAssetTransfer")
        assertEquals("the send is not handed the approved object, and only that", listOf("approved"), handOff.arguments)
        assertTrue("the send is not made from the confirmation", handOff.range.first in confirmation.range)
        val call = confirmation.text
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
        val declared = screenGate.range("private fun AssetSendConfirmDialog(", "private fun SendResultBanner(")!!
        val quantityRows = screenGate.calls("AssetConfirmRow", within = declared)
            .filter { it.named["label"] == "stringResource(R.string.as_quantity)" }
        assertEquals("the dialog has ${quantityRows.size} quantity rows — the gate reads exactly one", 1, quantityRows.size)
        assertEquals(
            "the dialog's quantity row is not the approved text, as it stands, and the asset's symbol",
            "quantityText + \" \" + (asset.metadata?.symbol ?: stringResource(R.string.as_tokens))",
            quantityRows.single().named["value"],
        )
        val costRows = screenGate.calls("CostPreviewCard", within = declared)
        assertEquals("the dialog works its cost rows out ${costRows.size} times — the gate reads exactly one", 1, costRows.size)
        assertEquals("the dialog's cost rows are not worked out from the approved units", "quantityUnits", costRows.single().named["units"])
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
        val vm = viewModelGate
        val handedOn = vm.theCall("assetManager.sendAsset")
        assertTrue("the asset manager is not called from sendAssetTransfer()", handedOn.range.first in vm.sendRange())
        assertEquals(
            "sendAssetTransfer() does not hand on what was approved, each value whole",
            mapOf(
                "assetId" to "approved.assetId", "quantity" to "approved.units",
                "toAddress" to "approved.address", "feePerKb" to "approved.feePerKb",
                "maxFeeSats" to "approved.feeEstimateSats",
            ),
            handedOn.named,
        )
        assertEquals("sendAssetTransfer() hands on something that was not approved", 5, handedOn.arguments.size)
        val otherScale = vm.theBranchOn("divisibilityOf(asset) != approved.divisibility", within = vm.sendRange())
        assertTrue(
            "sendAssetTransfer() does not stop when the asset's divisibility is not the one the approval was read and shown at",
            Regex("""\breturn\b""").containsMatchIn(vm.code.substring(otherScale)) && otherScale.last < handedOn.range.first,
        )
    }

    /** Only the approval on screen may be sent, and only once — decided before anything else is looked at. */
    @Test fun `the asset send first asks whether this approval is the one on screen`() {
        val send = send()
        val claim = send.indexOf("confirmation.claim(approved)")
        assertTrue("sendAssetTransfer() does not ask whether this approval is the one on screen", claim >= 0)
        val notOnScreen = viewModelGate.run { theBranchOn("!confirmation.claim(approved)", within = sendRange()) }
        assertTrue(
            "sendAssetTransfer() does not stop when this approval is not the one on screen",
            Regex("""\breturn\b""").containsMatchIn(viewModel.substring(notOnScreen)),
        )
        for (later in listOf("selectedAsset.value", "_ruleCheck.value", "_sendState.value = SendState.Sending")) {
            val at = send.indexOf(later)
            assertTrue("cannot find `$later` in sendAssetTransfer() — the gate is blind, not clean", at >= 0)
            assertTrue("sendAssetTransfer() reaches `$later` before it has asked whether this approval is the one on screen", claim < at)
        }
        assertTrue(
            "sendAssetTransfer() reaches the asset manager before it has asked whether this approval is the one on screen",
            viewModelGate.run { notOnScreen.last < theCall("assetManager.sendAsset").range.first },
        )
    }

    @Test fun `Review is the one place the quantity text becomes a send`() {
        val click = screen.reviewClick()
        val request = screenGate.theCall("viewModel.requestConfirm")
        assertEquals(
            "Review does not hand the form, as it stands, to the view model to be approved",
            listOf("recipientAddress", "quantityInput"), request.arguments,
        )
        assertTrue("the form is not handed over from Review's click", click.contains(request.text))
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
        val ui = screenGate
        val confirmation = ui.theCall("AssetSendConfirmDialog")
        val prompt = ui.theCall("spendAuth.authorize")
        val handOff = ui.theCall("viewModel.sendAssetTransfer")
        assertTrue("the credential prompt is not the confirmation's", prompt.range.first in confirmation.range)
        val allowed = ui.guardedBy(prompt)
        assertTrue("the credential prompt's answer is not, by itself, the condition the send stands under", allowed != null)
        assertTrue(
            "the send does not stand under the credential prompt's answer",
            handOff.range.first in allowed!! && handOff.range.last in allowed,
        )

        val vm = viewModelGate
        val manager = vm.theCall("assetManager.sendAsset")
        val refused = vm.theBranchOn("!_ruleCheck.value.allowsSend", within = vm.sendRange())
        assertTrue(
            "sendAssetTransfer() does not stop, before the asset manager is called, when the transfer-rule check does not allow a send",
            Regex("""\breturn\b""").containsMatchIn(vm.code.substring(refused)) && refused.last < manager.range.first,
        )
        val request = ui.theCall("viewModel.requestConfirm")
        val button = ui.calls("Button").filter { request.range.first in it.range }
        assertEquals("cannot find the Review button — the gate is blind, not clean", 1, button.size)
        // Held back by the transfer-rule check, and — one tap at a time — while Review plans the fee (B231).
        assertEquals("Review is no longer held back by the transfer-rule check", "ruleCheck.allowsSend && !planning", button.single().named["enabled"])
    }

    @Test fun `the gate can see the files it reads`() {
        assertTrue(screen.contains("fun AssetSendScreen(") && screen.contains("AssetSendConfirmDialog("))
        assertTrue(viewModel.contains("class AssetViewModel") && viewModel.contains("fun sendAssetTransfer("))
    }
}
