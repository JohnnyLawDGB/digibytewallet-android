# Address-History Backstop (build #1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline, tightly-coupled shared files) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Recover the wallet's currently-missing DigiDollar/asset/taproot transactions by importing every tx the node's address-history knows about but the CF scan skipped — with confirming height so 0-value DD credits.

**Architecture:** New history-based reconcile pass in `ChainReconciliationService`, backed by two new `DgbNodeClient` calls (`addressHistory`, `fetchRawTx`) against the (now taproot-capable) digiscope/own-node endpoints. Enumerate full owned address set → per-address history (txid+height) → diff against known txids → fetch raw hex → `registerRawTransaction(bytes, height, time)`. Wired into the existing "Scan for missing funds" button.

**Tech Stack:** Kotlin, OkHttp, org.json, coroutines; JUnit4 + mockk (host JVM). No native/submodule changes.

## Global Constraints

- **App/core module ONLY. NO `native/` or submodule edits** — this is the non-native backstop (build #1). The native P0 scan-fix is a separate later build.
- **Every imported tx MUST carry its confirming height** (from address-history) — BRWallet's dust-pending gate withholds 0-value DD credit until confirmed. Registering without height parks the tx and credits nothing.
- **History-based, not UTXO-based.** The address-history endpoint returns 0-value (DD) and fully-spent (asset marker) txs across all four address types; the existing `/api/wallet/reconcile` (scantxoutset) structurally cannot.
- **Respect `endpoint()`** — the user's `setCustomEndpoint` own-node override is the trust-minimized path; default is cert-pinned digiscope.
- **NO `mockkObject(NativeBridge)` in unit tests** — `NativeBridge`'s `init` calls `System.loadLibrary("core-lib")`; referencing it on the host JVM throws `UnsatisfiedLinkError`. Test the extracted pure functions only; the thin NativeBridge glue is verified on-device (established codebase convention — see `AssetSourceFixTest`, `parseReconcileResponse`).
- **No silent network catches** — log failures with tag `DgbNodeClient`/`ChainReconciliation` (per project convention).

---

## File Structure

- `core/…/reconcile/DgbNodeClient.kt` — add `AddressTx` data class, top-level `parseAddressHistory` + `parseRawTxResponse` (pure, tested), and `addressHistory()` + `fetchRawTx()` (network wrappers, reuse existing private `httpGetJson`).
- `core/…/reconcile/ChainReconciliationService.kt` — add top-level `extractKnownTxids` + `planHistoryImport` (pure, tested), `reconcileAddressHistory()` (orchestration glue), wire into `reconcile()`, add `historyTxsImported` to `State.Done`.
- `app/…/ui/settings/ReconcileScreen.kt` — render the recovered-from-history count.
- Test: `core/src/test/java/io/digibyte/core/reconcile/AddressHistoryParseTest.kt` (parse functions).
- Test: `core/src/test/java/io/digibyte/core/reconcile/AddressHistoryPlanTest.kt` (planning functions).

**Grounding facts (verified 2026-07-19):**
- `GET {endpoint}/rpc/address-history/:address` → `{"txCount":N,"transactions":[{"txid":"…","height":23877512,"confirmations":2919}, …]}` (height is direct — no block-hash hop).
- `GET {endpoint}/explorer/tx/:txid` → carries `"hex"` (+ `"blocktime"`; top-level `height` is absent — supply it from address-history).
- `NativeBridge.dumpAllAddresses(): String` (newline-separated, all 4 types via `BRWalletAllAddrs`); `getTransactionDetails(): String` (lines `txHash|amount|fee|blockHeight|timestamp|sent|received`); `registerRawTransaction(rawTx: ByteArray, blockHeight: Long, blockTimestamp: Long): Boolean`.
- Existing reusables: `DgbNodeClient.httpGetJson(url): JSONObject?` (private, same-class-accessible), `endpoint()`, `RawTxEntry(hex, blockHeight, blockTime)`; `ChainReconciliationService.hexToBytes(hex)`, `State.Done(...)`, `_state`.

---

### Task 1: `DgbNodeClient.addressHistory` + `AddressTx` + `parseAddressHistory`

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/reconcile/DgbNodeClient.kt`
- Test: `core/src/test/java/io/digibyte/core/reconcile/AddressHistoryParseTest.kt`

**Interfaces:**
- Produces: `data class AddressTx(val txid: String, val height: Long)`; `internal fun parseAddressHistory(root: JSONObject): List<AddressTx>`; `suspend fun DgbNodeClient.addressHistory(address: String): List<AddressTx>?`

- [ ] **Step 1: Write the failing test** — create `AddressHistoryParseTest.kt`:

```kotlin
package io.digibyte.core.reconcile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AddressHistoryParseTest {
    @Test fun parses_transactions_with_txid_and_height() {
        val json = JSONObject(
            """
            {"address":"dgb1plr8","txCount":2,"transactions":[
              {"txid":"8096148944b5c4d136d62d6521ae7f85283dce1656de991a77b8faf1b2973a63","height":23877512,"confirmations":2919},
              {"txid":"b42c76de310f5b3fee6d115bcaf2410c844a665d7c5eb2d7f0ecc31a22511636","height":23874652,"confirmations":5779}
            ]}
            """.trimIndent()
        )
        val result = parseAddressHistory(json)
        assertEquals(2, result.size)
        assertEquals("8096148944b5c4d136d62d6521ae7f85283dce1656de991a77b8faf1b2973a63", result[0].txid)
        assertEquals(23877512L, result[0].height)
        assertEquals(23874652L, result[1].height)
    }

    @Test fun missing_transactions_array_yields_empty() {
        assertEquals(0, parseAddressHistory(JSONObject("""{"address":"x","txCount":0}""")).size)
    }

    @Test fun blank_txid_entries_skipped() {
        val json = JSONObject("""{"transactions":[{"txid":"","height":1},{"txid":"aa","height":2}]}""")
        val r = parseAddressHistory(json)
        assertEquals(1, r.size)
        assertEquals("aa", r[0].txid)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "*.AddressHistoryParseTest"`
Expected: FAIL — `parseAddressHistory` unresolved.

- [ ] **Step 3: Implement** — in `DgbNodeClient.kt`, add the data class + parser at top level (after the existing `data class RawTxEntry`/`ReconcileResult` block, outside the class), and the network wrapper as a class member (next to `txConfirmation`):

Top-level (file scope):
```kotlin
/** One confirmed tx touching an address, from the node's address-history. */
data class AddressTx(val txid: String, val height: Long)

/** Pure: extract (txid, height) from a `/rpc/address-history/:address` body. */
internal fun parseAddressHistory(root: JSONObject): List<AddressTx> {
    val txs = root.optJSONArray("transactions") ?: return emptyList()
    val out = ArrayList<AddressTx>(txs.length())
    for (i in 0 until txs.length()) {
        val t = txs.optJSONObject(i) ?: continue
        val txid = t.optString("txid", "")
        if (txid.isBlank()) continue
        out += AddressTx(txid, t.optLong("height", 0L))
    }
    return out
}
```

Class member (inside `DgbNodeClient`):
```kotlin
/**
 * History of every confirmed tx touching [address], via the ElectrumX-backed
 * {endpoint}/rpc/address-history/:address endpoint. Unlike the UTXO-based
 * reconcile this returns 0-value (DigiDollar) and fully-spent (asset marker)
 * txs for all four address types — the taproot/DD case scantxoutset misses.
 * Returns null on network/parse failure.
 */
suspend fun addressHistory(address: String): List<AddressTx>? = withContext(Dispatchers.IO) {
    val root = httpGetJson("${endpoint()}/rpc/address-history/$address") ?: return@withContext null
    parseAddressHistory(root)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "*.AddressHistoryParseTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/digibyte/core/reconcile/DgbNodeClient.kt core/src/test/java/io/digibyte/core/reconcile/AddressHistoryParseTest.kt
git commit -m "feat(reconcile): DgbNodeClient.addressHistory (all-4-type tx history)"
```

---

### Task 2: `DgbNodeClient.fetchRawTx` + `parseRawTxResponse`

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/reconcile/DgbNodeClient.kt`
- Test: `core/src/test/java/io/digibyte/core/reconcile/AddressHistoryParseTest.kt` (add cases)

**Interfaces:**
- Consumes: `RawTxEntry(hex, blockHeight, blockTime)` (existing).
- Produces: `internal fun parseRawTxResponse(root: JSONObject, height: Long): RawTxEntry?`; `suspend fun DgbNodeClient.fetchRawTx(txid: String, height: Long): RawTxEntry?`

- [ ] **Step 1: Write the failing test** — append to `AddressHistoryParseTest.kt`:

```kotlin
    @Test fun parses_raw_tx_hex_and_time_with_supplied_height() {
        val json = JSONObject(
            """{"txid":"8096","hex":"07700001aabb","blocktime":1784416468,"confirmations":2919,"blockhash":"00044c37"}"""
        )
        val entry = parseRawTxResponse(json, height = 23877512L)!!
        assertEquals("07700001aabb", entry.hex)
        assertEquals(23877512L, entry.blockHeight)
        assertEquals(1784416468L, entry.blockTime)
    }

    @Test fun blank_hex_yields_null() {
        assertNull(parseRawTxResponse(JSONObject("""{"txid":"x"}"""), 1L))
    }

    @Test fun falls_back_to_time_when_blocktime_absent() {
        val entry = parseRawTxResponse(JSONObject("""{"hex":"aa","time":123}"""), 5L)!!
        assertEquals(123L, entry.blockTime)
    }
```

Add imports at top of the test file: `import org.junit.Assert.assertNull`.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "*.AddressHistoryParseTest"`
Expected: FAIL — `parseRawTxResponse` unresolved.

- [ ] **Step 3: Implement** — in `DgbNodeClient.kt`, add top-level parser + class member:

Top-level:
```kotlin
/** Pure: pack a `/explorer/tx/:txid` body (hex + block time) with the height
 *  the caller learned from address-history. Null if hex absent. */
internal fun parseRawTxResponse(root: JSONObject, height: Long): RawTxEntry? {
    val hex = root.optString("hex", "")
    if (hex.isBlank()) return null
    val time = root.optLong("blocktime", root.optLong("time", 0L))
    return RawTxEntry(hex = hex, blockHeight = height, blockTime = time)
}
```

Class member:
```kotlin
/**
 * Fetch a tx's raw hex + block time from {endpoint}/explorer/tx/:txid, packed
 * with the [height] the caller already learned from [addressHistory] (the
 * explorer/tx body exposes blockhash+confirmations but no direct height).
 * The height satisfies BRWallet's dust-pending gate on 0-value DD credits.
 * Returns null on network/parse failure.
 */
suspend fun fetchRawTx(txid: String, height: Long): RawTxEntry? = withContext(Dispatchers.IO) {
    val root = httpGetJson("${endpoint()}/explorer/tx/$txid") ?: return@withContext null
    parseRawTxResponse(root, height)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "*.AddressHistoryParseTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/digibyte/core/reconcile/DgbNodeClient.kt core/src/test/java/io/digibyte/core/reconcile/AddressHistoryParseTest.kt
git commit -m "feat(reconcile): DgbNodeClient.fetchRawTx (raw hex + supplied height)"
```

---

### Task 3: pure planning functions `extractKnownTxids` + `planHistoryImport`

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/reconcile/ChainReconciliationService.kt`
- Test: `core/src/test/java/io/digibyte/core/reconcile/AddressHistoryPlanTest.kt`

**Interfaces:**
- Consumes: `AddressTx` (Task 1).
- Produces: `internal fun extractKnownTxids(details: String): Set<String>`; `internal fun planHistoryImport(histories: List<List<AddressTx>>, knownTxids: Set<String>): List<AddressTx>`

- [ ] **Step 1: Write the failing test** — create `AddressHistoryPlanTest.kt`:

```kotlin
package io.digibyte.core.reconcile

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressHistoryPlanTest {
    @Test fun extracts_known_txids_from_details() {
        val details = """
            aaa|100|1|500|1700|0|100
            bbb|0|1|2147483647|1700|0|0
        """.trimIndent()
        assertEquals(setOf("aaa", "bbb"), extractKnownTxids(details))
    }

    @Test fun blank_lines_ignored() {
        assertEquals(emptySet<String>(), extractKnownTxids("\n  \n"))
    }

    @Test fun plan_drops_known_and_dedups_across_addresses() {
        val a = listOf(AddressTx("known", 10), AddressTx("new1", 20))
        val b = listOf(AddressTx("new1", 20), AddressTx("new2", 30))
        val plan = planHistoryImport(listOf(a, b), setOf("known"))
        assertEquals(listOf("new1", "new2"), plan.map { it.txid })
    }

    @Test fun plan_keeps_highest_height_for_duplicate_txid() {
        val plan = planHistoryImport(
            listOf(listOf(AddressTx("t", 0)), listOf(AddressTx("t", 99))),
            emptySet(),
        )
        assertEquals(1, plan.size)
        assertEquals(99L, plan[0].height)
    }

    @Test fun empty_history_yields_empty_plan() {
        assertEquals(emptyList<AddressTx>(), planHistoryImport(emptyList(), setOf("x")))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "*.AddressHistoryPlanTest"`
Expected: FAIL — `extractKnownTxids`/`planHistoryImport` unresolved.

- [ ] **Step 3: Implement** — in `ChainReconciliationService.kt`, add at top level (file scope, after the imports / before or after the class):

```kotlin
/** Known wallet txids = field 0 of each getTransactionDetails line
 *  (`txHash|amount|fee|blockHeight|timestamp|sent|received`). */
internal fun extractKnownTxids(details: String): Set<String> =
    details.trim().lines().mapNotNull { line ->
        line.split("|").getOrNull(0)?.trim()?.takeIf { it.isNotBlank() }
    }.toSet()

/** Flatten per-address histories, drop txids already in the wallet, dedup by
 *  txid keeping the highest confirming height. Order = first-seen among kept
 *  (deterministic for progress display + tests). */
internal fun planHistoryImport(
    histories: List<List<AddressTx>>,
    knownTxids: Set<String>,
): List<AddressTx> {
    val byTxid = LinkedHashMap<String, AddressTx>()
    for (history in histories) for (tx in history) {
        if (tx.txid in knownTxids) continue
        val existing = byTxid[tx.txid]
        if (existing == null || tx.height > existing.height) byTxid[tx.txid] = tx
    }
    return byTxid.values.toList()
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "*.AddressHistoryPlanTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/digibyte/core/reconcile/ChainReconciliationService.kt core/src/test/java/io/digibyte/core/reconcile/AddressHistoryPlanTest.kt
git commit -m "feat(reconcile): pure address-history import planning (dedup + known-diff)"
```

---

### Task 4: `reconcileAddressHistory()` orchestration + wire into `reconcile()`

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/reconcile/ChainReconciliationService.kt`

**Interfaces:**
- Consumes: `extractKnownTxids`, `planHistoryImport` (Task 3); `nodeClient.addressHistory`, `nodeClient.fetchRawTx` (Tasks 1-2); `NativeBridge.dumpAllAddresses/getTransactionDetails/registerRawTransaction`; `hexToBytes` (existing).
- Produces: `suspend fun reconcileAddressHistory(): Int`; new `State.Done.historyTxsImported: Int` field.

> **No unit test for this task.** It composes NativeBridge statics (untestable on host JVM — see Global Constraints) with the already-tested pure functions from Tasks 1-3. Verified on-device (recovery proof #1 in the design's §4). This mirrors the existing `reconcile()`/`confirmPendingTransactions()`, which are also NativeBridge glue with no unit test.

- [ ] **Step 1: Add the `historyTxsImported` field to `State.Done`** (default keeps callers compiling):

```kotlin
        data class Done(
            val scannedAddresses: Int,
            val utxosSeenOnChain: Int,
            val txsImported: Int,
            val alreadyKnown: Int,
            val totalChainBalanceSat: Long,
            val historyTxsImported: Int = 0,
        ) : State()
```

- [ ] **Step 2: Add `reconcileAddressHistory()`** as a member (e.g. after `confirmPendingTransactions()`):

```kotlin
    /**
     * Address-HISTORY reconcile (the backstop): enumerate the full owned
     * address set, ask the node for every tx touching each address, and
     * register the ones the wallet is missing — WITH their confirming height,
     * so BRWallet's dust-pending gate releases 0-value DigiDollar and spent
     * asset markers the UTXO reconcile cannot surface. History-based, not
     * UTXO-based. Recovers a tx that fell out of the CF scan set entirely
     * (which confirmPendingTransactions, driven by the wallet's own pending
     * list, cannot). Returns the count imported.
     */
    suspend fun reconcileAddressHistory(): Int {
        val addrs = NativeBridge.dumpAllAddresses().trim().lines().filter { it.isNotBlank() }
        if (addrs.isEmpty()) return 0
        val known = extractKnownTxids(NativeBridge.getTransactionDetails())
        val histories = ArrayList<List<AddressTx>>(addrs.size)
        for ((i, addr) in addrs.withIndex()) {
            _state.value = State.Scanning(
                "Reading address history ${i + 1}/${addrs.size}…",
                progress = (i + 1).toFloat() / addrs.size * 0.5f,
            )
            nodeClient.addressHistory(addr)?.let { histories += it }
        }
        val toImport = planHistoryImport(histories, known)
        var imported = 0
        for ((i, tx) in toImport.withIndex()) {
            _state.value = State.Scanning(
                "Recovering tx ${i + 1}/${toImport.size}…",
                progress = 0.5f + (i + 1).toFloat() / toImport.size * 0.5f,
            )
            val raw = nodeClient.fetchRawTx(tx.txid, tx.height) ?: continue
            val bytes = runCatching { hexToBytes(raw.hex) }.getOrNull() ?: continue
            if (NativeBridge.registerRawTransaction(bytes, raw.blockHeight, raw.blockTime)) {
                imported++
                android.util.Log.i("ChainReconciliation", "history-recovered ${tx.txid} @${tx.height}")
            }
        }
        return imported
    }
```

- [ ] **Step 3: Wire into `reconcile()`** — immediately after the `confirmPendingTransactions()` block (before "Listing wallet addresses…"), add:

```kotlin
            // Address-history backstop: import every tx touching the owned set
            // that the CF scan skipped (0-value DD, spent asset markers, taproot
            // — all four address types), with confirming height. Non-fatal.
            _state.value = State.Scanning("Scanning address history…")
            val historyImported = runCatching { reconcileAddressHistory() }.getOrDefault(0)
            if (historyImported > 0) {
                android.util.Log.i("ChainReconciliation",
                    "address-history reconcile imported $historyImported tx(s)")
            }
```

- [ ] **Step 4: Thread `historyImported` into BOTH `State.Done(...)` constructions** — the early empty-UTXO return AND the final return each gain `historyTxsImported = historyImported`. (The empty-UTXO Done is the common DD-only recovery path — scantxoutset returns no UTXO for a 0-value DD output, so this field is how the UI shows the recovery.)

- [ ] **Step 5: Build the core module + run the full reconcile test set**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "*.reconcile.*"`
Expected: PASS (AddressHistoryParseTest + AddressHistoryPlanTest + PostUpgradeReconcilerTest all green; the module compiles with the new field + method).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/io/digibyte/core/reconcile/ChainReconciliationService.kt
git commit -m "feat(reconcile): address-history backstop pass wired into Scan for missing funds"
```

---

### Task 5: surface recovered-from-history count in ReconcileScreen

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/settings/ReconcileScreen.kt`

**Interfaces:**
- Consumes: `State.Done.historyTxsImported` (Task 4).

- [ ] **Step 1: Add a result row + widen the reassurance gate** — in the `is State.Done` block, after the `ResultRow("Already in wallet", …)` line (`:403`), add:

```kotlin
                        if (s.historyTxsImported > 0) {
                            ResultRow(
                                "Recovered from history",
                                "${s.historyTxsImported}",
                                emphasize = true,
                            )
                        }
```

And change the reassurance gate at `:404` from `if (s.txsImported > 0) {` to:

```kotlin
                        if (s.txsImported > 0 || s.historyTxsImported > 0) {
```

- [ ] **Step 2: Compile the app module**

Run: `./gradlew :app:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/digibyte/ui/settings/ReconcileScreen.kt
git commit -m "feat(reconcile): show recovered-from-history count on scan result"
```

---

## Self-Review

- **Spec coverage:** enumerate all 4 types (`dumpAllAddresses`✓ T4), history per address (`addressHistory`✓ T1), classify/reflect (automatic via native `_BRWalletUpdateBalance` on register — no client classification needed), tie to UTXOs (native register✓), confirming height for DD dust-gate (✓ T2/T4), history-not-UTXO (✓), own-node endpoint respected (`endpoint()`✓). Covered.
- **Placeholder scan:** none — all code is literal.
- **Type consistency:** `AddressTx(txid, height)` used identically in T1/T3/T4; `RawTxEntry(hex, blockHeight, blockTime)` reused (not redefined); `parseAddressHistory`/`parseRawTxResponse`/`extractKnownTxids`/`planHistoryImport` signatures match across tasks.
- **On-device verification (post-merge, from design §4 proof #1):** run "Scan for missing funds" on the wallet missing the ~23.87M DD block; confirm the DD receive `8096…` + 3 taproot txs register with height, DD balance credits, asset rows appear. Do NOT drive the operator's tethered device — have them tap; observe via `adb logcat | grep ChainReconciliation`.
