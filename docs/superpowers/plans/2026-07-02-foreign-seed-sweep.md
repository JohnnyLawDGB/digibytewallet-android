# Foreign-Seed Sweep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Another wallet's phrase" mode to Recover Funds that scans an entered foreign BIP39 phrase and sweeps its funded paths (legacy P2PKH + native BIP84 P2WPKH; BIP49 deferred) into the current wallet.

**Architecture:** The engine already takes the seed as a parameter and signs both P2PKH and P2WPKH inputs. The only behavioral difference from today's own-seed flow is *which* profiles get swept: own-seed sweeps `nonNativeWithFunds` (native is already yours); foreign-seed sweeps **all** funded profiles incl. native. So the work is: (1) expose an `allWithFunds` set on the scan result, (2) a foreign classify/sweep path in the ViewModel that sources the seed from the entered phrase and zeros it, (3) a UI mode toggle + phrase field, (4) a P2WPKH-input signing KAT, (5) an on-chain device proof.

**Tech Stack:** Kotlin (core + Jetpack Compose), C/JNI signer (unchanged), JUnit (JVM) + instrumented `androidTest`, local `digibyte-cli` for the proof.

**Design spec:** `docs/superpowers/specs/2026-07-02-foreign-seed-sweep-design.md`

## Global Constraints

