// cf_prune_amortize_kat — _BRPeerManagerClearMemory must not do O(resident) work
// per block-add once the retention span clamp binds.
//
// THE BUG THIS GATES (measured on a Note 8, 2026-08-02, deep restore from 22,650,000).
// When CF_RETENTION_SPAN_MAX binds, cfFloor becomes `tip - SPAN` and therefore rises by
// ONE on every block-add. That defeats the existing O(1) no-op short-circuit twice over:
//
//   1. the memo is keyed `cfFloor <= clearMemNoopFloor`, which a strictly-rising floor
//      never satisfies again; and
//   2. freeing even the single block that just dropped below the floor executes
//      `clearMemNoopFloor = 0`, erasing the memo anyway.
//
// So every block-add walked the whole ~150,000-block resident set to free ONE block:
// ~149,198 BRSetGet/header, 77 ms/header, one core pegged. Because the descent runs under
// manager->lock it starved BRPeerManagerKeepAlive ("keepalive stale: no tick in 80s" x4),
// so the residual re-request driver never ran, outstanding heights were never retried, the
// scan frontier never advanced, and the clamp kept binding. Self-sustaining: the device
// abandoned 18,549 heights and header throughput collapsed 108,696/s -> 13/s (8,361x).
//
// WHAT IS ASSERTED: WORK, not wall clock. CF_PRUNE_INSTRUMENT counts full descents and the
// BRSetGet inside them, so the gate is deterministic and immune to host speed.
//
// NO CONSTANT SCALING. This runs at the REAL CF_RETENTION_SPAN_MAX (150000) and the REAL
// CLEAR_MEM_PRUNE_STRIDE (2048), building a genuine ~152,000-block resident set.
//
// An earlier version tried to shrink both with -D and they were SILENTLY IGNORED: the header
// declares them with a plain #define, so its definition wins over the command line, and -w hid
// the redefinition warning. The run looked scaled-down and was not. Anything that must be
// tunable here has to be #ifndef-guarded in the header or given a harness-only name -- do not
// assume a -D reached the code. (Only KAT_ADDS, defined in THIS file, varies between arms.)
//
// The expectation is computed from the compiled CLEAR_MEM_PRUNE_STRIDE, never a literal, so
// it cannot drift if that constant is retuned.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#ifndef CF_PRUNE_INSTRUMENT
#error "cf_prune_amortize_kat requires -DCF_PRUNE_INSTRUMENT"
#endif

// Iteration count; see the comment at its use. NOT a scaling of any production constant.
#ifndef KAT_ADDS
#define KAT_ADDS (CLEAR_MEM_PRUNE_STRIDE * 4u)
#endif

// The manager struct and _BRPeerManagerClearMemory are file-static; include the .c
// directly (same technique as cf_scan_ledger_drive_kat). BRPeerManager.c must therefore
// NOT also be passed as a separate compilation unit in run.sh.
#include "BRPeerManager.c"

static int g_fail = 0;
static void check(int cond, const char *what) {
    printf("  [%s] %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) g_fail = 1;
}

static UInt256 uniqueHash(uint32_t height) {
    UInt256 h = UINT256_ZERO;
    h.u32[0] = height;
    h.u32[7] = 0xC0FFEEu ^ height;
    return h;
}

static BRMerkleBlock *chainBlock(uint32_t height) {
    BRMerkleBlock *b = BRMerkleBlockNew();
    b->blockHash = uniqueHash(height);
    b->prevBlock = uniqueHash(height - 1);
    b->height    = height;
    b->timestamp = 1700000000u + height;
    return b;
}

// A manager whose resident set spans [base..tip], with the compact-filter chain anchored
// LOW so the scan-anchored floor sits far below the span floor and the CLAMP is what binds
// — which is the regime under test.
static BRPeerManager *buildClampedManager(BRWallet *wallet, uint32_t base, uint32_t count,
                                          uint32_t *outTip)
{
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    if (! m) return NULL;
    m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;

    uint32_t tip = base + count - 1;
    BRMerkleBlock *last = NULL;
    for (uint32_t h = base; h <= tip; h++) {
        BRMerkleBlock *b = chainBlock(h);
        BRSetAdd(m->blocks, b);
        last = b;
    }
    m->lastBlock = last;

    // Anchor the CF chain at the BASE, so cfNext ~ base and the scan-anchored floor is
    // ~base-144 — below the span floor (tip - SPAN) once the chain is longer than SPAN.
    // That is what makes the clamp, not the scan anchor, the binding constraint.
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, base, UINT256_ZERO);

    if (outTip) *outTip = tip;
    return m;
}

// Append one block at tip+1 and run the pruner, exactly as the block-add path does.
static void addBlockAndPrune(BRPeerManager *m, uint32_t height) {
    BRMerkleBlock *b = chainBlock(height);
    BRSetAdd(m->blocks, b);
    m->lastBlock = b;
    _BRPeerManagerClearMemory(m);
}

