// Host KAT for 4.1.4 item CF-LEDGER-GAPS: three ways the compact-filter scan could lose track of a
// height without anyone being told. Each case pins one invariant:
//
//   (a) FLOOR MEMO.  Any memo derived from the block chain is invalidated whenever a block it was
//       derived from is replaced. The resident block floor is memoized on (blocks, count, tip,
//       tip height). Replacing a checkpoint stub (prevBlock 0) by the real header of the same hash
//       changes none of those, but it connects the chain downward, so the true floor drops. Two
//       sites do that replacement: the "we already have the block" branch of _peerRelayedBlock and
//       _BRPeerManagerInstallSavedBlock. After either, the cached floor must equal a fresh walk.
//
//   (b) BAND KEPT.  A recorded abandoned band survives any ledger re-Init until a rescan clears it.
//       A band surfaced (abandonedBelow raised) and then a BRCFScanLedgerInit in the same locked
//       section used to memset it back to 0 before the app could poll it, so no band was ever
//       recorded. Sites: the cfheaders floor snap and the CF chain floor re-anchor (and the
//       corroborated rebuild, which uses the same helper). A re-Init BELOW the band makes those
//       heights requestable again, which is the rescan, so there the band is not carried (guard).
//       A re-Init strictly inside a band keeps the part below the new start (abandonedBelow ==
//       start). The corroborated-rebuild cases are in cf_ledger_gaps_rebuild_main.c.
//
//   (c) PARK CONTIGUOUS.  scannedThrough never advances over a height that was never requested and
//       evaluated. The never-brick checkpoint park set the fetch cursor to the checkpoint with no
//       look at what the ledger had requested; with the fetch lagging below the checkpoint that
//       moved the cursor FORWARD, the next fetch recorded a range above requestedThrough + 1, and
//       _cfLedgerAdvance (which assumes contiguous requests) carried scannedThrough over the
//       never-requested band. The park may only move the cursor back (guard: a cursor above the
//       checkpoint still parks at it).
//
//   (d) ABANDONED AT START (CF-ABANDONED-AT-START).  A band the ledger carries at or below its own
//       start (abandonedBelow <= start, left by a re-Init at or inside the band: the cfheaders
//       floor snap, the floor re-anchor, and a re-Init strictly inside the band, which is the
//       corroborated rebuild's and the arming call's geometry) can never be retired by this ledger:
//       a ledger never scans below its start, and BRCFScanLedgerRetireAbandonedTo refuses any
//       floor below it. Only a re-Init below the band (the rescan) clears it. The backfill must
//       therefore ask for nothing on its behalf; it used to send a getheaders on every tick, for
//       ever. Guards (every arm): such a band is never retired, even with headers resident below
//       it (the banner stays honest); a band ABOVE the start is still backfilled and retired.
//       And a resident floor BELOW the start is clamped to the start before Retire (which refuses
//       a sub-start floor): the band is retired to the start, every height of it resident, and the
//       backfill then idles; it used to re-send getheaders on every tick (guard: a floor above the
//       start still retires only down to that floor).
//
// Approach: #include BRPeer.c (private BRPeerContext, to mark a synthetic peer Connected) and
// BRPeerManager.c (file statics) into this unit, the same pattern as cf_checkpoint_neverbrick_kat.
// No sockets: BRPeerNew peers have socket -1, so every send no-ops.
//
// Each case is selected by name (argv[1]); with no argument the case names are listed. run.sh
// builds a green arm (no -D) and one red arm per seam, and requires each red case to FAIL in its
// arm and PASS in green, and every guard case to PASS in every arm.

#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <pthread.h>

#define BRPEER_HEADERS_KAT 1   // per-peer getheaders counter, for the (d) backfill cases
#define _dummyThreadCleanup _dummyThreadCleanup_brpeer
#include "BRPeer.c"
#undef _dummyThreadCleanup

#include "BRPeerManager.c"

#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"

static int g_fail = 0;
static void check(int c, const char *d) { printf(c ? "  PASS %s\n" : "  FAIL %s\n", d); if (! c) g_fail++; }

static UInt256 u256_fill(uint8_t b) { UInt256 h; memset(h.u8, b, sizeof(h.u8)); return h; }

static UInt256 u256_tag(uint32_t a, uint32_t b)
{
    UInt256 h = UINT256_ZERO;
    h.u32[0] = 0x4C454447u;   // "LEDG"
    h.u32[1] = a;
    h.u32[2] = b;
    return h;
}

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

