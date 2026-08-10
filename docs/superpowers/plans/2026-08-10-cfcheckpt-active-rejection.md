# cfcheckpt Active Rejection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Graduate the compact-filter-header checkpoint cross-check from observe-and-log to active rejection — a peer serving a filter-header chain that diverges from a pinned checkpoint is rejected and banned, and the wallet can never be driven off a checkpoint-confirmed chain by a liar — without ever hard-bricking.

**Architecture:** Four coordinated changes in the native C core, all keyed off the existing 478-entry `BRMainNetCFCheckpoints[]` table (mainnet-only): (1) validate a checkpoint-crossing cfheaders batch *before* `BRCompactFilterChainAppend` commits it, rejecting+banning on mismatch; (2) veto any re-anchor away from a checkpoint-confirmed chain; (3) never-brick recovery — refuse to advance, park at the nearest trusted checkpoint, surface a recoverable banner; (4) harden the re-anchor quorum (disagreers-must-agree + strict-majority+floor≥3). Two pure helpers make (1) testable in isolation; the rest wire into `_peerRelayedCFHeaders` and the re-anchor path in `BRPeerManager.c`.

**Tech Stack:** C (native core, `native/src/main/jni/digibytewallet-core/`), ASan host KATs (`native/src/test/host/`, plain `clang -w -include stdint.h` builds), Kotlin (surfacing string only).

**Spec:** `docs/superpowers/specs/2026-08-10-cfcheckpt-active-rejection-design.md`

## Global Constraints

