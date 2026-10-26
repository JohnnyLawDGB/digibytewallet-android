// Host KAT for the refusal hold in BRStrandedSend.h -- when the stranded-send sweep removes a send
// the network refused, which unlocks its coins -- from Android's PublishOutcome.actionFor,
// RefusalHold.kt and InvalidSendRemoval.kt.
//
// Every case of these Kotlin tests is carried over with its values:
//   PublishOutcomeTest     the four actionFor cases          (po_*)
//   RefusalHoldTest        all fourteen                      (rh_*)
//   InvalidSendRemovalTest all four                          (inv_*)
// then the cases the Kotlin cannot reach: SyncService.isPlainDgbSend's composition (private to the
// service, so untested there), the call site's "no verdict keeps", and the inputs Kotlin's types
// forbid (a hold of 0 ms, a NULL hold). Expected values are literals from the Kotlin tests, never
// derived from the header under test.
//
// The Kotlin harness reads a clock (`now`) and the peer manager (`manager`, null when unknown)
// through lambdas; here they are the globals g_now and g_manager / g_managerKnown, and seen()
// reads the manager before and after the answer as RefusalHold.observe does.
//
// Built five ways by run.sh:
//   -DSTRANDED_REFUSAL_RED_NO_PLAIN_GATE  an asset or DigiDollar send is removed too
//   -DSTRANDED_REFUSAL_RED_ONE_PEER       one peer's terminal rejection removes the send
//   -DSTRANDED_REFUSAL_RED_NO_HOLD        the first sighting of a refusal is acted on
//   -DSTRANDED_REFUSAL_RED_ANY_MANAGER    a hold spans a rebuilt (or unknown) peer manager
//   (default)                             the shipped rule; every case passes
// run.sh names the cases each comparison arm must fail; every other case must pass there.

#include "BRStrandedSend.h"
#include "BRStrandedSend.h"   // the include guard
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

static int g_failed = 0, g_ok = 1;
static const char *g_case = "";

static void begin(const char *name) { g_case = name; g_ok = 1; }

static void ck(int cond, const char *fmt, ...)
{
    va_list ap;

    if (cond) return;
    printf("FAIL: %s: ", g_case);
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    g_ok = 0;
}

static void end(void)
{
    printf("RESULT %s %s\n", g_case, g_ok ? "pass" : "fail");
    if (! g_ok) g_failed = 1;
}

#define KEEP   BRStrandedRefusalKeep
#define REMOVE BRStrandedRefusalRemoveAndUnlock

// ---- RefusalHoldTest's harness ----
static const int64_t MIN = 60000;     // private val min = 60_000L
static int64_t g_now;                  // private var now = 1_000_000L
static int64_t g_manager;              // private var manager: Long? = 1L
static int g_managerKnown;
static BRStrandedRefusalHold g_tx, g_other;   // tx = "a" x 64, other = "b" x 64

static void reset(int64_t now)
{
    g_now = now; g_manager = 1; g_managerKnown = 1;
    memset(&g_tx, 0, sizeof(g_tx));
    memset(&g_other, 0, sizeof(g_other));
}

// hold.observe(txid) { refused }, with the default hold.
static int seenOn(BRStrandedRefusalHold *h, int refused)
{
    return BRStrandedRefusalHoldObserve(h, refused, g_managerKnown, g_manager, g_managerKnown, g_manager,
                                        g_now, BR_STRANDED_REFUSAL_HOLD_MS);
}
static int seen(void) { return seenOn(&g_tx, 1); }

// rejected.actionFor(plainDgb = true, refusedByPeers = ...)
static BRStrandedRefusalAction rejectedPlain(int refusedByPeers)
{
    return BRStrandedRefusalActionFor(EINVAL, 1, refusedByPeers);
}

