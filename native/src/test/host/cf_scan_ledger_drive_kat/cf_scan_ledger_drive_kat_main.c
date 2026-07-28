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
#include <stdarg.h>

// --- CF-retention memory-ceiling WARN-log capture seam (Task 4) --------------
// _cfApplyRetentionCeiling warn-logs an "ABANDONED ..." line whenever the memory
// ceiling abandons >=1 retry-exhausted (gaveUp) height. In production that line
// routes to the platform logger at WARN (tag "bread"); on this host build it is
// otherwise silent. BRPeerManager.c #ifndef-guards CF_RETENTION_WLOG so this KAT
// can pre-#define it to capture the line and assert (a) it FIRED with the right
// count on the ceiling case, and (b) it did NOT fire on the no-abandon retain
// case (== the LAB "abandonedBelow stayed 0, no ABANDONED line" acceptance).
static int  g_wlogCount = 0;
static char g_wlogLast[512];
static int  test_cf_retention_wlog(const char *fmt, ...)
{
    va_list ap; va_start(ap, fmt);
    vsnprintf(g_wlogLast, sizeof g_wlogLast, fmt, ap);
    va_end(ap);
    g_wlogCount++;
    printf("WLOG> %s\n", g_wlogLast);
    return 0;
}
#define CF_RETENTION_WLOG(...) test_cf_retention_wlog(__VA_ARGS__)

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

// --- Task 3: full send-log + between-pass drain hook -------------------------
//
// The pre-restructure residual loop peeked/sent/committed ONE range per
// iteration, so g_capStart (last start) + g_capCount sufficed. The Pass A->B->C
// restructure sends MULTIPLE ranges per tick, so the batched-semantics test
// needs the WHOLE captured set, not just the last start. g_capLog records every
// getcfilters (start, resolved-stop) pair in send order. stop is resolved via
// the same test-side blockReg map the everRequested spine already uses; a send
// whose stopHash the test never registered logs stop==REG_NOT_FOUND (the
// pre-existing single-send cases don't read g_capLog, so they're unaffected).
#define CAPLOG_MAX 256
static struct { uint32_t start; uint32_t stop; } g_capLog[CAPLOG_MAX];
static int g_capLogCount = 0;
static void capLogReset(void) { g_capLogCount = 0; }
static int capLogHasStart(uint32_t start)
{
    for (int i = 0; i < g_capLogCount; i++) if (g_capLog[i].start == start) return 1;
    return 0;
}

// Between-pass staleness hook: when armed, the Nth captured getcfilters send
// (post-increment g_capCount == g_drainHookAfterNSends) MarkEvaluates
// g_drainHookHeight on g_drainHookMgr's ledger -- modeling a height DRAINING
// between Pass A's collect and Pass C's send of a LATER collected range, so the
// staleness guard can be driven RED-before-green. One-shot: it disarms itself
// after firing so it never perturbs a later send or a later test.
static BRPeerManager *g_drainHookMgr = NULL;
static uint32_t g_drainHookHeight = 0;
static int g_drainHookAfterNSends = -1;

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

    // Task 3: log the whole (start, stop) send set for the batched-semantics test.
    if (g_capLogCount < CAPLOG_MAX) {
        g_capLog[g_capLogCount].start = startHeight;
        g_capLog[g_capLogCount].stop  = stopH;   // REG_NOT_FOUND if unregistered
        g_capLogCount++;
    }

    // Task 3: between-pass drain hook (one-shot). Fires AFTER g_capCount was
    // bumped for this send, so "after N sends" counts inclusively.
    if (g_drainHookMgr && g_capCount == g_drainHookAfterNSends) {
        BRPeerManager *dm = g_drainHookMgr;
        g_drainHookMgr = NULL;   // disarm before mutating so a re-entrant send can't re-fire
        BRCFScanLedgerMarkEvaluated(&dm->cfLedger, g_drainHookHeight);
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

// --- paced-convoy Task 2: getcfheaders + convoy-flag-push capture ------------
//
// The convoy gate's ONLY observable is "did this call actually put a
// getcfheaders on the wire". _BRPeerManagerRequestNextCFHeaders reaches the peer
// through BRPeer.c's public BRPeerSendGetCFHeaders, so the same link-wrap seam
// the getcfilters capture already uses works verbatim here. Signature verified
// against BRPeer.h:278 (do not hand-wave -- a mismatched --wrap shim silently
// fails to bind).
//   void BRPeerSendGetCFHeaders(BRPeer *peer, uint8_t filterType, uint32_t startHeight, UInt256 stopHash);
static int      g_cfhCount = 0;
static uint32_t g_cfhStart = 0;

void __wrap_BRPeerSendGetCFHeaders(BRPeer *peer, uint8_t filterType, uint32_t startHeight, UInt256 stopHash)
{
    (void)peer; (void)filterType; (void)stopHash;
    g_cfhStart = startHeight;
    g_cfhCount++;
}

// The SUPPRESSION half of the getheaders gate cannot be driven from this TU:
// _BRPeerAcceptHeadersMessage (BRPeer.c:648, where the CF-only 2000-header
// continuation is held) is file-static to BRPeer.c, which is a SEPARATE
// compilation unit here (only BRPeerManager.c is #include-d). What IS testable
// -- and is the part that lives in BRPeerManager.c -- is the PUSH: the manager
// recomputing the header-window verdict and stamping it onto every connected
// peer. Wrapping the setter observes that push without adding a production-side
// getter that exists only for tests.
//   void BRPeerSetConvoyHdrGated(BRPeer *peer, int gated);
static int g_convoyPushCount = 0;
static int g_convoyPushLast  = -1;

void __wrap_BRPeerSetConvoyHdrGated(BRPeer *peer, int gated)
{
    (void)peer;
    g_convoyPushLast = gated;
    g_convoyPushCount++;
}

// --- paced-convoy Task 3 (B1.3): getheaders capture --------------------------
//
// The B1 driver's third leg RE-ISSUES the CF-only header continuation from the
// manager side (BRPeerManagerKeepAlive), i.e. through BRPeer.c's public
// BRPeerSendGetheaders -- reachable by exactly the same link-wrap seam. This
// covers the RE-KICK end-to-end (the code Task 3 adds); it does NOT cover
// BRPeer.c:648's suppression, which stays out of reach for the reason above.
// Signature verified against BRPeer.h:270 (do not hand-wave -- a mismatched
// --wrap shim silently fails to bind):
//   void BRPeerSendGetheaders(BRPeer *peer, const UInt256 locators[], size_t locatorsCount, UInt256 hashStop);
static int    g_hdrCount     = 0;
static size_t g_hdrLocators  = 0;

void __wrap_BRPeerSendGetheaders(BRPeer *peer, const UInt256 locators[], size_t locatorsCount, UInt256 hashStop)
{
    (void)peer; (void)locators; (void)hashStop;
    g_hdrLocators = locatorsCount;
    g_hdrCount++;
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

// Register an rhUniqueHash-keyed chain span [base..tip] into the test-side
// stopHash->height registry, so __wrap_BRPeerSendGetCFilters can resolve the
// stop hash of a send into a height range and fold it into the cumulative
// everRequested set (the causality spine serveSome depends on). Writes the same
// (hash, height) pairs blockRegAdd would, without needing the BRMerkleBlock
// objects — the chain built by rhBuildChainManager is owned by the manager.
static void rhRegisterChain(uint32_t base, uint32_t tip)
{
    for (uint32_t h = base; h <= tip && g_blockRegCount < REG_MAX; h++) {
        g_blockReg[g_blockRegCount].hash   = rhUniqueHash(h);
        g_blockReg[g_blockRegCount].height = h;
        g_blockRegCount++;
    }
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

// ============================================================================
// Task 3: residual-loop Pass A(collect) -> B(one-descent resolve) -> C(send)
// restructure. Two properties:
//   1. The batched restructure sends the BYTE-SAME getcfilters set the fused
//      loop did, bumps attempts ONLY for ranges that actually sent, and keeps
//      the reverse-map suppressor + rotate-away + send-fail-no-bump discipline.
//   2. Staleness guard: a height that DRAINS between Pass A's collect and Pass
//      C's send is NOT re-requested nor committed.
// ============================================================================

// (1) Batched restructure preserves the fused loop's send set + discipline.
// A single tick with three residual ranges exercising every branch at once:
//   - [1000..1000]  send FAILS (stop unreachable from tip -> stopHash ZERO)  -> no bump
//   - [1006..1006]  cfheader-lag buffered -> reverse-map SUPPRESSED           -> not sent, no bump
//   - [1008..1008]  resolvable, targeted at pa -> SENDS, rotate-away to pb     -> attempts=1, peer=pb
// The captured set must be EXACTLY {[1008..1008]} -- identical to the fused
// loop (verified: this test is GREEN on the pre-restructure code too).
static void test_residual_batched_preserves_semantics(BRWallet *wallet)
{
    printf("\n=== test_residual_batched_preserves_semantics (Task 3 Pass A/B/C) ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    blockRegReset();
    everReqReset();
    capLogReset();
    g_drainHookMgr = NULL;

    // Lower segment L=[1000..1002] present but UNREACHABLE from the tip: the
    // gap at 1003..1004 severs 1005->prevBlock(1004), so a descent from the tip
    // stops at 1005 and every height <= 1004 resolves to ZERO (send-fail).
    // Upper segment U=[1005..1010] IS reachable -> its stops resolve.
    BRMerkleBlock *prevB = NULL;
    for (uint32_t h = 1000; h <= 1002; h++) {
        BRMerkleBlock *b = dummyBlock(h, (uint8_t)(h - 1000 + 1), 1700000000u + h);
        if (prevB) b->prevBlock = prevB->blockHash;
        BRSetAdd(m->blocks, b); blockRegAdd(b); prevB = b;
    }
    prevB = NULL;
    BRMerkleBlock *bU5 = NULL, *bU6 = NULL, *bU8 = NULL;
    for (uint32_t h = 1005; h <= 1010; h++) {
        BRMerkleBlock *b = dummyBlock(h, (uint8_t)(h - 1005 + 0x40), 1700000000u + h);
        if (prevB) b->prevBlock = prevB->blockHash;   // 1005->prevBlock left pointing at absent 1004's default
        else       b->prevBlock = rhUniqueHash(1004); // severed link: 1004 is NOT in the set
        BRSetAdd(m->blocks, b); blockRegAdd(b); prevB = b;
        if (h == 1005) bU5 = b;
        if (h == 1006) bU6 = b;
        if (h == 1008) bU8 = b;
    }
    m->lastBlock = prevB; // tip == 1010
    check(bU5 && bU6 && bU8, "setup: upper-segment blocks materialized");
    check(UInt256IsZero(_BRPeerManagerBlockHashAtHeight(m, 1000)),
          "setup: 1000 is UNREACHABLE from tip (stop resolves ZERO -> send will fail)");
    check(! UInt256IsZero(_BRPeerManagerBlockHashAtHeight(m, 1008)),
          "setup: 1008 is reachable from tip (stop resolves -> send will go)");

    BRPeer *pb = BRPeerNew(BRMainNetParams.magicNumber);
    pb->address.u8[15] = 0x51; pb->port = 15001; pb->services |= SERVICES_NODE_COMPACT_FILTERS;
    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x52; pa->port = 15002; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pb);
    array_add(m->connectedPeers, pa); // pa LAST -> checked first in the reverse walk (exercises avoid-skip)

    // cfheader chain that has NOT advanced past 1006 (NextHeight==1005 <= 1006):
    // the buffered entry for 1006 stays in-flight (isReady false) -> suppressed.
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, 1005, UINT256_ZERO);
    check(BRCompactFilterChainNextHeight(m->compactFilterChain) <= bU6->height,
          "setup: cfheader chain has NOT advanced past 1006 (buffered entry stays in-flight)");

    BRCFScanLedgerInit(&m->cfLedger, 1000);
    BRCFScanLedgerRecordRequested(&m->cfLedger, 1000, 1000, UINT128_ZERO, 0, 0);         // send-fail
    BRCFScanLedgerRecordRequested(&m->cfLedger, 1006, 1006, UINT128_ZERO, 0, 0);         // suppressed
    BRCFScanLedgerRecordRequested(&m->cfLedger, 1008, 1008, pa->address, pa->port, 0);   // sends, targeted at pa
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 3, "setup: 3 residual holes outstanding");

    // Buffer a filter keyed to 1006's OWN hash (header present at 1006, cfheader
    // lag) -> reverse map resolves it to 1006 -> the residual loop skips 1006.
    uint8_t junk[4] = { 0x01, 0x00, 0x00, 0x00 };
    BRCFScanLedgerBufferFilter(&m->cfLedger, bU6->blockHash, junk, sizeof junk, (uint32_t)time(NULL));
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1, "setup: 1 cfheader-lag buffered entry (hash IS in blocks at 1006)");

    g_capCount = 0; g_capStart = 0; capLogReset();

    BRPeerManagerKeepAlive(m);

    // Captured set is EXACTLY {[1008..1008]} -- byte-same as the fused loop.
    check(g_capLogCount == 1, "batched: exactly one getcfilters sent this tick");
    check(g_capLogCount >= 1 && g_capLog[0].start == 1008 && g_capLog[0].stop == 1008,
          "batched: the one send is [1008..1008] (resolvable range)");
    check(! capLogHasStart(1000), "batched: 1000 NOT sent (stop unresolvable -> send failed)");
    check(! capLogHasStart(1006), "batched: 1006 NOT sent (reverse-map suppressed -- cfheader lag)");

    // attempts bumped ONLY for the range that actually sent.
    const BRCFOutstanding *e1000 = findOutstanding(&m->cfLedger, 1000);
    const BRCFOutstanding *e1006 = findOutstanding(&m->cfLedger, 1006);
    const BRCFOutstanding *e1008 = findOutstanding(&m->cfLedger, 1008);
    check(e1000 && e1000->attempts == 0, "batched: send-fail hole 1000 got NO attempt bump (send returned 0)");
    check(e1006 && e1006->attempts == 0, "batched: suppressed hole 1006 got NO attempt bump (never committed)");
    check(e1008 && e1008->attempts == 1, "batched: sent hole 1008 bumped to attempts=1 (committed)");

    // rotate-away survived: 1008 was targeted at pa, committed to pb.
    check(e1008 && e1008->port == pb->port && UInt128Eq(e1008->peer, pb->address),
          "batched: rotate-away -- 1008 re-committed to pb (not pa, the peer it was last sent to)");

    // suppressor kept the cfheader-lag entry buffered (still in-flight).
    check(BRCFScanLedgerBufferedCount(&m->cfLedger) == 1, "batched: cfheader-lag entry stays buffered (suppressor)");

    BRPeerManagerFree(m);
}

