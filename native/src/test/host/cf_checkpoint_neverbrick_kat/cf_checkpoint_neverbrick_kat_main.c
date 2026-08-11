// Host KAT for Task 6 of the cfcheckpt-active-rejection plan: NEVER-BRICK
// recovery when the continuity re-anchor budget (CF_CONTINUITY_REANCHOR_MAX,
// currently 3) is truly EXHAUSTED. Both re-anchor gates in
// _peerRelayedCFHeaders (the multi-peer quorum path and the single-filter-
// peer escape hatch) are guarded by `cfReanchorCount < CF_CONTINUITY_REANCHOR_MAX`
// -- once that budget is spent, NEITHER can ever fire again for this manager's
// lifetime. Before Task 6 the fall-through at that point was a bare log line
// ("cfheaders: continuity mismatch ... — not appending") + unlock + return:
// silent, permanent, and (per the comment it replaced) predicated on a bloom
// fallback that no longer exists -- bloom/BIP37 was fully excised in v4.0.0.
// That is the brick: the wallet spins on "Syncing" forever with no recovery
// affordance and no data path left to catch what the CF chain can no longer
// verify.
//
// THE FIX (BRPeerManager.c, the exhaustion branch just above the "not
// appending" log): once cfReanchorCount >= CF_CONTINUITY_REANCHOR_MAX (checked
// independently of quorum status -- see the "distinguish the two" note below),
// park the forward-fetch cursor at the nearest TRUSTED mainnet checkpoint
// (BRCFHighestCheckpointAtOrBelow(tip), a compiled-in table lookup that never
// reads anything peer-supplied) and surface the unverifiable band
// [cp->height .. tip] through the same _BRPeerManagerSurfaceUnscannableLocked
// funnel every other unscannable-band site in this file uses (raises
// abandonedBelow, WARNs, drives the Kotlin "Scan for missing transactions"
// banner).
//
// THE JUDGMENT CALL this KAT locks in: WHICH cursor field actually governs
// where the next forward fetch resumes. Every getcfilters/getcfheaders
// re-request site in BRPeerManager.c computes
// `reqStart = autoFetchCFiltersThrough + 1`, clamped UP to
// autoFetchCFiltersStart (see :4280-4281, :5969-5970) -- Through, not Start,
// is the real resume cursor. Naively parking only autoFetchCFiltersStart (a
// literal reading of the plan brief's pseudocode) would be a NO-OP whenever
// Through already sits at/above the checkpoint height (the normal case here,
// since the header chain advanced past it before the mismatch/exhaustion
// began): max(Through+1, Start) would still resolve to the OLD, unverified
// value. The fix snaps BOTH fields -- Start=cp->height,
// Through=cp->height-1 -- the same idiom every other "park the cursor at X"
// site in this file already uses (_BRPeerManagerReanchorAtFloorLocked,
// BRPeerManagerSnapAutoFetchThroughToScanFrontier's C-1 snap, the abandon-band
// snap at :7407-7408). test_budget_exhausted_parks_at_checkpoint_and_surfaces
// asserts BOTH fields land on the trusted checkpoint height, not just Start.
//
// CRITICAL DISTINCTION this KAT also proves: the new guard checks ONLY
// `cfReanchorCount >= CF_CONTINUITY_REANCHOR_MAX`, never quorum status. That is
// deliberate, not an oversight -- the below-quorum case (budget still
// available) may still recover as more agreeing peers arrive, so surfacing
// there would false-alarm; but once the budget is exhausted, NEITHER re-anchor
// gate can ever fire again regardless of how many peers eventually agree, so
// there is nothing left to wait for and the fix must act on the very FIRST
// mismatch that reaches the exhaustion branch, even from a single peer with no
// quorum at all. Rounds 1-3 below drive three legitimate, unvetoed
// quorum-majority re-anchors (cfReanchorCount 0->1->2->3) and assert the new
// guard stays inert throughout (autoFetchCFiltersStart tracks the ordinary
// re-anchor-at-floor value, abandonedBelow stays 0) -- proving the guard does
// NOT fire merely because a mismatch was collected while budget was still
// available. Only round 4's single-peer mismatch, submitted AFTER
// cfReanchorCount has already reached the cap, triggers the fix.
//
// Approach: same "#include a .c for its statics" idiom as
// cf_checkpoint_quorum_kat (the sibling KAT this file's helpers are copied
// from almost verbatim) -- a real BRPeerManager (BRPeerManagerNew) + real
// BRWallet (BRWalletNew) are built; _peerRelayedCFHeaders is driven directly
// with SYNTHETIC BRPeerNew() peers and hand-built batches -- no sockets, no
// real network peers. BOTH BRPeer.c (for the private BRPeerContext -- there is
// no public setter for connect status, and the quorum path's majority
// denominator, _BRPeerManagerConnectedFilterPeerCount, gates on
// BRPeerConnectStatus(p)==Connected) AND BRPeerManager.c (for the exhaustion
// decision itself and the otherwise-opaque BRPeerManagerStruct /
// BRPeerCallbackInfo definitions) are #include-d directly -- neither is also
// passed as a separate compilation unit in run.sh (that would be a duplicate-
// symbol link error). BRPeerNew's calloc leaves ctx->socket == -1, so every
// real, unwrapped send path these peers exercise (the active cfheaders probe,
// the post-reanchor "kick recovery" request) safely no-ops (ENOTCONN) instead
// of touching a real socket or crashing -- same reasoning
// cf_checkpoint_quorum_kat/cf_checkpoint_enforce_kat/cf_scan_ledger_drive_kat's
// file headers document for BRPeerNew() peers generally.
//
// NO --wrap SEAM NEEDED (unlike cf_checkpoint_veto_kat/cf_checkpoint_quorum_kat's
// test_quorum_veto_checkpoint_confirmed). Task 6's new code calls
// BRCFHighestCheckpointAtOrBelow(tip) -- a pure height->table lookup -- and
// never calls _BRPeerManagerCheckpointConfirmsOurChainLocked (the accessor
// that reads our own chain's header and compares it against a pinned 256-bit
// filterHeader value, which is what needed the SHA256d-preimage escape hatch
// in Tasks 2-5's KATs). This KAT's chain is anchored strictly ABOVE the
// highest pinned checkpoint (primeAboveTopCheckpoint, copied from
// cf_checkpoint_quorum_kat) purely so the Task-4 checkpoint VETO never
// applies during rounds 1-3's legitimate quorum re-anchors -- exactly the
// same reason that helper exists in the sibling file. No hash needs to match
// anything for the Task 6 decision itself: cp->height is a plain uint32_t
// comparison, immune to a preimage constraint.
//
// ---- ONE NAME COLLISION between the two included .c files ----
// Both BRPeer.c and BRPeerManager.c separately define a file-static
// `void _dummyThreadCleanup(void *info)`. Combined into a single translation
// unit via #include, that's a duplicate-definition compile error. Renamed via
// a scoped preprocessor #define around BRPeer.c's #include only (identical to
// cf_checkpoint_quorum_kat) -- no production source file is modified.
//
// ==== RED-BEFORE-GREEN GATE ====
// main() below runs all tests with NO #ifdef branching -- every check()
// asserts the FIXED (never-brick) outcome unconditionally, in source
// identical between builds. run.sh compiles this exact file twice:
//   RED   -DCF_NEVERBRICK_UNFIXED compiles the entire Task 6 block out of
//         BRPeerManager.c (#ifndef CF_NEVERBRICK_UNFIXED), restoring the
//         pre-Task-6 shape: the exhaustion branch is a bare log + unlock +
//         return. test_budget_exhausted_parks_at_checkpoint_and_surfaces's
//         post-round-4 checks -- which expect autoFetchCFiltersStart/Through
//         snapped to the trusted checkpoint height and abandonedBelow raised
//         above 0 -- FAIL (the cursor is left wherever round 3's ordinary
//         re-anchor-at-floor left it, and abandonedBelow stays 0), and the
//         binary exits nonzero.
//   GREEN the production shape (no flag) must pass every check in every test
//         and exit 0.
//
// Not compiled into the Android NDK build (host-only KAT, matches every other
// native/src/test/host/*/run.sh convention in this repo).

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
// very next manager call in production would deadlock. Doubles as this KAT's
// "no crash / no deadlock" evidence: a held lock here means the exhaustion
// path took a path that didn't unlock, which would wedge the very next real
// manager call in production.
static void assertLockNotHeld(BRPeerManager *manager, const char *ctx)
{
    int r = pthread_mutex_trylock(&manager->lock);
    check(r == 0, ctx);
    if (r == 0) pthread_mutex_unlock(&manager->lock);
}

