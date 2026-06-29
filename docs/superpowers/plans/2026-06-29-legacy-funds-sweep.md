# Legacy Funds Sweep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user recover funds sitting on a non-native derivation path (e.g. the old DigiByte BreadWallet `m/0'` "DigiByte seed" path) by sweeping them into a native address with one transaction — no full-chain sync or reconcile required.

**Architecture:** A two-phase flow — *Classify* (the existing `RecoveryScanService` multi-profile scan, made testable via a pluggable `UtxoSource`) then *Choose* (`RecoverFundsScreen`: Sweep now, or Sync coming-soon). Sweep reuses the existing `LegacySweepService` + native `buildAndSignLegacySweep` signer, but surfaces every outcome to the UI (the current onboarding caller swallows failures). Two entry points share one screen: onboarding (replacing the silent auto-sweep) and a re-runnable Settings row.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt DI, JUnit4 (JVM unit tests) + AndroidJUnit4 (instrumented, for JNI-backed derivation/signing), `digibyted -regtest` for end-to-end.

## Global Constraints

- Module layout: business logic in `:core`, UI/DI in `:app`, JNI in `:native`. Follow existing patterns.
- Seed handling (CRITICAL-3): the seed/mnemonic must live as a `ByteArray` and be zeroed with `fill(0)` in a `finally` block on every path; never materialize the mnemonic as an immutable `String` on the Settings (already-restored) path.
- DigiByte receive-address `format` ints: `0`=legacy `D…`, `1`=p2sh-segwit `S…`, `2`=bech32 `dgb1…`. Native sweep destination default = `getReceiveAddress(0, format = 2)` (bech32 BIP84).
- Default fee: `feePerKb = 100_000L` (DigiByte min relay, 100 sat/byte). Already the `LegacySweepService.sweep` default.
- Address validation must use `NativeBridge.isValidAddress(address): Boolean` (validates `D…`/`S…`/`dgb1…`). Do not hand-roll address parsing.
- BIP49 (P2SH-P2WPKH) sweeping stays out of scope: detect and label "manual recovery for now", never silently skip.
- Reuse existing types: `ReconcileResult`, `UtxoEntry`, `RawTxEntry` (in `DgbNodeClient.kt`). Do not invent parallel UTXO types.
- Commit after every task. Branch: `phase1-modernization`.

---

## File Structure

**Create:**
- `core/src/main/java/io/digibyte/core/recovery/UtxoSource.kt` — pluggable UTXO-lookup interface + `ReconcileBackendUtxoSource` impl.
- `core/src/test/java/io/digibyte/core/recovery/FakeUtxoSource.kt` — deterministic test source.
- `core/src/test/java/io/digibyte/core/recovery/UtxoSourceTest.kt` — unit tests for the source seam.
- `core/src/main/java/io/digibyte/core/recovery/SweepDestination.kt` — `Native` vs `External(address)` destination + resolver.
- `core/src/test/java/io/digibyte/core/recovery/SweepDestinationTest.kt` — pure-logic tests for the destination type.
- `app/src/main/java/io/digibyte/ui/recovery/RecoverFundsViewModel.kt` — classify + sweep orchestration for both entry points.
- `app/src/main/java/io/digibyte/ui/recovery/RecoverFundsScreen.kt` — shared classify→choose Compose UI.
- `native/src/androidTest/java/io/digibyte/native_core/LegacySweepDerivationTest.kt` — known-answer derivation/sign vectors (JNI).
- `native/src/androidTest/java/io/digibyte/native_core/LegacySweepRegtestTest.kt` — regtest end-to-end.
- `scripts/regtest/start-regtest.sh`, `scripts/regtest/fund-legacy-address.sh`, `scripts/regtest/README.md` — regtest harness.
- `docs/superpowers/plans/2026-06-29-legacy-funds-sweep-mainnet-proof.md` — one-time mainnet validation checklist.

**Modify:**
- `core/src/main/java/io/digibyte/core/recovery/RecoveryScanService.kt` — depend on `UtxoSource`; add `ByteArray` seed variant.
- `core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt` — add `ByteArray` seed variant of `sweep`.
- `app/src/main/java/io/digibyte/di/AppModule.kt` — provide `UtxoSource`; rewire `RecoveryScanService`.
- `app/src/main/java/io/digibyte/ui/onboarding/OnboardingViewModel.kt` — replace silent auto-sweep with navigation signal.
- `app/src/main/java/io/digibyte/ui/settings/SettingsScreen.kt` — add "Recover funds from another wallet" row.
- `app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt` — register the `recover_funds` route.

---

## Task 1: `UtxoSource` interface + `ReconcileBackendUtxoSource`

**Files:**
- Create: `core/src/main/java/io/digibyte/core/recovery/UtxoSource.kt`
- Create: `core/src/test/java/io/digibyte/core/recovery/FakeUtxoSource.kt`
- Test: `core/src/test/java/io/digibyte/core/recovery/UtxoSourceTest.kt`

**Interfaces:**
- Consumes: `DgbNodeClient.reconcileAddresses(addresses: List<String>): ReconcileResult?`, `ReconcileResult(utxos, rawTxs, chainHeight)`, `UtxoEntry`, `RawTxEntry`.
- Produces: `interface UtxoSource { suspend fun fetchUtxos(addresses: List<String>): ReconcileResult? }`; `class ReconcileBackendUtxoSource(nodeClient: DgbNodeClient) : UtxoSource`; test `FakeUtxoSource`.

- [ ] **Step 1: Write the failing test**

