# DigiDollar Wallet-Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect DigiDollar token outputs the wallet receives, track a separate DD balance (cents), expose it via JNI, and show it as a slim line on the wallet hero card — plus the `ddUtxos` set that a future SEND will consume.

**Architecture:** Mirror the existing DigiAsset `assetUtxos` precedent. A DD-detection branch in `_BRWalletUpdateBalance` routes DD outputs (classified by the already-built `BRDigiDollarOutputAmount`) into a new `wallet->ddUtxos` set and accumulates `wallet->ddBalance`, never touching the DGB satoshi balance; spent DD is pruned. Accessors → JNI → ViewModel StateFlow → hero-card line.

**Tech Stack:** C (breadwallet fork, `digibytewallet-core` submodule), host KAT via `clang` (as the taproot/DD-decoder KATs), JNI, Kotlin, Jetpack Compose.

**Source of truth:** `docs/superpowers/specs/2026-07-05-digidollar-wallet-wiring-design.md`; wire format `docs/superpowers/specs/2026-07-04-digidollar-wire-format.md`.

## Global Constraints

- **DD balance is CENTS (USD), the DGB balance is SATOSHIS. They never mix.** `wallet->ddBalance` is `uint64_t` cents; DD outputs add **0** to the DGB `balance`.
- **DD classification is `BRDigiDollarOutputAmount(tx, j) >= 0`** (from `BRDigiDollar.c`): signature `int64_t BRDigiDollarOutputAmount(const BRTransaction *tx, size_t voutIndex);` — returns cents for a DD token output of a `0x0770`-marked tx, else `-1`.
- **Ownership gate is unchanged:** a DD output is credited only inside the existing `BRSetContains(wallet->allAddrs, output.address)` check (`BRWallet.c:248`). Our taproot receive addresses are already in `allAddrs` after `BRWalletSetTaprootKey`.
- **Spent DD must be pruned** so the shown balance is spendable, not cumulative (mirror the DGB spent-prune at `BRWallet.c:269-276`).
- **Additive only:** do not change any existing function signature. `NativeBridge.kt`'s existing `external fun`s are asserted by `SeedIsolationTest` — add new ones, never modify.
- **No regression:** `./gradlew :app:assembleMainnetDebug` green; `./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*"` 42/42 green; the DGB balance path unchanged.
- **Submodule commits** (`BRWallet.c/.h`, `BRDigiDollar.*` untouched here) via `git -C native/src/main/jni/digibytewallet-core commit -F -` (NOT `eval`). Root commits for JNI/Kotlin/UI + the submodule pin bump.
- **testnet26 on-chain proof is DEFERRED** (needs rc46 node). This plan proves the logic with host KATs + build-green.

---

## File Structure

- **Modify (submodule):** `BRWallet.c` — struct fields `ddUtxos`/`ddBalance`, init/free, `_BRWalletUpdateBalance` DD branch + spent-prune, `BRWalletDigiDollarBalance`, `BRWalletDigiDollarUTXOs`; add `#include "BRDigiDollar.h"`.
- **Modify (submodule):** `BRWallet.h` — declare the two accessors.
- **Create (root):** `native/src/test/host/digidollar_wallet_kat/{digidollar_wallet_kat_main.c,run.sh}` — wallet-level host KAT.
- **Modify (root):** `native/src/main/jni/bridge/jni_wallet.c` — `getDigiDollarBalance` JNI method.
- **Modify (root):** `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt` — `external fun getDigiDollarBalance(): Long` (+ the duplicate stub under `native/src/androidTest/` if present).
- **Modify (root):** `app/src/main/java/io/digibyte/ui/wallet/WalletViewModel.kt` — `_ddBalance` StateFlow, poll, `formatDigiDollar`.
- **Create (root):** `app/src/test/java/io/digibyte/ui/wallet/DigiDollarFormatTest.kt` — unit test for the formatter.
- **Modify (root):** `app/src/main/java/io/digibyte/ui/components/BalanceDisplay.kt` + `WalletScreen.kt` — the hero-card DD line.

---

