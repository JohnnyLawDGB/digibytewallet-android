# DigiDollar Send UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user send DigiDollar from the app — a DGB⇄DD toggle in `SendScreen` that calls a single `sendDigiDollar` JNI (build+sign+publish in-memory), gated on `ddBalance > 0`.

**Architecture:** One JNI method composes the reviewed C-core (`BRDigiDollarAddressDecode` → `BRWalletCreateDigiDollarTransfer` → `seed_sign_transaction` → `BRPeerManagerPublishTx`) on an in-memory tx (never serializing between build and sign, so taproot input amounts/scripts survive). Kotlin bridge + `SendViewModel` DD mode + `SendScreen` toggle.

**Tech Stack:** C/JNI, Kotlin, Jetpack Compose.

**Source of truth:** `docs/superpowers/specs/2026-07-05-digidollar-send-ui-design.md`; wire format spec §4/§5.

## Global Constraints

- **Fund-moving wrapper — fail closed.** `sendDigiDollar` returns NULL (no half-send) if: no wallet/peerManager, session locked, TD decode fails, builder returns NULL (shortfall/bounds), or signing fails. The UI never builds tx bytes.
- **In-memory only:** build → sign → publish the SAME `BRTransaction*`; never serialize between build and sign (a serialized tx loses per-input amounts + prevout scripts the BIP341 taproot sighash needs).
- **Sign via `seed_sign_transaction(g_wallet, tx, 0)`** (the seed-accessor API used by `signTransaction`) — do NOT touch `g_seed` directly.
- **Publish via `BRWalletRegisterTransaction(g_wallet, tx)` then `BRPeerManagerPublishTx(g_peerManager, tx, NULL, NULL)`** — `BRPeerManagerPublishTx` takes ownership; do NOT free `tx` after it. On any pre-publish failure, `BRTransactionFree(tx)`.
- **Additive only:** new `external fun`s in `NativeBridge.kt` + its `androidTest` stub copy; no existing signature changed (SeedIsolationTest contract intact).
- **Amount bounds** enforced in C already (`[100, 10000000]` cents); the UI must still gate Send on TD-valid + `100 ≤ cents ≤ 10000000` + `cents ≤ ddBalance`.
- **DD-send UI visible only when `ddBalance > 0`** (mainnet/no-DD wallets: `SendScreen` unchanged).
- **No regression:** DGB send path untouched; `:app:assembleMainnetDebug` green; 42 security tests green.
- **isTestnet** for `BRDigiDollarAddressDecode` comes from the `BITCOIN_TESTNET` compile flag (mainnet build → mainnet `DD…` version; testnet build → `TD…`).
- **Submodule:** no C-core submodule change (all C-core exists); JNI + Kotlin are root-repo.

---

## File Structure

- **Modify (root):** `native/src/main/jni/bridge/jni_transaction.c` — `sendDigiDollar` + `isValidDigiDollarAddress` JNI.
- **Modify (root):** `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt` (+ the `native/src/androidTest/**/NativeBridge.kt` stub) — the two `external fun`s.
- **Modify (root):** `app/src/main/java/io/digibyte/ui/wallet/SendViewModel.kt` — DD mode + `sendDigiDollar()` action.
- **Create (root):** `app/src/test/java/io/digibyte/ui/wallet/DigiDollarSendValidationTest.kt` — USD→cents + bounds unit test.
- **Modify (root):** `app/src/main/java/io/digibyte/ui/wallet/SendScreen.kt` — the DGB⇄DD toggle + field adaptation.

---

## Task 1: JNI `sendDigiDollar` + `isValidDigiDollarAddress` + NativeBridge

**Files:**
- Modify: `native/src/main/jni/bridge/jni_transaction.c`
- Modify: `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt`, `native/src/androidTest/java/io/digibyte/core/bridge/NativeBridge.kt`

