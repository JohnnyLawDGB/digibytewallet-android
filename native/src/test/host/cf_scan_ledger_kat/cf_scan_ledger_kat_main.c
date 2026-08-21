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

// ---- Task 4: pure buffered-hash enumerator (reverse-map input) --------------
// BRCFScanLedgerBufferedHashes copies up to `cap` buffered blockHashes (FIFO
// order, oldest first) into out[] and returns the count written
// (min(filterBufCount, cap)). It is the pure, BRPeerManager-free input the
// residual re-request suppressor reverse-maps to heights via manager->blocks.
static int test_buffered_hashes_enumerates(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 1);

    // Empty buffer -> 0 written.
    UInt256 out[8];
    ASSERT(BRCFScanLedgerBufferedHashes(&l, out, 8) == 0);

    UInt256 h1 = {.u8={0xA1}}, h2 = {.u8={0xB2}}, h3 = {.u8={0xC3}};
    uint8_t f[] = {1, 2, 3};
    ASSERT(BRCFScanLedgerBufferFilter(&l, h1, f, sizeof f, 0) == 1);
    ASSERT(BRCFScanLedgerBufferFilter(&l, h2, f, sizeof f, 0) == 1);
    ASSERT(BRCFScanLedgerBufferFilter(&l, h3, f, sizeof f, 0) == 1);
    ASSERT(BRCFScanLedgerBufferedCount(&l) == 3);

    // Full copy: returns 3 and writes the 3 hashes in FIFO order.
    memset(out, 0, sizeof out);
    size_t n = BRCFScanLedgerBufferedHashes(&l, out, 8);
    ASSERT(n == 3);
    ASSERT(UInt256Eq(out[0], h1) && UInt256Eq(out[1], h2) && UInt256Eq(out[2], h3));

    // cap smaller than count truncates to cap and returns cap.
    memset(out, 0, sizeof out);
    ASSERT(BRCFScanLedgerBufferedHashes(&l, out, 2) == 2);
    ASSERT(UInt256Eq(out[0], h1) && UInt256Eq(out[1], h2));

    // cap 0 -> nothing written, returns 0.
    ASSERT(BRCFScanLedgerBufferedHashes(&l, out, 0) == 0);

    // NULL out -> 0 (the guard the caller relies on to never write through NULL).
    ASSERT(BRCFScanLedgerBufferedHashes(&l, NULL, 8) == 0);

    BRCFScanLedgerFree(&l);
    return 1;
}

// ---- Task 1 (CF retention scan-floor): LowestNeededHeight + abandonedBelow --
// hard floor + AbandonGaveUpBelow. Little-endian u32 helper for the hand-built
// pre-version (v1) persistence blob in the back-compat case.
static void putLE32(uint8_t *p, uint32_t v) {
    p[0]=(uint8_t)(v&0xff); p[1]=(uint8_t)((v>>8)&0xff);
    p[2]=(uint8_t)((v>>16)&0xff); p[3]=(uint8_t)((v>>24)&0xff);
}

// LowestNeededHeight = max(scannedThrough+1, abandonedBelow). gaveUp-INCLUSIVE by
// construction: _cfLedgerAdvance caps scannedThrough at min(outstanding[0],gaveUp[0])-1,
// so scannedThrough+1 already folds in the gaveUp hole — its header must be retained
// so _cfBufEval can eventually drain+credit it (excluding it silently loses a receive).
static int test_lowest_needed_height(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 100);   // scannedThrough=99
    UInt128 pa = UINT128_ZERO; pa.u8[15]=1;
    BRCFScanLedgerRecordRequested(&l, 100, 120, pa, 12024, 1000);
    for (uint32_t h=100; h<=104; h++) BRCFScanLedgerMarkEvaluated(&l, h);   // scannedThrough -> 104
    // Drive 105 (lowest outstanding) to gaveUp via the attempt cap.
    uint32_t now=1000, out=0;
    for (int i=0;i<CF_REREQ_MAX_ATTEMPTS;i++){ now+=1000; BRCFScanLedgerNextRerequest(&l, now, &out); }
    now+=1000; BRCFScanLedgerNextRerequest(&l, now, &out);                  // retire 105 -> gaveUp
    ASSERT(BRCFScanLedgerGaveUpCount(&l)==1 && l.gaveUp[0]==105);
    ASSERT(BRCFScanLedgerScannedThrough(&l)==104);                         // stops just below the gaveUp hole

    // gaveUp-INCLUSION: lowest-needed == scannedThrough+1 == 105 (the gaveUp hole).
    ASSERT(BRCFScanLedgerLowestNeededHeight(&l)==105);
    ASSERT(BRCFScanLedgerAbandonedBelow(&l)==0);

    // abandonedBelow above scannedThrough+1 → lowest-needed follows the hard floor.
    l.abandonedBelow = 200;
    ASSERT(BRCFScanLedgerLowestNeededHeight(&l)==200);
    ASSERT(BRCFScanLedgerAbandonedBelow(&l)==200);

    // abandonedBelow below scannedThrough+1 → scannedThrough+1 wins (max semantics).
    l.abandonedBelow = 50;
    ASSERT(BRCFScanLedgerLowestNeededHeight(&l)==105);
    return 1;
}

