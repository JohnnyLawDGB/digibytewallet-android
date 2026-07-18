# Sovereign Native Asset Balance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Derive the DigiAsset balance from the wallet's own native/on-chain truth (like DGB/DD), killing the phantom over-count and taking `/api/assets/unspent` off the standing data path — without ever deleting a row on a signal that can't prove it is a phantom.

**Architecture:** Tag every asset UTXO row with its provenance (`NATIVE` vs `BACKEND`). Stop *manufacturing* the over-count at the source (the 30s sweep no longer counts the change-marker of an unconfirmed outgoing send). Add a sovereign prune that deletes only `NATIVE` rows whose tx native has *positively removed*, gated on this-session sync + a debounce. Route the backend to the user reconcile only (insert-only, never deletes an owned row). Heal pre-existing legacy orphans one-time, change-address-scoped, behind a log-only dry-run.

**Tech Stack:** Kotlin, Room (SQLCipher), Jetpack, JNI (`NativeBridge`), JUnit4 + MockK. All changes are app/core — **no native (C/JNI) rebuild**.

**Spec:** `docs/superpowers/specs/2026-07-18-sovereign-native-asset-balance-design.md`
**Branch:** `feat/sovereign-native-asset-balance` (spec committed `81c56a84`, off `develop` @ v3.10.44).

## Global Constraints

- **HARD CONSTRAINT:** never delete a row on a signal that cannot distinguish "phantom" from "real holding the authority never scanned." Every delete rests on a positive signal (native removed a tx it held; a structurally-identified change output).
- **No backend-authoritative delete of owned rows, anywhere.** The reconcile stays additive-upsert + the existing not-owned prune only.
- Provenance values are exactly the strings `"NATIVE"` and `"BACKEND"` (constants in `object AssetSource`). SQL default is `'BACKEND'`.
- Migration: bump `@Database(version = 6)`, register `MIGRATION_5_6`, regenerate + commit `6.json`. **No `@ColumnInfo(defaultValue=...)`** on `asset_source` (mirror `asset_quantity`).
- Prune deletes only `asset_source = 'NATIVE'` rows, only when `getTransactionOutputsForHash(txid) == null`, only under gate `syncedThisSession && peerCount>0 && progress>=1.0f && isWalletLoaded()`, only after **≥2** consecutive absent sweeps, only for a validated 64-hex txid.
- Re-tag never rewrites `spent`, never lowers `asset_quantity`, never zeroes a non-zero `blockHeight`.
- `TX_UNCONFIRMED == Int.MAX_VALUE` (2147483647). `getTransactionDetails()` row format: `txHash|amount|fee|blockHeight|timestamp|sent|received`.
- Tests: `./gradlew :core:testMainnetDebugUnitTest` (core) / `:app:testMainnetDebugUnitTest` (app). Mirror `core/src/test/java/io/digibyte/core/asset/ClearStuckSendsGatingTest.kt` for `mockkObject(NativeBridge)` style.
- Commit after each green task. Do **not** run `git add -A`; add only the files named in the task.

---

## File Structure

**Created:**
- `core/src/main/java/io/digibyte/core/db/Migration_5_6.kt` — the `ALTER TABLE` migration.
- `core/src/main/java/io/digibyte/core/asset/AssetSource.kt` — `object AssetSource { NATIVE, BACKEND }`.
- `core/src/main/java/io/digibyte/core/asset/AssetMaintenanceGate.kt` — pure gate predicate.
- Tests: `core/src/test/java/io/digibyte/core/db/Migration_5_6Test.kt`, `.../asset/AssetProvenanceTaggingTest.kt`, `.../asset/AssetSourceFixTest.kt`, `.../asset/PruneRemovedNativeAssetRowsTest.kt`, `.../asset/AssetMaintenanceGateTest.kt`, `.../asset/LegacyChangeAddressHealTest.kt`.

**Modified:**
- `core/src/main/java/io/digibyte/core/db/entity/UtxoEntity.kt` — add `assetSource`.
- `core/src/main/java/io/digibyte/core/db/WalletDatabase.kt` — version 6, register migration.
- `core/src/main/java/io/digibyte/core/db/dao/UtxoDao.kt` — new queries.
- `core/src/main/java/io/digibyte/core/asset/AssetManager.kt` — tagging, source-fix, prune, heal, persist-on-detect.
- `app/src/main/java/io/digibyte/service/SyncService.kt` — maintenance cycle rewiring, onAssetDetected, onTransactionReceived, rebroadcastStrandedSends, syncedThisSession.
- `core/schemas/io.digibyte.core.db.WalletDatabase/6.json` — regenerated.

---

## Task 1: Provenance column + migration + DAO surface (C1)

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/db/entity/UtxoEntity.kt`
- Create: `core/src/main/java/io/digibyte/core/asset/AssetSource.kt`
- Create: `core/src/main/java/io/digibyte/core/db/Migration_5_6.kt`
- Modify: `core/src/main/java/io/digibyte/core/db/WalletDatabase.kt:23` (version) and `:55` (addMigrations)
- Modify: `core/src/main/java/io/digibyte/core/db/dao/UtxoDao.kt`
- Test: `core/src/test/java/io/digibyte/core/db/Migration_5_6Test.kt`
- Regenerate: `core/schemas/io.digibyte.core.db.WalletDatabase/6.json`

**Interfaces:**
- Produces: `object AssetSource { const val NATIVE = "NATIVE"; const val BACKEND = "BACKEND" }`.
- Produces: `UtxoEntity.assetSource: String` (`@ColumnInfo(name = "asset_source")`, default `"BACKEND"`).
- Produces DAO methods: `getAssetUtxosBySourceNow(source: String): List<UtxoEntity>`, `getAssetUtxoAt(txid: String, vout: Int): UtxoEntity?`, `markAssetSource(txid: String, vout: Int, source: String)`, `updateAssetQuantity(txid: String, vout: Int, quantity: Long)`.
- Produces: `MIGRATION_5_6: Migration`.

- [ ] **Step 1: Add the provenance constants.** Create `AssetSource.kt`:

```kotlin
package io.digibyte.core.asset

/** Provenance of an asset UTXO row. NATIVE rows were detected by the sovereign
 *  sweep (native knew the tx at insert) and are prunable on native tx-removal;
 *  BACKEND rows were surfaced by the on-demand reconcile and are never auto-pruned. */
object AssetSource {
    const val NATIVE = "NATIVE"
    const val BACKEND = "BACKEND"
}
```

- [ ] **Step 2: Add the column to the entity.** In `UtxoEntity.kt`, add the field after `spent` (mirror `asset_quantity` — Kotlin default only, **no** `defaultValue`):

```kotlin
    val spent: Boolean = false,
    @ColumnInfo(name = "asset_source") val assetSource: String = "BACKEND"
