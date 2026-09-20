package io.digibyte.ui.wallet

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level WIRING gate for one rule: the confirmation is drawn from an `ApprovedSend`, and
 * that same object is what the send receives.
 *
 * What an `ApprovedSend` holds and how it spells its amount is behaviour, and is tested as
 * behaviour in `core` (`SendAmountBindingTest`, `UsdCentsTest`). This gate covers the part no
 * behaviour test can reach — no `app` unit test builds a ViewModel or a composable — which is
 * that the screen actually uses the object. A confirmation fed from the text field again, or a
 * send that reads the field again after the credential prompt, would compile and look right.
 */
class SendAmountBindingWiringTest {

    private val uiRoot = File("src/main/java/io/digibyte/ui")

    private fun source(rel: String): String =
        File(uiRoot, rel).let {
            assertTrue("$rel is missing — this gate is watching a file that moved", it.exists())
            it.readLines()
                .map { l -> l.substringBefore("//") }
                .filterNot { l -> l.trimStart().startsWith("*") || l.trimStart().startsWith("/*") }
                .joinToString("\n")
        }

    /** The text from [from] up to the next occurrence of [until]. */
    private fun String.section(from: String, until: String): String {
        val start = indexOf(from)
        assertTrue("cannot find `$from` — the gate is blind, not clean", start >= 0)
        val end = indexOf(until, start + from.length)
        assertTrue("cannot find `$until` after `$from` — the gate is blind, not clean", end > start)
        return substring(start, end)
    }

    @Test fun `the DGB confirmation is drawn from the approved object`() {
        val call = source("wallet/SendScreen.kt").section("SendConfirmationDialog(", "onCancel")
        assertTrue("the confirmation's amount is not the approved object's text", call.contains("amountDgb = approved.amountText"))
        assertTrue("the confirmation's address is not the approved object's", call.contains("address = approved.address"))
        assertTrue("the confirmation's fee is not the approved object's", call.contains("feeEstimate = approved.feeEstimateSats"))
        assertTrue("the send is not handed the approved object", call.contains("viewModel.send(approved)"))
    }

    @Test fun `the DGB send takes the approved object and reads no field`() {
        val vm = source("wallet/SendViewModel.kt")
        val send = vm.section("fun send(", "fun resetState(")
        val reReads = listOf(
            "amountSatoshis(", "amountDgb.value", "amountFiat.value", "address.value",
            "feeRatePerKb.value", "estimatedFeeSat.value", "customFeeInput.value", "isCustomFee.value",
            "DgbAmount.toSats(",
        )
        for (field in reReads) {
            assertFalse("send() reads `$field` again instead of using what was approved", send.contains(field))
        }
        assertTrue("send() does not take the approved object", send.contains("fun send(approved: ApprovedSend.Dgb)"))
        for (use in listOf("approved.address", "approved.sats", "approved.feePerKb")) {
            assertTrue("send() does not use `$use`", send.contains(use))
        }
        assertTrue(
            "requestConfirm does not build the approved object",
            vm.section("fun requestConfirm(", "fun cancelConfirm(").contains("ApprovedSend.dgb("),
        )
    }

    /**
     * The dollars row sits beside the amount on the confirmation, so it is part of what is
     * approved: read once at review, held by the approval, and not the text field again.
     */
    @Test fun `the confirmation's dollars row is drawn from the approved object`() {
        val call = source("wallet/SendScreen.kt").section("SendConfirmationDialog(", "onCancel")
        assertTrue(
            "the confirmation's dollars row is not the approved object's text",
            call.contains("amountFiat = approved.approxUsdText"),
        )
        assertFalse(
            "the confirmation's dollars row is the text field, which can change while the dialog is up",
            call.contains("amountFiat = amountFiat"),
        )
        assertTrue(
            "requestConfirm does not hand the dollars text to the approval",
            source("wallet/SendViewModel.kt").section("fun requestConfirm(", "fun cancelConfirm(")
                .contains("dollarsShown = amountFiat.value"),
        )
    }

    /**
     * Either amount field can be worked out from the other, through a price lookup that takes
     * time. The worked-out text is only ever the conversion of the text NOW in the other field, or
     * empty: an edit empties it at once — before any branch, before the lookup is started — and a
     * lookup may write only while its own edit is still the latest one. Review reads the fields as
     * they stand, so between an edit and its lookup there is nothing to review.
     */
    @Test fun `a derived amount is emptied before the lookup for the new text starts`() {
        val vm = source("wallet/SendViewModel.kt")
        val handlers = listOf(
            Triple("fun onAmountDgbChanged(", "fun onAmountFiatChanged(", "amountFiat.value"),
            Triple("fun onAmountFiatChanged(", "fun applyScannedUri(", "amountDgb.value"),
        )
        // Both handlers are read before anything is asserted, so one report names every gap.
        val gaps = mutableListOf<String>()
        for ((from, until, derived) in handlers) {
            val body = vm.section(from, until)
            val emptied = body.indexOf("$derived = \"\"")
            val firstBranch = body.indexOf("if (")
            val lookup = body.indexOf("viewModelScope.launch")
            assertTrue("`$from` has no branch or starts no lookup — the gate is blind, not clean", firstBranch >= 0 && lookup >= 0)
            if (emptied < 0) {
                gaps += "`$from` never empties `$derived`"
            } else if (emptied > firstBranch || emptied > lookup) {
                gaps += "`$from` empties `$derived` on some paths only: the value worked out for earlier text " +
                    "stays on screen, and can be reviewed, while the lookup for the new text is pending"
            }
            if (!body.contains("val edit = ++amountEdit")) gaps += "`$from` does not number its edit"
            if (!body.contains("if (edit == amountEdit) $derived = ")) {
                gaps += "`$from` lets a lookup write `$derived` after a later edit to either field"
            }
            if (Regex(Regex.escape("$derived = ")).findAll(body).count() != 2) {
                gaps += "`$from` writes `$derived` somewhere other than the emptying and the numbered landing"
            }
        }
        // Nothing else writes either field. A write that went round the handlers would not empty
        // the other field and would not number itself, so a lookup already out could land on it.
        val fieldWrite = Regex("""amount(Dgb|Fiat)\.value = """)
        val inHandlers = handlers.sumOf { (from, until, _) -> fieldWrite.findAll(vm.section(from, until)).count() }
        if (fieldWrite.findAll(vm).count() != inHandlers) {
            gaps += "an amount field is written outside `onAmountDgbChanged` / `onAmountFiatChanged`"
        }
        assertTrue(gaps.joinToString("\n", prefix = "\n"), gaps.isEmpty())
    }

