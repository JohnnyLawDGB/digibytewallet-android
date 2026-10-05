package app.aroundtheblock.wallet.ui.wallet

import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.core.sync.HistoryRebuildOnUpgrade.Outcome
import app.aroundtheblock.wallet.core.sync.HistoryRebuildOnUpgrade.OutcomeKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [HistoryRebuildNotice.of]: what the wallet screen says about the one-time history rebuild. */
class HistoryRebuildNoticeTest {

    @After fun reset() = HistoryRebuildNoticeDismissals.resetForTest()

    @Test fun `nothing before the rebuild has been evaluated`() {
        assertNull(HistoryRebuildNotice.of(null, emptySet()))
    }

    @Test fun `nothing when no rebuild was needed`() {
        assertNull(HistoryRebuildNotice.of(Outcome(OutcomeKind.NOT_NEEDED), emptySet()))
    }

    @Test fun `a rebuild that ran shows the title and the body`() {
        val notice = HistoryRebuildNotice.of(Outcome(OutcomeKind.RAN, discarded = 12), emptySet())
        assertEquals(HistoryRebuildNotice(OutcomeKind.RAN, R.string.history_rebuild_notice_title, R.string.history_rebuild_notice_body), notice)
    }

    @Test fun `a deferred rebuild shows the deferred line only`() {
        val notice = HistoryRebuildNotice.of(Outcome(OutcomeKind.DEFERRED), emptySet())
        assertEquals(HistoryRebuildNotice(OutcomeKind.DEFERRED, null, R.string.history_rebuild_deferred), notice)
    }

    @Test fun `a dismissed outcome is not shown again, another outcome still is`() {
        assertNull(HistoryRebuildNotice.of(Outcome(OutcomeKind.RAN), setOf(OutcomeKind.RAN)))
        assertNull(HistoryRebuildNotice.of(Outcome(OutcomeKind.DEFERRED), setOf(OutcomeKind.DEFERRED)))
        assertEquals(OutcomeKind.RAN, HistoryRebuildNotice.of(Outcome(OutcomeKind.RAN), setOf(OutcomeKind.DEFERRED))?.kind)
    }

    @Test fun `dismissals are kept for the process, one kind at a time`() {
        assertEquals(emptySet<OutcomeKind>(), HistoryRebuildNoticeDismissals.dismissed.value)
        HistoryRebuildNoticeDismissals.dismiss(OutcomeKind.DEFERRED)
        HistoryRebuildNoticeDismissals.dismiss(OutcomeKind.DEFERRED)
        assertEquals(setOf(OutcomeKind.DEFERRED), HistoryRebuildNoticeDismissals.dismissed.value)
        assertNull(HistoryRebuildNotice.of(Outcome(OutcomeKind.DEFERRED), HistoryRebuildNoticeDismissals.dismissed.value))
        assertEquals(OutcomeKind.RAN,
            HistoryRebuildNotice.of(Outcome(OutcomeKind.RAN), HistoryRebuildNoticeDismissals.dismissed.value)?.kind)
    }
}
