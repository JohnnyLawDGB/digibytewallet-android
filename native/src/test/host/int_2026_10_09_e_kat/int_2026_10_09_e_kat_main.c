// Host KAT for INT-2026-10-09-E: at difficulty level 2 a header off the main chain that cannot be judged because its
// history stops at the resident floor is refused, and the peer treated as misbehaving; a header that extends the tip
// keeps the existing skip; a chain anchored at a checkpoint can still win over the one taken there first; a short
// reorg near the tip is judged and accepted.
//
// WHAT IT PROVES.
//   RED (fail in the -DINT_2026_10_09_E_UNFIXED build, pass in the shipped build):
//     unjudged_fork_refused        The resident chain starts at a block whose parent is not held (as after a
//                                  restart, when the oldest kept header's parent is gone). A header whose parent is
//                                  30 blocks above that start, so below the tip, has too little history under it to
//                                  be judged. It carries valid proof of work at its own target. It is refused: not
//                                  resident, the tip unchanged, the peer gets the misbehaving penalty.
//   GUARD (pass in both builds):
//     unjudged_extends_tip_kept    The next real header on top of that same short chain cannot be judged either; it
//                                  extends the tip, so it keeps the skip and is accepted as the new tip.
//     unjudged_known_header_kept   A header already held, sent again, is not refused and costs the peer nothing.
//     honest_chain_wins_after_unjudged_first
//                                  A wallet starting at a checkpoint without a difficulty context takes first a
//                                  header on it that cannot be judged and is not the real chain's. The real chain,
//                                  arriving beside it, still grows and wins; no peer is penalised. (A refusal of
//                                  every unjudgeable header off the tip would lock the wallet onto the first one.)
//     short_reorg_near_tip         Real testnet26 headers up to a tip, then a 3-header branch off the block two
//                                  below the tip (a 2-block reorg). Each branch header has its full history
//                                  resident, so each is judged (not skipped) and matches; the branch becomes the
//                                  main chain and the peer is not penalised.
//
// ENTRY LAYER. Headers go through the real BRPeer.c dispatch (_BRPeerAcceptMessage, "headers") into the manager's own
// _peerRelayedBlock, as in ../header_diff_v4_kat. The resident chain comes only from the production constructor (one
// real header passed in as a persisted block) and from the headers delivered after it.
//
// FIXTURES. The real header ranges are ../header_diff_v4_kat/header_diff_v4_vectors.inc (mainnet near the tip, and
// testnet26). The branch headers are int_2026_10_09_e_vectors.inc: each meets its own target by proof of work, and
// the reorg headers carry exactly the target the wallet computes from their resident ancestors (odo, at or near the
// network's easiest target on that range, so a nonce for each could be found). That file is written by this
// program's `gen` mode (see run.sh: it is too slow to run in the KAT; the KAT checks every row it delivers).
//
// One case per process: `int_2026_10_09_e_kat <case>` prints "RESULT <case> pass|fail".
// Host-only KAT, not compiled into the Android NDK build.

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <inttypes.h>
#include <pthread.h>

#define _dummyThreadCleanup _dummyThreadCleanup_brpeer
#include "BRPeer.c"
#undef _dummyThreadCleanup

#include "BRPeerManager.c"

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

#include "header_diff_v4_vectors.inc"
#ifndef INT_2026_10_09_E_GEN
#include "int_2026_10_09_e_vectors.inc"
#endif

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (! c) g_fail++; }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

static void hexBytes(uint8_t *out, const char *h, size_t n)
{
    for (size_t i = 0; i < n; i++) { unsigned v; sscanf(h + 2*i, "%2x", &v); out[i] = (uint8_t)v; }
}

// ---- fixture (as in ../header_diff_v4_kat) --------------------------------------------------------------------------
typedef struct {
    BRWallet *w;
    BRPeerManager *m;
    BRPeerCallbackInfo *info;
    BRPeer *peer;
    uint8_t (*rows)[80];
    uint32_t firstHeight;
    size_t count;
} Fx;

typedef struct { uint32_t match, skip, mismatch, pow; } Counts;

static Counts counts(void)
{
    Counts c = { BRMerkleBlockDiffCount(BR_DIFF_MATCH), BRMerkleBlockDiffCount(BR_DIFF_SKIP),
                 BRMerkleBlockDiffCount(BR_DIFF_MISMATCH), BRMerkleBlockPoWMismatchCount() };
    return c;
}