int main(void)
{
    int i;

    // ================= PublishOutcomeTest: what the sweep does with a verdict =================

    begin("po_corroborated_refusal_removes_plain");
    ck(BRStrandedRefusalActionFor(EINVAL, 1, 1) == REMOVE,
       "a send the network refused for good is only logged; its coins stay locked");
    end();

    begin("po_single_peer_rejection_removes_nothing");
    ck(BRStrandedRefusalActionFor(EINVAL, 1, 0) == KEEP,
       "one peer's rejection removed the send and unlocked its coins");
    end();

    begin("po_asset_or_dd_send_left_alone");
    for (i = 0; i <= 1; i++)
        ck(BRStrandedRefusalActionFor(EINVAL, 0, i) == KEEP, "an asset or DigiDollar send (corroborated %d) was removed", i);
    end();

    begin("po_nothing_but_terminal_removes");
    {
        const int errs[] = { 0, ENOTCONN, ETIMEDOUT, ECANCELED, 9999 };
        size_t e;
        for (e = 0; e < sizeof(errs) / sizeof(errs[0]); e++)
            for (i = 0; i <= 1; i++)
                ck(BRStrandedRefusalActionFor(errs[e], i, 1) == KEEP,
                   "errno %d (plain DGB: %d) must not remove a send", errs[e], i);
    }
    end();

    // ================= RefusalHoldTest =================

    begin("rh_seen_once_not_acted");
    reset(1000000);
    ck(! seen(), "the first sighting of a refusal is acted on at once");
    end();

    begin("rh_sweep_keeps_just_seen");
    reset(1000000);
    ck(rejectedPlain(seen()) == KEEP, "a refusal seen once removes the send and unlocks its coins");
    end();

    begin("rh_acted_after_ten_minutes");
    reset(1000000);
    ck(! seen(), "first sighting");
    g_now += 10 * MIN - 1;
    ck(! seen(), "acted on before ten minutes had passed");
    g_now += 1;
    ck(seen(), "a refusal that held at two observations ten minutes apart is not acted on");
    ck(rejectedPlain(seen()) == REMOVE, "held, terminal and plain, but kept");
    end();

    begin("rh_sweep_cadence");
    reset(1000000);
    {
        int s[8];
        for (i = 0; i <= 7; i++) { g_now = 1000000 + (int64_t)i * 90000; s[i] = seen(); }
        for (i = 0; i < 7; i++) ck(! s[i], "acted on inside the ten minutes (sweep %d)", i);
        ck(s[7], "not acted on at the first sweep ten minutes after the first sighting");
    }
    end();

    begin("rh_false_answer_forgets");
    reset(1000000);
    ck(! seen(), "first sighting");
    g_now += 5 * MIN;
    ck(! seenOn(&g_tx, 0), "a not-refused answer acted");
    g_now += 5 * MIN;
    ck(! seen(), "a refusal interrupted by a not-refused answer still counts from its first sighting");
    g_now += 10 * MIN - 1;
    ck(! seen(), "acted before ten minutes from the new first sighting");
    g_now += 1;
    ck(seen(), "not acted ten minutes after the new first sighting");
    end();

    begin("rh_each_send_own_sighting");
    reset(1000000);
    ck(! seen(), "tx first sighting");
    g_now += 5 * MIN;
    ck(! seenOn(&g_other, 1), "other first sighting");
    g_now += 5 * MIN;
    ck(seen(), "tx held ten minutes");
    ck(! seenOn(&g_other, 1), "one send's refusal time was used for another's");
    g_now += 5 * MIN;
    ck(seenOn(&g_other, 1), "other held ten minutes");
    end();

    begin("rh_clock_step_back");
    reset(1000000);
    ck(! seen(), "first sighting");
    g_now -= 30 * MIN;
    ck(! seen(), "a clock stepping back was read as time passing");
    g_now += 30 * MIN + 10 * MIN - 1;
    ck(! seen(), "acted one millisecond early");
    g_now += 1;
    ck(seen(), "not acted at ten minutes");
    end();

    begin("rh_new_session_remembers_nothing");
    reset(1000000);
    ck(! seen(), "first sighting");
    g_now += 60 * MIN;
    {
        BRStrandedRefusalHold fresh = { 0, 0, 0 };   // RefusalHold(clock, manager): a new instance
        ck(! seenOn(&fresh, 1), "a refusal first seen in another session counted in this one");
    }
    end();

    begin("rh_forgotten_starts_again");
    reset(1000000);
    ck(! seen(), "first sighting");
    g_now += 10 * MIN;
    BRStrandedRefusalHoldForget(&g_tx);
    ck(! seen(), "a forgotten send kept its first sighting");
    end();

    begin("rh_hold_is_ten_minutes");
    ck(BR_STRANDED_REFUSAL_HOLD_MS == 10 * MIN, "the hold is %lld ms, not %lld",
       (long long)BR_STRANDED_REFUSAL_HOLD_MS, (long long)(10 * MIN));
    ck(BR_STRANDED_REFUSAL_HOLD_MS == 600000LL, "the hold is not RefusalHold.HOLD_MS (600,000 ms)");
    end();

    begin("rh_rebuilt_manager_not_acted");
    reset(1000000);
    ck(! seen(), "first sighting");
    g_manager = 2;                         // rebuilt: the new manager's relay records start empty
    g_now += 10 * MIN;
    ck(! seen(), "a refusal first seen on one peer manager was acted on from another's answer");
    g_now += 10 * MIN - 1;
    ck(! seen(), "the hold did not restart on the rebuilt peer manager");
    g_now += 1;
    ck(seen(), "a refusal held ten minutes on one peer manager is not acted on");
    end();

    begin("rh_sweep_keeps_straddling_rebuild");
    reset(1000000);
    ck(rejectedPlain(seen()) == KEEP, "removed at the first sighting");
    g_manager = 2;
    g_now += 10 * MIN;
    ck(rejectedPlain(seen()) == KEEP, "removed on a rebuilt peer manager's first answer");
    end();

    begin("rh_answer_across_rebuild_no_sighting");
    reset(1000000);
    ck(! seen(), "first sighting");
    g_now += 10 * MIN;
    // hold.observe(tx) { manager = 2L; true }: read 1 before the answer, 2 after it
    ck(! BRStrandedRefusalHoldObserve(&g_tx, 1, 1, 1, 1, 2, g_now, BR_STRANDED_REFUSAL_HOLD_MS),
       "an answer read across a rebuild counted");
    g_manager = 2;
    ck(! seen(), "the hold did not restart after an answer read across a rebuild");
    g_now += 10 * MIN;
    ck(seen(), "not acted ten minutes after the restart");
    end();

    begin("rh_unknown_manager_never_completes");
    reset(1000000);
    g_managerKnown = 0;                    // manager = null
    ck(! seen(), "first sighting");
    g_now += 30 * MIN;
    ck(! seen(), "a refusal from an unknown peer manager was acted on");
    end();

    // ================= InvalidSendRemovalTest: the conflicted verdict holds the same way =================
    // RefusalHold(clock = { now }, manager = { 1L }, holdMs = 600_000L), now = 0. mayRemove observes
    // "refused" = !isValid() under its own key: here, its own slot.
    {
        BRStrandedRefusalHold inv, refusal;
#define MAY_REMOVE(valid) BRStrandedRefusalHoldObserve(&inv, ! (valid), 1, 1, 1, 1, g_now, 600000)

        begin("inv_first_reading_removes_nothing");
        memset(&inv, 0, sizeof(inv)); g_now = 0;
        ck(! MAY_REMOVE(0), "the first invalid reading removed the send");
        end();

        begin("inv_held_may_remove");
        memset(&inv, 0, sizeof(inv)); g_now = 0;
        ck(! MAY_REMOVE(0), "first reading");
        g_now = 600000;
        ck(MAY_REMOVE(0), "an invalid verdict that held for the hold time may not be removed");
        end();

        begin("inv_valid_again_starts_over");
        memset(&inv, 0, sizeof(inv)); g_now = 0;
        ck(! MAY_REMOVE(0), "first reading");
        g_now = 300000;
        ck(! MAY_REMOVE(1), "it won the race");
        g_now = 600000;
        ck(! MAY_REMOVE(0), "invalid again is a new first sighting");
        end();

        begin("inv_never_mixes_with_refusal");
        memset(&inv, 0, sizeof(inv)); memset(&refusal, 0, sizeof(refusal)); g_now = 0;
        ck(! BRStrandedRefusalHoldObserve(&refusal, 1, 1, 1, 1, 1, g_now, 600000), "a peer-refusal sighting");
        g_now = 600000;
        ck(! MAY_REMOVE(0), "an invalid verdict borrowed the refusal's first sighting");
        end();
#undef MAY_REMOVE
    }

    // ================= Beyond the Kotlin tests =================

    // SyncService.isPlainDgbSend: raw copy null -> not plain; isAssetTransaction (default true on a
    // throw) must be false; digiDollarTxType (default -1 on a throw) must be 0.
    begin("plain_dgb_only_held_no_asset_no_dd");
    ck(BRStrandedSendIsPlainDgb(1, 0, 0) == 1, "a held send with no asset and no DigiDollar is not plain");
    ck(! BRStrandedSendIsPlainDgb(0, 0, 0), "a send the wallet does not hold is plain");
    ck(! BRStrandedSendIsPlainDgb(1, 1, 0), "an asset transfer is plain");
    ck(! BRStrandedSendIsPlainDgb(1, -1, 0), "an asset reading that failed is plain");
    for (i = 1; i <= 3; i++) ck(! BRStrandedSendIsPlainDgb(1, 0, i), "DigiDollar type %d is plain", i);
    ck(! BRStrandedSendIsPlainDgb(1, 0, -1), "a DigiDollar reading that failed is plain");
    ck(! BRStrandedSendIsPlainDgb(1, 1, 2), "an asset DigiDollar send is plain");
    end();

    // outcome == null (getPublishResult -1, PENDING) -> KEEP at the call site.
    begin("no_verdict_keeps");
    for (i = 0; i <= 1; i++) ck(BRStrandedRefusalActionFor(-1, 1, i) == KEEP, "no verdict yet removed a send");
    end();

    // RefusalHold's init refuses holdMs <= 0 ("acts on a refusal at its first sighting"); C cannot
    // throw, so such a hold never acts and keeps no sighting. A NULL hold answers 0.
    begin("hold_nonpositive_or_null_never_acts");
    {
        BRStrandedRefusalHold h = { 0, 0, 0 };
        int64_t bad[] = { 0, -1, INT64_MIN };
        size_t b;
        for (b = 0; b < 3; b++) {
            ck(! BRStrandedRefusalHoldObserve(&h, 1, 1, 1, 1, 1, 0, bad[b]), "hold %lld acted (first)", (long long)bad[b]);
            ck(! BRStrandedRefusalHoldObserve(&h, 1, 1, 1, 1, 1, 1, bad[b]), "hold %lld acted (second)", (long long)bad[b]);
            ck(! h.sighted, "hold %lld kept a sighting", (long long)bad[b]);
        }
        ck(! BRStrandedRefusalHoldObserve(NULL, 1, 1, 1, 1, 1, 0, 600000), "a NULL hold acted");
        BRStrandedRefusalHoldForget(NULL);
    }
    end();

    // Kotlin Long arithmetic wraps; so must this. A first sighting near the top of the range, read
    // again past the wrap, is ten minutes later.
    begin("clock_difference_wraps_as_kotlin_long");
    {
        BRStrandedRefusalHold h = { 0, 0, 0 };
        int64_t first = INT64_MAX - 100000;
        int64_t later = (int64_t)((uint64_t)first + 600000u);   // wraps negative
        ck(! BRStrandedRefusalHoldObserve(&h, 1, 1, 7, 1, 7, first, 600000), "first sighting acted");
        ck(BRStrandedRefusalHoldObserve(&h, 1, 1, 7, 1, 7, later, 600000), "600,000 ms across the wrap did not count");
    }
    end();

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
