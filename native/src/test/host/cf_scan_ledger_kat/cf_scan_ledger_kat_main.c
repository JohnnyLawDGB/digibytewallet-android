// Host KAT for BRCFScanLedger — the pure per-height compact-filter scan
// completeness ledger (docs/superpowers/specs/2026-07-25-cf-scan-ledger-design.md,
// §9 cases 1-5). The module is pure (only BRInt.h, no pthread/sockets/locks),
// so it links standalone alongside BRCFScanLedger.c with `clang -w -I $CORE_DIR`
// — same idiom as cf_peer_status_kat.
//
// Cases:
//   1. Cursor does NOT advance past an unevaluated (dropped) height.
//   2. Header-race height is requeued (flagged), not dropped; NextRerequest offers it.
//   3. Peer-disconnect re-arms in-flight heights (peer cleared, offered again, not to A).
//   4. Attempt cap holds → height moves to gaveUp (reported), scannedThrough still refuses it.
//   5. Serialize -> Parse round-trips byte-identically (persisted fields survive;
//      attempts/timestamps/peer reset; pending dropped).
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "BRCFScanLedger.h"

static int g_failures = 0;
static void check(int cond, const char *desc) {
    printf("%s: %s\n", cond ? "PASS" : "FAIL", desc);
    if (! cond) g_failures++;
}

static UInt128 peerAddr(uint8_t tag) {
    UInt128 p = UINT128_ZERO;
    p.u8[15] = tag;   // distinct, non-zero peer id
    return p;
}

// ---- Case 1: cursor stops at a hole -----------------------------------------
static void case1_cursor_holds_at_hole(BRCFScanLedger *l) {
    UInt128 A = peerAddr(1);
    BRCFScanLedgerInit(l, 100);
    BRCFScanLedgerRecordRequested(l, 100, 110, A, 12024, 1000);
    check(BRCFScanLedgerOutstandingCount(l) == 11, "case1: [100..110] -> 11 outstanding");

    // Evaluate everything except 105 (dropped/unevaluated).
    for (uint32_t h = 100; h <= 110; h++) {
        if (h == 105) continue;
        BRCFScanLedgerMarkEvaluated(l, h);
    }
    check(BRCFScanLedgerScannedThrough(l) == 104, "case1: scannedThrough stops at 104 (not 110)");
    check(BRCFScanLedgerOutstandingCount(l) == 1, "case1: exactly the 1 dropped height outstanding");

    // Hole report coalesces to a single [105..105] range.
    uint32_t starts[8], ends[8];
    size_t n = BRCFScanLedgerHoleRanges(l, starts, ends, 8);
    check(n == 1 && starts[0] == 105 && ends[0] == 105, "case1: HoleRanges -> [105..105]");

    // Filling the hole lets the cursor jump to the top.
    BRCFScanLedgerMarkEvaluated(l, 105);
    check(BRCFScanLedgerScannedThrough(l) == 110, "case1: filling 105 advances scannedThrough to 110");
    check(BRCFScanLedgerOutstandingCount(l) == 0, "case1: no holes remain");
}

// ---- Case 2: header-race requeued, not dropped ------------------------------
static void case2_header_race_requeued(BRCFScanLedger *l) {
    UInt128 A = peerAddr(1);
    BRCFScanLedgerInit(l, 200);
    BRCFScanLedgerRecordRequested(l, 200, 200, A, 12024, 1000);
    BRCFScanLedgerMarkHeaderRace(l, 200);

    check(BRCFScanLedgerOutstandingCount(l) == 1, "case2: header-race height stays outstanding");
    check(l->outstanding[0].height == 200 && l->outstanding[0].headerRace == 1,
          "case2: height 200 flagged headerRace");
    check(BRCFScanLedgerScannedThrough(l) == 199, "case2: scannedThrough unchanged (199)");

    // Header-race first retry fires at CF_REREQ_HEADERRACE_SECS (10s), not 30s.
    uint32_t out = 0;
    check(BRCFScanLedgerNextRerequest(l, 1000 + 9, &out) == 0, "case2: not offered before 10s");
    check(BRCFScanLedgerNextRerequest(l, 1000 + CF_REREQ_HEADERRACE_SECS, &out) == 1 && out == 200,
          "case2: NextRerequest offers 200 at the 10s header-race delay");
    check(l->outstanding[0].attempts == 1, "case2: offering bumped attempts to 1");
}