// (2) Staleness guard (operator condition). Two collected ranges [1000] and
// [1005]. The between-pass drain hook MarkEvaluates 1005 the moment [1000] is
// sent in Pass C -- so by the time Pass C reaches [1005] it has DRAINED. The
// guard must re-check outstanding and NOT re-request nor commit 1005.
// RED against the pre-guard restructure (which sends 1005 from stale Pass-A
// state); GREEN once the Pass C re-check lands.
static void test_no_stale_between_pass_recommit(BRWallet *wallet)
{
    printf("\n=== test_no_stale_between_pass_recommit (Task 3 staleness guard) ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);

    blockRegReset();
    everReqReset();
    capLogReset();
    g_drainHookMgr = NULL;

    // Contiguous, fully-reachable chain [1000..1010]: both holes' stops resolve,
    // so absent the guard BOTH would send. tip past the whole cluster (no clip).
    BRMerkleBlock *prevB = NULL;
    for (uint32_t h = 1000; h <= 1010; h++) {
        BRMerkleBlock *b = dummyBlock(h, (uint8_t)(h - 1000 + 1), 1700000000u + h);
        if (prevB) b->prevBlock = prevB->blockHash;
        BRSetAdd(m->blocks, b); blockRegAdd(b); prevB = b;
    }
    m->lastBlock = prevB; // tip == 1010

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x61; pa->port = 16001; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    // Two NON-contiguous holes -> two separate coalesced peek ranges, both DUE.
    BRCFScanLedgerInit(&m->cfLedger, 1000);
    BRCFScanLedgerRecordRequested(&m->cfLedger, 1000, 1000, UINT128_ZERO, 0, 0);
    BRCFScanLedgerRecordRequested(&m->cfLedger, 1005, 1005, UINT128_ZERO, 0, 0);
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 2, "setup: 2 residual holes outstanding (1000, 1005)");

    // Arm the between-pass drain: MarkEvaluate 1005 right after the FIRST send
    // (of [1000]) -- i.e. after Pass A collected [1005] but before Pass C sends it.
    g_drainHookMgr = m;
    g_drainHookHeight = 1005;
    g_drainHookAfterNSends = 1;

    g_capCount = 0; g_capStart = 0; capLogReset();

    BRPeerManagerKeepAlive(m);

    // Only [1000] went on the wire; the drained 1005 was NOT re-requested.
    check(g_capLogCount == 1, "staleness guard: exactly one getcfilters sent (drained 1005 suppressed)");
    check(capLogHasStart(1000), "staleness guard: [1000] WAS sent (still outstanding at Pass C)");
    check(! capLogHasStart(1005),
          "staleness guard: [1005] was NOT re-requested (drained between Pass A collect and Pass C send)");

    // 1005 stays drained (evaluated); a stray commit must not resurrect it.
    check(findOutstanding(&m->cfLedger, 1005) == NULL, "staleness guard: 1005 stays evaluated (not re-added)");
    const BRCFOutstanding *e1000 = findOutstanding(&m->cfLedger, 1000);
    check(e1000 && e1000->attempts == 1, "staleness guard: 1000 sent+committed (attempts=1)");
    check(g_drainHookMgr == NULL, "staleness guard: the between-pass drain hook actually fired");

    BRPeerManagerFree(m);
}

// ===========================================================================
// Task 4: _BRPeerManagerClearMemory retention floor tracks the SCAN frontier +
// tip-anchored memory ceiling. PRODUCTION-SCALE (>5000-block) red-before-green.
// A <5000-block test never crosses CLEAR_MEM_BLOCKS_COUNT_TRIGGER, so the prune
// body never runs — a structural false-green. These build a real prevBlock-
// linked, DISTINCT-per-height chain (rhChainBlock / rhUniqueHash — NOT
// dummyBlock's 256-colliding single-byte fill) so BRSetCount actually crosses
// 5000 and the descent has a chain deep enough (>800) to walk.
// ===========================================================================

// height -> present in manager->blocks? BRSet is keyed by BRMerkleBlockHash,
// which reads blockHash at offset 0, so a bare UInt256* is a valid lookup key —
// the exact pattern _BRPeerManagerClearMemory's own descent uses.
static int rhBlockPresent(BRPeerManager *m, uint32_t height)
{
    UInt256 h = rhUniqueHash(height);
    return BRSetGet(m->blocks, &h) != NULL;
}

// Build a manager backed by a real prevBlock-linked, distinct-per-height chain
// [base .. base+count-1], CF-only syncMode (else the CF floor logic is skipped),
// and a compact-filter chain whose NextHeight == the tip. NextHeight is
// startHeight+count, so startHeight=tip / count=0 gives NextHeight==tip cheaply,
// WITHOUT appending `count` filters. Writes the tip height + the pre-chain
// baseline block count (mainnet pre-seeds a couple of checkpoints) so callers
// assert on the DELTA.
static BRPeerManager *rhBuildChainManager(BRWallet *wallet, uint32_t base, uint32_t count,
                                          uint32_t *outTip, size_t *outBaseCount)
{
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    if (! m) return NULL;
    m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;

    size_t baseCount = BRSetCount(m->blocks);
    uint32_t tip = base + count - 1;
    BRMerkleBlock *last = NULL;
    for (uint32_t hgt = base; hgt <= tip; hgt++) {
        BRMerkleBlock *b = rhChainBlock(hgt);   // distinct hash; prevBlock -> unique(height-1)
        BRSetAdd(m->blocks, b);
        last = b;
    }
    m->lastBlock = last;
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, tip, UINT256_ZERO);

    if (outTip) *outTip = tip;
    if (outBaseCount) *outBaseCount = baseCount;
    return m;
}

