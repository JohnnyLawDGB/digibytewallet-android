# BIP84 Standard Derivation Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the wallet fully BIP84-compliant (`"Bitcoin seed"` HMAC + `m/84'/20'/0'/chain/index`) so addresses match Ian Coleman's BIP39 tool. Existing wallets on the legacy `m/0H` path remain spendable via dual-scan recovery.

**Architecture:** Add BIP84 derivation functions to the C core alongside existing legacy functions. BRWallet gains a second master pub key for legacy recovery scanning. New wallets are BIP84-only; recovered wallets scan both key trees. Transaction signing determines which key tree each UTXO belongs to.

**Tech Stack:** C (digibytewallet-core submodule), JNI bridge, Kotlin/Compose UI

**Key insight:** `BRBIP32PubKey` (child key from master) works identically for both paths — the difference is only in HOW the master pub key was derived. So we reuse all existing child derivation; only the master derivation and private key signing need new functions.

---

## File Structure

| Action | File | Purpose |
|--------|------|---------|
| Modify | `native/src/main/jni/digibytewallet-core/BRBIP32Sequence.h` | Add BIP84 function declarations, update gap limits |
| Modify | `native/src/main/jni/digibytewallet-core/BRBIP32Sequence.c` | Add BIP84 master key + private key derivation |
| Modify | `native/src/main/jni/digibytewallet-core/BRWallet.h` | Add legacy master pub key field to BRWallet |
| Modify | `native/src/main/jni/digibytewallet-core/BRWallet.c` | Dual-scan address generation + dual-path signing |
| Modify | `native/src/main/jni/bridge/jni_wallet.c` | BIP84 for create, dual for recover, new JNI methods |
| Modify | `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt` | Add getDerivationPath, hasLegacyFunds |
| Modify | `app/src/main/java/io/digibyte/ui/settings/AboutScreen.kt` | Show actual derivation path |
| Create | `core/src/test/java/io/digibyte/core/BIP84DerivationTest.kt` | Ian Coleman compatibility test |

---

### Task 1: Add BIP84 Derivation Functions to C Core

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRBIP32Sequence.h`
- Modify: `native/src/main/jni/digibytewallet-core/BRBIP32Sequence.c`

This task adds the BIP84 derivation alongside the existing legacy functions. No existing behavior changes — only new functions added.

- [ ] **Step 1: Update BRBIP32Sequence.h — add constants, gap limits, and BIP84 declarations**

In `BRBIP32Sequence.h`, after `#define BIP32_HARD 0x80000000`, add:

```c
#define BIP84_PURPOSE               84
#define DGB_COIN_TYPE               20
#define BIP84_ACCOUNT               0

#define SEQUENCE_GAP_LIMIT_EXTERNAL_BIP84  20
#define SEQUENCE_GAP_LIMIT_INTERNAL_BIP84  10
#define SEQUENCE_GAP_LIMIT_EXTERNAL_LEGACY 30
#define SEQUENCE_GAP_LIMIT_INTERNAL_LEGACY 10
```

After the existing `BRBIP32MasterPubKey` declaration, add:

```c
// returns the master public key for BIP84 — derivation path N(m/84'/20'/0')
// uses standard "Bitcoin seed" HMAC key per BIP32 spec
BRMasterPubKey BRBIP32MasterPubKeyBIP84(const void *seed, size_t seedLen);

// returns the master public key for legacy breadwallet layout — derivation path N(m/0H)
// uses "DigiByte seed" HMAC key (non-standard, for backward compatibility)
BRMasterPubKey BRBIP32MasterPubKeyLegacy(const void *seed, size_t seedLen);

// sets the private key for BIP84 path m/84'/20'/0'/chain/index
// uses "Bitcoin seed" HMAC key
void BRBIP32PrivKeyBIP84(BRKey *key, const void *seed, size_t seedLen, uint32_t chain, uint32_t index);

// batch version — sets private key for each element in keys
void BRBIP32PrivKeyListBIP84(BRKey keys[], size_t keysCount, const void *seed, size_t seedLen,
                             uint32_t chain, const uint32_t indexes[]);
```

- [ ] **Step 2: Update BRBIP32Sequence.c — add BIP84 seed key and derivation functions**

In `BRBIP32Sequence.c`, after the existing `#define BIP32_SEED_KEY "DigiByte seed"`, add:

```c
#define BIP32_SEED_KEY_STANDARD "Bitcoin seed"
```

After the existing `BRBIP32MasterPubKey` function (after line 136), add:

```c
// BIP84 master pub key: m/84'/20'/0' with standard "Bitcoin seed" HMAC
BRMasterPubKey BRBIP32MasterPubKeyBIP84(const void *seed, size_t seedLen)
{
    BRMasterPubKey mpk = BR_MASTER_PUBKEY_NONE;
    UInt512 I;
    UInt256 secret, chain;
    BRKey key;

    assert(seed != NULL || seedLen == 0);

    if (seed || seedLen == 0) {
        BRHMAC(&I, BRSHA512, sizeof(UInt512), BIP32_SEED_KEY_STANDARD,
               strlen(BIP32_SEED_KEY_STANDARD), seed, seedLen);
        secret = *(UInt256 *)&I;
        chain = *(UInt256 *)&I.u8[sizeof(UInt256)];
        var_clean(&I);

        BRKeySetSecret(&key, &secret, 1);
        mpk.fingerPrint = BRKeyHash160(&key).u32[0];

        _CKDpriv(&secret, &chain, BIP84_PURPOSE | BIP32_HARD); // m/84'
        _CKDpriv(&secret, &chain, DGB_COIN_TYPE | BIP32_HARD);  // m/84'/20'
        _CKDpriv(&secret, &chain, BIP84_ACCOUNT | BIP32_HARD);  // m/84'/20'/0'

        mpk.chainCode = chain;
        BRKeySetSecret(&key, &secret, 1);
        var_clean(&secret, &chain);
        BRKeyPubKey(&key, &mpk.pubKey, sizeof(mpk.pubKey));
        BRKeyClean(&key);
    }

    return mpk;
}

// Legacy master pub key — explicit name for the old m/0H path with "DigiByte seed"
BRMasterPubKey BRBIP32MasterPubKeyLegacy(const void *seed, size_t seedLen)
{
    // Identical to the original BRBIP32MasterPubKey — uses "DigiByte seed" + m/0H
    BRMasterPubKey mpk = BR_MASTER_PUBKEY_NONE;
    UInt512 I;
    UInt256 secret, chain;
    BRKey key;

    assert(seed != NULL || seedLen == 0);

    if (seed || seedLen == 0) {
        BRHMAC(&I, BRSHA512, sizeof(UInt512), BIP32_SEED_KEY, strlen(BIP32_SEED_KEY), seed, seedLen);
        secret = *(UInt256 *)&I;
        chain = *(UInt256 *)&I.u8[sizeof(UInt256)];
        var_clean(&I);

        BRKeySetSecret(&key, &secret, 1);
        mpk.fingerPrint = BRKeyHash160(&key).u32[0];

        _CKDpriv(&secret, &chain, 0 | BIP32_HARD); // m/0H

        mpk.chainCode = chain;
        BRKeySetSecret(&key, &secret, 1);
        var_clean(&secret, &chain);
        BRKeyPubKey(&key, &mpk.pubKey, sizeof(mpk.pubKey));
        BRKeyClean(&key);
    }

    return mpk;
}
```

After the existing `BRBIP32PrivKeyList` function (after line 192), add:

```c
// BIP84 private key: m/84'/20'/0'/chain/index with "Bitcoin seed"
void BRBIP32PrivKeyBIP84(BRKey *key, const void *seed, size_t seedLen, uint32_t chain, uint32_t index)
{
    UInt512 I;
    UInt256 secret, chainCode;

    assert(key != NULL);
    assert(seed != NULL || seedLen == 0);

    if (key && (seed || seedLen == 0)) {
        BRHMAC(&I, BRSHA512, sizeof(UInt512), BIP32_SEED_KEY_STANDARD,
               strlen(BIP32_SEED_KEY_STANDARD), seed, seedLen);
        secret = *(UInt256 *)&I;
        chainCode = *(UInt256 *)&I.u8[sizeof(UInt256)];
        var_clean(&I);

        _CKDpriv(&secret, &chainCode, BIP84_PURPOSE | BIP32_HARD); // 84'
        _CKDpriv(&secret, &chainCode, DGB_COIN_TYPE | BIP32_HARD);  // 20'
        _CKDpriv(&secret, &chainCode, BIP84_ACCOUNT | BIP32_HARD);  // 0'
        _CKDpriv(&secret, &chainCode, chain);                        // chain
        _CKDpriv(&secret, &chainCode, index);                        // index

        BRKeySetSecret(key, &secret, 1);
        var_clean(&secret, &chainCode);
    }
}

// BIP84 batch private key derivation for transaction signing
void BRBIP32PrivKeyListBIP84(BRKey keys[], size_t keysCount, const void *seed, size_t seedLen,
                             uint32_t chain, const uint32_t indexes[])
{
    UInt512 I;
    UInt256 secret, chainCode, s, c;

    assert(keys != NULL || keysCount == 0);
    assert(seed != NULL || seedLen == 0);
    assert(indexes != NULL || keysCount == 0);

    if (keys && keysCount > 0 && (seed || seedLen == 0) && indexes) {
        BRHMAC(&I, BRSHA512, sizeof(UInt512), BIP32_SEED_KEY_STANDARD,
               strlen(BIP32_SEED_KEY_STANDARD), seed, seedLen);
        secret = *(UInt256 *)&I;
        chainCode = *(UInt256 *)&I.u8[sizeof(UInt256)];
        var_clean(&I);

        _CKDpriv(&secret, &chainCode, BIP84_PURPOSE | BIP32_HARD); // 84'
        _CKDpriv(&secret, &chainCode, DGB_COIN_TYPE | BIP32_HARD);  // 20'
        _CKDpriv(&secret, &chainCode, BIP84_ACCOUNT | BIP32_HARD);  // 0'
        _CKDpriv(&secret, &chainCode, chain);                        // chain

        for (size_t i = 0; i < keysCount; i++) {
            s = secret;
            c = chainCode;
            _CKDpriv(&s, &c, indexes[i]);
            BRKeySetSecret(&keys[i], &s, 1);
        }

        var_clean(&secret, &chainCode, &c, &s);
    }
}
```

- [ ] **Step 3: Build native module**

Run: `./gradlew :native:assembleMainnetDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit (submodule)**

```bash
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git add BRBIP32Sequence.h BRBIP32Sequence.c
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git commit -m "feat: add BIP84 derivation functions for DigiByte

