// Host KAT: a wallet whose resident chain STARTS AT a checkpoint judges the very first headers above that checkpoint
// against the real chain's difficulty (MultiShield V4), using the checkpoint's difficulty context (BRCheckPointContext,
// BRChainParams.h): the real headers that end at the checkpoint, shipped with the table and verified at start-up.
//
// Without the context the checkpoint stub has no ancestors, so the first ~61 headers above it cannot be judged
// ("diff-skip", never refused at any level) and every later header is judged only against them. That is the gap this
// suite closes for the newest checkpoints (V4-R1).
//
// ENTRY LAYER. As in header_diff_v4_kat: headers go through the real BRPeer.c dispatch (_BRPeerAcceptMessage,
// "headers") into the manager's own _peerRelayedBlock, and are judged in _BRPeerManagerVerifyBlock. The manager is
// built the way a NEW wallet's is: no saved blocks, an earliest key time just over 7 days after the fixture's
// checkpoint, so the production constructor makes that checkpoint's stub the chain tip.
//
// FIXTURE. checkpoint_diff_context_vectors.inc (gen_vectors.py): real headers above the checkpoint from a local full
// node, which an independent V4 reference judges, all of them, from the SHIPPED context; and two headers at the
// easiest target the wallet accepts (0x1e0fffff) with their proof of work met, one on the checkpoint and one on the
// 30th real header above it.
//
// SEAM. -DCHECKPOINT_DIFF_CONTEXT_UNFIXED builds the manager without loading any context (the code before this
// change): run.sh requires context_loaded and real_headers_judged to FAIL there and the easiest-target headers to be
// ACCEPTED unjudged. The shipped arm (proof of work 2, difficulty 2) must refuse both.
//
// One case per process: `checkpoint_diff_context_kat <case>` prints "RESULT <case> <verdict>".
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

#include "checkpoint_diff_context_vectors.inc"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (! c) g_fail++; }

static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

static void hexBytes(uint8_t *out, const char *h, size_t n)
{
    for (size_t i = 0; i < n; i++) { unsigned v; sscanf(h + 2*i, "%2x", &v); out[i] = (uint8_t)v; }
}

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

static const BRCheckPoint *fixtureCheckpoint(const BRChainParams *params)
{
    for (size_t i = 0; i < params->checkpointsCount; i++)
        if (params->checkpoints[i].height == kCheckpointHeight) return &params->checkpoints[i];
    return NULL;
}

// ---- fixture: a new wallet's manager, its chain at the fixture checkpoint's stub -----------------------------------
typedef struct {
    BRWallet *w;
    BRPeerManager *m;
    BRPeerCallbackInfo *info;
    BRPeer *peer;
} Fx;

