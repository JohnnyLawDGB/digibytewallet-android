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

// --- CF-scan ABANDONMENT WARN-log capture seam ------------------------------
// The B2 abandonment valve (BRPeerManagerKeepAlive) warn-logs an "ABANDONED ..."
// line whenever it abandons >=1 retry-exhausted (gaveUp) height. In production
// that line routes to the platform logger at WARN (tag "bread"); on this host
// build it is otherwise silent. BRPeerManager.c #ifndef-guards CF_RETENTION_WLOG
// so this KAT can pre-#define it to capture the line and assert both directions:
// it FIRED exactly once on a genuine abandonment, and it did NOT fire on any of
// the must-not-abandon paths (== the LAB "abandonedBelow stayed 0, no ABANDONED
// line" acceptance). Because the ledger's determinism guard makes cnt>0 <=>
// abandonedBelow advanced <=> this WARN, the log count IS the abandonment count.
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
// Task 6 (scale KAT): the RAW stopHash of the last captured getcfilters. The
// existing g_capLog resolves stopHash -> height through the test-side blockReg
// registry, which is REG_MAX(8192)-bounded and O(n) -- unusable on a >100k chain.
// The scale case instead knows the expected stop HEIGHT itself and compares the
// raw hash against rhUniqueHash(expected), which is the sharper assertion anyway:
// it proves the manager's tip-down prevBlock walk resolved the right block at
// that frontier position, not merely that it resolved something.
static UInt256 g_capStopHash;

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
    g_capStopHash = stopHash;
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
static UInt256  g_cfhStopHash;   // Task 6 (scale KAT): raw stop hash, same reason as g_capStopHash

void __wrap_BRPeerSendGetCFHeaders(BRPeer *peer, uint8_t filterType, uint32_t startHeight, UInt256 stopHash)
{
    (void)peer; (void)filterType;
    g_cfhStart = startHeight;
    g_cfhStopHash = stopHash;
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

    // ORDERING IS LOAD-BEARING (paced-convoy Task 5): the sibling sits BELOW the
    // dead hole. This case isolates BRCFScanLedgerRetireCapped, and the B2
    // abandonment valve now runs in the SAME KeepAlive tick, immediately after
    // RetireCapped — by design, since a retry-exhausted hole that PINS the scan
    // frontier must never be left un-retried while a CF peer is connected (it would
    // pin the whole convoy forever). With the dead hole lowest, the valve would
    // correctly re-arm it straight back into `outstanding` on this very tick and
    // this case would be measuring the valve, not RetireCapped. Keeping a
    // still-outstanding sibling BELOW it means the dead hole is not the
    // frontier-pinning hole, so the valve correctly stands aside and the
    // RetireCapped boundary is observed clean. (The re-arm-on-the-retiring-tick
    // behaviour itself is covered by test_valve_matched_set case (a).)
    // Do NOT "simplify" this back to H_DEAD < H_SIB.
    const uint32_t H_SIB = 300, H_DEAD = 301, H_SLOW = 310;
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

// ===========================================================================
// PRODUCTION-SHAPED RESUME (fix wave C-1). rhBuildChainManager models a manager
// whose block window is fully RESIDENT — a live mid-session state, which is what
// the cases that use it need. It is NOT how a resumed process starts, and every
// resume case in this file used it, which is exactly how C-1 survived ten task
// reviews: they also HAND-SET m->autoFetchCFiltersStart to the birth height, the
// one value production cannot produce on a real resume.
//
// This builds session 2 the way BRPeerManagerNewEx really does — hand it the
// deserialized saved-blocks run and let it do what it does with it: every saved
// block goes into `orphans`, then the persisted run is chained DOWNWARD from the
// highest one (fix wave R2), so manager->blocks ends up holding the checkpoints
// plus the WHOLE [savedTip-(savedCount-1) .. savedTip] run and `orphans` empties.
// The resolvable block FLOOR is therefore savedTip-(savedCount-1), NOT the saved
// tip: before R2 the chaining ran FORWARD, looked for a CHILD of the highest saved
// block, found none, and left savedCount-1 headers stranded in `orphans` —
// unreachable for the whole session, so an ordinary abrupt kill of a healthy wallet
// surfaced a spurious 1–2 height "history gap" band over heights that WERE scanned.
// savedCount mirrors SAVE_BLOCK_COUNT, the real size of the persisted window.
static BRPeerManager *rhBuildResumedManager(BRWallet *wallet, uint32_t savedTip, uint32_t savedCount)
{
    BRMerkleBlock *saved[SAVE_BLOCK_COUNT];
    if (savedCount == 0 || savedCount > SAVE_BLOCK_COUNT) return NULL;
    for (uint32_t i = 0; i < savedCount; i++) {
        saved[i] = rhChainBlock(savedTip - (savedCount - 1) + i);
    }
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, saved, savedCount, NULL, 0);
    if (! m) return NULL;
    m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;   // startSync's applied BIP158 state
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
    // (cfNext-144, cfNext==tip). (Depth is no longer a factor at all since the
    // tip-anchored ceiling was removed — the floor is purely min(cfNext,lowestNeeded).)
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

// ---- Part-3b determinism guard, RE-HOMED onto the B2 valve's primitive ------
//
// BRCFScanLedgerAbandonGaveUpBelow advances abandonedBelow ONLY to cover gaveUp
// heights it ACTUALLY dropped (highest-dropped + 1) — never preemptively to the
// clamp. That equivalence (cnt>0 <=> abandonedBelow advanced <=> the caller's
// WARN fires) is the whole reason the B2 valve's operator contract holds:
// "every abandonment is a visible, warn-logged, countable event, and
// abandonedBelow == 0 is a VERIFIED fact, not an assumption". A preemptive raise
// would let a scan that never ran raise its own floor and complete with a WRONG
// BALANCE — deep history never scanned — the single worst outcome this subsystem
// can produce.
//
// This case used to be carried by test_clearmemory_ceiling_scan_not_started,
// which tested the tip-anchored DEPTH ceiling (_cfApplyRetentionCeiling) that
// paced-convoy Task 5 deletes. The guard itself is retained and the B2 valve is
// now its only production caller, so the red-before-green gate
// (-DDETERMINISM_GUARD_PREEMPTIVE_ADVANCE, run.sh's kat_determinism_guard_unguarded
// build) is re-pointed here rather than dropped.
//
// ⚠️ WHAT THAT GATE IS, PRECISELY (fix wave, Task-10 M3): it is the only
// RED-BEFORE-GREEN GATE on this guard, NOT the only coverage of the property.
// cf_scan_ledger_kat's test_abandon_no_advance_when_nothing_dropped asserts the same
// thing GREEN-ONLY — its runner has no pre-fix -D build for the guard, so it would
// pass against a broken guard. Do NOT delete the gate on the strength of "this is
// already covered elsewhere": a test that cannot go red proves nothing.
static void test_abandon_guard_no_preemptive_advance(BRWallet *wallet)
{
    printf("\n=== test_abandon_guard_no_preemptive_advance (Part 3b guard, re-homed onto B2) ===\n");
    (void)wallet;

    // The "scan not started" shape: a deep birth floor, nothing requested yet, so
    // there is NOTHING legitimately abandonable anywhere below the clamp.
    BRCFScanLedger l;
    BRCFScanLedgerInit(&l, 20000000u);
    check(BRCFScanLedgerOutstandingCount(&l) == 0 && BRCFScanLedgerGaveUpCount(&l) == 0,
          "setup: empty outstanding + empty gaveUp (nothing legitimately abandonable)");
    check(BRCFScanLedgerAbandonedBelow(&l) == 0, "setup: abandonedBelow starts at 0");

    uint32_t cnt = 999, lo = 999, hi = 999;
    uint32_t newFloor = BRCFScanLedgerAbandonGaveUpBelow(&l, 20500000u, &cnt, &lo, &hi);

    check(cnt == 0 && lo == CF_LEDGER_NO_DROP && hi == CF_LEDGER_NO_DROP,
          "nothing was dropped (cnt == 0, no [lo..hi] range)");
    check(BRCFScanLedgerAbandonedBelow(&l) == 0,
          "GUARD: abandonedBelow did NOT advance (stays 0) — no preemptive raise (RED on the pre-guard shape)");
    check(newFloor == 20000000u && BRCFScanLedgerLowestNeededHeight(&l) == 20000000u,
          "GUARD: the scan floor is NOT raised past unscanned history");
}

// ============================================================================
// Paced-convoy fetch, Task 5: THE B2 ABANDONMENT VALVE — MATCHED SET (a/b/c/d)
// (spec 2026-07-28-paced-convoy-fetch-design.md, Part B2 + Part F KAT #3)
// ============================================================================
//
// A gaveUp hole pins BRCFScanLedgerLowestNeededHeight forever (no driver ever
// re-requests a gaveUp height), and the paced convoy keys its fetch windows on
// exactly that frontier — so an un-retired gaveUp hole halts the WHOLE convoy
// permanently. But `gaveUp` means only "5 retries elapsed", which is a HEURISTIC
// for unservable: during a convoy climb retries can exhaust for transient,
// convoy-induced reasons. Abandon too eagerly and a real wallet receive is
// silently dropped; abandon too reluctantly and the sync convoy wedges forever.
//
// So this is ONE harness with ONE variable flipped four ways. It must discriminate
// on the OFFERED-vs-UN-OFFERED axis, not merely eager-vs-reluctant:
//   (a) slow-but-real     -> served on a re-armed cycle: NEVER abandoned. Fails a
//                            too-EAGER valve.
//   (b) unservable        -> abandoned after EXACTLY CF_CONVOY_REARM_MAX cycles,
//                            not one earlier and not one later, then the convoy
//                            proceeds. Fails a too-RELUCTANT valve AND pins the
//                            =2 tuning (a valve that re-arms N>2 times is a slow
//                            wedge).
//   (c) no CF peer        -> NEVER abandoned while no connected CF peer exists,
//                            even at the exact abandon threshold — then abandoned
//                            the moment one appears (so the assertion is not
//                            vacuous). Fails a PEER-BLIND valve.
//   (d) peer flap         -> CF peer present at cycle start, gone during the
//                            retries, back at the abandon check: NOT abandoned —
//                            then abandoned on the next CLEAN cycle (again, not
//                            vacuous). Fails a valve that checks peer presence at
//                            the abandon INSTANT instead of THROUGHOUT, which is
//                            precisely the fleet-saturation case.
//
// Red-before-green: run.sh builds this case four times, once per shape
// (-DCONVOY_NO_B2_VALVE / -DCONVOY_B2_PEER_BLIND / -DCONVOY_B2_IGNORE_OFFER_LATCH
// / -DCONVOY_B2_REARM_ONCE) and HARD-FAILS if any of them passes.

#define B2_CHAIN_BASE   500000u
#define B2_CHAIN_COUNT  40u

// Build the shared harness: a real prevBlock-linked chain, CF-only syncMode, the
// stopHash->height registry primed (so __wrap_BRPeerSendGetCFilters can fold each
// send into the causal everRequested set), and ONE CF-capable peer connected.
// autoFetchCFiltersEnabled is deliberately left 0 so the B1 convoy driver stays
// inert and the ONLY things under test are RetireCapped -> the valve -> the
// residual re-request driver.
static BRPeerManager *b2BuildHarness(BRWallet *wallet, uint32_t *outTip, BRPeer **outPeer)
{
    uint32_t tip; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(wallet, B2_CHAIN_BASE, B2_CHAIN_COUNT, &tip, &baseCount);
    if (! m) return NULL;

    blockRegReset();
    everReqReset();
    capLogReset();
    rhRegisterChain(B2_CHAIN_BASE, tip);

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x51; pa->port = 13051;
    pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    if (outTip)  *outTip  = tip;
    if (outPeer) *outPeer = pa;
    return m;
}

// Model "the peer set currently has / has no CF-capable member" WITHOUT removing
// the peer from connectedPeers (which would have to free it and would perturb the
// LSan-clean teardown). _BRPeerManagerPeerCanServeFilters — the exact predicate
// both the residual driver's Pass A and the valve use — keys on the services bit,
// so toggling it is a faithful stand-in for "the fleet rotated onto peers that
// don't serve filters".
static void b2SetPeerServesFilters(BRPeer *p, int serves)
{
    if (serves) p->services |=  SERVICES_NODE_COMPACT_FILTERS;
    else        p->services &= ~(uint64_t)SERVICES_NODE_COMPACT_FILTERS;
}

// Tick the driver until `height` has burned its full CF_REREQ_MAX_ATTEMPTS, by
// forcing its backoff clock to "elapsed" between ticks (requestedAt = 0). This
// exercises the REAL Pass A -> Pass C -> CommitRerequest path (and therefore the
// real latch wiring) without 7.5 minutes of wall clock. Stops early if the hole
// drained or is no longer outstanding. Returns the ticks spent.
static int b2RunCycleToExhaustion(BRPeerManager *m, uint32_t height, int maxTicks)
{
    int t = 0;
    for (; t < maxTicks; t++) {
        BRCFOutstanding *e = mutOutstanding(&m->cfLedger, height);
        if (! e) break;                                   // served, or already retired
        if (e->attempts >= CF_REREQ_MAX_ATTEMPTS) break;   // cycle burned
        e->requestedAt = 0;                                // backoff elapsed
        BRPeerManagerKeepAlive(m);
    }
    return t;
}

// The tick where RetireCapped parks the exhausted hole in gaveUp and the valve
// gets to decide (re-arm or abandon). Deliberately SEPARATE from the loop above
// so each case can change the peer set right before the decision.
static void b2TickValveDecision(BRPeerManager *m)
{
    BRPeerManagerKeepAlive(m);
}

// Run a whole valve round: burn the current cycle, then take the decision tick.
static void b2RunRound(BRPeerManager *m, uint32_t height)
{
    b2RunCycleToExhaustion(m, height, 16);
    b2TickValveDecision(m);
}

static uint8_t b2ParkedCycles(const BRCFScanLedger *l, uint32_t height)
{
    for (size_t i = 0; i < l->gaveUpCount; i++) if (l->gaveUp[i] == height) return l->gaveUpRearmCycles[i];
    return 0xFF;   // not parked
}

static uint8_t b2OutstandingCycles(const BRCFScanLedger *l, uint32_t height)
{
    const BRCFOutstanding *e = findOutstanding(l, height);
    return e ? e->rearmCycles : 0xFF;
}

// ---- (a) SLOW-BUT-REAL: served on a re-armed cycle -> NEVER abandoned -------
static void test_valve_case_a_slow_but_real(BRWallet *wallet)
{
    printf("\n--- (a) slow-but-real: the hole IS served on a re-armed cycle ---\n");

    uint32_t TIP; BRPeer *pa = NULL;
    BRPeerManager *m = b2BuildHarness(wallet, &TIP, &pa);
    check(m != NULL, "(a) setup: harness built");
    if (! m) return;

    const uint32_t H = B2_CHAIN_BASE + 10u;
    BRCFScanLedgerInit(&m->cfLedger, B2_CHAIN_BASE);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H, H, UINT128_ZERO, 0, 0);
    check(BRCFScanLedgerLowestNeededHeight(&m->cfLedger) == B2_CHAIN_BASE,
          "(a) setup: scan frontier at the birth floor, hole H outstanding");

    int wlogBefore = g_wlogCount;

    // Round 0: the ORIGINAL cycle exhausts -> parked -> the valve must RE-ARM
    // (never abandon: rearmCycles 0 < CF_CONVOY_REARM_MAX).
    b2RunRound(m, H);
    check(! gaveUpContains(&m->cfLedger, H), "(a) after cycle 0: hole is NOT left parked in gaveUp");
    check(findOutstanding(&m->cfLedger, H) != NULL, "(a) after cycle 0: hole RE-ARMED back into outstanding");
    check(b2OutstandingCycles(&m->cfLedger, H) == 1, "(a) after cycle 0: rearmCycles == 1 (one fresh cycle granted)");
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == 0, "(a) after cycle 0: abandonedBelow still 0");

    // The re-armed cycle is when the slow peer finally serves it. serveSome may
    // ONLY serve heights the driver actually re-requested (the causality spine),
    // so this is a genuine "a live CF peer answered the re-armed offer".
    check(everReqContains(H), "(a) the re-armed hole WAS re-requested on the wire (causality)");
    uint32_t served[4];
    int n = serveSome(m, 4, served);
    check(n == 1 && served[0] == H, "(a) the re-armed hole is SERVED");

    // Settle: several more ticks must not resurrect any abandonment.
    for (int i = 0; i < 4; i++) BRPeerManagerKeepAlive(m);

    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == 0,
          "(a) NOT ABANDONED: abandonedBelow never advanced (cnt == 0 on every tick)");
    check(g_wlogCount == wlogBefore, "(a) NOT ABANDONED: no ABANDONED warn-log ever fired");
    check(BRCFScanLedgerGaveUpCount(&m->cfLedger) == 0, "(a) gaveUp is empty (the hole drained cleanly)");
    check(findOutstanding(&m->cfLedger, H) == NULL, "(a) the hole is gone from outstanding (evaluated)");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) >= H,
          "(a) scannedThrough climbed PAST the served height — the scan frontier is unblocked");

    BRPeerManagerFree(m);
}