// ---- Case 3: peer disconnect re-arms in-flight heights ----------------------
static void case3_peer_disconnect_rearms(BRCFScanLedger *l) {
    UInt128 A = peerAddr(1);
    UInt128 B = peerAddr(2);
    (void)B;
    BRCFScanLedgerInit(l, 300);
    BRCFScanLedgerRecordRequested(l, 300, 305, A, 12024, 1000);
    check(BRCFScanLedgerOutstandingCount(l) == 6, "case3: [300..305] -> 6 outstanding to peer A");

    BRCFScanLedgerReArmPeer(l, A, 12024);

    int allCleared = 1, allAttemptsZero = 1;
    for (size_t i = 0; i < l->outstandingCount; i++) {
        if (! UInt128IsZero(l->outstanding[i].peer)) allCleared = 0;
        if (l->outstanding[i].attempts != 0) allAttemptsZero = 0;
    }
    check(BRCFScanLedgerOutstandingCount(l) == 6, "case3: heights kept after re-arm");
    check(allCleared, "case3: every re-armed height had its peer cleared");
    check(allAttemptsZero, "case3: attempts left unchanged (still 0)");

    // A subsequent re-request offers them and does NOT target A (peer is cleared).
    uint32_t out = 0;
    int offered = BRCFScanLedgerNextRerequest(l, 1000 + CF_REREQ_BASE_SECS, &out);
    check(offered == 1 && out == 300, "case3: NextRerequest offers the lowest re-armed height (300)");
    // The offered entry must not carry peer A.
    int offeredEntryNotA = 1;
    for (size_t i = 0; i < l->outstandingCount; i++) {
        if (l->outstanding[i].height == out && UInt128Eq(l->outstanding[i].peer, A)) offeredEntryNotA = 0;
    }
    check(offeredEntryNotA, "case3: offered height is not targeted at the disconnected peer A");
}

// ---- Case 4: attempt cap -> gaveUp, cursor still refuses ---------------------
static void case4_attempt_cap_gaveup(BRCFScanLedger *l) {
    UInt128 A = peerAddr(1);
    const uint32_t H = 400;
    BRCFScanLedgerInit(l, H);
    BRCFScanLedgerRecordRequested(l, H, H, A, 12024, 1000);

    uint32_t now = 1000, out = 0;
    for (int i = 0; i < CF_REREQ_MAX_ATTEMPTS; i++) {
        now += 1000;  // well past any backoff delay
        int offered = BRCFScanLedgerNextRerequest(l, now, &out);
        check(offered == 1 && out == H, "case4: height offered while under the attempt cap");
    }
    // Cap reached: no longer offered; it becomes a reported permanent hole.
    now += 1000;
    check(BRCFScanLedgerNextRerequest(l, now, &out) == 0, "case4: not offered after CF_REREQ_MAX_ATTEMPTS");
    check(BRCFScanLedgerGaveUpCount(l) == 1 && l->gaveUp[0] == H, "case4: height moved to gaveUp (not dropped)");
    check(BRCFScanLedgerOutstandingCount(l) == 0, "case4: no longer in outstanding");
    check(BRCFScanLedgerScannedThrough(l) < H, "case4: scannedThrough still refuses to pass the gaveUp height");

    // gaveUp heights are still reported as holes.
    uint32_t starts[8], ends[8];
    size_t n = BRCFScanLedgerHoleRanges(l, starts, ends, 8);
    check(n == 1 && starts[0] == H && ends[0] == H, "case4: gaveUp height still shows in HoleRanges");
}

