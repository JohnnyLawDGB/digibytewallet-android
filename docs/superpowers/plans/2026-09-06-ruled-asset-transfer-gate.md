# Rule-Bearing DigiAsset Transfer Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make it impossible for this wallet to broadcast a transfer of a DigiAsset that may carry transfer rules, on both the send path and the recovery path, fail-closed, with a plain explanation in every supported language.

**Architecture:** A pure gate turns three facts (chain-proven issuance opcode, issuance locked flag, whether the proxy reports a rules object) into one of `NONE / RULE_BOUND / UNKNOWN`. The provenance walk learns the opcode and persists it; the metadata service learns the rules object and persists it; `AssetManager` composes the two and refuses in `sendAsset`; the recovery classifier resolves the same state per outpoint and the move service skips anything not `NONE`; the screens show a badge, disable Send, and explain.

**Tech Stack:** Kotlin, Room (SQLCipher), OkHttp/org.json, Jetpack Compose, JUnit4 + mockk + kotlinx-coroutines-test. No C changes.

**Spec:** `docs/superpowers/specs/2026-09-06-ruled-asset-transfer-gate-design.md`

## Global Constraints

- Work ONLY in the worktree `/home/polloloco/wt-ruled-asset-gate` on branch `fix/ruled-asset-send-gate`. Prefix every shell command with `cd /home/polloloco/wt-ruled-asset-gate &&`. Never touch `/home/polloloco/digibytewallet-android`.
- Only `TransferRuleState.NONE` may move an asset. Unknown means refuse. (spec §1)
- `NONE` requires a chain-proven issuance with opcode 1, 2 or 5 AND `issuanceLocked == true`. The API signal can only tighten. (spec §1)
- Every user-facing string ships in all 13 locale directories listed in `AppLocale.SUPPORTED` (`values`, `values-de`, `values-es`, `values-fr`, `values-hi`, `values-zh`, `values-ja`, `values-b+pt+BR`, `values-id`, `values-vi`, `values-tr`, `values-ru`, `values-b+fil`) in the same commit, in `strings_wallet.xml`. (spec §8)
- Every test is written first and must FAIL on the unfixed code before the implementation step. (spec §9)
- No version bump on this branch. (spec §10)
- Commit messages end with:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01HXtAff7AzbUR9bthQiFpg1
  ```
- Test commands: `./gradlew :core:testMainnetDebugUnitTest --tests "<fqcn>" -q` for core, `./gradlew :app:testMainnetDebugUnitTest --tests "<fqcn>" -q` for app. A PASS prints nothing on `-q`; a FAIL prints the failing test and `BUILD FAILED`.

---

## File structure

| File | Responsibility |
|---|---|
| `core/src/main/java/io/digibyte/core/asset/rules/TransferRuleState.kt` (new) | The three-state enum, the pure gate, and the screen-facing `RuleCheckState`. No dependencies. |
| `core/src/main/java/io/digibyte/core/asset/DigiAssetDecoder.kt` | `DecodedAssetHeader.hasRules`. |
| `core/src/main/java/io/digibyte/core/asset/AssetProvenanceWalker.kt` | `ResolvedAssetFacts` gains opcode/locked; `ProvenanceStore.issuanceFactsFor`; `resolve(forceToIssuance)`. |
| `core/src/main/java/io/digibyte/core/asset/RoomProvenanceStore.kt` | Maps the two new columns. |
| `core/src/main/java/io/digibyte/core/db/entity/AssetProvenanceEntity.kt`, `dao/AssetProvenanceDao.kt`, `Migration_10_11.kt` (new), `WalletDatabase.kt` | Persistence of the opcode. |
| `core/src/main/java/io/digibyte/core/asset/network/AssetNetworkClient.kt`, `DigiScopeAssetParsing.kt` | `AssetDataResponse.rules`. |
| `core/src/main/java/io/digibyte/core/db/dao/AssetMetadataDao.kt`, `core/src/main/java/io/digibyte/core/ipfs/AssetMetadataService.kt` | Persist and read `rulesJson`. |
| `core/src/main/java/io/digibyte/core/TransactionBuilder.kt` | `TxResult.Refused`, `SendRefusal`. |
| `core/src/main/java/io/digibyte/core/model/AssetData.kt` | `OwnedAsset.transferRules`. |
| `core/src/main/java/io/digibyte/core/asset/AssetManager.kt` | Hop fills opcode; `transferRuleState`; `verifyTransferRulesForTx`; `verifyTransferRules`; gate in `sendAsset`; `getOwnedAssets` fills the state. |
| `core/src/main/java/io/digibyte/core/recovery/ForeignUtxoAssetClassifier.kt`, `ForeignAssetTransferService.kt` | Recovery gate. |
| `app/src/main/java/io/digibyte/ui/asset/AssetViewModel.kt`, `AssetSendScreen.kt`, `AssetDetailScreen.kt`, `TransferRuleCard.kt` (new) | Screen gate. |
| `app/src/main/java/io/digibyte/ui/recovery/RecoverFundsViewModel.kt`, `RecoverFundsScreen.kt` | Recovery wiring and text. |
| `app/src/main/java/io/digibyte/ui/wallet/SendViewModel.kt` | Exhaustive `when` over `TxResult`. |
| `app/src/main/res/values*/strings_wallet.xml` (13 files) | Strings. |

---

### Task 1: The pure rule gate

**Files:**
- Create: `core/src/main/java/io/digibyte/core/asset/rules/TransferRuleState.kt`
- Test: `core/src/test/java/io/digibyte/core/asset/rules/AssetTransferRuleGateTest.kt`

**Interfaces:**
- Produces: `enum class TransferRuleState { NONE, RULE_BOUND, UNKNOWN }`; `object AssetTransferRuleGate { fun stateOf(issuanceOpcode: Int?, issuanceLocked: Boolean?, apiRulesPresent: Boolean?): TransferRuleState }`; `enum class RuleCheckState { CHECKING, NONE, RULE_BOUND, UNVERIFIED }` with `companion object { fun of(state: TransferRuleState): RuleCheckState }` and `val allowsSend: Boolean`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.digibyte.core.asset.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision table from the spec (§1), one test per row plus the rows that must NOT loosen.
 *
 * Why fail-closed: DigiAsset Core clears every output of a transfer that breaks a rule, so a
 * wrong "no rules" here destroys the user's whole input holding while the wallet shows success.
 * A wrong "unknown" merely blocks a send until the walk or the proxy answers.
 */
class AssetTransferRuleGateTest {

    @Test fun `opcode 3 is rule-bound whatever else is known`() {
        assertEquals(TransferRuleState.RULE_BOUND, AssetTransferRuleGate.stateOf(3, true, null))
        assertEquals(TransferRuleState.RULE_BOUND, AssetTransferRuleGate.stateOf(3, false, false))
    }

    @Test fun `opcode 4 is rule-bound whatever else is known`() {
        assertEquals(TransferRuleState.RULE_BOUND, AssetTransferRuleGate.stateOf(4, true, false))
        assertEquals(TransferRuleState.RULE_BOUND, AssetTransferRuleGate.stateOf(4, null, null))
    }

    @Test fun `a rules object from the proxy is rule-bound even when the chain says opcode 1`() {
        assertEquals(TransferRuleState.RULE_BOUND, AssetTransferRuleGate.stateOf(1, true, true))
        assertEquals(TransferRuleState.RULE_BOUND, AssetTransferRuleGate.stateOf(null, null, true))
    }

    @Test fun `a locked rule-free issuance is NONE with or without a proxy answer`() {
        for (op in listOf(1, 2, 5)) {
            assertEquals("opcode $op / api false", TransferRuleState.NONE, AssetTransferRuleGate.stateOf(op, true, false))
            assertEquals("opcode $op / api null", TransferRuleState.NONE, AssetTransferRuleGate.stateOf(op, true, null))
        }
    }

    @Test fun `an unlocked rule-free issuance is UNKNOWN because a reissuance can add rules`() {
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(1, false, false))
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(5, null, null))
    }

    @Test fun `no opcode is UNKNOWN unless the proxy says rule-bound`() {
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(null, true, false))
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(null, null, null))
    }

    @Test fun `an opcode outside the issuance set is UNKNOWN`() {
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(0x15, true, false))
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(0, true, false))
    }

    @Test fun `the proxy can never loosen`() {
        // api=false must not turn an unlocked or unknown asset into NONE
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(null, true, false))
        assertEquals(TransferRuleState.UNKNOWN, AssetTransferRuleGate.stateOf(1, false, false))
    }

    @Test fun `screen state follows the gate and only NONE allows send`() {
        assertEquals(RuleCheckState.NONE, RuleCheckState.of(TransferRuleState.NONE))
        assertEquals(RuleCheckState.RULE_BOUND, RuleCheckState.of(TransferRuleState.RULE_BOUND))
        assertEquals(RuleCheckState.UNVERIFIED, RuleCheckState.of(TransferRuleState.UNKNOWN))
        assertTrue(RuleCheckState.NONE.allowsSend)
        assertFalse(RuleCheckState.RULE_BOUND.allowsSend)
        assertFalse(RuleCheckState.UNVERIFIED.allowsSend)
        assertFalse(RuleCheckState.CHECKING.allowsSend)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.rules.AssetTransferRuleGateTest" -q 2>&1 | tail -20`
Expected: compilation error `Unresolved reference: TransferRuleState` (BUILD FAILED).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package io.digibyte.core.asset.rules

/**
 * Whether a DigiAsset may carry transfer rules (royalty, deflation, signers, vote, KYC, expiry).
 *
 * DigiAsset Core re-checks those rules on every indexer: a transfer that breaks one has the
 * assets on EVERY output cleared and the inputs spent — the sender's whole input holding is
 * destroyed while this wallet, which builds no rule outputs, reports success. So only [NONE]
 * may move, and anything the wallet cannot prove is [UNKNOWN], not "probably fine".
 */
enum class TransferRuleState { NONE, RULE_BOUND, UNKNOWN }

/**
 * The decision, kept pure so every row of the table is a unit test.
 *
 * @param issuanceOpcode  the opcode byte of the issuance the asset walks back to, or null when
 *                        the walk has not reached one (pre-upgrade rows, unresolved chains).
 * @param issuanceLocked  the locked flag of that issuance. Only a LOCKED rule-free issuance is
 *                        final: DigiAsset Core lets a later reissuance of an unlocked aggregatable
 *                        asset attach rules to an asset first issued without them.
 * @param apiRulesPresent true when the asset proxy returned a `rules` object (Core emits the key
 *                        only when the rules are non-empty), false when it answered without one,
 *                        null when no endpoint answered. It can only tighten the verdict.
 */
object AssetTransferRuleGate {
    private val RULE_OPCODES = setOf(3, 4)
    private val RULE_FREE_OPCODES = setOf(1, 2, 5)

    fun stateOf(issuanceOpcode: Int?, issuanceLocked: Boolean?, apiRulesPresent: Boolean?): TransferRuleState {
        if (issuanceOpcode in RULE_OPCODES) return TransferRuleState.RULE_BOUND
        if (apiRulesPresent == true) return TransferRuleState.RULE_BOUND
        if (issuanceOpcode in RULE_FREE_OPCODES && issuanceLocked == true) return TransferRuleState.NONE
        return TransferRuleState.UNKNOWN
    }
}

/** What a screen shows while it resolves the state; [CHECKING] is the only transient value. */
enum class RuleCheckState {
    CHECKING, NONE, RULE_BOUND, UNVERIFIED;

    val allowsSend: Boolean get() = this == NONE

