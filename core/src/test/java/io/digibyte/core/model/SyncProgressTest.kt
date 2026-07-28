package io.digibyte.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paced-convoy fetch, spec Part E — the pure predicates behind the progress card
 * and the "Synced" gate.
 *
 * Two properties are load-bearing for the whole feature's safety argument
 * (operator GATE 3):
 *
 *  1. **The frontier numerator is the SCAN frontier.** The convoy deliberately
 *     holds the block-header and cfheader frontiers within CF_CONVOY_WINDOW of the
 *     compact-filter SCAN frontier, so neither the header tip nor cfTip indicates
 *     progress on a deep restore. Only `getLowestNeededHeight()` does.
 *
 *  2. **Synced must not fire over an UN-RECOVERED abandoned band, and MUST fire
 *     once recovery covers it.** The B2 valve can abandon a height that was
 *     actually servable (it can only prove refusal by the peers it is connected
 *     to). That residual is tolerable ONLY because the band stays visible and
 *     recoverable. "Synced" with a quietly short balance is the silent loss the
 *     valve exists to prevent — and a gate keyed on the monotonic `abandonedBelow`
 *     watermark instead (which NEITHER recovery path clears) would make recovery
 *     terminal: funds restored, wallet permanently non-Synced and permanently
 *     nagging. Hence the explicit recovered signal.
 */
class SyncProgressTest {

    private val TIP = 23_800_000L

    // ── (1) numerator is the SCAN frontier ────────────────────────────────────

    /** Convoy steady state on a deep restore: headers + cfheaders are pinned a
     *  full window ABOVE the scan, which is still ~1.9M blocks from the tip.
     *  Keying on either of those reports ~92%; the honest number is the scan's. */
    @Test
    fun numeratorIsScanFrontier_notHeaderTip_notCfTip() {
        val scan = 21_900_000L
        val windowTop = scan + 10_000L          // CF_CONVOY_WINDOW
        val f = deriveSyncFrontier(
            state = SyncState.Syncing(0.9f, windowTop),
            peerCount = 8,
            currentHeight = windowTop, targetHeight = TIP,
            externalTip = TIP, cfTip = windowTop,
            scanFrontier = scan,
        )
        assertEquals(SyncStage.Syncing, f.stage)
        assertEquals(scan, f.currentBlock)
        assertEquals(TIP, f.targetBlock)         // denominator stays the network tip
        assertEquals(scan.toFloat() / TIP.toFloat(), f.progressFraction, 0.0001f)
        // The wrong numerators, explicitly excluded.
        assertNotEquals(windowTop, f.currentBlock)
    }

    /** The scan frontier gates "Synced" too: headers and cfheaders sitting at the
     *  tip must not flip the wallet to Synced while the scan is a window behind. */
    @Test
    fun scanBehind_blocksSynced_evenWhenHeadersAndCfAreAtTip() {
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP,
            scanFrontier = TIP - 10_000L,
        )
        assertEquals(SyncStage.Syncing, f.stage)
        assertEquals(TIP - 10_000L, f.currentBlock)
    }

    /** scanFrontier == 0 (before ledger init / null peer manager) → fall back to
     *  cfTip, then to the raw header height. Pins BOTH rungs of the fallback. */
    @Test
    fun scanFrontierZero_fallsBackToCfTip_thenCurrentHeight() {
        val cfTip = TIP - 150_000L
        val viaCfTip = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = cfTip,
            scanFrontier = 0L,
        )
        assertEquals(SyncStage.Syncing, viaCfTip.stage)
        assertEquals(cfTip, viaCfTip.currentBlock)

        val header = 20_000_000L
        val viaHeader = deriveSyncFrontier(
            state = SyncState.Syncing(0.5f, header),
            peerCount = 5,
            currentHeight = header, targetHeight = TIP,
            externalTip = TIP, cfTip = 0L,
            scanFrontier = 0L,
        )
        assertEquals(SyncStage.Syncing, viaHeader.stage)
        assertEquals(header, viaHeader.currentBlock)
    }

    /** scanFrontier == 0 must NOT be read as "scan is at height 0, therefore
     *  ~0% and infinitely behind" — that would pin a healthy at-tip wallet at
     *  Syncing/0% for every poll before the ledger exists. */
    @Test
    fun scanFrontierZero_atTip_stillReportsSynced() {
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP,
            scanFrontier = 0L,
        )
        assertEquals(SyncStage.Synced, f.stage)
        assertEquals(1.0f, f.progressFraction, 0.0001f)
    }

    // ── (2) Synced gate vs. the abandoned band ────────────────────────────────

    /** GATE 3(i): an un-recovered abandoned band means part of the wallet's
     *  history was never verified. Reporting "Synced" there is exactly the silent
     *  loss the valve is allowed to risk only because it stays surfaced. */
    @Test
    fun syncedDoesNotFire_whileAbandonedBandUnrecovered() {
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP,
            scanFrontier = TIP,
            abandonedBandUnrecovered = true,
        )
        assertNotEquals(SyncStage.Synced, f.stage)
        assertEquals(SyncStage.Syncing, f.stage)
        // …and the caller can tell this apart from an ordinary catch-up, so the
        // UI can say "history gap" instead of pretending to still be scanning.
        assertTrue(f.abandonedBandHolding)
    }

    /** GATE 3(ii) — recovery is NOT terminal. Once the recovered signal is set
     *  (successful node reconcile over the band, or a full rescan), the very same
     *  inputs must flip to Synced. A gate keyed on `abandonedBelow` (monotonic,
     *  never cleared) would fail this and strand the wallet forever. */
    @Test
    fun syncedFires_onceAbandonedBandRecovered() {
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 8,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP,
            scanFrontier = TIP,
            abandonedBandUnrecovered = false,   // recovery covered it
        )
        assertEquals(SyncStage.Synced, f.stage)
        assertEquals(1.0f, f.progressFraction, 0.0001f)
        assertFalse(f.abandonedBandHolding)
    }

    /** The band-hold flag means "ONLY the band is holding it back". While the
     *  scan is genuinely behind as well, this is an ordinary sync, not a gap. */
    @Test
    fun abandonedBandHolding_isFalse_whileScanIsAlsoBehind() {
        val f = deriveSyncFrontier(
            state = SyncState.Syncing(0.5f, 21_000_000L),
            peerCount = 8,
            currentHeight = 21_000_000L, targetHeight = TIP,
            externalTip = TIP, cfTip = 21_000_000L,
            scanFrontier = 21_000_000L,
            abandonedBandUnrecovered = true,
        )
        assertEquals(SyncStage.Syncing, f.stage)
        assertFalse(f.abandonedBandHolding)
    }

    /** Zero peers still dominates: an un-recovered band must not disguise a
     *  disconnected wallet as "syncing". */
    @Test
    fun noPeers_stillConnecting_evenWithUnrecoveredBand() {
        val f = deriveSyncFrontier(
            state = SyncState.Complete,
            peerCount = 0,
            currentHeight = TIP, targetHeight = TIP,
            externalTip = TIP, cfTip = TIP,
            scanFrontier = TIP,
            abandonedBandUnrecovered = true,
        )
        assertEquals(SyncStage.Connecting, f.stage)
        assertFalse(f.abandonedBandHolding)
    }
}