// THE red-before-green case. Unfixed (-DRETENTION_UNFIXED) floors at the
// cfHEADER frontier (cfNext-144), which is ABOVE the lagging scan floor, so the
// scan-floor header is pruned -> RED. Fixed floors at min(cfNext,lowestNeeded)-144
// == scan-floor -144, so the scan-floor header SURVIVES -> GREEN.
static void test_clearmemory_retains_scan_floor(BRWallet *wallet)
{
    printf("\n=== test_clearmemory_retains_scan_floor (Task 4 red-before-green, production scale) ===\n");

    const uint32_t BASE  = 20000000u;   // realistic mainnet-ish; above all checkpoints
    const uint32_t COUNT = 5300u;       // > trigger(5000) with tail-hop(800) headroom
    uint32_t TIP; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(wallet, BASE, COUNT, &TIP, &baseCount);
    check(m != NULL, "manager+chain built");
    if (! m) return;

    // Distinct hashes: the set grew by EXACTLY COUNT (dummyBlock's 256-collision
    // trap would surface here as a shortfall) and is over the prune trigger.
    check(BRSetCount(m->blocks) == baseCount + COUNT, "distinct-hash chain grew by exactly COUNT (no collisions)");

    // Scan floor: BELOW the tail boundary (tip-801) AND below the cfheader margin
    // (cfNext-144, cfNext==tip). tip - H_floor is kept < CF_RETENTION_MAX_SPAN so
    // the ceiling never fires on this retain path.
    const uint32_t H_floor = TIP - 3000u;
    BRCFScanLedgerInit(&m->cfLedger, H_floor);        // scannedThrough=H_floor-1 -> lowestNeeded=H_floor
    BRCFScanLedgerRecordRequested(&m->cfLedger, H_floor, H_floor, UINT128_ZERO, 0, 1700000000u);
    check(BRCFScanLedgerLowestNeededHeight(&m->cfLedger) == H_floor, "setup: lowestNeeded == H_floor (scan lags here)");
    check(BRCompactFilterChainNextHeight(m->compactFilterChain) == TIP, "setup: cfNext == tip (headers raced ahead)");
    check(H_floor < TIP - 801u && H_floor < TIP - CLEAR_MEM_CF_RETENTION_MARGIN,
          "setup: H_floor below tail boundary AND below cfNext-margin (WOULD be pruned unfixed)");
    check(rhBlockPresent(m, H_floor), "setup: floor header present pre-prune");
    check(BRSetCount(m->blocks) >= CLEAR_MEM_BLOCKS_COUNT_TRIGGER, "setup: BRSetCount >= 5000 BEFORE the pass (prune body runs)");

    int wlogBefore = g_wlogCount;
    _BRPeerManagerClearMemory(m);

    check(rhBlockPresent(m, H_floor), "FLOOR RETAINED: scan-floor header survives the prune (RED on unfixed, GREEN on fix)");
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == 0, "no abandonment: abandonedBelow stayed 0");
    check(g_wlogCount == wlogBefore, "no ABANDONED warn-log on the clean retain path");

    BRPeerManagerFree(m);
}

// The descent is the ONLY code that calls BRMerkleBlockFree; a sub-floor header
// below the tail boundary MUST be freed (guards against a leak / an early-out
// that would skip the descent across the whole deficit).
static void test_clearmemory_descent_frees(BRWallet *wallet)
{
    printf("\n=== test_clearmemory_descent_frees (Task 4: full descent frees below the floor) ===\n");
    const uint32_t BASE  = 20000000u;
    const uint32_t COUNT = 5300u;
    uint32_t TIP; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(wallet, BASE, COUNT, &TIP, &baseCount);
    check(m != NULL, "manager+chain built");
    if (! m) return;

    const uint32_t H_floor = TIP - 3000u;
    BRCFScanLedgerInit(&m->cfLedger, H_floor);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H_floor, H_floor, UINT128_ZERO, 0, 1700000000u);

    const uint32_t H_deep = BASE + 50u;   // far below cfFloor and below the tail boundary
    check(rhBlockPresent(m, H_deep), "setup: deep header present pre-prune");
    check(H_deep < H_floor - CLEAR_MEM_CF_RETENTION_MARGIN, "setup: deep height is below cfFloor");
    check(BRSetCount(m->blocks) >= CLEAR_MEM_BLOCKS_COUNT_TRIGGER, "setup: BRSetCount >= 5000 before the pass");

    _BRPeerManagerClearMemory(m);

    check(! rhBlockPresent(m, H_deep), "DESCENT FREES: deep sub-floor header removed from the set");
    check(rhBlockPresent(m, H_floor), "floor header still retained");
    int allOutstandingRetained = 1;
    for (size_t i = 0; i < m->cfLedger.outstandingCount; i++)
        if (! rhBlockPresent(m, m->cfLedger.outstanding[i].height)) allOutstandingRetained = 0;
    check(allOutstandingRetained, "INVARIANT: every still-outstanding height keeps its header");

    BRPeerManagerFree(m);
}

// Tip-anchored ceiling — TIMING BRANCH 1: SCAN NOT STARTED (Part 3b determinism
// guard, the wrong-balance regression baseline). ClearMemory fires during header
// sync, before the cfilter scan requests anything: empty outstanding, and NO
// gaveUp below the clamp — nothing legitimately abandonable. The guard must NOT
// advance abandonedBelow and must NOT raise the scan floor; a preemptive raise
// here would let a deep restore COMPLETE with a WRONG BALANCE (deep history never
// scanned). RED against the pre-guard abandonedBelow=target shape
// (-DRETENTION_PREEMPTIVE_ADVANCE, run.sh's ceiling red-before-green gate).
// Built with -DCF_RETENTION_MAX_SPAN overridden small so the birth floor can sit
// > MAX_SPAN below the tip without a 30k-block chain.
static void test_clearmemory_ceiling_scan_not_started(BRWallet *wallet)
{
    printf("\n=== test_clearmemory_ceiling_scan_not_started (Part 3b wrong-balance guard) ===\n");
    check(CF_RETENTION_MAX_SPAN <= 5000u, "harness: CF_RETENTION_MAX_SPAN overridden small for this case");

    const uint32_t BASE  = 20000000u;
    const uint32_t COUNT = 5600u;   // > trigger; birth floor sits > MAX_SPAN below tip
    uint32_t TIP; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(wallet, BASE, COUNT, &TIP, &baseCount);
    check(m != NULL, "manager+chain built");
    if (! m) return;

    const uint32_t clamp = TIP - CF_RETENTION_MAX_SPAN;
    // Birth floor at BASE (> MAX_SPAN below the tip); scan not started: empty
    // outstanding, empty gaveUp — the deep-restore-during-header-sync shape.
    BRCFScanLedgerInit(&m->cfLedger, BASE);
    check(BRCFScanLedgerLowestNeededHeight(&m->cfLedger) == BASE && BASE < clamp,
          "setup: birth floor BASE is > MAX_SPAN below the tip (below the clamp)");
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 0 && BRCFScanLedgerGaveUpCount(&m->cfLedger) == 0,
          "setup: empty outstanding + empty gaveUp (nothing legitimately abandonable)");
    check(BRSetCount(m->blocks) >= CLEAR_MEM_BLOCKS_COUNT_TRIGGER, "setup: BRSetCount >= 5000 before the pass");
    // A sub-clamp probe header: FREED iff the floor is (wrongly) raised to the
    // clamp, RETAINED iff the floor stays at the birth floor. clamp-500 sits below
    // the retained tail (tip-801) so the descent actually visits it.
    const uint32_t H_probe = clamp - 500u;
    check(rhBlockPresent(m, H_probe), "setup: sub-clamp probe header present pre-prune");

    int wlogBefore = g_wlogCount;
    _BRPeerManagerClearMemory(m);

    // THE guard: nothing dropped → abandonedBelow does NOT advance (stays 0), so a
    // caller reading abandonedBelow==0 is reading a verified fact.
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == 0,
          "GUARD: abandonedBelow did NOT advance (stays 0) — no preemptive raise (RED on pre-guard)");
    check(g_wlogCount == wlogBefore, "GUARD: no ABANDONED warn-log fired (nothing was abandoned)");
    check(rhBlockPresent(m, H_probe),
          "GUARD: scan floor NOT raised — the sub-clamp probe header is RETAINED (deep history still scannable)");

    BRPeerManagerFree(m);
}

// Tip-anchored ceiling — TIMING BRANCH 2: SCAN STARTED. A still-outstanding
// (attempts<cap) hole below the clamp is NEVER abandoned (recoverable), while
// retry-exhausted gaveUp holes below it ARE abandoned. abandonedBelow advances
// ONLY to cover the gaveUp actually dropped (highest-dropped+1, ≤ the outstanding
// hole — never past it), the WARN fires, and a deep-enough abandoned header is
// FREED, while the outstanding hole's header is RETAINED. Uses TWO gaveUp holes:
// the highest sets the watermark (it sits within the 144-block retention margin
// of the new floor, so it stays resident this pass), the deeper one is below the
// margin and is freed — the visible "abandoned header freed" evidence.
static void test_clearmemory_ceiling_scan_started(BRWallet *wallet)
{
    printf("\n=== test_clearmemory_ceiling_scan_started (Part 3b: abandon gaveUp, keep outstanding) ===\n");
    check(CF_RETENTION_MAX_SPAN <= 5000u, "harness: CF_RETENTION_MAX_SPAN overridden small for this case");

    const uint32_t BASE  = 20000000u;
    const uint32_t COUNT = 5600u;   // > trigger; room for holes > MAX_SPAN below tip
    uint32_t TIP; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(wallet, BASE, COUNT, &TIP, &baseCount);
    check(m != NULL, "manager+chain built");
    if (! m) return;

    const uint32_t clamp    = TIP - CF_RETENTION_MAX_SPAN;   // BASE+1599 at MAX_SPAN=4000
    const uint32_t H_out    = clamp - 300u;   // still-outstanding hole below clamp (recoverable)
    const uint32_t H_gvHi   = clamp - 400u;   // highest gaveUp below H_out (sets the watermark)
    const uint32_t H_gvLo   = clamp - 1300u;  // deeper gaveUp, > margin below H_gvHi → freed
    BRCFScanLedgerInit(&m->cfLedger, BASE);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H_out, H_out, UINT128_ZERO, 0, 1700000000u);
    const BRCFOutstanding *eo = findOutstanding(&m->cfLedger, H_out);
    check(eo != NULL && eo->attempts < CF_REREQ_MAX_ATTEMPTS, "setup: H_out outstanding, attempts < cap (recoverable)");
    m->cfLedger.gaveUp[0]   = H_gvLo;   // sorted ascending
    m->cfLedger.gaveUp[1]   = H_gvHi;
    m->cfLedger.gaveUpCount = 2;
    check(H_gvLo < H_gvHi && H_gvHi < H_out && H_out < clamp, "setup: H_gvLo < H_gvHi < H_out < clamp");
    check(BRSetCount(m->blocks) >= CLEAR_MEM_BLOCKS_COUNT_TRIGGER, "setup: BRSetCount >= 5000 before the pass");

    int wlogBefore = g_wlogCount;
    _BRPeerManagerClearMemory(m);

    // GUARD: abandonedBelow advances ONLY to cover the dropped gaveUp — to the
    // highest dropped (H_gvHi)+1, NEVER past the still-outstanding hole H_out.
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == H_gvHi + 1u,
          "abandonedBelow == highest-dropped gaveUp + 1 (NOT the clamp, NOT the outstanding hole)");
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) <= H_out,
          "abandonedBelow never advances past the still-outstanding (recoverable) hole");
    // The still-outstanding hole is untouched + its header retained.
    check(findOutstanding(&m->cfLedger, H_out) != NULL, "the still-outstanding hole is NOT abandoned");
    check(rhBlockPresent(m, H_out), "the outstanding hole's header is RETAINED");
    // Both gaveUp holes dropped from the ledger (abandoned), WARN fired for them.
    check(! gaveUpContains(&m->cfLedger, H_gvHi) && ! gaveUpContains(&m->cfLedger, H_gvLo),
          "both gaveUp holes below the clamp were abandoned (dropped from the ledger)");
    check(g_wlogCount == wlogBefore + 1, "exactly one ABANDONED warn-log fired for the dropped gaveUp");
    check(strstr(g_wlogLast, "ABANDONED") != NULL, "the captured warn-log names the ABANDONED event");
    // The deep abandoned header (below the retention margin) is FREED — visible loss.
    check(! rhBlockPresent(m, H_gvLo), "the deep abandoned gaveUp header is FREED");

    BRPeerManagerFree(m);
}

