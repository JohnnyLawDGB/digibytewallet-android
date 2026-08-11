// Host KAT for Task 5 of the cfcheckpt-active-rejection plan: proves the
// continuity-mismatch re-anchor QUORUM decision in _peerRelayedCFHeaders
// (BRPeerManager.c) now requires "a strict majority of connected filter
// peers AND >= CF_CONTINUITY_REANCHOR_FLOOR (3) distinct disagreers that
// SHARE THE SAME claimed prevFilterHeader" -- not the pre-Task-5 trigger of
// "CF_CONTINUITY_REANCHOR_K (2) distinct disagreers, any complaint".
//
// THE HOLE THIS CLOSES. Two peers with UNRELATED complaints -- e.g. each
// stuck on its own dead socket or serving a stale cfheaders reply, with
// DIFFERENT claimed prevFilterHeader values -- described no coherent
// alternate chain at all, yet the old K=2/any-disagree trigger treated them
// as a quorum and tore down + re-anchored a possibly-correct chain. The fix
// only credits disagreers that agree with EACH OTHER on what our tip's
// prevFilterHeader should have been, and additionally requires that
// agreeing bloc to be a majority of the currently connected filter-peer
// population -- not just an absolute floor, so a small clique can't
// out-vote a much larger population of peers that never complained.
//
// Approach: same "#include a .c for its statics" idiom as
// cf_checkpoint_veto_kat/cf_checkpoint_enforce_kat/cf_confirm_kat/
// cf_scan_ledger_drive_kat. A real BRPeerManager (BRPeerManagerNew) + real
// BRWallet (BRWalletNew) are built; _peerRelayedCFHeaders is driven directly
// with SYNTHETIC BRPeerNew() peers and hand-built batches -- no sockets, no
// real network peers.
//
// UNLIKE the sibling KATs, this one needs BOTH BRPeerManager.c's statics
// (the quorum decision itself) AND BRPeer.c's private BRPeerContext (to
// force each synthetic peer's connect status to BRPeerStatusConnected --
// there is no public setter, and _BRPeerManagerConnectedFilterPeerCount /
// the multi-peer quorum's majority denominator both gate on
// BRPeerConnectStatus(p) == BRPeerStatusConnected). So this file #include-s
// BOTH BRPeer.c AND BRPeerManager.c directly (neither is passed as a
// separate compilation unit in run.sh -- that would be a duplicate-symbol
// link error). BRPeerNew's calloc leaves ctx->socket == -1, so every real,
// unwrapped send path these peers exercise (the active cfheaders probe,
// the post-reanchor "kick recovery" request) safely no-ops (ENOTCONN)
// instead of touching a real socket or crashing -- same reasoning
// cf_checkpoint_enforce_kat/cf_scan_ledger_drive_kat's file headers
// document for BRPeerNew() peers generally.
//
// ---- ONE NAME COLLISION between the two included .c files ----
// Both BRPeer.c and BRPeerManager.c separately define a file-static
// `void _dummyThreadCleanup(void *info)` (used as each struct's default
// pthread cleanup handler). Combined into a single translation unit via
// #include, that's a duplicate-definition compile error. Renamed via a
// scoped preprocessor #define around BRPeer.c's #include only, so
// BRPeer.c's copy (definition + its 2 internal uses) becomes
// `_dummyThreadCleanup_brpeer`, colliding with nothing; BRPeerManager.c's
// own `_dummyThreadCleanup` (used for BRPeerManager's cleanup handler) is
// untouched. No production source file is modified by this rename -- it is
// a preprocessor-only substitution scoped to this KAT's translation unit.
//
// ---- The preimage escape hatch (same one Tasks 2/3/4 used, reviewer-
// approved) ---- test_quorum_veto_checkpoint_confirmed's premise requires
// our OWN committed chain's header at a REAL checkpoint height to equal the
// REAL pinned 256-bit filterHeader. Constructing that from scratch would be
// a SHA256d preimage break (same problem cf_candidate_header_kat,
// cf_checkpoint_enforce_kat's test_matching_batch_wiring, and
// cf_checkpoint_veto_kat's test_veto_confirmed_chain all document). Scoped
// identically to cf_checkpoint_veto_kat: GNU ld's
// -Wl,--wrap=BRCompactFilterChainHeader intercepts ONLY the one accessor
// call _BRPeerManagerCheckpointConfirmsOurChainLocked makes to read our
// chain's header at the pinned checkpoint height, forcing it to return the
// real pin value (read directly out of the real BRMainNetCFCheckpoints
// table, never a hand-typed literal). Armed for exactly the one call that
// needs it, disarmed immediately after; every other call in this file
// (including every call the other three tests make) passes through to the
// real, unwrapped accessor.
//
// ==== RED-BEFORE-GREEN GATE ====
// main() below runs all tests with NO #ifdef branching -- every check()
// asserts the FIXED (quorum-enforced) outcome unconditionally, in source
// identical between builds. run.sh compiles this exact file twice:
//   RED   -DCF_QUORUM_UNFIXED undefines CF_CONTINUITY_REANCHOR_FLOOR
//         entirely (BRPeerManager.h) and the quorum decision in
//         BRPeerManager.c falls back, via its own #ifndef guard, to the
//         pre-Task-5 "cfDisagreedCount >= CF_CONTINUITY_REANCHOR_K"
//         (any-disagree) trigger.
//         test_independent_disagreers_below_floor_no_reanchor's checks --
//         which expect 2 disagreeing peers with DIFFERENT claimed
//         prevFilterHeader to NOT re-anchor -- FAIL under this fallback
//         (2 >= K=2 fires regardless of agreement), and the binary exits
//         nonzero.
//   GREEN the production shape (no flag) must pass every check in every
//         test and exit 0.
// test_quorum_majority_reanchors and test_floor_met_but_not_majority_no_reanchor
// are GREEN-only proofs (see their own comments for why their RED-arm
// outcome isn't meaningfully comparable -- the RED K=2 threshold is a
// strict subset of the GREEN floor=3 threshold, so RED necessarily fires
// EARLIER on any input that also satisfies GREEN's stricter requirement;
// run.sh does not assert anything about their outcome in the red log).
//
// Not compiled into the Android NDK build (host-only KAT, matches every
// other native/src/test/host/*/run.sh convention in this repo).

