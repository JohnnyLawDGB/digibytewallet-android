package io.digibyte.ui.asset

import io.digibyte.ui.KotlinSourceGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level WIRING gate for one rule: the units a transfer request names reach the approval as
 * the integer they arrived as.
 *
 * What is done with the integer — the text written from it at a divisibility, the end of the
 * request at the first edit, the units the approval then holds — is behaviour, and is tested as
 * behaviour in `RequestedAssetQuantityTest`. This gate covers what no behaviour test can reach,
 * because no `app` unit test builds a route, a composable or a view model: that the route, the
 * screen and the view model actually hand the integer on, and that nothing between them carries
 * the quantity as text to be read at some scale later.
 */
class AssetRequestWiringTest {

    private val uiRoot = File("src/main/java/io/digibyte/ui")

    private fun gate(rel: String): KotlinSourceGate =
        File(uiRoot, rel).let {
            assertTrue("$rel is missing — this gate is watching a file that moved", it.exists())
            KotlinSourceGate.of(it.readText())
        }

    private val scan get() = gate("navigation/ScanDestination.kt")
    private val navigation get() = gate("navigation/AppNavigation.kt")
    private val screen get() = gate("asset/AssetSendScreen.kt")
    private val viewModel get() = gate("asset/AssetViewModel.kt")

    private fun KotlinSourceGate.theCall(callee: String, within: IntRange = code.indices): KotlinSourceGate.Call {
        val found = calls(callee, within)
        assertEquals("`$callee(` is called ${found.size} times — the gate reads one call, and it has to be the only one", 1, found.size)
        return found.single()
    }

    private operator fun IntRange.contains(other: IntRange) = other.first in this && other.last in this

    @Test fun `the route carries the requested units as an integer argument`() {
        assertTrue(
            "the scanner's route does not carry the request's units under the name `units`",
            scan.written.contains("&units=\$units") && Regex("""val\s+units\s*=\s*uri\.assetAmount\?\.takeIf\s*\{\s*it\s*>\s*0L\s*\}""").containsMatchIn(scan.code),
        )
        val nav = navigation
        assertTrue(
            "the asset send route has no `units` argument",
            nav.written.contains("\"asset_send/{assetId}?address={address}&units={units}\""),
        )
        val argument = nav.calls("navArgument").filter { nav.written.substring(it.range).startsWith("navArgument(\"units\")") }
        assertEquals("the `units` argument is declared ${argument.size} times", 1, argument.size)
        val declared = nav.trailingBlock(argument.single())
        assertTrue(
            "the `units` argument is not an integer with `0` for a route that names none",
            declared != null && KotlinSourceGate.squeeze(nav.code.substring(declared)) == "{ type = NavType.LongType; defaultValue = 0L }",
        )
        assertTrue(
            "the route's units are not read as an integer, or a route that names none is not read as none",
            Regex("""val\s+requestedUnits\s*=\s*backStackEntry\.arguments\?\.getLong\("units"\)\?\.takeIf\s*\{\s*it\s*>\s*0L\s*\}""")
                .containsMatchIn(nav.written),
        )
        assertEquals("the screen is not handed the route's units", "requestedUnits", nav.theCall("AssetSendScreen").named["requestedUnits"])
        for (asText in listOf("quantity={quantity}", "navArgument(\"quantity\")", "getString(\"quantity\")", "prefillQuantity")) {
            assertFalse("the route still carries a quantity as text: `$asText`", nav.written.contains(asText))
        }
    }

    @Test fun `the screen takes the requested units as an integer and hands them to the view model`() {
        val ui = screen
        assertTrue("the screen does not take the request as whole units", Regex("""requestedUnits\s*:\s*Long\?\s*=\s*null""").containsMatchIn(ui.code))
        assertFalse("the screen still takes a quantity as text", ui.code.contains("prefillQuantity"))

        val effects = ui.calls("LaunchedEffect").filter { it.arguments == listOf("requestedUnits", "decimals") }
        assertEquals("the quantity is pre-filled from ${effects.size} effects keyed on the request and the divisibility", 1, effects.size)
        val body = ui.trailingBlock(effects.single())
        assertTrue("cannot find the pre-fill effect's block — the gate is blind, not clean", body != null)
        assertEquals(
            "the request is not handed to the view model as the integer it arrived as",
            listOf("requestedUnits"), ui.theCall("viewModel.requestedQuantity.deliver").arguments,
        )
        assertEquals(
            "the field's text is not written at the divisibility the form reads the field with",
            listOf("decimals"), ui.theCall("viewModel.requestedQuantity.fieldText").arguments,
        )
        assertEquals(
            "the form does not read the field at `decimals`",
            listOf("quantityInput", "decimals"), ui.theCall("AssetQuantity.parse").arguments,
        )
        assertTrue(
            "the request is delivered, or the field's text asked for, outside the pre-fill effect",
            ui.theCall("viewModel.requestedQuantity.deliver").range in body!! && ui.theCall("viewModel.requestedQuantity.fieldText").range in body,
        )
    }