// ---- Paced-convoy fetch, Task 1: scan-frontier + abandonment accessors -----
// Semantics anchor for the frontier the convoy gate/driver polls: it is
// BRCFScanLedgerLowestNeededHeight (== max(scannedThrough+1, abandonedBelow)),
// NOT scannedThrough alone. BRCFScanLedgerLowestNeededHeight/AbandonedBelow
// already exist (CF-retention scan-floor Task 1) — the new code under test
// here is the BRPeerManager*/JNI accessor layer the convoy actually calls
// (BRPeerManagerLowestNeededHeight/AbandonedBelow/AbandonedCount), proven by
// threading the exact same shape through a real manager's embedded cfLedger.
static void test_lowest_needed_accessor(BRWallet *wallet)
{
    printf("\n=== test_lowest_needed_accessor (paced-convoy Task 1: frontier semantics anchor) ===\n");

    // --- Pure ledger: locks the exact semantics the convoy's frontier read relies on. ---
    BRCFScanLedger l;
    BRCFScanLedgerInit(&l, 1000);   // scannedThrough = 999
    UInt128 peer = UINT128_ZERO; peer.u8[15] = 1;
    BRCFScanLedgerRecordRequested(&l, 1000, 1005, peer, 12024, 1700000000u);
    for (uint32_t h = 1000; h <= 1002; h++) BRCFScanLedgerMarkEvaluated(&l, h);
    check(BRCFScanLedgerScannedThrough(&l) == 1002, "scannedThrough advanced to 1002 (1000..1002 evaluated)");
    check(BRCFScanLedgerLowestNeededHeight(&l) == 1003,
          "LowestNeededHeight == scannedThrough+1 == 1003 (still-outstanding 1003..1005 hold the frontier back)");

    // Raise the hard retention floor above the still-outstanding scan frontier
    // (production path: BRCFScanLedgerAbandonGaveUpBelow; anchored directly
    // here the same way cf_scan_ledger_kat's test_lowest_needed_height moves
    // the watermark, since AbandonGaveUpBelow itself never advances past a
    // still-outstanding hole — that guard is proven separately by
    // test_clearmemory_ceiling_scan_started above).
    l.abandonedBelow = 1010;
    check(BRCFScanLedgerAbandonedBelow(&l) == 1010, "abandonedBelow raised to 1010");
    check(BRCFScanLedgerLowestNeededHeight(&l) == 1010,
          "LowestNeededHeight folds in abandonedBelow (1010 > scannedThrough+1==1003) -- the convoy's fetch "
          "frontier is LowestNeededHeight, not scannedThrough");

    // --- BRPeerManager* accessors (the actual new code this task adds): lock ->
    // read ledger -> unlock wrappers the JNI layer (getLowestNeededHeight /
    // getAbandonedBelow / getAbandonedCount) calls. Same shape, on a real
    // manager's embedded cfLedger, plus AbandonedCount (start..abandonedBelow-1). ---
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    check(m != NULL, "setup: peer manager created");
    if (m) {
        BRCFScanLedgerInit(&m->cfLedger, 2000);   // scannedThrough = 1999, start = 2000
        BRCFScanLedgerRecordRequested(&m->cfLedger, 2000, 2005, peer, 12024, 1700000000u);
        for (uint32_t h = 2000; h <= 2002; h++) BRCFScanLedgerMarkEvaluated(&m->cfLedger, h);

        check(BRPeerManagerLowestNeededHeight(m) == 2003, "BRPeerManagerLowestNeededHeight mirrors the ledger fn (2003)");
        check(BRPeerManagerAbandonedBelow(m) == 0, "BRPeerManagerAbandonedBelow starts at 0 (nothing abandoned yet)");
        check(BRPeerManagerAbandonedCount(m) == 0, "BRPeerManagerAbandonedCount is 0 when abandonedBelow == start");

        m->cfLedger.abandonedBelow = 2010;
        check(BRPeerManagerLowestNeededHeight(m) == 2010, "BRPeerManagerLowestNeededHeight folds in the raised abandonedBelow (2010)");
        check(BRPeerManagerAbandonedBelow(m) == 2010, "BRPeerManagerAbandonedBelow reads the raised watermark (2010)");
        check(BRPeerManagerAbandonedCount(m) == 10, "BRPeerManagerAbandonedCount == abandonedBelow(2010) - start(2000) == 10");

        BRPeerManagerFree(m);
    }
}

// ---- Paced-convoy fetch, Task 2: THE CONVOY GATE ---------------------------
// (spec 2026-07-28-paced-convoy-fetch-design.md, Part A)
//
// Block-header sync fast-forwards to the chain tip UNPACED, so on a deep restore
// manager->blocks fills with [birth..tip] before the cfilter SCAN has processed
// anything (the OOM). The gate suppresses ONLY the two tip-racing continuations,
// keeping the header/cfheader frontiers within CF_CONVOY_WINDOW of the SCAN
// frontier. Suppressing a RECOVERY or SYNC-START send instead would deadlock the
// convoy from the other side -- hence the per-call-site isConvoyAdvance flag,
// and hence the EXEMPT half of this case, which is as load-bearing as the
// suppressed half.
//
// RED-before-green: run.sh builds this case with -DCONVOY_UNGATED (the
// suppression compiled out, the pre-fix shape) and HARD-FAILS if it passes.

static void test_convoy_gate_suppresses_continuations(BRWallet *wallet)
{
    printf("\n=== test_convoy_gate_suppresses_continuations (paced-convoy Task 2: gate the tip-racers only) ===\n");

    // Deep-restore shape: both fetch frontiers have raced a full
    // CF_CONVOY_WINDOW ahead of the CF SCAN frontier. The scan frontier
    // deliberately sits BELOW the retained header span -- that IS the production
    // shape (headers below the scan floor are pruned; the window is a height
    // arithmetic relation, not a set-membership one), and it keeps the chain this
    // case has to actually build small.
    const uint32_t BASE  = 20000000u;
    const uint32_t COUNT = 3000u;
    uint32_t TIP; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(wallet, BASE, COUNT, &TIP, &baseCount);
    check(m != NULL, "manager+chain built");
    if (! m) return;
    (void)baseCount;

    // rhBuildChainManager anchors the CF chain AT the block tip; this case needs
    // the cfheader frontier BELOW the block tip (so a real next batch exists to
    // request) yet still a full window above the scan frontier.
    BRCompactFilterChainFree(m->compactFilterChain);
    const uint32_t CFH_NEXT = BASE + 1000u;          // cfheader frontier == CFH_NEXT - 1
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, CFH_NEXT, UINT256_ZERO);

    const uint32_t SCAN = BASE - 11000u;
    BRCFScanLedgerInit(&m->cfLedger, SCAN);          // scannedThrough = SCAN-1 -> lowestNeeded = SCAN
    check(BRCFScanLedgerLowestNeededHeight(&m->cfLedger) == SCAN,
          "setup: scan frontier (LowestNeededHeight) == SCAN");
    check(m->lastBlock && m->lastBlock->height == TIP, "setup: lastBlock is the block tip");
    check(TIP - SCAN >= CF_CONVOY_WINDOW, "setup: W_hdr >= CF_CONVOY_WINDOW (header frontier raced ahead)");
    check((CFH_NEXT - 1u) - SCAN >= CF_CONVOY_WINDOW, "setup: W_cfh >= CF_CONVOY_WINDOW (cfheader frontier raced ahead)");
    check(CFH_NEXT <= TIP, "setup: a real next cfheaders batch exists below the block tip (send is otherwise possible)");

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x21; pa->port = 12021; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    // --- ARMING GUARD, asserted BEFORE the gate is armed (wedge-class) --------
    // A calloc'd manager's cfLedger has scannedThrough == 0, so
    // LowestNeededHeight == 1 and the RAW window against a mainnet-height tip is
    // ~23M -- "full" by arithmetic. But the CF scan is not armed yet
    // (autoFetchCFiltersEnabled == 0), and block-header sync is precisely what
    // has to run BEFORE the scan can be armed at a real floor. A predicate that
    // reported GATED here would suppress header sync on every fresh start and
    // wedge the wallet permanently. Both predicates must read OPEN until armed.
    check(m->autoFetchCFiltersEnabled == 0, "setup: CF scan not armed yet (calloc'd manager default)");
    check(_cfConvoyHdrGated(m) == 0, "ARMING GUARD: hdr predicate OPEN while the CF scan is unarmed (no fresh-start header wedge)");
    check(_cfConvoyCfhGated(m) == 0, "ARMING GUARD: cfh predicate OPEN while the CF scan is unarmed");

    m->autoFetchCFiltersEnabled = 1;   // the scan is now armed at a real floor
    m->autoFetchCFiltersStart   = SCAN;

    // --- the two window predicates read FULL ---
    check(_cfConvoyHdrGated(m) == 1, "WINDOW: _cfConvoyHdrGated == 1 (block-header frontier a full window ahead of the scan)");
    check(_cfConvoyCfhGated(m) == 1, "WINDOW: _cfConvoyCfhGated == 1 (cfheader frontier a full window ahead of the scan)");

    // --- getcfheaders: a CONVOY ADVANCE is SUPPRESSED at a full window ---
    g_cfhCount = 0; g_cfhStart = 0;
    _BRPeerManagerRequestNextCFHeaders(m, pa, /*isConvoyAdvance=*/1);
    check(g_cfhCount == 0,
          "GATE: convoy-advance getcfheaders SUPPRESSED at a full window (RED on -DCONVOY_UNGATED)");
    check(m->cfHeadersRequestedThrough == 0,
          "GATE: the suppressed advance left cfHeadersRequestedThrough at 0 (no phantom in-flight batch)");

    // --- ...while a RECOVERY / SYNC-START send is EXEMPT. Suppressing THIS is
    // what would deadlock the convoy, so it is asserted, not assumed. ---
    _BRPeerManagerRequestNextCFHeaders(m, pa, /*isConvoyAdvance=*/0);
    check(g_cfhCount == 1, "EXEMPT: isConvoyAdvance=0 (sync-start / re-anchor / recovery) STILL sends at a full window");
    check(g_cfhStart == CFH_NEXT, "EXEMPT: the recovery send asked for the real next batch start (CFH_NEXT)");

    // --- the getheaders half: KeepAlive recomputes the window and PUSHES the
    // verdict onto every connected peer (BRPeer.c's :622 continuation reads it
    // from the peer read thread, where the opaque manager is unreachable). ---
    g_convoyPushCount = 0; g_convoyPushLast = -1;
    BRPeerManagerKeepAlive(m);
    check(g_convoyPushCount >= 1, "PUSH: KeepAlive pushed the convoy header-gate verdict onto the connected peer");
    check(g_convoyPushLast == 1, "PUSH: the pushed verdict is GATED(1) at a full window (RED on -DCONVOY_UNGATED)");

    // --- CONTROL on the WINDOW axis. Without this, "sent 0" above could be any
    // unrelated early return in the driver rather than the gate. Same manager,
    // same peer, same chain -- only the scan frontier moves up so BOTH windows
    // fall below W; everything must re-open. ---
    const uint32_t SCAN_NEAR = BASE - 7000u;
    BRCFScanLedgerInit(&m->cfLedger, SCAN_NEAR);
    check(TIP - SCAN_NEAR < CF_CONVOY_WINDOW, "control setup: W_hdr < CF_CONVOY_WINDOW");
    check((CFH_NEXT - 1u) - SCAN_NEAR < CF_CONVOY_WINDOW, "control setup: W_cfh < CF_CONVOY_WINDOW");
    check(_cfConvoyHdrGated(m) == 0, "CONTROL: hdr window predicate OPEN below W");
    check(_cfConvoyCfhGated(m) == 0, "CONTROL: cfh window predicate OPEN below W");

    m->cfHeadersRequestedThrough = 0;   // clear the exempt send's in-flight marker
    g_cfhCount = 0; g_cfhStart = 0;
    _BRPeerManagerRequestNextCFHeaders(m, pa, /*isConvoyAdvance=*/1);
    check(g_cfhCount == 1,
          "CONTROL: the SAME convoy advance DOES send below the window (the gate is window-keyed, not a blanket mute)");

    g_convoyPushCount = 0; g_convoyPushLast = -1;
    BRPeerManagerKeepAlive(m);
    check(g_convoyPushLast == 0, "CONTROL: KeepAlive pushes UNGATED(0) below the window");

    BRPeerManagerFree(m);
}