#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <pthread.h>

#define _dummyThreadCleanup _dummyThreadCleanup_brpeer
#include "BRPeer.c"
#undef _dummyThreadCleanup

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

// Asserts manager->lock is NOT held after a call into _peerRelayedCFHeaders --
// every early-return in the handler must MGR_UNLOCK before returning, or the
// very next manager call in production would deadlock.
static void assertLockNotHeld(BRPeerManager *manager, const char *ctx)
{
    int r = pthread_mutex_trylock(&manager->lock);
    check(r == 0, ctx);
    if (r == 0) pthread_mutex_unlock(&manager->lock);
}

// dummy BRMerkleBlock, same pattern cf_confirm_kat/cf_checkpoint_veto_kat use:
// only ->height matters (the floor descent's first prevBlock lookup misses
// manager->blocks immediately -- BRMerkleBlockNew calloc's ->prevBlock to the
// zero hash -- so this height is the floor with zero hops, no genesis-linked
// chain required).
static BRMerkleBlock *dummyBlock(uint32_t height, uint8_t hashSeed)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    memset(b->blockHash.u8, hashSeed, sizeof(b->blockHash.u8));
    b->height = height;
    return b;
}

// Creates a synthetic BRPeer, gives it a DISTINCT address (so the dedup-by-
// address add-site treats it as a separate disagreer), marks it compact-
// filter-capable, forces its private status to Connected (there is no public
// setter -- see file header), and registers it in manager->connectedPeers so
// _BRPeerManagerConnectedFilterPeerCount / the active probe loop / the
// majority denominator all see it as a live filter peer. addrByte must be
// unique per call within a test (it is written to the low byte of the IPv6
// address).
static BRPeer *addConnectedFilterPeer(BRPeerManager *manager, uint8_t addrByte, uint16_t port)
{
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    p->address.u8[15] = addrByte;
    p->port = port;
    p->services |= SERVICES_NODE_COMPACT_FILTERS;
    ((BRPeerContext *)p)->status = BRPeerStatusConnected;
    array_add(manager->connectedPeers, p);
    return p;
}

