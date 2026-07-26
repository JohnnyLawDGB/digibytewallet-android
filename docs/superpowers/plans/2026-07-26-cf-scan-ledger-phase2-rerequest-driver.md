# CF Scan Ledger — Phase 2 (Buffer-the-Bytes Drain) Implementation Plan — REV 4

> **Hardening record:** re-request architecture rejected after 3 hostile-pass criticals (tail-poison → livelock → block-pruning coupling); **buffer-the-bytes** chosen (operator, 2026-07-26). REV 3's 4th pass found 3 wiring criticals — the credit path (a matched buffered filter must `getdata` the block, not just `MarkEvaluated`), a no-peer silent-loss, and a buffer leak — all fixed here. **The final gate is a FRESH hostile code-reviewer on the assembled branch (not the plan author), with named targets: (a) matched buffered filter dispatches `getdata` to a live peer [KAT-asserted via `--wrap`] and does NOT `MarkEvaluated` when no peer is available; (b) the getdata dispatch-vs-delivery window vs the live path [document, don't scope-creep]; (c) ASan live in BOTH run.sh + the Free path exercised across Init/Parse/Free; (d) the block-vs-cfheader `isReady` ordering + the per-batch element hoist. Code-review-clean is necessary, NOT sufficient — Phase 2 closes only on the rig: same-seed wipe/restore, the $1 at 23,920,918 credits via CF alone, `outstanding`→0, then the steady-state week.**

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** A wallet on a healthy peer never permanently misses a receive: a compact-filter dropped because its block header hadn't connected (the dominant "header-race" class — the observed 1001-hole floor cluster) is **buffered and evaluated the moment its header connects**, with no re-request and no dependency on block retention. The rare non-header-race drops fall back to best-effort re-request. Proven end-to-end: the on-chain $1 DigiDollar at block 23,920,918 credits via CF alone, no reconcile.

**Architecture — why buffer, not re-request (settled after 3 hostile passes).** Re-request throws away cfilter bytes we already received and asks for them again, which requires the block header to still be in memory — but `_BRPeerManagerClearMemory` prunes blocks below `cfNext − 144`, so the floor cluster's headers are gone before a 30s retry fires. Buffering the bytes removes the dependency instead of racing it: keep the dropped filter's raw bytes in a bounded byte-budgeted ring; when the header connects, verify+parse+match the buffered filter directly. The pruner stays completely ledger-agnostic. Re-request remains for verify/parse/disconnect drops (block known at drop; 36-min steady-state retention ≫ 7.5-min retry ⇒ no pinning needed).

**Tech Stack:** C (breadwallet-derived SPV core, submodule), clang host KATs, Gradle NDK. No Kotlin/JNI change this phase.

## Deviation ledger — shipped code vs REV 4 (brief the final reviewer with THIS + the hardening record)
Four passes hardened the plan; deviations are where the code is no longer the plan — exactly where the fresh reviewer's attention buys the most. Each entry: what departed, why, and the review verdict. **Every deliberate departure gets an entry; the final whole-branch reviewer distinguishes "intentional, reviewed departure" from "drift" from this list without re-litigating each.**

- **D1 — `>=` (not `>`) eviction threshold in `BRCFScanLedgerBufferFilter`/insert (Task 2).** REV 4's Task-2 code wording implied strict `>`. Reason: `CF_FILTER_BUFFER_MAX_BYTES` (262144) is exactly divisible by the test's 512-byte filters, so a strict `>` let `bufferedBytes` settle at the cap and never evict, failing `test_buffer_bytebudget_evicts_oldest`. **Verdict: task-review verified BY HAND — the `bufferedBytes ≤ CAP` invariant holds at both call sites incl. the exact-equality boundary; `>=` only makes eviction fire one entry earlier (strictly more conservative), never evicts a live entry improperly, consistent with `BufferFilter` returning 0 for an over-budget single filter. A discovered fix, not drift.**
- **D2 — `filterBufMagic` field + heuristic free-before-memset guard (Task 2).** REV 4 said "free the buffer at the top of `Init`/`Parse` before `memset`" but did not specify HOW to avoid `free()`-ing garbage pointers off the host-KAT's brief-mandated unzeroed-stack `BRCFScanLedger l;` pattern. The implementer added a `filterBufMagic` sentinel checked before the free. **Verdict: reviewed + a round-1 fix landed it as a CONTRACT — the comments now state the caller precondition (`l` must be Init/Parse'd or zeroed), explicitly call the magic a "defensive heuristic, not a memory-safety guarantee," and name that reading it is itself an indeterminate read that plain ASan cannot flag (MSan's territory). Production-safe (real ledger lives in a `calloc`'d `BRPeerManager`), but the precondition is now a documented contract, not reassurance — load-bearing for Task 5, which wires `BufferFilter` into a real call site.**
- **D3 — null/zero-count guard around `BRGCSFilterMatchAny` in `_cfBufEval` (Task 5).** REV 4's `_cfBufEval` snippet called `BRGCSFilterMatchAny(gcs, c->elems->elements, c->elems->elementLens, c->elems->count)` unconditionally. Shipped code wraps it `(c->elems && c->elems->count > 0) ? BRGCSFilterMatchAny(...) : 0`. Reason: `BRWalletGetFilterElements` is documented to return `NULL` for a wallet with zero encodable elements, and the LIVE match path at `_peerRelayedCFilter` already guards identically (`if (fe && fe->count > 0)`). So the drain path mirrors reviewed production behavior rather than inventing a pattern; only reachable for a zero-element wallet (no KAT exercises it — all use a real derived wallet). **Verdict: Task-5 review CONFIRMED correct-mirror — the fresh reviewer verified the guard matches the live `_peerRelayedCFilter` guard exactly and does not change match semantics for a normal (non-empty) wallet. A discovered defensive mirror, not drift.**

## Global Constraints