static BRPeer *addConnectedFilterPeer(BRPeerManager *m, uint8_t addrByte, uint16_t port)
{
    BRPeer *p = BRPeerNew(BRMainNetParams.magicNumber);
    p->address.u8[15] = addrByte;
    p->port = port;
    p->services |= SERVICES_NODE_COMPACT_FILTERS;
    ((BRPeerContext *)p)->status = BRPeerStatusConnected;
    array_add(m->connectedPeers, p);
    return p;
}

static BRMerkleBlock *mkBlock(uint32_t height, UInt256 hash, UInt256 prev, uint32_t ts)
{
    BRMerkleBlock *b = BRMerkleBlockNew();
    b->height    = height;
    b->blockHash = hash;
    b->prevBlock = prev;
    b->timestamp = ts;
    b->version   = BLOCK_VERSION_SCRYPT;
    return b;
}

// The highest hardcoded checkpoint stub resident in the manager.
static BRMerkleBlock *topStub(BRPeerManager *m)
{
    size_t n = BRSetCount(m->checkpoints);
    if (n == 0) return NULL;
    BRMerkleBlock **cps = calloc(n, sizeof(*cps));
    BRSetAll(m->checkpoints, (const void **)cps, n);
    BRMerkleBlock *s = cps[0];
    for (size_t i = 1; i < n; i++) if (cps[i]->height > s->height) s = cps[i];
    free(cps);
    return s;
}

// ===================================================================================== (a) ====

// Interior stub fixture: P (Hs-1, parent not resident) | S (stub at Hs, prevBlock 0) <- T1 (Hs+1, tip).
// The floor walk from T1 stops at S, so the floor reads Hs and the memo caches it.
typedef struct {
    BRWallet *w; BRPeerManager *m; BRMerkleBlock *stub, *P, *T1; uint32_t Hs; UInt256 stubHash;
} MemoFx;

static int memoFxInit(MemoFx *f)
{
    memset(f, 0, sizeof(*f));
    f->w = makeWallet();
    f->m = BRPeerManagerNew(&BRMainNetParams, f->w, 0, NULL, 0, NULL, 0);
    f->m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;
    f->stub = topStub(f->m);
    check(f->stub != NULL, "setup: the manager holds a checkpoint stub");
    if (! f->stub) return 0;
    f->Hs = f->stub->height;
    f->stubHash = f->stub->blockHash;
    check(UInt256IsZero(f->stub->prevBlock), "setup: the stub carries no parent link (prevBlock 0)");

    f->P = mkBlock(f->Hs - 1, u256_tag(f->Hs - 1, 1), u256_tag(f->Hs - 2, 99), f->stub->timestamp - 15);
    BRSetAdd(f->m->blocks, f->P);
    f->T1 = mkBlock(f->Hs + 1, u256_tag(f->Hs + 1, 1), f->stubHash, f->stub->timestamp + 15);
    BRSetAdd(f->m->blocks, f->T1);
    f->m->lastBlock = f->T1;

    MGR_LOCK(f->m);
    uint32_t walked = _BRPeerManagerBlockFloor(f->m);
    uint32_t cached = _BRPeerManagerBlockFloorCached(f->m);
    MGR_UNLOCK(f->m);
    check(walked == f->Hs && cached == f->Hs, "setup: the floor stops at the stub and the memo holds it");
    check(f->m->floorMemoValid == 1 && f->m->floorMemoTip == f->T1,
          "setup: the memo is keyed on the tip T1 (not the stub), so it is an INTERIOR stub");
    return 1;
}

static void memoFxFree(MemoFx *f)
{
    if (f->m) BRPeerManagerFree(f->m);
    if (f->w) BRWalletFree(f->w);
}

static void case_floor_memo_relayed_stub_replace(void)
{
    MemoFx f;
    if (! memoFxInit(&f)) { memoFxFree(&f); return; }
    size_t countBefore = BRSetCount(f.m->blocks);

    BRPeer *pa = addConnectedFilterPeer(f.m, 0x61, 12061);
    BRPeerCallbackInfo info = { pa, f.m, UINT256_ZERO };
    BRMerkleBlock *real = mkBlock(0, f.stubHash, f.P->blockHash, f.stub->timestamp);
    _peerRelayedBlock(&info, real);   // takes the "we already have the block" branch

    check(BRSetGet(f.m->blocks, &f.stubHash) == real, "the real header replaced the stub in manager->blocks");
    check(BRSetCount(f.m->blocks) == countBefore && f.m->lastBlock == f.T1,
          "the memo key is unchanged by the replacement (same count, same tip)");

    MGR_LOCK(f.m);
    uint32_t walked = _BRPeerManagerBlockFloor(f.m);
    uint32_t cached = _BRPeerManagerBlockFloorCached(f.m);
    MGR_UNLOCK(f.m);
    check(walked == f.Hs - 1, "the chain now connects through the real header: the true floor is Hs-1");
    check(cached == walked, "relayed replace: the memoized floor equals a fresh walk (memo invalidated)");
    memoFxFree(&f);
}