- The foreign seed is a `ByteArray`, **zeroed in `finally`** on every path, and **never persisted** (never written to KeyStore/prefs — only the user's own wallet seed is stored).
- Foreign sweeps always target **this wallet's own native address** (`SweepDestination.Native`), `destIsSelf = true`. No external destination in foreign mode.
- BIP49 (`addressFormat == 2`) stays deferred — funded BIP49 profiles are surfaced as "manual recovery," never silently swept (existing `sweepFromSeed` behavior — do not change it).
- No native/C signer change: the signer already handles P2PKH + P2WPKH.
- Commit trailer on every commit: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Build/test commands: JVM `./gradlew testMainnetDebugUnitTest`; instrumented `./gradlew :native:connectedMainnetDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>` on **`emulator-5554` (dgb-test-api33) only** (never api34/api35 — DigiAssets wallets; never the Note 8). App build `./gradlew :app:assembleMainnetDebug`.
- Real-funds device proof (Task 5) requires explicit user authorization before any `sendtoaddress`/`sendrawtransaction`.

---

### Task 1: Expose `allWithFunds` on the scan result

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/recovery/RecoveryScanService.kt` (the `State.Done` class, ~lines 32-46)
- Test: `core/src/test/java/io/digibyte/core/recovery/RecoveryScanClassifyTest.kt` (add one test)

**Interfaces:**
- Consumes: existing `State.Done.results: List<ProfileResult>`, `ProfileResult.totalSat: Long`, `ProfileResult.profile.isNative: Boolean`.
- Produces: `State.Done.allWithFunds: List<ProfileResult>` — every profile with `totalSat > 0` (native included), same order as `results`. (Existing `nonNativeWithFunds` stays for the own-seed path.)

- [ ] **Step 1: Write the failing test**

Add to `RecoveryScanClassifyTest.kt` (match the existing style — drive `classify`/`scanFromSeed` via `FakeUtxoSource`, or build `State.Done` directly from `ProfileResult`s if that's what sibling tests do):

```kotlin
@Test
fun allWithFunds_includesNativeAndNonNative_excludesEmpty() {
    // Two funded profiles (one native BIP84, one legacy) + one empty.
    val native = RecoveryScanService.ProfileResult(
        profile = DerivationProfile.BUILT_INS.first { it.isNative },
        addresses = listOf("dgb1qnative"),
        derivedAddresses = emptyList(),
        utxos = listOf(utxo("dgb1qnative", 300_000_000L)),
        rawTxs = emptyMap(),
    )
    val legacy = RecoveryScanService.ProfileResult(
        profile = DerivationProfile.BUILT_INS.first { !it.isNative && it.addressFormat == 0 },
        addresses = listOf("DLegacy"),
        derivedAddresses = emptyList(),
        utxos = listOf(utxo("DLegacy", 100_000_000L)),
        rawTxs = emptyMap(),
    )
    val empty = RecoveryScanService.ProfileResult(
        profile = DerivationProfile.BUILT_INS.first { !it.isNative && it.label.contains("BIP44 DGB") },
        addresses = listOf("DEmpty"),
        derivedAddresses = emptyList(),
        utxos = emptyList(),
        rawTxs = emptyMap(),
    )
    val done = RecoveryScanService.State.Done(listOf(native, legacy, empty))

    assertEquals(2, done.allWithFunds.size)
    assertTrue(done.allWithFunds.any { it.profile.isNative })          // native INCLUDED
    assertTrue(done.allWithFunds.any { !it.profile.isNative })
    assertFalse(done.allWithFunds.any { it.utxos.isEmpty() })          // empty EXCLUDED
    // Regression: nonNativeWithFunds still excludes native.
    assertTrue(done.nonNativeWithFunds.none { it.profile.isNative })
}
```

Add a small fixture helper if the test file doesn't already have one:
```kotlin
private fun utxo(addr: String, sat: Long) = io.digibyte.core.recovery.UtxoEntry(
    txid = "00", vout = 0, amountSatoshi = sat, address = addr,
    blockHeight = 0L, scriptPubKeyHex = "76a90088ac",
)
```
(If `RecoveryScanClassifyTest` already defines a `utxo(...)`/`UtxoEntry` fixture, reuse it and delete this helper — check first.)

- [ ] **Step 2: Run the test — expect FAIL**

Run: `./gradlew testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.RecoveryScanClassifyTest"`
Expected: FAIL — `unresolved reference: allWithFunds`.

- [ ] **Step 3: Add `allWithFunds` to `State.Done`**

In `RecoveryScanService.kt`, inside `data class Done`, next to `nonNativeWithFunds`:
```kotlin
            /** Every funded profile (native included) — the set to sweep when
             *  recovering a DIFFERENT wallet's phrase, where the native BIP84
             *  funds are foreign to this wallet and must be swept too. (The
             *  own-seed path uses [nonNativeWithFunds] instead, since native
             *  funds are already in this wallet.) BIP49 funded profiles are
             *  included here but are deferred inside the sweeper. */
            val allWithFunds: List<ProfileResult> =
                results.filter { it.totalSat > 0 }
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `./gradlew testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.RecoveryScanClassifyTest"`
Expected: PASS (all tests in the class, output pristine).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/digibyte/core/recovery/RecoveryScanService.kt \
        core/src/test/java/io/digibyte/core/recovery/RecoveryScanClassifyTest.kt
git commit -m "$(cat <<'EOF'
feat(recovery): expose allWithFunds (incl. native) for foreign-seed sweep

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: ViewModel foreign classify + sweep

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/recovery/RecoverFundsViewModel.kt` (add `isForeign` to `UiState.Findings`; add `classifyForeign` + `sweepForeign`; add a private `pendingForeignMnemonic`)
- Test: `core/src/test/java/io/digibyte/core/recovery/ForeignSweepSelectionTest.kt` (new — pure selection logic)

**Interfaces:**
- Consumes: `NativeBridge.isValidMnemonic(phrase: String): Boolean`; `NativeBridge.mnemonicToSeed(phraseBytes: ByteArray, passphrase: String?): ByteArray?`; `RecoveryScanService.scanFromSeed(seed: ByteArray): RecoveryScanService.State`; `State.Done.allWithFunds` (Task 1); `LegacySweepService.sweepFromSeed(seedBytes, nonNativeResults, destAddress, destIsSelf): LegacySweepService.Result` (param `nonNativeResults` sweeps whatever list it is given); `SweepDestination.Native`; `NativeBridge.getReceiveAddress(0, format = 2): String`.
- Produces: `UiState.Findings.isForeign: Boolean` (default false); `RecoverFundsViewModel.classifyForeign(mnemonic: String)`; `RecoverFundsViewModel.sweepForeign()`.

- [ ] **Step 1: Write a failing test for the pure selection rule**

The VM's foreign path calls native (`mnemonicToSeed`) so it is not JVM-runnable; extract the *decision* into a pure, testable function and test THAT. Create `core/src/main/java/io/digibyte/core/recovery/ForeignSweep.kt`:

```kotlin
package io.digibyte.core.recovery

/** Which scanned profiles to sweep. Own-seed recovery leaves native funds in
 *  place (already this wallet's); foreign-seed recovery takes everything funded
 *  incl. native. Pure + JVM-testable; the ViewModel calls this after scanning. */
fun sweepSet(done: RecoveryScanService.State.Done, isForeign: Boolean):
    List<RecoveryScanService.ProfileResult> =
    if (isForeign) done.allWithFunds else done.nonNativeWithFunds
```

Create `core/src/test/java/io/digibyte/core/recovery/ForeignSweepSelectionTest.kt`:
```kotlin
package io.digibyte.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignSweepSelectionTest {
    private fun res(isNative: Boolean, fmt: Int, sat: Long) =
        RecoveryScanService.ProfileResult(
            profile = DerivationProfile.BUILT_INS.first { it.isNative == isNative && it.addressFormat == fmt },
            addresses = listOf("a"), derivedAddresses = emptyList(),
            utxos = if (sat > 0) listOf(UtxoEntry("00", 0, sat, "a", 0L, "76a90088ac")) else emptyList(),
            rawTxs = emptyMap(),
        )

    @Test
    fun foreign_includesNative_own_excludesNative() {
        val done = RecoveryScanService.State.Done(
            listOf(res(true, 1, 200_000_000L), res(false, 0, 100_000_000L))
        )
        // Foreign: native + legacy both swept.
        assertEquals(2, sweepSet(done, isForeign = true).size)
        assertTrue(sweepSet(done, isForeign = true).any { it.profile.isNative })
        // Own: native left in place.
        assertEquals(1, sweepSet(done, isForeign = false).size)
        assertTrue(sweepSet(done, isForeign = false).none { it.profile.isNative })
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `./gradlew testMainnetDebugUnitTest --tests "io.digibyte.core.recovery.ForeignSweepSelectionTest"`
Expected: FAIL — `unresolved reference: sweepSet` (or the file doesn't compile until `ForeignSweep.kt` exists).

- [ ] **Step 3: Confirm PASS after adding `ForeignSweep.kt`**

`ForeignSweep.kt` from Step 1 provides `sweepSet`. Run the same command — expect PASS.

- [ ] **Step 4: Add the foreign path to the ViewModel**

In `RecoverFundsViewModel.kt`: (a) add `val isForeign: Boolean = false` to `UiState.Findings`; (b) add a private mnemonic holder; (c) add `classifyForeign` + `sweepForeign`. `import io.digibyte.core.recovery.sweepSet`.

```kotlin
    // Transient: the entered foreign phrase, held between classifyForeign and
    // sweepForeign so the sweep re-derives the same seed. Cleared after sweep /
    // on reset. (A JVM String can't be zeroed — same accepted limit as restore.)
    private var pendingForeignMnemonic: String? = null

    /** Scan a DIFFERENT wallet's phrase (not this wallet's stored seed). */
    fun classifyForeign(mnemonic: String) {
        val phrase = mnemonic.trim().split(Regex("\\s+")).joinToString(" ")
        if (!NativeBridge.isValidMnemonic(phrase)) {
            _state.value = UiState.Error("That doesn't look like a valid recovery phrase.")
            return
        }
        pendingForeignMnemonic = phrase
        _state.value = UiState.Classifying
        viewModelScope.launch {
            val seed = NativeBridge.mnemonicToSeed(phrase.toByteArray(), null) ?: run {
                _state.value = UiState.Error("Could not derive keys from that phrase."); return@launch
            }
            try {
                when (val s = withContext(Dispatchers.IO) { scanService.scanFromSeed(seed) }) {
                    is RecoveryScanService.State.Done -> {
                        val set = sweepSet(s, isForeign = true)      // includes native
                        _state.value = if (s.allBackendUnreachable) {
                            UiState.Error("Couldn't reach the lookup service — try again")
                        } else UiState.Findings(
                            findings = set, totalSat = set.sumOf { it.totalSat },
                            backendUnreachable = false, isForeign = true,
                        )
                    }
                    is RecoveryScanService.State.Failed -> _state.value = UiState.Error(s.reason)
                    else -> _state.value = UiState.Error("Scan did not complete")
                }
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.message ?: "Scan failed")
            } finally {
                seed.fill(0)
            }
        }
    }

    /** Sweep the previously-scanned foreign phrase into THIS wallet (native). */
    fun sweepForeign() {
        val phrase = pendingForeignMnemonic ?: run {
            _state.value = UiState.Error("Enter a recovery phrase and scan first."); return
        }
        val findings = (_state.value as? UiState.Findings)?.findings ?: emptyList()
        if (findings.isEmpty()) { _state.value = UiState.Error("Nothing to recover"); return }
        val dest = SweepDestination.Native.resolve(
            nativeSupplier = { NativeBridge.getReceiveAddress(0, format = 2) },
            validator = { NativeBridge.isValidAddress(it) },
        )
        if (dest !is DestResolution.Ok) {
            _state.value = UiState.Error("Could not get a destination address"); return
        }
        _state.value = UiState.Sweeping
        viewModelScope.launch {
            val seed = NativeBridge.mnemonicToSeed(phrase.toByteArray(), null) ?: run {
                _state.value = UiState.Error("Could not derive keys from that phrase."); return@launch
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    LegacySweepService(outgoingTxStore, walletTxPersister).sweepFromSeed(
                        seedBytes = seed,
                        nonNativeResults = findings,   // foreign: all-funded incl. native
                        destAddress = dest.address,
                        destIsSelf = true,             // lands in THIS wallet -> receive
                    )
                }
                _state.value = UiState.Done(result.outcomes)
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.message ?: "Sweep failed")
            } finally {
                seed.fill(0)
                pendingForeignMnemonic = null
            }
        }
    }
```

- [ ] **Step 5: Build (JVM path can't cover the native calls; build proves it compiles)**

Run: `./gradlew testMainnetDebugUnitTest && ./gradlew :app:assembleMainnetDebug`
Expected: both `BUILD SUCCESSFUL`. (`ForeignSweepSelectionTest` green; the `classifyForeign`/`sweepForeign` native path is exercised by Task 4 KAT + Task 5 device proof — note this in the report.)

**Reviewer checkpoint (spec §5/§7):** confirm the foreign path NEVER persists the seed — it calls no KeyStore / `SeedProvider` save/store API; the seed flows only into `mnemonicToSeed` / `scanFromSeed` / `sweepFromSeed` and is `fill(0)`-zeroed in `finally`. This is the negative of a native call, so it is verified by inspection, not a synthetic test.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/digibyte/ui/recovery/RecoverFundsViewModel.kt \
        core/src/main/java/io/digibyte/core/recovery/ForeignSweep.kt \
        core/src/test/java/io/digibyte/core/recovery/ForeignSweepSelectionTest.kt
git commit -m "$(cat <<'EOF'
feat(recovery): ViewModel foreign classify+sweep (entered phrase, seed zeroed)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: UI — mode toggle + phrase input

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/recovery/RecoverFundsScreen.kt`

**Interfaces:**
- Consumes: `vm.classify()`, `vm.classifyForeign(mnemonic)`, `vm.sweep(SweepDestination.Native)`, `vm.sweepForeign()`, `UiState.Findings.isForeign` (Task 2).
- Produces: (UI only) a `Mode` local; a phrase-input composable.

- [ ] **Step 1: Add a mode selector + phrase input; gate auto-classify by mode**

In `RecoverFundsScreen.kt`, replace the auto-classify `LaunchedEffect` and the `Box { when(state) … }` with a mode-aware version. Introduce a local mode and only auto-classify in "This wallet" mode:

```kotlin
    var mode by rememberSaveable { mutableStateOf(RecoverMode.ThisWallet) }
    var phrase by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(mode) {
        if (mode == RecoverMode.ThisWallet && state is RecoverFundsViewModel.UiState.Idle) vm.classify()
    }
```
Then inside the content `Box`, render the mode selector above the state body, and in foreign mode when `Idle`/`Error`, show the phrase field + Scan button:

```kotlin
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            ModeSelector(mode) { newMode ->
                mode = newMode
                phrase = ""
                vm.reset()   // add reset() to the VM (see Step 2) -> UiState.Idle
            }
            Box(Modifier.weight(1f)) {
                when (val s = state) {
                    is RecoverFundsViewModel.UiState.Classifying -> ScanningBody(
                        if (mode == RecoverMode.ThisWallet) "Checking older derivation paths…"
                        else "Scanning the entered phrase…")
                    is RecoverFundsViewModel.UiState.Sweeping -> ScanningBody("Sweeping…")
                    is RecoverFundsViewModel.UiState.Findings -> FindingsBody(
                        findings = s.findings, totalSat = s.totalSat,
                        // Foreign: sweep to THIS wallet only (no external option shown).
                        onSweepNative = { if (s.isForeign) vm.sweepForeign() else vm.sweep(SweepDestination.Native) },
                        onSweepExternal = if (s.isForeign) null else { addr -> vm.sweep(SweepDestination.External(addr)) },
                    )
                    is RecoverFundsViewModel.UiState.Done -> ResultBody(s.outcomes)
                    is RecoverFundsViewModel.UiState.Error ->
                        if (mode == RecoverMode.AnotherPhrase)
                            PhraseEntry(phrase, { phrase = it }, error = s.reason) { vm.classifyForeign(phrase) }
                        else ErrorBody(reason = s.reason, onRetry = { vm.classify() })
                    else -> // Idle
                        if (mode == RecoverMode.AnotherPhrase)
                            PhraseEntry(phrase, { phrase = it }, error = null) { vm.classifyForeign(phrase) }
                        else Unit
                }
            }
        }
```
Add the enum + composables at the bottom of the file:
```kotlin
enum class RecoverMode { ThisWallet, AnotherPhrase }

@Composable
private fun ModeSelector(mode: RecoverMode, onChange: (RecoverMode) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mode == RecoverMode.ThisWallet,
            onClick = { onChange(RecoverMode.ThisWallet) }, label = { Text("This wallet") })
        FilterChip(selected = mode == RecoverMode.AnotherPhrase,
            onClick = { onChange(RecoverMode.AnotherPhrase) }, label = { Text("Another wallet's phrase") })
    }
}

@Composable
private fun PhraseEntry(phrase: String, onPhrase: (String) -> Unit, error: String?, onScan: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Enter another wallet's recovery phrase to move its funds into this wallet. " +
             "The phrase is used once to sign the transfer and is never saved.",
             color = Color(0xFFB0BEC5), fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = phrase, onValueChange = onPhrase,
            modifier = Modifier.fillMaxWidth(), minLines = 3,
            label = { Text("12 or 24 word recovery phrase") },
            isError = error != null)
        if (error != null) Text(error, color = Color(0xFFE57373), fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onScan, enabled = phrase.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text("Scan for funds") }
    }
}
```
(Add the missing imports: `FilterChip`, `Arrangement`, `rememberSaveable`, `mutableStateOf`, `Column`, `Spacer`, `Button`, `OutlinedTextField`, etc. `FindingsBody`'s `onSweepExternal` param must become nullable `((String) -> Unit)?` — hide the external-address row when null.)

- [ ] **Step 2: Add `reset()` to the ViewModel**

In `RecoverFundsViewModel.kt`:
```kotlin
    /** Return to Idle and drop any held foreign phrase (mode switch / leaving). */
    fun reset() { pendingForeignMnemonic = null; _state.value = UiState.Idle }