```

`equals`/`hashCode` stay keyed on `txid`/`vout` — leave them unchanged.

- [ ] **Step 3: Write the migration test (failing).** Create `Migration_5_6Test.kt`:

```kotlin
package io.digibyte.core.db

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class Migration_5_6Test {
    @Test fun versions_are_5_to_6() {
        assertEquals(5, MIGRATION_5_6.startVersion)
        assertEquals(6, MIGRATION_5_6.endVersion)
    }

    @Test fun adds_asset_source_column_with_backend_default() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        MIGRATION_5_6.migrate(db)
        verify {
            db.execSQL("ALTER TABLE utxos ADD COLUMN asset_source TEXT NOT NULL DEFAULT 'BACKEND'")
        }
    }
}
```

- [ ] **Step 4: Run it — verify it fails.** Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.db.Migration_5_6Test"`
Expected: FAIL — `MIGRATION_5_6` unresolved.

- [ ] **Step 5: Create the migration.** `Migration_5_6.kt`:

```kotlin
package io.digibyte.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `asset_source` to `utxos` — provenance of each asset UTXO row
 * (NATIVE = sovereign sweep detection; BACKEND = on-demand reconcile).
 * Existing rows default to 'BACKEND' so they are never auto-pruned; the
 * first post-upgrade sweep re-tags genuinely native-held rows to 'NATIVE'.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE utxos ADD COLUMN asset_source TEXT NOT NULL DEFAULT 'BACKEND'")
    }
}
```

- [ ] **Step 6: Bump DB version + register the migration.** In `WalletDatabase.kt`: change `version = 5` (line 23) to `version = 6`; change the `addMigrations(...)` call (line 55) to append `MIGRATION_5_6`:

```kotlin
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
```

- [ ] **Step 7: Add the DAO queries.** In `UtxoDao.kt`, add (near the other asset queries):

```kotlin
    /** All asset rows of a given provenance (unspent + spent). Used by the
     *  native-positive-removal prune, which only touches NATIVE rows. */
    @Query("SELECT * FROM utxos WHERE is_asset = 1 AND asset_source = :source")
    suspend fun getAssetUtxosBySourceNow(source: String): List<UtxoEntity>

    /** The asset row at an outpoint, or null. Used by the non-clobbering
     *  re-tag to preserve spent/quantity/blockHeight. */
    @Query("SELECT * FROM utxos WHERE is_asset = 1 AND txid = :txid AND vout = :vout LIMIT 1")
    suspend fun getAssetUtxoAt(txid: String, vout: Int): UtxoEntity?

    /** Re-tag provenance only — never rewrites quantity/spent/blockHeight. */
    @Query("UPDATE utxos SET asset_source = :source WHERE is_asset = 1 AND txid = :txid AND vout = :vout")
    suspend fun markAssetSource(txid: String, vout: Int, source: String)

    /** Raise a resolved quantity without touching other columns. Callers must
     *  only ever pass a value >= the current one (never downgrade). */
    @Query("UPDATE utxos SET asset_quantity = :quantity WHERE is_asset = 1 AND txid = :txid AND vout = :vout")
    suspend fun updateAssetQuantity(txid: String, vout: Int, quantity: Long)
```

- [ ] **Step 8: Run the migration test — verify it passes.** Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.db.Migration_5_6Test"`
Expected: PASS (2 tests).

- [ ] **Step 9: Regenerate + verify the schema JSON.** Build core so Room emits `6.json`, then confirm no `defaultValue` was recorded for `asset_source`:

Run: `./gradlew :core:compileMainnetDebugKotlin`
Run: `python3 -c "import json;f=json.load(open('core/schemas/io.digibyte.core.db.WalletDatabase/6.json'));fs=[c for e in f['database']['entities'] if e['tableName']=='utxos' for c in e['fields'] if c['columnName']=='asset_source'];print(fs)"`
Expected: one field printed with `'notNull': True` and **no** `'defaultValue'` key. If `defaultValue` is present, remove any `@ColumnInfo(defaultValue=...)` — there must be none.

- [ ] **Step 10: Commit.**

```bash
git add core/src/main/java/io/digibyte/core/db/entity/UtxoEntity.kt \
        core/src/main/java/io/digibyte/core/asset/AssetSource.kt \
        core/src/main/java/io/digibyte/core/db/Migration_5_6.kt \
        core/src/main/java/io/digibyte/core/db/WalletDatabase.kt \
        core/src/main/java/io/digibyte/core/db/dao/UtxoDao.kt \
        core/src/test/java/io/digibyte/core/db/Migration_5_6Test.kt \
        core/schemas/io.digibyte.core.db.WalletDatabase/6.json
git commit -m "feat(assets): add asset_source provenance column + MIGRATION_5_6"
```

---

## Task 2: Provenance tagging + non-clobbering re-tag (C5)

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/asset/AssetManager.kt` (`processIncomingAssetTx` insert block ~246-277; `refreshAssetUtxosFromNetwork` upsert ~614-685)
- Test: `core/src/test/java/io/digibyte/core/asset/AssetProvenanceTaggingTest.kt`

**Interfaces:**
- Consumes (Task 1): `AssetSource.NATIVE/BACKEND`, `UtxoDao.getAssetUtxoAt`, `markAssetSource`, `updateAssetQuantity`.
- Produces: `processIncomingAssetTx` tags new rows `NATIVE`; re-detection of an existing outpoint re-tags via `markAssetSource` **without** rewriting quantity/spent/blockHeight. `refreshAssetUtxosFromNetwork` inserts rows tagged `BACKEND`.

- [ ] **Step 1: Write the failing test.** Create `AssetProvenanceTaggingTest.kt`. It drives `processIncomingAssetTx`'s per-output persistence decision by mocking `UtxoDao`. Assert: a new outpoint is inserted with `assetSource == "NATIVE"`; an existing outpoint (returned by `getAssetUtxoAt`) triggers `markAssetSource(..., "NATIVE")` and is **not** re-inserted, and `updateAssetQuantity` is never called with a value below the existing quantity.

```kotlin
package io.digibyte.core.asset

