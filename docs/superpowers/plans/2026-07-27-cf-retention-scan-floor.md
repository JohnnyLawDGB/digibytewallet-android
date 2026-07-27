# CF retention floor + single-descent batching — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** Stop `_BRPeerManagerClearMemory` pruning the block headers the CF scan still needs (retention must track the scan frontier, not just cfNext), bound the enlarged retained span with a tip-anchored memory ceiling whose loss is VISIBLE, and keep the enlarged span ANR-safe by batching the residual stop-hash resolution into one descent per tick.

**Architecture:** Design doc `docs/superpowers/specs/2026-07-27-clearmemory-retention-scan-floor-design.md` (REV 2, hostile-reviewed). Fix isolated by the 2026-07-27 LAB acceptance wedge (`docs/bugs/2026-07-27-cf-lab-wedge-clearmemory-prunes-scan-floor.md`).

**Tech Stack:** C (breadwallet-derived native core, submodule `digibytewallet-core`), host KATs (`scripts/run-host-kats.sh`, ASan/LSan).

## Global Constraints
- Submodule C: `native/src/main/jni/digibytewallet-core/` (fork `JohnnyLawDGB/digibytewallet-core`, branch off core `develop` tip `e9246c8`). Push fork before pin bump. Submodule git: `GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core GIT_WORK_TREE=native/src/main/jni/digibytewallet-core git …`.
- Host KATs: `bash scripts/run-host-kats.sh; echo "EXIT=$?"` → `EXIT=0`. NEVER pipe to head/tail.
- Commit co-author `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Merges to develop need HUMAN approval; core PR before app PR; `merge-base --is-ancestor` before the app pin bump.
- Pinned unchanged: `CLEAR_MEM_BLOCKS_COUNT_TRIGGER 5000`, `CLEAR_MEM_CF_RETENTION_MARGIN 144`, `CF_GAVEUP_MAX 512`, `CF_OUTSTANDING_MAX 4096`.
- **Scope: Tasks 1–4 (this branch) unblock the LAB re-run. Task 5 (JNI/UI surfacing of `abandonedBelow`) is a follow-up before wide release** — it can't affect the re-run (`abandonedBelow` stays 0 for the no-permanent-hole test wallet), BUT Task 1 MUST WARN-log any abandonment so that "abandonedBelow==0" is a verified log fact, not an assumption.
- **Task ORDER is load-bearing: 1 → 2 → 3 → 4. Task 2's equivalence KAT MUST be green before Task 3 starts** (Task 3 builds on the resolver; debugging both unproven at once costs days).
- Every eviction/abandonment path touches ONLY its intended fields; never silently advances `scannedThrough` presenting abandoned heights as scanned-complete.

---

### Task 1: ledger — LowestNeededHeight + abandonedBelow watermark + AbandonGaveUpBelow (pure module, TDD)
**Files:** Modify `native/src/main/jni/digibytewallet-core/BRCFScanLedger.h`/`.c`; Test `native/src/test/host/cf_scan_ledger_kat/`.

**Design (spec Part 1 + Part 3):** `abandonedBelow` is a monotonic hard floor; `LowestNeededHeight` = `max(scannedThrough+1, abandonedBelow)` (gaveUp-inclusive because `_cfLedgerAdvance` already caps scannedThrough at `min(outstanding[0],gaveUp[0])−1` — do NOT exclude gaveUp). `AbandonGaveUpBelow(clamp)` drops retry-exhausted gaveUp entries `< clamp`, advances `abandonedBelow` to `min(clamp, lowest-still-outstanding)` (NEVER past a still-retrying outstanding hole), returns the new lowest-still-needed height. Persisted; hard-floor-enforced in Peek/RecordRequested/MarkHeaderRace.

- [ ] **Step 1 (field + accessor, failing test):** add `uint32_t abandonedBelow;` to `BRCFScanLedger`. `test_lowest_needed_height`: seed scannedThrough + a gaveUp hole below outstanding[0]; assert `BRCFScanLedgerLowestNeededHeight == scannedThrough+1 == the gaveUp hole` (proves gaveUp-inclusion); with `abandonedBelow` higher, assert it returns `abandonedBelow`. RED (absent).
- [ ] **Step 2:** implement the field + `BRCFScanLedgerLowestNeededHeight` + `BRCFScanLedgerAbandonedBelow` accessor. GREEN.
- [ ] **Step 3 (AbandonGaveUpBelow, failing test):** `test_abandon_gaveup_below`: seed gaveUp={G1<G2<clamp<G3} and an outstanding hole O with O<clamp still sub-cap; call `AbandonGaveUpBelow(clamp)`; assert G1,G2 dropped from gaveUp, G3 kept, `abandonedBelow` advanced to `min(clamp, O)` (NOT past O — O is still retrying), the still-outstanding O is UNTOUCHED, return == O. A second call with a higher clamp only advances (never regresses). RED.
- [ ] **Step 4 (the WARN log — operator condition):** `AbandonGaveUpBelow`, when it abandons ≥1 height, MUST emit a warn-level log `cf-ledger: ABANDONED n gaveUp heights [lo..hi] (retention ceiling; too deep) abandonedBelow=X` (via the module's existing log macro / a caller-provided callback — the pure module has no logger, so return the (count, lo, hi) so BRPeerManager logs it at warn; add those out-params). Assert the test observes the returned count/range.
- [ ] **Step 5 (hard-floor guards, failing test):** `test_abandoned_is_hard_floor`: after `abandonedBelow=A`, assert `PeekRerequestRange` never offers a height `< A` (start `minHeight` at `max(minHeight,A)`); `RecordRequested`/`MarkHeaderRace` reject (no-op) heights `< A`. RED then implement then GREEN.
- [ ] **Step 6 (persist, failing test):** `test_abandonedBelow_roundtrips`: Serialize→Parse preserves `abandonedBelow`; bump the blob version; a pre-version blob parses with `abandonedBelow=0` (back-comp). RED then implement then GREEN.
- [ ] **Step 7:** `bash scripts/run-host-kats.sh; echo EXIT=$?` → `EXIT=0`. Commit (submodule): `feat(cf-ledger): abandonedBelow watermark + LowestNeededHeight + AbandonGaveUpBelow (retention scan-floor)`.

### Task 2: batch stop-hash resolver — ONE descent per tick (BEFORE Task 3; adversarial equivalence KAT is the linchpin)
**Files:** Modify `native/src/main/jni/digibytewallet-core/BRPeerManager.c` (add `_BRPeerManagerResolveHashesAtHeightsLocked`); Test `native/src/test/host/cf_scan_ledger_drive_kat/` (it `#include`s BRPeerManager.c → statics reachable).

