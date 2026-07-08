# Saved-Blocks Use-After-Free Hardening — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:systematic-debugging (confirm ownership model first) then superpowers:subagent-driven-development. Steps use `- [ ]`.
> **Device verification authorized on the Note 8 (SM-N950U, ce061716640b191c017e).**

**Goal:** Eliminate a confirmed use-after-free on the peer-manager free+recreate path so `forceReconnect()` (pull-to-refresh, onResume) can never dereference freed block objects — which produces garbage block heights / wrong-chain / stuck sync intermittently (heap-reuse dependent), and risks a crash.

**The bug (confirmed by adversarial review + code read):**
- `BRPeerManagerNew` adds the caller's saved blocks to its sets by **raw pointer, no copy**: `BRSetAdd(manager->blocks, block)` (`BRPeerManager.c:1854`) and `BRSetAdd(manager->orphans, blocks[i])` (`:1869`). The manager thereby *owns* those `BRMerkleBlock*`.
- `BRPeerManagerFree` frees them via `_setApplyFreeBlock` → `BRMerkleBlockFree` (`:313-315`, applied to orphans at `:368`; verify it also applies to `manager->blocks`).
- In `jni_peer.c`, `g_savedBlocks` is passed to `BRPeerManagerNew` (`:597`/`:600`) but is **never nulled** after handoff (only `loadSavedBlocks` reallocs it, `:831-838`). So after a `BRPeerManagerFree` on the recreate path (`:541`), `g_savedBlocks` points to freed memory, and the immediate next `BRPeerManagerNew` (recreate) **re-passes dangling pointers** → deref at `BRPeerManager.c:1854/1869`. `getSavedBlocksTip` (`:721-725`) iterating `g_savedBlocks` is UAF too.
- Reachable today via the surviving `forceReconnect()+startSync()` callers: `WalletViewModel.kt:74-75` (pull-to-refresh) and `MainActivity.kt:234-235` (onResume). (The Network Info trigger was one of three; it's now removed by the read-only fix.)

**Suspected additional latent hazard to confirm:** if the manager frees the `g_savedBlocks` originals on `Free`, then `loadSavedBlocks` freeing old `g_savedBlocks` on a later reload (`:831-835`) would be a **double-free**. Step 1 must settle this.

## Global Constraints
- Submodule commit/push discipline (`GIT_DIR`/`GIT_WORK_TREE` → `johnnylaw` → bump pin).
- Do not regress the initial-sync path (loadSavedBlocks → startSync) or the block-header chain height after a clean launch. The wallet must still resume from its persisted chain, not re-floor to the checkpoint.
- Verify with **AddressSanitizer** (host) — a logic-only test can miss UAF; the KAT must run under `-fsanitize=address`.

## File Structure
- Create: `native/src/test/host/savedblocks_uaf_kat/{main.c,run.sh}` — ASan repro + regression guard.
- Modify: `native/src/main/jni/digibytewallet-core/BRPeerManager.c` and/or `native/src/main/jni/bridge/jni_peer.c` — the ownership fix (approach chosen in Step 2).

---

## Task 1: Reproduce the UAF under ASan + pin the ownership model

**Files:** Create `native/src/test/host/savedblocks_uaf_kat/main.c` + `run.sh`.

- [ ] **Step 1: read `BRPeerManagerNew` + `BRPeerManagerFree` end-to-end** and answer, in the report: (a) does `Free` free `manager->blocks` AND `manager->orphans` (double-free risk if a block is in both sets)? (b) after `New` takes the blocks, does any existing path also free the caller's array (latent double-free with `loadSavedBlocks:831-835`)? Quote the exact lines.
- [ ] **Step 2: write the ASan KAT** — `main.c`: construct N `BRMerkleBlock*` (via `BRMerkleBlockNew` + set heights), call `BRPeerManagerNew(..., blocks, N, ...)`, then `BRPeerManagerFree(mgr)`, then **`BRPeerManagerNew(..., blocks, N, ...)` again with the SAME array** (mimicking the jni recreate that re-passes `g_savedBlocks`), then `BRPeerManagerFree`. `run.sh` compiles the real core sources with `clang -fsanitize=address -g` (model on `network_switch_kat/run.sh`, add the ASan flag + the needed .c files: BRPeerManager.c, BRMerkleBlock.c, BRWallet.c, BRAddress.c, crypto, etc.).
- [ ] **Step 3: run → ASan reports heap-use-after-free** (or double-free) at the second `New`/`Free`. This is the red repro. Capture the ASan trace in the report.

---

## Task 2: Fix the ownership (approach decided by Task 1's findings)

**Candidate approaches (pick per Task 1's ownership answer; RECOMMENDED first):**
1. **Deep-copy in `BRPeerManagerNew`** — copy each incoming block (`BRMerkleBlockCopy` if it exists, else serialize+parse) before `BRSetAdd`, so the manager owns its OWN copies and the caller's array stays valid across recreates. Requires: confirm no other caller relied on ownership-transfer (only `jni_peer.c` calls it in our fork), and that `loadSavedBlocks` remains the sole owner/freer of `g_savedBlocks` (no double-free). Cleanest; costs transient extra block memory.
2. **Null `g_savedBlocks` after handoff + reload-before-recreate** — after the first `New`, set `g_savedBlocks=NULL/count=0`; on the recreate path, re-`loadSavedBlocks()` from persistence BEFORE `BRPeerManagerNew` so the chain is repopulated (not empty). Keeps core semantics; moves the coordination to jni + the recreate caller.
3. **Snapshot-before-free** — before `BRPeerManagerFree` on recreate, serialize the live manager's block chain and pass those fresh objects to the new manager.

- [ ] **Step 1: implement the chosen approach.** If (1): make the copy in `BRPeerManagerNew` and verify `BRPeerManagerFree` frees only the manager's copies; ensure `getSavedBlocksTip`/any `g_savedBlocks` reader is still valid (or also fix it to not read freed memory).
- [ ] **Step 2: re-run the ASan KAT → clean** (no UAF/double-free), and it still builds the chain correctly (assert the recreated manager reports the expected tip height).
- [ ] **Step 3: build native + app** → `BUILD SUCCESSFUL`. Re-run all host KATs.
- [ ] **Step 4: on-device (Note 8) stress** — install debug build; repeatedly trigger `forceReconnect` via pull-to-refresh on the wallet screen and background/onResume cycles (`adb shell input`… permitted on the Note 8), ~30 cycles, while watching `adb logcat -b crash` for SIGSEGV and confirming block height stays correct (`getLastBlockHeight` non-zero, chain not re-floored) and the tx stays confirmed. This mirrors the v3.7.1 30-cycle reconnect stress that validated the g_peerManager guard.
- [ ] **Step 5: commit** (submodule + KAT); push to `johnnylaw`; bump pin.

---

## Verification checklist
- [ ] ASan KAT: red before the fix (Task 1), green after (Task 2).
- [ ] All existing host KATs still green.
- [ ] Both flavors build.
- [ ] Note 8: 30× forceReconnect (pull-to-refresh + onResume) stress with `logcat -b crash` clean; block height stays correct; no re-floor to checkpoint; confirmed tx stays confirmed.
- [ ] Submodule pushed to `johnnylaw`; pin bumped.

## Notes
- This is memory-safety-critical and heap-reuse-dependent — a plain logic test WILL miss it; ASan is mandatory.
- Independent of the peer-churn fix (`2026-07-08-cf-sync-peer-reliability.md`) — can ship in the same release or separately, but keep it a distinct, individually-reviewed commit given the risk.