- **Submodule:** all C edits in `native/src/main/jni/digibytewallet-core/` (fork `JohnnyLawDGB/digibytewallet-core`). Push the fork BEFORE bumping the pin. Submodule git ops use `GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core GIT_WORK_TREE=native/src/main/jni/digibytewallet-core git …`.
- **The gate `CF_LEDGER_DRIVE_REREQUEST` (`BRCFScanLedger.h:63`) is `#ifndef`-guarded (Task 1) and flips `0`→`1` LAST (Task 6).** All new call sites are wrapped `#if CF_LEDGER_DRIVE_REREQUEST … #endif`; KATs compile them live via `-DCF_LEDGER_DRIVE_REREQUEST=1`.
- **Host KATs:** `bash scripts/run-host-kats.sh; echo "EXIT=$?"` must print `EXIT=0`. NEVER pipe it to `tail`/`head` (masks the exit code).
- **Build order:** native before app — `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug`.
- **Commit co-author:** `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **App PRs:** `gh pr create --repo JohnnyLawDGB/digibytewallet-android --head JohnnyLawDGB:<branch> --base develop`.
- **Merges to `develop` need HUMAN approval.** Open PRs; do not self-merge.
- **Pinned constants (do NOT change):** `CF_REREQ_BASE_SECS 30`, `CF_REREQ_BACKOFF_CAP_SECS 120`, `CF_REREQ_MAX_ATTEMPTS 5`, `CF_OUTSTANDING_MAX 4096`, `CF_GAVEUP_MAX 512`, `CLEAR_MEM_CF_RETENTION_MARGIN 144`.

### Review-gate facts (resolved — do not re-litigate)
- **`CF_OUTSTANDING_MAX = 4096`; overflow did NOT fire in the item-3 capture** (count series held at 1001 for ~28 min, never near 4096 ⇒ a true measurement, not a floor). Task 1 wires the drop-signal so it can never again be silent.
- **The pruner stays ledger-agnostic.** No retention pinning. Verified arithmetic: block retention = `144 × ~15s = 36 min` steady-state ≫ retry schedule `30+60+120+120+120 = 7.5 min`, so the residual re-request path resolves inside retention without pinning. (Fast re-anchor shrinks the margin — residuals there are best-effort/gaveUp; the dominant header-race class is buffered, not re-requested.)
- **DGB cfilters are tiny:** median 4 B, mean 9 B, max 675 B (item-3 capture). The filter buffer is **byte-budgeted at `CF_FILTER_BUFFER_MAX_BYTES = 262144` (256 KB)** — many multiples of any realistic re-anchor burst.

### Scope boundary — heals cfilter holes, NOT cfheaders stalls
Under a permanent **cfheaders** stall (headers never connect — the recurring emulator wedge), buffered filters are never drained (their headers never arrive) and outstanding stays pinned: **Phase 2 makes a cfheaders wedge VISIBLE (`outstanding` pinned, `scannedThrough` frozen, `gaveUp` clean) but does NOT heal it** — that is `bip158WatchdogJob` / `reanchorCompactFilterChainAtFloor`'s layer. `outstanding` (not `gaveUp`) is the primary alarm. **Acceptance-run distinction, pre-agreed:** if the Task-7 run stalls with cfheaders NOT extending to tip, that is the infra wedge — route around it (own-node pin / fresh sync), NOT a Phase-2 failure to debug. A legitimate PASS requires headers healthy.

---

## File Structure
- **`BRCFScanLedger.h`** — gate `#ifndef` guard; constants (`CF_LEDGER_NO_DROP`, `CF_FILTER_BUFFER_MAX_BYTES`, `CF_FILTER_DRAIN_PER_TICK`, `CF_OUTSTANDING_LOWWATER`, `CF_REREQ_BATCH_PER_TICK`, `CF_REREQ_MAX_RANGE`); the filter-buffer struct fields; new decls.
- **`BRCFScanLedger.c`** — overflow drop-signal (+ `requestedThrough` preserved); the byte-budgeted filter buffer; the residual peek/commit re-request driver + retire; underflow-guarded due-check.
- **`BRPeerManager.c`** — EDIT 1 (drop site buffers header-race bytes), EDIT 2 (KeepAlive: drain buffered filters whose headers connected + re-request residuals + `lastDriveAt`), EDIT 3 (back-pressure gate + loud overflow log), EDIT 4 (re-anchor/wipe clears the buffer).
- **`native/src/test/host/cf_scan_ledger_drive_kat/{run.sh,cf_scan_ledger_drive_kat_main.c}`** — new integration KAT with a link-wrap seam.
- Pure-module unit tests extend `native/src/test/host/cf_scan_ledger_kat/cf_scan_ledger_kat_main.c`.

---

## Task 1: gate guard + overflow drop is never silent (pure module)

**Files:** Modify `BRCFScanLedger.h` (gate guard + `CF_LEDGER_NO_DROP`), `BRCFScanLedger.c:108-153`; Test `cf_scan_ledger_kat_main.c`.

**Interfaces — Produces:** `int BRCFScanLedgerRecordRequestedDropped(BRCFScanLedger*, uint32_t startH, uint32_t stopH, UInt128 peer, uint16_t port, uint32_t now, uint32_t *outLow, uint32_t *outHigh)` — count of overflow-evicted oldest heights + their `[low..high]`. `void BRCFScanLedgerRecordRequested(...)` stays (delegates).

- [ ] **Step 1: `#ifndef`-guard the gate.** `BRCFScanLedger.h:63`:
```c
#ifndef CF_LEDGER_DRIVE_REREQUEST
#define CF_LEDGER_DRIVE_REREQUEST 0   // Phase 1: observe. Task 6 flips to 1. -D wins for KATs.
#endif
```
Add `#define CF_LEDGER_NO_DROP 0xFFFFFFFFu`.

- [ ] **Step 2: Write the failing tests** in `cf_scan_ledger_kat_main.c`:
```c
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
```
Register both in `main()`.

- [ ] **Step 3: Run to verify they fail** — `bash native/src/test/host/cf_scan_ledger_kat/run.sh; echo "EXIT=$?"` → FAIL.

