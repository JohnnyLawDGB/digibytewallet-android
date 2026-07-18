# Dead Asset-Send Clear — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** A user-triggered clear that removes a *dead* asset send (never-confirmable — dust-rejected or conflicted) and the owned-address phantom `is_asset` rows it left, healing the displayed asset balance (S25 Ultra `CHANG` 30 → 10) **sovereignly** and **without deleting a genuine holding or enabling a double-spend**.

**Architecture:** Extend the existing user-triggered `WalletManager.clearStuckSends()` (which today drops unconfirmed txs from native + `OutgoingTxStore` but never touches Room). Add a **dead-tx predicate** gate + a Room-cleanup helper in `AssetManager`. Authority is native + owned-set only; no backend.

**Tech stack:** Kotlin, Room (`UtxoDao`), JNI (`NativeBridge`), JUnit (host) + androidTest (Room).

**Design source:** `docs/superpowers/specs/2026-07-18-dead-asset-send-clear-design.md` (read it for the full root cause + gap analysis).

## Global Constraints

- **Sovereign only.** Ownership is judged solely against `AssetManager.buildOwnedScriptHexes()` (native `dumpAllAddresses`). The DigiScope backend is NEVER a delete authority.
- **Never delete a genuine holding.** Only delete `is_asset=1` rows that are (a) **owned outputs of a proven-dead txid** and (b) scoped to that exact txid+vout. Never touch another txid's rows, never touch non-owned rows (the sovereign prune owns those), never touch `is_asset=0` (DGB) rows.
- **No input resurrection.** This v1 ships on `develop` and does **not** un-spend inputs (on develop asset input rows are already `spent=0`; `markSpent` is unwired). `UtxoDao.markUnspent` is added but **not called** in `clearStuckSends` here — it is wired only by the later held-branch integration, gated on native `outpointSpentState`. Do not reference `outpointSpentState` in this plan's code (the symbol does not exist on develop).
- **Ordering:** the JNI outpoint readers (`getTransactionOutputsForHash`) return null after `removeTransaction`. Always read outputs **before** removing.
- **Dead predicate:** a recorded, **unconfirmed** tx is *dead* iff `!NativeBridge.isTransactionValid(txid)` OR it has a non-OP_RETURN output with `1 ≤ sats < 5460` (`DUST_FLOOR`, the conservative legacy floor). A confirmed tx (`blockHeight ∈ (0, INT_MAX)`) is always KEPT.
- Existing signatures (verified on develop): `NativeBridge.removeTransaction(txid): Boolean`, `isTransactionValid(txid): Boolean`, `getTransactionOutputsForHash(hex): Array<String>?` → `"vout|sats|scriptHex"`, `getTransactionInputsForHash(hex): Array<String>?` → `"prevTxidHex|prevVout"`; `UtxoDao.deleteAssetUtxo(txid, vout)`, `markSpent(txid, vout)`, `getAllAssetUtxosNow()`; `AssetManager.buildOwnedScriptHexes(): Set<String>`; `DigiAssetDecoder.DA_ASSET_DUST_AMOUNT=700`, `AssetManager.DGB_CHANGE_DUST_THRESHOLD=5460`.

---

