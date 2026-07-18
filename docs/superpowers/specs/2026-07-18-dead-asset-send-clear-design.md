# Dead Asset-Send Clear — Design Spec

**Status:** Design (ready for review → implementation plan)
**Date:** 2026-07-18
**Author:** review-gated investigation (6-agent workflow, verdict NEEDS_WORK → gaps closed here)
**Related:** `project_digiasset_balance_inflation_sovereign_fix` (memory), held branch `feat/native-asset-spent`, [[project_dgb926_dust_threshold_asset_send_bug]]

## Goal

Let a user clear a **dead** DigiAsset send — one broadcast, locally registered, then rejected by the node (e.g. a pre-v3.10.36 700-sat dust marker) and wedged "Unconfirmed" forever — and, in doing so, remove the **owned-address phantom asset rows** that dead send left in Room, returning the displayed asset balance to its true value. Concretely: heal the S25 Ultra's `CHANG` **30 → 10** and clear the wedged tx `26e7edaa…683f91b7`, **sovereignly** (native/owned-set authority only, no backend), **without deleting any genuine holding or enabling a double-spend.**

## Background — the confirmed root cause

The inflation is **two owned-address `is_asset=1, spent=0` rows per partial asset send** (not the recipient marker — that is correctly gated out because it is not owned):

1. **Un-decremented input.** `AssetManager.sendAsset` (`AssetManager.kt:953`) deliberately does **not** call `markSpent` on the consumed asset input, so the pre-send input row (full quantity, at an owned address) stays counted.
2. **Owned asset-change marker.** `publishTransaction` runs `BRWalletRegisterTransaction` **before** broadcast (`jni_transaction.c:201`), so the send becomes wallet-known regardless of the node's dust rejection. The 30 s `sweepKnownTransactionsForAssets` + `onTransactionReceived` then run `processIncomingAssetTx`, which inserts an `is_asset` row for every **owned** non-OP_RETURN output. The asset-change output targets `getChangeAddress(1)` (`AssetManager.kt:922`) — an **owned** address — so it passes the ownership gate and becomes a new counted row.

Both rows sit at owned addresses, so the v3.10.36 **sovereign prune** (`refreshAssetUtxosFromNetwork`, deletes only rows whose script ∉ owned set) is structurally incapable of removing them. `insertAll` uses `OnConflictStrategy.REPLACE` on PK `(txid,vout)`, so repeated 30 s sweeps do not multiply a single outpoint — `CHANG`'s **30 / supply 10 / 9 UTXOs** is the residue of **multiple** dead dust-send attempts, each leaving a distinct change marker.

**Never-mined invariant (critical for correctness):** a dust-rejected send **never moved coins on-chain**, so the wallet **still owns the full original input**. The true balance is the **full original amount**; every unit above it is a fabricated marker. Therefore the clear must **delete only the fabricated output markers** and **restore the input as spendable** — it must **not** reduce the balance below the original.

> The **confirmed-send** input over-count (an input not decremented after a send that *did* mine) is a **different** phantom. It is the job of the held `feat/native-asset-spent` branch (native `spentOutputs` decrement), **not** this clear — confirmed sends are always KEPT here.

## Architecture

Extend the existing **user-triggered** `WalletManager.clearStuckSends()` (Settings → "Clear stuck sends"). Today it drops unconfirmed txs from the native wallet + `OutgoingTxStore` but **never touches Room**. Add a Room asset-cleanup pass, gated by a strict **dead-tx predicate**, factored into a new `AssetManager.clearDeadAssetSend(txid)` helper (the owned-set derivation and `UtxoDao` live in that layer).

Authority is **native + owned-set only**. No backend is consulted. The DigiScope path is never a delete authority (reaffirms sovereignty-first).

## Design

### Dead-tx predicate (closes Gap 2 — never-mined vs slow-valid)