// ---- (b) GENUINELY UNSERVABLE: abandoned after EXACTLY REARM_MAX cycles -----
static void test_valve_case_b_unservable(BRWallet *wallet)
{
    printf("\n--- (b) genuinely unservable: abandoned after EXACTLY CF_CONVOY_REARM_MAX cycles ---\n");
    check(CF_CONVOY_REARM_MAX == 2, "(b) harness: CF_CONVOY_REARM_MAX is the pinned 2");

    uint32_t TIP; BRPeer *pa = NULL;
    BRPeerManager *m = b2BuildHarness(wallet, &TIP, &pa);
    check(m != NULL, "(b) setup: harness built");
    if (! m) return;

    const uint32_t H = B2_CHAIN_BASE + 10u;
    BRCFScanLedgerInit(&m->cfLedger, B2_CHAIN_BASE);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H, H, UINT128_ZERO, 0, 0);

    int wlogBefore = g_wlogCount;

    // Cycle 0 (the original exhaustion) -> re-arm #1. NOTHING is ever served.
    b2RunRound(m, H);
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == 0,
          "(b) end of cycle 0: NOT abandoned (the original exhaustion alone is never a licence)");
    check(b2OutstandingCycles(&m->cfLedger, H) == 1, "(b) end of cycle 0: rearmCycles == 1");

    // Cycle REARM_MAX-1 == cycle 1 -> re-arm #2. THE TUNING PIN: a valve that
    // abandons here (REARM_MAX effectively 1) lets a single unlucky peer-rotation
    // cycle false-positive, so this must still be un-abandoned.
    b2RunRound(m, H);
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == 0,
          "(b) END OF CYCLE REARM_MAX-1: abandonedBelow has NOT advanced (RED on a re-arm-once valve)");
    check(g_wlogCount == wlogBefore, "(b) end of cycle REARM_MAX-1: still no ABANDONED warn-log");
    check(b2OutstandingCycles(&m->cfLedger, H) == CF_CONVOY_REARM_MAX,
          "(b) end of cycle REARM_MAX-1: rearmCycles == CF_CONVOY_REARM_MAX (the deciding cycle is armed)");

    // Cycle REARM_MAX == cycle 2 -> re-exhausted, fully offered-and-refused by a
    // live CF peer, with a CF peer still connected -> ABANDON.
    b2RunRound(m, H);
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == H + 1u,
          "(b) END OF CYCLE REARM_MAX: ABANDONED — abandonedBelow == H+1 (RED on a too-reluctant valve)");
    check(g_wlogCount == wlogBefore + 1, "(b) exactly ONE ABANDONED warn-log fired (cnt>0 <=> WARN <=> advance)");
    check(strstr(g_wlogLast, "ABANDONED") != NULL, "(b) the captured warn-log names the ABANDONED event");
    check(! gaveUpContains(&m->cfLedger, H), "(b) the abandoned hole is dropped from gaveUp");
    check(BRCFScanLedgerLowestNeededHeight(&m->cfLedger) == H + 1u,
          "(b) the scan frontier JUMPED past the abandoned hole (the convoy windows re-open)");

    // ...and the convoy actually PROCEEDS: the next heights are requestable,
    // servable, and the frontier keeps climbing. No wedge.
    const uint32_t H2 = H + 1u, H3 = H + 3u;
    BRCFScanLedgerRecordRequested(&m->cfLedger, H2, H3, UINT128_ZERO, 0, 0);
    for (int i = 0; i < 3 && BRCFScanLedgerOutstandingCount(&m->cfLedger) > 0; i++) {
        for (size_t k = 0; k < m->cfLedger.outstandingCount; k++) m->cfLedger.outstanding[k].requestedAt = 0;
        BRPeerManagerKeepAlive(m);
        uint32_t srv[8];
        serveSome(m, 8, srv);
    }
    check(BRCFScanLedgerOutstandingCount(&m->cfLedger) == 0, "(b) NO WEDGE: the following heights all drained");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) >= H3,
          "(b) NO WEDGE: scannedThrough climbed past the abandoned band and kept going");

    BRPeerManagerFree(m);
}

// ---- (c) NOT THE HEIGHT'S FAULT: zero connected CF peers -> never abandoned --
static void test_valve_case_c_no_cf_peer(BRWallet *wallet)
{
    printf("\n--- (c) no CF peer connected: NEVER abandoned (then abandoned once one returns) ---\n");

    uint32_t TIP; BRPeer *pa = NULL;
    BRPeerManager *m = b2BuildHarness(wallet, &TIP, &pa);
    check(m != NULL, "(c) setup: harness built");
    if (! m) return;

    const uint32_t H = B2_CHAIN_BASE + 10u;
    BRCFScanLedgerInit(&m->cfLedger, B2_CHAIN_BASE);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H, H, UINT128_ZERO, 0, 0);

    int wlogBefore = g_wlogCount;

    // Bring the hole to the EXACT abandon threshold with a CF peer present:
    // cycles 0 and 1 re-arm it, cycle REARM_MAX burns fully against a live peer.
    b2RunRound(m, H);                       // -> rearmCycles 1
    b2RunRound(m, H);                       // -> rearmCycles 2 == CF_CONVOY_REARM_MAX
    b2RunCycleToExhaustion(m, H, 16);       // deciding cycle burned, still outstanding
    check(b2OutstandingCycles(&m->cfLedger, H) == CF_CONVOY_REARM_MAX,
          "(c) setup: the hole is at the EXACT abandon threshold (rearmCycles == REARM_MAX, cycle burned)");
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == 0, "(c) setup: nothing abandoned yet");

    // Now the fleet rotates onto peers that do not serve filters — zero connected
    // CF-capable peers at the decision. This stall is NOT the height's fault.
    b2SetPeerServesFilters(pa, 0);
    check(_BRPeerManagerPeerCanServeFilters(pa) == 0, "(c) setup: no connected peer can serve filters");

    b2TickValveDecision(m);
    check(gaveUpContains(&m->cfLedger, H), "(c) the hole is parked in gaveUp (RetireCapped ran)");
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == 0,
          "(c) NEVER ABANDONED with zero CF peers (RED on a peer-blind valve)");
    check(g_wlogCount == wlogBefore, "(c) no ABANDONED warn-log fired");
    check(b2ParkedCycles(&m->cfLedger, H) == CF_CONVOY_REARM_MAX,
          "(c) the parked hole's re-arm state is untouched — the valve did NOTHING AT ALL");

    // Stay peer-less for a long while: still never abandoned, and still not
    // silently discarded either (it remains a reported hole).
    for (int i = 0; i < 12; i++) BRPeerManagerKeepAlive(m);
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == 0,
          "(c) still NEVER abandoned after 12 more peer-less ticks");
    check(gaveUpContains(&m->cfLedger, H), "(c) the hole is still REPORTED in gaveUp (never silently dropped)");
    check(g_wlogCount == wlogBefore, "(c) still no ABANDONED warn-log");

    // NON-VACUITY: the ONLY thing withholding abandonment was peer absence. Put a
    // CF peer back and the very next tick abandons.
    b2SetPeerServesFilters(pa, 1);
    b2TickValveDecision(m);
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == H + 1u,
          "(c) NON-VACUOUS: with a CF peer back, the same state abandons immediately");
    check(g_wlogCount == wlogBefore + 1, "(c) NON-VACUOUS: the ABANDONED warn-log fires only now");

    BRPeerManagerFree(m);
}

// ---- (d) PEER FLAP during the deciding cycle -> NOT abandoned ---------------
static void test_valve_case_d_peer_flap(BRWallet *wallet)
{
    printf("\n--- (d) peer flap during the deciding cycle: NOT abandoned (then abandoned on a clean cycle) ---\n");

    uint32_t TIP; BRPeer *pa = NULL;
    BRPeerManager *m = b2BuildHarness(wallet, &TIP, &pa);
    check(m != NULL, "(d) setup: harness built");
    if (! m) return;

    const uint32_t H = B2_CHAIN_BASE + 10u;
    BRCFScanLedgerInit(&m->cfLedger, B2_CHAIN_BASE);
    BRCFScanLedgerRecordRequested(&m->cfLedger, H, H, UINT128_ZERO, 0, 0);

    int wlogBefore = g_wlogCount;

    // Cycles 0 and 1: clean, with a CF peer -> the hole reaches the deciding cycle.
    b2RunRound(m, H);
    b2RunRound(m, H);
    check(b2OutstandingCycles(&m->cfLedger, H) == CF_CONVOY_REARM_MAX,
          "(d) setup: the DECIDING cycle is armed (rearmCycles == REARM_MAX)");
    const BRCFOutstanding *e0 = findOutstanding(&m->cfLedger, H);
    check(e0 && e0->offersReachedLivePeer == 1, "(d) setup: the deciding cycle starts UNTAINTED");

    // --- THE FLAP ---
    // Two offers land on the live peer...
    for (int i = 0; i < 2; i++) {
        BRCFOutstanding *e = mutOutstanding(&m->cfLedger, H);
        if (e) e->requestedAt = 0;
        BRPeerManagerKeepAlive(m);
    }
    // ...then the peer DISCONNECTS with an offer in flight. This is exactly what
    // production does on a drop (_peerDisconnected -> BRCFScanLedgerReArmPeer),
    // plus the peer no longer being CF-usable for the next few ticks.
    BRCFScanLedgerReArmPeer(&m->cfLedger, pa->address, pa->port);
    b2SetPeerServesFilters(pa, 0);
    const BRCFOutstanding *e1 = findOutstanding(&m->cfLedger, H);
    check(e1 && e1->offersReachedLivePeer == 0,
          "(d) THE LATCH: the in-flight offer's peer vanished -> the deciding cycle is TAINTED");
    for (int i = 0; i < 2; i++) {                 // due, but nobody to offer to
        BRCFOutstanding *e = mutOutstanding(&m->cfLedger, H);
        if (e) e->requestedAt = 0;
        BRPeerManagerKeepAlive(m);
    }
    // ...and it is BACK by the abandon check — the fleet-saturation shape.
    b2SetPeerServesFilters(pa, 1);
    b2RunCycleToExhaustion(m, H, 16);
    b2TickValveDecision(m);

    check(_BRPeerManagerPeerCanServeFilters(pa) == 1, "(d) a CF peer IS connected at the abandon check");
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == 0,
          "(d) NOT ABANDONED: the deciding cycle's offers did not all reach a live peer "
          "(RED on a valve that checks peer presence at the abandon INSTANT)");
    check(g_wlogCount == wlogBefore, "(d) no ABANDONED warn-log fired");
    check(findOutstanding(&m->cfLedger, H) != NULL,
          "(d) the hole is RE-ARMED again instead — a tainted cycle is never the deciding one");
    check(b2OutstandingCycles(&m->cfLedger, H) == CF_CONVOY_REARM_MAX + 1,
          "(d) rearmCycles kept climbing past REARM_MAX (the valve keeps working the hole, it does not wedge)");

    // NON-VACUITY: the ONLY thing withholding abandonment was the taint. One CLEAN
    // cycle with the peer present and it abandons.
    b2RunRound(m, H);
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == H + 1u,
          "(d) NON-VACUOUS: the next CLEAN, fully-live-offered cycle DOES abandon");
    check(g_wlogCount == wlogBefore + 1, "(d) NON-VACUOUS: exactly one ABANDONED warn-log, and only now");

    BRPeerManagerFree(m);
}