static Counts delta(Counts a, Counts b)
{
    Counts d = { b.match - a.match, b.skip - a.skip, b.mismatch - a.mismatch, b.pow - a.pow };
    return d;
}

static uint8_t (*loadRange(const DiffRange *r))[80]
{
    uint8_t (*rows)[80] = calloc(r->count, 80);
    for (size_t i = 0; i < r->count; i++) hexBytes(rows[i], r->hex[i], 80);
    return rows;
}

static const DiffRange *range(const char *name)
{
    for (size_t i = 0; i < sizeof(kDiffRanges)/sizeof(*kDiffRanges); i++)
        if (strcmp(kDiffRanges[i].name, name) == 0) return &kDiffRanges[i];
    return NULL;
}

// a manager on `params` and one compact-filter download peer whose headers go to the manager's own relay path. With
// `seed` set, the resident chain is the range's first header, passed in as a persisted block, so its parent is not
// held (the resident floor); otherwise the manager starts where its constructor puts it for `earliestKeyTime`.
static void fxInitEx(Fx *f, const DiffRange *r, const BRChainParams *params, uint32_t earliestKeyTime, int seed1)
{
    memset(f, 0, sizeof(*f));
    BRSetNetwork(r->testnet);
    f->rows = loadRange(r);
    f->firstHeight = r->firstHeight;
    f->count = r->count;

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    f->w = BRWalletNew(NULL, 0, mpk);
    assert(f->w != NULL);

    BRMerkleBlock *first = BRMerkleBlockParse(f->rows[0], 80);
    first->height = r->firstHeight;
    BRMerkleBlock *blocks[1] = { first };
    if (! seed1) BRMerkleBlockFree(first);
    f->m = BRPeerManagerNew(params, f->w, earliestKeyTime, seed1 ? blocks : NULL, seed1 ? 1 : 0, NULL, 0);
    assert(f->m != NULL);
    assert(! seed1 || (f->m->lastBlock && f->m->lastBlock->height == r->firstHeight));
    f->m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;

    f->info = calloc(1, sizeof(*f->info));
    f->info->manager = f->m;
    f->info->peer = f->peer = BRPeerNew(f->m->params->magicNumber);
    f->peer->address = (UInt128) { .u8 = { 0,0,0,0,0,0,0,0,0,0,0xff,0xff, 198,51,100,9 } };
    f->peer->port = f->m->params->standardPort;
    BRPeerSetCallbacks(f->peer, f->info, _peerConnected, _peerDisconnected, _peerRelayedPeers,
                       _peerRelayedTx, _peerHasTx, _peerRejectedTx, _peerRelayedBlock, _peerRelayedBlockTxns,
                       _peerRelayedBlockInv, _peerDataNotfound, _peerSetFeePerKb, _peerRequestedTx,
                       _peerNetworkIsReachable, _peerThreadCleanup);
    BRPeerSetCompactFiltersOnly(f->peer, 1);
    MGR_LOCK(f->m);
    array_add(f->m->peers, *f->peer);
    array_add(f->m->connectedPeers, f->peer);
    f->m->downloadPeer = f->peer;
    MGR_UNLOCK(f->m);
}

static void fxInit(Fx *f, const DiffRange *r)
{
    fxInitEx(f, r, r->testnet ? &BRTestNetParams : &BRMainNetParams, 0, 1);
}

static void fxFree(Fx *f)
{
    MGR_LOCK(f->m);
    for (size_t i = array_count(f->m->connectedPeers); i > 0; i--)
        if (f->m->connectedPeers[i - 1] == f->peer) array_rm(f->m->connectedPeers, i - 1);
    f->m->downloadPeer = NULL;
    MGR_UNLOCK(f->m);
    BRPeerFree(f->peer);
    free(f->info);
    BRPeerManagerFree(f->m);
    BRWalletFree(f->w);
    free(f->rows);
}

// one "headers" message carrying rows[from..to) through the real dispatch; returns the peer layer's verdict
static int deliverRows(Fx *f, uint8_t (*rows)[80], size_t from, size_t to)
{
    size_t n = to - from, off = 0;
    uint8_t vi[9];
    size_t viLen = BRVarIntSet(vi, sizeof(vi), n);
    uint8_t *msg = malloc(viLen + 81*n);

    memcpy(msg, vi, viLen); off = viLen;
    for (size_t i = from; i < to; i++) { memcpy(&msg[off], rows[i], 80); msg[off + 80] = 0; off += 81; }
    int r = _BRPeerAcceptMessage(f->peer, msg, off, MSG_HEADERS);
    free(msg);
    return r;
}

