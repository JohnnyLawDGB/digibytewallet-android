// Host KAT for Task 4 of the cfcheckpt-active-rejection plan: proves
// _peerRelayedCFHeaders (BRPeerManager.c) VETOES a continuity-mismatch
// re-anchor when our own compact-filter chain is checkpoint-confirmed --
// closing the single-peer-liar hole where one lying (or fork-stuck) peer
// could otherwise run out the clock on CF_SINGLE_PEER_REANCHOR_ROUNDS
// consecutive diverged rounds and force the wallet to tear down and
// re-anchor a chain a pinned mainnet filter-header checkpoint has already
// independently proven correct.
//
// Task 3 (cf_checkpoint_enforce_kat) already rejects+bans any batch that
// CROSSES a pinned checkpoint and disagrees with it, PRE-commit, before
// BRCompactFilterChainAppend ever runs. This KAT exercises the DIFFERENT,
// downstream hole Task 3 does not touch: a batch that does NOT cross any
// checkpoint (so Task 3's pre-commit check legitimately sees nothing to
// reject) but still fails to append (continuity mismatch -- wrong
// prevFilterHeader), repeatedly, from a lone peer, until the single-peer
// escape hatch's round counter would otherwise force a floor re-anchor.
//
// Approach: same "#include BRPeerManager.c for its statics" idiom as
// cf_checkpoint_enforce_kat/cf_confirm_kat/cf_scan_ledger_drive_kat. A real
// BRPeerManager (BRPeerManagerNew) + real BRWallet (BRWalletNew) are built;
// _peerRelayedCFHeaders is driven directly with a synthetic lone BRPeerNew()
// peer and hand-built batches -- no socket, no real network peer:
//   * an unregistered stopHash makes the height-alignment guard a no-op
//     (same reason cf_checkpoint_enforce_kat's tests use one);
//   * the divergent batches' height range never contains a checkpoint (Task
//     3's pre-commit check legitimately returns "no violation" -- nc==0 --
//     for them), so BRCompactFilterChainAppend is reached and fails purely
//     on prevFilterHeader mismatch (continuity, not checkpoint content);
//   * a synthetic BRPeerNew() peer with no connectedPeers entry makes
//     _BRPeerManagerConnectedFilterPeerCount(manager) == 0 (<=1), so every
//     round takes the single-peer escape-hatch branch instead of ever
//     reaching the K=2 quorum path (a single distinct peer address can never
//     push cfDisagreedCount to CF_CONTINUITY_REANCHOR_K==2);
//   * the same peer never being added to manager->connectedPeers also makes
//     _BRPeerManagerAnyFilterCapablePeer / the active probe loop safe no-ops
//     -- no wire send, no crash, same reasoning cf_checkpoint_enforce_kat's
//     file header documents.
// A real floor is required for _BRPeerManagerReanchorAtFloorLocked to do
// anything (floor==0 short-circuits it): a synthetic BRMerkleBlock is
// inserted into manager->blocks and set as manager->lastBlock, same
// dummyBlock pattern cf_confirm_kat/run.sh uses (its prevBlock is the zero
// hash by construction, so the floor descent takes zero hops and floor =
// that block's height, no genesis-linked chain needed).
//
// ---- The preimage escape hatch (same one Tasks 2/3 used, reviewer-approved) ----
// test_veto_confirmed_chain's premise requires our OWN committed chain's
// header at a REAL checkpoint height to equal the REAL pinned 256-bit
// filterHeader. Constructing that from scratch would be a SHA256d preimage
// break (same problem cf_candidate_header_kat and cf_checkpoint_enforce_kat's
// test_matching_batch_wiring document). Scoped exactly like those: GNU ld's
// The -D rename intercepts ONLY the one accessor call
// _BRPeerManagerCheckpointConfirmsOurChainLocked makes to read our chain's
// header at the pinned checkpoint height, forcing it to return the real pin
// value (read directly out of the real BRMainNetCFCheckpoints table, never a
// hand-typed literal) -- simulating what a genuine committed match would
// read back, without requiring one. The wrap is armed for exactly the one
// call that needs it (immediately before the 3rd diverged round in
// test_veto_confirmed_chain) and disarmed immediately after, defaulting to
// __real_BRCompactFilterChainHeader (the genuine, unmodified accessor)
// everywhere else -- including every call test_no_veto_above_top_checkpoint
// makes, and every call _BRPeerManagerCheckpointConfirmsOurChainLocked
// itself makes to BRCFHighestCheckpointAtOrBelow, BRCompactFilterChainCount,
// and BRCompactFilterChainStartHeight, none of which are wrapped -- those
// run for real in both tests. The VETO DECISION (the helper's own
// comparison logic) and the entire non-veto/RED-divergent code path run
// through completely real, unmodified code; only the one leaf 256-bit read
// that would otherwise be a preimage break is simulated.
//
// ==== RED-BEFORE-GREEN GATE ====
// main() below runs both tests with NO #ifdef branching -- every check()
// asserts the FIXED (veto-enforced) outcome unconditionally, in source
// identical between builds. run.sh compiles this exact file twice:
//   RED   -DCF_CHECKPOINT_VETO_UNFIXED compiles OUT the veto guard at both
//         re-anchor call sites in BRPeerManager.c (their own #ifndef
//         guards), restoring the pre-Task-4 shape: a lone diverging peer
//         that reaches CF_SINGLE_PEER_REANCHOR_ROUNDS forces the re-anchor
//         regardless of checkpoint confirmation. test_veto_confirmed_chain's
//         checks -- which expect the veto to fire -- FAIL, and the binary
//         exits nonzero.
//   GREEN the production shape (no flag) must pass every check in both
//         tests and exit 0.
// test_no_veto_above_top_checkpoint must PASS in BOTH builds: its contested
// height sits above the top pinned checkpoint, so
// _BRPeerManagerCheckpointConfirmsOurChainLocked legitimately returns 0
// (startHeight > cp->height) through the REAL helper in the GREEN build --
// the veto guard is reached but declines to fire -- and in the RED build the
// guard is compiled out entirely, so the real re-anchor fires either way,
// identically. Proves the RED build's failure is specific to the
// checkpoint-confirmed veto case, not a broken harness.
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
// -- both the veto-return and the real-reanchor-return arms of the new
// gated sites must MGR_UNLOCK before returning, exactly like every other
// early-return in the handler. A held lock would deadlock the very next
// manager call in production.
static void assertLockNotHeld(BRPeerManager *manager, const char *ctx)
{
    int r = pthread_mutex_trylock(&manager->lock);
    check(r == 0, ctx);
    if (r == 0) pthread_mutex_unlock(&manager->lock);
}