**Design (spec Part 2):** `_BRPeerManagerResolveHashesAtHeightsLocked(manager, const uint32_t *heights, size_t n, UInt256 *outHashes)`: sort the heights descending into scratch, walk `lastBlock` once via `BRSetGet(&prevBlock)`, emit each height's `blockHash` as `b->height` passes it (UINT256_ZERO for heights above tip or below the retained floor / not found). One O(span) walk resolving all N. Correct-by-construction from the current chain view; per-call scratch, no persistent state.

- [ ] **Step 1 (ADVERSARIAL property-equivalence KAT — the linchpin):** `test_batch_resolve_equals_naive`. Build a real linked block chain (distinct-per-height hashes — NOT dummyBlock's single-byte fill). For MANY randomized height sets, assert the batch output is BYTE-IDENTICAL to N individual `_BRPeerManagerBlockHashAtHeight` calls, over: (a) unsorted input, (b) duplicate heights, (c) heights ABOVE the tip (→ZERO), (d) heights BELOW the retained floor / not-present (→ZERO cleanly, not garbage), (e) a single height, (f) the empty set, (g) heights straddling a fork point (build a small fork; assert both resolve to the main-chain hash the naive walk gives). Run → RED (resolver absent / compile error).
- [ ] **Step 2:** implement `_BRPeerManagerResolveHashesAtHeightsLocked`. GREEN — batch == naive for every generated set. Any disagreement = a wrong stop-hash to getcfilters = silent wrong-range fetch; the KAT must catch it.
- [ ] **Step 3:** `bash scripts/run-host-kats.sh; echo EXIT=$?` → `EXIT=0`. Commit (submodule): `feat(cf): single-descent batch stop-hash resolver (+adversarial equivalence KAT)`.

### Task 3: residual-loop restructure to Pass A collect → Pass B one-descent resolve → Pass C send
**Files:** Modify `native/src/main/jni/digibytewallet-core/BRPeerManager.c` (`BRPeerManagerKeepAlive` residual block `:3245-3330`; `_BRPeerManagerRequestCFiltersLocked` `:3978` — add a pre-resolved-hash variant/params); Test `native/src/test/host/cf_scan_ledger_drive_kat/`.

**Design (spec Part 2):** Pass A runs the existing peek loop but only COLLECTS due `(rs,cap)` ranges + their peer selection into per-tick scratch (no send, no resolve, but STILL applies the reverse-map suppressor + tip-clip so the collected set == what would have been sent). Pass B: gather all `rs`+`cap` heights → `_BRPeerManagerResolveHashesAtHeightsLocked` once. Pass C: for each collected range send with pre-resolved start/stop hashes + `CommitRerequest`. Attempts commit ONLY for ranges that actually sent (`sent>0`), exactly as today.

- [ ] **Step 1 (failing drive-KAT — restructure preserves behavior + discipline):** `test_residual_batched_preserves_semantics`. Set up N due residual holes + a connected CF peer; run KeepAlive; assert the SAME getcfilters are sent (same ranges, captured via the --wrap seam) as the pre-restructure loop would, AND `attempts` bumped ONLY for ranges that sent (a range whose send fails → no attempt bump). Include the reverse-map suppressor case (a cfheader-lag height is still skipped). RED against a stub.
- [ ] **Step 2 (staleness guard — operator condition):** `test_no_stale_between_pass_recommit`: a height collected in Pass A that DRAINS via the buffer (MarkEvaluated) before Pass C must NOT be re-requested nor get an attempt committed. Model: collect ranges, drain one height, then send; assert no getcfilters + no CommitRerequest for the drained height. RED then implement the guard (Pass C re-checks the height is still outstanding before sending/committing) then GREEN.
- [ ] **Step 3:** implement the Pass A/B/C restructure + the `_BRPeerManagerRequestCFiltersLocked` pre-resolved-hash variant. Run drive-KAT (ASan+LSan) → GREEN. `bash scripts/run-host-kats.sh; echo EXIT=$?` → `EXIT=0`.
- [ ] **Step 4 — REVIEW EMPHASIS (carry into the task-review brief):** the reviewer must explicitly verify (a) nothing between Pass A and Pass C makes a collected entry stale without Pass C detecting it (the Step-2 guard); (b) the peek/commit discipline is intact — attempts commit only for what actually sent, the reverse-map suppressor still fires, the rotate-away peer selection survives. This discipline was hard-won across 3 hostile passes; a restructure is where it inverts.
- [ ] **Step 5:** Commit (submodule): `refactor(cf): batch the residual tick's stop-hash resolution into one descent (ANR-safe raised floor)`.

### Task 4: ClearMemory floor tracks the scan frontier + tip-anchored ceiling + production-scale KAT
**Files:** Modify `native/src/main/jni/digibytewallet-core/BRPeerManager.c` (`_BRPeerManagerClearMemory` `:1243`, both the cfNext branch `:1259` and bootstrap `:1273`; add `CF_RETENTION_MAX_SPAN` to `BRPeerManager.h`; the warn-log for abandonment); Test NEW `native/src/test/host/cf_clearmemory_retention_kat/` (or extend the drive-KAT).

**Design (spec Part 1 + Part 3):** `floorH = min(cfNext, LowestNeededHeight)`, then `_cfApplyRetentionCeiling` (tip-anchored: if `tip>MAX_SPAN && floorH<tip−MAX_SPAN`, `AbandonGaveUpBelow(tip−MAX_SPAN)` and raise floorH), then `cfFloor = floorH−margin`. **Full descent — NO early-out** (REV1's early-out rejected: the descent is the only code that frees). `CF_RETENTION_MAX_SPAN 30000` (~6.6 MB @220B). BRPeerManager WARN-logs the abandonment count/range returned by `AbandonGaveUpBelow`.

- [ ] **Step 1 (production-scale red-before-green KAT — MUST fire the trigger):** `test_clearmemory_retains_scan_floor`. Build a `prevBlock`-linked chain of **>5000** DISTINCT-per-height headers (write the height into the 32-byte hash; NOT single-byte fill) with a >800-deep chain from `lastBlock`; set the cf chain `NextHeight`==tip; `BRCFScanLedgerInit(&l, floor)` + `RecordRequested(H_floor)` with `H_floor < tip−801` AND `< cfNext−144`. **Assert `BRSetCount(blocks) ≥ 5000` BEFORE** the pass. Call `_BRPeerManagerClearMemory`. Assert `BRSetGet(blocks, &H_floor_hash) != NULL` (survives). run.sh: unfixed (`-DRETENTION_UNFIXED` or pre-edit) = RED (pruned), fixed = GREEN. RED-before-green enforced in run.sh.
- [ ] **Step 2 (descent-frees + invariant, failing test):** assert blocks below `cfFloor` AND below the tail boundary ARE freed (no Part-4 leak); iterate the ledger outstanding/buffered set → every one's header present post-pass.
- [ ] **Step 3 (ceiling case, failing test):** `#ifndef`-guard `CF_RETENTION_MAX_SPAN`, `-D` it small (4000); with `tip−lowestNeeded > MAX_SPAN` and a gaveUp hole below the clamp, assert `abandonedBelow` advanced to `tip−MAX_SPAN`, sub-clamp headers freed, `BRSetCount` bounded to ~MAX_SPAN, the WARN log fired, AND a still-outstanding (attempts<MAX) hole below clamp is NOT abandoned.
- [ ] **Step 4:** implement the floor + `_cfApplyRetentionCeiling` (both branches) + the warn-log + `CF_RETENTION_MAX_SPAN`. Run KATs (ASan) → GREEN. `bash scripts/run-host-kats.sh; echo EXIT=$?` → `EXIT=0`.
- [ ] **Step 5:** Commit (submodule): `fix(cf): retention floor tracks the scan frontier + tip-anchored memory ceiling (kills the deep-restore prune wedge)`. Outer commit for the KAT.

---

## Acceptance (this branch closes here — code-clean necessary NOT sufficient)
LAB re-run (own-node exclusive, seed-safe soft-wipe, reconcile disarmed): the $1 DD (block 23,920,918) credits via CF alone; `outstanding→0`, `scannedThrough→tip`, **`abandonedBelow` stays 0 (verified in the warn-log — no ABANDONED line)**, no OOM, no ANR/wedge. Full wallet (271.21 DGB + DD + assets) credits.

## Self-review notes
- gaveUp is IN the floor by construction; excluding it silently loses a buffered+gaveUp receive. Do not "simplify".
- The batch resolver is preferred over a persistent height→hash index precisely because it has no stale-entry surface; the equivalence KAT is what earns that trust.
- Abandonment abandons ONLY gaveUp (retry-exhausted); a still-retrying hole below the clamp keeps its header (transient span overage ≤ retry window).