static void case_floor_memo_install_saved_stub_replace(void)
{
    MemoFx f;
    if (! memoFxInit(&f)) { memoFxFree(&f); return; }

    BRMerkleBlock *real = mkBlock(f.Hs, f.stubHash, f.P->blockHash, f.stub->timestamp);
    MGR_LOCK(f.m);
    _BRPeerManagerInstallSavedBlock(f.m, real);
    MGR_UNLOCK(f.m);
    check(BRSetGet(f.m->blocks, &f.stubHash) == real, "the saved header replaced the stub in manager->blocks");

    MGR_LOCK(f.m);
    uint32_t walked = _BRPeerManagerBlockFloor(f.m);
    uint32_t cached = _BRPeerManagerBlockFloorCached(f.m);
    MGR_UNLOCK(f.m);
    check(walked == f.Hs - 1, "the chain now connects through the saved header: the true floor is Hs-1");
    check(cached == walked, "saved-block replace: the memoized floor equals a fresh walk (memo invalidated)");
    memoFxFree(&f);
}

// ===================================================================================== (b) ====

#define BAND_FLOOR   24000000u   // resident block floor (above every checkpoint, so no stub connects)
#define BAND_RESIDENT 40u        // resident heights [BAND_FLOOR .. BAND_FLOOR + 39]
#define BAND_START   1000u       // where the scan was armed (deep restore), far below the floor

typedef struct { BRWallet *w; BRPeerManager *m; BRPeer *p; } BandFx;

static void bandFxInit(BandFx *f, int withChain)
{
    memset(f, 0, sizeof(*f));
    f->w = makeWallet();
    f->m = BRPeerManagerNew(&BRMainNetParams, f->w, 0, NULL, 0, NULL, 0);
    BRPeerManager *m = f->m;
    m->syncMode = BR_SYNC_MODE_COMPACT_FILTERS_ONLY;
    UInt256 prev = u256_tag(BAND_FLOOR - 1, 77);   // not resident: the walk stops at BAND_FLOOR
    BRMerkleBlock *b = NULL;
    for (uint32_t i = 0; i < BAND_RESIDENT; i++) {
        b = mkBlock(BAND_FLOOR + i, u256_tag(BAND_FLOOR + i, 2), prev, 1700000000u + 15u * i);
        BRSetAdd(m->blocks, b);
        prev = b->blockHash;
    }
    m->lastBlock = b;
    f->p = addConnectedFilterPeer(m, 0x71, 12071);

    m->autoFetchCFiltersEnabled = 1;
    m->autoFetchCFiltersStart   = BAND_START;
    m->autoFetchCFiltersThrough = BAND_START - 1;
    BRCFScanLedgerInit(&m->cfLedger, BAND_START);
    if (withChain) {
        m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, BAND_START, u256_fill(0xA1));
    }

    // The KeepAlive C1 step: the scan frontier sits below the resident block floor, so the band
    // [BAND_START .. BAND_FLOOR-1] is surfaced (abandonedBelow raised, WARN).
    MGR_LOCK(m);
    check(_BRPeerManagerBlockFloor(m) == BAND_FLOOR, "setup: the resident block floor is BAND_FLOOR");
    _BRPeerManagerSurfaceUnscannableLocked(m, BAND_START, BAND_FLOOR, "kat: frontier below the block floor");
    check(m->cfLedger.abandonedBelow == BAND_FLOOR, "setup: the band is surfaced (abandonedBelow == floor)");
}

static void bandFxFree(BandFx *f)
{
    BRPeerManagerFree(f->m);
    BRWalletFree(f->w);
}

static void case_band_survives_cfheaders_floor_snap(void)
{
    BandFx f; bandFxInit(&f, /*withChain=*/0);   // returns with the lock HELD (same locked section)
    // ...and later in the same tick the cfheaders driver hits the floor snap and re-Inits the ledger.
    _BRPeerManagerRequestNextCFHeaders(f.m, f.p, /*isConvoyAdvance=*/0);
    uint32_t start = BRCFScanLedgerStartHeight(&f.m->cfLedger);
    MGR_UNLOCK(f.m);
    check(start == BAND_FLOOR, "the floor snap re-Inited the ledger at the block floor");
    check(BRPeerManagerAbandonedBelow(f.m) == BAND_FLOOR,
          "floor snap: the surfaced band is still the ledger's watermark when the app polls");
    bandFxFree(&f);
}

