// Host KAT harness for the CF scan-ledger's buffered-drain + residual
// peek/commit re-request DRIVER (BRPeerManager.c). Phase 2 Task 4 built the
// harness skeleton (build/teardown smoke test only); Task 5
// (.superpowers/sdd/2026-07-26-cf-scan-ledger-phase2-rerequest-driver/)
// wires the real driver into BRPeerManager.c and adds the real assertions
// here: does the buffered-drain credit path actually dispatch getdata (not
// merely MarkEvaluated) on a wallet-matching filter once its block header +
// cfheader connect, does a hit with no CF-capable peer keep the entry
// buffered instead of silently losing it, and does the residual re-request
// driver re-issue getcfilters for the RESIDUAL (verify/parse/disconnect)
// drop set while staying gated off whenever the buffer is non-empty.
//
// Approach (same #include-a-.c-for-statics pattern as cf_confirm_kat,
// bip341_signtx_kat, digidollar_send_kat): #include "BRPeerManager.c" directly
// to reach the file-static re-request driver plumbing and the otherwise-
// opaque BRPeerManagerStruct/BRPeerCallbackInfo definitions. A real
// BRPeerManager is built via the public BRPeerManagerNew() (so every internal
// array/mutex is correctly initialized), backed by a real BRWallet built via
// BRWalletNew() with a test mnemonic's derived master pubkey (same derivation
// pattern as digidollar_wallet_kat / cf_confirm_kat).
//
// Send-capture seam: the driver reaches out to peers through BRPeer.c's
// public API. Real BRPeer.c is linked in (every other BRPeer symbol used by
// the manager -- BRPeerNew, BRPeerSetCallbacks, etc. -- is the real one), but
// these four calls are intercepted at link time via GNU ld's `-Wl,--wrap=`
// (run.sh) and redirected here instead of touching a real socket:
//   - BRPeerConnectStatus  -> always reports BRPeerStatusConnected
//   - BRPeerIsSocketOpen   -> always reports an open (live) socket
//   - BRPeerSendGetCFilters   -> records into g_capStart / g_capCount
//   - BRPeerSendGetdataBlocks -> records into g_getdataCount / g_getdataHash
// (the buffered-drain credit assertion Task 5 exercises). This requires GNU
// ld's `--wrap`; host CI is Linux/clang -- NOT portable to a Darwin/ld64 host.
//
// Peers under test are always heap-allocated via BRPeerNew (never a stack
// `BRPeer literal = {0}`): BRPeer.c casts a `BRPeer *` to the private
// `BRPeerContext *` and reads fields past the public struct (e.g.
// BRPeerSendPingProbe's ctx->socket / ctx->pongInfo, touched by
// BRPeerManagerKeepAlive's ping sweep, which runs ahead of the drive logic
// under test every tick). A stack-sized BRPeer would make that an
// out-of-bounds read. BRPeerNew's calloc leaves ctx->socket == -1, so the
// (real, unwrapped) send path these peers exercise safely no-ops (ENOTCONN)
// instead of touching a real socket.
//
// LeakSanitizer stays LIVE for this KAT (run.sh does not set
// ASAN_OPTIONS=detect_leaks=0 the way the sibling KATs do) -- the scan-
// ledger's buffered raw filter bytes (BRCFScanLedger's filter-byte buffer,
// Phase 2 Task 2) are exactly the kind of allocation a silent leak would
// hide. Every KAT case must therefore end by calling BRPeerManagerFree(m)
// (which also now calls BRCFScanLedgerFree(&manager->cfLedger)) so LSan can
// prove the buffer and the manager are both actually freed.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRPeerManager.c"

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRCrypto.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "PASS: %s\n" : "FAIL: %s\n", d); if (!c) g_fail++; }

// --- link-wrap send-capture seam ------------------------------------------
//
// Verified against the live BRPeer.h signatures before writing these (do not
// hand-wave the types -- a mismatched --wrap shim silently fails to bind, or
// worse, corrupts the stack across the ABI mismatch):
//   BRPeerStatus BRPeerConnectStatus(BRPeer *peer);
//   int BRPeerIsSocketOpen(BRPeer *peer);
//   void BRPeerSendGetCFilters(BRPeer *peer, uint8_t filterType, uint32_t startHeight, UInt256 stopHash);
//   void BRPeerSendGetdataBlocks(BRPeer *peer, const UInt256 blockHashes[], size_t blockCount);
// All four matched the brief's assumed signatures exactly -- BRPeerStatus IS
// a real enum (not a bare int), and BRPeerSendGetdataBlocks's second
// parameter is an array-of-UInt256 (decays to `const UInt256 *`), same as
// BRPeerSendGetCFilters's stopHash is UInt256 by value.

static int g_capCount = 0;
static uint32_t g_capStart = 0;

// --- Task 5: stopHash->height registry + cumulative everRequested set ---------
//
// THE CAUSALITY SPINE. The residual driver reaches a peer through
// BRPeerSendGetCFilters(peer, type, startHeight, stopHash). stopHash is
// _BRPeerManagerBlockHashAtHeight(stopHeight) -- the hash of the block at the
// stop height in manager->blocks, a block THIS test populated. So the wrap
// resolves stopHash -> stop-height through a registry the test fills for its
// own dummy blocks, then folds every height in the inclusive [start..stop] into
// a CUMULATIVE test-side everRequested set. serveSome() (below) may ONLY
// MarkEvaluated heights present in everRequested -- a height that was never
// actually re-requested is never served. That is what makes the acceptance
// property gate-sensitive at production scale: with the deleted `if
// (BufferedCount==0)` gate re-added, a stale buffer keeps the residual path
// shut, NO getcfilters is captured, everRequested stays empty, serveSome serves
// nothing, and `outstanding` is frozen (the livelock reproduced).
#define REG_MAX 8192
static struct { UInt256 hash; uint32_t height; } g_blockReg[REG_MAX];
static size_t g_blockRegCount = 0;
#define REG_NOT_FOUND 0xFFFFFFFFu

static void blockRegReset(void) { g_blockRegCount = 0; }
static void blockRegAdd(const BRMerkleBlock *b)
{
    if (g_blockRegCount < REG_MAX) {
        g_blockReg[g_blockRegCount].hash   = b->blockHash;
        g_blockReg[g_blockRegCount].height = b->height;
        g_blockRegCount++;
    }
}
static uint32_t blockRegLookup(UInt256 h)
{
    for (size_t i = 0; i < g_blockRegCount; i++)
        if (UInt256Eq(g_blockReg[i].hash, h)) return g_blockReg[i].height;
    return REG_NOT_FOUND;
}

#define EVERREQ_MAX 8192
static uint32_t g_everReq[EVERREQ_MAX];
static size_t g_everReqCount = 0;

static void everReqReset(void) { g_everReqCount = 0; }
static int everReqContains(uint32_t h)
{
    for (size_t i = 0; i < g_everReqCount; i++) if (g_everReq[i] == h) return 1;
    return 0;
}
static void everReqAdd(uint32_t h)
{
    if (everReqContains(h)) return;
    if (g_everReqCount < EVERREQ_MAX) g_everReq[g_everReqCount++] = h;
}

BRPeerStatus __wrap_BRPeerConnectStatus(BRPeer *peer)
{
    (void)peer;
    return BRPeerStatusConnected;
}

int __wrap_BRPeerIsSocketOpen(BRPeer *peer)
{
    (void)peer;
    return 1;
}

void __wrap_BRPeerSendGetCFilters(BRPeer *peer, uint8_t filterType, uint32_t startHeight, UInt256 stopHash)
{
    (void)peer; (void)filterType;
    g_capStart = startHeight;
    g_capCount++;

    // Resolve stopHash -> stop height via the test's own dummy-block registry,
    // then fold the whole inclusive [start..stop] range into the cumulative
    // everRequested set. A stopHash the test never registered (e.g. the
    // pre-existing cases, which don't call blockRegAdd) resolves to
    // REG_NOT_FOUND and leaves everRequested untouched -- g_capStart/g_capCount
    // still update exactly as before, so those cases are unaffected.
    uint32_t stopH = blockRegLookup(stopHash);
    if (stopH != REG_NOT_FOUND && stopH >= startHeight) {
        for (uint32_t h = startHeight; ; h++) {   // guarded against a UINT32_MAX wrap
            everReqAdd(h);
            if (h == stopH) break;
        }
    }
}

static int g_getdataCount = 0;
static UInt256 g_getdataHash;

void __wrap_BRPeerSendGetdataBlocks(BRPeer *peer, const UInt256 blockHashes[], size_t blockCount)
{
    (void)peer;
    g_getdataCount += (int)blockCount;
    if (blockCount > 0) g_getdataHash = blockHashes[0];
}

// canonical all-zeros mnemonic, same one digidollar_wallet_kat/cf_confirm_kat
// use elsewhere in this tree
static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

// dummy BRMerkleBlock: BRMerkleBlockHash/Eq (BRMerkleBlock.h) only look at
// ->blockHash, so a hand-set height + arbitrary distinct hash byte pattern
// stands in for a real block header. ->prevBlock is left BRMerkleBlockNew's
// default (UINT256_ZERO) unless the caller links it explicitly.
static BRMerkleBlock *dummyBlock(uint32_t height, uint8_t hashSeed, uint32_t timestamp)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    memset(b->blockHash.u8, hashSeed, sizeof(b->blockHash.u8));
    b->height = height;
    b->timestamp = timestamp;
    return b;
}

// Find an outstanding entry by height (linear scan of the fully-visible
// BRCFScanLedger struct -- this TU has it via #include "BRPeerManager.c").
static const BRCFOutstanding *findOutstanding(const BRCFScanLedger *l, uint32_t height)
{
    for (size_t i = 0; i < l->outstandingCount; i++) {
        if (l->outstanding[i].height == height) return &l->outstanding[i];
    }
    return NULL;
}