- [ ] **Step 4: Implement.** `_cfLedgerInsertOutstanding` returns the evicted height (`CF_LEDGER_NO_DROP` if none) — same body, the front-drop records `dropped = l->outstanding[0].height;` before the `memmove`. Then:
```c
int BRCFScanLedgerRecordRequestedDropped(BRCFScanLedger *l, uint32_t startH, uint32_t stopH,
                                         UInt128 peer, uint16_t port, uint32_t now,
                                         uint32_t *outLow, uint32_t *outHigh) {
    if (stopH < startH) return 0;
    int n = 0; uint32_t lo = CF_LEDGER_NO_DROP, hi = 0;
    for (uint32_t h = startH; ; h++) {
        uint32_t d = _cfLedgerInsertOutstanding(l, h, peer, port, now);
        if (d != CF_LEDGER_NO_DROP) { n++; if (d < lo) lo = d; if (d > hi) hi = d; }
        if (h == stopH) break;                       // guards h==UINT32_MAX
    }
    if (stopH > l->requestedThrough) l->requestedThrough = stopH;   // ★ MUST NOT drop this (scannedThrough ceiling)
    if (outLow) *outLow = (n ? lo : CF_LEDGER_NO_DROP);
    if (outHigh) *outHigh = (n ? hi : CF_LEDGER_NO_DROP);
    return n;
}
void BRCFScanLedgerRecordRequested(BRCFScanLedger *l, uint32_t sH, uint32_t eH,
                                   UInt128 peer, uint16_t port, uint32_t now) {
    BRCFScanLedgerRecordRequestedDropped(l, sH, eH, peer, port, now, NULL, NULL);
}
```
Declare `BRCFScanLedgerRecordRequestedDropped` in `.h`.

- [ ] **Step 5: Run to verify** — `run.sh` → PASS, `EXIT=0`.
- [ ] **Step 6: Commit** — `feat(cf-ledger): guard gate + signal overflow drops (never silent), preserve requestedThrough`.

---

## Task 2: byte-budgeted filter-byte buffer (pure module)

**Files:** Modify `BRCFScanLedger.h` (struct fields + constants + decls), `BRCFScanLedger.c` (impl); Test `cf_scan_ledger_kat_main.c`.