// ---- Case 5: Serialize -> Parse byte-identical round-trip --------------------
static void case5_serialize_roundtrip(BRCFScanLedger *l1, BRCFScanLedger *l2) {
    UInt128 A = peerAddr(1);
    BRCFScanLedgerInit(l1, 500);
    // A spread of state: evaluated run, a header-race hole, a gaveUp height, a pending entry.
    BRCFScanLedgerRecordRequested(l1, 500, 520, A, 12024, 2000);
    for (uint32_t h = 500; h <= 509; h++) BRCFScanLedgerMarkEvaluated(l1, h);   // scannedThrough -> 509
    BRCFScanLedgerMarkHeaderRace(l1, 512);                                       // header-race hole
    // Drive the lowest outstanding hole (510) to gaveUp — NextRerequest always
    // offers the lowest eligible height, so 510 exhausts its attempts first.
    uint32_t now = 3000, out = 0;
    for (int i = 0; i < CF_REREQ_MAX_ATTEMPTS; i++) { now += 1000; BRCFScanLedgerNextRerequest(l1, now, &out); }
    now += 1000; BRCFScanLedgerNextRerequest(l1, now, &out);                     // retire 510 -> gaveUp
    check(BRCFScanLedgerGaveUpCount(l1) == 1 && l1->gaveUp[0] == 510, "case5: 510 in gaveUp pre-serialize");
    // A pending-confirm entry (NOT persisted — must be dropped by the round-trip).
    UInt256 bh = UINT256_ZERO; bh.u8[0] = 0xAB;
    UInt256 tx = UINT256_ZERO; tx.u8[0] = 0xCD;
    BRCFScanLedgerRecordPending(l1, bh, &tx, 1, 4000);
    check(l1->pendingCount == 1, "case5: pending recorded pre-serialize");

    uint8_t buf[16384];
    size_t need = BRCFScanLedgerSerialize(l1, buf, sizeof(buf));
    check(need > 0 && need <= sizeof(buf), "case5: Serialize produced a bounded blob");

    check(BRCFScanLedgerParse(l2, buf, need) == 1, "case5: Parse succeeds");

    // Persisted fields survived.
    check(l2->start == l1->start, "case5: start survived");
    check(l2->scannedThrough == l1->scannedThrough, "case5: scannedThrough survived");
    check(l2->requestedThrough == l1->requestedThrough, "case5: requestedThrough survived");
    check(l2->outstandingCount == l1->outstandingCount, "case5: outstanding count survived");
    int heightsMatch = 1, raceMatch = 1, resetsOk = 1;
    for (size_t i = 0; i < l2->outstandingCount; i++) {
        if (l2->outstanding[i].height != l1->outstanding[i].height) heightsMatch = 0;
        if (l2->outstanding[i].headerRace != l1->outstanding[i].headerRace) raceMatch = 0;
        // attempts/timestamps/peer reset on parse (§5).
        if (l2->outstanding[i].attempts != 0) resetsOk = 0;
        if (l2->outstanding[i].requestedAt != 0) resetsOk = 0;
        if (! UInt128IsZero(l2->outstanding[i].peer)) resetsOk = 0;
    }
    check(heightsMatch, "case5: outstanding heights survived");
    check(raceMatch, "case5: headerRace flags survived (512 still flagged)");
    check(resetsOk, "case5: attempts/timestamps/peer reset to zero on parse");
    check(l2->gaveUpCount == 1 && l2->gaveUp[0] == 510, "case5: gaveUp list survived");
    check(l2->pendingCount == 0, "case5: pending NOT persisted (dropped on parse)");

    // Byte-identical: re-serialize the parsed ledger and compare.
    uint8_t buf2[16384];
    size_t need2 = BRCFScanLedgerSerialize(l2, buf2, sizeof(buf2));
    check(need2 == need && memcmp(buf, buf2, need) == 0, "case5: Serialize->Parse->Serialize is byte-identical");
}

// ---- Phase 2 Task 1: overflow drop is never silent; requestedThrough advances ----