// Mutable twin of findOutstanding, for pre-seeding attempts/requestedAt directly
// on a ledger outstanding entry (Scenario B: exercise RetireCapped at its exact
// attempt cap without a 7.5-min wall-clock advance). Full struct access via the
// #include "BRPeerManager.c" pattern.
static BRCFOutstanding *mutOutstanding(BRCFScanLedger *l, uint32_t height)
{
    for (size_t i = 0; i < l->outstandingCount; i++) {
        if (l->outstanding[i].height == height) return &l->outstanding[i];
    }
    return NULL;
}

static int gaveUpContains(const BRCFScanLedger *l, uint32_t height)
{
    for (size_t i = 0; i < l->gaveUpCount; i++) if (l->gaveUp[i] == height) return 1;
    return 0;
}

static int uint32InArr(const uint32_t *a, size_t n, uint32_t h)
{
    for (size_t i = 0; i < n; i++) if (a[i] == h) return 1;
    return 0;
}

// serveSome: MarkEvaluated up to n still-outstanding heights that are in the
// cumulative everRequested set, drawn LOWEST-first (outstanding[] is sorted
// ascending, so a plain forward scan is lowest-first). Models CF responses
// trickling in over ticks. It NEVER serves a height that was not captured as
// re-requested -- the causality that keeps the property test gate-sensitive.
// Writes the served heights into servedOut[] (caller sizes it >= n) and returns
// the count served. Heights are gathered before any MarkEvaluated so the
// mid-scan array mutation MarkEvaluated performs cannot skip a candidate.
static int serveSome(BRPeerManager *m, int n, uint32_t *servedOut)
{
    uint32_t toServe[128];
    int k = 0;
    for (size_t i = 0; i < m->cfLedger.outstandingCount && k < n && k < 128; i++) {
        uint32_t h = m->cfLedger.outstanding[i].height;
        if (everReqContains(h)) toServe[k++] = h;
    }
    for (int j = 0; j < k; j++) {
        BRCFScanLedgerMarkEvaluated(&m->cfLedger, toServe[j]);
        if (servedOut) servedOut[j] = toServe[j];
    }
    return k;
}

// --- per-tick invariant snapshot + checker (Scenario A) ----------------------
// The acceptance property is asserted AT EVERY TICK, not just at the endpoint:
// a fix can be per-tick-correct yet fail to converge on a cross-tick
// interaction, and a false-green here would let a non-converging fix reach a
// 30-minute device run undetected.
typedef struct {
    size_t   outstandingCount;
    uint32_t scannedThrough;
    uint32_t requestedThrough;
    size_t   gaveUpCount;
    uint32_t gaveUp[CF_GAVEUP_MAX];
    size_t   heightCount;
    uint32_t heights[CF_OUTSTANDING_MAX];
} LedgerSnap;

static void snapLedger(const BRCFScanLedger *l, LedgerSnap *s)
{
    s->outstandingCount  = l->outstandingCount;
    s->scannedThrough    = l->scannedThrough;
    s->requestedThrough  = l->requestedThrough;
    s->gaveUpCount       = l->gaveUpCount;
    for (size_t i = 0; i < l->gaveUpCount; i++) s->gaveUp[i] = l->gaveUp[i];
    s->heightCount       = l->outstandingCount;
    for (size_t i = 0; i < l->outstandingCount; i++) s->heights[i] = l->outstanding[i].height;
}

// Assert the four cross-tick invariants for one drive tick (KeepAlive+serve),
// comparing the current ledger to the previous tick's snapshot. `served`/
// `nServed` are the heights MarkEvaluated this tick; `expBuffered` is the
// buffered-entry count that must hold throughout (the stale orphan never
// drains inside the sub-second loop).
static void checkTick(BRPeerManager *m, const LedgerSnap *prev,
                      const uint32_t *served, int nServed, size_t expBuffered, int tick)
{
    BRCFScanLedger *l = &m->cfLedger;
    char lbl[192];

    // (a) outstanding monotonically NON-INCREASING vs the previous tick, and it
    // fell by EXACTLY the number served (KeepAlive's drain/re-request must not
    // add or spuriously drop a hole -- only serveSome removes).
    snprintf(lbl, sizeof lbl, "tick %d (a): outstanding non-increasing (%zu <= %zu)",
             tick, l->outstandingCount, prev->outstandingCount);
    check(l->outstandingCount <= prev->outstandingCount, lbl);
    snprintf(lbl, sizeof lbl, "tick %d (a): outstanding fell by exactly nServed (%zu == %zu - %d)",
             tick, l->outstandingCount, prev->outstandingCount, nServed);
    check(l->outstandingCount + (size_t)nServed == prev->outstandingCount, lbl);

    // (b) scannedThrough only advances over genuinely-evaluated heights: it is
    // non-decreasing and never sits at/above the lowest still-outstanding hole.
    snprintf(lbl, sizeof lbl, "tick %d (b): scannedThrough non-decreasing (%u >= %u)",
             tick, l->scannedThrough, prev->scannedThrough);
    check(l->scannedThrough >= prev->scannedThrough, lbl);
    int bOk = (l->outstandingCount == 0) || (l->scannedThrough < l->outstanding[0].height);
    snprintf(lbl, sizeof lbl, "tick %d (b): scannedThrough (%u) below lowest outstanding hole (%u)",
             tick, l->scannedThrough, l->outstandingCount ? l->outstanding[0].height : 0);
    check(bOk, lbl);

    // (c) Task-3 byte-identity: the age-out/suppressor must touch NO scan-state
    // field. gaveUp set byte-identical, requestedThrough unchanged, and the
    // outstanding height-set == prev minus exactly the heights served.
    int gaveUpSame = (l->gaveUpCount == prev->gaveUpCount) &&
                     (memcmp(l->gaveUp, prev->gaveUp, l->gaveUpCount * sizeof(uint32_t)) == 0);
    snprintf(lbl, sizeof lbl, "tick %d (c): gaveUp set byte-identical (count %zu)", tick, l->gaveUpCount);
    check(gaveUpSame, lbl);
    snprintf(lbl, sizeof lbl, "tick %d (c): requestedThrough unchanged (%u == %u)",
             tick, l->requestedThrough, prev->requestedThrough);
    check(l->requestedThrough == prev->requestedThrough, lbl);

    int setOk = 1;
    for (int j = 0; j < nServed; j++) {                            // each served height: was outstanding, now gone
        if (! uint32InArr(prev->heights, prev->heightCount, served[j])) setOk = 0;
        if (findOutstanding(l, served[j]) != NULL) setOk = 0;
    }
    for (size_t i = 0; i < l->outstandingCount; i++) {            // each survivor: was in prev, not served this tick
        uint32_t h = l->outstanding[i].height;
        if (! uint32InArr(prev->heights, prev->heightCount, h)) setOk = 0;
        if (uint32InArr(served, (size_t)nServed, h)) setOk = 0;
    }
    snprintf(lbl, sizeof lbl, "tick %d (c): outstanding set == prev minus served (no spurious perturbation)", tick);
    check(setOk, lbl);

    // (d) the stale buffered entry never blocked a hole: it is still resident
    // (age-out is 900s, far beyond this sub-second loop) yet the drain still
    // progressed this tick. "remains, suppressing nothing" per the brief.
    snprintf(lbl, sizeof lbl, "tick %d (d): stale buffer resident but blocking nothing (buffered %zu == %zu)",
             tick, BRCFScanLedgerBufferedCount(l), expBuffered);
    check(BRCFScanLedgerBufferedCount(l) == expBuffered, lbl);
}

// ---------------------------------------------------------------------------
// Test-only single-element BIP158 basic-filter ENCODER.
//
// BRGCSFilter.c (this submodule) only DECODES compact filters -- an SPV
// wallet is a filter *consumer*, never a *builder*, in production, so no
// public encoder exists to reuse. This mirrors BRGCSFilter.c's private
// siphash24 / fastrange64 / Golomb-Rice bit-layout byte-for-byte (verified
// against that file's implementation, not guessed) so the REAL decoder
// (BRGCSFilterBasicParse) parses what this builds and BRGCSFilterMatchAny
// hits on the element it was built for. Used solely to synthesize a single
// wallet-matching filter for the buffered-drain credit KATs below.
// P=19, M=784931 (BR_GCS_BASIC_FILTER_P/M, BRGCSFilter.h) -- BIP158 basic
// filter params.
#define TEST_SIP_ROTL(x, b) ((uint64_t)(((x) << (b)) | ((x) >> (64 - (b)))))
#define TEST_SIP_ROUND(v0, v1, v2, v3) do {          \
    v0 += v1; v1 = TEST_SIP_ROTL(v1, 13); v1 ^= v0;  \
    v0 = TEST_SIP_ROTL(v0, 32);                      \
    v2 += v3; v3 = TEST_SIP_ROTL(v3, 16); v3 ^= v2;  \
    v0 += v3; v3 = TEST_SIP_ROTL(v3, 21); v3 ^= v0;  \
    v2 += v1; v1 = TEST_SIP_ROTL(v1, 17); v1 ^= v2;  \
    v2 = TEST_SIP_ROTL(v2, 32);                      \
} while (0)

static uint64_t test_siphash24(uint64_t k0, uint64_t k1, const uint8_t *data, size_t len)
{
    uint64_t v0 = k0 ^ UINT64_C(0x736f6d6570736575);
    uint64_t v1 = k1 ^ UINT64_C(0x646f72616e646f6d);
    uint64_t v2 = k0 ^ UINT64_C(0x6c7967656e657261);
    uint64_t v3 = k1 ^ UINT64_C(0x7465646279746573);

    const uint8_t *end = data + (len - (len & 7));
    while (data != end) {
        uint64_t m = UInt64GetLE(data);
        v3 ^= m;
        TEST_SIP_ROUND(v0, v1, v2, v3);
        TEST_SIP_ROUND(v0, v1, v2, v3);
        v0 ^= m;
        data += 8;
    }

    uint64_t b = ((uint64_t)len) << 56;
    switch (len & 7) {
        case 7: b |= ((uint64_t)data[6]) << 48; /* fallthrough */
        case 6: b |= ((uint64_t)data[5]) << 40; /* fallthrough */
        case 5: b |= ((uint64_t)data[4]) << 32; /* fallthrough */
        case 4: b |= ((uint64_t)data[3]) << 24; /* fallthrough */
        case 3: b |= ((uint64_t)data[2]) << 16; /* fallthrough */
        case 2: b |= ((uint64_t)data[1]) << 8;  /* fallthrough */
        case 1: b |= ((uint64_t)data[0]);       /* fallthrough */
        case 0: break;
    }

    v3 ^= b;
    TEST_SIP_ROUND(v0, v1, v2, v3);
    TEST_SIP_ROUND(v0, v1, v2, v3);
    v0 ^= b;
    v2 ^= 0xff;
    TEST_SIP_ROUND(v0, v1, v2, v3);
    TEST_SIP_ROUND(v0, v1, v2, v3);
    TEST_SIP_ROUND(v0, v1, v2, v3);
    TEST_SIP_ROUND(v0, v1, v2, v3);

    return v0 ^ v1 ^ v2 ^ v3;
}

