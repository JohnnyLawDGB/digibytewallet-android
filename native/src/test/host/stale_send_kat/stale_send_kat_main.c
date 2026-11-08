// Host KAT for BRStaleSend.h -- the stale-send clear's decision (4.1.3, review R8): whether a valid,
// unconfirmed own send that never relayed is cleared. Carries the predicate cases of StaleSendTest
// (the Kotlin StaleSendPredicate that BRStaleSendOldEnough / BRStaleSendIsStale replaced) and the
// rule of DgbNodeClient.sendNeverRelayed (which now drives BRStaleSendNextStep), both through
// jni_stale_send.c.
//
// The node is a script: drive() runs the same loop DgbNodeClient.sendNeverRelayed runs (at most
// one txid question and one per input, then fail closed), records each question, and returns the
// answer. The cases use Kotlin's literals (MIN_AGE_S = 24 * 60 * 60), not the header's macro, so a
// changed macro fails here.
//
// Built three times by run.sh:
//   -DSTALE_SEND_AGE_RED    no timestamp guard, and exactly one day is not yet old enough; the
//                           age cases fail, the relay cases hold
//   -DSTALE_SEND_RELAY_RED  any node is asked and an input with no answer reads as unspent; those
//                           relay cases fail, the age cases hold
//   (default)               the shipped rule; every case passes

#include "BRStaleSend.h"
#include "BRStaleSend.h"   // the include guard holds
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

static const int64_t NOW = 1800000000LL;   // StaleSendTest's now
static const int64_t DAY = 24LL * 60 * 60; // StaleSendPredicate.MIN_AGE_S

// One scripted node: its answer for the txid and for each input. Returns 1 for "never relayed",
// 0 otherwise; `asked` gets the questions in order ("T" for the txid, the input index otherwise).
static int drive(int pinned, size_t n, int txAnswer, const int *inputAnswers, char *asked, size_t askedSize)
{
    int status = BRStaleSendTxNotAsked, last = BRStaleSendInputNoAnswer;
    size_t done = 0, i;

    asked[0] = '\0';
    for (i = 0; i < n + 2; i++) {
        char q[24];
        switch (BRStaleSendNextStep(pinned, n, status, done, last)) {
        case BRStaleSendAskTx:
            status = txAnswer;
            snprintf(q, sizeof(q), "%sT", asked[0] ? "," : "");
            strncat(asked, q, askedSize - strlen(asked) - 1);
            break;
        case BRStaleSendAskInput:
            if (done >= n) return 0;
            last = inputAnswers[done];
            snprintf(q, sizeof(q), "%s%zu", asked[0] ? "," : "", done);
            strncat(asked, q, askedSize - strlen(asked) - 1);
            done++;
            break;
        case BRStaleSendNeverRelayed:
            return 1;
        default:
            return 0;
        }
    }
    return 0;
}