// the range's rows[from..to) in messages of at most 100 headers; returns 1 if every message was accepted
static int deliverRange(Fx *f, size_t from, size_t to)
{
    int ok = 1;

    for (size_t i = from; i < to; i += 100) ok &= deliverRows(f, f->rows, i, (i + 100 < to) ? i + 100 : to);
    return ok;
}

static uint32_t tipHeight(Fx *f)
{
    MGR_LOCK(f->m);
    uint32_t h = f->m->lastBlock->height;
    MGR_UNLOCK(f->m);
    return h;
}

static UInt256 tipHash(Fx *f)
{
    MGR_LOCK(f->m);
    UInt256 h = f->m->lastBlock->blockHash;
    MGR_UNLOCK(f->m);
    return h;
}

static int resident(Fx *f, const uint8_t hdr[80])
{
    UInt256 h;
    BRSHA256_2(&h, hdr, 80);
    MGR_LOCK(f->m);
    int r = BRSetGet(f->m->blocks, &h) != NULL;
    MGR_UNLOCK(f->m);
    return r;
}

static int misbehavin(Fx *f)
{
    MGR_LOCK(f->m);
    int n = f->m->misbehavinCount;
    MGR_UNLOCK(f->m);
    return n;
}

static UInt256 hashOf(const uint8_t hdr[80])
{
    UInt256 h;
    BRSHA256_2(&h, hdr, 80);
    return h;
}

// does the header meet its own target by proof of work (the check BRPeer.c runs at level 2)?
static int meetsOwnTarget(const uint8_t hdr[80])
{
    BRMerkleBlock *b = BRMerkleBlockParse(hdr, 80);
    uint32_t before = BRMerkleBlockPoWMismatchCount();
    int ok = BRMerkleBlockIsValid(b, (uint32_t)time(NULL)) && BRMerkleBlockPoWMismatchCount() == before;
    BRMerkleBlockFree(b);
    return ok;
}

// =============================================================================
// Cases
// =============================================================================

// The fork header is mainnet_recent row 30's child: the resident chain is rows 0..40, row 0's parent is not held.
#define FORK_PARENT_ROW 30
#define SHORT_CHAIN_ROWS 41

#ifndef INT_2026_10_09_E_GEN
// RED. An unjudgeable header that does not extend the tip, whose history stops at the resident floor, is refused at
// level 2.
static const char *case_unjudged_fork_refused(void)
{
    Fx f;
    uint8_t fork[1][80];

    fxInit(&f, range("mainnet_recent"));
    hexBytes(fork[0], kUnjudgedForkHeader, 80);
    check(UInt256Eq(UInt256Get(&fork[0][4]), hashOf(f.rows[FORK_PARENT_ROW])), "setup: the header's parent is row 30");
    check(meetsOwnTarget(fork[0]), "setup: the header meets its own target by proof of work");
    check(deliverRange(&f, 1, SHORT_CHAIN_ROWS) && tipHeight(&f) == f.firstHeight + SHORT_CHAIN_ROWS - 1,
          "setup: rows 1..40 accepted (not judged: no history below row 0)");

    UInt256 tip0 = tipHash(&f);
    int mis0 = misbehavin(&f);
    Counts c0 = counts();
    int v = deliverRows(&f, fork, 0, 1);
    Counts d = delta(c0, counts());
    int mis1 = misbehavin(&f), res = resident(&f, fork[0]);

    printf("NOTE: fork header at %" PRIu32 " (tip %" PRIu32 "): verdict=%d resident=%d skip=%" PRIu32 " match=%" PRIu32
           " mismatch=%" PRIu32 " misbehavin %d -> %d (DGB_HEADER_DIFF_CHECK=%d DGB_HEADER_POW_CHECK=%d)\n",
           f.firstHeight + FORK_PARENT_ROW + 1, tipHeight(&f), v, res, d.skip, d.match, d.mismatch, mis0, mis1,
           DGB_HEADER_DIFF_CHECK, DGB_HEADER_POW_CHECK);
    check(d.skip == 1 && d.match == 0 && d.mismatch == 0, "the header cannot be judged (counted as not judged)");
    check(! res, "the header is refused: not resident");
    check(UInt256Eq(tipHash(&f), tip0), "the tip is unchanged");
    check(mis1 == mis0 + 1, "the peer gets the misbehaving penalty");
    fxFree(&f);
    return g_fail ? "fail" : "pass";
}

