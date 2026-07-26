# CF Scan-Ledger Stale-Buffer Livelock + Exclusive-Pin Enforcement — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Fix the mainline production livelock where stale buffered cfilters (orphaned by a header re-sync) starve the buffer-gated residual re-request for every height, and enforce `custom_node_exclusive` against public priority-peer injection. Both surfaced by the Phase-2 acceptance rig (2026-07-26): the $1 DD at block 23,920,918 did NOT credit via CF; `outstanding` pinned at 2000, `scannedThrough` frozen, `gaveUp` 0.

**Architecture:** Two independent tracks. **Track A** (small, ships first, unblocks the lab run): pin-enforcement guard + a per-block diagnostic-log demotion. **Track B** (the real work, drain-core, full hostile review): a three-part fix to the stale-buffer/residual-gating livelock — evict-on-mismatch, per-height residual eligibility, age-out.

**Tech Stack:** C (breadwallet-derived native core, submodule `digibytewallet-core`), JNI bridge (outer repo `native/src/main/jni/bridge/`), host KATs (`scripts/run-host-kats.sh`, ASan).

## Reframe that motivates this (do not re-litigate)
Multi-peer public-fleet discovery **is the product**; own-node-exclusive is an optional mode + test instrument. So cfilters-from-one-peer-while-headers-climb-from-another, peer-drop header re-syncs, and racing chain views are **normal production behavior**, not a polluted test. The stale-buffer livelock they produce is a **mainline day-one blocker**, not a follow-up. Buffered bytes are an *optimization*; the ledger's `outstanding` record is the source of truth — so **evicting a buffered entry is always safe** (worst case, the height stays outstanding and residual re-requests it).

