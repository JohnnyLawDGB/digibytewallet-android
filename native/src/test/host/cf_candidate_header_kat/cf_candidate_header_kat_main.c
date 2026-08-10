//
//  cf_candidate_header_kat_main.c
//
//  Host KAT for Task 2 of the cfcheckpt-active-rejection plan: proves
//  BRCompactFilterChainBatchViolatesCheckpoint folds a pending cfheaders
//  batch forward from the chain's current tip WITHOUT mutating the chain,
//  and correctly distinguishes an honest continuation from one that
//  diverges from a pinned mainnet checkpoint.
//
//  This file #include-s BRCompactFilterChain.c directly (same "peek at
//  file-statics" idiom cf_confirm_kat/run.sh uses for BRPeerManager.c's
//  file-static _peerRelayedBlockTxns -- see that run.sh's header comment)
//  so this KAT can reach the file-static _batchViolatesCheckpoints helper
//  and _foldHeader. _batchViolatesCheckpoints takes an EXPLICIT checkpoint
//  list rather than always consulting the real BRMainNetCFCheckpoints
//  table; BRCompactFilterChainBatchViolatesCheckpoint (the public entry
//  point) is a thin wrapper that looks up the real table via
//  BRCFCheckpointsInRange and delegates to it. The explicit-list helper
//  exists precisely so a host test can exercise the match/no-violation
//  branch with a SELF-CONSISTENT synthetic checkpoint: matching one of the
//  478 real pinned mainnet values from constructed data is a SHA256d
//  preimage problem (you'd need to find inputs whose double-SHA256 equals
//  an already-fixed 256-bit target -- computationally infeasible without
//  pulling the real filter-hash sequence from a live node's
//  `getblockfilter`, which this host environment doesn't have). See
//  task-2-report.md for the full rationale plus a discussion of why this
//  does not weaken the test: both call sites (the public entry point in
//  test_no_checkpoint_in_range/test_real_checkpoint_mismatch_detected
//  below, against the REAL table; and _batchViolatesCheckpoints directly
//  in test_self_consistent_match_and_flip, against a synthetic one)
//  exercise the *identical* fold+compare logic -- the only thing that
//  differs is which checkpoint list feeds it, and BRCFCheckpointsInRange's
//  own correctness (real table, real lookups) was already proven
//  exhaustively by Task 1's cf_checkpoint_lookup_kat.
//
//  Not compiled into the Android NDK build (host-only KAT, matches every
//  other native/src/test/host/*/run.sh convention in this repo).
//

#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "BRCompactFilterChain.c"
#include "BRCompactFilterCheckpoints.h"
#include "BRInt.h"

static UInt256 u256_fill(uint8_t b)
{
    UInt256 h;
    memset(h.u8, b, sizeof(h.u8));
    return h;
}

// ---- Test 1: no checkpoint falls inside the batch's range -----------------
// [60000, 60009] sits strictly between checkpoints (spacing = 50000, first
// entry at 50000) -- BRCFCheckpointsInRange (real table) must return empty,
// and the validator must return 0 without touching the chain.
static void test_no_checkpoint_in_range(void)
{
    UInt256 anchor = u256_fill(0xa1);
    BRCompactFilterChain *c = BRCompactFilterChainNew(0, 60000, anchor);

    UInt256 batch[10];
    for (int i = 0; i < 10; i++) batch[i] = u256_fill((uint8_t)(0x10 + i));

    uint32_t nextBefore = BRCompactFilterChainNextHeight(c);
    size_t countBefore = BRCompactFilterChainCount(c);
    UInt256 tipBefore = BRCompactFilterChainTipHeader(c);

    uint32_t vh = 0xffffffff;
    UInt256 vc = UINT256_ZERO;
    int r = BRCompactFilterChainBatchViolatesCheckpoint(c, batch, 10, &vh, &vc);
    assert(r == 0);

    // No mutation.
    assert(BRCompactFilterChainNextHeight(c) == nextBefore);
    assert(BRCompactFilterChainCount(c) == countBefore);
    assert(UInt256Eq(BRCompactFilterChainTipHeader(c), tipBefore));

    BRCompactFilterChainFree(c);
    printf("test_no_checkpoint_in_range: PASS\n");
}