static uint64_t test_fastrange64(uint64_t hash, uint64_t F)
{
    return (uint64_t)(((__uint128_t)hash * (__uint128_t)F) >> 64);
}

static void test_gcsWriteBit(uint8_t *out, size_t off, size_t *bitPos, unsigned bit)
{
    size_t byteIdx = off + (*bitPos) / 8;
    out[byteIdx] |= (uint8_t)((bit & 1u) << (7 - (*bitPos % 8)));
    (*bitPos)++;
}

// Builds a valid BIP158 basic filter containing EXACTLY ONE element, keyed
// to blockHash the same way BRGCSFilterBasicParse derives its SipHash key
// (k0/k1 = the block hash's first 16 raw wire bytes). Returns the encoded
// length (a handful of bytes for N=1 -- outCap must be >= 8).
static size_t buildSingleElementFilter(UInt256 blockHash, const uint8_t *elem, size_t elemLen,
                                       uint8_t *out, size_t outCap)
{
    uint64_t k0 = UInt64GetLE(&blockHash.u8[0]);
    uint64_t k1 = UInt64GetLE(&blockHash.u8[8]);
    uint64_t F  = (uint64_t)BR_GCS_BASIC_FILTER_M; // N=1 -> F = N*M = M
    uint64_t val = test_fastrange64(test_siphash24(k0, k1, elem, elemLen), F);
    const uint8_t P = BR_GCS_BASIC_FILTER_P;
    uint64_t q = val >> P;
    uint64_t r = val & ((((uint64_t)1) << P) - 1);

    memset(out, 0, outCap);
    out[0] = 0x01; // CompactSize N=1 (single byte, N < 0xFD)
    size_t off = 1;
    size_t bitPos = 0;
    for (uint64_t i = 0; i < q; i++) test_gcsWriteBit(out, off, &bitPos, 1);
    test_gcsWriteBit(out, off, &bitPos, 0);
    for (int i = (int)P - 1; i >= 0; i--) test_gcsWriteBit(out, off, &bitPos, (unsigned)((r >> i) & 1));

    size_t totalBytes = (bitPos + 7) / 8;
    return off + totalBytes;
}

// ---------------------------------------------------------------------------
// Test 1 (THE CRUX): a header-race-buffered, wallet-MATCHING filter drains
// the moment its block header + cfheader both connect, and the block is
// FETCHED via getdata (not merely MarkEvaluated) -- proving the receive
// actually credits instead of being marked scanned and silently lost.
static void test_buffered_drains_and_CREDITS_at_connect(BRWallet *wallet)
{
    printf("\n=== test_buffered_drains_and_CREDITS_at_connect (THE CRUX) ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    uint32_t H = 500000;
    BRMerkleBlock *bH  = dummyBlock(H,     0xA0, 1700000000);
    BRMerkleBlock *bH1 = dummyBlock(H + 1, 0xA1, 1700000015);
    BRMerkleBlock *bH2 = dummyBlock(H + 2, 0xA2, 1700000030);
    bH1->prevBlock = bH->blockHash;
    bH2->prevBlock = bH1->blockHash;
    BRSetAdd(m->blocks, bH);
    BRSetAdd(m->blocks, bH1);
    BRSetAdd(m->blocks, bH2);
    m->lastBlock = bH2; // block header for H+1 IS connected (present in manager->blocks)

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x01; pa->port = 10001; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    // cfheader chain covering H and H+1 -> NextHeight()==H+2 > H+1 (cfheader present).
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, H, UINT256_ZERO);
    UInt256 dummyFilterHash; memset(dummyFilterHash.u8, 0x77, sizeof(dummyFilterHash.u8));
    check(BRCompactFilterChainAppend(m->compactFilterChain, BRCompactFilterChainTipHeader(m->compactFilterChain),
                                     &dummyFilterHash, 1) == 1, "setup: dummy header appended for height H");

    BRWalletFilterElements *fe = BRWalletGetFilterElements(wallet);
    check(fe != NULL && fe->count > 0, "setup: wallet has >=1 filter element to build a matching filter from");
    uint8_t encoded[16];
    size_t encodedLen = buildSingleElementFilter(bH1->blockHash, fe->elements[0], fe->elementLens[0],
                                                 encoded, sizeof encoded);
    UInt256 filterHash; BRSHA256_2(filterHash.u8, encoded, encodedLen);
    BRWalletFilterElementsFree(fe);
    check(BRCompactFilterChainAppend(m->compactFilterChain, BRCompactFilterChainTipHeader(m->compactFilterChain),
                                     &filterHash, 1) == 1, "setup: real filterHash appended for height H+1 (cfheader now present)");

    check(BRCompactFilterChainNextHeight(m->compactFilterChain) > bH1->height,
          "setup: cfheader present for H+1 (isReady's cfheader gate should pass)");
    check(BRCompactFilterChainVerifyFilter(m->compactFilterChain, bH1->height, encoded, encodedLen) == 1,
          "setup: hand-built filter verifies against the chain at H+1 (encoder sanity)");

    BRCFScanLedgerInit(&m->cfLedger, H);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H + 1, H + 1, UINT128_ZERO, 0, 0);
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 1, "setup: H+1 outstanding before drain");
    check(BRCFScanLedgerBufferFilter(&m->cfLedger, bH1->blockHash, encoded, encodedLen, (uint32_t)time(NULL)) == 1,
          "setup: filter buffered for H+1 (simulates the header-race drop EDIT 1 handles)");
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1, "setup: buffer holds 1 entry");

    g_capCount = 0; g_capStart = 0; g_getdataCount = 0;

    BRPeerManagerKeepAlive(m);

    check(g_getdataCount == 1, "CRUX: getdata dispatched exactly once (block FETCHED -> receive credits)");
    check(UInt256Eq(g_getdataHash, bH1->blockHash), "CRUX: getdata targeted the buffered block's own hash");
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 0, "MarkEvaluated fired: H+1 no longer outstanding");
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 0, "buffer drained (entry removed after credit)");
    check(g_capCount == 0, "no getcfilters sent -- this is the buffer-drain path, not the re-request path");

    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// Test 2 (silent-loss guard): the exact same buffered wallet-matching hit,
// but with ZERO CF-capable peers connected -- the drain must NOT fabricate a
// credit it can't dispatch. The entry stays buffered and the height stays
// outstanding so the very next tick retries once a peer connects.
static void test_buffered_hit_no_peer_stays(BRWallet *wallet)
{
    printf("\n=== test_buffered_hit_no_peer_stays (silent-loss guard) ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    uint32_t H = 600000;
    BRMerkleBlock *bH  = dummyBlock(H,     0xB0, 1700100000);
    BRMerkleBlock *bH1 = dummyBlock(H + 1, 0xB1, 1700100015);
    bH1->prevBlock = bH->blockHash;
    BRSetAdd(m->blocks, bH);
    BRSetAdd(m->blocks, bH1);
    m->lastBlock = bH1;
    check(array_count(m->connectedPeers) == 0, "setup: zero connected peers (no CF-capable peer to dispatch to)");

    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, H, UINT256_ZERO);
    UInt256 dummyFilterHash; memset(dummyFilterHash.u8, 0x88, sizeof(dummyFilterHash.u8));
    BRCompactFilterChainAppend(m->compactFilterChain, BRCompactFilterChainTipHeader(m->compactFilterChain),
                               &dummyFilterHash, 1);

    BRWalletFilterElements *fe = BRWalletGetFilterElements(wallet);
    uint8_t encoded[16];
    size_t encodedLen = buildSingleElementFilter(bH1->blockHash, fe->elements[0], fe->elementLens[0],
                                                 encoded, sizeof encoded);
    UInt256 filterHash; BRSHA256_2(filterHash.u8, encoded, encodedLen);
    BRWalletFilterElementsFree(fe);
    BRCompactFilterChainAppend(m->compactFilterChain, BRCompactFilterChainTipHeader(m->compactFilterChain),
                               &filterHash, 1);
    check(BRCompactFilterChainNextHeight(m->compactFilterChain) > bH1->height,
          "setup: cfheader present for H+1 (would drain if a peer existed)");

    BRCFScanLedgerInit(&m->cfLedger, H);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H + 1, H + 1, UINT128_ZERO, 0, 0);
    BRCFScanLedgerBufferFilter(&m->cfLedger, bH1->blockHash, encoded, encodedLen, (uint32_t)time(NULL));
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1, "setup: buffer holds 1 (wallet-matching) entry");

    g_getdataCount = 0; g_capCount = 0;

    BRPeerManagerKeepAlive(m);

    check(g_getdataCount == 0, "silent-loss guard: no getdata dispatched (nothing to send it to)");
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 1,
          "silent-loss guard: MarkEvaluated did NOT fire -- outstanding count unchanged");
    check(findOutstanding(&m->cfLedger, H + 1) != NULL,
          "silent-loss guard: H+1 specifically is still outstanding (next tick will retry)");
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1,
          "silent-loss guard: entry KEPT buffered, not dropped, on a hit that couldn't dispatch");

    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// Test 3: block header present, but the cfheader chain has NOT yet reached
