# saveBlocks Lock-Release Race — Implementation Plan (block-persistence UAF)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Close a peer-triggerable use-after-free / heap-overflow in the block-persistence path: `_peerRelayedBlock` releases `manager->lock` and then hands live pointers into `manager->blocks` to the `saveBlocks` callback, which serializes them while another peer thread can free/mutate them under multi-peer churn.

**Architecture:** Serialize the blocks to an **immutable byte buffer while holding `manager->lock`** (option (a)), release the lock, then hand the **bytes** (not block pointers) to the lock-free JNI upcall. Preserves the correct instinct (no native lock across JNI) and fixes the error (no live manager-owned pointers across the unlock).

**Tech Stack:** C (submodule `digibytewallet-core`: `BRPeerManager.c`, `BRMerkleBlock.c`), JNI bridge (outer `native/src/main/jni/bridge/jni_peer.c`), host **TSan** harness (`native/src/test/host/`).

## Root cause (confirmed — symbolized against the correct binary `5860a018`)
```
_peerThreadRoutine → _BRPeerAcceptMessage → _BRPeerAcceptHeadersMessage(BRPeer.c:633)
 → _peerRelayedBlock(BRPeerManager.c:1586) → bridge_saveBlocks(jni_peer.c:286)
  → BRMerkleBlockSerialize(BRMerkleBlock.c:220) → memcpy → OVERRUN/UAF
```
`_peerRelayedBlock` fills `saveBlocks[]` with `BRSetGet(manager->blocks, …)` pointers under the lock (`:1567-1571`), `pthread_mutex_unlock` at `:1581`, then `manager->saveBlocks(…, saveBlocks, i, …)` at `:1586`. In the unlocked window a concurrent peer thread's reorg (`BRSetRemove`/orphan swap → `BRMerkleBlockFree`) frees/mutates a pointed-to block; the two-pass serialize (size w/ NULL buf, then memcpy) reads inconsistent `hashesCount`/`hashes`/`flags` → heap overflow (grew) or UAF (freed). The `next` tail-recursion (`:1594`) widens the window under multi-algo (~15s) reorgs.

