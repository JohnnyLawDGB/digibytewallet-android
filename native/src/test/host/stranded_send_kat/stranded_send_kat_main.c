// Host KAT for BRStrandedSend.h -- the stranded-send sweep's decision.
//
// Carries Android's StrandedSendSelectorTest.kt case for case (same heights, attempt counts and
// times). The Kotlin tests feed the rule through its row parser; the parser stays on Android
// (the row text is the JNI's format), so each case here starts from what the parser hands the
// rule: listed or not, the listed height (0 = unreadable), and whether the listing is full.
//
// Built twice by run.sh:
//   -DSTRANDED_SEND_UNFIXED  the sweep before the rule; every RED case fails
//   (default)                the shipped rule; every case passes

#include "BRStrandedSend.h"
#include <stdio.h>

static int g_failed = 0;

static void result(const char *name, int pass, const char *detail)
{
    if (! pass) {
        printf("FAIL: %s%s%s\n", name, detail ? ": " : "", detail ? detail : "");
        g_failed = 1;
    }
    printf("RESULT %s %s\n", name, pass ? "pass" : "fail");
}

static const char *name(BRStrandedAction a)
{
    switch (a) {
    case BRStrandedRepublish: return "REPUBLISH";
    case BRStrandedHold: return "HOLD";
    case BRStrandedSettleConfirmed: return "SETTLE_CONFIRMED";
    case BRStrandedSettleAgedOut: return "SETTLE_AGED_OUT";
    case BRStrandedSettleGaveUp: return "SETTLE_GAVE_UP";
    }
    return "?";
}

#define NOW 1790000000000LL   // the Kotlin tests' clock