// AbandonGaveUpBelow: drops gaveUp<target (target=min(clamp, lowest-outstanding)),
// reporting count/range, and (Part 3b guard) advances the hard floor ONLY to cover
// the gaveUp actually dropped (highest-dropped+1), NEVER preemptively to target/clamp
// and NEVER past a still-retrying hole; monotonic; returns the new lowest-still-needed.
static int test_abandon_gaveup_below(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 100);   // scannedThrough=99
    UInt128 pa = UINT128_ZERO; pa.u8[15]=1;
    // A still-retrying outstanding hole O=500 (< clamp, sub-cap).
    BRCFScanLedgerRecordRequested(&l, 500, 500, pa, 12024, 1000);
    ASSERT(BRCFScanLedgerOutstandingCount(&l)==1 && l.outstanding[0].height==500);
    // gaveUp = {200, 300, 2000}: G1=200,G2=300 (< target=O=500) < 2000 (> clamp).
    // Both G1,G2 sit below O so abandoning them is covered by the floor.
    l.gaveUp[0]=200; l.gaveUp[1]=300; l.gaveUp[2]=2000; l.gaveUpCount=3;

    uint32_t cnt=999, lo=999, hi=999;
    uint32_t newFloor = BRCFScanLedgerAbandonGaveUpBelow(&l, 1000, &cnt, &lo, &hi);

    // G1,G2 dropped (< target=O=500), G3=2000 kept.
    ASSERT(BRCFScanLedgerGaveUpCount(&l)==1 && l.gaveUp[0]==2000);
    // count + [lo..hi] returned for the caller's WARN log.
    ASSERT(cnt==2 && lo==200 && hi==300);
    // GUARD: abandonedBelow advances ONLY to cover the highest dropped (300)+1 = 301
    // — NOT preemptively to target(O=500) or clamp(1000). Never past a still-retrying hole.
    ASSERT(BRCFScanLedgerAbandonedBelow(&l)==301);
    // O is UNTOUCHED — still a live outstanding hole.
    ASSERT(BRCFScanLedgerOutstandingCount(&l)==1 && l.outstanding[0].height==500);
    // returns the new lowest-still-needed height == abandonedBelow == 301.
    ASSERT(newFloor==301 && BRCFScanLedgerLowestNeededHeight(&l)==301);

    // Second call, higher clamp: O still outstanding & below clamp so target stays O;
    // 2000 sits ABOVE O so it is KEPT (drop bounded by target). NOTHING dropped this
    // call (k==0) → abandonedBelow STAYS at 301 (no preemptive raise to O/clamp).
    uint32_t nf2 = BRCFScanLedgerAbandonGaveUpBelow(&l, 5000, &cnt, &lo, &hi);
    ASSERT(BRCFScanLedgerGaveUpCount(&l)==1 && l.gaveUp[0]==2000 && cnt==0);
    ASSERT(BRCFScanLedgerAbandonedBelow(&l)==301 && nf2==301);

    // Resolve O → no outstanding hole caps the floor any more; only NOW can 2000 be
    // abandoned. Floor advances to cover it: highest-dropped(2000)+1 = 2001 (NOT clamp=5000).
    BRCFScanLedgerMarkEvaluated(&l, 500);
    ASSERT(BRCFScanLedgerOutstandingCount(&l)==0);
    uint32_t nf3 = BRCFScanLedgerAbandonGaveUpBelow(&l, 5000, &cnt, &lo, &hi);
    ASSERT(cnt==1 && lo==2000 && hi==2000);           // 2000 abandoned once recoverable-hole O is gone
    ASSERT(BRCFScanLedgerGaveUpCount(&l)==0);
    ASSERT(BRCFScanLedgerAbandonedBelow(&l)==2001 && nf3==2001);   // covers the dropped 2000, NOT the clamp

    // Monotonic: a lower clamp with nothing to drop NEVER regresses the floor.
    uint32_t nf4 = BRCFScanLedgerAbandonGaveUpBelow(&l, 3000, &cnt, &lo, &hi);
    ASSERT(cnt==0 && BRCFScanLedgerAbandonedBelow(&l)==2001 && nf4==2001);
    return 1;
}

// Silent-loss guard: abandonedBelow is the SINGLE PERSISTED source of truth for
// abandonment. AbandonGaveUpBelow must NEVER drop a gaveUp height that sits ABOVE
// the new watermark — such a height would vanish from the persisted gaveUp list
// (removed from HoleRanges) while abandonedBelow (capped at the still-outstanding
// hole O) doesn't cover it, so after a process restart it is lost entirely: not in
// gaveUp, not below the persisted floor, never scanned. Drop only < target, keep
// gaveUp in [O, clamp).
static int test_abandon_keeps_gaveup_above_watermark(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 100);
    UInt128 pa = UINT128_ZERO; pa.u8[15]=1;
    // Still-retrying outstanding hole O=500 (attempts < MAX, below clamp).
    BRCFScanLedgerRecordRequested(&l, 500, 500, pa, 12024, 1000);
    ASSERT(BRCFScanLedgerOutstandingCount(&l)==1 && l.outstanding[0].height==500);
    // gaveUp = {300, 700}: INTERLEAVED — G1=300 < O=500 < G2=700 < clamp=1000.
    l.gaveUp[0]=300; l.gaveUp[1]=700; l.gaveUpCount=2;

    uint32_t cnt=999, lo=999, hi=999;
    uint32_t nf = BRCFScanLedgerAbandonGaveUpBelow(&l, 1000, &cnt, &lo, &hi);

    // Only G1 (below target=O=500) is abandoned; G2 in [O,clamp) is KEPT.
    ASSERT(cnt==1 && lo==300 && hi==300);
    ASSERT(BRCFScanLedgerGaveUpCount(&l)==1 && l.gaveUp[0]==700);
    // GUARD: abandonedBelow advances only to cover the dropped G1 → highest-dropped(300)+1
    // = 301 (NOT preemptively to target O=500). Everything dropped sits below it (300 < 301).
    ASSERT(BRCFScanLedgerAbandonedBelow(&l)==301);
    ASSERT(BRCFScanLedgerOutstandingCount(&l)==1 && l.outstanding[0].height==500);   // O untouched
    ASSERT(nf==301);
    // G2=700 is still a REPORTED hole (survives a restart via the persisted gaveUp list).
    uint32_t starts[8], ends[8];
    size_t n = BRCFScanLedgerHoleRanges(&l, starts, ends, 8);
    int found700 = 0;
    for (size_t i=0;i<n;i++) if (starts[i]<=700 && 700<=ends[i]) found700 = 1;
    ASSERT(found700);
    return 1;
}

