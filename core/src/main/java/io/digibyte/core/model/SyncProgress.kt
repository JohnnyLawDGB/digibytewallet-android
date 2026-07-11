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
 * Derive the CF-gated sync frontier from raw inputs. Pure and deterministic —
 * the single place this logic lives.
 *
 * @param state         header-based sync state produced by SyncService
 * @param peerCount     live SPV peer count
 * @param currentHeight raw header height (getLastBlockHeight)
 * @param targetHeight  peer-quorum estimated height (getEstimatedBlockHeight)
 * @param externalTip   authoritative tip from ChainTipFetcher, or 0 if unknown
 * @param cfTip         compact-filter chain tip (getCFChainTipHeight), or 0 if
 *                      CF hasn't started this session
 */
fun deriveSyncFrontier(
    state: SyncState,
    peerCount: Int,
    currentHeight: Long,
    targetHeight: Long,
    externalTip: Long,
    cfTip: Long,
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
    val behind = materiallyBehind || cfBehind

    val stage = when {
        state is SyncState.Failed -> SyncStage.Failed
        peerCount <= 0 -> SyncStage.Connecting
        behind -> SyncStage.Syncing
        state is SyncState.Complete -> SyncStage.Synced
        else -> SyncStage.Syncing
    }

    // The bottleneck frontier: the CF tip when cfheaders lags (so the bar moves
    // with filter catch-up instead of freezing at ~100% on headers), else headers.
    val frontier = if (cfBehind && cfTip > 0) cfTip else currentHeight

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
