# Send Screen Fee Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 3-tier Bitcoin-style fee selector with a single default fee and optional custom fee input for DigiByte's 15-second blocks.

**Architecture:** Remove fee tier state from SendViewModel, replace with default/custom fee toggle. Default fee uses `DEFAULT_FEE_PER_KB` (100 sat/byte). Custom fee lets user type total DGB amount, back-calculated to sat/KB rate. Warning system flags fees below relay minimum. SendScreen swaps tier chips for a single fee line with Custom toggle.

**Tech Stack:** Kotlin (Jetpack Compose), C (JNI/NDK)

---

### Task 1: Refactor SendViewModel — remove tiers, add custom fee

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/wallet/SendViewModel.kt`

- [ ] **Step 1: Replace fee tier state with custom fee state**

Replace lines 26-58 (the `FEE_DEFAULTS`, `selectedFeeTier`, and `feeEstimate` block) with:

```kotlin
/** Estimated vsize for a typical 1-input P2WPKH → 2-output transaction. */
private val TYPICAL_TX_VSIZE = 141L

/** Default fee rate: 100,000 sat/KB (100 sat/byte) — DigiByte min relay fee. */
private val DEFAULT_FEE_PER_KB = 100_000L

sealed class FeeWarning {
    data object None : FeeWarning()
    data object BelowRelay : FeeWarning()
    data object ZeroFee : FeeWarning()
}