import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.db.dao.*
import io.digibyte.core.db.entity.UtxoEntity
import io.digibyte.core.ipfs.AssetMetadataService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class AssetProvenanceTaggingTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val metaDao = mockk<AssetMetadataDao>(relaxed = true)
    private val metaSvc = mockk<AssetMetadataService>(relaxed = true)
    private lateinit var mgr: AssetManager

    @Before fun setup() {
        mockkObject(NativeBridge)
        mgr = AssetManager(utxoDao, txDao, metaDao, metaSvc)
        // Minimal issuance tx: one OP_RETURN "DA" marker output + one owned P2PKH output.
        // (Use a decoder-valid OP_RETURN captured from DigiAssetDecoderTest fixtures.)
        every { NativeBridge.getTransactionOutputsForHash(any()) } returns arrayOf(
            "0|0|6a...DAMARKER...",       // OP_RETURN carrying a valid issuance header (totalQuantity=10)
            "1|6000|76a914...OWNED...88ac" // owned marker output
        )
        every { NativeBridge.getTransactionInputsForHash(any()) } returns arrayOf("aa..bb|0")
        every { NativeBridge.deriveIssuanceAssetId(any(), any(), any(), any(), any()) } returns "La1234"
        coEvery { utxoDao.getAssetIdAt(any(), any()) } returns null
    }
    @After fun tearDown() = unmockkObject(NativeBridge)

    @Test fun new_owned_output_is_inserted_as_NATIVE() = runTest {
        coEvery { utxoDao.getAssetUtxoAt(any(), 1) } returns null
        mgr.processIncomingAssetTx("a".repeat(64), blockHeight = 0L, ownedScriptHexes = setOf("76a914...owned...88ac"))
        coVerify {
            utxoDao.insertAll(withArg { list ->
                assert(list.any { it.vout == 1 && it.assetSource == AssetSource.NATIVE })
            })
        }
    }

    @Test fun existing_output_is_retagged_not_reinserted() = runTest {
        coEvery { utxoDao.getAssetUtxoAt(any(), 1) } returns UtxoEntity(
            txid = "a".repeat(64), vout = 1, scriptPubKey = ByteArray(0), satoshis = 6000,
            blockHeight = 800000, isAsset = true, assetId = "La1234", assetQuantity = 10,
            spent = false, assetSource = AssetSource.BACKEND
        )
        mgr.processIncomingAssetTx("a".repeat(64), blockHeight = 0L, ownedScriptHexes = setOf("76a914...owned...88ac"))
        coVerify { utxoDao.markAssetSource("a".repeat(64), 1, AssetSource.NATIVE) }
        coVerify(exactly = 0) { utxoDao.insertAll(any()) }
        coVerify(exactly = 0) { utxoDao.updateAssetQuantity("a".repeat(64), 1, less(10L)) }
    }
}
```

> Note to implementer: replace the `...` script/OP_RETURN placeholders with real bytes from `DigiAssetDecoderTest` fixtures so the decoder accepts the header. The owned-script hex passed in must equal output 1's script hex.

- [ ] **Step 2: Run it — verify it fails.** Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.AssetProvenanceTaggingTest"`
Expected: FAIL — new rows carry `BACKEND` (default) and existing rows are REPLACE-reinserted.

- [ ] **Step 3: Rewrite the per-output persistence block.** In `processIncomingAssetTx`, replace the insert block (currently ~252-277, the `val existing = utxoDao.getAssetIdAt(...)` through the `utxoDao.insertAll(listOf(UtxoEntity(...)))`) with a non-clobbering upsert:

```kotlin
            val computedQty = quantityForOutput(out.vout)
            val existingRow = utxoDao.getAssetUtxoAt(txHashHex, out.vout)
            if (existingRow != null) {
                // Re-detection of a row we already hold: re-tag provenance ONLY.
                // Never rewrite spent/blockHeight (REPLACE would reset them and
                // fight the native-spent-decrement branch), and never lower a
                // resolved quantity (native underestimates percent/range = 0).
                utxoDao.markAssetSource(txHashHex, out.vout, io.digibyte.core.asset.AssetSource.NATIVE)
                if (computedQty > existingRow.assetQuantity) {
                    utxoDao.updateAssetQuantity(txHashHex, out.vout, computedQty)
                }
                if (existingRow.assetId == null || existingRow.assetId.startsWith("unresolved:")) {
                    anyStillUnresolved = true
                }
            } else {
                // Genuinely new outpoint: insert as NATIVE.
                anyStillUnresolved = anyStillUnresolved || placeholderAssetId.startsWith("unresolved:")
                utxoDao.insertAll(
                    listOf(
                        UtxoEntity(
                            txid = txHashHex,
                            vout = out.vout,
                            scriptPubKey = out.script,
                            satoshis = out.sats,
                            blockHeight = blockHeight,
                            isAsset = true,
                            assetId = placeholderAssetId,
                            assetQuantity = computedQty,
                            spent = false,
                            assetSource = io.digibyte.core.asset.AssetSource.NATIVE,
                        )
                    )
                )
            }
```

(The surrounding `for (out in outputs)` loop, the OP_RETURN skip, and the ownership gate `if (owned.isNotEmpty() && out.script.toHex() !in owned) continue` are unchanged.)

- [ ] **Step 4: Tag the backend upsert BACKEND.** In `refreshAssetUtxosFromNetwork`, the `fresh += UtxoEntity(...)` construction (~623-632) — add `assetSource = io.digibyte.core.asset.AssetSource.BACKEND` to the `UtxoEntity(...)`. (The `spent` field is not set there and defaults false — leave as is; this is the reconcile/backend path.)

- [ ] **Step 5: Run the test — verify it passes.** Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.AssetProvenanceTaggingTest"`
Expected: PASS.

- [ ] **Step 6: Full core suite (no regressions).** Run: `./gradlew :core:testMainnetDebugUnitTest`
Expected: BUILD SUCCESSFUL (existing asset tests still green — the id-resolution / M3 path is preserved).

- [ ] **Step 7: Commit.**

```bash
git add core/src/main/java/io/digibyte/core/asset/AssetManager.kt \
        core/src/test/java/io/digibyte/core/asset/AssetProvenanceTaggingTest.kt
git commit -m "feat(assets): tag rows NATIVE/BACKEND; non-clobbering re-tag (no quantity/spent reset)"
```

---

## Task 3: Source-fix — skip owned change of unconfirmed outgoing sends (C2)

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/asset/AssetManager.kt` (`processIncomingAssetTx` signature + owned-output gate; `sweepKnownTransactionsForAssets` ~540-560)
- Test: `core/src/test/java/io/digibyte/core/asset/AssetSourceFixTest.kt`

**Interfaces:**
- Consumes: `NativeBridge.getTransactionDetails()` (rows `txHash|amount|fee|blockHeight|timestamp|sent|received`, `TX_UNCONFIRMED = Int.MAX_VALUE`).
- Produces: `processIncomingAssetTx(txHashHex, blockHeight, ownedScriptHexes = null, isOutgoingUnconfirmed: Boolean = false)` — when `isOutgoingUnconfirmed` is true, owned outputs are **not** persisted (metadata/id detection may still run). The sweep computes the flag per tx.

- [ ] **Step 1: Write the failing test.** Create `AssetSourceFixTest.kt`. Assert: with `isOutgoingUnconfirmed = true`, no owned row is inserted or re-tagged; with `false` (the same tx), the owned row is inserted; an incoming (`isOutgoingUnconfirmed = false`) unconfirmed receive still inserts.

```kotlin
package io.digibyte.core.asset