// NULL-chain carve-out (spec blocker B-3). compactFilterChain is created LAZILY
// on the first cfheaders RESPONSE, so on a fresh restore it is NULL and
// BRCompactFilterChainNextHeight(NULL) == 0. A naive `NextHeight - 1` therefore
// reads 0xFFFFFFFF, scores the cfheader window as permanently FULL, and
// suppresses the very FIRST cfheaders request -- deadlocking the fresh deep
// restore forever (nothing else ever creates the chain). The carve-out treats a
// NULL chain as an OPEN gate. The header window is deliberately kept BELOW W in
// this case so the cfheader formula is the ONLY variable.
//
// RED-before-green: run.sh builds this case with -DCONVOY_NULLCHAIN_NAIVE (the
// carve-out compiled out, i.e. the underflowing formula) and HARD-FAILS if it passes.
static void test_convoy_gate_null_chain_open(BRWallet *wallet)
{
    printf("\n=== test_convoy_gate_null_chain_open (paced-convoy Task 2: NULL-chain carve-out, blocker B-3) ===\n");

    const uint32_t BASE  = 20000000u;
    const uint32_t COUNT = 300u;
    uint32_t TIP; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(wallet, BASE, COUNT, &TIP, &baseCount);
    check(m != NULL, "manager+chain built");
    if (! m) return;
    (void)baseCount;

    // Fresh restore: no cfheaders response has landed yet, so no chain exists.
    BRCompactFilterChainFree(m->compactFilterChain);
    m->compactFilterChain = NULL;

    BRCFScanLedgerInit(&m->cfLedger, BASE);
    m->autoFetchCFiltersEnabled = 1;
    m->autoFetchCFiltersStart   = BASE;              // the TOFU birth height the first batch starts at
    m->autoFetchCFiltersThrough = BASE - 1u;

    check(BRCompactFilterChainNextHeight(m->compactFilterChain) == 0,
          "setup: NextHeight(NULL) == 0 -- a naive `NextHeight - 1` underflows to 0xFFFFFFFF");
    check(BRCFScanLedgerLowestNeededHeight(&m->cfLedger) == BASE, "setup: scan frontier == BASE");
    check(TIP - BASE < CF_CONVOY_WINDOW, "setup: the HEADER window is below W (the cfheader formula is the only variable)");
    check(_cfConvoyHdrGated(m) == 0, "setup: hdr window predicate OPEN (shallow header lead)");

    check(_cfConvoyCfhGated(m) == 0,
          "CARVE-OUT: NULL compactFilterChain -> cfh window predicate OPEN, no 0xFFFFFFFF underflow "
          "(RED on -DCONVOY_NULLCHAIN_NAIVE)");

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x22; pa->port = 12022; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    g_cfhCount = 0; g_cfhStart = 0;
    _BRPeerManagerRequestNextCFHeaders(m, pa, /*isConvoyAdvance=*/1);
    check(g_cfhCount == 1,
          "CARVE-OUT: the FIRST convoy advance on a NULL chain is NOT suppressed (a fresh deep restore can start at all)");
    check(g_cfhStart == BASE, "CARVE-OUT: that first request starts at the configured CF birth height");

    BRPeerManagerFree(m);
}

// ---- Paced-convoy fetch, Task 3: THE B1 KEEPALIVE CONVOY DRIVER ------------
// (spec 2026-07-28-paced-convoy-fetch-design.md, Part B1)
//
// Task 2's gate ALONE is a silent permanent wedge. Suppressing a continuation
// removes the only thing that re-fires it, and the forward getcfilters auto-fetch
// has exactly ONE production trigger: a cfheaders arrival
// (_peerRelayedCFHeaders). B1 is the un-suppressor: KeepAlive drives the convoy
// itself every tick.
//
// RED-before-green: run.sh builds these cases with -DCONVOY_NO_B1_DRIVER (the
// driver compiled out, gate still installed) and HARD-FAILS if they pass.