```kotlin
// core/src/test/java/io/digibyte/core/recovery/UtxoSourceTest.kt
package io.digibyte.core.recovery

import io.digibyte.core.reconcile.ReconcileResult
import io.digibyte.core.reconcile.UtxoEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UtxoSourceTest {
    @Test
    fun fakeSource_returnsConfiguredUtxos() = runBlocking {
        val utxo = UtxoEntry("aa".repeat(32), 0, 100_000L, "Daddr", 100L, "76a914...88ac")
        val source: UtxoSource = FakeUtxoSource(
            mapOf("Daddr" to ReconcileResult(listOf(utxo), emptyMap(), 200L))
        )
        val result = source.fetchUtxos(listOf("Daddr"))
        assertEquals(1, result!!.utxos.size)
        assertEquals(100_000L, result.utxos[0].amountSatoshi)
    }

    @Test
    fun fakeSource_unreachableReturnsNull() = runBlocking {
        val source: UtxoSource = FakeUtxoSource(emptyMap(), reachable = false)
        assertNull(source.fetchUtxos(listOf("Daddr")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.UtxoSourceTest"`
Expected: FAIL — `UtxoSource` / `FakeUtxoSource` unresolved.

- [ ] **Step 3: Write the interface + backend impl**

```kotlin
// core/src/main/java/io/digibyte/core/recovery/UtxoSource.kt
package io.digibyte.core.recovery

import io.digibyte.core.reconcile.DgbNodeClient
import io.digibyte.core.reconcile.ReconcileResult

/**
 * Pluggable "given these addresses, return their UTXOs (+ raw txs)" lookup.
 * Decouples the recovery scan from any one backend so we can (a) unit-test the
 * classify pipeline with a fake and (b) add multi-Electrum fallback later
 * without touching scan/sweep logic. Returns null to mean "lookup failed /
 * unreachable" — distinct from a successful empty result.
 */
interface UtxoSource {
    suspend fun fetchUtxos(addresses: List<String>): ReconcileResult?
}

/** First implementation: the existing reconcile backend (api.digiscope.me). */
class ReconcileBackendUtxoSource(
    private val nodeClient: DgbNodeClient,
) : UtxoSource {
    override suspend fun fetchUtxos(addresses: List<String>): ReconcileResult? =
        nodeClient.reconcileAddresses(addresses)
}
```

- [ ] **Step 4: Write the fake test source**

```kotlin
// core/src/test/java/io/digibyte/core/recovery/FakeUtxoSource.kt
package io.digibyte.core.recovery

import io.digibyte.core.reconcile.ReconcileResult

/**
 * Deterministic UtxoSource for tests. Maps each queried address to a canned
 * ReconcileResult; addresses with no entry contribute nothing. When
 * reachable=false, every call returns null (simulates backend down).
 */
class FakeUtxoSource(
    private val byAddress: Map<String, ReconcileResult>,
    private val reachable: Boolean = true,
) : UtxoSource {
    var lastQueried: List<String>? = null
        private set

    override suspend fun fetchUtxos(addresses: List<String>): ReconcileResult? {
        lastQueried = addresses
        if (!reachable) return null
        val utxos = addresses.flatMap { byAddress[it]?.utxos.orEmpty() }
        val rawTxs = addresses.flatMap { byAddress[it]?.rawTxs?.entries.orEmpty() }
            .associate { it.key to it.value }
        return ReconcileResult(utxos, rawTxs, chainHeight = 0L)
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.UtxoSourceTest"`
Expected: PASS (both tests).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/io/digibyte/core/recovery/UtxoSource.kt \
        core/src/test/java/io/digibyte/core/recovery/FakeUtxoSource.kt \
        core/src/test/java/io/digibyte/core/recovery/UtxoSourceTest.kt
git commit -m "feat(recovery): pluggable UtxoSource seam + reconcile-backend impl"
```

---

## Task 2: Refactor `RecoveryScanService` onto `UtxoSource` + add seed-bytes variant + rewire DI

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/recovery/RecoveryScanService.kt`
- Modify: `app/src/main/java/io/digibyte/di/AppModule.kt:177-188`
- Test: `core/src/test/java/io/digibyte/core/recovery/RecoveryScanClassifyTest.kt` (create)

**Interfaces:**
- Consumes: `UtxoSource` (Task 1); `NativeBridge.mnemonicToSeed(phraseBytes, passphrase): ByteArray?`, `NativeBridge.deriveAddresses(...)`.
- Produces: `RecoveryScanService(utxoSource: UtxoSource)`; new `suspend fun scanFromSeed(seedBytes: ByteArray, passphrase: String?): State`. Existing `scan(mnemonic, passphrase): State` retained (delegates).

> Note: `scan()` invokes JNI (`mnemonicToSeed`, `deriveAddresses`) so it cannot run in a JVM unit test. This task's unit test only covers the address→UTXO **classification** by injecting a pre-derived address path. To keep that testable, factor the post-derivation classification into an internal `classifyDerived(profiles, derivedAddressesByProfile): State.Done` and test that. The JNI-backed full `scan` is covered by the instrumented test in Task 7.

- [ ] **Step 1: Read the current service**

Run: `sed -n '1,160p' core/src/main/java/io/digibyte/core/recovery/RecoveryScanService.kt`
Expected: see constructor `class RecoveryScanService(private val nodeClient: DgbNodeClient)` and the `scan` body calling `nodeClient.reconcileAddresses(addrs)`.

- [ ] **Step 2: Write the failing classify test**