// -1 = pass through to the real accessor (default, self-restoring). Armed =
// force this ONE (chain, height) call's return value for the tightly-scoped
// preimage escape hatch (see file header comment). Identical mechanism to
// cf_checkpoint_veto_kat's wrap.
static int                        g_forceHeaderArmed = 0;
static const BRCompactFilterChain *g_forceHeaderChain = NULL;
static uint32_t                    g_forceHeaderHeight = 0;
static UInt256                     g_forceHeaderValue;

extern UInt256 __real_BRCompactFilterChainHeader(const BRCompactFilterChain *chain, uint32_t height);

UInt256 __wrap_BRCompactFilterChainHeader(const BRCompactFilterChain *chain, uint32_t height)
{
    if (g_forceHeaderArmed && chain == g_forceHeaderChain && height == g_forceHeaderHeight) {
        return g_forceHeaderValue;
    }
    return __real_BRCompactFilterChainHeader(chain, height);
}

// Shared setup for the three ABOVE-the-top-checkpoint tests: anchors the
// compact-filter chain strictly above the highest pinned mainnet checkpoint
// (read from the real table, not hand-typed) so Task 4's checkpoint veto
// legitimately never applies here (BRCompactFilterChainStartHeight(...) >
// cp->height) -- any "no re-anchor" result in these tests is therefore
// attributable purely to the Task 5 quorum decision, matching
// cf_checkpoint_veto_kat's test_no_veto_above_top_checkpoint pattern.
static void primeAboveTopCheckpoint(BRPeerManager *manager, uint8_t anchorSeed, uint8_t primeSeedBase)
{
    uint32_t topCpHeight = BRMainNetCFCheckpoints[BRMainNetCFCheckpointsCount - 1].height;
    uint32_t startHeight = topCpHeight + 1;
    UInt256 anchor = u256_fill(anchorSeed);
    manager->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, startHeight, anchor);
    UInt256 prime[5];
    for (int i = 0; i < 5; i++) prime[i] = u256_fill((uint8_t)(primeSeedBase + i));
    check(BRCompactFilterChainAppend(manager->compactFilterChain, anchor, prime, 5) == 1,
          "setup: priming append succeeds above the top checkpoint");
}