    companion object {
        fun of(state: TransferRuleState): RuleCheckState = when (state) {
            TransferRuleState.NONE -> NONE
            TransferRuleState.RULE_BOUND -> RULE_BOUND
            TransferRuleState.UNKNOWN -> UNVERIFIED
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.rules.AssetTransferRuleGateTest" -q 2>&1 | tail -20`
Expected: no output (PASS).

- [ ] **Step 5: Commit**

```bash
cd /home/polloloco/wt-ruled-asset-gate && git add core/src/main/java/io/digibyte/core/asset/rules/TransferRuleState.kt core/src/test/java/io/digibyte/core/asset/rules/AssetTransferRuleGateTest.kt && git commit -q -m "feat(assets): pure transfer-rule gate — NONE / RULE_BOUND / UNKNOWN, fail-closed

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HXtAff7AzbUR9bthQiFpg1"
```

---

### Task 2: The decoder flags rule-bearing issuances

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/asset/DigiAssetDecoder.kt:306-327` (the `DecodedAssetHeader` data class)
- Test: `core/src/test/java/io/digibyte/core/asset/DigiAssetDecoderTest.kt`

**Interfaces:**
- Produces: `DecodedAssetHeader.hasRules: Boolean` (true iff `opcode == 3 || opcode == 4`).

- [ ] **Step 1: Write the failing tests**

Add these members to `DigiAssetDecoderTest` (inside the class, after the existing vectors near line 60; the helper `hexToBytes` and the `decoder` field already exist):

```kotlin
    // Real mainnet issuance of asset 5401 "Brasa Royalty Refusal Test 003", txid
    // de8969169a38241fe520f461c06f9222934d325c640b83b40172aa3f751a00c6 (height 24,164,934):
    // DA + v3 + opcode 0x04 (immutable rules) + sha256(metadata) + amount 5 + rules block +
    // issuance flags 0x10 (div 0, locked, aggregatable). Its royalty rule is 0.1 DGB to
    // DFPBRuSBW5k9aDHTq8ixu294dhZkREUwRK. Sending it without that output destroys it.
    private val ISSUANCE_V3_RULED_5401 =
        "6a2b444103041a343da7c7a2b6d25667e63e24980cb56ddf65970bbd8315e99e97c0ec847d9e0510101f000510"

    @Test
    fun `an opcode 4 issuance reports hasRules and its chain facts`() {
        val h = decoder.decode(hexToBytes(ISSUANCE_V3_RULED_5401))
        assertNotNull(h)
        assertEquals(3, h!!.version)
        assertEquals(4, h.opcode)
        assertEquals(AssetOperation.ISSUANCE, h.operation)
        assertTrue(h.hasRules)
        assertTrue(h.locked)
        assertEquals(5L, h.totalQuantity)
        assertEquals(0, h.divisibility)
    }

    @Test
    fun `an opcode 3 issuance reports hasRules`() {
        // Same bytes with the opcode byte flipped to 0x03 (rewritable rules).
        val rewritable = ISSUANCE_V3_RULED_5401.replaceFirst("44410304", "44410303")
        val h = decoder.decode(hexToBytes(rewritable))
        assertNotNull(h)
        assertEquals(3, h!!.opcode)
        assertTrue(h.hasRules)
    }

    @Test
    fun `rule-free issuances and transfers do not report hasRules`() {
        assertFalse(decoder.decode(hexToBytes(ISSUANCE_V3_LOCKED))!!.hasRules)
        assertFalse(decoder.decode(hexToBytes(ISSUANCE_V3_UNLOCKED_DISPERSED))!!.hasRules)
        assertFalse(decoder.decode(hexToBytes(TRANSFER_V3))!!.hasRules)
        assertFalse(decoder.decode(hexToBytes(BURN_V3))!!.hasRules)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.DigiAssetDecoderTest" -q 2>&1 | tail -20`
Expected: compilation error `Unresolved reference: hasRules`.

- [ ] **Step 3: Write minimal implementation**

In `DecodedAssetHeader` (DigiAssetDecoder.kt, the data class starting `data class DecodedAssetHeader(`), add inside the class body, directly before `fun toAssetData(`:

```kotlin
    /**
     * True for issuance opcodes 0x03 (rewritable rules) and 0x04 (immutable rules) — the only
     * two that carry a rules block. The decoder does not parse that block: issuance supply is
     * credited to the first non-OP_RETURN output without reading instructions
     * (AssetTxQuantity.forOutput), so the bytes after the amount do not affect crediting; what
     * matters is that this asset must never be moved by a transfer that satisfies no rule.
     */
    val hasRules: Boolean get() = opcode == 3 || opcode == 4
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.DigiAssetDecoderTest" -q 2>&1 | tail -20`
Expected: no output (PASS). If the 5401 vector fails on `totalQuantity` or `locked`, do NOT edit the vector: the hex is the on-chain payload (verify with `git show` of the report file, section "Chain-proven vector"); investigate the decoder's issuance parsing instead and report.

- [ ] **Step 5: Commit**

```bash
cd /home/polloloco/wt-ruled-asset-gate && git add core/src/main/java/io/digibyte/core/asset/DigiAssetDecoder.kt core/src/test/java/io/digibyte/core/asset/DigiAssetDecoderTest.kt && git commit -q -m "feat(assets): decoder flags rule-bearing issuances (opcode 0x03/0x04), KAT on asset 5401

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HXtAff7AzbUR9bthQiFpg1"
```

---

### Task 3: The provenance walk keeps the issuance opcode and can be forced to the issuance

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/asset/AssetProvenanceWalker.kt` (whole file: `ResolvedAssetFacts`, `ProvenanceStore`, `resolve`, `InMemoryProvenanceStore`)
- Test: `core/src/test/java/io/digibyte/core/asset/AssetProvenanceWalkerTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `ResolvedAssetFacts(assetId, totalSupply, divisibility, metadataCid, issuanceOpcode: Int? = null, issuanceLocked: Boolean? = null)`; `ProvenanceStore.issuanceFactsFor(assetId: String): ResolvedAssetFacts?`; `AssetProvenanceWalker.resolve(startTxid: String, forceToIssuance: Boolean = false)`.

- [ ] **Step 1: Write the failing tests**

In `AssetProvenanceWalkerTest`, the `FakeStore` must implement the new interface member. Replace the `FakeStore` class with:

```kotlin
    private class FakeStore : ProvenanceStore {
        val assets = mutableMapOf<String, ResolvedAssetFacts>()
        val frontiers = mutableMapOf<String, WalkFrontier>()

        override suspend fun assetFor(txid: String) = assets[txid]
        override suspend fun putAssets(txids: List<String>, facts: ResolvedAssetFacts) {
            txids.forEach { assets[it] = facts }
        }
        override suspend fun issuanceFactsFor(assetId: String): ResolvedAssetFacts? =
            assets.values.firstOrNull { it.assetId == assetId && it.issuanceOpcode != null }
        override suspend fun frontierFor(startTxid: String) = frontiers[startTxid]
        override suspend fun putFrontier(frontier: WalkFrontier) {
            frontiers[frontier.startTxid] = frontier
        }
        override suspend fun clearFrontier(startTxid: String) { frontiers.remove(startTxid) }
    }
```

Then add these tests at the end of the class (before its closing brace):

```kotlin
    // ---- forceToIssuance: upgrading rows that predate the opcode column ---------------------

    private val ruledFacts = facts.copy(issuanceOpcode = 4, issuanceLocked = true)

    @Test fun `default mode answers from the cache without fetching`() = runTest {
        val store = FakeStore().apply { assets["tx0"] = facts }   // legacy row: no opcode
        val net = Counting(chain(3))
        val out = AssetProvenanceWalker(net.hop, store).resolve("tx0")
        assertEquals(facts, out)
        assertEquals(0, net.fetches)
    }

    @Test fun `forceToIssuance walks past a cached start txid to the issuance`() = runTest {
        val store = FakeStore().apply { assets["tx0"] = facts }   // legacy row: no opcode
        val hops = chain(3).toMutableMap()
        hops["tx3"] = AssetProvenanceWalker.Hop.Issuance(ruledFacts)
        val net = Counting(hops)

        val out = AssetProvenanceWalker(net.hop, store).resolve("tx0", forceToIssuance = true)

        assertEquals(ruledFacts, out)
        assertEquals(4, net.fetches)                       // tx0, tx1, tx2, tx3
        assertEquals(4, store.assets["tx0"]?.issuanceOpcode)
        assertEquals(4, store.assets["tx3"]?.issuanceOpcode)
        assertEquals(ruledFacts, store.issuanceFactsFor(facts.assetId))
    }

    @Test fun `forceToIssuance walks past a cached ancestor too`() = runTest {
        val store = FakeStore().apply { assets["tx2"] = facts }   // an ancestor known without opcode
        val hops = chain(3).toMutableMap()
        hops["tx3"] = AssetProvenanceWalker.Hop.Issuance(ruledFacts)
        val net = Counting(hops)

        val out = AssetProvenanceWalker(net.hop, store).resolve("tx0", forceToIssuance = true)

        assertEquals(ruledFacts, out)
        assertEquals(4, net.fetches)
        assertEquals(4, store.assets["tx2"]?.issuanceOpcode)
    }

    @Test fun `forceToIssuance still returns null and keeps a frontier when the chain is unreachable`() = runTest {
        val store = FakeStore().apply { assets["tx0"] = facts }
        val hops = chain(3).toMutableMap()
        hops.remove("tx2")                                  // Unavailable from here
        val net = Counting(hops)

        val out = AssetProvenanceWalker(net.hop, store).resolve("tx0", forceToIssuance = true)

        assertNull(out)
        assertEquals("tx2", store.frontiers["tx0"]?.resumeTxid)
        assertNull(store.issuanceFactsFor(facts.assetId))
    }

    @Test fun `issuanceFactsFor ignores rows that carry no opcode`() = runTest {
        val store = FakeStore().apply { assets["tx0"] = facts }
        assertNull(store.issuanceFactsFor(facts.assetId))
        store.assets["tx9"] = ruledFacts
        assertEquals(ruledFacts, store.issuanceFactsFor(facts.assetId))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.AssetProvenanceWalkerTest" -q 2>&1 | tail -20`
Expected: compilation errors (`issuanceOpcode`, `issuanceFactsFor`, `forceToIssuance` unresolved).

- [ ] **Step 3: Write minimal implementation**

In `AssetProvenanceWalker.kt`:

Replace `ResolvedAssetFacts` with:

```kotlin
/** On-chain facts about an asset, taken from its issuance header. */
data class ResolvedAssetFacts(
    val assetId: String,
    val totalSupply: Long,
    val divisibility: Int,
    val metadataCid: String?,
    /** The issuance's opcode byte (1..5). Null on rows written before the wallet kept it, and
     *  the reason a pre-upgrade asset reads as "rules unknown" until re-walked. */
    val issuanceOpcode: Int? = null,
    /** The issuance's locked flag. Null when [issuanceOpcode] is. */
    val issuanceLocked: Boolean? = null,
)
```

Add to the `ProvenanceStore` interface, after `putAssets`:

```kotlin
    /** Facts for [assetId] from any row that recorded the issuance opcode, or null when no
     *  walk has reached that asset's issuance since the wallet started keeping it. */
    suspend fun issuanceFactsFor(assetId: String): ResolvedAssetFacts?
```

Replace the `resolve` function signature and the two cache short-circuits so the function reads:

```kotlin
    /**
     * @param forceToIssuance ignore what the store already knows about the txids on the path and
     *   walk until the issuance is reached, then memoise the whole path with what it says. This is
     *   how a row written before the wallet kept the issuance opcode learns it. Frontier resume is
     *   honoured either way — a frontier only exists for a walk that never finished.
     */
    suspend fun resolve(startTxid: String, forceToIssuance: Boolean = false): ResolvedAssetFacts? {
        if (!forceToIssuance) store.assetFor(startTxid)?.let { return it }

        // Pick up where the last attempt stopped rather than starting over. This is the whole
        // fix: without it a chain longer than one budget is unreachable no matter how often the
        // walk runs, and each run pays full price to learn nothing.
        val resumed = store.frontierFor(startTxid)
        var current = resumed?.resumeTxid ?: startTxid
        val priorHops = resumed?.hopsWalked ?: 0

        // Everything on this attempt's path shares whatever asset we end up finding, so they all
        // get memoised together — not just the txid we were asked about.
        val proven = linkedSetOf(startTxid)
        val seen = mutableSetOf<String>()

        for (step in 0 until maxHopsPerAttempt) {
            if (!seen.add(current)) {
                // A chain that loops will loop again next time; keeping a resume point would
                // just schedule the same dead end forever.
                store.clearFrontier(startTxid)
                return null
            }

            // An ancestor we already resolved answers the whole question — this is what makes
            // receiving back an asset we previously sent cost one hop instead of the chain.
            if (!forceToIssuance) {
                store.assetFor(current)?.let { known ->
                    store.putAssets(proven.toList(), known)
                    store.clearFrontier(startTxid)
                    return known
                }
            }

            when (val step2 = hop(current)) {
                is Hop.Issuance -> {
                    store.putAssets((proven + current).toList(), step2.facts)
                    store.clearFrontier(startTxid)
                    return step2.facts
                }
                is Hop.Transfer -> {
                    proven.add(current)
                    current = step2.parentTxid
                }
                Hop.Unavailable -> {
                    // Weather, not a verdict. Keep the ground already covered.
                    store.putFrontier(WalkFrontier(startTxid, current, priorHops + step))
                    return null
                }
                Hop.DeadEnd -> {
                    store.clearFrontier(startTxid)
                    return null
                }
            }
        }

        store.putFrontier(WalkFrontier(startTxid, current, priorHops + maxHopsPerAttempt))
        return null
    }
```

Add to `InMemoryProvenanceStore`, after `putAssets`:

```kotlin
    override suspend fun issuanceFactsFor(assetId: String): ResolvedAssetFacts? =
        assets.values.firstOrNull { it.assetId == assetId && it.issuanceOpcode != null }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.AssetProvenanceWalkerTest" -q 2>&1 | tail -20`
Expected: no output (PASS). `RoomProvenanceStore` will not compile yet; that is Task 4. If the whole module fails to compile because of it, add this temporary override to `RoomProvenanceStore` now and replace it in Task 4: `override suspend fun issuanceFactsFor(assetId: String): ResolvedAssetFacts? = null`.

- [ ] **Step 5: Commit**

```bash
cd /home/polloloco/wt-ruled-asset-gate && git add core/src/main/java/io/digibyte/core/asset/AssetProvenanceWalker.kt core/src/main/java/io/digibyte/core/asset/RoomProvenanceStore.kt core/src/test/java/io/digibyte/core/asset/AssetProvenanceWalkerTest.kt && git commit -q -m "feat(assets): provenance facts carry the issuance opcode; walk can be forced to the issuance

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HXtAff7AzbUR9bthQiFpg1"
```

---

### Task 4: Persist the opcode (Room entity, DAO, migration 10→11, store mapping)

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/db/entity/AssetProvenanceEntity.kt:13-20`
- Modify: `core/src/main/java/io/digibyte/core/db/dao/AssetProvenanceDao.kt`
- Create: `core/src/main/java/io/digibyte/core/db/Migration_10_11.kt`
- Modify: `core/src/main/java/io/digibyte/core/db/WalletDatabase.kt:25` (version) and `:58` (addMigrations)
- Modify: `core/src/main/java/io/digibyte/core/asset/RoomProvenanceStore.kt`
- Add generated: `core/schemas/io.digibyte.core.db.WalletDatabase/11.json`
- Test: `core/src/test/java/io/digibyte/core/db/Migration_10_11Test.kt`

**Interfaces:**
- Consumes: `ResolvedAssetFacts.issuanceOpcode/issuanceLocked`, `ProvenanceStore.issuanceFactsFor` (Task 3).
- Produces: `AssetProvenanceEntity.issuanceOpcode: Int?`, `AssetProvenanceEntity.issuanceLocked: Boolean?`; `AssetProvenanceDao.issuanceFactsFor(assetId: String): AssetProvenanceEntity?`; `MIGRATION_10_11`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.digibyte.core.db

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provenance table learns the issuance opcode and locked flag so the transfer-rule gate
 * has a chain-proven signal (spec §3). Same guarded ALTER pattern as 9→10: SQLite has no
 * ADD COLUMN IF NOT EXISTS and destructive fallback is on, so the columns may already exist.
 */
class Migration_10_11Test {

    @Test fun versions_are_10_to_11() {
        assertEquals(10, MIGRATION_10_11.startVersion)
        assertEquals(11, MIGRATION_10_11.endVersion)
    }

    private fun dbWithColumns(vararg names: String): Pair<SupportSQLiteDatabase, MutableList<String>> {
        val cursor = mockk<Cursor>()
        val moves = names.map { true } + false
        every { cursor.getColumnIndexOrThrow("name") } returns 1
        every { cursor.moveToNext() } returnsMany moves
        every { cursor.getString(1) } returnsMany names.toList()
        every { cursor.close() } just runs
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { db.query("PRAGMA table_info(asset_provenance)") } returns cursor
        val sql = mutableListOf<String>()
        every { db.execSQL(capture(sql)) } just runs
        return db to sql
    }

    @Test fun adds_both_columns_when_absent() {
        val (db, sql) = dbWithColumns("txid", "assetId", "totalSupply", "divisibility", "metadataCid")
        MIGRATION_10_11.migrate(db)
        assertEquals(2, sql.size)
        assertTrue(sql[0].contains("ALTER TABLE asset_provenance ADD COLUMN issuanceOpcode INTEGER"))
        assertTrue(sql[1].contains("ALTER TABLE asset_provenance ADD COLUMN issuanceLocked INTEGER"))
    }

    @Test fun is_a_no_op_when_both_columns_exist() {
        val (db, sql) = dbWithColumns("txid", "issuanceOpcode", "issuanceLocked")
        MIGRATION_10_11.migrate(db)
        assertEquals(0, sql.size)
        verify { db.query("PRAGMA table_info(asset_provenance)") }
    }

    @Test fun adds_only_the_missing_column() {
        val (db, sql) = dbWithColumns("txid", "issuanceOpcode")
        MIGRATION_10_11.migrate(db)
        assertEquals(listOf("ALTER TABLE asset_provenance ADD COLUMN issuanceLocked INTEGER"), sql)
    }
}
```

- [ ] **Step 1b: Write the failing store round-trip test**

`core/src/test/java/io/digibyte/core/asset/RoomProvenanceStoreTest.kt`:

```kotlin
package io.digibyte.core.asset

import io.digibyte.core.db.dao.AssetProvenanceDao
import io.digibyte.core.db.entity.AssetProvenanceEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Room store must carry the issuance opcode both ways, and a legacy row must read as null. */
class RoomProvenanceStoreTest {

    private val dao = mockk<AssetProvenanceDao>(relaxed = true)
    private val store = RoomProvenanceStore(dao, nowMillis = { 1L })

    private val ruled = ResolvedAssetFacts(
        assetId = "LaRuled", totalSupply = 5, divisibility = 0, metadataCid = "bafk",
        issuanceOpcode = 4, issuanceLocked = true,
    )

    @Test fun `putAssets writes the opcode and locked flag on every row`() = runBlocking {
        val rows = slot<List<AssetProvenanceEntity>>()
        coEvery { dao.putProvenance(capture(rows)) } returns Unit

        store.putAssets(listOf("tx0", "tx1"), ruled)

        assertEquals(2, rows.captured.size)
        rows.captured.forEach {
            assertEquals(4, it.issuanceOpcode)
            assertEquals(true, it.issuanceLocked)
            assertEquals("LaRuled", it.assetId)
        }
    }

    @Test fun `assetFor maps the columns back`() = runBlocking {
        coEvery { dao.provenanceFor("tx0") } returns AssetProvenanceEntity(
            txid = "tx0", assetId = "LaRuled", totalSupply = 5, divisibility = 0,
            metadataCid = "bafk", issuanceOpcode = 4, issuanceLocked = true,
        )
        assertEquals(ruled, store.assetFor("tx0"))
    }

    @Test fun `a legacy row reads as opcode null`() = runBlocking {
        coEvery { dao.provenanceFor("old") } returns AssetProvenanceEntity(
            txid = "old", assetId = "LaRuled", totalSupply = 5, divisibility = 0, metadataCid = null,
        )
        val facts = store.assetFor("old")!!
        assertNull(facts.issuanceOpcode)
        assertNull(facts.issuanceLocked)
    }

    @Test fun `issuanceFactsFor delegates to the opcode-aware query`() = runBlocking {
        coEvery { dao.issuanceFactsFor("LaRuled") } returns AssetProvenanceEntity(
            txid = "tx9", assetId = "LaRuled", totalSupply = 5, divisibility = 0,
            metadataCid = "bafk", issuanceOpcode = 4, issuanceLocked = true,
        )
        assertEquals(ruled, store.issuanceFactsFor("LaRuled"))
        coVerify { dao.issuanceFactsFor("LaRuled") }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.db.Migration_10_11Test" -q 2>&1 | tail -20`
Expected: compilation error `Unresolved reference: MIGRATION_10_11`.

- [ ] **Step 3: Write the implementation**

`core/src/main/java/io/digibyte/core/db/Migration_10_11.kt`:

```kotlin
package io.digibyte.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Transfer-rule gate (docs/superpowers/specs/2026-09-06-ruled-asset-transfer-gate-design.md §3):
 * the provenance cache learns which issuance opcode an asset walks back to, and whether that
 * issuance was locked, so a send can be refused for an asset that may carry rules.
 *
 * Existing rows get NULL in both columns, which the gate reads as "unknown" — refused until the
 * screen re-walks the asset to its issuance. Additive and guarded, like 9→10: SQLite has no
 * `ADD COLUMN IF NOT EXISTS`, and `fallbackToDestructiveMigration()` is on.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val existing = HashSet<String>()
        db.query("PRAGMA table_info(asset_provenance)").use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) existing.add(c.getString(nameIdx))
        }
        if ("issuanceOpcode" !in existing) {
            db.execSQL("ALTER TABLE asset_provenance ADD COLUMN issuanceOpcode INTEGER")
        }
        if ("issuanceLocked" !in existing) {
            db.execSQL("ALTER TABLE asset_provenance ADD COLUMN issuanceLocked INTEGER")
        }
    }
}
```

`AssetProvenanceEntity` — replace the data class with:

```kotlin
@Entity(tableName = "asset_provenance")
data class AssetProvenanceEntity(
    @PrimaryKey val txid: String,
    val assetId: String,
    val totalSupply: Long,
    val divisibility: Int,
    val metadataCid: String?,
    /** Opcode byte of the issuance this path walks back to; NULL on rows from before v4.0.79. */
    val issuanceOpcode: Int? = null,
    /** Locked flag of that issuance; NULL when [issuanceOpcode] is. */
    val issuanceLocked: Boolean? = null,
)
```

`AssetProvenanceDao` — add after `putProvenance`:

```kotlin
    /** Any row for [assetId] that recorded the issuance opcode. All rows on one walked path share
     *  the same facts, so the first is as good as any; rows without the opcode are pre-upgrade. */
    @Query("SELECT * FROM asset_provenance WHERE assetId = :assetId AND issuanceOpcode IS NOT NULL LIMIT 1")
    suspend fun issuanceFactsFor(assetId: String): AssetProvenanceEntity?
```

`WalletDatabase.kt` — change `version = 10,` to `version = 11,` and the migrations line to:

```kotlin
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
```

`RoomProvenanceStore.kt` — replace `assetFor`, `putAssets` and add `issuanceFactsFor` (remove any temporary override from Task 3):

```kotlin
    override suspend fun assetFor(txid: String): ResolvedAssetFacts? =
        dao.provenanceFor(txid)?.toFacts()

    override suspend fun putAssets(txids: List<String>, facts: ResolvedAssetFacts) {
        if (txids.isEmpty()) return
        dao.putProvenance(
            txids.distinct().map {
                AssetProvenanceEntity(
                    txid = it,
                    assetId = facts.assetId,
                    totalSupply = facts.totalSupply,
                    divisibility = facts.divisibility,
                    metadataCid = facts.metadataCid,
                    issuanceOpcode = facts.issuanceOpcode,
                    issuanceLocked = facts.issuanceLocked,
                )
            }
        )
    }

    override suspend fun issuanceFactsFor(assetId: String): ResolvedAssetFacts? =
        dao.issuanceFactsFor(assetId)?.toFacts()

    private fun AssetProvenanceEntity.toFacts() = ResolvedAssetFacts(
        assetId, totalSupply, divisibility, metadataCid, issuanceOpcode, issuanceLocked,
    )
```

- [ ] **Step 4: Run the test, export the schema, verify**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.db.Migration_10_11Test" --tests "io.digibyte.core.asset.RoomProvenanceStoreTest" -q 2>&1 | tail -20 && ls core/schemas/io.digibyte.core.db.WalletDatabase/11.json`
Expected: no test output (PASS) and the `11.json` path printed (the annotation processor exports it when core compiles). Then confirm the new columns are in the exported schema: `grep -c 'issuanceOpcode' core/schemas/io.digibyte.core.db.WalletDatabase/11.json` prints a number ≥ 1.

- [ ] **Step 5: Commit**

```bash
cd /home/polloloco/wt-ruled-asset-gate && git add core/src/main/java/io/digibyte/core/db/Migration_10_11.kt core/src/main/java/io/digibyte/core/db/entity/AssetProvenanceEntity.kt core/src/main/java/io/digibyte/core/db/dao/AssetProvenanceDao.kt core/src/main/java/io/digibyte/core/db/WalletDatabase.kt core/src/main/java/io/digibyte/core/asset/RoomProvenanceStore.kt core/schemas/io.digibyte.core.db.WalletDatabase/11.json core/src/test/java/io/digibyte/core/db/Migration_10_11Test.kt core/src/test/java/io/digibyte/core/asset/RoomProvenanceStoreTest.kt && git commit -q -m "feat(db): asset_provenance keeps issuanceOpcode + issuanceLocked (migration 10→11)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HXtAff7AzbUR9bthQiFpg1"
```

---

### Task 5: The proxy's rules object is parsed and persisted

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/asset/network/AssetNetworkClient.kt:61-71` (`AssetDataResponse`)
- Modify: `core/src/main/java/io/digibyte/core/asset/network/DigiScopeAssetParsing.kt`
- Modify: `core/src/main/java/io/digibyte/core/db/dao/AssetMetadataDao.kt`
- Modify: `core/src/main/java/io/digibyte/core/ipfs/AssetMetadataService.kt` (new method after `getMetadata`)
- Test: `core/src/test/java/io/digibyte/core/asset/network/DigiScopeAssetParsingTest.kt`

**Interfaces:**
- Produces: `AssetDataResponse.rules: Map<String, Any?>? = null`; `AssetMetadataDao.updateRulesJson(assetId: String, rulesJson: String?)`; `AssetMetadataDao.rulesJsonFor(assetId: String): String?`; `AssetMetadataService.refreshRules(assetId: String): Boolean?` (true = proxy returned a non-empty rules object, false = proxy answered without one, null = no endpoint answered). Persists `"{}"` for false so "answered, none" is distinguishable from "never asked" (null column).

- [ ] **Step 1: Write the failing tests**

Add to `DigiScopeAssetParsingTest`:

```kotlin
    /** Captured from api.digiscope.me/api/digiassets/asset/5401 on 2026-09-06 (trimmed). */
    @Test
    fun `asset data keeps the rules object when the proxy sends one`() {
        val json = JSONObject(
            """{"assetId":"La8UJyF13C2VG1EbxvkWmDf3gu5y2W4Worg9rn","cid":"bafkreia2gq62","count":5,"decimals":0,
                "rules":{"changeable":false,"royalty":{"addresses":{"DFPBRuSBW5k9aDHTq8ixu294dhZkREUwRK":10000000}}}}"""
        )

        val data = DigiScopeAssetParsing.assetData(json, fallbackAssetId = "ignored")!!

        assertEquals(setOf("changeable", "royalty"), data.rules!!.keys)
        assertEquals(false, data.rules!!["changeable"])
    }

    @Test
    fun `asset data has null rules when the proxy sends none`() {
        val json = JSONObject("""{"assetId":"La3t7Jdv","cid":"bafy","count":10,"decimals":0}""")
        assertNull(DigiScopeAssetParsing.assetData(json, fallbackAssetId = "ignored")!!.rules)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.network.DigiScopeAssetParsingTest" -q 2>&1 | tail -20`
Expected: compilation error `Unresolved reference: rules`.

- [ ] **Step 3: Write the implementation**

`AssetDataResponse` — add a last field:

```kotlin
data class AssetDataResponse(
    val assetId: String,
    val cid: String?,
    val issuer: String?,
    val count: Long,
    val decimals: Int,
    /** Raw IPFS-resolved metadata JSON object. Null when `excludeIPFS=true`
     *  or the CID isn't pinned. Kept as a nullable map rather than parsed
     *  eagerly so new fields surface automatically. */
    val ipfs: Map<String, Any?>?,
    /** DigiAsset Core's `rules` object (royalty/deflation/expiry/geofence/vote/signers/…),
     *  which it emits only when the asset actually has rules. Null when absent from the
     *  response. Consumed by the transfer-rule gate as a signal that can only tighten. */
    val rules: Map<String, Any?>? = null,
)
```

`DigiScopeAssetParsing.assetData` — replace the body with:

```kotlin
    fun assetData(json: JSONObject, fallbackAssetId: String): AssetDataResponse? {
        if (json.has("error")) return null
        return AssetDataResponse(
            assetId = json.optString("assetId", fallbackAssetId),
            cid = json.optString("cid").takeIf { it.isNotEmpty() },
            issuer = json.optString("issuer").takeIf { it.isNotEmpty() },
            count = json.optLong("count", 0L),
            decimals = json.optInt("decimals", 0),
            ipfs = null,
            rules = json.optJSONObject("rules")?.let { toMap(it) },
        )
    }

    private fun toMap(obj: JSONObject): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.opt(k)
        }
        return out
    }
```

`AssetMetadataDao` — add after `insertChainFacts`:

```kotlin
    /** The proxy's rules object as JSON text; `"{}"` when the proxy answered without one, NULL
     *  when it was never learned. Written by AssetMetadataService.refreshRules only. */
    @Query("UPDATE asset_metadata SET rulesJson = :rulesJson WHERE assetId = :assetId")
    suspend fun updateRulesJson(assetId: String, rulesJson: String?)

    @Query("SELECT rulesJson FROM asset_metadata WHERE assetId = :assetId")
    suspend fun rulesJsonFor(assetId: String): String?
```

`AssetMetadataService` — add after the closing brace of `getMetadata` (before `private suspend fun storeFromJson`):

```kotlin
    /**
     * Ask the asset proxy whether [assetId] carries transfer rules and remember the answer.
     *
     * @return true when the proxy returned a non-empty `rules` object, false when it answered
     *   without one, null when no endpoint answered. The row is created if missing so the answer
     *   has somewhere to live; `"{}"` is stored for "answered, none" so a later reader can tell it
     *   from "never asked" (NULL). Never throws: the gate treats null as unknown.
     */
    suspend fun refreshRules(assetId: String): Boolean? {
        val client = assetNetworkClient ?: return null
        val remote = runCatching { client.getAssetData(assetId) }.getOrNull() ?: return null
        val present = !remote.rules.isNullOrEmpty()
        val json = if (present) JSONObject(remote.rules!!).toString() else "{}"
        runCatching {
            assetMetadataDao.insertChainFacts(AssetMetadataEntity(assetId = assetId))
            assetMetadataDao.updateRulesJson(assetId, json)
        }
        return present
    }
```

(`AssetMetadataEntity` and `JSONObject` are already imported in that file; if `AssetMetadataEntity` is not, add `import io.digibyte.core.db.entity.AssetMetadataEntity`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.network.*" -q 2>&1 | tail -20`
Expected: no output (PASS).

- [ ] **Step 5: Commit**

```bash
cd /home/polloloco/wt-ruled-asset-gate && git add core/src/main/java/io/digibyte/core/asset/network/AssetNetworkClient.kt core/src/main/java/io/digibyte/core/asset/network/DigiScopeAssetParsing.kt core/src/main/java/io/digibyte/core/db/dao/AssetMetadataDao.kt core/src/main/java/io/digibyte/core/ipfs/AssetMetadataService.kt core/src/test/java/io/digibyte/core/asset/network/DigiScopeAssetParsingTest.kt && git commit -q -m "feat(assets): keep the proxy's rules object; AssetMetadataService.refreshRules persists it

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HXtAff7AzbUR9bthQiFpg1"
```

---

### Task 6: `AssetManager` composes the state and `sendAsset` refuses

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/TransactionBuilder.kt:9-12` (`TxResult`)
- Modify: `core/src/main/java/io/digibyte/core/model/AssetData.kt:42-47` (`OwnedAsset`)
- Modify: `core/src/main/java/io/digibyte/core/asset/AssetManager.kt` — `classifyProvenanceHop` (~841-882), `getOwnedAssets` (~216-233), `sendAsset` (~1382-1390), plus three new methods
- Modify: `app/src/main/java/io/digibyte/ui/wallet/SendViewModel.kt:242-251` (exhaustive `when`)
- Test: `core/src/test/java/io/digibyte/core/asset/AssetSendRuleGateTest.kt`

**Interfaces:**
- Consumes: `AssetTransferRuleGate`, `TransferRuleState` (Task 1); `ResolvedAssetFacts.issuanceOpcode/issuanceLocked`, `resolve(forceToIssuance)`, `ProvenanceStore.issuanceFactsFor` (Task 3); `AssetMetadataDao.rulesJsonFor`, `AssetMetadataService.refreshRules` (Task 5).
- Produces: `TxResult.Refused(val reason: SendRefusal)`; `enum class SendRefusal { RULE_BOUND_ASSET, RULES_UNKNOWN }` (package `io.digibyte.core`); `OwnedAsset.transferRules: TransferRuleState = TransferRuleState.UNKNOWN`; `AssetManager.transferRuleState(assetId): TransferRuleState`; `AssetManager.verifyTransferRulesForTx(txid): TransferRuleState`; `AssetManager.verifyTransferRules(assetId): TransferRuleState`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.digibyte.core.asset

import io.digibyte.core.SendRefusal
import io.digibyte.core.TxResult
import io.digibyte.core.asset.rules.TransferRuleState
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.ipfs.AssetMetadataService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The send path refuses BEFORE it reads a UTXO or touches the native bridge. Only refusals are
 * testable on the JVM (an allowed send goes straight into NativeBridge, which has no library
 * here) — and refusals are the whole point: on the unfixed code these tests die in
 * NativeBridge's static initialiser, which is the red this fix turns green.
 */
class AssetSendRuleGateTest {

    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val txDao = mockk<TransactionDao>(relaxed = true)
    private val metaDao = mockk<AssetMetadataDao>(relaxed = true)
    private val metaService = mockk<AssetMetadataService>(relaxed = true)
    private val store = InMemoryProvenanceStore()

    private fun manager() = AssetManager(
        utxoDao = utxoDao, transactionDao = txDao, metadataDao = metaDao,
        metadataService = metaService, provenanceStore = store,
    )

    private fun facts(opcode: Int?, locked: Boolean?) = ResolvedAssetFacts(
        assetId = "LaRuled", totalSupply = 5, divisibility = 0, metadataCid = null,
        issuanceOpcode = opcode, issuanceLocked = locked,
    )

    @Test fun `a rule-bound asset is refused before any UTXO is read`() = runBlocking {
        store.putAssets(listOf("iss"), facts(4, true))
        coEvery { metaDao.rulesJsonFor("LaRuled") } returns null

        val r = manager().sendAsset("LaRuled", 1L, "dgb1qanything", 100_000L)

        assertEquals(TxResult.Refused(SendRefusal.RULE_BOUND_ASSET), r)
        coVerify(exactly = 0) { utxoDao.getAssetUtxosByIdNow(any()) }
    }

    @Test fun `an asset whose issuance was never walked is refused as unknown`() = runBlocking {
        coEvery { metaDao.rulesJsonFor("LaRuled") } returns null

        val r = manager().sendAsset("LaRuled", 1L, "dgb1qanything", 100_000L)

        assertEquals(TxResult.Refused(SendRefusal.RULES_UNKNOWN), r)
    }

    @Test fun `the proxy's rules object refuses even a rule-free locked issuance`() = runBlocking {
        store.putAssets(listOf("iss"), facts(1, true))
        coEvery { metaDao.rulesJsonFor("LaRuled") } returns """{"royalty":{"addresses":{"D1":1}}}"""

        val r = manager().sendAsset("LaRuled", 1L, "dgb1qanything", 100_000L)

        assertEquals(TxResult.Refused(SendRefusal.RULE_BOUND_ASSET), r)
    }

    @Test fun `transferRuleState reads the two stores and nothing else`() = runBlocking {
        store.putAssets(listOf("iss"), facts(1, true))
        coEvery { metaDao.rulesJsonFor("LaRuled") } returns "{}"
        assertEquals(TransferRuleState.NONE, manager().transferRuleState("LaRuled"))

        coEvery { metaDao.rulesJsonFor("LaRuled") } returns """{"changeable":false}"""
        assertEquals(TransferRuleState.RULE_BOUND, manager().transferRuleState("LaRuled"))
        coVerify(exactly = 0) { metaService.refreshRules(any()) }
    }

    @Test fun `verifyTransferRules refuses as unknown when the asset has no held UTXO to walk from`() = runBlocking {
        coEvery { utxoDao.getAssetUtxosByIdNow("LaRuled") } returns emptyList()
        assertEquals(TransferRuleState.UNKNOWN, manager().verifyTransferRules("LaRuled"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.AssetSendRuleGateTest" -q 2>&1 | tail -20`
Expected: compilation errors (`SendRefusal`, `TxResult.Refused`, `transferRuleState` unresolved).

- [ ] **Step 3: Write the implementation**

`TransactionBuilder.kt` — replace the `TxResult` sealed class:

```kotlin
/** Why a spend-class action was refused before anything was built or signed. */
enum class SendRefusal {
    /** The asset carries transfer rules this wallet cannot satisfy; sending would destroy it. */
    RULE_BOUND_ASSET,
    /** The asset's rules could not be established; refused rather than guessed. */
    RULES_UNKNOWN,
}

sealed class TxResult {
    data class Success(val txid: String) : TxResult()
    data class Error(val message: String) : TxResult()
    /** Refused by policy, not by failure: nothing was selected, built, signed or broadcast.
     *  Typed so the screen can say why in the user's language. */
    data class Refused(val reason: SendRefusal) : TxResult()
}
```

`AssetData.kt` — replace `OwnedAsset`:

```kotlin
data class OwnedAsset(
    val assetId: String,
    val quantity: Long,
    val metadata: AssetMetadata?,
    val utxoCount: Int,
    /** Whether this asset may carry transfer rules. Defaults to UNKNOWN — the fail-closed
     *  value — so a caller that forgets to fill it cannot enable Send by accident. */
    val transferRules: io.digibyte.core.asset.rules.TransferRuleState =
        io.digibyte.core.asset.rules.TransferRuleState.UNKNOWN,
)
```

`AssetManager.kt`:

(a) Add imports near the top:

```kotlin
import io.digibyte.core.SendRefusal
import io.digibyte.core.asset.rules.AssetTransferRuleGate
import io.digibyte.core.asset.rules.TransferRuleState
```

(b) In `classifyProvenanceHop`, replace the `ResolvedAssetFacts(` construction (the one that reads `assetId = derived,`) with:

```kotlin
                AssetProvenanceWalker.Hop.Issuance(
                    ResolvedAssetFacts(
                        assetId = derived,
                        totalSupply = header.totalQuantity ?: 0L,
                        divisibility = header.divisibility,
                        metadataCid = header.metadataCid,
                        issuanceOpcode = header.opcode,
                        issuanceLocked = header.locked,
                    )
                )
```

(c) Add these three methods directly after the `provenanceWalker` lazy property:

```kotlin
    // ── Transfer-rule gate (spec 2026-09-06-ruled-asset-transfer-gate-design §5) ─────────────

    /**
     * What the wallet already knows, without touching the network: the issuance opcode the
     * provenance walk recorded and the rules object the proxy last reported. UNKNOWN until both
     * a walk has reached this asset's issuance since the column existed and, for unlocked
     * assets, forever — a reissuance can still add rules to those.
     */
    suspend fun transferRuleState(assetId: String): TransferRuleState {
        val facts = provenanceStore.issuanceFactsFor(assetId)
        val apiRulesPresent = metadataDao.rulesJsonFor(assetId)?.let { json ->
            runCatching { org.json.JSONObject(json).length() > 0 }.getOrNull()
        }
        return AssetTransferRuleGate.stateOf(facts?.issuanceOpcode, facts?.issuanceLocked, apiRulesPresent)
    }

    /**
     * Establish the state for the asset created by the transaction [txid] belongs to: walk to
     * the issuance ignoring the cache (so pre-upgrade rows learn the opcode), then ask the proxy.
     * Used by the screens when [transferRuleState] is UNKNOWN and by the recovery classifier,
     * whose outpoints are unnamed until walked. UNKNOWN when the walk cannot reach an issuance.
     */
    suspend fun verifyTransferRulesForTx(txid: String): TransferRuleState {
        val facts = runCatching { provenanceWalker.resolve(txid, forceToIssuance = true) }.getOrNull()
            ?: return TransferRuleState.UNKNOWN
        val apiRulesPresent = runCatching { metadataService.refreshRules(facts.assetId) }.getOrNull()
        return AssetTransferRuleGate.stateOf(facts.issuanceOpcode, facts.issuanceLocked, apiRulesPresent)
    }

    /** [verifyTransferRulesForTx] from one of the asset's held outputs. */
    suspend fun verifyTransferRules(assetId: String): TransferRuleState {
        val utxo = utxoDao.getAssetUtxosByIdNow(assetId).firstOrNull()
            ?: return TransferRuleState.UNKNOWN
        return verifyTransferRulesForTx(utxo.txid)
    }
```

(d) In `getOwnedAssets`, add `transferRules = transferRuleState(assetId),` as the last argument of the `OwnedAsset(` constructor call (after `utxoCount = utxoCount`).

(e) In `sendAsset`, insert as the FIRST statements of the function body (before `if (!NativeBridge.isValidAddress(toAddress))`):

```kotlin
        // Refuse before reading a UTXO or touching native. DigiAsset Core clears every output of
        // a transfer that breaks a rule; this wallet builds no rule outputs, so a rule-bearing
        // asset must never leave through here, and "don't know" is not "no rules".
        when (transferRuleState(assetId)) {
            TransferRuleState.RULE_BOUND -> return TxResult.Refused(SendRefusal.RULE_BOUND_ASSET)
            TransferRuleState.UNKNOWN -> return TxResult.Refused(SendRefusal.RULES_UNKNOWN)
            TransferRuleState.NONE -> Unit
        }
```

`SendViewModel.kt` — add a branch to the `when (result)` so it reads:

```kotlin
                _sendState.value = when (result) {
                    is TxResult.Success -> {
                        android.util.Log.i(TAG, "send accepted: ${result.txid}")
                        SendState.Success(result.txid)
                    }
                    is TxResult.Error -> {
                        android.util.Log.w(TAG, "send refused: ${result.message}")
                        SendState.Error(result.message)
                    }
                    is TxResult.Refused -> {
                        // Unreachable for a plain DGB send today; typed refusals are asset-only.
                        android.util.Log.w(TAG, "send refused by policy: ${result.reason}")
                        SendState.Error(result.reason.name)
                    }
                }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.asset.AssetSendRuleGateTest" -q 2>&1 | tail -20 && ./gradlew :app:compileMainnetDebugKotlin -q 2>&1 | tail -10`
Expected: no test output (PASS); app compiles (the `AssetViewModel` `when` over `TxResult` will now fail to compile because it is not exhaustive — if so, add `is TxResult.Refused -> SendState.Failure(result.reason.name)` there TEMPORARILY; Task 8 replaces it with the typed `SendState.Refused`).

- [ ] **Step 5: Commit**

```bash
cd /home/polloloco/wt-ruled-asset-gate && git add core/src/main/java/io/digibyte/core/TransactionBuilder.kt core/src/main/java/io/digibyte/core/model/AssetData.kt core/src/main/java/io/digibyte/core/asset/AssetManager.kt app/src/main/java/io/digibyte/ui/wallet/SendViewModel.kt app/src/main/java/io/digibyte/ui/asset/AssetViewModel.kt core/src/test/java/io/digibyte/core/asset/AssetSendRuleGateTest.kt && git commit -q -m "fix(assets): sendAsset refuses rule-bound or unverified assets before touching a UTXO

The wallet built a plain transfer for any asset; DigiAsset Core clears every
output of a transfer that breaks a rule, destroying the sender's whole input
holding while the wallet reported success. Report:
security/reports/bounty/2026-09-06-ruled-asset-send-burns-holding.md

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HXtAff7AzbUR9bthQiFpg1"
```

---

### Task 7: The recovery path refuses the same way

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/recovery/ForeignUtxoAssetClassifier.kt`
- Modify: `core/src/main/java/io/digibyte/core/recovery/ForeignAssetTransferService.kt` (`Move`, the `for (utxo in partition.assetBearing)` loop, plus a top-level enum)
- Modify: `app/src/main/java/io/digibyte/ui/recovery/RecoverFundsViewModel.kt:41-47` (constructor) and `:106-109` (classifier)
- Test: `core/src/test/java/io/digibyte/core/recovery/ForeignUtxoAssetClassifierTest.kt`, `core/src/test/java/io/digibyte/core/recovery/ForeignAssetTransferServiceTest.kt`

**Interfaces:**
- Consumes: `TransferRuleState` (Task 1), `AssetManager.verifyTransferRulesForTx` (Task 6).
- Produces: `ForeignUtxoAssetClassifier(fetchRawTx, isAssetTx, resolveRuleState: suspend (txid: String) -> TransferRuleState = { TransferRuleState.UNKNOWN })`; `Verdict(classified, carriesAsset, ruleState: TransferRuleState? = null)`; `enum class MoveRefusal { RULE_BOUND, RULES_UNKNOWN }` (package `io.digibyte.core.recovery`); `ForeignAssetTransferService.Move.refusal: MoveRefusal? = null`.

- [ ] **Step 1: Write the failing tests**

In `ForeignUtxoAssetClassifierTest`, change the helper to accept a resolver and add two tests:

```kotlin
    private fun classifier(
        fetch: suspend (String) -> ByteArray? = { plainTx },
        isAsset: (ByteArray) -> Boolean = { it.contentEquals(assetTx) },
        resolve: suspend (String) -> io.digibyte.core.asset.rules.TransferRuleState =
            { io.digibyte.core.asset.rules.TransferRuleState.NONE },
    ) = ForeignUtxoAssetClassifier(fetch, isAsset, resolve)

    @Test fun `an asset transaction carries the resolved rule state`() = runBlocking {
        val u = utxo("cccc")
        val v = classifier(
            fetch = { assetTx },
            resolve = { io.digibyte.core.asset.rules.TransferRuleState.RULE_BOUND },
        ).classify(listOf(u))[u]!!
        assertTrue(v.carriesAsset)
        assertEquals(io.digibyte.core.asset.rules.TransferRuleState.RULE_BOUND, v.ruleState)
    }

    @Test fun `a resolver that throws yields UNKNOWN rule state, not a crash and not NONE`() = runBlocking {
        val u = utxo("dddd")
        val v = classifier(fetch = { assetTx }, resolve = { error("proxy down") }).classify(listOf(u))[u]!!
        assertTrue(v.carriesAsset)
        assertEquals(io.digibyte.core.asset.rules.TransferRuleState.UNKNOWN, v.ruleState)
    }

    @Test fun `without a resolver the default rule state is UNKNOWN`() = runBlocking {
        val u = utxo("eeee")
        val v = ForeignUtxoAssetClassifier({ assetTx }, { true }).classify(listOf(u))[u]!!
        assertEquals(io.digibyte.core.asset.rules.TransferRuleState.UNKNOWN, v.ruleState)
    }
```

In `ForeignAssetTransferServiceTest`, change `classifier()` so existing happy paths keep moving, and add the refusal tests:

```kotlin
    /** Only the asset UTXO's parent carries a marker; the fee UTXO's parent is plain. */
    private fun classifier(
        ruleState: io.digibyte.core.asset.rules.TransferRuleState =
            io.digibyte.core.asset.rules.TransferRuleState.NONE,
    ) = ForeignUtxoAssetClassifier(
        fetchRawTx = { txid -> if (txid == "a55e7") byteArrayOf(1) else byteArrayOf(2) },
        isAssetTx = { it.contentEquals(byteArrayOf(1)) },
        resolveRuleState = { ruleState },
    )
```

and change `service(...)` to take the classifier: add a parameter `assetClassifier: ForeignUtxoAssetClassifier = classifier(),` and pass `assetClassifier = assetClassifier,` instead of `assetClassifier = classifier(),`. Then add:

```kotlin
    // ---- transfer rules ------------------------------------------------------------------------

    @Test fun `a rule-bound asset is not signed, not broadcast, and reported as refused`() {
        var signed = 0
        var broadcastCount = 0
        val svc = service(
            sign = { _, _, _, _ -> signed++; "00ff" },
            broadcast = { broadcastCount++; "txid-moved" },
            assetClassifier = classifier(io.digibyte.core.asset.rules.TransferRuleState.RULE_BOUND),
        )
        val r = run(svc, listOf(profileResult()))
        val move = r.moves.single()
        assertEquals("a55e7:0", move.outpoint)
        assertFalse(move.moved)
        assertEquals(MoveRefusal.RULE_BOUND, move.refusal)
        assertEquals(0, signed)
        assertEquals(0, broadcastCount)
        assertTrue(move.spentInputs.isEmpty())
    }

    @Test fun `an asset whose rules are unknown is refused as unknown`() {
        val svc = service(assetClassifier = classifier(io.digibyte.core.asset.rules.TransferRuleState.UNKNOWN))
        val move = run(svc, listOf(profileResult())).moves.single()
        assertFalse(move.moved)
        assertEquals(MoveRefusal.RULES_UNKNOWN, move.refusal)
    }

    @Test fun `a rule-free asset still moves`() {
        val move = run(service(), listOf(profileResult())).moves.single()
        assertTrue(move.moved)
        assertNull(move.refusal)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.ForeignUtxoAssetClassifierTest" --tests "io.digibyte.core.recovery.ForeignAssetTransferServiceTest" -q 2>&1 | tail -20`
Expected: compilation errors (`resolveRuleState`, `ruleState`, `MoveRefusal`, `refusal` unresolved).

- [ ] **Step 3: Write the implementation**

`ForeignUtxoAssetClassifier.kt` — replace the constructor, `Verdict`, and `classify`:

```kotlin
class ForeignUtxoAssetClassifier(
    /** Raw transaction bytes for a txid, or null when it could not be fetched. */
    private val fetchRawTx: suspend (txid: String) -> ByteArray?,
    /** Whether raw transaction bytes carry a DigiAsset marker. `NativeBridge::isAssetTransaction`
     *  in production; a lambda in tests. */
    private val isAssetTx: (ByteArray) -> Boolean,
    /**
     * Transfer-rule state of the asset the transaction [txid] carries. Production wires
     * `AssetManager.verifyTransferRulesForTx`, which walks to the issuance and asks the proxy.
     * The default is UNKNOWN — the fail-closed value — so a caller that does not wire it cannot
     * move an asset at all. A rule-bound asset moved by a plain transfer is destroyed by every
     * DigiAsset Core indexer; leaving it on the old seed costs nothing.
     */
    private val resolveRuleState: suspend (txid: String) -> io.digibyte.core.asset.rules.TransferRuleState =
        { io.digibyte.core.asset.rules.TransferRuleState.UNKNOWN },
) {

    /** What was learned about one outpoint. */
    data class Verdict(
        val classified: Boolean,
        val carriesAsset: Boolean,
        /** Only meaningful when [carriesAsset]; null otherwise. */
        val ruleState: io.digibyte.core.asset.rules.TransferRuleState? = null,
    ) {
        companion object {
            val PLAIN = Verdict(classified = true, carriesAsset = false)
            val ASSET = Verdict(classified = true, carriesAsset = true)
            /** Could not be answered — the caller must hold the outpoint back. */
            val UNKNOWN = Verdict(classified = false, carriesAsset = false)
        }
    }

    /**
     * Classify every outpoint, fetching each parent transaction once however many of its outputs
     * appear.
     *
     * A fetch failure or a parser throw yields [Verdict.UNKNOWN] for that outpoint rather than
     * aborting the scan: one unreachable transaction must not strand a whole wallet's recovery,
     * and the unknown outpoints are reported to the user rather than silently dropped. A resolver
     * failure yields an ASSET verdict with rule state UNKNOWN: the outpoint is held AND not moved.
     */
    suspend fun classify(utxos: List<UtxoEntry>): Map<UtxoEntry, Verdict> {
        val byTxid = mutableMapOf<String, Verdict>()
        val out = mutableMapOf<UtxoEntry, Verdict>()

        for (utxo in utxos) {
            val verdict = byTxid.getOrPut(utxo.txid) {
                try {
                    val raw = fetchRawTx(utxo.txid)
                    when {
                        raw == null || raw.isEmpty() -> Verdict.UNKNOWN
                        isAssetTx(raw) -> Verdict.ASSET.copy(ruleState = ruleStateOf(utxo.txid))
                        else -> Verdict.PLAIN
                    }
                } catch (_: Throwable) {
                    // Includes a native parser throwing on a malformed transaction. Unknown, not
                    // safe: a transaction we cannot parse is exactly the one to be careful with.
                    Verdict.UNKNOWN
                }
            }
            out[utxo] = verdict
        }
        return out
    }

    private suspend fun ruleStateOf(txid: String): io.digibyte.core.asset.rules.TransferRuleState =
        try {
            resolveRuleState(txid)
        } catch (_: Throwable) {
            io.digibyte.core.asset.rules.TransferRuleState.UNKNOWN
        }
}
```

`ForeignAssetTransferService.kt`:

(a) Add a top-level enum after the imports, before `/** Moves the DigiAssets ... */`:

```kotlin
/** Why an asset was deliberately left on the old seed. Typed so the screen can say it in the
 *  user's language; [ForeignAssetTransferService.Move.failureReason] stays for genuine failures. */
enum class MoveRefusal { RULE_BOUND, RULES_UNKNOWN }
```

(b) Replace `Move`:

```kotlin
    /** What became of one asset. [txid] is non-null only once the transfer reached relay. */
    data class Move(
        val outpoint: String,
        val units: Long,
        val txid: String?,
        val failureReason: String?,
        /** Every outpoint this move's plan spends. Empty when no plan was built. The sweep is
         *  the complement of these — see [RecoverySequence.sweepExclusions]. */
        val spentInputs: List<String> = emptyList(),
        /** Set when the asset was refused by policy (transfer rules), never attempted. */
        val refusal: MoveRefusal? = null,
    ) {
        val moved: Boolean get() = txid != null
    }
```

(c) In `moveAssets`, inside `for (utxo in partition.assetBearing) {`, insert as the first statements (before `val spend = toSpend(utxo, byAddress)`):

```kotlin
                // Rule-bound or unverified assets stay where they are. A plain transfer of a
                // rule-bearing asset is cleared by every DigiAsset Core indexer — the whole input
                // holding gone — and this path builds no rule outputs. Nothing is lost by leaving
                // it on the old seed; the user is told which outpoint and why.
                val ruleState = verdicts[utxo]?.ruleState
                    ?: io.digibyte.core.asset.rules.TransferRuleState.UNKNOWN
                if (ruleState != io.digibyte.core.asset.rules.TransferRuleState.NONE) {
                    val refusal = if (ruleState == io.digibyte.core.asset.rules.TransferRuleState.RULE_BOUND)
                        MoveRefusal.RULE_BOUND else MoveRefusal.RULES_UNKNOWN
                    log('w', "${utxo.txid}:${utxo.vout}: not moved — transfer rules $ruleState")
                    moves += Move("${utxo.txid}:${utxo.vout}", 0L, null, null, refusal = refusal)
                    continue
                }
```

`RecoverFundsViewModel.kt`:

(a) Add a constructor parameter after `assetNetworkClient`:

```kotlin
    private val assetManager: io.digibyte.core.asset.AssetManager,
```

(b) Replace `assetClassifier()`:

```kotlin
    private fun assetClassifier() = io.digibyte.core.recovery.ForeignUtxoAssetClassifier(
        fetchRawTx = { txid -> assetNetworkClient.getRawTransaction(txid) },
        isAssetTx = { raw -> io.digibyte.core.bridge.NativeBridge.isAssetTransaction(raw) },
        // Walks the foreign transaction to its issuance and asks the proxy. Anything that does
        // not come back NONE stays on the old seed (see ForeignAssetTransferService.moveAssets).
        resolveRuleState = { txid -> assetManager.verifyTransferRulesForTx(txid) },
    )
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.*" -q 2>&1 | tail -20 && ./gradlew :app:compileMainnetDebugKotlin -q 2>&1 | tail -10`
Expected: no test output (PASS); app compiles. If any OTHER test in `core/src/test/java/io/digibyte/core/recovery/` constructs `ForeignUtxoAssetClassifier(...)` directly and now sees its assets refused, that is the default UNKNOWN doing its job: pass `resolveRuleState = { io.digibyte.core.asset.rules.TransferRuleState.NONE }` in that test's construction and nothing else.

- [ ] **Step 5: Commit**

```bash
cd /home/polloloco/wt-ruled-asset-gate && git add core/src/main/java/io/digibyte/core/recovery/ForeignUtxoAssetClassifier.kt core/src/main/java/io/digibyte/core/recovery/ForeignAssetTransferService.kt app/src/main/java/io/digibyte/ui/recovery/RecoverFundsViewModel.kt core/src/test/java/io/digibyte/core/recovery/ForeignUtxoAssetClassifierTest.kt core/src/test/java/io/digibyte/core/recovery/ForeignAssetTransferServiceTest.kt && git commit -q -m "fix(recovery): leave rule-bound or unverified assets on the old seed instead of moving them

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HXtAff7AzbUR9bthQiFpg1"
```

---

### Task 8: Screens, view model, and strings in 13 languages

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/asset/AssetViewModel.kt` (state, `selectAsset`, `sendAssetTransfer` `when`, `SendState`)
- Create: `app/src/main/java/io/digibyte/ui/asset/TransferRuleCard.kt`
- Modify: `app/src/main/java/io/digibyte/ui/asset/AssetSendScreen.kt` (collect state; banner; Review button `enabled`; `Refused` banner)
- Modify: `app/src/main/java/io/digibyte/ui/asset/AssetDetailScreen.kt` (collect state; card; Send button gating)
- Modify: `app/src/main/java/io/digibyte/ui/recovery/RecoverFundsScreen.kt` (`AssetMoveSection`)
- Modify: `app/src/main/res/values/strings_wallet.xml` and the 12 translated `strings_wallet.xml` files
- Modify: `app/src/test/java/io/digibyte/ui/locale/OnboardingHardcodedStringTest.kt` (`COVERED` gains the new file)
- Test: the two locale gates (`LocaleResourceParityTest`, `OnboardingHardcodedStringTest`), `AssetTransferRuleGateTest` (already covers `RuleCheckState`)

**Interfaces:**
- Consumes: `RuleCheckState`, `TransferRuleState` (Task 1); `SendRefusal`, `TxResult.Refused`, `AssetManager.transferRuleState/verifyTransferRules` (Task 6); `MoveRefusal`, `Move.refusal` (Task 7).
- Produces: `AssetViewModel.ruleCheck: StateFlow<RuleCheckState>`, `AssetViewModel.retryRuleCheck()`, `AssetViewModel.SendState.Refused(val reason: SendRefusal)`; composable `TransferRuleCard(state: RuleCheckState, onRetry: () -> Unit, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Write the failing test (strings gate)**

Add `"ui/asset/TransferRuleCard.kt",` to the `COVERED` list in `OnboardingHardcodedStringTest` directly after `"ui/asset/AssetMediaPlayer.kt",`. Then run the two gates: they must FAIL now because the file does not exist (`missing` assertion) — that is the red for this task.

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :app:testMainnetDebugUnitTest --tests "io.digibyte.ui.locale.*" -q 2>&1 | tail -20`
Expected: FAIL naming `ui/asset/TransferRuleCard.kt` as missing.

- [ ] **Step 2: Strings — English**

Append to `app/src/main/res/values/strings_wallet.xml` immediately before `</resources>`:

```xml
    <!-- Transfer-rule gate: assets issued with rules (royalty, deflation, …) must not be sent by
         a plain transfer, which every DigiAsset Core indexer treats as a burn. -->
    <string name="as_rules_checking">Checking this asset\'s transfer rules…</string>
    <string name="as_rules_bound_title">This asset has transfer rules</string>
    <string name="as_rules_bound_body">It was issued with rules (such as royalties) that this wallet cannot satisfy yet. Sending it now would destroy it, so sending is disabled. Your asset stays safe in this wallet.</string>
    <string name="as_rules_unverified_body">This asset\'s transfer rules could not be verified. Sending stays disabled until they are, to protect the asset.</string>
    <string name="as_rules_retry">Check again</string>
    <string name="as_refused_rule_bound">Blocked: this asset has transfer rules this wallet cannot satisfy yet. Nothing was sent.</string>
    <string name="as_refused_rules_unknown">Blocked: this asset\'s transfer rules could not be verified. Nothing was sent.</string>
    <string name="rf_move_refused_rule_bound">%1$s stayed where it is: this asset has transfer rules this wallet cannot satisfy yet</string>
    <string name="rf_move_refused_unknown">%1$s stayed where it is: its transfer rules could not be verified</string>
```

- [ ] **Step 3: Strings — the twelve translations**

Append the block for each locale immediately before `</resources>` of that directory's `strings_wallet.xml`. Machine-assisted, unreviewed, same as the rest of the file.

`values-de/strings_wallet.xml`:
```xml
    <string name="as_rules_checking">Übertragungsregeln dieses Assets werden geprüft…</string>
    <string name="as_rules_bound_title">Dieses Asset hat Übertragungsregeln</string>
    <string name="as_rules_bound_body">Es wurde mit Regeln (z. B. Lizenzgebühren) ausgegeben, die diese Wallet noch nicht erfüllen kann. Ein Senden würde es jetzt zerstören, daher ist das Senden deaktiviert. Dein Asset bleibt sicher in dieser Wallet.</string>
    <string name="as_rules_unverified_body">Die Übertragungsregeln dieses Assets konnten nicht überprüft werden. Zum Schutz des Assets bleibt das Senden deaktiviert, bis sie überprüft sind.</string>
    <string name="as_rules_retry">Erneut prüfen</string>
    <string name="as_refused_rule_bound">Blockiert: Dieses Asset hat Übertragungsregeln, die diese Wallet noch nicht erfüllen kann. Es wurde nichts gesendet.</string>
    <string name="as_refused_rules_unknown">Blockiert: Die Übertragungsregeln dieses Assets konnten nicht überprüft werden. Es wurde nichts gesendet.</string>
    <string name="rf_move_refused_rule_bound">%1$s bleibt, wo es ist: Dieses Asset hat Übertragungsregeln, die diese Wallet noch nicht erfüllen kann</string>
    <string name="rf_move_refused_unknown">%1$s bleibt, wo es ist: Seine Übertragungsregeln konnten nicht überprüft werden</string>
```

`values-es/strings_wallet.xml`:
```xml
    <string name="as_rules_checking">Comprobando las reglas de transferencia de este activo…</string>
    <string name="as_rules_bound_title">Este activo tiene reglas de transferencia</string>
    <string name="as_rules_bound_body">Se emitió con reglas (como regalías) que esta billetera aún no puede cumplir. Enviarlo ahora lo destruiría, por lo que el envío está desactivado. Tu activo permanece seguro en esta billetera.</string>
    <string name="as_rules_unverified_body">No se pudieron verificar las reglas de transferencia de este activo. El envío permanece desactivado hasta verificarlas, para proteger el activo.</string>
    <string name="as_rules_retry">Comprobar de nuevo</string>
    <string name="as_refused_rule_bound">Bloqueado: este activo tiene reglas de transferencia que esta billetera aún no puede cumplir. No se envió nada.</string>
    <string name="as_refused_rules_unknown">Bloqueado: no se pudieron verificar las reglas de transferencia de este activo. No se envió nada.</string>
    <string name="rf_move_refused_rule_bound">%1$s se quedó donde estaba: este activo tiene reglas de transferencia que esta billetera aún no puede cumplir</string>
    <string name="rf_move_refused_unknown">%1$s se quedó donde estaba: no se pudieron verificar sus reglas de transferencia</string>
```

`values-fr/strings_wallet.xml`:
```xml
    <string name="as_rules_checking">Vérification des règles de transfert de cet actif…</string>
    <string name="as_rules_bound_title">Cet actif a des règles de transfert</string>
    <string name="as_rules_bound_body">Il a été émis avec des règles (comme des redevances) que ce portefeuille ne peut pas encore respecter. L\'envoyer maintenant le détruirait, l\'envoi est donc désactivé. Votre actif reste en sécurité dans ce portefeuille.</string>
    <string name="as_rules_unverified_body">Les règles de transfert de cet actif n\'ont pas pu être vérifiées. L\'envoi reste désactivé jusqu\'à leur vérification, pour protéger l\'actif.</string>
    <string name="as_rules_retry">Vérifier à nouveau</string>
    <string name="as_refused_rule_bound">Bloqué : cet actif a des règles de transfert que ce portefeuille ne peut pas encore respecter. Rien n\'a été envoyé.</string>
    <string name="as_refused_rules_unknown">Bloqué : les règles de transfert de cet actif n\'ont pas pu être vérifiées. Rien n\'a été envoyé.</string>
    <string name="rf_move_refused_rule_bound">%1$s est resté en place : cet actif a des règles de transfert que ce portefeuille ne peut pas encore respecter</string>
    <string name="rf_move_refused_unknown">%1$s est resté en place : ses règles de transfert n\'ont pas pu être vérifiées</string>
```

`values-hi/strings_wallet.xml`:
```xml
    <string name="as_rules_checking">इस एसेट के ट्रांसफ़र नियम जाँचे जा रहे हैं…</string>
    <string name="as_rules_bound_title">इस एसेट के ट्रांसफ़र नियम हैं</string>
    <string name="as_rules_bound_body">इसे ऐसे नियमों (जैसे रॉयल्टी) के साथ जारी किया गया था जिन्हें यह वॉलेट अभी पूरा नहीं कर सकता। इसे अभी भेजने से यह नष्ट हो जाएगा, इसलिए भेजना बंद है। आपका एसेट इस वॉलेट में सुरक्षित रहता है।</string>
    <string name="as_rules_unverified_body">इस एसेट के ट्रांसफ़र नियमों की पुष्टि नहीं हो सकी। एसेट की सुरक्षा के लिए, पुष्टि होने तक भेजना बंद रहेगा।</string>
    <string name="as_rules_retry">फिर से जाँचें</string>
    <string name="as_refused_rule_bound">रोका गया: इस एसेट के ऐसे ट्रांसफ़र नियम हैं जिन्हें यह वॉलेट अभी पूरा नहीं कर सकता। कुछ भी नहीं भेजा गया।</string>
    <string name="as_refused_rules_unknown">रोका गया: इस एसेट के ट्रांसफ़र नियमों की पुष्टि नहीं हो सकी। कुछ भी नहीं भेजा गया।</string>
    <string name="rf_move_refused_rule_bound">%1$s वहीं रहा: इस एसेट के ऐसे ट्रांसफ़र नियम हैं जिन्हें यह वॉलेट अभी पूरा नहीं कर सकता</string>
    <string name="rf_move_refused_unknown">%1$s वहीं रहा: इसके ट्रांसफ़र नियमों की पुष्टि नहीं हो सकी</string>
```

`values-zh/strings_wallet.xml`:
```xml
    <string name="as_rules_checking">正在检查此资产的转账规则…</string>
    <string name="as_rules_bound_title">此资产带有转账规则</string>
    <string name="as_rules_bound_body">它在发行时附带了本钱包尚无法满足的规则（例如版税）。现在发送会将其销毁，因此已禁用发送。您的资产安全地保留在本钱包中。</string>
    <string name="as_rules_unverified_body">无法验证此资产的转账规则。为保护资产，在验证完成前发送将保持禁用。</string>
    <string name="as_rules_retry">重新检查</string>
    <string name="as_refused_rule_bound">已阻止：此资产带有本钱包尚无法满足的转账规则。未发送任何内容。</string>
    <string name="as_refused_rules_unknown">已阻止：无法验证此资产的转账规则。未发送任何内容。</string>
    <string name="rf_move_refused_rule_bound">%1$s 留在原处：此资产带有本钱包尚无法满足的转账规则</string>
    <string name="rf_move_refused_unknown">%1$s 留在原处：无法验证其转账规则</string>
```

`values-ja/strings_wallet.xml`:
```xml
    <string name="as_rules_checking">このアセットの転送ルールを確認しています…</string>
    <string name="as_rules_bound_title">このアセットには転送ルールがあります</string>
    <string name="as_rules_bound_body">このウォレットがまだ満たせないルール（ロイヤリティなど）付きで発行されています。今送信すると破壊されるため、送信は無効になっています。アセットはこのウォレットに安全に保管されます。</string>
    <string name="as_rules_unverified_body">このアセットの転送ルールを確認できませんでした。アセットを保護するため、確認できるまで送信は無効のままです。</string>
    <string name="as_rules_retry">再確認</string>
    <string name="as_refused_rule_bound">ブロックされました：このアセットには、このウォレットがまだ満たせない転送ルールがあります。何も送信されていません。</string>
    <string name="as_refused_rules_unknown">ブロックされました：このアセットの転送ルールを確認できませんでした。何も送信されていません。</string>
    <string name="rf_move_refused_rule_bound">%1$s はそのまま残りました：このアセットには、このウォレットがまだ満たせない転送ルールがあります</string>
    <string name="rf_move_refused_unknown">%1$s はそのまま残りました：転送ルールを確認できませんでした</string>
```

`values-b+pt+BR/strings_wallet.xml`:
```xml
    <string name="as_rules_checking">Verificando as regras de transferência deste ativo…</string>
    <string name="as_rules_bound_title">Este ativo tem regras de transferência</string>
    <string name="as_rules_bound_body">Ele foi emitido com regras (como royalties) que esta carteira ainda não consegue cumprir. Enviá-lo agora o destruiria, por isso o envio está desativado. Seu ativo permanece seguro nesta carteira.</string>
    <string name="as_rules_unverified_body">Não foi possível verificar as regras de transferência deste ativo. O envio continua desativado até que sejam verificadas, para proteger o ativo.</string>
    <string name="as_rules_retry">Verificar novamente</string>
    <string name="as_refused_rule_bound">Bloqueado: este ativo tem regras de transferência que esta carteira ainda não consegue cumprir. Nada foi enviado.</string>
    <string name="as_refused_rules_unknown">Bloqueado: não foi possível verificar as regras de transferência deste ativo. Nada foi enviado.</string>
    <string name="rf_move_refused_rule_bound">%1$s ficou onde estava: este ativo tem regras de transferência que esta carteira ainda não consegue cumprir</string>
    <string name="rf_move_refused_unknown">%1$s ficou onde estava: não foi possível verificar suas regras de transferência</string>
```

`values-id/strings_wallet.xml`:
```xml
    <string name="as_rules_checking">Memeriksa aturan transfer aset ini…</string>
    <string name="as_rules_bound_title">Aset ini memiliki aturan transfer</string>
    <string name="as_rules_bound_body">Aset ini diterbitkan dengan aturan (seperti royalti) yang belum dapat dipenuhi dompet ini. Mengirimnya sekarang akan menghancurkannya, jadi pengiriman dinonaktifkan. Aset Anda tetap aman di dompet ini.</string>
    <string name="as_rules_unverified_body">Aturan transfer aset ini tidak dapat diverifikasi. Pengiriman tetap dinonaktifkan sampai terverifikasi, untuk melindungi aset.</string>
    <string name="as_rules_retry">Periksa lagi</string>
    <string name="as_refused_rule_bound">Diblokir: aset ini memiliki aturan transfer yang belum dapat dipenuhi dompet ini. Tidak ada yang dikirim.</string>
    <string name="as_refused_rules_unknown">Diblokir: aturan transfer aset ini tidak dapat diverifikasi. Tidak ada yang dikirim.</string>
    <string name="rf_move_refused_rule_bound">%1$s tetap di tempatnya: aset ini memiliki aturan transfer yang belum dapat dipenuhi dompet ini</string>
    <string name="rf_move_refused_unknown">%1$s tetap di tempatnya: aturan transfernya tidak dapat diverifikasi</string>
```

`values-vi/strings_wallet.xml`:
```xml
    <string name="as_rules_checking">Đang kiểm tra quy tắc chuyển của tài sản này…</string>
    <string name="as_rules_bound_title">Tài sản này có quy tắc chuyển</string>
    <string name="as_rules_bound_body">Tài sản được phát hành kèm các quy tắc (như tiền bản quyền) mà ví này chưa thể đáp ứng. Gửi ngay bây giờ sẽ phá hủy nó, nên tính năng gửi đã bị tắt. Tài sản của bạn vẫn an toàn trong ví này.</string>
    <string name="as_rules_unverified_body">Không thể xác minh quy tắc chuyển của tài sản này. Để bảo vệ tài sản, tính năng gửi sẽ bị tắt cho đến khi xác minh xong.</string>
    <string name="as_rules_retry">Kiểm tra lại</string>
    <string name="as_refused_rule_bound">Đã chặn: tài sản này có quy tắc chuyển mà ví này chưa thể đáp ứng. Chưa gửi gì cả.</string>
    <string name="as_refused_rules_unknown">Đã chặn: không thể xác minh quy tắc chuyển của tài sản này. Chưa gửi gì cả.</string>
    <string name="rf_move_refused_rule_bound">%1$s vẫn ở nguyên chỗ cũ: tài sản này có quy tắc chuyển mà ví này chưa thể đáp ứng</string>
    <string name="rf_move_refused_unknown">%1$s vẫn ở nguyên chỗ cũ: không thể xác minh quy tắc chuyển của nó</string>
```

`values-tr/strings_wallet.xml`:
```xml
    <string name="as_rules_checking">Bu varlığın transfer kuralları kontrol ediliyor…</string>
    <string name="as_rules_bound_title">Bu varlığın transfer kuralları var</string>
    <string name="as_rules_bound_body">Bu cüzdanın henüz karşılayamadığı kurallarla (telif ücreti gibi) ihraç edilmiş. Şimdi göndermek onu yok edeceğinden gönderme devre dışı bırakıldı. Varlığınız bu cüzdanda güvende kalır.</string>
    <string name="as_rules_unverified_body">Bu varlığın transfer kuralları doğrulanamadı. Varlığı korumak için, doğrulanana kadar gönderme devre dışı kalır.</string>
    <string name="as_rules_retry">Yeniden kontrol et</string>
    <string name="as_refused_rule_bound">Engellendi: bu varlığın, bu cüzdanın henüz karşılayamadığı transfer kuralları var. Hiçbir şey gönderilmedi.</string>
    <string name="as_refused_rules_unknown">Engellendi: bu varlığın transfer kuralları doğrulanamadı. Hiçbir şey gönderilmedi.</string>
    <string name="rf_move_refused_rule_bound">%1$s olduğu yerde kaldı: bu varlığın, bu cüzdanın henüz karşılayamadığı transfer kuralları var</string>
    <string name="rf_move_refused_unknown">%1$s olduğu yerde kaldı: transfer kuralları doğrulanamadı</string>
```

`values-ru/strings_wallet.xml`:
```xml
    <string name="as_rules_checking">Проверяем правила передачи этого актива…</string>
    <string name="as_rules_bound_title">У этого актива есть правила передачи</string>
    <string name="as_rules_bound_body">Он выпущен с правилами (например, роялти), которые этот кошелёк пока не может выполнить. Отправка сейчас уничтожила бы его, поэтому отправка отключена. Ваш актив остаётся в безопасности в этом кошельке.</string>
    <string name="as_rules_unverified_body">Не удалось проверить правила передачи этого актива. Чтобы защитить актив, отправка остаётся отключённой до их проверки.</string>
    <string name="as_rules_retry">Проверить снова</string>
    <string name="as_refused_rule_bound">Заблокировано: у этого актива есть правила передачи, которые этот кошелёк пока не может выполнить. Ничего не отправлено.</string>
    <string name="as_refused_rules_unknown">Заблокировано: не удалось проверить правила передачи этого актива. Ничего не отправлено.</string>
    <string name="rf_move_refused_rule_bound">%1$s остался на месте: у этого актива есть правила передачи, которые этот кошелёк пока не может выполнить</string>
    <string name="rf_move_refused_unknown">%1$s остался на месте: не удалось проверить его правила передачи</string>
```

`values-b+fil/strings_wallet.xml`:
```xml
    <string name="as_rules_checking">Sinusuri ang mga panuntunan sa paglilipat ng asset na ito…</string>
    <string name="as_rules_bound_title">May mga panuntunan sa paglilipat ang asset na ito</string>
    <string name="as_rules_bound_body">Inilabas ito na may mga panuntunan (tulad ng royalty) na hindi pa kayang tuparin ng wallet na ito. Masisira ito kung ipapadala ngayon, kaya naka-disable ang pagpapadala. Nananatiling ligtas ang iyong asset sa wallet na ito.</string>
    <string name="as_rules_unverified_body">Hindi ma-verify ang mga panuntunan sa paglilipat ng asset na ito. Mananatiling naka-disable ang pagpapadala hanggang ma-verify ang mga ito, para protektahan ang asset.</string>
    <string name="as_rules_retry">Suriin muli</string>
    <string name="as_refused_rule_bound">Na-block: may mga panuntunan sa paglilipat ang asset na ito na hindi pa kayang tuparin ng wallet na ito. Walang naipadala.</string>
    <string name="as_refused_rules_unknown">Na-block: hindi ma-verify ang mga panuntunan sa paglilipat ng asset na ito. Walang naipadala.</string>
    <string name="rf_move_refused_rule_bound">%1$s ay nanatili sa kinaroroonan nito: may mga panuntunan sa paglilipat ang asset na ito na hindi pa kayang tuparin ng wallet na ito</string>
    <string name="rf_move_refused_unknown">%1$s ay nanatili sa kinaroroonan nito: hindi ma-verify ang mga panuntunan sa paglilipat nito</string>
```

- [ ] **Step 4: `AssetViewModel`**

(a) Add imports:

```kotlin
import io.digibyte.core.SendRefusal
import io.digibyte.core.asset.rules.RuleCheckState
import io.digibyte.core.asset.rules.TransferRuleState
```

(b) After `val sendState: StateFlow<SendState> = _sendState.asStateFlow()` add:

```kotlin
    // ── Transfer-rule check ─────────────────────────────────────────────
    //
    // Resolved once per selected asset. Reads the stores first; only if that is UNKNOWN does it
    // walk the asset to its issuance and ask the proxy (verifyTransferRules). CHECKING is the only
    // transient value, and nothing but NONE enables Send — see RuleCheckState.allowsSend.
    private val _ruleCheck = MutableStateFlow(RuleCheckState.CHECKING)
    val ruleCheck: StateFlow<RuleCheckState> = _ruleCheck.asStateFlow()

    private fun checkRules(assetId: String) {
        _ruleCheck.value = RuleCheckState.CHECKING
        viewModelScope.launch {
            val read = runCatching { assetManager.transferRuleState(assetId) }
                .getOrDefault(TransferRuleState.UNKNOWN)
            val state = if (read != TransferRuleState.UNKNOWN) read
            else runCatching { assetManager.verifyTransferRules(assetId) }
                .getOrDefault(TransferRuleState.UNKNOWN)
            _ruleCheck.value = RuleCheckState.of(state)
        }
    }

    fun retryRuleCheck() {
        _selectedAssetId.value?.let { checkRules(it) }
    }
```

(c) Replace `selectAsset`:

```kotlin
    fun selectAsset(assetId: String) {
        if (_selectedAssetId.value != assetId || _ruleCheck.value == RuleCheckState.UNVERIFIED) {
            checkRules(assetId)
        }
        _selectedAssetId.value = assetId
    }
```

(d) In `sendAssetTransfer`, before `_sendState.value = SendState.Sending`, add:

```kotlin
        if (!_ruleCheck.value.allowsSend) {
            _sendState.value = SendState.Refused(
                if (_ruleCheck.value == RuleCheckState.RULE_BOUND) SendRefusal.RULE_BOUND_ASSET
                else SendRefusal.RULES_UNKNOWN
            )
            return
        }
```

and replace the `when (result)` with:

```kotlin
            _sendState.value = when (result) {
                is TxResult.Success -> SendState.Success(result.txid)
                is TxResult.Error -> SendState.Failure(result.message)
                is TxResult.Refused -> SendState.Refused(result.reason)
            }
```

(remove any temporary `Refused` branch added in Task 6).

(e) Add to `SendState`:

```kotlin
        data class Refused(val reason: SendRefusal) : SendState()
```

- [ ] **Step 5: `TransferRuleCard.kt`**

```kotlin
package io.digibyte.ui.asset

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.digibyte.R
import io.digibyte.core.asset.rules.RuleCheckState

/**
 * Tells the user why an asset cannot be sent. Rendered on the detail and send screens whenever
 * the transfer-rule check is anything but NONE, which is the only state that enables Send.
 *
 * A rule-bearing asset moved by a plain transfer is cleared by every DigiAsset Core indexer:
 * the whole input holding is destroyed while the wallet reports success. So the card says three
 * things in this order: what is true, what is blocked, and that the asset is safe.
 */
@Composable
fun TransferRuleCard(
    state: RuleCheckState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == RuleCheckState.NONE) return
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            when (state) {
                RuleCheckState.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.as_rules_checking),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                RuleCheckState.RULE_BOUND -> {
                    Text(
                        text = stringResource(R.string.as_rules_bound_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.as_rules_bound_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                RuleCheckState.UNVERIFIED -> {
                    Text(
                        text = stringResource(R.string.as_rules_unverified_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.as_rules_retry))
                    }
                }
                RuleCheckState.NONE -> Unit
            }
        }
    }
}
```

- [ ] **Step 6: `AssetSendScreen`**

(a) After `val sendState by viewModel.sendState.collectAsStateWithLifecycle()` add:

```kotlin
    val ruleCheck by viewModel.ruleCheck.collectAsStateWithLifecycle()
    val refusedRuleBound = stringResource(R.string.as_refused_rule_bound)
    val refusedUnknown = stringResource(R.string.as_refused_rules_unknown)
```

(b) In the terminal-banner `when (val s = sendState)`, add a branch before `else -> {}`:

```kotlin
            is AssetViewModel.SendState.Refused -> SendResultBanner(
                success = false,
                title = stringResource(R.string.as_send_failed),
                detail = if (s.reason == io.digibyte.core.SendRefusal.RULE_BOUND_ASSET) refusedRuleBound else refusedUnknown,
                onDismiss = { viewModel.resetSendState() }
            )
```

and extend the `LaunchedEffect(sendState)` condition that closes the confirm dialog to include `sendState is AssetViewModel.SendState.Refused`.

(c) Immediately before the line `// ── DGB cost preview ─` insert:

```kotlin
            // ── Transfer rules ────────────────────────────────────────────
            TransferRuleCard(state = ruleCheck, onRetry = { viewModel.retryRuleCheck() })
            if (ruleCheck != io.digibyte.core.asset.rules.RuleCheckState.NONE) {
                Spacer(modifier = Modifier.height(16.dp))
            }
```

(d) On the Review `Button(` that follows `// ── Review button ─`, add the parameter `enabled = ruleCheck.allowsSend,` directly after `onClick = { … },` (i.e. before `modifier =`).

- [ ] **Step 7: `AssetDetailScreen`**

(a) After `val owned by viewModel.ownedAssets.collectAsStateWithLifecycle()` add:

```kotlin
    val ruleCheck by viewModel.ruleCheck.collectAsStateWithLifecycle()
```

(b) Immediately before the `// ── Action buttons ─` item insert a new item:

```kotlin
        // ── Transfer rules ────────────────────────────────────────────────
        if (ruleCheck != io.digibyte.core.asset.rules.RuleCheckState.NONE) {
            item {
                TransferRuleCard(
                    state = ruleCheck,
                    onRetry = { viewModel.retryRuleCheck() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
```

(c) Wrap the existing `// Send button` `Button(...)` block in `if (ruleCheck.allowsSend) { … }` so the button is absent, not merely disabled, for anything but NONE.

- [ ] **Step 8: `RecoverFundsScreen`**

In `AssetMoveSection`, replace the `Text(text = stringResource(R.string.rf_move_failed, …))` inside `moves.filter { !it.moved }.forEach { failed ->` with:

```kotlin
                Text(
                    text = when (failed.refusal) {
                        io.digibyte.core.recovery.MoveRefusal.RULE_BOUND ->
                            stringResource(R.string.rf_move_refused_rule_bound, failed.outpoint)
                        io.digibyte.core.recovery.MoveRefusal.RULES_UNKNOWN ->
                            stringResource(R.string.rf_move_refused_unknown, failed.outpoint)
                        null -> stringResource(
                            R.string.rf_move_failed,
                            failed.outpoint,
                            failed.failureReason ?: "",
                        )
                    },
                    color = WARNING_RED,
                    style = MaterialTheme.typography.bodySmall,
                )
```

- [ ] **Step 9: Run the gates and the compile**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :app:testMainnetDebugUnitTest --tests "io.digibyte.ui.locale.*" -q 2>&1 | tail -20 && ./gradlew :app:compileMainnetDebugKotlin -q 2>&1 | tail -10`
Expected: no output (both gates PASS: every key in all 13 files, no literal prose in `TransferRuleCard.kt`, `AssetSendScreen.kt`, `AssetDetailScreen.kt`, `RecoverFundsScreen.kt`); app compiles.

- [ ] **Step 10: Commit**

```bash
cd /home/polloloco/wt-ruled-asset-gate && git add app/src/main/java/io/digibyte/ui/asset/AssetViewModel.kt app/src/main/java/io/digibyte/ui/asset/TransferRuleCard.kt app/src/main/java/io/digibyte/ui/asset/AssetSendScreen.kt app/src/main/java/io/digibyte/ui/asset/AssetDetailScreen.kt app/src/main/java/io/digibyte/ui/recovery/RecoverFundsScreen.kt app/src/main/res/values/strings_wallet.xml app/src/main/res/values-de/strings_wallet.xml app/src/main/res/values-es/strings_wallet.xml app/src/main/res/values-fr/strings_wallet.xml app/src/main/res/values-hi/strings_wallet.xml app/src/main/res/values-zh/strings_wallet.xml app/src/main/res/values-ja/strings_wallet.xml "app/src/main/res/values-b+pt+BR/strings_wallet.xml" app/src/main/res/values-id/strings_wallet.xml app/src/main/res/values-vi/strings_wallet.xml app/src/main/res/values-tr/strings_wallet.xml app/src/main/res/values-ru/strings_wallet.xml "app/src/main/res/values-b+fil/strings_wallet.xml" app/src/test/java/io/digibyte/ui/locale/OnboardingHardcodedStringTest.kt && git commit -q -m "feat(ui): transfer-rule card, Send disabled unless rule-free, recovery refusal text — 13 languages

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HXtAff7AzbUR9bthQiFpg1"
```

---

### Task 9: Full verification, build, and report update

**Files:**
- Modify: `security/reports/bounty/2026-09-06-ruled-asset-send-burns-holding.md` (append a "Fix built" section)
- Build output: `app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk` (not committed)

- [ ] **Step 1: Run every suite**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :core:testMainnetDebugUnitTest :app:testMainnetDebugUnitTest -q 2>&1 | tail -30; for d in core/build/test-results/testMainnetDebugUnitTest app/build/test-results/testMainnetDebugUnitTest; do python3 - "$d" <<'EOF'
import sys,glob,xml.etree.ElementTree as ET
t=f=e=0
for p in glob.glob(sys.argv[1]+'/*.xml'):
    r=ET.parse(p).getroot(); t+=int(r.get('tests',0)); f+=int(r.get('failures',0)); e+=int(r.get('errors',0))
print(sys.argv[1],'tests',t,'failures',f,'errors',e)
EOF
done`
Expected: 0 failures, 0 errors; core count ≥ 947 + the new tests (at least 968), app count ≥ 7.

- [ ] **Step 2: Build the debug APK**

Run: `cd /home/polloloco/wt-ruled-asset-gate && ./gradlew :app:assembleMainnetDebug -q 2>&1 | tail -10 && ls -la app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk`
Expected: the APK path listed. (The native module builds from the submodule already checked out at the pinned commit; no C changed.)

- [ ] **Step 3: Record what was built in the report**

Append to `security/reports/bounty/2026-09-06-ruled-asset-send-burns-holding.md`:

```markdown
## Fix built (branch `fix/ruled-asset-send-gate`)

Implemented per `docs/superpowers/plans/2026-09-06-ruled-asset-transfer-gate.md`:
- `AssetTransferRuleGate` (pure, decision table tested) — NONE only for a locked issuance with opcode 1/2/5 and no proxy rules object; RULE_BOUND for opcode 3/4 or a proxy rules object; UNKNOWN otherwise.
- Decoder `hasRules`; provenance walk records `issuanceOpcode`/`issuanceLocked` (Room migration 10→11) and can be forced to the issuance for pre-upgrade rows; proxy `rules` object persisted to `rulesJson`.
- `AssetManager.sendAsset` returns `TxResult.Refused` before reading a UTXO; `ForeignAssetTransferService` leaves RULE_BOUND/UNKNOWN outpoints on the old seed with a typed `MoveRefusal`.
- Detail and send screens show `TransferRuleCard` and remove/disable Send for anything but NONE; recovery screen names refused outpoints. Strings in 13 languages.

Still open: the live protocol with the reporter (unfixed build first, then this build), and the release decision under the founder-review hold.
```

- [ ] **Step 4: Commit**

```bash
cd /home/polloloco/wt-ruled-asset-gate && git add security/reports/bounty/2026-09-06-ruled-asset-send-burns-holding.md && git commit -q -m "docs(security): record the built fix in the ruled-asset report

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HXtAff7AzbUR9bthQiFpg1"
```

- [ ] **Step 5: Report**

State plainly: test counts for both modules, the APK path, and the two items still open (live protocol, release timing). Do not tag, push, or merge.
