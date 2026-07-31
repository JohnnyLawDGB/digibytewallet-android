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
 * How far the compact-filter SCAN may trail the tip before we stop claiming "Synced".
 *
 * cfheaders arriving is not the same as blocks being scanned: the filter-header chain can sit at
 * the tip while a band below it was never evaluated against the wallet, which is precisely how a
 * permanently unscanned band once hid behind a "Synced" indicator while the wallet reported
 * "Block 23,943,959" — the scan had frozen 25,928 blocks short and nothing said so.
 *
 * Matched to the other two thresholds. The scan legitimately trails by an in-flight window during
 * catch-up, but at the tip it settles within a few blocks, and 100 DigiByte blocks is ~25 minutes,
 * so this cannot flap on ordinary block arrival.
 */
const val SCAN_BEHIND_THRESHOLD = 100L

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
 * @param scanFrontier  compact-filter SCAN frontier — the height through which filters
 *                      have actually been evaluated against the wallet
 *                      (getCfScanLedgerCounts()[0]), or 0 if unknown. Distinct from
 *                      [cfTip], which only says the filter HEADERS arrived. 0 keeps the
 *                      pre-existing behaviour, same convention as cfTip.
 */
fun deriveSyncFrontier(
    state: SyncState,
    peerCount: Int,
    currentHeight: Long,
    targetHeight: Long,
    externalTip: Long,
    cfTip: Long,
    scanFrontier: Long = 0L,
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

    // CF-first honesty: never claim "Synced" while cfheaders materially lags.
    // cfTip == 0 means CF hasn't started yet → fall back to header logic.
    val cfBehind = cfTip > 0 && effectiveTarget > 0 &&
        (effectiveTarget - cfTip) > CF_BEHIND_THRESHOLD
    // Scan honesty: cfheaders reaching the tip says the filter HEADERS arrived, not that any
    // block was looked at. Without this term "Synced" is satisfiable with an arbitrarily large
    // band never evaluated against the wallet — money in it is simply never seen, and the UI is
    // the only thing that could have said so. scanFrontier == 0 means the ledger has not
    // reported yet → fall back to the header/cfheader logic, same convention as cfTip.
    val scanBehind = scanFrontier > 0 && effectiveTarget > 0 &&
        (effectiveTarget - scanFrontier) > SCAN_BEHIND_THRESHOLD
    val behind = materiallyBehind || cfBehind || scanBehind

    val stage = when {
        state is SyncState.Failed -> SyncStage.Failed
        peerCount <= 0 -> SyncStage.Connecting
        behind -> SyncStage.Syncing
        state is SyncState.Complete -> SyncStage.Synced
        else -> SyncStage.Syncing
    }

    // The bottleneck frontier: the CF tip when cfheaders lags (so the bar moves
    // with filter catch-up instead of freezing at ~100% on headers), else headers.
    // Show the true bottleneck. The scan is the last stage, so when it lags it is what the bar
    // should track — otherwise the bar sits at ~100% on headers while the actual work continues.
    val frontier = when {
        scanBehind && scanFrontier > 0 -> scanFrontier
        cfBehind && cfTip > 0 -> cfTip
        else -> currentHeight
    }

    val progress = when {
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
    )
}