## Task 1: C core — DD detection, balance accounting, spent-prune, `BRWalletDigiDollarBalance` + host KAT

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRWallet.c` (struct, init/free, `_BRWalletUpdateBalance`, accessor, include)
- Modify: `native/src/main/jni/digibytewallet-core/BRWallet.h` (accessor decl)
- Create: `native/src/test/host/digidollar_wallet_kat/digidollar_wallet_kat_main.c` + `run.sh`

**Interfaces:**
- Consumes: `BRDigiDollarOutputAmount(const BRTransaction*, size_t) -> int64_t` (from `BRDigiDollar.c`, already built); `BRWalletSetTaprootKey`, `BRWalletReceiveAddress(wallet, 2)`, `BRWalletRegisterTransaction`, `BRWalletBalance`.
- Produces: `uint64_t BRWalletDigiDollarBalance(BRWallet *wallet);` and `wallet->ddUtxos`/`wallet->ddBalance` (Task 2 reads `ddUtxos`).

- [ ] **Step 1: Write the failing KAT.** Create `native/src/test/host/digidollar_wallet_kat/digidollar_wallet_kat_main.c`:

```c
// Wallet-level KAT: DD outputs paid to our taproot address are detected, summed
// into a cents DD balance (never the DGB balance), and pruned when spent.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include "BRWallet.h"
#include "BRBIP32Sequence.h"
#include "BRBIP39Mnemonic.h"
#include "BRAddress.h"
#include "BRDigiDollar.h"

static int g_fail = 0;
static void check(int c, const char *d){ printf(c?"PASS: %s\n":"FAIL: %s\n", d); if(!c) g_fail++; }

// canonical all-zeros mnemonic; its m/86'/20'/0'/0/0 P2TR addr is KAT-pinned elsewhere
static const char *kMnemonic =
    "abandon abandon abandon abandon abandon abandon abandon abandon "
    "abandon abandon abandon about";

// build a DD transfer tx paying `spk` (zero-value) with `cents`, + a "DD" OP_RETURN
static BRTransaction *ddTx(const uint8_t *spk, size_t spkLen, uint16_t centsLE,
                           UInt256 prevHash, uint32_t prevN) {
    BRTransaction *tx = BRTransactionNew();
    tx->version = 0x02000770;
    BRTransactionAddInput(tx, prevHash, prevN, 0, spk, spkLen, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddOutput(tx, 0, spk, spkLen);              // vout0: DD token (ours), zero-value
    uint8_t orr[9] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,
                      (uint8_t)(centsLE & 0xff), (uint8_t)(centsLE >> 8)}; // "DD" type2 [cents]
    BRTransactionAddOutput(tx, 0, orr, sizeof(orr));         // vout1: OP_RETURN
    return tx;
}

