package app.aroundtheblock.wallet.ui.wallet

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the wallet screen shows the history rebuild notice that [HistoryRebuildNotice.of]
 * decides from the rebuild's outcome in this process, and its dismissal is remembered for the
 * process. A gate because the screen is Compose and this repo has no Compose UI test harness; the
 * decision itself is covered by [HistoryRebuildNoticeTest].
 */
class HistoryRebuildNoticeGateTest {

    private val screen = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/ui/wallet/WalletScreen.kt").readText())

    @Test fun `the wallet screen follows the rebuild outcome of this process`() {
        assertTrue("the wallet screen does not collect the rebuild outcome",
            Regex("""HistoryRebuildOnUpgrade\s*\.\s*lastOutcome\s*\.\s*collectAsStateWithLifecycle\s*\(""").containsMatchIn(screen.code))
        assertTrue("the wallet screen does not collect the dismissed notices",
            Regex("""HistoryRebuildNoticeDismissals\s*\.\s*dismissed\s*\.\s*collectAsStateWithLifecycle\s*\(""").containsMatchIn(screen.code))
    }

    @Test fun `the notice shown is the one the decision returns`() {
        val decided = screen.calls("HistoryRebuildNotice.of")
        assertEquals("expected one notice decision on the wallet screen", 1, decided.size)
        val banners = screen.calls("HistoryRebuildNoticeBanner")
        assertEquals("expected one rebuild notice on the wallet screen", 1, banners.size)
    }

    @Test fun `dismissing the notice is remembered for the process`() {
        val banner = screen.calls("HistoryRebuildNoticeBanner").singleOrNull() ?: error("no rebuild notice")
        val onDismiss = banner.valueRange("onDismiss") ?: error("the notice is not dismissible")
        assertEquals("dismissing does not record the outcome's kind", 1,
            screen.calls("HistoryRebuildNoticeDismissals.dismiss", onDismiss).size)
    }
}