    /**
     * Three places write the quantity field: the pre-fill, typing, and MAX. The pre-fill writes what
     * the view model wrote from the units; the other two are the user's, and say so — a write that
     * did not would leave the request standing over text it no longer describes.
     */
    @Test fun `every write of the quantity field is the request's or ends the request`() {
        val ui = screen
        val effects = ui.calls("LaunchedEffect").filter { it.arguments == listOf("requestedUnits", "decimals") }
        assertEquals("the quantity is pre-filled from ${effects.size} effects keyed on the request and the divisibility", 1, effects.size)
        val prefill = ui.trailingBlock(effects.single())
        assertTrue("cannot find the pre-fill effect's block — the gate is blind, not clean", prefill != null)
        val writes = Regex("""(?<!\w)quantityInput\s*=(?!=)""").findAll(ui.code).map { it.range.first }.toList()
        assertEquals("the quantity field is written in ${writes.size} places — the gate knows three", 3, writes.size)
        val (requests, users) = writes.partition { it in prefill!! }
        assertEquals("the pre-fill writes the field ${requests.size} times", 1, requests.size)
        assertEquals(
            "the pre-fill does not write exactly the text the view model wrote from the units",
            "{ quantityInput = it quantityError = null }",
            KotlinSourceGate.squeeze(ui.code.substring(ui.enclosingBlock(requests.single())!!)),
        )
        for (write in users) {
            val block = ui.enclosingBlock(write)
            assertTrue("cannot find the block a write of the field stands in — the gate is blind, not clean", block != null)
            val line = ui.code.substring(0, write).count { it == '\n' } + 1
            assertEquals(
                "AssetSendScreen.kt:$line writes the quantity field without ending the request",
                1, ui.calls("viewModel.requestedQuantity.edited", within = block!!).count { it.arguments.isEmpty() },
            )
        }
    }

    @Test fun `Review reads the request's units at the divisibility it approves at`() {
        val vm = viewModel
        assertTrue(
            "the view model does not hold the request",
            Regex("""val\s+requestedQuantity\s*=\s*RequestedAssetQuantity\(\)""").containsMatchIn(vm.code),
        )
        val confirmBody = vm.range("fun requestConfirm(", "fun cancelConfirm(")
        assertTrue("cannot find requestConfirm() — the gate is blind, not clean", confirmBody != null)
        val approval = vm.theCall("ApprovedSend.asset", within = confirmBody!!)
        assertEquals(
            "the approval's quantity is not the request's units, or the field's own text, read in one step",
            "requestedQuantity.textToApprove(quantityInput, divisibility)", approval.named["typedQuantity"],
        )
        assertEquals("the approval is not made at the divisibility its quantity was read at", "divisibility", approval.named["divisibility"])
        val read = Regex("""val\s+divisibility\s*=\s*divisibilityOf\(asset\)""").findAll(vm.code).filter { it.range.first in confirmBody }.toList()
        assertEquals("requestConfirm() reads the asset's divisibility ${read.size} times — once, so that both uses are one value", 1, read.size)
        assertTrue("the divisibility is read after the approval is made", read.single().range.last < approval.range.first)
        assertEquals(
            "requestConfirm() reads the field's text somewhere other than through the request",
            2, Regex("""(?<!\w)quantityInput(?!\w)""").findAll(vm.code).count { it.range.first in confirmBody },
        )
    }

    @Test fun `the gate can see the files it reads`() {
        assertTrue(scan.code.contains("object ScanDestination") && scan.code.contains("fun forDigiByteUri("))
        assertTrue(navigation.calls("AssetSendScreen").isNotEmpty() && navigation.calls("navArgument").size > 3)
        assertTrue(screen.code.contains("fun AssetSendScreen(") && screen.code.contains("quantityInput"))
        assertTrue(viewModel.code.contains("class AssetViewModel") && viewModel.code.contains("class RequestedAssetQuantity"))
    }
}