`clearStuckSends` today drops **every** unconfirmed recorded send with no validity/dust filter — that would clobber a slow-but-valid asset send that is merely propagating. Replace the blanket drop with a predicate. A recorded tx is **dead** iff it is unconfirmed **and**:

- `!NativeBridge.isTransactionValid(txid)` (a double-spend / conflict — authoritative), **OR**
- it has a **below-dust output**: any non-OP_RETURN output with `1 ≤ sats < DUST_FLOOR` (a tx with a sub-dust output is non-relayable → can never confirm). Use `DUST_FLOOR = DGB_CHANGE_DUST_THRESHOLD` (5 460, the conservative legacy floor; the current 6 000-sat `DA_MARKER_SATS` sends sit above it and are never flagged).

A valid, above-dust, merely-slow send matches **neither** clause → left untouched. (This predicate also neutralizes **Gap 3**, the confirmed-height race: a tx that just confirmed is valid and has no sub-dust output, so it is never classified dead even while it still reports `TX_UNCONFIRMED`.)

Confirmed txs (`blockHeight` in `(0, INT_MAX)`) are KEPT exactly as today.

### Per-dead-tx algorithm (closes Gap 1 — no resurrection; sovereign)

Ordering is load-bearing. For each dead `txid`:

1. **Read outpoints while the tx is still wallet-known** (the JNI readers return null after removal):
   - `inputs  = NativeBridge.getTransactionInputsForHash(txid)`  → `"prevTxidHex|prevVout"` list.
   - `outputs = NativeBridge.getTransactionOutputsForHash(txid)` → `"vout|sats|scriptHex"` list.
2. **Drop from native:** `NativeBridge.removeTransaction(txid)`. This cascades to dependents and, in the native wallet, **un-spends the dead tx's inputs iff no other registered tx also spends them** (BRWallet rebuilds `spentOutputs` from all remaining txs' inputs). This native post-removal state is the **authority** for step 4.
3. **Delete owned OUTPUT phantoms (always safe):** for each output whose `scriptHex ∈ buildOwnedScriptHexes()`, call `UtxoDao.deleteAssetUtxo(txid, vout)`. These are the fabricated change/self-markers of a never-confirmed tx — never genuine holdings. Scope strictly to **this** txid's owned outputs; never delete another tx's rows, never delete non-owned rows (the prune owns those).
4. **Restore the consumed input — gated on native post-removal truth (the anti-resurrection guard):** for each input outpoint, un-spend the Room asset row **only if** the native wallet now reports that outpoint **unspent**:
   - When native `outpointSpentState(prevTxid, prevVout)` is available (held branch): `UtxoDao.markUnspent(prevTxid, prevVout)` **iff** state == UNSPENT (0). If native still reports it SPENT (1) — because a *different* confirmed tx legitimately spends it — **leave `spent=1`**. This is the exact guard the prior `rederiveAssetUtxos` attempt lacked.
   - When `outpointSpentState` is **absent** (current `develop`): **skip** the un-spend. On develop `markSpent` is unwired, so asset input rows are already `spent=0` — there is nothing to un-spend and nothing to resurrect. The clear still heals the balance via step 3 alone.
5. `store.remove(txid)`; after the loop, `WalletTxPersister(context).persist()`.

**Why this heals `CHANG` on develop today:** step 3 deletes the owned change-marker phantoms (the +20); the still-owned original input row remains `spent=0` and correctly counted → balance returns to the true 10. Step 4 is a safe no-op on develop and becomes the forward-safe input restorer once the held branch lands.

### New/changed surfaces