#define ASSERT(cond) check((cond), #cond)

static int test_overflow_reports_drop(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 1);
    UInt128 p = UINT128_ZERO; p.u16[5]=0xffff; p.u32[3]=0x01020304;
    BRCFScanLedgerRecordRequested(&l, 1000, 1000 + CF_OUTSTANDING_MAX - 1, p, 12024, 100); // fills [1000..5095]
    ASSERT(BRCFScanLedgerOutstandingCount(&l) == CF_OUTSTANDING_MAX);
    uint32_t lo=0, hi=0;
    int dropped = BRCFScanLedgerRecordRequestedDropped(&l, 9000, 9000, p, 12024, 200, &lo, &hi); // 9000 > 5095 → evicts oldest
    ASSERT(dropped == 1 && lo == 1000 && hi == 1000);
    ASSERT(BRCFScanLedgerOutstandingCount(&l) == CF_OUTSTANDING_MAX);
    return 1;
}
// TRIPWIRE for the system invariant: requestedThrough (scannedThrough's ceiling) must advance.
static int test_record_advances_requestedThrough(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 100);
    UInt128 p = UINT128_ZERO; p.u16[5]=0xffff; p.u32[3]=0x01020304;
    BRCFScanLedgerRecordRequested(&l, 100, 150, p, 12024, 0);
    for (uint32_t h = 100; h <= 150; h++) BRCFScanLedgerMarkEvaluated(&l, h);
    ASSERT(BRCFScanLedgerScannedThrough(&l) == 150);
    return 1;
}

// ---- Phase 2 Task 2: byte-budgeted filter-byte buffer (header-race hold) ----
// harness stubs. evalFn signature MATCHES the interface: (ctx, height, blockHash, bytes, len).
static uint32_t g_evalHeights[64]; static int g_evalN; static int g_evalRet = 1;   // g_evalRet controls remove(1)/keep(0)
static int stub_eval(void *ctx, uint32_t h, UInt256 bh, const uint8_t *b, size_t n){
    (void)ctx;(void)bh;(void)b;(void)n; g_evalHeights[g_evalN++]=h; return g_evalRet; }
struct conn { UInt256 hash; uint32_t height; int ready; };
static int stub_isready(void *ctx, UInt256 hash, uint32_t *outH){          // the isReady callback
    struct conn *c = (struct conn*)ctx;
    for (int i=0;i<4;i++) if (c[i].ready && UInt256Eq(c[i].hash, hash)) { *outH=c[i].height; return 1; } return 0; }

