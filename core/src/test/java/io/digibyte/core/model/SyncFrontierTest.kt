package io.digibyte.core.model

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

    // ---- scan-frontier honesty -------------------------------------------------
    //
    // The defect these cover, in one line: filter HEADERS reaching the tip is not the same as
    // blocks having been SCANNED. On device the scan froze 25,928 blocks short while the UI
    // reported "Block 23,943,959" and Synced, so a payment in that band could never be seen.

    @Test
    fun scanFrontierBehind_isSyncing_evenWithHeadersAndCfTipAtTip() {
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP,
            scanFrontier = TIP - 25_928L,   // the measured freeze
        )
        assertEquals(SyncStage.Syncing, f.stage)
    }

    @Test
    fun scanFrontierBehind_barTracksTheScan_notTheHeaders() {
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP,
            scanFrontier = TIP - 25_928L,
        )
        // The bar must show the bottleneck, not sit at ~100% on headers.
        assertEquals(TIP - 25_928L, f.currentBlock)
        assertTrue("progress should be < 1 while a band is unscanned", f.progressFraction < 1.0f)
    }

    @Test
    fun scanFrontierAtTip_isSynced() {
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP,
            scanFrontier = TIP,
        )
        assertEquals(SyncStage.Synced, f.stage)
    }

    @Test
    fun scanFrontierWithinThreshold_isSynced_soItCannotFlapOnBlockArrival() {
        // A scan trailing by a few blocks is normal at the tip; 100 DGB blocks is ~25 min.
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP,
            scanFrontier = TIP - SCAN_BEHIND_THRESHOLD,
        )
        assertEquals(SyncStage.Synced, f.stage)
    }

    @Test
    fun scanFrontierUnknown_fallsBackToPreExistingBehaviour() {
        // 0 == the ledger has not reported (pre-startSync, or an older core). Must NOT trap the
        // wallet in Syncing forever — same convention as cfTip.
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP,
            scanFrontier = 0L,
        )
        assertEquals(SyncStage.Synced, f.stage)
    }

    @Test
    fun scanFrontierDefaultsToUnknown_soExistingCallersAreUnchanged() {
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP,
        )
        assertEquals(SyncStage.Synced, f.stage)
    }
}
