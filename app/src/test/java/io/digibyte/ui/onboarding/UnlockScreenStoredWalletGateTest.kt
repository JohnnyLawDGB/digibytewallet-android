package io.digibyte.ui.onboarding

import io.digibyte.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the unlock screen opens only a wallet that is stored on this device.
 *
 *  - A wipe that could not be verified can leave this process with the wiped wallet still loaded
 *    while its record is gone from the device. A credential then opens nothing: the screen says
 *    the wallet did not open, rather than flipping the app to a wallet that is no longer stored.
 *    A wallet whose seed is still on the device opens as before, the stood-down PIN included.
 *  - A screen brought back with nothing stored behind it — the task restored after a verified
 *    wipe ended its process — asks for no credential at all and goes to onboarding: there is no
 *    wallet for a PIN or a fingerprint to open.
 *
 * A scan because the screen is a composable. `WalletManager.hasSavedWallet` reads what the seed
 * store holds, and a store whose write did not land keeps reading what its file holds
 * (`WalletSessionAfterWipeTest` in `core`), so a refused wipe still counts as a stored wallet.
 */
class UnlockScreenStoredWalletGateTest {

    private val screen = KotlinSourceGate.of(File("src/main/java/io/digibyte/ui/onboarding/UnlockScreen.kt").readText())

    private fun function(name: String): IntRange {
        val declared = Regex("""suspend\s+fun\s+$name\s*\(""").find(screen.code) ?: error("scanner is blind: no $name")
        return screen.blockAt(screen.code.indexOf('{', declared.range.last)) ?: error("unbalanced $name")
    }

    private fun squeezed(range: IntRange) = KotlinSourceGate.squeeze(screen.code.substring(range))

    @Test fun `the unlock screen opens only a wallet that is stored`() {
        val body = function("performUnlockAndNavigate")
        val opener = Regex("""val\s+opened\s*=\s*withContext\s*\(\s*Dispatchers\.IO\s*\)\s*\{""").find(screen.code, body.first)
            ?: error("scanner is blind: the unlock no longer loads the wallet off the main thread")
        val load = screen.blockAt(opener.range.last) ?: error("unbalanced load")
        val nothingStored = screen.branchesOn("!walletManager.hasSavedWallet()", load)
        assertEquals("the unlock does not first ask whether a wallet is stored", 1, nothingStored.size)
        val branch = nothingStored.single()
        assertEquals("with no wallet stored the unlock opens something", "{ false }", squeezed(branch))
        assertTrue(
            "the stored-wallet question is not the head of the unlock's choice",
            Regex("""^\s*else\b""").containsMatchIn(screen.code.substring(branch.last + 1, load.last)),
        )
        val opening = screen.calls("walletManager.unlockFromUi", load) +
            screen.calls("walletManager.restoreFromDisk", load) +
            screen.calls("BootGuard.beginRestore", load)
        assertEquals("scanner is blind: the unlock no longer opens the wallet in the two known ways", 3, opening.size)
        assertTrue("the wallet is opened before the screen knows one is stored", opening.all { it.range.first > branch.last })
        assertTrue(
            "the wallet is opened outside the choice the stored-wallet question heads",
            screen.calls("walletManager.unlockFromUi", body).size == 1 && screen.calls("walletManager.restoreFromDisk", body).size == 1,
        )
    }

    @Test fun `with nothing stored the unlock screen goes to onboarding before it asks for anything`() {
        val entry = screen.calls("LaunchedEffect").mapNotNull { screen.trailingBlock(it) }
            .singleOrNull { block -> screen.calls("biometricAuth.authenticate", block).isNotEmpty() }
            ?: error("scanner is blind: no entry effect that prompts for biometric")
        val owedWipe = screen.branchesOn("owedWipeHoldsTheScreen()", entry)
        assertEquals("scanner is blind: an owed wipe no longer comes first on entry", 1, owedWipe.size)
        val nothingStored = screen.branchesOn("!walletManager.hasSavedWallet()", entry)
        assertEquals("the screen does not ask on entry whether a wallet is stored", 1, nothingStored.size)
        val branch = nothingStored.single()
        val onboarding = screen.calls("navController.navigate", branch)
        assertEquals("with nothing stored the screen does not go to onboarding", listOf(listOf("\"onboarding\"")), onboarding.map { it.arguments })
        val clears = screen.trailingBlock(onboarding.single())?.let { KotlinSourceGate.squeeze(screen.written.substring(it)) }
        assertEquals("onboarding is not reached with the screen cleared behind it", "{ popUpTo(0) { inclusive = true } }", clears)
        assertTrue("the screen goes on after sending its owner to onboarding", squeezed(branch).endsWith("return@LaunchedEffect }"))
        val prompt = screen.calls("biometricAuth.authenticate", entry).single()
        assertTrue("an owed wipe is no longer retried first on entry", owedWipe.single().last < branch.first)
        assertTrue("the screen asks for a credential before it knows one could open anything", branch.last < prompt.range.first)
    }
}