static void test_valve_matched_set(BRWallet *wallet)
{
    printf("\n=== test_valve_matched_set (paced-convoy Task 5: B2 connected-subset-refusal valve) ===\n");
    test_valve_case_a_slow_but_real(wallet);
    test_valve_case_b_unservable(wallet);
    test_valve_case_c_no_cf_peer(wallet);
    test_valve_case_d_peer_flap(wallet);
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
    // (production path: BRCFScanLedgerAbandonGaveUpBelow, driven by the B2 valve;
    // anchored directly here the same way cf_scan_ledger_kat's
    // test_lowest_needed_height moves the watermark, since AbandonGaveUpBelow
    // itself never advances past a still-outstanding hole — that guard is proven
    // separately by test_abandon_guard_no_preemptive_advance and by
    // cf_scan_ledger_kat's test_abandon_advance_covers_dropped_only).
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

    // Arm through the REAL production entry point, then restore, then reconcile —
    // no hand-set autoFetchCFiltersStart/Through. (That hand-set BASE was the value
    // production cannot produce on a real resume, and it is how C-1 hid here.)
    BRPeerManagerEnableAutoCompactFilterFetch(m, BASE);
    check(m->autoFetchCFiltersStart == BASE && m->autoFetchCFiltersThrough == BASE - 1u,
          "ARM: EnableAutoCompactFilterFetch(BASE) resolves against the resident window (no clamp)");

    check(BRCFScanLedgerParse(&m->cfLedger, blob, blobLen) == 1, "resume: the persisted ledger parsed back in");
    free(blob);

    BRPeerManagerSnapAutoFetchThroughToScanFrontier(m);

    const uint32_t SCAN0 = BRCFScanLedgerLowestNeededHeight(&m->cfLedger);   // BASE+500
    // SCOPE, STATED (fix wave C-1): this case deliberately models a manager whose
    // block window still COVERS the scan frontier — a live mid-session state. It
    // says nothing about the resumed-process shape, where BRPeerManagerNewEx leaves
    // the block floor at savedTip-(SAVE_BLOCK_COUNT-1), still far above a frontier a
    // full convoy window down; that is test_resume_below_block_floor_surfaces.
    // Asserted, not assumed:
    check(_BRPeerManagerBlockFloor(m) <= SCAN0,
          "SCOPE: the block floor is at or below the scan frontier here (the resumed-process "
          "shape, floor ABOVE the frontier, is covered by test_resume_below_block_floor_surfaces)");
    check(m->autoFetchCFiltersThrough == SCAN0 - 1u,
          "RECONCILED: the cursor sits at LowestNeededHeight-1 (produced by the real snap)");
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

// ---- Paced-convoy fetch, Task 4: RESUME CURSOR RECONCILIATION --------------
// (spec 2026-07-28-paced-convoy-fetch-design.md, Part B1-resume)
//
// Production resume order (SyncService.kt): enableAutoCompactFilterFetch(birth)
// arms autoFetchCFiltersThrough = birth-1, THEN startSync(), THEN
// restoreCfScanLedger() overwrites the ledger with the PERSISTED one, whose
// scannedThrough can sit far above birth. The cursor is not itself persisted, so
// without a reconciliation step it stays at birth-1 while the ledger says the
// scan already got far past that. The next forward-fetch tick then computes
// reqStart = autoFetchCFiltersThrough+1 == birth, re-requests ALREADY-SCANNED
// history, and BRCFScanLedgerRecordRequested re-inserts those heights as
// outstanding -- dragging scannedThrough back down and discarding the persisted
// progress. Under the paced convoy's KeepAlive drive (B1.1, ~10 s ticks) this
// would repeat every tick, not just once per resume.
//
// THE OFF-BY-ONE: the fix must snap to LowestNeededHeight-1, NOT
// LowestNeededHeight itself. reqStart is autoFetchCFiltersThrough+1, so snapping
// to LowestNeededHeight would make the very next fetch start at
// LowestNeededHeight+1 -- silently skipping exactly the one height the scan was
// waiting on, forever. The single g_capStart==LOWEST assertion below is
// discriminating in BOTH directions: it fails if the snap doesn't run at all
// (g_capStart lands at BASE, the re-scan), and it fails if the snap uses LOWEST
// instead of LOWEST-1 (g_capStart lands at LOWEST+1, the skip).
//
// RED-before-green: run.sh builds this case with -DRESUME_SNAP_UNFIXED (the
// snap compiled to a no-op -- BRPeerManagerSnapAutoFetchThroughToScanFrontier's
// body skipped, cursor stays at birth-1) and HARD-FAILS if it passes.
static void test_resume_snaps_cursor(BRWallet *wallet)
{
    printf("\n=== test_resume_snaps_cursor (paced-convoy Task 4: resume cursor reconciliation) ===\n");

    const uint32_t BASE  = 20000000u;
    const uint32_t COUNT = 3000u;

    // ---- Session 1: the scan descended partway, then PERSISTED. Serialize/Parse
    // (not a hand-built struct) so the resume goes through the same path
    // production uses (CfScanLedgerStore.save -> ... -> restoreCfScanLedger). ----
    BRCFScanLedger persisted;
    BRCFScanLedgerInit(&persisted, BASE);
    BRCFScanLedgerRecordRequested(&persisted, BASE, BASE + 2499u, UINT128_ZERO, 0, 1700000000u);
    for (uint32_t h = BASE; h <= BASE + 2499u; h++) BRCFScanLedgerMarkEvaluated(&persisted, h);
    check(BRCFScanLedgerScannedThrough(&persisted) == BASE + 2499u, "session 1: scannedThrough == BASE+2499");

    size_t blobLen = BRCFScanLedgerSerialize(&persisted, NULL, 0);
    uint8_t *blob  = (blobLen > 0) ? malloc(blobLen) : NULL;
    check(blob != NULL && BRCFScanLedgerSerialize(&persisted, blob, blobLen) == blobLen,
          "session 1: ledger serialized (the persisted blob the resume restores)");
    BRCFScanLedgerFree(&persisted);
    if (! blob) return;

    // ---- Session 2: fresh manager. Reproduce the PRODUCTION RESUME ORDER
    // exactly: enableAutoCompactFilterFetch(birth) arms the cursor at birth-1
    // FIRST, the persisted ledger is restored SECOND. ----
    uint32_t TIP; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(wallet, BASE, COUNT, &TIP, &baseCount);
    check(m != NULL, "session 2: manager+chain built");
    if (! m) { free(blob); return; }
    (void)baseCount;

    BRPeerManagerEnableAutoCompactFilterFetch(m, BASE);
    check(m->autoFetchCFiltersThrough == BASE - 1u,
          "ARM: enableAutoCompactFilterFetch(birth) sets the cursor to birth-1 (BASE-1)");

    // The cfheader chain got ahead of the scan before the kill (same shape as
    // test_b1_resumes_drain_trough); keep it below the block tip so a real next
    // batch exists for the behavioral drive below.
    BRCompactFilterChainFree(m->compactFilterChain);
    const uint32_t CFH_NEXT = BASE + 2900u;
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, CFH_NEXT, UINT256_ZERO);

    check(BRCFScanLedgerParse(&m->cfLedger, blob, blobLen) == 1,
          "RESTORE: the persisted ledger (scannedThrough far above birth) parsed back in");
    free(blob);

    const uint32_t LOWEST = BRCFScanLedgerLowestNeededHeight(&m->cfLedger);
    check(LOWEST == BASE + 2500u, "setup: LowestNeededHeight == scannedThrough+1 == BASE+2500 (far above birth)");
    // SCOPE, STATED (fix wave C-1): rhBuildChainManager leaves the whole chain
    // resident, so EnableAutoCompactFilterFetch's resolvability clamp does NOT fire
    // and the block floor stays below the frontier. A real resumed process is the
    // opposite on both counts — see test_resume_below_block_floor_surfaces.
    check(_BRPeerManagerBlockFloor(m) <= LOWEST,
          "SCOPE: the block floor is at or below the scan frontier here (no clamp fired)");

    // ---- THE BUG, caught pre-snap: the cursor is STILL birth-1 after the
    // restore -- the resume order left it stale. ----
    check(m->autoFetchCFiltersThrough == BASE - 1u,
          "PRE-SNAP: cursor is still birth-1 after the restore -- THE BUG (unreconciled, would re-scan from birth)");

    // ---- THE FIX ----
    BRPeerManagerSnapAutoFetchThroughToScanFrontier(m);
    check(m->autoFetchCFiltersThrough == LOWEST - 1u,
          "SNAP (RED on -DRESUME_SNAP_UNFIXED): autoFetchCFiltersThrough raised to LowestNeededHeight-1 "
          "(BASE+2499) -- NOT birth-1, and NOT LowestNeededHeight itself");

    // ---- Behavioral consequence: drive ONE KeepAlive tick (the B1.1 forward
    // drive) and assert the getcfilters reqStart == LOWEST EXACTLY. This is the
    // off-by-one catch: reqStart would be BASE if the snap never ran (re-scan of
    // already-scanned history), or LOWEST+1 if the snap landed on LOWEST instead
    // of LOWEST-1 (a silently skipped height). Only the correct snap produces
    // reqStart == LOWEST. ----
    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x41; pa->port = 12041; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    blockRegReset(); everReqReset(); capLogReset();
    rhRegisterChain(BASE, TIP);
    g_capCount = 0; g_capStart = 0;

    BRPeerManagerKeepAlive(m);

    check(g_capCount >= 1, "DRIVE: KeepAlive issued a forward getcfilters after the snap");
    check(g_capStart == LOWEST,
          "THE OFF-BY-ONE CATCH (RED on -DRESUME_SNAP_UNFIXED): reqStart == LowestNeededHeight exactly -- "
          "no re-request of already-scanned [BASE..BASE+2499] AND no skipped height");

    BRPeerManagerFree(m);

    // ---- Abandonment case: the ledger's hard retention floor (abandonedBelow)
    // must win over scannedThrough+1 when it is higher -- the snap must never
    // leave the cursor low enough to re-request a permanently abandoned height. ----
    BRPeerManager *m2 = rhBuildChainManager(wallet, BASE, COUNT, &TIP, &baseCount);
    check(m2 != NULL, "abandonment case: manager+chain built");
    if (! m2) return;

    BRPeerManagerEnableAutoCompactFilterFetch(m2, BASE);
    check(m2->autoFetchCFiltersThrough == BASE - 1u, "abandonment: armed at birth-1 before restore");

    // scannedThrough == BASE+49 (contiguous evaluated prefix; BASE+50..BASE+99
    // stay outstanding) -- then raise the hard floor ABOVE scannedThrough+1, the
    // same way test_lowest_needed_accessor anchors abandonedBelow directly
    // (production reaches this via BRCFScanLedgerAbandonGaveUpBelow, the
    // CF-retention memory ceiling).
    BRCFScanLedgerInit(&m2->cfLedger, BASE);
    BRCFScanLedgerRecordRequested(&m2->cfLedger, BASE, BASE + 99u, UINT128_ZERO, 0, 1700000000u);
    for (uint32_t h = BASE; h <= BASE + 49u; h++) BRCFScanLedgerMarkEvaluated(&m2->cfLedger, h);
    m2->cfLedger.abandonedBelow = BASE + 200u;
    check(BRCFScanLedgerLowestNeededHeight(&m2->cfLedger) == BASE + 200u,
          "abandonment setup: LowestNeededHeight folds in the raised abandonedBelow (200 > scannedThrough+1==50)");

    BRPeerManagerSnapAutoFetchThroughToScanFrontier(m2);
    check(m2->autoFetchCFiltersThrough == BASE + 199u,
          "ABANDONMENT (RED on -DRESUME_SNAP_UNFIXED): the snap lands at abandonedBelow-1 (BASE+199) -- the "
          "ledger's hard floor is respected, no pointless re-request below it");

    BRPeerManagerFree(m2);
}