// THE load-bearing case (spec Part B1 step 1 + self-healing mode (e)).
//
// The DRAIN TROUGH: a wallet killed and resumed mid-descent at the exact moment
// the ledger had drained EMPTY -- outstanding == 0, gaveUp == 0 -- while the
// cfheader frontier still sits well above scannedThrough+1. There is:
//   * no hole for the residual peek/commit driver to work (outstanding is empty,
//     and PeekRerequestRange/NextRerequest iterate `outstanding` only), and
//   * no cfheaders arrival to fire the forward fetch (the chain is already
//     appended through CFH_FRONTIER; nothing is in flight).
// Nothing can create the first outstanding entry, the scan never advances, the
// convoy window never re-opens, and deep history is silently never scanned --
// while the wallet reports itself progressing. B1's forward drive is what breaks
// that, and it must mirror the CALLER-side steps of the :2803 cfheaders-arrival
// path, because _BRPeerManagerRequestCFiltersLocked does NEITHER of them: the
// cursor advance (else the drive re-requests the same batch forever) and the
// ledger RecordRequested (else the in-flight heights are untracked and
// _cfLedgerAdvance sails scannedThrough PAST an unscanned height -- a silent
// missed receive, the exact bug class this whole subsystem exists to prevent).
static void test_b1_resumes_drain_trough(BRWallet *wallet)
{
    printf("\n=== test_b1_resumes_drain_trough (paced-convoy Task 3 / B1.1: the resumed drain trough) ===\n");

    const uint32_t BASE  = 20000000u;
    const uint32_t COUNT = 3000u;

    // ---- Session 1: a mid-descent ledger that drained to EMPTY, then PERSISTED.
    // Serialize/Parse (not a hand-built struct) so the trough is reached the way
    // production reaches it: saveCFLedger on the way down, restoreCfScanLedger on
    // the way back up. Parse also drops peer/attempts/requestedAt (§5), which is
    // exactly why nothing is left in flight after the resume.
    BRCFScanLedger persisted;
    BRCFScanLedgerInit(&persisted, BASE);
    BRCFScanLedgerRecordRequested(&persisted, BASE, BASE + 499u, UINT128_ZERO, 0, 1700000000u);
    for (uint32_t h = BASE; h <= BASE + 499u; h++) BRCFScanLedgerMarkEvaluated(&persisted, h);
    check(BRCFScanLedgerOutstandingCount(&persisted) == 0 && BRCFScanLedgerGaveUpCount(&persisted) == 0,
          "session 1: the ledger drained to EMPTY (outstanding == 0, gaveUp == 0)");
    check(BRCFScanLedgerScannedThrough(&persisted) == BASE + 499u, "session 1: scannedThrough == BASE+499");

    size_t blobLen = BRCFScanLedgerSerialize(&persisted, NULL, 0);
    uint8_t *blob  = (blobLen > 0) ? malloc(blobLen) : NULL;
    check(blob != NULL && BRCFScanLedgerSerialize(&persisted, blob, blobLen) == blobLen,
          "session 1: ledger serialized (the persisted blob the resume restores)");
    BRCFScanLedgerFree(&persisted);
    if (! blob) return;

    // ---- Session 2: fresh process -> fresh manager, restore the blob. ----
    uint32_t TIP; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(wallet, BASE, COUNT, &TIP, &baseCount);
    check(m != NULL, "session 2: manager+chain built (the recreated manager)");
    if (! m) { free(blob); return; }
    (void)baseCount;

    // The cfheader chain got ahead of the scan before the kill, and is restored
    // from FilterHeaderStore ahead of the ledger. Keep it BELOW the block tip so
    // this case says nothing about the cfheaders re-kick (covered separately).
    BRCompactFilterChainFree(m->compactFilterChain);
    const uint32_t CFH_NEXT     = BASE + 2500u;
    const uint32_t CFH_FRONTIER = CFH_NEXT - 1u;
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, CFH_NEXT, UINT256_ZERO);

    check(BRCFScanLedgerParse(&m->cfLedger, blob, blobLen) == 1, "resume: the persisted ledger parsed back in");
    free(blob);

    m->autoFetchCFiltersEnabled = 1;
    m->autoFetchCFiltersStart   = BASE;
    // The B1-resume cursor snap is a SEPARATE task; pinned explicitly here so what
    // this case exercises is the forward DRIVE, not the snap.
    m->autoFetchCFiltersThrough = BRCFScanLedgerScannedThrough(&m->cfLedger);

    const uint32_t SCAN0 = BRCFScanLedgerLowestNeededHeight(&m->cfLedger);   // BASE+500
    check(SCAN0 == BASE + 500u, "resume: scan frontier == scannedThrough+1 == BASE+500");
    check(CFH_FRONTIER > SCAN0,
          "THE TROUGH: cfHeadersFrontier > scannedThrough+1 -- unscanned heights sit below the cfheader frontier");
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 0 && BRCFScanLedgerGaveUpCount(&m->cfLedger) == 0,
          "THE TROUGH: outstanding == 0 AND gaveUp == 0 -- the residual driver has NOTHING to work");
    check(_cfConvoyCfhGated(m) == 0 && _cfConvoyHdrGated(m) == 0,
          "THE TROUGH: BOTH convoy windows are OPEN -- so this is not a gate wedge, it is a MISSING DRIVER");
    check(CFH_NEXT <= TIP, "setup: the cfheader frontier sits below the block tip");

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x31; pa->port = 12031; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    blockRegReset(); everReqReset(); capLogReset();
    rhRegisterChain(BASE, TIP);
    g_capCount = 0; g_capStart = 0;

    // ---- ONE KeepAlive tick. No cfheaders ever arrives in this case. ----
    BRPeerManagerKeepAlive(m);

    check(g_capCount >= 1,
          "B1.1: KeepAlive issued a FORWARD getcfilters with NO cfheaders arrival (RED on -DCONVOY_NO_B1_DRIVER)");
    check(g_capStart == SCAN0, "B1.1: the forward fetch starts at the scan frontier (scannedThrough+1)");

    const uint32_t EXP_STOP = SCAN0 + (MAX_CFILTERS_RESULTS - 1u);
    check(EXP_STOP < CFH_FRONTIER, "setup: this first batch is MAX_CFILTERS_RESULTS-capped, not frontier-capped");
    check(m->autoFetchCFiltersThrough == EXP_STOP,
          "B1.1 CALLER-SIDE STEP 1: autoFetchCFiltersThrough advanced to reqStop "
          "(_BRPeerManagerRequestCFiltersLocked does NOT do this -- omit it and the drive re-requests forever)");
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == MAX_CFILTERS_RESULTS,
          "B1.1 CALLER-SIDE STEP 2: the in-flight heights were RECORDED in the ledger "
          "(omit it and _cfLedgerAdvance sails scannedThrough PAST an unscanned height = a silent missed receive)");
    check(findOutstanding(&m->cfLedger, SCAN0) != NULL && findOutstanding(&m->cfLedger, EXP_STOP) != NULL,
          "B1.1: both ends of the requested range are outstanding in the ledger");
    const BRCFOutstanding *e0 = findOutstanding(&m->cfLedger, SCAN0);
    check(e0 && e0->port == pa->port && UInt128Eq(e0->peer, pa->address),
          "B1.1: the ledger recorded the peer the getcfilters actually went to (re-request rotation stays correct)");

    // ---- The scan RESUMES CLIMBING: responses to the newly-created holes. ----
    uint32_t served[128];
    uint32_t scanBefore = BRCFScanLedgerScannedThrough(&m->cfLedger);
    int nServed = serveSome(m, 64, served);
    check(nServed == 64, "responses: 64 of the heights B1 actually requested were evaluated");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) == scanBefore + 64u,
          "SCAN RESUMES CLIMBING: scannedThrough advanced over the served heights (the trough is broken)");

    // ---- Multi-tick: the convoy keeps climbing on KeepAlive ALONE. ----
    for (int t = 0; t < 4; t++) {
        BRPeerManagerKeepAlive(m);
        serveSome(m, 128, served);
    }
    check(m->autoFetchCFiltersThrough == CFH_FRONTIER,
          "CONVERGENCE: the forward cursor climbed to the cfheader frontier across ticks (never past it)");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) == BASE + 499u + 64u + 4u*128u,
          "CONVERGENCE: scannedThrough kept climbing every tick (64 + 4x128 served, contiguous)");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) < BRCFScanLedgerLowestNeededHeight(&m->cfLedger),
          "INVARIANT: scannedThrough still sits below the lowest still-outstanding hole (no sail-past)");

    BRPeerManagerFree(m);
}

// B1.2 -- the cfheaders advance re-kick. The gate holds the clean-append
// continuation while W_cfh >= CF_CONVOY_WINDOW; once the SCAN frontier climbs
// enough to re-open the window, nothing in the reactive wire path re-fires it
// (the suppressed advance deliberately left cfHeadersRequestedThrough untouched,
// so there is no timeout to expire and no response to arrive). KeepAlive must.
static void test_b1_rekicks_cfheaders_on_window_reopen(BRWallet *wallet)
{
    printf("\n=== test_b1_rekicks_cfheaders_on_window_reopen (paced-convoy Task 3 / B1.2) ===\n");

    const uint32_t BASE  = 20000000u;
    const uint32_t COUNT = 3000u;
    uint32_t TIP; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(wallet, BASE, COUNT, &TIP, &baseCount);
    check(m != NULL, "manager+chain built");
    if (! m) return;
    (void)baseCount;

    BRCompactFilterChainFree(m->compactFilterChain);
    const uint32_t CFH_NEXT = BASE + 1000u;          // cfheader frontier == CFH_NEXT-1, a real next batch exists
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, CFH_NEXT, UINT256_ZERO);

    const uint32_t SCAN = BASE - 11000u;
    BRCFScanLedgerInit(&m->cfLedger, SCAN);
    m->autoFetchCFiltersEnabled = 1;
    m->autoFetchCFiltersStart   = SCAN;
    // Forward cursor pinned AT the cfheader frontier: the forward cfilter drive has
    // nothing to do, so cfheaders is the only variable in this case.
    m->autoFetchCFiltersThrough = CFH_NEXT - 1u;

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x32; pa->port = 12032; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    check(_cfConvoyCfhGated(m) == 1, "setup: the cfheader window is FULL (W_cfh >= CF_CONVOY_WINDOW)");
    g_cfhCount = 0; g_cfhStart = 0;
    _BRPeerManagerRequestNextCFHeaders(m, pa, /*isConvoyAdvance=*/1);
    check(g_cfhCount == 0, "setup: the convoy advance really is suppressed at a full window");
    check(m->cfHeadersRequestedThrough == 0,
          "setup: the suppressed advance left NO in-flight marker -- nothing will ever re-fire it by itself");

    g_cfhCount = 0;
    BRPeerManagerKeepAlive(m);
    check(g_cfhCount == 0,
          "B1.2: KeepAlive does NOT re-kick cfheaders while the window is STILL FULL (the driver respects the gate)");

    // The scan frontier climbs (a run of cfilters evaluated) -> the window re-opens.
    BRCFScanLedgerRecordRequested(&m->cfLedger, SCAN, SCAN + 1999u, UINT128_ZERO, 0, (uint32_t)time(NULL));
    for (uint32_t h = SCAN; h <= SCAN + 1999u; h++) BRCFScanLedgerMarkEvaluated(&m->cfLedger, h);
    check(BRCFScanLedgerLowestNeededHeight(&m->cfLedger) == SCAN + 2000u,
          "the scan frontier advanced by 2000 (MarkEvaluated run)");
    check(_cfConvoyCfhGated(m) == 0, "the cfheader window RE-OPENED (W_cfh now < CF_CONVOY_WINDOW)");

    g_cfhCount = 0; g_cfhStart = 0;
    BRPeerManagerKeepAlive(m);
    check(g_cfhCount == 1,
          "B1.2: the next KeepAlive tick RE-ISSUES the suppressed cfheaders advance "
          "(RED on -DCONVOY_NO_B1_DRIVER: nothing else can)");
    check(g_cfhStart == CFH_NEXT, "B1.2: the re-kick asks for the real next batch start (CFH_NEXT)");

    // ...and it is SERIALIZED, not a per-tick storm: the batch it just put in
    // flight blocks the next tick's re-kick (the driver reuses the existing
    // cfHeadersRequestedThrough guard rather than inventing a second throttle).
    g_cfhCount = 0;
    BRPeerManagerKeepAlive(m);
    check(g_cfhCount == 0, "B1.2: the re-kick is serialized by the in-flight guard (no per-tick cfheaders storm)");

    BRPeerManagerFree(m);
}