## Global Constraints
- **Submodule** C edits: `native/src/main/jni/digibytewallet-core/` (fork `JohnnyLawDGB/digibytewallet-core`, branch off core `develop` = `9dc8e37`). Push fork before pin bump. Submodule git: `GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core GIT_WORK_TREE=native/src/main/jni/digibytewallet-core git …`.
- **Outer** JNI edits: `native/src/main/jni/bridge/jni_peer.c` (outer repo, NOT the submodule).
- **Host KATs:** `bash scripts/run-host-kats.sh; echo "EXIT=$?"` must print `EXIT=0`. NEVER pipe to `tail`/`head` (masks exit code). ASan/LSan live in the drive-KAT.
- **Build order:** native before app — `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug`.
- **Commit co-author:** `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **Merges to `develop` need HUMAN approval.** Core PR merges before app PR; app pin must be `git merge-base --is-ancestor`-verified against core develop before the app merge.
- **Pinned constants unchanged:** `CF_OUTSTANDING_MAX 4096`, `CF_GAVEUP_MAX 512`, `CF_REREQ_MAX_ATTEMPTS 5`, `CF_REREQ_BASE_SECS 30`, `CF_REREQ_BACKOFF_CAP_SECS 120`, `CF_OUTSTANDING_LOWWATER 3072`, `CLEAR_MEM_CF_RETENTION_MARGIN 144`.
- **Acceptance is TWO runs** (Track B closes on both): LAB (own-node exclusive, pin enforced) then PRODUCTION (default config, public fleet, no pins). Steady-state week in default config.

---

## TRACK A — pin enforcement + log demotion (ships first, unblocks the lab)

### Task 1: exclusive-pin enforcement in `injectPeerByIp`

**Files:** Modify `native/src/main/jni/bridge/jni_peer.c` (`injectPeerByIp`, ~`:563-607`).

**Context:** `g_ownNodeExclusive` (`:113`) is set by `setPinnedPeer`. `startSync` already suppresses the *native* oracle-bootstrap prepend when it's set (`:807`). The GAP: the Kotlin-driven `injectPeerByIp` (seeder peers) has no exclusivity check, so public peers (e.g. `95.111.238.51`) get prepended + added to the live pool despite exclusive mode. **TRAP (do not miss):** per the `setPinnedPeer` comment (`:615`), the **own-node itself is injected through `injectPeerByIp`** — so the guard MUST allow the pinned own-node (`g_pinnedAddr`/`g_pinnedPort`) through and drop only non-pin peers, or exclusive mode starves to zero peers.

- [ ] **Step 1:** In `injectPeerByIp`, after `PEER_GUARD()` and after parsing `ip` to a `UInt128` (mirror the existing `inet_pton` at `:597`), add: if `g_ownNodeExclusive` AND the parsed `(addr,port)` is NOT `(g_pinnedAddr,g_pinnedPort)` (compare with `UInt128Eq` + port), release the string and `return` without injecting. Log at debug: `injectPeerByIp: exclusive mode, dropping non-pin peer <ip>`.
- [ ] **Step 2:** Manual reasoning check (no host KAT — this is JNI, unmockable on host per `reference_host_kat_suite`): confirm the own-node path (`injectCustomNode` → `injectPeerByIp` with the pin IP) still injects, and a seeder IP does not, when exclusive.
- [ ] **Step 3: Commit** (outer): `fix(peers): enforce custom_node_exclusive against public priority-peer injection`.

**Verification is on-device (the lab run), not host KAT** — the JNI peer path can't be host-mocked. Build must succeed: `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug` → `BUILD SUCCESSFUL`.

### Task 2: demote the per-block cfilter-match diagnostic log

**Files:** Modify `native/src/main/jni/digibytewallet-core/BRPeerManager.c` (`:2533-2547`, submodule).

**Context:** A `v3.10.8` "temporary" diagnostic (comment: *"Remove once CF confirmation is proven on-device"*) logs, PER BLOCK during CF scan, a match line (`:2539`) plus a hex dump of the first wallet element (`:2541-2547`). Thousands of these during catch-up flood logd → a plausible contributor to the observed binder-buffer exhaustion. CF confirmation IS proven (Phase 2). Scan progress is already covered by the `cf-ledger: scannedThrough=…` counts.

- [ ] **Step 1:** Gate the whole diagnostic block (the `:2539` `peer_log` AND the `:2541-2547` hex-element block) on `if (hit)` — keep the signal that actually matters (a match), silence the per-block miss flood. Verify the block's exact end brace before wrapping.
- [ ] **Step 2:** `bash scripts/run-host-kats.sh; echo "EXIT=$?"` → `EXIT=0` (no behavior change; the match logic at `:2530-2531` is untouched — only the log is gated).
- [ ] **Step 3: Commit** (submodule): `perf(cf): gate per-block cfilter-match diagnostic on hit (kill logd flood)`.

---

## TRACK B — stale-buffer / residual-gating livelock (drain-core, full hostile review)

**The livelock (root cause, from the rig):** buffered cfilters are keyed by blockHash; a header re-sync (normal multi-peer behavior) replaces the chain view, orphaning those hashes; orphaned entries never become "ready" to drain; and the residual re-request is gated on the GLOBAL `BufferedCount()==0` (`BRPeerManager.c:3173`), so a handful of undrainable stale entries starves re-request for EVERY height. `outstanding` pins, `gaveUp` stays 0 — a livelock with perfect visibility and no exit.

**Two staleness sub-cases the implementer + hostile pass MUST distinguish (grounding, verified):** an orphaned buffered hash `Bh` either (i) **stays in `manager->blocks`** — then `_cfBufIsReady` resolves it, `_cfBufEval`'s `BRCompactFilterChainVerifyFilter` fails against the canonical cfheader commitment, and the existing verify-fail path already evicts it (leaving the height outstanding); or (ii) **is pruned** (`BRSetGet(blocks,Bh)`→NULL) — then `_cfBufIsReady` returns "not ready" forever → the true livelock. There is NO general height→block index (checkpoints only, `:1844`); canonical-block-at-height is a `prevBlock` main-chain walk (`:1623` pattern). The fix design must handle case (ii) without an expensive per-entry deep walk.

### Task 3: age-out backstop (pure module) — TDD first

**Files:** Modify `native/src/main/jni/digibytewallet-core/BRCFScanLedger.c`/`.h`; Test `native/src/test/host/cf_scan_ledger_kat/`.

**Rationale:** the pruned-hash case (ii) can only be resolved by time — `BRCFFilterBufEntry.at` (unix secs when buffered) already exists. Age-out is the correctness backstop that guarantees BufferedCount eventually returns to 0 for any stale entry.

- [ ] **Step 1 (failing test):** buffer an entry with `at = now - CF_FILTER_BUF_MAX_AGE_SECS - 1`; call the new `BRCFScanLedgerEvictAgedFilters(l, nowSec)`; assert the entry is freed, `filterBufCount`→0, `bufferedBytes`→0. Fresh entries (recent `at`) are NOT evicted.
- [ ] **Step 2 (constant):** add `CF_FILTER_BUF_MAX_AGE_SECS` to `BRCFScanLedger.h` — pin it ≥ the retry schedule so a legitimately-slow header still drains before age-out (retry schedule 30+60+120+120+120 = 7.5 min; propose **900** (15 min), with headroom; hostile pass to confirm it's ≫ a real header-connect latency but ≪ the retention margin). Evicting an age-out entry leaves its height outstanding for residual — safe by construction.
- [ ] **Step 3:** implement `BRCFScanLedgerEvictAgedFilters` (walk `filterBuf`, free entries with `nowSec - at > CF_FILTER_BUF_MAX_AGE_SECS`, compact the FIFO array, decrement counts/bytes). Run KAT → PASS. Commit (submodule): `feat(cf-ledger): age-out buffered filters (stale-hash livelock backstop)`.

### Task 4: per-height residual eligibility (replace the global BufferedCount gate)

**Files:** Modify `native/src/main/jni/digibytewallet-core/BRPeerManager.c` (`:3169-3173` residual block); Test `native/src/test/host/cf_scan_ledger_drive_kat/`.

**Design:** delete the global `if (BRCFScanLedgerBufferedCount(...)==0)` wrapper. Instead, drive the age-out (Task 3) each tick, then let the residual peek/commit run, but make each candidate outstanding height **skip only if it has a live buffered entry** — i.e. the buffer contains the CANONICAL hash for that height. Since the buffer is hash-keyed, this is: `canon = <canonical block at H>`; `if (canon && BRCFScanLedgerHasBufferedHash(&ledger, canon->blockHash)) skip;`. Getting `canon` cheaply: H is at/near the scan cursor, and the residual already caps at `tipH`; the hostile pass must confirm the main-chain lookup cost is bounded (candidate heights are near `scannedThrough`, not tip-deep). Add `int BRCFScanLedgerHasBufferedHash(const BRCFScanLedger*, UInt256)` (pure, O(bufCount)).

- [ ] **Step 1 (failing drive-KAT):** `test_residual_not_starved_by_unrelated_buffer` — buffer a LIVE entry for height 50 (header not yet connected), leave height 200 outstanding with no buffered entry; run `BRPeerManagerKeepAlive`; assert residual getcfilters IS sent for 200 (was blocked by the old global gate) while 50 is left to drain.
- [ ] **Step 2:** `test_residual_skips_own_buffered_height` — a height whose canonical hash IS buffered is skipped by residual (no double-request).
- [ ] **Step 3:** implement `BRCFScanLedgerHasBufferedHash` + rewire the residual block to per-height eligibility. Run drive-KAT (ASan) → PASS. Commit (submodule): `feat(cf-ledger): per-height residual eligibility (kills the buffer-starves-residual livelock)`.

### Task 5: evict-on-mismatch (fast path)

**Files:** Modify `native/src/main/jni/digibytewallet-core/BRPeerManager.c` (`_cfBufIsReady`/`_cfBufEval`/drain, `:3095-3170`); Test drive-KAT.

**Design:** when the drain resolves a buffered entry to a block `b` at height H (`BRSetGet(blocks,Bh)` non-NULL, real height) but `b` is NOT the canonical block at H (a re-sync put a different hash there), evict it immediately (drop from buffer, leave the height outstanding) instead of relying on verify-fail or age-out. This is the "fast path" for sub-case (i). Implementation: extend the drain to a tri-state (READY / NOT_YET / STALE); STALE removes the buffer entry without `MarkEvaluated`. Canonical-at-H via the bounded main-chain walk (`:1623` pattern), or piggy-back on the verify-fail already present in `_cfBufEval` (which structurally detects the mismatch) — the hostile pass decides whether a separate isReady STALE branch adds value over the existing verify-fail eviction, or whether Tasks 3+4 alone close the livelock (in which case Task 5 is dropped, logged in the deviation ledger).

- [ ] **Step 1 (failing drive-KAT):** `test_stale_buffered_evicted_on_resync` — buffer a filter for hash `Bh` at height H; make canonical(H) a DIFFERENT hash still present in `blocks`; drain; assert the stale entry is evicted (`BufferedCount`→0), H stays outstanding, no `MarkEvaluated`, no getdata.
- [ ] **Step 2:** implement + run (ASan) → PASS. Commit (submodule): `feat(cf-ledger): evict-on-mismatch for stale buffered filters after a re-sync`.

### Task 6: livelock end-to-end reproduction KAT + gate flip check

**Files:** Test `native/src/test/host/cf_scan_ledger_drive_kat/`.

- [ ] **Step 1:** `test_livelock_reproduction` — the exact rig scenario in miniature: outstanding set of N heights, buffer a stale entry, no canonical resolution; assert that WITHOUT the fix the residual is starved (document via a gated assert) and WITH the fix `outstanding` drains (residual fires, stale evicted, `gaveUp` still 0). Run full sweep → `EXIT=0`. Commit (outer): `test(cf-ledger): reproduce + verify the stale-buffer residual-starvation livelock`.

---

## Acceptance (Track B closes here — code-review-clean is necessary, NOT sufficient)
1. **LAB run:** emulator, own-node exclusive with the pin ACTUALLY enforced (Track A merged). Wipe/restore (seed-safe soft-wipe), CF-only (reconcile disarmed). PASS = the $1 DD (block 23,920,918) credits via CF alone, `outstanding`→0, `scannedThrough`→tip, `gaveUp` 0, no SIGSEGV/leak.
2. **PRODUCTION run:** emulator, DEFAULT config — public fleet discovery, NO pins. Same wipe/restore must credit the DD and drain `outstanding`→0 through the multi-peer reality (header races, peer drops included).
3. **Steady-state week** in default config: `gaveUp` 0, `outstanding` drains within minutes of any blip, zero manual reconciles.

## Self-Review notes (executor)
- Every eviction (age-out, per-height skip, mismatch) is safe because the ledger's `outstanding` record is the truth and residual re-requests any dropped height. The buffer is a latency optimization only.
- Tasks 3 (age-out) + 4 (per-height) are the load-bearing livelock fixes and are independently testable; Task 5 (evict-on-mismatch) is the fast path and may be reduced/dropped if the hostile pass shows 3+4 suffice — record any drop in the deviation ledger.
- The `BufferedCount==0` global gate is the fragile design being removed; do not reintroduce a global buffer switch anywhere in the residual path.