static int test_buffer_store_take_drain(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 1); g_evalRet=1;
    UInt256 h1={.u8={1}}, h2={.u8={2}}, h3={.u8={3}};
    uint8_t f1[]={0xAA,0xBB}, f2[]={0xCC}, f3[]={0xDD,0xEE,0xFF};
    ASSERT(BRCFScanLedgerBufferFilter(&l,h1,f1,2,10)==1);
    ASSERT(BRCFScanLedgerBufferFilter(&l,h2,f2,1,11)==1);
    ASSERT(BRCFScanLedgerBufferFilter(&l,h3,f3,3,12)==1);
    ASSERT(BRCFScanLedgerBufferedCount(&l)==3);
    struct conn cs[4] = { {h1,100,1}, {h2,101,0}, {h3,102,1}, {0} };        // only h1,h3 ready
    uint8_t scratch[64]; g_evalN=0;
    size_t removed = BRCFScanLedgerDrainConnected(&l, stub_isready, cs, scratch, sizeof scratch, stub_eval, 8);
    ASSERT(removed==2 && g_evalN==2);                  // h1,h3 evaluated+removed; h2 stays (not ready)
    ASSERT(BRCFScanLedgerBufferedCount(&l)==1);
    BRCFScanLedgerFree(&l);                             // LSan: free the malloc'd remainder
    return 1;
}
// evalFn returning 0 (a HIT with no peer) must KEEP the entry, not remove it.
static int test_buffer_drain_keeps_on_eval_zero(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 1);
    UInt256 h1={.u8={7}}; uint8_t f[]={9,9}; BRCFScanLedgerBufferFilter(&l,h1,f,2,0);
    struct conn cs[4]={{h1,100,1},{0},{0},{0}}; uint8_t sc[8]; g_evalN=0; g_evalRet=0;   // eval returns 0 → keep
    size_t removed = BRCFScanLedgerDrainConnected(&l, stub_isready, cs, sc, sizeof sc, stub_eval, 8);
    ASSERT(removed==0 && g_evalN==1 && BRCFScanLedgerBufferedCount(&l)==1);  // ran but kept
    g_evalRet=1; BRCFScanLedgerFree(&l);
    return 1;
}
static int test_buffer_bytebudget_evicts_oldest(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 1); g_evalRet=1;
    // Fill past the byte budget; assert bytes never exceed the cap, the OLDEST is gone, and a NEWEST survives.
    uint8_t big[512]; memset(big,7,sizeof big);
    UInt256 first={.u8={0xF0}}; BRCFScanLedgerBufferFilter(&l, first, big, sizeof big, 0);
    UInt256 newest = first; int i;
    for (i=1; ; i++) {
        UInt256 h={.u8={(uint8_t)i,(uint8_t)(i>>8),0xAB}}; BRCFScanLedgerBufferFilter(&l,h,big,sizeof big,i);
        newest = h;
        if (BRCFScanLedgerBufferedBytes(&l) + sizeof(big) > CF_FILTER_BUFFER_MAX_BYTES && i > 4) break; // filled past cap
        if (i > (int)(CF_FILTER_BUFFER_MAX_BYTES/sizeof(big)) + 8) break;                                // safety
    }
    ASSERT(BRCFScanLedgerBufferedBytes(&l) <= CF_FILTER_BUFFER_MAX_BYTES);
    struct conn cs[4] = { {first,1,1}, {newest,2,1}, {0}, {0} }; uint8_t sc[512]; g_evalN=0;
    BRCFScanLedgerDrainConnected(&l, stub_isready, cs, sc, sizeof sc, stub_eval, 1000);
    // the OLDEST (first) was evicted → never drained; the NEWEST survived → drained. Distinguishes evict-oldest from evict-all.
    int drainedFirst=0, drainedNewest=0;
    for (int k=0;k<g_evalN;k++){ if (g_evalHeights[k]==1) drainedFirst=1; if (g_evalHeights[k]==2) drainedNewest=1; }
    ASSERT(drainedFirst==0 && drainedNewest==1);
    BRCFScanLedgerFree(&l);
    return 1;
}
static int test_buffer_clear(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 1);
    UInt256 h1={.u8={1}}; uint8_t f[]={1,2,3};
    BRCFScanLedgerBufferFilter(&l,h1,f,3,0);
    BRCFScanLedgerClearFilterBuffer(&l);
    ASSERT(BRCFScanLedgerBufferedCount(&l)==0 && BRCFScanLedgerBufferedBytes(&l)==0);
    BRCFScanLedgerFree(&l);                             // no-op after clear, but proves Free is safe on empty
    return 1;
}

// ---- Phase 2 Task 3: residual re-request driver (peek/commit + retire) -----