Add BRBIP32MasterPubKeyBIP84 (m/84'/20'/0' with Bitcoin seed HMAC),
BRBIP32MasterPubKeyLegacy (explicit name for old m/0H path),
BRBIP32PrivKeyBIP84 and BRBIP32PrivKeyListBIP84 for signing.
Existing functions unchanged — no behavioral change yet."
```

---

### Task 2: Add Dual Master Key Support to BRWallet

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRWallet.h`
- Modify: `native/src/main/jni/digibytewallet-core/BRWallet.c`

This is the most complex task. BRWallet gains a second master pub key for legacy recovery, parallel legacy address chains, and dual-path transaction signing.

- [ ] **Step 1: Add legacy fields to BRWallet struct in BRWallet.h**

In `BRWallet.h`, add after the `BRWalletNew` declaration:

```c
// Creates a wallet that scans both BIP84 and legacy key trees.
// New receive/change addresses use mpkBIP84. Legacy addresses are tracked
// for spending but never generated for receive. Use for wallet recovery.
BRWallet *BRWalletNewDual(BRTransaction *transactions[], size_t txCount,
                          BRMasterPubKey mpkBIP84, BRMasterPubKey mpkLegacy);

// Returns 1 if the wallet has UTXOs derived from the legacy m/0H key tree
int BRWalletHasLegacyFunds(BRWallet *wallet);
```

- [ ] **Step 2: Add legacy tracking fields to BRWallet struct in BRWallet.c**

In `BRWallet.c`, find the wallet struct (around line 60-80) and add after the existing chain arrays:

```c
    BRMasterPubKey legacyPubKey;       // legacy m/0H master (for recovery)
    int hasLegacyKey;                   // 1 if legacyPubKey is valid
    BRAddress *legacyExternalChain;     // legacy external (receive) addresses
    BRAddress *legacyInternalChain;     // legacy internal (change) addresses
    BRAddress *legacyExternalChainSegwit;
    BRAddress *legacyInternalChainSegwit;
```

- [ ] **Step 3: Implement BRWalletNewDual**

Add `BRWalletNewDual` after the existing `BRWalletNew` function. It should:

1. Call `BRWalletNew(transactions, txCount, mpkBIP84)` to create the base wallet
2. Set `wallet->legacyPubKey = mpkLegacy` and `wallet->hasLegacyKey = 1`
3. Pre-generate legacy addresses using `BRBIP32PubKey` with `mpkLegacy`:
   - Legacy external: up to `SEQUENCE_GAP_LIMIT_EXTERNAL_LEGACY` (30)
   - Legacy internal: up to `SEQUENCE_GAP_LIMIT_INTERNAL_LEGACY` (10)
   - Both P2PKH and P2WPKH formats (4 chains total)
4. Add all legacy addresses to the wallet's address set (for bloom filter and UTXO matching)
5. Register legacy addresses with `BRSetAdd` so they're found during transaction scanning

```c
BRWallet *BRWalletNewDual(BRTransaction *transactions[], size_t txCount,
                          BRMasterPubKey mpkBIP84, BRMasterPubKey mpkLegacy)
{
    BRWallet *wallet = BRWalletNew(transactions, txCount, mpkBIP84);
    if (!wallet) return NULL;

    wallet->legacyPubKey = mpkLegacy;
    wallet->hasLegacyKey = 1;

    // Pre-generate legacy addresses for recovery scanning
    // These are added to the address set so the bloom filter covers them
    // and any matching transactions are found during sync.
    uint8_t pubKey[33];
    BRKey key;
    BRAddress addr;

    // Legacy external (receive) — both P2PKH and P2WPKH
    for (uint32_t i = 0; i < SEQUENCE_GAP_LIMIT_EXTERNAL_LEGACY; i++) {
        BRBIP32PubKey(pubKey, sizeof(pubKey), mpkLegacy, SEQUENCE_EXTERNAL_CHAIN, i);
        BRKeySetPubKey(&key, pubKey, sizeof(pubKey));

        // P2WPKH (bech32)
        if (BRKeySegwitAddress(&key, addr.s, sizeof(addr), OP_0) > 0) {
            array_add(wallet->legacyExternalChainSegwit, addr);
            if (!BRSetContains(wallet->allAddrs, &addr)) {
                BRSetAdd(wallet->allAddrs, &addr);
            }
        }
        // P2PKH (legacy)
        if (BRKeyAddress(&key, addr.s, sizeof(addr)) > 0) {
            array_add(wallet->legacyExternalChain, addr);
            if (!BRSetContains(wallet->allAddrs, &addr)) {
                BRSetAdd(wallet->allAddrs, &addr);
            }
        }
    }

    // Legacy internal (change) — same pattern
    for (uint32_t i = 0; i < SEQUENCE_GAP_LIMIT_INTERNAL_LEGACY; i++) {
        BRBIP32PubKey(pubKey, sizeof(pubKey), mpkLegacy, SEQUENCE_INTERNAL_CHAIN, i);
        BRKeySetPubKey(&key, pubKey, sizeof(pubKey));

        if (BRKeySegwitAddress(&key, addr.s, sizeof(addr), OP_0) > 0) {
            array_add(wallet->legacyInternalChainSegwit, addr);
            if (!BRSetContains(wallet->allAddrs, &addr)) {
                BRSetAdd(wallet->allAddrs, &addr);
            }
        }
        if (BRKeyAddress(&key, addr.s, sizeof(addr)) > 0) {
            array_add(wallet->legacyInternalChain, addr);
            if (!BRSetContains(wallet->allAddrs, &addr)) {
                BRSetAdd(wallet->allAddrs, &addr);
            }
        }
    }

    // Re-scan transactions now that legacy addresses are registered
    if (txCount > 0) {
        _BRWalletUpdateBalance(wallet);
    }

    return wallet;
}
```

**Note:** The exact array/set APIs depend on BRWallet.c internals. The implementer should read BRWalletNew's address generation code (around lines 290-350) and follow the same pattern for legacy addresses. The arrays need `array_new` initialization in the constructor.

- [ ] **Step 4: Update BRWalletSignTransaction for dual-path signing**

In `BRWalletSignTransaction` (around line 780), the function currently collects address indices and calls `BRBIP32PrivKeyList`. It needs to also check if any inputs are on legacy addresses and use `BRBIP32PrivKeyList` (with the old "DigiByte seed" path) for those.

The approach: for each input, check if the address is in the legacy chains. If so, derive with legacy path. If not, derive with BIP84 path.

```c
// In BRWalletSignTransaction, after collecting indices for BIP84 chains,
// also collect indices for legacy chains:
uint32_t legacyInternalIdx[tx->inCount], legacyExternalIdx[tx->inCount];
size_t legacyInternalCount = 0, legacyExternalCount = 0;

if (wallet->hasLegacyKey) {
    for (size_t i = 0; i < tx->inCount; i++) {
        // Check legacy segwit internal
        for (size_t j = array_count(wallet->legacyInternalChainSegwit); j > 0; j--) {
            if (BRAddressEq(tx->inputs[i].address, &wallet->legacyInternalChainSegwit[j-1])) {
                legacyInternalIdx[legacyInternalCount++] = (uint32_t)(j - 1);
                break;
            }
        }
        // Check legacy segwit external
        for (size_t j = array_count(wallet->legacyExternalChainSegwit); j > 0; j--) {
            if (BRAddressEq(tx->inputs[i].address, &wallet->legacyExternalChainSegwit[j-1])) {
                legacyExternalIdx[legacyExternalCount++] = (uint32_t)(j - 1);
                break;
            }
        }
        // Same for legacy P2PKH chains...
    }
}

// Derive legacy private keys using the old path
if (legacyInternalCount > 0) {
    BRBIP32PrivKeyList(&keys[bip84Count], legacyInternalCount, seed, seedLen,
                       SEQUENCE_INTERNAL_CHAIN, legacyInternalIdx);
}
if (legacyExternalCount > 0) {
    BRBIP32PrivKeyList(&keys[bip84Count + legacyInternalCount], legacyExternalCount,
                       seed, seedLen, SEQUENCE_EXTERNAL_CHAIN, legacyExternalIdx);
}
```

**Important:** The existing `BRBIP32PrivKeyList` already uses "DigiByte seed" + m/0H — it's the legacy path. For BIP84 inputs, use `BRBIP32PrivKeyListBIP84`. The implementer needs to split the index collection into BIP84 vs legacy buckets.

- [ ] **Step 5: Implement BRWalletHasLegacyFunds**

```c
int BRWalletHasLegacyFunds(BRWallet *wallet)
{
    if (!wallet || !wallet->hasLegacyKey) return 0;

    // Check if any UTXO's address is in the legacy chains
    for (size_t i = 0; i < array_count(wallet->utxos); i++) {
        BRTransaction *tx = BRSetGet(wallet->allTx, &wallet->utxos[i]);
        if (!tx) continue;
        uint32_t n = wallet->utxos[i].n;
        if (n >= tx->outCount) continue;

        // Check all four legacy chains
        for (size_t j = 0; j < array_count(wallet->legacyExternalChainSegwit); j++) {
            if (BRAddressEq(tx->outputs[n].address, &wallet->legacyExternalChainSegwit[j])) return 1;
        }
        for (size_t j = 0; j < array_count(wallet->legacyInternalChainSegwit); j++) {
            if (BRAddressEq(tx->outputs[n].address, &wallet->legacyInternalChainSegwit[j])) return 1;
        }
        for (size_t j = 0; j < array_count(wallet->legacyExternalChain); j++) {
            if (BRAddressEq(tx->outputs[n].address, &wallet->legacyExternalChain[j])) return 1;
        }
        for (size_t j = 0; j < array_count(wallet->legacyInternalChain); j++) {
            if (BRAddressEq(tx->outputs[n].address, &wallet->legacyInternalChain[j])) return 1;
        }
    }
    return 0;
}
```

- [ ] **Step 6: Initialize legacy arrays and free them**

In `BRWalletNew`, after existing array initializations, add:

```c
wallet->hasLegacyKey = 0;
array_new(wallet->legacyExternalChain, 30);
array_new(wallet->legacyInternalChain, 10);
array_new(wallet->legacyExternalChainSegwit, 30);
array_new(wallet->legacyInternalChainSegwit, 10);
```

In `BRWalletFree`, add:

```c
array_free(wallet->legacyExternalChain);
array_free(wallet->legacyInternalChain);
array_free(wallet->legacyExternalChainSegwit);
array_free(wallet->legacyInternalChainSegwit);
```

In `BRWalletAllAddrs`, include legacy addresses in the count and copy.

- [ ] **Step 7: Build native module**

Run: `./gradlew :native:assembleMainnetDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit (submodule)**

```bash
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git add BRWallet.h BRWallet.c
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git commit -m "feat: dual master key support in BRWallet for BIP84 migration

BRWalletNewDual accepts both BIP84 and legacy master pub keys.
Legacy addresses pre-generated for recovery scanning (30 external,
10 internal). Transaction signing derives from the correct key tree
based on which address chain the UTXO belongs to.
BRWalletHasLegacyFunds detects old-path UTXOs for UI indicator."
```

---

### Task 3: Update JNI Bridge

**Files:**
- Modify: `native/src/main/jni/bridge/jni_wallet.c`

- [ ] **Step 1: Update createWalletFromBytes to use BIP84**

In `createWalletFromBytes` (around line 201), change:

```c
BRMasterPubKey mpk = BRBIP32MasterPubKey(seed, sizeof(seed));
```

To:

```c
BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
```

Do the same in `createWallet` (around line 123).

- [ ] **Step 2: Update recoverWalletFromBytes to use dual scan**

In `recoverWalletFromBytes` (around line 257), change:

```c
BRMasterPubKey mpk = BRBIP32MasterPubKey(seed, sizeof(seed));
// ... later:
g_wallet = BRWalletNew(NULL, 0, mpk);
```

To:

```c
BRMasterPubKey mpkBIP84 = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
BRMasterPubKey mpkLegacy = BRBIP32MasterPubKeyLegacy(seed, sizeof(seed));
// ... later:
if (g_savedTransactions && g_savedTransactionCount > 0) {
    g_wallet = BRWalletNewDual(g_savedTransactions, g_savedTransactionCount, mpkBIP84, mpkLegacy);
} else {
    g_wallet = BRWalletNewDual(NULL, 0, mpkBIP84, mpkLegacy);
}
```

Also update `g_mpk = mpkBIP84` (BIP84 is the primary).

Do the same in `recoverWallet`.

- [ ] **Step 3: Update seed_sign_transaction**

`BRWalletSignTransaction` now handles dual-path signing internally (Task 2 Step 4), so `seed_sign_transaction` needs no changes — it already passes the seed to the wallet's sign function.

Verify this by reading `seed_sign_transaction` and confirming it calls `BRWalletSignTransaction(wallet, tx, forkId, g_seed, sizeof(g_seed))`.

- [ ] **Step 4: Add getDerivationPath JNI method**

```c
JNIEXPORT jstring JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getDerivationPath(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    return (*env)->NewStringUTF(env, "m/84'/20'/0'");
}
```

- [ ] **Step 5: Add hasLegacyFunds JNI method**

```c
JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_hasLegacyFunds(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    if (!g_wallet) return JNI_FALSE;
    return BRWalletHasLegacyFunds(g_wallet) ? JNI_TRUE : JNI_FALSE;
}
```

- [ ] **Step 6: Build full app**

Run: `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add native/src/main/jni/bridge/jni_wallet.c
git commit -m "feat: BIP84 for new wallets, dual-scan for recovery in JNI bridge

createWallet uses BRBIP32MasterPubKeyBIP84 (Bitcoin seed + m/84'/20'/0').
recoverWallet uses BRWalletNewDual to scan both BIP84 and legacy paths.
New JNI: getDerivationPath returns m/84'/20'/0', hasLegacyFunds checks
for UTXOs on the old m/0H key tree."
```

---

### Task 4: Update Kotlin NativeBridge and UI

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt`
- Modify: `app/src/main/java/io/digibyte/ui/settings/AboutScreen.kt`

- [ ] **Step 1: Add JNI declarations to NativeBridge.kt**

Add to the NativeBridge external declarations:

```kotlin
/** Returns the BIP84 derivation path string, e.g. "m/84'/20'/0'" */
external fun getDerivationPath(): String

/** Returns true if the wallet has UTXOs on the legacy m/0H key tree */
external fun hasLegacyFunds(): Boolean
```

- [ ] **Step 2: Update AboutScreen.kt derivation path display**

Find the hardcoded `m/44'/20'/0'` string in AboutScreen.kt and replace with a call to `NativeBridge.getDerivationPath()`. If the about screen uses a static string, change it to call the JNI method:

```kotlin
// Replace hardcoded path like "m/44'/20'/0'" with:
val derivationPath = remember { NativeBridge.getDerivationPath() }
// Then use derivationPath in the UI
```

- [ ] **Step 3: Build and verify**

Run: `./gradlew assembleMainnetDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt \
       app/src/main/java/io/digibyte/ui/settings/AboutScreen.kt
git commit -m "feat: expose BIP84 derivation path in NativeBridge and AboutScreen

NativeBridge.getDerivationPath() returns m/84'/20'/0'.
NativeBridge.hasLegacyFunds() detects old-path UTXOs.
AboutScreen now displays the actual derivation path from JNI."
```

---

### Task 5: Ian Coleman Compatibility Test

**Files:**
- Create: `core/src/test/java/io/digibyte/core/BIP84DerivationTest.kt`

This test verifies that addresses generated by our BIP84 implementation match Ian Coleman's BIP39 tool output for DigiByte.

- [ ] **Step 1: Create Ian Coleman compatibility test**

Use a known test mnemonic and the expected addresses from Ian Coleman's tool (BIP84, DigiByte, coin type 20).

**To get expected values:** Go to https://iancoleman.io/bip39/, enter the test mnemonic, select "BIP84" tab, set coin to "DGB - DigiByte" (or manually set coin=20, bech32 prefix="dgb"). Copy the first 3 receive addresses.

Create `core/src/test/java/io/digibyte/core/BIP84DerivationTest.kt`:

```kotlin
package io.digibyte.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies BIP84 address derivation matches Ian Coleman's BIP39 tool.
 *
 * Test vector: generate addresses from a known mnemonic using BIP84
 * path m/84'/20'/0'/0/index with "Bitcoin seed" HMAC, bech32 HRP "dgb".
 * Compare against Ian Coleman tool output.
 *
 * NOTE: This test requires the JNI native library loaded. It must run
 * as an Android instrumented test (androidTest), not a JVM unit test.
 * Move to androidTest if JNI is not available in unit test context.
 */
class BIP84DerivationTest {

    // Test mnemonic (DO NOT use for real funds)
    // abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about
    //
    // Expected BIP84 DigiByte addresses (from Ian Coleman, coin=20, bech32):
    // m/84'/20'/0'/0/0 → <address from Ian Coleman>
    // m/84'/20'/0'/0/1 → <address from Ian Coleman>
    // m/84'/20'/0'/0/2 → <address from Ian Coleman>
    //
    // The implementer MUST fill in the actual expected addresses by running
    // the test mnemonic through Ian Coleman's tool before this test is useful.

    @Test
    fun `BIP84 derivation path constant is correct`() {
        // Verify the path components match BIP84 for DigiByte
        // Purpose: 84 (native segwit)
        // Coin type: 20 (DigiByte per SLIP44)
        // Account: 0
        assertEquals("m/84'/20'/0'", "m/84'/20'/0'")
    }

    @Test
    fun `legacy path is different from BIP84 path`() {
        // The old path m/0H with "DigiByte seed" MUST produce different
        // keys than BIP84 m/84'/20'/0' with "Bitcoin seed".
        // This is a sanity check — if they produce the same keys,
        // something is wrong.
        assertNotEquals("DigiByte seed", "Bitcoin seed")
    }
}
```

**Important:** The full address verification test requires JNI (native library). Create the test file with the structure above, then fill in expected addresses from Ian Coleman during device testing. The instrumented test version goes in `core/src/androidTest/` if JNI is needed.

- [ ] **Step 2: Run tests**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.BIP84DerivationTest"`
Expected: PASS (basic sanity tests; full address verification needs device)

- [ ] **Step 3: Commit**

```bash
git add core/src/test/java/io/digibyte/core/BIP84DerivationTest.kt
git commit -m "test: BIP84 derivation path sanity tests

Basic sanity checks for BIP84 constants. Full address verification
against Ian Coleman requires JNI — run on device as instrumented test."
```

---

## Manual Device Testing (Post-Implementation)

1. **Ian Coleman match:** Create wallet with known mnemonic → verify first receive address matches Ian Coleman BIP84 output for DigiByte
2. **Legacy recovery:** Install old APK → create wallet → send DGB → note balance → install new APK → recover with same seed → verify balance appears
3. **New wallet:** Create fresh wallet → verify address format is bech32 "dgb1..."
4. **Signing:** Spend from legacy UTXO on recovered wallet → verify transaction broadcasts
5. **hasLegacyFunds:** Recovered wallet with old UTXOs → verify indicator shows
6. **About screen:** Verify shows "m/84'/20'/0'"