static void case_band_survives_floor_reanchor(void)
{
    BandFx f; bandFxInit(&f, /*withChain=*/1);
    int r = _BRPeerManagerReanchorAtFloorLocked(f.m, /*force=*/1);
    uint32_t start = BRCFScanLedgerStartHeight(&f.m->cfLedger);
    MGR_UNLOCK(f.m);
    check(r == 1 && start == BAND_FLOOR, "the floor re-anchor rebuilt the chain and re-Inited the ledger at the floor");
    check(BRPeerManagerAbandonedBelow(f.m) == BAND_FLOOR,
          "floor re-anchor: the surfaced band is still the ledger's watermark when the app polls");
    bandFxFree(&f);
}

// A re-Init strictly INSIDE the band (scannedThrough + 1 < start < band), as the corroborated rebuild
// or an arming call can land: the part of the band below the new start stays recorded.
static void case_band_inside_reinit_keeps_lower_part(void)
{
    BandFx f; bandFxInit(&f, /*withChain=*/0);
    uint32_t inside = BAND_START + 500;
    _BRPeerManagerReinitCFLedgerLocked(f.m, inside);
    uint32_t below = f.m->cfLedger.abandonedBelow;
    MGR_UNLOCK(f.m);
    check(below == inside,
          "re-Init inside a band: the part of the band below the new start stays recorded (abandonedBelow == start)");
    bandFxFree(&f);
}

// GUARD (every arm): a re-Init BELOW the band makes it requestable again, which is the rescan, so
// the band is not carried. Also proves the carry rule does not invent a band on a clean ledger.
static void case_band_not_carried_below_or_absent(void)
{
    BandFx f; bandFxInit(&f, /*withChain=*/0);
    _BRPeerManagerReinitCFLedgerLocked(f.m, BAND_START);   // a rescan from the band's low edge
    uint32_t below = f.m->cfLedger.abandonedBelow;
    BRCFScanLedgerInit(&f.m->cfLedger, BAND_START);
    _BRPeerManagerReinitCFLedgerLocked(f.m, BAND_FLOOR);   // nothing surfaced: nothing to carry
    uint32_t clean = f.m->cfLedger.abandonedBelow;
    MGR_UNLOCK(f.m);
    check(below == 0, "a re-Init below the band clears it (those heights are scanned again)");
    check(clean == 0, "a re-Init of a ledger with no band records none");
    bandFxFree(&f);
}

// ===================================================================================== (c) ====

typedef struct { BRWallet *w; BRPeerManager *m; BRPeer *p; BRPeerCallbackInfo info; uint32_t cpH, chainStart, tip; } ParkFx;

// A filter-header chain from chainStart to tip that spans the top trusted checkpoint, the re-anchor
// budget exhausted, the ledger armed at chainStart with [chainStart .. requestedThrough] requested
// and evaluated, and the fetch cursor at requestedThrough.
static void parkFxInit(ParkFx *f, uint32_t belowCp, uint32_t aboveCp, uint32_t evaluated)
{
    memset(f, 0, sizeof(*f));
    f->w = makeWallet();
    f->m = BRPeerManagerNew(&BRMainNetParams, f->w, 0, NULL, 0, NULL, 0);
    BRPeerManager *m = f->m;
    BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY);
    BRMerkleBlock *floorBlock = mkBlock(24000000u, u256_fill(0xE0), UINT256_ZERO, 1700000000u);
    BRSetAdd(m->blocks, floorBlock);
    m->lastBlock = floorBlock;

    f->cpH = BRMainNetCFCheckpoints[BRMainNetCFCheckpointsCount - 1].height;
    f->chainStart = f->cpH - belowCp;
    UInt256 anchor = u256_fill(0xA7);
    m->compactFilterChain = BRCompactFilterChainNew(FILTER_TYPE_BASIC, f->chainStart, anchor);
    size_t n = belowCp + aboveCp;
    UInt256 *hdrs = calloc(n, sizeof(UInt256));
    for (size_t i = 0; i < n; i++) hdrs[i] = u256_tag((uint32_t)i, 3);
    check(BRCompactFilterChainAppend(m->compactFilterChain, anchor, hdrs, n) == 1, "setup: the filter-header chain is built");
    free(hdrs);
    f->tip = BRCompactFilterChainNextHeight(m->compactFilterChain) - 1;
    check(BRCFHighestCheckpointAtOrBelow(f->tip) && BRCFHighestCheckpointAtOrBelow(f->tip)->height == f->cpH,
          "setup: the top trusted checkpoint lies inside the chain");

    m->autoFetchCFiltersEnabled = 1;
    m->autoFetchCFiltersStart   = f->chainStart;
    BRCFScanLedgerInit(&m->cfLedger, f->chainStart);
    uint32_t last = f->chainStart + evaluated - 1;
    BRCFScanLedgerRecordRequested(&m->cfLedger, f->chainStart, last, UINT128_ZERO, 0, 1);
    for (uint32_t h = f->chainStart; h <= last; h++) BRCFScanLedgerMarkEvaluated(&m->cfLedger, h);
    m->autoFetchCFiltersThrough = last;
    check(m->cfLedger.requestedThrough == last && BRCFScanLedgerScannedThrough(&m->cfLedger) == last,
          "setup: the ledger has requested and evaluated exactly the fetched range");

    m->cfReanchorCount = CF_CONTINUITY_REANCHOR_MAX;   // the budget is spent within this failure run
    f->p = addConnectedFilterPeer(m, 0x81, 12081);
    f->info = (BRPeerCallbackInfo){ .peer = f->p, .manager = m, .hash = UINT256_ZERO };
}

