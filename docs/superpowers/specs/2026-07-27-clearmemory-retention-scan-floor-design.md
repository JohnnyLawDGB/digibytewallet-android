# CF retention floor tracks the scan frontier + single-descent stop-hash batching — design (REV 2)

**Status:** DESIGN, post-hostile-review REV 2 (3-lens panel returned unanimous REVISE on REV 1; all findings + the operator's single-descent-batching decision folded in). Fixes the dominant on-device CF wedge (`docs/bugs/2026-07-27-cf-lab-wedge-clearmemory-prunes-scan-floor.md`).

## Problem (unchanged)
`_BRPeerManagerClearMemory` (`BRPeerManager.c:1243`, sole call site `_peerRelayedBlock:1502`, fires once `BRSetCount(manager->blocks) >= CLEAR_MEM_BLOCKS_COUNT_TRIGGER`=5000) retains headers down to `cfFloor = cfNext − 144` where `cfNext` is the **cfheader** frontier. cfheaders burst to the tip while the cfilter **scan** lags → the scan-floor headers get pruned → `_cfBufIsReady` (`BRSetGet(blocks)` → NULL) and residual stop-hash (`_BRPeerManagerBlockHashAtHeight` → ZERO) both die → permanent wedge. Reorg-independent, peer-independent (proven by a clean relaunch: `regress=0`, `22686→856` prune, scan frozen at floor).

**Invariant:** never prune a header the CF scan or its residual re-request still needs; bound the retained span with an explicit memory ceiling whose loss is VISIBLE, not silent; keep the enlarged span ANR-safe.

## Global Constraints
- Submodule C only for the core change; outer host-KAT. Fork branch off core `develop` tip `e9246c8`.
- `bash scripts/run-host-kats.sh; echo "EXIT=$?"` → `EXIT=0` (never pipe to head/tail).
- Commit co-author `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Pinned unchanged: `CLEAR_MEM_BLOCKS_COUNT_TRIGGER 5000`, `CLEAR_MEM_CF_RETENTION_MARGIN 144`, `CF_GAVEUP_MAX 512`, `CF_OUTSTANDING_MAX 4096`.
- Acceptance: LAB re-run credits the $1 DD (block 23,920,918) via CF alone, `outstanding→0`, no OOM, no ANR/wedge.

## Facts settled by grounding + the 3-lens review (do not re-derive)
- `_cfLedgerAdvance` pins `scannedThrough = min(outstanding[0].height, gaveUp[0]) − 1`, so **`scannedThrough+1` already folds in gaveUp**. gaveUp-inclusion is CORRECT and REQUIRED: a height that is both buffered (header-race bytes) and gaveUp (retry-exhausted) needs its header for `_cfBufEval` to eventually drain+credit it — excluding gaveUp silently loses that receive. **Consequence: a single permanent hole pins the floor forever → the span is NOT self-bounding → the ceiling is a ROUTINE mechanism, not a rare backstop.**
- No `gaveUp→outstanding` per-height retry path exists (grepped) — gaveUp is only reported + defensively removed by `MarkEvaluated`; the residual driver iterates only `outstanding`.
- `outstanding[]`/`gaveUp[]` are sorted ascending (insert lower-bound+memmove) → O(1) heads.
- Each `_BRPeerManagerBlockHashAtHeight` step is `BRSetGet(blocks,&prevBlock)` (UInt256 key) = hash+probe+cache-miss, NOT a pointer deref. 6 callers; the deep ones are the residual path (`:3328→:3978→:3988/:4040`, ≤64/tick).
- Persistence is hard-capped at `SAVE_BLOCK_COUNT`=300 — a larger `manager->blocks` does NOT enlarge what is serialized. No array is sized to `BRSetCount(blocks)`; `BRSet` grows dynamically. No CORRECTNESS dependency on a small set (relay/reorg walks are hash/prevBlock-bounded). The only hazards of a larger set are memory + the prevBlock walk depth — both addressed below.
- `BRMerkleBlock` ≈ 220 B (incl. powHash + set overhead), header-only in CF-only mode (hashes/flags NULL).
- The existing test helper `dummyBlock` sets `blockHash = memset(seed)` with a `uint8_t` seed → only 256 distinct hashes; `BRSet` dedups by hash so a naive 5000-block build caps at ~256 and NEVER fires the trigger (false-green trap).

---

## Part 1 — retention floor tracks the scan frontier (`BRCFScanLedger` + `BRPeerManager`)
Add an O(1) reporter (gaveUp-inclusive by construction — it is exactly `scannedThrough+1`, documented so no one "simplifies" it to exclude gaveUp):
```c
// BRCFScanLedger.h/.c — O(1). Lowest height the CF scan still needs a header for.
// == scannedThrough+1, which _cfLedgerAdvance already caps at min(outstanding[0],
// gaveUp[0]) — so this covers outstanding, gaveUp (retry-exhausted holes whose
// buffered bytes still need the header to drain+credit), AND buffered heights
// (buffered ⊆ outstanding∪gaveUp by construction). Do NOT change to exclude gaveUp:
// a buffered+gaveUp height would lose its header and its receive is silently lost.
uint32_t BRCFScanLedgerLowestNeededHeight(const BRCFScanLedger *l)
{
    uint32_t lo = l->scannedThrough + 1;
    if (l->abandonedBelow > lo) lo = l->abandonedBelow;   // Part 3 hard floor
    return lo;
}
```
`_BRPeerManagerClearMemory` cfNext branch (`:1259-1261`) becomes (bootstrap branch `:1273-1276` gets the analogous `min(autoFetchCFiltersStart, lowestNeeded)`):
```c
uint32_t cfNext = BRCompactFilterChainNextHeight(manager->compactFilterChain);
uint32_t lowestNeeded = BRCFScanLedgerLowestNeededHeight(&manager->cfLedger);
uint32_t floorH = (lowestNeeded < cfNext) ? lowestNeeded : cfNext;
floorH = _cfApplyRetentionCeiling(manager, floorH);        // Part 3
cfFloor = (floorH > CLEAR_MEM_CF_RETENTION_MARGIN) ? floorH - CLEAR_MEM_CF_RETENTION_MARGIN
        : (floorH > 0 ? 1 : 0);
```
Steady state (scan caught up, `outstanding`/`gaveUp` empty): `lowestNeeded == cfNext` → `min` collapses to today's `cfNext−144` (no regression).
**The descent runs in full** — REV 1's Part-4 early-out is REJECTED (it skipped the only code that calls `BRMerkleBlockFree`, so nothing freed → OOM). Optional perf follow-up: track a lowest-retained pointer to shorten the descent; NOT in this fix.

## Part 2 — single-descent stop-hash batching (kills the ANR the raised floor would create)
The raised floor makes `_BRPeerManagerBlockHashAtHeight` walk up to `MAX_SPAN` deep, ≤64×/tick, under `manager->lock` — the recurring ANR class. Fix WITHOUT a persistent index (a stale height→hash entry would hand `getcfilters` a wrong stop-hash = a NEW silent wrong-range-fetch loss surface, rejected immediately before acceptance). Instead, **batch the residual tick into ONE descent**:
- Restructure the residual loop (`KeepAlive:3245-3330`): **Pass A** — run the peek loop to COLLECT all due `(rs, cap)` ranges into a per-tick scratch array (no send, no hash resolution). **Pass B** — gather all `rs`+`cap` heights, one descent from `lastBlock` (sorted-descending emit) resolving every height→hash in a single O(span) walk (`_BRPeerManagerResolveHashesAtHeightsLocked(manager, heights[], n, outHashes[])`). **Pass C** — send each range with its pre-resolved start/stop hashes + `CommitRerequest`.
- `_BRPeerManagerRequestCFiltersLocked` gains pre-resolved-hash parameters (or a sibling that takes them) so it does not re-walk. Forward (`:2497`) + cfheaders (`:2119/:2156`) resolve near the tip (shallow) — leave them, or route through the same helper for uniformity (shallow, cheap).
- Correct-by-construction from the current chain view each tick; per-tick scratch, no persistent state, no reorg-invalidation. Result: **64 walks of depth D → 1 walk of depth D**, so `MAX_SPAN` (Part 3) bounds MEMORY only; the walk is structurally 1×/tick (~30 ms at 100k, once per ~10 s — sub-ANR).

## Part 3 — tip-anchored memory ceiling with VISIBLE abandonment
Retained span = `lastBlock->height − cfFloor`. Anchor the clamp to the **tip** (not cfNext — else span = (tip−cfNext)+MAX_SPAN, unbounded while headers lead cfheaders). Add:
```c
#define CF_RETENTION_MAX_SPAN 30000   // ~6.6 MB @220B/header. Size for OOM-prone devices; hostile-review the value.
```
`_cfApplyRetentionCeiling(manager, floorH)`:
```c
uint32_t tip = manager->lastBlock ? manager->lastBlock->height : 0;
if (tip > CF_RETENTION_MAX_SPAN) {
    uint32_t clamp = tip - CF_RETENTION_MAX_SPAN;
    if (floorH < clamp) {
        // Abandon ONLY retry-exhausted (gaveUp) heights below clamp — NEVER a
        // still-retrying outstanding hole (cfNext racing past a hole with
        // attempts<MAX is not a reason to abandon a recoverable payment). If the
        // lowest hole below clamp is still outstanding, floorH stays there and the
        // span transiently exceeds MAX_SPAN (bounded by the retry window ≤7.5 min ×
        // header rate) until that hole resolves or is retired to gaveUp.
        uint32_t newFloor = BRCFScanLedgerAbandonGaveUpBelow(&manager->cfLedger, clamp);
        // returns the lowest STILL-NEEDED height after abandoning gaveUp<clamp;
        // advances abandonedBelow to min(clamp, lowest-still-outstanding).
        if (newFloor > floorH) floorH = newFloor;
    }
}
return floorH;
```
`BRCFScanLedgerAbandonGaveUpBelow(l, clamp)` (ledger, O(1)-amortized):
- Drop `gaveUp[]` entries `< clamp` (retry-exhausted; their headers are about to be pruned — recovery is a full reconcile/rescan, not a per-height retry).
- Advance the **`abandonedBelow`** watermark to the highest abandoned height+1, but NOT past the lowest still-**outstanding** height (never abandon a retrying hole).
- Return the new lowest-still-needed height (the new floor target).
- `abandonedBelow` is a **hard floor**: `PeekRerequestRange` starts at `minHeight = max(minHeight, abandonedBelow)`; `RecordRequested`/`MarkHeaderRace` reject heights `< abandonedBelow`. It only ever advances.
- **Not silent:** `scannedThrough` is ALLOWED to advance over the abandoned band (it is "processed-abandoned", so the forward scan proceeds above), BUT `abandonedBelow` is **persisted** (bump the Serialize/Parse version) and **surfaced** through a new JNI/status accessor `BRCFScanLedgerAbandonedBelow(l)` → the UI reports "**N blocks abandoned (too deep to retain) — rescan to recover**" as a category DISTINCT from scanned. `deriveSyncFrontier`/completeness must read both `scannedThrough` and `abandonedBelow`; "synced" is qualified by the abandoned count. Abandonment is a reported, countable event — visible loss, never a silent scannedThrough leap presented as complete.

## Part 4 — production-scale red-before-green KAT
New `native/src/test/host/cf_clearmemory_retention_kat/` (or extend `cf_scan_ledger_drive_kat`, which `#include`s `BRPeerManager.c` → statics + `manager->blocks`/`cfLedger`/`compactFilterChain` reachable). MUST cross the real trigger.
- **Distinct-per-height hashes** — write the height into the 32-byte hash (NOT `dummyBlock`'s single-byte fill, which collides at 256). Build a `prevBlock`-LINKED chain of **>5000** headers with an unbroken **>800-deep** chain from `lastBlock` (the tail-find hops back `TRIGGER−TAIL_LEN`=800). `BRCFScanLedgerInit(&l, floor)` then `RecordRequested(H_floor)` where `H_floor < tip−801` AND `H_floor < cfNext−144` (else it survives on unfixed code = false-green).
- **Assert `BRSetCount(manager->blocks) ≥ 5000` BEFORE the pass** (proves the prune body runs).
- **Red-before-green:** after `_BRPeerManagerClearMemory`, assert `BRSetGet(blocks, &H_floor_hash) != NULL` (floor header survives). FAILS on current `cfNext−margin`; PASSES on `min(cfNext,lowestNeeded)−margin`. Run.sh builds a `-DRETENTION_UNFIXED` shape (or pre-edit source) and requires unfixed=RED, fixed=GREEN.
- **Descent-frees case:** blocks below `cfFloor` AND below the tail boundary exist → assert they ARE freed (guards against a Part-4-style leak).
- **Ceiling case:** `#ifndef`-guard `CF_RETENTION_MAX_SPAN` and `-D`-override it small (e.g. 4000) for a case where `tip−lowestNeeded > MAX_SPAN`; assert `abandonedBelow` advanced to `tip−MAX_SPAN`, sub-clamp headers FREED, `BRSetCount` bounded to ~MAX_SPAN, and a still-**outstanding** (attempts<MAX) hole below clamp is NOT abandoned.
- **Batching correctness:** batched `_BRPeerManagerResolveHashesAtHeightsLocked` returns the SAME hashes as N individual `_BRPeerManagerBlockHashAtHeight` calls (property test over random heights incl. tip, floor, gaps, out-of-range→ZERO).
- **Silent-loss guard:** abandon a deep band, `MarkEvaluated` a height above clamp, assert `abandonedBelow` reflects the band AND the status surfaces it (not folded into a "complete" claim without the abandoned count).

## Out of scope (follow-ups; note, don't do)
- **height→hash persistent index memoization** — the definitive O(1) walk fix, but a new correctness surface (stale entry → wrong stop-hash) in the most concurrency-sensitive code; batching (Part 2) removes the ANR without it. Evidence-gated on the LAB re-run's floor-cluster drain.
- **Header-sync pacing** (throttle so cfheaders can't outrun the scan) — would keep the span small and make Part 3 rarely fire. Separate spec.
- **`BRCFScanLedgerMarkHeaderRace` is dead in production** (only the KAT calls it) — the 10s header-race retry isn't wired. Orthogonal Phase-2 gap.

## Task decomposition (for SDD)
1. `BRCFScanLedgerLowestNeededHeight` + `abandonedBelow` field + `BRCFScanLedgerAbandonGaveUpBelow` + hard-floor guards in Peek/RecordRequested/MarkHeaderRace + Serialize/Parse version bump + `BRCFScanLedgerAbandonedBelow` accessor — pure ledger, host-KAT.
2. `_BRPeerManagerResolveHashesAtHeightsLocked` batch resolver + prove-equivalent KAT.
3. Residual-loop restructure (Pass A/B/C) to use the batch resolver; `_BRPeerManagerRequestCFiltersLocked` pre-resolved-hash variant.
4. `_BRPeerManagerClearMemory` floor = `min(cfNext, lowestNeeded)` + `_cfApplyRetentionCeiling` (both branches), full descent — the production-scale KAT (Part 4).
5. JNI/status plumbing for `abandonedBelow` → UI "abandoned (too deep)" surfacing.
6. Whole-branch review, then the LAB acceptance re-run.
