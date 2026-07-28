package io.digibyte.core.model

/**
 * Single source of truth for the wallet's CF-gated sync frontier.
 *
 * DigiByte is compact-filters-only: in that mode the FUNCTIONAL sync frontier
 * is the compact-filter chain tip ([cfTip]) — deposits and confirmations are
 * only detected up to cfTip, NOT the header height, which races to the tip via
 * every peer. Reporting "Synced" while cfheaders lags the header chain is
 * exactly how a deposit gets silently missed while the UI says "Up to date".
 *
 * Every surface that shows sync status (the main [SyncProgressInfo] card, the
 * Network Info screen, the DigiRunner overlay) derives from [deriveSyncFrontier],
 * so they share one CF-gated STAGE definition and can't categorically diverge.
 * Previously this logic lived inline in WalletViewModel and Network Info used a
 * separate header-based path — the two drifted, and Network Info showed "Synced"
 * while the wallet was still catching up (the missed-deposit trap). Note the
 * screens still poll the native tip in their own ViewModels, so the exact block
 * NUMBER can differ by up to one poll interval during active sync; it converges
 * in steady state. (A single shared source would make the numbers identical too
 * — see the v3.10.22 follow-up.)
 */
data class SyncFrontier(
    /** The user-facing stage — the same [SyncStage] the main card renders. */
    val stage: SyncStage,
    /** The bottleneck frontier: cfTip when cfheaders is what we're waiting on,
     *  else the header height. This is the honest "Block X" to display. */
    val currentBlock: Long,
    /** The effective chain tip — the authoritative external tip when known,
     *  else the peer-quorum estimate. Never regresses below the seen tip. */
    val targetBlock: Long,
    /** 0.0–1.0, tracking [currentBlock] toward [targetBlock]. */
    val progressFraction: Float,
    /** True iff the ONLY thing keeping this out of [SyncStage.Synced] is an
     *  un-recovered abandoned compact-filter band. Everything else (headers, the
     *  CF scan frontier, peers, [SyncState.Complete]) says "done" — the band is
     *  the hold. Lets callers distinguish "still grinding" from "caught up, but a
     *  slice of history was never verified", and lets the anti-flash balance
     *  latch treat this as reached-synced-once. */
    val abandonedBandHolding: Boolean = false,
)

/** Blocks-behind-tip past which the UI honestly shows catch-up progress
 *  instead of "Synced", even if the header-based [SyncState.Complete] latched.
 *  Well above normal tip lag (a handful of 15s blocks) so steady state never
 *  flickers; far below any real re-sync (hundreds of thousands). */
const val SYNC_BEHIND_THRESHOLD = 100L

/** CF-only: the wallet is not FUNCTIONALLY synced until the compact-filter
 *  chain tip is within this many blocks of the header tip — tx/deposit
 *  detection only reaches cfTip. */
const val CF_BEHIND_THRESHOLD = 100L

/**
 * Derive the CF-gated sync frontier from raw inputs. Pure and deterministic —
 * the single place this logic lives.
 *
 * @param state         header-based sync state produced by SyncService
 * @param peerCount     live SPV peer count
 * @param currentHeight raw header height (getLastBlockHeight)
 * @param targetHeight  peer-quorum estimated height (getEstimatedBlockHeight)
 * @param externalTip   stable sync-target tip — a native monotonic high-water
 *                      mark of ONLY the PoW-validated header height (never
 *                      regresses, un-inflatable), or 0 if unknown. No external
 *                      call. The peer estimate is passed live as [targetHeight]
 *                      (this fn maxes them), so a spiked estimate self-heals.
 * @param cfTip         compact-filter chain tip (getCFChainTipHeight), or 0 if
 *                      CF hasn't started this session
 * @param scanFrontier  compact-filter SCAN frontier (`getLowestNeededHeight()`),
 *                      or 0 before the ledger is initialised / the native peer
 *                      manager exists. Under the paced convoy this — NOT the
 *                      header tip, NOT [cfTip] — is the only thing that indicates
 *                      progress: the convoy deliberately holds the header and
 *                      cfheader frontiers within CF_CONVOY_WINDOW of it.
 * @param abandonedBandUnrecovered
 *                      true while a compact-filter band was ABANDONED by the B2
 *                      valve and neither recovery path (node reconcile / full
 *                      rescan) has covered it yet. NOT `abandonedBelow > 0`:
 *                      that watermark is a monotonic hard floor no recovery
 *                      clears, so keying on it directly makes recovery terminal.
 */
