// Host KAT for BRDandelionEmbargo.h: the Dandelion++ embargo window, its delay from a random
// draw, the fluff decision at the deadline (on whichever manager is current), the re-arm wait,
// which embargoes are pending, and the stranded-send sweep's spare of a pending one.
//
// Every expectation but the wall-clock deadline's and the re-arm wait's is fixed outside the
// header: the delay vectors file is Android's arithmetic computed by
// vectors/dandelion-embargo/generate.py, the boundary sweep below recomputes Android's expression
// in this file's own double arithmetic, and the rest are literals carried from
// DandelionBroadcastPolicyTest.kt (digibytewallet-android v4.1.3) or from Broadcaster.kt,
// jni_transaction.c and SyncService.kt's behaviour, cited at each case.
//
// The wall-clock deadline and the re-arm wait (BRDandelionEmbargoDeadlineMs and
// BRDandelionEmbargoRemainingMs: deadline_ms, rearm_wait, rearm_clock_set_back_is_capped, and the
// deadline and "past due" checks in deadline_step_after_a_teardown) have no Android counterpart:
// Android keeps no deadline, only one coroutine delay() that is never asked again (Broadcaster.kt
// armEmbargo). Those cases test the header's own rule, for a platform whose timers are suspended
// with the app (iOS): a wait asked again never exceeds the window, and one already due runs at once.
//
//   argv[1]  path of vectors/dandelion-embargo/vectors.txt
//
// Prints "RESULT <case> pass|fail" per case and "ALL PASS" when every case passed.

#include "BRDandelionEmbargo.h"
#include "BRDandelionEmbargo.h"   // the include guard holds

#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_caseFailed, g_anyFailed;
static const char *g_case;

#define CHECK(cond, ...) do { if (! (cond)) { g_caseFailed = 1; printf("FAIL: %s: ", g_case); \
    printf(__VA_ARGS__); printf("\n"); } } while (0)

static void begin(const char *name) { g_case = name; g_caseFailed = 0; }
static void end(void)
{
    printf("RESULT %s %s\n", g_case, g_caseFailed ? "fail" : "pass");
    if (g_caseFailed) g_anyFailed = 1;
}

// Android's expression, DandelionBroadcastPolicy.kt:32-35, at rng01 = draw * 2^-53, in this
// file's doubles (x86-64 SSE2 / arm64: binary64, round to nearest even). volatile keeps the one
// product a real binary64 multiply.
static int64_t kotlinDelay(uint64_t draw)
{
    volatile double rng01 = (double)draw * 0x1p-53;
    if (rng01 < 0.0) rng01 = 0.0;
    if (rng01 > 1.0) rng01 = 1.0;
    volatile double product = rng01 * 20000.0;
    return 10000 + (int64_t)product;
}

// ---- the vectors file ----

typedef struct { uint64_t draw; int64_t ms; char why[32]; } Vec;
static Vec *g_vec;
static size_t g_nvec;

static int loadVectors(const char *path)
{
    FILE *f = fopen(path, "r");
    char line[256];
    size_t cap = 0;

    if (! f) { printf("cannot open %s\n", path); return 0; }
    while (fgets(line, sizeof(line), f)) {
        Vec v;
        if (line[0] == '#' || line[0] == '\n') continue;
        if (sscanf(line, "delay %" SCNu64 " %" SCNd64 " %31s", &v.draw, &v.ms, v.why) != 3) {
            printf("bad vector line: %s", line); fclose(f); return 0;
        }
        if (g_nvec == cap) { cap = cap ? cap * 2 : 256; g_vec = realloc(g_vec, cap * sizeof(*g_vec)); }
        g_vec[g_nvec++] = v;
    }
    fclose(f);
    return g_nvec > 0;
}

static size_t checkVectors(const char *whyPrefix)
{
    size_t n = 0;
    for (size_t i = 0; i < g_nvec; i++) {
        if (strncmp(g_vec[i].why, whyPrefix, strlen(whyPrefix)) != 0) continue;
        n++;
        int64_t got = BRDandelionEmbargoDelayMs(g_vec[i].draw);
        CHECK(got == g_vec[i].ms, "draw %" PRIu64 " (%s): %" PRId64 ", Android %" PRId64,
              g_vec[i].draw, g_vec[i].why, got, g_vec[i].ms);
    }
    return n;
}

