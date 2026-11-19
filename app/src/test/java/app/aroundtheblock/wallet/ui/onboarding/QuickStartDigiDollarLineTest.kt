package app.aroundtheblock.wallet.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QUICKSTART-DD: wherever the quick start's DGB figure is shown (its own result screen and the
 * line under the wallet's balance), DigiDollar is shown beside it: the reported figure when the
 * lookup finished, and "could not be checked" when it did not (`unverifiedDdCents == null`).
 * Never nothing, which reads as "holds none", and never a zero the lookup did not report.
 */
class QuickStartDigiDollarLineTest {

    private val screens = listOf(
        "src/main/java/app/aroundtheblock/wallet/ui/onboarding/QuickStartScreen.kt" to "quickstart_found_dd",
        "src/main/java/app/aroundtheblock/wallet/ui/wallet/WalletScreen.kt" to "quickstart_unverified_dd",
    )

    @Test fun `both displays say so when DigiDollar could not be checked`() {
        for ((path, figure) in screens) {
            val src = File(path).readText()
            assertEquals("$path: the DigiDollar figure line", 1, Regex("""R\.string\.$figure\b""").findAll(src).count())
            assertEquals("$path: no line for a DigiDollar lookup that was not made", 1,
                Regex("""R\.string\.quickstart_dd_unchecked\b""").findAll(src).count())
            // The two are the two arms of one decision on the stored cents: a figure, else unchecked.
            assertTrue("$path: the unchecked line is not the null arm of the DigiDollar figure",
                Regex("""q\.unverifiedDdCents\?\.let\s*\{[^}]*R\.string\.$figure\b[^}]*\}\s*\?:\s*stringResource\(R\.string\.quickstart_dd_unchecked\)""")
                    .containsMatchIn(src))
        }
    }

    @Test fun `the figure is formatted as dollars by the wallet's one formatter`() {
        for ((path, _) in screens) {
            val src = File(path).readText()
            assertTrue("$path: DigiDollar cents are not formatted by formatDigiDollar",
                Regex("""WalletViewModel\.formatDigiDollar\(""").containsMatchIn(src))
        }
        assertEquals("$1.00", app.aroundtheblock.wallet.ui.wallet.WalletViewModel.formatDigiDollar(100L))
        assertEquals("$0.00", app.aroundtheblock.wallet.ui.wallet.WalletViewModel.formatDigiDollar(0L))
    }

    @Test fun `the wallet screen masks the DigiDollar figure with the balance`() {
        val src = File("src/main/java/app/aroundtheblock/wallet/ui/wallet/WalletScreen.kt").readText()
        assertTrue(Regex("""R\.string\.quickstart_unverified_dd,\s*mask\(WalletViewModel\.formatDigiDollar\(""").containsMatchIn(src))
    }
}
