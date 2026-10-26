// Host KAT for BRCFAbandonmentRecord.h -- the abandoned compact-filter band's record and the poll
// that keeps it. The store cases are Android's CfAbandonmentStoreTest, case for case and value for
// value (its nextAbandonedBand cases test BRCFAbandonment.h's predicate and live with it); the
// poll cases follow WalletViewModel's polling order.
//
// Built twice by run.sh:
//   -DCF_ABANDONMENT_RECORD_RED  phase 2 without phase 1 (the R1 bug); every RED case fails
//   (default)                    the shipped rule; every case passes

#include "BRCFAbandonmentRecord.h"
#include <stdio.h>

static int g_failed = 0;
static void result(const char *name, int pass)
{
    if (! pass) { printf("FAIL: %s\n", name); g_failed = 1; }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

#define FRESH(r) BRCFAbandonmentRecord r = { { 0, 0, 0 }, 0, 0, 0 }
#define U(r) BRCFAbandonmentIsUnrecovered(&r)
#define COV(r, below, frontier) BRCFAbandonmentNoteScanCoverage(&r, below, frontier)

int main(void)
{
    // ---- Android's CfAbandonmentStoreTest (store cases) ----
    {   FRESH(r);
        result("no_band_nothing_to_surface", ! r.hasBand && ! U(r) && BRCFAbandonmentMarkRecovered(&r) == 0);
    }
    {   FRESH(r); int noted = BRCFAbandonmentNote(&r, 1000, 900), was = U(r), lo = r.band.low, hi = r.band.high;
        int marked = BRCFAbandonmentMarkRecovered(&r);
        result("surfaces_until_recovered", noted == 1 && was && lo == 900 && hi == 999 && marked == 1 && ! U(r) &&
               r.hasBand && r.band.high == 999);
    }
    {   FRESH(r); BRCFAbandonmentNote(&r, 1000, 900); BRCFAbandonmentMarkRecovered(&r);
        int quiet = ! U(r), again = BRCFAbandonmentNote(&r, 2000, 1900);
        result("abandonment_after_recovery_resurfaces", quiet && again == 1 && U(r) && r.band.high == 1999 &&
               r.band.low == 900 && ! r.recovered);
    }
    {   FRESH(r); int i, churn = 0; BRCFAbandonmentNote(&r, 1000, 900); BRCFAbandonmentMarkRecovered(&r);
        for (i = 0; i < 5; i++) churn |= BRCFAbandonmentNote(&r, 1000, 900);
        result("re_polling_same_watermark_does_not_unrecover", churn == 0 && r.recovered && ! U(r));
    }
    {   FRESH(r); BRCFAbandonmentNote(&r, 1000, 900);
        int a = COV(r, 0, 950), saw = r.sawFrontierInBand, mid = U(r), b = COV(r, 0, 1000);
        result("scan_passed_band_after_reinit_recovers", a == 0 && saw && mid && b == 1 && ! U(r) && r.recovered);
    }
    {   FRESH(r); int i, any = 0, before; BRCFAbandonmentNote(&r, 1000, 900); before = r.sawFrontierInBand;
        for (i = 0; i < 5; i++) any |= COV(r, 0, 1000);
        int far = COV(r, 0, 500000);
        result("frontier_never_seen_inside_is_not_recovery", before == 0 && any == 0 && far == 0 && U(r) && ! r.recovered);
    }
    {   FRESH(r); BRCFAbandonmentNote(&r, 1000, 900);
        int a = COV(r, 0, 0), saw = r.sawFrontierInBand, b = COV(r, 0, 1000);
        result("zero_frontier_is_not_an_observation", a == 0 && saw == 0 && b == 0 && U(r));
    }
    {   FRESH(r); BRCFAbandonmentRecord persisted; BRCFAbandonmentNote(&r, 1000, 900);
        COV(r, 0, 900);
        persisted = r;   // ...the process dies, the app relaunches with the persisted record
        int saw = persisted.sawFrontierInBand, done = COV(persisted, 0, 1000);
        result("in_band_observation_persists_across_phases", saw && done == 1 && ! U(persisted));
    }
    {   FRESH(r); BRCFAbandonmentNote(&r, 1000, 900); COV(r, 0, 950);
        int saw = r.sawFrontierInBand, ext = BRCFAbandonmentNote(&r, 1500, 1400), reset = ! r.sawFrontierInBand;
        int past = COV(r, 0, 1500);
        result("new_abandonment_resets_the_witness", saw && ext == 1 && reset && past == 0 && U(r));
    }
    {   FRESH(r); BRCFAbandonmentNote(&r, 1001, 1000);
        int lo = r.band.low, hi = r.band.high, atFloor = COV(r, 0, 1001), still = U(r);
        int witness = COV(r, 0, 1000), heal = COV(r, 0, 1001);
        result("one_height_band_needs_the_witness", lo == 1000 && hi == 1000 && atFloor == 0 && still &&
               witness == 0 && heal == 1 && ! U(r));
    }
    {   FRESH(r); BRCFAbandonmentNote(&r, 1000, 900);
        int a = COV(r, 0, 500), ua = U(r), b = COV(r, 0, 999);
        result("watermark_cleared_scan_not_past_the_band", a == 0 && ua && b == 0 && U(r));
    }
    {   FRESH(r); BRCFAbandonmentNote(&r, 1000, 900);
        result("watermark_still_standing_is_never_recovery", COV(r, 1000, 9999) == 0 && U(r));
    }
    {   FRESH(r); int a = COV(r, 0, 9999); BRCFAbandonmentNote(&r, 1000, 900); BRCFAbandonmentMarkRecovered(&r);
        int b = COV(r, 0, 9999), c = COV(r, 0, 950);
        result("scan_coverage_noop_without_an_unrecovered_band", a == 0 && b == 0 && c == 0 && ! r.sawFrontierInBand);
    }
    {   FRESH(r); BRCFAbandonmentNote(&r, 1000, 900); BRCFAbandonmentClear(&r);
        result("clear_forgets_the_band", ! r.hasBand && ! U(r) && ! r.recovered && ! r.sawFrontierInBand);
    }

    // ---- the poll, in WalletViewModel's order ----
    {   FRESH(r); uint32_t hint = 0;
        int a = BRCFAbandonmentPoll(&r, &hint, 1, 900, 0, 0, 0, 0);
        int b = BRCFAbandonmentPoll(&r, &hint, 0, 1000, 1000, 0, 0, 0);
        result("poll_captures_the_low_hint_while_pending", a == 0 && hint == 900 &&
               b == (BR_CF_ABANDON_CHANGED | BR_CF_ABANDON_RECORDED) && r.band.low == 900 && r.band.high == 999 &&
               r.band.lowKnown == 1);
    }
    {   FRESH(r); uint32_t hint = 0;
        BRCFAbandonmentPoll(&r, &hint, 0, 950, 1000, 0, 0, 0);
        result("poll_without_a_hint_marks_low_unknown", hint == 0 && r.hasBand && r.band.lowKnown == 0 && r.band.high == 999);
    }
    {   FRESH(r); uint32_t hint = 900; BRCFAbandonmentPoll(&r, &hint, 0, 1000, 1000, 0, 0, 0);
        int again = BRCFAbandonmentPoll(&r, &hint, 0, 1000, 1000, 0, 0, 0);
        result("poll_rereading_a_standing_watermark_changes_nothing", again == 0 && U(r));
    }
    {   FRESH(r); uint32_t hint = 900; BRCFAbandonmentPoll(&r, &hint, 0, 1000, 1000, 0, 0, 0);
        int w = BRCFAbandonmentPoll(&r, &hint, 0, 950, 0, 0, 0, 0);
        int h = BRCFAbandonmentPoll(&r, &hint, 0, 1000, 0, 0, 0, 0);
        result("poll_two_phase_heal", w == BR_CF_ABANDON_CHANGED && h == (BR_CF_ABANDON_CHANGED | BR_CF_ABANDON_RECOVERED) &&
               ! U(r));
    }
    {   // a reconnect re-initialises at the band's top: frontier just past it, the ledger starting
        // above the band (so it proves nothing about it either)
        FRESH(r); uint32_t hint = 900; BRCFAbandonmentPoll(&r, &hint, 0, 1000, 1000, 0, 0, 0);
        int p = BRCFAbandonmentPoll(&r, &hint, 0, 1000, 0, 1000, 1500, 0);
        result("poll_reinit_at_the_top_is_not_recovery", p == 0 && U(r));
    }
    {   // the fourth path (Note 8, v4.0.44): the scan far above, the ledger contiguous from below it
        FRESH(r); uint32_t hint = 900; BRCFAbandonmentPoll(&r, &hint, 0, 1000, 1000, 0, 0, 0);
        int p = BRCFAbandonmentPoll(&r, &hint, 0, 600000, 0, 800, 1200, 0);
        result("poll_ledger_proves_coverage", p == (BR_CF_ABANDON_CHANGED | BR_CF_ABANDON_RECOVERED) && ! U(r));
    }
    {   // an unknown low: the ledger's own start stands in for it
        FRESH(r); uint32_t hint = 0; BRCFAbandonmentPoll(&r, &hint, 0, 1000, 1000, 0, 0, 0);
        int p = BRCFAbandonmentPoll(&r, &hint, 0, 600000, 0, 500, 1000, 0);
        result("poll_unknown_low_uses_the_ledger_start", r.band.lowKnown == 0 &&
               p == (BR_CF_ABANDON_CHANGED | BR_CF_ABANDON_RECOVERED) && ! U(r));
    }
    {   FRESH(r); uint32_t hint = 900; BRCFAbandonmentPoll(&r, &hint, 0, 1000, 1000, 0, 0, 0);
        int p = BRCFAbandonmentPoll(&r, &hint, 0, 0, 0, 800, 1200, 3);
        result("poll_a_ledger_with_a_hole_is_not_proof", p == 0 && U(r));
    }
    {   FRESH(r); uint32_t hint = 900; BRCFAbandonmentPoll(&r, &hint, 0, 1000, 1000, 0, 0, 0);
        int p = BRCFAbandonmentPoll(&r, &hint, 0, 0, 0, 0, 0, 0);
        result("poll_failed_reads_are_not_evidence", p == 0 && U(r) && ! r.sawFrontierInBand);
    }

    // ---- mutation guards (each fails one plausible slip; values from CfAbandonmentStore and
    //      WalletViewModel's poll) ----
    {   // a pending poll whose frontier read failed (0) keeps the hint it had
        FRESH(r); uint32_t hint = 0;
        BRCFAbandonmentPoll(&r, &hint, 1, 900, 0, 0, 0, 0);
        int a = BRCFAbandonmentPoll(&r, &hint, 1, 0, 0, 0, 0, 0), kept = (hint == 900);
        int b = BRCFAbandonmentPoll(&r, &hint, 0, 0, 1000, 0, 0, 0);
        result("poll_failed_frontier_read_keeps_the_hint", a == 0 && kept &&
               b == (BR_CF_ABANDON_CHANGED | BR_CF_ABANDON_RECORDED) && r.band.low == 900 && r.band.lowKnown == 1);
    }
    {   // a floor still inside the band (partial retirement): no witness, no recovery, even with
        // the witness already held from a fully retired moment
        FRESH(r); FRESH(w); BRCFAbandonmentNote(&r, 1000, 900); BRCFAbandonmentNote(&w, 1000, 900);
        int a = COV(r, 950, 920), sawA = r.sawFrontierInBand;
        COV(w, 0, 950);
        int b = COV(w, 950, 1000);
        result("partial_retirement_is_neither_witness_nor_recovery", a == 0 && sawA == 0 && U(r) &&
               w.sawFrontierInBand == 1 && b == 0 && U(w) && ! w.recovered);
    }
    {   // marking an already-recovered band again changes nothing and says so
        FRESH(r); BRCFAbandonmentNote(&r, 1000, 900);
        int first = BRCFAbandonmentMarkRecovered(&r), second = BRCFAbandonmentMarkRecovered(&r);
        result("mark_recovered_twice_reports_no_change", first == 1 && second == 0 && r.recovered && ! U(r));
    }

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