int main(void)
{
    char why[200];
    BRStrandedAction a, b, c;

    // The heights of the Kotlin tests' rows: a confirmed asset send (24,082,399), the sentinel.
    // "the exact confirmed asset-send row parses as confirmed at its height"
    result("heights_classify",
           BRStrandedHeightIsConfirmed(24082399) && BRStrandedHeightIsConfirmed(24082557) &&
           ! BRStrandedHeightIsConfirmed(TX_UNCONFIRMED) && ! BRStrandedHeightIsConfirmed(0) &&
           ! BRStrandedHeightIsConfirmed((uint32_t)TX_UNCONFIRMED + 1u), NULL);

    // "a send listed at a confirmed height is settled, not re-published"
    a = BRStrandedSendDecide(1, 24082399, BRStrandedListingIsFull(10, 9), 0, 0, NOW);
    snprintf(why, sizeof(why), "got %s", name(a));
    result("confirmed_listed_settles", a == BRStrandedSettleConfirmed && BRStrandedActionSettles(a), why);

    // "a send listed at the unconfirmed sentinel is re-published"
    a = BRStrandedSendDecide(1, TX_UNCONFIRMED, BRStrandedListingIsFull(10, 9), 0, 0, NOW);
    snprintf(why, sizeof(why), "got %s", name(a));
    result("sentinel_listed_republishes", a == BRStrandedRepublish && ! BRStrandedActionSettles(a), why);

    // "a send absent from a full window has aged out of it and is settled": zero re-publishes.
    a = BRStrandedSendDecide(0, 0, BRStrandedListingIsFull(BR_STRANDED_WINDOW, BR_STRANDED_WINDOW), 0, 0, NOW);
    snprintf(why, sizeof(why), "got %s", name(a));
    result("absent_from_full_window_aged_out", a == BRStrandedSettleAgedOut && BRStrandedActionSettles(a), why);

    // "a send absent from a window that is not full is tried a bounded number of times"
    {
        int ok = 1; uint32_t n;
        for (n = 0; n < BR_STRANDED_MAX_ATTEMPTS; n++)
            if (BRStrandedSendDecide(0, 0, BRStrandedListingIsFull(10, 10), n, NOW - 90000, NOW) != BRStrandedRepublish) ok = 0;
        a = BRStrandedSendDecide(0, 0, BRStrandedListingIsFull(10, 10), BR_STRANDED_MAX_ATTEMPTS, NOW - 90000, NOW);
        snprintf(why, sizeof(why), "first %u tries %s, then %s", BR_STRANDED_MAX_ATTEMPTS, ok ? "REPUBLISH" : "not all REPUBLISH", name(a));
        result("absent_tried_then_given_up", ok && a == BRStrandedSettleGaveUp && BRStrandedActionSettles(a), why);
    }

    // "an unconfirmed send is held after the attempt cap and tried again after the hold"
    a = BRStrandedSendDecide(1, TX_UNCONFIRMED, 0, BR_STRANDED_MAX_ATTEMPTS, NOW - 90000, NOW);
    b = BRStrandedSendDecide(1, TX_UNCONFIRMED, 0, BR_STRANDED_MAX_ATTEMPTS, NOW - BR_STRANDED_HOLD_MS, NOW);
    snprintf(why, sizeof(why), "capped %s, hold over %s", name(a), name(b));
    result("unconfirmed_held_then_retried", a == BRStrandedHold && ! BRStrandedActionSettles(a) && b == BRStrandedRepublish, why);

    // "a malformed row neither throws nor makes the other rows unconfirmed": a row whose height
    // cannot be read is listed at 0, tried, then held like any unconfirmed send; never given up.
    a = BRStrandedSendDecide(1, 0, 0, 0, 0, NOW);
    b = BRStrandedSendDecide(1, 0, 0, BR_STRANDED_MAX_ATTEMPTS, NOW, NOW);
    snprintf(why, sizeof(why), "first %s, capped %s", name(a), name(b));
    result("unreadable_height_tried_then_held", a == BRStrandedRepublish && b == BRStrandedHold, why);

    // "a send absent from a full window of unconfirmed rows is re-published, not aged out"
    a = BRStrandedSendDecide(0, 0, BRStrandedListingIsFull(BR_STRANDED_WINDOW, 0), 0, 0, NOW);
    b = BRStrandedSendDecide(0, 0, BRStrandedListingIsFull(BR_STRANDED_WINDOW, 0), BR_STRANDED_MAX_ATTEMPTS, NOW, NOW);
    snprintf(why, sizeof(why), "full=%d, first %s, capped %s", BRStrandedListingIsFull(BR_STRANDED_WINDOW, 0), name(a), name(b));
    result("all_unconfirmed_window_not_full",
           ! BRStrandedListingIsFull(BR_STRANDED_WINDOW, 0) && a == BRStrandedRepublish && b == BRStrandedSettleGaveUp, why);

    // "one confirmed row anchors a full window, and an absent send is aged out"
    a = BRStrandedSendDecide(0, 0, BRStrandedListingIsFull(BR_STRANDED_WINDOW, 1), 0, 0, NOW);
    snprintf(why, sizeof(why), "full=%d, got %s", BRStrandedListingIsFull(BR_STRANDED_WINDOW, 1), name(a));
    result("one_confirmed_row_anchors_full", BRStrandedListingIsFull(BR_STRANDED_WINDOW, 1) && a == BRStrandedSettleAgedOut, why);

    // Beyond the Kotlin tests: the window bound itself, and the clock edges.
    result("window_bound",
           ! BRStrandedListingIsFull(BR_STRANDED_WINDOW - 1, BR_STRANDED_WINDOW - 1) &&
           BRStrandedListingIsFull(BR_STRANDED_WINDOW + 5, 1), NULL);
    a = BRStrandedSendDecide(1, TX_UNCONFIRMED, 0, BR_STRANDED_MAX_ATTEMPTS, NOW - BR_STRANDED_HOLD_MS + 1, NOW);
    b = BRStrandedSendDecide(1, TX_UNCONFIRMED, 0, BR_STRANDED_MAX_ATTEMPTS, NOW + 5000, NOW);   // clock went back
    c = BRStrandedSendDecide(1, TX_UNCONFIRMED, 0, BR_STRANDED_MAX_ATTEMPTS - 1, NOW, NOW);     // under the cap
    snprintf(why, sizeof(why), "1 ms short %s, clock back %s, under cap %s", name(a), name(b), name(c));
    result("hold_edges", a == BRStrandedHold && b == BRStrandedHold && c == BRStrandedRepublish, why);

    // A held send the core marks invalid is conflicted (removed, not re-published); a held valid
    // one and one the wallet does not hold are not (BRWalletTransactionIsValid reads 1 for those).
    result("conflicted_only_when_held_and_invalid",
           BRStrandedSendIsConflicted(1, 0) && ! BRStrandedSendIsConflicted(1, 1) && ! BRStrandedSendIsConflicted(0, 1) &&
           ! BRStrandedSendIsConflicted(0, 0), NULL);

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
