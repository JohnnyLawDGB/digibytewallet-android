// Host KAT for Task 3 of the cfcheckpt-active-rejection plan: proves
// _peerRelayedCFHeaders (BRPeerManager.c) actively REJECTS+BANS a peer whose
// cfheaders batch crosses a pinned mainnet filter-header checkpoint
// (BRMainNetCFCheckpoints[]) and disagrees with it, instead of merely
// logging the mismatch and committing the batch anyway (the pre-Task-3
// observe-only behavior). This is the security-critical change: without
// it, a single lying peer can walk the wallet's compact-filter chain onto
// an arbitrary fork past a point the wallet has independently pinned as
// trustworthy.
//
// Approach: #include "BRPeerManager.c" directly (same "peek at file-statics"
// idiom cf_confirm_kat/run.sh and cf_scan_ledger_drive_kat/run.sh use) to
// reach the file-static _peerRelayedCFHeaders and the otherwise-opaque
// BRPeerManagerStruct/BRPeerCallbackInfo definitions. A real BRPeerManager
// is built via the public BRPeerManagerNew() and a real BRWallet via
// BRWalletNew() (same test-mnemonic derivation cf_confirm_kat uses). The
// synthetic serving peer is heap-allocated via BRPeerNew (never a stack
// literal -- BRPeer.c casts to the private BRPeerContext* and touches
// fields past the public struct elsewhere in the manager; BRPeerNew's
// calloc also leaves ctx->socket == -1, so _BRPeerManagerPeerMisbehavin's
// real BRPeerDisconnectTagged -> BRPeerDisconnect call safely no-ops
// instead of touching a real fd).
//
// _peerRelayedCFHeaders is called DIRECTLY with a hand-built batch -- no
// socket, no real network peer, no --wrap needed for the two real-path
// scenarios below, because:
//   * a stopHash that was never registered into manager->blocks makes the
//     height-alignment guard (the `BRSetGet(manager->blocks, &stopHash)`
//     block near the top of the handler) a no-op, so it falls through to
//     the continuity/append logic unconditionally;
//   * manager->autoFetchCFiltersEnabled is left at its calloc default (0),
//     so the post-append auto-fetch-cfilters block is skipped entirely;
//   * a synthetic BRPeerNew() peer advertises no services, and
//     manager->lastBlock is NULL (no header chain was built), so
//     _BRPeerManagerRequestNextCFHeaders (called after every clean append)
//     returns at its very first guard (`if (!manager->lastBlock) return;`)
//     -- no wire send, no crash.
//
// The MISMATCH/reject scenario (test_mismatch_rejected) drives the REAL,
// unmodified BRCompactFilterChainBatchViolatesCheckpoint against the REAL
// BRMainNetCFCheckpoints table -- this is the actual security property
// the whole plan exists for, end to end, no test doubles. Matching the
// real pinned 256-bit value at height 50000 from constructed data would be
// a SHA256d preimage break (same problem cf_candidate_header_kat's file
// header documents for Task 2), so the constructed batch necessarily
// diverges -- exactly the case enforcement must catch.
//
// RED/GREEN gate shape: main() below runs every test with NO #ifdef
// branching -- every check() asserts the FIXED (enforced) outcome
// unconditionally, in source identical between builds. run.sh compiles
// this exact file twice: default (production shape) and
// -DCF_CHECKPOINT_ENFORCE_UNFIXED (the enforce block in BRPeerManager.c
// compiled OUT by its own #ifndef guard, restoring pre-Task-3
// observe-only). test_mismatch_rejected's checks must FLIP from PASS to
// FAIL under -UNFIXED (proving the flag is load-bearing, not a test that
// would pass either way), while test_safety_control_no_checkpoint_in_range
// and test_matching_batch_wiring keep passing in both builds (neither
// depends on the enforce block: the safety control because no checkpoint
// is ever in range for it to act on, and the matching-batch case because
// the -UNFIXED build appends every batch unconditionally regardless of
// the forced wrap verdict).
//
// The MATCH/no-penalty scenario is
// split into two complementary checks instead of forging that same
// preimage break:
//   1. test_safety_control_no_checkpoint_in_range drives the REAL,
//      unwrapped validator with a batch whose height range contains no
//      pinned checkpoint at all -- BRCFCheckpointsInRange legitimately
//      returns empty and the validator returns 0 (no violation) through
//      completely real code, proving the wiring's "0 -> append normally,
//      no penalty" branch end to end.
//   2. test_matching_batch_wiring proves the SAME wiring branch for a
//      batch that genuinely SPANS a real checkpoint height, using GNU ld's
//      `--wrap=BRCompactFilterChainBatchViolatesCheckpoint` (same --wrap
//      idiom cf_scan_ledger_drive_kat/run.sh uses to intercept BRPeer.c's
//      send calls) to force the verdict "no violation" for this one
//      scenario -- simulating what a genuine match's crypto comparison
//      would report without requiring one. The wrap defaults to calling
//      through to the REAL __real_ function (see g_forceViolatesResult
//      below); every other test in this file exercises the genuine,
//      unforced validator. This is the plan's own authorized escape hatch
//      ("a test-local/synthetic checkpoint via the same validator path is
//      acceptable for the MATCH branch") applied at the peer-manager
//      wiring layer instead of inside BRCompactFilterChain.c, because the
//      thing under test here is _peerRelayedCFHeaders's control flow, not
//      the fold math (which Task 2's cf_candidate_header_kat already
//      proves exhaustively, including a self-consistent match).
//
// Not compiled into the Android NDK build (host-only KAT, matches every
// other native/src/test/host/*/run.sh convention in this repo).