```

- [ ] **Step 3: Build the app**

Run: `./gradlew :app:assembleMainnetDebug`
Expected: `BUILD SUCCESSFUL`. (Compose UI has no unit test here; verified by build + the Task 5 device run.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/digibyte/ui/recovery/RecoverFundsScreen.kt \
        app/src/main/java/io/digibyte/ui/recovery/RecoverFundsViewModel.kt
git commit -m "$(cat <<'EOF'
feat(recovery): Recover Funds mode toggle + foreign-phrase entry UI

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: P2WPKH-input signing KAT (native BIP84 sweep)

**Files:**
- Create: `native/src/androidTest/java/io/digibyte/native_core/LegacySweepSegwitKatTest.kt`

**Interfaces:**
- Consumes: `NativeBridge.mnemonicToSeed`, `NativeBridge.deriveAddresses`, `NativeBridge.buildAndSignLegacySweep`, `NativeBridge.isRawTransactionSigned`. (Model on the existing `LegacySweepSignedTxKatTest.kt`.)

Proves the signer produces a valid tx when the INPUT is native BIP84 (P2WPKH) — the input type foreign-seed sweep newly exercises and which no test covers yet.

- [ ] **Step 1: Write the KAT (pass-1 form: hex unpinned)**

Model on `LegacySweepSignedTxKatTest.kt`. Use the fixed abandon-…-about seed, its NATIVE path (`hmacKey = "Bitcoin seed"`, `prefixPath = m/84'/20'/0'`, chain 0 index 0), and a synthetic **P2WPKH** UTXO whose `scriptPubKeyHex` is `0014<hash160>` for that native address. Derive the native address in-test via `deriveAddresses(seed, "Bitcoin seed", intArrayOf(84|HARD,20|HARD,0|HARD), 1, 0, /*P2WPKH*/1)`; obtain its `0014…` scriptPubKey (prefer an existing native address→script helper — grep `NativeBridge`/`jni_derive.c` for one; if none, compute `0014` + the pubkey's hash160, deriving the pubkey the same way the KAT derives the address). Log the derived address + scriptPubKey on first run so the exact synthetic prevout is captured:

```kotlin
@RunWith(AndroidJUnit4::class)
class LegacySweepSegwitKatTest {
    private val HARD = 0x80000000.toInt()
    private val testMnemonic =
        "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    @Test
    fun sweep_signsSyntheticP2wpkhNativeUtxo_deterministically() {
        val seed = NativeBridge.mnemonicToSeed(testMnemonic.toByteArray(), null)
        assertTrue("seed 64 bytes", seed != null && seed.size == 64)
        val signedHex = NativeBridge.buildAndSignLegacySweep(
            seedBytes = seed!!,
            hmacKey = "Bitcoin seed",
            prefixPath = intArrayOf(84 or HARD, 20 or HARD, 0 or HARD),   // m/84'/20'/0'
            txidsHex = arrayOf(SYNTHETIC_TXID),
            vouts = intArrayOf(0),
            amounts = longArrayOf(500_000_000L),
            chainIndices = intArrayOf(0),
            addressIndices = intArrayOf(0),
            scriptPubKeysHex = arrayOf(SYNTHETIC_P2WPKH_SCRIPT),   // 0014<hash160 of native m/84'/20'/0'/0/0>
            destAddress = DEST_ADDRESS,
            feePerKb = 100_000L,
        )
        seed.fill(0)
        assertTrue("non-null signed hex", signedHex != null && signedHex.isNotEmpty())
        assertTrue("BRTransactionIsSigned", NativeBridge.isRawTransactionSigned(signedHex!!))
        android.util.Log.i("SegwitKat", "signed p2wpkh-input sweep hex = $signedHex")
        assertEquals(EXPECTED_SIGNED_TX_HEX, signedHex)
    }

    companion object {
        const val SYNTHETIC_TXID =
            "2222222222222222222222222222222222222222222222222222222222222222"
        // 0014 + hash160 of the abandon…about seed's m/84'/20'/0'/0/0 pubkey.
        // Pin from the first run's logcat (see Step 2).
        const val SYNTHETIC_P2WPKH_SCRIPT = "0014____PIN_FROM_RUN____"
        const val DEST_ADDRESS = "DBBSWfQdrDxq7S7YwZ6vi67BXZMvNKkAxe"
        const val EXPECTED_SIGNED_TX_HEX = ""   // pin in pass 2
    }
}
```

