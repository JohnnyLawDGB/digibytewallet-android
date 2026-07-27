# CF LAB-acceptance wedge — ClearMemory prunes the scan floor out from under the cfilter scan (2026-07-27)

**Status:** ROOT-CAUSED (live wedge captured). NOT the stale-buffer livelock Track B fixed. A distinct **architectural** gap: block-header retention tracks the cfheader frontier, not the cfilter scan frontier.

**Context:** first LAB acceptance run of the Track B stale-buffer-livelock fix (core#5 + app#37 on develop, native BuildId `04c4d3f7`). Emulator `dgb-test-api35`, own-node exclusive (`10.0.2.2:12024`), seed-safe soft-wipe, `cf_birth_height=23900000`, reconcile disarmed. Target: credit the $1 DD at block 23,920,918 via CF alone.

## Symptom
Permanent wedge, ~30 min, no credit. Terminal ledger:
```
cf-ledger: scannedThrough=23900000 outstanding=2856 gaveUp=0 pending=2   (frozen)
```
- ✅ Track A exclusive pin works (drops public peers; 1 peer).
- ✅ Wire works: cfheaders synced to tip (23,926,477), **2597 cfilters received**, KeepAlive ticking (pings/pongs /10s), **zero SIGSEGV** in 30 min.
- ❌ `scannedThrough` frozen at the floor; `outstanding` pinned at 2856; `gaveUp` 0; **residual re-request NEVER fired** (0); forward cfilter cursor stalled at 23,903,001; no balance/DD.

## Root cause (the smoking gun)
```
10:15:59  [MEMORY]: Blocks reduced from 18595 to 856 blocks
```
`_BRPeerManagerClearMemory` (`BRPeerManager.c:1243`) ran and pruned `manager->blocks` from 18,595 → **856** entries — a small window at the tip. Its retention floor:
```c
uint32_t cfNext = BRCompactFilterChainNextHeight(manager->compactFilterChain);   // :1259
if (cfNext > CLEAR_MEM_CF_RETENTION_MARGIN) cfFloor = cfNext - CLEAR_MEM_CF_RETENTION_MARGIN;  // :1260
...
if (cfFloor > 0 && blockPtr->height >= cfFloor) continue;   // keep at/above cfFloor; else prune  // :1290
```
**`cfFloor = cfNext − margin`.** cfNext (the cfHEADER frontier) raced to the tip (23,926,477) while the cfILTER scan was stuck at the floor (`scannedThrough=23900000`). So ClearMemory pruned every block below ~`cfNext−margin` — **including the entire floor cluster 23,900,000–23,903,001 where all 2856 outstanding cfilters live.**

### Why that wedges BOTH recovery paths at once
The floor block headers are gone, and **every** path to evaluate a floor cfilter needs that header:
1. **Buffer-drain dead** — `_cfBufIsReady` (`:3160`) does `BRMerkleBlock *b = BRSetGet(m->blocks, &h); if (!b …) return 0;`. Pruned → NULL → never ready → the buffered floor cfilters never drain, and **never even reach `_cfBufEval`** (so the verify-fail eviction path that would free the height for residual is never triggered — it is gated behind isReady). **Buffer-the-bytes did NOT remove the pruning dependency; `_cfBufIsReady` reintroduces it.**
2. **Residual re-request dead** — to build a `getcfilters`, `_BRPeerManagerRequestCFiltersLocked` resolves the stop-hash via `_BRPeerManagerBlockHashAtHeight(cap)` (`:1988`), which walks `manager->blocks`. Pruned → `UINT256_ZERO` → the send fails silently (`sent=0`), so `CommitRerequest` is never called, no "re-requested residual" log, **and no attempt is ever bumped → the height never reaches the cap → `gaveUp` stays 0.** This is exactly the observed `gaveUp=0` + zero residual logs.
3. **Forward cursor stall** is a *symptom*: forward auto-fetch is windowed ahead of `scannedThrough`; with `scannedThrough` frozen at the floor, the window caps the forward cursor at ~floor+3000 (23,903,001). Not an independent defect.

## Discriminator dumps (operator's three questions)
1. **Buffer resolution:** the buffered floor hashes do NOT resolve (`BRSetGet`→NULL, pruned). They do **not** hit `_cfBufEval` verify-fail eviction because `_cfBufIsReady` returns false *first* — eviction is gated behind readiness. (Age-out will reclaim the bytes after 900 s, but the *height* stays outstanding.)
2. **Forward cursor:** stalled because windowed on the frozen `scannedThrough`, not an independent gate.
3. **Floor headers:** **PRUNED** — the `18595→856` ClearMemory pass, retention `cfNext−margin`, cfNext at tip.

## The reorg's role (not exculpatory)
A header excursion (23.9M → 22.5M back-fill → 23.9M; `saved_blocks: skipping regressive write`) preceded the wedge and likely *stalled the scan at the floor long enough* for cfheaders to race ahead and trip `CLEAR_MEM_BLOCKS_COUNT_TRIGGER`. But the wedge **mechanism** is the retention-floor-vs-scan-floor mismatch, which is reproducible **any time the cfheader frontier outpaces the cfilter scan by > the ClearMemory trigger** — cfheaders are tiny and sync in a burst; cfilter eval requires wallet matching and lags. **A reorg is a reliable trigger, not the only one. "Works after restart" is a workaround, not a pass** — header regressions happen in production.

## Fix direction (architectural)
Retention must cover the whole `[scan-floor .. cfNext]` window the CF scan still needs — not just `[cfNext−margin .. cfNext]`. Options, in order of preference:
- **`cfFloor = min(cfNext, lowestNeededCFHeight) − margin`**, where `lowestNeededCFHeight` = the ledger's lowest outstanding/unevaluated CF height (≈ `scannedThrough+1`). Retain every block from the scan floor up. This is the correct invariant: **never prune a block header the CF scan or its residual re-request still needs.**
- Secondary: bound how far cfheaders may outrun the cfilter scan (pause cfheader advance / ClearMemory while `cfNext − scannedThrough` exceeds the retention window), so the two frontiers can't diverge past the retention margin.

## Next-KAT (the gap the host KATs couldn't reach)
The drive-KAT models the residual loop but not ClearMemory pruning nor a cfheader-frontier-races-ahead-of-scan scenario. Add a host KAT: seed outstanding floor heights, advance cfNext to the tip, run ClearMemory, assert the scan floor's headers are RETAINED (and the buffer/residual can still make progress). Red-before-green against the current `cfNext−margin` retention.

## Artifacts
- Filtered evidence logcat: `docs/bugs/2026-07-27-cf-lab-wedge-evidence-logcat.txt`
- Native BuildId under test: `04c4d3f78fb649869a849d3db16a547708d8902f` (Track B).
