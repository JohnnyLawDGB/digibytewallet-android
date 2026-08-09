/* checkpoint_stub_free_guard_kat — P1: a checkpoint-stub REPLACEMENT must never free the stub
 * unless manager->checkpoints was actually repointed to the replacement.
 *
 * THE DEFECT. A hardcoded checkpoint is a hash-only stub that BRPeerManagerNewEx inserts as the
 * SAME BRMerkleBlock * into TWO sets keyed differently:
 *
 *     manager->blocks       -- keyed by HASH  (BRMerkleBlockHash / BRMerkleBlockEq)
 *     manager->checkpoints  -- keyed by HEIGHT (_BRBlockHeightHash / _BRBlockHeightEq)
 *
 * When a persisted/relayed real header replaces that stub (_BRPeerManagerInstallSavedBlock,
 * and its twin at the equal-hash relay site in _peerRelayedBlock), the code:
 *
 *   1. BRSetAdd(manager->blocks, incoming)       -- displaces the stub by HASH. Always succeeds
 *                                                    when incoming->blockHash == stub->blockHash.
 *   2. BRSetGet(manager->checkpoints, replaced)   -- confirms `replaced` (== stub) really sits in
 *      == replaced                                  checkpoints (pointer identity, found via the
 *                                                    STUB's own, correct height).
 *   3. BRSetAdd(manager->checkpoints, incoming)   -- repoints checkpoints, but keyed on
 *                                                    INCOMING's height, not the stub's.
 *   4. BRMerkleBlockFree(replaced)                -- frees the stub.
 *
 * Step 3 only displaces the stub from checkpoints when incoming->height == stub->height (same
 * key). If a corrupt persisted height (saved_blocks_deserialize.h assigns block->height straight
 * from the blob, no cross-check) disagrees with the resident stub's real height, step 3 inserts
 * `incoming` under a DIFFERENT bucket, `replaced` (the stub) is untouched in checkpoints, and
 * step 4 frees it anyway -- manager->checkpoints is left holding a dangling pointer. The only
 * guard was assert(checkpoint == replaced) -- a no-op under NDEBUG (release builds: see
 * native/CMakeLists.txt, AGP release => -O2 -DNDEBUG). Nothing ever removes from
 * manager->checkpoints, so the dangling entry persists for the session; the next
 * _BRPeerManagerVerifyBlock probe (BRSetGet(manager->checkpoints, block) -> _BRBlockHeightEq)
 * dereferences ->height on freed memory -- the same 192-byte (sizeof(BRMerkleBlock)) checkpoints-
 * set UAF class this project has already ASan-confirmed once (CHECKPOINT_ALIAS_UNFIXED).
 *
 * THE FIX. Gate the free on the repoint having ACTUALLY landed, at runtime: only free `replaced`
 * (or `b`, at the equal-hash site) when the checkpoints BRSetAdd genuinely displaced that same
 * object. If the heights disagree, skip the free -- the stub LEAKS (owned solely by
 * manager->checkpoints, never touched again) instead of dangling. A leak is safe; a dangling
 * pointer in a set that is read on every relayed header is not.
 *
 * TWO SITES, BOTH COVERED HERE:
 *   Site 1 -- _BRPeerManagerInstallSavedBlock (the resume/extend-chain path): exercised directly.
 *   Site 2 -- the equal-hash "we already have the block" branch inside _peerRelayedBlock: driven
 *             through the real function with a hand-built duplicate-hash relay.
 *
 * ==== RED-BEFORE-GREEN GATE ====================================================================
 *   * UNFIXED (-DCHECKPOINT_STUB_FREE_UNGUARDED_UNFIXED): both sites repoint-by-height and free
 *     unconditionally (assert-only guarded). ASan MUST report heap-use-after-free when the
 *     surviving checkpoints entry is read back. This build MUST FAIL.
 *   * FIXED (no flag): the runtime repoint-success guard is live at both sites. MUST PASS -- the
 *     stub survives, still resolvable at its correct height, with its original height intact.
 *
 * DETERMINISTIC -- no threads, no timing. Fails 100% of the time when unfixed.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "BRPeerManager.c"

static int g_fail = 0;
static void check(int cond, const char *what) {
    printf("  [%s] %s\n", cond ? "PASS" : "FAIL", what);
    if (! cond) g_fail = 1;
}

// Picks the highest-height resident checkpoint stub and hands back its identity (pointer, height,
// hash) BEFORE anything under test runs. Fails the caller's setup check if there are no
// checkpoints at all (would make the whole scenario vacuous).
static BRMerkleBlock *pickStub(BRPeerManager *m, uint32_t *outHeight, UInt256 *outHash)
{
    size_t cpCount = BRSetCount(m->checkpoints);
    if (cpCount == 0) return NULL;

    BRMerkleBlock **cps = calloc(cpCount, sizeof(*cps));
    BRSetAll(m->checkpoints, (const void **)cps, cpCount);

    BRMerkleBlock *stub = cps[0];
    for (size_t i = 1; i < cpCount; i++) if (cps[i]->height > stub->height) stub = cps[i];

    *outHeight = stub->height;
    *outHash   = stub->blockHash;
    free(cps);
    return stub;
}

// True if `height` collides with some OTHER real hardcoded checkpoint -- the fabricated
// mismatched height must land in an empty bucket for the test to isolate the defect cleanly
// (a collision would make _BRPeerManagerVerifyBlock's checkpoint-hash check, or the repoint
// itself, land on a THIRD object instead of proving nothing swapped).
static int collidesWithRealCheckpoint(uint32_t height)
{
    for (size_t i = 0; i < BRMainNetParams.checkpointsCount; i++)
        if (BRMainNetParams.checkpoints[i].height == height) return 1;
    return 0;
}

// ---- Site 1: _BRPeerManagerInstallSavedBlock (resume / extend-chain install path) ------------
static void test_install_saved_block_height_mismatch(BRWallet *wallet)
{
    printf("\n=== test_install_saved_block_height_mismatch (site 1: _BRPeerManagerInstallSavedBlock) ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    check(m != NULL, "manager allocated");
    if (! m) return;
    m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;

    uint32_t stubHeight; UInt256 stubHash;
    BRMerkleBlock *stub = pickStub(m, &stubHeight, &stubHash);
    check(stub != NULL, "manager has at least one hardcoded checkpoint to target");
    if (! stub) { BRPeerManagerFree(m); return; }

    // Corrupted-height "persisted header": HASH matches the checkpoint (so BRSetAdd displaces
    // the stub in manager->blocks -- hash-keyed), HEIGHT does not (so the checkpoints repoint,
    // height-keyed, lands in a different bucket).
    uint32_t badHeight = stubHeight + 90000000u;   // nowhere near any real DGB/checkpoint height
    check(! collidesWithRealCheckpoint(badHeight), "setup: fabricated height hits an empty checkpoints bucket");

    BRMerkleBlock *incoming = BRMerkleBlockNew();
    incoming->blockHash = stubHash;
    incoming->height    = badHeight;
    incoming->timestamp = stub->timestamp;

    _BRPeerManagerInstallSavedBlock(m, incoming);

    check(BRSetGet(m->blocks, &stubHash) == incoming,
          "manager->blocks was repointed to the incoming header (hash-keyed swap always lands)");

    // THE READ THAT CRASHES ON A DANGLING checkpoints ENTRY: exactly what
    // _BRPeerManagerVerifyBlock does on every relayed header (BRSetGet -> _BRBlockHeightEq reads
    // ->height on whatever is resident). If the unfixed code freed the stub while checkpoints
    // still held it at stubHeight's bucket, this dereferences freed memory.
    BRMerkleBlock heightProbe; memset(&heightProbe, 0, sizeof(heightProbe));
    heightProbe.height = stubHeight;
    BRMerkleBlock *found = BRSetGet(m->checkpoints, &heightProbe);

    check(found == stub, "checkpoints[stubHeight] still resolves to the ORIGINAL stub object (not freed, not swapped)");
    check(found && found->height == stubHeight, "the surviving stub reports its correct, unmodified height");

    BRPeerManagerFree(m);
}

// ---- Site 2: the equal-hash "we already have the block" branch in _peerRelayedBlock ----------
static void test_relayed_equal_hash_height_mismatch(BRWallet *wallet)
{
    printf("\n=== test_relayed_equal_hash_height_mismatch (site 2: _peerRelayedBlock equal-hash path) ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    check(m != NULL, "manager allocated");
    if (! m) return;
    m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;

    uint32_t stubHeight; UInt256 stubHash;
    BRMerkleBlock *stub = pickStub(m, &stubHeight, &stubHash);
    check(stub != NULL, "manager has at least one hardcoded checkpoint to target");
    if (! stub) { BRPeerManagerFree(m); return; }

    // A decoy "prev" header, resident in manager->blocks, whose height is deliberately offset so
    // that prev->height + 1 (what _peerRelayedBlock stamps onto the relayed block -- it always
    // overwrites block->height from the prev lookup) disagrees with the checkpoint's real height.
    uint32_t badHeight = stubHeight + 90000000u;
    check(! collidesWithRealCheckpoint(badHeight), "setup: fabricated height hits an empty checkpoints bucket");

    BRMerkleBlock *prev = BRMerkleBlockNew();
    prev->height    = badHeight - 1u;
    prev->blockHash = UINT256_ZERO;
    prev->blockHash.u32[0] = 0xC0FFEEu;
    prev->blockHash.u32[1] = badHeight;            // distinguish from any other synthetic hash
    prev->timestamp = stub->timestamp;
    BRSetAdd(m->blocks, prev);

    check(! UInt256Eq(prev->blockHash, m->lastBlock->blockHash),
          "setup: decoy prev is not manager->lastBlock (would hit the extend-main-chain branch instead)");

    // The "relayed" duplicate: same hash as the checkpoint (so it is already resident by hash --
    // BRSetContains true, taking the equal-hash branch), chained onto the decoy prev so it is not
    // an orphan. _peerRelayedBlock overwrites ->height to prev->height+1 == badHeight before this
    // block is ever compared against manager->checkpoints.
    BRMerkleBlock *incoming = BRMerkleBlockNew();
    incoming->blockHash = stubHash;
    incoming->prevBlock = prev->blockHash;
    incoming->timestamp = stub->timestamp + 1u;

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x51;
    pa->port = 12051;
    array_add(m->connectedPeers, pa);
    BRPeerCallbackInfo info = { pa, m, UINT256_ZERO };

    _peerRelayedBlock(&info, incoming);

    check(BRSetGet(m->blocks, &stubHash) == incoming,
          "manager->blocks was repointed to the relayed duplicate (hash-keyed swap always lands)");

    // THE READ THAT CRASHES ON A DANGLING checkpoints ENTRY -- same probe as site 1.
    BRMerkleBlock heightProbe; memset(&heightProbe, 0, sizeof(heightProbe));
    heightProbe.height = stubHeight;
    BRMerkleBlock *found = BRSetGet(m->checkpoints, &heightProbe);

    check(found == stub, "checkpoints[stubHeight] still resolves to the ORIGINAL stub object (not freed, not swapped)");
    check(found && found->height == stubHeight, "the surviving stub reports its correct, unmodified height");

    BRPeerManagerFree(m);
}

int main(void)
{
#ifdef CHECKPOINT_STUB_FREE_UNGUARDED_UNFIXED
    printf("ARM: UNFIXED (-DCHECKPOINT_STUB_FREE_UNGUARDED_UNFIXED)\n");
#else
    printf("ARM: FIXED\n");
#endif

    BRMasterPubKey mpk;
    memset(&mpk, 0, sizeof(mpk));
    /* Non-zero fingerprint: BRBIP32PubKey asserts the key is not BR_MASTER_PUBKEY_NONE, so an
     * all-zero mpk aborts in wallet setup -- correctly rejected as "died in setup, not a red
     * arm" rather than mistaken for the defect. */
    mpk.fingerPrint = 0x11223344;
    BRWallet *wallet = BRWalletNew(NULL, 0, mpk);
    if (! wallet) { printf("SKIP: wallet alloc failed\n"); return 0; }

    test_install_saved_block_height_mismatch(wallet);
    test_relayed_equal_hash_height_mismatch(wallet);

    if (g_fail) { printf("\ncheckpoint_stub_free_guard_kat: FAIL\n"); return 1; }
    printf("\ncheckpoint_stub_free_guard_kat: PASS\n");
    return 0;
}