int main(void) {
    uint8_t seed[64]; BRBIP39DeriveKey(seed, kMnemonic, NULL);
    BRMasterPubKey mpk84 = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRMasterPubKey mpk86 = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));
    BRWallet *w = BRWalletNew(NULL, 0, mpk84);
    check(w != NULL, "wallet created"); if (!w){printf("\nFATAL\n");return 1;}
    BRWalletSetTaprootKey(w, mpk86);

    BRAddress ta = BRWalletReceiveAddress(w, 2);             // our taproot addr[0]
    uint8_t spk[64]; size_t spkLen = BRAddressScriptPubKey(spk, sizeof(spk), ta.s);
    check(spkLen == 34 && spk[0] == 0x51, "taproot scriptPubKey resolves");

    // credit 5000 cents ($50) to us
    UInt256 h1; memset(h1.u8, 0x11, 32);
    BRTransaction *tx = ddTx(spk, spkLen, 5000, h1, 0);
    BRWalletRegisterTransaction(w, tx);
    check(BRWalletDigiDollarBalance(w) == 5000, "DD balance credited: 5000 cents");
    check(BRWalletBalance(w) == 0, "DGB balance unaffected by DD (zero-value)");

    // spend it: a tx consuming (tx->txHash, 0) — pays a foreign DD output
    uint8_t fspk[34]; fspk[0]=0x51; fspk[1]=0x20; memset(fspk+2, 0xCD, 32);
    BRTransaction *sp = BRTransactionNew(); sp->version = 0x02000770;
    BRTransactionAddInput(sp, tx->txHash, 0, 0, spk, spkLen, NULL, 0, NULL, 0, 0xffffffff);
    BRTransactionAddOutput(sp, 0, fspk, 34);
    uint8_t orr2[9] = {0x6a,0x02,0x44,0x44,0x01,0x02,0x02,0x88,0x13};
    BRTransactionAddOutput(sp, 0, orr2, sizeof(orr2));
    BRWalletRegisterTransaction(w, sp);
    check(BRWalletDigiDollarBalance(w) == 0, "DD balance 0 after spending the DD utxo");

    BRWalletFree(w);
    printf(g_fail==0 ? "\nALL PASS\n" : "\n%d FAIL\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
```

Create `run.sh` by copying `native/src/test/host/bip341_signtx_kat/run.sh` and changing the `$SCRIPT_DIR/..._main.c` to this file; it already links `BRWallet.c`, `BRKey.c`, `BRBIP32Sequence.c`, `BRBIP39Mnemonic.c`, `BRAddress.c`, `BRSet.c`, `BRDigiAsset.c`, `BRCrypto.c`, `BRBase58.c`, `BRBech32.c`, `crypto/*`. **Add `$CORE_DIR/BRDigiDollar.c` to that clang source list.** (BRTransaction.c is pulled via `#include` in that recipe; if the linker reports duplicate/missing BRTransaction symbols, add `$CORE_DIR/BRTransaction.c` explicitly and drop the `#include "BRTransaction.c"` — but this KAT does not `#include` it, so list `$CORE_DIR/BRTransaction.c` on the clang line.)

- [ ] **Step 2: Run to verify it fails.** `bash native/src/test/host/digidollar_wallet_kat/run.sh` → compile error: `BRWalletDigiDollarBalance` undefined.

- [ ] **Step 3: Add struct fields.** In `BRWallet.c`, in `struct BRWalletStruct` immediately after `BRUTXO *assetUtxos;` (`:42`):

```c
    BRUTXO *ddUtxos;      // DigiDollar token UTXOs (zero-value P2TR, cents-denominated)
    uint64_t ddBalance;   // DigiDollar balance in CENTS (never mixed with the sat balance)
```

- [ ] **Step 4: Init + free.** Add `#include "BRDigiDollar.h"` near the top of `BRWallet.c` (with the other includes). Next to `array_new(wallet->assetUtxos, 30);` (`:299`) add:

```c
    array_new(wallet->ddUtxos, 30);
```
(If `BRWalletNewDual` allocates `assetUtxos` separately, mirror there too — grep `array_new(wallet->assetUtxos`.) Next to `array_free(wallet->assetUtxos);` (`:1775`) add:

```c
    array_free(wallet->ddUtxos);
```

- [ ] **Step 5: Detection + accumulation + reset.** In `_BRWalletUpdateBalance`: after `array_clear(wallet->assetUtxos);` (`:185`) add `array_clear(wallet->ddUtxos);`. Declare a local next to `uint64_t balance = 0, prevBalance = 0;` (`:178`): `uint64_t ddBalance = 0;`. In the ownership branch, replace the existing asset/else block (`:255-262`) so the DD check comes first:

```c
                    int64_t ddCents = BRDigiDollarOutputAmount(tx, (uint32_t)j);
                    if (ddCents >= 0) {
                        array_add(wallet->ddUtxos, ((BRUTXO) { tx->txHash, (uint32_t)j }));
                        ddBalance += (uint64_t)ddCents;
                        balance += 0; // DD tokens are zero-value; never touch the DGB balance
                    } else if (BRTxOutputIsAsset(tx, &tx->outputs[j])) {
                        array_add(wallet->assetUtxos, ((BRUTXO) { tx->txHash, (uint32_t)j }));
                        balance += 0;
                    } else {
                        array_add(wallet->utxos, ((BRUTXO) { tx->txHash, (uint32_t)j }));
                        balance += tx->outputs[j].amount;
                    }
```

- [ ] **Step 6: Spent-DD prune + assign.** After the main `for (i ...)` transaction loop closes (right before `wallet->balance = balance;` at `:286`), add:

```c
    // prune spent DD UTXOs so ddBalance is spendable, not cumulative (spentOutputs is
    // fully populated after the tx loop). Mirrors the DGB utxo prune at :269-276.
    for (j = array_count(wallet->ddUtxos); j > 0; j--) {
        if (BRSetContains(wallet->spentOutputs, &wallet->ddUtxos[j - 1])) {
            BRTransaction *dt = BRSetGet(wallet->allTx, &wallet->ddUtxos[j - 1].hash);
            int64_t c = dt ? BRDigiDollarOutputAmount(dt, wallet->ddUtxos[j - 1].n) : -1;
            if (c > 0 && ddBalance >= (uint64_t)c) ddBalance -= (uint64_t)c;
            array_rm(wallet->ddUtxos, j - 1);
        }
    }
    wallet->ddBalance = ddBalance;
```

- [ ] **Step 7: Accessor.** After `BRWalletBalance` (`:702-712`) add:

```c
// DigiDollar balance in CENTS (USD). Separate from BRWalletBalance (satoshis).
uint64_t BRWalletDigiDollarBalance(BRWallet *wallet)
{
    uint64_t b;
    assert(wallet != NULL);
    pthread_mutex_lock(&wallet->lock);
    b = wallet->ddBalance;
    pthread_mutex_unlock(&wallet->lock);
    return b;
}
```

In `BRWallet.h`, after `uint64_t BRWalletBalance(BRWallet *wallet);` (`:167`):

```c
// wallet DigiDollar balance in cents (USD) — separate from the satoshi balance
uint64_t BRWalletDigiDollarBalance(BRWallet *wallet);
```

- [ ] **Step 8: Run the KAT to verify it passes.** `bash native/src/test/host/digidollar_wallet_kat/run.sh` → `ALL PASS`. (If `BRWalletRegisterTransaction` rejects the synthetic tx, ensure the DD output's `address` resolves to the taproot addr — `BRTransactionAddOutput` sets it from `spk` — and that `BRWalletSetTaprootKey` put addr[0] in `allAddrs`; the credit assertion proves the gate.)

- [ ] **Step 9: Regression gates.** `./gradlew :app:assembleMainnetDebug 2>&1 | tail -5` = BUILD SUCCESSFUL; `./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*" 2>&1 | tail -5` = 42/42.

- [ ] **Step 10: Commit.** Submodule `BRWallet.c`/`.h` (`git -C … commit -F -`), then root commit for the KAT + submodule pin bump. `feat(digidollar): …` + `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

## Task 2: C core — `BRWalletDigiDollarUTXOs` enumeration (SEND groundwork)

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRWallet.c` (accessor)
- Modify: `native/src/main/jni/digibytewallet-core/BRWallet.h` (decl)
- Modify: `native/src/test/host/digidollar_wallet_kat/digidollar_wallet_kat_main.c` (assert count)

**Interfaces:**
- Consumes: `wallet->ddUtxos` (Task 1).
- Produces: `size_t BRWalletDigiDollarUTXOs(BRWallet *wallet, BRUTXO utxos[], size_t utxosCount);` (SEND coin-selection reads this; pair each `{hash,n}` with `BRDigiDollarOutputAmount`).

- [ ] **Step 1: Add the failing assertion.** In the KAT, after the "credited 5000" check and before the spend, add:

```c
    check(BRWalletDigiDollarUTXOs(w, NULL, 0) == 1, "ddUtxos count == 1 after credit");
    BRUTXO one[4];
    check(BRWalletDigiDollarUTXOs(w, one, 4) == 1, "ddUtxos copy-out returns 1");
```
And after the spend: `check(BRWalletDigiDollarUTXOs(w, NULL, 0) == 0, "ddUtxos count == 0 after spend");`

- [ ] **Step 2: Run to verify it fails.** `bash …/run.sh` → `BRWalletDigiDollarUTXOs` undefined.

- [ ] **Step 3: Implement.** After `BRWalletUTXOs` (`:714`) in `BRWallet.c`, mirroring it:

```c
// populates utxos with the wallet's unspent DigiDollar token outputs and returns their
// number. Returns the count if utxos is NULL. (Pair each with BRDigiDollarOutputAmount for cents.)
size_t BRWalletDigiDollarUTXOs(BRWallet *wallet, BRUTXO *utxos, size_t utxosCount)
{
    assert(wallet != NULL);
    pthread_mutex_lock(&wallet->lock);
    if (! utxos || array_count(wallet->ddUtxos) < utxosCount) utxosCount = array_count(wallet->ddUtxos);
    for (size_t i = 0; utxos && i < utxosCount; i++) utxos[i] = wallet->ddUtxos[i];
    pthread_mutex_unlock(&wallet->lock);
    return utxosCount;
}
```

In `BRWallet.h`, after the `BRWalletUTXOs` decl (`:176`):

```c
// wallet's unspent DigiDollar token UTXOs (SEND coin-selection input)
size_t BRWalletDigiDollarUTXOs(BRWallet *wallet, BRUTXO utxos[], size_t utxosCount);
```

- [ ] **Step 4: Run to verify it passes.** `bash …/run.sh` → `ALL PASS`.
- [ ] **Step 5: Regression gates.** app build + 42 security green.
- [ ] **Step 6: Commit.** Submodule + root pin bump.

---

## Task 3: JNI `getDigiDollarBalance` + Kotlin bridge

**Files:**
- Modify: `native/src/main/jni/bridge/jni_wallet.c`
- Modify: `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt`
- Modify (if present): `native/src/androidTest/java/io/digibyte/core/bridge/NativeBridge.kt` (duplicate stub — must stay in sync or `:native` androidTest won't compile)

**Interfaces:**
- Consumes: `BRWalletDigiDollarBalance` (Task 1).
- Produces: `NativeBridge.getDigiDollarBalance(): Long` (cents) — Task 4 polls it.

- [ ] **Step 1: JNI method.** In `jni_wallet.c`, after the `getBalance` function (`:565-571`), add (mirroring it):

```c
/* ---------- getDigiDollarBalance (cents) ---------- */

JNIEXPORT jlong JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getDigiDollarBalance(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    if (!g_wallet) return 0;
    return (jlong)BRWalletDigiDollarBalance(g_wallet);
}
```

- [ ] **Step 2: Kotlin external fun.** In `NativeBridge.kt`, after `external fun getBalance(): Long` (`:46`):

```kotlin
    /** Get current DigiDollar balance in cents (USD). 0 if none. */
    external fun getDigiDollarBalance(): Long
```
If `native/src/androidTest/java/io/digibyte/core/bridge/NativeBridge.kt` exists as a duplicate stub, add the same declaration there so the androidTest source set compiles.

- [ ] **Step 3: Verify build resolves the JNI symbol.** `./gradlew :app:assembleMainnetDebug 2>&1 | tail -5` = BUILD SUCCESSFUL (the app build compiles the native module + Kotlin; an unresolved JNI name or a broken NativeBridge contract fails here). Also `./gradlew :core:compileMainnetDebugKotlin 2>&1 | tail -5` succeeds.
- [ ] **Step 4: Regression.** `./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*"` 42/42 (includes `SeedIsolationTest`, which asserts the NativeBridge contract — confirms the addition is non-breaking).
- [ ] **Step 5: Commit.** Root commit (JNI + Kotlin).

---

## Task 4: WalletViewModel — DD balance StateFlow + USD formatter

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/wallet/WalletViewModel.kt`
- Create: `app/src/test/java/io/digibyte/ui/wallet/DigiDollarFormatTest.kt`

**Interfaces:**
- Consumes: `NativeBridge.getDigiDollarBalance()` (Task 3).
- Produces: `viewModel.ddBalance: StateFlow<Long>` (cents) + `WalletViewModel.formatDigiDollar(cents: Long): String` — Task 5 renders these.

- [ ] **Step 1: Write the failing formatter test.** Create `app/src/test/java/io/digibyte/ui/wallet/DigiDollarFormatTest.kt`:

```kotlin
package io.digibyte.ui.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class DigiDollarFormatTest {
    @Test fun formatsCentsAsUsd() {
        assertEquals("$50.00", WalletViewModel.formatDigiDollar(5000))
        assertEquals("$1.23", WalletViewModel.formatDigiDollar(123))
        assertEquals("$0.09", WalletViewModel.formatDigiDollar(9))
        assertEquals("$0.00", WalletViewModel.formatDigiDollar(0))
        assertEquals("$1,234.56", WalletViewModel.formatDigiDollar(123456))
    }
}
```

- [ ] **Step 2: Run to verify it fails.** `./gradlew :app:testMainnetDebugUnitTest --tests "*.DigiDollarFormatTest" 2>&1 | tail -8` → FAIL (`formatDigiDollar` unresolved).

- [ ] **Step 3: Add the formatter.** In `WalletViewModel.kt`'s companion object, next to `formatSatoshis` (`:651-658`):

```kotlin
        /** DigiDollar cents → USD string. Example: 5000 → "$50.00" */
        fun formatDigiDollar(cents: Long): String {
            val dollars = cents / 100.0
            val fmt = NumberFormat.getNumberInstance(Locale.US).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }
            return "$" + fmt.format(dollars)
        }