// this height -- isReady must gate the entry off (not drain it, and not
// discard it as a failed-verify either).
static void test_buffered_waits_for_cfheader(BRWallet *wallet)
{
    printf("\n=== test_buffered_waits_for_cfheader ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    uint32_t H = 700000;
    BRMerkleBlock *bH  = dummyBlock(H,     0xC0, 1700200000);
    BRMerkleBlock *bH1 = dummyBlock(H + 1, 0xC1, 1700200015);
    bH1->prevBlock = bH->blockHash;
    BRSetAdd(m->blocks, bH);
    BRSetAdd(m->blocks, bH1); // block header for H+1 IS connected
    m->lastBlock = bH1;

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x02; pa->port = 10002; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    // cfheader chain covers ONLY height H -> NextHeight()==H+1, which is NOT
    // > H+1 -- the cfheader for H+1 itself is not yet present.
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, H, UINT256_ZERO);
    UInt256 dummyFilterHash; memset(dummyFilterHash.u8, 0x99, sizeof(dummyFilterHash.u8));
    BRCompactFilterChainAppend(m->compactFilterChain, BRCompactFilterChainTipHeader(m->compactFilterChain),
                               &dummyFilterHash, 1);
    check(BRCompactFilterChainNextHeight(m->compactFilterChain) == H + 1,
          "setup: cfheader chain stops at H (NextHeight==H+1, NOT > H+1)");

    // Content is irrelevant here -- isReady's cfheader gate must block
    // evaluation before these bytes are ever looked at.
    uint8_t junk[4] = { 0x01, 0xAA, 0xBB, 0x00 };
    BRCFScanLedgerInit(&m->cfLedger, H);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H + 1, H + 1, UINT128_ZERO, 0, 0);
    BRCFScanLedgerBufferFilter(&m->cfLedger, bH1->blockHash, junk, sizeof junk, (uint32_t)time(NULL));
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1, "setup: buffer holds 1 entry");

    g_getdataCount = 0; g_capCount = 0;

    BRPeerManagerKeepAlive(m);

    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1,
          "isReady gate: NOT drained (cfheader not yet present) -- no failed-verify discard either");
    check(g_getdataCount == 0, "isReady gate: no getdata -- eval never reached (gated before verify/parse)");
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 1, "isReady gate: H+1 still outstanding");

    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// Test 3b (final-review Minor #2, CLEAN-MISS branch): a buffered filter that
// VERIFIES and PARSES fine but does not match any wallet element (a genuine
// miss, not a header-race hit) must be scanned through -- MarkEvaluated fires
// (the height is truly resolved, nothing more will ever come for it) and,
// crucially, NO getdata is dispatched (there is nothing to fetch on a miss;
// dispatching one would be a wasted/wrong fetch). This is the `hit` branch of
// _cfBufEval taking the `if (hit)` == false fork all the way through to the
// shared `BRCFScanLedgerMarkEvaluated` / `return 1` tail.
static void test_buffered_clean_miss_marks_no_getdata(BRWallet *wallet)
{
    printf("\n=== test_buffered_clean_miss_marks_no_getdata ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    uint32_t H = 1100000;
    BRMerkleBlock *bH  = dummyBlock(H,     0x10, 1700500000);
    BRMerkleBlock *bH1 = dummyBlock(H + 1, 0x11, 1700500015);
    bH1->prevBlock = bH->blockHash;
    BRSetAdd(m->blocks, bH);
    BRSetAdd(m->blocks, bH1); // block header for H+1 IS connected
    m->lastBlock = bH1;

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x06; pa->port = 10006; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa); // present so a getdata COULD dispatch if (wrongly) triggered

    // cfheader chain covering H and H+1 -> NextHeight()==H+2 > H+1 (isReady's
    // cfheader gate passes), same shape as the crux test.
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, H, UINT256_ZERO);
    UInt256 dummyFilterHash; memset(dummyFilterHash.u8, 0x77, sizeof(dummyFilterHash.u8));
    check(BRCompactFilterChainAppend(m->compactFilterChain, BRCompactFilterChainTipHeader(m->compactFilterChain),
                                     &dummyFilterHash, 1) == 1, "setup: dummy header appended for height H");

    // Element deliberately NOT derived from the wallet at all (a fixed,
    // structurally-distinct byte pattern) -- BRGCSFilterMatchAny against the
    // real wallet elements must come back 0: a genuine clean miss, not a hit.
    static const uint8_t notWalletElem[20] = {
        0xC1, 0xC1, 0xC1, 0xC1, 0xC1, 0xC1, 0xC1, 0xC1, 0xC1, 0xC1,
        0xC1, 0xC1, 0xC1, 0xC1, 0xC1, 0xC1, 0xC1, 0xC1, 0xC1, 0xC1
    };
    uint8_t encoded[16];
    size_t encodedLen = buildSingleElementFilter(bH1->blockHash, notWalletElem, sizeof notWalletElem,
                                                 encoded, sizeof encoded);
    UInt256 filterHash; BRSHA256_2(filterHash.u8, encoded, encodedLen);
    check(BRCompactFilterChainAppend(m->compactFilterChain, BRCompactFilterChainTipHeader(m->compactFilterChain),
                                     &filterHash, 1) == 1, "setup: real filterHash appended for height H+1 (cfheader now present)");

    check(BRCompactFilterChainNextHeight(m->compactFilterChain) > bH1->height,
          "setup: cfheader present for H+1 (isReady's cfheader gate should pass)");
    // Setup-time sanity (per task): a broken encoder must fail loudly HERE,
    // not confusingly later inside the real drive path.
    check(BRCompactFilterChainVerifyFilter(m->compactFilterChain, bH1->height, encoded, encodedLen) == 1,
          "setup: hand-built filter verifies against the chain at H+1 (encoder sanity)");

    BRCFScanLedgerInit(&m->cfLedger, H);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H + 1, H + 1, UINT128_ZERO, 0, 0);
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 1, "setup: H+1 outstanding before drain");
    check(BRCFScanLedgerBufferFilter(&m->cfLedger, bH1->blockHash, encoded, encodedLen, (uint32_t)time(NULL)) == 1,
          "setup: filter buffered for H+1");
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1, "setup: buffer holds 1 entry");

    g_getdataCount = 0; g_capCount = 0;

    BRPeerManagerKeepAlive(m);

    check(g_getdataCount == 0, "CLEAN MISS: no getdata dispatched -- a miss must not fetch a block");
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 0,
          "CLEAN MISS: MarkEvaluated fired -- H+1 no longer outstanding (fully resolved, non-hit)");
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 0, "CLEAN MISS: buffer drained (entry removed after eval)");

    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// Test 3c (final-review Minor #2, VERIFY-FAIL branch): a buffered entry whose
// bytes do NOT verify against the cfheader commitment at that height (bytes
// for a DIFFERENT filter than the one actually committed) must be dropped
// from the buffer WITHOUT being marked evaluated -- it stays outstanding so
// the residual re-request driver can pick it back up. isReady is content-
// blind (block header connected + cfheader chain height gate only), so it
// stays TRUE here even though the committed cfheader hash doesn't match these
// bytes -- the drain genuinely enters _cfBufEval and fails at
// `BRCompactFilterChainVerifyFilter`, it is not skipped by isReady==false.
static void test_buffered_verify_fail_stays_outstanding(BRWallet *wallet)
{
    printf("\n=== test_buffered_verify_fail_stays_outstanding ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    uint32_t H = 1200000;
    BRMerkleBlock *bH  = dummyBlock(H,     0x20, 1700600000);
    BRMerkleBlock *bH1 = dummyBlock(H + 1, 0x21, 1700600015);
    bH1->prevBlock = bH->blockHash;
    BRSetAdd(m->blocks, bH);
    BRSetAdd(m->blocks, bH1); // block header for H+1 IS connected -> isReady's header gate passes
    m->lastBlock = bH1;

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x07; pa->port = 10007; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa); // present so a getdata COULD dispatch if (wrongly) triggered

    // cfheader chain covers H and H+1 -> NextHeight()==H+2 > H+1: isReady's
    // cfheader-presence gate ALSO passes (this is what keeps isReady TRUE).
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, H, UINT256_ZERO);
    UInt256 dummyFilterHash; memset(dummyFilterHash.u8, 0x77, sizeof(dummyFilterHash.u8));
    check(BRCompactFilterChainAppend(m->compactFilterChain, BRCompactFilterChainTipHeader(m->compactFilterChain),
                                     &dummyFilterHash, 1) == 1, "setup: dummy header appended for height H");

    // The COMMITTED filter (its hash is what gets appended to the cfheader
    // chain at H+1) is built over one element...
    static const uint8_t committedElem[20] = {
        0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA,
        0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA
    };
    uint8_t committedEncoded[16];
    size_t committedLen = buildSingleElementFilter(bH1->blockHash, committedElem, sizeof committedElem,
                                                    committedEncoded, sizeof committedEncoded);
    UInt256 committedFilterHash; BRSHA256_2(committedFilterHash.u8, committedEncoded, committedLen);
    check(BRCompactFilterChainAppend(m->compactFilterChain, BRCompactFilterChainTipHeader(m->compactFilterChain),
                                     &committedFilterHash, 1) == 1,
          "setup: COMMITTED filterHash appended for height H+1 (cfheader now present)");
    check(BRCompactFilterChainNextHeight(m->compactFilterChain) > bH1->height,
          "setup: cfheader present for H+1 (isReady's cfheader gate should pass)");

    // ...but the bytes actually BUFFERED for H+1 are built over a DIFFERENT
    // element -- a distinct, differently-encoded filter whose SHA256d will
    // NOT match committedFilterHash. isReady only checks block-header-
    // connected + cfheader-chain-height, never filter content, so this alone
    // does not stop the drain from reaching _cfBufEval.
    static const uint8_t buggeredElem[20] = {
        0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB,
        0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB, 0xBB
    };
    uint8_t buggeredEncoded[16];
    size_t buggeredLen = buildSingleElementFilter(bH1->blockHash, buggeredElem, sizeof buggeredElem,
                                                   buggeredEncoded, sizeof buggeredEncoded);
    check(BRCompactFilterChainVerifyFilter(m->compactFilterChain, bH1->height, buggeredEncoded, buggeredLen) == 0,
          "setup: buffered bytes do NOT verify against the committed cfheader at H+1 (encoder-mismatch sanity)");

    BRCFScanLedgerInit(&m->cfLedger, H);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H + 1, H + 1, UINT128_ZERO, 0, 0);
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 1, "setup: H+1 outstanding before drain");
    check(BRCFScanLedgerBufferFilter(&m->cfLedger, bH1->blockHash, buggeredEncoded, buggeredLen, (uint32_t)time(NULL)) == 1,
          "setup: mismatched filter buffered for H+1");
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1, "setup: buffer holds 1 entry");

    g_getdataCount = 0; g_capCount = 0;

    BRPeerManagerKeepAlive(m);

    check(g_getdataCount == 0, "VERIFY FAIL: no getdata dispatched");
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 1,
          "VERIFY FAIL: MarkEvaluated did NOT fire -- H+1 outstanding count UNCHANGED");
    check(findOutstanding(&m->cfLedger, H + 1) != NULL,
          "VERIFY FAIL: H+1 specifically stays outstanding (residual re-request will retry it)");
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 0,
          "VERIFY FAIL: entry removed from the buffer (dropped, not left resident)");

    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// Test 4: a residual (verify/parse/disconnect-class) hole with an EMPTY
