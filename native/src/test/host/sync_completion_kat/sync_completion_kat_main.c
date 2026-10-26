// Host KAT for BRSyncCompletion.h -- when the wallet may count itself synced. The poll and
// callback cases are written from SyncService's keepalive loop and onSyncComplete (no Kotlin test
// covers those); the commit cases are CfRestorePreflightTest's shouldMarkSynced, case for case.
//
// Built twice by run.sh:
//   -DSYNC_COMPLETION_RED  the header tip alone (the filter scan ignored); every RED case fails
//   (default)              the shipped rule; every case passes

#include "BRSyncCompletion.h"
#include <stdio.h>

static int g_failed = 0;
static void result(const char *name, int pass)
{
    if (! pass) { printf("FAIL: %s\n", name); g_failed = 1; }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

#define CP  24350000LL     // the newest checkpoint in the pinned core's table (BRChainParams.h),
                           // and SyncService.LATEST_CHECKPOINT_HEIGHT
#define TIP 24363000LL

static int polls(BRSyncCompletionState *s, int n, int64_t h, int64_t est, int64_t scan)
{
    int i, last = 0;
    for (i = 0; i < n; i++) last = BRSyncCompletionPollStep(s, h, est, scan, CP);
    return last;
}

int main(void)
{
    {   BRSyncCompletionState s = { 0 };
        int a = BRSyncCompletionPollStep(&s, TIP, TIP, TIP + 1, CP);
        int b = BRSyncCompletionPollStep(&s, TIP, TIP, TIP + 1, CP);
        int c = BRSyncCompletionPollStep(&s, TIP, TIP, TIP + 1, CP);
        result("three_real_tip_polls_mark_synced", a == 0 && b == 0 && c == 1);
    }
    {   BRSyncCompletionState s = { 0 };   // 60 817 blocks unscanned at the header tip (Note 8, 2026-08-02)
        result("headers_at_tip_scan_behind_is_not_synced", polls(&s, 5, TIP, TIP, TIP - 60817) == 0 && s.atTipPolls == 0);
    }
    {   BRSyncCompletionState s = { 0 };
        result("unknown_scan_frontier_is_never_caught_up", polls(&s, 5, TIP, TIP, 0) == 0);
    }
    {   BRSyncCompletionState s = { 0 }, t = { 0 };
        result("scan_slack_is_inclusive",
               polls(&s, 3, TIP, TIP, TIP - BR_SYNC_CF_TIP_SLACK + 1) == 1 &&     // last scanned = TIP - 10
               polls(&t, 3, TIP, TIP, TIP - BR_SYNC_CF_TIP_SLACK) == 0);          // last scanned = TIP - 11
    }
    {   BRSyncCompletionState s = { 0 }, t = { 0 };   // the scan is measured against the ESTIMATE, not the headers
        // Headers 5 behind the peers' estimate (inside the header slack). Last scanned TIP - 8 is
        // within 10 of the header height but 13 behind the estimate: not caught up (SyncService:
        // scanFrontier - 1 >= estHeight - CF_TIP_SLACK). TIP - 5 is exactly 10 behind it: caught up.
        result("scan_slack_is_from_the_estimate",
               polls(&s, 5, TIP, TIP + 5, TIP - 7) == 0 && s.atTipPolls == 0 &&
               polls(&t, 3, TIP, TIP + 5, TIP - 4) == 1);
    }
    {   BRSyncCompletionState s = { 0 }, t = { 0 };
        result("header_slack_is_inclusive",
               polls(&s, 3, TIP - 5, TIP, TIP) == 1 && polls(&t, 3, TIP - 6, TIP, TIP) == 0);
    }
    {   BRSyncCompletionState s = { 0 }, t = { 0 };   // a cohort of stale peers agreeing on a low tip
        result("below_the_newest_checkpoint_is_not_the_tip",
               polls(&s, 5, CP - 1, CP - 1, CP) == 0 && polls(&t, 3, CP, CP, CP + 1) == 1);
    }
    {   BRSyncCompletionState s = { 0 };
        int a = polls(&s, 2, TIP, TIP, TIP + 1);
        int off = BRSyncCompletionPollStep(&s, TIP, TIP + 100, TIP + 1, CP);   // fell behind for one poll
        int b = polls(&s, 2, TIP, TIP, TIP + 1), c = BRSyncCompletionPollStep(&s, TIP, TIP, TIP + 1, CP);
        result("one_poll_off_the_tip_starts_again", a == 0 && off == 0 && b == 0 && c == 1);
    }
    {   BRSyncCompletionState s = { 0 };
        int a = polls(&s, 3, TIP, TIP, TIP + 1);
        BRSyncCompletionCheckpointFailed(&s);
        int b = BRSyncCompletionPollStep(&s, TIP, TIP, TIP + 1, CP), c = polls(&s, 2, TIP, TIP, TIP + 1);
        result("a_failed_checkpoint_counts_again", a == 1 && b == 0 && c == 1);
    }
    {   BRSyncCompletionState s = { 0 };
        result("unknown_heights_never_mark", polls(&s, 5, 0, TIP, TIP + 1) == 0 && polls(&s, 5, TIP, 0, TIP + 1) == 0);
    }
    result("callback_marks_at_the_scanned_tip", BRSyncCompletionOnSyncStopped(TIP, TIP + 1, CP) == 1 &&
           BRSyncCompletionOnSyncStopped(TIP, TIP - BR_SYNC_CF_TIP_SLACK + 1, CP) == 1);
    result("callback_scan_behind_is_ignored", BRSyncCompletionOnSyncStopped(TIP, TIP - 60817, CP) == 0 &&
           BRSyncCompletionOnSyncStopped(TIP, TIP - BR_SYNC_CF_TIP_SLACK, CP) == 0);
    result("callback_unknown_scan_is_ignored", BRSyncCompletionOnSyncStopped(TIP, 0, CP) == 0);
    result("callback_below_the_checkpoint_is_ignored", BRSyncCompletionOnSyncStopped(CP - 1, CP, CP) == 0);
    result("callback_height_zero_passes_as_android", BRSyncCompletionOnSyncStopped(0, 0, CP) == 1);
    result("commit_defers_only_for_an_undurable_pending_ledger",
           BRSyncCompletionMayCommit(1, 0) == 0 && BRSyncCompletionMayCommit(1, 1) == 1 &&
           BRSyncCompletionMayCommit(0, 0) == 1 && BRSyncCompletionMayCommit(0, 1) == 1);

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