```

- [ ] **Step 4: Run to verify it passes.** Same command → PASS.

- [ ] **Step 5: Add the StateFlow + poll.** Next to `_balance` (`:48-49`):

```kotlin
    /** Live DigiDollar balance in cents — polled alongside the DGB balance. */
    private val _ddBalance = MutableStateFlow(prefs.getLong("last_dd_balance", 0L))
    val ddBalance: StateFlow<Long> = _ddBalance.asStateFlow()
```
In `pollNativeBalance()`, right after the DGB balance block (after the `if (nativeBalance != _balance.value) { … }` closes, ~`:465`):

```kotlin
                // Poll DigiDollar balance (cents). Trivially trusted — DD is zero on
                // mainnet, nonzero only on a testnet26 build that syncs real DD txs.
                val ddCents = NativeBridge.getDigiDollarBalance()
                if (ddCents != _ddBalance.value) {
                    _ddBalance.value = ddCents
                    prefs.edit().putLong("last_dd_balance", ddCents).apply()
                }
```

- [ ] **Step 6: Verify build + formatter test.** `./gradlew :app:assembleMainnetDebug` green; `./gradlew :app:testMainnetDebugUnitTest --tests "*.DigiDollarFormatTest"` PASS.
- [ ] **Step 7: Commit.** Root commit.

---

## Task 5: UI — hero-card DigiDollar line

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/components/BalanceDisplay.kt`
- Modify: `app/src/main/java/io/digibyte/ui/wallet/WalletScreen.kt`