// ============================================================================
// FIX WAVE C-1: resume mid-descent must not silently skip CF_CONVOY_WINDOW blocks
// ============================================================================
//
// THE SEQUENCE, built out of production code only:
//   session 1 descends to scan frontier S while the convoy holds the header tip
//   at exactly S + CF_CONVOY_WINDOW. The process is killed. `saved_blocks` holds
//   the top SAVE_BLOCK_COUNT headers of that window; `saved_cf_ledger` holds the
//   scan ledger; `saved_filter_headers` holds the cfheader chain.
//   session 2: BRPeerManagerNewEx chains the persisted run DOWNWARD from the
//   highest saved block (fix wave R2), so the block FLOOR is
//   F = savedTip - (SAVE_BLOCK_COUNT-1) = S + W - 299 — still ~a full convoy
//   window above the restored scan frontier, because 300 << CF_CONVOY_WINDOW.
//   enableAutoCompactFilterFetch is called with the (never-advanced) deep
//   cf_birth_height, which still cannot resolve, so its clamp arms start AND
//   cursor at the saved tip A. THEN the ledger is restored, putting the scan
//   frontier back at S — a full window BELOW everything the manager can resolve.
//
// R2 IS ASSERTED HERE TOO, on the same manager, because it is the SAME structural
// fact: what the resumed process can resolve. R2 moves the floor from A down to F,
// which shrinks the surfaced band by 299 heights and — the point of R2 — makes the
// 1–2 height case (an ordinary abrupt kill of a healthy wallet, where the 20-s
// coalesced CF-ledger write trails the per-callback saved-blocks write) resolvable
// instead of surfaced. It deliberately does NOT cure the deep-restore band below.
//
// The band [S .. F-1] is unservable for the whole session in BOTH directions: no
// getcfilters can carry a resolvable stop hash for it, and a volunteered cfilter
// would be dropped by _peerRelayedCFilter as an unknown block. Before the fix the
// raise-only snap could not pull start/cursor back down, so the next forward fetch
// began at A and _cfLedgerAdvance sailed scannedThrough from S-1 to A-1 with
// abandonedBelow still 0 — ~10,000 heights marked scanned, never requested, no
// WARN, no banner, wallet reaches Synced. On EVERY resume of a deep descent.
//
// THE REQUIREMENT ASSERTED HERE (derived from the requirement, not from what the
// implementation prints): no height is ever marked scanned without either being
// scanned or being surfaced as abandoned.
static void test_resume_below_block_floor_surfaces(BRWallet *wallet)
{
    printf("\n=== test_resume_below_block_floor_surfaces (fix wave C-1 + R2) ===\n");

    const uint32_t SCAN      = 20000000u;                    // session 1's scan frontier S
    const uint32_t BIRTH     = SCAN - 3000u;                 // the deep cf_birth_height, never advanced
    const uint32_t SAVED_TIP = SCAN + CF_CONVOY_WINDOW;      // A: where the convoy pins the header tip
    const uint32_t CFH_NEXT  = SAVED_TIP + 1u;               // restored cfheader frontier == A
    // F: the resolvable block floor of a resumed manager — DERIVED from
    // SAVE_BLOCK_COUNT, never hardcoded, so it tracks the persisted window size.
    const uint32_t RESUME_FLOOR = SAVED_TIP - (SAVE_BLOCK_COUNT - 1u);

    // ---- session 1: a real ledger descended from BIRTH to S, then persisted --
    BRCFScanLedger persisted;
    BRCFScanLedgerInit(&persisted, BIRTH);
    BRCFScanLedgerRecordRequested(&persisted, BIRTH, SCAN - 1u, UINT128_ZERO, 0, 1700000000u);
    for (uint32_t h = BIRTH; h <= SCAN - 1u; h++) BRCFScanLedgerMarkEvaluated(&persisted, h);
    check(BRCFScanLedgerScannedThrough(&persisted) == SCAN - 1u &&
          BRCFScanLedgerOutstandingCount(&persisted) == 0,
          "session 1: the scan drained to the frontier S (the DRAIN TROUGH shape)");
    size_t blobLen = BRCFScanLedgerSerialize(&persisted, NULL, 0);
    uint8_t *blob  = (blobLen > 0) ? malloc(blobLen) : NULL;
    check(blob != NULL && BRCFScanLedgerSerialize(&persisted, blob, blobLen) == blobLen,
          "session 1: ledger serialized (the blob CfScanLedgerStore persists)");
    BRCFScanLedgerFree(&persisted);
    if (! blob) return;

    // ---- session 2, PRODUCTION SHAPE ----------------------------------------
    BRPeerManager *m = rhBuildResumedManager(wallet, SAVED_TIP, SAVE_BLOCK_COUNT);
    check(m != NULL, "session 2: manager built by the REAL BRPeerManagerNewEx from saved_blocks");
    if (! m) { free(blob); return; }

    // THE PRODUCTION SHAPE, asserted rather than assumed. BRPeerManagerNewEx puts
    // every saved header in `orphans`, then chains the run DOWNWARD from the highest
    // (fix wave R2) -- so the WHOLE persisted run reaches `blocks` and `orphans` empties.
    check(BRSetCount(m->orphans) == 0,
          "R2 PRODUCTION SHAPE (RED on -DRESUME_FLOOR_UNFIXED): the whole saved run was chained "
          "into `blocks` -- NO saved header is left stranded in `orphans`");
    check(m->lastBlock && m->lastBlock->height == SAVED_TIP, "PRODUCTION SHAPE: lastBlock is the saved tip");
    check(_BRPeerManagerBlockFloor(m) == RESUME_FLOOR,
          "R2 PRODUCTION SHAPE (RED on -DRESUME_FLOOR_UNFIXED): the resolvable block FLOOR is "
          "savedTip-(SAVE_BLOCK_COUNT-1), not the saved tip -- so the 1-2 heights an abrupt kill "
          "of a HEALTHY wallet leaves the coalesced CF ledger trailing by are RESOLVABLE, and no "
          "band is surfaced for them at all");
    // Every height of the persisted run resolves, not merely the two endpoints:
    // the floor walk proves the chain is CONNECTED, this proves it is COMPLETE.
    {
        int missing = 0;
        for (uint32_t h = RESUME_FLOOR; h <= SAVED_TIP; h++) {
            if (UInt256IsZero(_BRPeerManagerBlockHashAtHeight(m, h))) { missing = 1; break; }
        }
        check(! missing,
              "R2: EVERY height of the persisted [savedTip-299 .. savedTip] run resolves to a "
              "stop hash (the whole run is resident, not just a connected pair)");
    }
    check(_BRPeerManagerBlockFloor(m) > SCAN,
          "GEOMETRY: R2 does NOT cure the deep-restore band -- SAVE_BLOCK_COUNT (300) is two "
          "orders below CF_CONVOY_WINDOW (10000), so the floor still sits far above the frontier");
    check(SAVED_TIP - SCAN == CF_CONVOY_WINDOW,
          "GEOMETRY: the convoy pins the header tip exactly CF_CONVOY_WINDOW above the scan frontier");

    // FilterHeaderStore restore (setPendingFilterChain, applied in startSync).
    BRCompactFilterChainFree(m->compactFilterChain);
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, CFH_NEXT, UINT256_ZERO);

    // enableAutoCompactFilterFetch(cf_birth_height) — SyncService.kt calls this
    // BEFORE restoreCfScanLedger, and cf_birth_height is never advanced with the
    // scan, so on every launch it is the original deep birth.
    BRPeerManagerEnableAutoCompactFilterFetch(m, BIRTH);
    check(m->autoFetchCFiltersStart == SAVED_TIP,
          "THE CLAMP (from production code, not hand-set): the deep birth is unresolvable, so "
          "EnableAutoCompactFilterFetch arms start at the SAVED TIP");
    check(m->autoFetchCFiltersThrough == SAVED_TIP - 1u, "THE CLAMP: the cursor is armed at savedTip-1");

    // restoreCfScanLedger — the frontier lands a full window BELOW the floor.
    check(BRCFScanLedgerParse(&m->cfLedger, blob, blobLen) == 1, "RESTORE: the persisted ledger parsed back in");
    free(blob);
    check(BRCFScanLedgerLowestNeededHeight(&m->cfLedger) == SCAN,
          "RESTORE: the scan frontier is back at S -- CF_CONVOY_WINDOW BELOW the block floor");

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x51; pa->port = 12051; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    // THE STRUCTURAL FACT the whole fix rests on, checked against production code:
    // no getcfilters covering the frontier can even be SENT, because its stop hash
    // is below the block floor. So no retry, no peer and no driver can ever change
    // this, and `attempts` can never advance -> the hole can never reach gaveUp ->
    // the B2 valve is structurally BLIND to it.
    check(_BRPeerManagerRequestCFiltersLocked(m, SCAN, SCAN + (MAX_CFILTERS_RESULTS - 1u), pa) == 0,
          "UNSERVABLE: a getcfilters at the restored frontier cannot be sent (stop hash below the floor)");

    // ---- THE FIX: the resume reconciliation --------------------------------
    int wlogBefore = g_wlogCount;
    BRPeerManagerSnapAutoFetchThroughToScanFrontier(m);

    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == RESUME_FLOOR,
          "SURFACED (RED on -DCONVOY_C1_UNFIXED): abandonedBelow covers the whole unscannable band "
          "[S .. F-1] -- the skip is now visible to CfAbandonmentStore, the banner and recovery");
    check(g_wlogCount == wlogBefore + 1,
          "SURFACED: exactly ONE ABANDONED warn-log fired (count>0 <=> advance <=> WARN)");
    check(strstr(g_wlogLast, "ABANDONED") != NULL, "SURFACED: the captured warn-log names the ABANDONED event");
    check(BRCFScanLedgerLowestNeededHeight(&m->cfLedger) == RESUME_FLOOR,
          "SURFACED: the frontier is now the block floor, so the convoy window re-opens and the "
          "descent can continue instead of wedging");
    check(m->autoFetchCFiltersStart == RESUME_FLOOR && m->autoFetchCFiltersThrough == RESUME_FLOOR - 1u,
          "RECONCILED: start/cursor sit exactly at the frontier (reqStart == LowestNeededHeight)");

    // ---- one real KeepAlive tick, then THE INVARIANT -----------------------
    blockRegReset(); everReqReset(); capLogReset();
    blockRegAdd(m->lastBlock);
    g_capCount = 0; g_capStart = 0;
    BRPeerManagerKeepAlive(m);

    check(g_capCount >= 1 && g_capStart == RESUME_FLOOR,
          "DRIVE: the forward fetch starts at the surfaced frontier, not one height above or below");

    // Serve what the drive actually requested. THIS is what moves scannedThrough:
    // RecordRequested only raises requestedThrough, and _cfLedgerAdvance runs on
    // MarkEvaluated — so the sail happens on the first response, not on the send.
    uint32_t served[128];
    int nServed = serveSome(m, 128, served);
    check(nServed >= 1, "DRIVE: the requested height was served (the response that runs _cfLedgerAdvance)");

    uint32_t st = BRCFScanLedgerScannedThrough(&m->cfLedger);
    uint32_t ab = BRCFScanLedgerAbandonedBelow(&m->cfLedger);
    // THE REQUIREMENT, stated PER HEIGHT rather than as a summary comparison:
    // every height the ledger now calls scanned, from the restored frontier up,
    // must be either (a) below the surfaced abandoned watermark, or (b) a height
    // this session actually evaluated. In the pre-fix shape scannedThrough lands
    // on the saved tip while abandonedBelow is still 0 and only ONE height was
    // ever served, so ~10,000 heights satisfy neither.
    uint32_t firstBad = 0;
    for (uint32_t h = SCAN; h <= st; h++) {
        if (h < ab) continue;                                            // surfaced as abandoned
        if (uint32InArr(served, (size_t)nServed, h)) continue;           // actually evaluated
        firstBad = h;
        break;
    }
    if (firstBad != 0) {
        printf("C1-KAT: first height marked scanned but neither evaluated nor surfaced = %u "
               "(scannedThrough=%u abandonedBelow=%u served=%d)\n", firstBad, st, ab, nServed);
    }
    check(firstBad == 0,
          "NO SILENT SKIP (THE INVARIANT, RED on -DCONVOY_C1_UNFIXED): no height is marked scanned "
          "without being scanned or being surfaced as abandoned");

    BRPeerManagerFree(m);

    // ========================================================================
    // THE VARIANT: an ordinary mid-flight kill leaves `outstanding` non-empty.
    // outstanding[0] correctly pins the frontier, but its stop hash is below the
    // resident window, so RequestCFilters returns 0, Pass C commits only on
    // sent>0, `attempts` never increments, RetireCapped never fires and the hole
    // can NEVER become gaveUp -- so the B2 valve can never see it. That is a
    // second, un-valved frontier pin: an INVISIBLE PERMANENT WEDGE.
    // ========================================================================
    printf("\n--- C-1 VARIANT: a restored outstanding hole below the floor must not pin invisibly ---\n");

    BRCFScanLedger midflight;
    BRCFScanLedgerInit(&midflight, BIRTH);
    BRCFScanLedgerRecordRequested(&midflight, BIRTH, SCAN - 1u, UINT128_ZERO, 0, 1700000000u);
    for (uint32_t h = BIRTH; h <= SCAN - 1u; h++) BRCFScanLedgerMarkEvaluated(&midflight, h);
    BRCFScanLedgerRecordRequested(&midflight, SCAN, SCAN + 499u, UINT128_ZERO, 0, 1700000000u);   // in flight at the kill
    check(BRCFScanLedgerOutstandingCount(&midflight) == 500,
          "variant/session 1: 500 heights were in flight when the process died");
    size_t vLen = BRCFScanLedgerSerialize(&midflight, NULL, 0);
    uint8_t *vBlob = (vLen > 0) ? malloc(vLen) : NULL;
    check(vBlob != NULL && BRCFScanLedgerSerialize(&midflight, vBlob, vLen) == vLen, "variant/session 1: serialized");
    BRCFScanLedgerFree(&midflight);
    if (! vBlob) return;

    BRPeerManager *v = rhBuildResumedManager(wallet, SAVED_TIP, SAVE_BLOCK_COUNT);
    check(v != NULL, "variant/session 2: manager built by the REAL BRPeerManagerNewEx");
    if (! v) { free(vBlob); return; }
    BRCompactFilterChainFree(v->compactFilterChain);
    v->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, CFH_NEXT, UINT256_ZERO);
    BRPeerManagerEnableAutoCompactFilterFetch(v, BIRTH);
    check(BRCFScanLedgerParse(&v->cfLedger, vBlob, vLen) == 1, "variant/RESTORE: the mid-flight ledger parsed back in");
    free(vBlob);

    BRPeer *pv = BRPeerNew(BRMainNetParams.magicNumber);
    pv->address.u8[15] = 0x52; pv->port = 12052; pv->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(v->connectedPeers, pv);

    check(v->cfLedger.outstandingCount == 500 && v->cfLedger.outstanding[0].height == SCAN,
          "variant: the restored hole at S pins the frontier");
    check(v->cfLedger.outstanding[0].attempts == 0, "variant: Parse resets attempts (a fresh cycle)");
    check(BRCFScanLedgerGaveUpCount(&v->cfLedger) == 0, "variant: nothing is in gaveUp -- the valve sees nothing");
    // The variant deliberately does NOT call the resume snap: it isolates the
    // KeepAlive backstop. So the forward-fetch cursor is wherever the
    // EnableAutoCompactFilterFetch clamp left it -- the SAVED TIP - 1, which under
    // R2's lower floor sits SAVE_BLOCK_COUNT-1 heights ABOVE the surfaced frontier.
    check(v->autoFetchCFiltersThrough == SAVED_TIP - 1u,
          "variant setup: the cursor is armed at the clamped saved tip, ABOVE the floor R2 produces "
          "-- so the backstop's own cursor reconciliation is what has to close the gap");

    blockRegReset(); everReqReset(); capLogReset();
    blockRegAdd(v->lastBlock);
    g_capCount = 0; g_capStart = 0;

    // Ten residual drive ticks with the clock advanced past every backoff step.
    // The hole is offered and offered and NEVER burns an attempt, because nothing
    // can go on the wire -- so it can never reach gaveUp and the valve stays blind.
    for (int t = 0; t < 10; t++) {
        v->cfLedger.lastDriveAt = 0;
        for (size_t i = 0; i < v->cfLedger.outstandingCount; i++) v->cfLedger.outstanding[i].requestedAt = 1;
        BRPeerManagerKeepAlive(v);
    }
    check(BRCFScanLedgerLowestNeededHeight(&v->cfLedger) == RESUME_FLOOR,
          "VARIANT FIXED (RED on -DCONVOY_C1_UNFIXED): the un-servable hole was surfaced instead of "
          "pinning the frontier forever where no valve could ever see it");
    check(BRCFScanLedgerAbandonedBelow(&v->cfLedger) == RESUME_FLOOR,
          "VARIANT FIXED: the band is surfaced (recoverable), not silently dropped");
    check(findOutstanding(&v->cfLedger, SCAN) == NULL,
          "VARIANT FIXED: the unservable hole no longer sits in `outstanding` pinning _cfLedgerAdvance");

    // THE SAME INVARIANT AS THE MAIN CASE, on the backstop path: every height the
    // ledger now calls scanned must be either below the surfaced watermark or a
    // height this session actually put on the wire. Without the backstop's cursor
    // reconciliation the forward fetch starts at the clamped saved tip, RecordRequested
    // raises requestedThrough NON-CONTIGUOUSLY over [floor .. savedTip-1] and
    // _cfLedgerAdvance sails scannedThrough across all 299 of them -- above
    // abandonedBelow, never requested, invisible. (This is a gap R2 OPENS: pre-R2 the
    // floor and the clamped cursor coincided, so it was zero by coincidence.)
    {
        uint32_t vst = BRCFScanLedgerScannedThrough(&v->cfLedger);
        uint32_t vab = BRCFScanLedgerAbandonedBelow(&v->cfLedger);
        uint32_t vBad = 0;
        for (uint32_t h = vab; h <= vst; h++) {
            if (! everReqContains(h)) { vBad = h; break; }
        }
        if (vBad != 0) {
            printf("C1-VARIANT-KAT: first height marked scanned but never requested = %u "
                   "(scannedThrough=%u abandonedBelow=%u)\n", vBad, vst, vab);
        }
        check(vBad == 0,
              "VARIANT: NO SILENT SKIP -- every height above the surfaced watermark that the ledger "
              "calls scanned was actually requested on the wire (the backstop reconciles the cursor "
              "to the frontier, so requestedThrough stays CONTIGUOUS)");
    }

    BRPeerManagerFree(v);
}

// ============================================================================
// FIX WAVE R2: an ordinary abrupt kill of a HEALTHY wallet must surface NOTHING
// ============================================================================
//
// This is the OUTCOME R2 exists for, asserted as the outcome rather than as the
// mechanism. Saved blocks are persisted on EVERY save callback; the CF scan ledger
// is on a 20-s coalescing timer. So on an abrupt kill (~half of them land in the
// unflushed window) the restored ledger trails the restored block tip by 1-2
// heights. With the resume floor at the saved TIP those 1-2 heights sat BELOW
// everything the session could resolve, so the C-1 surfacing -- correctly, given
// that floor -- raised abandonedBelow over them: a non-dismissible "history gap"
// banner with Synced WITHHELD, on a fully-synced healthy wallet, over heights that
// WERE actually scanned and whose only loss was the record. A new high-frequency
// FALSE ALARM on the ordinary user population, eroding the banner's signal value
// exactly where the B2 valve's safety argument depends on users heeding it.
//
// With the floor at savedTip-(SAVE_BLOCK_COUNT-1) those heights are inside the
// resident run, so they are simply RE-SCANNED and nothing is surfaced at all.
//
// Deliberately NOT asserted as "the floor is lower" (that is the mechanism, and
// test_resume_below_block_floor_surfaces pins it): asserted as abandonedBelow == 0,
// zero ABANDONED warn-logs, and the trailing heights actually going back on the wire.
static void test_resume_healthy_kill_surfaces_no_band(BRWallet *wallet)
{
    printf("\n=== test_resume_healthy_kill_surfaces_no_band (fix wave R2) ===\n");

    const uint32_t SAVED_TIP = 20010000u;                 // a healthy, fully-synced wallet
    const uint32_t BIRTH     = SAVED_TIP - 3000u;         // the deep cf_birth_height, never advanced
    const uint32_t LEDGER_AT = SAVED_TIP - 2u;            // the 20-s coalesced write trails the block tip by 2
    const uint32_t CFH_NEXT  = SAVED_TIP + 1u;            // cfheaders are caught up (healthy wallet)

    // ---- session 1: fully scanned through LEDGER_AT, then killed ------------
    BRCFScanLedger persisted;
    BRCFScanLedgerInit(&persisted, BIRTH);
    BRCFScanLedgerRecordRequested(&persisted, BIRTH, LEDGER_AT, UINT128_ZERO, 0, 1700000000u);
    for (uint32_t h = BIRTH; h <= LEDGER_AT; h++) BRCFScanLedgerMarkEvaluated(&persisted, h);
    check(BRCFScanLedgerScannedThrough(&persisted) == LEDGER_AT &&
          BRCFScanLedgerOutstandingCount(&persisted) == 0,
          "session 1: healthy wallet, ledger drained to LEDGER_AT (2 heights behind the saved tip)");
    size_t blobLen = BRCFScanLedgerSerialize(&persisted, NULL, 0);
    uint8_t *blob  = (blobLen > 0) ? malloc(blobLen) : NULL;
    check(blob != NULL && BRCFScanLedgerSerialize(&persisted, blob, blobLen) == blobLen,
          "session 1: ledger serialized");
    BRCFScanLedgerFree(&persisted);
    if (! blob) return;

    // ---- session 2, PRODUCTION SHAPE ---------------------------------------
    BRPeerManager *m = rhBuildResumedManager(wallet, SAVED_TIP, SAVE_BLOCK_COUNT);
    check(m != NULL, "session 2: manager built by the REAL BRPeerManagerNewEx from saved_blocks");
    if (! m) { free(blob); return; }
    BRCompactFilterChainFree(m->compactFilterChain);
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, CFH_NEXT, UINT256_ZERO);
    BRPeerManagerEnableAutoCompactFilterFetch(m, BIRTH);
    check(BRCFScanLedgerParse(&m->cfLedger, blob, blobLen) == 1, "RESTORE: the persisted ledger parsed back in");
    free(blob);
    check(BRCFScanLedgerLowestNeededHeight(&m->cfLedger) == LEDGER_AT + 1u,
          "RESTORE: the scan frontier is 1 height below the saved tip -- the coalescing-window trail");

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x53; pa->port = 12053; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    // ---- the resume reconciliation + one real KeepAlive tick ----------------
    int wlogBefore = g_wlogCount;
    BRPeerManagerSnapAutoFetchThroughToScanFrontier(m);

    blockRegReset(); everReqReset(); capLogReset();
    blockRegAdd(m->lastBlock);   // the batch stop hash caps at the cfheader frontier == the saved tip
    g_capCount = 0; g_capStart = 0;
    BRPeerManagerKeepAlive(m);

    // THE OUTCOME. Both halves matter: nothing surfaced AND nothing warned. cnt>0
    // <=> abandonedBelow advanced <=> exactly one WARN, so a zero WARN delta is an
    // independent witness that no surfacing happened by any of the three paths
    // (snap, KeepAlive backstop, floor re-anchor).
    check(BRCFScanLedgerAbandonedBelow(&m->cfLedger) == 0,
          "NO FALSE BAND (RED on -DRESUME_FLOOR_UNFIXED): an ordinary abrupt kill of a HEALTHY, "
          "fully-synced wallet surfaces NOTHING -- the 2 heights the coalesced ledger trailed by "
          "are inside the resident saved run, so they are simply re-scanned");
    check(g_wlogCount == wlogBefore,
          "NO FALSE BAND: zero ABANDONED warn-logs -- no banner, and Synced is not withheld");

    // ... and they really do go back on the wire, so "not surfaced" is not "lost".
    check(g_capCount >= 1 && g_capStart == LEDGER_AT + 1u,
          "RE-SCANNED (RED on -DRESUME_FLOOR_UNFIXED): the forward fetch resumes at the trailing "
          "height itself -- its stop hash resolves inside the resident run");

    uint32_t served[8];
    int nServed = serveSome(m, 8, served);
    check(nServed >= 1, "RE-SCANNED: the trailing heights were served (a real _cfLedgerAdvance)");
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) >= LEDGER_AT + 1u,
          "RE-SCANNED: the scan actually advanced past the trailing height");

    BRPeerManagerFree(m);
}