@HiltViewModel
class SendViewModel @Inject constructor(
    private val transactionBuilder: TransactionBuilder,
    private val utxoManager: UtxoManager,
    private val priceProvider: PriceProvider
) : ViewModel() {

    /** Destination address — validated on change. */
    val address = MutableStateFlow("")

    /** Is the current address valid? */
    private val _addressValid = MutableStateFlow<Boolean?>(null)
    val addressValid: StateFlow<Boolean?> = _addressValid.asStateFlow()

    /** Amount in DGB (user text input). */
    val amountDgb = MutableStateFlow("")

    /** Amount in fiat (user text input or converted). Kept in sync with amountDgb. */
    val amountFiat = MutableStateFlow("")

    /** Whether user has toggled custom fee mode. */
    val isCustomFee = MutableStateFlow(false)

    /** Custom fee input in DGB (text field value). */
    val customFeeInput = MutableStateFlow("")

    /** Default fee estimate in satoshis for a typical transaction. */
    val defaultFeeSat: Long = TYPICAL_TX_VSIZE * DEFAULT_FEE_PER_KB / 1000

    /** Fee rate in sat/KB to pass to the C core. */
    val feeRatePerKb: StateFlow<Long> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (!custom) {
            DEFAULT_FEE_PER_KB
        } else {
            val feeDgb = input.replace(",", "").toDoubleOrNull() ?: 0.0
            val feeSat = (feeDgb * 100_000_000).toLong()
            if (feeSat <= 0 || TYPICAL_TX_VSIZE <= 0) return@combine DEFAULT_FEE_PER_KB
            (feeSat * 1000) / TYPICAL_TX_VSIZE
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_FEE_PER_KB)

    /** Estimated total fee in satoshis (for display). */
    val estimatedFeeSat: StateFlow<Long> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (!custom) {
            defaultFeeSat
        } else {
            val feeDgb = input.replace(",", "").toDoubleOrNull() ?: 0.0
            (feeDgb * 100_000_000).toLong()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, defaultFeeSat)

    /** Warning state for the custom fee. */
    val feeWarning: StateFlow<FeeWarning> = combine(isCustomFee, customFeeInput) { custom, input ->
        if (!custom) return@combine FeeWarning.None
        val feeDgb = input.replace(",", "").toDoubleOrNull() ?: 0.0
        val feeSat = (feeDgb * 100_000_000).toLong()
        if (feeSat <= 0) return@combine FeeWarning.ZeroFee
        val satPerVbyte = feeSat.toDouble() / TYPICAL_TX_VSIZE
        if (satPerVbyte < 100.0) FeeWarning.BelowRelay else FeeWarning.None
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FeeWarning.None)

    /** Current send flow state. */
    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    /** Error message for validation failures shown inline. */
    private val _validationError = MutableStateFlow<String?>(null)
    val validationError: StateFlow<String?> = _validationError.asStateFlow()
```

Note: The `SendState` sealed class (lines 18-24) stays unchanged at the top of the file.

- [ ] **Step 2: Update `send()` to use `feeRatePerKb`**

Replace the `send()` function (lines 171-191) with:

```kotlin
    fun send() {
        val addr = address.value
        val sats = amountSatoshis() ?: run {
            _sendState.value = SendState.Error("Invalid amount")
            return
        }
        val feePerKb = feeRatePerKb.value

        _sendState.value = SendState.Sending

        viewModelScope.launch {
            val utxos = utxoManager.getSpendableUtxos().first()
            val result = transactionBuilder.sendTransaction(addr, sats, feePerKb, utxos)
            _sendState.value = when (result) {
                is TxResult.Success -> SendState.Success(result.txid)
                is TxResult.Error   -> SendState.Error(result.message)
            }
        }
    }
```

- [ ] **Step 3: Remove old fee tier methods**

Delete `feeTierLabel()` (lines 199-203) and `feeTierSatPerKb()` (lines 206-209).

Add a helper for toggling custom fee:

```kotlin
    fun toggleCustomFee() {
        val wasCustom = isCustomFee.value
        isCustomFee.value = !wasCustom
        if (!wasCustom) {
            // Pre-populate with default fee when entering custom mode
            customFeeInput.value = String.format("%.8f", defaultFeeSat / 100_000_000.0)
        }
    }
```

- [ ] **Step 4: Verify compilation**

Run: `cd /home/polloloco/digibytewallet-android && ./gradlew :app:compileMainnetDebugKotlin 2>&1 | tail -10`

Note: This will fail because SendScreen still references removed fields. That's expected — Task 2 fixes it. If you want to verify just the ViewModel compiles, temporarily comment out the SendScreen references. Otherwise proceed to Task 2 and compile after both are done.

- [ ] **Step 5: Commit**

```bash
cd /home/polloloco/digibytewallet-android
git add app/src/main/java/io/digibyte/ui/wallet/SendViewModel.kt
git commit -m "$(cat <<'EOF'
refactor: replace fee tiers with single default + custom fee in SendViewModel

Remove 3-tier fee selector (Next Block / 5 Minutes / Economy). DigiByte's
15-second blocks and empty mempool mean all tiers confirm instantly.

New model: single default fee (100 sat/byte) with optional custom total
fee input in DGB. Warning system flags fees below relay minimum.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Rewrite SendScreen fee section

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/wallet/SendScreen.kt`

- [ ] **Step 1: Update state collection at top of SendScreen**

Replace lines 61-63:

```kotlin
    val selectedFeeTier by viewModel.selectedFeeTier.collectAsStateWithLifecycle()
    val feeEstimate by viewModel.feeEstimate.collectAsStateWithLifecycle()
```

With:

```kotlin
    val isCustomFee by viewModel.isCustomFee.collectAsStateWithLifecycle()
    val customFeeInput by viewModel.customFeeInput.collectAsStateWithLifecycle()
    val estimatedFeeSat by viewModel.estimatedFeeSat.collectAsStateWithLifecycle()
    val feeWarning by viewModel.feeWarning.collectAsStateWithLifecycle()
```

- [ ] **Step 2: Update confirmation dialog call**

Replace the `feeEstimate = feeEstimate` parameter in the `SendConfirmationDialog` call (line 86) with:

```kotlin
            feeEstimate = estimatedFeeSat,
```

- [ ] **Step 3: Replace fee tier section with new fee UI**

Replace lines 251-281 (from `// ── Fee tier selector` through the `sat/KB` text) with:

```kotlin
        // ── Network fee ──────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Network Fee",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { viewModel.toggleCustomFee() }) {
                Text(
                    text = if (isCustomFee) "Default" else "Custom",
                    style = MaterialTheme.typography.labelMedium,
                    color = DigiByteAccent
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (isCustomFee) {
            OutlinedTextField(
                value = customFeeInput,
                onValueChange = { viewModel.customFeeInput.value = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("0.00014100") },
                suffix = { Text("DGB", color = DigiByteAccent, fontWeight = FontWeight.Bold) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                isError = feeWarning is FeeWarning.ZeroFee
            )
        } else {
            val defaultFeeDgb = viewModel.defaultFeeSat / 100_000_000.0
            Text(
                text = String.format("%.8f DGB", defaultFeeDgb),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Fee status / warning
        when (feeWarning) {
            is FeeWarning.BelowRelay -> {
                Text(
                    text = "⚠ Below minimum relay fee — transaction may not broadcast",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFA000) // amber
                )
            }
            is FeeWarning.ZeroFee -> {
                Text(
                    text = "Fee required",
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteRed
                )
            }
            is FeeWarning.None -> {
                Text(
                    text = "Confirms in ~15 seconds",
                    style = MaterialTheme.typography.labelSmall,
                    color = DigiByteGreen
                )
            }
        }
```

- [ ] **Step 4: Add FeeWarning import**

At the top of SendScreen.kt, the `FeeWarning` class is in the same package (defined in SendViewModel.kt), so no import is needed. Verify this compiles.

- [ ] **Step 5: Delete the FeeTierChip composable**

Delete the entire `FeeTierChip` composable function (lines 339-375 in the original file). It is no longer used.

- [ ] **Step 6: Verify full compilation**

Run: `cd /home/polloloco/digibytewallet-android && ./gradlew :app:compileMainnetDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
cd /home/polloloco/digibytewallet-android
git add app/src/main/java/io/digibyte/ui/wallet/SendScreen.kt
git commit -m "$(cat <<'EOF'
ui: replace fee tier chips with default fee + custom toggle

Remove 3 fee tier chips (Next Block / 5 Minutes / Economy).
New UI: single default fee line showing DGB amount + "Confirms in ~15
seconds", with Custom toggle for power users to enter total fee in DGB.
Amber warning for fees below relay minimum, red for zero fee.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Simplify C core getEstimatedFee

**Files:**
- Modify: `native/src/main/jni/bridge/jni_transaction.c:220-235`

- [ ] **Step 1: Simplify getEstimatedFee to return DEFAULT_FEE_PER_KB always**

Replace the `getEstimatedFee` function (the last function in the file):

```c
JNIEXPORT jlong JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getEstimatedFee(JNIEnv *env, jobject thiz,
                                                           jint priority) {
    (void)env;
    (void)thiz;

    /* Fee tiers — DigiByte fees are simple, based on sat/KB */
    /* priority: 0=high, 1=medium, 2=low */
    switch (priority) {
        case 0:  return (jlong)(DEFAULT_FEE_PER_KB * 5);   /* high: 5x default */
        case 1:  return (jlong)(DEFAULT_FEE_PER_KB * 2);   /* medium: 2x default */
        case 2:  return (jlong)DEFAULT_FEE_PER_KB;          /* low: default min relay */
        default: return (jlong)DEFAULT_FEE_PER_KB;
    }
}
```

With:

```c
JNIEXPORT jlong JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getEstimatedFee(JNIEnv *env, jobject thiz,
                                                           jint priority) {
    (void)env;
    (void)thiz;
    (void)priority;

    /* DigiByte has 15-second blocks and no fee market.
     * All transactions at the minimum relay fee confirm in the next block.
     * Return DEFAULT_FEE_PER_KB (100 sat/byte) for all priority levels. */
    return (jlong)DEFAULT_FEE_PER_KB;
}
```

- [ ] **Step 2: Verify native compilation**

Run: `cd /home/polloloco/digibytewallet-android && ./gradlew :native:assembleMainnetDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
cd /home/polloloco/digibytewallet-android
git add native/src/main/jni/bridge/jni_transaction.c
git commit -m "$(cat <<'EOF'
simplify: getEstimatedFee returns DEFAULT_FEE_PER_KB for all priorities

DigiByte has 15-second blocks and no fee market. The 5x/2x/1x multipliers
were Bitcoin-style fee tiers that served no purpose — all transactions at
minimum relay fee confirm in the next block.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Full build, deploy, and device test

**Files:**
- No new file changes — build and verify

- [ ] **Step 1: Full build**

Run: `cd /home/polloloco/digibytewallet-android && ./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all tests**

Run: `cd /home/polloloco/digibytewallet-android && ./gradlew testMainnetDebugUnitTest 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 3: Deploy to device**

Run: `adb install -r /home/polloloco/digibytewallet-android/app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk`
Expected: Success

- [ ] **Step 4: Verify send screen UI**

After unlocking the wallet:
1. Navigate to Send screen
2. Verify: single fee line showing "0.00014100 DGB" + "Confirms in ~15 seconds" in green
3. Verify: "Custom" link visible
4. Tap "Custom" — verify text field appears pre-populated with default fee, toggle changes to "Default"
5. Enter a very small fee (e.g., 0.00000001) — verify amber warning "Below minimum relay fee"
6. Clear the field — verify red "Fee required"
7. Tap "Default" — verify returns to single line display

- [ ] **Step 5: Test send with default fee**

1. Enter a self-send (receive address + small amount like 0.1 DGB)
2. Tap "Review & Send"
3. Verify confirmation dialog shows fee in DGB
4. Confirm and send
5. Verify broadcast succeeds via logcat: `adb logcat -d | grep "DGB-JNI" | grep "publish"`
Expected: `publishTransaction: broadcast succeeded`

- [ ] **Step 6: Commit build verification (if any adjustments needed)**

If any adjustments were needed during testing, commit them. Otherwise this step is a no-op.
