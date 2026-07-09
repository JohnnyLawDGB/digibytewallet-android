# Compact-Filter Confirmation Driver — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.
> Device verification on the Note 8 is **post-release** (signing: release-key is CI-only; a signed vX installs in-place over the installed release without wiping — see [[project_signing_and_ci]]).

**Goal:** Make `COMPACT_FILTERS_ONLY` sync actually **confirm** transactions (set `blockHeight`), fixing the reported symptom (tx + balance visible but stuck `UNCONFIRMED` forever) and delivering the linchpin for bloom removal. No BIP37 merkle proof, no bloom.

**Architecture:** The CF full-block handler (`BRPeer.c:_BRPeerAcceptBlockMessage`) already parses every tx in a filter-matched block and registers it via `relayedTx`. It just never confirms. This plan collects the block's tx hashes in that handler and hands `(blockHash, txHashes[])` to a new manager callback, which derives the height from the already-synced header (`BRSetGet(manager->blocks, &blockHash) → b->height`) and confirms the wallet's txs via the **existing** `_BRPeerManagerUpdateTx`/`BRWalletUpdateTransactions` primitive. Reference: `docs/superpowers/specs/2026-07-09-cf-only-pipeline-audit.md`.

## Global Constraints
- Submodule discipline: `GIT_DIR`/`GIT_WORK_TREE`; push to `johnnylaw/header-on-ddencode` before pin bump.
- **Reuse the existing confirm primitive** `_BRPeerManagerUpdateTx(manager, txHashes, count, height, txTime)` (BRPeerManager.c:1578) → `BRWalletUpdateTransactions` — do NOT write a new confirmation mechanism. Confirmation *count* is already depth-derived (`tip − blockHeight`) in `BRWallet`.
- **No merkleblock / `BRMerkleBlockTxHashes` dependency** on the CF path — the full block carries all tx bytes; hashes come from the parsed txs.
- The peer layer (`BRPeer.c`) stays wallet-agnostic: it passes ALL of the block's tx hashes; the manager filters to wallet txs (via `BRWalletContainsTransaction`/`BRWalletTransactionForHash`) before `_BRPeerManagerUpdateTx` (avoids handing a huge list to the wallet + keeps ownership clean).
- **Idempotent + main-chain-safe:** only confirm if the block is present in `manager->blocks` AND on the main chain (mirror the existing `_peerRelayedBlock` main-chain check at BRPeerManager.c:~1596-1601). Re-delivery of an already-confirmed block must be a no-op.
- **Mempool product decision (settled 2026-07-09):** CF-only shows an incoming tx only once **mined** (BIP158 can't see the mempool) — this is accepted + will be surfaced to users as a privacy tradeoff. This plan confirms mined txs; it does not add mempool visibility.
- Lock discipline: the new manager handler takes `manager->lock` like the other relayed-* handlers; do NOT hold it across the wallet callback re-entrancy (follow `_peerRelayedBlock`'s pattern exactly).

## File Structure
- Modify `native/src/main/jni/digibytewallet-core/BRPeer.h` — add the new callback to the callback struct + `BRPeerSetCallbacks` signature.
- Modify `native/src/main/jni/digibytewallet-core/BRPeer.c` — full-block handler collects tx hashes + fires the new callback; thread the callback through `BRPeerSetCallbacks`.
- Modify `native/src/main/jni/digibytewallet-core/BRPeerManager.c` — implement the handler; register it in both `BRPeerSetCallbacks` call sites (~:2440, ~:2588).
- Create `native/src/test/host/cf_confirm_kat/` — host KAT proving a registered tx gets its height set through the new manager handler (with a synthetic block in `manager->blocks`), and that a non-main-chain / missing block is a no-op.

---

## Task 1: New peer→manager callback for CF full-block confirmation

**Files:** `BRPeer.h`, `BRPeer.c`.

**Interfaces:** Produces callback `void (*relayedBlockTxns)(void *info, UInt256 blockHash, const UInt256 txHashes[], size_t txCount)` on `BRPeer`, fired by `_BRPeerAcceptBlockMessage` after all txs are delivered.

- [ ] **Step 1:** In `BRPeer.h`, add `void (*relayedBlockTxns)(void *info, UInt256 blockHash, const UInt256 txHashes[], size_t txCount);` to the callback struct (near `relayedBlock`), and add the matching parameter to `BRPeerSetCallbacks(...)`.
- [ ] **Step 2:** In `BRPeer.c` `BRPeerSetCallbacks`, wire the new param into `ctx->relayedBlockTxns`.
- [ ] **Step 3:** In `_BRPeerAcceptBlockMessage` (BRPeer.c:939): compute the block hash from the 80-byte header (`BRSHA256_2` over `msg[0..80]` → `UInt256 blockHash`, matching how block hashes are computed elsewhere — verify against `BRMerkleBlockParse`). Allocate a `UInt256 *txHashes` sized `txCount`; as each tx is parsed, record `tx->txHash` into the array (BEFORE `relayedTx` takes ownership — read `tx->txHash` first). After the loop, if `ctx->relayedBlockTxns && txCount > 0`, call `ctx->relayedBlockTxns(ctx->info, blockHash, txHashes, txCount)`. Free the array. Preserve all existing malformed-block guards + the existing `relayedTx` delivery.
- [ ] **Step 4:** Build native + app → BUILD SUCCESSFUL (handler compiles; callback unset in the manager yet = no behavior change).
- [ ] **Step 5:** commit (submodule).

---

## Task 2: Manager handler — confirm the wallet's txs at the synced block height

**Files:** `BRPeerManager.c`.

**Interfaces:** Consumes `relayedBlockTxns`. Reuses `_BRPeerManagerUpdateTx` (BRPeerManager.c:1578).

- [ ] **Step 1:** Add `static void _peerRelayedBlockTxns(void *info, UInt256 blockHash, const UInt256 txHashes[], size_t txCount)`:
  - `pthread_mutex_lock(&manager->lock)`.
  - `BRMerkleBlock *b = BRSetGet(manager->blocks, &blockHash);` — if `!b`, unlock + return (header not synced yet; the block will be re-requested / re-relayed).
  - **Main-chain check** (mirror BRPeerManager.c:~1596-1601): walk `manager->lastBlock` back to `b->height`; confirm `BRMerkleBlockEq(walked, b)`. If not on the main chain, unlock + return.
  - Filter to wallet txs: build `UInt256 walletHashes[]` from `txHashes` where `BRWalletTransactionForHash(manager->wallet, txHashes[i]) != NULL`. (Cap/stack-guard the array — a block can have many txs; use a heap alloc sized `txCount` or filter in place.)
  - If any wallet hashes: `_BRPeerManagerUpdateTx(manager, walletHashes, walletCount, b->height, b->timestamp)`.
  - Unlock. Then, OUTSIDE the lock, if the wallet's tx status changed, fire `manager->txStatusUpdate(manager->info)` (match how `_peerRelayedBlock` notifies).
  - **Do not** call `_BRPeerManagerUpdateTx` with 0 count (matches the existing `if (txCount > 0)` guard).
- [ ] **Step 2:** Register `_peerRelayedBlockTxns` in BOTH `BRPeerSetCallbacks` call sites (BRPeerManager.c:~2440 and ~:2588) as the new `relayedBlockTxns` argument.
- [ ] **Step 3:** Build native + app → BUILD SUCCESSFUL.
- [ ] **Step 4:** commit (submodule).

---

## Task 3: Host KAT — CF confirmation sets blockHeight

**Files:** Create `native/src/test/host/cf_confirm_kat/{cf_confirm_kat_main.c,run.sh}`.

- [ ] **Step 1 (red):** KAT that: builds a `BRWallet`, registers a tx paying the wallet (unconfirmed → assert `BRWalletTransactions` shows it `TX_UNCONFIRMED`); builds a `BRMerkleBlock` at a known height and inserts it into a manager's `blocks` set (or drives `_peerRelayedBlockTxns` via a minimal harness); calls the new confirm path with `(blockHash, [txHash])`; asserts the tx's `blockHeight == the block height` afterward. Also assert: a block NOT in the set → no-op (tx stays unconfirmed); a non-wallet tx hash → ignored. Model `run.sh` on `network_switch_kat/run.sh`, compiling the needed core .c files. Run → fails (path not wired / wrong).
- [ ] **Step 2:** With Tasks 1–2 in place, run → PASS.
- [ ] **Step 3:** commit (KAT, outer repo).

(If exercising `_peerRelayedBlockTxns` directly is impractical because it's `static`, either `#include "BRPeerManager.c"` in the KAT as other KATs do for statics, or assert at the `BRWalletUpdateTransactions` level that a height-stamp on a registered wallet tx produces the expected `TX_CONFIRMED` state — the primitive is the invariant under test.)

---

## Task 4: Wire-through + release
- [ ] **Step 1:** Full build both flavors + all host KATs green.
- [ ] **Step 2:** Push submodule to `johnnylaw/header-on-ddencode`; bump the outer pin.
- [ ] **Step 3:** Ship as **v3.10.3**; on the Note 8 (in-place update over v3.10.1/.2), set **Compact Filters Only**, and verify a mined incoming tx AND a sent tx transition `UNCONFIRMED → confirmed` at the correct height (`getTransactionDetails` blockHeight non-`INT32_MAX`; confirmations tick with the chain). This is the on-device proof that CF-only confirms — the go/no-go for the rest of the bloom-removal sequence.

## Out of scope (follow-on, tracked in the audit doc)
Watch-set +100/taproot pregen hoist (#3), mainnet CF-peer retention (#4), stranded-send rebroadcast rework (#6), and finally bloom excision (`X.0.0`). This plan is step #1 — the one that makes CF-only viable.