static void parkFxFree(ParkFx *f)
{
    BRPeerManagerFree(f->m);
    BRWalletFree(f->w);
}

// One continuity mismatch from a lone peer: the never-brick park runs, nothing is corroborated.
static void parkMismatch(ParkFx *f)
{
    UInt256 batch[3] = { u256_fill(0x91), u256_fill(0x92), u256_fill(0x93) };
    _peerRelayedCFHeaders(&f->info, FILTER_TYPE_BASIC, u256_fill(0x77), u256_fill(0xFF), batch, 3);
}

// What every forward-fetch site resumes from.
static uint32_t resumeAt(BRPeerManager *m)
{
    uint32_t s = m->autoFetchCFiltersThrough + 1;
    return (s < m->autoFetchCFiltersStart) ? m->autoFetchCFiltersStart : s;
}

static void case_park_never_jumps_past_requested(void)
{
    ParkFx f; parkFxInit(&f, /*belowCp=*/1000, /*aboveCp=*/200, /*evaluated=*/10);
    uint32_t reqThrough = f.m->cfLedger.requestedThrough;
    parkMismatch(&f);
    check(f.m->cfReanchorCount == CF_CONTINUITY_REANCHOR_MAX && f.m->compactFilterChain != NULL,
          "the exhausted-budget path parked (no re-anchor, chain kept)");
    uint32_t r = resumeAt(f.m);
    check(r <= reqThrough + 1, "park: the fetch cursor never moves above requestedThrough + 1");

    // The next forward fetch, as both forward sites do it: request [resume .. resume+9] and evaluate it.
    uint32_t stop = r + 9;
    BRCFScanLedgerRecordRequested(&f.m->cfLedger, r, stop, UINT128_ZERO, 0, 2);
    for (uint32_t h = r; h <= stop; h++) BRCFScanLedgerMarkEvaluated(&f.m->cfLedger, h);
    uint32_t scanned = BRCFScanLedgerScannedThrough(&f.m->cfLedger);
    check(scanned == reqThrough + 10,
          "scannedThrough covers only heights that were requested and evaluated (no sail over a gap)");
    check(BRPeerManagerAbandonedBelow(f.m) == 0, "a lone disagreer still abandons nothing");
    parkFxFree(&f);
}

