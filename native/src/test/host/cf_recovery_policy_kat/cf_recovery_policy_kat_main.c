// Host KAT: proves BRCFRecoveryPolicy.h's decision table -- which artifacts a
// compact-filter recovery is allowed to destroy.
//
// The defect this table exists to prevent: the BIP158 watchdog used to delete
// the filter-header chain and the CF scan ledger together on every recovery.
// The chain is cheap (re-fetching it IS the recovery); the ledger is what lets
// a restart resume near tip. Dropping the ledger on a routine stall turned a
// recovery into ~6 hours of re-scanning 1.4M blocks on a Note 8.
//
// Ported from core/sync/CfRecoveryPolicy.kt + CfRecoveryPolicyTest.kt so both
// platforms share one table. The Kotlin tests are the source of truth for the
// five known reasons; test6 additionally covers the C-only default case, which
// has no Kotlin counterpart because `when` over an enum is exhaustive there.
//
// Header-only under test -- no core .c files, no linking beyond libc.
#include <stdio.h>

#include "BRCFRecoveryPolicy.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

int main(void)
{
    BRCFRecoveryDecision d;

    // test1 -- a stall is not evidence about the scan record. THE load-bearing
    // case: this is the routine path, and dropping the ledger here is the
    // ~6-hour regression.
    d = BRCFRecoveryDecide(BRCFRecoveryReasonFilterChainWedged);
    check(d.dropFilterChain == 1, "test1: wedged drops the filter chain");
    check(d.dropScanLedger == 0, "test1: wedged KEEPS the scan ledger");

    // test2 -- a re-anchor must not leave a persisted chain that a kill could
    // restore, but says nothing about what was scanned.
    d = BRCFRecoveryDecide(BRCFRecoveryReasonReanchored);
    check(d.dropFilterChain == 1, "test2: reanchored drops the filter chain");
    check(d.dropScanLedger == 0, "test2: reanchored KEEPS the scan ledger");

    // test3 -- still wedged after a re-anchor: the chain is not merely stale,
    // so the record derived alongside it is suspect too.
    d = BRCFRecoveryDecide(BRCFRecoveryReasonFilterChainCorrupt);
    check(d.dropFilterChain == 1, "test3: chain-corrupt drops the filter chain");
    check(d.dropScanLedger == 1, "test3: chain-corrupt drops the scan ledger");

    // test4 -- the ledger blob itself failed to decode. Only the ledger is
    // implicated; re-fetching the chain would be wasted work.
    d = BRCFRecoveryDecide(BRCFRecoveryReasonScanLedgerCorrupt);
    check(d.dropFilterChain == 0, "test4: ledger-corrupt KEEPS the filter chain");
    check(d.dropScanLedger == 1, "test4: ledger-corrupt drops the scan ledger");

    // test5 -- explicit reset destroys both by definition.
    d = BRCFRecoveryDecide(BRCFRecoveryReasonWalletReset);
    check(d.dropFilterChain == 1, "test5: reset drops the filter chain");
    check(d.dropScanLedger == 1, "test5: reset drops the scan ledger");

    // test6 -- C-ONLY. A C enum is an int, so an out-of-range value can arrive
    // from a future version, a corrupted read or a bad cast. Kotlin's exhaustive
    // `when` made this unreachable; here it must be defined, and it must not
    // fall back to the pre-fix drop-everything shape.
    d = BRCFRecoveryDecide((BRCFRecoveryReason)9999);
    check(d.dropScanLedger == 0, "test6: an unknown reason KEEPS the scan ledger");
    check(d.dropFilterChain == 1, "test6: an unknown reason still drops the chain");

    // test7 -- the invariant CfRecoveryPolicyTest.kt asserts as a set: exactly
    // three reasons may destroy the ledger. A new reason added without thinking
    // about the ledger fails here rather than in the field.
    {
        int destroyers = 0, i;
        for (i = 0; i <= (int)BRCFRecoveryReasonWalletReset; i++) {
            if (BRCFRecoveryMayDropScanLedger((BRCFRecoveryReason)i)) destroyers++;
        }
        check(destroyers == 3, "test7: exactly three reasons may drop the scan ledger");
        check(BRCFRecoveryMayDropScanLedger(BRCFRecoveryReasonScanLedgerCorrupt) &&
              BRCFRecoveryMayDropScanLedger(BRCFRecoveryReasonFilterChainCorrupt) &&
              BRCFRecoveryMayDropScanLedger(BRCFRecoveryReasonWalletReset),
              "test7: and they are exactly ledger-corrupt, chain-corrupt, reset");
    }

    // test8 -- the convenience accessor must not drift from the table.
    {
        int i, agree = 1;
        for (i = 0; i <= (int)BRCFRecoveryReasonWalletReset; i++) {
            if (BRCFRecoveryMayDropScanLedger((BRCFRecoveryReason)i) !=
                BRCFRecoveryDecide((BRCFRecoveryReason)i).dropScanLedger) agree = 0;
        }
        check(agree, "test8: MayDropScanLedger agrees with Decide for every reason");
    }

    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nall checks passed\n", g_fail);
    return g_fail ? 1 : 0;
}
