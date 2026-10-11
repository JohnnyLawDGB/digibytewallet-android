// Host KAT for BRSyncFrontier.h -- the CF-gated sync frontier every sync-status surface shares.
// Cases 1-15 are Android's SyncFrontierTest, case for case and value for value (progress compared
// EXACTLY, to float32 values computed outside this code, where the Kotlin test allows 0.0001).
// The rest cover what SyncFrontierTest does not: the scan frontier, the host states it never
// passes, the per-process session, Blocks Remaining, and wrap-around at the extremes.
//
// Built twice by run.sh:
//   -DSYNC_FRONTIER_RED  header-only (the filter scan ignored); every RED case fails
//   (default)            the shipped rule; every case passes

#include "BRSyncFrontier.h"
#include <stdio.h>

static int g_failed = 0;
static void result(const char *name, int pass)
{
    if (! pass) { printf("FAIL: %s\n", name); g_failed = 1; }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

#define TIP 23800000LL
#define S BRSyncStateSyncing
#define C BRSyncStateComplete

// deriveSyncFrontier's defaults: scanFrontier 0, no abandoned band, not reached this session.
static BRSyncFrontier d(BRSyncState st, float prog, int peers, int64_t cur, int64_t tgt, int64_t ext, int64_t cf)
{
    return BRSyncFrontierDerive(st, prog, peers, cur, tgt, ext, cf, 0, 0, 0);
}

int main(void)
{
    BRSyncFrontier f, g;

    // ---- Android's SyncFrontierTest ----
    f = d(S, 0.5f, 0, 11900000, TIP, TIP, 0);
    result("no_peers_is_connecting", f.stage == BRSyncStageConnecting);

    f = BRSyncFrontierDerive(BRSyncStateFailed, 0, 8, TIP, TIP, TIP, TIP, 0, 0, 0);
    result("failed_is_failed_even_with_peers", f.stage == BRSyncStageFailed);

    f = d(C, 0, 8, TIP, TIP, TIP, TIP - 150000);
    result("cf_lagging_while_header_complete", f.stage == BRSyncStageSyncing && f.currentBlock == TIP - 150000 &&
           f.targetBlock == TIP && f.progressFraction == 0x1.fcc5eap-1f);

    f = d(S, 0.5f, 5, 20000000, TIP, TIP, 0);
    result("header_behind_cf_not_started", f.stage == BRSyncStageSyncing && f.currentBlock == 20000000 &&
           f.progressFraction == 0x1.ae408ap-1f);

    f = d(C, 0, 8, TIP, TIP, TIP, TIP);
    result("caught_up_is_synced", f.stage == BRSyncStageSynced && f.progressFraction == 1.0f && f.currentBlock == TIP);

    f = d(C, 0, 8, TIP, TIP, 0, 0);
    result("cf_not_started_header_complete_is_synced", f.stage == BRSyncStageSynced && f.progressFraction == 1.0f);

    f = d(S, 0.9f, 5, 23700000, 23650000, TIP, 0);
    result("external_tip_over_stale_peer_target", f.targetBlock == TIP && f.stage == BRSyncStageSyncing &&
           f.progressFraction == 0x1.fdd946p-1f);

    f = d(C, 0, 8, TIP, TIP, TIP, TIP - BR_CF_BEHIND_THRESHOLD);
    g = d(C, 0, 8, TIP, TIP, TIP, TIP - BR_CF_BEHIND_THRESHOLD - 1);
    result("cf_behind_threshold_strict", f.stage == BRSyncStageSynced && g.stage == BRSyncStageSyncing &&
           g.progressFraction < 1.0f);

    f = BRSyncFrontierDerive(C, 0, 8, TIP, TIP + 10000000, TIP, TIP, 0, 0, 1);
    result("huge_target_after_synced_is_synced", f.stage == BRSyncStageSynced && f.targetBlock == TIP &&
           f.currentBlock == TIP && f.progressFraction == 1.0f);

    f = BRSyncFrontierDerive(C, 0, 8, TIP, TIP + 10000000, TIP, TIP, 0, 0, 0);
    result("huge_target_not_synced_this_session", f.stage == BRSyncStageSyncing && f.targetBlock == TIP + 10000000);

    f = BRSyncFrontierDerive(C, 0, 8, TIP, TIP + 30000, TIP, TIP, 0, 0, 1);
    result("plausible_lead_after_synced", f.stage == BRSyncStageSyncing && f.targetBlock == TIP + 30000 &&
           f.progressFraction == 0x1.ff5afep-1f);

    f = BRSyncFrontierDerive(C, 0, 8, TIP, TIP + BR_SYNC_TARGET_MAX_LEAD, TIP, TIP, 0, 0, 1);
    g = BRSyncFrontierDerive(C, 0, 8, TIP, TIP + BR_SYNC_TARGET_MAX_LEAD + 1, TIP, TIP, 0, 0, 1);
    result("lead_bound_strict", f.stage == BRSyncStageSyncing && f.targetBlock == TIP + BR_SYNC_TARGET_MAX_LEAD &&
           g.stage == BRSyncStageSynced && g.targetBlock == TIP);

    f = BRSyncFrontierDerive(S, 0.9f, 8, TIP, TIP + 10000000, TIP, TIP, 0, 0, 1);
    result("huge_target_requires_complete", f.stage == BRSyncStageSyncing && f.targetBlock == TIP + 10000000);

    f = BRSyncFrontierDerive(C, 0, 8, TIP, TIP + 10000000, TIP, TIP - 150000, 0, 0, 1);
    result("huge_target_cf_lag_still_syncing", f.stage == BRSyncStageSyncing && f.currentBlock == TIP - 150000 &&
           f.targetBlock == TIP);

    f = BRSyncFrontierDerive(C, 0, 8, TIP, TIP + 10000000, TIP, TIP, 0, 1, 1);
    result("huge_target_abandoned_band_holds", f.stage == BRSyncStageSyncing && f.abandonedBandHolding == 1);

    // ---- beyond SyncFrontierTest ----
    // A deep restore under the paced convoy: headers and filter headers near the tip, the scan
    // millions of blocks down. The scan is the frontier; Complete (sticky) must not read Synced.
    f = BRSyncFrontierDerive(C, 0, 8, TIP, TIP, TIP, TIP - 5000, 10000000, 0, 0);
    result("scan_frontier_governs_a_deep_restore", f.stage == BRSyncStageSyncing && f.currentBlock == 10000000 &&
           f.progressFraction == 0x1.ae408ap-2f);

    {   // the host states SyncFrontierTest never passes
        BRSyncFrontier idle = d(BRSyncStateIdle, 0.7f, 3, 0, 0, 0, 0);
        BRSyncFrontier resc = d(BRSyncStateRescanning, 0.7f, 3, 0, 0, 0, 0);
        BRSyncFrontier split = d(BRSyncStateChainSplit, 0, 3, TIP, TIP, TIP, TIP);
        BRSyncFrontier own = d(S, 0.25f, 3, 0, 0, 0, 0);
        BRSyncFrontier failedNoPeers = d(BRSyncStateFailed, 0, 0, 0, 0, 0, 0);
        result("host_states", idle.stage == BRSyncStageSyncing && idle.progressFraction == 0.0f &&
               resc.stage == BRSyncStageSyncing && resc.progressFraction == 0.0f &&
               split.stage == BRSyncStageSyncing && split.progressFraction == 1.0f &&
               own.stage == BRSyncStageSyncing && own.progressFraction == 0.25f &&
               failedNoPeers.stage == BRSyncStageFailed);
    }
    {   // the session latch is set AFTER a derivation: the first poll does not see its own Synced
        BRSyncFrontierSession s = {0, 0}, fresh = {0, 0};
        BRSyncFrontier a = BRSyncFrontierStep(&fresh, C, 0, 8, TIP, TIP + 10000000, TIP, 0, 0);
        BRSyncFrontier b, c;
        int before = s.reachedSynced;
        b = BRSyncFrontierStep(&s, C, 0, 8, TIP, TIP, TIP, 0, 0);
        c = BRSyncFrontierStep(&s, C, 0, 8, TIP, TIP + 10000000, TIP, 0, 0);
        result("session_latches_after", a.stage == BRSyncStageSyncing && fresh.reachedSynced == 0 && before == 0 &&
               b.stage == BRSyncStageSynced && s.reachedSynced == 1 &&
               c.stage == BRSyncStageSynced && c.targetBlock == TIP);
    }
    {   // the running header maximum never drops (a rescan moving the header back keeps the target)
        BRSyncFrontierSession s = {0, 0};
        BRSyncFrontierStep(&s, S, 0, 8, 2000, 0, 0, 0, 0);
        f = BRSyncFrontierStep(&s, S, 0, 8, 1000, 0, 0, 0, 0);
        result("session_header_max_never_drops", s.headerHeightMax == 2000 && f.targetBlock == 2000 &&
               f.stage == BRSyncStageSyncing && f.progressFraction == 0.5f);
    }
    {   // an unknown estimate (0): the header height stands in
        BRSyncFrontierSession s = {0, 0};
        f = BRSyncFrontierStep(&s, C, 0, 8, TIP, 0, TIP, 0, 0);
        result("estimate_zero_uses_header", f.stage == BRSyncStageSynced && f.targetBlock == TIP);
    }
    {   // Network Info's Blocks Remaining
        BRSyncFrontier synced = {BRSyncStageSynced, TIP - 3, TIP, 1.0f, 0};
        BRSyncFrontier behind = {BRSyncStageSyncing, 10000000, TIP, 0.4f, 0};
        BRSyncFrontier ahead = {BRSyncStageSyncing, TIP, TIP - 1, 1.0f, 0};
        BRSyncFrontier none = {BRSyncStageConnecting, 0, 0, 0.0f, 0};
        result("blocks_remaining", BRSyncFrontierBlocksRemaining(synced) == 0 &&
               BRSyncFrontierBlocksRemaining(behind) == TIP - 10000000 &&
               BRSyncFrontierBlocksRemaining(ahead) == 0 && BRSyncFrontierBlocksRemaining(none) == 0);
    }
    {   // extremes wrap as Kotlin's Long does, with no signed-overflow UB (UBSan arm)
        BRSyncFrontier odd = {BRSyncStageSyncing, -10, INT64_MAX, 0.0f, 0};
        f = BRSyncFrontierDerive(S, 0.5f, 8, INT64_MAX, INT64_MAX, INT64_MAX, INT64_MAX, INT64_MAX, 0, 1);
        result("extremes_wrap_like_kotlin", BRSyncFrontierBlocksRemaining(odd) == INT64_MIN + 9 &&
               f.stage == BRSyncStageSyncing && f.progressFraction == 1.0f);
    }

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