// GUARD. The next real header on top of the same short chain: it cannot be judged, it extends the tip, it is the
// new tip, and the peer is not penalised.
static const char *case_unjudged_extends_tip_kept(void)
{
    Fx f;

    fxInit(&f, range("mainnet_recent"));
    check(deliverRange(&f, 1, SHORT_CHAIN_ROWS), "setup: rows 1..40 accepted");

    int mis0 = misbehavin(&f);
    Counts c0 = counts();
    int v = deliverRows(&f, f.rows, SHORT_CHAIN_ROWS, SHORT_CHAIN_ROWS + 1);
    Counts d = delta(c0, counts());

    printf("NOTE: header at %" PRIu32 ": verdict=%d tip=%" PRIu32 " skip=%" PRIu32 " misbehavin %d -> %d\n",
           f.firstHeight + SHORT_CHAIN_ROWS, v, tipHeight(&f), d.skip, mis0, misbehavin(&f));
    check(d.skip == 1 && d.match == 0 && d.mismatch == 0, "the header cannot be judged (counted as not judged)");
    check(v == 1 && resident(&f, f.rows[SHORT_CHAIN_ROWS]) && tipHeight(&f) == f.firstHeight + SHORT_CHAIN_ROWS,
          "it extends the tip and is accepted as the new tip");
    check(misbehavin(&f) == mis0, "the peer is not penalised");
    fxFree(&f);
    return g_fail ? "fail" : "pass";
}

// GUARD. A header already held (row 20 of the short chain), sent again: no refusal, no penalty, the tip unchanged.
static const char *case_unjudged_known_header_kept(void)
{
    Fx f;

    fxInit(&f, range("mainnet_recent"));
    check(deliverRange(&f, 1, SHORT_CHAIN_ROWS), "setup: rows 1..40 accepted");

    UInt256 tip0 = tipHash(&f);
    int mis0 = misbehavin(&f);
    Counts c0 = counts();
    int v = deliverRows(&f, f.rows, 20, 21);
    Counts d = delta(c0, counts());

    printf("NOTE: row 20 again: verdict=%d skip=%" PRIu32 " misbehavin %d -> %d\n", v, d.skip, mis0, misbehavin(&f));
    check(v == 1 && resident(&f, f.rows[20]) && UInt256Eq(tipHash(&f), tip0), "still held, the tip unchanged");
    check(misbehavin(&f) == mis0, "the peer is not penalised");
    fxFree(&f);
    return g_fail ? "fail" : "pass";
}

