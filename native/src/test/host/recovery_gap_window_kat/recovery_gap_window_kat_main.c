// Host KAT for BRRecoveryGapWindow.h -- how far the plain-DGB recovery scan derives on each path,
// and when a path's answer is finished or capped (4.1.3 G1). Carries every case of Android's
// RecoveryGapWidenTest (the Kotlin RecoveryGapWindow and the per-path decision in
// RecoveryScanService.classifyWidening, which the core replaced -> jni_recovery_gap.c): the
// gapWindowArithmetic assertions one by one, and each scan scenario as a simulation of the
// platform's loop (simulate() below, which does what classifyWidening does per round with a
// lookup that answers from a set of used indices).
//
// Built three times by run.sh:
//   -DRECOVERY_GAP_RED  a grown window grows only to a gap past the last use; the cases where the
//                       use sits near the far end fail, every other case holds
//   -DRECOVERY_CAP_RED  a path the cap or the round bound stopped is reported finished; the capped
//                       cases fail, every other case holds
//   (default)           the shipped rule; every case passes

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

// The lookup: the highest used index on chain below window (-1: none).
typedef int32_t (*Highest)(int chain, int32_t window, const void *ctx);

typedef struct {
    int outcome;
    int32_t external, internal;   // the windows the kept answer was derived at
    int rounds;                   // lookups made (one per round, as FakeUtxoSource counted them)
} Scan;

// One path through the platform's loop (classifyWidening with one profile).
static Scan simulate(int32_t external, int32_t internal, Highest highest, const void *ctx)
{
    Scan s;
    int32_t bound = BRRecoveryGapScanRoundBound(external, internal);
    int32_t round = 0;

    for (;;) {
        int32_t usedExternal = highest(0, external, ctx), usedInternal = highest(1, internal, ctx);
        int32_t nextExternal = BRRecoveryGapNext(0, external, usedExternal);
        int32_t nextInternal = BRRecoveryGapNext(1, internal, usedInternal);
        int outcome;

        round++;
        outcome = BRRecoveryGapOutcome(external, internal, nextExternal, nextInternal, usedExternal, usedInternal, round, bound);
        if (outcome != BR_RECOVERY_GAP_ASK_AGAIN || round > 10000) {
            s.outcome = outcome; s.external = external; s.internal = internal; s.rounds = round;
            return s;
        }
        external = nextExternal; internal = nextInternal;
    }
}

// A set of used external indices (the internal chain unused).
typedef struct { const int32_t *at; int n; } Used;

static int32_t fromSet(int chain, int32_t window, const void *ctx)
{
    const Used *u = ctx;
    int32_t best = -1;
    for (int i = 0; chain == 0 && i < u->n; i++) if (u->at[i] < window && u->at[i] > best) best = u->at[i];
    return best;
}

static int32_t everyNinety(int chain, int32_t window, const void *ctx)
{
    (void)ctx;
    if (chain != 0) return -1;
    int32_t top = window < BR_RECOVERY_MAX_EXTERNAL ? window : BR_RECOVERY_MAX_EXTERNAL;
    return ((top - 1) / 90) * 90;   // coins at 0, 90, ... below the window and below MAX_EXTERNAL
}

static int32_t everything(int chain, int32_t window, const void *ctx) { (void)chain; (void)ctx; return window - 1; }

// A lookup that always answers "used" exactly a gap from the far end: the adversarial lookup the
// whole-gap growth is for.
static int32_t gapFromTheEnd(int chain, int32_t window, const void *ctx)
{
    (void)ctx;
    return window - (chain == 0 ? BR_RECOVERY_GAP_EXTERNAL : BR_RECOVERY_GAP_INTERNAL);
}

static int32_t nothing(int chain, int32_t window, const void *ctx) { (void)chain; (void)window; (void)ctx; return -1; }