// ---- GREEN / RED falsifying test: two peers disagree with our tip but
// claim DIFFERENT prevFilterHeader (independent transients -- no coherent
// alternate chain described). Below the CF_CONTINUITY_REANCHOR_FLOOR
// agreeing-bloc requirement (each is alone in its own bucket, agree==1) --
// must NOT re-anchor. Under -DCF_QUORUM_UNFIXED the fallback
// "cfDisagreedCount >= CF_CONTINUITY_REANCHOR_K(2)" doesn't care about
// agreement and fires anyway: this is the K=2 false-fire the fix closes. ---
static void test_independent_disagreers_below_floor_no_reanchor(void)
{
    BRWallet *wallet = makeWallet();
    BRPeerManager *manager = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(manager, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    uint32_t floorHeight = 24000000;
    BRMerkleBlock *floorBlock = dummyBlock(floorHeight, 0xC0);
    BRSetAdd(manager->blocks, floorBlock);
    manager->lastBlock = floorBlock;

    primeAboveTopCheckpoint(manager, 0xA1, 0x30);
    uint32_t primedStart = BRCompactFilterChainStartHeight(manager->compactFilterChain);
    size_t primedCount = BRCompactFilterChainCount(manager->compactFilterChain);
    check(manager->misbehavinCount == 0, "independent: sanity -- starts clean");

    BRPeer *peerA = addConnectedFilterPeer(manager, 0x01, 10001);
    BRPeer *peerB = addConnectedFilterPeer(manager, 0x02, 10002);
    check(_BRPeerManagerConnectedFilterPeerCount(manager) == 2,
          "independent: sanity -- exactly 2 connected filter peers");

    UInt256 batch[3];
    for (int i = 0; i < 3; i++) batch[i] = u256_fill((uint8_t)(0x60 + i));
    UInt256 prevA = u256_fill(0xD1);
    UInt256 prevB = u256_fill(0xD2);  // DIFFERENT from prevA -- independent transients, not a shared fork
    BRPeerCallbackInfo infoA = { .peer = peerA, .manager = manager, .hash = UINT256_ZERO };
    BRPeerCallbackInfo infoB = { .peer = peerB, .manager = manager, .hash = UINT256_ZERO };

    _peerRelayedCFHeaders(&infoA, FILTER_TYPE_BASIC, u256_fill(0xE1), prevA, batch, 3);
    check(manager->cfDisagreedCount == 1, "independent: peer A recorded as 1st disagreer");
    check(manager->cfReanchorCount == 0, "independent: no re-anchor after 1 disagreer");
    assertLockNotHeld(manager, "independent: lock released after peer A's call");

    _peerRelayedCFHeaders(&infoB, FILTER_TYPE_BASIC, u256_fill(0xE2), prevB, batch, 3);
    check(manager->cfDisagreedCount == 2, "independent: peer B recorded as 2nd, DISTINCT disagreer");
    check(manager->cfReanchorCount == 0,
          "independent: cfReanchorCount unchanged after 2nd disagreer -- re-anchor did NOT fire");
    check(manager->compactFilterChain != NULL, "independent: compactFilterChain was NOT torn down");
    check(BRCompactFilterChainStartHeight(manager->compactFilterChain) == primedStart,
          "independent: chain start height unchanged");
    check(BRCompactFilterChainCount(manager->compactFilterChain) == primedCount,
          "independent: chain header count unchanged");
    check(manager->misbehavinCount == 0,
          "independent: neither disagreeing peer was banned -- disagreement alone is never punished");
    assertLockNotHeld(manager, "independent: lock released after peer B's call");

    BRPeerManagerFree(manager);
    BRWalletFree(wallet);
    printf("test_independent_disagreers_below_floor_no_reanchor: done\n");
}

// ---- GREEN-only: 3 distinct peers ALL claim the SAME prevFilterHeader (a
// coherent alternate chain) and are a strict majority (3-of-3) of the
// connected filter-peer population -- the fix must still allow a genuine
// re-anchor, not merely block false ones. Not compared against the RED arm
// (see file header: RED's weaker K=2 threshold necessarily fires no later
// than GREEN's on any input satisfying GREEN's stricter requirement, so a
// same-final-state comparison isn't meaningful here). ----------------------
static void test_quorum_majority_reanchors(void)
{
    BRWallet *wallet = makeWallet();
    BRPeerManager *manager = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(manager, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    uint32_t floorHeight = 24000000;
    BRMerkleBlock *floorBlock = dummyBlock(floorHeight, 0xC2);
    BRSetAdd(manager->blocks, floorBlock);
    manager->lastBlock = floorBlock;

    primeAboveTopCheckpoint(manager, 0xA2, 0x40);

    BRPeer *p1 = addConnectedFilterPeer(manager, 0x11, 10011);
    BRPeer *p2 = addConnectedFilterPeer(manager, 0x12, 10012);
    BRPeer *p3 = addConnectedFilterPeer(manager, 0x13, 10013);
    check(_BRPeerManagerConnectedFilterPeerCount(manager) == 3,
          "majority: sanity -- exactly 3 connected filter peers");

    UInt256 batch[3];
    for (int i = 0; i < 3; i++) batch[i] = u256_fill((uint8_t)(0x80 + i));
    UInt256 sharedPrev = u256_fill(0xDA);  // SAME for all three -- a coherent alternate chain
    BRPeerCallbackInfo info1 = { .peer = p1, .manager = manager, .hash = UINT256_ZERO };
    BRPeerCallbackInfo info2 = { .peer = p2, .manager = manager, .hash = UINT256_ZERO };
    BRPeerCallbackInfo info3 = { .peer = p3, .manager = manager, .hash = UINT256_ZERO };

    _peerRelayedCFHeaders(&info1, FILTER_TYPE_BASIC, u256_fill(0xF1), sharedPrev, batch, 3);
    check(manager->cfReanchorCount == 0, "majority: no re-anchor after 1 agreeing disagreer");
    _peerRelayedCFHeaders(&info2, FILTER_TYPE_BASIC, u256_fill(0xF2), sharedPrev, batch, 3);
    check(manager->cfReanchorCount == 0, "majority: no re-anchor after 2 agreeing disagreers (below floor)");
    _peerRelayedCFHeaders(&info3, FILTER_TYPE_BASIC, u256_fill(0xF3), sharedPrev, batch, 3);

    check(manager->cfReanchorCount == 1,
          "majority: 3 agreeing disagreers + majority of 3 connected filter peers DOES re-anchor");
    check(manager->compactFilterChain == NULL, "majority: chain WAS torn down (real re-anchor, no veto)");
    check(manager->autoFetchCFiltersStart == floorHeight,
          "majority: autoFetchCFiltersStart snapped to the block floor");
    check(manager->misbehavinCount == 0,
          "majority: no peer was banned -- this is a legitimate re-anchor, not a vetoed liar");
    assertLockNotHeld(manager, "majority: manager->lock released after the real re-anchor return");

    BRPeerManagerFree(manager);
    BRWalletFree(wallet);
    printf("test_quorum_majority_reanchors: done\n");
}

// ---- GREEN-only: proves the MAJORITY clause is independently enforced,
// not just the floor. 3 distinct peers agree on the same prevFilterHeader
// (clears CF_CONTINUITY_REANCHOR_FLOOR) but the connected filter-peer
// population is 7 -- 3 is NOT a majority of 7 (3 > 7/2==3 is false). A
// small agreeing clique must not be able to out-vote a much larger silent
// population. If the implementation only checked the floor (forgot the
// majority half, or used >= instead of >), this test would catch it. -----
static void test_floor_met_but_not_majority_no_reanchor(void)
{
    BRWallet *wallet = makeWallet();
    BRPeerManager *manager = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(manager, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    uint32_t floorHeight = 24000000;
    BRMerkleBlock *floorBlock = dummyBlock(floorHeight, 0xC3);
    BRSetAdd(manager->blocks, floorBlock);
    manager->lastBlock = floorBlock;

    primeAboveTopCheckpoint(manager, 0xA3, 0x50);
    uint32_t primedStart = BRCompactFilterChainStartHeight(manager->compactFilterChain);
    size_t primedCount = BRCompactFilterChainCount(manager->compactFilterChain);

    BRPeer *p1 = addConnectedFilterPeer(manager, 0x21, 10021);
    BRPeer *p2 = addConnectedFilterPeer(manager, 0x22, 10022);
    BRPeer *p3 = addConnectedFilterPeer(manager, 0x23, 10023);
    // 4 additional SILENT connected filter peers: present in the pool, never
    // disagree, exist purely to dilute the majority denominator to 7.
    addConnectedFilterPeer(manager, 0x24, 10024);
    addConnectedFilterPeer(manager, 0x25, 10025);
    addConnectedFilterPeer(manager, 0x26, 10026);
    addConnectedFilterPeer(manager, 0x27, 10027);
    check(_BRPeerManagerConnectedFilterPeerCount(manager) == 7,
          "floor-not-majority: sanity -- 7 connected filter peers total");

    UInt256 batch[3];
    for (int i = 0; i < 3; i++) batch[i] = u256_fill((uint8_t)(0x90 + i));
    UInt256 sharedPrev = u256_fill(0xDB);
    BRPeerCallbackInfo info1 = { .peer = p1, .manager = manager, .hash = UINT256_ZERO };
    BRPeerCallbackInfo info2 = { .peer = p2, .manager = manager, .hash = UINT256_ZERO };
    BRPeerCallbackInfo info3 = { .peer = p3, .manager = manager, .hash = UINT256_ZERO };

    _peerRelayedCFHeaders(&info1, FILTER_TYPE_BASIC, u256_fill(0xF4), sharedPrev, batch, 3);
    _peerRelayedCFHeaders(&info2, FILTER_TYPE_BASIC, u256_fill(0xF5), sharedPrev, batch, 3);
    _peerRelayedCFHeaders(&info3, FILTER_TYPE_BASIC, u256_fill(0xF6), sharedPrev, batch, 3);

    check(manager->cfDisagreedCount == 3, "floor-not-majority: sanity -- 3 agreeing disagreers collected (floor met)");
    check(manager->cfReanchorCount == 0,
          "floor-not-majority: 3 agreeing disagreers meet the floor but are NOT a majority of 7 -- no re-anchor");
    check(manager->compactFilterChain != NULL, "floor-not-majority: compactFilterChain was NOT torn down");
    check(BRCompactFilterChainStartHeight(manager->compactFilterChain) == primedStart,
          "floor-not-majority: chain start height unchanged");
    check(BRCompactFilterChainCount(manager->compactFilterChain) == primedCount,
          "floor-not-majority: chain header count unchanged");
    assertLockNotHeld(manager, "floor-not-majority: manager->lock released after the 3rd call");

    BRPeerManagerFree(manager);
    BRWalletFree(wallet);
    printf("test_floor_met_but_not_majority_no_reanchor: done\n");
}

// ---- GREEN-only fold-in (Task 4 deferred m1): proves Task 4's checkpoint
// veto still applies when reached via the QUORUM path (>1 disagreer), not
// only the single-peer escape hatch cf_checkpoint_veto_kat already covers.
// Same confirmed-chain premise as cf_checkpoint_veto_kat's
// test_veto_confirmed_chain: our own chain is anchored below the first
// pinned checkpoint and primed with real, honestly-folded headers past it;
// the contested batch's height range [50020..50024] crosses NO checkpoint
// (Task 3's pre-commit check legitimately sees nc==0), so it fails purely
// on continuity (wrong prevFilterHeader) once 3 agreeing disagreers (a
// majority of 3 connected filter peers) reach the quorum decision. -------
static void test_quorum_veto_checkpoint_confirmed(void)
{
    BRWallet *wallet = makeWallet();
    BRPeerManager *manager = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(manager, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    BRMerkleBlock *floorBlock = dummyBlock(999000, 0xC4);
    BRSetAdd(manager->blocks, floorBlock);
    manager->lastBlock = floorBlock;

    // Anchor BELOW the first pinned checkpoint (BRMainNetCFCheckpoints[0],
    // height 50000) and prime 30 real, honestly-folded headers past it --
    // genuinely resident chain data, nothing forced here.
    UInt256 anchor = u256_fill(0xC5);
    manager->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, 49990, anchor);
    UInt256 prime[30];
    for (int i = 0; i < 30; i++) prime[i] = u256_fill((uint8_t)(0x20 + i));
    check(BRCompactFilterChainAppend(manager->compactFilterChain, anchor, prime, 30) == 1,
          "veto-quorum: priming append succeeds (chain resident through height 50019)");
    check(BRCompactFilterChainNextHeight(manager->compactFilterChain) == 50020,
          "veto-quorum: sanity -- next batch would start at 50020");

    const BRCFCheckpoint *cp = BRCFHighestCheckpointAtOrBelow(50020);
    check(cp != NULL && cp->height == BRMainNetCFCheckpoints[0].height,
          "veto-quorum: sanity -- the contested height's highest-at-or-below checkpoint is BRMainNetCFCheckpoints[0]");

    BRPeer *p1 = addConnectedFilterPeer(manager, 0x31, 10031);
    BRPeer *p2 = addConnectedFilterPeer(manager, 0x32, 10032);
    BRPeer *p3 = addConnectedFilterPeer(manager, 0x33, 10033);
    check(_BRPeerManagerConnectedFilterPeerCount(manager) == 3,
          "veto-quorum: sanity -- exactly 3 connected filter peers (a majority of 3 needs only 2, so 3 easily clears it)");

    // Heights [50020..50024] cross NO pinned checkpoint (the next one is at
    // 100000) -- Task 3's pre-commit check legitimately sees nc==0 and never
    // rejects this batch; it fails purely on the wrong prevFilterHeader.
    UInt256 divergentBatch[5];
    for (int i = 0; i < 5; i++) divergentBatch[i] = u256_fill((uint8_t)(0x70 + i));
    UInt256 sharedWrongPrev = u256_fill(0xDC);
    BRPeerCallbackInfo info1 = { .peer = p1, .manager = manager, .hash = UINT256_ZERO };
    BRPeerCallbackInfo info2 = { .peer = p2, .manager = manager, .hash = UINT256_ZERO };
    BRPeerCallbackInfo info3 = { .peer = p3, .manager = manager, .hash = UINT256_ZERO };

    _peerRelayedCFHeaders(&info1, FILTER_TYPE_BASIC, u256_fill(0xF7), sharedWrongPrev, divergentBatch, 5);
    check(manager->cfReanchorCount == 0, "veto-quorum: no re-anchor after 1 agreeing disagreer");
    _peerRelayedCFHeaders(&info2, FILTER_TYPE_BASIC, u256_fill(0xF8), sharedWrongPrev, divergentBatch, 5);
    check(manager->cfReanchorCount == 0, "veto-quorum: no re-anchor after 2 agreeing disagreers (below floor)");
    check(manager->misbehavinCount == 0, "veto-quorum: sanity -- nobody banned yet");

    // 3rd call reaches the quorum decision (floor + majority both clear) --
    // this is the exact moment _BRPeerManagerCheckpointConfirmsOurChainLocked
    // reads our chain's header at the pinned checkpoint height. Arm the
    // tightly-scoped wrap for ONLY this one call so that read reports the
    // real pin value; disarm immediately after.
    g_forceHeaderChain = manager->compactFilterChain;
    g_forceHeaderHeight = cp->height;
    g_forceHeaderValue = cp->filterHeader;  // the REAL pinned value, read from the real table
    g_forceHeaderArmed = 1;
    _peerRelayedCFHeaders(&info3, FILTER_TYPE_BASIC, u256_fill(0xF9), sharedWrongPrev, divergentBatch, 5);
    g_forceHeaderArmed = 0;

    check(manager->cfReanchorCount == 0,
          "veto-quorum: cfReanchorCount unchanged after the 3rd agreeing disagreer -- re-anchor did NOT fire");
    check(manager->compactFilterChain != NULL, "veto-quorum: compactFilterChain was NOT torn down");
    check(BRCompactFilterChainStartHeight(manager->compactFilterChain) == 49990,
          "veto-quorum: chain start height unchanged (49990)");
    check(BRCompactFilterChainCount(manager->compactFilterChain) == 30,
          "veto-quorum: chain header count unchanged (30)");
    check(manager->misbehavinCount == 1,
          "veto-quorum: the 3rd (triggering) disagreeing peer WAS misbehavin'd/banned instead of winning the re-anchor");
    assertLockNotHeld(manager, "veto-quorum: manager->lock released after the veto-return");

    BRPeerManagerFree(manager);
    BRWalletFree(wallet);
    printf("test_quorum_veto_checkpoint_confirmed: done\n");
}

int main(void)
{
    // Deliberately NO #ifdef branching here: every test asserts the FIXED
    // outcome unconditionally, in source identical between the RED and
    // GREEN builds. run.sh builds this exact file twice -- see file header
    // for exactly what the RED arm (-DCF_QUORUM_UNFIXED) is expected to
    // falsify.
    test_independent_disagreers_below_floor_no_reanchor();
    test_quorum_majority_reanchors();
    test_floor_met_but_not_majority_no_reanchor();
    test_quorum_veto_checkpoint_confirmed();

    printf(g_fail == 0 ? "\ncf_checkpoint_quorum_kat: ALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
