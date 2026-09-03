// Host KAT: proves BRRecreateSequence.h's ORDER -- the sequencing a mid-session
// peer-manager recreate must follow.
//
// The defect: a recovery that just calls forceReconnect() + startSync() rebuilds
// the manager from the stale cold-start g_savedBlocks, flooring lastBlock to the
// birth checkpoint. Measured on a Note 8, a scan at 24,052,509 dropped to
// 22,650,000 and took ~6 hours to climb back.
//
// Unlike the other two KATs, this header is a SPECIFICATION rather than an
// executor -- the five steps are `suspend` lambdas in Kotlin and cannot be driven
// from a C callback without blocking a coroutine thread inside JNI. So what is
// asserted here is the order, the names, and the every-step-runs rule; each
// platform keeps its own executor and asks this header what the order is.
//
// Ported from core/sync/RecreateSequence.kt. Header-only under test.
#include <stdio.h>
#include <string.h>

#include "BRRecreateSequence.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

int main(void)
{
    int i;

    // test1 -- the order, position by position. This is the whole artifact.
    {
        static const int expected[BR_RECREATE_STEP_COUNT] = {
            BRRecreateStepFlushPersistedState,
            BRRecreateStepReloadBlocksNearTip,
            BRRecreateStepForceReconnect,
            BRRecreateStepStartSync,
            BRRecreateStepRestoreLedgerAndSnap,
        };
        int allMatch = 1;
        for (i = 0; i < BR_RECREATE_STEP_COUNT; i++) {
            if (BRRecreateStepAt(i) != expected[i]) {
                printf("      position %d: got %d (%s), expected %d (%s)\n",
                       i, BRRecreateStepAt(i),
                       BRRecreateStepName((BRRecreateStep)BRRecreateStepAt(i)),
                       expected[i], BRRecreateStepName((BRRecreateStep)expected[i]));
                allMatch = 0;
            }
        }
        check(allMatch, "test1: the five steps are in the fixed order");
    }

    // test2 -- THE defect, stated directly. The near-tip reload must happen
    // before the rebuild consumes it, and the ledger restore after the new
    // manager exists.
    check(BRRecreateMustPrecede(BRRecreateStepReloadBlocksNearTip, BRRecreateStepForceReconnect),
          "test2: reload happens BEFORE the rebuild that consumes it");
    check(BRRecreateMustPrecede(BRRecreateStepReloadBlocksNearTip, BRRecreateStepStartSync),
          "test2: reload happens BEFORE startSync");
    check(BRRecreateMustPrecede(BRRecreateStepStartSync, BRRecreateStepRestoreLedgerAndSnap),
          "test2: the ledger restore happens AFTER the new manager exists");

    // test3 -- the flush is first. Parts 1+2 without it only shrink the
    // give-back to one save interval, charged again on every recovery.
    {
        int flushFirst = 1;
        for (i = 0; i < BR_RECREATE_STEP_COUNT; i++) {
            if (BRRecreateStepAt(i) == BRRecreateStepFlushPersistedState) { flushFirst = (i == 0); break; }
        }
        check(flushFirst, "test3: the persisted-state flush runs first");
        check(BRRecreateMustPrecede(BRRecreateStepFlushPersistedState, BRRecreateStepReloadBlocksNearTip),
              "test3: flush precedes reload -- both restores read the PERSISTED snapshot");
    }

    // test4 -- every step runs even if an earlier one throws. Aborting halfway
    // leaves a manager marked for rebuild and never rebuilt.
    {
        int allContinue = 1;
        for (i = 0; i < BR_RECREATE_STEP_COUNT; i++) {
            if (!BRRecreateContinuesAfterFailure((BRRecreateStep)i)) allContinue = 0;
        }
        check(allContinue, "test4: a failure in any step does not abort the sequence");
    }

    // test5 -- no step is skippable. Skipping the flush must be written down by
    // a call site, never inherited from a default.
    {
        int noneSkippable = 1;
        for (i = 0; i < BR_RECREATE_STEP_COUNT; i++) {
            if (BRRecreateStepIsSkippable((BRRecreateStep)i)) noneSkippable = 0;
        }
        check(noneSkippable, "test5: no step is skippable");
    }

    // test6 -- the sequence is a permutation: every step appears exactly once.
    // Catches a duplicated or dropped entry, which an order check alone can miss.
    {
        int seen[BR_RECREATE_STEP_COUNT];
        int ok = 1;
        memset(seen, 0, sizeof(seen));
        for (i = 0; i < BR_RECREATE_STEP_COUNT; i++) {
            int s = BRRecreateStepAt(i);
            if (s < 0 || s >= BR_RECREATE_STEP_COUNT) { ok = 0; break; }
            seen[s]++;
        }
        for (i = 0; ok && i < BR_RECREATE_STEP_COUNT; i++) if (seen[i] != 1) ok = 0;
        check(ok, "test6: every step appears exactly once");
    }

    // test7 -- names are total over the real steps and NULL outside, so a label
    // can never be a dangling pointer.
    {
        int named = 1;
        for (i = 0; i < BR_RECREATE_STEP_COUNT; i++) {
            const char *n = BRRecreateStepName((BRRecreateStep)i);
            if (!n || !*n) named = 0;
        }
        check(named, "test7: every step has a non-empty name");
        check(BRRecreateStepName((BRRecreateStep)99) == 0, "test7: unknown step has no name");
        check(strcmp(BRRecreateStepName(BRRecreateStepFlushPersistedState), "flush") == 0 &&
              strcmp(BRRecreateStepName(BRRecreateStepReloadBlocksNearTip), "reload") == 0 &&
              strcmp(BRRecreateStepName(BRRecreateStepRestoreLedgerAndSnap), "restoreLedger") == 0,
              "test7: names match the labels RecreateSequence.kt writes");
    }

    // test8 -- bounds. An out-of-range index must be -1, not a wild read.
    check(BRRecreateStepAt(-1) == -1, "test8: index -1 is rejected");
    check(BRRecreateStepAt(BR_RECREATE_STEP_COUNT) == -1, "test8: index past the end is rejected");
    check(BRRecreateStepAt(9999) == -1, "test8: a large index is rejected");

    printf(g_fail ? "\n%d CHECK(S) FAILED\n" : "\nall checks passed\n", g_fail);
    return g_fail ? 1 : 0;
}