// Part 3b determinism guard (the LOAD-BEARING wrong-balance test): empty
// outstanding + NO gaveUp below the clamp — the "scan-not-started" shape
// (ClearMemory fires during header sync, before the cfilter scan requests
// anything). With nothing legitimately abandonable, AbandonGaveUpBelow must
// return cnt==0 AND leave abandonedBelow UNCHANGED. A preemptive raise here would
// let a deep restore COMPLETE with a WRONG BALANCE (deep history never scanned).
static int test_abandon_no_advance_when_nothing_dropped(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 100);   // scannedThrough=99, abandonedBelow=0
    ASSERT(BRCFScanLedgerOutstandingCount(&l)==0 && BRCFScanLedgerGaveUpCount(&l)==0);
    ASSERT(BRCFScanLedgerAbandonedBelow(&l)==0);

    uint32_t cnt=999, lo=999, hi=999;
    uint32_t nf = BRCFScanLedgerAbandonGaveUpBelow(&l, 1000000, &cnt, &lo, &hi);

    // Nothing dropped → NO advance. This is the guard: abandonedBelow stays 0.
    ASSERT(cnt==0 && lo==CF_LEDGER_NO_DROP && hi==CF_LEDGER_NO_DROP);
    ASSERT(BRCFScanLedgerAbandonedBelow(&l)==0);                 // UNCHANGED — no preemptive raise
    ASSERT(nf==100 && BRCFScanLedgerLowestNeededHeight(&l)==100); // == scannedThrough+1 (floor NOT raised)
    return 1;
}

// Part 3b: with gaveUp below the clamp and NO outstanding, the floor advances
// ONLY to cover the gaveUp actually dropped (highest-dropped+1), NEVER to the clamp.
static int test_abandon_advance_covers_dropped_only(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 100);   // scannedThrough=99
    // No outstanding; two retry-exhausted gaveUp holes G1,G2 both below the clamp.
    l.gaveUp[0]=4000; l.gaveUp[1]=4200; l.gaveUpCount=2;   // G1=4000, G2=4200

    uint32_t cnt=999, lo=999, hi=999;
    uint32_t nf = BRCFScanLedgerAbandonGaveUpBelow(&l, 100000, &cnt, &lo, &hi);

    // Both dropped; abandonedBelow == highest-dropped(G2=4200)+1 = 4201, NOT clamp(100000).
    ASSERT(cnt==2 && lo==4000 && hi==4200);
    ASSERT(BRCFScanLedgerGaveUpCount(&l)==0);
    ASSERT(BRCFScanLedgerAbandonedBelow(&l)==4201);             // G2+1, NOT the clamp
    ASSERT(nf==4201 && BRCFScanLedgerLowestNeededHeight(&l)==4201);
    return 1;
}

// abandonedBelow is a HARD FLOOR: RecordRequested / MarkHeaderRace reject (no-op)
// heights < abandonedBelow; PeekRerequestRange never offers a height < abandonedBelow.
static int test_abandoned_is_hard_floor(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 1);
    UInt128 pa = UINT128_ZERO; pa.u8[15]=1;
    l.abandonedBelow = 1000;   // floor

    // RecordRequested drops the below-floor part, keeps [1000..1002].
    BRCFScanLedgerRecordRequested(&l, 900, 1002, pa, 12024, 100);
    ASSERT(BRCFScanLedgerOutstandingCount(&l)==3 && l.outstanding[0].height==1000);
    // A request wholly below the floor is a complete no-op.
    BRCFScanLedgerRecordRequested(&l, 500, 800, pa, 12024, 100);
    ASSERT(BRCFScanLedgerOutstandingCount(&l)==3);
    // MarkHeaderRace below the floor is a no-op (never resurrects an abandoned height).
    BRCFScanLedgerMarkHeaderRace(&l, 700);
    ASSERT(BRCFScanLedgerOutstandingCount(&l)==3);
    // MarkHeaderRace at/above the floor still records.
    BRCFScanLedgerMarkHeaderRace(&l, 1005);
    ASSERT(BRCFScanLedgerOutstandingCount(&l)==4);

    // PeekRerequestRange clamps to the floor even for heights recorded BEFORE it rose.
    BRCFScanLedger l2; BRCFScanLedgerInit(&l2, 1);
    BRCFScanLedgerRecordRequested(&l2, 100, 105, pa, 12024, 0);   // recorded first
    l2.abandonedBelow = 103;                                       // floor raised after the fact
    uint32_t s=0, e=0;
    int got = BRCFScanLedgerPeekRerequestRange(&l2, CF_REREQ_BASE_SECS, 0, &s, &e);
    ASSERT(got==1 && s==103);   // never offers 100..102 despite them being outstanding & due
    return 1;
}