import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.db.dao.*
import io.digibyte.core.ipfs.AssetMetadataService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class AssetSourceFixTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager
    private val owned = setOf("76a914...owned...88ac")

    @Before fun setup() {
        mockkObject(NativeBridge)
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        every { NativeBridge.getTransactionOutputsForHash(any()) } returns arrayOf(
            "0|0|6a...DAMARKER...",
            "1|6000|76a914...owned...88ac"
        )
        every { NativeBridge.getTransactionInputsForHash(any()) } returns arrayOf("aa..bb|0")
        every { NativeBridge.deriveIssuanceAssetId(any(), any(), any(), any(), any()) } returns "La1"
        coEvery { utxoDao.getAssetIdAt(any(), any()) } returns null
        coEvery { utxoDao.getAssetUtxoAt(any(), any()) } returns null
    }
    @After fun tearDown() = unmockkObject(NativeBridge)

    @Test fun outgoing_unconfirmed_does_not_persist_owned_change() = runTest {
        mgr.processIncomingAssetTx("b".repeat(64), 0L, owned, isOutgoingUnconfirmed = true)
        coVerify(exactly = 0) { utxoDao.insertAll(any()) }
        coVerify(exactly = 0) { utxoDao.markAssetSource(any(), any(), any()) }
    }

    @Test fun confirmed_or_incoming_persists_owned_output() = runTest {
        mgr.processIncomingAssetTx("b".repeat(64), 0L, owned, isOutgoingUnconfirmed = false)
        coVerify { utxoDao.insertAll(any()) }
    }
}
```

- [ ] **Step 2: Run it — verify it fails.** Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.AssetSourceFixTest"`
Expected: FAIL — the outgoing-unconfirmed case still inserts (param doesn't exist).

- [ ] **Step 3: Add the parameter + short-circuit the owned-output persistence.** Change the `processIncomingAssetTx` signature:

```kotlin
    suspend fun processIncomingAssetTx(
        txHashHex: String,
        blockHeight: Long,
        ownedScriptHexes: Set<String>? = null,
        isOutgoingUnconfirmed: Boolean = false,
    ): IncomingAssetInfo? {
```

Inside the `for (out in outputs)` loop, immediately after the OP_RETURN skip and the ownership gate, before the persistence block from Task 2, add:

```kotlin
            // Source-fix: an unconfirmed OUTGOING send's owned change-marker is
            // not a settled holding — the un-decremented input already reflects
            // the pre-send balance, so counting the change double-counts and
            // (if the send strands) becomes a permanent phantom the sweep would
            // re-insert every tick. Detect + skip persistence; let it settle on
            // confirmation (when the flag flips false).
            if (isOutgoingUnconfirmed) continue
```

- [ ] **Step 4: Feed the flag from the sweep.** Rewrite `sweepKnownTransactionsForAssets` to enumerate `getTransactionDetails()` (which carries `sent` + `blockHeight`) instead of `getAllTransactionHashes()`:

```kotlin
    suspend fun sweepKnownTransactionsForAssets(): Int {
        // Row format (jni_wallet getTransactionDetails):
        //   txHash|amount|fee|blockHeight|timestamp|sent|received   (TX_UNCONFIRMED = Int.MAX_VALUE)
        val rows = runCatching { NativeBridge.getTransactionDetails().trim().lines() }
            .getOrNull()?.filter { it.isNotBlank() } ?: return 0
        val owned = buildOwnedScriptHexes()
        var detected = 0
        for (line in rows) {
            val p = line.split("|")
            if (p.size < 6) continue
            val txHash = p[0]
            if (txHash.isBlank()) continue
            val blockHeight = p[3].toLongOrNull() ?: 0L
            val sent = p[5].toLongOrNull() ?: 0L
            val isOutgoingUnconfirmed = sent > 0L && blockHeight >= Int.MAX_VALUE.toLong()
            val info = runCatching {
                processIncomingAssetTx(txHash, blockHeight = 0L, ownedScriptHexes = owned,
                    isOutgoingUnconfirmed = isOutgoingUnconfirmed)
            }.onFailure {
                android.util.Log.d("AssetManager", "sweep: processIncoming failed for $txHash", it)
            }.getOrNull()
            if (info != null) detected++
        }
        android.util.Log.i("AssetManager",
            "sweepKnownTransactions: ${rows.size} txs scanned, $detected asset txs found")
        return detected
    }
```

(Note: `blockHeight = 0L` is still passed to `processIncomingAssetTx` for the inserted row — unchanged from today; the confirmed/unconfirmed decision is carried by `isOutgoingUnconfirmed`, not by the persisted height.)

- [ ] **Step 5: Run the test — verify it passes.** Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.AssetSourceFixTest"`
Expected: PASS.

- [ ] **Step 6: Full core suite.** Run: `./gradlew :core:testMainnetDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit.**

```bash
git add core/src/main/java/io/digibyte/core/asset/AssetManager.kt \
        core/src/test/java/io/digibyte/core/asset/AssetSourceFixTest.kt
git commit -m "feat(assets): sweep skips owned change of unconfirmed outgoing sends (source-fix)"
```

---

## Task 4: Native-positive-removal prune + gate (C3)

**Files:**
- Create: `core/src/main/java/io/digibyte/core/asset/AssetMaintenanceGate.kt`
- Modify: `core/src/main/java/io/digibyte/core/asset/AssetManager.kt` (add `pruneRemovedNativeAssetRows`)
- Test: `core/src/test/java/io/digibyte/core/asset/AssetMaintenanceGateTest.kt`, `.../asset/PruneRemovedNativeAssetRowsTest.kt`

**Interfaces:**
- Consumes (Task 1): `AssetSource.NATIVE`, `UtxoDao.getAssetUtxosBySourceNow`, `UtxoDao.deleteAssetUtxo(txid, vout): Int`.
- Consumes: `NativeBridge.getTransactionOutputsForHash(txid): Array<String>?` (null ⇒ tx absent, under the gate).
- Produces: `fun assetPruneGateOpen(syncedThisSession: Boolean, peerCount: Int, progress: Float, walletLoaded: Boolean): Boolean` (pure).
- Produces: `suspend fun AssetManager.pruneRemovedNativeAssetRows(): Int` (debounce state internal; `ABSENCE_DEBOUNCE_THRESHOLD = 2`). Caller gates via `assetPruneGateOpen`.

- [ ] **Step 1: Write the gate test (failing).** Create `AssetMaintenanceGateTest.kt`:

```kotlin
package io.digibyte.core.asset

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetMaintenanceGateTest {
    @Test fun open_only_when_all_conditions_hold() {
        assertTrue(assetPruneGateOpen(true, 1, 1.0f, true))
    }
    @Test fun closed_if_any_condition_fails() {
        assertFalse(assetPruneGateOpen(false, 1, 1.0f, true))
        assertFalse(assetPruneGateOpen(true, 0, 1.0f, true))
        assertFalse(assetPruneGateOpen(true, 1, 0.99f, true))
        assertFalse(assetPruneGateOpen(true, 1, 1.0f, false))
    }
}
```

- [ ] **Step 2: Run it — verify it fails.** Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.AssetMaintenanceGateTest"`
Expected: FAIL — `assetPruneGateOpen` unresolved.

- [ ] **Step 3: Create the pure gate.** `AssetMaintenanceGate.kt`:

```kotlin
package io.digibyte.core.asset

/**
 * True only when it is safe to run the native-positive-removal prune:
 *  - syncedThisSession: an onSyncComplete was observed IN THIS PROCESS (NOT the
 *    persisted has_synced flag, which is true before this session verifies the
 *    tx set — a sticky flag would arm the prune against an unrescanned wallet);
 *  - a peer is connected and sync progress is at tip, so native's tx set is
 *    current rather than mid-rebuild.
 */
fun assetPruneGateOpen(
    syncedThisSession: Boolean,
    peerCount: Int,
    progress: Float,
    walletLoaded: Boolean,
): Boolean = syncedThisSession && peerCount > 0 && progress >= 1.0f && walletLoaded
```

- [ ] **Step 4: Run the gate test — passes.** Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.AssetMaintenanceGateTest"`
Expected: PASS.

- [ ] **Step 5: Write the prune test (failing).** Create `PruneRemovedNativeAssetRowsTest.kt`:

```kotlin
package io.digibyte.core.asset

import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PruneRemovedNativeAssetRowsTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager
    private fun row(txid: String, source: String) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = ByteArray(0), satoshis = 6000,
        blockHeight = 0, isAsset = true, assetId = "La1", assetQuantity = 3,
        spent = false, assetSource = source
    )

    @Before fun setup() {
        mockkObject(NativeBridge)
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        coEvery { utxoDao.deleteAssetUtxo(any(), any()) } returns 1
    }
    @After fun tearDown() = unmockkObject(NativeBridge)

    @Test fun native_row_gone_deleted_only_after_debounce() = runTest {
        val txid = "a".repeat(64)
        coEvery { utxoDao.getAssetUtxosBySourceNow(AssetSource.NATIVE) } returns listOf(row(txid, AssetSource.NATIVE))
        every { NativeBridge.getTransactionOutputsForHash(txid) } returns null   // native dropped it
        assertEquals(0, mgr.pruneRemovedNativeAssetRows())                        // sweep 1: below threshold
        assertEquals(1, mgr.pruneRemovedNativeAssetRows())                        // sweep 2: delete
        coVerify(exactly = 1) { utxoDao.deleteAssetUtxo(txid, 0) }
    }

    @Test fun native_row_present_never_deleted_and_resets_debounce() = runTest {
        val txid = "c".repeat(64)
        coEvery { utxoDao.getAssetUtxosBySourceNow(AssetSource.NATIVE) } returns listOf(row(txid, AssetSource.NATIVE))
        every { NativeBridge.getTransactionOutputsForHash(txid) } returns null andThen null andThen arrayOf("0|6000|76a914..88ac")
        mgr.pruneRemovedNativeAssetRows()   // absent 1
        mgr.pruneRemovedNativeAssetRows()   // absent 2 -> would delete; but the third call proves reset
        // Reset semantics: re-mock so the row is present again before threshold reached
    }

    @Test fun malformed_txid_never_queried_or_deleted() = runTest {
        coEvery { utxoDao.getAssetUtxosBySourceNow(AssetSource.NATIVE) } returns listOf(row("short", AssetSource.NATIVE))
        assertEquals(0, mgr.pruneRemovedNativeAssetRows())
        verify(exactly = 0) { NativeBridge.getTransactionOutputsForHash("short") }
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }
}
```

> Note: the middle test is a scaffold for the reset property — the implementer should make it concrete (present tx before the 2nd absent hit → `deleteAssetUtxo` never called). Keep the first and third tests as the load-bearing assertions.

- [ ] **Step 6: Run it — verify it fails.** Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.PruneRemovedNativeAssetRowsTest"`
Expected: FAIL — `pruneRemovedNativeAssetRows` unresolved.

- [ ] **Step 7: Implement the prune.** In `AssetManager`, add a debounce field near the other private state and the method:

```kotlin
    /** Per-txid count of consecutive prune passes native has lacked the tx.
     *  A NATIVE asset row is deleted only after the count reaches the
     *  threshold, so a transient reload/reorg window can't mass-delete. */
    private val nativeAbsenceCounts = mutableMapOf<String, Int>()

    /**
     * Sovereign replacement for the removed 30s backend refresh. Deletes a
     * NATIVE-tagged asset row once native has POSITIVELY removed its tx
     * (getTransactionOutputsForHash == null) across [ABSENCE_DEBOUNCE_THRESHOLD]
     * consecutive passes. BACKEND rows are never touched (a real holding native
     * never scanned is a BACKEND row with a null tx and must survive). Caller
     * must gate with [assetPruneGateOpen]. Returns the count deleted.
     */
    suspend fun pruneRemovedNativeAssetRows(): Int {
        val rows = utxoDao.getAssetUtxosBySourceNow(AssetSource.NATIVE)
        val liveTxids = HashSet<String>(rows.size)
        var deleted = 0
        for (row in rows) {
            if (row.txid.length != 64) continue   // never pass a malformed txid to native
            liveTxids.add(row.txid)
            val gone = runCatching { NativeBridge.getTransactionOutputsForHash(row.txid) }
                .getOrNull() == null
            if (!gone) {
                nativeAbsenceCounts.remove(row.txid)
                continue
            }
            val n = (nativeAbsenceCounts[row.txid] ?: 0) + 1
            nativeAbsenceCounts[row.txid] = n
            if (n >= ABSENCE_DEBOUNCE_THRESHOLD) {
                deleted += utxoDao.deleteAssetUtxo(row.txid, row.vout)
                nativeAbsenceCounts.remove(row.txid)
            }
        }
        // Drop stale debounce entries for txids no longer NATIVE-tagged.
        nativeAbsenceCounts.keys.retainAll(liveTxids)
        return deleted
    }

    companion object {
        const val ABSENCE_DEBOUNCE_THRESHOLD = 2
    }
```

> If `AssetManager` already has a `companion object`, add the const to it rather than declaring a second one.

- [ ] **Step 8: Run the prune test — passes.** Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.PruneRemovedNativeAssetRowsTest"`
Expected: PASS.

- [ ] **Step 9: Commit.**

```bash
git add core/src/main/java/io/digibyte/core/asset/AssetMaintenanceGate.kt \
        core/src/main/java/io/digibyte/core/asset/AssetManager.kt \
        core/src/test/java/io/digibyte/core/asset/AssetMaintenanceGateTest.kt \
        core/src/test/java/io/digibyte/core/asset/PruneRemovedNativeAssetRowsTest.kt
git commit -m "feat(assets): native-positive-removal prune (NATIVE-only, gated, debounced)"
```

---

## Task 5: Backend off the standing path + serialize maintenance (C4 + C8)

**Files:**
- Modify: `app/src/main/java/io/digibyte/service/SyncService.kt` (maintenance cycle ~466-489; `onAssetDetected` :1338; add `syncedThisSession` + its set site at `onSyncComplete`)

**Interfaces:**
- Consumes (Tasks 3-4): `assetManager.pruneRemovedNativeAssetRows()`, `assetPruneGateOpen(...)`, `assetManager.sweepKnownTransactionsForAssets()`, `assetManager.processIncomingAssetTx(...)`, `NativeBridge.getSyncProgress()`, `getPeerCount()`, `isWalletLoaded()`.
- Produces: a session-local `@Volatile var syncedThisSession = false` set true where `onSyncComplete` fires this process.

This task is SyncService wiring (an Android Service, not unit-tested here — the pure pieces it calls are already covered by Tasks 3-4). Verification is `:app:assembleMainnetDebug` + the on-device check in Rollout.

- [ ] **Step 1: Add the this-session sync flag.** Near the other SyncService session state, add:

```kotlin
    /** Set true when an onSyncComplete fires IN THIS PROCESS. Distinct from the
     *  persisted has_synced flag (which is true at startup before this session
     *  verifies the tx set) — the asset prune must gate on this, not that. */
    @Volatile private var syncedThisSession = false
```

Find the `onSyncComplete` callback (the same site that persists `saved_transactions` / flips `hasReachedSynced`) and set `syncedThisSession = true` there.

- [ ] **Step 2: Rewire the maintenance cycle.** Replace the `if (tickCount % 3L == 0L && NativeBridge.getPeerCount() > 0) { ... }` block (466-489) with a single guarded cycle that drops the backend refresh, keeps the sweep, and adds the gated prune:

```kotlin
            // Every 3rd tick (~30s): sovereign asset maintenance. NO backend
            // call on the standing path — /api/assets/unspent is reconcile-only
            // now. Guarded so a slow cycle on a long-history wallet can't stack.
            if (tickCount % 3L == 0L && NativeBridge.getPeerCount() > 0
                && assetMaintenanceRunning.compareAndSet(false, true)) {
                launch {
                    try {
                        runCatching { assetManager.sweepKnownTransactionsForAssets() }
                            .onFailure { android.util.Log.d("SyncService", "native sweep threw", it) }
                        if (assetPruneGateOpen(
                                syncedThisSession = syncedThisSession,
                                peerCount = NativeBridge.getPeerCount(),
                                progress = NativeBridge.getSyncProgress(),
                                walletLoaded = NativeBridge.isWalletLoaded(),
                            )) {
                            runCatching { assetManager.pruneRemovedNativeAssetRows() }
                                .onFailure { android.util.Log.d("SyncService", "asset prune threw", it) }
                        }
                    } finally {
                        assetMaintenanceRunning.set(false)
                    }
                }
            }
```

Add the guard field near the session state (and the import `io.digibyte.core.asset.assetPruneGateOpen`):

```kotlin
    private val assetMaintenanceRunning = java.util.concurrent.atomic.AtomicBoolean(false)
```

- [ ] **Step 3: Neutralize the event-path backend call.** In `onAssetDetected` (:1338), replace the backend refresh with the sovereign native path (insert-only, tags NATIVE via Task 2/3):

```kotlin
                // Sovereign: surface the just-detected asset via native decode of
                // this tx — NO backend hit (onAssetDetected is exactly when the
                // indexer is most likely behind). A fresh receive is confirmed
                // false here, so its owned outputs persist as NATIVE rows.
                runCatching {
                    assetManager.processIncomingAssetTx(txHashHex = txHash, blockHeight = 0L,
                        isOutgoingUnconfirmed = false)
                }.onFailure { android.util.Log.d("SyncService", "native detect-after-asset threw", it) }
```

- [ ] **Step 4: Confirm the reconcile stays additive-only.** Inspect `ChainReconciliationService.kt:75` — it calls `refreshAssetUtxosFromNetwork()`, unchanged. Confirm **no** owned-row delete was added to `refreshAssetUtxosFromNetwork` in any task (only the pre-existing not-owned prune remains). No code change; this is a verification step.

- [ ] **Step 5: Verify no backend call remains on the standing path.** Run: `grep -n "refreshAssetUtxosFromNetwork" app/src/main/java/io/digibyte/service/SyncService.kt`
Expected: **no matches** (both :478 and :1338 removed). The only remaining caller is `ChainReconciliationService.kt:75`.

- [ ] **Step 6: Build.** Run: `./gradlew :app:assembleMainnetDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/io/digibyte/service/SyncService.kt
git commit -m "feat(assets): backend off the standing path (sweep+event native-only); gated prune; serialize maintenance"
```

---

## Task 6: Persist-on-receipt + double-spend asset cleanup (C6 + C7)

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/asset/AssetManager.kt` (`processIncomingAssetTx` — add `persistAfterDetect`)
- Modify: `app/src/main/java/io/digibyte/service/SyncService.kt` (`onTransactionReceived` :1207; `rebroadcastStrandedSends` :1782-1789)
- Test: `core/src/test/java/io/digibyte/core/asset/AssetPersistOnDetectTest.kt`

**Interfaces:**
- Consumes: `AssetManager.walletTxPersister` (already a constructor dep, used at :1018); `AssetManager.buildOwnedScriptHexes(): Set<String>`; `AssetManager.clearDeadAssetSend(txid, ownedScriptHexes, outputs): Int`; `NativeBridge.getTransactionOutputsForHash`, `removeTransaction`.
- Produces: `processIncomingAssetTx(..., persistAfterDetect: Boolean = false)` — when true and detection returned non-null, calls `walletTxPersister?.persist()`.

- [ ] **Step 1: Write the failing test.** Create `AssetPersistOnDetectTest.kt`:

```kotlin
package io.digibyte.core.asset

import io.digibyte.core.WalletTxPersister
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.db.dao.UtxoDao
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class AssetPersistOnDetectTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val persister = mockk<WalletTxPersister>(relaxed = true)
    private lateinit var mgr: AssetManager

    @Before fun setup() {
        mockkObject(NativeBridge)
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), walletTxPersister = persister)
        every { NativeBridge.getTransactionOutputsForHash(any()) } returns arrayOf(
            "0|0|6a...DAMARKER...", "1|6000|76a914...owned...88ac")
        every { NativeBridge.getTransactionInputsForHash(any()) } returns arrayOf("aa..bb|0")
        every { NativeBridge.deriveIssuanceAssetId(any(), any(), any(), any(), any()) } returns "La1"
        coEvery { utxoDao.getAssetIdAt(any(), any()) } returns null
        coEvery { utxoDao.getAssetUtxoAt(any(), any()) } returns null
    }
    @After fun tearDown() = unmockkObject(NativeBridge)

    @Test fun persist_called_on_detect_receive_path() = runTest {
        mgr.processIncomingAssetTx("d".repeat(64), 0L, setOf("76a914...owned...88ac"),
            persistAfterDetect = true)
        verify { persister.persist() }
    }
    @Test fun sweep_path_does_not_persist() = runTest {
        mgr.processIncomingAssetTx("d".repeat(64), 0L, setOf("76a914...owned...88ac"))
        verify(exactly = 0) { persister.persist() }
    }
}
```

- [ ] **Step 2: Run it — verify it fails.** Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.AssetPersistOnDetectTest"`
Expected: FAIL — `persistAfterDetect` unresolved.