// ---- Test 2: real checkpoint in range, synthetic batch necessarily
//      diverges from the real pin -- the security-critical rejection path
//      this whole plan exists for. Uses the REAL, unmodified
//      BRCFCheckpointsInRange / BRMainNetCFCheckpoints end-to-end via the
//      public entry point. -------------------------------------------------
static void test_real_checkpoint_mismatch_detected(void)
{
    UInt256 anchor = u256_fill(0xa2);
    BRCompactFilterChain *c = BRCompactFilterChainNew(0, 49995, anchor);

    UInt256 batch[10];
    for (int i = 0; i < 10; i++) batch[i] = u256_fill((uint8_t)(0x30 + i));
    // Batch covers heights [49995, 50004]; BRMainNetCFCheckpoints[0].height == 50000.

    uint32_t nextBefore = BRCompactFilterChainNextHeight(c);
    size_t countBefore = BRCompactFilterChainCount(c);
    UInt256 tipBefore = BRCompactFilterChainTipHeader(c);

    uint32_t vh = 0;
    UInt256 vc = UINT256_ZERO;
    int r = BRCompactFilterChainBatchViolatesCheckpoint(c, batch, 10, &vh, &vc);
    assert(r == 1);
    assert(vh == 50000);
    // A synthetic dSHA256 chain colliding with the real pinned value at
    // 50000 is a preimage break; assert it did NOT happen.
    assert(! UInt256Eq(vc, BRMainNetCFCheckpoints[0].filterHeader));

    // No mutation on the violating call.
    assert(BRCompactFilterChainNextHeight(c) == nextBefore);
    assert(BRCompactFilterChainCount(c) == countBefore);
    assert(UInt256Eq(BRCompactFilterChainTipHeader(c), tipBefore));

    // A second, independently-corrupted batch is caught too, and the chain
    // is still untouched -- the validator never commits on any outcome.
    UInt256 bad[10];
    memcpy(bad, batch, sizeof(bad));
    bad[7].u8[0] ^= 0xff;

    uint32_t vh2 = 0;
    UInt256 vc2 = UINT256_ZERO;
    int r2 = BRCompactFilterChainBatchViolatesCheckpoint(c, bad, 10, &vh2, &vc2);
    assert(r2 == 1);
    assert(vh2 == 50000);

    assert(BRCompactFilterChainNextHeight(c) == nextBefore);
    assert(BRCompactFilterChainCount(c) == countBefore);
    assert(UInt256Eq(BRCompactFilterChainTipHeader(c), tipBefore));

    BRCompactFilterChainFree(c);
    printf("test_real_checkpoint_mismatch_detected: PASS\n");
}

// ---- Test 3: empty batch is never a violation, and does not underflow the
//      NextHeight + count - 1 range computation when NextHeight == 0. -----
static void test_empty_batch_is_no_violation(void)
{
    UInt256 anchor = u256_fill(0xa3);
    BRCompactFilterChain *c = BRCompactFilterChainNew(0, 0, anchor); // start=0: the underflow-prone edge

    uint32_t vh = 0;
    UInt256 vc = UINT256_ZERO;
    int r = BRCompactFilterChainBatchViolatesCheckpoint(c, NULL, 0, &vh, &vc);
    assert(r == 0);
    assert(BRCompactFilterChainNextHeight(c) == 0);
    assert(BRCompactFilterChainCount(c) == 0);

    BRCompactFilterChainFree(c);
    printf("test_empty_batch_is_no_violation: PASS\n");
}