// saved: headers a restarted wallet passes back as persisted blocks (adopted by the constructor), or NULL for a new one
static void fxInitSaved(Fx *f, const BRChainParams *params, BRMerkleBlock **saved, size_t savedCount)
{
    memset(f, 0, sizeof(*f));
    BRSetNetwork(0);

    const BRCheckPoint *cp = fixtureCheckpoint(params);
    assert(cp != NULL);

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    f->w = BRWalletNew(NULL, 0, mpk);
    assert(f->w != NULL);

    // a wallet created just over 7 days after the checkpoint: the constructor anchors it there
    f->m = BRPeerManagerNew(params, f->w, cp->timestamp + 7*24*60*60 + 1, saved, savedCount, NULL, 0);
    assert(f->m != NULL);
    if (! saved) {
        assert(f->m->lastBlock && f->m->lastBlock->height == kCheckpointHeight);
        assert(UInt256IsZero(f->m->lastBlock->prevBlock));   // the stub itself: no ancestors resident
    }
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

static void fxInitParams(Fx *f, const BRChainParams *params) { fxInitSaved(f, params, NULL, 0); }
static void fxInit(Fx *f) { fxInitParams(f, &BRMainNetParams); }

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
static int deliverRange(Fx *f, uint8_t (*rows)[80], size_t from, size_t to)
{
    int ok = 1;

    for (size_t i = from; i < to; i += 100) ok &= deliverRows(f, rows, i, (i + 100 < to) ? i + 100 : to);
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

static uint8_t (*loadAbove(void))[80]
{
    const size_t n = sizeof(kAbove)/sizeof(*kAbove);
    uint8_t (*rows)[80] = calloc(n, 80);
    for (size_t i = 0; i < n; i++) hexBytes(rows[i], kAbove[i], 80);
    return rows;
}

// the number of verified contexts the manager holds, and whether one ends at the fixture checkpoint
static size_t contextsLoaded(Fx *f, int *atFixture)
{
    size_t n = 0;

    *atFixture = 0;
#if DGB_HEADER_DIFF_CHECK >= 1
    MGR_LOCK(f->m);
    n = f->m->diffContextCount;
    for (size_t i = 0; i < n; i++) {
        if (f->m->diffContexts[i]->height == kCheckpointHeight &&
            UInt256Eq(f->m->diffContextHashes[i], f->m->lastBlock->blockHash)) *atFixture = 1;
    }
    MGR_UNLOCK(f->m);
#endif
    return n;
}

// =============================================================================
// Cases
// =============================================================================

// The shipped table: every mainnet context verifies against the mainnet checkpoint table, the newest checkpoint has
// one, and the fixture checkpoint has one; tampered copies of the fixture's context do not verify.
static const char *case_context_verifies(void)
{
    const BRChainParams *p = &BRMainNetParams;
    const BRCheckPoint *newest = &p->checkpoints[p->checkpointsCount - 1];
    const BRCheckPointContext *fixture = NULL;
    int newestHas = 0;
    char d[200];

    check(p->checkpointContexts != NULL && p->checkpointContextsCount > 0, "mainnet carries checkpoint contexts");
    for (size_t i = 0; i < p->checkpointContextsCount; i++) {
        const BRCheckPointContext *ctx = &p->checkpointContexts[i];

        snprintf(d, sizeof(d), "context at %" PRIu32 " (%zu rows) verifies", ctx->height, ctx->count);
        check(BRCheckPointContextVerify(p->checkpoints, p->checkpointsCount, ctx, NULL), d);
        if (ctx->height == newest->height) newestHas = 1;
        if (ctx->height == kCheckpointHeight) fixture = ctx;
    }
    snprintf(d, sizeof(d), "the newest checkpoint (%" PRIu32 ") has a context", newest->height);
    check(newestHas, d);
    snprintf(d, sizeof(d), "the fixture checkpoint (%" PRIu32 ") has a context (else rerun gen_vectors.py)",
             kCheckpointHeight);
    check(fixture != NULL, d);
    if (! fixture) return "fail";

    // tampering: a modifiable copy of the rows
    const size_t n = fixture->count;
    char (*rows)[161] = calloc(n, sizeof(*rows));
    const char **ptrs = calloc(n, sizeof(*ptrs));
    for (size_t i = 0; i < n; i++) { memcpy(rows[i], fixture->headers[i], 161); ptrs[i] = rows[i]; }
    BRCheckPointContext t = { fixture->height, n, ptrs };
    UInt256 h;

    check(BRCheckPointContextVerify(p->checkpoints, p->checkpointsCount, &t, &h) &&
          UInt256Eq(h, UInt256Reverse(fixtureCheckpoint(p)->hash)), "an untouched copy verifies, at the checkpoint");
    rows[n/2][137] = (rows[n/2][137] == '0') ? '1' : '0';   // a timestamp byte of a middle row
    check(! BRCheckPointContextVerify(p->checkpoints, p->checkpointsCount, &t, NULL),
          "a changed timestamp in a middle row breaks the link: refused");
    memcpy(rows[n/2], fixture->headers[n/2], 161);
    rows[n - 1][145] = (rows[n - 1][145] == '0') ? '1' : '0';   // the checkpoint row's target
    check(! BRCheckPointContextVerify(p->checkpoints, p->checkpointsCount, &t, NULL),
          "a changed target in the checkpoint row: refused");
    memcpy(rows[n - 1], fixture->headers[n - 1], 161);
    t.count = n - 1;                                         // ends one block below the checkpoint
    check(! BRCheckPointContextVerify(p->checkpoints, p->checkpointsCount, &t, NULL),
          "a run that does not end at the checkpoint: refused");
    t.count = n;
    t.height = fixture->height - 50000 + 1;                  // a height with no checkpoint
    check(! BRCheckPointContextVerify(p->checkpoints, p->checkpointsCount, &t, NULL),
          "a run named for a height that has no checkpoint: refused");
    t.height = fixture->height;
    BRCheckPointContext shortRun = { fixture->height, BR_CHECKPOINT_CONTEXT_MIN_ROWS - 1,
                                     ptrs + (n - (BR_CHECKPOINT_CONTEXT_MIN_ROWS - 1)) };
    check(! BRCheckPointContextVerify(p->checkpoints, p->checkpointsCount, &shortRun, NULL),
          "a run shorter than the averaging window and median spans: refused");
    check(! BRCheckPointContextVerify(BRTestNetParams.checkpoints, BRTestNetParams.checkpointsCount, &t, NULL),
          "a mainnet run against the testnet table: refused");

    // a manager on params whose context is tampered with loads none for that checkpoint
    static BRChainParams params;
    static BRCheckPointContext one;
    rows[n/2][137] = (rows[n/2][137] == '0') ? '1' : '0';
    one = t;
    params = BRMainNetParams;
    params.checkpointContexts = &one;
    params.checkpointContextsCount = 1;
    Fx f;
    fxInitParams(&f, &params);
    int at = 0;
    size_t loaded = contextsLoaded(&f, &at);
    check(loaded == 0 && ! at, "a manager given only a tampered context loads none");
    fxFree(&f);

    free(ptrs);
    free(rows);
    return g_fail ? "fail" : "pass";
}

// A new wallet's manager holds the verified context of the checkpoint its chain starts at.
static const char *case_context_loaded(void)
{
    Fx f;
    fxInit(&f);
    int at = 0;
    size_t n = contextsLoaded(&f, &at);
    char d[160];

    snprintf(d, sizeof(d), "the manager holds %zu verified context(s), one at its starting checkpoint %" PRIu32, n,
             kCheckpointHeight);
    check(n == BRMainNetParams.checkpointContextsCount && at, d);
    fxFree(&f);
    return g_fail ? "fail" : "pass";
}

// The real headers above the starting checkpoint: every one accepted and judged, from the very first, none skipped,
// none mismatched; exactly the count the independent reference judges from the shipped context.
static const char *case_real_headers_judged(void)
{
    const size_t n = sizeof(kAbove)/sizeof(*kAbove);
    uint8_t (*rows)[80] = loadAbove();
    Fx f;
    fxInit(&f);

    Counts c0 = counts();
    int ok = deliverRange(&f, rows, 0, n);
    Counts d = delta(c0, counts());
    char s[240];

    snprintf(s, sizeof(s), "%zu real headers above %" PRIu32 " delivered, every message accepted, tip %" PRIu32
             " (expected %" PRIu32 ")", n, kCheckpointHeight, tipHeight(&f), kCheckpointHeight + (uint32_t)n);
    check(ok && tipHeight(&f) == kCheckpointHeight + n && resident(&f, rows[n - 1]), s);
    check(d.pow == 0, "no proof-of-work mismatch on real headers");
#if DGB_HEADER_DIFF_CHECK >= 1
    snprintf(s, sizeof(s), "judged %" PRIu32 " (reference %" PRIu32 "), skipped %" PRIu32 " (0), mismatched %" PRIu32
             " (0)", d.match + d.mismatch, kAboveJudged, d.skip, d.mismatch);
    check(d.match == kAboveJudged && d.skip == 0 && d.mismatch == 0, s);
#else
    check(d.match + d.mismatch + d.skip == 0, "level 0: nothing counted");
#endif
    fxFree(&f);
    free(rows);
    return g_fail ? "fail" : "pass";
}

// An easiest-target header (0x1e0fffff, proof of work met) at the first height above the checkpoint, or on the 30th
// real header above it (see the modes below). Prints the verdict for run.sh to hold against the arm:
//   refused             not resident, tip unchanged, judged once as a mismatch, the peer treated as misbehaving
//   accepted_counted    resident, the new tip, judged once as a mismatch (level 1: observe only)
//   accepted_skipped    resident, the new tip, not judged (ancestors not in hand: the code before this change)
//   mode 0  a new wallet, the header on the checkpoint
//   mode 1  a new wallet that has taken the 30 real headers above the checkpoint, the header on the 30th
//   mode 2  the same wallet restarted: those 30 headers come back as persisted blocks above the stub
//   mode 3  as 2, with the checkpoint's own real header persisted below them (it replaces the stub when resident)
static const char *easiest(int mode)
{
    uint8_t (*rows)[80] = loadAbove();
    uint8_t easy[1][80];
    Fx f;
    const size_t which = (mode == 0) ? 0 : 1, below = (mode == 0) ? 0 : kSecondOn;
    const uint32_t base = kCheckpointHeight + (uint32_t)below;

    hexBytes(easy[0], kEasy[which], 80);
    if (mode <= 1) {
        fxInit(&f);
        if (below) {
            int ok = deliverRange(&f, rows, 0, below);
            assert(ok && tipHeight(&f) == base);
        }
    }
    else {
        BRMerkleBlock *saved[kSecondOn + 1];
        size_t n = 0;

        if (mode == 3) {
            const BRCheckPointContext *ctx = NULL;
            uint8_t hdr[80];

            for (size_t i = 0; i < BRMainNetParams.checkpointContextsCount; i++)
                if (BRMainNetParams.checkpointContexts[i].height == kCheckpointHeight)
                    ctx = &BRMainNetParams.checkpointContexts[i];
            assert(ctx && BRCheckPointContextRow(ctx, 0, hdr));
            saved[n] = BRMerkleBlockParse(hdr, 80);
            saved[n++]->height = kCheckpointHeight;
        }
        for (size_t i = 0; i < below; i++) {
            saved[n] = BRMerkleBlockParse(rows[i], 80);
            saved[n++]->height = kCheckpointHeight + 1 + (uint32_t)i;
        }
        fxInitSaved(&f, &BRMainNetParams, saved, n);
        assert(tipHeight(&f) == base);
        if (mode == 3) {   // the checkpoint's real header is the resident block at that hash, not the stub
            MGR_LOCK(f.m);
            const BRMerkleBlock *r = BRSetGet(f.m->blocks, saved[0]);
            assert(r && ! UInt256IsZero(r->prevBlock));
            MGR_UNLOCK(f.m);
        }
    }

    MGR_LOCK(f.m); int mis0 = f.m->misbehavinCount; MGR_UNLOCK(f.m);
    Counts c0 = counts();
    int v = deliverRows(&f, easy, 0, 1);
    Counts d = delta(c0, counts());
    MGR_LOCK(f.m); int mis1 = f.m->misbehavinCount; MGR_UNLOCK(f.m);
    int res = resident(&f, easy[0]);
    uint32_t tip = tipHeight(&f);

    printf("NOTE: height %" PRIu32 " verdict=%d resident=%d tip %" PRIu32 " -> %" PRIu32 " diff match/mismatch/skip %"
           PRIu32 "/%" PRIu32 "/%" PRIu32 " pow-mismatch %" PRIu32 " misbehavin %d -> %d (V4 expects %08" PRIx32
           ", DGB_HEADER_DIFF_CHECK=%d)\n", base + 1, v, res, base, tip, d.match, d.mismatch, d.skip, d.pow, mis0,
           mis1, kEasyExpected[which], DGB_HEADER_DIFF_CHECK);
    check(d.pow == 0, "the header's proof of work meets its own target (only the target itself is wrong)");

    const char *verdict = "unexpected";
    if (! res && tip == base && d.mismatch == 1 && d.match == 0 && d.skip == 0 && mis1 == mis0 + 1)
        verdict = "refused";
    else if (res && tip == base + 1 && d.mismatch == 1 && d.match == 0 && d.skip == 0 && mis1 == mis0)
        verdict = "accepted_counted";
    else if (res && tip == base + 1 && d.mismatch == 0 && d.match == 0 && d.skip == 1 && mis1 == mis0)
        verdict = "accepted_skipped";
    fxFree(&f);
    free(rows);
    return g_fail ? "fail" : verdict;
}

static const char *case_easiest_on_checkpoint(void) { return easiest(0); }
static const char *case_easiest_above_checkpoint(void) { return easiest(1); }
static const char *case_easiest_after_resume(void) { return easiest(2); }
static const char *case_easiest_after_resume_checkpoint_header(void) { return easiest(3); }

typedef struct { const char *name; const char *(*fn)(void); } Case;
static const Case kCases[] = {
    { "context_verifies", case_context_verifies },
    { "context_loaded", case_context_loaded },
    { "real_headers_judged", case_real_headers_judged },
    { "easiest_on_checkpoint", case_easiest_on_checkpoint },
    { "easiest_above_checkpoint", case_easiest_above_checkpoint },
    { "easiest_after_resume", case_easiest_after_resume },
    { "easiest_after_resume_checkpoint_header", case_easiest_after_resume_checkpoint_header },
};

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc == 2 && strcmp(argv[1], "list") == 0) {
        for (size_t i = 0; i < sizeof(kCases)/sizeof(*kCases); i++) printf("%s\n", kCases[i].name);
        return 0;
    }
    for (size_t i = 0; argc == 2 && i < sizeof(kCases)/sizeof(*kCases); i++) {
        if (strcmp(argv[1], kCases[i].name) != 0) continue;
        const char *v = kCases[i].fn();
        printf("RESULT %s %s\n", kCases[i].name, v);
        return (strcmp(v, "fail") == 0 || strcmp(v, "unexpected") == 0) ? 1 : 0;
    }
    fprintf(stderr, "usage: %s list | <case>\n", argv[0]);
    return 2;
}