- `UtxoDao.markUnspent(txid, vout)` — **new** `@Query("UPDATE utxos SET spent = 0 WHERE txid = :txid AND vout = :vout")` (mirror of the existing `markSpent`).
- `AssetManager.clearDeadAssetSend(txid, inputs, outputs): AssetClearResult` — **new** helper: performs steps 3–4 against Room using `buildOwnedScriptHexes()` (owned-set, built once and passed in for a batch) and the outpoint lists captured in step 1. Returns counts (`outputsDeleted`, `inputsRestored`) for logging/UX. Pure of native calls except an optional `outpointSpentState` read (guarded).
- `WalletManager.clearStuckSends()` — **changed**: apply the dead-tx predicate; for each dead tx, capture inputs/outputs → `removeTransaction` → `clearDeadAssetSend`. Return type extended to also report assets cleaned.
- Optional `outpointSpentState` seam — referenced defensively; present only on the held branch. Detect via a capability check (try/catch on the extern or a `hasOutpointSpentState()` flag) so develop compiles and runs without it.

## Sequencing with `feat/native-asset-spent`

This clear **ships on `develop` first** and fixes the Ultra without waiting on the held branch:
- Steps 1–3 (+5) are fully functional on develop and heal the inflation.
- Step 4 is a guarded no-op on develop and the correct input-restorer under the branch.
- When the held branch merges (wiring `markSpent` on send + `outpointSpentState`), step 4 activates automatically and stays correct because it mirrors the **native post-removal** spent-state — it can never un-spend an outpoint a different confirmed tx spends.

No submodule change is required for this spec (all native seams — `removeTransaction`, `getTransaction{Inputs,Outputs}ForHash`, `isTransactionValid` — already exist on develop). `outpointSpentState` arrives with the held branch.

## Testing

**Unit / DAO (host + androidTest, no device):**
- `markUnspent` flips `spent` 1→0 for the exact outpoint only.
- `clearDeadAssetSend`: given owned outputs + a mix of owned/non-owned, deletes only owned outputs of the target txid; never touches other txids or non-owned rows.
- Dead-tx predicate truth table: (unconfirmed + sub-dust) = dead; (unconfirmed + valid + above-dust) = **not** dead; (confirmed) = kept; (unconfirmed + `isTransactionValid==false`) = dead.
- Resurrection guard (simulated `outpointSpentState`): input un-spent only when native reports UNSPENT; left `spent=1` when native reports SPENT-by-other.

**On-device (S25 Ultra — the live test bed; leave `CHANG` 30/10 as-is until this runs):**
1. Confirm the pre-state: `CHANG` Balance 30 / Supply 10 / 9 UTXOs; tx `26e7eda…` wedged Unconfirmed.
2. Run the extended "Clear stuck sends" → `CHANG` returns to **10**, UTXOs Held corrects, the input asset is spendable on a fresh send, and `26e7eda…` leaves the activity list.
3. Regression: an unrelated asset holding and unrelated **confirmed** sends are untouched; a **slow-but-valid** unconfirmed send (if present) is **not** cleared.
4. Compose-test with `feat/native-asset-spent` checked out: step 4 restores the input without double-counting after `removeTransaction`'s native un-spend, and a **confirmed** asset send still decrements correctly (no resurrection).

## Decisions & open questions (for review)

1. **Dead predicate scope** — chosen: `invalid OR below-dust-output`. Deliberately **not** pure age-based (age alone is ambiguous). Add an age floor as an extra guard? (Recommend: no — below-dust/invalid is precise; age adds false-negative risk.)
2. **Proactive surfacing** — v1 relies on the existing user-triggered "Clear stuck sends" button. A proactive "stuck send detected" banner (so the user knows to run it) is a **follow-up**, not in this spec. Confirm.
3. **DGB-side coherence (out of scope, noted)** — the dead tx's DGB change output row (`is_asset=0`) is left to the existing DGB reconcile; `deleteAssetUtxo` is `is_asset=1`-scoped and won't touch it. Flag only so DGB balance display stays coherent after a clear.
4. **Helper return / UX copy** — surface `"Cleared N stuck send(s); corrected M asset row(s)"` vs the current `(dropped, kept)` pair. Confirm wording.

## Non-goals

- Decrementing balance on a **confirmed** asset send (held `feat/native-asset-spent`).
- Removing the `/api/assets/unspent` poll (separate sovereignty item; converges on native asset enumeration later).
- Any backend change.