```kotlin
// core/src/test/java/io/digibyte/core/recovery/RecoveryScanClassifyTest.kt
package io.digibyte.core.recovery

import io.digibyte.core.reconcile.ReconcileResult
import io.digibyte.core.reconcile.UtxoEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryScanClassifyTest {
    private val legacyProfile =
        DerivationProfile.BUILT_INS.first { it.label == "Legacy DigiByte mobile wallet" }

    @Test
    fun classify_marksNonNativeWithFunds() = runBlocking {
        val addr = "DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk"
        val utxo = UtxoEntry("aa".repeat(32), 0, 424_797_024L, addr, 100L, "76a914aa88ac")
        val source = FakeUtxoSource(mapOf(addr to ReconcileResult(listOf(utxo), emptyMap(), 200L)))
        val service = RecoveryScanService(source)

        val done = service.classifyDerived(
            mapOf(legacyProfile to listOf(addr)),
        )
        assertEquals(1, done.nonNativeWithFunds.size)
        assertEquals(424_797_024L, done.nonNativeWithFunds[0].totalSat)
    }

    @Test
    fun classify_backendDown_flagsUnreachable() = runBlocking {
        val source = FakeUtxoSource(emptyMap(), reachable = false)
        val service = RecoveryScanService(source)
        val done = service.classifyDerived(
            mapOf(legacyProfile to listOf("DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk")),
        )
        assertTrue(done.allBackendUnreachable)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.RecoveryScanClassifyTest"`
Expected: FAIL — constructor still takes `DgbNodeClient`; `classifyDerived` does not exist.

- [ ] **Step 4: Refactor the service**

Change the constructor and extract `classifyDerived`. In `RecoveryScanService.kt`:

```kotlin
// 1. Constructor:
class RecoveryScanService(
    private val utxoSource: UtxoSource,
) {
    // ...

    // 2. Extract the per-profile UTXO lookup + ProfileResult assembly into a
    //    suspend helper that takes already-derived addresses. The existing scan()
    //    keeps deriving addresses via JNI, then calls this.
    suspend fun classifyDerived(
        derivedByProfile: Map<DerivationProfile, List<String>>,
    ): State.Done {
        val results = derivedByProfile.map { (profile, addrs) ->
            val fetched = utxoSource.fetchUtxos(addrs)   // was nodeClient.reconcileAddresses(addrs)
            ProfileResult(
                profile = profile,
                addresses = addrs,
                utxos = fetched?.utxos ?: emptyList(),
                rawTxs = fetched?.rawTxs ?: emptyMap(),
                reachableBackend = fetched != null,
            )
        }
        return State.Done(results)
    }

    // 3. scan(mnemonic) derives per-profile addresses via JNI as before, then:
    //    val done = classifyDerived(derivedByProfile); _state.value = done; return done
    //    (Replace the inline reconcileAddresses loop with the call above.)

    // 4. Add the seed-bytes variant used by the Settings entry (no mnemonic String):
    suspend fun scanFromSeed(seedBytes: ByteArray, passphrase: String?): State {
        // Derive each profile's addresses directly from seedBytes via
        // NativeBridge.deriveAddresses(seedBytes, profile.hmacKey, profile.prefixPath,
        //   profile.gapExternal, profile.gapInternal, profile.addressFormat),
        // filtering empty strings, then call classifyDerived(...).
        // seedBytes is owned by the caller; do NOT zero it here.
    }
}
```

Keep `scan(mnemonic, passphrase)` deriving the seed internally via `mnemonicToSeed`, zeroing the seed in a `finally`, and delegating its post-derive work to `classifyDerived`.

- [ ] **Step 5: Rewire DI**

In `app/src/main/java/io/digibyte/di/AppModule.kt`, add a `UtxoSource` provider and update the scan-service provider:

```kotlin
@Provides @Singleton
fun provideUtxoSource(
    nodeClient: io.digibyte.core.reconcile.DgbNodeClient,
): io.digibyte.core.recovery.UtxoSource =
    io.digibyte.core.recovery.ReconcileBackendUtxoSource(nodeClient)

@Provides @Singleton
fun provideRecoveryScanService(
    utxoSource: io.digibyte.core.recovery.UtxoSource,
): io.digibyte.core.recovery.RecoveryScanService =
    io.digibyte.core.recovery.RecoveryScanService(utxoSource)
```

- [ ] **Step 6: Run unit tests + build**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.*"`
Expected: PASS.
Run: `./gradlew :app:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL (DI compiles with the new graph).

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/io/digibyte/core/recovery/RecoveryScanService.kt \
        core/src/test/java/io/digibyte/core/recovery/RecoveryScanClassifyTest.kt \
        app/src/main/java/io/digibyte/di/AppModule.kt
git commit -m "refactor(recovery): scan via UtxoSource, add seed-bytes scan, rewire DI"
```

---

## Task 3: `LegacySweepService` seed-bytes variant

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt:40-76`

**Interfaces:**
- Consumes: existing `sweepOneProfile(seed, result, destAddress, feePerKb)`, `NativeBridge.mnemonicToSeed`.
- Produces: new `suspend fun sweepFromSeed(seedBytes: ByteArray, nonNativeResults: List<RecoveryScanService.ProfileResult>, destAddress: String, feePerKb: Long = 100_000L): Result`. Existing `sweep(mnemonic, ...)` retained.

> No change to `sweepOneProfile` or the native signer — they already return `SweepOutcome` with `failureReason`/`txid`. We only add a `ByteArray`-seed entry point so the Settings path never builds a mnemonic `String`.

- [ ] **Step 1: Read the current sweep entry**

Run: `sed -n '40,76p' core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt`
Expected: `sweep(mnemonic, passphrase, nonNativeResults, destAddress, feePerKb)` derives `seed` via `mnemonicToSeed` then loops `sweepOneProfile`.

- [ ] **Step 2: Add the seed-bytes variant**

```kotlin
/** Seed-bytes entry point for the already-restored (Settings) path. The caller
 *  owns seedBytes and must zero it; we never derive a mnemonic String here. */