int main(void)
{
    char why[200], asked[64];
    int ok, r;
    const int U = BRStaleSendInputUnspent, S = BRStaleSendInputSpent, X = BRStaleSendInputNoAnswer;

    // ---- the age (StaleSendTest) ----

    // aDayOldSendTheNetworkNeverTookIsStale, first half: exactly one day old is old enough.
    ok = BRStaleSendIsStale(NOW - DAY, NOW, 1) == 1 && BRStaleSendOldEnough(NOW - DAY, NOW) == 1 &&
         BRStaleSendIsStale(NOW - 10 * DAY, NOW, 1) == 1;
    snprintf(why, sizeof(why), "one day old, never relayed -> %d", BRStaleSendIsStale(NOW - DAY, NOW, 1));
    result("a_day_old_send_never_relayed_is_stale", ok, why);

    // aDayOldSendTheNetworkNeverTookIsStale, second half: one the network took is never stale.
    ok = BRStaleSendIsStale(NOW - 10 * DAY, NOW, 0) == 0 && BRStaleSendIsStale(NOW - DAY, NOW, 0) == 0;
    result("a_send_the_network_took_is_never_stale", ok, NULL);

    // aYoungSendOrOneWithNoTimestampIsNeverStale, first case: a second short of a day.
    ok = BRStaleSendIsStale(NOW - DAY + 1, NOW, 1) == 0 && BRStaleSendOldEnough(NOW - DAY + 1, NOW) == 0 &&
         BRStaleSendOldEnough(NOW, NOW) == 0;
    result("a_young_send_is_never_stale", ok, NULL);

    // aYoungSendOrOneWithNoTimestampIsNeverStale, the rest: timestamp 0 (none) is never old enough,
    // however late the clock; Kotlin's `timestampS > 0L` makes a negative one the same.
    ok = BRStaleSendIsStale(0, NOW, 1) == 0 && BRStaleSendOldEnough(0, NOW) == 0 &&
         BRStaleSendOldEnough(-1, NOW) == 0 && BRStaleSendOldEnough(INT64_MIN, NOW) == 0;
    snprintf(why, sizeof(why), "oldEnough(0, now) -> %d", BRStaleSendOldEnough(0, NOW));
    result("a_send_with_no_timestamp_is_never_stale", ok, why);

    // A clock that went back gives a negative age: not old enough. The difference wraps as
    // Kotlin's Long does (Long.MIN_VALUE - 1 is Long.MAX_VALUE, which is old enough).
    ok = BRStaleSendOldEnough(NOW + DAY, NOW) == 0 && BRStaleSendOldEnough(1, INT64_MIN) == 1 &&
         BRStaleSendOldEnough(INT64_MAX, INT64_MIN) == 0;
    result("age_wraps_as_kotlin_long", ok, NULL);

    ok = BR_STALE_SEND_MIN_AGE_S == DAY;
    result("min_age_is_one_day", ok, NULL);

    // ---- the node's evidence (DgbNodeClient.sendNeverRelayed) ----
    {
        const int all[3] = { U, U, U };

        // `endpoint() != DEFAULT_BASE_URL` -> false, before any request.
        r = drive(0, 3, BRStaleSendTxUnknown, all, asked, sizeof(asked));
        snprintf(why, sizeof(why), "custom node -> %d, asked [%s]", r, asked);
        result("a_custom_node_is_never_asked", r == 0 && asked[0] == '\0', why);

        // `inputs.isEmpty()` -> false, before any request (pinned or not).
        r = drive(1, 0, BRStaleSendTxUnknown, all, asked, sizeof(asked));
        ok = r == 0 && asked[0] == '\0';
        r = drive(0, 0, BRStaleSendTxUnknown, all, asked, sizeof(asked));
        ok = ok && r == 0 && asked[0] == '\0';
        result("a_send_with_no_inputs_is_never_asked", ok, NULL);

        // The txid is asked first, then every input in order; all unspent -> never relayed.
        r = drive(1, 3, BRStaleSendTxUnknown, all, asked, sizeof(asked));
        snprintf(why, sizeof(why), "-> %d, asked [%s]", r, asked);
        result("unknown_txid_and_every_input_unspent_is_never_relayed", r == 1 && strcmp(asked, "T,0,1,2") == 0, why);

        // Only the node's own "not found" goes on to the inputs; nothing else, and no undefined code.
        {
            const int stops[] = { BRStaleSendTxConfirmed, BRStaleSendTxInMempool, BRStaleSendTxUnavailable, 5, -1, 99 };
            size_t k;
            ok = 1;
            for (k = 0; k < sizeof(stops) / sizeof(stops[0]); k++) {
                r = drive(1, 3, stops[k], all, asked, sizeof(asked));
                if (r != 0 || strcmp(asked, "T") != 0) {
                    ok = 0;
                    snprintf(why, sizeof(why), "status %d -> %d, asked [%s]", stops[k], r, asked);
                }
            }
            result("only_the_nodes_own_not_found_goes_on", ok, ok ? NULL : why);
        }

        // An explicit "spent" stops it, and no later input is asked.
        {
            const int spent[3] = { U, S, U };
            r = drive(1, 3, BRStaleSendTxUnknown, spent, asked, sizeof(asked));
            snprintf(why, sizeof(why), "-> %d, asked [%s]", r, asked);
            result("a_spent_input_stops_it", r == 0 && strcmp(asked, "T,0,1") == 0, why);
        }

        // `outputUnspent(t, v) != true` -> false: no answer is not "unspent", nor is an undefined code.
        {
            const int none[3] = { U, X, U }, odd[2] = { 7, U }, firstNone[1] = { X };
            ok = 1;
            r = drive(1, 3, BRStaleSendTxUnknown, none, asked, sizeof(asked));
            ok = ok && r == 0 && strcmp(asked, "T,0,1") == 0;
            snprintf(why, sizeof(why), "no answer -> %d, asked [%s]", r, asked);
            r = drive(1, 2, BRStaleSendTxUnknown, odd, asked, sizeof(asked));
            ok = ok && r == 0 && strcmp(asked, "T,0") == 0;
            r = drive(1, 1, BRStaleSendTxUnknown, firstNone, asked, sizeof(asked));
            ok = ok && r == 0;
            result("only_an_explicit_unspent_answer_counts", ok, why);
        }

        // A caller that reports more answers than inputs gets no verdict; inputsAsked == n with the
        // last unspent is the verdict.
        ok = BRStaleSendNextStep(1, 2, BRStaleSendTxUnknown, 3, U) == BRStaleSendNotShown &&
             BRStaleSendNextStep(1, 2, BRStaleSendTxUnknown, 2, U) == BRStaleSendNeverRelayed &&
             BRStaleSendNextStep(1, 2, BRStaleSendTxUnknown, 0, X) == BRStaleSendAskInput &&
             BRStaleSendNextStep(1, 2, BRStaleSendTxNotAsked, 0, X) == BRStaleSendAskTx;
        result("more_answers_than_inputs_is_no_verdict", ok, NULL);
    }

    // The platforms pass these as ints (Android: DgbNodeClient's STALE_* constants, which
    // StaleSendCoreRuleGateTest reads against this header).
    ok = BRStaleSendTxNotAsked == 0 && BRStaleSendTxConfirmed == 1 && BRStaleSendTxInMempool == 2 &&
         BRStaleSendTxUnknown == 3 && BRStaleSendTxUnavailable == 4 &&
         BRStaleSendInputNoAnswer == 0 && BRStaleSendInputUnspent == 1 && BRStaleSendInputSpent == 2 &&
         BRStaleSendNotShown == 0 && BRStaleSendNeverRelayed == 1 && BRStaleSendAskTx == 2 && BRStaleSendAskInput == 3;
    result("codes_are_fixed", ok, NULL);

    if (! g_failed) printf("ALL PASS\n");
    return g_failed;
}