    @Test fun `the DigiDollar confirmation is drawn from the approved object`() {
        val call = source("wallet/SendScreen.kt").section("DigiDollarConfirmationDialog(", "onCancel")
        assertTrue("the confirmation's amount is not the approved object's text", call.contains("amountUsd = approvedDd.amountText"))
        assertTrue("the confirmation's address is not the approved object's", call.contains("address = approvedDd.address"))
        assertTrue("the send is not handed the approved object", call.contains("viewModel.sendDigiDollar(approvedDd)"))
    }

    @Test fun `the DigiDollar send takes the approved object and reads no field`() {
        val send = source("wallet/SendViewModel.kt").section("fun sendDigiDollar(", "companion object")
        for (field in listOf("parseUsdToCents(", "UsdCents.parse(", "amountFiat.value", "address.value")) {
            assertFalse("sendDigiDollar() reads `$field` again instead of using what was approved", send.contains(field))
        }
        assertTrue(
            "sendDigiDollar() does not take the approved object",
            send.contains("fun sendDigiDollar(approved: ApprovedSend.DigiDollar"),
        )
        for (use in listOf("approved.address", "approved.cents")) {
            assertTrue("sendDigiDollar() does not use `$use`", send.contains(use))
        }
    }

    private val moneyPath = listOf(
        "wallet/SendViewModel.kt",
        "wallet/SendScreen.kt",
        "wallet/ReceiveScreen.kt",
        "asset/AssetViewModel.kt",
        "asset/AssetSendScreen.kt",
        "hub/TipBottomSheet.kt",
    )

    /**
     * Text to number. Amount text reaches an integer through `DgbAmount` / `UsdCents` only: a
     * binary floating-point conversion reads some valid decimals as a neighbouring value. This
     * check sees conversions of text and the rounding of a double, nothing else; the check after
     * it covers the other direction.
     */
    @Test fun `no amount text is read through a floating-point conversion`() {
        val floatConversion = Regex("""\.to(Double|Float)(OrNull)?\(\)|Math\.round\(""")
        val offenders = moneyPath.flatMap { rel ->
            source(rel).lines()
                .filter { floatConversion.containsMatchIn(it) }
                .map { "$rel: ${it.trim()}" }
        }
        assertTrue("floating-point conversion of amount text:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /**
     * Number to text. A whole number of satoshis or cents is written out with `DgbAmount.format`,
     * `UsdCents.format` or `BigDecimal.valueOf(units, scale)` — never divided into a double first,
     * which can spell a large value one unit off and takes its decimal mark from the default
     * locale, so that two rows of one screen disagree.
     *
     * [knownDisplayOnly] names the fee labels of the asset screen that still do it. They label a
     * fee, are never parsed back, and belong to the asset-screen work; a new line fails here, and
     * so does a listed line that no longer exists.
     */
    @Test fun `no integer amount is written out through a floating-point division`() {
        val unitDivision = Regex("""/\s*(100_000_000\.0|100000000\.0|1[eE]8|100\.0)|Math\.pow\(""")
        val offenders = moneyPath.flatMap { rel ->
            source(rel).lines().map { it.trim() }
                .filter { unitDivision.containsMatchIn(it) && it !in knownDisplayOnly[rel].orEmpty() }
                .map { "$rel: $it" }
        }
        assertTrue("integer amount written out through a double:\n" + offenders.joinToString("\n"), offenders.isEmpty())
        for ((rel, listed) in knownDisplayOnly) {
            val lines = source(rel).lines().map { it.trim() }
            for (line in listed) {
                assertTrue("$rel no longer has this line — take it off the list: $line", line in lines)
            }
        }
    }

    private val knownDisplayOnly = mapOf(
        "asset/AssetSendScreen.kt" to setOf(
            "val defaultFeeDgb = viewModel.defaultFeeSat / 100_000_000.0",
            "value = String.format(\"%.8f DGB\", feeSats / 100_000_000.0)",
            "java.text.DecimalFormat(\"0.########\").format(sats / 100_000_000.0)",
        ),
    )

    @Test fun `the gate can see the files it reads`() {
        val screen = source("wallet/SendScreen.kt")
        assertTrue(screen.contains("SendConfirmationDialog(") && screen.contains("DigiDollarConfirmationDialog("))
        assertTrue(source("wallet/SendViewModel.kt").contains("class SendViewModel"))
    }
}