// ============================================================================
// Paced-convoy fetch, Task 6: BRPeerManagerHasPendingAbandonment (spec Part C)
// ============================================================================
//
// The ordering signal between the B2 valve and the Kotlin tip-stall watchdog. The
// two scan-stall watchers must not race: while the valve is mid-decision on the
// hole that PINS the scan frontier, its re-arm IS productive work and the
// watchdog's escalation (ungated getheaders, then a manager recreate) is churn.
//
// It returns a COUNT (rearmCycles + 1), not a boolean, and that is the whole point:
// on a churny fleet every deciding cycle can be tainted, so the valve can re-arm
// INDEFINITELY -- a consumer suppressing on a bare boolean would stand down forever,
// exactly when the backstop is needed. The count lets the consumer BOUND its
// suppression. Both properties are pinned below.
static void test_pending_abandonment_accessor(BRWallet *wallet)
{
    printf("\n=== test_pending_abandonment_accessor (paced-convoy Task 6: the watchdog ordering signal) ===\n");

    const uint32_t BASE = 700000u;
    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    check(m != NULL, "manager created");
    if (! m) return;
    m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;

    BRCFScanLedgerInit(&m->cfLedger, BASE);
    check(BRPeerManagerHasPendingAbandonment(m) == 0,
          "no gaveUp hole at all -> 0 (nothing pending; the watchdog is NOT suppressed)");

    // A hole that is merely OUTSTANDING (still being retried on its original cycle)
    // is not the valve's business either -- the residual driver owns it.
    BRCFScanLedgerRecordRequested(&m->cfLedger, BASE, BASE + 9u, UINT128_ZERO, 0, 1700000000u);
    check(BRPeerManagerHasPendingAbandonment(m) == 0,
          "an OUTSTANDING (not yet retry-exhausted) hole -> 0: the valve is not deciding anything yet");

    // Retire it to gaveUp the production way: attempts at the cap, then RetireCapped.
    BRCFOutstanding *e = mutOutstanding(&m->cfLedger, BASE);
    check(e != NULL, "setup: the hole is outstanding before retirement");
    if (e) { e->attempts = CF_REREQ_MAX_ATTEMPTS; }
    BRCFScanLedgerRetireCapped(&m->cfLedger);
    check(gaveUpContains(&m->cfLedger, BASE), "setup: the hole was retired to gaveUp (RetireCapped)");

    check(BRPeerManagerHasPendingAbandonment(m) == 1,
          "PENDING, ORIGINAL CYCLE: a gaveUp hole pinning the scan frontier -> 1 (== rearmCycles 0 + 1)");

    // One re-arm cycle granted (what the valve does on a tainted/early cycle). The
    // hole is OUTSTANDING again for ~7.5 min of rotated retry -- the larger half of
    // the valve's window, and still the valve's work, so it must still read pending.
    check(BRCFScanLedgerReArmGaveUp(&m->cfLedger, BASE) == 1, "setup: the valve re-armed the hole once");
    check(gaveUpContains(&m->cfLedger, BASE) == 0, "setup: a re-armed hole has exactly one home (out of gaveUp)");
    check(BRPeerManagerHasPendingAbandonment(m) == 2,
          "RE-ARM CYCLE IN FLIGHT: an OUTSTANDING hole with rearmCycles>0 still reads pending (2) -- spec "
          "Part C defers the watchdog while a re-arm is pending, not only at the decision instant");

    // ...and back to gaveUp with the cycle count carried across the round trip.
    e = mutOutstanding(&m->cfLedger, BASE);
    if (e) { e->attempts = CF_REREQ_MAX_ATTEMPTS; }
    BRCFScanLedgerRetireCapped(&m->cfLedger);
    check(BRPeerManagerHasPendingAbandonment(m) == 2,
          "BOUNDABLE: after one re-arm the signal reads 2 (rearmCycles 1 + 1) -- a CONSUMER CAN BOUND ITS "
          "SUPPRESSION ON THIS, which a bare boolean could not (an indefinitely re-arming hole on a churny "
          "fleet would otherwise suppress the watchdog forever)");

    // The count keeps CLIMBING past CF_CONVOY_REARM_MAX+1 when the valve cannot
    // converge (every deciding cycle tainted by peer churn) -- which is precisely
    // the signal Task 8 must bound its suppression on. If this saturated at the
    // valve's own threshold there would be nothing to bound against.
    check(BRCFScanLedgerReArmGaveUp(&m->cfLedger, BASE) == 1, "setup: a second re-arm cycle granted");
    e = mutOutstanding(&m->cfLedger, BASE);
    if (e) { e->attempts = CF_REREQ_MAX_ATTEMPTS; }
    BRCFScanLedgerRetireCapped(&m->cfLedger);
    check(BRPeerManagerHasPendingAbandonment(m) == CF_CONVOY_REARM_MAX + 1,
          "the signal reaches CF_CONVOY_REARM_MAX+1 == the cycle at which the valve becomes ABANDON-ELIGIBLE "
          "(the exact ceiling a consumer bounds its suppression at)");
    check(BRCFScanLedgerReArmGaveUp(&m->cfLedger, BASE) == 1, "setup: a THIRD re-arm (the non-converging fleet)");
    e = mutOutstanding(&m->cfLedger, BASE);
    if (e) { e->attempts = CF_REREQ_MAX_ATTEMPTS; }
    BRCFScanLedgerRetireCapped(&m->cfLedger);
    check(BRPeerManagerHasPendingAbandonment(m) > (uint32_t)(CF_CONVOY_REARM_MAX + 1),
          "NON-CONVERGING VALVE IS VISIBLE: the count climbs PAST the abandon-eligible cycle, so a consumer "
          "can tell 'the valve is working' from 'the valve is looping' and stop suppressing (the Task-5 "
          "review's permanent-stand-down failure)");

    // A gaveUp height ABOVE a still-outstanding hole does NOT pin the frontier and is
    // not the valve's business -- same predicate the valve itself uses.
    BRCFScanLedgerRecordRequested(&m->cfLedger, BASE - 5u, BASE - 5u, UINT128_ZERO, 0, 1700000000u);
    check(m->cfLedger.outstandingCount > 0 && m->cfLedger.outstanding[0].height < BASE,
          "setup: a LOWER still-outstanding hole now sits below the gaveUp one");
    check(BRPeerManagerHasPendingAbandonment(m) == 0,
          "NOT PINNING: a gaveUp height above a still-outstanding hole -> 0 (byte-identical to the valve's "
          "own pinning predicate, so the watchdog can never defer to a decision that is not happening)");

    BRPeerManagerFree(m);
}

// ============================================================================
// Paced-convoy fetch, Task 6: THE MEMORY-BOUND PROOF (spec Part C + Part F #4)
// ============================================================================
//
// This is the headline claim of the whole feature: a genuinely DEEP restore no
// longer grows manager->blocks without bound. Everything else in this branch --
// the gate, the KeepAlive driver, the resume snap, the abandonment valve -- exists
// to make THIS property true, and none of the other cases can observe it, because
// they all run on 300..6000-block chains where an unpaced fast-forward is
// indistinguishable from a paced one.
//
// WHAT IS DRIVEN FOR REAL (not modeled):
//   * the convoy window predicates (_cfConvoyHdrGated / _cfConvoyCfhGated) through
//     the production CF_CONVOY_*_GATED macros -- so -DCONVOY_UNGATED reds this case;
//   * _BRPeerManagerClearMemory -- the real retention floor + descent;
//   * BRPeerManagerKeepAlive -- the real B1.1 forward cfilter drive (which resolves
//     its own stop hash by the real tip-down prevBlock walk), B1.2 cfheaders
//     re-kick, B1.3 getheaders re-kick, residual driver and B2 valve;
//   * BRCFScanLedger* -- the real ledger, RecordRequested/MarkEvaluated/advance;
//   * BRCompactFilterChainAppend -- real filter-header chaining.
//
// WHAT IS MODELED (the network, i.e. everything outside the manager):
//   * the PEER's CF-only 2000-header continuation. BRPeer.c:648 is file-static to a
//     separate compilation unit, so the header supply is issued here instead --
//     but it is issued THROUGH the production gate verdict, and in 2-batch groups,
//     because the peer reads a pushed volatile flag that is one batch stale
//     (spec Part A, "bounded overshoot"). That makes the modeled supply the
//     PESSIMISTIC shape, not a convenient one.
//   * the cfheaders RESPONSE: appended only for a getcfheaders the manager
//     actually put on the wire, for exactly the [start..batchEnd] it asked for.
//     A suppressed request advances nothing -- the causality spine.
//   * the cfilter RESPONSES: MarkEvaluated only for heights that are OUTSTANDING,
//     and a height only becomes outstanding because _BRPeerManagerRequestCFiltersLocked
//     returned a real send (it returns 0 with no CF peer or an unresolvable stop
//     hash) and B1.1 then recorded the range. Same causality, without the
//     REG_MAX(8192)-bounded everRequested registry, which cannot span 100k+ heights.

#define SCALE_BIRTH      23800000u   // deep birth floor; above the highest mainnet checkpoint
#define SCALE_CHAIN_LEN  105000u     // > 100k: a genuinely deep restore, not a scaled-down proxy
#define SCALE_TIP        (SCALE_BIRTH + SCALE_CHAIN_LEN - 1u)
#define SCALE_HDR_BATCH  2000u       // wire max headers per `headers` message (BRPeer.c's continuation quantum)
#define SCALE_MAX_TICKS  400         // >> the ~105 ticks the descent needs at MAX_CFILTERS_RESULTS/tick

// Append `count` filter headers to the manager's chain -- the cfheaders RESPONSE.
// Content is irrelevant to this case (Append just dSHA256-chains them); what
// matters is that NextHeight advances by exactly the batch the manager asked for.
static int scaleAppendCfHeaders(BRPeerManager *m, uint32_t count)
{
    static UInt256 fh[MAX_CFHEADERS_RESULTS];
    if (count == 0 || count > MAX_CFHEADERS_RESULTS || ! m->compactFilterChain) return 0;
    uint32_t next = BRCompactFilterChainNextHeight(m->compactFilterChain);
    for (uint32_t i = 0; i < count; i++) fh[i] = rhUniqueHash(next + i);
    return BRCompactFilterChainAppend(m->compactFilterChain,
                                      BRCompactFilterChainTipHeader(m->compactFilterChain), fh, count);
}

// One modeled header batch: append up to SCALE_HDR_BATCH prevBlock-linked,
// distinct-per-height blocks (rhChainBlock -- NEVER dummyBlock, whose uint8_t hash
// seed collides after 256 blocks and would silently cap this "105k-block" chain at
// ~256 entries, making the whole bound assertion vacuous). Returns the count added.
//
// Production calls _BRPeerManagerClearMemory once per RELAYED BLOCK
// (_peerRelayedBlock:1750); calling it once per BATCH here is EQUIVALENT for the
// resident-set bound -- the pruner only ever frees blocks strictly BELOW cfFloor,
// and every block a batch adds is far ABOVE it, so no intra-batch call could free
// anything the batch-end call does not -- and ~2000x cheaper.
static uint32_t scaleSupplyHeaderBatch(BRPeerManager *m, uint32_t chainTip)
{
    if (! m->lastBlock || m->lastBlock->height >= chainTip) return 0;
    uint32_t from = m->lastBlock->height + 1u;
    uint32_t to   = from + (SCALE_HDR_BATCH - 1u);
    if (to > chainTip) to = chainTip;
    for (uint32_t h = from; h <= to; h++) {
        BRMerkleBlock *b = rhChainBlock(h);
        BRSetAdd(m->blocks, b);
        m->lastBlock = b;
    }
    _BRPeerManagerClearMemory(m);
    return to - from + 1u;
}

// Model up to `n` cfilter responses: MarkEvaluated the LOWEST outstanding heights
// (outstanding[] is sorted ascending), so the scan frontier climbs contiguously.
static int scaleServe(BRPeerManager *m, int n)
{
    int k = 0;
    while (k < n && m->cfLedger.outstandingCount > 0) {
        BRCFScanLedgerMarkEvaluated(&m->cfLedger, m->cfLedger.outstanding[0].height);
        k++;
    }
    return k;
}

// The stop-hash resolver property, asserted at a live frontier position.
//
// Ground truth is rhUniqueHash(h) -- the hash the chain BUILDER used -- so the
// expectations are derived from the chain, never from what the resolver returns.
// Three requirement-level rules, checked over a spread of heights:
//   (1) every height the retention floor GUARANTEES is resident -- i.e. at/above
//       cfFloor, at/below the header frontier, AND at/above the restore's own
//       anchor height (nothing below the anchor was ever part of the chain, so a
//       fresh restore legitimately has no header there) -- must resolve to its
//       true main-chain hash. This is what makes the bounded window usable: a
//       short walk that cannot resolve the scan frontier would be a wedge, not a win.
//   (2) every height ABOVE the header frontier must resolve to UINT256_ZERO --
//       cleanly absent, never garbage read off the end of a pruned chain.
//   (3) heights BELOW the retention floor MAY have been pruned; if the resolver
//       still returns something it must be the true hash, never garbage.
// And the BATCHED resolver must be byte-identical to the naive walk everywhere
// (it is what every getcfilters/getcfheaders stop hash goes through).
// Returns 1 on success; writes a reason on failure.
static int scaleResolverOk(BRPeerManager *m, uint32_t chainBase, char *why, size_t whyLen)
{
    uint32_t scanFrontier = BRCFScanLedgerLowestNeededHeight(&m->cfLedger);
    uint32_t cfNext       = m->compactFilterChain ? BRCompactFilterChainNextHeight(m->compactFilterChain) : 0;
    uint32_t floorH       = (cfNext && cfNext < scanFrontier) ? cfNext : scanFrontier;
    uint32_t cfFloor      = (floorH > CLEAR_MEM_CF_RETENTION_MARGIN)
                            ? floorH - CLEAR_MEM_CF_RETENTION_MARGIN : 1u;
    if (cfFloor < chainBase) cfFloor = chainBase;   // nothing below the restore anchor was ever built
    uint32_t hdrFrontier  = m->lastBlock ? m->lastBlock->height : 0;

    uint32_t hs[24];
    size_t n = 0;
    hs[n++] = cfFloor;                        // the retained window's guaranteed low edge
    hs[n++] = cfFloor + 1u;
    hs[n++] = scanFrontier;                   // the scan frontier itself
    hs[n++] = scanFrontier + 1u;
    hs[n++] = scanFrontier + MAX_CFILTERS_RESULTS - 1u;   // the forward fetch's stop position
    hs[n++] = hdrFrontier;                    // the header frontier (lastBlock)
    hs[n++] = hdrFrontier - 1u;
    hs[n++] = hdrFrontier + 1u;               // above the frontier -> ZERO
    hs[n++] = hdrFrontier + 5000u;            // far above -> ZERO
    hs[n++] = SCALE_TIP + 1u;                 // above the whole chain -> ZERO
    hs[n++] = SCALE_BIRTH;                    // the birth floor (pruned once the scan climbs past)
    hs[n++] = SCALE_BIRTH - 1u;               // the pre-birth anchor
    for (int i = 1; i <= 10; i++)             // a spread across the middle of the retained window
        hs[n++] = cfFloor + (uint32_t)i * ((hdrFrontier > cfFloor) ? (hdrFrontier - cfFloor) / 11u : 1u);

    UInt256 naive[24], batch[24];
    for (size_t i = 0; i < n; i++) naive[i] = _BRPeerManagerBlockHashAtHeight(m, hs[i]);
    for (size_t i = 0; i < n; i++) batch[i] = rhForkHash(0xFFFFFFFFu);   // poison: catch an unwritten slot
    _BRPeerManagerResolveHashesAtHeightsLocked(m, hs, n, batch);

    for (size_t i = 0; i < n; i++) {
        if (! UInt256Eq(batch[i], naive[i])) {
            snprintf(why, whyLen, "batch != naive at height %u", hs[i]);
            return 0;
        }
        if (hs[i] >= cfFloor && hs[i] <= hdrFrontier) {
            if (! UInt256Eq(batch[i], rhUniqueHash(hs[i]))) {
                snprintf(why, whyLen, "retained height %u did NOT resolve to its true chain hash "
                         "(cfFloor %u, hdrFrontier %u)", hs[i], cfFloor, hdrFrontier);
                return 0;
            }
        }
        else if (hs[i] > hdrFrontier) {
            if (! UInt256Eq(batch[i], UINT256_ZERO)) {
                snprintf(why, whyLen, "height %u above the header frontier %u resolved to a NON-ZERO hash",
                         hs[i], hdrFrontier);
                return 0;
            }
        }
        else {   // below the retention floor: may be pruned, but never garbage
            if (! UInt256Eq(batch[i], UINT256_ZERO) && ! UInt256Eq(batch[i], rhUniqueHash(hs[i]))) {
                snprintf(why, whyLen, "sub-floor height %u resolved to neither ZERO nor its true hash", hs[i]);
                return 0;
            }
        }
    }
    return 1;
}