// B1.3 -- the getheaders advance re-kick, asserted on the MANAGER side.
//
// SCOPE (stated, not faked): BRPeer.c:648 -- where the CF-only 2000-header
// continuation is actually suppressed -- is file-static to a SEPARATE
// compilation unit here, so it cannot be driven from this TU. What this case
// covers end-to-end is the code Task 3 adds: KeepAlive re-issuing the
// continuation from the block-header tip. The suppression side stays covered
// only by the pushed-verdict assertion in test_convoy_gate_suppresses_continuations.
//
// The re-kick is deliberately conditioned on an OBSERVED FROZEN TIP (the header
// frontier not advancing across a whole tick) rather than firing every tick:
// during ordinary open-window header sync BRPeer.c's own continuation is already
// running, and an unconditional per-tick full-locator getheaders would duplicate
// every 2000-header batch -- ~0.44 MB of redundant traffic per tick on exactly
// the deep restore this feature exists to make cheap. Both directions are
// asserted below.
static void test_b1_rekicks_getheaders_when_tip_frozen(BRWallet *wallet)
{
    printf("\n=== test_b1_rekicks_getheaders_when_tip_frozen (paced-convoy Task 3 / B1.3) ===\n");

    const uint32_t BASE  = 20000000u;
    const uint32_t COUNT = 300u;
    uint32_t TIP; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(wallet, BASE, COUNT, &TIP, &baseCount);
    check(m != NULL, "manager+chain built");
    if (! m) return;
    (void)baseCount;

    // rhBuildChainManager anchors the CF chain NextHeight AT the block tip, so
    // pinning the forward cursor at NextHeight-1 leaves BOTH cfilter legs of the
    // driver with nothing to do: getheaders is the only variable in this case.
    m->autoFetchCFiltersEnabled = 1;
    m->autoFetchCFiltersStart   = BASE;
    m->autoFetchCFiltersThrough = TIP - 1u;
    BRCFScanLedgerInit(&m->cfLedger, BASE);
    m->estimatedHeight = TIP + 5000u;                 // the network is ahead: header work remains

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x33; pa->port = 12033; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);
    m->downloadPeer = pa;

    check(_cfConvoyHdrGated(m) == 0, "setup: the header window is OPEN");
    check(m->lastBlock && m->lastBlock->height == TIP && TIP < m->estimatedHeight,
          "setup: the block-header frontier sits below the network tip (header work remains)");

    g_hdrCount = 0; g_hdrLocators = 0;
    BRPeerManagerKeepAlive(m);
    check(g_hdrCount == 0,
          "B1.3: the first tick only SAMPLES the header tip -- no re-kick without an OBSERVED freeze");

    BRPeerManagerKeepAlive(m);
    check(g_hdrCount == 1,
          "B1.3: a header tip frozen across a whole tick, below the network tip, RE-ISSUES getheaders "
          "(RED on -DCONVOY_NO_B1_DRIVER)");
    check(g_hdrLocators >= 1, "B1.3: the re-kick carries real block locators (walk-back capable, same as the orphan re-anchor)");

    // CONTROL 1 -- a tip that ADVANCED this tick is NOT re-kicked. Without this,
    // "sent 1" above could be an unconditional per-tick getheaders (a duplicate-
    // header storm during ordinary sync) rather than a stall re-kick.
    BRMerkleBlock *nb = rhChainBlock(TIP + 1u);
    BRSetAdd(m->blocks, nb);
    m->lastBlock = nb;
    g_hdrCount = 0;
    BRPeerManagerKeepAlive(m);
    check(g_hdrCount == 0, "CONTROL: no re-kick on a tick where the header tip ADVANCED (no duplicate-header storm)");

    // CONTROL 2 -- a FULL header window suppresses the re-kick even with the tip
    // frozen: the driver un-suppresses the convoy, it does not defeat it.
    BRCFScanLedgerInit(&m->cfLedger, BASE - 11000u);
    check(_cfConvoyHdrGated(m) == 1, "control setup: the header window is FULL again");
    g_hdrCount = 0;
    BRPeerManagerKeepAlive(m);
    check(g_hdrCount == 0, "CONTROL: a FULL header window suppresses the re-kick (the convoy still paces)");

    // ...and re-opening the window releases that same frozen tip on the VERY NEXT
    // tick: a gated period issues no re-kicks, so it earns no penalty and carries
    // none across (fix round 2 -- the GATED->open episode reset). The escalated
    // form of this is test_b1_rekick_backoff_not_stale_across_gated_period below.
    BRCFScanLedgerInit(&m->cfLedger, BASE);
    check(_cfConvoyHdrGated(m) == 0, "control setup: the header window re-opened");
    g_hdrCount = 0;
    BRPeerManagerKeepAlive(m);
    check(g_hdrCount == 1, "B1.3: re-opening the window releases the frozen header tip on the very next tick");

    // ...and the rate limit is immediately back in force for the NEXT one: the
    // transition buys exactly one prompt re-kick, never a standing bypass.
    g_hdrCount = 0;
    BRPeerManagerKeepAlive(m);
    check(g_hdrCount == 0,
          "THROTTLE: the interval is back in force straight after the reopen re-kick (the transition is not a bypass)");

    // CONTROL 3 -- at the network tip there is no header work, so no re-kick
    // (a healthy at-tip wallet must not be nagged every 10 s).
    m->estimatedHeight = m->lastBlock->height;
    g_hdrCount = 0;
    BRPeerManagerKeepAlive(m);
    check(g_hdrCount == 0, "CONTROL: at the network tip (nothing left to fetch) the re-kick stays silent");

    BRPeerManagerFree(m);
}

// B1.3 RATE LIMIT (Task 3 fix round 1, review IMPORTANT-1).
//
// A frozen tip is necessary but NOT sufficient to justify a re-kick, and the
// frozen condition can be PERMANENT:
//   (a) slow link -- BRPeer.c issues its continuation BEFORE the relay loop, so
//       lastBlock does not move until a whole ~440 KB batch is parsed. One
//       injected getheaders per ~10 s tick then means several per batch, and
//       because count >= 2000 each reply spawns its OWN persistent, lockstep
//       continuation chain: N x ~2.2 MB of duplicate headers per window-open
//       period, recurring on every re-open of a multi-hour deep restore.
//   (b) THIS CASE -- estimatedHeight is only ever RAISED (never lowered), so a
//       peer that advertised a height we never reach leaves a FULLY SYNCED wallet
//       permanently `lastBlock->height < estimatedHeight` with W_hdr ~ 0, i.e.
//       the window permanently OPEN and the tip permanently frozen. Unthrottled
//       that is a ~1.2 KB full-locator getheaders every 10 s FOREVER (~10 MB/day
//       upstream), each answered with 0 headers. Steady state, not a transient.
//
// RED-before-green: run.sh builds this case with -DCONVOY_HDR_REKICK_UNTHROTTLED
// (the interval check compiled out, the backoff bookkeeping still live, so what
// goes red is the THROTTLE and not the arithmetic) and HARD-FAILS if it passes.
static void test_b1_getheaders_rekick_is_throttled(BRWallet *wallet)
{
    printf("\n=== test_b1_getheaders_rekick_is_throttled (Task 3 fix round 1: B1.3 rate limit) ===\n");

    const uint32_t BASE  = 20000000u;
    const uint32_t COUNT = 300u;
    uint32_t TIP; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(wallet, BASE, COUNT, &TIP, &baseCount);
    check(m != NULL, "manager+chain built");
    if (! m) return;
    (void)baseCount;

    // Forward cursor pinned at the cfheader frontier: both cfilter legs idle, so
    // getheaders is the only variable (same isolation as the B1.3 case above).
    m->autoFetchCFiltersEnabled = 1;
    m->autoFetchCFiltersStart   = BASE;
    m->autoFetchCFiltersThrough = TIP - 1u;
    BRCFScanLedgerInit(&m->cfLedger, BASE);
    m->estimatedHeight = TIP + 50000u;     // stale-HIGH forever: estimatedHeight is only ever RAISED

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x34; pa->port = 12034; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);
    m->downloadPeer = pa;

    check(_cfConvoyHdrGated(m) == 0,
          "setup: the header window is PERMANENTLY open (a synced wallet has W_hdr ~ 0, nowhere near W)");
    check(m->lastBlock && m->lastBlock->height < m->estimatedHeight,
          "setup: stale-HIGH estimatedHeight leaves a fully-synced wallet permanently 'below the network tip'");

    // ---- 12 consecutive frozen ticks. Unthrottled that is 11 getheaders. ----
    g_hdrCount = 0;
    for (int t = 0; t < 12; t++) BRPeerManagerKeepAlive(m);
    check(g_hdrCount == 1,
          "THROTTLE: 12 consecutive frozen ticks re-kick getheaders EXACTLY ONCE "
          "(RED on -DCONVOY_HDR_REKICK_UNTHROTTLED: one per tick, forever)");
    check(m->convoyHdrKickBackoff == CF_CONVOY_HDR_REKICK_BASE_SECS * 2u,
          "THROTTLE: the interval BACKED OFF after that unproductive re-kick (30 -> 60)");

    // ---- Only ELAPSED TIME releases the next one. The stamp is backdated because
    // the KAT cannot advance the real time(NULL) clock the driver reads. ----
    m->convoyLastHdrKickAt -= (time_t)(m->convoyHdrKickBackoff + 1u);
    g_hdrCount = 0;
    for (int t = 0; t < 5; t++) BRPeerManagerKeepAlive(m);
    check(g_hdrCount == 1, "THROTTLE: one elapsed interval buys exactly ONE more re-kick, not one per tick");
    check(m->convoyHdrKickBackoff == CF_CONVOY_HDR_REKICK_BASE_SECS * 4u,
          "THROTTLE: the interval keeps doubling while the tip stays frozen (60 -> 120)");

    // ---- ...and SATURATES: bounded by the ceiling, never unbounded (and never
    // stuck below it, which would leave the steady-state leak only half-fixed). ----
    for (int i = 0; i < 12; i++) {
        m->convoyLastHdrKickAt -= (time_t)(m->convoyHdrKickBackoff + 1u);
        BRPeerManagerKeepAlive(m);
    }
    check(m->convoyHdrKickBackoff == CF_CONVOY_HDR_REKICK_MAX_SECS,
          "THROTTLE: the backoff saturates at CF_CONVOY_HDR_REKICK_MAX_SECS (the steady-state leak is bounded)");

    // ---- RESET ON PROGRESS. This is what keeps the ceiling free: a tick where the
    // header tip actually ADVANCED clears the backoff, so a genuine window re-open
    // during a descent is re-kicked at BASE and is never billed the ceiling. ----
    BRMerkleBlock *nb = rhChainBlock(TIP + 1u);
    BRSetAdd(m->blocks, nb);
    m->lastBlock = nb;
    BRPeerManagerKeepAlive(m);
    check(m->convoyHdrKickBackoff == CF_CONVOY_HDR_REKICK_BASE_SECS,
          "RESET: real header progress clears the backoff to BASE "
          "(a genuine window re-open is never billed the ceiling -- the descent pays nothing)");

    BRPeerManagerFree(m);
}