// abandonedBelow round-trips through Serialize/Parse (blob v2), and a pre-version
// (v1) blob parses with abandonedBelow == 0 (backward compatible).
static int test_abandonedBelow_roundtrips(void) {
    BRCFScanLedger l1; BRCFScanLedgerInit(&l1, 100);
    UInt128 pa = UINT128_ZERO; pa.u8[15]=1;
    BRCFScanLedgerRecordRequested(&l1, 100, 110, pa, 12024, 0);
    l1.abandonedBelow = 777;   // non-zero floor to persist
    uint8_t buf[4096];
    size_t need = BRCFScanLedgerSerialize(&l1, buf, sizeof buf);
    ASSERT(need > 0 && need <= sizeof buf);

    BRCFScanLedger l2;
    ASSERT(BRCFScanLedgerParse(&l2, buf, need)==1);
    ASSERT(BRCFScanLedgerAbandonedBelow(&l2)==777);                       // survived
    ASSERT(l2.start==100 && l2.requestedThrough==l1.requestedThrough);
    ASSERT(BRCFScanLedgerOutstandingCount(&l2)==11);
    // byte-identical re-serialize.
    uint8_t buf2[4096];
    size_t need2 = BRCFScanLedgerSerialize(&l2, buf2, sizeof buf2);
    ASSERT(need2==need && memcmp(buf, buf2, need)==0);

    // Back-compat: hand-build a pre-version (v1) blob and confirm it parses with
    // abandonedBelow == 0. Reuse the real magic bytes from a v2 serialize so the
    // test never hardcodes the module's private CF_LEDGER_MAGIC.
    BRCFScanLedger probeL; BRCFScanLedgerInit(&probeL, 1);
    uint8_t probe[64]; BRCFScanLedgerSerialize(&probeL, probe, sizeof probe);
    uint8_t v1[28];
    v1[0]=probe[0]; v1[1]=probe[1]; v1[2]=probe[2]; v1[3]=probe[3];   // magic (LE) from v2 output
    putLE32(v1+4, 1);        // version = 1 (pre-abandonedBelow layout)
    putLE32(v1+8, 500);      // start
    putLE32(v1+12, 499);     // scannedThrough
    putLE32(v1+16, 499);     // requestedThrough
    putLE32(v1+20, 0);       // outstandingCount
    putLE32(v1+24, 0);       // gaveUpCount
    BRCFScanLedger lv1;
    ASSERT(BRCFScanLedgerParse(&lv1, v1, sizeof v1)==1);
    ASSERT(BRCFScanLedgerAbandonedBelow(&lv1)==0);                        // back-compat default
    ASSERT(lv1.start==500 && lv1.scannedThrough==499 && lv1.requestedThrough==499);
    ASSERT(BRCFScanLedgerOutstandingCount(&lv1)==0 && BRCFScanLedgerGaveUpCount(&lv1)==0);
    return 1;
}

// ---- Paced-convoy Task 5: the B2 valve's ledger primitives -----------------

// BRCFScanLedgerReArmGaveUp: a parked hole goes back to `outstanding` with a
// FRESH cycle and EXACTLY ONE HOME, and its re-arm cycle count SURVIVES the
// outstanding -> gaveUp -> outstanding round trip. That survival is the whole
// mechanism: BRCFOutstanding is destroyed on retirement, so without the parked
// carry the counter would restart at 0 every cycle, CF_CONVOY_REARM_MAX could
// never be reached, and the valve would re-arm forever without ever abandoning —
// a permanent convoy wedge dressed up as caution.
static int test_rearm_gaveup_roundtrip(void) {
    BRCFScanLedger l; BRCFScanLedgerInit(&l, 100);   // scannedThrough = 99
    UInt128 pa = UINT128_ZERO; pa.u8[15]=1;
    BRCFScanLedgerRecordRequested(&l, 100, 120, pa, 12024, 1000);
    for (uint32_t h=100; h<=104; h++) BRCFScanLedgerMarkEvaluated(&l, h);

    // Drive 105 (the lowest outstanding) to gaveUp via the attempt cap.
    uint32_t now=1000, out=0;
    for (int i=0;i<CF_REREQ_MAX_ATTEMPTS;i++){ now+=1000; BRCFScanLedgerNextRerequest(&l, now, &out); }
    now+=1000; BRCFScanLedgerNextRerequest(&l, now, &out);
    ASSERT(BRCFScanLedgerGaveUpCount(&l)==1 && l.gaveUp[0]==105);

    uint32_t gh=0; uint8_t gc=99, gl=99;
    ASSERT(BRCFScanLedgerLowestGaveUp(&l, &gh, &gc, &gl)==1);
    ASSERT(gh==105 && gc==0 && gl==1);   // never re-armed yet; its original cycle was untainted

    // --- cycle 1 ---
    ASSERT(BRCFScanLedgerReArmGaveUp(&l, 105)==1);
    ASSERT(BRCFScanLedgerGaveUpCount(&l)==0);                       // EXACTLY ONE HOME: gone from gaveUp
    ASSERT(l.outstanding[0].height==105);                            // back in outstanding, still the lowest
    ASSERT(l.outstanding[0].attempts==0);                            // a FRESH full retry cycle
    ASSERT(l.outstanding[0].rearmCycles==1);
    ASSERT(l.outstanding[0].offersReachedLivePeer==1);               // new cycle starts untainted
    ASSERT(BRCFScanLedgerLowestNeededHeight(&l)==105);               // the frontier is still pinned there

    // Taint it, then re-exhaust: the parked verdict must carry BOTH bytes out.
    BRCFScanLedgerMarkOffersMissedLivePeer(&l, 105, 105);
    ASSERT(l.outstanding[0].offersReachedLivePeer==0);
    for (int i=0;i<CF_REREQ_MAX_ATTEMPTS;i++){ now+=1000; BRCFScanLedgerNextRerequest(&l, now, &out); }
    now+=1000; BRCFScanLedgerNextRerequest(&l, now, &out);
    ASSERT(BRCFScanLedgerGaveUpCount(&l)==1 && l.gaveUp[0]==105);
    ASSERT(BRCFScanLedgerLowestGaveUp(&l, &gh, &gc, &gl)==1);
    ASSERT(gh==105 && gc==1 && gl==0);   // THE CARRY: cycle count survived, taint survived

    // --- cycle 2 --- the counter keeps CLIMBING (it does not restart at 0/1).
    ASSERT(BRCFScanLedgerReArmGaveUp(&l, 105)==1);
    ASSERT(l.outstanding[0].rearmCycles==2);
    ASSERT(l.outstanding[0].offersReachedLivePeer==1);               // the new cycle is clean again

    // Not-a-parked-hole and below-the-hard-floor are both no-ops.
    ASSERT(BRCFScanLedgerReArmGaveUp(&l, 105)==0);                   // it is outstanding, not parked
    ASSERT(BRCFScanLedgerReArmGaveUp(&l, 999)==0);                   // never parked at all
    l.gaveUp[0]=50; l.gaveUpRearmCycles[0]=0; l.gaveUpOffersLive[0]=1; l.gaveUpCount=1;
    l.abandonedBelow = 60;
    ASSERT(BRCFScanLedgerReArmGaveUp(&l, 50)==0);                    // below the abandoned hard floor
    ASSERT(BRCFScanLedgerGaveUpCount(&l)==1);                        // and it was NOT silently dropped
    return 1;
}