static void test_convoy_scale_bounded(BRWallet *wallet)
{
    printf("\n=== test_convoy_scale_bounded (paced-convoy Task 6: THE memory bound, >100k-block descent) ===\n");

    BRPeerManager *m = BRPeerManagerNew(&BRMainNetParams, wallet, 0, NULL, 0, NULL, 0);
    check(m != NULL, "manager created");
    if (! m) return;
    m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;

    // The params' checkpoint headers are pre-seeded into manager->blocks and are NOT
    // on the prevBlock walk from our synthetic chain, so the pruner's descent never
    // reaches them and they are resident for the whole run. Counted into the bound.
    const size_t baseCount = BRSetCount(m->blocks);

    // The header the restore starts from: one below birth, so the chain the convoy
    // builds is exactly [SCALE_BIRTH .. SCALE_TIP].
    BRMerkleBlock *anchor = rhChainBlock(SCALE_BIRTH - 1u);
    BRSetAdd(m->blocks, anchor);
    m->lastBlock = anchor;
    m->estimatedHeight = SCALE_TIP;

    // Arm the CF scan at the deep birth floor -- the fresh-deep-restore shape.
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, SCALE_BIRTH, UINT256_ZERO);
    m->autoFetchCFiltersEnabled = 1;
    m->autoFetchCFiltersStart   = SCALE_BIRTH;
    m->autoFetchCFiltersThrough  = SCALE_BIRTH - 1u;
    BRCFScanLedgerInit(&m->cfLedger, SCALE_BIRTH);

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x51; pa->port = 12051; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);

    // The blockReg/everRequested spine is deliberately left EMPTY: it is
    // REG_MAX(8192)-bounded with O(n) lookups and cannot span 105k heights. This
    // case gets its causality from `outstanding` instead (see the header comment)
    // and its stop-hash truth from g_capStopHash/g_cfhStopHash directly.
    blockRegReset(); everReqReset(); capLogReset();
    g_capCount = 0; g_cfhCount = 0;

    check(SCALE_CHAIN_LEN > 100000u, "setup: this is a genuinely DEEP restore (>100k blocks below the tip)");
    check(_cfConvoyScanArmed(m) == 1, "setup: the convoy is ARMED (CF-only syncMode + auto-fetch enabled)");
    check(BRCFScanLedgerLowestNeededHeight(&m->cfLedger) == SCALE_BIRTH,
          "setup: the scan frontier starts at the deep birth floor");
    check(_cfConvoyHdrGated(m) == 0 && _cfConvoyCfhGated(m) == 0,
          "setup: both convoy windows start OPEN (nothing suppressed before the descent begins)");

    // ---- THE BOUND, derived from the requirement (spec Part C), not measured ----
    //   resident heights  = [cfFloor .. blockHeaderFrontier]
    //   cfFloor           = scanFrontier - CLEAR_MEM_CF_RETENTION_MARGIN   (Tasks 1-4 floor)
    //   blockHeaderFrontier <= scanFrontier + (CF_CONVOY_WINDOW - 1) + 2*SCALE_HDR_BATCH
    //         (the gate is only ever evaluated OPEN at W_hdr <= W-1, and the peer reads
    //          the pushed verdict one batch stale, so at most two more batches land)
    //   => count <= CF_CONVOY_WINDOW + 2*SCALE_HDR_BATCH + CLEAR_MEM_CF_RETENTION_MARGIN
    //               + the never-pruned checkpoint headers.
    const size_t BOUND = (size_t)CF_CONVOY_WINDOW + 2u * SCALE_HDR_BATCH
                       + CLEAR_MEM_CF_RETENTION_MARGIN + baseCount;
    check(BOUND * 4u < (size_t)SCALE_CHAIN_LEN,
          "the bound is a REAL bound: < 1/4 of the chain length, so an unbounded build cannot slip under it");

    size_t   peak = 0;
    int      boundViolations = 0, resolverFails = 0, stopHashFails = 0;
    int      scanRegressions = 0, contiguityBreaks = 0, cfhStartBreaks = 0;
    uint32_t prevScan = BRCFScanLedgerLowestNeededHeight(&m->cfLedger);
    uint32_t peakHdrLead = 0;
    int      tick = 0, checkpoints = 0, ticksWithFetch = 0;
    char     lbl[224], why[192];
    why[0] = '\0';

    for (tick = 0; tick < SCALE_MAX_TICKS &&
                   BRCFScanLedgerScannedThrough(&m->cfLedger) < SCALE_TIP; tick++) {

        // ---- (1) HEADER SUPPLY: the peer keeps its 2000-header continuation
        // running until the manager's pushed verdict says the window is full.
        // Two batches per verdict == the documented bounded overshoot.
        while (! CF_CONVOY_HDR_GATED(m) && m->lastBlock->height < SCALE_TIP) {
            for (int b = 0; b < 2; b++) if (scaleSupplyHeaderBatch(m, SCALE_TIP) == 0) break;
            size_t c = BRSetCount(m->blocks);
            if (c > peak) peak = c;
            if (c > BOUND) boundViolations++;
        }
        {   // observed window lead, for the report line
            uint32_t sf = BRCFScanLedgerLowestNeededHeight(&m->cfLedger);
            uint32_t hf = m->lastBlock->height;
            if (hf > sf && hf - sf > peakHdrLead) peakHdrLead = hf - sf;
        }

        // ---- (2) the PRODUCTION convoy driver ----
        uint32_t cfhNextBefore = BRCompactFilterChainNextHeight(m->compactFilterChain);
        uint32_t throughBefore = m->autoFetchCFiltersThrough;
        int      capBefore = g_capCount, cfhBefore = g_cfhCount;

        BRPeerManagerKeepAlive(m);

        // ---- (3) the forward getcfilters that KeepAlive actually sent ----
        if (g_capCount > capBefore) {
            ticksWithFetch++;
            uint32_t reqStop = m->autoFetchCFiltersThrough;   // B1.1 advances this to reqStop on a real send
            // The stop hash MUST be the hash of the block at the stop height: this is
            // the tip-down prevBlock walk resolving correctly from a WINDOW, at a
            // frontier ~CF_CONVOY_WINDOW below lastBlock.
            if (! UInt256Eq(g_capStopHash, rhUniqueHash(reqStop))) stopHashFails++;
            // CONTIGUITY: the fetch resumes exactly where the last one stopped. A gap
            // would be a silently unscanned height; an overlap would be re-work.
            if (g_capStart != throughBefore + 1u) contiguityBreaks++;
            if (reqStop < g_capStart) contiguityBreaks++;
        }

        // ---- (4) the cfheaders RESPONSE, only for a request that really went out ----
        if (g_cfhCount > cfhBefore) {
            uint32_t reqEnd = m->cfHeadersRequestedThrough;   // batchEnd, set on a real send
            if (g_cfhStart != cfhNextBefore) cfhStartBreaks++;
            if (! UInt256Eq(g_cfhStopHash, rhUniqueHash(reqEnd))) stopHashFails++;
            if (reqEnd >= g_cfhStart) scaleAppendCfHeaders(m, reqEnd - g_cfhStart + 1u);
            m->cfHeadersRequestedThrough = 0;   // the response landed (_peerRelayedCFHeaders:2657)
        }

        // ---- (5) the cfilter responses ----
        scaleServe(m, MAX_CFILTERS_RESULTS);

        // ---- (6) the property, EVERY tick ----
        size_t nowCount = BRSetCount(m->blocks);
        if (nowCount > peak) peak = nowCount;
        if (nowCount > BOUND) boundViolations++;
        uint32_t scanNow = BRCFScanLedgerLowestNeededHeight(&m->cfLedger);
        if (scanNow < prevScan) scanRegressions++;
        prevScan = scanNow;

        // ---- (7) the CHECKPOINT schedule: an emitted assertion at tick
        // 0/1/2/5 (the ramp, where the window first fills) and every 10th tick
        // thereafter. The per-tick accumulators above cover the ticks in between,
        // so nothing is unchecked -- this only bounds the output volume.
        if (tick <= 2 || tick == 5 || (tick % 10) == 0) {
            checkpoints++;
            snprintf(lbl, sizeof lbl,
                     "tick %d: BRSetCount %zu <= bound %zu (scan %u, hdr %u, lead %u, chain %u)",
                     tick, nowCount, BOUND, scanNow, m->lastBlock->height,
                     m->lastBlock->height > scanNow ? m->lastBlock->height - scanNow : 0, SCALE_CHAIN_LEN);
            check(nowCount <= BOUND, lbl);

            if (! scaleResolverOk(m, SCALE_BIRTH - 1u, why, sizeof why)) {
                resolverFails++;
                snprintf(lbl, sizeof lbl, "tick %d: stop-hash resolver correct at this frontier -- %s", tick, why);
                check(0, lbl);
            }
        }
    }

    // ---- the acceptance property ----
    check(boundViolations == 0,
          "MEMORY BOUND HELD AT EVERY TICK: BRSetCount(manager->blocks) never exceeded "
          "CF_CONVOY_WINDOW + overshoot + margin across the WHOLE >100k descent "
          "(RED on -DCONVOY_UNGATED: the headers fast-forward to the tip and it grows to chain length)");
    snprintf(lbl, sizeof lbl, "peak BRSetCount %zu <= bound %zu (chain is %u blocks -- %.1f%% resident)",
             peak, BOUND, SCALE_CHAIN_LEN, 100.0 * (double)peak / (double)SCALE_CHAIN_LEN);
    check(peak <= BOUND, lbl);

    // ...and NOT vacuous: the convoy actually climbed the whole chain.
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) == SCALE_TIP,
          "NOT VACUOUS: the SCAN reached the chain tip -- every one of the >100k heights was scanned, "
          "so the bound was held by PACING, not by the descent stalling");
    check(m->lastBlock && m->lastBlock->height == SCALE_TIP,
          "NOT VACUOUS: the block-header frontier also reached the tip (the convoy converges)");
    check(tick < SCALE_MAX_TICKS, "the descent converged inside the tick budget");
    check(ticksWithFetch > 50, "NOT VACUOUS: the forward cfilter fetch fired on most ticks (real work happened)");
    check(scanRegressions == 0, "the scan frontier never regressed across the descent");
    check(contiguityBreaks == 0,
          "every forward getcfilters resumed exactly at the previous stop + 1 -- no gap (silently unscanned "
          "height) and no overlap");
    check(cfhStartBreaks == 0, "every getcfheaders started at the chain's real NextHeight");
    check(stopHashFails == 0,
          "STOP-HASH RESOLVER: every getcfilters/getcfheaders stop hash was the true chain hash at that "
          "height, at every frontier position across the descent");
    check(resolverFails == 0,
          "BATCHED RESOLVER: byte-identical to the naive walk, correct for every retained height and clean "
          "ZERO above the frontier, at every checkpoint");
    check(checkpoints >= 10, "the checkpoint schedule actually ran (>=10 emitted checkpoints)");

    // The memory was really RETURNED, not merely counted: the birth-height header
    // is gone from the set once the scan climbed past it.
    check(! rhBlockPresent(m, SCALE_BIRTH),
          "MEMORY ACTUALLY FREED: the birth-height header was released once the scan climbed past it");
    check(rhBlockPresent(m, SCALE_TIP), "the tip header is resident at the end of the descent");

    printf("   [scale] chain %u blocks | ticks %d | peak BRSetCount %zu / bound %zu | peak header lead %u "
           "(W=%d) | %.2f%% of chain resident\n",
           SCALE_CHAIN_LEN, tick, peak, BOUND, peakHdrLead, CF_CONVOY_WINDOW,
           100.0 * (double)peak / (double)SCALE_CHAIN_LEN);

    BRPeerManagerFree(m);
}