### Task 1: `UtxoDao.markUnspent`

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/db/dao/UtxoDao.kt`
- Test: `core/src/androidTest/java/io/digibyte/core/db/UtxoDaoTest.kt`

**Interfaces:**
- Produces: `suspend fun UtxoDao.markUnspent(txid: String, vout: Int)` — sets `spent = 0` for that exact outpoint (mirror of existing `markSpent`).

- [ ] **Step 1: Write the failing test** — in `UtxoDaoTest.kt`, insert an `is_asset=1` UTXO, `markSpent(txid, vout)`, assert it drops out of `getAssetUtxos()`, then `markUnspent(txid, vout)`, assert it returns and `getAssetBalances()` counts it again. Assert `markUnspent` on a *different* vout leaves the row spent.
- [ ] **Step 2: Run it — expect FAIL** (`markUnspent` unresolved). Command: `./gradlew :core:connectedMainnetDebugAndroidTest --tests "*.UtxoDaoTest"` (or the project's Room test task).
- [ ] **Step 3: Implement** — add to `UtxoDao`:
```kotlin
@Query("UPDATE utxos SET spent = 0 WHERE txid = :txid AND vout = :vout")
suspend fun markUnspent(txid: String, vout: Int)
```
- [ ] **Step 4: Run it — expect PASS.**
- [ ] **Step 5: Commit** — `feat(asset): UtxoDao.markUnspent (seam for held-branch input restore)`.

---

### Task 2: Dead-send predicate (pure)

**Files:**
- Create: `core/src/main/java/io/digibyte/core/asset/DeadSendPredicate.kt`
- Test: `core/src/test/java/io/digibyte/core/asset/DeadSendPredicateTest.kt`

**Interfaces:**
- Produces: a pure classifier callable with data the caller already has (validity + parsed outputs), no JNI/Room inside:
```kotlin
object DeadSendPredicate {
    const val DUST_FLOOR = 5460L               // conservative legacy dust floor
    /** A parsed output value; OP_RETURN outputs are value 0 and ignored. */
    data class OutSats(val sats: Long)
    /** Dead iff conflicted OR carries a sub-dust (non-relayable) output. Caller
     *  passes only UNCONFIRMED txs; confirmed txs are filtered upstream. */
    fun isDead(isValid: Boolean, outputs: List<OutSats>): Boolean =
        !isValid || outputs.any { it.sats in 1 until DUST_FLOOR }
}
```

- [ ] **Step 1: Write the failing test** — truth table: (isValid=true, outputs=[6000, 30000]) → false; (isValid=true, outputs=[700, …]) → true; (isValid=false, outputs=[6000]) → true; (isValid=true, outputs=[0]) → false (OP_RETURN only); (isValid=true, outputs=[5460]) → false (at floor, not below); (isValid=true, outputs=[]) → false.
- [ ] **Step 2: Run — expect FAIL.** `./gradlew :core:testMainnetDebugUnitTest --tests "*.DeadSendPredicateTest"`
- [ ] **Step 3: Implement** `DeadSendPredicate.kt` as above.
- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit** — `feat(asset): dead-send predicate (invalid OR below-dust)`.

---

### Task 3: `AssetManager.clearDeadAssetSend` (owned-output phantom deletion)

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/asset/AssetManager.kt`
- Test: `core/src/androidTest/java/io/digibyte/core/asset/AssetManagerClearDeadSendTest.kt` (Room-backed; use the in-memory DB pattern from `UtxoDaoTest`)

**Interfaces:**
- Consumes: `UtxoDao.deleteAssetUtxo(txid, vout)`, `buildOwnedScriptHexes()`.
- Produces:
```kotlin
/** Delete the OWNED is_asset output rows a dead tx fabricated. `outputs` are
 *  the dead tx's outputs as "vout|sats|scriptHex" (from getTransactionOutputsForHash),
 *  read BEFORE removeTransaction. `ownedScriptHexes` from buildOwnedScriptHexes().
 *  Deletes only rows whose script is owned AND txid==this dead txid. Returns count. */
suspend fun clearDeadAssetSend(txid: String, ownedScriptHexes: Set<String>, outputs: List<String>): Int
```
Implementation: parse each `"vout|sats|scriptHex"`; for outputs whose `scriptHex` (lowercased) ∈ `ownedScriptHexes`, call `utxoDao.deleteAssetUtxo(txid, voutInt)`; count deletions. Skip malformed lines and empty scripts. Never delete for a different txid.