// dummy BRMerkleBlock, same pattern cf_confirm_kat/run.sh uses: only
// ->height matters here (_BRPeerManagerBlockFloor reads manager->lastBlock
// ->height, and this block's ->prevBlock is the zero hash by construction --
// calloc'd by BRMerkleBlockNew -- so the floor descent's first prevBlock
// lookup misses manager->blocks immediately and returns this height as the
// floor with zero hops, no genesis-linked chain required).
static BRMerkleBlock *dummyBlock(uint32_t height, uint8_t hashSeed)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    memset(b->blockHash.u8, hashSeed, sizeof(b->blockHash.u8));
    b->height = height;
    return b;
}

// -1 = pass through to the real accessor (default, self-restoring). >=0 =
// force this ONE call's return value for the tightly-scoped preimage escape
// hatch (see the file header comment). g_forceHeader{Chain,Height,Value}
// gate the substitution to the exact (chain, height) pair the veto check
// under test reads -- every other (chain, height) pair, including every
// call in test_no_veto_above_top_checkpoint, passes through untouched.
static int                        g_forceHeaderArmed = 0;
static const BRCompactFilterChain *g_forceHeaderChain = NULL;
static uint32_t                    g_forceHeaderHeight = 0;
static UInt256                     g_forceHeaderValue;