// GUARD. A 2-block reorg near the tip on real testnet26 history: the branch headers are judged and match, and the
// branch becomes the main chain.
static const char *case_short_reorg_near_tip(void)
{
    Fx f;
    uint8_t branch[REORG_BRANCH_LEN][80];
    const DiffRange *r = range("testnet_clamps");

    fxInit(&f, r);
    for (size_t i = 0; i < REORG_BRANCH_LEN; i++) hexBytes(branch[i], kReorgBranch[i], 80);
    check(deliverRange(&f, 1, r->count) && tipHeight(&f) == r->firstHeight + r->count - 1,
          "setup: the real range up to its tip");
    check(UInt256Eq(UInt256Get(&branch[0][4]), hashOf(f.rows[REORG_PARENT_ROW])),
          "setup: the branch starts on the block two below the tip");
    for (size_t i = 0; i < REORG_BRANCH_LEN; i++) {
        char s[96];
        snprintf(s, sizeof(s), "setup: branch header %zu meets its own target by proof of work", i);
        check(meetsOwnTarget(branch[i]), s);
    }

    uint32_t tip0 = tipHeight(&f);
    int mis0 = misbehavin(&f);
    Counts c0 = counts();
    int v = 1;
    for (size_t i = 0; i < REORG_BRANCH_LEN; i++) v &= deliverRows(&f, branch, i, i + 1);   // one per message
    Counts d = delta(c0, counts());
    UInt256 newTip = hashOf(branch[REORG_BRANCH_LEN - 1]);

    MGR_LOCK(f.m);
    UInt256 atOld = _BRPeerManagerBlockHashAtHeight(f.m, tip0);
    MGR_UNLOCK(f.m);
    printf("NOTE: reorg: verdict=%d tip %" PRIu32 " -> %" PRIu32 " match=%" PRIu32 " skip=%" PRIu32 " mismatch=%" PRIu32
           " pow=%" PRIu32 " misbehavin %d -> %d (DGB_HEADER_DIFF_CHECK=%d DGB_HEADER_POW_CHECK=%d)\n", v, tip0,
           tipHeight(&f), d.match, d.skip, d.mismatch, d.pow, mis0, misbehavin(&f), DGB_HEADER_DIFF_CHECK,
           DGB_HEADER_POW_CHECK);
    check(v == 1, "every branch header is accepted by the peer layer");
#if DGB_HEADER_DIFF_CHECK >= 1
    check(d.match == REORG_BRANCH_LEN && d.skip == 0 && d.mismatch == 0,
          "every branch header is judged (none skipped) and its target matches");
#endif
    check(d.pow == 0, "every branch header meets its target by proof of work");
    check(UInt256Eq(tipHash(&f), newTip) && tipHeight(&f) == tip0 + 1, "the branch is the main chain, one above the old tip");
    check(UInt256Eq(atOld, hashOf(branch[REORG_BRANCH_LEN - 2])), "the old tip's height now holds the branch's block");
    check(resident(&f, f.rows[r->count - 1]), "the replaced block stays resident, off the main chain");
    check(misbehavin(&f) == mis0, "the peer is not penalised");
    fxFree(&f);
    return g_fail ? "fail" : "pass";
}
// Mainnet parameters whose newest checkpoint is 9,100,000 and that carry no difficulty context: the shape of a network
// whose newest checkpoint has none (testnet26 today), where a wallet starting there cannot judge its first headers.
// mainnet_algo_swap row 180 is that checkpoint's real header.
#define CP_ROW 180
static const BRChainParams *paramsEndingAt9100000(void)
{
    static BRChainParams params;
    size_t n = 0;

    params = BRMainNetParams;
    while (n < params.checkpointsCount && params.checkpoints[n].height != 9100000u) n++;
    assert(n < params.checkpointsCount);
    params.checkpointsCount = n + 1;
    params.checkpointContexts = NULL;
    params.checkpointContextsCount = 0;
    return &params;
}

// GUARD. A wallet starting at a checkpoint without a context takes, first, a header on it that cannot be judged and
// is not the real chain's (it meets its own target by proof of work). The real chain, which then arrives beside it
// and cannot be judged at first either, still grows and wins: the wallet ends on the real tip and no peer is
// penalised.
static const char *case_honest_chain_wins_after_unjudged_first(void)
{
    const DiffRange *r = range("mainnet_algo_swap");
    const BRChainParams *params = paramsEndingAt9100000();
    Fx f;
    uint8_t first[1][80];

    fxInitEx(&f, r, params, params->checkpoints[params->checkpointsCount - 1].timestamp + 8*24*60*60, 0);
    hexBytes(first[0], kUnjudgedFirstHeader, 80);
    check(f.m->lastBlock && f.m->lastBlock->height == 9100000u &&
          UInt256Eq(f.m->lastBlock->blockHash, hashOf(f.rows[CP_ROW])),
          "setup: the wallet starts at the 9,100,000 checkpoint (its real header is row 180)");
    check(UInt256Eq(UInt256Get(&first[0][4]), hashOf(f.rows[CP_ROW])) && meetsOwnTarget(first[0]),
          "setup: the first header sits on the checkpoint and meets its own target by proof of work");

    int mis0 = misbehavin(&f);
    Counts c0 = counts();
    int v1 = deliverRows(&f, first, 0, 1);
    int took = resident(&f, first[0]) && UInt256Eq(tipHash(&f), hashOf(first[0]));
    int v2 = deliverRange(&f, CP_ROW + 1, r->count);
    Counts d = delta(c0, counts());
    UInt256 realTip = hashOf(f.rows[r->count - 1]);

    MGR_LOCK(f.m);
    UInt256 at1 = _BRPeerManagerBlockHashAtHeight(f.m, 9100001u);
    MGR_UNLOCK(f.m);
    printf("NOTE: first header taken=%d; real chain: verdict=%d tip %" PRIu32 " real=%d judged=%" PRIu32
           " skipped=%" PRIu32 " mismatch=%" PRIu32 " misbehavin %d -> %d (DGB_HEADER_DIFF_CHECK=%d)\n", took, v2,
           tipHeight(&f), UInt256Eq(tipHash(&f), realTip), d.match, d.skip, d.mismatch, mis0, misbehavin(&f),
           DGB_HEADER_DIFF_CHECK);
    check(v1 == 1 && took, "the first header is taken: it extends the tip, which has no history to judge it by");
    check(v2 == 1, "every message of the real chain is accepted by the peer layer");
    check(UInt256Eq(tipHash(&f), realTip) && tipHeight(&f) == f.firstHeight + r->count - 1,
          "the real chain wins: the wallet ends on its tip");
    check(UInt256Eq(at1, hashOf(f.rows[CP_ROW + 1])), "9,100,001 on the main chain is the real block, not the first one");
    check(d.mismatch == 0, "no real header is off target");
    check(misbehavin(&f) == mis0, "no peer is penalised");
    fxFree(&f);
    return g_fail ? "fail" : "pass";
}
#endif // INT_2026_10_09_E_GEN

