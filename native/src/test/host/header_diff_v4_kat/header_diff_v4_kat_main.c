// Host KAT: a header at a height MultiShield V4 governs carries exactly the target (compact form) the reference
// client's GetNextWorkRequiredV4 computes from the header's ancestors; with the ancestors resident the wallet
// computes that same target, and what it does with a header that differs depends on DGB_HEADER_DIFF_CHECK
// (0 nothing, 1 count and log, 2 refuse). A header whose ancestors are not resident is not judged and never
// refused.
//
// ENTRY LAYER. Headers go through the real BRPeer.c dispatch (_BRPeerAcceptMessage, "headers") into the manager's
// own _peerRelayedBlock, wired as BRPeerManager.c wires it; the manager judges each header in
// _BRPeerManagerVerifyBlock from what is resident in manager->blocks. The resident chain comes only from the
// production constructor (one real header passed in as a persisted block) and from the headers delivered after it.
//
// FIXTURES. header_diff_v4_vectors.inc (gen_vectors.py): four ranges of 360 real consecutive headers -- mainnet near
// the tip, mainnet across algoSwapChangeTarget 9,100,000 (and the 9,100,000 checkpoint), mainnet across the first
// Odo block 9,112,320, and testnet26 with both timespan clamps and targets at powLimit -- each with the number of
// rows an independent reference judges and skips; synthetic inputs at the parameter edges for the pure function;
// a sha256d header ground to the easiest target the wallet accepts (0x1e0fffff) on top of the near-tip range; and
// the reference's counts over the testnet range with the minimum-difficulty rule turned on (it is off on both
// networks, in the reference client and on the testnet26 chain), which the wallet must reproduce when turned on.
//
// SEAM. -DHEADER_DIFF_V4_UNFIXED drops the damping of the averaging timespan in BRDifficultyV4Target, a plausible
// mis-port: run.sh requires every real-data case to FAIL in that build. Level 0 is the comparison arm: the code
// path of the builds before the level existed, where the easiest-target header is accepted and nothing is counted.
//
// One case per process: `header_diff_v4_kat <case>` prints "RESULT <case> <verdict>".
// `header_diff_v4_kat judge_file <path>` runs a file written by `gen_vectors.py --live` (live node check).
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

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (! c) g_fail++; }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

static void hexBytes(uint8_t *out, const char *h, size_t n)
{
    for (size_t i = 0; i < n; i++) { unsigned v; sscanf(h + 2*i, "%2x", &v); out[i] = (uint8_t)v; }
}