static int test_peek_coalesces_and_no_bump(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 1);
    UInt128 pa=UINT128_ZERO; pa.u16[5]=0xffff; pa.u32[3]=0x0A000202;
    UInt128 pb=UINT128_ZERO; pb.u16[5]=0xffff; pb.u32[3]=0x0A000203;
    BRCFScanLedgerRecordRequested(&l, 100, 102, pa, 12024, 0);
    BRCFScanLedgerRecordRequested(&l, 103, 103, pb, 12024, 0);   // different peer → splits
    uint32_t s=0,e=0;
    ASSERT(BRCFScanLedgerPeekRerequestRange(&l, CF_REREQ_BASE_SECS, 0, &s, &e)==1 && s==100 && e==102);
    ASSERT(BRCFScanLedgerPeekRerequestRange(&l, CF_REREQ_BASE_SECS, 0, &s, &e)==1 && s==100 && e==102); // no mutation
    return 1;
}
static int test_peek_gap_splits(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 1);
    UInt128 pa=UINT128_ZERO; pa.u16[5]=0xffff; pa.u32[3]=0x0A000202;
    BRCFScanLedgerRecordRequested(&l, 200, 200, pa, 12024, 0);
    BRCFScanLedgerRecordRequested(&l, 202, 202, pa, 12024, 0);   // gap at 201
    uint32_t s=0,e=0;
    ASSERT(BRCFScanLedgerPeekRerequestRange(&l, CF_REREQ_BASE_SECS, 0, &s, &e)==1 && s==200 && e==200);
    return 1;
}
static int test_peek_minheight_skips_below(void) {                  // the livelock guard
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 1);
    UInt128 pa=UINT128_ZERO; pa.u16[5]=0xffff; pa.u32[3]=0x0A000202;
    BRCFScanLedgerRecordRequested(&l, 100, 101, pa, 12024, 0);   // "below floor"
    BRCFScanLedgerRecordRequested(&l, 200, 201, pa, 12024, 0);   // "in window"
    uint32_t s=0,e=0;
    ASSERT(BRCFScanLedgerPeekRerequestRange(&l, CF_REREQ_BASE_SECS, 200, &s, &e)==1 && s==200 && e==201);
    ASSERT(BRCFScanLedgerPeekRerequestRange(&l, CF_REREQ_BASE_SECS, 101, &s, &e)==1 && s==101 && e==101); // straddle clamps
    return 1;
}
static int test_commit_bumps_only_range(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 1);
    UInt128 pa=UINT128_ZERO; pa.u16[5]=0xffff; pa.u32[3]=0x0A000202;
    UInt128 pb=UINT128_ZERO; pb.u16[5]=0xffff; pb.u32[3]=0x0A000203;
    BRCFScanLedgerRecordRequested(&l, 300, 302, pa, 12024, 0);
    uint32_t s=0,e=0; BRCFScanLedgerPeekRerequestRange(&l, CF_REREQ_BASE_SECS, 0, &s, &e); // 300..302
    BRCFScanLedgerCommitRerequest(&l, 300, 301, pb, 12024, CF_REREQ_BASE_SECS);            // sent only 300..301
    ASSERT(BRCFScanLedgerPeekRerequestRange(&l, CF_REREQ_BASE_SECS, 0, &s, &e)==1 && s==302 && e==302); // 302 still due
    return 1;
}
static int test_retire_caps_to_gaveup_and_holds_cursor(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 1);
    UInt128 pa=UINT128_ZERO; pa.u16[5]=0xffff; pa.u32[3]=0x0A000202;
    BRCFScanLedgerRecordRequested(&l, 400, 400, pa, 12024, 0);
    uint32_t s=0,e=0, t=0;
    for (int k=0;k<CF_REREQ_MAX_ATTEMPTS;k++){ t+=CF_REREQ_BACKOFF_CAP_SECS;
        BRCFScanLedgerRetireCapped(&l);                                   // once per "tick"
        if (BRCFScanLedgerPeekRerequestRange(&l,t,0,&s,&e)) BRCFScanLedgerCommitRerequest(&l,s,e,pa,12024,t); }
    BRCFScanLedgerRetireCapped(&l);                                       // capped entry → gaveUp
    ASSERT(BRCFScanLedgerPeekRerequestRange(&l, t+CF_REREQ_BACKOFF_CAP_SECS, 0, &s, &e)==0);
    ASSERT(BRCFScanLedgerGaveUpCount(&l)==1);
    ASSERT(BRCFScanLedgerScannedThrough(&l) < 400);
    return 1;
}