fun deriveSyncFrontier(
    state: SyncState,
    peerCount: Int,
    currentHeight: Long,
    targetHeight: Long,
    externalTip: Long,
    cfTip: Long,
    scanFrontier: Long = 0L,
    abandonedBandUnrecovered: Boolean = false,
): SyncFrontier {
    // Prefer the authoritative external tip when available; fall back to the
    // peer-quorum target only when the fetch has never succeeded. Never let the
    // effective target regress — once we've seen the real tip we trust it over
    // any lower peer claim.
    val effectiveTarget = if (externalTip > targetHeight) externalTip else targetHeight

    // Honest progress: if the real header height is materially behind the tip,
    // surface catch-up even if SyncState.Complete latched.
    val materiallyBehind = currentHeight > 0 && effectiveTarget > 0 &&
        (effectiveTarget - currentHeight) > SYNC_BEHIND_THRESHOLD

    // PACED-CONVOY RE-KEY (spec Part E). The functional frontier is the compact-
    // filter SCAN frontier, not cfTip: the convoy deliberately runs the header and
    // cfheader frontiers a full CF_CONVOY_WINDOW (10000) AHEAD of the scan, so on a
    // deep restore both of them report ~100% while the scan is still millions of
    // blocks down. Fall back cfTip-then-currentHeight only when the scan frontier is
    // unavailable (0 = ledger not initialised yet / null native peer manager),
    // which is the pre-convoy behaviour this replaces.
    val effectiveScan = when {
        scanFrontier > 0 -> scanFrontier
        cfTip > 0 -> cfTip
        else -> currentHeight
    }

    // CF-first honesty: never claim "Synced" while the filter SCAN materially lags —
    // tx/deposit detection only reaches that height. effectiveScan == 0 means nothing
    // is known yet → fall back to header logic rather than reading it as "at genesis".
    val cfBehind = effectiveScan > 0 && effectiveTarget > 0 &&
        (effectiveTarget - effectiveScan) > CF_BEHIND_THRESHOLD

    // GATE 3 (spec Part E, blocker-fix B-2): an abandoned compact-filter band that no
    // recovery has covered means a slice of history was NEVER verified — the wallet's
    // balance may be quietly short. It must not read as "Synced". Note this keys on the
    // RECOVERED signal, never on the native `abandonedBelow` watermark: that watermark
    // is a monotonic hard floor which NEITHER recovery path clears, so gating on it
    // would make recovery terminal (funds restored by a reconcile, wallet permanently
    // non-Synced and permanently nagging).
    val behind = materiallyBehind || cfBehind || abandonedBandUnrecovered

    val stage = when {
        state is SyncState.Failed -> SyncStage.Failed
        peerCount <= 0 -> SyncStage.Connecting
        behind -> SyncStage.Syncing
        state is SyncState.Complete -> SyncStage.Synced
        else -> SyncStage.Syncing
    }

    // True when the band — and nothing else — is what holds Synced back. Lets the UI
    // say "history gap, tap to scan" instead of pretending a finished scan is still
    // running, and lets the anti-flash balance latch treat this as reached-synced-once
    // (otherwise a wallet with an un-recovered band could never trust a genuine
    // spend-to-zero).
    val abandonedBandHolding = abandonedBandUnrecovered && !materiallyBehind &&
        !cfBehind && peerCount > 0 && state is SyncState.Complete

    // The bottleneck frontier to DISPLAY: the scan frontier whenever the filter layer
    // is what we're waiting on, else the header height.
    val frontier = if (cfBehind && effectiveScan > 0) effectiveScan else currentHeight

    val progress = when {
        abandonedBandHolding -> 1.0f   // everything scanned except the surfaced band
        behind && effectiveTarget > 0 ->
            (frontier.toFloat() / effectiveTarget.toFloat()).coerceIn(0f, 1f)
        state is SyncState.Complete -> 1.0f
        currentHeight > 0 && effectiveTarget > 0 ->
            (currentHeight.toFloat() / effectiveTarget.toFloat()).coerceIn(0f, 1f)
        state is SyncState.Syncing -> state.progress
        else -> 0.0f
    }

    return SyncFrontier(
        stage = stage,
        currentBlock = frontier,
        targetBlock = effectiveTarget,
        progressFraction = progress.coerceIn(0f, 1f),
        abandonedBandHolding = abandonedBandHolding,
    )
}