**Interfaces — Produces:**
- `int BRCFScanLedgerBufferFilter(BRCFScanLedger*, UInt256 blockHash, const uint8_t *bytes, size_t len, uint32_t now)` — store a raw (unverified) cfilter keyed by `blockHash`. De-dups by hash. Byte-budgeted: while `bufferedBytes + len > CF_FILTER_BUFFER_MAX_BYTES`, **evict the OLDEST** entry (FIFO). Returns 1 if stored, 0 if the single filter itself exceeds the budget (caller leaves the height outstanding for re-request). Evictions are LOGGED by the caller via the return-of-evicted-count (below).
- `size_t BRCFScanLedgerDrainConnected(BRCFScanLedger*, int (*isReady)(void *ctx, UInt256 blockHash, uint32_t *outHeight), void *ctx, uint8_t *scratch, size_t scratchCap, int (*evalFn)(void *ctx, uint32_t height, UInt256 blockHash, const uint8_t *bytes, size_t len), size_t maxDrain)` — for up to `maxDrain` buffered entries whose `isReady(blockHash)→height` returns 1, copy the bytes to `scratch` and call `evalFn(ctx, height, blockHash, bytes, len)`. **Remove the entry ONLY if `evalFn` returns 1** (credited, or a clean verified miss); if `evalFn` returns 0 (a wallet HIT that could NOT dispatch `getdata` this tick — no CF-capable peer connected), **KEEP the entry buffered and leave the height outstanding** so the next tick retries. Returns the number REMOVED. **`evalFn` carries `blockHash`** because both `BRGCSFilterBasicParse` (SipHash key) and the `getdata` credit need it. Bounds the per-tick burst (target a). Pure module stays BRPeerManager-free via the function pointers.
- `void BRCFScanLedgerClearFilterBuffer(BRCFScanLedger*)` — free + discard ALL buffered bytes (re-anchor/wipe — target d).
- `void BRCFScanLedgerFree(BRCFScanLedger*)` — free all buffered bytes (teardown). **Must exist** (it currently does NOT) and be called from `BRPeerManagerFree`, AND the buffer must be freed at the TOP of `BRCFScanLedgerInit` and `BRCFScanLedgerParse` BEFORE their `memset` (the ledger is a POD reset by memset — otherwise the malloc'd buffer leaks on load and teardown; `ClearFilterBuffer` at re-anchor sites alone is insufficient).
- `size_t BRCFScanLedgerBufferedCount(const BRCFScanLedger*)` / `size_t BRCFScanLedgerBufferedBytes(const BRCFScanLedger*)` — observability + residual-gate predicate.

**Design notes for the four hostile targets:**
- **(a) burst:** draining is pull-based and capped at `maxDrain = CF_FILTER_DRAIN_PER_TICK` per KeepAlive tick. The caller fetches the wallet filter-element set ONCE per drain batch (NOT per filter — the normal `:2484` path rebuilds it per filter, which under a 128-drain would rebuild 128× under `manager->lock`).
- **(b) verify ordering:** buffered bytes are RAW/UNVERIFIED. `isReady` must require BOTH the block header (in `manager->blocks`, height known) AND the **cfheader** for that height (`BRCompactFilterChainNextHeight(cfChain) > height`) — block-header and cfheader sync are independent streams, and verify needs the cfheader. A filter that fails verify is discarded, height left outstanding (re-request fallback).
- **(c) eviction:** FIFO byte-budgeted; an evicted entry's height stays outstanding → re-request fallback (no silent loss).
- **(d) re-anchor/wipe:** `BRCFScanLedgerClearFilterBuffer` drops everything. In-memory only; NOT persisted (ledger persistence remains the source of truth; process death → normal floor re-anchor).

- [ ] **Step 1: Write the failing tests:**
```c
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
```
Register all four in `main()`.

- [ ] **Step 2: Run to verify they fail** — `run.sh` → FAIL.

- [ ] **Step 3: Implement.** In `.h`: `#define CF_FILTER_BUFFER_MAX_BYTES 262144` and `#define CF_FILTER_DRAIN_PER_TICK 128`. Add to `struct BRCFScanLedger` a dynamic FIFO of `{ UInt256 blockHash; uint8_t *bytes; size_t len; uint32_t at; }` (a small growable array via the codebase's `array_new`/`array_add` if the module already uses them, else a fixed ring of pointers — check the existing module style; `pending[]` is fixed-size, so mirror that with a **pointer ring** `filterBuf[CF_FILTER_BUF_SLOTS]` sized so `CF_FILTER_BUF_SLOTS * avg` covers the budget, plus a running `bufferedBytes`). Implement `BufferFilter` (malloc+copy the bytes, de-dup by hash, FIFO-evict-oldest while over budget, free evicted), `DrainConnected` (scan FIFO, for each `isReady` entry copy≤scratchCap bytes, `evalFn(…, blockHash, …)`, **and remove+free ONLY when `evalFn` returns 1** — a `0` return keeps the entry; stop at `maxDrain`), `ClearFilterBuffer` (free all). **Create `BRCFScanLedgerFree` (it does NOT exist today) that frees all buffered bytes; call the buffer-free at the TOP of `BRCFScanLedgerInit` AND `BRCFScanLedgerParse` BEFORE their `memset`** (the module POD-resets by memset — else the buffer leaks on load/teardown). Wire `BRCFScanLedgerFree` into `BRPeerManager.c` `BRPeerManagerFree` (`:3413`, alongside the existing frees). Declare all in `.h`. **Also add `-fsanitize=address -fno-omit-frame-pointer -g` to `native/src/test/host/cf_scan_ledger_kat/run.sh`** so LSan catches a buffer leak in the pure tests (each test that buffers must `BRCFScanLedgerFree(&l)` at the end).

- [ ] **Step 4: Run to verify** — `run.sh` → PASS (run under ASan if the KAT harness enables it — no leaks), `EXIT=0`.
- [ ] **Step 5: Commit** — `feat(cf-ledger): byte-budgeted filter-byte buffer (header-race drain primitive)`.

---

## Task 3: residual re-request driver (pure module — best-effort path)

**Files:** Modify `BRCFScanLedger.c/.h`; Test `cf_scan_ledger_kat_main.c`.

**Interfaces — Produces:** `void BRCFScanLedgerRetireCapped(BRCFScanLedger*)` (public, once/tick); `int BRCFScanLedgerPeekRerequestRange(BRCFScanLedger*, uint32_t now, uint32_t minHeight, uint32_t *outStart, uint32_t *outStop)` (offers lowest due `<MAX`-attempt same-peer contiguous run with `height >= minHeight`, no mutation); `void BRCFScanLedgerCommitRerequest(BRCFScanLedger*, uint32_t startH, uint32_t stopH, UInt128 peer, uint16_t port, uint32_t now)` (bump attempts + re-stamp for still-outstanding heights in range).

**Scope note:** this path now serves only **residual** drops (verify/parse/disconnect — block known at drop, near-tip, in-window, 36-min retention). The header-race floor cluster is handled by Task 2's buffer, so the earlier livelock/pruning concerns don't apply to the residual set. The caller (Task 5 EDIT 2) still uses `minHeight`/tip-cap defensively. Peek offers without bumping; Commit bumps only what sent (no attempt burned on a failed/partial send); `RetireCapped` runs once per tick (3-outcome: kept-on-gaveUp-full).

- [ ] **Step 1: Write the failing tests** (`Peek` takes `minHeight`; `RetireCapped` is called explicitly by the test, not inside `Peek`):
```c
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
```
Register all five in `main()`.

- [ ] **Step 2: Run to verify they fail** — `run.sh` → FAIL.

- [ ] **Step 3: Implement.** Add `#define CF_REREQ_MAX_RANGE 1000  // == MAX_CFILTERS_RESULTS (BRPeer.h:116)`. Then (verify `_cfLedgerAddGaveUp`/`_cfLedgerRerequestDelay` real names first):
```c
// returns 1 if the entry at index i was removed (moved to gaveUp), 0 if kept (gaveUp full) — 3-outcome.
static int _cfLedgerMoveToGaveUp(BRCFScanLedger *l, size_t i) {
    if (! _cfLedgerAddGaveUp(l, l->outstanding[i].height)) return 0;   // gaveUp full → keep outstanding
    memmove(&l->outstanding[i], &l->outstanding[i+1],
            (l->outstandingCount - i - 1) * sizeof(BRCFOutstanding));
    l->outstandingCount--;
    return 1;
}
void BRCFScanLedgerRetireCapped(BRCFScanLedger *l) {                    // PUBLIC — caller runs once/tick, NOT inside Peek
    for (size_t i = 0; i < l->outstandingCount; ) {
        if (l->outstanding[i].attempts >= CF_REREQ_MAX_ATTEMPTS) { if (! _cfLedgerMoveToGaveUp(l, i)) i++; }
        else i++;
    }
}
static int _cfLedgerDue(const BRCFOutstanding *e, uint32_t now) {       // underflow-guarded
    uint32_t elapsed = (now >= e->requestedAt) ? (now - e->requestedAt) : 0;
    return elapsed >= _cfLedgerRerequestDelay(e);
}
int BRCFScanLedgerPeekRerequestRange(BRCFScanLedger *l, uint32_t now, uint32_t minHeight,
                                     uint32_t *outStart, uint32_t *outStop) {
    size_t start = l->outstandingCount;                                // no retire here (caller's job)
    for (size_t i = 0; i < l->outstandingCount; i++) {
        if (l->outstanding[i].height < minHeight) continue;            // lower-bound cursor
        if (l->outstanding[i].attempts < CF_REREQ_MAX_ATTEMPTS && _cfLedgerDue(&l->outstanding[i], now))
            { start = i; break; }
    }
    if (start == l->outstandingCount) return 0;
    const BRCFOutstanding *s = &l->outstanding[start];
    size_t end = start;
    while (end + 1 < l->outstandingCount) {
        const BRCFOutstanding *nx = &l->outstanding[end + 1];
        if (nx->height != l->outstanding[end].height + 1) break;
        if (nx->attempts >= CF_REREQ_MAX_ATTEMPTS) break;
        if (! _cfLedgerDue(nx, now)) break;
        if (nx->port != s->port || ! UInt128Eq(nx->peer, s->peer)) break;
        if (nx->height - s->height + 1 > CF_REREQ_MAX_RANGE) break;
        end++;
    }
    *outStart = l->outstanding[start].height;
    *outStop  = l->outstanding[end].height;
    return 1;                                                          // NO mutation of the offered run
}
void BRCFScanLedgerCommitRerequest(BRCFScanLedger *l, uint32_t startH, uint32_t stopH,
                                   UInt128 peer, uint16_t port, uint32_t now) {
    for (size_t i = 0; i < l->outstandingCount; i++) {
        uint32_t h = l->outstanding[i].height;
        if (h >= startH && h <= stopH) {
            l->outstanding[i].attempts++;
            l->outstanding[i].requestedAt = now;
            l->outstanding[i].peer = peer;
            l->outstanding[i].port = port;
        }
    }
}
```
Refactor the existing single-height `NextRerequest` to share `_cfLedgerMoveToGaveUp`/`_cfLedgerDue` (its existing KAT proves behavior preservation). Declare the three public fns (`RetireCapped`/`PeekRerequestRange`/`CommitRerequest`) in `.h`.

- [ ] **Step 4: Run to verify** — `run.sh` → all pure cases PASS incl. the existing `NextRerequest` case, `EXIT=0`.
- [ ] **Step 5: Commit** — `feat(cf-ledger): residual peek/commit re-request driver`.

---

## Task 4: integration KAT harness with a send-capture seam

**Files:** Create `native/src/test/host/cf_scan_ledger_drive_kat/{run.sh,cf_scan_ledger_drive_kat_main.c}`.

**Not a verbatim `cf_confirm_kat` clone.**

- [ ] **Step 1: Create `run.sh`** — start from `cf_confirm_kat/run.sh`; change only the `_main.c` name + binary name; add `-DCF_LEDGER_DRIVE_REREQUEST=1`, **`-fsanitize=address -fno-omit-frame-pointer -g`** (LSan must be live — the buffer mallocs), and the link-wrap seam `-Wl,--wrap=BRPeerConnectStatus -Wl,--wrap=BRPeerIsSocketOpen -Wl,--wrap=BRPeerSendGetCFilters -Wl,--wrap=BRPeerSendGetdataBlocks`. **Keep `BRPeerManager.c` OUT of the clang source list** (it is `#include`d — adding it too duplicate-symbols). **Copy cf_confirm_kat's FULL unit list verbatim** (`BRCFScanLedger.c BRWallet.c BRTransaction.c BRMerkleBlock.c BRGCSFilter.c BRWalletFilterElements.c BRNetwork.c BRDigiDollar.c BRDigiAsset.c BRKey.c BRAddress.c BRSet.c BRBase58.c BRBech32.c BRCrypto.c BRBIP32Sequence.c BRBIP39Mnemonic.c crypto/{groestl,skein,qubit,odocrypt}.c crypto/sha3/*.c`) — `#include "BRPeerManager.c"` pulls the wallet-match+getdata path `_cfBufEval` exercises, so an abbreviated list link-under-resolves. Header comment: requires GNU ld (`--wrap`); host CI is Linux/clang.

- [ ] **Step 2: Create `cf_scan_ledger_drive_kat_main.c`** with the harness + `__wrap_` shims (verify exact `BRPeer.h` names): `BRPeerStatus __wrap_BRPeerConnectStatus(BRPeer*)` → `BRPeerStatusConnected`; `int __wrap_BRPeerIsSocketOpen(BRPeer*)` → 1; `void __wrap_BRPeerSendGetCFilters(BRPeer*, uint8_t, uint32_t startH, UInt256)` recording `g_capStart`+`g_capCount`; **`void __wrap_BRPeerSendGetdataBlocks(BRPeer*, const UInt256*, size_t)` recording `g_getdataCount`+`g_getdataHash`** (the buffered-drain credit assertion). Smoke test `ASSERT(CF_LEDGER_DRIVE_REREQUEST==1)`. **Every KAT case ends by calling `BRPeerManagerFree(m)`** so LSan proves the buffer + manager are freed (exercises the new `BRCFScanLedgerFree` path).

- [ ] **Step 3: Run to verify it builds + passes** — `run.sh` → PASS, `EXIT=0`; `bash scripts/run-host-kats.sh; echo "EXIT=$?"` → `EXIT=0`.
- [ ] **Step 4: Commit** — `test(cf-ledger): drive-KAT harness with link-wrap send-capture seam`.

---

## Task 5: wire the buffer + drain + residual re-request into BRPeerManager.c

**Files:** Modify `BRPeerManager.c` — EDIT 1 (drop site, `:2445-2454`), EDIT 2 (`BRPeerManagerKeepAlive`, `:3020-3051`), EDIT 3 (`:2398` + `:2409`), EDIT 4 (re-anchor sites); `BRCFScanLedger.h` (`CF_OUTSTANDING_LOWWATER 3072`, `CF_REREQ_BATCH_PER_TICK 64`); Test `cf_scan_ledger_drive_kat_main.c`.

**Interfaces — Consumes:** Task 2 (`BufferFilter`/`DrainConnected`/`ClearFilterBuffer`), Task 3 (`RetireCapped`/`Peek`/`Commit`), Task 1 (`RecordRequestedDropped`), plus `_BRPeerManagerBlockHashAtHeight`, `_BRPeerManagerRequestCFiltersLocked` (`:3682`), `_BRPeerManagerPeerCanServeFilters` (`:3675`), `BRCompactFilterChainVerifyFilter` + `BRGCSFilterBasicParse` + the wallet-match used at the normal eval path (`:2512`).

- [ ] **Step 1: Write the failing KAT assertions.** Shared setup: `m = BRPeerManagerNew(...)`; `BRPeerManagerSetSyncMode(m, BR_SYNC_MODE_COMPACT_FILTERS_ONLY)`; `pa=BRPeerNew(magic)`, `pb=BRPeerNew(magic)` (never stack literals — the ping sweep derefs a `BRPeerContext` tail), distinct `->address/port`, `->services |= SERVICES_NODE_COMPACT_FILTERS`, `array_add(m->connectedPeers, …)`; a prevBlock-LINKED chain `b_H,b_{H+1},b_{H+2}` (`b_{H+1}->prevBlock=b_H->blockHash` …), `BRSetAdd(m->blocks, …)`, `m->lastBlock=b_{H+2}`. Seed dueness via **`requestedAt=0`** (EDIT 2 reads the real clock — `time(NULL) ≫ 30` ⇒ immediately due; do NOT try to "advance the clock").
  - `test_buffered_drains_and_CREDITS_at_connect()` (the crux): buffer a **wallet-matching** filter for `b_{H+1}` (both its block header and cfheader present); `KeepAlive`; assert **`g_getdataCount==1` and `g_getdataHash == b_{H+1}->blockHash`** (the block was fetched → the receive credits), `MarkEvaluated(H+1)` fired, `BufferedCount==0`, and `g_capCount==0` (no `getcfilters` — the buffer path, not re-request). Merely asserting `MarkEvaluated` is NOT sufficient.
  - `test_buffered_hit_no_peer_stays()` (silent-loss guard): same buffered hit but with **zero CF-capable peers connected**; `KeepAlive`; assert `g_getdataCount==0`, **`MarkEvaluated` did NOT fire** (`scannedThrough` unchanged), **`BufferedCount==1`** (kept), and the height still outstanding — so the next tick retries.
  - `test_buffered_waits_for_cfheader()`: block header present but cfheader NOT (`BRCompactFilterChainNextHeight <= H+1`); `KeepAlive`; assert `BufferedCount==1` (isReady false → not drained yet, no failed-verify discard).
  - `test_residual_rerequests_and_rotates()`: `RecordRequested([H..H+2], pa)`, **buffer EMPTY**; `KeepAlive`; assert `g_capCount==1`, `g_capStart==H`, committed range `H..H+2` to `pb != pa` (rotate-away).
  - `test_residual_gated_by_buffer()`: one buffered entry (header NOT connected → won't drain) + a separate resolvable residual hole; `KeepAlive`; assert `g_capCount==0` (residual re-request is gated off while `BufferedCount>0`).
  - `test_residual_caps_at_tip()`: seed only `b_H,b_{H+1}`, `m->lastBlock=b_{H+1}`, ledger `[H..H+2]`, buffer empty; assert the send covers `H..H+1` and `H+2` stays outstanding `attempts==0`.
  Register all six in `main()`; each ends with `BRPeerManagerFree(m)` (LSan).

- [ ] **Step 2: Run to verify they fail** — `run.sh` → FAIL.

- [ ] **Step 3: Implement EDIT 1** (drop site `:2445-2454`, inside `if(!b)` — the raw cfilter bytes + `blockHash` are in scope here, before verify/parse):
```c
#if CF_LEDGER_DRIVE_REREQUEST
    if (! BRCFScanLedgerBufferFilter(&manager->cfLedger, blockHash, encoded, encodedLen, (uint32_t)time(NULL)))
        ; /* too big / not stored — height stays outstanding for the residual re-request path */
#endif
```
(The raw filter bytes are the `encoded`/`encodedLen` locals used just below at the verify `:2456` and parse `:2472` sites — CONFIRM they are already in scope at the `if(!b)` drop site `:2445`; if the handler slices them only after the block-header check, hoist that slice above `:2445` or buffer from the message payload span so the bytes exist here.)

- [ ] **Step 4: Implement EDIT 2.** `_cfBufEval` uses the SAME real primitives the live eval path `BRPeerManager.c:2484-2518` already uses — `BRWalletGetFilterElements(m->wallet)` → `BRGCSFilterMatchAny(gcs, fe->elements, fe->elementLens, fe->count)` → on hit `BRPeerSendGetdataBlocks(peer, &blockHash, 1)`. Do NOT reimplement the match; call `BRGCSFilterMatchAny`. (Optional: factor a small `(m, fe, gcs, blockHash, peer)` helper to share the ~few-line match+getdata with the live path — nice-to-have, not required; the correctness requirement is reusing the real fns, not writing a placeholder.) Then define the drain trampolines (the pure module stays BRPeerManager-free via the fn pointers). **`isReady` requires BOTH block header AND cfheader; `_cfBufEval` threads `blockHash`, fetches the block on a hit BEFORE `MarkEvaluated`, and KEEPS the entry (returns 0) when a hit cannot dispatch:**
```c
#if CF_LEDGER_DRIVE_REREQUEST
struct _cfDrainCtx { BRPeerManager *m; BRWalletFilterElements *elems; };   // elems fetched ONCE per batch (perf)
static int _cfBufIsReady(void *vctx, UInt256 h, uint32_t *outH) {
    BRPeerManager *m = ((struct _cfDrainCtx*)vctx)->m;
    BRMerkleBlock *b = BRSetGet(m->blocks, &h);
    if (! b || b->height == BLOCK_UNKNOWN_HEIGHT) return 0;                 // block header not connected
    if (BRCompactFilterChainNextHeight(m->compactFilterChain) <= b->height) return 0;  // cfheader not yet present (verify would fail)
    *outH = b->height; return 1;
}
static int _cfBufEval(void *vctx, uint32_t height, UInt256 blockHash, const uint8_t *bytes, size_t len) {
    struct _cfDrainCtx *c = vctx; BRPeerManager *m = c->m;
    if (! BRCompactFilterChainVerifyFilter(m->compactFilterChain, height, bytes, len)) return 1; // bad bytes: drop, leave outstanding (re-request)
    BRGCSFilter *gcs = BRGCSFilterBasicParse(bytes, len, blockHash);        // ★ blockHash is the SipHash key (3-arg)
    if (! gcs) return 1;                                                    // unparseable: drop, leave outstanding
    int hit = BRGCSFilterMatchAny(gcs, c->elems->elements, c->elems->elementLens, c->elems->count); // real match (see :2488)
    BRGCSFilterFree(gcs);
    if (hit) {
        BRPeer *p = NULL;                                                   // a connected CF-capable peer for the getdata
        for (size_t i = array_count(m->connectedPeers); i > 0; i--)
            if (_BRPeerManagerPeerCanServeFilters(m->connectedPeers[i-1])) { p = m->connectedPeers[i-1]; break; }
        if (! p) return 0;                                                  // ★ hit but no peer → KEEP buffered, stay outstanding, retry
        BRPeerSendGetdataBlocks(p, &blockHash, 1);                          // ★ credit: fetch the block → tx registered on arrival
    }
    BRCFScanLedgerMarkEvaluated(&m->cfLedger, height);                      // scanned (hit dispatched, or clean verified miss)
    return 1;                                                              // remove from buffer
}
#endif
        …
#if CF_LEDGER_DRIVE_REREQUEST
    {
        uint32_t nowSec = (uint32_t)time(NULL);
        uint8_t scratch[1024];                              // ≥ max cfilter (675 B observed)
        struct _cfDrainCtx dctx = { manager, BRWalletGetFilterElements(manager->wallet) }; // (a) elements ONCE per batch
        BRCFScanLedgerDrainConnected(&manager->cfLedger, _cfBufIsReady, &dctx,
                                     scratch, sizeof scratch, _cfBufEval, CF_FILTER_DRAIN_PER_TICK);
        BRWalletFilterElementsFree(dctx.elems);             // free once (verify exact accessor/free names)
        // residual best-effort re-request — GATED on an empty buffer so undrained-but-outstanding
        // header-race heights are never duplicate-requested (they belong to the buffer path).
        if (BRCFScanLedgerBufferedCount(&manager->cfLedger) == 0) {
            BRCFScanLedgerRetireCapped(&manager->cfLedger);
            uint32_t tipH = manager->lastBlock ? manager->lastBlock->height : 0, minH = 0;
            for (unsigned n = 0; n < CF_REREQ_BATCH_PER_TICK; n++) {
                uint32_t rs=0, re=0;
                if (! BRCFScanLedgerPeekRerequestRange(&manager->cfLedger, nowSec, minH, &rs, &re)) break;
                uint32_t cap = (re <= tipH) ? re : tipH;
                if (cap < rs) { minH = re + 1; continue; }
                UInt128 avoidA = UINT128_ZERO; uint16_t avoidP = 0;
                for (size_t i=0;i<manager->cfLedger.outstandingCount;i++)
                    if (manager->cfLedger.outstanding[i].height==rs){ avoidA=manager->cfLedger.outstanding[i].peer;
                        avoidP=manager->cfLedger.outstanding[i].port; break; }
                BRPeer *chosen=NULL,*any=NULL;
                for (size_t i=array_count(manager->connectedPeers);i>0;i--){ BRPeer *p=manager->connectedPeers[i-1];
                    if (!_BRPeerManagerPeerCanServeFilters(p)) continue; if(!any) any=p;
                    if (avoidP!=0 && p->port==avoidP && UInt128Eq(p->address,avoidA)) continue; chosen=p; break; }
                if (!chosen) chosen=any; if (!chosen) break;
                size_t sent=_BRPeerManagerRequestCFiltersLocked(manager, rs, cap, chosen);
                if (sent>0){ BRCFScanLedgerCommitRerequest(&manager->cfLedger, rs, cap, chosen->address, chosen->port, nowSec);
                    peer_log(chosen,"cf-ledger: re-requested residual holes [%u..%u]", rs, cap); }
                minH = re + 1;
            }
        }
        manager->cfLedger.lastDriveAt = nowSec;
    }
#endif
```
**Dispatch-vs-delivery (known pre-existing gap — do NOT scope-creep a fix):** after `getdata` is dispatched and the height `MarkEvaluated`, if the peer dies before the block arrives the block is not fetched — **identical to the LIVE match path's behaviour at `:2518`** (it also `getdata`s and moves on). Since the live path shares this window, it is a documented pre-existing gap (candidate for the confirmation-twin / pending machinery in a later phase), NOT a Phase-2 fix. (Verify names with grep: `BRWalletGetFilterElements`/`…Free`, `BRWalletFilterElements`, `BRCompactFilterChainNextHeight`/`…VerifyFilter`, `BRGCSFilterBasicParse(bytes,len,blockHash)`, `BRPeerSendGetdataBlocks`, `m->compactFilterChain`/`m->wallet`, `BLOCK_UNKNOWN_HEIGHT`, `BRGCSFilterMatchAny` + the `BRWalletFilterElements` field names (`->elements`/`->elementLens`/`->count`).)

- [ ] **Step 5: Implement EDIT 3** — two gated edits: (1) back-pressure — widen the forward-fetch guard at `:2398` to `if (manager->autoFetchCFiltersEnabled #if CF_LEDGER_DRIVE_REREQUEST && BRCFScanLedgerOutstandingCount(&manager->cfLedger) < CF_OUTSTANDING_LOWWATER #endif ) {`; (2) loud overflow log — at the forward RecordRequested caller `:2409`, swap to `BRCFScanLedgerRecordRequestedDropped(&manager->cfLedger, reqStart, reqStop, peer->address, peer->port, (uint32_t)time(NULL), &dLo, &dHi)` and `peer_log(peer, "cf-ledger: OUTSTANDING OVERFLOW — dropped %d oldest holes [%u..%u]", nDrop, dLo, dHi)` when `nDrop>0` (confirm `reqStart`/`reqStop`/`peer` are in scope there).

- [ ] **Step 6: Implement EDIT 4** — at every re-anchor / wipe site, add gated `BRCFScanLedgerClearFilterBuffer(&manager->cfLedger);` (target d — stale buffered bytes from the old scan must not survive a floor change): `BRPeerManagerEnableAutoCompactFilterFetch` re-arm (`:3762`), the snap-up re-anchor (`:2073`), `_BRPeerManagerReanchorAtFloorLocked` (`:3530`), **and `BRPeerManagerDisableAutoCompactFilterFetch` (`:3772`)** (hygiene — a disable must not leave stale bytes lingering). Grep for every `autoFetchCFiltersStart =` write to confirm the set is complete.

- [ ] **Step 7: Run to verify** — `run.sh` → PASS; `bash scripts/run-host-kats.sh; echo "EXIT=$?"` → `EXIT=0` (ASan clean — no buffer leak).
- [ ] **Step 8: Commit** — `feat(cf-ledger): buffer header-race drops, drain at header-connect, residual re-request`.

---

## Task 6: flip the gate + full suite

- [ ] **Step 1:** Flip `BRCFScanLedger.h:63` inside the `#ifndef` guard → `1`.
- [ ] **Step 2:** `bash scripts/run-host-kats.sh; echo "EXIT=$?"` → `EXIT=0`, ALL dirs PASS (ASan incl.). If red, STOP + fix.
- [ ] **Step 3: Commit** — `feat(cf-ledger): Phase 2 — flip CF_LEDGER_DRIVE_REREQUEST to 1`.

---

## Task 7: build + on-device acceptance

**This IS the acceptance gate.**

- [ ] **Step 1: Build** — `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug 2>&1 | tail -5` → `BUILD SUCCESSFUL`.
- [ ] **Step 2: Own-node rig** — local `digibyted` `listen=1`, `blockfilterindex=1`, `peerblockfilters=1`, synced past 23,920,918; emulator pinned EXCLUSIVE to `10.0.2.2:12024`.
- [ ] **Step 3: Install + re-wedge** — install APK; `run-as` delete `saved_cf_ledger*.bin`/`saved_filter_headers*.bin`/saved-blocks, force `cf_birth_height=23900000`; start sync.
- [ ] **Step 4: Observe** (`getCfScanLedgerCounts` + `bread`): `outstanding` climbs to ~1001 at the floor, buffered filters drain as headers connect (`cf-ledger: buffered filter drained @ H`), `outstanding` → 0, `scannedThrough` → tip. `gaveUp` stays 0.
- [ ] **Step 5: ACCEPTANCE GATE (verbatim):** same seed, same pinned own-node, same on-chain $1 DD (block **23,920,918**, txid `813d6e46…`). **Wipe → restore seed → sync.** PASS iff ALL: (1) the DD credits via **CF alone (no "Scan for missing funds")**; (2) `outstanding` **drains to 0** and `scannedThrough` reaches the block tip; (3) **`gaveUp` stays 0**; (4) **no native SIGSEGV** and **no ASan/leak** across the run. Steady-state (ongoing): `gaveUp` 0 over a week; `outstanding` drains within minutes of any blip (**`outstanding` NOT draining is the primary alarm**); zero manual reconciles. If (1)-(4) don't all hold, STOP — do not release.
- [ ] **Step 6:** Append drain evidence to `docs/bugs/2026-07-26-…md` under "Phase-2 DRAIN — CONFIRMED". Commit.

---

## Task 8: ship

- [ ] **Step 1: Push submodule fork FIRST** — branch `seq/cf-scan-ledger-drive` off core `develop`; `git push johnnylaw HEAD:refs/heads/seq/cf-scan-ledger-drive`.
- [ ] **Step 2: Bump the pin + stage the KAT dir in the SAME app commit** (explicit `git add`, never `-A`).
- [ ] **Step 3: Version bump** — `app/build.gradle.kts` +1 / patch; mirror `CLAUDE.md`.
- [ ] **Step 4: Commit + push + open PR** — `gh pr create --repo JohnnyLawDGB/digibytewallet-android --head JohnnyLawDGB:seq/cf-scan-ledger-drive --base develop …` (footer). No self-merge.
- [ ] **Step 5: Watch CI green** (Analyze, CodeQL, build, **Native host KATs**). Report PR URL + CI.
- [ ] **Step 6: Pre-publish suite** — `./scripts/pre-publish-test.sh` (API 28/33/34/35) before any tag.

---

## Self-Review notes (executor)
- **Primary drain = buffer-the-bytes** (header-race, the floor cluster); **residual re-request** = best-effort for verify/parse/disconnect (36-min retention ≫ 7.5-min retry, no pinning). The pruner is never coupled to the ledger.
- **Four hostile targets designed in:** (a) drain bounded at `CF_FILTER_DRAIN_PER_TICK` in the tick, not inline at header-connect; (b) verify happens in `_cfBufEval` at drain time (header connected ⇒ cfheader present); (c) FIFO byte-budget eviction, evicted → outstanding → re-request; (d) `ClearFilterBuffer` on every re-anchor/wipe.
- **Buffer is in-memory only, NOT persisted** — process death falls back to the normal floor re-anchor. Free all bytes in `BRCFScanLedgerFree`; the drive-KAT runs under ASan to prove no leak.
- **Byte budget 256 KB** vs observed cfilters (median 4 B / max 675 B) — orders of magnitude of headroom over a ~1000-filter re-anchor burst.
- **DRY:** `_cfBufEval` must reuse the normal eval path's wallet-match + getdata (factor it out of `:2512`), not duplicate it.
- **Verify names with `grep -n` before each edit:** `BRCompactFilterChainVerifyFilter`, `BRGCSFilterBasicParse`/`Free`, `m->compactFilterChain`, `BLOCK_UNKNOWN_HEIGHT`, the raw-filter-bytes locals at `:2445`, `BRPeerManagerSetSyncMode`/`BR_SYNC_MODE_COMPACT_FILTERS_ONLY`, `BRCFScanLedgerFree`.