#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>

#include "BRPeerManager.c"

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

static UInt256 u256_fill(uint8_t b)
{
    UInt256 h;
    memset(h.u8, b, sizeof(h.u8));
    return h;
}

// canonical all-zeros mnemonic, same one every other host KAT in this tree uses
static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

static BRWallet *makeWallet(void)
{
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    return BRWalletNew(NULL, 0, mpk);
}

// Asserts manager->lock is NOT held after a call into _peerRelayedCFHeaders
// -- every early-return path in the handler must MGR_UNLOCK before
// returning, on every arm (enforce-reject included). A held lock would
// deadlock the very next manager call in production; pthread_mutex_trylock
// gives a hang-free way to prove it right here instead of discovering a
// hang deeper in the same run.
static void assertLockNotHeld(BRPeerManager *manager, const char *ctx)
{
    int r = pthread_mutex_trylock(&manager->lock);
    check(r == 0, ctx);
    if (r == 0) pthread_mutex_unlock(&manager->lock);
}

// -1 = pass through to the real validator (default). 0/1 = force the
// return value for the one scenario that needs a synthetic verdict. See
// the file header comment for the full rationale.
static int    g_forceViolatesResult = -1;
static uint32_t g_forceHeight = 0;
static UInt256 g_forceComputed;

extern int __real_BRCompactFilterChainBatchViolatesCheckpoint(const BRCompactFilterChain *chain,
        const UInt256 *filterHashes, size_t count, uint32_t *outHeight, UInt256 *outComputed);

int __wrap_BRCompactFilterChainBatchViolatesCheckpoint(const BRCompactFilterChain *chain,
        const UInt256 *filterHashes, size_t count, uint32_t *outHeight, UInt256 *outComputed)
{
    if (g_forceViolatesResult >= 0) {
        if (outHeight) *outHeight = g_forceHeight;
        if (outComputed) *outComputed = g_forceComputed;
        return g_forceViolatesResult;
    }
    return __real_BRCompactFilterChainBatchViolatesCheckpoint(chain, filterHashes, count, outHeight, outComputed);
}