// ---- cases ----

// The window and draw constants are Android's: EMBARGO_MIN_MS = 10_000L, EMBARGO_MAX_MS = 30_000L
// (DandelionBroadcastPolicy.kt:6-7); the draw is nextDouble's 53 bits.
static void constants_are_androids(void)
{
    begin("constants_are_androids");
    CHECK(BR_DANDELION_EMBARGO_MIN_MS == 10000, "MIN %lld", (long long)BR_DANDELION_EMBARGO_MIN_MS);
    CHECK(BR_DANDELION_EMBARGO_MAX_MS == 30000, "MAX %lld", (long long)BR_DANDELION_EMBARGO_MAX_MS);
    CHECK(BR_DANDELION_EMBARGO_SPAN_MS == 20000, "SPAN %lld", (long long)BR_DANDELION_EMBARGO_SPAN_MS);
    CHECK(BR_DANDELION_EMBARGO_DRAW_BITS == 53, "DRAW_BITS %d", BR_DANDELION_EMBARGO_DRAW_BITS);
    CHECK(BR_DANDELION_EMBARGO_DRAW_LIMIT == 9007199254740992ULL, "DRAW_LIMIT");
    CHECK(BR_DANDELION_EMBARGO_DRAW_MAX == 9007199254740991ULL, "DRAW_MAX");
    end();
}

// The edges: draw 0 -> MIN; the largest draw -> 29999, just under MAX (Android's largest
// nextDouble, 1 - 2^-53); a draw at or past 2^53 -> MAX (Android's coerceIn clamp of 1.0).
static void delay_edges(void)
{
    begin("delay_edges");
    CHECK(BRDandelionEmbargoDelayMs(0) == 10000, "draw 0");
    CHECK(BRDandelionEmbargoDelayMs(BR_DANDELION_EMBARGO_DRAW_MAX) == 29999, "max draw %" PRId64,
          BRDandelionEmbargoDelayMs(BR_DANDELION_EMBARGO_DRAW_MAX));
    CHECK(BRDandelionEmbargoDelayMs(BR_DANDELION_EMBARGO_DRAW_LIMIT) == 30000, "draw 2^53");
    CHECK(BRDandelionEmbargoDelayMs(UINT64_MAX) == 30000, "draw 2^64-1");
    CHECK(BRDandelionEmbargoDelayMs(1ULL << 52) == 20000, "draw 2^52");
    size_t n = checkVectors("zero") + checkVectors("one") + checkVectors("two") + checkVectors("half") +
               checkVectors("max_draw") + checkVectors("limit_clamps") + checkVectors("above_limit") +
               checkVectors("u64_max");
    CHECK(n == 9, "expected 9 edge vectors, read %zu", n);
    end();
}

// DandelionBroadcastPolicyTest's inputs 0.0, 0.5, 0.999, 1.0, as draws: within [MIN, MAX], and at
// Android's exact values (0.999 * 2^53 is exact: a double in [0.5, 1) is a multiple of 2^-53).
// 0.999 is a draw the double's rounding carries up: the product is 19979.99... before rounding and
// 19980.0 after, so Android waits 29980 ms where the plain quotient says 29979. RED under
// DANDELION_EMBARGO_DELAY_RED.
static void delay_kotlin_test_inputs(void)
{
    begin("delay_kotlin_test_inputs");
    size_t n = checkVectors("kotlin_test_");
    CHECK(n == 4, "expected 4 Kotlin test vectors, read %zu", n);
    for (size_t i = 0; i < g_nvec; i++) {
        if (strncmp(g_vec[i].why, "kotlin_test_", 12) != 0) continue;
        int64_t ms = BRDandelionEmbargoDelayMs(g_vec[i].draw);
        CHECK(ms >= BR_DANDELION_EMBARGO_MIN_MS && ms <= BR_DANDELION_EMBARGO_MAX_MS, "%s out of range", g_vec[i].why);
    }
    CHECK(BRDandelionEmbargoDelayMs(8998192055486251ULL) == 29980, "0.999: 29980, not the quotient's 29979");
    end();
}

// The draws where the double's rounding carries the product up to a whole millisecond: Android
// is 1 ms above the exact quotient there. RED under DANDELION_EMBARGO_DELAY_RED.
static void delay_rounds_up_vectors(void)
{
    begin("delay_rounds_up_vectors");
    size_t n = checkVectors("rounds_up");
    CHECK(n == 200, "expected 200 rounds_up vectors, read %zu", n);
    end();
}

