// Host KAT for BRSyncWatchdog.h -- the BIP158 and tip-stall watchdogs' decisions. Carries
// Android's Bip158WatchdogPolicyTest.kt case for case (same names, same values); the Kotlin
// named-argument wrappers become designated initializers whose later fields override the
// defaults. The tier ordering of SyncService.startTipStallWatchdog (not unit-tested in Kotlin)
// is checked from its code in the decide_* cases.
//
// Built twice by run.sh:
//   -DSYNC_WATCHDOG_RED  the two historical bugs: unbounded suppression (pending > 0, fix wave C2)
//                        and a forward-only frontier tracker; every RED case fails
//   (default)            the shipped rule; every case passes

#include "BRSyncWatchdog.h"
#include <stdio.h>

static int g_failed = 0;
static void result(const char *name, int pass)
{
    if (! pass) { printf("FAIL: %s\n", name); g_failed = 1; }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

#define SCAN_FROZEN (10LL * 60 * 1000)
#define SCAN_ADVANCING 0LL
#define SCAN_PINNED_PAST_CEILING (60LL * 60 * 1000)
#define SUPP BR_WD_SUPPRESSION_MAX_MS_FALLBACK
#define TIP BR_WD_TIP_STALL_TIMEOUT_MS
#define FLOOR 20000000LL
#define TIP_AT_START 23900000LL

typedef struct { int synced, reanchored; int64_t since, scan; int pending; } PostArgs;
static BRWatchdogPostTimeoutAction postA(PostArgs a)
{ return BRWatchdogDecidePostTimeout(a.synced, a.reanchored, a.since, a.scan, a.pending, BR_WD_CF_FROZEN_RECOVERY_MS, SUPP); }
#define POST(...) postA((PostArgs){ .synced = 1, .reanchored = 0, .since = 0, .scan = SCAN_FROZEN, .pending = 0, __VA_ARGS__ })

typedef struct { int climbing; int64_t cfFrozen, netMax; int already; int64_t scan; int pending; } FrozenArgs;
static int frozenA(FrozenArgs a)
{ return BRWatchdogShouldRecoverFrozenCf(a.climbing, a.cfFrozen, a.netMax, a.already, a.scan, a.pending, BR_WD_CF_FROZEN_RECOVERY_MS, SUPP); }
#define FROZEN(...) frozenA((FrozenArgs){ .climbing = 1, .cfFrozen = BR_WD_CF_FROZEN_RECOVERY_MS * 5, .netMax = 23779855, .already = 0, .scan = SCAN_FROZEN, .pending = 0, __VA_ARGS__ })

typedef struct { int peers; int64_t scan, tip; int full, pending; } T1Args;
static int tier1A(T1Args a) { (void)a.pending; return BRWatchdogShouldRerequestHeaders(a.peers, a.scan, a.tip, a.full, TIP); }
#define TIER1(...) tier1A((T1Args){ .peers = 5, .scan = TIP, .tip = TIP, .full = 0, .pending = 0, __VA_ARGS__ })

typedef struct { int peers; int64_t scan, tip; int full, fired, pending; int64_t supp; } T2Args;
static int tier2A(T2Args a) { return BRWatchdogShouldForceReconnect(a.peers, a.scan, a.tip, a.full, a.fired, a.pending, TIP, a.supp); }
#define TIER2(...) tier2A((T2Args){ .peers = 5, .scan = TIP * 2, .tip = TIP * 2, .full = 0, .fired = 1, .pending = 0, .supp = SUPP, __VA_ARGS__ })

typedef struct { int peers; int64_t scan, tip; int full, pending; } FastArgs;
static int fastA(FastArgs a) { (void)a.pending; return BRWatchdogShouldFastRecover(a.peers, a.scan, a.tip, a.full, 3LL * 60 * 1000); }
#define FAST(...) fastA((FastArgs){ .peers = 5, .scan = 3LL * 60 * 1000, .tip = 3LL * 60 * 1000, .full = 0, .pending = 0, __VA_ARGS__ })

typedef struct { int caught, peers; int64_t cfFrozen; int reanchored; int64_t since; int heals; int64_t scan; int pending; } HealArgs;
static int healA(HealArgs a)
{ return BRWatchdogShouldHealCorruptChain(a.caught, a.peers, a.cfFrozen, a.reanchored, a.since, a.heals, a.scan, a.pending, BR_WD_CF_CORRUPT_HEAL_MS, BR_WD_MAX_CF_CORRUPT_HEALS, SUPP); }
#define HEAL(...) healA((HealArgs){ .caught = 1, .peers = 4, .cfFrozen = BR_WD_CF_CORRUPT_HEAL_MS, .reanchored = 1, .since = BR_WD_REANCHOR_GRACE_MS, .heals = 0, .scan = SCAN_FROZEN, .pending = 0, __VA_ARGS__ })

static int fp(BRWatchdogFrontierProgress p, int changed, int64_t frontier, int64_t ms)
{ return p.changed == changed && p.frontier == frontier && p.lastChangeMs == ms; }

int main(void)
{
    // ---- decidePostTimeoutAction -------------------------------------------------------------
    result("synced_not_reanchored_reanchors", POST() == BRWatchdogReanchor);
    result("never_synced_stays_on_filters", POST(.synced = 0) == BRWatchdogStayOnFilters);
    result("fresh_reanchor_given_grace",
           POST(.reanchored = 1, .since = 0) == BRWatchdogAwaitReanchor &&
           POST(.reanchored = 1, .since = BR_WD_REANCHOR_GRACE_MS - 1) == BRWatchdogAwaitReanchor);
    result("reanchor_never_rebuilt_stays", POST(.reanchored = 1, .since = BR_WD_REANCHOR_GRACE_MS) == BRWatchdogStayOnFilters);
    result("grace_ignored_before_reanchor", POST(.synced = 0, .reanchored = 0) == BRWatchdogStayOnFilters);
    result("post_timeout_not_while_scan_advances", POST(.scan = SCAN_ADVANCING) == BRWatchdogStayOnFilters);
    result("post_timeout_suppressed_by_valve", POST(.pending = 1) == BRWatchdogStayOnFilters);

    // ---- isFilterSyncHealthy -------------------------------------------------------------------
    result("synced_at_tip_is_healthy", BRWatchdogIsFilterSyncHealthy(0, 0, 1));
    result("riding_the_chain_is_healthy", BRWatchdogIsFilterSyncHealthy(10, 1, 0));
    result("far_behind_not_healthy", ! BRWatchdogIsFilterSyncHealthy(500, 0, 1));
    result("restore_tip_below_network_not_healthy", ! BRWatchdogIsFilterSyncHealthy(0, 0, 0));
    result("gap_boundary_inclusive",
           BRWatchdogIsFilterSyncHealthy(BR_WD_HEALTHY_CF_GAP_BLOCKS, 0, 1) && ! BRWatchdogIsFilterSyncHealthy(BR_WD_HEALTHY_CF_GAP_BLOCKS + 1, 1, 1));
    result("convoy_window_top_healthy", BRWatchdogIsFilterSyncHealthy(0, 1, 0));

    // ---- shouldRecoverFrozenCf -----------------------------------------------------------------
    result("frozen_cf_past_window_recovers", FROZEN(.cfFrozen = BR_WD_CF_FROZEN_RECOVERY_MS));
    result("frozen_cf_not_while_scan_advances", ! FROZEN(.scan = SCAN_ADVANCING));
    result("frozen_cf_suppressed_by_valve", ! FROZEN(.pending = 1));
    result("frozen_cf_not_before_window", ! FROZEN(.cfFrozen = BR_WD_CF_FROZEN_RECOVERY_MS - 1));
    result("frozen_cf_not_when_headers_idle", ! FROZEN(.climbing = 0, .cfFrozen = BR_WD_CF_FROZEN_RECOVERY_MS * 3));
    result("frozen_cf_not_pre_cf_phase", ! FROZEN(.cfFrozen = BR_WD_CF_FROZEN_RECOVERY_MS * 3, .netMax = 0));
    result("frozen_cf_once_per_session", ! FROZEN(.already = 1));

    // ---- convoy window and suppression ----------------------------------------------------------
    result("window_full_at_window",
           BRWatchdogIsConvoyWindowFull(1000000 + BR_WD_CF_CONVOY_WINDOW_FALLBACK, 1000000, BR_WD_CF_CONVOY_WINDOW_FALLBACK) &&
           ! BRWatchdogIsConvoyWindowFull(1000000 + BR_WD_CF_CONVOY_WINDOW_FALLBACK - 1, 1000000, BR_WD_CF_CONVOY_WINDOW_FALLBACK));
    result("window_open_when_unarmed",
           ! BRWatchdogIsConvoyWindowFull(23900000, 0, BR_WD_CF_CONVOY_WINDOW_FALLBACK) &&
           ! BRWatchdogIsConvoyWindowFull(23900000, 1, BR_WD_CF_CONVOY_WINDOW_FALLBACK));
    result("window_open_when_scan_above_tip", ! BRWatchdogIsConvoyWindowFull(1000000, 1000050, BR_WD_CF_CONVOY_WINDOW_FALLBACK));
    result("nothing_pending_no_suppression", ! BRWatchdogIsConvoySuppressed(0, 0, SUPP) && ! BRWatchdogIsConvoySuppressed(0, SCAN_FROZEN, SUPP));
    result("suppressed_pending_within_ceiling",
           BRWatchdogIsConvoySuppressed(1, 0, SUPP) && BRWatchdogIsConvoySuppressed(1, SUPP - 1, SUPP) && BRWatchdogIsConvoySuppressed(9, SCAN_FROZEN, SUPP));
    result("released_past_ceiling",
           ! BRWatchdogIsConvoySuppressed(1, SUPP, SUPP) && ! BRWatchdogIsConvoySuppressed(255, SUPP, SUPP) &&
           ! BRWatchdogIsConvoySuppressed(255, 60LL * 60 * 1000, SUPP));
    result("ceiling_derived_from_rearm_budget",
           BRWatchdogSuppressionMaxMs(2) == BR_WD_CF_VALVE_RETRY_CYCLE_MS * 4 && BRWatchdogSuppressionMaxMs(4) == BR_WD_CF_VALVE_RETRY_CYCLE_MS * 6 &&
           BRWatchdogSuppressionMaxMs(8) == BR_WD_CF_VALVE_RETRY_CYCLE_MS * 10 &&
           BRWatchdogSuppressionMaxMs(BR_WD_CF_CONVOY_REARM_MAX_FALLBACK) == BR_WD_SUPPRESSION_MAX_MS_FALLBACK);
    result("window_from_the_caller",
           BRWatchdogIsConvoyWindowFull(20000000 + 4000, 20000000, 4000) && ! BRWatchdogIsConvoyWindowFull(20000000 + 3999, 20000000, 4000) &&
           ! BRWatchdogIsConvoyWindowFull(20000000 + 4000, 20000000, BR_WD_CF_CONVOY_WINDOW_FALLBACK));

    // ---- tip-stall tiers ------------------------------------------------------------------------
    result("tier1_scan_frozen_with_peers", TIER1());
    result("tier1_not_before_window", ! TIER1(.scan = TIP - 1, .tip = TIP - 1));
    result("tier1_not_zero_peers", ! TIER1(.peers = 0, .scan = TIP * 3));
    result("tier1_not_header_gated_scan_advancing", ! TIER1(.scan = 0, .tip = TIP * 10, .full = 1));
    result("tier1_not_scan_advancing_window_open", ! TIER1(.scan = 0, .tip = TIP * 10, .full = 0));
    result("residual_dead_branch_fires", TIER1(.scan = TIP, .tip = TIP, .full = 1));
    result("no_escalation_while_header_rekicks", ! TIER1(.scan = TIP * 5, .tip = 0, .full = 1));
    result("tier2_after_tier1_and_extra_window",
           ! TIER2(.fired = 0) && ! TIER2(.scan = TIP * 2 - 1, .tip = TIP * 2 - 1) && TIER2());
    result("tier2_needs_peers", ! TIER2(.peers = 0, .scan = TIP * 5));
    result("tier2_not_while_scan_advances", ! TIER2(.scan = 0, .tip = TIP * 10, .full = 1));
    result("fast_fires_on_frozen_scan_needs_peers", FAST() && ! FAST(.peers = 0) && ! FAST(.scan = 0, .tip = 0));
    result("fast_not_when_gated_and_scanning", ! FAST(.scan = 0, .tip = 60LL * 60 * 1000, .full = 1));

    // ---- shouldHealCorruptFilterChain -----------------------------------------------------------
    result("heal_after_reanchor_grace_frozen", HEAL());
    result("heal_not_before_grace", ! HEAL(.since = BR_WD_REANCHOR_GRACE_MS - 1));
    result("heal_not_before_frozen_window", ! HEAL(.cfFrozen = BR_WD_CF_CORRUPT_HEAL_MS - 1));
    result("heal_not_before_reanchor_tried", ! HEAL(.reanchored = 0));
    result("heal_not_while_headers_import", ! HEAL(.caught = 0));
    result("heal_not_zero_peers", ! HEAL(.peers = 0));
    result("heal_bounded_per_session",
           HEAL(.heals = BR_WD_MAX_CF_CORRUPT_HEALS - 1) && ! HEAL(.heals = BR_WD_MAX_CF_CORRUPT_HEALS) && ! HEAL(.heals = BR_WD_MAX_CF_CORRUPT_HEALS + 1));
    result("heal_not_while_scan_advances", ! HEAL(.scan = SCAN_ADVANCING));

    // ---- scan-frontier tracking ----------------------------------------------------------------
    result("frontier_forward_restarts_clock", fp(BRWatchdogStepScanFrontier(FLOOR, 1000, FLOOR + 500, 9000), 1, FLOOR + 500, 9000));
    result("frontier_unchanged_holds_clock", fp(BRWatchdogStepScanFrontier(FLOOR, 1000, FLOOR, 9000), 0, FLOOR, 1000));
    result("regression_is_reinit", fp(BRWatchdogStepScanFrontier(23890000, 1000, FLOOR, 9000), 1, FLOOR, 9000));
    result("unarmed_reading_ignored",
           fp(BRWatchdogStepScanFrontier(23890000, 1000, 0, 9000), 0, 23890000, 1000) &&
           fp(BRWatchdogStepScanFrontier(23890000, 1000, 1, 9000), 0, 23890000, 1000));

    // ---- TipStallState ---------------------------------------------------------------------------
    {
        BRTipStallState s = BRTipStallInit(TIP_AT_START, 23890000, 0); s.tier1Fired = 1;
        BRTipStallState a = BRTipStallStep(s, TIP_AT_START, FLOOR, 60LL * 60 * 1000);
        result("latch_clears_on_reinit", a.progressed && ! a.tier1Fired && BRTipStallScanStalledMs(a, 60LL * 60 * 1000) == 0 && a.lastScan == FLOOR);
    }
    {
        BRTipStallState s = BRTipStallInit(TIP_AT_START, 23890000, 0); s.tier1Fired = 1;
        int64_t t = 60LL * 60 * 1000;
        s = BRTipStallStep(s, TIP_AT_START, FLOOR, t);
        for (int i = 0; i < 90; i++) { t += 60000; s = BRTipStallStep(s, TIP_AT_START, FLOOR + (i + 1) * 20LL, t); }
        result("reinit_no_tier2_harassment",
               ! BRWatchdogShouldRerequestHeaders(6, BRTipStallScanStalledMs(s, t), BRTipStallBlockTipStalledMs(s, t), 0, TIP) &&
               ! BRWatchdogShouldForceReconnect(6, BRTipStallScanStalledMs(s, t), BRTipStallBlockTipStalledMs(s, t), 0, 1, 0, TIP, SUPP));
    }
    {
        BRTipStallState s = BRTipStallInit(TIP_AT_START, FLOOR, 0); s.tier1Fired = 1;
        s = BRTipStallStep(s, TIP_AT_START, FLOOR, TIP);
        result("frozen_frontier_keeps_clock_and_latch",
               ! s.progressed && s.tier1Fired && BRTipStallScanStalledMs(s, TIP) == TIP &&
               BRWatchdogShouldRerequestHeaders(6, BRTipStallScanStalledMs(s, TIP), BRTipStallBlockTipStalledMs(s, TIP), 0, TIP));
    }
    {
        BRTipStallState s = BRTipStallInit(TIP_AT_START, FLOOR, 0); s.tier1Fired = 1;
        s = BRTipStallStep(s, TIP_AT_START + 2000, FLOOR, 60000);
        result("armed_scan_tip_advance_not_progress",
               ! s.progressed && s.tier1Fired && BRTipStallScanStalledMs(s, 60000) == 60000 && BRTipStallBlockTipStalledMs(s, 60000) == 0);
    }
    {
        BRTipStallState s = BRTipStallInit(TIP_AT_START, 0, 0); s.tier1Fired = 1;
        s = BRTipStallStep(s, TIP_AT_START + 1, 0, 60000);
        int ok = ! s.scanArmed && s.progressed && ! s.tier1Fired && BRTipStallScanStalledMs(s, 60000) == 0;
        BRTipStallState f = BRTipStallStep(s, TIP_AT_START + 1, 0, 60000 + TIP);
        result("unarmed_scan_tip_is_authoritative", ok && BRTipStallScanStalledMs(f, 60000 + TIP) == TIP);
    }
    {
        BRTipStallState s = BRTipStallInit(TIP_AT_START, 0, 0);
        s = BRTipStallStep(s, TIP_AT_START, FLOOR, 30000);
        result("arming_mid_session_no_false_stall", s.scanArmed && s.progressed && BRTipStallScanStalledMs(s, 30000) == 0);
    }

    // ---- fix wave C2 ---------------------------------------------------------------------------
    result("c2c_tier1_never_suppressed", TIER1(.pending = 1) && TIER1(.pending = 7) && TIER1(.pending = 255));
    result("c2c_fast_never_suppressed", FAST(.pending = 1) && FAST(.pending = 255));
    result("c2c_nondestructive_armed_mid_progress", TIER1(.pending = 1, .scan = TIP) && FAST(.pending = 1, .scan = 3LL * 60 * 1000));
    result("c2a_destructive_suppressed_while_progressing",
           ! HEAL(.pending = 1, .scan = SCAN_FROZEN) && ! FROZEN(.pending = 1, .scan = SCAN_FROZEN) &&
           POST(.pending = 1, .scan = SCAN_FROZEN) == BRWatchdogStayOnFilters);
    result("c2a_tier2_never_both",
           TIP * 2 > SUPP && TIER2(.pending = 1, .scan = TIP * 2, .tip = TIP * 2) && ! TIER2(.pending = 1, .scan = TIP * 2 - 1, .tip = TIP * 2 - 1));
    {
        int64_t raised = BRWatchdogSuppressionMaxMs(4);
        result("c2_tier2_live_gate",
               ! TIER2(.pending = 1, .scan = TIP * 2, .tip = TIP * 2, .supp = raised) &&
               TIER2(.pending = 1, .scan = raised + 1, .tip = raised + 1, .supp = raised));
    }
    result("c2b_heal_rearms", HEAL(.pending = 1, .scan = SCAN_PINNED_PAST_CEILING));
    result("c2b_frozen_rearms", FROZEN(.pending = 1, .scan = SCAN_PINNED_PAST_CEILING));
    result("c2b_tier2_rearms", TIER2(.pending = 1, .scan = SCAN_PINNED_PAST_CEILING, .tip = SCAN_PINNED_PAST_CEILING));
    result("c2b_post_timeout_rearms", POST(.pending = 1, .scan = SCAN_PINNED_PAST_CEILING) == BRWatchdogReanchor);
    result("c2d_corrupt_chain_heals",
           HEAL(.cfFrozen = SCAN_PINNED_PAST_CEILING, .since = SCAN_PINNED_PAST_CEILING, .scan = SCAN_PINNED_PAST_CEILING, .pending = 1) &&
           ! HEAL(.cfFrozen = SCAN_PINNED_PAST_CEILING, .since = SCAN_PINNED_PAST_CEILING, .heals = BR_WD_MAX_CF_CORRUPT_HEALS,
                  .scan = SCAN_PINNED_PAST_CEILING, .pending = 1));
    result("heal_cooldown_wider_than_threshold", BR_WD_CF_CORRUPT_HEAL_COOLDOWN_MS > BR_WD_CF_CORRUPT_HEAL_MS);

    // ---- the tier ordering (SyncService.startTipStallWatchdog, from its code) -------------------
    {
        BRTipStallState s = BRTipStallInit(TIP_AT_START, FLOOR, 0); s.tier1Fired = 1;
        int64_t now = TIP * 2, t2 = 0 - BR_WD_TIP_STALL_TIER2_THROTTLE_MS, fast = 0;
        s = BRTipStallStep(s, TIP_AT_START, FLOOR, now);
        BRTipStallAction a = BRTipStallDecide(&s, 5, 0, 0, SUPP, now, &t2, &fast);
        result("decide_tier2_first_resets_latch", a == BRTipStallRecreateManager && t2 == now && ! s.tier1Fired);
        // within the hour, tier 2 is throttled; tier 1 fires again against the fresh manager
        a = BRTipStallDecide(&s, 5, 0, 0, SUPP, now + 60000, &t2, &fast);
        BRTipStallAction b = BRTipStallDecide(&s, 5, 0, 0, SUPP, now + 120000, &t2, &fast);
        result("decide_tier2_throttled_then_tier1_then_fast", a == BRTipStallRerequestHeaders && s.tier1Fired &&
               b == BRTipStallFastRecover && fast == now + 120000);
        BRTipStallAction c = BRTipStallDecide(&s, 5, 0, 0, SUPP, now + 120000 + BR_WD_TIP_STALL_FAST_MS - 1, &t2, &fast);
        result("decide_fast_throttled", c == BRTipStallNone);
    }
    {
        BRTipStallState s = BRTipStallInit(TIP_AT_START, FLOOR, 0);
        int64_t t2 = 0, fast = 0;
        s = BRTipStallStep(s, TIP_AT_START, FLOOR, TIP);
        BRTipStallAction a = BRTipStallDecide(&s, 5, 0, 0, SUPP, TIP, &t2, &fast);
        result("decide_tier1_latches_once", a == BRTipStallRerequestHeaders && s.tier1Fired &&
               BRTipStallDecide(&s, 5, 0, 0, SUPP, TIP + 1, &t2, &fast) == BRTipStallFastRecover);
        BRTipStallState z = BRTipStallInit(TIP_AT_START, FLOOR, 0);
        z = BRTipStallStep(z, TIP_AT_START, FLOOR, TIP * 3);
        result("decide_nothing_without_peers", BRTipStallDecide(&z, 0, 0, 0, SUPP, TIP * 3, &t2, &fast) == BRTipStallNone);
    }

    // ---- the BIP158 loop (SyncService.startBip158Watchdog, from its code) ---------------------
    // Wall-clock base, as Android's System.currentTimeMillis(): the heal cooldown starts from 0, so
    // with real time the first heal is never held back by it.
    {
        const int64_t T0 = 1790000000000LL;
        int rot = -1;
        BRBip158WatchdogState s = BRBip158WatchdogInit(24000000, 24000040, 23990000, T0);
        result("loop_healthy_exits", BRBip158WatchdogStep(&s, 24000000, 24000050, 24000060, 23990000, 5, 0, 1, SUPP, T0 + 15000, &rot) == BRBip158Healthy);

        s = BRBip158WatchdogInit(1000, 1000, 900, T0);
        result("loop_progressing_waits", BRBip158WatchdogStep(&s, 1100, 5000, 50000, 900, 5, 0, 1, SUPP, T0 + 15000, &rot) == BRBip158Wait);

        s = BRBip158WatchdogInit(1000, 50000, 900, T0);
        result("loop_catching_up_waits", BRBip158WatchdogStep(&s, 1000, 50000, 90000, 900, 5, 0, 1, SUPP, T0 + 200000, &rot) == BRBip158Wait);
    }
    {   // the cf tip froze while headers climb, the scan frozen too: one recovery, at 120 s, once
        const int64_t T0 = 1790000000000LL;
        int rot = -1, recoveries = 0; int64_t at = -1;
        BRBip158WatchdogState s = BRBip158WatchdogInit(1000, 2000, 900, T0);
        for (int i = 1; i <= 40; i++) {
            int64_t t = T0 + i * BR_WD_BIP158_POLL_MS;
            BRBip158Action a = BRBip158WatchdogStep(&s, 1000, 2000 + i * 100, 1000000, 900, 5, 0, 1, SUPP, t, &rot);
            if (a == BRBip158RecoverFrozenCf) { recoveries++; if (at < 0) at = t - T0; }
        }
        result("loop_frozen_cf_recovers_once_at_120s", recoveries == 1 && at == 120000);
    }
    {   // synced, headers at the tip, the filter chain stuck: re-anchor at 120 s, grace, then three
        // heals 240 s apart rotating through the canon pool, and no fourth
        const int64_t T0 = 1790000000000LL;
        int rot = -1, reanchors = 0, heals = 0, rots[8] = {0}; int64_t healAt[8] = {0}, reanchorAt = -1, other = 0;
        BRBip158WatchdogState s = BRBip158WatchdogInit(1000, 50000, 900, T0);
        for (int i = 1; i <= 100; i++) {
            int64_t t = T0 + i * BR_WD_BIP158_POLL_MS;
            BRBip158Action a = BRBip158WatchdogStep(&s, 1000, 50000, 50000, 900, 5, 0, 1, SUPP, t, &rot);
            if (a == BRBip158Reanchor) { reanchors++; reanchorAt = t - T0; BRBip158WatchdogReanchored(&s, 1, t); }
            else if (a == BRBip158HealCorruptChain) { if (heals < 8) { healAt[heals] = t - T0; rots[heals] = rot; } heals++; }
            else if (a != BRBip158Wait) other++;
        }
        result("loop_reanchor_then_grace_then_bounded_rotating_heals",
               reanchors == 1 && reanchorAt == 120000 && heals == 3 && other == 0 &&
               healAt[0] == 210000 && healAt[1] == 450000 && healAt[2] == 690000 &&
               rots[0] == 0 && rots[1] == 1 && rots[2] == 2);
    }
    {   // a declined re-anchor changes nothing: the next poll asks again
        const int64_t T0 = 1790000000000LL;
        int rot = -1;
        BRBip158WatchdogState s = BRBip158WatchdogInit(1000, 50000, 900, T0);
        BRBip158Action a = BRBip158Wait;
        for (int i = 1; i <= 8; i++) a = BRBip158WatchdogStep(&s, 1000, 50000, 50000, 900, 5, 0, 1, SUPP, T0 + i * 15000, &rot);
        BRBip158WatchdogReanchored(&s, 0, T0 + 120000);
        BRBip158Action b = BRBip158WatchdogStep(&s, 1000, 50000, 50000, 900, 5, 0, 1, SUPP, T0 + 135000, &rot);
        result("loop_declined_reanchor_asked_again", a == BRBip158Reanchor && b == BRBip158Reanchor && ! s.reanchoredThisSession);
    }
    {   // a wallet that never reached sync never re-anchors and so never heals
        const int64_t T0 = 1790000000000LL;
        int rot = -1, acted = 0;
        BRBip158WatchdogState s = BRBip158WatchdogInit(1000, 50000, 900, T0);
        for (int i = 1; i <= 100; i++)
            if (BRBip158WatchdogStep(&s, 1000, 50000, 50000, 900, 5, 0, 0, SUPP, T0 + i * 15000, &rot) != BRBip158Wait) acted++;
        result("loop_never_synced_never_reanchors", acted == 0);
    }
    {   // the valve owns a pinned hole: the frozen-CF recovery waits for the 30-min ceiling, then fires
        const int64_t T0 = 1790000000000LL;
        int rot = -1; int64_t at = -1;
        BRBip158WatchdogState s = BRBip158WatchdogInit(1000, 2000, 900, T0);
        for (int i = 1; i <= 200 && at < 0; i++) {
            int64_t t = T0 + i * BR_WD_BIP158_POLL_MS;
            if (BRBip158WatchdogStep(&s, 1000, 2000 + i * 100, 10000000, 900, 5, 1, 1, SUPP, t, &rot) == BRBip158RecoverFrozenCf) at = t - T0;
        }
        result("loop_valve_holds_recovery_until_ceiling", at == SUPP);
    }

    // ---- the zero-peer escalation (SyncService's keepalive tick, from its code) ----------------
    {
        int streak = 0;
        BRZeroPeerAction a1 = BRZeroPeerStep(&streak, 0, 0, 1, 1.0), a2 = BRZeroPeerStep(&streak, 0, 0, 1, 1.0),
                         a3 = BRZeroPeerStep(&streak, 0, 0, 1, 1.0), a4 = BRZeroPeerStep(&streak, 0, 0, 1, 1.0);
        result("zero_peers_reconnect_then_recreate_every_third",
               a1 == BRZeroPeerReconnect && a2 == BRZeroPeerReconnect && a3 == BRZeroPeerRecreateThenReconnect &&
               a4 == BRZeroPeerReconnect && streak == 1);
    }
    {
        int streak = 2;
        BRZeroPeerAction a = BRZeroPeerStep(&streak, 3, 0, 1, 1.0);
        BRZeroPeerAction b = BRZeroPeerStep(&streak, 0, 0, 1, 1.0);
        result("a_peer_resets_the_streak", a == BRZeroPeerNone && b == BRZeroPeerReconnect && streak == 1);
    }
    {
        int streak = 2;
        BRZeroPeerAction a = BRZeroPeerStep(&streak, 0, 1, 1, 1.0);
        result("tor_coming_up_defers_and_keeps_the_streak", a == BRZeroPeerDeferForTor && streak == 2);
    }
    {
        int streak = 0;
        result("never_synced_with_peers_kicks_sync",
               BRZeroPeerStep(&streak, 4, 0, 0, 0.42) == BRZeroPeerKickSync &&
               BRZeroPeerStep(&streak, 4, 0, 0, 1.0) == BRZeroPeerNone &&
               BRZeroPeerStep(&streak, 4, 0, 1, 0.42) == BRZeroPeerNone);
    }

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