// ---- Task 3 (stale-buffer livelock fix): age-out byte-reclamation backstop --
// A pruned/orphaned-hash entry can sit in the filter buffer forever (a
// re-serving peer keeps re-buffering it, and BufferFilter's de-dup path resets
// `at` on every re-buffer). BRCFScanLedgerEvictAgedFilters is a pure
// byte-budget backstop, keyed off the IMMUTABLE `firstAt` (set once, on
// insert, never rejuvenated by a re-buffer) rather than the mutable `at`.

// Case A: age-out keys off firstAt (immutable), NOT at (reset on re-buffer).
static int test_ageout_keys_off_first_buffered(void) {
    UInt256 hash = {.u8={0x11}};
    uint8_t bytes[] = {1,2,3};

    // Part A: buffer at (now-901), then RE-BUFFER the same hash at `now` —
    // `at` resets to `now` but `firstAt` must stay pinned at (now-901). If
    // eviction were keyed off `at` this entry would look fresh and survive;
    // keyed off `firstAt` (901s old, past CF_FILTER_BUF_MAX_AGE_SECS=900) it
    // must be evicted.
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 1);
    uint32_t now = 10000;
    ASSERT(BRCFScanLedgerBufferFilter(&l, hash, bytes, sizeof(bytes), now - 901) == 1);
    ASSERT(BRCFScanLedgerBufferFilter(&l, hash, bytes, sizeof(bytes), now) == 1); // re-buffer (de-dup path)
    ASSERT(BRCFScanLedgerBufferedCount(&l) == 1);

    BRCFScanLedgerEvictAgedFilters(&l, now);

    ASSERT(BRCFScanLedgerBufferedCount(&l) == 0);
    ASSERT(BRCFScanLedgerBufferedBytes(&l) == 0);
    BRCFScanLedgerFree(&l);

    // Part B: a FRESH entry (firstAt == now, never re-buffered) at the same
    // `now` must NOT be evicted — proves the age check isn't just "evict
    // everything".
    BRCFScanLedger l2; BRCFScanLedgerInit(&l2, 1);
    ASSERT(BRCFScanLedgerBufferFilter(&l2, hash, bytes, sizeof(bytes), now) == 1);
    BRCFScanLedgerEvictAgedFilters(&l2, now);
    ASSERT(BRCFScanLedgerBufferedCount(&l2) == 1);
    BRCFScanLedgerFree(&l2);

    // Part C: exact boundary — firstAt == now - CF_FILTER_BUF_MAX_AGE_SECS
    // (age == 900) must NOT be evicted; the check is strictly `age > MAX`, so
    // only 901+ evicts. Pins the off-by-one that the whole firstAt clock rests on.
    BRCFScanLedger l3; BRCFScanLedgerInit(&l3, 1);
    ASSERT(BRCFScanLedgerBufferFilter(&l3, hash, bytes, sizeof(bytes), now - CF_FILTER_BUF_MAX_AGE_SECS) == 1);
    BRCFScanLedgerEvictAgedFilters(&l3, now);
    ASSERT(BRCFScanLedgerBufferedCount(&l3) == 1); // age == 900 == MAX, not > MAX → kept
    BRCFScanLedgerFree(&l3);
    return 1;
}