// Every draw within 3 of every millisecond boundary, against this file's own double arithmetic.
// RED under DANDELION_EMBARGO_DELAY_RED.
static void delay_every_boundary_matches_double(void)
{
    begin("delay_every_boundary_matches_double");
    size_t checked = 0, carried = 0, bad = 0;
    for (uint64_t m = 1; m < 20000; m++) {
        uint64_t base = (m << 48) / 625;
        for (uint64_t d = base - 3; d <= base + 3; d++) {
            int64_t want = kotlinDelay(d), got = BRDandelionEmbargoDelayMs(d);
            checked++;
            if (want != 10000 + (int64_t)((d * 625) >> 48)) carried++;
            if (got != want && bad++ < 5) CHECK(0, "draw %" PRIu64 ": %" PRId64 ", Kotlin %" PRId64, d, got, want);
        }
    }
    CHECK(bad == 0, "%zu boundary draws differ from Kotlin", bad);
    CHECK(carried == 7434, "the sweep reached %zu carrying draws, expected 7434", carried);
    printf("  %zu boundary draws checked, %zu of them carried by the rounding\n", checked, carried);
    end();
}

// Draws away from the rounding: the boundary vectors where the quotient and Android agree, and
// fixed pseudo-random ones.
static void delay_boundary_and_random_vectors(void)
{
    begin("delay_boundary_and_random_vectors");
    size_t b = checkVectors("boundary"), r = checkVectors("random");
    CHECK(b == 200 && r == 500, "read %zu boundary and %zu random vectors", b, r);
    end();
}

// A million draws through the platform's own path (64 random bits -> BRDandelionEmbargoDraw): in
// the window, never MAX, and equal to Kotlin's expression on this file's doubles.
static void delay_random_draws_match_double(void)
{
    begin("delay_random_draws_match_double");
    uint64_t x = 0x9e3779b97f4a7c15ULL;
    size_t bad = 0;
    for (int i = 0; i < 1000000; i++) {
        x ^= x << 13; x ^= x >> 7; x ^= x << 17;   // xorshift64: a fixed sequence, not randomness
        uint64_t draw = BRDandelionEmbargoDraw(x);
        int64_t got = BRDandelionEmbargoDelayMs(draw);
        if (draw >= BR_DANDELION_EMBARGO_DRAW_LIMIT && bad++ < 5) CHECK(0, "draw %" PRIu64 " not below 2^53", draw);
        if ((got < 10000 || got > 29999) && bad++ < 5) CHECK(0, "draw %" PRIu64 ": %" PRId64 " outside [10000, 29999]", draw, got);
        if (got != kotlinDelay(draw) && bad++ < 5) CHECK(0, "draw %" PRIu64 ": %" PRId64 ", Kotlin %" PRId64, draw, got, kotlinDelay(draw));
    }
    CHECK(bad == 0, "%zu bad draws", bad);
    end();
}

// The draw is the top 53 bits of 64 random bits.
static void draw_is_top_53_bits(void)
{
    begin("draw_is_top_53_bits");
    CHECK(BRDandelionEmbargoDraw(UINT64_MAX) == 9007199254740991ULL, "all ones");
    CHECK(BRDandelionEmbargoDraw(0) == 0, "zero");
    CHECK(BRDandelionEmbargoDraw(0x7ff) == 0, "the low 11 bits are dropped");
    CHECK(BRDandelionEmbargoDraw(0x800) == 1, "bit 11 is the draw's bit 0");
    CHECK(BRDandelionEmbargoDraw(0x8000000000000000ULL) == 4503599627370496ULL, "the top bit is the draw's bit 52");
    end();
}

// shouldStem(enabled, hasDandelionPeer) = enabled && hasDandelionPeer (DandelionBroadcastPolicyTest).
static void stem_rule(void)
{
    begin("stem_rule");
    CHECK(BRDandelionShouldStem(1, 1) == 1, "enabled, peer");
    CHECK(BRDandelionShouldStem(1, 0) == 0, "enabled, no peer");
    CHECK(BRDandelionShouldStem(0, 1) == 0, "disabled, peer");
    CHECK(BRDandelionShouldStem(0, 0) == 0, "disabled, no peer");
    end();
}

