# Sovereign Native Asset Balance — Design

**Date:** 2026-07-18
**Status:** Approved shape (post red-team); ready for implementation plan
**Author context:** DigiByte Android wallet (Kotlin + C-core JNI). DigiAsset balance layer.

## Goal

Make the DigiAsset balance derive from the wallet's own on-chain / native truth — the same way DGB and DigiDollar balances already do — so that:

1. **The phantom over-count stops** (e.g. the "Chang" test asset shows ~21/10 UTXOs when the true holding is 10 in ~1 UTXO), and
2. **The `/api/assets/unspent` backend dependency leaves the standing data path** (the operator-observed server flood), demoted to the same user-triggered on-demand reconcile DGB already relies on.

Both without ever deleting a row on a signal that cannot prove the row is a phantom rather than a real holding the wallet simply hasn't scanned.

## Non-goals (explicitly deferred)

- **Native spent-decrement of asset inputs** — the `feat/native-asset-spent` branch (`BRWalletUtxoSpendable`/spentOutputs). This design must be *sequenced before* that branch and must not fight it, but does not implement it.
- **Full native quantity resolution for percent/range transfers** — the "M3" parent-walk. This design preserves already-resolved quantities and never downgrades them, but does not complete native resolution.
- **Removing the backend entirely** — Posture A keeps `/api/assets/unspent` as an on-demand reconcile fallback (like DGB's node reconcile). Full removal is a later step once native quantity resolution lands.

## The HARD CONSTRAINT (load-bearing)

> A delete keyed on a signal that cannot distinguish "phantom" from "real holding the authority never scanned" loses funds-display (and spendability).

This was established by two independent adversarial passes. It rules out:

- **Native-absence deletes** — a real holding at a gap-limit / restored / pre-install address is absent from native's watch set, indistinguishable from a removed-tx phantom.
- **Backend-absence deletes** — the asset client contract (`AssetNetworkClient.getAssetUtxos` → flat `List<AssetUtxoResponse>?`, no per-address ack, no completeness token; `DigiScopeAssetClient` silently drops UTXOs it can't classify and returns empty on a missing key; `MultiEndpointAssetClient` rotates endpoints per chunk) makes a stale-but-`200 OK` response that *omits* a real holding byte-indistinguishable from "you hold nothing there." A "skip on partial" guard is **unimplementable** — there is no partial discriminator.

Every deletion in this design rests on a **positive** signal (native previously held a tx and has now removed it; a structurally-identified change output of a wallet-originated send), never on absence-from-an-authority.

## Background — the two real defects

**Over-count source.** `AssetManager.processIncomingAssetTx` inserts every *owned* non-OP_RETURN output of any wallet-known tx as an `is_asset=1, spent=0` row (the 30s sweep passes `blockHeight=0L`). For an **outgoing** asset send, the owned **change-marker** is inserted and counted, while `sendAsset` intentionally does **not** mark the spent input (`AssetManager.kt:998-1005`) — so the pre-send holding *and* the change both count (the double-count). When such a send never confirms:

- If its tx is later **removed** (double-spend, or a user `clearStuckSends`), the row is orphaned but survives (`getAssetBalances = SUM(asset_quantity) WHERE is_asset=1 AND spent=0` still counts it). ← legacy "Chang" orphans.
- If its tx **stays resident** — a *valid* send that never confirms (fee-under-relay, mempool-expired, fluffed-then-dropped Dandelion; SPV has no eviction signal) — the change-marker counts **and the 30s sweep re-inserts it every tick**. ← the dominant, self-renewing phantom.

**Backend in the standing path.** `refreshAssetUtxosFromNetwork` (hits `/api/assets/unspent`) is called from **three** sites, not two:
- `SyncService.kt:478` — the 30s sweep tick.
- `SyncService.kt:1338` — `onAssetDetected`, an SPV/JNI event callback on every asset receive (transient-backend-lag moment).
- `ChainReconciliationService.kt:75` — the user reconcile.

## Architecture

Nine coordinated changes, all app/core-side. **No native (C/JNI) rebuild is required** — every native signal used (`getTransactionOutputsForHash`, `getTransactionDetails`, `getChangeAddress`, `isWalletLoaded`, `getPeerCount`, `getSyncProgress`) already exists in `NativeBridge`.

| # | Component | Purpose | Constraint served |
|---|-----------|---------|-------------------|
| C1 | Provenance column `asset_source` | Distinguish native-detected rows (prunable) from backend-surfaced ones (never auto-pruned) | HARD CONSTRAINT |
| C2 | Source-fix: sweep skips owned change of unconfirmed outgoing sends | Stop *manufacturing* the over-count (dominant phantom) | Over-count |
| C3 | Native-positive-removal prune (NATIVE rows only, gated + debounced) | Sovereign cleanup of removed-tx orphans, replacing the backend refresh | Over-count / sovereignty |
| C4 | Backend fully off the standing path | Kill the flood; keep backend insert-only in reconcile | Sovereignty / HARD CONSTRAINT |
| C5 | Re-tag via partial `UPDATE` (non-clobbering) | Provenance change must not rewrite quantity/spent/height | Data-loss / livelock |
| C6 | Persist asset txs on receipt | Close the restart durability gap the prune could exploit | Data-loss |
| C7 | Wire dead-asset cleanup into the double-spend removal path | Close the one orphan source the prune actually nets | Correctness |
| C8 | Serialize overlapping sweep/prune ticks | Weak-device load hygiene | Hardening |
| C9 | Legacy one-time Chang heal (dry-run first, change-address-scoped) | Heal pre-existing owned orphans | Over-count (legacy) |

---

## C1 — Provenance column `asset_source`

**Entity.** Add to `UtxoEntity`, mirroring the `asset_quantity` pattern exactly (Kotlin default only, **no** `@ColumnInfo(defaultValue)`):

```kotlin
@ColumnInfo(name = "asset_source") val assetSource: String = "BACKEND"
```

Values: `"NATIVE"` (inserted by `processIncomingAssetTx` — native knew the tx at insert time) or `"BACKEND"` (inserted by `refreshAssetUtxosFromNetwork`). Non-asset rows keep the default; it is meaningless for them.

**Default = `BACKEND` is deliberate.** A pre-existing row of unknown provenance must land in the *never-auto-pruned* class, so a real holding native never scanned (gap-limit / restore / pre-install) is never at risk. Promotion `BACKEND → NATIVE` happens only when native positively re-detects the tx (C5), which is a one-way trapdoor *into* the prunable set — and only for rows whose tx native currently holds.

**Migration (MIG-1).** Bump `@Database(version = 6)`; register `MIGRATION_5_6`:

```sql
ALTER TABLE utxos ADD COLUMN asset_source TEXT NOT NULL DEFAULT 'BACKEND'
```

Add it to the `addMigrations(...)` list. `fallbackToDestructiveMigration()` stays as the net **but must not be the migration path** — a missing `MIGRATION_5_6` would silently DROP every table on a real v5→v6 upgrade (wiping exactly the `BACKEND` holdings this design protects), and fresh-install QA never hits it. Guardrails:

- Instrumented upgrade test: open a real seeded v5 DB, run to v6, assert asset rows survive with `asset_source='BACKEND'`.
- CI guard: fail on a `@Database` version bump without a matching registered `Migration`.
- Regenerate + commit `core/schemas/…/6.json`; grep it to confirm **no** `defaultValue` is recorded for `asset_source` (MIG-2 — a recorded `defaultValue` enters Room's identity hash and mismatches the migration's quoting → `IllegalStateException` on launch, which `fallbackToDestructiveMigration` does not rescue).

---

## C2 — Source-fix: sweep skips owned change of unconfirmed outgoing sends

This is the primary over-count fix and it works at the source, so no cleanup can be "re-manufactured."

`sweepKnownTransactionsForAssets` currently enumerates `getAllTransactionHashes()` (hashes only). Change it to enumerate `getTransactionDetails()` rows — `txHash|amount|fee|blockHeight|timestamp|sent|received`, `TX_UNCONFIRMED = INT32_MAX` — the same source `clearStuckSends` parses (`WalletManager.kt:315-340`). For each row, compute `isOutgoingUnconfirmed = (sent > 0) && (blockHeight == TX_UNCONFIRMED)` and pass it to `processIncomingAssetTx`.

In `processIncomingAssetTx`, when `isOutgoingUnconfirmed` is true, **do not insert owned outputs** (the change-marker). Metadata/asset-id detection may still run; only the owned-row insertion is skipped. Rationale: an unconfirmed outgoing send's change is not a settled holding, and the un-decremented input already represents the pre-send balance — inserting the change double-counts. When the send **confirms** (`blockHeight != TX_UNCONFIRMED`) the flag flips false and the next sweep inserts the change as a `NATIVE` row normally.

**Interplay with the input (documented, not fixed here).** While unconfirmed: input counts (correct — the send hasn't settled), change skipped → no double-count. On confirm: change counts *and the input must be marked spent* — that decrement is the `feat/native-asset-spent` branch (Non-goal). Until that lands, a *confirmed* self-send still needs the spent-decrement to avoid re-inflation; this design must be sequenced **before** that branch and the two together produce the correct confirmed-send arithmetic. This design alone fully corrects the *unconfirmed* over-count and prevents new orphans.

---

## C3 — Native-positive-removal prune

New `AssetManager.pruneRemovedNativeAssetRows()`, run in the 30s maintenance cycle **in place of** the removed backend refresh (C4):

```
for each asset row where asset_source = 'NATIVE':
    if NativeBridge.getTransactionOutputsForHash(txid) == null   // native has removed the tx
        deleteAssetUtxo(txid, vout)
```

**Why safe.** A `NATIVE` row's tx was present at insert (`processIncomingAssetTx` returns early on a null tx, `AssetManager.kt:142`). A later null therefore means native *removed* the tx (a positive removal signal), not that it never knew it. `BACKEND` rows are never touched — a real native-missed holding is exactly a `BACKEND` row with a null tx, and must survive.

**Gates (DL-4 — the sticky-Synced hole).** `hasReachedSynced` is seeded from the persisted `has_synced` pref before this session verifies anything, so it is not a this-session signal. The prune runs only when **all** hold:

- `isWalletLoaded()` is true, and
- a session-local `syncedThisSession` boolean (set on an `onSyncComplete` observed *in this process*) is true, and
- `getPeerCount() > 0`, and
- `getSyncProgress() >= 1.0f`.

Plus a **consecutive-absence debounce**: a `NATIVE` row is deleted only after `getTransactionOutputsForHash(txid)` has been null across **N** connected sweeps (N ≥ 2), not on the first miss — so a transient reload/reorg window cannot mass-delete. Even a wrong delete self-heals (the next sweep's `processIncomingAssetTx` re-inserts if the tx returns), but the debounce avoids the flicker.

**Precision note (claim reword).** `getTransactionOutputsForHash` overloads null across ~7 paths (no-wallet, null/malformed hex, tx-absent, JNI-internal OOM). It means "tx-absent" **only** under the gates above with a validated 64-hex txid. The prune must validate the txid is 64-hex before calling, so the malformed-hex overload is provably excluded.

**Coverage (scoped honestly).** C3's net-new coverage is owned rows orphaned by a **double-spend removal** and by the `BRWalletRemoveTransaction` dependent-tx cascade (`BRWallet.c:1582`, which `clearDeadAssetSend`'s parent-txid-only cleanup misses). `clearStuckSends` already cleans its own dead sends (C7 extends this). C3 does **not** cover the resident-valid-stuck send (C2 does) or legacy orphans (C9 does). It is the sovereign *replacement* for the removed 30s backend refresh.

---

## C4 — Backend fully off the standing path

Route **all** `/api/assets/unspent` traffic through the user reconcile only:

- **`SyncService.kt:478`** (30s sweep): remove the `refreshAssetUtxosFromNetwork` call; run `pruneRemovedNativeAssetRows()` (C3) instead.
- **`SyncService.kt:1338`** (`onAssetDetected`): replace the backend call with the sovereign native path for the detected txHash — `getTransactionOutputsForHash` + `processIncomingAssetTx` (insert-only, tags `NATIVE`). No backend hit, and the immediate receive becomes a `NATIVE` row instead of a `BACKEND`-defaulted one that escapes the prune.
- **`ChainReconciliationService.kt:75`** (user reconcile): keep `refreshAssetUtxosFromNetwork`, but it stays **additive-only** — the existing additive upsert + the existing sovereign *not-owned* prune. **No owned-row delete is added anywhere** (the cut from DL-1). This preserves the code's own documented invariant (`AssetManager.kt:581-582, 681-684`: "the backend is never allowed to DELETE").

Net: the standing data path (sweep + event) makes zero backend calls; the backend is touched only when the user runs reconcile, and even then never deletes an owned row.

---

## C5 — Re-tag via partial `UPDATE` (non-clobbering)

`insertAll` is `@Insert(onConflict = REPLACE)` over PK `(txid, vout)`, so re-inserting a row to change its tag rewrites the whole row: `assetQuantity = quantityForOutput()` (returns **0** for percent/range transfers and issuance non-first outputs), `spent = false`, `blockHeight = 0L`. That would (a) drop a real percent/range holding to 0 out of the `SUM`, and (b) reset the `feat/native-asset-spent` branch's `spent=1` every 30s → re-inflation livelock.

Provenance changes must therefore use a targeted update that touches only the tag:

```kotlin
@Query("UPDATE utxos SET asset_source = 'NATIVE' WHERE txid = :txid AND vout = :vout")
suspend fun markAssetSourceNative(txid: String, vout: Int)
```

`processIncomingAssetTx`'s re-detection of an already-present outpoint calls `markAssetSourceNative` for the tag; only a genuinely-new outpoint goes through `insertAll`. When it does update mutable fields, preserve `spent`, preserve non-zero `blockHeight`, and never downgrade a resolved quantity (write `max(existing, computed)`; already-resolved `assetId` is preserved today via `getAssetIdAt`). The same non-clobbering rule applies to `refreshAssetUtxosFromNetwork`'s upsert.

---

## C6 — Persist asset txs on receipt (durability)

Root fix for DL-4's precondition: `onTransactionReceived` (`SyncService.kt:1198`) writes a durable Room asset row via `processIncomingAssetTx` but the native tx only reaches `saved_transactions` at `onSyncComplete`/SyncWorker. An asset received late in a session and killed before persist is absent from the rebuilt native wallet on restart, so `getTransactionOutputsForHash` is null for a *real* NATIVE row. Call `walletTxPersister.persist()` after a successful `processIncomingAssetTx` in the receive path, so received asset txs survive restart and the native-lost-on-restart precondition disappears. (This complements the C3 gates rather than replacing them.)

---

## C7 — Wire dead-asset cleanup into the double-spend removal path

`rebroadcastStrandedSends` (`SyncService.kt:1783`) calls `removeTransaction` with **no** asset-row cleanup, unlike `clearStuckSends`. Call `clearDeadAssetSend` (or the equivalent owned-row delete for the removed txid) at that site, so the double-spend orphan is cleaned at the point of removal — symmetric with `clearStuckSends`, and the exact orphan class C3 otherwise sweeps up on a later tick.

---

## C8 — Serialize overlapping sweep/prune ticks

Each 30s tick fires sweep and prune as unawaited `launch{}` with no in-flight guard; on a long-history device a cycle can exceed 30s and stack. Operations are idempotent (REPLACE/UPDATE, per-outpoint gated delete) so there is no mass-delete amplification — this is redundant JNI/heap load, not corruption. Guard the maintenance cycle behind an `AtomicBoolean.compareAndSet(false, true)` (reset in `finally`); drop overlapping ticks; run prune after sweep within one guarded cycle. Not a correctness ship-blocker.

---

## C9 — Legacy one-time Chang heal (dry-run first)

Pre-existing owned orphans (Chang: txs already gone from native — observed 7 rows vs 3 live asset txs — and defaulting to `BACKEND` after C1's migration) are not reached by C2 (no new insert), C3 (`BACKEND`-tagged), or C7 (not a fresh removal). They need a one-time cleanup, and the HARD CONSTRAINT forbids the obvious "delete owned rows native no longer has" (that also deletes a real native-missed holding).

**Distinguishing signal — change (internal) address.** A phantom is a *change-marker of a wallet-originated send*, which sits at an **internal/change** address. A real missed holding is a *receive*, at an **external/receive** address. Enumerate the wallet's change-address set via `getChangeAddress(index, format)` (→ `BRWalletInternalChangeAddress`) over a bounded index range (e.g. 0..200, generous for historical internal-chain usage; a test wallet uses far fewer), convert each to a scriptPubKey via `addressToScriptPubKey`, and build the change-script set — no native rebuild.

**Heal rule (one-time, on first post-upgrade synced sweep, behind the C3 gates):**

```
delete an owned asset row iff ALL:
  - getTransactionOutputsForHash(txid) == null      // native no longer has the tx
  - row.scriptPubKey ∈ changeScriptSet              // structurally a change output
  - (C3 gates: syncedThisSession && peerCount>0 && progress==1.0 && isWalletLoaded)
```

A real *external* receive can never satisfy the change-script test, so it is never eligible.

**Dry-run gate (mandatory before any legacy delete ships as enabled).** Ship the heal first in **log-only** mode: for each candidate it logs `(txid, vout, assetId, quantity, nativeHasTx=false, isChangeScript=true)` and **deletes nothing**. Install `-r` on the operator's Ultra (wallet preserved), read the dump, confirm the candidate set is exactly the expected Chang phantoms (and that the true Chang holding — the issuance UTXO whose tx native still holds — is **not** a candidate). Only then flip deletion on.

**Residual (honest).** The one edge the change-address signal cannot exclude: asset *change from the wallet's own confirmed send* also lands at a change address, and if native lost that tx (durability gap) the row would be a candidate. This is mitigated by C6 (persist-on-receive) + the C3 gates, and by the fact that C9 is a **one-time, operator-supervised, dry-run-gated** pass rather than a standing automatic delete. The going-forward prune (C3) never carries this risk (NATIVE-tagged, positive-removal only).

---

## Data-loss safety summary

| Deletion site | Signal | Can it delete a real holding? |
|---|---|---|
| C3 prune | `NATIVE` tag + native tx removed + this-session gates + N-sweep debounce | No — native positively removed a tx it held; `BACKEND` rows untouched |
| C4 reconcile | none — additive upsert + existing not-owned prune only | No — no owned-row delete exists |
| C7 double-spend | specific removed txid's owned outputs | No — scoped to the tx being removed |
| C9 legacy | tx gone **and** change-address **and** gates, dry-run-gated | Only the narrow confirmed-own-change-native-lost edge, operator-supervised |

No deletion anywhere rests on backend-absence.

## Testing strategy

- **C1/MIG:** unit — both insert paths tag correctly (`NATIVE`/`BACKEND`); instrumented v5→v6 upgrade preserves rows as `BACKEND`; committed `6.json` has no `defaultValue`; CI version/migration guard.
- **C2:** unit — an outgoing unconfirmed tx (`sent>0`, `height==INT32_MAX`) does not insert its owned change row; the same tx confirmed does insert it; an incoming unconfirmed receive (`sent==0`) still inserts.
- **C3:** unit — a `NATIVE` row whose tx is gone is deleted after N sweeps; a `NATIVE` row whose tx is present is kept; a `BACKEND` row whose tx is gone is kept; prune no-ops when `!syncedThisSession` / `peerCount==0` / `progress<1.0` / `!isWalletLoaded`; a non-64-hex txid is never passed to native.
- **C4:** unit/inspection — no `refreshAssetUtxosFromNetwork` call remains at `478` or `1338`; reconcile path still additive-only (no owned-row delete).
- **C5:** unit — `markAssetSourceNative` changes only the tag; a percent/range `BACKEND` row keeps its quantity through a native re-detect; a `spent=1` row stays `spent=1` through re-tag.
- **C6:** unit — receive path calls the persister after `processIncomingAssetTx`.
- **C7:** unit — `rebroadcastStrandedSends` removal cleans the removed txid's owned asset rows.
- **C9:** unit — candidate iff (tx gone ∧ change-script); log-only mode deletes nothing; a real external-address row with tx gone is never a candidate. Plus the on-device dry-run on the Ultra.

## Rollout

1. Land C1–C8 behind the migration; C9 ships in **log-only** mode.
2. Build debug, install `-r` on the Ultra (wallet preserved), read the C9 dry-run dump + confirm the sweep now makes no backend calls and the over-count no longer regenerates.
3. Confirm the Chang candidate set is exactly the phantoms; enable C9 deletion.
4. Verify Chang collapses to its true count (≈1 UTXO / 10) and stays put across sweeps.
5. Sequence `feat/native-asset-spent` **after** this (C5 makes them compatible; C2 handles the unconfirmed arithmetic they jointly complete).

## Open residuals

- Confirmed self-send input-decrement depends on `feat/native-asset-spent` (Non-goal / sequencing).
- Full native quantity resolution for percent/range (M3) still leans on the on-demand reconcile.
- C9's one-time change-address heuristic carries the narrow confirmed-own-change-native-lost edge (operator-supervised, dry-run-gated).