// buffer re-requests via getcfilters and rotates away from the peer the
// original request targeted.
static void test_residual_rerequests_and_rotates(BRWallet *wallet)
{
    printf("\n=== test_residual_rerequests_and_rotates ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    uint32_t H = 800000;
    BRMerkleBlock *bH  = dummyBlock(H,     0xD0, 1700300000);
    BRMerkleBlock *bH1 = dummyBlock(H + 1, 0xD1, 1700300015);
    BRMerkleBlock *bH2 = dummyBlock(H + 2, 0xD2, 1700300030);
    bH1->prevBlock = bH->blockHash;
    bH2->prevBlock = bH1->blockHash;
    BRSetAdd(m->blocks, bH);
    BRSetAdd(m->blocks, bH1);
    BRSetAdd(m->blocks, bH2);
    m->lastBlock = bH2; // tip == H+2, so the coalesced [H..H+2] run is NOT tip-clipped

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x03; pa->port = 10003; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    BRPeer *pb = BRPeerNew(BRMainNetParams.magicNumber);
    pb->address.u8[15] = 0x04; pb->port = 10004; pb->services |= SERVICES_NODE_COMPACT_FILTERS;
    // Insertion order deliberately puts pa LAST (highest index): the driver's
    // peer-selection loop walks connectedPeers in reverse, so pa is checked
    // FIRST -- exercising the actual avoid-skip branch (pa is the "peer this
    // hole was last sent to", so it must be skipped) rather than pb winning
    // by iteration-order coincidence.
    array_add(m->connectedPeers, pb);
    array_add(m->connectedPeers, pa);

    BRCFScanLedgerInit(&m->cfLedger, H);
    // requestedAt=0 -> immediately due against the real clock (nowSec-0 >> 30s
    // base backoff). Do NOT try to "advance the clock" -- EDIT 2 reads time(NULL).
    BRCFScanLedgerRecordRequested(&m->cfLedger, H, H + 2, pa->address, pa->port, 0);
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 3, "setup: H..H+2 outstanding, targeted at pa");

    g_capCount = 0; g_capStart = 0;

    BRPeerManagerKeepAlive(m);

    check(g_capCount == 1, "residual driver: exactly one getcfilters sent (single coalesced run)");
    check(g_capStart == H, "residual driver: coalesced run starts at H");

    const BRCFOutstanding *eH  = findOutstanding(&m->cfLedger, H);
    const BRCFOutstanding *eH1 = findOutstanding(&m->cfLedger, H + 1);
    const BRCFOutstanding *eH2 = findOutstanding(&m->cfLedger, H + 2);
    check(eH && eH1 && eH2, "residual driver: all 3 heights still outstanding (re-requested, not evaluated)");
    check(eH && eH->attempts == 1 && eH1 && eH1->attempts == 1 && eH2 && eH2->attempts == 1,
          "residual driver: committed range H..H+2 bumped attempts to 1");
    check(eH && eH->port == pb->port && UInt128Eq(eH->peer, pb->address),
          "residual driver: rotate-away -- committed to pb");
    check(eH && ! (eH->port == pa->port && UInt128Eq(eH->peer, pa->address)),
          "residual driver: definitely NOT re-sent to pa (the peer that dropped it)");

    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// Test 5 (Task 4 -- livelock break): the residual re-request path is NO LONGER
// gated on an empty buffer. This REPLACES the old test_residual_gated_by_buffer
// (which locked in the very `if (BufferedCount==0)` gate Task 4 deletes). A
// STALE buffered entry whose blockHash was orphaned by a header re-sync (NOT in
// manager->blocks) keeps BufferedCount>0 forever; under the old global gate that
// starved residual re-request for EVERY height (the production livelock). With
// the gate deleted + the O(1) reverse-map suppressor, the orphaned hash resolves
// to NULL (contributes no skip height), so a genuinely-due residual hole is
// still re-requested despite the stale buffer. RED against the gate-present code
// (the gate suppresses the send -> g_capCount stays 0).
static void test_residual_not_starved_by_stale_buffer(BRWallet *wallet)
{
    printf("\n=== test_residual_not_starved_by_stale_buffer (Task 4 livelock break) ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    uint32_t H = 200;
    BRMerkleBlock *bH = dummyBlock(H, 0xA0, 1700000000);
    BRSetAdd(m->blocks, bH);
    m->lastBlock = bH; // tip == 200 so the residual [200..200] run is not tip-clipped

    // A connected CF-capable peer, else `if (!chosen) break` (BRPeerManager.c)
    // would (falsely) suppress the send -- a setup bug, not a result.
    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x11; pa->port = 11001; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    BRCFScanLedgerInit(&m->cfLedger, H);
    // Height 200 outstanding + DUE: requestedAt=0 vs the real time(NULL) clock the
    // driver reads (>> the 30s base backoff), so PeekRerequestRange offers it.
    BRCFScanLedgerRecordRequested(&m->cfLedger, H, H, UINT128_ZERO, 0, 0);
    check(findOutstanding(&m->cfLedger, H) != NULL, "setup: height 200 outstanding");

    // A STALE buffered entry: its blockHash is NOT in m->blocks (orphaned by a
    // header re-sync). It cannot drain (isReady -> BRSetGet NULL) and keeps
    // BufferedCount>0 forever -- exactly the residual-starving wedge.
    UInt256 orphanHash; memset(orphanHash.u8, 0xEE, sizeof(orphanHash.u8));
    uint8_t junk[4] = { 0x01, 0x00, 0x00, 0x00 };
    BRCFScanLedgerBufferFilter(&m->cfLedger, orphanHash, junk, sizeof junk, (uint32_t)time(NULL));
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1,
          "setup: 1 stale (orphaned-hash) buffered entry -> BufferedCount>0");

    g_capCount = 0; g_capStart = 0;

    BRPeerManagerKeepAlive(m);

    check(g_capCount >= 1,
          "livelock break: residual getcfilters STILL fired despite the stale buffer (gate deleted)");
    check(g_capStart == H,
          "livelock break: the residual re-request targeted the outstanding hole (height 200)");
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1,
          "stale buffered entry untouched (orphan hash still unresolvable, suppresses nothing)");

    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// Test 6 (Task 4 -- suppressor skips the in-flight height): a buffered entry
// whose blockHash IS in manager->blocks at height H, but whose cfheader chain
// has NOT advanced past H (cfheader-lag), stays buffered AND stays outstanding.
// The O(1) reverse-map suppressor must resolve that buffered hash -> height H
// and SKIP re-requesting H (it is in-flight via the buffer path), while a
// SIBLING genuine residual hole at a different height IS re-requested. Proves
// the skip is height-targeted (via the reverse map), not a blanket
// re-suppression, and that NO forward canonical(H) walk is used. RED against the
// gate-present code (buffer non-empty -> the whole residual path is gated off ->
// the sibling is NOT re-requested either).
static void test_residual_skips_cfheader_lag_height(BRWallet *wallet)
{
    printf("\n=== test_residual_skips_cfheader_lag_height (Task 4 suppressor) ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    uint32_t H   = 200;   // cfheader-lag buffered height (in blocks, cfheader NOT advanced past it)
    uint32_t SIB = 205;   // sibling genuine residual hole (not buffered)
    BRMerkleBlock *bH   = dummyBlock(H,   0xB0, 1700000000);
    BRMerkleBlock *bSib = dummyBlock(SIB, 0xB5, 1700000075);
    BRSetAdd(m->blocks, bH);
    BRSetAdd(m->blocks, bSib);
    m->lastBlock = bSib;  // tip == 205 so the sibling [205..205] run is not tip-clipped

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x12; pa->port = 11002; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    // cfheader chain that has NOT advanced past H: NextHeight()==200 <= H(200),
    // so isReady gates the buffered entry OFF -> it stays buffered (in-flight),
    // NOT drained. (A fresh chain reports NextHeight == startHeight.)
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, H, UINT256_ZERO);
    check(BRCompactFilterChainNextHeight(m->compactFilterChain) <= bH->height,
          "setup: cfheader chain has NOT advanced past H (cfheader-lag -> entry stays buffered)");

    BRCFScanLedgerInit(&m->cfLedger, H);
    // Both H and the sibling outstanding + due (requestedAt=0 vs the real clock).
    BRCFScanLedgerRecordRequested(&m->cfLedger, H,   H,   UINT128_ZERO, 0, 0);
    BRCFScanLedgerRecordRequested(&m->cfLedger, SIB, SIB, UINT128_ZERO, 0, 0);

    // Buffer a filter keyed to bH's OWN hash: its header IS present at height 200,
    // but the cfheader-lag keeps it buffered -> the reverse map resolves it to 200.
    uint8_t junk[4] = { 0x01, 0x00, 0x00, 0x00 };
    BRCFScanLedgerBufferFilter(&m->cfLedger, bH->blockHash, junk, sizeof junk, (uint32_t)time(NULL));
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1,
          "setup: 1 cfheader-lag buffered entry (hash IS in blocks at H)");

    g_capCount = 0; g_capStart = 0;

    BRPeerManagerKeepAlive(m);

    check(g_capCount == 1, "suppressor: exactly one residual getcfilters sent (the sibling, not H)");
    check(g_capStart == SIB,
          "suppressor: the send targeted the SIBLING hole (205), skipping the in-flight H (200)");

    const BRCFOutstanding *eH   = findOutstanding(&m->cfLedger, H);
    const BRCFOutstanding *eSib = findOutstanding(&m->cfLedger, SIB);
    check(eH && eH->attempts == 0,
          "suppressor: H (200) was SKIPPED via the reverse map -- attempts still 0 (never committed)");
    check(eSib && eSib->attempts == 1,
          "suppressor: the sibling (205) WAS re-requested -- attempts bumped to 1");
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1,
          "cfheader-lag entry stays buffered (still in-flight)");

    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// Test 6: the residual driver caps a coalesced re-request run at the local