// Blob v3: the parked valve bytes round-trip, an outstanding entry's rearmCycles
// round-trips (its per-cycle latch does not — it follows `attempts` into the
// unpersisted set), and a v2 blob still parses with all of them 0.
static int test_valve_state_persists_v3(void) {
    BRCFScanLedger l1; BRCFScanLedgerInit(&l1, 100);
    UInt128 pa = UINT128_ZERO; pa.u8[15]=1;
    BRCFScanLedgerRecordRequested(&l1, 100, 102, pa, 12024, 0);
    l1.outstanding[1].rearmCycles           = 2;
    l1.outstanding[1].offersReachedLivePeer = 0;   // transient: must NOT survive
    l1.gaveUp[0]=200; l1.gaveUpRearmCycles[0]=2; l1.gaveUpOffersLive[0]=1;
    l1.gaveUp[1]=201; l1.gaveUpRearmCycles[1]=1; l1.gaveUpOffersLive[1]=0;
    l1.gaveUpCount=2;

    uint8_t buf[4096];
    size_t need = BRCFScanLedgerSerialize(&l1, buf, sizeof buf);
    ASSERT(need > 0 && need <= sizeof buf);

    BRCFScanLedger l2;
    ASSERT(BRCFScanLedgerParse(&l2, buf, need)==1);
    ASSERT(l2.outstandingCount==3 && l2.outstanding[1].rearmCycles==2);   // survived
    ASSERT(l2.outstanding[1].offersReachedLivePeer==1);                   // fresh cycle -> clean latch
    ASSERT(l2.gaveUpCount==2);
    ASSERT(l2.gaveUp[0]==200 && l2.gaveUpRearmCycles[0]==2 && l2.gaveUpOffersLive[0]==1);
    ASSERT(l2.gaveUp[1]==201 && l2.gaveUpRearmCycles[1]==1 && l2.gaveUpOffersLive[1]==0);

    // Byte-identical re-serialize (unconditionally, because nothing written is
    // re-derived on load).
    uint8_t buf2[4096];
    size_t need2 = BRCFScanLedgerSerialize(&l2, buf2, sizeof buf2);
    ASSERT(need2==need && memcmp(buf, buf2, need)==0);

    // Back-compat: hand-build a v2 blob (narrower entries, no valve bytes) and
    // confirm it parses with every valve byte at 0 — i.e. every parked hole simply
    // gets its full re-arm budget back after the upgrade. Same shape as the v1
    // case above; magic reused from a real serialize, never hardcoded.
    uint8_t probe[64]; BRCFScanLedger probeL; BRCFScanLedgerInit(&probeL, 1);
    BRCFScanLedgerSerialize(&probeL, probe, sizeof probe);
    uint8_t v2[28 + 2*(4+1) + 4 + 1*4];
    v2[0]=probe[0]; v2[1]=probe[1]; v2[2]=probe[2]; v2[3]=probe[3];   // magic
    putLE32(v2+4, 2);          // version = 2 (pre-valve-bytes layout)
    putLE32(v2+8, 300);        // start
    putLE32(v2+12, 299);       // scannedThrough
    putLE32(v2+16, 320);       // requestedThrough
    putLE32(v2+20, 0);         // abandonedBelow
    putLE32(v2+24, 2);         // outstandingCount
    putLE32(v2+28, 310); v2[32]=0;     // outstanding[0] = {310, headerRace 0}
    putLE32(v2+33, 311); v2[37]=1;     // outstanding[1] = {311, headerRace 1}
    putLE32(v2+38, 1);                 // gaveUpCount
    putLE32(v2+42, 305);               // gaveUp[0] = 305
    BRCFScanLedger lv2;
    ASSERT(BRCFScanLedgerParse(&lv2, v2, sizeof v2)==1);
    ASSERT(lv2.start==300 && lv2.requestedThrough==320);
    ASSERT(lv2.outstandingCount==2 && lv2.outstanding[0].height==310 && lv2.outstanding[1].headerRace==1);
    ASSERT(lv2.outstanding[0].rearmCycles==0 && lv2.outstanding[1].rearmCycles==0);
    ASSERT(lv2.gaveUpCount==1 && lv2.gaveUp[0]==305);
    ASSERT(lv2.gaveUpRearmCycles[0]==0 && lv2.gaveUpOffersLive[0]==0);
    return 1;
}