// ============================================================================
// Paced-convoy fetch, Task 6: REORG MID-DESCENT AT THE RETAINED-WINDOW BOUNDARY
// ============================================================================
//
// The convoy makes manager->blocks a WINDOW, and a window has a bottom edge that
// today's code never had. _peerRelayedBlock's reorg path walks the fork back to
// where it joins the main chain (BRPeerManager.c:1812) through exactly that set,
// so the join point is now something that can be BELOW the retained floor.
//
// This case drives the reorg the production way -- real fork headers through the
// real _peerRelayedBlock -- with the join point sitting just ABOVE the retained
// floor: the deepest join the convoy still guarantees is resolvable. It asserts
// the reorg completes correctly there (right join point found, credits preserved,
// the abandoned branch's confirmations rolled back, no crash) AND that
// manager->blocks stays bounded across the reorg.
//
// THE OTHER EDGE IS NOT ASSERTED HERE, DELIBERATELY -- see the note at the end of
// this function: a fork whose join point is BELOW the retained floor makes that
// walk terminate with b == NULL, and :1817/:1819 dereference b->height with no
// guard. That NULL-guard is the sibling task's (spec Task 7); running the case
// before the guard exists would SIGSEGV the whole suite rather than fail one
// assertion. The structural PRECONDITION is asserted instead, without the deref.
static void test_reorg_mid_descent(void)
{
    printf("\n=== test_reorg_mid_descent (paced-convoy Task 6: reorg at the retained-window boundary) ===\n");

    // A FRESH wallet: this case registers real transactions, and the suite's shared
    // wallet is used by every other case.
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRWallet *w = BRWalletNew(NULL, 0, mpk);
    check(w != NULL, "fresh wallet created for the credit assertions");
    if (! w) return;

    // ABOVE the highest mainnet checkpoint (23,660,000): _peerRelayedBlock discards
    // forks at/below the last checkpoint outright (:1798), so a lower base would
    // make this case silently test nothing.
    const uint32_t BASE  = 24000000u;
    const uint32_t COUNT = 6000u;                 // > CLEAR_MEM_BLOCKS_COUNT_TRIGGER, so the pruner runs
    const uint32_t MAIN_TIP = BASE + COUNT - 1u;

    uint32_t TIP; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(w, BASE, COUNT, &TIP, &baseCount);
    check(m != NULL && TIP == MAIN_TIP, "main chain built [BASE .. BASE+5999]");
    if (! m) { BRWalletFree(w); return; }

    // Mid-descent state: the scan sits 2000 below the header frontier (a legitimate
    // convoy position, W_hdr = 2000 < CF_CONVOY_WINDOW), and the cfheader frontier
    // has raced to the header tip inside the window.
    const uint32_t SCAN = MAIN_TIP - 2000u;
    BRCompactFilterChainFree(m->compactFilterChain);
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, MAIN_TIP + 1u, UINT256_ZERO);
    m->autoFetchCFiltersEnabled = 1;
    m->autoFetchCFiltersStart   = SCAN;
    m->autoFetchCFiltersThrough = SCAN - 1u;
    m->estimatedHeight          = MAIN_TIP + 1000u;   // keep _BRPeerManagerLoadMempools out of this case
    BRCFScanLedgerInit(&m->cfLedger, SCAN);

    // Prune to the convoy's retained window. This is the REAL floor, not a hand-set one.
    _BRPeerManagerClearMemory(m);
    const uint32_t FLOOR = SCAN - CLEAR_MEM_CF_RETENTION_MARGIN;
    check(rhBlockPresent(m, FLOOR), "the retained window's low edge (scanFrontier-144) is resident");
    check(! rhBlockPresent(m, FLOOR - 1u),
          "THE WINDOW BOUNDARY: one height below the retention floor is PRUNED (this is the edge the "
          "convoy newly creates, and the edge this reorg is injected at)");
    check(_cfConvoyHdrGated(m) == 0, "setup: the header window is open (W_hdr = 2000 < CF_CONVOY_WINDOW)");

    // ---- wallet credits either side of the fork point ----
    const uint32_t H_JOIN = SCAN - 100u;          // 44 above the retained floor: the deepest resolvable join
    check(H_JOIN > FLOOR, "setup: the fork joins ABOVE the retained floor (resolvable by construction)");

    BRAddress a0 = BRWalletReceiveAddress(w, 1);   // segwit: the BIP84 wallet's own receive address
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), a0.s);
    check(spkLen > 0, "setup: wallet receive address -> scriptPubKey");

    const uint64_t AMT = 500000000ULL;            // 5 DGB, far above dust (no pending-gate interference)
    BRTransaction *txBelow = NULL, *txAbove = NULL;
    UInt256 hBelow = UINT256_ZERO, hAbove = UINT256_ZERO;
    for (int k = 0; k < 2; k++) {
        UInt256 prevOut; memset(prevOut.u8, (uint8_t)(0xC0 + k), sizeof(prevOut.u8));
        BRTransaction *tx = BRTransactionNew();
        static const uint8_t placeholder[1] = { 0 };
        BRTransactionAddInput(tx, prevOut, 0, 0, spk, spkLen, placeholder, 0, placeholder, 0, 0xffffffff);
        BRTransactionAddOutput(tx, AMT, spk, spkLen);
        {   // finalize txHash the way the wire would
            uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
            size_t len = BRTransactionSerialize(tx, data, sizeof(data));
            BRTransaction *t = BRTransactionParse(data, len);
            if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
        }
        // Straddle the join point by EXACTLY one block, so the assertions below pin
        // the fork-join walk to H_JOIN precisely rather than to a loose range:
        // BRWalletSetTxUnconfirmedAfter(join) un-confirms strictly ABOVE `join`.
        tx->timestamp   = 1700000000u;
        tx->blockHeight = (k == 0) ? H_JOIN : (H_JOIN + 1u);
        check(BRWalletRegisterTransaction(w, tx) != 0, "credit registered into the wallet");
        if (k == 0) { txBelow = tx; hBelow = tx->txHash; } else { txAbove = tx; hAbove = tx->txHash; }
    }
    const uint64_t balanceBefore = BRWalletBalance(w);
    check(balanceBefore == 2u * AMT, "setup: both credits are in the balance before the reorg");
    check(txBelow && txAbove, "setup: both transactions built");

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x61; pa->port = 12061; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);
    BRPeerCallbackInfo info = { pa, m, UINT256_ZERO };

    // ---- inject the fork: [H_JOIN+1 .. MAIN_TIP+1], one longer than the main chain,
    // relayed in ascending order through the production handler. Every block up to
    // MAIN_TIP takes the "new block is on a fork" branch; the one at MAIN_TIP+1
    // overtakes and triggers the fork-join walk + reorg. ----
    const size_t countBeforeFork = BRSetCount(m->blocks);
    for (uint32_t h = H_JOIN + 1u; h <= MAIN_TIP + 1u; h++) {
        BRMerkleBlock *fb = BRMerkleBlockNew();
        fb->blockHash = rhForkHash(h);
        fb->prevBlock = (h == H_JOIN + 1u) ? rhUniqueHash(H_JOIN) : rhForkHash(h - 1u);
        fb->height    = h;
        fb->timestamp = (uint32_t)time(NULL) - 60u;   // recent: never aged out as a stale orphan
        _peerRelayedBlock(&info, fb);                 // <- NO CRASH is itself an assertion
    }

    // ---- correctness after the reorg ----
    check(m->lastBlock != NULL && m->lastBlock->height == MAIN_TIP + 1u,
          "REORG COMPLETED: the longer fork became the main chain (lastBlock at MAIN_TIP+1)");
    check(m->lastBlock && UInt256Eq(m->lastBlock->blockHash, rhForkHash(MAIN_TIP + 1u)),
          "REORG COMPLETED: lastBlock is the FORK's tip block, not the old main-chain tip");

    BRTransaction *tb = BRWalletTransactionForHash(w, hBelow);
    BRTransaction *ta = BRWalletTransactionForHash(w, hAbove);
    check(tb != NULL && ta != NULL, "both transactions survive the reorg in the wallet (no credit dropped)");
    check(tb && tb->blockHeight == H_JOIN,
          "JOIN POINT EXACT: the credit AT the join height keeps its confirmation -- the fork-join walk "
          "stopped at H_JOIN, not one block deeper (which would have needlessly un-confirmed it)");
    check(ta && ta->blockHeight == TX_UNCONFIRMED,
          "JOIN POINT EXACT: the credit ONE block above the join was rolled back to unconfirmed -- it was "
          "confirmed on the branch the reorg abandoned, and the walk did not stop one block too high");
    check(BRWalletBalance(w) == balanceBefore,
          "CREDITS PRESERVED ACROSS THE REORG: the balance is unchanged -- an un-confirmed credit is "
          "still the user's money, it is not lost");

    // ...and the reorg did not blow the window open: the fork's blocks are bounded
    // by the fork length, and the retained floor still holds underneath.
    size_t countAfter = BRSetCount(m->blocks);
    check(countAfter <= countBeforeFork + (size_t)(MAIN_TIP + 1u - H_JOIN),
          "BOUNDED THROUGH THE REORG: manager->blocks grew by at most the fork's own length");
    check(! rhBlockPresent(m, FLOOR - 1u), "the retention floor still holds below the reorged window");

    // ---- THE OTHER EDGE (now covered, in its own case) ----------------------
    // If the fork's join point is BELOW the retained floor -- reachable in
    // production whenever the scan frontier climbs (raising cfFloor past the join)
    // between the fork's first block and the one that overtakes the main chain --
    // then the walk at BRPeerManager.c:1812
    //     while (b && b2 && ! BRMerkleBlockEq(b, b2)) { b = BRSetGet(blocks, &b->prevBlock); ... }
    // exits with b == NULL, and :1817/:1819 dereference it. That edge is NOT
    // asserted here (it is a SIGSEGV, not a failed assertion, so it belongs in a
    // case run under its own red gate): see test_reorg_below_window_no_crash below,
    // which builds the join point's absence for real and is gated red-before-green
    // against -DREORG_NULLGUARD_UNFIXED.

    BRPeerManagerFree(m);
    BRWalletFree(w);
}

// ============================================================================
// Paced-convoy fetch, Task 7: REORG WHOSE JOIN POINT IS BELOW THE RETAINED WINDOW
// ============================================================================
//
// THE crash the sibling case above deliberately declined to run. The convoy makes
// manager->blocks a bounded WINDOW; _peerRelayedBlock's fork/reorg branch walks the
// fork back to its join with the main chain THROUGH THAT SET (BRPeerManager.c:1812).
// Once the window has a bottom edge the join point can be gone, the walk exits with
// b == NULL, and :1817/:1819 dereference b->height. Before the window existed the
// whole chain was resident and the join was always found, which is why this was
// unreachable and is not any more.
//
// THE CONSTRUCTION IS THE HARD PART. _BRPeerManagerClearMemory frees the join block
// only when BOTH of these hold, and either one missed leaves the join resident, the
// walk never reaching NULL, and this case GREEN WITHOUT EVER EXERCISING THE GUARD:
//
//   (1) BRSetCount(manager->blocks) >= CLEAR_MEM_BLOCKS_COUNT_TRIGGER (5000). Below
//       that the pruner returns having freed nothing at all. The chain here is 6000
//       blocks and the count is ASSERTED at the trigger, not assumed. (This is also
//       why the prune happens EXACTLY ONCE: pruning earlier would drop the count
//       under 5000 and silently no-op the pass that matters.)
//
//   (2) The pruner only walks the prevBlock descent FROM manager->lastBlock, and it
//       skips everything at/above the retention floor. So it reaches the join point
//       only on a pass that runs AFTER the scan frontier has climbed past it. The
//       ordering below is therefore load-bearing:
//         a. the fork's FIRST block is relayed while the join is still resident --
//            it has to be, or the block attaches as an ORPHAN and there is no fork
//            at all (prev == NULL takes the orphan branch at :1683);
//         b. THEN the scan frontier advances past the join (floor climbs above it);
//         c. THEN a main-chain block is relayed, which is the production trigger for
//            _BRPeerManagerClearMemory (BRPeerManager.c:1750) -- and that pass takes
//            the join block with it;
//         d. THEN the rest of the fork is relayed, the last block overtaking the main
//            chain and running the fork-join walk into the hole.
//
// The join's absence is asserted DIRECTLY -- BRSetGet(manager->blocks, &joinHash)
// == NULL, immediately before the overtaking block is relayed -- so a green here
// cannot mean "the walk never hit NULL".
//
// AND THE JOIN HEIGHT IS NOT FREE. The walk descends b (the fork) and b2 (the main
// chain) IN LOCKSTEP at equal heights. b dies where the fork's parent is missing --
// at the join, H_JOIN. b2 dies where the main chain's parent is missing -- one below
// the retention floor, FLOOR-1. Whichever dies FIRST ends the loop, and only a
// b == NULL exit reaches the unguarded deref:
//   * H_JOIN >= FLOOR      -> the join is still resident, no NULL at all, normal reorg
//                             (that is the sibling case, test_reorg_mid_descent);
//   * H_JOIN <  FLOOR - 1  -> main(H_JOIN+1) was pruned too, so b2 goes NULL FIRST and
//                             the loop exits with b VALID at height FLOOR-1: no crash,
//                             but a roll-back to the wrong (too high) height -- a real,
//                             separate residual, noted in the task report, NOT this bug;
//   * H_JOIN == FLOOR - 1  -> b looks up the join and gets NULL while b2 is still alive
//                             on the floor block. THIS is the b == NULL exit.
// So the case is pinned at H_JOIN == FLOOR-1 -- the exact and only height at which the
// fork-join walk terminates on the fork side. An earlier draft of this case used a join
// 356 blocks below the floor; it built a genuinely absent join, printed every
// precondition PASS, and did NOT crash the unguarded build, because b2 died first. That
// is the false green this comment exists to prevent.
//
// RED-BEFORE-GREEN: run.sh builds this case alone with -DREORG_NULLGUARD_UNFIXED
// (the guard compiled out) and requires an ASan SEGV report whose faulting address is
// offsetof(BRMerkleBlock, height) -- NOT merely a nonzero exit, which a failed
// assertion produces too. NOTE that on this host build _peer_log expands to nothing
// (no -DDEBUG), so the peer_log's b->height is not evaluated and the crash lands on
// the BRWalletSetTxUnconfirmedAfter(wallet, b->height) below it; on Android _peer_log
// is __android_log_print and the peer_log dereferences too. The guard covers both.
static void test_reorg_below_window_no_crash(void)
{
    printf("\n=== test_reorg_below_window_no_crash (paced-convoy Task 7: join point below the retained window) ===\n");

    // A FRESH wallet: this case registers real transactions, and the suite's shared
    // wallet is used by every other case.
    uint8_t seed[64];
    BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRWallet *w = BRWalletNew(NULL, 0, mpk);
    check(w != NULL, "fresh wallet created for the credit assertions");
    if (! w) return;

    // Same BASE as the sibling case and for the same reason: ABOVE the highest
    // mainnet checkpoint (23,660,000), or _peerRelayedBlock discards the fork
    // outright at :1797 and this case would silently test nothing.
    const uint32_t BASE     = 24000000u;
    const uint32_t COUNT    = 6000u;                  // > CLEAR_MEM_BLOCKS_COUNT_TRIGGER (trap 1)
    const uint32_t MAIN_TIP = BASE + COUNT - 1u;      // 24,005,999

    uint32_t TIP; size_t baseCount;
    BRPeerManager *m = rhBuildChainManager(w, BASE, COUNT, &TIP, &baseCount);
    check(m != NULL && TIP == MAIN_TIP, "main chain built [BASE .. BASE+5999]");
    if (! m) { BRWalletFree(w); return; }

    // The fork joins ~3000 below the tip. The pruner never touches the first
    // (CLEAR_MEM_BLOCKS_COUNT_TRIGGER - CLEAR_MEM_BLOCKS_COUNT_TAIL_LEN) == 800
    // blocks below lastBlock, so a shallower join would be structurally unprunable.
    // The eventual retention floor is pinned ONE ABOVE it (see the header comment):
    // the join is the topmost pruned main-chain block, which is the only height at
    // which the fork-side walk goes NULL before the main-side walk does.
    const uint32_t H_JOIN  = BASE + 3000u;                                   // 24,003,000
    const uint32_t FLOOR_B = H_JOIN + 1u;                                    // the post-climb retention floor
    const uint32_t SCAN_B  = FLOOR_B + CLEAR_MEM_CF_RETENTION_MARGIN;        // the scan frontier that produces it
    check(H_JOIN + (CLEAR_MEM_BLOCKS_COUNT_TRIGGER - CLEAR_MEM_BLOCKS_COUNT_TAIL_LEN) < MAIN_TIP,
          "setup: the join sits below the pruner's untouchable 800-block head, so the tail descent reaches it");

    // ---- phase A: frontier still BELOW the join, so the join is resident --------
    const uint32_t SCAN_A = H_JOIN;
    BRCompactFilterChainFree(m->compactFilterChain);
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, MAIN_TIP + 1u, UINT256_ZERO);
    m->autoFetchCFiltersEnabled = 1;
    m->autoFetchCFiltersStart   = SCAN_A;
    m->autoFetchCFiltersThrough = SCAN_A - 1u;
    m->estimatedHeight          = MAIN_TIP + 1000u;   // keep _BRPeerManagerLoadMempools out of this case
    BRCFScanLedgerInit(&m->cfLedger, SCAN_A);
    check(SCAN_A - CLEAR_MEM_CF_RETENTION_MARGIN < H_JOIN,
          "setup: the phase-A retention floor is BELOW the join, so the join is resident when the fork attaches");

    // ---- wallet credits straddling the join point ------------------------------
    BRAddress a0 = BRWalletReceiveAddress(w, 1);      // segwit: the BIP84 wallet's own receive address
    uint8_t spk[64];
    size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), a0.s);
    check(spkLen > 0, "setup: wallet receive address -> scriptPubKey");

    const uint64_t AMT = 500000000ULL;                // 5 DGB, far above dust
    UInt256 hBelow = UINT256_ZERO, hAbove = UINT256_ZERO;
    for (int k = 0; k < 2; k++) {
        UInt256 prevOut; memset(prevOut.u8, (uint8_t)(0xD0 + k), sizeof(prevOut.u8));
        BRTransaction *tx = BRTransactionNew();
        static const uint8_t placeholder[1] = { 0 };
        BRTransactionAddInput(tx, prevOut, 0, 0, spk, spkLen, placeholder, 0, placeholder, 0, 0xffffffff);
        BRTransactionAddOutput(tx, AMT, spk, spkLen);
        {   // finalize txHash the way the wire would
            uint8_t data[BRTransactionSerialize(tx, NULL, 0)];
            size_t len = BRTransactionSerialize(tx, data, sizeof(data));
            BRTransaction *t = BRTransactionParse(data, len);
            if (t) { tx->txHash = t->txHash; tx->wtxHash = t->wtxHash; BRTransactionFree(t); }
        }
        tx->timestamp   = 1700000000u;
        tx->blockHeight = (k == 0) ? H_JOIN : (H_JOIN + 1u);
        check(BRWalletRegisterTransaction(w, tx) != 0, "credit registered into the wallet");
        if (k == 0) hBelow = tx->txHash; else hAbove = tx->txHash;
    }
    const uint64_t balanceBefore = BRWalletBalance(w);
    check(balanceBefore == 2u * AMT, "setup: both credits are in the balance before the reorg");

    BRPeer *pa = BRPeerNew(BRMainNetParams.magicNumber);
    pa->address.u8[15] = 0x62; pa->port = 12062; pa->services |= SERVICES_NODE_COMPACT_FILTERS;
    array_add(m->connectedPeers, pa);
    BRPeerCallbackInfo info = { pa, m, UINT256_ZERO };

    // ---- (2a) the fork's FIRST block, relayed while the join IS resident --------
    check(rhBlockPresent(m, H_JOIN), "setup: the join block is resident at the instant the fork's first block arrives");
    {
        BRMerkleBlock *fb = BRMerkleBlockNew();
        fb->blockHash = rhForkHash(H_JOIN + 1u);
        fb->prevBlock = rhUniqueHash(H_JOIN);         // -> the MAIN-chain block at the join height
        fb->height    = H_JOIN + 1u;
        fb->timestamp = (uint32_t)time(NULL) - 60u;   // recent: never aged out as a stale orphan
        _peerRelayedBlock(&info, fb);
    }
    {
        UInt256 fh = rhForkHash(H_JOIN + 1u);
        check(BRSetGet(m->blocks, &fh) != NULL,
              "the fork's first block ATTACHED AS A FORK (it is in manager->blocks, not the orphan set) -- it "
              "found its parent, which is the whole reason it had to be relayed BEFORE the prune");
    }

    // ---- (2b) the scan frontier climbs PAST the join ---------------------------
    m->autoFetchCFiltersStart   = SCAN_B;
    m->autoFetchCFiltersThrough = SCAN_B - 1u;
    BRCFScanLedgerInit(&m->cfLedger, SCAN_B);                                // re-init is filter-buffer-safe
    check(SCAN_B - CLEAR_MEM_CF_RETENTION_MARGIN == H_JOIN + 1u,
          "setup: the retention floor has climbed to EXACTLY one above the join -- the join is the topmost "
          "pruned main-chain block, the only height at which the walk goes NULL on the FORK side");

    // TRAP (1), ASSERTED not assumed: below 5000 the pruner frees NOTHING.
    check(BRSetCount(m->blocks) >= (size_t)CLEAR_MEM_BLOCKS_COUNT_TRIGGER,
          "PRUNER WILL ACTUALLY RUN: BRSetCount(manager->blocks) is at/above the 5000-block trigger, so the "
          "prune below is a real prune and not a silent no-op");

    // ---- (2c) the production ClearMemory trigger: a main-chain extension --------
    {
        BRMerkleBlock *nb = rhChainBlock(MAIN_TIP + 1u);
        _peerRelayedBlock(&info, nb);   // "new block extends main chain" -> _BRPeerManagerClearMemory (:1750)
    }
    check(m->lastBlock && m->lastBlock->height == MAIN_TIP + 1u,
          "the main chain extended by one -- the branch that invokes _BRPeerManagerClearMemory in production");

    // ---- THE EVIDENCE: the join point is genuinely GONE from manager->blocks ----
    UInt256 joinHash = rhUniqueHash(H_JOIN);
    check(BRSetGet(m->blocks, &joinHash) == NULL,
          "JOIN POINT ABSENT: the bounded window FREED the fork's join block, so the walk at :1812 will "
          "terminate at b == NULL (without this the case would green without ever exercising the guard)");
    {
        UInt256 fh = rhForkHash(H_JOIN + 1u);
        check(BRSetGet(m->blocks, &fh) != NULL,
              "the fork's first block SURVIVED the prune (the pruner walks only the main-chain descent from "
              "lastBlock), so the walk really does descend into the missing parent rather than stopping above it");
    }
    check(rhBlockPresent(m, FLOOR_B),
          "the retained window's low edge is still resident -- the prune was a WINDOW, not a wipe");
    check(rhBlockPresent(m, H_JOIN + 1u),
          "THE MAIN-SIDE WALK SURVIVES: main(H_JOIN+1) (== the floor block) is resident, so b2 does NOT go "
          "NULL first -- b, the FORK side, is the pointer that dies, and it dies at exactly the join");
    check(! rhBlockPresent(m, H_JOIN) && rhBlockPresent(m, H_JOIN + 1u),
          "THE WINDOW EDGE IS EXACTLY AT THE JOIN: pruned at H_JOIN, resident at H_JOIN+1");

    // ---- (2d) the rest of the fork; the last block overtakes and runs the walk --
    // The unguarded build's SEGV faults at NULL + offsetof(BRMerkleBlock, height) --
    // the byte offset of the ONE field the reorg branch reads off `b`. Publishing the
    // offset lets run.sh's red gate check the faulting address NUMERICALLY, which
    // attributes the crash to `b->height` on a NULL b without needing a symbolized
    // backtrace (llvm-symbolizer costs ~90 s on this binary, and would make the gate
    // depend on a symbolizer being installed; the gate therefore runs the crash build
    // with ASAN_OPTIONS=symbolize=0 and reads this instead).
    printf("REORG-KAT: offsetof(BRMerkleBlock, height) = %zu\n", (size_t)offsetof(BRMerkleBlock, height));

    // Flush FIRST: the unguarded build dies on SIGSEGV inside the loop below, and a
    // buffered stdout would be lost with it -- leaving the crash log with no evidence
    // of WHY it crashed. run.sh's red gate greps this log for the precondition PASSes
    // above (and for the absence of any FAIL) so that a crash for the wrong reason
    // cannot satisfy the gate.
    fflush(stdout);

    for (uint32_t h = H_JOIN + 2u; h <= MAIN_TIP + 2u; h++) {
        BRMerkleBlock *fb = BRMerkleBlockNew();
        fb->blockHash = rhForkHash(h);
        fb->prevBlock = rhForkHash(h - 1u);
        fb->height    = h;
        fb->timestamp = (uint32_t)time(NULL) - 60u;
        _peerRelayedBlock(&info, fb);   // the h == MAIN_TIP+2 pass runs the fork-join walk into the hole
    }

    // Executing at all past that loop IS the crash assertion: unguarded, :1819
    // dereferences the NULL b and the process dies on SIGSEGV mid-loop.
    check(m->lastBlock != NULL && m->lastBlock->height == MAIN_TIP + 2u,
          "NO CRASH, AND THE REORG STILL COMPLETED: the fork-join walk terminated at b == NULL, the reorg "
          "branch did not dereference it, and the longer fork was still adopted (RED on "
          "-DREORG_NULLGUARD_UNFIXED: ASan SEGV in _peerRelayedBlock on the unguarded "
          "BRWalletSetTxUnconfirmedAfter(wallet, b->height))");
    check(m->lastBlock && UInt256Eq(m->lastBlock->blockHash, rhForkHash(MAIN_TIP + 2u)),
          "the adopted tip is the FORK's tip block, not the old main-chain tip");

    // NOT VACUOUS: nothing re-added the join between the evidence above and the walk.
    check(BRSetGet(m->blocks, &joinHash) == NULL,
          "NOT VACUOUS: the join point was STILL absent when the overtaking block ran the walk");

    // Funds are not lost by the unresolvable-join reorg. NOTE: with no resolvable
    // join height there is nothing to roll back TO, so the
    // BRWalletSetTxUnconfirmedAfter un-confirm is skipped and a credit confirmed on
    // the abandoned branch keeps its (now stale) height until it is re-relayed or the
    // chain is reconciled. That is a real residual of the minimal guard, recorded
    // here as a comment rather than asserted as correct -- what IS asserted is the
    // property that actually matters: no credit is dropped and no balance moves.
    check(BRWalletTransactionForHash(w, hBelow) != NULL && BRWalletTransactionForHash(w, hAbove) != NULL,
          "NO CREDIT DROPPED: both transactions survive the unresolvable-join reorg in the wallet");
    check(BRWalletBalance(w) == balanceBefore,
          "CREDITS PRESERVED ACROSS THE REORG: the balance is unchanged -- the user's money is not lost");

    BRPeerManagerFree(m);
    BRWalletFree(w);
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