#ifdef INT_2026_10_09_E_GEN
// ---- vector writer (gen mode) -----------------------------------------------------------------------------------------
// Searches nonces until a header meets its target with its own algorithm's proof-of-work hash.
static void findNonce(uint8_t hdr[80])
{
    BRMerkleBlock *b = BRMerkleBlockParse(hdr, 80);
    uint32_t before = BRMerkleBlockPoWMismatchCount();

    for (uint32_t n = 0;; n++) {
        UInt32SetLE(&hdr[76], n);
        b->nonce = n;
        UInt256 pow, t = UINT256_ZERO;
        BRMerkleBlockPoWHash(b, &pow);
        uint32_t size = b->target >> 24, tv = b->target & 0x00ffffff;
        if (size > 3) UInt32SetLE(&t.u8[size - 3], tv); else UInt32SetLE(t.u8, tv >> (3 - size)*8);
        int meets = 1;
        for (int i = 31; i >= 0; i--) { if (pow.u8[i] < t.u8[i]) break; if (pow.u8[i] > t.u8[i]) { meets = 0; break; } }
        if (meets) break;
    }
    BRMerkleBlockFree(b);
    (void)before;
}

static void printHex(FILE *o, const char *pre, const uint8_t hdr[80], const char *post)
{
    fprintf(o, "%s\"", pre);
    for (int i = 0; i < 80; i++) fprintf(o, "%02x", hdr[i]);
    fprintf(o, "\"%s\n", post);
}