// ---- fix-wave C-1: BRCFScanLedgerAbandonUnscannableBelow ---------------------
//
// The primitive behind the "no height is ever marked scanned without being scanned
// or being surfaced as abandoned" invariant. Pinned here at the ledger level; the
// production RESUME path that drives it is pinned in cf_scan_ledger_drive_kat.
static int test_abandon_unscannable_below(void) {
    // (a) NO preemptive raise — the same determinism discipline AbandonGaveUpBelow
    // carries. A floor at or below the low edge surfaces nothing, so abandonedBelow
    // must not move and the caller must not warn.
    BRCFScanLedger a; BRCFScanLedgerInit(&a, 1000);
    uint32_t cnt = 0xffffffffu;
    BRCFScanLedgerAbandonUnscannableBelow(&a, 1000, 1000, &cnt);
    ASSERT(cnt==0 && a.abandonedBelow==0);
    BRCFScanLedgerAbandonUnscannableBelow(&a, 1000, 900, &cnt);
    ASSERT(cnt==0 && a.abandonedBelow==0);
    BRCFScanLedgerAbandonUnscannableBelow(&a, 0, 5000, &cnt);      // unarmed low edge
    ASSERT(cnt==0 && a.abandonedBelow==0);
    BRCFScanLedgerAbandonUnscannableBelow(&a, 1000, 0, &cnt);      // no block floor yet
    ASSERT(cnt==0 && a.abandonedBelow==0);

    // (b) THE RESUME SHAPE: the scan frontier sits far below the block floor, and
    // the band is surfaced (count + watermark) rather than skipped.
    BRCFScanLedger b; BRCFScanLedgerInit(&b, 1000);
    BRCFScanLedgerRecordRequested(&b, 1000, 1499, UINT128_ZERO, 0, 100);
    for (uint32_t h = 1000; h <= 1499; h++) BRCFScanLedgerMarkEvaluated(&b, h);
    ASSERT(BRCFScanLedgerLowestNeededHeight(&b)==1500);
    const uint32_t FLOOR = 11500;                      // scan frontier + 10000 (a full convoy window)
    uint32_t lo = BRCFScanLedgerLowestNeededHeight(&b);
    uint32_t newLowest = BRCFScanLedgerAbandonUnscannableBelow(&b, lo, FLOOR, &cnt);
    check(cnt == FLOOR - 1500, "unscannable: the surfaced count is exactly the band [1500..11499]");
    check(b.abandonedBelow == FLOOR, "unscannable: abandonedBelow raised to the block floor");
    check(newLowest == FLOOR, "unscannable: LowestNeededHeight is now the floor (the convoy can advance)");

    // (c) THE VARIANT: an outstanding hole below the floor is DROPPED, not left to
    // pin the frontier. It can never be served (its getcfilters stop hash cannot
    // resolve, so `attempts` never advances, so it never reaches gaveUp and the B2
    // valve is structurally blind to it) — leaving it is the invisible permanent pin.
    BRCFScanLedger c; BRCFScanLedgerInit(&c, 1000);
    BRCFScanLedgerRecordRequested(&c, 1000, 1999, UINT128_ZERO, 0, 100);
    for (uint32_t h = 1000; h <= 1499; h++) BRCFScanLedgerMarkEvaluated(&c, h);
    c.gaveUp[0] = 1600; c.gaveUpRearmCycles[0] = 2; c.gaveUpOffersLive[0] = 1;
    c.gaveUp[1] = 90000; c.gaveUpRearmCycles[1] = 1; c.gaveUpOffersLive[1] = 0;
    c.gaveUpCount = 2;
    ASSERT(BRCFScanLedgerOutstandingCount(&c) == 500);              // [1500..1999]
    ASSERT(BRCFScanLedgerLowestNeededHeight(&c) == 1500);
    BRCFScanLedgerAbandonUnscannableBelow(&c, 1500, FLOOR, &cnt);
    check(BRCFScanLedgerOutstandingCount(&c) == 0,
          "unscannable: every outstanding hole below the floor is dropped (no invisible pin)");
    check(BRCFScanLedgerGaveUpCount(&c) == 1 && c.gaveUp[0] == 90000,
          "unscannable: a gaveUp hole below the floor is dropped, one ABOVE it is KEPT");
    check(c.gaveUpRearmCycles[0] == 1 && c.gaveUpOffersLive[0] == 0,
          "unscannable: the surviving gaveUp entry keeps ITS OWN index-parallel valve bytes");
    check(BRCFScanLedgerLowestNeededHeight(&c) == FLOOR,
          "unscannable: the frontier is no longer pinned below the floor");

    // (d) MONOTONIC + EXACT COUNT: a lower floor never walks the watermark back
    // down, AND it must report count 0 — history already below the watermark was
    // surfaced by the earlier call, so counting it again would break
    // "count>0 <=> advance <=> WARN" and warn about an abandonment that did not
    // happen. (`lo` is deliberately passed as the ORIGINAL low edge here, i.e. the
    // stale value a careless caller would supply.)
    BRCFScanLedgerAbandonUnscannableBelow(&c, 1500, FLOOR - 5000, &cnt);
    check(c.abandonedBelow == FLOOR, "unscannable: abandonedBelow is monotonic");
    check(cnt == 0,
          "unscannable: a floor below the existing watermark reports count 0 "
          "(count>0 <=> advance <=> WARN stays exact; no double-warn)");

    // (e) ...and a floor only PARTLY above the watermark counts only the NEW band.
    uint32_t newLow = BRCFScanLedgerAbandonUnscannableBelow(&c, 1500, FLOOR + 400, &cnt);
    check(cnt == 400 && c.abandonedBelow == FLOOR + 400 && newLow == FLOOR + 400,
          "unscannable: only the heights newly surfaced are counted (the band above the old watermark)");
    return 1;
}