// ---- Safety control: no checkpoint in the batch's range --------------------
// [60000, 60009] sits strictly between checkpoints (spacing 50000, first
// entry at 50000) -- same range Task 2's cf_candidate_header_kat uses for
// its analogous "no checkpoint in range" case. Must hold identically in
// BOTH build arms: the enforce block (guarded by
// `#ifndef CF_CHECKPOINT_ENFORCE_UNFIXED`) never even reaches a checkpoint
// comparison here, so whether it's compiled in or out cannot change the
// outcome -- proving enforcement never touches a non-checkpoint batch.
static void test_safety_control_no_checkpoint_in_range(void)
{
    BRWallet *wallet = makeWallet();
    BRPeerManager *manager = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeer *peer = BRPeerNew(BRMainNetParams.magicNumber);

    UInt256 anchor = u256_fill(0xa1);
    manager->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, 60000, anchor);

    UInt256 batch[10];
    for (int i = 0; i < 10; i++) batch[i] = u256_fill((uint8_t)(0x10 + i));

    UInt256 stopHash = u256_fill(0xEE); // never registered in manager->blocks
    BRPeerCallbackInfo info = { .peer = peer, .manager = manager, .hash = UINT256_ZERO };

    g_forceViolatesResult = -1; // real validator for this test
    _peerRelayedCFHeaders(&info, FILTER_TYPE_BASIC, stopHash, anchor, batch, 10);

    check(BRCompactFilterChainNextHeight(manager->compactFilterChain) == 60010,
          "safety-control: no-checkpoint batch still advances the chain tip");
    check(BRCompactFilterChainCount(manager->compactFilterChain) == 10,
          "safety-control: no-checkpoint batch is fully appended");
    check(manager->misbehavinCount == 0,
          "safety-control: serving peer is NOT penalized for a non-checkpoint batch");
    check(manager->cfHeadersRequestedThrough == 60009,
          "safety-control: cfHeadersRequestedThrough advances to the new tip");
    assertLockNotHeld(manager, "safety-control: manager->lock released after the call");

    BRPeerManagerFree(manager);
    BRWalletFree(wallet);
    printf("test_safety_control_no_checkpoint_in_range: done\n");
}

// ---- GREEN: a checkpoint-crossing batch that mismatches the real pin ------
// is REJECTED pre-commit -- never appended, tip does not advance, the
// serving peer is misbehavin'd, cfHeadersRequestedThrough is left alone (so
// the next driver tick re-requests instead of treating this as satisfied).
// Real BRCompactFilterChainBatchViolatesCheckpoint, real BRMainNetCFCheckpoints
// table, real _peerRelayedCFHeaders -- no test doubles anywhere in this path.
static void test_mismatch_rejected(void)
{
    BRWallet *wallet = makeWallet();
    BRPeerManager *manager = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeer *peer = BRPeerNew(BRMainNetParams.magicNumber);

    UInt256 anchor = u256_fill(0xa2);
    manager->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, 49995, anchor);
    // Batch covers heights [49995, 50004]; BRMainNetCFCheckpoints[0].height == 50000.
    UInt256 batch[10];
    for (int i = 0; i < 10; i++) batch[i] = u256_fill((uint8_t)(0x30 + i));
    // Matching the real pinned value at 50000 from this data would be a
    // SHA256d preimage break -- the fold necessarily diverges.

    UInt256 stopHash = u256_fill(0xEE);
    BRPeerCallbackInfo info = { .peer = peer, .manager = manager, .hash = UINT256_ZERO };

    check(manager->misbehavinCount == 0, "mismatch: sanity -- peer starts clean");

    g_forceViolatesResult = -1; // real validator, real table
    _peerRelayedCFHeaders(&info, FILTER_TYPE_BASIC, stopHash, anchor, batch, 10);

    check(BRCompactFilterChainNextHeight(manager->compactFilterChain) == 49995,
          "mismatch: chain tip did NOT advance past the batch start (rejected pre-commit)");
    check(BRCompactFilterChainCount(manager->compactFilterChain) == 0,
          "mismatch: BRCompactFilterChainCount unchanged -- nothing was committed");
    check(manager->misbehavinCount == 1,
          "mismatch: serving peer WAS disconnected/evicted (misbehavin count bumped)");
    check(manager->cfHeadersRequestedThrough == 0,
          "mismatch: cfHeadersRequestedThrough left alone -- re-request path stays live");
    assertLockNotHeld(manager, "mismatch: manager->lock released after the reject-return");

    BRPeerManagerFree(manager);
    BRWalletFree(wallet);
    printf("test_mismatch_rejected: done\n");
}