// writes the vectors to `path` (the core logs to stdout, so not there)
static int gen(const char *path)
{
    Fx f;
    uint8_t fork[80];

    // the fork header off mainnet_recent row 30: sha256d at the easiest target the wallet accepts
    fxInit(&f, range("mainnet_recent"));
    memcpy(fork, f.rows[FORK_PARENT_ROW + 1], 80);
    UInt32SetLE(&fork[0], 0x20000202);
    UInt256Set(&fork[4], hashOf(f.rows[FORK_PARENT_ROW]));
    memset(&fork[36], 0x5a, 32);
    UInt32SetLE(&fork[68], UInt32GetLE(&f.rows[FORK_PARENT_ROW][68]) + 15);
    UInt32SetLE(&fork[72], 0x1e0fffff);
    findNonce(fork);
    fxFree(&f);

    // the first header on the 9,100,000 checkpoint (mainnet_algo_swap row 180): sha256d at 0x1e0fffff
    uint8_t first[80];
    fxInit(&f, range("mainnet_algo_swap"));
    memcpy(first, f.rows[181], 80);
    UInt32SetLE(&first[0], 0x20000202);
    UInt256Set(&first[4], hashOf(f.rows[180]));
    memset(&first[36], 0x3c, 32);
    UInt32SetLE(&first[68], UInt32GetLE(&f.rows[180][68]) + 15);
    UInt32SetLE(&first[72], 0x1e0fffff);
    findNonce(first);
    fxFree(&f);

    // the reorg branch off testnet_clamps row 357 (two below the tip): odo, each at the target the wallet computes
    const DiffRange *r = range("testnet_clamps");
    const size_t parentRow = 357, len = 3;
    uint8_t branch[3][80];
    fxInit(&f, r);
    deliverRange(&f, 1, r->count);
    for (size_t i = 0; i < len; i++) {
        const uint8_t *parentHdr = (i == 0) ? f.rows[parentRow] : branch[i - 1];
        UInt256 ph = hashOf(parentHdr);
        memcpy(branch[i], f.rows[parentRow + 1], 80);
        UInt32SetLE(&branch[i][0], 0x20000e02);
        UInt256Set(&branch[i][4], ph);
        memset(&branch[i][36], 0x60 + (int)i, 32);
        UInt32SetLE(&branch[i][68], UInt32GetLE(&parentHdr[68]) + 15);

        MGR_LOCK(f.m);
        BRMerkleBlock *prev = BRSetGet(f.m->blocks, &ph), *b = BRMerkleBlockParse(branch[i], 80);
        assert(prev != NULL);
        b->height = prev->height + 1;
        uint32_t expected = 0;
        int ok = _BRPeerManagerDiffV4ExpectedLocked(f.m, b, prev, &expected);
        BRMerkleBlockFree(b);
        MGR_UNLOCK(f.m);
        assert(ok);
        fprintf(stderr, "branch %zu: expected %08x\n", i, expected);
        UInt32SetLE(&branch[i][72], expected);
        findNonce(branch[i]);
        deliverRows(&f, branch, i, i + 1);
        assert(resident(&f, branch[i]));
    }
    fxFree(&f);

    FILE *o = fopen(path, "w");
    if (! o) return 1;
    fprintf(o, "// AUTO-GENERATED by `run.sh gen` (this program built with -DINT_2026_10_09_E_GEN). DO NOT HAND-EDIT.\n");
    fprintf(o, "// Each header meets its own target by proof of work.\n\n");
    fprintf(o, "// mainnet_recent row 30's child: sha256d at 0x1e0fffff (does not extend the tip of rows 0..40)\n");
    printHex(o, "static const char *kUnjudgedForkHeader = ", fork, ";");
    fprintf(o, "\n// on the 9,100,000 checkpoint (mainnet_algo_swap row 180): sha256d at 0x1e0fffff, not the real chain's\n");
    printHex(o, "static const char *kUnjudgedFirstHeader = ", first, ";");
    fprintf(o, "\n// testnet_clamps: a branch off row %zu (two below the range's tip), odo, each at the target the wallet\n"
            "// computes from its resident ancestors\n", parentRow);
    fprintf(o, "#define REORG_PARENT_ROW %zu\n#define REORG_BRANCH_LEN %zu\n", parentRow, len);
    fprintf(o, "static const char *const kReorgBranch[REORG_BRANCH_LEN] = {\n");
    for (size_t i = 0; i < len; i++) printHex(o, "    ", branch[i], ",");
    fprintf(o, "};\n");
    fclose(o);
    return 0;
}
#endif

typedef struct { const char *name; const char *(*fn)(void); } Case;

static const Case kCases[] = {
#ifndef INT_2026_10_09_E_GEN
    { "unjudged_fork_refused", case_unjudged_fork_refused },
    { "unjudged_extends_tip_kept", case_unjudged_extends_tip_kept },
    { "unjudged_known_header_kept", case_unjudged_known_header_kept },
    { "short_reorg_near_tip", case_short_reorg_near_tip },
    { "honest_chain_wins_after_unjudged_first", case_honest_chain_wins_after_unjudged_first },
#endif
    { NULL, NULL },
};

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc != 2) {
        fprintf(stderr, "usage: %s <case>|list\n", argv[0]);
        return 2;
    }
#ifdef INT_2026_10_09_E_GEN
    if (strcmp(argv[1], "gen") == 0) return gen(getenv("E_KAT_VECTORS_OUT") ? getenv("E_KAT_VECTORS_OUT") : "vectors.inc");
#endif
    if (strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; kCases[i].name; i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    for (size_t i = 0; kCases[i].name; i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        printf("---- %s ----\n", kCases[i].name);
        const char *v = kCases[i].fn();
        printf("RESULT %s %s\n", kCases[i].name, v);
        return strcmp(v, "pass") == 0 ? 0 : 1;
    }
    fprintf(stderr, "unknown case %s\n", argv[1]);
    return 2;
}