// ---- fixture ------------------------------------------------------------------------------------------------------
typedef struct {
    BRWallet *w;
    BRPeerManager *m;
    BRPeerCallbackInfo *info;
    BRPeer *peer;
    uint8_t (*rows)[80];       // the range's headers, wire order
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

// a manager on `params` whose resident chain is the range's first header (passed in as a persisted block), and one
// compact-filter download peer whose headers go to the manager's own relay path
static void fxInitParams(Fx *f, const BRChainParams *params, int testnet, uint32_t firstHeight, uint8_t (*rows)[80],
                         size_t count)
{
    memset(f, 0, sizeof(*f));
    BRSetNetwork(testnet);
    f->rows = rows;
    f->firstHeight = firstHeight;
    f->count = count;

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    f->w = BRWalletNew(NULL, 0, mpk);
    assert(f->w != NULL);

    BRMerkleBlock *first = BRMerkleBlockParse(rows[0], 80);
    first->height = firstHeight;
    BRMerkleBlock *blocks[1] = { first };
    f->m = BRPeerManagerNew(params, f->w, 0, blocks, 1, NULL, 0);
    assert(f->m != NULL);
    assert(f->m->lastBlock && f->m->lastBlock->height == firstHeight);
    f->m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;

    f->info = calloc(1, sizeof(*f->info));
    f->info->manager = f->m;
    f->info->peer = f->peer = BRPeerNew(f->m->params->magicNumber);
    f->peer->address = (UInt128) { .u8 = { 0,0,0,0,0,0,0,0,0,0,0xff,0xff, 198,51,100,7 } };
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

static void fxInit(Fx *f, int testnet, uint32_t firstHeight, uint8_t (*rows)[80], size_t count)
{
    fxInitParams(f, testnet ? &BRTestNetParams : &BRMainNetParams, testnet, firstHeight, rows, count);
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

// rows[from..to) in messages of at most 100 headers; returns 1 if every message was accepted
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

static int resident(Fx *f, const uint8_t hdr[80])
{
    UInt256 h;
    BRSHA256_2(&h, hdr, 80);
    MGR_LOCK(f->m);
    int r = BRSetGet(f->m->blocks, &h) != NULL;
    MGR_UNLOCK(f->m);
    return r;
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

// =============================================================================
// Cases
// =============================================================================

// The reference client's arith_uint256 SetCompact/GetCompact table (src/test/arith_uint256_tests.cpp,
// bignum_SetCompact): value, flags and the compact form read back.
static const char *case_compact(void)
{
    static const struct { uint32_t in; const char *hex; uint32_t out; int getNeg, neg, ovf; } v[] = {
        { 0x00000000, "0000000000000000000000000000000000000000000000000000000000000000", 0x00000000, 0, 0, 0 },
        { 0x00123456, "0000000000000000000000000000000000000000000000000000000000000000", 0x00000000, 0, 0, 0 },
        { 0x01003456, "0000000000000000000000000000000000000000000000000000000000000000", 0x00000000, 0, 0, 0 },
        { 0x02000056, "0000000000000000000000000000000000000000000000000000000000000000", 0x00000000, 0, 0, 0 },
        { 0x03000000, "0000000000000000000000000000000000000000000000000000000000000000", 0x00000000, 0, 0, 0 },
        { 0x04000000, "0000000000000000000000000000000000000000000000000000000000000000", 0x00000000, 0, 0, 0 },
        { 0x00923456, "0000000000000000000000000000000000000000000000000000000000000000", 0x00000000, 0, 0, 0 },
        { 0x01803456, "0000000000000000000000000000000000000000000000000000000000000000", 0x00000000, 0, 0, 0 },
        { 0x02800056, "0000000000000000000000000000000000000000000000000000000000000000", 0x00000000, 0, 0, 0 },
        { 0x03800000, "0000000000000000000000000000000000000000000000000000000000000000", 0x00000000, 0, 0, 0 },
        { 0x04800000, "0000000000000000000000000000000000000000000000000000000000000000", 0x00000000, 0, 0, 0 },
        { 0x01123456, "0000000000000000000000000000000000000000000000000000000000000012", 0x01120000, 0, 0, 0 },
        { 0x01fedcba, "000000000000000000000000000000000000000000000000000000000000007e", 0x01fe0000, 1, 1, 0 },
        { 0x02123456, "0000000000000000000000000000000000000000000000000000000000001234", 0x02123400, 0, 0, 0 },
        { 0x03123456, "0000000000000000000000000000000000000000000000000000000000123456", 0x03123456, 0, 0, 0 },
        { 0x04123456, "0000000000000000000000000000000000000000000000000000000012345600", 0x04123456, 0, 0, 0 },
        { 0x04923456, "0000000000000000000000000000000000000000000000000000000012345600", 0x04923456, 1, 1, 0 },
        { 0x05009234, "0000000000000000000000000000000000000000000000000000000092340000", 0x05009234, 0, 0, 0 },
        { 0x20123456, "1234560000000000000000000000000000000000000000000000000000000000", 0x20123456, 0, 0, 0 },
        // powLimit's compact form, and the targets the chain carries most
        { 0x1e0fffff, "00000fffff000000000000000000000000000000000000000000000000000000", 0x1e0fffff, 0, 0, 0 },
        { 0x1c00ffff, "0000000000ffff00000000000000000000000000000000000000000000000000", 0x1c00ffff, 0, 0, 0 },
    };
    for (size_t i = 0; i < sizeof(v)/sizeof(*v); i++) {
        int neg = -1, ovf = -1;
        UInt256 t = BRTargetFromCompact(v[i].in, &neg, &ovf);
        char d[160];
        snprintf(d, sizeof(d), "SetCompact(%08" PRIx32 ") = %s, flags %d/%d; GetCompact = %08" PRIx32, v[i].in,
                 v[i].hex, v[i].neg, v[i].ovf, v[i].out);
        check(strcmp(u256hex(UInt256Reverse(t)), v[i].hex) == 0 && neg == v[i].neg && ovf == v[i].ovf &&
              BRTargetToCompact(t, v[i].getNeg) == v[i].out, d);
    }
    int neg = -1, ovf = -1;
    BRTargetFromCompact(0xff123456, &neg, &ovf);
    check(neg == 0 && ovf == 1, "SetCompact(ff123456): wider than 256 bits, not negative");
    UInt256 x = UINT256_ZERO; x.u8[0] = 0x80;
    check(BRTargetToCompact(x, 0) == 0x02008000, "GetCompact(0x80) = 02008000: the sign bit is never produced");
    check(BRDifficultyV4PowLimitCompact(&BRMainNetParams.diffV4) == 0x1e0fffff &&
          BRDifficultyV4PowLimitCompact(&BRTestNetParams.diffV4) == 0x1e0fffff, "powLimit (~0 >> 20) is 1e0fffff");
    return g_fail ? "fail" : "pass";
}

// BRDifficultyV4Target on synthetic inputs at the parameter edges, both networks (expected from gen_vectors.py's
// independent reference).
static const char *case_inputs(void)
{
    for (size_t i = 0; i < sizeof(kDiffInputVectors)/sizeof(*kDiffInputVectors); i++) {
        const DiffInputVector *v = &kDiffInputVectors[i];
        const BRDifficultyV4Params *p = v->testnet ? &BRTestNetParams.diffV4 : &BRMainNetParams.diffV4;
        uint32_t got = BRDifficultyV4Target(p, v->lastTimes, v->firstTimes, v->bits, v->distance);
        char d[200];
        snprintf(d, sizeof(d), "%s %s: %08" PRIx32 " (expected %08" PRIx32 ")", v->testnet ? "testnet" : "mainnet",
                 v->what, got, v->expected);
        check(got == v->expected, d);
    }
    return g_fail ? "fail" : "pass";
}

// Every row of a real range through the manager. Level >= 1: exactly the reference's judged and skipped counts,
// no mismatch. Level 0: nothing counted. At every level every row is accepted and the tip is the range's last row.
static const char *runRange(const char *name)
{
    const DiffRange *r = range(name);
    assert(r != NULL);
    uint8_t (*rows)[80] = loadRange(r);
    Fx f;
    fxInit(&f, r->testnet, r->firstHeight, rows, r->count);

    Counts c0 = counts();
    int ok = deliverRange(&f, 1, r->count);
    Counts d = delta(c0, counts());
    char s[200];

    snprintf(s, sizeof(s), "%s: %zu rows delivered, every message accepted, tip %" PRIu32 " (expected %" PRIu32 ")",
             name, r->count - 1, tipHeight(&f), r->firstHeight + (uint32_t)r->count - 1);
    check(ok && tipHeight(&f) == r->firstHeight + r->count - 1 && resident(&f, rows[r->count - 1]), s);
    check(d.pow == 0, "no proof-of-work mismatch on real headers");
#if DGB_HEADER_DIFF_CHECK >= 1
    snprintf(s, sizeof(s), "%s: judged %" PRIu32 " (reference %" PRIu32 "), skipped %" PRIu32 " (reference %" PRIu32
             "), mismatched %" PRIu32, name, d.match + d.mismatch, r->judged, d.skip, r->skip, d.mismatch);
    check(d.mismatch == 0 && d.match == r->judged && d.skip == r->skip && d.match >= 200, s);
#else
    snprintf(s, sizeof(s), "%s: level 0 counts nothing (judged %" PRIu32 ", skipped %" PRIu32 ")", name,
             d.match + d.mismatch, d.skip);
    check(d.match == 0 && d.skip == 0 && d.mismatch == 0, s);
#endif
    fxFree(&f);
    free(rows);
    return g_fail ? "fail" : "pass";
}

static const char *case_range_mainnet_recent(void)    { return runRange("mainnet_recent"); }
static const char *case_range_mainnet_algo_swap(void) { return runRange("mainnet_algo_swap"); }
static const char *case_range_mainnet_first_odo(void) { return runRange("mainnet_first_odo"); }
static const char *case_range_testnet_clamps(void)    { return runRange("testnet_clamps"); }

// A real header on top of a full resident window, alone in its message: accepted at every level; at levels 1 and 2
// judged once and matched.
static const char *case_level_real_header(void)
{
    const DiffRange *r = range("mainnet_recent");
    uint8_t (*rows)[80] = loadRange(r);
    Fx f;
    fxInit(&f, 0, r->firstHeight, rows, r->count);
    deliverRange(&f, 1, r->count - 1);

    Counts c0 = counts();
    int v = deliverRows(&f, rows, r->count - 1, r->count);
    Counts d = delta(c0, counts());

    check(v == 1 && resident(&f, rows[r->count - 1]) && tipHeight(&f) == r->firstHeight + r->count - 1,
          "the real header is accepted and becomes the tip");
#if DGB_HEADER_DIFF_CHECK >= 1
    check(d.match == 1 && d.mismatch == 0 && d.skip == 0, "it is judged once and its target matches");
#else
    check(d.match == 0 && d.mismatch == 0 && d.skip == 0, "level 0: nothing counted");
#endif
    fxFree(&f);
    free(rows);
    return g_fail ? "fail" : "pass";
}

// The easiest target the wallet accepts (0x1e0fffff), proof of work ground to meet it, on top of a full resident
// window of real headers, from the download peer. Prints the verdict for run.sh to hold against the level:
//   refused             not resident, tip unchanged, one mismatch counted, the peer treated as misbehaving
//   accepted_counted    resident, the new tip, exactly one mismatch counted
//   accepted_uncounted  resident, the new tip, nothing counted (the builds before the level existed)
static const char *case_level_easiest_target(void)
{
    const DiffRange *r = range("mainnet_recent");
    uint8_t (*rows)[80] = loadRange(r);
    uint8_t easy[1][80];
    Fx f;

    hexBytes(easy[0], kEasiestTargetHeader, 80);
    fxInit(&f, 0, r->firstHeight, rows, r->count);
    deliverRange(&f, 1, r->count);
    assert(tipHeight(&f) == r->firstHeight + r->count - 1);

    MGR_LOCK(f.m); int mis0 = f.m->misbehavinCount; MGR_UNLOCK(f.m);
    Counts c0 = counts();
    int v = deliverRows(&f, easy, 0, 1);
    Counts d = delta(c0, counts());
    MGR_LOCK(f.m); int mis1 = f.m->misbehavinCount; MGR_UNLOCK(f.m);
    int res = resident(&f, easy[0]);
    uint32_t tip = tipHeight(&f), base = r->firstHeight + (uint32_t)r->count - 1;

    printf("NOTE: verdict=%d resident=%d tip %" PRIu32 " -> %" PRIu32 " diff match/mismatch/skip %" PRIu32 "/%" PRIu32
           "/%" PRIu32 " pow-mismatch %" PRIu32 " misbehavin %d -> %d (DGB_HEADER_DIFF_CHECK=%d)\n", v, res, base, tip,
           d.match, d.mismatch, d.skip, d.pow, mis0, mis1, DGB_HEADER_DIFF_CHECK);
    check(d.pow == 0, "the header's proof of work meets its own target (only the target itself is wrong)");

    const char *verdict = "unexpected";
    if (! res && tip == base && d.mismatch == 1 && d.match == 0 && mis1 == mis0 + 1) verdict = "refused";
    else if (res && tip == base + 1 && d.mismatch == 1 && d.match == 0 && d.skip == 0 && mis1 == mis0)
        verdict = "accepted_counted";
    else if (res && tip == base + 1 && d.mismatch == 0 && d.match == 0 && d.skip == 0 && mis1 == mis0)
        verdict = "accepted_uncounted";
    fxFree(&f);
    free(rows);
    return g_fail ? "fail" : verdict;
}

// Right after the resident chain starts (one persisted header), the next headers lack the window: at levels 1 and 2
// each is counted as skipped, and none is refused -- even at level 2.
static const char *case_skip_without_history(void)
{
    const DiffRange *r = range("mainnet_recent");
    uint8_t (*rows)[80] = loadRange(r);
    Fx f;
    fxInit(&f, 0, r->firstHeight, rows, r->count);

    Counts c0 = counts();
    int ok = deliverRange(&f, 1, 41);
    Counts d = delta(c0, counts());

    check(ok && tipHeight(&f) == r->firstHeight + 40 && resident(&f, rows[40]), "all 40 are accepted");
#if DGB_HEADER_DIFF_CHECK >= 1
    check(d.skip == 40 && d.match == 0 && d.mismatch == 0, "all 40 are counted as not judged");
#else
    check(d.skip == 0 && d.match == 0 && d.mismatch == 0, "level 0: nothing counted");
#endif
    fxFree(&f);
    free(rows);
    return g_fail ? "fail" : "pass";
}

// A header whose version names no algorithm: the reference client finds no block of it and expects powLimit
// whatever the history, so such a header at powLimit matches here (refusing it is the allowed-algorithm check's
// job, DGB_HEADER_POW_CHECK). One at another target is a mismatch.
static const char *case_unknown_algorithm(void)
{
    const DiffRange *r = range("mainnet_recent");
    uint8_t (*rows)[80] = loadRange(r);
    uint8_t hdr[2][80];
    UInt256 tipHash;
    Fx f;
    fxInit(&f, 0, r->firstHeight, rows, r->count);
    deliverRange(&f, 1, r->count);

    BRSHA256_2(&tipHash, rows[r->count - 1], 80);
    for (int i = 0; i < 2; i++) {
        memcpy(hdr[i], rows[r->count - 1], 80);
        UInt32SetLE(&hdr[i][0], 0x20000102);   // bit 8: no algorithm
        UInt256Set(&hdr[i][4], tipHash);
        UInt32SetLE(&hdr[i][68], UInt32GetLE(&rows[r->count - 1][68]) + 15);
        UInt32SetLE(&hdr[i][72], i == 0 ? 0x1e0fffff : 0x1a0ebd68);
    }

    Counts c0 = counts();
    deliverRows(&f, hdr, 0, 1);
    Counts d0 = delta(c0, counts());
    c0 = counts();
    deliverRows(&f, hdr, 1, 2);
    Counts d1 = delta(c0, counts());
#if DGB_HEADER_DIFF_CHECK >= 1
    check(d0.match == 1 && d0.mismatch == 0, "unknown algorithm at powLimit: judged, matches");
    check(d1.match == 0 && d1.mismatch == 1, "unknown algorithm at another target: judged, mismatch");
#else
    check(d0.match + d0.mismatch + d0.skip + d1.match + d1.mismatch + d1.skip == 0, "level 0: nothing counted");
#endif
    fxFree(&f);
    free(rows);
    return g_fail ? "fail" : "pass";
}

// The minimum-difficulty rule (fPowAllowMinDifficultyBlocks) is off on both networks, as in the reference client and
// on the testnet26 chain. Turned on over the testnet range, the wallet must reach exactly the counts the generator's
// independent reference reaches with it on (a chain built without the rule: most of those mismatches are real).
static const char *case_min_difficulty_rule(void)
{
    static BRChainParams params;
    const DiffRange *r = range("testnet_clamps");
    uint8_t (*rows)[80] = loadRange(r);
    Fx f;

    check(! BRMainNetParams.diffV4.allowMinDifficultyBlocks && ! BRTestNetParams.diffV4.allowMinDifficultyBlocks,
          "the rule is off on both networks");
    params = BRTestNetParams;
    params.diffV4.allowMinDifficultyBlocks = 1;
    fxInitParams(&f, &params, 1, r->firstHeight, rows, r->count);

    Counts c0 = counts();
    deliverRange(&f, 1, r->count);
    Counts d = delta(c0, counts());
    char s[200];
#if DGB_HEADER_DIFF_CHECK >= 1
    snprintf(s, sizeof(s), "rule on: match %" PRIu32 " (reference %" PRIu32 "), mismatch %" PRIu32 " (%" PRIu32
             "), skip %" PRIu32 " (%" PRIu32 ")", d.match, kMinRuleMatch, d.mismatch, kMinRuleMismatch, d.skip,
             kMinRuleSkip);
    check(d.match == kMinRuleMatch && d.mismatch == kMinRuleMismatch && d.skip == kMinRuleSkip, s);
#else
    snprintf(s, sizeof(s), "level 0 counts nothing (%" PRIu32 ")", d.match + d.mismatch + d.skip);
    check(d.match + d.mismatch + d.skip == 0, s);
#endif
    fxFree(&f);
    free(rows);
    return g_fail ? "fail" : "pass";
}

// Live check: a file written by `gen_vectors.py --live N FILE` ("main N", then "height hex" per line). Every row after
// the first goes through the manager; the rows that have their window must all match.
static int judgeFile(const char *path)
{
    FILE *fp = fopen(path, "r");
    char net[16], hex[200];
    unsigned n = 0, height = 0;
    size_t cap = 4096, count = 0;
    uint8_t (*rows)[80] = calloc(cap, 80);
    uint32_t first = 0;

    if (! fp || fscanf(fp, "%15s %u", net, &n) != 2) { printf("cannot read %s\n", path); return 1; }
    while (fscanf(fp, "%u %199s", &height, hex) == 2) {
        if (count == cap) rows = realloc(rows, (cap *= 2)*80);
        if (count == 0) first = height;
        assert(height == first + count && strlen(hex) == 160);
        hexBytes(rows[count++], hex, 80);
    }
    fclose(fp);

    Fx f;
    fxInit(&f, strcmp(net, "test") == 0, first, rows, count);
    Counts c0 = counts();
    int ok = deliverRange(&f, 1, count);
    Counts d = delta(c0, counts());
    uint32_t tip = tipHeight(&f);

    printf("live: %zu headers %" PRIu32 "..%" PRIu32 ": judged %" PRIu32 ", mismatched %" PRIu32 ", skipped %" PRIu32
           ", pow-mismatch %" PRIu32 ", tip %" PRIu32 " (DGB_HEADER_DIFF_CHECK=%d)\n", count, first,
           first + (uint32_t)count - 1, d.match + d.mismatch, d.mismatch, d.skip, d.pow, tip, DGB_HEADER_DIFF_CHECK);
    int pass = ok && tip == first + count - 1 && d.mismatch == 0 && d.match >= n;
    printf("RESULT judge_file %s\n", pass ? "pass" : "fail");
    fxFree(&f);
    free(rows);
    return pass ? 0 : 1;
}

typedef struct { const char *name; const char *(*fn)(void); } Case;
static const Case kCases[] = {
    { "compact", case_compact },
    { "inputs", case_inputs },
    { "range_mainnet_recent", case_range_mainnet_recent },
    { "range_mainnet_algo_swap", case_range_mainnet_algo_swap },
    { "range_mainnet_first_odo", case_range_mainnet_first_odo },
    { "range_testnet_clamps", case_range_testnet_clamps },
    { "level_real_header", case_level_real_header },
    { "level_easiest_target", case_level_easiest_target },
    { "skip_without_history", case_skip_without_history },
    { "unknown_algorithm", case_unknown_algorithm },
    { "min_difficulty_rule", case_min_difficulty_rule },
};

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc == 2 && strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < sizeof(kCases)/sizeof(*kCases); i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    if (argc == 3 && strcmp(argv[1], "judge_file") == 0) return judgeFile(argv[2]);
    for (size_t i = 0; argc == 2 && i < sizeof(kCases)/sizeof(*kCases); i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        const char *v = kCases[i].fn();
        printf("RESULT %s %s\n", kCases[i].name, v);
        return (strcmp(v, "fail") == 0 || strcmp(v, "unexpected") == 0) ? 1 : 0;
    }
    fprintf(stderr, "usage: %s list | <case> | judge_file <path>\n", argv[0]);
    return 2;
}