// A ledger that lags below the filter-header chain's start (requestedThrough + 1 < chainStart).
// Below chainStart no cfilter can be verified, so the cursor cannot resume at requestedThrough + 1;
// but the in-chain heights [chainStart .. cp-1] can be, so the park must stop at chainStart rather
// than jump to the checkpoint and carry scannedThrough over them unrequested.
static void case_park_lagging_stops_at_chain_start(void)
{
    ParkFx f; parkFxInit(&f, /*belowCp=*/1000, /*aboveCp=*/200, /*evaluated=*/10);
    BRPeerManager *m = f.m;
    uint32_t L = f.chainStart - 50;   // the ledger armed 50 below the chain's start
    BRCFScanLedgerInit(&m->cfLedger, L);
    BRCFScanLedgerRecordRequested(&m->cfLedger, L, L + 9, UINT128_ZERO, 0, 1);
    for (uint32_t h = L; h <= L + 9; h++) BRCFScanLedgerMarkEvaluated(&m->cfLedger, h);
    m->autoFetchCFiltersStart = L; m->autoFetchCFiltersThrough = L + 9;
    check(m->cfLedger.requestedThrough + 1 < f.chainStart, "setup: the ledger lags below the chain's start");
    parkMismatch(&f);
    check(m->cfReanchorCount == CF_CONTINUITY_REANCHOR_MAX && m->compactFilterChain != NULL,
          "the exhausted-budget path parked (no re-anchor, chain kept)");
    uint32_t r = resumeAt(m);
    check(r == f.chainStart, "lagging park: the fetch cursor resumes at the chain's start, not the checkpoint");

    uint32_t stop = r + 9;
    BRCFScanLedgerRecordRequested(&m->cfLedger, r, stop, UINT128_ZERO, 0, 2);
    for (uint32_t h = r; h <= stop; h++) BRCFScanLedgerMarkEvaluated(&m->cfLedger, h);
    check(BRCFScanLedgerScannedThrough(&m->cfLedger) <= stop && stop < f.cpH,
          "scannedThrough never covers the in-chain heights [chainStart .. cp-1] unrequested");
    check(BRPeerManagerAbandonedBelow(m) == 0, "a lone disagreer still abandons nothing");
    parkFxFree(&f);
}

// GUARD (every arm): a cursor already above the checkpoint still parks back AT the checkpoint.
static void case_park_still_moves_back(void)
{
    ParkFx f; parkFxInit(&f, /*belowCp=*/100, /*aboveCp=*/200, /*evaluated=*/250);
    parkMismatch(&f);
    check(f.m->autoFetchCFiltersStart == f.cpH && f.m->autoFetchCFiltersThrough == f.cpH - 1,
          "a cursor above the checkpoint is parked back at the trusted checkpoint");
    check(BRPeerManagerAbandonedBelow(f.m) == 0, "a lone disagreer abandons nothing");
    parkFxFree(&f);
}

// ===================================================================================== (d) ====

#define BACKFILL_TICKS 5u

static size_t getheadersSent(BRPeer *p) { return ((BRPeerContext *)p)->katGetheadersCount; }

// Runs BACKFILL_TICKS backfill steps (the lock must NOT be held) and returns the heights retired.
static uint32_t backfillTicks(BRPeerManager *m)
{
    uint32_t retired = 0;
    for (uint32_t i = 0; i < BACKFILL_TICKS; i++) retired += BRPeerManagerBackfillAbandonedBandStep(m);
    return retired;
}

// Makes headers resident for [BAND_FLOOR - n .. BAND_FLOOR - 1], linked under the fixture's bottom
// block, so the resident block floor drops to BAND_FLOOR - n.
static void residentBelowBand(BRPeerManager *m, uint32_t n)
{
    MGR_LOCK(m);
    UInt256 want = u256_tag(BAND_FLOOR - 1, 77);   // what the bottom block's prevBlock points at
    for (uint32_t i = 1; i <= n; i++) {
        uint32_t h = BAND_FLOOR - i;
        UInt256 prev = (i == n) ? u256_tag(h - 1, 78) : u256_tag(h - 1, 77);
        BRMerkleBlock *b = mkBlock(h, want, prev, 1700000000u - 15u * i);
        BRSetAdd(m->blocks, b);
        want = prev;
    }
    m->floorMemoValid = 0;
    check(_BRPeerManagerBlockFloor(m) == BAND_FLOOR - n, "setup: headers are resident below the band");
    MGR_UNLOCK(m);
}

// The three entry points that leave a band at or below the ledger's start. Each returns with the
// lock RELEASED and the ledger in the carried state.
enum { AT_START_SNAP, AT_START_REANCHOR, AT_START_INSIDE };

static void atStartFxInit(BandFx *f, int entry)
{
    bandFxInit(f, /*withChain=*/entry == AT_START_REANCHOR);   // lock HELD, band [BAND_START .. BAND_FLOOR-1]
    BRPeerManager *m = f->m;
    if (entry == AT_START_SNAP) {
        _BRPeerManagerRequestNextCFHeaders(m, f->p, /*isConvoyAdvance=*/0);
    } else if (entry == AT_START_REANCHOR) {
        check(_BRPeerManagerReanchorAtFloorLocked(m, /*force=*/1) == 1, "setup: the floor re-anchor ran");
    } else {
        _BRPeerManagerReinitCFLedgerLocked(m, BAND_FLOOR - 1000);   // strictly inside the band
    }
    uint32_t start = BRCFScanLedgerStartHeight(&m->cfLedger);
    uint32_t below = m->cfLedger.abandonedBelow;
    MGR_UNLOCK(m);
    check(below > 0 && below <= start, "setup: the band is carried at or below the ledger's start");
    ((BRPeerContext *)f->p)->katGetheadersCount = 0;
}

