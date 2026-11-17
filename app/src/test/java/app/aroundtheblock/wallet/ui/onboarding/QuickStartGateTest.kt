package app.aroundtheblock.wallet.ui.onboarding

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The quick start sends a restored wallet's addresses to a server, so by default it sends nothing,
 * and what it shows is never money: no balance, UTXO or send code reads it.
 */
class QuickStartGateTest {

    private val appRoot = File("src/main/java")
    private val coreRoot = File("../core/src/main/java")
    private val screen by lazy {
        KotlinSourceGate.of(File(appRoot, "app/aroundtheblock/wallet/ui/onboarding/QuickStartScreen.kt").readText())
    }

    /** Only the store itself, the list of stores, the two erasers, the one display and the one writer. */
    private val readers = setOf(
        "QuickStartStore.kt", "WalletStores.kt", "WalletViewModel.kt", "OnboardingViewModel.kt",
        "WalletStoreEraser.kt",
        // The full wipe clears it by name, as it clears every other per-wallet store.
        "WalletDataEraser.kt",
    )

    @Test fun `no money path reads the quick start`() {
        val files = (appRoot.walkTopDown() + coreRoot.walkTopDown()).filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("scanner is blind: no sources", files.size > 100)
        val found = files.filter { f ->
            val code = KotlinSourceGate.of(f.readText()).written
            code.contains("QuickStartStore") || code.contains("\"dgb_quickstart\"")
        }.map { it.name }.toSet()
        assertTrue("scanner is blind: the store is not found", "QuickStartStore.kt" in found)
        assertTrue("scanner is blind: the display does not read it", "WalletViewModel.kt" in found)
        assertTrue("scanner is blind: the add does not write it", "OnboardingViewModel.kt" in found)
        assertEquals("the quick start is read outside the display", emptySet<String>(), found - readers)
    }

    @Test fun `own node is offered only when set`() {
        assertEquals(listOf(QuickStartSource.OWN_NODE, QuickStartSource.DIGISCOPE), quickStartChoices("https://x"))
        assertEquals(listOf(QuickStartSource.DIGISCOPE), quickStartChoices(null))
        assertEquals(listOf(QuickStartSource.DIGISCOPE), quickStartChoices("  "))
    }

    @Test fun `the own node is shown without its credentials`() {
        assertEquals("node.example:8443", quickStartHost("https://alice:s3cret@node.example:8443/api?token=x"))
        assertEquals("node.example", quickStartHost("https://node.example/api"))
        assertEquals(null, quickStartHost("not a url"))
    }

    private val buttons = listOf("Button", "OutlinedButton", "TextButton")

    private class Declared(val start: Int, val onClick: IntRange?, val content: String, val callee: String)

    private fun declaredButtons(): List<Declared> = buttons.flatMap { b ->
        screen.calls(b).map { c ->
            Declared(c.range.first, c.valueRange("onClick"), screen.code.substring(screen.trailingBlock(c) ?: c.range), b)
        }
    }.sortedBy { it.start }

    @Test fun `nothing is sent before a choice`() {
        val sends = screen.calls("viewModel.runQuickStart")
        assertEquals("scanner is blind or a send was added: ${sends.map { it.text }}", 2, sends.size)
        assertEquals("runQuickStart is called other than through viewModel", 2, Regex("""runQuickStart\s*\(""").findAll(screen.code).count())
        val declared = declaredButtons()
        val expected = mapOf(
            "R.string.quickstart_own_node" to "QuickStartSource.OWN_NODE",
            "R.string.quickstart_digiscope_confirm" to "QuickStartSource.DIGISCOPE",
        )
        val seen = mutableSetOf<String>()
        for (send in sends) {
            val button = declared.firstOrNull { it.onClick != null && send.range.first in it.onClick }
            assertTrue("runQuickStart outside a button's onClick: ${send.text}", button != null)
            val label = expected.keys.firstOrNull { button!!.content.contains(it) }
            assertTrue("runQuickStart on a button that is not a choice: ${button!!.content}", label != null)
            assertEquals("the $label button sends to the wrong place", listOf(expected.getValue(label!!)), send.arguments)
            seen += label
        }
        assertEquals(expected.keys, seen)
    }

    @Test fun `skipping is the default and sends nothing`() {
        val first = declaredButtons().firstOrNull() ?: error("scanner is blind: no buttons")
        assertEquals("the first button is not the primary one", "Button", first.callee)
        assertTrue("the first button is not Skip: ${first.content}", first.content.contains("R.string.quickstart_skip"))
        val onClick = first.onClick ?: error("Skip has no onClick")
        val click = screen.code.substring(onClick)
        assertTrue("Skip sends", !Regex("""runQuickStart\s*\(""").containsMatchIn(click))
        assertTrue("Skip does not go to the commit step", screen.calls("navController.navigate", onClick).any { it.arguments.firstOrNull() == "ADD_COMMIT_ROUTE" })
    }

    @Test fun `one scan routine`() {
        val vm = KotlinSourceGate.of(File(appRoot, "app/aroundtheblock/wallet/ui/onboarding/OnboardingViewModel.kt").readText())
        val start = vm.code.indexOf("private fun runScan(").takeIf { it >= 0 } ?: error("no runScan")
        val body = vm.blockAt(vm.code.indexOf('{', vm.code.indexOf(')', vm.code.indexOf("onDone", start)))) ?: error("runScan has no body")
        val scans = Regex("""\.scan\(""").findAll(vm.code).toList()
        assertEquals("a second scan call: the routine is copied", 1, scans.size)
        assertTrue("the scan runs outside runScan", scans.single().range.first in body)
        val zeroes = Regex("""passBytes\?\.fill\(0\)""").findAll(vm.code).toList()
        assertEquals("a second passphrase copy-and-zero", 1, zeroes.size)
        assertTrue("the passphrase copy is zeroed outside runScan", zeroes.single().range.first in body)
        assertEquals("the generation guard is copied", 1, Regex("""generation\s*!=\s*scanGeneration""").findAll(vm.code).count())
    }
}