// Case B: the silent-loss invariant — EvictAgedFilters touches ONLY the
// filter-byte buffer. outstanding/scannedThrough/requestedThrough/gaveUp must
// come out byte-for-byte identical, and it must never call MarkEvaluated
// (which would fabricate a "no cfilter needed here" hole).
static int test_ageout_leaves_ledger_scan_state_byte_identical(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 100);
    UInt128 pa = UINT128_ZERO; pa.u16[5]=0xffff; pa.u32[3]=0x0A000202;
    BRCFScanLedgerRecordRequested(&l, 100, 110, pa, 12024, 0);          // 11 outstanding
    for (uint32_t h = 100; h <= 104; h++) BRCFScanLedgerMarkEvaluated(&l, h); // scannedThrough -> 104

    // Drive the lowest remaining outstanding height (105) to gaveUp via the
    // attempt cap, so the snapshot has a MIXED outstanding+gaveUp set.
    uint32_t now = 1000, out = 0;
    for (int i = 0; i < CF_REREQ_MAX_ATTEMPTS; i++) { now += 1000; BRCFScanLedgerNextRerequest(&l, now, &out); }
    now += 1000; BRCFScanLedgerNextRerequest(&l, now, &out);            // retires 105 -> gaveUp
    ASSERT(BRCFScanLedgerGaveUpCount(&l) == 1 && l.gaveUp[0] == 105);
    ASSERT(BRCFScanLedgerOutstandingCount(&l) == 5);                    // 106..110

    // Snapshot every scan-state field BEFORE touching the filter buffer.
    uint32_t snapScanned    = l.scannedThrough;
    uint32_t snapRequested  = l.requestedThrough;
    size_t   snapOutCount   = l.outstandingCount;
    BRCFOutstanding snapOut[CF_OUTSTANDING_MAX];
    memcpy(snapOut, l.outstanding, snapOutCount * sizeof(BRCFOutstanding));
    size_t   snapGaveUpCount = l.gaveUpCount;
    uint32_t snapGaveUp[CF_GAVEUP_MAX];
    memcpy(snapGaveUp, l.gaveUp, snapGaveUpCount * sizeof(uint32_t));

    // Buffer several entries, all aged past CF_FILTER_BUF_MAX_AGE_SECS.
    uint8_t bytes[] = {1,2,3,4};
    for (int i = 0; i < 5; i++) {
        UInt256 h = {.u8={(uint8_t)(0x50+i)}};
        ASSERT(BRCFScanLedgerBufferFilter(&l, h, bytes, sizeof(bytes), now - CF_FILTER_BUF_MAX_AGE_SECS - 1) == 1);
    }
    ASSERT(BRCFScanLedgerBufferedCount(&l) == 5);

    BRCFScanLedgerEvictAgedFilters(&l, now);

    // Only the filter-buffer fields changed.
    ASSERT(BRCFScanLedgerBufferedCount(&l) == 0);
    ASSERT(BRCFScanLedgerBufferedBytes(&l) == 0);

    // Everything else is byte-for-byte identical to the pre-evict snapshot.
    ASSERT(l.scannedThrough == snapScanned);
    ASSERT(l.requestedThrough == snapRequested);
    ASSERT(l.outstandingCount == snapOutCount);
    ASSERT(memcmp(l.outstanding, snapOut, snapOutCount * sizeof(BRCFOutstanding)) == 0);
    ASSERT(l.gaveUpCount == snapGaveUpCount);
    ASSERT(memcmp(l.gaveUp, snapGaveUp, snapGaveUpCount * sizeof(uint32_t)) == 0);

    BRCFScanLedgerFree(&l);
    return 1;
}

int main(void) {
    // Heap-allocate (the struct is large: outstanding[] + pending[]).
    BRCFScanLedger *l  = calloc(1, sizeof(*l));
    BRCFScanLedger *l2 = calloc(1, sizeof(*l2));
    if (! l || ! l2) { printf("FAIL: allocation\n"); return 1; }

    case1_cursor_holds_at_hole(l);
    case2_header_race_requeued(l);
    case3_peer_disconnect_rearms(l);
    case4_attempt_cap_gaveup(l);
    case5_serialize_roundtrip(l, l2);

    free(l);
    free(l2);

    test_overflow_reports_drop();
    test_record_advances_requestedThrough();

    test_buffer_store_take_drain();
    test_buffer_drain_keeps_on_eval_zero();
    test_buffer_bytebudget_evicts_oldest();
    test_buffer_clear();

    test_peek_coalesces_and_no_bump();
    test_peek_gap_splits();
    test_peek_minheight_skips_below();
    test_commit_bumps_only_range();
    test_retire_caps_to_gaveup_and_holds_cursor();

    test_ageout_keys_off_first_buffered();
    test_ageout_leaves_ledger_scan_state_byte_identical();

    printf(g_failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", g_failures);
    return g_failures ? 1 : 0;
}