suspend fun sweepFromSeed(
    seedBytes: ByteArray,
    nonNativeResults: List<RecoveryScanService.ProfileResult>,
    destAddress: String,
    feePerKb: Long = 100_000L,
): Result {
    val outcomes = nonNativeResults.map { result ->
        if (result.profile.addressFormat == 2 /* P2SH-P2WPKH / BIP49 */) {
            SweepOutcome(result.profile, null, null, 0L, 0,
                "BIP49 P2SH-P2WPKH sweep not yet supported — manual recovery required")
        } else {
            sweepOneProfile(seedBytes, result, destAddress, feePerKb)
        }
    }
    return Result(outcomes)
}
```

Refactor the existing `sweep(mnemonic, ...)` to derive `seedBytes` then delegate to `sweepFromSeed(seedBytes, ...)`, zeroing `seedBytes` in a `finally`. (Keeps one code path for the per-profile loop.)

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :core:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/io/digibyte/core/recovery/LegacySweepService.kt
git commit -m "feat(recovery): seed-bytes sweep entry point for re-runnable recovery"
```

---

## Task 4: `SweepDestination` type + resolver

**Files:**
- Create: `core/src/main/java/io/digibyte/core/recovery/SweepDestination.kt`
- Test: `core/src/test/java/io/digibyte/core/recovery/SweepDestinationTest.kt`

**Interfaces:**
- Consumes: `(String) -> Boolean` validator (wraps `NativeBridge.isValidAddress`), `() -> String?` native-address supplier (wraps `getReceiveAddress(0, 2)`).
- Produces: `sealed class SweepDestination { object Native; data class External(val address: String) }`; `fun SweepDestination.resolve(nativeSupplier, validator): DestResolution`; `sealed class DestResolution { data class Ok(val address: String); data class Invalid(val reason: String) }`.

- [ ] **Step 1: Write the failing test**

```kotlin
// core/src/test/java/io/digibyte/core/recovery/SweepDestinationTest.kt
package io.digibyte.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepDestinationTest {
    @Test
    fun native_resolvesToWalletAddress() {
        val r = SweepDestination.Native.resolve(
            nativeSupplier = { "dgb1qnative" }, validator = { true })
        assertEquals(DestResolution.Ok("dgb1qnative"), r)
    }

    @Test
    fun native_missingWalletAddress_isInvalid() {
        val r = SweepDestination.Native.resolve(
            nativeSupplier = { null }, validator = { true })
        assertTrue(r is DestResolution.Invalid)
    }

    @Test
    fun external_validAddress_resolves() {
        val r = SweepDestination.External("Dgood").resolve(
            nativeSupplier = { "dgb1qnative" }, validator = { it == "Dgood" })
        assertEquals(DestResolution.Ok("Dgood"), r)
    }

    @Test
    fun external_invalidAddress_isInvalid() {
        val r = SweepDestination.External("xxx").resolve(
            nativeSupplier = { "dgb1qnative" }, validator = { false })
        assertTrue(r is DestResolution.Invalid)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.SweepDestinationTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement**

```kotlin
// core/src/main/java/io/digibyte/core/recovery/SweepDestination.kt
package io.digibyte.core.recovery

/** Where a sweep sends recovered funds. Default is the current wallet's own
 *  fresh native address; External is the opt-in "send elsewhere" path. */
sealed class SweepDestination {
    object Native : SweepDestination()
    data class External(val address: String) : SweepDestination()
}

sealed class DestResolution {
    data class Ok(val address: String) : DestResolution()
    data class Invalid(val reason: String) : DestResolution()
}

/**
 * Resolve to a concrete, validated destination address.
 * @param nativeSupplier returns the wallet's fresh native (bech32) receive addr.
 * @param validator returns true for a syntactically valid DGB address.
 */
