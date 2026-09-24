package io.digibyte.ui.onboarding

import io.digibyte.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate for PIN setup's create-or-skip decision.
 *
 * PIN setup creates the wallet from the words onboarding holds, unless a wallet is already stored:
 * the restore path stores it before PIN setup, and a recomposition finds the one this screen just
 * made. What is loaded in memory is not the question — a wipe earlier in this process leaves that
 * answer about a wallet that is no longer on the device. The decision itself, over a real wipe, is
 * covered by `WalletSessionAfterWipeTest` in `core`; this pins that the screen asks it.
 */
class PinSetupCreateGateTest {

    private val screen = KotlinSourceGate.of(File("src/main/java/io/digibyte/ui/onboarding/PinSetupScreen.kt").readText())

    @Test fun `the gate does not ask what is loaded in memory`() {
        val asked = screen.calls("isWalletLoaded").map { KotlinSourceGate.squeeze(it.text) }
        assertEquals("PIN setup decides from the in-memory wallet: $asked", emptyList<String>(), asked)
    }

    @Test fun `the wallet is created only under the stored-wallet answer`() {
        val creates = screen.calls("viewModel.createWallet")
        assertEquals("scanner is blind: PIN setup no longer creates the wallet at all", 1, creates.size)
        val asks = screen.calls("walletManager.pinSetupCreatesWallet")
        assertEquals("PIN setup does not ask whether it must create the wallet", 1, asks.size)
        val guarded = screen.guardedBy(asks.single())
        assertTrue("the answer is not, by itself, the condition of what follows it", guarded != null)
        assertTrue(
            "the wallet is created outside the branch the stored-wallet answer opens",
            creates.single().range.first in guarded!!,
        )
    }

    /**
     * A stored wallet is not created again, and PIN setup goes on only when that wallet is really
     * open in this process. Stored but not loaded — a lost-PIN restore that did not load — must end
     * on the wallet-failed message, never on a wallet screen with nothing behind it.
     */
    @Test fun `with a wallet already stored PIN setup goes on only when it is loaded`() {
        val ask = screen.calls("walletManager.pinSetupCreatesWallet").single()
        val guarded = screen.guardedBy(ask) ?: error("scanner is blind: the stored-wallet answer guards nothing")
        val rest = screen.code.substring(guarded.last + 1)
        val elseAt = Regex("""^\s*else\s*\{""").find(rest) ?: error("the stored-wallet answer has no branch for a wallet already stored")
        val otherwise = screen.blockAt(guarded.last + 1 + elseAt.range.last) ?: error("unbalanced else branch")
        val reports = screen.calls("afterWalletReady", otherwise)
        assertEquals("the branch for a stored wallet does not report whether it is ready", 1, reports.size)
        assertEquals(
            "the branch for a stored wallet reports something other than whether it is loaded",
            listOf("walletManager.isWalletReady()"), reports.single().arguments,
        )
    }
}