// shouldFluffAfterEmbargo(relayCount) = relayCount == 0 (DandelionBroadcastPolicyTest: 0, 1, 5).
static void fluff_rule(void)
{
    begin("fluff_rule");
    CHECK(BRDandelionEmbargoShouldFluff(0) == 1, "0 relays");
    CHECK(BRDandelionEmbargoShouldFluff(1) == 0, "1 relay");
    CHECK(BRDandelionEmbargoShouldFluff(5) == 0, "5 relays");
    CHECK(BRDandelionEmbargoShouldFluff(SIZE_MAX) == 0, "SIZE_MAX relays");
    end();
}

// The deadline step on the stem's own manager (Broadcaster.kt:69-74): fluff on 0 relays only.
static void deadline_step_on_the_stem_manager(void)
{
    begin("deadline_step_on_the_stem_manager");
    CHECK(BRDandelionEmbargoAtDeadline(1, 0) == BRDandelionDeadlineFluff, "0 relays: fluff");
    CHECK(BRDandelionEmbargoAtDeadline(1, 1) == BRDandelionDeadlineRelease, "1 relay: release");
    CHECK(BRDandelionEmbargoAtDeadline(1, 5) == BRDandelionDeadlineRelease, "5 relays: release");
    CHECK(BRDandelionDeadlineRelease == 0 && BRDandelionDeadlineFluff == 1, "enum values");
    end();
}

// No manager at the deadline: jni_transaction.c:617 reads 0 relays and :606 returns from the
// fluff, so nothing is sent; then Broadcaster.kt:76 ends the embargo.
static void deadline_step_without_a_manager(void)
{
    begin("deadline_step_without_a_manager");
    CHECK(BRDandelionEmbargoAtDeadline(0, 0) == BRDandelionDeadlineRelease, "no manager, 0");
    CHECK(BRDandelionEmbargoAtDeadline(0, 3) == BRDandelionDeadlineRelease, "no manager, 3");
    end();
}

// The manager the stem went to was torn down before the deadline (iOS: every trip to the
// background; Android: forceReconnect -> startSync, jni_peer.c:879-893). The step reads the NEW
// manager: 0 relays -> fluff on it (no publish entry there, so it sends nothing and the send is
// left to the sweep); relays on the new manager -> release. Then the embargo ends and the sweep
// may re-publish: no immediate flood is substituted.
static void deadline_step_after_a_teardown(void)
{
    begin("deadline_step_after_a_teardown");
    int64_t armedAt = 1763000000000LL;
    int64_t deadline = BRDandelionEmbargoDeadlineMs(armedAt, BRDandelionEmbargoDelayMs(1ULL << 52));
    CHECK(deadline == armedAt + 20000, "deadline %" PRId64, deadline);
    // Foreground 45 s later, new manager, not yet relayed by any of its peers.
    CHECK(BRDandelionEmbargoRemainingMs(armedAt + 45000, deadline) == 0, "past due: at once");
    CHECK(BRDandelionEmbargoAtDeadline(1, 0) == BRDandelionDeadlineFluff, "new manager, 0 relays: its fluff (a no-op)");
    CHECK(BRDandelionEmbargoAtDeadline(1, 2) == BRDandelionDeadlineRelease, "new manager, 2 relays: release");
    CHECK(BRDandelionEmbargoIsPending(1, 1) == 0, "after the step the embargo is over");
    CHECK(BRDandelionEmbargoSweepMayRepublish(BRDandelionEmbargoIsPending(1, 1)) == 1, "the sweep may re-publish");
    end();
}

// Deadline = the wall clock when the stem returned 1, plus the delay; saturating.
static void deadline_ms(void)
{
    begin("deadline_ms");
    CHECK(BRDandelionEmbargoDeadlineMs(1763000000000LL, 10000) == 1763000010000LL, "plus MIN");
    CHECK(BRDandelionEmbargoDeadlineMs(1763000000000LL, 29999) == 1763000029999LL, "plus 29999");
    CHECK(BRDandelionEmbargoDeadlineMs(INT64_MAX - 5, 10000) == INT64_MAX, "saturates");
    CHECK(BRDandelionEmbargoDeadlineMs(0, 30000) == 30000, "from 0");
    end();
}