// B1.3 GATED->open EPISODE RESET (Task 3 fix round 2, re-review IMPORTANT).
//
// The round-1 backoff punishes UNPRODUCTIVE RE-KICKS, and it was reset only on
// `!hdrFrozen` -- real header-tip progress. But `hdrFrozen` and the window
// verdict are INDEPENDENT predicates: _cfConvoyHdrGated flips on the SCAN
// frontier, which B1.2's floor-snap/re-anchor moves (and which climbs on its own
// during a descent), so the window can go open->full->open with lastBlock->height
// never advancing once. A genuinely stalled tip that had already escalated to the
// 600 s ceiling therefore carried that stale interval straight through the gated
// period, and the reopen -- the exact event B1.3 exists to serve -- was NOT
// released on the next tick but waited out up to 600 s. Pre-throttle, a reopen
// always fired on the very next ~10 s tick, so that was a latency regression
// introduced by the round-1 fix.
//
// This case models the sharpest form: the gated period is SHORT (one tick) and
// the carried backoff is at the ceiling, so nothing but the episode reset can
// make the reopen prompt.
//
// RED-before-green: run.sh builds this case with
// -DCONVOY_HDR_REKICK_STALE_ACROSS_GATE (the reset compiled out, the
// convoyHdrWasGated tracking still live, so what goes red is the RESET and not the
// transition detection) and HARD-FAILS if it passes.
static void test_b1_rekick_backoff_not_stale_across_gated_period(BRWallet *wallet)
{
    printf("\n=== test_b1_rekick_backoff_not_stale_across_gated_period (Task 3 fix round 2: episode reset) ===\n");

    const uint32_t BASE  = 20000000u;
    const uint32_t COUNT = 300u;
    uint32_t TIP; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(wallet, BASE, COUNT, &TIP, &baseCount);
    check(m != NULL, "manager+chain built");
    if (! m) return;
    (void)baseCount;

    // Forward cursor pinned at the cfheader frontier: both cfilter legs idle, so
    // getheaders is the only variable.
    m->autoFetchCFiltersEnabled = 1;
    m->autoFetchCFiltersStart   = BASE;
    m->autoFetchCFiltersThrough = TIP - 1u;
    BRCFScanLedgerInit(&m->cfLedger, BASE);       // W_hdr == 299 -> window OPEN
    m->estimatedHeight = TIP + 50000u;            // header work remains; the tip is genuinely stalled

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x35; pa->port = 12035; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);
    m->downloadPeer = pa;

    // ---- A genuinely stalled tip (dead/slow peer) escalates the backoff all the
    // way to the ceiling. Stamps are backdated because the KAT cannot advance the
    // real time(NULL) clock the driver reads. ----
    for (int i = 0; i < 8; i++) {
        m->convoyLastHdrKickAt -= (time_t)(m->convoyHdrKickBackoff + 1u);
        BRPeerManagerKeepAlive(m);
    }
    check(m->convoyHdrKickBackoff == CF_CONVOY_HDR_REKICK_MAX_SECS,
          "setup: a genuinely stalled tip escalated the backoff to the ceiling (600 s)");

    // ---- The convoy CLOSES the header window (B1.2's floor-snap/re-anchor moving
    // the scan frontier, or the scan falling a full window behind). NOTE the header
    // tip never advances anywhere in this case, so the !hdrFrozen reset can never
    // fire -- the episode reset is the ONLY thing that can clear the backoff. ----
    BRCFScanLedgerInit(&m->cfLedger, BASE - 11000u);
    check(_cfConvoyHdrGated(m) == 1, "the header window CLOSED (scan frontier moved; no re-kick is possible now)");
    g_hdrCount = 0;
    BRPeerManagerKeepAlive(m);
    check(g_hdrCount == 0, "gated: no re-kick goes out while the window is shut (so no penalty can be earned)");

    // ---- ...and it REOPENS with the tip STILL frozen. This is the resume-a-held-
    // continuation event B1.3 exists for: it must be served NOW, not after the
    // stale pre-gate 600 s interval runs out. ----
    BRCFScanLedgerInit(&m->cfLedger, BASE);
    check(_cfConvoyHdrGated(m) == 0, "the header window RE-OPENED with the tip still frozen");
    g_hdrCount = 0;
    BRPeerManagerKeepAlive(m);
    check(g_hdrCount == 1,
          "EPISODE RESET: the reopen is served on the VERY NEXT tick, not after the stale pre-gate interval "
          "(RED on -DCONVOY_HDR_REKICK_STALE_ACROSS_GATE: waits out up to 600 s)");
    check(m->convoyHdrKickBackoff == CF_CONVOY_HDR_REKICK_BASE_SECS * 2u,
          "EPISODE RESET: the stall episode RESTARTED at BASE (30 -> 60), it did not continue from the ceiling");

    // ---- NOT A BYPASS: the transition buys exactly ONE prompt re-kick; the rate
    // limit is immediately back in force (round-1's bandwidth property, locally). ----
    g_hdrCount = 0;
    for (int t = 0; t < 6; t++) BRPeerManagerKeepAlive(m);
    check(g_hdrCount == 0,
          "NOT A BYPASS: 6 further open-window ticks issue NO re-kick (the interval re-armed at BASE)");

    // ---- ROUND-1 PROPERTY INTACT: with the window staying open, a permanently
    // frozen tip still decays all the way back to the 600 s ceiling. ----
    for (int i = 0; i < 10; i++) {
        m->convoyLastHdrKickAt -= (time_t)(m->convoyHdrKickBackoff + 1u);
        BRPeerManagerKeepAlive(m);
    }
    check(m->convoyHdrKickBackoff == CF_CONVOY_HDR_REKICK_MAX_SECS,
          "ROUND-1 PROPERTY INTACT: an open window + permanently frozen tip still decays to the 600 s ceiling");

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

#ifdef KAT_REDGREEN_ONLY
    // run.sh builds this twice for the retention floor's red-before-green gate:
    // once unfixed (-DRETENTION_UNFIXED, must FAIL == RED) and once fixed (must
    // PASS == GREEN), running ONLY the scan-floor-retention case so the RED is
    // unambiguously the floor prune and nothing incidental.
    test_clearmemory_retains_scan_floor(wallet);
    BRWalletFree(wallet);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
#endif

#ifdef KAT_CEILING_REDGREEN_ONLY
    // run.sh builds this twice for the Part-3b determinism guard's red-before-green
    // gate: once with the pre-guard preemptive advance (-DRETENTION_PREEMPTIVE_ADVANCE,
    // must FAIL == RED — an empty-scan deep restore would raise the floor and complete
    // with a WRONG BALANCE) and once fixed (must PASS == GREEN), running ONLY the
    // scan-not-started case so the RED is unambiguously the preemptive abandonedBelow
    // raise and nothing incidental.
    test_clearmemory_ceiling_scan_not_started(wallet);
    BRWalletFree(wallet);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
#endif

#ifdef KAT_CONVOY_REDGREEN_ONLY
    // run.sh builds this twice for the convoy gate's red-before-green gate: once
    // with the suppression compiled out (-DCONVOY_UNGATED == the pre-fix shape:
    // the advance sends regardless and the peer flag is never raised, must FAIL
    // == RED) and once gated (must PASS == GREEN), running ONLY the gate case so
    // the RED is unambiguously the missing gate and nothing incidental.
    test_convoy_gate_suppresses_continuations(wallet);
    BRWalletFree(wallet);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
#endif

#ifdef KAT_CONVOY_NULL_REDGREEN_ONLY
    // run.sh builds this twice for the NULL-chain carve-out (blocker B-3): once
    // with the naive `NextHeight(chain) - 1` formula (-DCONVOY_NULLCHAIN_NAIVE,
    // which underflows to 0xFFFFFFFF on a NULL chain, scores the window FULL and
    // suppresses the first cfheaders request -- the fresh-deep-restore deadlock,
    // must FAIL == RED) and once with the carve-out (must PASS == GREEN).
    test_convoy_gate_null_chain_open(wallet);
    BRWalletFree(wallet);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
#endif

#ifdef KAT_B1_REDGREEN_ONLY
    // run.sh builds this twice for the B1 KeepAlive convoy driver's red-before-green
    // gate: once with the driver compiled out (-DCONVOY_NO_B1_DRIVER == the Task-2
    // gate WITHOUT its un-suppressor, i.e. the silent permanent wedge: a wallet
    // resumed at a drain trough never re-primes the forward fetch, the scan never
    // advances and deep history is silently never scanned -- must FAIL == RED) and
    // once with the driver (must PASS == GREEN), running ONLY the three B1 cases so
    // the RED is unambiguously the missing driver and nothing incidental.
    test_b1_resumes_drain_trough(wallet);
    test_b1_rekicks_cfheaders_on_window_reopen(wallet);
    test_b1_rekicks_getheaders_when_tip_frozen(wallet);
    test_b1_getheaders_rekick_is_throttled(wallet);
    test_b1_rekick_backoff_not_stale_across_gated_period(wallet);
    BRWalletFree(wallet);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
#endif

#ifdef KAT_B1_GATERESET_REDGREEN_ONLY
    // run.sh builds this twice for the B1.3 GATED->open episode reset's
    // red-before-green gate: once with the reset compiled out
    // (-DCONVOY_HDR_REKICK_STALE_ACROSS_GATE == the round-1 shape, where a stale
    // pre-gate backoff makes a window reopen wait out up to 600 s in exactly the
    // case B1.3 exists to serve -- must FAIL == RED) and once with the reset (must
    // PASS == GREEN), running ONLY that case so the RED is unambiguous.
    test_b1_rekick_backoff_not_stale_across_gated_period(wallet);
    BRWalletFree(wallet);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
#endif

#ifdef KAT_B1_THROTTLE_REDGREEN_ONLY
    // run.sh builds this twice for the B1.3 rate limit's red-before-green gate:
    // once with the interval check compiled out (-DCONVOY_HDR_REKICK_UNTHROTTLED ==
    // the pre-fix shape, one getheaders per frozen tick forever -- must FAIL == RED)
    // and once throttled (must PASS == GREEN), running ONLY the throttle case so the
    // RED is unambiguously the missing rate limit and nothing incidental.
    test_b1_getheaders_rekick_is_throttled(wallet);
    BRWalletFree(wallet);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
#endif

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
    test_residual_batched_preserves_semantics(wallet);     // Task 3: Pass A/B/C sends the fused set, discipline intact
    test_no_stale_between_pass_recommit(wallet);           // Task 3: between-pass staleness guard (RED before the guard)
    test_clearmemory_retains_scan_floor(wallet);           // Task 4: floor tracks the SCAN frontier (red-before-green)
    test_clearmemory_descent_frees(wallet);                // Task 4: full descent frees below the floor (no leak)
    test_clearmemory_ceiling_scan_not_started(wallet);     // Task 4b: ceiling timing branch 1 — scan-not-started wrong-balance guard
    test_clearmemory_ceiling_scan_started(wallet);         // Task 4b: ceiling timing branch 2 — abandon gaveUp, keep outstanding
    test_lowest_needed_accessor(wallet);                   // paced-convoy-fetch Task 1: frontier semantics anchor + BRPeerManager accessors
    test_convoy_gate_suppresses_continuations(wallet);     // paced-convoy-fetch Task 2: gate the tip-racers, exempt recovery (red-before-green)
    test_convoy_gate_null_chain_open(wallet);              // paced-convoy-fetch Task 2: NULL-chain carve-out (red-before-green)
    test_b1_resumes_drain_trough(wallet);                  // paced-convoy-fetch Task 3: B1.1 forward drive out of the drain trough (red-before-green)
    test_b1_rekicks_cfheaders_on_window_reopen(wallet);    // paced-convoy-fetch Task 3: B1.2 cfheaders re-kick on window re-open
    test_b1_rekicks_getheaders_when_tip_frozen(wallet);    // paced-convoy-fetch Task 3: B1.3 getheaders re-kick (manager side)
    test_b1_getheaders_rekick_is_throttled(wallet);        // paced-convoy-fetch Task 3 fix 1: B1.3 rate limit (red-before-green)
    test_b1_rekick_backoff_not_stale_across_gated_period(wallet); // paced-convoy-fetch Task 3 fix 2: GATED->open episode reset (red-before-green)

    BRWalletFree(wallet);

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