static void atStartIdle(int entry, const char *inv)
{
    BandFx f; atStartFxInit(&f, entry);
    uint32_t before = BRPeerManagerAbandonedBelow(f.m);
    uint32_t retired = backfillTicks(f.m);
    check(retired == 0 && BRPeerManagerAbandonedBelow(f.m) == before,
          "the carried band is not retired (its heights were never scanned)");
    printf("  info: %zu getheaders over %u backfill ticks\n", getheadersSent(f.p), BACKFILL_TICKS);
    check(getheadersSent(f.p) == 0, inv);
    bandFxFree(&f);
}

static void case_backfill_idle_after_floor_snap(void)
{
    atStartIdle(AT_START_SNAP,
                "floor snap: the backfill sends no getheaders for a band this ledger can never retire");
}

static void case_backfill_idle_after_floor_reanchor(void)
{
    atStartIdle(AT_START_REANCHOR,
                "floor re-anchor: the backfill sends no getheaders for a band this ledger can never retire");
}

static void case_backfill_idle_after_inside_reinit(void)
{
    atStartIdle(AT_START_INSIDE,
                "re-Init inside a band: the backfill sends no getheaders for a band this ledger can never retire");
}

// GUARD (every arm): with headers resident below a carried band, the band is still not retired.
// Those heights lie below the ledger's start and were never scanned; retiring them would tell the
// app they had been. The carried state is written directly (abandonedBelow == start, as the snap,
// the re-anchor and an inside re-Init leave it, and abandonedBelow < start, as the reorg rebuild can),
// so the guard holds in arms that compile the carry itself out.
static void case_backfill_never_retires_band_at_start(void)
{
    uint32_t bands[2] = { BAND_FLOOR, BAND_FLOOR - 1000 };
    for (int i = 0; i < 2; i++) {
        BandFx f; bandFxInit(&f, /*withChain=*/0);   // lock HELD
        BRCFScanLedgerInit(&f.m->cfLedger, BAND_FLOOR);
        f.m->cfLedger.abandonedBelow = bands[i];
        MGR_UNLOCK(f.m);
        residentBelowBand(f.m, 1500);
        uint32_t retired = backfillTicks(f.m);
        check(retired == 0 && BRPeerManagerAbandonedBelow(f.m) == bands[i],
              "a band at or below the ledger's start is never retired, even with headers resident below it");
        bandFxFree(&f);
    }
}

// GUARD (every arm): a band ABOVE the ledger's start is still backfilled (one getheaders per tick
// while no header helps) and retired down to the resident floor once headers arrive.
static void case_backfill_band_above_start_still_retires(void)
{
    BandFx f; bandFxInit(&f, /*withChain=*/0);
    uint32_t start = BRCFScanLedgerStartHeight(&f.m->cfLedger);
    MGR_UNLOCK(f.m);
    check(start == BAND_START && f.m->cfLedger.abandonedBelow == BAND_FLOOR, "setup: the band sits above the start");
    ((BRPeerContext *)f.p)->katGetheadersCount = 0;
    uint32_t retired = backfillTicks(f.m);
    check(retired == 0 && getheadersSent(f.p) == BACKFILL_TICKS,
          "a band above the start is chased with one getheaders per tick");
    residentBelowBand(f.m, 10);
    retired = BRPeerManagerBackfillAbandonedBandStep(f.m);
    check(retired == 10 && BRPeerManagerAbandonedBelow(f.m) == BAND_FLOOR - 10,
          "a band above the start is retired down to the resident floor, and no further");
    check(BRCFScanLedgerLowestNeededHeight(&f.m->cfLedger) == BAND_FLOOR - 10,
          "the retired heights are requested again (they are scanned, not assumed)");
    bandFxFree(&f);
}

// The ledger re-anchored just under the resident floor (start = BAND_FLOOR - 5) with a band
// [start .. BAND_FLOOR-1] surfaced on it. Returns with the lock RELEASED.
static void startBetweenFxInit(BandFx *f)
{
    bandFxInit(f, /*withChain=*/0);   // lock HELD
    BRPeerManager *m = f->m;
    BRCFScanLedgerInit(&m->cfLedger, BAND_FLOOR - 5);
    _BRPeerManagerSurfaceUnscannableLocked(m, BAND_FLOOR - 5, BAND_FLOOR, "kat: band above a mid-floor start");
    check(m->cfLedger.abandonedBelow == BAND_FLOOR && BRCFScanLedgerStartHeight(&m->cfLedger) == BAND_FLOOR - 5,
          "setup: a band [start .. BAND_FLOOR-1] sits just above the ledger's start");
    MGR_UNLOCK(m);
    ((BRPeerContext *)f->p)->katGetheadersCount = 0;
}