// Portable stand-in for GNU ld's __real_ symbol. The call sites (in the
// #include-d BRPeerManager.c) are renamed to __wrap_ by a per-TU -D, so this
// declaration must still reach the genuine definition in another TU. An asm
// label does that on both ld64 and GNU ld: string literals are not
// macro-expanded, so the -D cannot rewrite the symbol name.
#if defined(__APPLE__)
#  define KAT_REAL_SYM(s) __asm__("_" s)
#else
#  define KAT_REAL_SYM(s) __asm__(s)
#endif
extern UInt256 __real_BRCompactFilterChainHeader(const BRCompactFilterChain *chain, uint32_t height)
    KAT_REAL_SYM("BRCompactFilterChainHeader");

UInt256 __wrap_BRCompactFilterChainHeader(const BRCompactFilterChain *chain, uint32_t height)
{
    if (g_forceHeaderArmed && chain == g_forceHeaderChain && height == g_forceHeaderHeight) {
        return g_forceHeaderValue;
    }
    return __real_BRCompactFilterChainHeader(chain, height);
}

// ---- GREEN (veto): our chain is checkpoint-confirmed; a lone diverging
// peer reaches CF_SINGLE_PEER_REANCHOR_ROUNDS -> re-anchor is VETOED, the
// lone peer is misbehavin'd instead, the chain is untouched. ----------------
static void test_veto_confirmed_chain(void)
{
    BRWallet *wallet = makeWallet();
    BRPeerManager *manager = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(manager, BR_SYNC_MODE_COMPACT_FILTERS_ONLY); // reanchor short-circuits on BLOOM_ONLY

    // Real floor: a resident block with no linkable prevBlock (zero hash),
    // so the floor descent stops immediately at this height.
    BRMerkleBlock *floorBlock = dummyBlock(999000, 0xAA);
    BRSetAdd(manager->blocks, floorBlock);
    manager->lastBlock = floorBlock;

    BRPeer *peer = BRPeerNew(BRMainNetParams.magicNumber);

    // Anchor our chain BELOW the first pinned checkpoint (BRMainNetCFCheckpoints[0],
    // height 50000) and prime it with 30 real, honestly-folded headers spanning
    // past that checkpoint height -- this is genuinely resident chain data,
    // committed via the real BRCompactFilterChainAppend fold, nothing forced here.
    UInt256 anchor = u256_fill(0xC1);
    manager->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, 49990, anchor);
    UInt256 prime[30];
    for (int i = 0; i < 30; i++) prime[i] = u256_fill((uint8_t)(0x20 + i));
    check(BRCompactFilterChainAppend(manager->compactFilterChain, anchor, prime, 30) == 1,
          "veto: priming append succeeds (real fold, tip advances to height 50019)");
    check(BRCompactFilterChainNextHeight(manager->compactFilterChain) == 50020,
          "veto: sanity -- chain resident through height 50019, next batch would start at 50020");
    check(manager->misbehavinCount == 0, "veto: sanity -- peer starts clean");

    const BRCFCheckpoint *cp = BRCFHighestCheckpointAtOrBelow(50020);
    check(cp != NULL && cp->height == BRMainNetCFCheckpoints[0].height,
          "veto: sanity -- the contested height's highest-at-or-below checkpoint is BRMainNetCFCheckpoints[0]");

    // The lone peer's diverging batch: heights [50020..50024] cross NO pinned
    // checkpoint (the next one is at 100000), so Task 3's pre-commit check
    // legitimately sees nc==0 and never rejects this batch -- it fails purely
    // on the wrong prevFilterHeader (continuity mismatch), same as any honest
    // fork disagreement would.
    UInt256 divergentBatch[5];
    for (int i = 0; i < 5; i++) divergentBatch[i] = u256_fill((uint8_t)(0x70 + i));
    UInt256 wrongPrev = u256_fill(0xDE); // certainly != our real tip header
    UInt256 stopHash = u256_fill(0xEE);  // never registered -> alignment guard is a no-op
    BRPeerCallbackInfo info = { .peer = peer, .manager = manager, .hash = UINT256_ZERO };

    // Rounds 1-2: below CF_SINGLE_PEER_REANCHOR_ROUNDS (3) -- no re-anchor
    // attempt yet either way, sanity-checked so the divergence at round 3 is
    // provably about the veto, not an earlier difference.
    _peerRelayedCFHeaders(&info, FILTER_TYPE_BASIC, stopHash, wrongPrev, divergentBatch, 5);
    check(manager->cfSingleDisagreeRounds == 1, "veto: round 1 -- single-peer diverged-round counter at 1/3");
    check(manager->cfReanchorCount == 0, "veto: round 1 -- no re-anchor attempted yet");
    assertLockNotHeld(manager, "veto: round 1 -- manager->lock released");

    _peerRelayedCFHeaders(&info, FILTER_TYPE_BASIC, stopHash, wrongPrev, divergentBatch, 5);
    check(manager->cfSingleDisagreeRounds == 2, "veto: round 2 -- single-peer diverged-round counter at 2/3");
    check(manager->cfReanchorCount == 0, "veto: round 2 -- no re-anchor attempted yet");
    check(manager->misbehavinCount == 0, "veto: round 2 -- peer still not penalized");
    assertLockNotHeld(manager, "veto: round 2 -- manager->lock released");

    // Round 3: CF_SINGLE_PEER_REANCHOR_ROUNDS reached -- this is the exact
    // moment _BRPeerManagerCheckpointConfirmsOurChainLocked reads our chain's
    // header at the pinned checkpoint height. Arm the tightly-scoped wrap for
    // ONLY this one call so that read reports the real pin value (see file
    // header comment); disarm immediately after.
    g_forceHeaderChain = manager->compactFilterChain;
    g_forceHeaderHeight = cp->height;
    g_forceHeaderValue = cp->filterHeader; // the REAL pinned value, read from the real table
    g_forceHeaderArmed = 1;
    _peerRelayedCFHeaders(&info, FILTER_TYPE_BASIC, stopHash, wrongPrev, divergentBatch, 5);
    g_forceHeaderArmed = 0;

    check(manager->cfSingleDisagreeRounds == 3, "veto: round 3 -- single-peer diverged-round counter reached 3/3");
    check(manager->cfReanchorCount == 0,
          "veto: cfReanchorCount unchanged after 3rd diverged round -- re-anchor did NOT fire");
    check(manager->compactFilterChain != NULL,
          "veto: compactFilterChain was NOT torn down");
    check(BRCompactFilterChainStartHeight(manager->compactFilterChain) == 49990,
          "veto: chain start height unchanged (49990) -- not re-anchored to the floor");
    check(BRCompactFilterChainCount(manager->compactFilterChain) == 30,
          "veto: chain header count unchanged (30) -- nothing discarded");
    check(manager->misbehavinCount == 1,
          "veto: the lone diverging peer WAS misbehavin'd/banned instead of winning the re-anchor");
    assertLockNotHeld(manager, "veto: manager->lock released after the veto-return");

    BRPeerManagerFree(manager);
    BRWalletFree(wallet);
    printf("test_veto_confirmed_chain: done\n");
}