- [ ] **Step 3: Add the param + persist call.** Extend the `processIncomingAssetTx` signature with `persistAfterDetect: Boolean = false`, and just before the function returns its `IncomingAssetInfo`, add:

```kotlin
        if (persistAfterDetect) {
            // Durability: snapshot the native tx set so a freshly-received asset
            // tx survives a restart (else it's absent from the rebuilt wallet and
            // its NATIVE row would look phantom to the prune).
            runCatching { walletTxPersister?.persist() }
        }
```

- [ ] **Step 4: Wire the receive path.** In `SyncService.onTransactionReceived` (:1207), change the detect call to pass the flag:

```kotlin
                val detected = runCatching {
                    assetManager.processIncomingAssetTx(txHashHex = txHash, blockHeight = 0L,
                        persistAfterDetect = true)
                }.onFailure {
                    android.util.Log.w("SyncService", "native asset detect failed for $txHash", it)
                }.getOrNull()
```

- [ ] **Step 5: Wire the double-spend removal cleanup.** In `rebroadcastStrandedSends`, the double-spend branch (:1782-1789), read the tx's owned outputs BEFORE removal and clean the asset rows after a successful removal:

```kotlin
            if (!runCatching { NativeBridge.isTransactionValid(txid) }.getOrDefault(true)) {
                val outputs = runCatching { NativeBridge.getTransactionOutputsForHash(txid) }
                    .getOrNull()?.toList()
                if (runCatching { NativeBridge.removeTransaction(txid) }.getOrDefault(false)) {
                    store.remove(txid)
                    dropped = true
                    if (outputs != null) {
                        val owned = runCatching { assetManager.buildOwnedScriptHexes() }
                            .getOrDefault(emptySet())
                        if (owned.isNotEmpty()) {
                            runCatching { assetManager.clearDeadAssetSend(txid, owned, outputs) }
                                .onFailure { android.util.Log.d("SyncService", "double-spend asset cleanup threw", it) }
                        }
                    }
                    android.util.Log.i("SyncService",
                        "Dandelion recovery: dropped conflicted (double-spend) send $txid")
                }
                continue
            }
```