static const char *name(int outcome)
{
    return outcome == BR_RECOVERY_GAP_ASK_AGAIN ? "ask-again" : outcome == BR_RECOVERY_GAP_FINISHED ? "finished" :
           outcome == BR_RECOVERY_GAP_CAPPED ? "capped" : "?";
}

int main(void)
{
    char why[200];
    int ok;
    Scan s;

    // The values RecoveryGapWindow held (GAP_EXTERNAL, GAP_INTERNAL, MAX_EXTERNAL, MAX_INTERNAL).
    ok = BR_RECOVERY_GAP_EXTERNAL == 100 && BR_RECOVERY_GAP_INTERNAL == 50 && BR_RECOVERY_MAX_EXTERNAL == 2000 &&
         BR_RECOVERY_MAX_INTERNAL == 1000 && BR_RECOVERY_GAP_ASK_AGAIN == 0 && BR_RECOVERY_GAP_FINISHED == 1 &&
         BR_RECOVERY_GAP_CAPPED == 2;
    result("gaps_and_caps_are_kotlins", ok, NULL);

    // gapWindowArithmetic, one assertion each (Kotlin's next(count, used, 100, 2000) is chain 0).
    ok = BRRecoveryGapNext(0, 200, -1) == 200;
    result("nothing_used_keeps_the_window", ok, NULL);

    ok = BRRecoveryGapNext(0, 200, 99) == 200;
    result("use_a_gap_from_the_end_keeps_the_window", ok, NULL);

    ok = BRRecoveryGapNext(0, 200, 250) == 351;
    snprintf(why, sizeof(why), "next(200, 250) = %d", BRRecoveryGapNext(0, 200, 250));
    result("use_past_the_window_grows_a_gap_past_it", ok, why);

    // A grown window grows by at least a whole gap, even when the use sits right at its end.
    ok = BRRecoveryGapNext(0, 200, 100) == 300 && BRRecoveryGapNext(1, 100, 50) == 150 &&
         BRRecoveryGapNext(0, 50, 0) == 150;
    snprintf(why, sizeof(why), "next(200, 100) = %d, internal next(100, 50) = %d, next(50, 0) = %d",
             BRRecoveryGapNext(0, 200, 100), BRRecoveryGapNext(1, 100, 50), BRRecoveryGapNext(0, 50, 0));
    result("grown_window_grows_by_a_whole_gap", ok, why);

    ok = BRRecoveryGapNext(0, 1950, 1990) == 2000 && BRRecoveryGapNext(1, 990, 989) == 1000;
    result("growth_stops_at_the_cap", ok, NULL);

    ok = BRRecoveryGapCapped(0, 1990) && ! BRRecoveryGapCapped(0, 1899) && ! BRRecoveryGapCapped(0, -1) &&
         BRRecoveryGapCapped(1, 950) && ! BRRecoveryGapCapped(1, 949);
    result("use_within_a_gap_of_the_cap_is_capped", ok, NULL);

    // Never below count: a window already past the cap (a profile's own start) is kept.
    ok = BRRecoveryGapNext(0, 5000, 4999) == 5000 && BRRecoveryGapNext(1, 1200, 10) == 1200;
    result("never_below_count", ok, NULL);

    // Index 0 is a used address, not "nothing used": only a negative index is Kotlin's null.
    ok = BRRecoveryGapNext(0, 0, 0) == 101 && BRRecoveryGapNext(1, 0, 0) == 51 && BRRecoveryGapNext(0, 50, 0) > 50 &&
         BRRecoveryGapCapped(0, 0) == 0 && BRRecoveryGapOutcome(0, 0, 101, 51, 0, 0, 1, 22) == BR_RECOVERY_GAP_ASK_AGAIN;
    snprintf(why, sizeof(why), "next(0, 0) = %d, next(50, 0) = %d", BRRecoveryGapNext(0, 0, 0), BRRecoveryGapNext(0, 50, 0));
    result("index_zero_is_used", ok, why);

    // Kotlin's null is any negative index here (a real index never is).
    ok = BRRecoveryGapNext(0, 200, -5) == 200 && BRRecoveryGapNext(0, 0, INT32_MIN) == 0 && ! BRRecoveryGapCapped(1, -1);
    result("a_negative_index_is_nothing_used", ok, NULL);

    // Kotlin's Int wrapped: used + gap + 1 past INT32_MAX is negative, so neither grows nor caps.
    ok = BRRecoveryGapNext(0, 200, INT32_MAX) == 200 && ! BRRecoveryGapCapped(0, INT32_MAX - 50) &&
         BRRecoveryGapNext(0, INT32_MAX - 10, INT32_MAX - 200) == INT32_MAX - 10;
    result("arithmetic_wraps_as_kotlin_int", ok, NULL);

    // A chain the scan does not have never grows and is never a finished answer.
    ok = BRRecoveryGapNext(2, 200, 199) == 200 && BRRecoveryGapNext(-1, 7, 3) == 7 && BRRecoveryGapCapped(2, -1) &&
         BRRecoveryGapRoundBound(5, 0) == 2;
    result("unknown_chain_never_grows_and_is_capped", ok, NULL);

    // roundBound(start, gap, max) = (max(0, max - start) + gap - 1) / gap + 2.
    ok = BRRecoveryGapRoundBound(0, 200) == 20 && BRRecoveryGapRoundBound(1, 100) == 20 &&
         BRRecoveryGapRoundBound(0, 0) == 22 && BRRecoveryGapRoundBound(1, 0) == 22 &&
         BRRecoveryGapRoundBound(0, 1950) == 3 && BRRecoveryGapRoundBound(0, 1999) == 3 &&
         BRRecoveryGapRoundBound(0, 2000) == 2 && BRRecoveryGapRoundBound(1, 5000) == 2 &&
         BRRecoveryGapScanRoundBound(200, 100) == 20 && BRRecoveryGapScanRoundBound(1950, 100) == 20 &&
         BRRecoveryGapScanRoundBound(0, 0) == 22 && BRRecoveryGapScanRoundBound(5000, 5000) == 2;
    result("round_bound", ok, NULL);

    // The per-path decision classifyWidening made: grew && round < bound asks again; otherwise
    // capped = grew || capped(external) || capped(internal).
    ok = BRRecoveryGapOutcome(200, 100, 300, 100, 199, -1, 1, 20) == BR_RECOVERY_GAP_ASK_AGAIN &&
         BRRecoveryGapOutcome(200, 100, 200, 150, -1, 99, 19, 20) == BR_RECOVERY_GAP_ASK_AGAIN &&
         BRRecoveryGapOutcome(200, 100, 200, 100, 150, 40, 1, 20) == BR_RECOVERY_GAP_FINISHED &&
         BRRecoveryGapOutcome(200, 100, 200, 100, -1, -1, 50, 20) == BR_RECOVERY_GAP_FINISHED;
    result("growing_path_asks_again_and_settled_path_is_finished", ok, NULL);

    ok = BRRecoveryGapOutcome(200, 100, 300, 100, 199, -1, 20, 20) == BR_RECOVERY_GAP_CAPPED &&
         BRRecoveryGapOutcome(200, 100, 200, 150, -1, 99, 21, 20) == BR_RECOVERY_GAP_CAPPED;
    snprintf(why, sizeof(why), "grew at round 20 of 20 -> %s", name(BRRecoveryGapOutcome(200, 100, 300, 100, 199, -1, 20, 20)));
    result("round_bound_stop_is_capped", ok, why);

    ok = BRRecoveryGapOutcome(2000, 100, 2000, 100, 1990, -1, 5, 20) == BR_RECOVERY_GAP_CAPPED &&
         BRRecoveryGapOutcome(200, 1000, 200, 1000, -1, 999, 5, 20) == BR_RECOVERY_GAP_CAPPED;
    snprintf(why, sizeof(why), "external cap -> %s", name(BRRecoveryGapOutcome(2000, 100, 2000, 100, 1990, -1, 5, 20)));
    result("cap_stop_is_capped", ok, why);

    // aCoinPastTheFixedWindowIsFound: 150 inside the old 200 window, 250 past it.
    {
        static const int32_t at[] = { 150, 250 };
        Used u = { at, 2 };
        s = simulate(200, 100, fromSet, &u);
        ok = s.outcome == BR_RECOVERY_GAP_FINISHED && s.external > 250;
        snprintf(why, sizeof(why), "%s at %d/%d after %d rounds", name(s.outcome), s.external, s.internal, s.rounds);
        result("coin_past_the_fixed_window_is_found", ok, why);
    }

    // anEmptyPathIsAskedOnce.
    s = simulate(200, 100, nothing, NULL);
    ok = s.outcome == BR_RECOVERY_GAP_FINISHED && s.rounds == 1 && s.external == 200 && s.internal == 100;
    result("empty_path_is_asked_once", ok, NULL);

    // aPathStillInUseAtTheCapIsMarkedIncomplete: a coin every 90 indices up to the cap; the kept
    // answer is derived at MAX_EXTERNAL external addresses and is capped.
    s = simulate(200, 100, everyNinety, NULL);
    ok = s.outcome == BR_RECOVERY_GAP_CAPPED && s.external == BR_RECOVERY_MAX_EXTERNAL;
    snprintf(why, sizeof(why), "%s at %d external after %d rounds", name(s.outcome), s.external, s.rounds);
    result("path_in_use_at_the_cap_is_capped", ok, why);

    // anAddressPaidButEmptiedCountsAsUsed: 190 holds nothing but a returned transaction paid it,
    // so the window grows and finds the coin at 280 (which address is "used" is the platform's;
    // here the lookup says 10 and 190, then 280 once it is derived).
    {
        static const int32_t at[] = { 10, 190, 280 };
        Used u = { at, 3 };
        s = simulate(200, 100, fromSet, &u);
        ok = s.outcome == BR_RECOVERY_GAP_FINISHED && s.external > 280;
        snprintf(why, sizeof(why), "%s at %d", name(s.outcome), s.external);
        result("paid_but_emptied_address_widens_the_window", ok, why);
    }

    // aLookupThatAlwaysAnswersUsedIsBoundedAndAskedOnlyForGrownPaths: every address used; the
    // window reaches the cap in whole-gap steps, within roundBound(100, 50, 1000) lookups, capped.
    s = simulate(200, 100, everything, NULL);
    ok = s.outcome == BR_RECOVERY_GAP_CAPPED && s.rounds <= BRRecoveryGapRoundBound(1, 100) &&
         s.external == BR_RECOVERY_MAX_EXTERNAL && s.internal == BR_RECOVERY_MAX_INTERNAL;
    snprintf(why, sizeof(why), "%s after %d rounds (bound %d)", name(s.outcome), s.rounds, BRRecoveryGapRoundBound(1, 100));
    result("always_used_lookup_is_bounded_and_capped", ok, why);

    // A lookup that always answers "used" a gap from the far end still reaches the cap in whole
    // gaps: growing one address per round would stop at the round bound far below the cap.
    s = simulate(200, 100, gapFromTheEnd, NULL);
    ok = s.outcome == BR_RECOVERY_GAP_CAPPED && s.external == BR_RECOVERY_MAX_EXTERNAL &&
         s.internal == BR_RECOVERY_MAX_INTERNAL && s.rounds <= BRRecoveryGapScanRoundBound(200, 100);
    snprintf(why, sizeof(why), "%s at %d/%d after %d rounds", name(s.outcome), s.external, s.internal, s.rounds);
    result("use_near_the_far_end_reaches_the_cap_in_whole_gaps", ok, why);

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