// ---- Test 4: self-consistent checkpoint match/mismatch discrimination ----
// See the file header comment for why this uses a synthetic checkpoint fed
// directly to the file-static _batchViolatesCheckpoints (the exact fold+
// compare logic the public function delegates to) instead of trying to
// forge a match against a real pinned value.
static void test_self_consistent_match_and_flip(void)
{
    UInt256 anchor = u256_fill(0xb3);
    BRCompactFilterChain *c = BRCompactFilterChainNew(0, 100, anchor);

    UInt256 good[5];
    for (int i = 0; i < 5; i++) good[i] = u256_fill((uint8_t)(0x50 + i));
    // Heights 100, 101, 102, 103, 104.

    // Independently fold heights 100..102 with the same public primitive
    // Append relies on (BRGCSFilterHeader -- what _foldHeader wraps) to
    // land on a self-chosen checkpoint value at height 102.
    UInt256 h = anchor;
    h = BRGCSFilterHeader(good[0], h); // height 100
    h = BRGCSFilterHeader(good[1], h); // height 101
    h = BRGCSFilterHeader(good[2], h); // height 102 -- our checkpoint target

    BRCFCheckpoint testCp = { 102, h };
    const BRCFCheckpoint *cps[1] = { &testCp };

    uint32_t nextBefore = BRCompactFilterChainNextHeight(c);
    size_t countBefore = BRCompactFilterChainCount(c);
    UInt256 tipBefore = BRCompactFilterChainTipHeader(c);

    uint32_t vh = 0xffffffff;
    UInt256 vc = UINT256_ZERO;
    int rGood = _batchViolatesCheckpoints(c, good, 5, cps, 1, &vh, &vc);
    assert(rGood == 0); // matches -> no violation

    assert(BRCompactFilterChainNextHeight(c) == nextBefore);
    assert(BRCompactFilterChainCount(c) == countBefore);
    assert(UInt256Eq(BRCompactFilterChainTipHeader(c), tipBefore));

    // Flip one byte feeding into the fold before height 102 -- the computed
    // header at 102 now necessarily differs from our self-chosen target.
    UInt256 bad[5];
    memcpy(bad, good, sizeof(bad));
    bad[1].u8[0] ^= 0xff;

    uint32_t vh2 = 0;
    UInt256 vc2 = UINT256_ZERO;
    int rBad = _batchViolatesCheckpoints(c, bad, 5, cps, 1, &vh2, &vc2);
    assert(rBad == 1);
    assert(vh2 == 102);
    assert(! UInt256Eq(vc2, h));

    assert(BRCompactFilterChainNextHeight(c) == nextBefore);
    assert(BRCompactFilterChainCount(c) == countBefore);
    assert(UInt256Eq(BRCompactFilterChainTipHeader(c), tipBefore));

    BRCompactFilterChainFree(c);
    printf("test_self_consistent_match_and_flip: PASS\n");
}

// ---- Test 5: _foldHeader reproduces BRCompactFilterChainAppend's math
//      byte-for-byte against a real COMMITTED append. -----------------------
static void test_fold_matches_committed_append(void)
{
    UInt256 anchor = u256_fill(0xc4);
    BRCompactFilterChain *c = BRCompactFilterChainNew(0, 200, anchor);

    UInt256 batch[4];
    for (int i = 0; i < 4; i++) batch[i] = u256_fill((uint8_t)(0x70 + i));

    int ok = BRCompactFilterChainAppend(c, anchor, batch, 4);
    assert(ok == 1);

    UInt256 h = anchor;
    for (int i = 0; i < 4; i++) {
        h = _foldHeader(h, batch[i]);
        assert(UInt256Eq(h, BRCompactFilterChainHeader(c, (uint32_t)(200 + i))));
    }

    BRCompactFilterChainFree(c);
    printf("test_fold_matches_committed_append: PASS\n");
}

int main(void)
{
    test_no_checkpoint_in_range();
    test_real_checkpoint_mismatch_detected();
    test_empty_batch_is_no_violation();
    test_self_consistent_match_and_flip();
    test_fold_matches_committed_append();
    printf("cf_candidate_header_kat: ALL PASS\n");
    return 0;
}