// dummy BRMerkleBlock, same pattern cf_checkpoint_quorum_kat/cf_confirm_kat use:
// only ->height matters (BRMerkleBlockNew calloc's ->prevBlock to the zero
// hash, so _BRPeerManagerBlockFloor's descent stops immediately at this
// height with zero hops -- no genesis-linked chain required).
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
// address). Copied verbatim from cf_checkpoint_quorum_kat.
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

// Shared setup, copied from cf_checkpoint_quorum_kat's primeAboveTopCheckpoint:
// anchors the compact-filter chain strictly above the highest pinned mainnet
// checkpoint (read from the real table, not hand-typed) so Task 4's checkpoint
// veto never applies -- any re-anchor result in this file is therefore
// attributable purely to the Task 5 quorum decision / Task 6 exhaustion
// decision, never a coincidental veto.
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

// ---- GREEN / RED falsifying test: drives cfReanchorCount to
// CF_CONTINUITY_REANCHOR_MAX (3) via three real, unvetoed quorum-majority
// re-anchors (rounds 1-3, same mechanics as
// cf_checkpoint_quorum_kat's test_quorum_majority_reanchors), then submits a
// FOURTH mismatch (round 4) from a single peer AFTER the budget is spent. ---
static void test_budget_exhausted_parks_at_checkpoint_and_surfaces(void)
{
    BRWallet *wallet = makeWallet();
    BRPeerManager *manager = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(manager, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    uint32_t floorHeight = 24000000;  // well above the highest pinned checkpoint (23,800,000)
    BRMerkleBlock *floorBlock = dummyBlock(floorHeight, 0xE0);
    BRSetAdd(manager->blocks, floorBlock);
    manager->lastBlock = floorBlock;

    uint32_t topCpHeight = BRMainNetCFCheckpoints[BRMainNetCFCheckpointsCount - 1].height;

    // Three peers, reused across every round (a re-anchor never disconnects
    // anyone in production) so the connected-filter-peer denominator stays a
    // constant 3 for the whole test -- 3-of-3 agreeing clears both
    // CF_CONTINUITY_REANCHOR_FLOOR (3) and a strict majority of 3 every round.
    BRPeer *p1 = addConnectedFilterPeer(manager, 0x01, 20001);
    BRPeer *p2 = addConnectedFilterPeer(manager, 0x02, 20002);
    BRPeer *p3 = addConnectedFilterPeer(manager, 0x03, 20003);
    check(_BRPeerManagerConnectedFilterPeerCount(manager) == 3,
          "neverbrick: sanity -- exactly 3 connected filter peers, held constant across every round");
    BRPeerCallbackInfo info1 = { .peer = p1, .manager = manager, .hash = UINT256_ZERO };
    BRPeerCallbackInfo info2 = { .peer = p2, .manager = manager, .hash = UINT256_ZERO };
    BRPeerCallbackInfo info3 = { .peer = p3, .manager = manager, .hash = UINT256_ZERO };

    check(manager->cfReanchorCount == 0, "neverbrick: sanity -- starts with a fresh re-anchor budget");
    check(BRPeerManagerAbandonedBelow(manager) == 0, "neverbrick: sanity -- nothing abandoned yet");

    // ---- Rounds 1-3: three legitimate, unvetoed quorum-majority re-anchors,
    // spending the whole CF_CONTINUITY_REANCHOR_MAX==3 budget. Each round primes
    // a fresh chain above the top checkpoint (the prior round's reanchor tore
    // the old one down to NULL), then all 3 peers submit a batch with a shared,
    // DIFFERENT-per-round wrong prevFilterHeader. ----
    for (int round = 1; round <= 3; round++) {
        primeAboveTopCheckpoint(manager, (uint8_t)(0xA0 + round), (uint8_t)(0x30 + round * 6));

        UInt256 batch[3];
        for (int i = 0; i < 3; i++) batch[i] = u256_fill((uint8_t)(0x60 + round * 3 + i));
        UInt256 sharedPrev = u256_fill((uint8_t)(0xD0 + round));  // distinct per round

        _peerRelayedCFHeaders(&info1, FILTER_TYPE_BASIC, u256_fill((uint8_t)(0xE1 + round * 3)), sharedPrev, batch, 3);
        _peerRelayedCFHeaders(&info2, FILTER_TYPE_BASIC, u256_fill((uint8_t)(0xE2 + round * 3)), sharedPrev, batch, 3);

        // Before the 3rd (quorum-clearing) call: the guard this KAT exists to
        // test must NOT have fired yet -- budget is still available (cfReanchorCount
        // < MAX going into this round), so an exhaustion-park here would be
        // premature/false-alarming even though 2 agreeing disagreers are already
        // collected. This is the "distinguish the two" boundary from the file
        // header, checked on every round, not just asserted once.
        check(BRPeerManagerAbandonedBelow(manager) == 0,
              "neverbrick: round in progress -- budget not yet exhausted, guard stays inert (abandonedBelow==0)");

        _peerRelayedCFHeaders(&info3, FILTER_TYPE_BASIC, u256_fill((uint8_t)(0xE3 + round * 3)), sharedPrev, batch, 3);

        check(manager->cfReanchorCount == (uint32_t)round,
              "neverbrick: round's 3rd agreeing disagreer (majority of 3) fires a real re-anchor");
        check(manager->compactFilterChain == NULL,
              "neverbrick: round's re-anchor tore the chain down (ordinary re-anchor-at-floor, not the exhaustion path)");
        check(manager->autoFetchCFiltersStart == floorHeight,
              "neverbrick: round's ordinary re-anchor snapped the cursor to the BLOCK FLOOR (not a checkpoint -- "
              "budget not exhausted yet, this is _BRPeerManagerReanchorAtFloorLocked's own snap)");
        check(manager->misbehavinCount == 0,
              "neverbrick: no peer banned -- these are legitimate majority re-anchors, not vetoed liars");
        assertLockNotHeld(manager, "neverbrick: lock released after each round's 3rd call");
    }

    check(manager->cfReanchorCount == CF_CONTINUITY_REANCHOR_MAX,
          "neverbrick: sanity -- budget is now fully spent (cfReanchorCount == CF_CONTINUITY_REANCHOR_MAX)");
    check(BRPeerManagerAbandonedBelow(manager) == 0,
          "neverbrick: sanity -- still nothing abandoned -- no mismatch has reached the exhaustion branch yet "
          "(reaching cfReanchorCount==MAX via a SUCCESSFUL re-anchor call is not the same event as a NEW mismatch "
          "arriving while the budget is already spent)");

    // ---- Round 4: budget is now exhausted. Prime one final chain above the
    // top checkpoint (giving a real, non-checkpoint-exact tip: topCpHeight+5)
    // and submit ONE mismatch from a SINGLE peer -- below CF_CONTINUITY_REANCHOR_FLOOR
    // (bestAgree==1) and therefore NOT a quorum by itself. Proves the fix acts
    // purely on budget exhaustion, not on quorum status. ----
    primeAboveTopCheckpoint(manager, 0xB9, 0x90);
    uint32_t tip = BRCompactFilterChainNextHeight(manager->compactFilterChain) - 1;
    check(tip == topCpHeight + 5, "neverbrick: sanity -- round 4's primed tip is topCpHeight + 5");
    const BRCFCheckpoint *cp = BRCFHighestCheckpointAtOrBelow(tip);
    check(cp != NULL && cp->height == topCpHeight,
          "neverbrick: sanity -- the trusted checkpoint at/below round 4's tip is the real top table entry");

    UInt256 batch4[3];
    for (int i = 0; i < 3; i++) batch4[i] = u256_fill((uint8_t)(0x99 + i));
    // Deliberately peer-controlled/adversarial-looking value -- proves the
    // parked cursor below is NOT derived from anything this peer sent.
    UInt256 peerSuppliedPrev = u256_fill(0xFF);

    _peerRelayedCFHeaders(&info1, FILTER_TYPE_BASIC, u256_fill(0x77), peerSuppliedPrev, batch4, 3);

    check(manager->cfReanchorCount == CF_CONTINUITY_REANCHOR_MAX,
          "neverbrick: cfReanchorCount did NOT grow past the cap -- the exhaustion path never re-anchors");
    check(manager->compactFilterChain != NULL,
          "neverbrick: compactFilterChain was NOT torn down -- this is a park+surface, not a re-anchor");
    check(manager->autoFetchCFiltersStart == cp->height,
          "neverbrick: autoFetchCFiltersStart snapped to the TRUSTED checkpoint height (compiled-in table value)");
    check(manager->autoFetchCFiltersStart == topCpHeight,
          "neverbrick: ...and that value is exactly the real BRMainNetCFCheckpoints top entry, never peerSuppliedPrev");
    check(manager->autoFetchCFiltersThrough == cp->height - 1,
          "neverbrick: autoFetchCFiltersThrough snapped to checkpoint-1 -- THIS is the field that actually governs "
          "reqStart = autoFetchCFiltersThrough + 1 at every forward-fetch request site; Start alone would have been "
          "a no-op here since Through already sat at floorHeight-1 (24000000-1), well above the checkpoint");
    check(BRPeerManagerAbandonedBelow(manager) == tip + 1,
          "neverbrick: abandonedBelow raised to tip+1 -- the unverifiable band [checkpoint..tip] is surfaced");
    check(BRPeerManagerAbandonedBelow(manager) > 0,
          "neverbrick: abandonedBelow > 0 -- a recoverable band now exists ('Scan for missing transactions' reachable)");
    check(manager->cfAbandonedHeightsTotal == (size_t)(tip + 1 - cp->height),
          "neverbrick: cfAbandonedHeightsTotal accumulated exactly the surfaced band's size (6 heights)");
    check(manager->misbehavinCount == 0,
          "neverbrick: no peer banned -- exhaustion recovery punishes nobody, it just stops trusting an unverifiable chain");
    assertLockNotHeld(manager, "neverbrick: lock released after round 4's exhaustion park+surface");

    // ---- Boundedness / not-spinning: two MORE mismatches after exhaustion
    // (different peers, so cfDisagreedCount keeps accumulating too) must NOT
    // grow anything further -- the exhaustion decision is idempotent on an
    // unchanged chain/tip, proving this is a bounded, one-shot park, not an
    // unbounded re-surface loop. ----
    uint32_t abandonedBeforeRepeat = BRPeerManagerAbandonedBelow(manager);
    size_t totalBeforeRepeat = manager->cfAbandonedHeightsTotal;

    _peerRelayedCFHeaders(&info2, FILTER_TYPE_BASIC, u256_fill(0x78), peerSuppliedPrev, batch4, 3);
    _peerRelayedCFHeaders(&info3, FILTER_TYPE_BASIC, u256_fill(0x79), peerSuppliedPrev, batch4, 3);

    check(manager->cfReanchorCount == CF_CONTINUITY_REANCHOR_MAX,
          "neverbrick: bounded -- 2 more post-exhaustion mismatches still did not grow cfReanchorCount");
    check(manager->autoFetchCFiltersStart == cp->height && manager->autoFetchCFiltersThrough == cp->height - 1,
          "neverbrick: bounded -- cursor stays pinned at the same checkpoint, not re-derived or drifting");
    check(BRPeerManagerAbandonedBelow(manager) == abandonedBeforeRepeat,
          "neverbrick: bounded -- abandonedBelow did NOT advance further on repeat exhausted mismatches (no re-request growth)");
    check(manager->cfAbandonedHeightsTotal == totalBeforeRepeat,
          "neverbrick: bounded -- cfAbandonedHeightsTotal did NOT grow further (idempotent, not an unbounded surface loop)");
    check(manager->misbehavinCount == 0,
          "neverbrick: bounded -- still nobody banned after the repeat mismatches");
    assertLockNotHeld(manager, "neverbrick: lock released after the repeat post-exhaustion calls (no deadlock, no crash)");

    BRPeerManagerFree(manager);
    BRWalletFree(wallet);
    printf("test_budget_exhausted_parks_at_checkpoint_and_surfaces: done\n");
}

int main(void)
{
    // Deliberately NO #ifdef branching here: every test asserts the FIXED
    // outcome unconditionally, in source identical between the RED and GREEN
    // builds. run.sh builds this exact file twice -- see file header for
    // exactly what the RED arm (-DCF_NEVERBRICK_UNFIXED) is expected to falsify.
    test_budget_exhausted_parks_at_checkpoint_and_surfaces();

    printf(g_fail == 0 ? "\ncf_checkpoint_neverbrick_kat: ALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