**Interfaces:**
- Consumes: `viewModel.ddBalance` + `WalletViewModel.formatDigiDollar` (Task 4).
- Produces: (UI only).

- [ ] **Step 1: Extend `BalanceDisplay` with an optional DD line.** Add a param and render it under the DGB amount. Change the signature (`:24-30`) to add `ddAmount: String? = null,` after `dgbAmount: String,`, and after the `dgbAmount` `Text(...)` block (before the closing `Column` brace, ~`:63`):

```kotlin
        if (ddAmount != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = ddAmount,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                ),
                color = dgbColor,   // reuse the accent (dimmed when unsynced)
                textAlign = TextAlign.Center
            )
        }
```
Update the KDoc to document `@param ddAmount Pre-formatted DigiDollar string e.g. "$50.00", or null to hide the line`.

- [ ] **Step 2: Wire it from `WalletScreen`.** After `val balance by viewModel.balance.collectAsStateWithLifecycle()` (`:52`) add:

```kotlin
    val ddBalance by viewModel.ddBalance.collectAsStateWithLifecycle()
```
In the `BalanceDisplay(...)` call (`:97-100`), add after `dgbAmount = …,`:

```kotlin
                        ddAmount = if (ddBalance > 0L)
                            WalletViewModel.formatDigiDollar(ddBalance) else null,
```
(DD line is hidden when the balance is 0, so mainnet/no-DD wallets show nothing extra.)

- [ ] **Step 3: Verify build.** `./gradlew :app:assembleMainnetDebug 2>&1 | tail -5` = BUILD SUCCESSFUL.
- [ ] **Step 4: Regression.** `./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*"` 42/42; `./gradlew :app:testMainnetDebugUnitTest --tests "*.DigiDollarFormatTest"` PASS.
- [ ] **Step 5: Commit.** Root commit.

---

## Out of scope (later increments)

- **SEND / transfer builder** (spec §9.2) — consumes `BRWalletDigiDollarUTXOs` (built here) + `BRDigiDollarOutputAmount` for coin selection; gated on a real testnet26 DD address for recipient parsing.
- **DD address decode** (`TD…` Base58Check) — testnet26-gated fund-safety (wire-format spec §8 Q2).
- **On-chain SHOW proof** — register a real testnet26 DD tx at a wallet P2TR address on a rc46 node; confirm the hero-card line. Deferred until rc46 is running here.
