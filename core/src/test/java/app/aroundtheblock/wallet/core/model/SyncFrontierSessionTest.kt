package app.aroundtheblock.wallet.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-wide "reached Synced this session" latch ([SyncFrontierSession]) that every
 * sync-status screen derives through. The lead rule itself is [SyncFrontierTest]'s; this is
 * what makes two screens fed the same inputs read the same stage.
 */
class SyncFrontierSessionTest {

    private val tip = 24_000_000L
    private val outlier = tip + SYNC_TARGET_MAX_LEAD + 1

    private fun SyncFrontierSession.atTip(target: Long = tip) = derive(
        state = SyncState.Complete, peerCount = 8,
        currentHeight = tip, targetHeight = target, externalTip = tip, cfTip = tip, scanFrontier = tip,
    )

    @Test fun `a second screen sharing the session reads what the first one does`() {
        // The main card reaches Synced, then a peer reports a target past the lead rule.
        // Network Info, derived through the same session from the same inputs, must agree.
        val session = SyncFrontierSession()
        assertEquals(SyncStage.Synced, session.atTip().stage)
        val mainCard = session.atTip(outlier)
        val networkInfo = session.atTip(outlier)
        assertEquals(SyncStage.Synced, mainCard.stage)
        assertEquals(mainCard, networkInfo)
    }

    @Test fun `the latch the old Network Info path lacked is what decides the stage`() {
        // The disagreement this session removes: the same inputs, with and without the latch.
        val withLatch = deriveSyncFrontier(
            SyncState.Complete, 8, tip, outlier, tip, tip, tip, reachedSyncedThisSession = true,
        )
        val withoutLatch = deriveSyncFrontier(SyncState.Complete, 8, tip, outlier, tip, tip, tip)
        assertEquals(SyncStage.Synced, withLatch.stage)
        assertEquals(SyncStage.Syncing, withoutLatch.stage)
    }

    @Test fun `a fresh session does not treat a far target as unproven`() {
        // Cold start after a long time offline: Complete is sticky, the peers are far ahead.
        val session = SyncFrontierSession()
        assertFalse(session.reachedSynced)
        assertEquals(SyncStage.Syncing, session.atTip(outlier).stage)
        assertFalse(session.reachedSynced)
    }

    @Test fun `the latch is read before the derivation and set after it`() {
        val session = SyncFrontierSession()
        // The first Synced derivation sees the previous (false) latch, and only then sets it.
        val first = session.atTip()
        assertEquals(SyncStage.Synced, first.stage)
        assertTrue(session.reachedSynced)
    }

    @Test fun `the abandoned band hold latches as Synced does`() {
        val session = SyncFrontierSession()
        val held = session.derive(
            state = SyncState.Complete, peerCount = 8,
            currentHeight = tip, targetHeight = tip, externalTip = tip, cfTip = tip, scanFrontier = tip,
            abandonedBandUnrecovered = true,
        )
        assertEquals(SyncStage.Syncing, held.stage)
        assertTrue(held.abandonedBandHolding)
        assertTrue(session.reachedSynced)
    }

    @Test fun `reset returns the session to a fresh process`() {
        val session = SyncFrontierSession()
        session.atTip()
        session.reset()
        assertFalse(session.reachedSynced)
        assertEquals(SyncStage.Syncing, session.atTip(outlier).stage)
    }
}