- **Worktree:** `/tmp/wallet-cfcheckpt`, branch `feat/cfcheckpt-active-rejection` (off develop). Core submodule at `native/src/main/jni/digibytewallet-core` (HEAD `1405954`). Commit the submodule (johnnylaw remote) BEFORE bumping the parent pin; push core before app.
- **Mainnet-only.** Every new behavior gates on `manager->params->standardPort == BRMainNetParams.standardPort` (same gate as the observe check at `BRPeerManager.c:~3976`). Testnet26 behavior is unchanged.
- **NEVER hard-brick.** A checkpoint mismatch or eclipse must degrade to a surfaced, user-recoverable state (refuse-advance + park at trusted checkpoint + banner) — never a crash, never a silent wedge. This invariant outranks all others; if a change could brick, it's wrong.
- **Historical region only.** Checkpoints cover up to the highest entry (~23.85M). Blocks above the last checkpoint stay TOFU + continuity + quorum. Do not claim or attempt tip protection here.
- **Existing levers (reuse, don't reinvent):** `_BRPeerManagerPeerMisbehavin(manager, peer)` (disconnect+evict), `_BRPeerManagerReanchorAtFloorLocked(manager, force)` (`BRPeerManager.c:6650`), `_BRPeerManagerSurfaceUnscannableLocked(manager, lo, floor, why)` (`:2055`, the C2/I3 recover-me banner path), `BRCompactFilterChainHeader(chain, height)` / `BRCompactFilterChainTipHeader(chain)`.
- **Every fix keeps a KAT; every gate bidirectional** (red on unfixed via a `-D…_UNFIXED` arm, green on fixed). Do not weaken existing tests. `-D` cannot override a plain `#define` (edit the header seam). Harness/production constant collisions forbidden.
- **Checkpoint types:** `typedef struct { uint32_t height; UInt256 filterHeader; } BRCFCheckpoint;` — `BRMainNetCFCheckpoints[]`, count `BRMainNetCFCheckpointsCount` (both in `BRCompactFilterCheckpoints.h`).

---

### Task 1: Checkpoint table lookups

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRCompactFilterCheckpoints.h`
- Test: `native/src/test/host/cf_checkpoint_lookup_kat/{cf_checkpoint_lookup_kat_main.c,run.sh}` (create)

**Interfaces:**
- Produces:
  - `const BRCFCheckpoint *BRCFHighestCheckpointAtOrBelow(uint32_t height);` — returns the highest table entry with `height <= given`, or `NULL` if none (given below the first checkpoint).
  - `size_t BRCFCheckpointsInRange(uint32_t lo, uint32_t hi, const BRCFCheckpoint **out, size_t outCap);` — fills `out` with pointers to entries whose `height` is in `[lo, hi]` inclusive, returns the count (clamped to `outCap`).
  - The table is ascending by height; both functions rely on that.

- [ ] **Step 1: Write the failing test**

Create `cf_checkpoint_lookup_kat_main.c`:
```c
#include <assert.h>
#include <stdio.h>
#include "BRCompactFilterCheckpoints.h"
int main(void) {
    // AtOrBelow
    assert(BRCFHighestCheckpointAtOrBelow(49999) == NULL);
    assert(BRCFHighestCheckpointAtOrBelow(50000)->height == 50000);
    assert(BRCFHighestCheckpointAtOrBelow(50001)->height == 50000);
    assert(BRCFHighestCheckpointAtOrBelow(149999)->height == 100000);
    const BRCFCheckpoint *top = &BRMainNetCFCheckpoints[BRMainNetCFCheckpointsCount-1];
    assert(BRCFHighestCheckpointAtOrBelow(top->height + 1000000)->height == top->height);
    // InRange
    const BRCFCheckpoint *hits[8];
    assert(BRCFCheckpointsInRange(50000, 150000, hits, 8) == 3);   // 50k,100k,150k
    assert(hits[0]->height == 50000 && hits[2]->height == 150000);
    assert(BRCFCheckpointsInRange(50001, 99999, hits, 8) == 0);    // none strictly between
    assert(BRCFCheckpointsInRange(0, 40000, hits, 8) == 0);
    printf("cf_checkpoint_lookup_kat: ALL PASS\n");
    return 0;
}
```
Create `run.sh` (copy the idiom from `native/src/test/host/cf_gate_kat/run.sh`):
```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(cd "$SCRIPT_DIR/../../../../.." && pwd)/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
clang -w -include stdint.h -I "$CORE_DIR" "$SCRIPT_DIR/cf_checkpoint_lookup_kat_main.c" -o "$BUILD_DIR/k"
"$BUILD_DIR/k"
```

- [ ] **Step 2: Run to verify it fails** — `chmod +x` then `./native/src/test/host/cf_checkpoint_lookup_kat/run.sh`. Expected: compile error (`BRCFHighestCheckpointAtOrBelow` / `BRCFCheckpointsInRange` undefined).

- [ ] **Step 3: Implement** — add to `BRCompactFilterCheckpoints.h`, after the `BRMainNetCFCheckpointsCount` line:
```c
static inline const BRCFCheckpoint *BRCFHighestCheckpointAtOrBelow(uint32_t height) {
    const BRCFCheckpoint *best = NULL;
    for (size_t i = 0; i < BRMainNetCFCheckpointsCount; i++) {
        if (BRMainNetCFCheckpoints[i].height <= height) best = &BRMainNetCFCheckpoints[i];
        else break; // ascending
    }
    return best;
}
static inline size_t BRCFCheckpointsInRange(uint32_t lo, uint32_t hi,
                                            const BRCFCheckpoint **out, size_t outCap) {
    size_t n = 0;
    for (size_t i = 0; i < BRMainNetCFCheckpointsCount && n < outCap; i++) {
        uint32_t h = BRMainNetCFCheckpoints[i].height;
        if (h >= lo && h <= hi) out[n++] = &BRMainNetCFCheckpoints[i];
        else if (h > hi) break;
    }
    return n;
}
```

- [ ] **Step 4: Run to verify pass** — `./…/cf_checkpoint_lookup_kat/run.sh` → `ALL PASS`.

- [ ] **Step 5: Register + commit** — add the new KAT dir; `scripts/run-host-kats.sh` auto-discovers it. Commit (core submodule):
```bash
git -C native/src/main/jni/digibytewallet-core add BRCompactFilterCheckpoints.h
git add native/src/test/host/cf_checkpoint_lookup_kat/
git commit -m "feat(cf): checkpoint table range/at-or-below lookups + KAT"
```
(Header lives in the core submodule; the KAT lives in the app repo. Two commits — core first.)

---

### Task 2: Pure batch-vs-checkpoint validator

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRCompactFilterChain.c`, `BRCompactFilterChain.h`
- Test: `native/src/test/host/cf_candidate_header_kat/{cf_candidate_header_kat_main.c,run.sh}` (create)

**Interfaces:**
- Consumes: `BRCompactFilterChainTipHeader`, `BRCompactFilterChainNextHeight`, the internal filter-header fold (same math as `BRCompactFilterChainAppend`).
- Produces:
  - `int BRCompactFilterChainBatchViolatesCheckpoint(const BRCompactFilterChain *chain, const UInt256 *filterHashes, size_t count, uint32_t *outHeight, UInt256 *outComputed);` — folds the batch *without mutating the chain* starting from the chain's current tip header at `NextHeight()`, and for every pinned checkpoint whose height falls in `[NextHeight, NextHeight+count-1]` compares the folded header to the pin. Returns 1 (and sets `*outHeight`/`*outComputed` to the first violation) if any checkpoint mismatches; 0 if all in-range checkpoints match or none are in range. Mainnet table only (uses `BRCFCheckpointsInRange`). Never allocates, never mutates.

- [ ] **Step 1: Write the failing test** — `cf_candidate_header_kat_main.c` builds a real chain and proves the validator agrees with a committed append at a checkpoint height, and flags a corrupted batch:
```c
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "BRCompactFilterChain.h"
#include "BRCompactFilterCheckpoints.h"
#include "BRInt.h"
int main(void) {
    // Build a chain anchored so that checkpoint 50000 lands inside a batch.
    // (Use the same anchor/append helper the existing BRCompactFilterChain tests use;
    //  see BRCompactFilterChainTests.c test_append_extends_chain for the setup idiom.)
    // Construct filterHashes[] such that the folded header at height 50000 equals
    // BRMainNetCFCheckpoints[0].filterHeader (a known-good batch), append a COPY of the
    // chain with the real values, and assert the validator returns 0 (no violation):
    //   BRCompactFilterChain *c = <anchor at 49000, tip header known>;
    //   UInt256 good[2000]; <fill so fold hits the pin at 50000>;
    //   uint32_t vh; UInt256 vc;
    //   assert(BRCompactFilterChainBatchViolatesCheckpoint(c, good, 2000, &vh, &vc) == 0);
    // Now corrupt one hash so the folded header at 50000 differs:
    //   UInt256 bad[2000]; memcpy(bad, good, sizeof(bad)); bad[10].u8[0] ^= 0xff;
    //   assert(BRCompactFilterChainBatchViolatesCheckpoint(c, bad, 2000, &vh, &vc) == 1);
    //   assert(vh == 50000);
    // And prove NO MUTATION: the chain's tip/next are unchanged after both calls.
    //   assert(BRCompactFilterChainNextHeight(c) == <original>);
    printf("cf_candidate_header_kat: ALL PASS\n");
    return 0;
}
```
`run.sh` compiles `BRCompactFilterChain.c` + deps (mirror `cf_confirm_kat/run.sh`'s core-source list, trimmed to what `BRCompactFilterChain.c` needs: `BRCrypto.c`, `BRInt`-only). Fill in the concrete `good[]`/anchor using the existing `BRCompactFilterChainTests.c` setup (read it first — it already constructs valid batches).

- [ ] **Step 2: Run to verify it fails** — validator undefined → compile error.

- [ ] **Step 3: Implement** — in `BRCompactFilterChain.c`, factor the header fold out of `BRCompactFilterChainAppend` into a static helper `_foldHeader(prev, filterHash) -> UInt256` (dSHA256 of `filterHash || prev`, matching the existing append math — do not change append's result), then:
```c
int BRCompactFilterChainBatchViolatesCheckpoint(const BRCompactFilterChain *chain,
        const UInt256 *filterHashes, size_t count, uint32_t *outHeight, UInt256 *outComputed) {
    uint32_t start = BRCompactFilterChainNextHeight(chain);
    const BRCFCheckpoint *cps[16];
    size_t nc = BRCFCheckpointsInRange(start, start + (uint32_t)count - 1, cps, 16);
    if (nc == 0) return 0;
    UInt256 h = BRCompactFilterChainTipHeader(chain);
    for (size_t i = 0; i < count; i++) {
        h = _foldHeader(h, filterHashes[i]);
        uint32_t height = start + (uint32_t)i;
        for (size_t c = 0; c < nc; c++) {
            if (cps[c]->height == height && ! UInt256Eq(h, cps[c]->filterHeader)) {
                if (outHeight) *outHeight = height;
                if (outComputed) *outComputed = h;
                return 1;
            }
        }
    }
    return 0;
}
```
Declare it in `BRCompactFilterChain.h`. Verify `_foldHeader` reproduces `BRCompactFilterChainHeader`'s value (the observe soak proved the byte-order; keep it).

- [ ] **Step 4: Run to verify pass** — `ALL PASS`, and the no-mutation asserts hold.

- [ ] **Step 5: Commit** (core submodule + KAT app-side, core first): `feat(cf): pure batch-vs-checkpoint validator (no-mutation fold) + KAT`.

---

### Task 3: Pre-commit enforce in `_peerRelayedCFHeaders`

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRPeerManager.c` (`_peerRelayedCFHeaders`, the append site ~just before `BRCompactFilterChainAppend`, and the observe block ~3976-3999)
- Test: `native/src/test/host/cf_checkpoint_enforce_kat/{…_main.c,run.sh}` (create)

**Interfaces:**
- Consumes: `BRCompactFilterChainBatchViolatesCheckpoint` (Task 2), `_BRPeerManagerPeerMisbehavin`, `BRCFHighestCheckpointAtOrBelow`.
- Produces: enforcement behavior — a checkpoint-crossing batch that violates a pin is never appended; the peer is misbehavin'd; `cfHeadersRequestedThrough` does not advance.

- [ ] **Step 1: Write the failing KAT** — `cf_checkpoint_enforce_kat`, following the `cf_scan_ledger_drive_kat` harness idiom (synthetic peers + manager). Red arm `-DCF_CHECKPOINT_ENFORCE_UNFIXED` restores observe-only (append-then-log). Assertions:
  - GREEN: feed a cfheaders batch spanning a checkpoint height with ONE header corrupted so the fold mismatches the pin → assert the chain tip did NOT advance past the batch start, `BRCompactFilterChainCount` unchanged, and the serving peer was disconnected/evicted (misbehavin count bumped).
  - GREEN: feed a matching batch spanning a checkpoint → appended, tip advanced, peer NOT penalized.
  - RED (`-DCF_CHECKPOINT_ENFORCE_UNFIXED`): the corrupted batch is APPENDED (tip advances) and only logged — proving the gate catches the pre-fix behavior.
  - Safety control (must hold in both arms): a batch with NO checkpoint in range is appended normally (enforce doesn't touch non-checkpoint batches).

- [ ] **Step 2: Run to verify RED fails on unfixed** — build with `-DCF_CHECKPOINT_ENFORCE_UNFIXED`, assert the corrupted batch appends (gate red); without the flag, gate currently also "red" (not yet implemented) → confirm the named assertion.

- [ ] **Step 3: Implement** — in `_peerRelayedCFHeaders`, on mainnet, immediately BEFORE the `BRCompactFilterChainAppend(...)` call:
```c
#ifndef CF_CHECKPOINT_ENFORCE_UNFIXED
if (manager->params->standardPort == BRMainNetParams.standardPort) {
    uint32_t vh; UInt256 vc;
    if (BRCompactFilterChainBatchViolatesCheckpoint(manager->compactFilterChain,
            filterHashes, count, &vh, &vc)) {
        peer_log(peer, "cf-checkpoint: height %u *** ENFORCE REJECT *** computed=%s pinned=%s",
                 vh, log_u256_hex_encode(vc),
                 log_u256_hex_encode(BRCFHighestCheckpointAtOrBelow(vh)->filterHeader));
        _BRPeerManagerPeerMisbehavin(manager, peer);   // crypto-proof ban
        // do NOT append, do NOT advance cfHeadersRequestedThrough; re-request happens
        // via the existing "no advance" path (leave cfHeadersRequestedThrough as-is).
        return;   // matches the early-return shape used elsewhere in this handler
    }
}
#endif
```
Keep the existing post-append observe MATCH log (it's now a redundant confirm, harmless — or convert to a single "cf-checkpoint: height %u OK (enforced)" after a successful append). Do not remove the mainnet gate.

- [ ] **Step 4: Run to verify GREEN** — enforce KAT passes; RED arm still red-confirms. Regression: `cf_scan_ledger_drive_kat` and `cf_confirm_kat` still pass (non-checkpoint batches unaffected).

- [ ] **Step 5: Commit** — `fix(cf): enforce filter-header checkpoints pre-commit — reject+ban on mismatch (was observe-only)`.

---

### Task 4: Checkpoint-vetoes-reanchor

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRPeerManager.c` (the two re-anchor call sites ~4041 and ~4065, and/or inside a new guard helper)
- Test: extend `cf_checkpoint_enforce_kat` (or a `cf_checkpoint_veto_kat`)

**Interfaces:**
- Consumes: `BRCFHighestCheckpointAtOrBelow`, `BRCompactFilterChainHeader`.
- Produces: `_BRPeerManagerCheckpointConfirmsOurChainLocked(manager, uint32_t contestedHeight) -> int` — 1 if the highest pinned checkpoint at/below `contestedHeight` exists AND our committed chain's header at that height equals the pin. Any re-anchor is skipped (vetoed) when this returns 1.

- [ ] **Step 1: Write the failing KAT** — scenario: our chain is checkpoint-confirmed (append a valid chain past a checkpoint). Drive the re-anchor path (peer(s) disagreeing to trigger the quorum/single-peer escape hatch). Red arm `-DCF_CHECKPOINT_VETO_UNFIXED`.
  - GREEN: with our chain matching the checkpoint, a lone diverging peer that reaches `CF_SINGLE_PEER_REANCHOR_ROUNDS` → assert NO re-anchor happened (chain tip/start unchanged, `_BRPeerManagerReanchorAtFloorLocked` not called), and the peer is misbehavin'd instead.
  - GREEN: with our chain NOT reaching any checkpoint (contested height above the top checkpoint), the existing re-anchor path still runs (veto does not apply at the tip).
  - RED: without the veto, the lone diverging peer forces a re-anchor off our checkpoint-confirmed chain.

- [ ] **Step 2: Run to verify RED** — unfixed arm re-anchors away from the confirmed chain.

- [ ] **Step 3: Implement** — add the helper and gate both `_BRPeerManagerReanchorAtFloorLocked(manager, 1)` calls at ~4041 and ~4065:
```c
static int _BRPeerManagerCheckpointConfirmsOurChainLocked(BRPeerManager *manager, uint32_t contested) {
    if (manager->params->standardPort != BRMainNetParams.standardPort) return 0;
    const BRCFCheckpoint *cp = BRCFHighestCheckpointAtOrBelow(contested);
    if (! cp) return 0;
    if (BRCompactFilterChainCount(manager->compactFilterChain) == 0) return 0;
    if (BRCompactFilterChainStartHeight(manager->compactFilterChain) > cp->height) return 0;
    return UInt256Eq(BRCompactFilterChainHeader(manager->compactFilterChain, cp->height),
                     cp->filterHeader);
}
```
At each re-anchor site:
```c
#ifndef CF_CHECKPOINT_VETO_UNFIXED
if (_BRPeerManagerCheckpointConfirmsOurChainLocked(manager, contestedHeight)) {
    peer_log(peer, "cf-checkpoint: re-anchor VETOED — our chain is checkpoint-confirmed");
    _BRPeerManagerPeerMisbehavin(manager, peer);  // the disagreeing peer is the liar
    return; // or skip the re-anchor branch, mirroring local control flow
} else
#endif
    _BRPeerManagerReanchorAtFloorLocked(manager, 1);
```
`contestedHeight` = the height the divergent batch would have written (use the batch start height available at each site; if not in scope, compute from `BRCompactFilterChainNextHeight`).

- [ ] **Step 4: Run to verify GREEN** — veto KAT passes, RED arm re-anchors (red-confirms). Regression: the honest-reorg re-anchor above the checkpoint region still works (Task 5's KAT covers this; run `cf_scan_ledger_drive_kat`).

- [ ] **Step 5: Commit** — `fix(cf): a checkpoint-confirmed chain vetoes re-anchor — close the single-peer-liar hole`.

---

### Task 5: Quorum-reliability fixes (disagreers-must-agree + majority+floor≥3)

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRPeerManager.h` (constants), `BRPeerManager.c` (the disagreement/quorum bookkeeping ~3880 + the re-anchor decision ~4041)
- Test: extend the enforce/veto KAT or `cf_checkpoint_quorum_kat`

**Interfaces:**
- Consumes: `cfDisagreedPeers[]`, `cfDisagreedCount`, connected-filter-peer count.
- Produces: `CF_CONTINUITY_REANCHOR_FLOOR` (=3); re-anchor requires a strict majority of connected filter peers AND ≥ `CF_CONTINUITY_REANCHOR_FLOOR` distinct disagreers that **share the same** `prevFilterHeader`.

- [ ] **Step 1: Write the failing KAT** — red arm `-DCF_QUORUM_UNFIXED` restores K=2 / disagree-with-us. Assertions:
  - GREEN: 2 peers that disagree with us but with DIFFERENT `prevFilterHeader` (independent transients) → NO re-anchor (below floor / not sharing prev).
  - GREEN: ≥3 peers sharing the same `prevFilterHeader` AND a majority of connected filter peers, above the checkpoint region → re-anchor DOES happen (genuine majority still works).
  - RED: the 2 non-agreeing disagreers trigger a re-anchor (K=2 false-fire).

- [ ] **Step 2: Run to verify RED** — K=2 false-fires on the 2 non-agreeing disagreers.

- [ ] **Step 3: Implement** — in `BRPeerManager.h`:
```c
#ifndef CF_QUORUM_UNFIXED
#define CF_CONTINUITY_REANCHOR_FLOOR 3
#endif
```
Widen `cfDisagreedPeers[]` capacity to at least `CF_CONTINUITY_REANCHOR_FLOOR` and store each disagreer's claimed `prevFilterHeader` alongside its address (new parallel field `cfDisagreedPrev[N]`). In the quorum decision, count only disagreers whose `prevFilterHeader` matches the plurality prev, and require `count >= CF_CONTINUITY_REANCHOR_FLOOR && count > (connectedFilterPeers/2)`. Keep the `CF_CONTINUITY_REANCHOR_MAX=3`/session budget. Under `-DCF_QUORUM_UNFIXED`, fall back to the K=2/any-disagree behavior.

- [ ] **Step 4: Run to verify GREEN** — quorum KAT passes; RED arm false-fires (red-confirms). Regression: the veto KAT (Task 4) and enforce KAT (Task 3) still pass.

- [ ] **Step 5: Commit** — `fix(cf): re-anchor needs agreeing majority + floor>=3, not K=2 any-disagree`.

---

### Task 6: Never-brick recovery (terminal-advance + surface)

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRPeerManager.c` (the re-anchor budget-exhaustion / persistent-mismatch path)
- Test: `cf_checkpoint_neverbrick_kat` (create) or extend the enforce KAT

**Interfaces:**
- Consumes: `_BRPeerManagerSurfaceUnscannableLocked(manager, lo, floor, why)`, `BRCFHighestCheckpointAtOrBelow`, the auto-fetch cursor (`autoFetchCFiltersStart` / `cfHeadersRequestedThrough`).
- Produces: on exhaustion, the scan cursor is parked at the nearest trusted checkpoint and a recoverable band is surfaced; the loop does not crash or spin.

- [ ] **Step 1: Write the failing KAT** — red arm `-DCF_NEVERBRICK_UNFIXED` restores the old exhausted-budget behavior (silent stop / wedge). Scenario: every connected peer serves a checkpoint-violating chain, enforcement rejects every batch, the re-anchor budget (`CF_CONTINUITY_REANCHOR_MAX`) exhausts. Assertions (GREEN):
  - The fetch cursor is snapped to `BRCFHighestCheckpointAtOrBelow(tip)->height` (a trusted value), never a peer-supplied one.
  - `_BRPeerManagerSurfaceUnscannableLocked` was called (abandonedBelow raised / recover-me band surfaced) with a filter-chain-verification `why`.
  - The manager did NOT crash and the loop is not spinning (bounded — assert no unbounded re-request growth).
  - RED: the exhausted path silently stops (no surface, cursor wedged) — the current behavior.

- [ ] **Step 2: Run to verify RED** — unfixed path stops silently without surfacing.

- [ ] **Step 3: Implement** — at the budget-exhaustion site (where `cfReanchorCount >= CF_CONTINUITY_REANCHOR_MAX`):
```c
#ifndef CF_NEVERBRICK_UNFIXED
const BRCFCheckpoint *cp = (manager->params->standardPort == BRMainNetParams.standardPort)
                         ? BRCFHighestCheckpointAtOrBelow(<tip height>) : NULL;
if (cp) {
    // park the forward-fetch cursor at the trusted checkpoint (never a peer value)
    manager->autoFetchCFiltersStart = cp->height;   // exact field name per local code
    _BRPeerManagerSurfaceUnscannableLocked(manager, cp->height, <tip>+1,
        "filter-header chain could not be verified against checkpoints");
    // leave cfHeadersRequestedThrough where it is; do not accept further violating batches
}
#endif
```
This reuses the C2/I3 recover-me surfacing (raises `abandonedBelow`, WARN, drives the Kotlin banner) rather than inventing a new UI path.

- [ ] **Step 4: Run to verify GREEN** — never-brick KAT passes; RED arm wedges silently (red-confirms). Regression: `cf_abandon_total_kat` still passes (surfacing machinery shared).

- [ ] **Step 5: Commit** — `fix(cf): on checkpoint-verification exhaustion, park at trusted checkpoint + surface (never brick)`.

---

### Task 7: Kotlin surfacing message

**Files:**
- Modify: the recover-me banner string source (grep `Scan for missing transactions` / the `AbandonedBandBanner` copy in `app/src/main/java/io/digibyte/…` — the survey found the surfacing in WalletViewModel/SyncService)
- Test: `app/src/test/java/io/digibyte/…` (existing surfacing test, if any) or compile-only

**Interfaces:**
- Consumes: the `why`/reason surfaced by Task 6 (native → Kotlin via the abandoned-band surface path).
- Produces: user-facing copy that distinguishes a filter-chain-verification failure ("Couldn't verify the filter chain — pair your own node or rescan") from an ordinary abandoned band, if the surface path carries a reason; otherwise reuse the existing recover-me banner verbatim.

- [ ] **Step 1** — grep for the existing recover-me / abandoned-band banner copy and its ViewModel wiring; determine whether the surface reason reaches Kotlin. If it does, add the filter-chain-verification message variant; if it does not (reason is native-log-only), reuse the existing banner and note that a reason-plumbing follow-up is optional (do NOT build new native→Kotlin plumbing here — YAGNI; the recoverable state is what matters).
- [ ] **Step 2** — `./gradlew :app:testMainnetDebugUnitTest --tests '*Abandon*' --tests '*Recover*'` (whatever covers the banner) → green; if no test exists, `./gradlew :app:compileMainnetDebugKotlin`.
- [ ] **Step 3: Commit** — `feat(sync): filter-chain-verification recover-me message`.

---

### Task 8: Integration gate + on-device verification

**Files:** none (gate)

- [ ] **Step 1: Full host-KAT suite** — `./scripts/run-host-kats.sh` → exit 0, all green including the 4 new KATs (lookup, candidate, enforce, veto/quorum/neverbrick) and every red arm red-confirming. `cf_scan_ledger_drive_kat` still passes.
- [ ] **Step 2: All-ABI build** — commit the core submodule (push johnnylaw), bump the app pin, `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug` → BUILD SUCCESSFUL; record the APK sha256.
- [ ] **Step 3: On-device verification (user-coordinated, Note 8).** Install the RC, run a deep restore crossing several mainnet checkpoints. Confirm from logcat: every checkpoint crossing logs `cf-checkpoint: … OK (enforced)` / no `ENFORCE REJECT` against honest peers, **zero false rejections**, sync completes. This is the load-bearing safety check — an honest device must never trip enforce. (The observe telemetry already shows clean MATCHes; enforce must reproduce them.)
- [ ] **Step 4:** open PR to develop; independent review; then merge per the release flow (its own patch release, not bundled).

---

## Self-review

- **Spec coverage:** Piece 1 pre-commit enforce → Tasks 2+3; Piece 2 checkpoint-veto → Task 4; Piece 3 never-brick recovery → Task 6; Piece 4 quorum fixes → Task 5; table lookups → Task 1; surfacing → Task 7; mainnet-only + never-brick + historical-only constraints → Global Constraints + each task's gate; testing (none exists today) → Tasks 1-6 KATs + Task 8 device. Scope boundary (tip not covered, provenance) → stated, no task (correctly out of scope).
- **Placeholders:** none — every code step has concrete signatures/bodies. Two spots defer to reading an existing file for a fill-in (Task 2's `good[]` batch construction → `BRCompactFilterChainTests.c`; Task 7's banner string → grep result); both name the exact source to copy, which is concrete, not a placeholder.
- **Type consistency:** `BRCompactFilterChainBatchViolatesCheckpoint`, `BRCFHighestCheckpointAtOrBelow`, `BRCFCheckpointsInRange`, `_BRPeerManagerCheckpointConfirmsOurChainLocked`, `CF_CONTINUITY_REANCHOR_FLOOR` used identically across tasks; `BRCFCheckpoint`/`BRMainNetCFCheckpointsCount` match the header; the red-arm flags (`CF_CHECKPOINT_ENFORCE_UNFIXED`, `CF_CHECKPOINT_VETO_UNFIXED`, `CF_QUORUM_UNFIXED`, `CF_NEVERBRICK_UNFIXED`) are distinct per task.
