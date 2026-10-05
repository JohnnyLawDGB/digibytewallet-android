package app.aroundtheblock.wallet.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the CF-gated sync derivation extracted from WalletViewModel (the
 * v3.10.20 behavior proven on-device). These vectors are the contract every
 * sync-status surface now shares via [deriveSyncFrontier]; a change that
 * breaks one of them changes what "Synced" means and must be deliberate.
 */
class SyncFrontierTest {

    private val TIP = 23_800_000L

    @Test
    fun noPeers_isConnecting_regardlessOfState() {
        val f = deriveSyncFrontier(
            state = SyncState.Syncing(0.5f, 11_900_000L),
            peerCount = 0,
            currentHeight = 11_900_000L, targetHeight = TIP,
            externalTip = TIP, cfTip = 0L,
        )
        assertEquals(SyncStage.Connecting, f.stage)
    }

    @Test
    fun failedState_isFailed_evenWithPeers() {
        val f = deriveSyncFrontier(
            state = SyncState.Failed(1, "boom"),
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP,
        )
        assertEquals(SyncStage.Failed, f.stage)
    }

    @Test
    fun cfLagging_whileHeaderComplete_showsSyncing_onCfFrontier() {
        // Headers at tip, SyncState.Complete latched, but cfheaders 150k behind:
        // this is the missed-deposit case — must NOT report Synced.
        val cfTip = TIP - 150_000L
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = cfTip,
        )
        assertEquals(SyncStage.Syncing, f.stage)
        assertEquals(cfTip, f.currentBlock)          // bottleneck frontier = cfTip
        assertEquals(TIP, f.targetBlock)
        assertEquals(cfTip.toFloat() / TIP.toFloat(), f.progressFraction, 0.0001f)
    }

    @Test
    fun headerBehind_cfNotStarted_showsSyncing_onHeaderFrontier() {
        val header = 20_000_000L
        val f = deriveSyncFrontier(
            state = SyncState.Syncing(0.5f, header),
            peerCount = 5,
            currentHeight = header, targetHeight = TIP,
            externalTip = TIP, cfTip = 0L,           // CF hasn't started
        )
        assertEquals(SyncStage.Syncing, f.stage)
        assertEquals(header, f.currentBlock)         // cfBehind false → header frontier
        assertEquals(header.toFloat() / TIP.toFloat(), f.progressFraction, 0.0001f)
    }

    @Test
    fun caughtUp_headerAndCf_atTip_isSynced() {
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP,
        )
        assertEquals(SyncStage.Synced, f.stage)
        assertEquals(1.0f, f.progressFraction, 0.0001f)
        assertEquals(TIP, f.currentBlock)
    }

    @Test
    fun cfNotStarted_headerAtTip_complete_fallsBackToSynced() {
        // externalTip unknown (0) and cfTip 0 → header logic governs.
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = 0L, cfTip = 0L,
        )
        assertEquals(SyncStage.Synced, f.stage)
        assertEquals(1.0f, f.progressFraction, 0.0001f)
    }

    @Test
    fun externalTip_preferredOverStalePeerTarget() {
        // Peer quorum reports a stale low target; the authoritative external
        // tip must win as the denominator (and drive materiallyBehind).
        val header = 23_700_000L
        val stalePeerTarget = 23_650_000L
        val f = deriveSyncFrontier(
            state = SyncState.Syncing(0.9f, header),
            peerCount = 5,
            currentHeight = header, targetHeight = stalePeerTarget,
            externalTip = TIP, cfTip = 0L,
        )
        assertEquals(TIP, f.targetBlock)
        assertEquals(SyncStage.Syncing, f.stage)     // 100k behind the real tip
        assertEquals(header.toFloat() / TIP.toFloat(), f.progressFraction, 0.0001f)
    }

    @Test
    fun cfBehindThreshold_isStrict() {
        // Exactly CF_BEHIND_THRESHOLD behind → NOT behind (strict >). One more → behind.
        val atThreshold = deriveSyncFrontier(
            state = SyncState.Complete, peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP - CF_BEHIND_THRESHOLD,
        )
        assertEquals(SyncStage.Synced, atThreshold.stage)

        val overThreshold = deriveSyncFrontier(
            state = SyncState.Complete, peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP - CF_BEHIND_THRESHOLD - 1,
        )
        assertEquals(SyncStage.Syncing, overThreshold.stage)
        assertTrue(overThreshold.progressFraction < 1.0f)
    }

    // ---- an unproven target does not hold the Send gate after a completed session ----------
    // The native estimate is the connected peers' agreed height, bounded by plausible growth; a
    // target a week's worth of blocks above a height THIS session already declared Synced is a
    // residual outlier, not a real deficit. The rule is gated on reachedSyncedThisSession because
    // a cold start after a month offline is identical by magnitude (Complete is sticky in prefs).

    @Test
    fun hugeTarget_afterSyncedThisSession_isSynced_targetIsHeaderHeight() {
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP + 10_000_000L,
            externalTip = TIP, cfTip = TIP,
            reachedSyncedThisSession = true,
        )
        assertEquals(SyncStage.Synced, f.stage)
        assertEquals(TIP, f.targetBlock)
        assertEquals(TIP, f.currentBlock)
        assertEquals(1.0f, f.progressFraction, 0.0001f)
    }

    @Test
    fun hugeTarget_notSyncedThisSession_isSyncing() {
        // Cold start after a month offline: Complete restored sticky from prefs, header height
        // a month behind, honest peers far ahead. Nothing distinguishes this from an outlier target by
        // magnitude — it must keep showing catch-up progress.
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP + 10_000_000L,
            externalTip = TIP, cfTip = TIP,
            reachedSyncedThisSession = false,
        )
        assertEquals(SyncStage.Syncing, f.stage)
        assertEquals(TIP + 10_000_000L, f.targetBlock)
    }

    @Test
    fun plausibleLead_afterSyncedThisSession_isSyncing() {
        // Asleep for ~5 days: 30_000 blocks is honest catch-up, well inside the lead bound.
        val target = TIP + 30_000L
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = target,
            externalTip = TIP, cfTip = TIP,
            reachedSyncedThisSession = true,
        )
        assertEquals(SyncStage.Syncing, f.stage)
        assertEquals(target, f.targetBlock)
        assertEquals(TIP.toFloat() / target.toFloat(), f.progressFraction, 0.0001f)
    }

    @Test
    fun leadBound_isStrict() {
        // Exactly SYNC_TARGET_MAX_LEAD ahead → still a real target (Syncing). One more → unproven.
        val atBound = deriveSyncFrontier(
            state = SyncState.Complete, peerCount = 8,
            currentHeight = TIP, targetHeight = TIP + SYNC_TARGET_MAX_LEAD,
            externalTip = TIP, cfTip = TIP,
            reachedSyncedThisSession = true,
        )
        assertEquals(SyncStage.Syncing, atBound.stage)
        assertEquals(TIP + SYNC_TARGET_MAX_LEAD, atBound.targetBlock)

        val overBound = deriveSyncFrontier(
            state = SyncState.Complete, peerCount = 8,
            currentHeight = TIP, targetHeight = TIP + SYNC_TARGET_MAX_LEAD + 1,
            externalTip = TIP, cfTip = TIP,
            reachedSyncedThisSession = true,
        )
        assertEquals(SyncStage.Synced, overBound.stage)
        assertEquals(TIP, overBound.targetBlock)
    }

    @Test
    fun hugeTarget_afterSyncedThisSession_requiresCompleteState() {
        // Header sync still in progress (not Complete): the lead rule does not apply.
        val f = deriveSyncFrontier(
            state = SyncState.Syncing(0.9f, TIP),
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP + 10_000_000L,
            externalTip = TIP, cfTip = TIP,
            reachedSyncedThisSession = true,
        )
        assertEquals(SyncStage.Syncing, f.stage)
        assertEquals(TIP + 10_000_000L, f.targetBlock)
    }

    @Test
    fun hugeTarget_afterSyncedThisSession_cfLagStillShowsSyncing() {
        // The unproven target is replaced by the header height, but a compact-filter scan that
        // materially lags that header height is still the missed-deposit case: not Synced.
        val cfTip = TIP - 150_000L
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP + 10_000_000L,
            externalTip = TIP, cfTip = cfTip,
            reachedSyncedThisSession = true,
        )
        assertEquals(SyncStage.Syncing, f.stage)
        assertEquals(cfTip, f.currentBlock)
        assertEquals(TIP, f.targetBlock)
    }

    @Test
    fun hugeTarget_afterSyncedThisSession_abandonedBandStillHolds() {
        // GATE 3 is unaffected: an un-recovered band still withholds Synced, and is reported as
        // the only thing holding it.
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP + 10_000_000L,
            externalTip = TIP, cfTip = TIP,
            abandonedBandUnrecovered = true,
            reachedSyncedThisSession = true,
        )
        assertEquals(SyncStage.Syncing, f.stage)
        assertTrue(f.abandonedBandHolding)
    }
}