// ---- fix-wave I-2: the v3 entry stride must survive the NEXT version bump ----
//
// Every back-compat branch in Parse must compare against its OWN numbered constant.
// The v2 branch does (`version >= CF_LEDGER_VERSION_2`, a fix an earlier task
// earned after exactly this bug); the v3 branch was written `version >=
// CF_LEDGER_VERSION`, which means "the layout Serialize writes TODAY" and therefore
// silently changes meaning at the next bump: every already-shipped v3 blob would
// take the NARROWER v2 entry stride and mis-parse outstanding[]/gaveUp[].
//
// This case hand-builds a REAL v3 blob and parses it. Run normally it is a plain
// (green) round-trip guard. Run under the run.sh gate build, which simulates the
// next bump with -DCF_LEDGER_VERSION=4u, it is the red-before-green proof: with the
// pre-fix comparison the blob takes the v2 stride, Parse still returns 1 (the
// length checks PASS — a narrower stride needs FEWER bytes, which is exactly why
// the corruption is silent) and gaveUp[1] comes back as garbage while the parked
// valve bytes come back as 0. A garbage low gaveUp height then feeds the B2 valve,
// which can raise the MONOTONIC hard floor abandonedBelow to an arbitrary height =
// a permanent skip of real history.
//
// The geometry is chosen so the mis-parse is SILENT rather than a rejected blob:
// outstandingCount == 0 and gaveUpCount == 2, so the narrow stride mis-reads only
// gaveUp[1] and the two parked bytes, and no length/CF_GAVEUP_MAX check trips.
static int test_v3_stride_survives_a_future_version_bump(void) {
    uint8_t probe[64]; BRCFScanLedger probeL; BRCFScanLedgerInit(&probeL, 1);
    BRCFScanLedgerSerialize(&probeL, probe, sizeof probe);   // magic, never hardcoded

    // 28-byte fixed header + outCount(4) + gaveUpCount(4) + 2 x 6-byte gaveUp entries
    uint8_t v3[28 + 4 + 2*(4+1+1)];
    v3[0]=probe[0]; v3[1]=probe[1]; v3[2]=probe[2]; v3[3]=probe[3];   // magic
    putLE32(v3+4, 3);            // version = 3 (the CURRENT shipped layout)
    putLE32(v3+8, 20000000);     // start
    putLE32(v3+12, 20000499);    // scannedThrough
    putLE32(v3+16, 20001000);    // requestedThrough
    putLE32(v3+20, 0);           // abandonedBelow
    putLE32(v3+24, 0);           // outstandingCount = 0
    putLE32(v3+28, 2);           // gaveUpCount = 2
    putLE32(v3+32, 20000600); v3[36]=2; v3[37]=1;   // gaveUp[0] = {600, cycles 2, offersLive 1}
    putLE32(v3+38, 20000700); v3[42]=1; v3[43]=0;   // gaveUp[1] = {700, cycles 1, offersLive 0}

    BRCFScanLedger l;
    ASSERT(BRCFScanLedgerParse(&l, v3, sizeof v3)==1);
    ASSERT(l.start==20000000 && l.scannedThrough==20000499 && l.requestedThrough==20001000);
    ASSERT(l.gaveUpCount==2);
    // The four discriminating reads. Under the pre-fix comparison with a bumped
    // CF_LEDGER_VERSION these are: gaveUp[1] garbage, and every parked byte 0.
    check(l.gaveUp[0]==20000600, "v3 stride: gaveUp[0] height parsed at the v3 stride");
    check(l.gaveUp[1]==20000700,
          "v3 stride: gaveUp[1] height parsed at the v3 stride (RED under a simulated "
          "version bump with the pre-fix `version >= CF_LEDGER_VERSION` comparison)");
    check(l.gaveUpRearmCycles[0]==2 && l.gaveUpOffersLive[0]==1,
          "v3 stride: gaveUp[0]'s parked B2 valve bytes survived");
    check(l.gaveUpRearmCycles[1]==1 && l.gaveUpOffersLive[1]==0,
          "v3 stride: gaveUp[1]'s parked B2 valve bytes survived");
    return 1;
}

static void test_forward_fetch_waits_for_every_hole(void) {
    BRCFScanLedger l;
    BRCFScanLedgerInit(&l, 100);
    check(BRCFScanLedgerCanRequestForward(&l) == 1,
          "forward fetch is ready when the ledger has no unresolved height");

    UInt128 peer = UINT128_ZERO;
    BRCFScanLedgerRecordRequested(&l, 100, 102, peer, 12024, 1);
    check(BRCFScanLedgerCanRequestForward(&l) == 0,
          "forward fetch waits while any requested height is outstanding");
    BRCFScanLedgerMarkEvaluated(&l, 100);
    BRCFScanLedgerMarkEvaluated(&l, 102);
    check(BRCFScanLedgerCanRequestForward(&l) == 0,
          "out-of-order evaluations do not open the next forward batch");
    BRCFScanLedgerMarkEvaluated(&l, 101);
    check(BRCFScanLedgerCanRequestForward(&l) == 1,
          "forward fetch opens only after the whole batch is evaluated");
}