// ---- GREEN (no veto at the tip, real code, no simulation): our chain's
// START sits ABOVE the top pinned checkpoint, so
// _BRPeerManagerCheckpointConfirmsOurChainLocked legitimately returns 0
// (BRCompactFilterChainStartHeight(...) > cp->height) -- the veto guard is
// reached but declines to fire, and the existing re-anchor path runs for
// real: cfReanchorCount increments, the chain is torn down, autoFetch state
// snaps to the block floor. Must pass identically whether the veto guard is
// compiled in (GREEN) or compiled out (RED) -- it's never reached in a state
// where it WOULD fire either way, so this is a safety control proving the
// RED failure above is specific to the confirmed-chain case. -------------
static void test_no_veto_above_top_checkpoint(void)
{
    BRWallet *wallet = makeWallet();
    BRPeerManager *manager = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(manager, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    uint32_t floorHeight = 24000000;
    BRMerkleBlock *floorBlock = dummyBlock(floorHeight, 0xBB);
    BRSetAdd(manager->blocks, floorBlock);
    manager->lastBlock = floorBlock;

    BRPeer *peer = BRPeerNew(BRMainNetParams.magicNumber);

    // Anchor strictly ABOVE the top pinned checkpoint (read from the real
    // table, not hand-typed, so a future checkpoint-table regen can't
    // silently invalidate this test's premise).
    uint32_t topCpHeight = BRMainNetCFCheckpoints[BRMainNetCFCheckpointsCount - 1].height;
    uint32_t startHeight = topCpHeight + 1;
    UInt256 anchor = u256_fill(0xC2);
    manager->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, startHeight, anchor);
    UInt256 prime[5];
    for (int i = 0; i < 5; i++) prime[i] = u256_fill((uint8_t)(0x40 + i));
    check(BRCompactFilterChainAppend(manager->compactFilterChain, anchor, prime, 5) == 1,
          "safety: priming append succeeds above the top checkpoint");

    uint32_t contested = BRCompactFilterChainNextHeight(manager->compactFilterChain);
    check(BRCompactFilterChainStartHeight(manager->compactFilterChain) > topCpHeight,
          "safety: sanity -- our chain START sits above the top pinned checkpoint");
    // Drive the REAL (unwrapped) helper here too, to document exactly why it
    // declines: no forced header, no arming -- this call is not part of the
    // control flow under test, just a same-process sanity check.
    check(_BRPeerManagerCheckpointConfirmsOurChainLocked(manager, contested) == 0,
          "safety: sanity -- the real helper declines (startHeight > cp->height), veto does not apply here");

    UInt256 divergentBatch[5];
    for (int i = 0; i < 5; i++) divergentBatch[i] = u256_fill((uint8_t)(0x90 + i));
    UInt256 wrongPrev = u256_fill(0xDF);
    UInt256 stopHash = u256_fill(0xEF);
    BRPeerCallbackInfo info = { .peer = peer, .manager = manager, .hash = UINT256_ZERO };

    _peerRelayedCFHeaders(&info, FILTER_TYPE_BASIC, stopHash, wrongPrev, divergentBatch, 5);
    check(manager->cfSingleDisagreeRounds == 1, "safety: round 1 -- diverged-round counter at 1/3");
    _peerRelayedCFHeaders(&info, FILTER_TYPE_BASIC, stopHash, wrongPrev, divergentBatch, 5);
    check(manager->cfSingleDisagreeRounds == 2, "safety: round 2 -- diverged-round counter at 2/3");
    _peerRelayedCFHeaders(&info, FILTER_TYPE_BASIC, stopHash, wrongPrev, divergentBatch, 5);

    check(manager->cfReanchorCount == 1,
          "safety: real re-anchor fired at round 3 -- our chain does not reach the top checkpoint");
    check(manager->compactFilterChain == NULL,
          "safety: chain WAS torn down (re-anchor completed, no veto)");
    check(manager->autoFetchCFiltersStart == floorHeight,
          "safety: autoFetchCFiltersStart snapped to the block floor");
    check(manager->misbehavinCount == 0,
          "safety: no peer was banned -- this is a legitimate re-anchor, not a vetoed liar");
    assertLockNotHeld(manager, "safety: manager->lock released after the real-reanchor return");

    BRPeerManagerFree(manager);
    BRWalletFree(wallet);
    printf("test_no_veto_above_top_checkpoint: done\n");
}

int main(void)
{
    // Deliberately NO #ifdef branching here: both tests assert the FIXED
    // outcome unconditionally, in source identical between the RED and
    // GREEN builds. run.sh builds this exact file twice -- default
    // (production shape) and -DCF_CHECKPOINT_VETO_UNFIXED (the veto guard
    // in BRPeerManager.c compiled OUT by its own #ifndef guard at both
    // re-anchor call sites) -- and checks that test_veto_confirmed_chain's
    // checks FLIP from PASS to FAIL under -UNFIXED while
    // test_no_veto_above_top_checkpoint keeps passing in both (its
    // contested height never reaches a state where the veto guard would
    // fire, so compiling it out changes nothing there).
    test_veto_confirmed_chain();
    test_no_veto_above_top_checkpoint();

    printf(g_fail == 0 ? "\ncf_checkpoint_veto_kat: ALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