**Interfaces:**
- Consumes: `BRDigiDollarAddressDecode(uint8_t[32], const char*, int)`, `BRWalletCreateDigiDollarTransfer(BRWallet*, const uint8_t[32], uint64_t)`, `seed_sign_transaction(g_wallet, tx, 0)`, `BRTransactionIsSigned`, `BRWalletRegisterTransaction`, `BRPeerManagerPublishTx`, `seed_is_valid()`, globals `g_wallet`/`g_peerManager`. `#include "BRDigiDollar.h"`.
- Produces: `NativeBridge.sendDigiDollar(td, cents): String?`, `NativeBridge.isValidDigiDollarAddress(addr): Boolean`.

- [ ] **Step 1: Implement the JNI methods.** In `jni_transaction.c` (add `#include "BRDigiDollar.h"`; note `BITCOIN_TESTNET` is defined by the build flavor):

```c
/* ---------- isValidDigiDollarAddress ---------- */
JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_isValidDigiDollarAddress(JNIEnv *env, jobject thiz, jstring addr) {
    (void)thiz;
    if (! addr) return JNI_FALSE;
    const char *s = (*env)->GetStringUTFChars(env, addr, NULL);
    uint8_t key[32];
#ifdef BITCOIN_TESTNET
    int isTestnet = 1;
#else
    int isTestnet = 0;
#endif
    int ok = s && BRDigiDollarAddressDecode(key, s, isTestnet);
    if (s) (*env)->ReleaseStringUTFChars(env, addr, s);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* ---------- sendDigiDollar: decode TD -> build -> sign -> publish (in-memory) ---------- */
JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_sendDigiDollar(JNIEnv *env, jobject thiz, jstring tdAddress, jlong cents) {
    (void)thiz;
    if (! g_wallet || ! g_peerManager) { LOGW("sendDigiDollar: wallet/peerManager not ready"); return NULL; }
    if (! seed_is_valid()) { LOGW("sendDigiDollar: session locked (no seed)"); return NULL; }
    if (! tdAddress || cents <= 0) return NULL;

    const char *td = (*env)->GetStringUTFChars(env, tdAddress, NULL);
    uint8_t key[32];
#ifdef BITCOIN_TESTNET
    int isTestnet = 1;
#else
    int isTestnet = 0;
#endif
    int decoded = td && BRDigiDollarAddressDecode(key, td, isTestnet);
    if (td) (*env)->ReleaseStringUTFChars(env, tdAddress, td);
    if (! decoded) { LOGW("sendDigiDollar: bad TD address"); return NULL; }

    BRTransaction *tx = BRWalletCreateDigiDollarTransfer(g_wallet, key, (uint64_t)cents);
    if (! tx) { LOGW("sendDigiDollar: builder returned NULL (insufficient DD/DGB or bounds)"); return NULL; }

    int signed_ok = seed_sign_transaction(g_wallet, tx, 0);
    if (! signed_ok || ! BRTransactionIsSigned(tx)) {
        LOGW("sendDigiDollar: signing failed");
        BRTransactionFree(tx);
        return NULL;
    }

    /* txid (reversed display order) before publish — BRPeerManagerPublishTx takes ownership */
    UInt256 txHash = tx->txHash;
    char txidHex[65];
    for (int i = 0; i < 32; i++) sprintf(txidHex + i * 2, "%02x", txHash.u8[31 - i]);
    txidHex[64] = '\0';

    BRWalletRegisterTransaction(g_wallet, tx);
    BRPeerManagerPublishTx(g_peerManager, tx, NULL, NULL);   /* takes ownership; do NOT free tx */

    return (*env)->NewStringUTF(env, txidHex);
}
```
(If `LOGW`, `seed_is_valid`, `seed_sign_transaction`, `g_peerManager`, `UInt256` aren't already visible in `jni_transaction.c`, they are used by the neighboring `signTransaction`/`publishTransaction` — include the same headers those use.)

- [ ] **Step 2: NativeBridge external funs.** In `core/.../NativeBridge.kt`, near `publishTransaction` (`:59`):

```kotlin
    /** Build+sign+publish a DigiDollar transfer to a TD… address for `cents` USD cents.
     *  Returns the txid on success, or null on any failure (bad address, insufficient DD/DGB, sign/broadcast). */
    external fun sendDigiDollar(tdAddress: String, cents: Long): String?

    /** True if `addr` is a valid DigiDollar address for the current network (TD… testnet / DD… mainnet). */
    external fun isValidDigiDollarAddress(addr: String): Boolean
```
Add the same two declarations to `native/src/androidTest/java/io/digibyte/core/bridge/NativeBridge.kt` (the duplicate stub) so the androidTest source set compiles.

- [ ] **Step 3: Verify build.** `./gradlew :app:assembleMainnetDebug 2>&1 | tail -5` = BUILD SUCCESSFUL (compiles the JNI + links; an unresolved/misnamed native symbol fails here). `./gradlew :core:compileMainnetDebugKotlin 2>&1 | tail -5` succeeds.
- [ ] **Step 4: Regression.** `./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*" 2>&1 | tail -5` = 42/42 (SeedIsolationTest confirms the NativeBridge contract intact).
- [ ] **Step 5: Commit.** Root commit (JNI + both NativeBridge copies). `feat(digidollar): …` + `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

## Task 2: SendViewModel DD mode + validation unit test

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/wallet/SendViewModel.kt`
- Create: `app/src/test/java/io/digibyte/ui/wallet/DigiDollarSendValidationTest.kt`

**Interfaces:**
- Consumes: `NativeBridge.sendDigiDollar`, `NativeBridge.isValidDigiDollarAddress`, `NativeBridge.getDigiDollarBalance` (cents).
- Produces: `sendMode: StateFlow<SendMode>`, `toggleSendMode()`, `sendDigiDollar()`, companion `parseUsdToCents(String): Long?` and `ddAmountValid(cents, ddBalance): Boolean`.

- [ ] **Step 1: Write the failing validation unit test.** Create `DigiDollarSendValidationTest.kt`:

```kotlin
package io.digibyte.ui.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class DigiDollarSendValidationTest {
    @Test fun usdParsesToCents() {
        assertEquals(4000L, SendViewModel.parseUsdToCents("40"))
        assertEquals(4000L, SendViewModel.parseUsdToCents("40.00"))
        assertEquals(4050L, SendViewModel.parseUsdToCents("40.50"))
        assertEquals(9L,    SendViewModel.parseUsdToCents("0.09"))
        assertEquals(null,  SendViewModel.parseUsdToCents(""))
        assertEquals(null,  SendViewModel.parseUsdToCents("abc"))
        assertEquals(null,  SendViewModel.parseUsdToCents("-5"))
    }
    @Test fun ddAmountBoundsAndBalance() {
        // valid: within [100,10000000] and <= ddBalance
        org.junit.Assert.assertTrue(SendViewModel.ddAmountValid(4000, ddBalance = 10000))
        // below $1
        org.junit.Assert.assertFalse(SendViewModel.ddAmountValid(50, ddBalance = 10000))
        // above $100k
        org.junit.Assert.assertFalse(SendViewModel.ddAmountValid(10000001, ddBalance = 20000000))
        // exceeds balance
        org.junit.Assert.assertFalse(SendViewModel.ddAmountValid(4000, ddBalance = 1000))
    }
}
```

- [ ] **Step 2: Run to verify it fails.** `./gradlew :app:testMainnetDebugUnitTest --tests "*.DigiDollarSendValidationTest" 2>&1 | tail -8` → FAIL (unresolved).

- [ ] **Step 3: Add the companion helpers + DD mode.** In `SendViewModel.kt` companion object:

```kotlin
        /** "40.50" USD -> 4050 cents; null if blank/non-numeric/negative. */
        fun parseUsdToCents(s: String): Long? {
            val d = s.trim().toDoubleOrNull() ?: return null
            if (d < 0) return null
            return Math.round(d * 100.0)
        }
        /** DD send amount valid: within consensus [100, 10000000] cents and <= held balance. */
        fun ddAmountValid(cents: Long, ddBalance: Long): Boolean =
            cents in 100..10_000_000 && cents <= ddBalance
```
Add the mode + action (mirror the existing `send()` at `:210`):

```kotlin
    enum class SendMode { DGB, DD }
    private val _sendMode = MutableStateFlow(SendMode.DGB)
    val sendMode: StateFlow<SendMode> = _sendMode.asStateFlow()
    fun toggleSendMode() { _sendMode.value = if (_sendMode.value == SendMode.DGB) SendMode.DD else SendMode.DGB }

    fun sendDigiDollar(tdAddress: String, usd: String, onResult: (txid: String?) -> Unit) {
        val cents = parseUsdToCents(usd)
        if (cents == null || !NativeBridge.isValidDigiDollarAddress(tdAddress)) { onResult(null); return }
        viewModelScope.launch(Dispatchers.IO) {
            val txid = runCatching { NativeBridge.sendDigiDollar(tdAddress, cents) }
                .onFailure { android.util.Log.w("SendViewModel", "sendDigiDollar failed", it) }
                .getOrNull()
            onResult(txid)
        }
    }
```
(Expose `ddBalance` in this VM if not already — collect `NativeBridge.getDigiDollarBalance()` on the existing poll or inject from `WalletViewModel`; the UI reads it to gate the toggle + validate `cents <= ddBalance`.)

- [ ] **Step 4: Run to verify it passes.** Same command → PASS.
- [ ] **Step 5: Build + regression.** `./gradlew :app:assembleMainnetDebug` green; 42 security green.
- [ ] **Step 6: Commit.** Root commit.

---

## Task 3: SendScreen DGB⇄DD toggle

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/wallet/SendScreen.kt`

**Interfaces:**
- Consumes: `viewModel.sendMode`, `viewModel.ddBalance`, `viewModel.toggleSendMode()`, `viewModel.sendDigiDollar(...)`, `NativeBridge.isValidDigiDollarAddress` (via VM), `WalletViewModel.formatDigiDollar`.

- [ ] **Step 1: Add the toggle + DD field adaptation.** In `SendScreen.kt`: collect `sendMode` and `ddBalance`. When `ddBalance > 0`, render a compact segmented toggle (two `TextButton`s / a `SegmentedButton` row) "DGB | DigiDollar" above the address field, calling `viewModel.toggleSendMode()`. When `sendMode == DD`:
  - address field label → "DigiDollar address (TD…)", validity via `isValidDigiDollarAddress` (VM exposes a `ddAddressValid` StateFlow mirroring `addressValid`), error text "Invalid DigiDollar address";
  - amount field prefix/label → "$" USD (no DGB/fiat toggle);
  - a helper line "Network fee paid in DGB";
  - the Send button → `viewModel.sendDigiDollar(address, amountUsd) { txid -> if (txid != null) onSent(txid) else showError() }`, enabled only when `ddAddressValid && SendViewModel.ddAmountValid(parseUsdToCents(amt) ?: -1, ddBalance)`.
  When `sendMode == DGB` (or `ddBalance == 0`): the screen is exactly today's DGB send.

  (Follow the existing field/border/error pattern at `SendScreen.kt:150-190`; keep the composable render-only, logic in the VM.)

- [ ] **Step 2: Verify build.** `./gradlew :app:assembleMainnetDebug 2>&1 | tail -5` = BUILD SUCCESSFUL.
- [ ] **Step 3: Regression.** 42 security green; `./gradlew :app:testMainnetDebugUnitTest --tests "*.DigiDollarSendValidationTest"` PASS; the DGB send path visually unchanged when `ddBalance == 0`.
- [ ] **Step 4: Commit.** Root commit.

---

## Out of scope (later)

- **DD receive-address display** (encode our taproot key → our `TD…`) — a small follow-up increment.
- **Multi-recipient** DD send.
- **On-chain end-to-end** app send — validated once Task-4 funding lands (build-green now).
