// Host KAT: proves BRPublishOutcome.h's errno -> action mapping, and that it is
// correct on the platform it is compiled for rather than only on Linux.
//
// The defect this header exists to fix: core/sync/PublishOutcome.kt hardcoded
// ENOTCONN = 107 and ETIMEDOUT = 110, which are Linux values. Darwin is 57 and
// 60. The same core compiled for iOS would deliver 60 for a timeout, match no
// case, and fall through to the default -- making UnconfirmedDelivery
// unreachable on iOS and hiding the one failure mode the policy exists to
// surface (a transaction that went out and was echoed by nobody). It fails safe,
// which is exactly why it would have shipped unnoticed.
//
// test6 is the regression gate for that specific class of bug: it asserts the
// mapping in terms of the PLATFORM's errno symbols, so a build on any OS proves
// itself. A literal-valued table cannot pass it on both Linux and Darwin.
//
// Ported from core/sync/PublishOutcome.kt + its test. Header-only under test --
// no core .c files, no linking beyond libc.
#include <stdio.h>
#include <errno.h>

#include "BRPublishOutcome.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

int main(void)
{
    BRPublishOutcome o;

    printf("platform errno: EINVAL=%d ENOTCONN=%d ETIMEDOUT=%d\n\n",
           EINVAL, ENOTCONN, ETIMEDOUT);

    // test1 -- acceptance. A peer relayed it back; the network has it.
    o = BRPublishOutcomeOf(0);
    check(o.kind == BRPublishKindAccepted, "test1: 0 is Accepted");
    check(o.shouldRetry == 0, "test1: accepted does not retry");
    check(o.isTerminal == 0, "test1: accepted is not terminal");
    check(BRPublishOutcomeUserVisiblySent(0) == 1, "test1: accepted may be shown as sent");

    // test2 -- the ONLY terminal case. The core itself says it is malformed, so
    // it can never be accepted and the wallet may release its inputs.
    o = BRPublishOutcomeOf(EINVAL);
    check(o.kind == BRPublishKindRejected, "test2: EINVAL is Rejected");
    check(o.shouldRetry == 0, "test2: rejected does not retry");
    check(o.isTerminal == 1, "test2: rejected IS terminal");

    // test3 -- the interesting one. Silence is the only evidence of refusal, but
    // silence is also what a slow relay looks like, so it must stay retryable.
    o = BRPublishOutcomeOf(ETIMEDOUT);
    check(o.kind == BRPublishKindUnconfirmedDelivery, "test3: ETIMEDOUT is UnconfirmedDelivery");
    check(o.shouldRetry == 1, "test3: unconfirmed delivery retries");
    check(o.isTerminal == 0, "test3: unconfirmed delivery is NOT terminal");
    check(BRPublishOutcomeUserVisiblySent(ETIMEDOUT) == 0,
          "test3: unconfirmed delivery must NOT be shown as sent");

    // test4 -- never reached the wire.
    o = BRPublishOutcomeOf(ENOTCONN);
    check(o.kind == BRPublishKindNotDelivered, "test4: ENOTCONN is NotDelivered");
    check(o.shouldRetry == 1, "test4: not-delivered retries");
    check(o.isTerminal == 0, "test4: not-delivered is not terminal");

    // test5 -- the fail-safe. Wrongly retrying costs radio; wrongly destroying a
    // send loses a transaction that was still propagating. NOTHING unrecognised
    // may be terminal.
    {
        int codes[] = { 1, 5, 42, 57, 60, 107, 110, 999, -1, 12345 };
        int i, allSafe = 1, anyTerminal = 0;
        for (i = 0; i < (int)(sizeof(codes)/sizeof(codes[0])); i++) {
            if (codes[i] == 0 || codes[i] == EINVAL) continue;   // covered above
            o = BRPublishOutcomeOf(codes[i]);
            if (!o.shouldRetry) allSafe = 0;
            if (o.isTerminal) anyTerminal = 1;
        }
        check(allSafe, "test5: every non-EINVAL code stays retryable");
        check(!anyTerminal, "test5: no unrecognised code is ever terminal");
    }

    // test6 -- THE REGRESSION GATE for the Linux-literals bug.
    //
    // Asserted against the platform's own symbols, so this file proves itself on
    // whatever OS it is compiled for. On Linux ETIMEDOUT is 110 and on Darwin it
    // is 60; both must land on UnconfirmedDelivery. A table written with literal
    // numbers can satisfy this on one platform only.
    check(BRPublishOutcomeOf(ETIMEDOUT).kind == BRPublishKindUnconfirmedDelivery,
          "test6: ETIMEDOUT maps by SYMBOL, not by a hardcoded number");
    check(BRPublishOutcomeOf(ENOTCONN).kind == BRPublishKindNotDelivered,
          "test6: ENOTCONN maps by SYMBOL, not by a hardcoded number");

    // And the specific historical failure: on Darwin, feeding the Linux literal
    // 110 must NOT be treated as a timeout. If it were, someone had hardcoded.
    if (ETIMEDOUT != 110) {
        check(BRPublishOutcomeOf(110).kind != BRPublishKindUnconfirmedDelivery,
              "test6: the Linux literal 110 is not special-cased on this platform");
    } else {
        printf("SKIP: test6 Darwin-only check (this platform's ETIMEDOUT is 110)\n");
    }

    // test7 -- the accessor the Kotlin mirror asserts against must agree with
    // the platform, and must be total.
    check(BRPublishErrnoValue(BR_PUBLISH_ERRNO_EINVAL_IDX) == EINVAL,
          "test7: errno accessor reports EINVAL");
    check(BRPublishErrnoValue(BR_PUBLISH_ERRNO_ENOTCONN_IDX) == ENOTCONN,
          "test7: errno accessor reports ENOTCONN");
    check(BRPublishErrnoValue(BR_PUBLISH_ERRNO_ETIMEDOUT_IDX) == ETIMEDOUT,
          "test7: errno accessor reports ETIMEDOUT");
    check(BRPublishErrnoValue(99) == 0, "test7: out-of-range index returns 0");

    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nall checks passed\n", g_fail);
    return g_fail ? 1 : 0;
}
