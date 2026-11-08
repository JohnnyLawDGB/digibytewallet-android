// Host KAT for the DigiDollar half of BRRecoveryGapWindow.h -- how far the DigiDollar recovery scan
// derives at m/86'/20'/0', and when its answer is finished or capped (4.1.2 row 19). Carries every
// case of Android's DigiDollarGapWindowTest (the Kotlin DigiDollarGapWindow, the gaps that were in
// RecoveryScanService and the stop decision in its scanDigiDollar, which the core replaced ->
// jni_recovery_gap.c): each assertion as a named case, the Kotlin test's own loop verbatim, and the
// platform's scan loop as a simulation (simulate() below, which does what scanDigiDollar does per
// round with a lookup that answers from a set of used indices).
//
// Kotlin's next(count, used, gap = 10) and capped(used, gap = 10) are chain 1 (the internal gap);
// the scan passed only its external gap (20) with chain 0 and its internal gap (10) with chain 1.
//
// Built three times by run.sh:
//   -DDIGIDOLLAR_GAP_RED  the fixed window: a window never grows; the cases that need it to grow
//                         fail, every other case holds
//   -DDIGIDOLLAR_CAP_RED  a scan the cap stopped is reported finished; the capped cases fail,
//                         every other case holds
//   (default)             the shipped rule; every case passes

#include "BRRecoveryGapWindow.h"
#include "BRRecoveryGapWindow.h"   // twice: the include guard holds
#include <stdio.h>
#include <string.h>

static int g_failed = 0;

static void result(const char *name, int pass, const char *detail)
{
    if (! pass) {
        printf("FAIL: %s%s%s\n", name, detail ? ": " : "", detail ? detail : "");
        g_failed = 1;
    }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

#define RESULT_IS(out) ((out) == BR_RECOVERY_GAP_ASK_AGAIN ? "ask-again" : (out) == BR_RECOVERY_GAP_FINISHED ? "finished" : \
                        (out) == BR_RECOVERY_GAP_CAPPED ? "capped" : "?")

// The lookup: the highest used index on chain below window (-1: none).
typedef int32_t (*Highest)(int chain, int32_t window, const void *ctx);

typedef struct {
    int outcome;
    int32_t external, internal;   // the windows the kept answer was derived at
    int rounds;
} Scan;

// The platform's loop (scanDigiDollar): start at the gaps, ask, grow while the rule says ask again.
static Scan simulate(Highest highest, const void *ctx)
{
    Scan s;
    int32_t external = BRRecoveryDigiDollarGapStart(0), internal = BRRecoveryDigiDollarGapStart(1);
    int round = 0;

    for (;;) {
        int32_t usedExternal = highest(0, external, ctx), usedInternal = highest(1, internal, ctx);
        int32_t nextExternal = BRRecoveryDigiDollarGapNext(0, external, usedExternal);
        int32_t nextInternal = BRRecoveryDigiDollarGapNext(1, internal, usedInternal);
        int outcome = BRRecoveryDigiDollarGapOutcome(external, internal, nextExternal, nextInternal, usedExternal, usedInternal);

        round++;
        if (outcome != BR_RECOVERY_GAP_ASK_AGAIN || round > 10000) {
            s.outcome = outcome; s.external = external; s.internal = internal; s.rounds = round;
            return s;
        }
        external = nextExternal; internal = nextInternal;
    }
}

// Used indices per chain.
typedef struct { const int32_t *external; int nExternal; const int32_t *internal; int nInternal; } Used;

static int32_t fromSet(int chain, int32_t window, const void *ctx)
{
    const Used *u = ctx;
    const int32_t *at = chain == 0 ? u->external : u->internal;
    int n = chain == 0 ? u->nExternal : u->nInternal;
    int32_t best = -1;
    for (int i = 0; i < n; i++) if (at[i] < window && at[i] > best) best = at[i];
    return best;
}