// ---- GREEN: a batch that genuinely SPANS a real checkpoint height and
// MATCHES it is appended normally, tip advances, peer is NOT penalized.
// See the file header comment for why the match verdict is forced via
// --wrap instead of forged from real data (a genuine match is the same
// SHA256d preimage problem test_mismatch_rejected's comment documents).
static void test_matching_batch_wiring(void)
{
    BRWallet *wallet = makeWallet();
    BRPeerManager *manager = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeer *peer = BRPeerNew(BRMainNetParams.magicNumber);

    UInt256 anchor = u256_fill(0xa4);
    manager->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, 49995, anchor);
    // Same real checkpoint-crossing range as test_mismatch_rejected
    // ([49995, 50004], spans BRMainNetCFCheckpoints[0].height == 50000) --
    // this batch genuinely spans a pinned checkpoint; only the crypto
    // verdict is forced (see g_forceViolatesResult), not the range.
    UInt256 batch[10];
    for (int i = 0; i < 10; i++) batch[i] = u256_fill((uint8_t)(0x60 + i));

    UInt256 stopHash = u256_fill(0xEE);
    BRPeerCallbackInfo info = { .peer = peer, .manager = manager, .hash = UINT256_ZERO };

    g_forceViolatesResult = 0; // simulate "validator checked the real pin and it matched"
    _peerRelayedCFHeaders(&info, FILTER_TYPE_BASIC, stopHash, anchor, batch, 10);
    g_forceViolatesResult = -1; // restore pass-through for every later test

    check(BRCompactFilterChainNextHeight(manager->compactFilterChain) == 50005,
          "match: a checkpoint-crossing batch that MATCHES is appended (tip advances)");
    check(BRCompactFilterChainCount(manager->compactFilterChain) == 10,
          "match: all 10 headers committed");
    check(manager->misbehavinCount == 0,
          "match: serving peer is NOT penalized when the checkpoint matches");
    check(manager->cfHeadersRequestedThrough == 50004,
          "match: cfHeadersRequestedThrough advances to the new tip");
    assertLockNotHeld(manager, "match: manager->lock released after the call");

    BRPeerManagerFree(manager);
    BRWalletFree(wallet);
    printf("test_matching_batch_wiring: done\n");
}

int main(void)
{
    // Deliberately NO #ifdef branching here: every test below asserts the
    // FIXED (enforced) outcome unconditionally, in source identical between
    // the RED and GREEN builds. run.sh builds this exact file twice --
    // default (production shape) and -DCF_CHECKPOINT_ENFORCE_UNFIXED
    // (pre-Task-3 observe-only shape, the enforce block in BRPeerManager.c
    // compiled OUT by its own #ifndef guard) -- and checks that
    // test_mismatch_rejected's checks FLIP from PASS to FAIL under the
    // -UNFIXED build while test_safety_control_no_checkpoint_in_range and
    // test_matching_batch_wiring keep passing in both (neither depends on
    // the enforce block: the first because no checkpoint is ever in range,
    // the second because the -UNFIXED build appends unconditionally
    // regardless of the forced wrap verdict). A gate that can't flip like
    // this on the same source is not proof the flag does anything.
    test_safety_control_no_checkpoint_in_range();
    test_mismatch_rejected();
    test_matching_batch_wiring();

    printf(g_fail == 0 ? "\ncf_checkpoint_enforce_kat: ALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