- [ ] **Step 1: Write the failing test** — seed Room with: an owned asset-change row `(deadTxid, 2)` qty 20; a NON-owned row `(deadTxid, 0)` (recipient marker); an owned row for a DIFFERENT `(otherTxid, 1)` qty 10; a real holding `(origTxid, 0)` qty 10. Call `clearDeadAssetSend(deadTxid, ownedSet, outputsOf(deadTxid))`. Assert: `(deadTxid,2)` deleted; `(deadTxid,0)` NOT deleted (non-owned); `(otherTxid,1)` untouched; `(origTxid,0)` untouched; returns 1; `getAssetBalances()` for that asset drops by exactly 20.
- [ ] **Step 2: Run — expect FAIL** (method unresolved). `./gradlew :core:connectedMainnetDebugAndroidTest --tests "*.AssetManagerClearDeadSendTest"`
- [ ] **Step 3: Implement** the helper on `AssetManager`.
- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit** — `feat(asset): clearDeadAssetSend deletes owned phantom output rows`.

---

### Task 4: Wire into `WalletManager.clearStuckSends` + UI copy

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/WalletManager.kt` (`clearStuckSends`)
- Modify: the caller/UI that shows the result (grep `clearStuckSends(` — likely `app/.../ui/settings/*` and/or a ViewModel) to surface `assetRowsCleared`.

**Interfaces:**
- Consumes: `DeadSendPredicate`, `AssetManager.clearDeadAssetSend`, `NativeBridge.{isTransactionValid,getTransactionOutputsForHash,removeTransaction}`.
- Produces: `clearStuckSends()` returns `data class StuckSendResult(val dropped: Int, val kept: Int, val assetRowsCleared: Int)` (replace the current `Pair<Int,Int>`; update all callers).

- [ ] **Step 1: Change `clearStuckSends`** to, for each recorded unconfirmed txid:
  1. read `outputs = NativeBridge.getTransactionOutputsForHash(txid)?.toList() ?: emptyList()`;
  2. compute `isValid = runCatching { NativeBridge.isTransactionValid(txid) }.getOrDefault(true)`;
  3. `dead = DeadSendPredicate.isDead(isValid, outputs.map { OutSats(it.split("|").getOrNull(1)?.toLongOrNull() ?: 0L) })`;
  4. if NOT dead → **keep** (do not remove — closes the slow-valid gap), `kept++`, continue;
  5. if dead → `assetRowsCleared += assetManager.clearDeadAssetSend(txid, ownedSet, outputs)` (build `ownedSet` once before the loop), then `NativeBridge.removeTransaction(txid)` + `store.remove(txid)` + `dropped++`.
  (Confirmed txs are kept exactly as today.) `WalletTxPersister(context).persist()` when `dropped>0`. This needs an `AssetManager` handle in `WalletManager` — inject/pass it (follow the existing DI pattern; if `WalletManager` can't hold `AssetManager`, expose a small callback or move the loop into a coordinator the caller already wires).
- [ ] **Step 2: Update the return type** to `StuckSendResult` and fix all callers (grep `clearStuckSends`).
- [ ] **Step 3: Update the UI copy** to `"Cleared N stuck send(s); corrected M asset row(s)"` using `assetRowsCleared`.
- [ ] **Step 4: Build** — `./gradlew :app:assembleMainnetDebug` must be `BUILD SUCCESSFUL`. Run `./gradlew :core:testMainnetDebugUnitTest` (predicate) green.
- [ ] **Step 5: Commit** — `feat(asset): clearStuckSends gates on dead predicate + cleans owned phantom rows`.

---

## Final review + ship gate

- Whole-branch adversarial review (most-capable model): re-confirm no genuine-holding deletion, no non-owned deletion, slow-valid sends untouched, sovereignty (no backend authority), ordering (read-before-remove), and that no `outpointSpentState`/input-un-spend slipped in.
- **On-device Ultra gate (ship blocker):** run the extended "Clear stuck sends" → `CHANG` returns to 10, UTXOs Held corrects, `26e7eda…` leaves activity; regression: unrelated holdings + confirmed sends untouched.
- Ship: bump version (per the pending 4.0.0 decision) + tag off develop.