- [ ] **Step 6: Run the test — passes.** Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.AssetPersistOnDetectTest"`
Expected: PASS.

- [ ] **Step 7: Build the app.** Run: `./gradlew :app:assembleMainnetDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit.**

```bash
git add core/src/main/java/io/digibyte/core/asset/AssetManager.kt \
        app/src/main/java/io/digibyte/service/SyncService.kt \
        core/src/test/java/io/digibyte/core/asset/AssetPersistOnDetectTest.kt
git commit -m "feat(assets): persist asset txs on receipt; clean asset rows on double-spend removal"
```

---

## Task 7: Legacy Chang heal — change-address-scoped, log-only dry-run (C9)

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/asset/AssetManager.kt` (add `buildChangeScriptHexes`, `healLegacyChangeAddressOrphans`)
- Modify: `app/src/main/java/io/digibyte/service/SyncService.kt` (one-time trigger, dry-run const, once-per-install pref)
- Test: `core/src/test/java/io/digibyte/core/asset/LegacyChangeAddressHealTest.kt`

**Interfaces:**
- Consumes: `UtxoDao.getAllAssetUtxosNow(): List<UtxoEntity>`, `deleteAssetUtxo`; `NativeBridge.getChangeAddress(index, format)`, `addressToScriptPubKey(addr): ByteArray?`, `getTransactionOutputsForHash`.
- Produces: `suspend fun buildChangeScriptHexes(maxIndex: Int = 200): Set<String>`; `suspend fun healLegacyChangeAddressOrphans(changeScriptHexes: Set<String>, dryRun: Boolean): LegacyHealResult` where `data class LegacyHealResult(val candidates: List<String>, val deleted: Int)`.