## Grounding baked into this plan (do not re-derive)
- **Class audit (BRPeerManager.c, all 9 callback fields swept):** ONLY `saveBlocks@1586` has the defect. `savePeers@1063` is the SAFE TEMPLATE (by-value `BRPeer save[]` snapshot under the lock — but `BRPeer` is a flat struct; `BRMerkleBlock` is NOT, it has `hashes`/`flags` heap pointers, so a shallow snapshot is insufficient — hence serialize, not copy). `saveFilterHeaders@2389` is a NEAR-MISS: passes `manager->compactFilterChain` but is safe ONLY because the lock is still held (unlock at `:2458`). Every other callback passes only the opaque `manager->info` or a freshly-malloc'd buffer.
- **Hold-time arithmetic (option (a) chosen):** `saveCount` hard-capped at `SAVE_BLOCK_COUNT=300` (BRPeerManager.h:60). CF-only path ⇒ every saved block is a bare **80-byte** header (`totalTx=0`, `hashes/flags=NULL`; blocks enter only via `_BRPeerAcceptHeadersMessage`→`BRMerkleBlockParse(…,81)` where the `off+4<=bufLen` guard fails ⇒ `totalTx=0`). Worst realistic save = **300 × 80 B = 24 KB**, added lock hold **~25-45 µs** on a Note 8; bounded ≤ ~150 µs even in the one-time post-upgrade case (legacy `totalTx>0` merkleblocks ~730 B, which `ClearMemory` evicts within a few save cycles). **Invisible to #33:** the UI never acquires `manager->lock`; it reads cached status mirrors refreshed under the lock at `~:1577`. Option (b) snapshot-then-serialize buys only ~10-15 µs of lock window at the cost of a double-copy + malloc-under-lock ⇒ rejected.
- **Trigger explained:** the crash landed on a `totalTx>0` block because those exist ONLY as pre-v4.0.0 upgrade remnants (full merkleblocks with malloc'd hashes/flags reconstructed from the saved-blocks blob). Narrow trigger; the fix closes the race for ALL block types (a freed bare header is also a UAF on the field reads).

## Global Constraints
- **SELF-CONTAINED. Own branch/spec/review/TSan gate. Do NOT touch the drain, the CF ledger, or Track B.** Separate bug, separate merge. Branch: outer `feat/saveblocks-lockrelease-race`, submodule `seq/saveblocks-serialize-under-lock` (off core develop `a2647b6`).
- **Red-before-green at the concurrency layer:** the TSan harness MUST fault/race-report on the UNFIXED code before the fix, then be clean after. A host KAT that can't reproduce the race can't prove the fix.
- **BuildId gate** (WORKFLOW-wallet.md house rule #7): any tombstone used in this work must have its BuildId matched to the retained `.so` first.
- Host KATs: `bash scripts/run-host-kats.sh; echo "EXIT=$?"` → `EXIT=0` (never pipe to tail/head). Build order native→app. Commit co-author `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`. Merges to develop need human approval; core PR before app PR, `merge-base --is-ancestor`-verified pin.

---

## Task 1: TSan stress harness — RED before green (do this FIRST)

**Files:** Create `native/src/test/host/saveblocks_race_tsan/{run.sh, saveblocks_race_tsan_main.c}` (outer).

**Design:** `#include "BRPeerManager.c"`. Two pthreads sharing one `BRPeerManager`: (1) a **relay thread** that repeatedly drives the save path (fill `saveBlocks[]` from `manager->blocks` + serialize — i.e. the real `_peerRelayedBlock` save logic, or a minimal replica calling the same serialize), and (2) a **mutation thread** that in a tight loop churns `manager->blocks` — insert/`BRSetRemove`+`BRMerkleBlockFree` (reorg), including at least one `totalTx>0` block with malloc'd `hashes`/`flags` so a freed-hashes memcpy is reachable. Deliberately target the unlock→callback window. Build `run.sh` on `gcs_match_kat/run.sh`'s unit list + **`-fsanitize=thread -fno-omit-frame-pointer -g`** (NOT ASan — this is a data race, TSan's domain). Header note: requires the pthread + TSan runtime; host CI is Linux/clang.

- [ ] **Step 1:** Build the harness; run it against the CURRENT (unfixed) `BRPeerManager.c`. **Expected: TSan reports a data race on the block fields (or a UAF/crash).** Capture the exact TSan output. If it does NOT reproduce, the harness isn't hitting the window — tighten the interleave (more iterations, a `totalTx>0` free mid-serialize) until it reliably faults. A harness that's green on unfixed code is worthless — iterate until RED.
- [ ] **Step 2: Commit** (outer, on `feat/saveblocks-lockrelease-race`): `test(peers): TSan stress harness reproducing the saveBlocks lock-release race (RED on unfixed core)`. Record the RED TSan output in the commit body / report.

## Task 2: the fix — serialize under the lock, hand bytes across JNI

**Files:** Modify submodule `BRPeerManager.c` (`_peerRelayedBlock` + the `saveBlocks` fn-ptr field + `BRPeerManagerNew` param) and outer `jni_peer.c` (`bridge_saveBlocks` + the registration). Keep the on-wire persistence format byte-identical (Kotlin's parser is unchanged).

**Contract change:** `saveBlocks` callback goes from `(…, BRMerkleBlock *blocks[], size_t count, …)` to `(…, const uint8_t *bytes, size_t len, …)`. The core builds the existing `[u32 count][per block: u32 serLen, u32 height, serialized bytes]` buffer **while holding `manager->lock`** (using `BRMerkleBlockSerialize`, which resolves `hashes`/`flags` into flat bytes — no dangling pointers escape), THEN unlocks, THEN calls `saveBlocks(info, replace, bytes, len, &stackIntegrityCheck)`. `bridge_saveBlocks` becomes a thin bytes→`SetByteArrayRegion`→`CallVoidMethod` handoff (still lock-free). Single-pass, pre-sized buffer (size pass + fill pass BOTH under the lock — the whole point).

- [ ] **Step 1:** Add a static helper in `BRPeerManager.c` that serializes a `BRMerkleBlock*[]` + heights into the framed buffer (moved from `jni_peer.c:263-291`), returning `malloc`'d `(bytes,len)`. Call it in `_peerRelayedBlock` BEFORE `pthread_mutex_unlock` (`:1581`); then unlock; then invoke `manager->saveBlocks(info, REPLACE_SAVED_BLOCKS, bytes, len, &stackIntegrityCheck)`; `free(bytes)` after. Confirm `saveCount` bound (300) keeps the buffer ≤ ~24 KB (bare headers) — a stack VLA is NOT safe here (219 KB pathological), use `malloc`.
- [ ] **Step 2:** Update the `saveBlocks` fn-ptr typedef in the `BRPeerManager` struct + `BRPeerManagerNew` signature + its `.h`. Update `jni_peer.c`: register the new-signature callback; `bridge_saveBlocks` now receives `(bytes,len)` and does only the JNI upcall.
- [ ] **Step 3:** Rebuild the TSan harness (Task 1) against the FIXED core; run. **Expected: TSan CLEAN, no race, no crash — the green half of red-before-green.** Full host-KAT sweep `EXIT=0`. Commit (submodule + outer): `fix(peers): serialize saved blocks under manager->lock; hand bytes (not live pointers) to the JNI upcall`.

## Task 3: pin the `saveFilterHeaders` near-miss

**Files:** Modify submodule `BRPeerManager.c` (`_peerRelayedCFHeaders`, `~:2389`).

- [ ] **Step 1:** Add a comment + a runtime assert at the `saveFilterHeaders` call that `manager->lock` is HELD there (it passes the live `manager->compactFilterChain`) and the unlock MUST stay below it — so a future "shorten the lock across JNI" edit can't silently reintroduce the saveBlocks bug on the CF header chain. (bridge_saveFilterHeaders already copies into its own buffer + does not re-lock — safe as long as the lock stays held at the call.) Commit (submodule): `docs(peers): pin the lock-must-be-held invariant on the saveFilterHeaders near-miss`.

## Task 4: correct the shutdown-misattribution comment

**Files:** Modify submodule `BRPeerManager.c` (`:76-79`, the `stackIntegrityCheck` block).

- [ ] **Step 1:** Replace the *"Perhaps iOS is killing the C part… memory corruption can occur"* explanation with the real mechanism: this canary guarded a lock-release-then-use race in the saveBlocks path (blocks freed/mutated by a concurrent peer thread during the unlocked serialize), fixed in Task 2 by serializing under the lock. Keep the canary as belt-and-suspenders but stop the comment from lying about the cause (the same principle as the filterBufMagic comment fix — a soothing comment about a real hazard is worse than none). Commit (submodule): `docs(peers): correct saveBlocks corruption comment — it was a lock-release race, not app shutdown`.

## Task 5: verify + build
- [ ] **Step 1:** `bash scripts/run-host-kats.sh; echo "EXIT=$?"` → `EXIT=0` (incl. the new TSan harness green on fixed code). `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug` → `BUILD SUCCESSFUL`.
- [ ] **Step 2:** Fresh **hostile whole-branch review** (fresh reviewer, not the plan author) briefed with: the class audit (only saveBlocks; savePeers template; saveFilterHeaders near-miss), the hold-time arithmetic (option (a), 24 KB/~40 µs, UI-invisible), and the red-before-green TSan evidence. Named targets: (a) the byte buffer is fully built UNDER the lock (no manager-owned pointer survives the unlock); (b) the on-wire format is byte-identical (Kotlin parser unchanged); (c) `malloc` not VLA for the buffer; (d) TSan was genuinely RED on unfixed code then GREEN.

## Acceptance
Code gate = hostile-review-clean + TSan red-before-green proven. Release gate = on-device: the multi-peer wipe/restore re-sync (default config, public fleet) that crashed **no longer SIGSEGVs** (retain the symbol-stable `.so`; verify any tombstone's BuildId before trusting it). THEN this branch merges (core PR → develop → app pin bump → app PR), and **Track B (stale-buffer livelock) becomes testable** because the multi-peer resync no longer crashes out from under its acceptance run. Purge-on-exclusive slots in parallel/after (independent, small).