#ifdef KAT_DETERMINISM_GUARD_REDGREEN_ONLY
    // run.sh builds this twice for the Part-3b determinism guard's red-before-green
    // gate: once with the pre-guard preemptive advance (-DDETERMINISM_GUARD_PREEMPTIVE_ADVANCE,
    // must FAIL == RED) and once fixed (must PASS == GREEN), running ONLY that one
    // case so the RED is unambiguously the preemptive abandonedBelow raise.
    //
    // RE-HOMED (paced-convoy Task 5): the case this gate used to run
    // (test_clearmemory_ceiling_scan_not_started) tested _cfApplyRetentionCeiling,
    // the DEPTH trigger this task deletes, so it went with it. The determinism
    // guard itself is RETAINED and is now MORE load-bearing, not less: the B2 valve
    // is its only production caller, and the valve's whole operator contract —
    // "cnt>0 <=> WARN <=> abandonedBelow advanced", i.e. every abandonment is a
    // visible, warn-logged event and abandonedBelow==0 is a verified fact — rests on
    // it. So the gate is re-pointed at the guard's own case rather than deleted.
    test_abandon_guard_no_preemptive_advance(wallet);
    BRWalletFree(wallet);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
#endif

#ifdef KAT_B2_REDGREEN_ONLY
    // run.sh builds this FOUR times for the B2 abandonment valve's matched set --
    // one shape per axis the valve must get right, each of which must FAIL == RED:
    //   -DCONVOY_NO_B2_VALVE           : no valve at all (today's shape: the only
    //                                    abandonment was the deleted depth ceiling).
    //                                    A gaveUp hole is never re-armed and never
    //                                    abandoned -> the convoy wedges forever.
    //   -DCONVOY_B2_PEER_BLIND         : the valve ignores whether any CF peer is
    //                                    connected -> abandons an un-offered height.
    //   -DCONVOY_B2_IGNORE_OFFER_LATCH : the valve checks CF-peer presence at the
    //                                    abandon INSTANT instead of THROUGHOUT the
    //                                    deciding cycle -> a peer flap reads as five
    //                                    live refusals.
    //   -DCONVOY_B2_REARM_ONCE         : the valve abandons after ONE re-arm cycle
    //                                    -> one unlucky rotation cycle false-positives.
    test_valve_matched_set(wallet);
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

#ifdef KAT_SCALE_REDGREEN_ONLY
    // run.sh builds this twice for the MEMORY BOUND's red-before-green gate -- the
    // headline claim of the whole paced-convoy feature: once with the suppression
    // compiled out (-DCONVOY_UNGATED == the pre-fix shape, where the header
    // continuation fast-forwards straight to the tip and manager->blocks grows to the
    // full 105k-block chain length instead of staying at ~W+margin -- the deep-restore
    // OOM, which MUST FAIL == RED) and once gated (must PASS == GREEN). Only the scale
    // case runs, so the RED is unambiguously the unbounded resident set.
    test_convoy_scale_bounded(wallet);
    BRWalletFree(wallet);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
#endif

#ifdef KAT_REORG_NULLGUARD_REDGREEN_ONLY
    // run.sh builds this twice for the reorg fork-join NULL-guard's red-before-green
    // gate: once with the guard compiled out (-DREORG_NULLGUARD_UNFIXED == the pre-fix
    // shape, where a fork whose join point the bounded window already freed makes the
    // :1812 walk exit at b == NULL and :1819 dereference it -- must CRASH, and run.sh
    // requires an actual ASan SEGV report, NOT merely a nonzero exit, so a failed
    // assertion or a broken build cannot satisfy the gate) and once guarded (must PASS
    // == GREEN). Only this case runs, so the RED is unambiguously the missing guard.
    test_reorg_below_window_no_crash();
    BRWalletFree(wallet);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
#endif

#ifdef KAT_RESUME_C1_REDGREEN_ONLY
    // run.sh builds this twice for the fix wave's C-1 red-before-green gate: once
    // with the reconciliation compiled out (-DCONVOY_C1_UNFIXED -- the pre-fix
    // shape: the resume snap is RAISE-ONLY and nothing surfaces an unscannable
    // band, so a resumed deep descent marks ~CF_CONVOY_WINDOW never-requested
    // heights scanned with abandonedBelow still 0, and a restored outstanding hole
    // below the block floor pins the frontier where no valve can ever see it --
    // must FAIL == RED) and once with the fix (must PASS == GREEN), running ONLY
    // this case so the RED is unambiguously the missing reconciliation.
    test_resume_below_block_floor_surfaces(wallet);
    BRWalletFree(wallet);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
#endif

#ifdef KAT_RESUME_FLOOR_REDGREEN_ONLY
    // run.sh builds this twice for the fix wave's R2 red-before-green gate: once
    // with BRPeerManagerNewEx chaining FORWARD from the highest saved block
    // (-DRESUME_FLOOR_UNFIXED -- the pre-fix shape, where the loop looks for a CHILD,
    // finds none, exits after ONE iteration and strands SAVE_BLOCK_COUNT-1 headers in
    // `orphans`, putting the resume block floor at the SAVED TIP -- must FAIL == RED)
    // and once chaining DOWNWARD (must PASS == GREEN). BOTH resume cases run: the
    // floor is the shared structural fact, so the C-1 case pins the mechanism (floor,
    // orphan residency, band width) and the R2 case pins the OUTCOME (a healthy
    // wallet's abrupt kill surfaces no band at all).
    test_resume_below_block_floor_surfaces(wallet);
    test_resume_healthy_kill_surfaces_no_band(wallet);
    BRWalletFree(wallet);
    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
#endif

#ifdef KAT_RESUME_SNAP_REDGREEN_ONLY
    // run.sh builds this twice for the resume cursor reconciliation's
    // red-before-green gate: once with the snap compiled to a no-op
    // (-DRESUME_SNAP_UNFIXED -- the pre-fix shape: the forward-fetch cursor
    // stays at birth-1 after a ledger restore whose scannedThrough sits far
    // above birth, so the next forward-fetch tick re-requests already-scanned
    // history and drags scannedThrough back down, discarding persisted scan
    // progress every KeepAlive tick -- must FAIL == RED) and once with the fix
    // (must PASS == GREEN), running ONLY this case so the RED is unambiguously
    // the missing snap and nothing incidental.
    test_resume_snaps_cursor(wallet);
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
    test_abandon_guard_no_preemptive_advance(wallet);      // Task 4b guard, re-homed onto the B2 valve's primitive (red-before-green)
    test_lowest_needed_accessor(wallet);                // paced-convoy-fetch Task 1: frontier semantics anchor + BRPeerManager accessors
    test_convoy_gate_suppresses_continuations(wallet);     // paced-convoy-fetch Task 2: gate the tip-racers, exempt recovery (red-before-green)
    test_convoy_gate_null_chain_open(wallet);              // paced-convoy-fetch Task 2: NULL-chain carve-out (red-before-green)
    test_b1_resumes_drain_trough(wallet);                  // paced-convoy-fetch Task 3: B1.1 forward drive out of the drain trough (red-before-green)
    test_b1_rekicks_cfheaders_on_window_reopen(wallet);    // paced-convoy-fetch Task 3: B1.2 cfheaders re-kick on window re-open
    test_b1_rekicks_getheaders_when_tip_frozen(wallet);    // paced-convoy-fetch Task 3: B1.3 getheaders re-kick (manager side)
    test_b1_getheaders_rekick_is_throttled(wallet);        // paced-convoy-fetch Task 3 fix 1: B1.3 rate limit (red-before-green)
    test_b1_rekick_backoff_not_stale_across_gated_period(wallet); // paced-convoy-fetch Task 3 fix 2: GATED->open episode reset (red-before-green)
    test_resume_snaps_cursor(wallet);                      // paced-convoy-fetch Task 4: resume cursor reconciliation (red-before-green)
    test_resume_below_block_floor_surfaces(wallet);        // fix wave C-1: resumed mid-descent silent skip + the un-valved variant
    test_resume_healthy_kill_surfaces_no_band(wallet);     // fix wave R2: an ordinary kill of a healthy wallet surfaces NOTHING (red-before-green)
    test_valve_matched_set(wallet);                        // paced-convoy-fetch Task 5: B2 valve MATCHED SET a/b/c/d (red-before-green x4)
    test_pending_abandonment_accessor(wallet);             // paced-convoy-fetch Task 6: the valve/watchdog ordering signal (boundable count)
    test_convoy_scale_bounded(wallet);                     // paced-convoy-fetch Task 6: THE memory bound, >100k descent (red-before-green)
    test_reorg_mid_descent();                              // paced-convoy-fetch Task 6: reorg at the retained-window boundary
    test_reorg_below_window_no_crash();                    // paced-convoy-fetch Task 7: fork-join below the window (red-before-green CRASH gate)

    BRWalletFree(wallet);

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