// ---------------------------------------------------------------------------
// Retiring an abandoned band (2026-08-21). abandonedBelow was monotonic with NO
// lowering path at all, which made a "history gap" permanent: the only cures were a
// backend reconcile or a full rebuild from wallet birth — re-scanning ~24M blocks to
// recover ~20k.
//
// It felt like a safety property, but it is a consequence of having no way to put the
// pruned headers back. The loop: abandoning clamps those heights out of
// LowestNeededHeight -> the prune floor rises above them -> their block headers are
// pruned -> getcfilters cannot resolve a stop hash for the range -> the band really IS
// unrequestable, retroactively justifying the abandonment.
//
// So the lowering path exists, but it is NARROW and its contract is the whole point:
// the CALLER must have made headers for the retired range resident first. The ledger
// cannot verify that and must not pretend to.
// ---------------------------------------------------------------------------
static void test_retire_abandoned_band(void)
{
    BRCFScanLedger l;
    BRCFScanLedgerInit(&l, 24000000);

    uint32_t cnt = 0;
    BRCFScanLedgerAbandonUnscannableBelow(&l, 24050000, 24070273, &cnt);
    check(BRCFScanLedgerAbandonedBelow(&l) == 24070273,
          "retire: precondition -- a band is abandoned up to 24070273");

    // Retire the lower half. abandonedBelow must FOLLOW the caller down, exactly.
    uint32_t retired = BRCFScanLedgerRetireAbandonedTo(&l, 24060000);
    check(retired == 10273, "retire: reports exactly the number of heights retired");
    check(BRCFScanLedgerAbandonedBelow(&l) == 24060000,
          "retire: abandonedBelow lowered to the caller's new floor");

    // Retiring the rest clears it entirely.
    retired = BRCFScanLedgerRetireAbandonedTo(&l, 24050000);
    check(retired == 10000, "retire: the remaining heights are retired");
    check(BRCFScanLedgerAbandonedBelow(&l) == 24050000,
          "retire: abandonedBelow reaches the band's low edge");

    // A floor at/above the current one is a NO-OP, never an accidental raise. Raising
    // is what AbandonUnscannableBelow is for, and this path must not become a second
    // way to condemn heights.
    check(BRCFScanLedgerRetireAbandonedTo(&l, 24050000) == 0,
          "retire: retiring to the same floor is a no-op");
    check(BRCFScanLedgerRetireAbandonedTo(&l, 24060000) == 0,
          "retire: a HIGHER floor is a no-op -- this path can only ever lower");
    check(BRCFScanLedgerAbandonedBelow(&l) == 24050000,
          "retire: ...and the floor did not move on either no-op");

    // Never below the ledger's own start: those heights were never in scope.
    check(BRCFScanLedgerRetireAbandonedTo(&l, 23000000) == 0,
          "retire: a floor below the ledger start is refused, not clamped silently");
    check(BRCFScanLedgerAbandonedBelow(&l) == 24050000,
          "retire: ...and the floor is unchanged after that refusal");

    // Retiring must not disturb scan progress. In the field case the band had ALREADY
    // been scanned (scannedThrough sat exactly at the band top), so retiring is pure
    // bookkeeping there -- it must not rewind scannedThrough and cause a re-scan.
    BRCFScanLedger l2;
    BRCFScanLedgerInit(&l2, 24000000);
    for (uint32_t h = 24000000; h <= 24070272; h++) BRCFScanLedgerMarkEvaluated(&l2, h);
    uint32_t before = BRCFScanLedgerScannedThrough(&l2);
    uint32_t c2 = 0;
    BRCFScanLedgerAbandonUnscannableBelow(&l2, 24050000, 24070273, &c2);
    BRCFScanLedgerRetireAbandonedTo(&l2, 24050000);
    check(BRCFScanLedgerScannedThrough(&l2) == before,
          "retire: scannedThrough is untouched -- retiring is not a rewind");

    printf("test_retire_abandoned_band: done\n");
}

int main(void) {
#ifdef KAT_LEDGER_STRIDE_REDGREEN_ONLY
    // Gate build: run ONLY the stride case so the RED is unambiguous.
    test_v3_stride_survives_a_future_version_bump();
    printf(g_failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", g_failures);
    return g_failures ? 1 : 0;
#else
    check(CF_LEDGER_DRIVE_REREQUEST == 1,
          "tripwire: production retries partial compact-filter responses");

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
    test_forward_fetch_waits_for_every_hole();

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

    test_buffered_hashes_enumerates();

    test_lowest_needed_height();
    test_abandon_gaveup_below();
    test_abandon_keeps_gaveup_above_watermark();
    test_abandon_no_advance_when_nothing_dropped();
    test_abandon_advance_covers_dropped_only();
    test_abandoned_is_hard_floor();
    test_abandonedBelow_roundtrips();

    test_rearm_gaveup_roundtrip();      // paced-convoy Task 5: B2 re-arm primitive + the parked-state carry
    test_valve_state_persists_v3();     // paced-convoy Task 5: blob v3 + v2 back-compat
    test_v3_stride_survives_a_future_version_bump();   // fix-wave I-2: the version-gate trap, one line over
    test_abandon_unscannable_below();                  // fix-wave C-1: the unscannable-band primitive
    test_retire_abandoned_band();                      // 2026-08-21: the one lowering path

    printf(g_failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", g_failures);
    return g_failures ? 1 : 0;
#endif
}
