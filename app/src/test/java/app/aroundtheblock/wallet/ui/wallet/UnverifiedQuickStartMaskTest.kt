package app.aroundtheblock.wallet.ui.wallet

import app.aroundtheblock.wallet.core.settings.BALANCE_MASK
import app.aroundtheblock.wallet.core.wallets.QuickStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The quick start's "reported by" line under the balance: with balances hidden, the server that
 * answered is hidden too — an own node's host names the user's own machine.
 */
class UnverifiedQuickStartMaskTest {

    private val q = QuickStart(unverifiedSat = 150_000_000L, unverifiedDdCents = null, sourceLabel = "node.example.lan", atSecs = 1L)

    @Test fun `hidden balances hide the amount and the server`() {
        assertEquals(listOf(BALANCE_MASK, BALANCE_MASK), unverifiedQuickStartArgs(q, hidden = true))
    }

    @Test fun `shown balances show both`() {
        assertEquals(listOf("1.50", "node.example.lan"), unverifiedQuickStartArgs(q, hidden = false))
    }

    @Test fun `the wallet screen's line is built from those arguments`() {
        val src = File("src/main/java/app/aroundtheblock/wallet/ui/wallet/WalletScreen.kt").readText()
        assertTrue(
            "the unverified line does not use unverifiedQuickStartArgs",
            Regex("""R\.string\.quickstart_unverified,\s*\*unverifiedQuickStartArgs\(q, hidden\)""").containsMatchIn(src),
        )
        assertTrue("the server label is passed unmasked", !src.contains("q.sourceLabel),"))
    }
}