// block tip -- heights beyond the tip stay outstanding, uncommitted
// (attempts==0), so they are offered again once the tip actually advances.
static void test_residual_caps_at_tip(BRWallet *wallet)
{
    printf("\n=== test_residual_caps_at_tip ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    uint32_t H = 1000000;
    BRMerkleBlock *bH  = dummyBlock(H,     0xF0, 1700400000);
    BRMerkleBlock *bH1 = dummyBlock(H + 1, 0xF1, 1700400015);
    bH1->prevBlock = bH->blockHash;
    BRSetAdd(m->blocks, bH);
    BRSetAdd(m->blocks, bH1); // b_{H+2} deliberately NOT seeded
    m->lastBlock = bH1;       // tip is H+1

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x05; pa->port = 10005; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    BRCFScanLedgerInit(&m->cfLedger, H);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H, H + 2, pa->address, pa->port, 0);

    g_capCount = 0; g_capStart = 0;

    BRPeerManagerKeepAlive(m);

    check(g_capCount == 1, "caps-at-tip: exactly one getcfilters sent");
    check(g_capStart == H, "caps-at-tip: send starts at H");

    const BRCFOutstanding *eH  = findOutstanding(&m->cfLedger, H);
    const BRCFOutstanding *eH1 = findOutstanding(&m->cfLedger, H + 1);
    const BRCFOutstanding *eH2 = findOutstanding(&m->cfLedger, H + 2);
    check(eH && eH->attempts == 1, "caps-at-tip: H committed (attempts=1, send covers H..H+1)");
    check(eH1 && eH1->attempts == 1, "caps-at-tip: H+1 committed (attempts=1, send covers H..H+1)");
    check(eH2 && eH2->attempts == 0,
          "caps-at-tip: H+2 stays outstanding, attempts==0 (beyond tip, not committed)");

    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// Scenario A (Task 5 acceptance property): a whole RESIDUAL cluster converges
// to outstanding==0 across ticks WITH a stale buffer resident throughout -- the
// exact wedge regime. The drain is CAUSAL on real residual re-requests (the
// drive-KAT runs the real BRPeerManagerKeepAlive residual loop; serveSome only
// evaluates heights that loop actually captured on the wire), so the gate-
// present build (re-add of the deleted `if (BufferedCount==0)`) freezes here:
// no capture -> nothing served -> outstanding never reaches 0. Task 4's Test 5/6
// prove the per-tick mechanism; THIS proves the emergent convergence they don't.
static void test_cluster_drains_to_zero_with_stale_buffer(BRWallet *wallet)
{
    printf("\n=== test_cluster_drains_to_zero_with_stale_buffer (Scenario A: acceptance property) ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    blockRegReset();
    everReqReset();

    // Contiguous, prevBlock-chained header chain [FLOOR..TIP] so
    // _BRPeerManagerBlockHashAtHeight (which walks lastBlock backward via
    // prevBlock) resolves the stopHash of every residual re-request. Realistic
    // residual regime: headers are fully synced, only the cfilters were dropped.
    // Register each block into the test-side stopHash->height map.
    const uint32_t FLOOR = 1000, TIP = 1060;
    BRMerkleBlock *prevB = NULL;
    for (uint32_t h = FLOOR; h <= TIP; h++) {
        BRMerkleBlock *b = dummyBlock(h, (uint8_t)(h - FLOOR + 1), 1700000000u + (h - FLOOR) * 15);
        if (prevB) b->prevBlock = prevB->blockHash;
        BRSetAdd(m->blocks, b);
        blockRegAdd(b);
        prevB = b;
    }
    m->lastBlock = prevB; // tip == TIP, past the whole cluster (no tip-clip)

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x21; pa->port = 12001; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    // 40 RESIDUAL outstanding holes across TWO gaps -> two coalesced peek runs:
    //   [1000..1019] and [1030..1049]   (1020..1029 already evaluated: the gap).
    // All DUE (requestedAt=0 vs the real time(NULL) clock >> the 30s base
    // backoff). They hold NO buffered bytes of their own, so they can ONLY be
    // resolved via residual re-request -- never the ungated buffer-drain path.
    BRCFScanLedgerInit(&m->cfLedger, FLOOR);
    BRCFScanLedgerRecordRequested(&m->cfLedger, 1000, 1019, UINT128_ZERO, 0, 0);
    BRCFScanLedgerRecordRequested(&m->cfLedger, 1030, 1049, UINT128_ZERO, 0, 0);
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 40, "setup: 40 residual holes outstanding across 2 gaps");

    // One ORPHANED stale buffered entry: its hash is NOT in m->blocks (a header
    // re-sync orphaned it). It can never drain (isReady -> BRSetGet NULL) and
    // can never age out inside this sub-second loop (firstAt=now, 900s
    // threshold), so BufferedCount stays 1 throughout -- exactly the residual-
    // starving wedge the old `if (BufferedCount==0)` gate created.
    UInt256 orphanHash; memset(orphanHash.u8, 0xEE, sizeof orphanHash.u8);
    uint8_t junk[4] = { 0x01, 0x00, 0x00, 0x00 };
    BRCFScanLedgerBufferFilter(&m->cfLedger, orphanHash, junk, sizeof junk, (uint32_t)time(NULL));
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1, "setup: 1 stale orphan buffered -> BufferedCount>0 throughout");

    // Drive: KeepAlive (the real residual re-request) then serve a STAGGERED
    // subset (k=8) of the CUMULATIVE captured re-request set, modeling responses
    // trickling in over ticks. Assert the invariant AT EVERY TICK.
    LedgerSnap prev; snapLedger(&m->cfLedger, &prev);
    const int K = 8;
    int drained = 0;
    for (int tick = 1; tick <= 20; tick++) {
        BRPeerManagerKeepAlive(m);
        uint32_t served[128];
        int nServed = serveSome(m, K, served);
        checkTick(m, &prev, served, nServed, /*expBuffered=*/1, tick);
        snapLedger(&m->cfLedger, &prev);
        if (BRCFScanLedgerOutstandingCount(&m->cfLedger) == 0) { drained = 1; break; }
    }

    check(drained, "ACCEPTANCE: cluster converged to outstanding==0 within the tick budget");
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 0, "ACCEPTANCE endpoint: outstanding == 0");
    check(BRCFScanLedgerGaveUpCount(&m->cfLedger) == 0,
          "ACCEPTANCE endpoint: gaveUp == 0 (every hole was served, none abandoned)");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) == 1049,
          "ACCEPTANCE endpoint: scannedThrough advanced to the cluster top (== requestedThrough)");

    // (d) reclamation: the orphan never blocked a hole (the cluster drained
    // while it stayed buffered). Now prove it is ALSO eventually reclaimed by
    // age-out -- backdate its IMMUTABLE firstAt past the 900s threshold and run
    // one more tick. EvictAgedFilters frees it; touches only the buffer
    // (outstanding is already 0).
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1,
          "post-drain: orphan still buffered (it never blocked and never drained)");
    if (m->cfLedger.filterBufCount == 1) {
        m->cfLedger.filterBuf[0]->firstAt = (uint32_t)time(NULL) - (CF_FILTER_BUF_MAX_AGE_SECS + 100);
    }
    BRPeerManagerKeepAlive(m);
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 0,
          "post-drain: aged orphan reclaimed by age-out (buffer empty) -- invariant (d)");

    BRPeerManagerFree(m);
}