- [ ] **Step 1: Write the failing test.** Create `LegacyChangeAddressHealTest.kt`:

```kotlin
package io.digibyte.core.asset

import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LegacyChangeAddressHealTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager
    private val changeScript = byteArrayOf(1, 2, 3)
    private val externalScript = byteArrayOf(9, 9, 9)
    private fun row(txid: String, script: ByteArray) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = script, satoshis = 6000, blockHeight = 0,
        isAsset = true, assetId = "La1", assetQuantity = 7, spent = false, assetSource = AssetSource.BACKEND)

    @Before fun setup() {
        mockkObject(NativeBridge)
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        coEvery { utxoDao.deleteAssetUtxo(any(), any()) } returns 1
    }
    @After fun tearDown() = unmockkObject(NativeBridge)

    @Test fun change_orphan_txgone_is_candidate_and_deleted_when_not_dryrun() = runTest {
        val txid = "a".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, changeScript))
        every { NativeBridge.getTransactionOutputsForHash(txid) } returns null   // tx gone
        val res = mgr.healLegacyChangeAddressOrphans(setOf(changeScript.joinToString("") { "%02x".format(it) }), dryRun = false)
        assertEquals(1, res.deleted)
        coVerify { utxoDao.deleteAssetUtxo(txid, 0) }
    }

    @Test fun dryrun_logs_but_deletes_nothing() = runTest {
        val txid = "a".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, changeScript))
        every { NativeBridge.getTransactionOutputsForHash(txid) } returns null
        val res = mgr.healLegacyChangeAddressOrphans(setOf(changeScript.joinToString("") { "%02x".format(it) }), dryRun = true)
        assertEquals(1, res.candidates.size)
        assertEquals(0, res.deleted)
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    @Test fun external_address_row_never_a_candidate() = runTest {
        val txid = "b".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, externalScript))
        every { NativeBridge.getTransactionOutputsForHash(txid) } returns null
        val res = mgr.healLegacyChangeAddressOrphans(setOf(changeScript.joinToString("") { "%02x".format(it) }), dryRun = false)
        assertEquals(0, res.candidates.size)
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    @Test fun present_tx_never_a_candidate() = runTest {
        val txid = "c".repeat(64)
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(txid, changeScript))
        every { NativeBridge.getTransactionOutputsForHash(txid) } returns arrayOf("0|6000|010203")
        val res = mgr.healLegacyChangeAddressOrphans(setOf(changeScript.joinToString("") { "%02x".format(it) }), dryRun = false)
        assertEquals(0, res.candidates.size)
    }
}
```

- [ ] **Step 2: Run it — verify it fails.** Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.LegacyChangeAddressHealTest"`
Expected: FAIL — `healLegacyChangeAddressOrphans` unresolved.

- [ ] **Step 3: Implement the heal + change-script builder.** In `AssetManager`:

```kotlin
    data class LegacyHealResult(val candidates: List<String>, val deleted: Int)

    /** The wallet's internal/change scriptPubKey set (lowercase hex), enumerated
     *  from native change addresses. Bounded — historical internal-chain usage is
     *  small. No native rebuild: getChangeAddress + addressToScriptPubKey exist. */
    suspend fun buildChangeScriptHexes(maxIndex: Int = 200): Set<String> {
        val out = HashSet<String>()
        for (i in 0 until maxIndex) {
            val addr = runCatching { NativeBridge.getChangeAddress(i, 0) }.getOrNull()
            if (addr.isNullOrBlank()) continue
            val script = runCatching { NativeBridge.addressToScriptPubKey(addr) }.getOrNull() ?: continue
            out.add(script.toHex().lowercase())
        }
        return out
    }

    /**
     * One-time heal of pre-existing owned-CHANGE-address asset orphans (dead-send
     * change markers from before the going-forward source-fix). A row is a
     * candidate iff native no longer has its tx AND its script is a change/internal
     * address — a real EXTERNAL receive can never satisfy the change-script test,
     * so it is never eligible. Ships log-only (dryRun) first; deletes only once the
     * operator confirms the candidate set on-device.
     */
    suspend fun healLegacyChangeAddressOrphans(
        changeScriptHexes: Set<String>,
        dryRun: Boolean,
    ): LegacyHealResult {
        val changeLower = changeScriptHexes.map { it.lowercase() }.toSet()
        val candidates = mutableListOf<String>()
        var deleted = 0
        for (row in utxoDao.getAllAssetUtxosNow()) {
            if (row.txid.length != 64) continue
            if (row.scriptPubKey.isEmpty()) continue
            if (row.scriptPubKey.toHex().lowercase() !in changeLower) continue
            val gone = runCatching { NativeBridge.getTransactionOutputsForHash(row.txid) }
                .getOrNull() == null
            if (!gone) continue
            candidates.add("${row.txid}:${row.vout} qty=${row.assetQuantity} asset=${row.assetId}")
            android.util.Log.i("AssetManager",
                "legacyHeal ${if (dryRun) "DRYRUN candidate" else "DELETE"}: ${row.txid}:${row.vout} qty=${row.assetQuantity}")
            if (!dryRun) deleted += utxoDao.deleteAssetUtxo(row.txid, row.vout)
        }
        android.util.Log.i("AssetManager",
            "legacyHeal: ${candidates.size} candidates, deleted=$deleted, dryRun=$dryRun")
        return LegacyHealResult(candidates, deleted)
    }
```

> `toHex()` is the private extension already in `AssetManager` (used at line 1087). `NativeBridge.addressToScriptPubKey` is already used by `refreshAssetUtxosFromNetwork`. Confirm `getChangeAddress`'s `format` arg — use `0` (legacy) to match how change scripts were stored historically; if change markers were segwit, the enumeration must cover the same format the rows carry. The dry-run surfaces any mismatch (a real Chang phantom that isn't a candidate ⇒ wrong format).

- [ ] **Step 4: Trigger it once, log-only, behind the gate.** In `SyncService`, after the maintenance cycle's gate check (Task 5), add a one-time invocation guarded by a pref so it runs once per install and only when synced. Add the const at top of the asset-maintenance section:

```kotlin
    // Legacy Chang heal ships LOG-ONLY first. Flip to false only after the
    // on-device dry-run confirms the candidate set (see plan Rollout).
    private val legacyHealDryRun = true
```

Inside the guarded `launch { ... }` in the maintenance cycle (after the prune), add:

```kotlin
                        val healPrefs = getSharedPreferences("dgb_asset_heal", MODE_PRIVATE)
                        if (!healPrefs.getBoolean("legacy_done", false)
                            && assetPruneGateOpen(syncedThisSession, NativeBridge.getPeerCount(),
                                NativeBridge.getSyncProgress(), NativeBridge.isWalletLoaded())) {
                            runCatching {
                                val changeSet = assetManager.buildChangeScriptHexes()
                                val res = assetManager.healLegacyChangeAddressOrphans(changeSet, dryRun = legacyHealDryRun)
                                // Only mark done on a real (non-dry) pass, so the dry-run can be
                                // observed across sessions until deletion is enabled.
                                if (!legacyHealDryRun) healPrefs.edit().putBoolean("legacy_done", true).apply()
                                android.util.Log.i("SyncService", "legacyHeal ran: ${res.candidates.size} candidates")
                            }.onFailure { android.util.Log.d("SyncService", "legacyHeal threw", it) }
                        }
```

- [ ] **Step 5: Run the heal test — passes.** Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.LegacyChangeAddressHealTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Full suites + build.** Run: `./gradlew :core:testMainnetDebugUnitTest :app:assembleMainnetDebug 2>&1 | tail -6`
Expected: `BUILD SUCCESSFUL`, all core tests green.

- [ ] **Step 7: Commit.**

```bash
git add core/src/main/java/io/digibyte/core/asset/AssetManager.kt \
        app/src/main/java/io/digibyte/service/SyncService.kt \
        core/src/test/java/io/digibyte/core/asset/LegacyChangeAddressHealTest.kt
git commit -m "feat(assets): legacy change-address orphan heal (log-only dry-run first)"
```

---

## Rollout (post-implementation, on-device)

1. `./gradlew :app:assembleMainnetDebug` → `adb -s R5GYC4FQKBW install -r app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk` (wallet preserved — this is an upgrade, MIGRATION_5_6 runs).
2. Read the dry-run: `adb -s R5GYC4FQKBW logcat -d | grep -iE "legacyHeal|sweepKnownTransactions"`. Confirm (a) the sweep no longer logs any backend refresh and no `/api/assets/unspent` traffic, (b) the `legacyHeal DRYRUN candidate` lines are exactly the Chang phantoms, and (c) the true Chang issuance UTXO (tx native still holds) is **not** among the candidates.
3. If the candidate set is correct, set `legacyHealDryRun = false`, rebuild+install, and confirm Chang collapses to its true count (≈1 UTXO / 10) and stays put across sweeps.
4. Sequence `feat/native-asset-spent` **after** this branch merges (Task 2's non-clobbering re-tag is the compatibility guarantee).

---

## Self-Review

**Spec coverage:** C1→Task 1; C5→Task 2; C2→Task 3; C3→Task 4; C4+C8→Task 5; C6+C7→Task 6; C9→Task 7. All nine components mapped. MIG-1/MIG-2 guardrails are in Task 1 (Steps 3, 9). The HARD CONSTRAINT is honored (no backend-absence delete; prune is NATIVE-only positive-removal; legacy heal is change-address-scoped + dry-run-gated).

**Placeholder scan:** The only `...` are in *test fixtures* (OP_RETURN / script bytes) with an explicit implementer note to substitute real `DigiAssetDecoderTest` fixtures — a fixture-substitution instruction, not a logic gap. No TBDs in production code steps.

**Type consistency:** `AssetSource.NATIVE/BACKEND` (String), `getAssetUtxosBySourceNow`/`getAssetUtxoAt`/`markAssetSource`/`updateAssetQuantity` (Task 1) are consumed with identical signatures in Tasks 2/4. `processIncomingAssetTx(txHashHex, blockHeight, ownedScriptHexes, isOutgoingUnconfirmed, persistAfterDetect)` param names are consistent across Tasks 3/5/6. `assetPruneGateOpen(syncedThisSession, peerCount, progress, walletLoaded)` and `pruneRemovedNativeAssetRows()` match between Tasks 4 and 5. `LegacyHealResult(candidates, deleted)` consistent Task 7.