fun SweepDestination.resolve(
    nativeSupplier: () -> String?,
    validator: (String) -> Boolean,
): DestResolution = when (this) {
    is SweepDestination.Native -> {
        val a = nativeSupplier()
        if (a.isNullOrEmpty()) DestResolution.Invalid("Wallet has no receive address yet")
        else DestResolution.Ok(a)
    }
    is SweepDestination.External -> {
        val a = address.trim()
        if (a.isEmpty()) DestResolution.Invalid("Enter an address")
        else if (!validator(a)) DestResolution.Invalid("Not a valid DigiByte address")
        else DestResolution.Ok(a)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.SweepDestinationTest"`
Expected: PASS (all four).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/digibyte/core/recovery/SweepDestination.kt \
        core/src/test/java/io/digibyte/core/recovery/SweepDestinationTest.kt
git commit -m "feat(recovery): SweepDestination (native default / validated external)"
```

---

## Task 5: `RecoverFundsViewModel`

**Files:**
- Create: `app/src/main/java/io/digibyte/ui/recovery/RecoverFundsViewModel.kt`

**Interfaces:**
- Consumes: `RecoveryScanService.scanFromSeed`, `LegacySweepService.sweepFromSeed`, `SweepDestination`/`resolve`, `NativeBridge.getReceiveAddress(0, 2)`, `NativeBridge.isValidAddress`, the wallet's `loadSeed(): ByteArray` (via the existing seed store / `WalletManager`).
- Produces: `RecoverFundsViewModel` exposing `StateFlow<UiState>` where `UiState = Classifying | Findings(list, totalSat) | Sweeping | Result(outcomes) | Error(reason)`, and `fun classify()`, `fun sweep(destination: SweepDestination)`.

> The ViewModel owns the seed lifecycle: load `ByteArray`, use it for classify+sweep, `fill(0)` in `finally`. It depends on the concrete `RecoveryScanService` (Hilt-injected) and constructs `LegacySweepService()` (stateless). Pure-logic (destination resolution, outcome→UiState mapping) is unit-tested in Task 4 and via the resolver; JNI-backed flows are exercised by the instrumented tests (Tasks 7–8).

- [ ] **Step 1: Implement the ViewModel**

```kotlin
// app/src/main/java/io/digibyte/ui/recovery/RecoverFundsViewModel.kt
package io.digibyte.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.recovery.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecoverFundsViewModel @Inject constructor(
    private val scanService: RecoveryScanService,
    private val seedProvider: SeedProvider,   // thin wrapper over the encrypted seed store; see Step 2
) : ViewModel() {

    sealed class UiState {
        object Idle : UiState()
        object Classifying : UiState()
        data class Findings(
            val findings: List<RecoveryScanService.ProfileResult>,
            val totalSat: Long,
            val backendUnreachable: Boolean,
        ) : UiState()
        object Sweeping : UiState()
        data class Done(val outcomes: List<LegacySweepService.SweepOutcome>) : UiState()
        data class Error(val reason: String) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var lastFindings: List<RecoveryScanService.ProfileResult> = emptyList()

    fun classify() {
        _state.value = UiState.Classifying
        viewModelScope.launch {
            val seed = seedProvider.loadSeed() ?: run {
                _state.value = UiState.Error("Wallet seed unavailable"); return@launch
            }
            try {
                when (val s = scanService.scanFromSeed(seed, passphrase = null)) {
                    is RecoveryScanService.State.Done -> {
                        lastFindings = s.nonNativeWithFunds
                        _state.value = if (s.allBackendUnreachable)
                            UiState.Error("Couldn't reach the lookup service — try again")
                        else UiState.Findings(s.nonNativeWithFunds, s.totalBalanceSat, false)
                    }
                    is RecoveryScanService.State.Failed -> _state.value = UiState.Error(s.reason)
                    else -> _state.value = UiState.Error("Scan did not complete")
                }
            } finally {
                seed.fill(0)
            }
        }
    }

    fun sweep(destination: SweepDestination) {
        val findings = lastFindings
        if (findings.isEmpty()) { _state.value = UiState.Error("Nothing to recover"); return }
        when (val res = destination.resolve(
            nativeSupplier = { NativeBridge.getReceiveAddress(0, format = 2) },
            validator = { NativeBridge.isValidAddress(it) },
        )) {
            is DestResolution.Invalid -> _state.value = UiState.Error(res.reason)
            is DestResolution.Ok -> {
                _state.value = UiState.Sweeping
                viewModelScope.launch {
                    val seed = seedProvider.loadSeed() ?: run {
                        _state.value = UiState.Error("Wallet seed unavailable"); return@launch
                    }
                    try {
                        val result = LegacySweepService().sweepFromSeed(
                            seedBytes = seed,
                            nonNativeResults = findings,
                            destAddress = res.address,
                        )
                        _state.value = UiState.Done(result.outcomes)
                    } catch (t: Throwable) {
                        _state.value = UiState.Error(t.message ?: "Sweep failed")
                    } finally {
                        seed.fill(0)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Add the `SeedProvider` seam + DI**

If a single-method seed loader does not already exist, add one wrapping the existing encrypted seed store used by `WalletManager.loadSeed()`:

```kotlin
// core/src/main/java/io/digibyte/core/recovery/SeedProvider.kt
package io.digibyte.core.recovery

/** Returns the wallet's seed/mnemonic as a zeroable ByteArray, or null if
 *  unavailable. Implemented over the existing encrypted seed store. */
fun interface SeedProvider { fun loadSeed(): ByteArray? }
```

Provide it in `AppModule` delegating to the existing seed-loading code (the same source `WalletManager` uses). If `WalletManager` already exposes a suitable `loadSeed(): ByteArray`, bind `SeedProvider` to it directly.

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/digibyte/ui/recovery/RecoverFundsViewModel.kt \
        core/src/main/java/io/digibyte/core/recovery/SeedProvider.kt \
        app/src/main/java/io/digibyte/di/AppModule.kt
git commit -m "feat(recovery): RecoverFundsViewModel (classify + sweep, seed-zeroing)"
```

---

## Task 6: `RecoverFundsScreen` + Settings entry + nav route

**Files:**
- Create: `app/src/main/java/io/digibyte/ui/recovery/RecoverFundsScreen.kt`
- Modify: `app/src/main/java/io/digibyte/ui/settings/SettingsScreen.kt` (add a `SettingsRow`)
- Modify: `app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt` (register `recover_funds`)

**Interfaces:**
- Consumes: `RecoverFundsViewModel.state`, `classify()`, `sweep(SweepDestination)`; `SettingsRow(...)` pattern.
- Produces: composable `RecoverFundsScreen(navController)`; nav route `"recover_funds"`.

- [ ] **Step 1: Implement the screen**

Follow the existing dark-theme `SettingsRow`/`SettingsCategory` styling. The screen renders by `state`:
- `Classifying`/`Sweeping` → progress + label.
- `Findings` → for each finding: label, `pathString()`, amount; a **Sweep** button (sweepable profiles) or a "wrapped-segwit — manual recovery for now" note (BIP49). A destination control defaulting to **"Into this wallet"**, with an **Advanced ▸ Send to another address** expander containing a text field (validated live via `isValidAddress`) and a red "This address is not in your wallet — sweeps are irreversible" warning. Sweep button calls `vm.sweep(SweepDestination.Native)` or `vm.sweep(SweepDestination.External(text))`.
- `Done` → per-outcome rows: success (txid, amount) or `failureReason`. "Recovered funds will appear once confirmed."
- `Error` → message + Retry (`vm.classify()`).

```kotlin
// app/src/main/java/io/digibyte/ui/recovery/RecoverFundsScreen.kt
package io.digibyte.ui.recovery

import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.digibyte.core.recovery.SweepDestination

@Composable
fun RecoverFundsScreen(
    navController: NavController,
    vm: RecoverFundsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) {
        if (state is RecoverFundsViewModel.UiState.Idle) vm.classify()
    }
    // Scaffold + TopAppBar("Recover funds from another wallet") ...
    when (val s = state) {
        is RecoverFundsViewModel.UiState.Classifying -> ScanningBody("Checking older derivation paths…")
        is RecoverFundsViewModel.UiState.Sweeping -> ScanningBody("Sweeping…")
        is RecoverFundsViewModel.UiState.Findings -> FindingsBody(
            findings = s.findings,
            onSweepNative = { vm.sweep(SweepDestination.Native) },
            onSweepExternal = { addr -> vm.sweep(SweepDestination.External(addr)) },
        )
        is RecoverFundsViewModel.UiState.Done -> ResultBody(s.outcomes)
        is RecoverFundsViewModel.UiState.Error -> ErrorBody(s.reason, onRetry = { vm.classify() })
        else -> Unit
    }
}
```

Implement `ScanningBody`, `FindingsBody` (with the destination default + Advanced expander + validation + irreversible warning), `ResultBody`, `ErrorBody` as private composables in this file, reusing the dark palette from `SettingsScreen.kt`.

- [ ] **Step 2: Register the nav route**

In `app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt`, alongside the existing `RecoveryScanScreen` composable entry:

```kotlin
composable("recover_funds") {
    io.digibyte.ui.recovery.RecoverFundsScreen(navController)
}
```

- [ ] **Step 3: Add the Settings row**

In `SettingsScreen.kt`, in the same category as "Scan for missing funds":

```kotlin
SettingsRow(
    icon = Icons.Default.SavingsOutlined,   // or another existing imported icon
    iconTint = Color(0xFF26C6DA),
    title = "Recover funds from another wallet",
    subtitle = "Sweep coins from old/other derivation paths into this wallet",
    onClick = { navController.navigate("recover_funds") }
)
```

- [ ] **Step 4: Build + install + manual smoke**

Run: `./gradlew :app:assembleMainnetDebug`
Expected: BUILD SUCCESSFUL.
Manual: open Settings → "Recover funds from another wallet" → confirm it classifies and renders Findings or a clean "No recoverable funds found".

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/digibyte/ui/recovery/RecoverFundsScreen.kt \
        app/src/main/java/io/digibyte/ui/settings/SettingsScreen.kt \
        app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt
git commit -m "feat(recovery): RecoverFundsScreen + Settings entry + nav route"
```

---

## Task 7: Replace onboarding silent auto-sweep with navigation + known-answer derivation test

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/onboarding/OnboardingViewModel.kt:139-176`
- Create: `native/src/androidTest/java/io/digibyte/native_core/LegacySweepDerivationTest.kt`

**Interfaces:**
- Consumes: `_scanResults` (`RecoveryScanService.State`), `nonNativeWithFunds`.
- Produces: a `StateFlow<Boolean>`/event `pendingLegacyRecovery` the onboarding UI observes to navigate to `recover_funds`; removal of the in-VM sweep.

- [ ] **Step 1: Replace the swallowing sweep block**

Delete lines 139-176 (the `try { … LegacySweepService().sweep(…) … } catch { log }`) and replace with a navigation signal:

```kotlin
// after recoverWallet success:
if (success) {
    val scan = _scanResults.value
    _pendingLegacyRecovery.value =
        scan is RecoveryScanService.State.Done && scan.nonNativeWithFunds.isNotEmpty()
}
```

Add the backing flow:

```kotlin
private val _pendingLegacyRecovery = MutableStateFlow(false)
val pendingLegacyRecovery: StateFlow<Boolean> = _pendingLegacyRecovery.asStateFlow()
```

The onboarding navigation observes `pendingLegacyRecovery`; when true, route to `recover_funds` (same screen as Settings) after the wallet lands. No sweep happens silently in the VM anymore.

- [ ] **Step 2: Write the known-answer derivation instrumented test**

```kotlin
// native/src/androidTest/java/io/digibyte/native_core/LegacySweepDerivationTest.kt
package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacySweepDerivationTest {
    private val HARD = 0x80000000.toInt()

    // A FIXED TEST seed — never funded on mainnet. Replace the addresses below
    // with values computed once via this same code, then pinned as the vector.
    private val testMnemonic =
        "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    @Test
    fun legacyDigiByteSeed_profileDerivesDeterministically() {
        val seed = NativeBridge.mnemonicToSeed(testMnemonic.toByteArray(), null)
        assertTrue(seed != null && seed.size == 64)
        val addrs = NativeBridge.deriveAddresses(
            seed!!, "DigiByte seed", intArrayOf(0 or HARD),
            /*gapExternal=*/5, /*gapInternal=*/0, /*format=*/0,
        )
        seed.fill(0)
        assertTrue(addrs != null && addrs.isNotEmpty())
        // First external address is stable for a fixed seed:
        assertEquals(EXPECTED_FIRST_LEGACY_ADDR, addrs!![0])
        addrs.forEach { assertTrue(it.isEmpty() || it.startsWith("D")) }
    }

    companion object {
        // Pin after first green run (record the printed value):
        const val EXPECTED_FIRST_LEGACY_ADDR = "REPLACE_AFTER_FIRST_RUN"
    }
}
```

> The `EXPECTED_FIRST_LEGACY_ADDR` constant is pinned once from the first run's actual output (it is deterministic for a fixed seed). On the first run, temporarily assert non-empty and log `addrs[0]`, copy the value into the constant, then assert equality. This converts the derivation into a regression-locked known-answer vector.

- [ ] **Step 3: Run the instrumented test on an emulator**

Run: `./gradlew :native:connectedMainnetDebugAndroidTest --tests "io.digibyte.native_core.LegacySweepDerivationTest"` (emulator `dgb-test-api33` running).
Expected: first run prints the address; pin it; second run PASS.

- [ ] **Step 4: Build app**

Run: `./gradlew :app:compileMainnetDebugKotlin`
Expected: BUILD SUCCESSFUL (onboarding VM compiles without the sweep block).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/digibyte/ui/onboarding/OnboardingViewModel.kt \
        native/src/androidTest/java/io/digibyte/native_core/LegacySweepDerivationTest.kt
git commit -m "feat(recovery): onboarding routes to RecoverFundsScreen; pin derivation vector"
```

---

## Task 8: Regtest harness + end-to-end sweep test

**Files:**
- Create: `scripts/regtest/start-regtest.sh`, `scripts/regtest/fund-legacy-address.sh`, `scripts/regtest/README.md`
- Create: `native/src/androidTest/java/io/digibyte/native_core/LegacySweepRegtestTest.kt`

**Interfaces:**
- Consumes: a local `digibyted -regtest` RPC; `NativeBridge.buildAndSignLegacySweep`, `deriveAddresses`, `mnemonicToSeed`.
- Produces: a repeatable e2e: fund a legacy-derived address → build+sign sweep → broadcast via regtest RPC → mine → assert UTXO spent and value at the native destination.

- [ ] **Step 1: Write the regtest start script**

```bash
# scripts/regtest/start-regtest.sh
#!/usr/bin/env bash
# Stand up a throwaway DigiByte regtest node for sweep e2e tests.
set -euo pipefail
DATADIR="${1:-/tmp/dgb-regtest}"
mkdir -p "$DATADIR"
cat > "$DATADIR/digibyte.conf" <<EOF
regtest=1
server=1
txindex=1
blockfilterindex=1
peerblockfilters=1
rpcuser=regtest
rpcpassword=regtest
[regtest]
rpcport=18443
EOF
digibyted -datadir="$DATADIR" -daemon
echo "regtest node up (rpc 18443, datadir $DATADIR)"
```

- [ ] **Step 2: Write the funding helper**

```bash
# scripts/regtest/fund-legacy-address.sh
#!/usr/bin/env bash
# Mine coins and send to a legacy-derived address so a sweep has something to take.
#   $1 = legacy address (D...)   $2 = amount DGB (default 4.24797024)
set -euo pipefail
CLI="digibyte-cli -datadir=${DATADIR:-/tmp/dgb-regtest} -regtest -rpcuser=regtest -rpcpassword=regtest"
ADDR="$1"; AMT="${2:-4.24797024}"
$CLI createwallet miner 2>/dev/null || $CLI loadwallet miner 2>/dev/null || true
MINE=$($CLI getnewaddress)
$CLI generatetoaddress 101 "$MINE" >/dev/null
TXID=$($CLI sendtoaddress "$ADDR" "$AMT")
$CLI generatetoaddress 1 "$MINE" >/dev/null
echo "$TXID"
```

- [ ] **Step 3: Document the harness**

`scripts/regtest/README.md`: prerequisites (`digibyted`/`digibyte-cli` on PATH), how to start/stop, the RPC creds, and that it is throwaway/never-mainnet. Include the exact `gettxout`/`getreceivedbyaddress` assertions used by the test.

- [ ] **Step 4: Write the e2e instrumented test**

```kotlin
// native/src/androidTest/java/io/digibyte/native_core/LegacySweepRegtestTest.kt
package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end against a local digibyted -regtest reachable from the emulator
 * (host 10.0.2.2:18443). Skips cleanly if the node is not running so CI without
 * regtest does not fail. Run the scripts/regtest harness first and fund the
 * legacy address printed by step 1.
 */
@RunWith(AndroidJUnit4::class)
class LegacySweepRegtestTest {
    private val HARD = 0x80000000.toInt()
    private val testMnemonic =
        "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    @Test
    fun sweep_movesLegacyUtxoToNativeAddress() {
        org.junit.Assume.assumeTrue(Regtest.isUp())  // skip if no regtest

        val seed = NativeBridge.mnemonicToSeed(testMnemonic.toByteArray(), null)!!
        val legacy = NativeBridge.deriveAddresses(
            seed, "DigiByte seed", intArrayOf(0 or HARD), 1, 0, 0)!![0]

        // Fund via RPC helper (Regtest wraps JSON-RPC to 10.0.2.2:18443):
        val (txid, vout, scriptHex, amountSat) = Regtest.fundAddress(legacy, 4.24797024)

        val dest = "REGTEST_NATIVE_DEST"  // a regtest-controlled bech32 addr
        val signed = NativeBridge.buildAndSignLegacySweep(
            seedBytes = seed,
            hmacKey = "DigiByte seed",
            prefixPath = intArrayOf(0 or HARD),
            txidsHex = arrayOf(txid),
            vouts = intArrayOf(vout),
            amounts = longArrayOf(amountSat),
            chainIndices = intArrayOf(0),
            addressIndices = intArrayOf(0),
            scriptPubKeysHex = arrayOf(scriptHex),
            destAddress = dest,
            feePerKb = 100_000L,
        )
        seed.fill(0)
        assertNotNull("sweep must sign", signed)

        val broadcastTxid = Regtest.sendRawTransaction(signed!!)
        Regtest.mine(1)
        assertTrue("original UTXO must be spent", Regtest.isSpent(txid, vout))
        assertTrue("dest must have received the funds (minus fee)",
            Regtest.receivedByAddress(dest) > 0L)
    }
}
```

> `Regtest` is a small test-only JSON-RPC helper (in the same androidTest source set) hitting `http://10.0.2.2:18443` with the regtest creds, exposing `isUp/fundAddress/sendRawTransaction/mine/isSpent/receivedByAddress`. `assumeTrue` makes the suite skip (not fail) when no regtest node is present, so it is safe in CI.

- [ ] **Step 5: Run the e2e on emulator with regtest up**

```bash
./scripts/regtest/start-regtest.sh
./gradlew :native:connectedMainnetDebugAndroidTest --tests "io.digibyte.native_core.LegacySweepRegtestTest"
```
Expected: PASS (UTXO spent, dest funded). Without regtest: test is skipped, suite green.

- [ ] **Step 6: Commit**

```bash
chmod +x scripts/regtest/*.sh
git add scripts/regtest/ native/src/androidTest/java/io/digibyte/native_core/LegacySweepRegtestTest.kt
git commit -m "test(recovery): regtest harness + end-to-end legacy sweep test"
```

---

## Task 9: Edge-case coverage (dust, multi-UTXO, missing scriptPubKey, BIP49, double-sweep)

**Files:**
- Modify: `native/src/androidTest/java/io/digibyte/native_core/LegacySweepRegtestTest.kt` (add cases)
- Modify: `core/src/test/java/io/digibyte/core/recovery/RecoveryScanClassifyTest.kt` (add cases)

**Interfaces:** consumes everything above; no new production types.

- [ ] **Step 1: Add pure-JVM classify edge tests**

Add to `RecoveryScanClassifyTest`:
- `classify_emptyUtxos_noFindings()` — fake returns reachable but no UTXOs → `nonNativeWithFunds` empty, `allBackendUnreachable=false`.
- `classify_multipleAddresses_sumsBalance()` — two funded addresses on one profile → `totalSat` is the sum.
- `bip49Profile_isNotSweepable()` — assert the BIP49 `DerivationProfile` has `addressFormat == 2` (so `sweepFromSeed` routes it to the "manual recovery" outcome).

```kotlin
@Test fun classify_emptyUtxos_noFindings() = runBlocking {
    val source = FakeUtxoSource(emptyMap())   // reachable, nothing funded
    val done = RecoveryScanService(source)
        .classifyDerived(mapOf(legacyProfile to listOf("Daddr")))
    assertTrue(done.nonNativeWithFunds.isEmpty())
    assertFalse(done.allBackendUnreachable)
}
```

- [ ] **Step 2: Run JVM edge tests**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.RecoveryScanClassifyTest"`
Expected: PASS.

- [ ] **Step 3: Add regtest edge cases**

Add to `LegacySweepRegtestTest` (all `assumeTrue(Regtest.isUp())`):
- `sweep_dustAfterFee_failsCleanly()` — fund 1 sat → `buildAndSignLegacySweep` returns null → assert the `SweepOutcome.failureReason` path (via `LegacySweepService.sweepFromSeed`) says dust/too-small, no broadcast.
- `sweep_multipleUtxosSameAddress_consolidates()` — two UTXOs on one address → one sweep tx spends both; dest receives sum − fee.
- `sweep_secondBroadcast_isRejected()` — broadcast the same signed sweep twice → second `sendRawTransaction` errors (double-spend); assert surfaced, not crashing.

- [ ] **Step 4: Run regtest edge cases**

Run: `./gradlew :native:connectedMainnetDebugAndroidTest --tests "io.digibyte.native_core.LegacySweepRegtestTest"`
Expected: PASS (or skipped without regtest).

- [ ] **Step 5: Commit**

```bash
git add core/src/test/java/io/digibyte/core/recovery/RecoveryScanClassifyTest.kt \
        native/src/androidTest/java/io/digibyte/native_core/LegacySweepRegtestTest.kt
git commit -m "test(recovery): dust/multi-utxo/bip49/double-sweep edge coverage"
```

---

## Task 10: Mainnet proof checklist + spec follow-on note

**Files:**
- Create: `docs/superpowers/plans/2026-06-29-legacy-funds-sweep-mainnet-proof.md`

**Interfaces:** none (documentation/validation task).

- [ ] **Step 1: Write the one-time mainnet validation procedure**

Document: build a release-lineage debug APK, restore a seed with real non-native funds (coordinate with the reporting user who holds `DCrAZfrumyKz36cDfE8YCL2fJc5eU7Ffxk` / tx `fad5f9b3…`, **or** a small self-funded legacy-path deposit), run classify → Sweep into native, confirm on-chain that the UTXO is spent and the funds land on a native `dgb1…` address, and that the wallet balance then reflects them via normal sync (no reconcile). Record txids and screenshots.

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/plans/2026-06-29-legacy-funds-sweep-mainnet-proof.md
git commit -m "docs(recovery): mainnet sweep validation checklist"
```

- [ ] **Step 3: Full regression sweep before release**

Run: `./gradlew :core:testMainnetDebugUnitTest`
Run: `./gradlew :app:assembleMainnetDebug`
Expected: green; APK builds.

---

## Self-Review

- **Spec coverage:** entry points (Task 6 Settings + Task 7 onboarding); pluggable UtxoSource (Tasks 1-2); classify→choose (Tasks 5-6); destination default + validated external (Tasks 4-6); nothing-silent error handling (Tasks 5-7 surface outcomes; backend-unreachable distinguished in Task 2); BIP49 detect-but-defer (Tasks 3, 9); seed zeroing (Tasks 2-3, 5); regtest-primary + known-answer + mainnet proof (Tasks 7-10). Out-of-scope items remain code-free.
- **Placeholders:** the only intentional fill-after-run value is `EXPECTED_FIRST_LEGACY_ADDR` (Task 7) and the regtest `REGTEST_NATIVE_DEST`/`Regtest` helper, both explicitly described as pin-on-first-run / test-harness, not logic gaps.
- **Type consistency:** `UtxoSource.fetchUtxos → ReconcileResult?` used identically in Tasks 1-2; `ProfileResult`, `SweepOutcome`, `Result`, `State.Done.nonNativeWithFunds/allBackendUnreachable/totalBalanceSat`, `SweepDestination`/`resolve`/`DestResolution`, `getReceiveAddress(0,2)`, `feePerKb=100_000L` consistent across tasks.