// ---------------------------------------------------------------------------
// Scenario B (Task 5): gaveUp is the now-LIVE RetireCapped signal, exercised in
// BOTH directions at the exact attempt cap. RetireCapped moved inside
// BRPeerManagerKeepAlive when Task 4 deleted the buffer gate (it was dead code
// behind it). Verified against BRCFScanLedger.c BRCFScanLedgerRetireCapped:
//   for (...) if (l->outstanding[i].attempts >= CF_REREQ_MAX_ATTEMPTS) moveToGaveUp
// so the comparison is `>=` -- attempts == CF_REREQ_MAX_ATTEMPTS retires, and
// attempts == CF_REREQ_MAX_ATTEMPTS-1 does NOT. Tested WITHOUT a 7.5-min clock
// advance by pre-seeding `attempts` directly.
static void test_gaveup_retires_dead_hole_at_exact_cap(BRWallet *wallet)
{
    printf("\n=== test_gaveup_retires_dead_hole_at_exact_cap (Scenario B: RetireCapped both directions) ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    blockRegReset();
    everReqReset();

    // Contiguous header chain [FLOOR..TIP] so the slow hole's re-request stop
    // resolves; register each block for stopHash->height resolution.
    const uint32_t FLOOR = 300, TIP = 315;
    BRMerkleBlock *prevB = NULL;
    for (uint32_t h = FLOOR; h <= TIP; h++) {
        BRMerkleBlock *b = dummyBlock(h, (uint8_t)(h - FLOOR + 1), 1700000000u + (h - FLOOR) * 15);
        if (prevB) b->prevBlock = prevB->blockHash;
        BRSetAdd(m->blocks, b);
        blockRegAdd(b);
        prevB = b;
    }
    m->lastBlock = prevB;

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x31; pa->port = 13001; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    const uint32_t H_DEAD = 300, H_SIB = 301, H_SLOW = 310;
    uint32_t nowSec = (uint32_t)time(NULL);

    BRCFScanLedgerInit(&m->cfLedger, FLOOR);
    // now=0 -> requestedAt=0 (DUE) by default; overridden below for the two
    // capped/near-capped holes.
    BRCFScanLedgerRecordRequested(&m->cfLedger, H_DEAD, H_DEAD, UINT128_ZERO, 0, 0);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H_SIB,  H_SIB,  UINT128_ZERO, 0, 0);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H_SLOW, H_SLOW, UINT128_ZERO, 0, 0);

    // Pre-seed attempts DIRECTLY (full struct access; no 7.5-min clock advance).
    // The dead + sibling holes are made NON-DUE (requestedAt=nowSec, so the real
    // time(NULL) clock KeepAlive reads shows ~0 elapsed << their backoff): this
    // stops the peek loop from re-requesting+re-committing them this tick, which
    // would bump attempts and confound the boundary. Only RetireCapped (which
    // ignores due-ness) acts on them. H_SLOW keeps requestedAt=0 (DUE) so the
    // driver re-requests it -> a legitimate captured serve.
    BRCFOutstanding *ed = mutOutstanding(&m->cfLedger, H_DEAD);
    BRCFOutstanding *es = mutOutstanding(&m->cfLedger, H_SIB);
    check(ed && es, "setup: dead + sibling outstanding entries located");
    ed->attempts    = CF_REREQ_MAX_ATTEMPTS;       // exactly AT the cap -> must retire
    ed->requestedAt = nowSec;                       // non-due (moot: retired before peek)
    es->attempts    = CF_REREQ_MAX_ATTEMPTS - 1;   // one BELOW the cap -> must NOT retire
    es->requestedAt = nowSec;                       // non-due so peek won't bump it to the cap

    g_capCount = 0; g_capStart = 0;

    BRPeerManagerKeepAlive(m);

    // (i) exact-cap dead hole retired to gaveUp.
    check(findOutstanding(&m->cfLedger, H_DEAD) == NULL,
          "(i) dead hole at attempts==CAP retired -> gone from outstanding");
    check(gaveUpContains(&m->cfLedger, H_DEAD),
          "(i) dead hole at attempts==CAP present in gaveUp");

    // (ii) "not before the cap": sibling one below cap stays outstanding, untouched.
    const BRCFOutstanding *sibAfter = findOutstanding(&m->cfLedger, H_SIB);
    check(sibAfter != NULL, "(ii) sibling at attempts==CAP-1 still outstanding (NOT retired)");
    check(sibAfter && sibAfter->attempts == CF_REREQ_MAX_ATTEMPTS - 1,
          "(ii) sibling attempts unchanged at CAP-1 (non-due -> peek left it alone)");
    check(! gaveUpContains(&m->cfLedger, H_SIB), "(ii) sibling NOT in gaveUp");

    // The slow hole WAS re-requested this tick (its serve below is causal).
    check(g_capStart == H_SLOW, "slow hole re-requested this tick (getcfilters start == H_SLOW)");

    // (iii) "slow but served": serve it via the captured re-request -> drains
    // clean, NEVER touches gaveUp.
    uint32_t served[4];
    int n = serveSome(m, 4, served);
    check(n == 1 && served[0] == H_SLOW, "(iii) slow hole served from the captured re-request set");
    check(findOutstanding(&m->cfLedger, H_SLOW) == NULL, "(iii) slow hole drained -> gone from outstanding");
    check(! gaveUpContains(&m->cfLedger, H_SLOW), "(iii) slow hole NEVER touched gaveUp");

    // Boundary pinned in both directions: gaveUp holds EXACTLY the one dead hole.
    check(BRCFScanLedgerGaveUpCount(&m->cfLedger) == 1,
          "gaveUp holds exactly the one dead hole (both directions of the cap pinned)");

    BRPeerManagerFree(m);
}

// ============================================================================
// Task 2: single-descent batch stop-hash resolver — ADVERSARIAL equivalence KAT
// ============================================================================
//
// _BRPeerManagerResolveHashesAtHeightsLocked(manager, heights, n, outHashes)
// resolves N heights to block hashes in ONE descent from lastBlock. It MUST
// return, for every heights[i], the BYTE-IDENTICAL UInt256 that N independent
// _BRPeerManagerBlockHashAtHeight(manager, heights[i]) calls return. A wrong
// hash here becomes a wrong getcfilters stop-hash = a silent wrong-range fetch,
// so this equivalence is the linchpin of the whole retention-floor approach.
//
// dummyBlock() (above) sets blockHash = memset(single seed byte) -> only 256
// distinct hashes; BRSet dedups by hash so a many-block chain collapses to ~256
// and the prevBlock walk is meaningless (false-green trap). This test instead
// writes the HEIGHT into the hash (rhUniqueHash) so every height owns a unique,
// never-zero hash, and links each block's prevBlock to the previous height's
// unique hash — a real prevBlock-linked chain the naive walk actually traverses.

// Unique, never-UINT256_ZERO hash keyed by height. u32[0] carries the height
// (distinct per height); the rest carry a fixed non-zero magic so no real block
// ever collides with the UINT256_ZERO "not found" sentinel the resolvers emit.
static UInt256 rhUniqueHash(uint32_t height)
{
    UInt256 h = UINT256_ZERO;
    h.u32[0] = height;
    h.u32[1] = 0x9E3779B9u ^ height;   // extra mixing (u32[0] alone already unique)
    h.u32[2] = 0xA5A5A5A5u;
    h.u32[7] = 0x5A5A5A5Au;            // guarantees hash != UINT256_ZERO
    return h;
}

// A DIFFERENT, still-unique, non-zero hash for the same height — used for the
// orphan/fork block. Differs from rhUniqueHash(height) in u32[3] (which the
// main-chain hash leaves 0), so it can never equal any main-chain hash.
static UInt256 rhForkHash(uint32_t height)
{
    UInt256 h = rhUniqueHash(height);
    h.u32[3] = 0xDEADBEEFu;
    return h;
}

// Real prevBlock-linked main-chain header: unique hash at `height`, prevBlock =
// the unique hash of `height-1` (whose block is only in the set if that height
// was built, so the chain terminates cleanly below the base height).
static BRMerkleBlock *rhChainBlock(uint32_t height)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    b->blockHash = rhUniqueHash(height);
    b->prevBlock = rhUniqueHash(height - 1);
    b->height    = height;
    b->timestamp = 1700000000u + height;
    return b;
}

// Deterministic PRNG (xorshift32, fixed seed) — NO Math.random / time(); the
// randomized height sets are reproducible run-to-run.
static uint32_t rhRngState = 0x1234567u;
static uint32_t rhRand(void)
{
    uint32_t x = rhRngState;
    x ^= x << 13; x ^= x >> 17; x ^= x << 5;
    rhRngState = x;
    return x;
}

#define RH_MAXN 200

// Run one explicit height set through BOTH resolvers; 1 iff byte-identical for
// every element (same order). The batch output is poisoned first with a value
// no naive result can equal (rhForkHash's u32[3]=0xDEADBEEF vs. main-chain 0,
// and non-zero vs. the ZERO sentinel) so a slot the resolver forgot to write is
// caught as a mismatch, not silently accepted.
static int rhBatchEqualsNaive(BRPeerManager *m, const uint32_t *hs, size_t n)
{
    UInt256 nv[RH_MAXN], bt[RH_MAXN];
    for (size_t i = 0; i < n; i++) nv[i] = _BRPeerManagerBlockHashAtHeight(m, hs[i]);
    for (size_t i = 0; i < n; i++) bt[i] = rhForkHash(0xFFFFFFFFu);   // poison
    _BRPeerManagerResolveHashesAtHeightsLocked(m, hs, n, bt);
    for (size_t i = 0; i < n; i++) if (! UInt256Eq(bt[i], nv[i])) return 0;
    return 1;
}