static int32_t everything(int chain, int32_t window, const void *ctx) { (void)chain; (void)ctx; return window - 1; }
static int32_t nothing(int chain, int32_t window, const void *ctx) { (void)chain; (void)window; (void)ctx; return -1; }

int main(void)
{
    char why[200];
    int ok;
    Scan s;

    // The values Kotlin held: DigiDollarGapWindow.MAX, RecoveryScanService's ddGapExternal and
    // ddGapInternal (which were also the starting windows), and the shared outcome values.
    ok = BR_RECOVERY_DIGIDOLLAR_GAP_EXTERNAL == 20 && BR_RECOVERY_DIGIDOLLAR_GAP_INTERNAL == 10 &&
         BR_RECOVERY_DIGIDOLLAR_MAX == 1000 && BRRecoveryDigiDollarGapStart(0) == 20 && BRRecoveryDigiDollarGapStart(1) == 10 &&
         BR_RECOVERY_GAP_ASK_AGAIN == 0 && BR_RECOVERY_GAP_FINISHED == 1 && BR_RECOVERY_GAP_CAPPED == 2;
    result("gaps_cap_and_starts_are_kotlins", ok, NULL);

    // `an unused chain keeps its window`
    ok = BRRecoveryDigiDollarGapNext(1, 10, -1) == 10;
    result("an_unused_chain_keeps_its_window", ok, NULL);

    // `use near the end widens the window to a gap past it`: 10 -> 20, then 20 -> 22.
    ok = BRRecoveryDigiDollarGapNext(1, 10, 9) == 20 && BRRecoveryDigiDollarGapNext(1, 20, 11) == 22;
    snprintf(why, sizeof(why), "next(10, 9) = %d, next(20, 11) = %d",
             BRRecoveryDigiDollarGapNext(1, 10, 9), BRRecoveryDigiDollarGapNext(1, 20, 11));
    result("use_near_the_end_widens_the_window_to_a_gap_past_it", ok, why);

    // `a window already a gap past the last use stops growing`
    ok = BRRecoveryDigiDollarGapNext(1, 22, 11) == 22;
    result("a_window_a_gap_past_the_last_use_stops_growing", ok, NULL);

    // `the window never passes the cap`: next(995, 994) is MAX.
    ok = BRRecoveryDigiDollarGapNext(1, 995, 994) == BR_RECOVERY_DIGIDOLLAR_MAX;
    snprintf(why, sizeof(why), "next(995, 994) = %d", BRRecoveryDigiDollarGapNext(1, 995, 994));
    result("the_window_grows_to_the_cap_and_not_past_it", ok, why);

    // `twelve sends are found where the fixed window stopped at ten`: the Kotlin test's own loop,
    // internal indexes 0..11 used, window from 10.
    {
        int32_t count = 10;
        for (int guard = 0; guard < 10000; guard++) {
            int32_t highest = -1, next;
            for (int32_t i = 0; i <= 11; i++) if (i < count) highest = i;
            next = BRRecoveryDigiDollarGapNext(1, count, highest);
            if (next == count) break;
            count = next;
        }
        ok = 11 < count;   // every used index is inside the final window
        snprintf(why, sizeof(why), "final window %d leaves used index 11 outside it", count);
        result("twelve_sends_are_found_where_the_fixed_window_stopped_at_ten", ok, why);
    }

    // `a window the cap stopped is reported as capped, one a gap past its last use is not`
    ok = BRRecoveryDigiDollarGapCapped(1, 995) && ! BRRecoveryDigiDollarGapCapped(1, 11) && ! BRRecoveryDigiDollarGapCapped(1, -1);
    result("capped_is_kotlins", ok, NULL);

    // The external chain at the scan's external gap (20).
    ok = BRRecoveryDigiDollarGapNext(0, 20, 19) == 40 && BRRecoveryDigiDollarGapNext(0, 40, 21) == 42 &&
         BRRecoveryDigiDollarGapNext(0, 990, 985) == BR_RECOVERY_DIGIDOLLAR_MAX;
    snprintf(why, sizeof(why), "external next(20, 19) = %d", BRRecoveryDigiDollarGapNext(0, 20, 19));
    result("external_chain_widens_at_its_own_gap", ok, why);

    ok = BRRecoveryDigiDollarGapNext(0, 42, 21) == 42 && BRRecoveryDigiDollarGapNext(0, 20, -1) == 20 &&
         BRRecoveryDigiDollarGapCapped(0, 980) && ! BRRecoveryDigiDollarGapCapped(0, 979) &&
         BRRecoveryDigiDollarGapCapped(1, 990) && ! BRRecoveryDigiDollarGapCapped(1, 989);
    result("each_chain_caps_within_its_own_gap", ok, NULL);

    // Grows only to a gap past the last use, not by a whole gap (unlike the plain-DGB rule): a use
    // at index 0 of the starting internal window of 10 grows it by one.
    ok = BRRecoveryDigiDollarGapNext(1, 10, 0) == 11 && BRRecoveryDigiDollarGapNext(1, 0, 0) == 11 &&
         BRRecoveryDigiDollarGapNext(0, 20, 0) == 21;
    snprintf(why, sizeof(why), "next(10, 0) = %d, next(0, 0) = %d", BRRecoveryDigiDollarGapNext(1, 10, 0),
             BRRecoveryDigiDollarGapNext(1, 0, 0));
    result("index_zero_is_used_and_grows_to_a_gap_past_it", ok, why);

    // Kotlin's null is any negative index here (a real index never is).
    ok = BRRecoveryDigiDollarGapNext(1, 10, -5) == 10 && BRRecoveryDigiDollarGapNext(0, 20, INT32_MIN) == 20 &&
         ! BRRecoveryDigiDollarGapCapped(1, -1) && ! BRRecoveryDigiDollarGapCapped(0, INT32_MIN);
    result("a_negative_index_is_nothing_used", ok, NULL);

    // Kotlin's Int wrapped: used + gap + 1 past INT32_MAX is negative, so neither grows nor caps.
    ok = BRRecoveryDigiDollarGapNext(1, 10, INT32_MAX) == 10 && BRRecoveryDigiDollarGapNext(0, 20, INT32_MAX - 5) == 20 &&
         ! BRRecoveryDigiDollarGapCapped(1, INT32_MAX - 5) && ! BRRecoveryDigiDollarGapCapped(0, INT32_MAX);
    result("arithmetic_wraps_as_kotlin_int", ok, NULL);

    // Kotlin's minOf(wanted, MAX): a window already past the cap that still wants to grow falls to
    // the cap; one that does not want to grow is kept, including one already exactly a gap past
    // its last use (wanted == count: Kotlin's strict `wanted > count`, not >=). The scan never has
    // such a window.
    ok = BRRecoveryDigiDollarGapNext(1, 1500, 1495) == BR_RECOVERY_DIGIDOLLAR_MAX &&
         BRRecoveryDigiDollarGapNext(1, 1500, 10) == 1500 && BRRecoveryDigiDollarGapNext(1, 1500, 1489) == 1500 &&
         BRRecoveryDigiDollarGapNext(0, 1500, 1479) == 1500;
    snprintf(why, sizeof(why), "next(1500, 1495) = %d", BRRecoveryDigiDollarGapNext(1, 1500, 1495));
    result("a_window_past_the_cap_falls_to_it_as_kotlins_did", ok, why);

    // A chain the scan does not have starts empty, never grows and is never a finished answer.
    ok = BRRecoveryDigiDollarGapStart(2) == 0 && BRRecoveryDigiDollarGapStart(-1) == 0 &&
         BRRecoveryDigiDollarGapNext(2, 10, 9) == 10 && BRRecoveryDigiDollarGapNext(-1, 7, 3) == 7 &&
         BRRecoveryDigiDollarGapCapped(2, -1) && BRRecoveryDigiDollarGapCapped(-1, 5);
    result("unknown_chain_never_grows_and_is_capped", ok, NULL);

    // scanDigiDollar's stop decision: either window grew -> ask again (even at the cap); neither
    // grew -> capped if capped(external) || capped(internal), else finished.
    ok = BRRecoveryDigiDollarGapOutcome(20, 10, 20, 20, -1, 9) == BR_RECOVERY_GAP_ASK_AGAIN &&
         BRRecoveryDigiDollarGapOutcome(20, 10, 40, 10, 19, -1) == BR_RECOVERY_GAP_ASK_AGAIN &&
         BRRecoveryDigiDollarGapOutcome(20, 995, 20, 1000, -1, 994) == BR_RECOVERY_GAP_ASK_AGAIN &&
         BRRecoveryDigiDollarGapOutcome(20, 22, 20, 22, -1, 11) == BR_RECOVERY_GAP_FINISHED &&
         BRRecoveryDigiDollarGapOutcome(20, 10, 20, 10, -1, -1) == BR_RECOVERY_GAP_FINISHED &&
         BRRecoveryDigiDollarGapOutcome(1000, 10, 1000, 10, 979, -1) == BR_RECOVERY_GAP_FINISHED;
    result("growing_scan_asks_again_and_settled_scan_is_finished", ok, NULL);

    ok = BRRecoveryDigiDollarGapOutcome(20, 1000, 20, 1000, -1, 995) == BR_RECOVERY_GAP_CAPPED &&
         BRRecoveryDigiDollarGapOutcome(1000, 10, 1000, 10, 990, -1) == BR_RECOVERY_GAP_CAPPED &&
         BRRecoveryDigiDollarGapOutcome(1000, 1000, 1000, 1000, 999, 999) == BR_RECOVERY_GAP_CAPPED;
    snprintf(why, sizeof(why), "internal cap -> %s", RESULT_IS(BRRecoveryDigiDollarGapOutcome(20, 1000, 20, 1000, -1, 995)));
    result("cap_stop_is_capped", ok, why);

    // An empty wallet: asked once, at the starting windows, finished.
    s = simulate(nothing, NULL);
    ok = s.outcome == BR_RECOVERY_GAP_FINISHED && s.rounds == 1 && s.external == 20 && s.internal == 10;
    result("empty_wallet_is_asked_once", ok, NULL);

    // Twelve sends with change (internal 0..11) and three receives (external 0..2): the scan finds
    // every used index (windows a gap past the last use: 23 and 22) and is finished, where the
    // fixed window stopped at 10 and said complete.
    {
        static const int32_t ext[] = { 0, 1, 2 };
        static const int32_t in[] = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 };
        Used u = { ext, 3, in, 12 };
        s = simulate(fromSet, &u);
        ok = s.outcome == BR_RECOVERY_GAP_FINISHED && s.internal == 22 && s.external == 23;
        snprintf(why, sizeof(why), "%s at %d/%d after %d rounds", RESULT_IS(s.outcome), s.external, s.internal, s.rounds);
        result("change_past_ten_sends_is_found_and_finished", ok, why);
    }

    // A lookup that answers "used" for every address: both windows walk to the cap (a gap per
    // round), the scan stops there, and the answer is capped, not finished.
    s = simulate(everything, NULL);
    ok = s.outcome == BR_RECOVERY_GAP_CAPPED && s.external == BR_RECOVERY_DIGIDOLLAR_MAX &&
         s.internal == BR_RECOVERY_DIGIDOLLAR_MAX && s.rounds <= 10000;
    snprintf(why, sizeof(why), "%s at %d/%d after %d rounds", RESULT_IS(s.outcome), s.external, s.internal, s.rounds);
    result("always_used_lookup_stops_at_the_cap_and_is_capped", ok, why);

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