// The wait at arming and at re-arm; a deadline already passed (or now) runs at once.
static void rearm_wait(void)
{
    begin("rearm_wait");
    int64_t d = 1763000020000LL;
    CHECK(BRDandelionEmbargoRemainingMs(d - 20000, d) == 20000, "at arming");
    CHECK(BRDandelionEmbargoRemainingMs(d - 1, d) == 1, "1 ms left");
    CHECK(BRDandelionEmbargoRemainingMs(d, d) == 0, "due now: at once");
    CHECK(BRDandelionEmbargoRemainingMs(d + 1, d) == 0, "1 ms past: at once");
    CHECK(BRDandelionEmbargoRemainingMs(d + 86400000LL, d) == 0, "a day past: at once");
    CHECK(BRDandelionEmbargoRemainingMs(INT64_MAX, INT64_MIN) == 0, "far past: at once");
    CHECK(BRDandelionEmbargoRemainingMs(d - 30000, d) == 30000, "exactly MAX left");
    end();
}

// A wall clock set back: the wait never exceeds MAX, so the sweep's spare cannot outlast the
// window. RED under DANDELION_EMBARGO_REARM_RED.
static void rearm_clock_set_back_is_capped(void)
{
    begin("rearm_clock_set_back_is_capped");
    int64_t d = 1763000020000LL;
    CHECK(BRDandelionEmbargoRemainingMs(d - 30001, d) == 30000, "MAX + 1 left");
    CHECK(BRDandelionEmbargoRemainingMs(d - 3600000LL, d) == 30000, "clock an hour back");
    CHECK(BRDandelionEmbargoRemainingMs(INT64_MIN, INT64_MAX) == 30000, "extreme");
    end();
}

// Pending: armed by this process and its deadline step not run (Broadcaster.kt:66 adds, :76
// removes after the step).
static void pending_in_this_process(void)
{
    begin("pending_in_this_process");
    CHECK(BRDandelionEmbargoIsPending(1, 0) == 1, "armed, step not run: pending");
    CHECK(BRDandelionEmbargoIsPending(1, 1) == 0, "armed, step run: over");
    CHECK(BRDandelionEmbargoIsPending(0, 1) == 0, "earlier process, step run: not pending");
    end();
}

// An embargo written down by an earlier process is not pending: Android's set is in memory
// (Broadcaster.kt:33), and after a restart the sweep re-publishes (DandelionBroadcastPolicy.kt:21).
// RED under DANDELION_EMBARGO_PENDING_RED.
static void pending_earlier_process_is_not(void)
{
    begin("pending_earlier_process_is_not");
    CHECK(BRDandelionEmbargoIsPending(0, 0) == 0, "earlier process, step not run: not pending");
    CHECK(BRDandelionEmbargoSweepMayRepublish(BRDandelionEmbargoIsPending(0, 0)) == 1, "the sweep may re-publish");
    end();
}

// shouldSweepRepublish(embargoPending) = !embargoPending (DandelionBroadcastPolicyTest).
static void sweep_rule(void)
{
    begin("sweep_rule");
    CHECK(BRDandelionEmbargoSweepMayRepublish(1) == 0, "pending: spared");
    CHECK(BRDandelionEmbargoSweepMayRepublish(0) == 1, "not pending: re-published");
    CHECK(BRDandelionEmbargoSweepMayRepublish(BRDandelionEmbargoIsPending(1, 0)) == 0, "this process's running embargo: spared");
    end();
}

int main(int argc, char **argv)
{
    if (argc < 2 || ! loadVectors(argv[1])) { printf("usage: %s <vectors.txt>\n", argv[0]); return 2; }
    printf("read %zu delay vectors\n", g_nvec);

    constants_are_androids();
    delay_edges();
    delay_kotlin_test_inputs();
    delay_rounds_up_vectors();
    delay_every_boundary_matches_double();
    delay_boundary_and_random_vectors();
    delay_random_draws_match_double();
    draw_is_top_53_bits();
    stem_rule();
    fluff_rule();
    deadline_step_on_the_stem_manager();
    deadline_step_without_a_manager();
    deadline_step_after_a_teardown();
    deadline_ms();
    rearm_wait();
    rearm_clock_set_back_is_capped();
    pending_in_this_process();
    pending_earlier_process_is_not();
    sweep_rule();

    free(g_vec);
    if (g_anyFailed) return 1;
    printf("ALL PASS\n");
    return 0;
}