static void test_batch_resolve_equals_naive(BRWallet *wallet)
{
    printf("\n=== test_batch_resolve_equals_naive (Task 2 linchpin) ===\n");
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    check(m != NULL, "manager created");
    if (! m) return;

    const uint32_t BASE  = 1000;
    const uint32_t COUNT = 4000;
    const uint32_t TIP   = BASE + COUNT - 1;   // 4999

    // BRPeerManagerNew pre-seeds manager->blocks with the params' checkpoint
    // headers (mainnet has one at height 0 and one at 5000, both OUTSIDE the
    // [BASE..TIP] chain we build, so neither is ever on the walk from lastBlock),
    // so the set is NOT empty here. Capture that baseline and assert on the DELTA:
    // the point is that the COUNT distinct-hash blocks we add below never
    // dedup/collide (dummyBlock's 256-hash trap would show up as a shortfall).
    const size_t baseCount = BRSetCount(m->blocks);

    // Build a real prevBlock-linked main chain with distinct-per-height hashes.
    BRMerkleBlock *tip = NULL;
    for (uint32_t hgt = BASE; hgt <= TIP; hgt++) {
        BRMerkleBlock *b = rhChainBlock(hgt);
        BRSetAdd(m->blocks, b);
        tip = b;
    }
    m->lastBlock = tip;

    // No collisions: distinct hashes => the set holds EXACTLY COUNT blocks
    // (dummyBlock's 256-collision trap would surface right here).
    check(BRSetCount(m->blocks) == baseCount + COUNT, "distinct-hash chain: BRSetCount grew by exactly block count (no collisions)");
    check(m->lastBlock && m->lastBlock->height == TIP, "lastBlock is the tip");
    check(UInt256Eq(_BRPeerManagerBlockHashAtHeight(m, BASE + 1234), rhUniqueHash(BASE + 1234)),
          "sanity: naive resolves a present mid-chain height to its unique hash");

    // (g) FORK: an orphan at H_FORK with a DIFFERENT hash, NOT reachable from
    // lastBlock's prevBlock chain (no block's prevBlock points at it). It
    // coexists in the set (distinct hash, not deduped); both resolvers must
    // return the MAIN-chain hash, never the orphan.
    const uint32_t H_FORK = BASE + 2000;   // 3000
    BRMerkleBlock *orphan = BRMerkleBlockNew();
    orphan->blockHash = rhForkHash(H_FORK);
    orphan->prevBlock = rhUniqueHash(H_FORK - 1);
    orphan->height    = H_FORK;
    orphan->timestamp = 1700000000u + H_FORK;
    BRSetAdd(m->blocks, orphan);
    check(BRSetCount(m->blocks) == baseCount + COUNT + 1, "orphan coexists in set (distinct hash, not deduped)");

    // ---- randomized property engine: batch byte-identical to naive ----
    // Range spans BELOW base, the whole chain, and ABOVE tip so every draw
    // exercises present / off-the-bottom / above-tip. Deterministic (rhRand).
    uint32_t heights[RH_MAXN];
    int mismatches = 0, coveredAboveTip = 0, coveredBelow = 0, coveredPresent = 0;
    for (int iter = 0; iter < 400; iter++) {
        size_t n = 1 + (rhRand() % RH_MAXN);        // 1..RH_MAXN (empty tested separately)
        for (size_t i = 0; i < n; i++) {
            heights[i] = (BASE - 200) + (rhRand() % (COUNT + 400));   // [800 .. 5199]
            if (heights[i] > TIP) coveredAboveTip = 1;
            else if (heights[i] < BASE) coveredBelow = 1;
            else coveredPresent = 1;
        }
        UInt256 nv[RH_MAXN], bt[RH_MAXN];
        for (size_t i = 0; i < n; i++) nv[i] = _BRPeerManagerBlockHashAtHeight(m, heights[i]);
        for (size_t i = 0; i < n; i++) bt[i] = rhForkHash(0xFFFFFFFFu);   // poison
        _BRPeerManagerResolveHashesAtHeightsLocked(m, heights, n, bt);
        for (size_t i = 0; i < n; i++) if (! UInt256Eq(bt[i], nv[i])) mismatches++;
    }
    check(mismatches == 0, "randomized sets (400 iters, n up to 200): batch byte-identical to naive");
    check(coveredAboveTip && coveredBelow && coveredPresent,
          "randomized coverage spanned present + off-bottom + above-tip heights");

    // ---- (a) unsorted input ----
    uint32_t hs_a[] = { BASE + 2500, BASE + 10, TIP, BASE + 1200, BASE, BASE + 3999 };
    check(rhBatchEqualsNaive(m, hs_a, 6), "(a) unsorted input: batch==naive");

    // ---- (b) duplicate heights -> same hash twice ----
    uint32_t hs_b[] = { BASE + 2500, BASE + 2500, BASE + 2500, BASE + 4000, BASE + 4000 };
    check(rhBatchEqualsNaive(m, hs_b, 5), "(b) duplicate heights: batch==naive");
    {
        UInt256 outb[5];
        _BRPeerManagerResolveHashesAtHeightsLocked(m, hs_b, 5, outb);
        check(UInt256Eq(outb[0], outb[1]) && UInt256Eq(outb[1], outb[2]) &&
              UInt256Eq(outb[0], rhUniqueHash(BASE + 2500)),
              "(b) duplicate height resolves to the SAME unique hash each time");
    }

    // ---- (c) heights ABOVE the tip -> ZERO ----
    uint32_t hs_c[] = { TIP + 1, TIP + 100, TIP + 5000, 0xFFFFFF00u };
    check(rhBatchEqualsNaive(m, hs_c, 4), "(c) above-tip heights: batch==naive");
    {
        UInt256 outc[4];
        _BRPeerManagerResolveHashesAtHeightsLocked(m, hs_c, 4, outc);
        int allZero = 1;
        for (int i = 0; i < 4; i++) if (! UInt256Eq(outc[i], UINT256_ZERO)) allZero = 0;
        check(allZero, "(c) above-tip heights resolve to UINT256_ZERO (clean)");
    }

    // ---- (d) heights BELOW the retained window / not present -> ZERO cleanly ----
    uint32_t hs_d[] = { BASE - 1, BASE - 100, 0, 500 };
    // confirm the naive truth is itself ZERO here (setup really makes them absent)
    {
        int allNaiveZero = 1;
        for (int i = 0; i < 4; i++)
            if (! UInt256Eq(_BRPeerManagerBlockHashAtHeight(m, hs_d[i]), UINT256_ZERO)) allNaiveZero = 0;
        check(allNaiveZero, "(d) below-window heights are genuinely absent (naive == ZERO)");
    }
    check(rhBatchEqualsNaive(m, hs_d, 4), "(d) below-window heights: batch==naive");
    {
        UInt256 outd[4];
        _BRPeerManagerResolveHashesAtHeightsLocked(m, hs_d, 4, outd);
        int allZero = 1;
        for (int i = 0; i < 4; i++) if (! UInt256Eq(outd[i], UINT256_ZERO)) allZero = 0;
        check(allZero, "(d) below-window heights resolve to UINT256_ZERO (clean, not garbage)");
    }

    // ---- (e) a single height ----
    uint32_t hs_e[] = { BASE + 1777 };
    check(rhBatchEqualsNaive(m, hs_e, 1), "(e) single height: batch==naive");
    {
        UInt256 oute[1];
        _BRPeerManagerResolveHashesAtHeightsLocked(m, hs_e, 1, oute);
        check(UInt256Eq(oute[0], rhUniqueHash(BASE + 1777)), "(e) single present height resolves to its unique hash");
    }

    // ---- (f) the empty set (n==0): a no-op; must not write or crash ----
    {
        UInt256 canary = rhForkHash(0x12345678u);
        UInt256 out0 = canary;
        _BRPeerManagerResolveHashesAtHeightsLocked(m, heights, 0, &out0);
        check(UInt256Eq(out0, canary), "(f) empty set (n==0): output untouched, no crash");
        // NULL args with n==0 must also be a clean no-op.
        _BRPeerManagerResolveHashesAtHeightsLocked(m, NULL, 0, NULL);
        check(1, "(f) NULL heights/out with n==0: clean no-op (no crash)");
    }

    // ---- (g) heights straddling the FORK point ----
    uint32_t hs_g[] = { H_FORK, H_FORK - 1, H_FORK + 1 };
    check(rhBatchEqualsNaive(m, hs_g, 3), "(g) fork-straddling heights: batch==naive");
    {
        UInt256 outg[3];
        _BRPeerManagerResolveHashesAtHeightsLocked(m, hs_g, 3, outg);
        check(UInt256Eq(outg[0], rhUniqueHash(H_FORK)), "(g) fork height resolves to the MAIN-chain hash");
        check(! UInt256Eq(outg[0], rhForkHash(H_FORK)), "(g) fork height NEVER resolves to the orphan hash");
        check(UInt256Eq(_BRPeerManagerBlockHashAtHeight(m, H_FORK), rhUniqueHash(H_FORK)),
              "(g) naive also resolves the fork height to the MAIN-chain hash");
    }

    BRPeerManagerFree(m);
}

int main(void)
{
    // --- Smoke test: the compile-time gate the whole Phase 2 driver is
    // built behind is actually flipped ON for this KAT build (run.sh passes
    // -DCF_LEDGER_DRIVE_REREQUEST=1). As of this branch the production default
    // in BRCFScanLedger.h is ALSO 1 (Task 6 flipped it); -D just makes the KAT
    // independent of that default. ---
    check(CF_LEDGER_DRIVE_REREQUEST == 1, "smoke: CF_LEDGER_DRIVE_REREQUEST compiled in as 1 for this KAT");

    // --- Smoke test: the harness can build and tear down a real
    // BRPeerManager (backed by a real BRWallet) LSan-clean, proving out the
    // scaffolding the real driver cases below reuse. No peers are connected
    // and no driver logic is exercised here -- that's every test below. ---
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));

    BRWallet *wallet = BRWalletNew(NULL, 0, mpk);
    check(wallet != NULL, "smoke: wallet created");
    if (!wallet) { printf("\nFATAL\n"); return 1; }

    BRPeerManager *manager = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    check(manager != NULL, "smoke: peer manager created");
    if (!manager) { BRWalletFree(wallet); printf("\nFATAL\n"); return 1; }

    // Referencing the capture globals here (rather than leaving them
    // write-only) keeps -Wunused-but-set-variable quiet under stricter
    // flags and documents that the real driver tests below read exactly
    // these four: the wrap shims above are otherwise never called on this
    // build/teardown-only path (no BRPeerManagerConnect happens in this KAT).
    check(g_capCount == 0 && g_capStart == 0, "smoke: getcfilters capture seam untouched before any drive");
    check(g_getdataCount == 0, "smoke: getdata capture seam untouched before any drive");
    (void)g_getdataHash;

    BRPeerManagerFree(manager);

    // --- Real Task 5 driver cases (plus the final-review Minor #2 CLEAN-MISS
    // / VERIFY-FAIL additions). All reuse this same wallet (it is never
    // mutated by BRPeerManagerNew/Free -- only referenced), each building its
    // own fresh BRPeerManager so state never leaks test-to-test. ---
    test_buffered_drains_and_CREDITS_at_connect(wallet);
    test_buffered_hit_no_peer_stays(wallet);
    test_buffered_waits_for_cfheader(wallet);
    test_buffered_clean_miss_marks_no_getdata(wallet);
    test_buffered_verify_fail_stays_outstanding(wallet);
    test_residual_rerequests_and_rotates(wallet);
    test_residual_not_starved_by_stale_buffer(wallet); // Task 4: livelock break (was test_residual_gated_by_buffer)
    test_residual_skips_cfheader_lag_height(wallet);    // Task 4: O(1) reverse-map suppressor
    test_residual_caps_at_tip(wallet);
    test_cluster_drains_to_zero_with_stale_buffer(wallet); // Task 5 Scenario A: multi-tick cluster drain-to-zero
    test_gaveup_retires_dead_hole_at_exact_cap(wallet);    // Task 5 Scenario B: RetireCapped both directions at cap
    test_batch_resolve_equals_naive(wallet);               // Task 2: batch stop-hash resolver == naive (adversarial)

    BRWalletFree(wallet);

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