// RED (CF_RETIRE_FLOOR_CLAMP_UNFIXED): headers come back below the ledger's start (floor
// BAND_FLOOR - 10 < start). The band is retired down to the start, every height of it resident,
// and the backfill then idles; unclamped, Retire refused the sub-start floor on every tick.
static void case_backfill_retires_to_start_when_floor_below_start(void)
{
    BandFx f; startBetweenFxInit(&f);
    residentBelowBand(f.m, 10);
    uint32_t retired = backfillTicks(f.m);
    uint32_t start = BRCFScanLedgerStartHeight(&f.m->cfLedger);
    printf("  info: retired %u, %zu getheaders over %u backfill ticks\n", retired, getheadersSent(f.p), BACKFILL_TICKS);
    check(retired == 5 && BRPeerManagerAbandonedBelow(f.m) == start,
          "floor below start: the band is retired down to the ledger's start, never below it");
    check(BRCFScanLedgerLowestNeededHeight(&f.m->cfLedger) == start,
          "the retired heights are requested again from the start (scanned, not assumed)");
    check(getheadersSent(f.p) == 0, "once at the start, the backfill sends no getheaders");
    bandFxFree(&f);
}

// GUARD (every arm): headers resident only part-way down the band (floor BAND_FLOOR - 3, above the
// start). The band is retired exactly to that floor; the heights under it, whose headers are not
// resident, stay condemned.
static void case_backfill_retire_stops_at_resident_floor(void)
{
    BandFx f; startBetweenFxInit(&f);
    residentBelowBand(f.m, 3);
    uint32_t retired = BRPeerManagerBackfillAbandonedBandStep(f.m);
    check(retired == 3 && BRPeerManagerAbandonedBelow(f.m) == BAND_FLOOR - 3,
          "a band is retired only down to the resident header floor, not to the start");
    bandFxFree(&f);
}

typedef struct { const char *name; void (*fn)(void); } Case;
static const Case CASES[] = {
    { "floor_memo_relayed_stub_replace",       case_floor_memo_relayed_stub_replace },
    { "floor_memo_install_saved_stub_replace", case_floor_memo_install_saved_stub_replace },
    { "band_survives_cfheaders_floor_snap",    case_band_survives_cfheaders_floor_snap },
    { "band_survives_floor_reanchor",          case_band_survives_floor_reanchor },
    { "band_inside_reinit_keeps_lower_part",   case_band_inside_reinit_keeps_lower_part },
    { "band_not_carried_below_or_absent",      case_band_not_carried_below_or_absent },
    { "park_never_jumps_past_requested",       case_park_never_jumps_past_requested },
    { "park_lagging_stops_at_chain_start",     case_park_lagging_stops_at_chain_start },
    { "park_still_moves_back",                 case_park_still_moves_back },
    { "backfill_idle_after_floor_snap",        case_backfill_idle_after_floor_snap },
    { "backfill_idle_after_floor_reanchor",    case_backfill_idle_after_floor_reanchor },
    { "backfill_idle_after_inside_reinit",     case_backfill_idle_after_inside_reinit },
    { "backfill_never_retires_band_at_start",  case_backfill_never_retires_band_at_start },
    { "backfill_band_above_start_still_retires", case_backfill_band_above_start_still_retires },
    { "backfill_retires_to_start_when_floor_below_start", case_backfill_retires_to_start_when_floor_below_start },
    { "backfill_retire_stops_at_resident_floor", case_backfill_retire_stops_at_resident_floor },
};

int main(int argc, char **argv)
{
    if (argc < 2) {
        for (size_t i = 0; i < sizeof(CASES) / sizeof(CASES[0]); i++) printf("%s\n", CASES[i].name);
        return 2;
    }
    for (size_t i = 0; i < sizeof(CASES) / sizeof(CASES[0]); i++) {
        if (strcmp(argv[1], CASES[i].name) != 0) continue;
        printf("case %s\n", CASES[i].name);
        CASES[i].fn();
        printf("RESULT %s %s\n", CASES[i].name, g_fail ? "FAIL" : "PASS");
        return g_fail ? 1 : 0;
    }
    printf("unknown case %s\n", argv[1]);
    return 2;
}