- [ ] **Step 2: Pin the synthetic script + hex (two-pass, on emulator-5554)**

Boot `dgb-test-api33` as `emulator-5554` only (never api34/api35/Note 8). Pass 1: with `SYNTHETIC_P2WPKH_SCRIPT` unknown and the `assertEquals` line commented, add a step that logs the derived native scriptPubKey; run:
```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :native:connectedMainnetDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.digibyte.native_core.LegacySweepSegwitKatTest
adb -s emulator-5554 logcat -d -s SegwitKat:*
```
Copy the derived `0014…` script into `SYNTHETIC_P2WPKH_SCRIPT`, re-run to get the signed hex, paste into `EXPECTED_SIGNED_TX_HEX`, uncomment `assertEquals`, re-run green.

- [ ] **Step 3: Structural cross-check via the node**

`digibyte-cli decoderawtransaction <EXPECTED_SIGNED_TX_HEX>` — confirm one vin, a `txinwitness` present on the input (segwit), and the output pays `DEST_ADDRESS`. Record in the report.

- [ ] **Step 4: Commit**

```bash
git add native/src/androidTest/java/io/digibyte/native_core/LegacySweepSegwitKatTest.kt
git commit -m "$(cat <<'EOF'
test(recovery): P2WPKH-input signing KAT (native BIP84 sweep)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: On-chain device proof — foreign phrase, legacy + native (USER-GATED)

**Files:**
- Create: `docs/superpowers/plans/2026-07-02-foreign-seed-sweep-proof.md` (runbook + recorded txids)

Extends the v3.8.0 mainnet proof to a foreign phrase with BOTH a legacy and a native funded input. **Moves real funds — requires explicit user authorization before each `sendtoaddress`/`sendrawtransaction`.** Emulator-5554 / node only; never the Note 8 for the sweep signing.

- [ ] **Step 1: Write the runbook** with the exact procedure: run `LegacyAddressGenTest` for a fresh throwaway phrase → derive its legacy `m/0'` AND native `m/84'` addresses (log both) → fund each with a small amount → in the app's "Another wallet's phrase" mode (emulator-5554, v3.8.0+build) enter the phrase → Scan → Sweep → verify on-chain both source UTXOs spent and funds landed on this wallet's native address.

- [ ] **Step 2: Execute (gated on user go-ahead)** — record funding txids, sweep txid, block height, and `gettxout` (spent) + destination receipt into the runbook. Confirm the app shows the swept funds as a RECEIVE (not a negative "Sent"), validating the `destIsSelf` path for the foreign case.

- [ ] **Step 3: Regression + commit** — `./gradlew testMainnetDebugUnitTest` green + `:app:assembleMainnetDebug` builds; commit the runbook.

---