int main(void) {
    // Line-buffered: the RED arm runs for minutes under ASan, and block-buffered stdout
    // makes a live run indistinguishable from a hang.
    setvbuf(stdout, NULL, _IOLBF, 0);
    printf("cf_prune_amortize_kat\n");
    printf("  CF_RETENTION_SPAN_MAX  = %u (production)\n", (unsigned)CF_RETENTION_SPAN_MAX);
    printf("  CLEAR_MEM_PRUNE_STRIDE = %u (production)\n", (unsigned)CLEAR_MEM_PRUNE_STRIDE);
    printf("  KAT_ADDS               = %u (this arm)\n",    (unsigned)KAT_ADDS);

    BRMasterPubKey mpk;
    memset(&mpk, 0, sizeof(mpk));
    mpk.fingerPrint = 0x11223344;
    BRWallet *w = BRWalletNew(NULL, 0, mpk);
    check(w != NULL, "setup: wallet created");
    if (! w) return 1;

    // Resident set must exceed CLEAR_MEM_BLOCKS_COUNT_TRIGGER (5000) or the descent body
    // never runs at all, and must exceed CF_RETENTION_SPAN_MAX so the clamp's floor lands
    // INSIDE the set (that is the whole pathology — a floor below the set frees nothing and
    // the existing no-op memo already handles it).
    const uint32_t BASE  = 500000u;
    const uint32_t COUNT = CF_RETENTION_SPAN_MAX + 2000u;
    uint32_t tip = 0;

    BRPeerManager *m = buildClampedManager(w, BASE, COUNT, &tip);
    check(m != NULL, "setup: manager built");
    if (! m) { BRWalletFree(w); return 1; }

    check(BRSetCount(m->blocks) >= CLEAR_MEM_BLOCKS_COUNT_TRIGGER,
          "setup: resident set >= CLEAR_MEM_BLOCKS_COUNT_TRIGGER (descent body runs)");
    check(tip > CF_RETENTION_SPAN_MAX, "setup: tip above the span cap (clamp can bind)");
    check(tip - CF_RETENTION_SPAN_MAX > BASE,
          "setup: clamped floor lands INSIDE the resident set (one block/add becomes prunable)");

    // Prime: one call so any first-time bookkeeping settles, then zero the counters so the
    // measurement covers only the steady clamped regime.
    _BRPeerManagerClearMemory(m);
    m->pruneDescents = 0;
    m->pruneSetGets  = 0;

    // Block-adds to perform. Runs at the REAL production constants (see the header banner);
    // only the ITERATION COUNT differs between arms, via -DKAT_ADDS:
    //   GREEN (default STRIDE*4 = 8192): must cross several stride windows or "amortised"
    //     would be indistinguishable from "never ran". ~3 min under ASan at the real span.
    //   RED   (-DKAT_ADDS=64): the unfixed code walks the WHOLE 150,000-block resident set
    //     per add, so 8192 adds is ~1.2e9 lookups -- hours. 64 adds still produces 64
    //     descents against an allowance of 2, which fails decisively in seconds.
    const uint32_t ADDS = KAT_ADDS;
    for (uint32_t i = 1; i <= ADDS; i++) addBlockAndPrune(m, tip + i);

    printf("  after %u block-adds: descents=%llu setGets=%llu\n",
           (unsigned)ADDS,
           (unsigned long long)m->pruneDescents,
           (unsigned long long)m->pruneSetGets);

    // The clamp must actually have been the binding constraint for this to mean anything.
    check(m->pruneDescents > 0, "sanity: at least one descent ran (regime is live, not skipped)");

    // THE GATE. Derived from the compiled stride, never a literal: +2 tolerance covers the
    // partial window at each end.
    const uint64_t allowed = (uint64_t)(ADDS / CLEAR_MEM_PRUNE_STRIDE) + 2u;
    char msg[192];
    snprintf(msg, sizeof(msg),
             "AMORTISED: descents (%llu) <= adds/STRIDE + 2 (%llu) — walk is not per-block",
             (unsigned long long)m->pruneDescents, (unsigned long long)allowed);
    check(m->pruneDescents <= allowed, msg);

    // A descent that never frees would also satisfy the count bound while leaking memory,
    // so bound the OTHER direction too: the resident set must still be pruned back toward
    // the floor, i.e. it may exceed the retained span by at most one stride window.
    size_t resident = BRSetCount(m->blocks);
    size_t maxResident = (size_t)CF_RETENTION_SPAN_MAX + CLEAR_MEM_PRUNE_STRIDE
                       + CLEAR_MEM_BLOCKS_COUNT_TAIL_LEN + SAVE_BLOCK_COUNT
                       + CLEAR_MEM_BLOCKS_RESERVE_COUNT;
    snprintf(msg, sizeof(msg),
             "resident (%zu) <= span + stride + tail headroom (%zu) — deferral is BOUNDED",
             resident, maxResident);
    check(resident <= maxResident, msg);

    BRPeerManagerFree(m);
    BRWalletFree(w);

    printf(g_fail ? "cf_prune_amortize_kat: FAIL\n" : "cf_prune_amortize_kat: ALL PASS\n");
    return g_fail;
}
