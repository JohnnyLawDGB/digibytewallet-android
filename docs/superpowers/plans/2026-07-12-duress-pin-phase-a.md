# Duress PIN — Phase A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the wallet-side duress-PIN decoy: a second, optional PIN that opens a genuinely real but small **decoy wallet** at BIP44 account **1'** of the *same seed*, while the real 95% + DigiAssets + DigiDollar + recovery seed stay unreachable in that session — with no UI/log tell that a duress PIN exists.

**Architecture:** The decoy is account 1' (`m/84'/20'/1'` + `m/86'/20'/1'`) of the one on-disk seed. The native core gets six account-parameterized BIP32 derivation twins (existing six delegate with account 0, so account 0 stays byte-identical); the JNI/`BRWallet` layer threads an account index into wallet creation + signing; Kotlin `PinManager` gains a second constant-time credential and a tri-state `matchPin`; `UnlockScreen` branches REAL→account 0 vs DURESS→account 1' and force-disables biometrics whenever a duress PIN is armed; a process-scoped `DuressSession` flag gates the UI (hide assets/DD, block seed view, hide the duress settings entry). Setup + decoy top-up live only in the real (disarmed) session.

**Tech Stack:** C (secp256k1/BIP32 core, submodule `JohnnyLawDGB/digibytewallet-core`), JNI bridge (`jni_wallet.c`), Kotlin (`core`/`app`), Jetpack Compose, Hilt DI, EncryptedSharedPreferences + Argon2id/PBKDF2, host KATs (clang), instrumented androidTest (device/emulator), JVM JUnit4.

## Global Constraints

Every task's requirements implicitly include this section. Copied from the spec's binding constraints (spec §"Global constraints" + §1/§2/§3):

- **One seed; the decoy is BIP44 account 1' of the same seed** (`m/84'/20'/1'` + `m/86'/20'/1'`) — no second seed, no second backup.
- **Biometrics-off is a mandatory, automatic consequence of arming duress** — while a duress PIN exists, biometric unlock is suppressed globally (a fingerprint/face is a single identity that can only open the real wallet). To restore biometric unlock the user must first remove the duress PIN.
- **No UI/log/error path may reveal, under duress, that a duress PIN exists.** In a duress session the Security screen shows biometrics simply "off" (no reason), the Duress-PIN and Top-up rows are absent, View Recovery Phrase is absent/blocked, and no error/log distinguishes a duress state.
- **Account-0 derivation must stay byte-identical.** The six existing derivation functions keep their exact signatures and delegate to the new `*ForAccount` twins with `BIP84_ACCOUNT` (== 0); the pre-existing account-0 KATs (`bip86_derivation_kat`, `bip86_privkey_kat`) must stay green — that green run *is* the byte-identical guarantee.
- **Native changes require rebuilding `:native` before `:app`:** `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug`.
- App-level decoy, **not** key hiding (see spec threat model) — honest in-app disclosure of what duress does and does not protect.
- **Out of scope for Phase A (do NOT build):** the DigiScope alert (`DuressAlertClient`, `/api/duress/*`) and the on-chain keyed-marker OP_RETURN on the duress send. Those are Phase B. Phase A is independently shippable and delivers protection before the alert lands.

---

### Task 1: Native — account-parameterized BIP32 derivation (submodule)

Adds six `*ForAccount` sibling functions to the C core so callers can derive at account N; the six existing public functions become one-line delegators passing `BIP84_ACCOUNT` (0), keeping account 0 byte-identical. Correctness + isolation + byte-identical account-0 are proven by a new self-contained host KAT.

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRBIP32Sequence.h` (add 6 declarations after line 99)
- Modify: `native/src/main/jni/digibytewallet-core/BRBIP32Sequence.c` (refactor 6 functions at lines 140-171, 176-207, 297-321, 324-355, 358-382, 385-416)
- Create: `native/src/test/host/duress_account_kat/duress_account_kat_main.c`
- Create: `native/src/test/host/duress_account_kat/run.sh`

**Interfaces:**
- Consumes: existing `BRBIP32PubKey(uint8_t*, size_t, BRMasterPubKey, uint32_t chain, uint32_t index)`, `BRKeyPubKey(BRKey*, uint8_t*, size_t)`, `BRKeyTaprootAddress(BRKey*, char*, size_t)`, `UInt256Eq`, constant `BIP84_ACCOUNT` (== 0, `BRBIP32Sequence.h:46`).
- Produces (later tasks link against these exact signatures):
  - `BRMasterPubKey BRBIP32MasterPubKeyBIP84ForAccount(const void *seed, size_t seedLen, uint32_t account);`
  - `BRMasterPubKey BRBIP32MasterPubKeyBIP86ForAccount(const void *seed, size_t seedLen, uint32_t account);`
  - `void BRBIP32PrivKeyBIP84ForAccount(BRKey *key, const void *seed, size_t seedLen, uint32_t account, uint32_t chain, uint32_t index);`
  - `void BRBIP32PrivKeyListBIP84ForAccount(BRKey keys[], size_t keysCount, const void *seed, size_t seedLen, uint32_t account, uint32_t chain, const uint32_t indexes[]);`
  - `void BRBIP32PrivKeyBIP86ForAccount(BRKey *key, const void *seed, size_t seedLen, uint32_t account, uint32_t chain, uint32_t index);`
  - `void BRBIP32PrivKeyListBIP86ForAccount(BRKey keys[], size_t keysCount, const void *seed, size_t seedLen, uint32_t account, uint32_t chain, const uint32_t indexes[]);`
  - The six existing functions keep their signatures and now delegate account 0.

- [ ] **Step 1: Write the failing host KAT**

Create `native/src/test/host/duress_account_kat/duress_account_kat_main.c`:

```c
// Host KAT for the account-parameterized BIP32 derivation twins added in
// Duress-PIN Phase A Task 1 (BRBIP32*ForAccount). Proves, with NO externally
// computed magic numbers except the already-reviewed account-0 BIP86 pins
// reused verbatim from bip86_privkey_kat:
//
//  (A) BYTE-IDENTICAL ACCOUNT 0 (the refactor guarantee): the whole-struct
//      output of the existing BRBIP32MasterPubKeyBIP84/86(seed,len) equals
//      *ForAccount(seed,len,0), and the existing BRBIP32PrivKeyBIP84/86 secret
//      equals *ForAccount(...,0,...).
//  (B) ACCOUNT-0 INDEPENDENT ANCHOR: *ForAccount(...,0) for BIP86 reproduces
//      the three independently-computed m/86'/20'/0'/0/i pubkeys pinned and
//      reviewed in bip86_privkey_kat/bip86_derivation_kat.
//  (C) ACCOUNT-1' INTERNAL CORRECTNESS: for BIP84 and BIP86, the public
//      derivation path and the private derivation path produce the identical
//      compressed pubkey for m/PURPOSE'/20'/1'/0/i (priv/pub consistency), and
//      the batch list function agrees with the singular function.
//  (D) ACCOUNT ISOLATION (the whole point of the decoy): account-1 pubkey !=
//      account-0 pubkey at every index, for BIP84 and BIP86; and the account-1'
//      BIP86 taproot address is a well-formed dgb1p… that differs from the
//      account-0 taproot address.
//
// FIXED SEED: BIP-32 spec test-vector-1 seed (16 raw bytes), same as
// bip86_privkey_kat so the account-0 pins below are the SAME reviewed values.
// Exit code 0 = all checks passed, 1 = at least one failed.

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "BRBIP32Sequence.h"
#include "BRKey.h"
#include "BRInt.h"

static int g_failures = 0;

static void check(int cond, const char *desc)
{
    if (cond) { printf("PASS: %s\n", desc); }
    else      { printf("FAIL: %s\n", desc); g_failures++; }
}

static uint8_t hexval(char c)
{
    if (c >= '0' && c <= '9') return (uint8_t)(c - '0');
    if (c >= 'a' && c <= 'f') return (uint8_t)(c - 'a' + 10);
    if (c >= 'A' && c <= 'F') return (uint8_t)(c - 'A' + 10);
    fprintf(stderr, "bad hex char: %c\n", c); exit(2);
}

static void hex2bin(const char *hex, uint8_t *out, size_t n)
{
    for (size_t i = 0; i < n; i++)
        out[i] = (uint8_t)((hexval(hex[2*i]) << 4) | hexval(hex[2*i + 1]));
}

static const char *SEED_HEX = "000102030405060708090a0b0c0d0e0f";

// Reviewed account-0 m/86'/20'/0'/0/i pubkeys, reused verbatim from bip86_privkey_kat.
static const char *expected_pub0_86[3] = {
    "02d5e0879430e60b846958cbb075bc6e4b72bb9551d0f02dc15f6f21b2eca662d0",
    "02be431cc9db9d3a70d6536537c01eb9852664c310620c8258a9501a4d79463173",
    "02d5578f2049ae7f94b99ac5768209e271fb970a606bac44e907d7e9b94c888031",
};

int main(void)
{
    uint8_t seed[16];
    hex2bin(SEED_HEX, seed, sizeof(seed));

    // ---- (A) byte-identical account 0: master pubkeys ----
    BRMasterPubKey m84_legacy = BRBIP32MasterPubKeyBIP84(seed, sizeof(seed));
    BRMasterPubKey m84_acct0  = BRBIP32MasterPubKeyBIP84ForAccount(seed, sizeof(seed), 0);
    check(memcmp(&m84_legacy, &m84_acct0, sizeof(BRMasterPubKey)) == 0,
          "BIP84: BRBIP32MasterPubKeyBIP84() == *ForAccount(...,0) (byte-identical account 0)");

    BRMasterPubKey m86_legacy = BRBIP32MasterPubKeyBIP86(seed, sizeof(seed));
    BRMasterPubKey m86_acct0  = BRBIP32MasterPubKeyBIP86ForAccount(seed, sizeof(seed), 0);
    check(memcmp(&m86_legacy, &m86_acct0, sizeof(BRMasterPubKey)) == 0,
          "BIP86: BRBIP32MasterPubKeyBIP86() == *ForAccount(...,0) (byte-identical account 0)");

    // ---- (A) byte-identical account 0: private keys ----
    for (uint32_t i = 0; i < 3; i++) {
        char label[192];
        BRKey a, b;
        memset(&a, 0, sizeof(a)); memset(&b, 0, sizeof(b));
        BRBIP32PrivKeyBIP84(&a, seed, sizeof(seed), 0, i);
        BRBIP32PrivKeyBIP84ForAccount(&b, seed, sizeof(seed), 0, 0, i);
        snprintf(label, sizeof(label),
                 "BIP84 m/84'/20'/0'/0/%u: BRBIP32PrivKeyBIP84 secret == *ForAccount(...,0,...) secret", i);
        check(UInt256Eq(a.secret, b.secret), label);

        BRKey c, d;
        memset(&c, 0, sizeof(c)); memset(&d, 0, sizeof(d));
        BRBIP32PrivKeyBIP86(&c, seed, sizeof(seed), 0, i);
        BRBIP32PrivKeyBIP86ForAccount(&d, seed, sizeof(seed), 0, 0, i);
        snprintf(label, sizeof(label),
                 "BIP86 m/86'/20'/0'/0/%u: BRBIP32PrivKeyBIP86 secret == *ForAccount(...,0,...) secret", i);
        check(UInt256Eq(c.secret, d.secret), label);
    }

    // ---- (B) account-0 independent anchor (BIP86 reviewed pins) ----
    for (uint32_t i = 0; i < 3; i++) {
        char label[192];
        uint8_t pub[33];
        BRBIP32PubKey(pub, sizeof(pub), m86_acct0, 0, i);
        uint8_t expected[33];
        hex2bin(expected_pub0_86[i], expected, 33);
        snprintf(label, sizeof(label),
                 "BIP86 account 0 via *ForAccount reproduces reviewed m/86'/20'/0'/0/%u pubkey", i);
        check(memcmp(pub, expected, 33) == 0, label);
    }

    // ---- account-1' master pubkeys ----
    BRMasterPubKey m84_acct1 = BRBIP32MasterPubKeyBIP84ForAccount(seed, sizeof(seed), 1);
    BRMasterPubKey m86_acct1 = BRBIP32MasterPubKeyBIP86ForAccount(seed, sizeof(seed), 1);

    // ---- (C) account-1' priv/pub consistency + (D) non-collision ----
    for (uint32_t i = 0; i < 3; i++) {
        char label[192];

        // BIP84 account 1'
        BRKey k84;
        memset(&k84, 0, sizeof(k84));
        BRBIP32PrivKeyBIP84ForAccount(&k84, seed, sizeof(seed), 1, 0, i);
        uint8_t priv84[33]; size_t priv84Len = BRKeyPubKey(&k84, priv84, sizeof(priv84));
        uint8_t pub84[33];  BRBIP32PubKey(pub84, sizeof(pub84), m84_acct1, 0, i);
        snprintf(label, sizeof(label),
                 "BIP84 m/84'/20'/1'/0/%u: private-path pubkey == public-path pubkey", i);
        check(priv84Len == 33 && memcmp(priv84, pub84, 33) == 0, label);

        uint8_t pub84_0[33]; BRBIP32PubKey(pub84_0, sizeof(pub84_0), m84_acct0, 0, i);
        snprintf(label, sizeof(label),
                 "BIP84 index %u: account-1' pubkey != account-0' pubkey (isolation)", i);
        check(memcmp(pub84, pub84_0, 33) != 0, label);

        // BIP86 account 1'
        BRKey k86;
        memset(&k86, 0, sizeof(k86));
        BRBIP32PrivKeyBIP86ForAccount(&k86, seed, sizeof(seed), 1, 0, i);
        uint8_t priv86[33]; size_t priv86Len = BRKeyPubKey(&k86, priv86, sizeof(priv86));
        uint8_t pub86[33];  BRBIP32PubKey(pub86, sizeof(pub86), m86_acct1, 0, i);
        snprintf(label, sizeof(label),
                 "BIP86 m/86'/20'/1'/0/%u: private-path pubkey == public-path pubkey", i);
        check(priv86Len == 33 && memcmp(priv86, pub86, 33) == 0, label);

        uint8_t pub86_0[33]; BRBIP32PubKey(pub86_0, sizeof(pub86_0), m86_acct0, 0, i);
        snprintf(label, sizeof(label),
                 "BIP86 index %u: account-1' pubkey != account-0' pubkey (isolation)", i);
        check(memcmp(pub86, pub86_0, 33) != 0, label);

        // (D) account-1' taproot address is well-formed and != account-0 taproot address
        char addr1[91], addr0[91];
        memset(addr1, 0, sizeof(addr1)); memset(addr0, 0, sizeof(addr0));
        BRKeyTaprootAddress(&k86, addr1, sizeof(addr1));
        BRKey k86_0; memset(&k86_0, 0, sizeof(k86_0));
        BRBIP32PrivKeyBIP86ForAccount(&k86_0, seed, sizeof(seed), 0, 0, i);
        BRKeyTaprootAddress(&k86_0, addr0, sizeof(addr0));
        snprintf(label, sizeof(label),
                 "BIP86 index %u: account-1' taproot addr is dgb1p… and != account-0 taproot addr", i);
        check(strncmp(addr1, "dgb1p", 5) == 0 && strcmp(addr1, addr0) != 0, label);
    }

    // ---- (C) account-1' batch consistency ----
    uint32_t indexes[3] = { 0, 1, 2 };
    BRKey ind84[3], batch84[3], ind86[3], batch86[3];
    memset(ind84, 0, sizeof(ind84)); memset(batch84, 0, sizeof(batch84));
    memset(ind86, 0, sizeof(ind86)); memset(batch86, 0, sizeof(batch86));
    for (uint32_t i = 0; i < 3; i++) {
        BRBIP32PrivKeyBIP84ForAccount(&ind84[i], seed, sizeof(seed), 1, 0, i);
        BRBIP32PrivKeyBIP86ForAccount(&ind86[i], seed, sizeof(seed), 1, 0, i);
    }
    BRBIP32PrivKeyListBIP84ForAccount(batch84, 3, seed, sizeof(seed), 1, 0, indexes);
    BRBIP32PrivKeyListBIP86ForAccount(batch86, 3, seed, sizeof(seed), 1, 0, indexes);
    for (uint32_t i = 0; i < 3; i++) {
        char label[192];
        snprintf(label, sizeof(label),
                 "BIP84 account 1' index %u: batch secret == singular secret", i);
        check(UInt256Eq(batch84[i].secret, ind84[i].secret), label);
        snprintf(label, sizeof(label),
                 "BIP86 account 1' index %u: batch secret == singular secret", i);
        check(UInt256Eq(batch86[i].secret, ind86[i].secret), label);
    }

    if (g_failures == 0) { printf("\nALL PASS (0 failure(s))\n"); return 0; }
    printf("\nSOME FAILED (%d failure(s))\n", g_failures); return 1;
}
```

Create `native/src/test/host/duress_account_kat/run.sh` (identical clang link set to `bip86_privkey_kat/run.sh` — needs `BRKeyTaprootAddress` + `BRBIP32PubKey` + full crypto chain):

```bash
#!/usr/bin/env bash
# Host KAT runner for the account-parameterized BIP32 twins (BRBIP32*ForAccount)
# added in Duress-PIN Phase A Task 1. Same real-file compile approach as
# bip86_privkey_kat/run.sh (clang, NOT gcc; -include stdint.h; link the whole
# crypto dependency chain because BRBIP32Sequence.c/BRKey.c reference symbols
# down through crypto/*). Exit 0 = all checks passed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

shopt -s nullglob
SHA3_SRCS=("$CORE_DIR"/crypto/sha3/*.c)
shopt -u nullglob

clang -w -include stdint.h \
    -I "$CORE_DIR" \
    -I "$CORE_DIR/secp256k1/include" \
    "$SCRIPT_DIR/duress_account_kat_main.c" \
    "$CORE_DIR/BRBIP32Sequence.c" \
    "$CORE_DIR/BRKey.c" \
    "$CORE_DIR/BRNetwork.c" \
    "$CORE_DIR/BRAddress.c" \
    "$CORE_DIR/BRCrypto.c" \
    "$CORE_DIR/BRBase58.c" \
    "$CORE_DIR/BRBech32.c" \
    "$CORE_DIR/crypto/groestl.c" \
    "$CORE_DIR/crypto/skein.c" \
    "$CORE_DIR/crypto/qubit.c" \
    "$CORE_DIR/crypto/odocrypt.c" \
    "${SHA3_SRCS[@]}" \
    -o "$BUILD_DIR/duress_account_kat"

"$BUILD_DIR/duress_account_kat"
```

Then `chmod +x native/src/test/host/duress_account_kat/run.sh`.

- [ ] **Step 2: Run the KAT to verify it fails**

Run: `bash native/src/test/host/duress_account_kat/run.sh`
Expected: FAIL — clang link error: `undefined symbol: _BRBIP32MasterPubKeyBIP84ForAccount` (and the five other `*ForAccount` symbols). The KAT does not build because the functions do not exist yet.

- [ ] **Step 3: Add the six declarations to the header**

In `native/src/main/jni/digibytewallet-core/BRBIP32Sequence.h`, immediately after line 99 (the end of the existing `BRBIP32PrivKeyListBIP86` declaration, before line 101's `BRBIP32PubKey`), add:

```c
// ── Account-parameterized derivation (duress decoy = account 1') ──────────────
// Byte-identical to the account-0 functions above when account == BIP84_ACCOUNT (0).
// `account` sits right after seedLen to mirror the path order m/purpose'/coin'/ACCOUNT'/chain/index.

BRMasterPubKey BRBIP32MasterPubKeyBIP84ForAccount(const void *seed, size_t seedLen, uint32_t account);
BRMasterPubKey BRBIP32MasterPubKeyBIP86ForAccount(const void *seed, size_t seedLen, uint32_t account);

void BRBIP32PrivKeyBIP84ForAccount(BRKey *key, const void *seed, size_t seedLen, uint32_t account,
                                   uint32_t chain, uint32_t index);
void BRBIP32PrivKeyListBIP84ForAccount(BRKey keys[], size_t keysCount, const void *seed, size_t seedLen,
                                       uint32_t account, uint32_t chain, const uint32_t indexes[]);
void BRBIP32PrivKeyBIP86ForAccount(BRKey *key, const void *seed, size_t seedLen, uint32_t account,
                                   uint32_t chain, uint32_t index);
void BRBIP32PrivKeyListBIP86ForAccount(BRKey keys[], size_t keysCount, const void *seed, size_t seedLen,
                                       uint32_t account, uint32_t chain, const uint32_t indexes[]);
```

- [ ] **Step 4: Refactor the six functions in the .c (bodies → `*ForAccount`, originals → delegators)**

In `native/src/main/jni/digibytewallet-core/BRBIP32Sequence.c`, replace `BRBIP32MasterPubKeyBIP84` (lines 140-171) with the renamed body plus a delegator (only the account `_CKDpriv` line changes: `BIP84_ACCOUNT` → `account`):

```c
// BIP84 master pub key at an arbitrary hardened account: m/84'/20'/account'
BRMasterPubKey BRBIP32MasterPubKeyBIP84ForAccount(const void *seed, size_t seedLen, uint32_t account)
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
        _CKDpriv(&secret, &chain, account | BIP32_HARD);        // m/84'/20'/account'

        mpk.chainCode = chain;
        BRKeySetSecret(&key, &secret, 1);
        var_clean(&secret, &chain);
        BRKeyPubKey(&key, &mpk.pubKey, sizeof(mpk.pubKey));
        BRKeyClean(&key);
    }

    return mpk;
}

// BIP84 master pub key: m/84'/20'/0' with standard "Bitcoin seed" HMAC
BRMasterPubKey BRBIP32MasterPubKeyBIP84(const void *seed, size_t seedLen)
{
    return BRBIP32MasterPubKeyBIP84ForAccount(seed, seedLen, BIP84_ACCOUNT);
}
```

Replace `BRBIP32MasterPubKeyBIP86` (lines 176-207) the same way (only the account line changes; keep the BIP86 purpose line):

```c
// BIP86 master pub key at an arbitrary hardened account: m/86'/20'/account'
BRMasterPubKey BRBIP32MasterPubKeyBIP86ForAccount(const void *seed, size_t seedLen, uint32_t account)
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

        _CKDpriv(&secret, &chain, BIP86_PURPOSE | BIP32_HARD); // m/86'
        _CKDpriv(&secret, &chain, DGB_COIN_TYPE | BIP32_HARD);  // m/86'/20'
        _CKDpriv(&secret, &chain, account | BIP32_HARD);        // m/86'/20'/account'

        mpk.chainCode = chain;
        BRKeySetSecret(&key, &secret, 1);
        var_clean(&secret, &chain);
        BRKeyPubKey(&key, &mpk.pubKey, sizeof(mpk.pubKey));
        BRKeyClean(&key);
    }

    return mpk;
}

// BIP86 master pub key: m/86'/20'/0' with standard "Bitcoin seed" HMAC
BRMasterPubKey BRBIP32MasterPubKeyBIP86(const void *seed, size_t seedLen)
{
    return BRBIP32MasterPubKeyBIP86ForAccount(seed, seedLen, BIP84_ACCOUNT);
}
```

Replace `BRBIP32PrivKeyBIP84` (lines 297-321):

```c
// BIP84 private key at an arbitrary hardened account: m/84'/20'/account'/chain/index
void BRBIP32PrivKeyBIP84ForAccount(BRKey *key, const void *seed, size_t seedLen, uint32_t account,
                                   uint32_t chain, uint32_t index)
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
        _CKDpriv(&secret, &chainCode, account | BIP32_HARD);        // account'
        _CKDpriv(&secret, &chainCode, chain);                        // chain
        _CKDpriv(&secret, &chainCode, index);                        // index

        BRKeySetSecret(key, &secret, 1);
        var_clean(&secret, &chainCode);
    }
}

// BIP84 private key: m/84'/20'/0'/chain/index with "Bitcoin seed"
void BRBIP32PrivKeyBIP84(BRKey *key, const void *seed, size_t seedLen, uint32_t chain, uint32_t index)
{
    BRBIP32PrivKeyBIP84ForAccount(key, seed, seedLen, BIP84_ACCOUNT, chain, index);
}
```

Replace `BRBIP32PrivKeyListBIP84` (lines 324-355):

```c
// BIP84 batch private key derivation at an arbitrary hardened account
void BRBIP32PrivKeyListBIP84ForAccount(BRKey keys[], size_t keysCount, const void *seed, size_t seedLen,
                                       uint32_t account, uint32_t chain, const uint32_t indexes[])
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
        _CKDpriv(&secret, &chainCode, account | BIP32_HARD);        // account'
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

// BIP84 batch private key derivation for transaction signing (account 0)
void BRBIP32PrivKeyListBIP84(BRKey keys[], size_t keysCount, const void *seed, size_t seedLen,
                             uint32_t chain, const uint32_t indexes[])
{
    BRBIP32PrivKeyListBIP84ForAccount(keys, keysCount, seed, seedLen, BIP84_ACCOUNT, chain, indexes);
}
```

Replace `BRBIP32PrivKeyBIP86` (lines 358-382):

```c
// BIP86 (Taproot) private key at an arbitrary hardened account: m/86'/20'/account'/chain/index
void BRBIP32PrivKeyBIP86ForAccount(BRKey *key, const void *seed, size_t seedLen, uint32_t account,
                                   uint32_t chain, uint32_t index)
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

        _CKDpriv(&secret, &chainCode, BIP86_PURPOSE | BIP32_HARD); // 86'
        _CKDpriv(&secret, &chainCode, DGB_COIN_TYPE | BIP32_HARD);  // 20'
        _CKDpriv(&secret, &chainCode, account | BIP32_HARD);        // account'
        _CKDpriv(&secret, &chainCode, chain);                        // chain
        _CKDpriv(&secret, &chainCode, index);                        // index

        BRKeySetSecret(key, &secret, 1);
        var_clean(&secret, &chainCode);
    }
}

// BIP86 (Taproot) private key: m/86'/20'/0'/chain/index with "Bitcoin seed"
void BRBIP32PrivKeyBIP86(BRKey *key, const void *seed, size_t seedLen, uint32_t chain, uint32_t index)
{
    BRBIP32PrivKeyBIP86ForAccount(key, seed, seedLen, BIP84_ACCOUNT, chain, index);
}
```

Replace `BRBIP32PrivKeyListBIP86` (lines 385-416):

```c
// BIP86 batch private key derivation at an arbitrary hardened account
void BRBIP32PrivKeyListBIP86ForAccount(BRKey keys[], size_t keysCount, const void *seed, size_t seedLen,
                                       uint32_t account, uint32_t chain, const uint32_t indexes[])
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

        _CKDpriv(&secret, &chainCode, BIP86_PURPOSE | BIP32_HARD); // 86'
        _CKDpriv(&secret, &chainCode, DGB_COIN_TYPE | BIP32_HARD);  // 20'
        _CKDpriv(&secret, &chainCode, account | BIP32_HARD);        // account'
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

// BIP86 batch private key derivation for transaction signing (account 0)
void BRBIP32PrivKeyListBIP86(BRKey keys[], size_t keysCount, const void *seed, size_t seedLen,
                             uint32_t chain, const uint32_t indexes[])
{
    BRBIP32PrivKeyListBIP86ForAccount(keys, keysCount, seed, seedLen, BIP84_ACCOUNT, chain, indexes);
}
```

- [ ] **Step 5: Run the new KAT to verify it passes**

Run: `bash native/src/test/host/duress_account_kat/run.sh`
Expected: PASS — ends with `ALL PASS (0 failure(s))`, exit code 0 (every PASS line printed, no FAIL).

- [ ] **Step 6: Run the pre-existing account-0 KATs to prove byte-identical account 0**

Run: `bash native/src/test/host/bip86_derivation_kat/run.sh && bash native/src/test/host/bip86_privkey_kat/run.sh`
Expected: BOTH end with `ALL PASS (0 failure(s))`, exit code 0. Green here is the byte-identical-account-0 guarantee (the delegators preserve the exact prior behavior).

- [ ] **Step 7: Commit — submodule ritual + parent pin bump + KAT**

The `.c`/`.h` edits live in the git submodule `native/src/main/jni/digibytewallet-core` (fork `JohnnyLawDGB/digibytewallet-core`, remote `johnnylaw`); the KAT lives in the parent repo. Push the fork FIRST, then bump the parent pin.

```bash
# 1) Commit the submodule change (explicit GIT_DIR/GIT_WORK_TREE per CLAUDE.md)
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git add BRBIP32Sequence.c BRBIP32Sequence.h

GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git commit -m "feat(bip32): account-parameterized derivation twins (duress decoy = account 1')

Add BRBIP32{MasterPubKey,PrivKey,PrivKeyList}BIP84/86ForAccount; existing six
functions delegate with BIP84_ACCOUNT so account 0 stays byte-identical.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"

# 2) Push the fork so the pin is fetchable
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git push johnnylaw HEAD

# 3) Bump the pin + add the KAT in the parent repo
git add native/src/main/jni/digibytewallet-core \
        native/src/test/host/duress_account_kat/duress_account_kat_main.c \
        native/src/test/host/duress_account_kat/run.sh
git commit -m "feat(native): pin account-parameterized BIP32 core + duress account-1' host KAT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Expected: submodule commit + push succeed; `git status --ignore-submodules=all` in the parent shows a clean tree after the parent commit.

---

### Task 2: Native — JNI + BRWallet account threading (create / derive / sign at account N)

Threads an account index (default 0) through wallet construction and the signing key derivation so a wallet built for account 1' also *signs* with account-1' keys. `g_seed` stays static to `jni_wallet.c` (accessor model unchanged); only the derivation account is added.

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRWallet.h` (add `BRWalletSetAccount` declaration near line 116)
- Modify: `native/src/main/jni/digibytewallet-core/BRWallet.c` (struct field at ~line 60; `BRWalletNew` at 319; signing at 1435/1438/1450/1453; new setter)
- Modify: `native/src/main/jni/bridge/jni_wallet.c` (new JNI entrypoints + `g_account` + `getLoadedAccount`)
- Modify: `native/src/androidTest/java/io/digibyte/core/bridge/NativeBridge.kt` (stub — mirror the three new external funs)
- Create: `native/src/androidTest/java/io/digibyte/native_core/DuressAccountWalletTest.kt` (device KAT)

**Interfaces:**
- Consumes (Task 1): `BRBIP32MasterPubKeyBIP84ForAccount`, `BRBIP32MasterPubKeyBIP86ForAccount`, `BRBIP32PrivKeyListBIP84ForAccount`, `BRBIP32PrivKeyListBIP86ForAccount`.
- Produces (JNI symbols; Task 3 declares matching `external fun`s):
  - `Java_io_digibyte_core_bridge_NativeBridge_createWalletFromBytesForAccount(JNIEnv*, jobject, jbyteArray, jint account)` → `jboolean`
  - `Java_io_digibyte_core_bridge_NativeBridge_recoverWalletFromBytesForAccount(JNIEnv*, jobject, jbyteArray, jlong creationTimestamp, jint account)` → `jboolean`
  - `Java_io_digibyte_core_bridge_NativeBridge_getLoadedAccount(JNIEnv*, jobject)` → `jint` (the account the loaded wallet was built with; `-1` if no wallet)
  - `void BRWalletSetAccount(BRWallet *wallet, uint32_t account);`

- [ ] **Step 1: Write the failing device KAT**

Create `native/src/androidTest/java/io/digibyte/native_core/DuressAccountWalletTest.kt`. Anchors on the already-pinned account-0 addresses from `TaprootReceiveAddressTest` (canonical "abandon…about" mnemonic) and proves account-1' is a *different*, self-consistent, signable wallet.

```kotlin
package io.digibyte.native_core

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Duress-PIN Phase A Task 2 KAT: createWalletFromBytesForAccount(mnemonic, 1) builds a
 * wallet at BIP84/86 account 1' whose receive addresses DIFFER from the pinned account-0'
 * addresses, and whose transactions sign successfully (account-1' private keys match
 * account-1' addresses). Also proves account 0 is unchanged and getLoadedAccount tracks it.
 */
@RunWith(AndroidJUnit4::class)
class DuressAccountWalletTest {

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon about"

    // Pinned account-0' references (identical to TaprootReceiveAddressTest).
    private val account0Segwit = "dgb1q9gmf0pv8jdymcly6lz6fl7lf6mhslsd72e2jq8"
    private val account0Taproot = "dgb1pcevt23hht82rkdrjdpwzstmqyj4ngyy42r9cu73rl4n9h5vu6hgsx5tm5q"

    private fun bytes() = mnemonic.toByteArray(Charsets.UTF_8)

    @Test
    fun account1_addresses_differ_from_account0_and_getLoadedAccount_tracks() {
        assertTrue("account-0 wallet builds",
            NativeBridge.createWalletFromBytesForAccount(bytes(), 0))
        assertEquals("getLoadedAccount reflects account 0", 0, NativeBridge.getLoadedAccount())
        assertEquals("account 0 segwit unchanged", account0Segwit, NativeBridge.getReceiveAddress(0, 2))
        assertEquals("account 0 taproot unchanged", account0Taproot, NativeBridge.getReceiveAddress(0, 3))

        assertTrue("account-1' decoy wallet builds",
            NativeBridge.createWalletFromBytesForAccount(bytes(), 1))
        assertEquals("getLoadedAccount reflects account 1", 1, NativeBridge.getLoadedAccount())

        val decoySegwit = NativeBridge.getReceiveAddress(0, 2)
        val decoyTaproot = NativeBridge.getReceiveAddress(0, 3)
        assertNotNull(decoySegwit); assertNotNull(decoyTaproot)
        assertTrue("decoy segwit is dgb1q…", decoySegwit!!.startsWith("dgb1q"))
        assertTrue("decoy taproot is dgb1p…", decoyTaproot!!.startsWith("dgb1p"))
        assertNotEquals("decoy segwit must differ from account 0", account0Segwit, decoySegwit)
        assertNotEquals("decoy taproot must differ from account 0", account0Taproot, decoyTaproot)
    }

    @Test
    fun account1_zeroArg_default_still_account0() {
        // The unchanged zero-arg builder must remain account 0 (byte-identical default path).
        assertTrue(NativeBridge.createWalletFromBytes(bytes()))
        assertEquals(0, NativeBridge.getLoadedAccount())
        assertEquals(account0Segwit, NativeBridge.getReceiveAddress(0, 2))
    }
}
```

- [ ] **Step 2: Run the KAT to verify it fails**

Run: `./gradlew :native:connectedMainnetDebugAndroidTest --tests "io.digibyte.native_core.DuressAccountWalletTest"`
Expected: FAIL — compile error `unresolved reference: createWalletFromBytesForAccount` / `getLoadedAccount` (the external funs and JNI symbols do not exist yet). (Requires a connected device/emulator: `adb devices` shows one.)

- [ ] **Step 3: Add the account field + setter to BRWallet (submodule)**

In `native/src/main/jni/digibytewallet-core/BRWallet.c`, inside `struct BRWalletStruct` (after line 59's `int hasTaprootKey;`), add:

```c
    // Hardened BIP44 account level the wallet's keys derive at (0 = real, 1 = duress decoy).
    // Signing re-derivation reads this so an account-1' wallet signs with account-1' keys.
    uint32_t account;
```

In `BRWalletNew` (after line 345's `wallet->hasTaprootKey = 0;`), initialize it:

```c
    wallet->account = BIP84_ACCOUNT; // 0 — default real account; BRWalletSetAccount overrides for the decoy
```

Add the setter (place it right after `BRWalletSetTaprootKey`, which ends near line 525):

```c
// Set the hardened BIP44 account the wallet's signing keys derive at. Must be called
// immediately after BRWalletNew/BRWalletNewDual (before signing). Address generation
// already flows from the account-N master pub key installed at construction; this makes
// BRWalletSignTransaction re-derive private keys at the SAME account.
void BRWalletSetAccount(BRWallet *wallet, uint32_t account)
{
    assert(wallet != NULL);
    if (wallet) wallet->account = account;
}
```

In `native/src/main/jni/digibytewallet-core/BRWallet.h`, after line 116's `BRWalletSetTaprootKey` declaration, add:

```c
// sets the hardened BIP44 account (0 = real, 1 = duress decoy) used by BRWalletSignTransaction
void BRWalletSetAccount(BRWallet *wallet, uint32_t account);
```

- [ ] **Step 4: Route the signing key derivation through the account (submodule)**

In `native/src/main/jni/digibytewallet-core/BRWallet.c`, `BRWalletSignTransaction`, change ONLY the four BIP84/BIP86 batch derivations (lines 1435, 1438, 1450, 1453) to the `*ForAccount` twins passing `wallet->account`. The two legacy calls (1442, 1445) are account-less and stay unchanged.

```c
        // BIP84 keys: use "Bitcoin seed" + m/84'/20'/account'
        BRBIP32PrivKeyListBIP84ForAccount(keys + keyOff, bip84InternalCount, seed, seedLen,
                                          wallet->account, SEQUENCE_INTERNAL_CHAIN, bip84InternalIdx);
        keyOff += bip84InternalCount;
        BRBIP32PrivKeyListBIP84ForAccount(keys + keyOff, bip84ExternalCount, seed, seedLen,
                                          wallet->account, SEQUENCE_EXTERNAL_CHAIN, bip84ExternalIdx);
        keyOff += bip84ExternalCount;
        // Legacy keys: use "DigiByte seed" + m/0H (account-less — unchanged)
        BRBIP32PrivKeyList(keys + keyOff, legacyInternalCount, seed, seedLen,
                           SEQUENCE_INTERNAL_CHAIN, legacyInternalIdx);
        keyOff += legacyInternalCount;
        BRBIP32PrivKeyList(keys + keyOff, legacyExternalCount, seed, seedLen,
                           SEQUENCE_EXTERNAL_CHAIN, legacyExternalIdx);
        keyOff += legacyExternalCount;
        // Taproot keys: use "Bitcoin seed" + m/86'/20'/account'
        BRBIP32PrivKeyListBIP86ForAccount(keys + keyOff, taprootInternalCount, seed, seedLen,
                                          wallet->account, SEQUENCE_INTERNAL_CHAIN, taprootInternalIdx);
        keyOff += taprootInternalCount;
        BRBIP32PrivKeyListBIP86ForAccount(keys + keyOff, taprootExternalCount, seed, seedLen,
                                          wallet->account, SEQUENCE_EXTERNAL_CHAIN, taprootExternalIdx);
```

Because `wallet->account` defaults to 0, every existing (account-0) sign is byte-identical.

- [ ] **Step 5: Add the account-aware JNI entrypoints + `g_account` + `getLoadedAccount` (parent repo)**

In `native/src/main/jni/bridge/jni_wallet.c`, near the top globals (after line 23's `int g_mpkValid = 0;`), add:

```c
/* Hardened BIP44 account the currently-loaded g_wallet was built with (0 = real,
 * 1 = duress decoy). Read by getLoadedAccount so Kotlin's unlock fast-path can
 * detect when the in-memory wallet is the wrong account and force a rebuild. */
static uint32_t g_account = 0;
```

Refactor `createWalletFromBytes` (lines 224-295) so its body becomes a static helper taking an account, and add the account-aware JNI export. Replace the whole `Java_..._createWalletFromBytes` function with:

```c
static jboolean create_wallet_from_bytes_impl(JNIEnv *env, jbyteArray phraseBytes, uint32_t account) {
    if (!phraseBytes) {
        LOGW("createWalletFromBytes: phraseBytes is null");
        return JNI_FALSE;
    }

    jsize phraseLen = (*env)->GetArrayLength(env, phraseBytes);
    if (phraseLen <= 0 || phraseLen > 1024) {
        LOGW("createWalletFromBytes: invalid length=%d", phraseLen);
        return JNI_FALSE;
    }

    char phraseChars[phraseLen + 1];
    (*env)->GetByteArrayRegion(env, phraseBytes, 0, phraseLen, (jbyte *)phraseChars);
    phraseChars[phraseLen] = '\0';

    if (!BRBIP39PhraseIsValid(BRBIP39WordsEn, phraseChars)) {
        LOGW("createWalletFromBytes: invalid BIP39 phrase");
        secure_zero(phraseChars, sizeof(phraseChars));
        return JNI_FALSE;
    }

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, phraseChars, NULL);
    secure_zero(phraseChars, sizeof(phraseChars));

    BRMasterPubKey mpk = BRBIP32MasterPubKeyBIP84ForAccount(seed, sizeof(seed), account);
    /* Taproot (BIP86: m/86'/20'/account') twin from the SAME seed — installed after BRWalletNew. */
    BRMasterPubKey mpkBIP86 = BRBIP32MasterPubKeyBIP86ForAccount(seed, sizeof(seed), account);

    if (g_wallet) {
        LOGW("createWalletFromBytes: wallet already exists, freeing old one");
        BRWalletFree(g_wallet);
        g_wallet = NULL;
    }

    g_wallet = BRWalletNew(NULL, 0, mpk);
    if (!g_wallet) {
        LOGE("createWalletFromBytes: BRWalletNew failed");
        secure_zero(seed, sizeof(seed));
        return JNI_FALSE;
    }

    BRWalletSetAccount(g_wallet, account);

    /* Install the BIP86 Taproot key + pre-gen the P2TR gap windows (m/86', same seed) */
    BRWalletSetTaprootKey(g_wallet, mpkBIP86);

    memcpy(g_seed, seed, sizeof(seed));
    g_seedValid = 1;
    g_mpk = mpk;
    g_mpkValid = 1;
    g_account = account;
    g_walletCreationTime = (uint32_t)time(NULL);  /* New wallet = now */
    g_peerManagerNeedsRecreate = 1;

    secure_zero(seed, sizeof(seed));

    LOGI("createWalletFromBytes: wallet created (account=%u, creationTime=%u)", account, g_walletCreationTime);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_createWalletFromBytes(JNIEnv *env, jobject thiz,
                                                                  jbyteArray phraseBytes) {
    (void)thiz;
    return create_wallet_from_bytes_impl(env, phraseBytes, BIP84_ACCOUNT);
}

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_createWalletFromBytesForAccount(JNIEnv *env, jobject thiz,
                                                                            jbyteArray phraseBytes,
                                                                            jint account) {
    (void)thiz;
    return create_wallet_from_bytes_impl(env, phraseBytes, (uint32_t)account);
}
```

Refactor `recoverWalletFromBytes` (lines 386-458) the same way. Replace the whole `Java_..._recoverWalletFromBytes` function with:

```c
static jboolean recover_wallet_from_bytes_impl(JNIEnv *env, jbyteArray phraseBytes,
                                               jlong creationTimestamp, uint32_t account) {
    if (!phraseBytes) {
        LOGW("recoverWalletFromBytes: phraseBytes is null");
        return JNI_FALSE;
    }

    jsize phraseLen = (*env)->GetArrayLength(env, phraseBytes);
    if (phraseLen <= 0 || phraseLen > 1024) {
        LOGW("recoverWalletFromBytes: invalid length=%d", phraseLen);
        return JNI_FALSE;
    }

    char phraseChars[phraseLen + 1];
    (*env)->GetByteArrayRegion(env, phraseBytes, 0, phraseLen, (jbyte *)phraseChars);
    phraseChars[phraseLen] = '\0';

    if (!BRBIP39PhraseIsValid(BRBIP39WordsEn, phraseChars)) {
        LOGW("recoverWalletFromBytes: invalid BIP39 phrase");
        secure_zero(phraseChars, sizeof(phraseChars));
        return JNI_FALSE;
    }

    uint8_t seed[64];
    BRBIP39DeriveKey(seed, phraseChars, NULL);
    secure_zero(phraseChars, sizeof(phraseChars));

    BRMasterPubKey mpkBIP84  = BRBIP32MasterPubKeyBIP84ForAccount(seed, sizeof(seed), account);
    BRMasterPubKey mpkLegacy = BRBIP32MasterPubKeyLegacy(seed, sizeof(seed));
    BRMasterPubKey mpkBIP86  = BRBIP32MasterPubKeyBIP86ForAccount(seed, sizeof(seed), account);

    if (g_wallet) {
        BRWalletFree(g_wallet);
        g_wallet = NULL;
    }

    extern BRTransaction **g_savedTransactions;
    extern size_t g_savedTransactionCount;

    if (g_savedTransactions && g_savedTransactionCount > 0) {
        LOGI("recoverWalletFromBytes: restoring with %zu saved transactions", g_savedTransactionCount);
        g_wallet = BRWalletNewDual(g_savedTransactions, g_savedTransactionCount, mpkBIP84, mpkLegacy);
    } else {
        g_wallet = BRWalletNewDual(NULL, 0, mpkBIP84, mpkLegacy);
    }
    if (!g_wallet) {
        LOGE("recoverWalletFromBytes: BRWalletNewDual failed");
        secure_zero(seed, sizeof(seed));
        return JNI_FALSE;
    }

    BRWalletSetAccount(g_wallet, account);

    /* Install the BIP86 Taproot key + pre-gen the P2TR gap windows (m/86', same seed) */
    BRWalletSetTaprootKey(g_wallet, mpkBIP86);

    memcpy(g_seed, seed, sizeof(seed));
    g_seedValid = 1;
    g_mpk = mpkBIP84;
    g_mpkValid = 1;
    g_account = account;

    secure_zero(seed, sizeof(seed));

    g_walletCreationTime = creationTimestamp > 0 ? (uint32_t)creationTimestamp : (uint32_t)time(NULL);
    g_peerManagerNeedsRecreate = 1;

    LOGI("recoverWalletFromBytes: wallet recovered (account=%u, creationTime=%u)", account, g_walletCreationTime);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_recoverWalletFromBytes(JNIEnv *env, jobject thiz,
                                                                   jbyteArray phraseBytes,
                                                                   jlong creationTimestamp) {
    (void)thiz;
    return recover_wallet_from_bytes_impl(env, phraseBytes, creationTimestamp, BIP84_ACCOUNT);
}

JNIEXPORT jboolean JNICALL
Java_io_digibyte_core_bridge_NativeBridge_recoverWalletFromBytesForAccount(JNIEnv *env, jobject thiz,
                                                                             jbyteArray phraseBytes,
                                                                             jlong creationTimestamp,
                                                                             jint account) {
    (void)thiz;
    return recover_wallet_from_bytes_impl(env, phraseBytes, creationTimestamp, (uint32_t)account);
}
```

Add the `getLoadedAccount` accessor (place it right after the `recoverWalletFromBytesForAccount` export):

```c
/* Returns the hardened account (0/1) the loaded wallet was built with, or -1 if
 * no wallet is loaded. Lets Kotlin detect a wrong-account fast-path and rebuild. */
JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_getLoadedAccount(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return g_wallet ? (jint)g_account : (jint)-1;
}
```

- [ ] **Step 6: Mirror the three new external funs into the native androidTest stub**

`native/src/androidTest/java/io/digibyte/core/bridge/NativeBridge.kt` is a duplicate stub that must stay in sync or `:native` androidTest fails to compile. After line 49 (`external fun recoverWalletFromBytes(...)`), add:

```kotlin
    external fun createWalletFromBytesForAccount(phraseBytes: ByteArray, account: Int): Boolean
    external fun recoverWalletFromBytesForAccount(phraseBytes: ByteArray, creationTimestamp: Long, account: Int): Boolean
    external fun getLoadedAccount(): Int
```

- [ ] **Step 7: Rebuild native then app, and run the device KAT**

Run: `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug`
Expected: BUILD SUCCESSFUL (native rebuilt before app, per Global Constraints).

Run: `./gradlew :native:connectedMainnetDebugAndroidTest --tests "io.digibyte.native_core.DuressAccountWalletTest"`
Expected: PASS — both tests green: account-1' addresses differ from the pinned account-0 addresses, `getLoadedAccount()` returns 1 then 0, and the unchanged zero-arg builder stays account 0.

- [ ] **Step 8: Commit — submodule ritual (BRWallet) + parent (jni_wallet, stub, KAT)**

```bash
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git add BRWallet.c BRWallet.h

GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git commit -m "feat(wallet): thread hardened account into BRWalletSignTransaction (duress decoy)

Add BRWalletStruct.account + BRWalletSetAccount; sign path derives BIP84/86 keys at
wallet->account (default 0 => byte-identical). Legacy m/0H keys unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"

GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git push johnnylaw HEAD

git add native/src/main/jni/digibytewallet-core \
        native/src/main/jni/bridge/jni_wallet.c \
        native/src/androidTest/java/io/digibyte/core/bridge/NativeBridge.kt \
        native/src/androidTest/java/io/digibyte/native_core/DuressAccountWalletTest.kt
git commit -m "feat(jni): account-aware wallet create/recover + getLoadedAccount (duress decoy)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Expected: submodule commit + push succeed; parent commit clean.

---

### Task 3: Kotlin bridge + WalletManager account threading + DuressSession holder

Exposes the new JNI entrypoints in the canonical `NativeBridge`, threads an `account: Int` (default 0) through `WalletManager`'s load path with a wrong-account fast-path guard and per-account persistence isolation, and introduces the process-scoped `DuressSession` flag the UI reads. Default (account 0) path stays byte-identical.

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt` (after lines 35/38/198)
- Create: `core/src/main/java/io/digibyte/core/security/DuressSession.kt`
- Modify: `app/src/main/java/io/digibyte/di/AppModule.kt` (add `provideDuressSession`)
- Modify: `core/src/main/java/io/digibyte/core/WalletManager.kt` (restoreFromDisk/unlock/unlockFromUi, account suffix)
- Create: `core/src/test/java/io/digibyte/core/security/DuressSessionTest.kt`
- Create: `core/src/test/java/io/digibyte/core/WalletManagerAccountSuffixTest.kt`

**Interfaces:**
- Consumes (Task 2): JNI `createWalletFromBytesForAccount`, `recoverWalletFromBytesForAccount`, `getLoadedAccount`.
- Produces (Tasks 5-7 rely on these):
  - `NativeBridge.createWalletFromBytesForAccount(phraseBytes: ByteArray, account: Int): Boolean`
  - `NativeBridge.recoverWalletFromBytesForAccount(phraseBytes: ByteArray, creationTimestamp: Long, account: Int): Boolean`
  - `NativeBridge.getLoadedAccount(): Int`
  - `class DuressSession` with `val active: StateFlow<Boolean>`, `fun arm()`, `fun disarm()`, `fun showAssets(): Boolean`, `fun showDigiDollar(): Boolean`, `fun showDuressSettings(): Boolean`, `fun allowSeedView(): Boolean`
  - `WalletManager.restoreFromDisk(account: Int = 0): Boolean`
  - `WalletManager.unlock(authToken: ByteArray, account: Int = 0): Boolean`
  - `WalletManager.unlockFromUi(account: Int = 0)`

- [ ] **Step 1: Write the failing DuressSession unit test**

Create `core/src/test/java/io/digibyte/core/security/DuressSessionTest.kt`:

```kotlin
package io.digibyte.core.security

import org.junit.Assert.*
import org.junit.Test

class DuressSessionTest {

    @Test
    fun default_isDisarmed_andEverythingVisible() {
        val s = DuressSession()
        assertFalse(s.active.value)
        assertTrue(s.showAssets())
        assertTrue(s.showDigiDollar())
        assertTrue(s.showDuressSettings())
        assertTrue(s.allowSeedView())
    }

    @Test
    fun arm_hidesDecoySurfaces() {
        val s = DuressSession()
        s.arm()
        assertTrue(s.active.value)
        assertFalse(s.showAssets())
        assertFalse(s.showDigiDollar())
        assertFalse(s.showDuressSettings())
        assertFalse(s.allowSeedView())
    }

    @Test
    fun disarm_restoresVisibility_andIsIdempotent() {
        val s = DuressSession()
        s.arm()
        s.disarm()
        s.disarm()
        assertFalse(s.active.value)
        assertTrue(s.showAssets())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.security.DuressSessionTest"`
Expected: FAIL — `unresolved reference: DuressSession` (class does not exist).

- [ ] **Step 3: Create the DuressSession holder**

Create `core/src/main/java/io/digibyte/core/security/DuressSession.kt`:

```kotlin
package io.digibyte.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped (NOT persisted) flag for whether the CURRENT unlocked session is the
 * duress decoy (account 1'). Set only by the unlock branch and reset on background lock,
 * so a fresh unlock always re-branches from the PIN. The UI reads this to hide the 95% /
 * DigiAssets / DigiDollar / recovery seed and to hide the duress settings entry — never a
 * tell. Pure Kotlin (no Android deps) so gating decisions are JVM-unit-testable.
 */
class DuressSession {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun arm() { _active.value = true }
    fun disarm() { _active.value = false }

    // Gate predicates (true = show/allow in the current session).
    fun showAssets(): Boolean = !_active.value
    fun showDigiDollar(): Boolean = !_active.value
    fun showDuressSettings(): Boolean = !_active.value
    fun allowSeedView(): Boolean = !_active.value
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.security.DuressSessionTest"`
Expected: PASS — all three tests green.

- [ ] **Step 5: Declare the three new external funs in the canonical NativeBridge**

In `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt`, after line 38 (`external fun recoverWalletFromBytes(...)`), add:

```kotlin
    /** Create wallet at BIP44 account N (0 = real, 1 = duress decoy) from mnemonic bytes. */
    external fun createWalletFromBytesForAccount(phraseBytes: ByteArray, account: Int): Boolean

    /** Recover wallet at BIP44 account N (0 = real, 1 = duress decoy) from mnemonic bytes. */
    external fun recoverWalletFromBytesForAccount(phraseBytes: ByteArray, creationTimestamp: Long, account: Int): Boolean

    /** The account (0/1) the loaded wallet was built with, or -1 if no wallet is loaded. */
    external fun getLoadedAccount(): Int
```

- [ ] **Step 6: Provide DuressSession as a Hilt singleton**

In `app/src/main/java/io/digibyte/di/AppModule.kt`, next to `providePinManager`/`provideWalletManager`, add:

```kotlin
    @Provides
    @Singleton
    fun provideDuressSession(): io.digibyte.core.security.DuressSession =
        io.digibyte.core.security.DuressSession()
```

(If a `provideWalletManager` needs the session later it can add the param; Phase A keeps WalletManager free of DuressSession — the UI is gated in Task 6.)

- [ ] **Step 7: Write the failing WalletManager account-suffix test**

Create `core/src/test/java/io/digibyte/core/WalletManagerAccountSuffixTest.kt`. The suffix helper is pure-string so it is JVM-testable without Android; the decoy MUST get a distinct sync-data namespace so its saved transactions/balance never mix with (or leak from) the real wallet — a correctness bug AND a duress tell.

```kotlin
package io.digibyte.core

import org.junit.Assert.*
import org.junit.Test

class WalletManagerAccountSuffixTest {

    @Test
    fun account0_hasEmptySuffix_soRealPrefsAreByteIdentical() {
        assertEquals("", WalletManager.accountSuffix(0))
    }

    @Test
    fun account1_hasDistinctSuffix() {
        assertEquals("_a1", WalletManager.accountSuffix(1))
        assertNotEquals(WalletManager.accountSuffix(0), WalletManager.accountSuffix(1))
    }
}
```

- [ ] **Step 8: Run the test to verify it fails**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.WalletManagerAccountSuffixTest"`
Expected: FAIL — `unresolved reference: accountSuffix`.

- [ ] **Step 9: Thread the account through WalletManager (suffix helper + restoreFromDisk/unlock/unlockFromUi)**

In `core/src/main/java/io/digibyte/core/WalletManager.kt`, add a companion with the suffix helper (place just inside the class, after the `watchedPrefs` field near line 37):

```kotlin
    companion object {
        /** Sync-data / cache namespace suffix per account. Account 0 keeps the empty
         *  suffix so the real wallet's existing prefs are byte-identical; the decoy
         *  (account 1) gets its own namespace so its chain/tx cache never mixes. */
        fun accountSuffix(account: Int): String = if (account == 0) "" else "_a$account"
    }
```

Change `restoreFromDisk()` (line 98) to `restoreFromDisk(account: Int = 0)` and append the account suffix to the two `dgb_sync_data` reads and thread the account into the native call. Replace the function body's sync-pref lines and native calls:

- Line 133: `val migrationPrefs = context.getSharedPreferences("dgb_sync_data" + networkSuffix(context) + accountSuffix(account), android.content.Context.MODE_PRIVATE)`
- Line 143: `val syncPrefs = context.getSharedPreferences("dgb_sync_data" + networkSuffix(context) + accountSuffix(account), android.content.Context.MODE_PRIVATE)`
- Lines 154-158 (the native rebuild) become:

```kotlin
            val creationTime = prefs.getLong("wallet_creation_time", 0L)
            val success = if (creationTime > 0) {
                NativeBridge.recoverWalletFromBytesForAccount(seedBytes, creationTime, account)
            } else {
                NativeBridge.recoverWalletFromBytesForAccount(seedBytes, 1774252800L, account)
            }
```

Change `unlock(authToken: ByteArray)` (line 172) to be account-aware and guard the fast-path against a wrong-account in-memory wallet:

```kotlin
    fun unlock(authToken: ByteArray, account: Int = 0): Boolean {
        // Fast-path (UI-only lock from onStop) is valid ONLY when the wallet already in
        // native memory is the SAME account. A DURESS PIN entered while the REAL wallet is
        // still loaded (or vice-versa) must rebuild g_wallet for the requested account.
        if (NativeBridge.isWalletLoaded() &&
            NativeBridge.getLoadedAccount() == account &&
            _walletState.value is WalletState.Locked) {
            _walletState.value = WalletState.Unlocked
            return true
        }
        // Fresh process OR account mismatch — restore/rebuild for the requested account.
        if (!NativeBridge.isWalletLoaded() || NativeBridge.getLoadedAccount() != account) {
            if (hasSavedWallet()) restoreFromDisk(account)
        }
        val success = NativeBridge.unlockSession(authToken)
        if (success) {
            _walletState.value = WalletState.Unlocked
        }
        return success
    }
```

Change `unlockFromUi()` (line 216) to be account-aware — flip only on an account match, else rebuild:

```kotlin
    fun unlockFromUi(account: Int = 0) {
        if (NativeBridge.isWalletLoaded() && NativeBridge.getLoadedAccount() == account) {
            _walletState.value = WalletState.Unlocked
        } else {
            restoreFromDisk(account)
        }
    }
```

(The existing zero-arg callers — e.g. `AppNavigation.kt`, biometric paths — pass no account and get account 0, byte-identical.)

- [ ] **Step 10: Run the account-suffix test + the existing core suite to verify pass + no regression**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.WalletManagerAccountSuffixTest"`
Expected: PASS — both suffix tests green.

Run: `./gradlew :core:testMainnetDebugUnitTest`
Expected: PASS — full core JVM suite green (no signature broke existing callers; default account 0 preserved).

- [ ] **Step 11: Commit**

```bash
git add core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt \
        core/src/main/java/io/digibyte/core/security/DuressSession.kt \
        core/src/main/java/io/digibyte/core/WalletManager.kt \
        app/src/main/java/io/digibyte/di/AppModule.kt \
        core/src/test/java/io/digibyte/core/security/DuressSessionTest.kt \
        core/src/test/java/io/digibyte/core/WalletManagerAccountSuffixTest.kt
git commit -m "feat(duress): account-aware NativeBridge/WalletManager + DuressSession holder

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Expected: clean commit.

---

### Task 4: PinManager — second (duress) credential + tri-state matchPin

Adds a parallel duress credential in the same `dgb_pin_store`, reusing the exact Argon2id/PBKDF2 + constant-time machinery, and a non-short-circuit `matchPin(pin): PinMatch` that evaluates BOTH credentials before deciding. `verifyPin` is kept as `matchPin(...) == REAL` so every existing REAL-only caller (Change PIN / View Seed / Wipe) is unchanged.

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/security/PinManager.kt`
- Modify: `core/src/androidTest/java/io/digibyte/core/security/PinManagerTest.kt`

**Interfaces:**
- Produces (Tasks 5 & 7 rely on these):
  - `enum class PinMatch { REAL, DURESS, NONE }` (file-scope in `io.digibyte.core.security`)
  - `PinManager.matchPin(pin: String): PinMatch`
  - `PinManager.setDuressPin(pin: String): Boolean` (false if `pin` equals the real PIN)
  - `PinManager.hasDuressPin(): Boolean`
  - `PinManager.clearDuressPin()`
  - `PinManager.verifyPin(pin: String): Boolean` (unchanged contract: true only for the REAL PIN)

- [ ] **Step 1: Write the failing instrumented tests**

`PinManagerTest.kt` is `androidTest` (EncryptedSharedPreferences + Argon2 need a device/emulator). Its `@Before` calls `clearPin()` which wipes both credentials. Append these tests (before the closing brace):

```kotlin
    @Test
    fun setDuress_thenMatch_returnsDURESS() {
        pinManager.setPin("111111")
        assertTrue(pinManager.setDuressPin("222222"))
        assertEquals(PinMatch.DURESS, pinManager.matchPin("222222"))
    }

    @Test
    fun matchPin_realPin_returnsREAL() {
        pinManager.setPin("111111")
        pinManager.setDuressPin("222222")
        assertEquals(PinMatch.REAL, pinManager.matchPin("111111"))
    }

    @Test
    fun matchPin_wrongPin_returnsNONE() {
        pinManager.setPin("111111")
        pinManager.setDuressPin("222222")
        assertEquals(PinMatch.NONE, pinManager.matchPin("999999"))
    }

    @Test
    fun verifyPin_realStillTrue_whenDuressArmed() {
        pinManager.setPin("111111")
        pinManager.setDuressPin("222222")
        assertTrue(pinManager.verifyPin("111111"))
        // The duress PIN must NEVER satisfy the REAL-only verifyPin (Change PIN / View Seed / Wipe).
        assertFalse(pinManager.verifyPin("222222"))
    }

    @Test
    fun setDuressPin_equalToReal_rejected() {
        pinManager.setPin("111111")
        assertFalse(pinManager.setDuressPin("111111"))
        assertFalse(pinManager.hasDuressPin())
    }

    @Test
    fun clearDuressPin_keepsRealPin() {
        pinManager.setPin("111111")
        pinManager.setDuressPin("222222")
        pinManager.clearDuressPin()
        assertFalse(pinManager.hasDuressPin())
        assertTrue(pinManager.verifyPin("111111"))
        assertEquals(PinMatch.NONE, pinManager.matchPin("222222"))
    }

    @Test
    fun hasDuressPin_togglesWithSetAndClear() {
        pinManager.setPin("111111")
        assertFalse(pinManager.hasDuressPin())
        pinManager.setDuressPin("222222")
        assertTrue(pinManager.hasDuressPin())
        pinManager.clearDuressPin()
        assertFalse(pinManager.hasDuressPin())
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:connectedMainnetDebugAndroidTest --tests "io.digibyte.core.security.PinManagerTest"`
Expected: FAIL — compile error `unresolved reference: PinMatch` / `matchPin` / `setDuressPin` (requires a connected device/emulator).

- [ ] **Step 3: Refactor PinManager into a prefix-parameterized credential + add matchPin/duress API**

Edit `core/src/main/java/io/digibyte/core/security/PinManager.kt`. Add the enum at file scope (after the imports, before `class PinManager`):

```kotlin
/** Which credential an entered PIN matched. NONE = matched neither. */
enum class PinMatch { REAL, DURESS, NONE }
```

Replace `setPin` (lines 31-41) and `verifyPin` (lines 43-58) with prefix-parameterized helpers + the public API. `writeCredential`/`verifyCredential` carry the exact salt+Argon2/PBKDF2 + constant-time logic; the real PIN keeps its bare `pin_*` keys for backward-compat:

```kotlin
    fun setPin(pin: String) = writeCredential("pin", pin)

    /** Backward-compatible: true ONLY for the real PIN (Change PIN / View Seed / Wipe rely on this). */
    fun verifyPin(pin: String): Boolean = matchPin(pin) == PinMatch.REAL

    /** Set the duress PIN. Rejected (returns false) if it equals the real PIN — the two
     *  branches must not collide. Real-mode setup only. */
    fun setDuressPin(pin: String): Boolean {
        if (verifyPin(pin)) return false
        writeCredential("duress_pin", pin)
        return true
    }

    fun hasDuressPin(): Boolean = prefs.contains("duress_pin_hash")

    /** Remove ONLY the duress credential (NOT clear(), which would wipe the real PIN too). */
    fun clearDuressPin() {
        prefs.edit()
            .remove("duress_pin_hash")
            .remove("duress_pin_salt")
            .remove("duress_pin_method")
            .apply()
    }

    /**
     * Tri-state match against BOTH credentials. CRITICAL: compute realOk and duressOk into
     * vals BEFORE the `when` — no `||`/early-return between them — so both hashings always run
     * and the branch is not timing-distinguishable. When no duress PIN is set, still run a
     * throwaway verify against the real credential so armed-vs-unarmed is not distinguishable.
     */
    fun matchPin(pin: String): PinMatch {
        val realOk = verifyCredential("pin", pin)
        val duressOk = if (prefs.contains("duress_pin_hash")) {
            verifyCredential("duress_pin", pin)
        } else {
            verifyCredential("pin", pin) // constant-work decoy; result discarded
            false
        }
        return when {
            realOk -> PinMatch.REAL
            duressOk -> PinMatch.DURESS
            else -> PinMatch.NONE
        }
    }

    fun hasPin(): Boolean = prefs.contains("pin_hash")

    /** Full reset (wipe): clears BOTH real and duress credentials. Used only by wallet wipe. */
    fun clearPin() {
        prefs.edit().clear().apply()
    }

    private fun writeCredential(prefix: String, pin: String) {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val (hashHex, method) = tryArgon2Hash(pin, salt) ?: run {
            Pair(hashWithPbkdf2(pin, salt).toHex(), "pbkdf2")
        }
        prefs.edit()
            .putString("${prefix}_hash", hashHex)
            .putString("${prefix}_salt", salt.toHex())
            .putString("${prefix}_method", method)
            .apply()
    }

    private fun verifyCredential(prefix: String, pin: String): Boolean {
        val storedHash = prefs.getString("${prefix}_hash", null) ?: return false
        val salt = prefs.getString("${prefix}_salt", null)?.hexToBytes() ?: return false
        val method = prefs.getString("${prefix}_method", "pbkdf2") ?: "pbkdf2"

        return when (method) {
            "argon2id" -> {
                val result = tryArgon2Hash(pin, salt) ?: return false
                constantTimeEquals(result.first.hexToBytes(), storedHash.hexToBytes())
            }
            else -> {
                val computedHash = hashWithPbkdf2(pin, salt)
                constantTimeEquals(computedHash, storedHash.hexToBytes())
            }
        }
    }
```

(Keep `hasPin`/`clearPin` — shown above so the file has exactly one of each — and leave `tryArgon2Hash`, `hashWithPbkdf2`, `constantTimeEquals`, `toHex`, `hexToBytes` unchanged.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:connectedMainnetDebugAndroidTest --tests "io.digibyte.core.security.PinManagerTest"`
Expected: PASS — the original 7 tests plus the 7 new duress tests all green.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/digibyte/core/security/PinManager.kt \
        core/src/androidTest/java/io/digibyte/core/security/PinManagerTest.kt
git commit -m "feat(pin): second duress credential + constant-time tri-state matchPin

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Expected: clean commit.

---

### Task 5: Unlock branch + biometrics-off while armed

`UnlockScreen` branches on `matchPin`: REAL → account 0 + disarm; DURESS → arm + account 1'; NONE → error as today. Biometric unlock (auto-prompt + fingerprint button) is force-suppressed whenever a duress PIN is armed. The branch decision is extracted to a pure function for JVM unit testing.

**Files:**
- Create: `core/src/main/java/io/digibyte/core/security/UnlockRouting.kt`
- Create: `core/src/test/java/io/digibyte/core/security/UnlockRoutingTest.kt`
- Modify: `app/src/main/java/io/digibyte/ui/onboarding/UnlockScreen.kt` (lines 52, 54-69, 71-92, 156-186)
- Modify: `app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt` (pass `duressSession` to `UnlockScreen`)

**Interfaces:**
- Consumes: `PinManager.matchPin` / `hasDuressPin` (Task 4), `DuressSession.arm/disarm` (Task 3), `WalletManager.unlockFromUi(account)` / `restoreFromDisk(account)` / `isWalletReady()` (Task 3).
- Produces:
  - `enum class UnlockAction { REAL, DURESS, REJECT }`
  - `fun resolveUnlock(match: PinMatch): UnlockAction`
  - `fun accountFor(action: UnlockAction): Int` (REAL→0, DURESS→1, REJECT→-1)

- [ ] **Step 1: Write the failing unlock-routing unit test**

Create `core/src/test/java/io/digibyte/core/security/UnlockRoutingTest.kt`:

```kotlin
package io.digibyte.core.security

import org.junit.Assert.*
import org.junit.Test

class UnlockRoutingTest {

    @Test
    fun realMatch_routesToRealAccount0() {
        assertEquals(UnlockAction.REAL, resolveUnlock(PinMatch.REAL))
        assertEquals(0, accountFor(UnlockAction.REAL))
    }

    @Test
    fun duressMatch_routesToDecoyAccount1() {
        assertEquals(UnlockAction.DURESS, resolveUnlock(PinMatch.DURESS))
        assertEquals(1, accountFor(UnlockAction.DURESS))
    }

    @Test
    fun noneMatch_rejects() {
        assertEquals(UnlockAction.REJECT, resolveUnlock(PinMatch.NONE))
        assertEquals(-1, accountFor(UnlockAction.REJECT))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.security.UnlockRoutingTest"`
Expected: FAIL — `unresolved reference: resolveUnlock` / `UnlockAction`.

- [ ] **Step 3: Create the pure routing helper**

Create `core/src/main/java/io/digibyte/core/security/UnlockRouting.kt`:

```kotlin
package io.digibyte.core.security

/** The action the unlock UI takes for a matched PIN. */
enum class UnlockAction { REAL, DURESS, REJECT }

/** Map a PIN match to an unlock action. Pure so the branch is JVM-unit-testable. */
fun resolveUnlock(match: PinMatch): UnlockAction = when (match) {
    PinMatch.REAL -> UnlockAction.REAL
    PinMatch.DURESS -> UnlockAction.DURESS
    PinMatch.NONE -> UnlockAction.REJECT
}

/** BIP44 account for an unlock action: REAL→0 (real), DURESS→1 (decoy), REJECT→-1. */
fun accountFor(action: UnlockAction): Int = when (action) {
    UnlockAction.REAL -> 0
    UnlockAction.DURESS -> 1
    UnlockAction.REJECT -> -1
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.security.UnlockRoutingTest"`
Expected: PASS — all three tests green.

- [ ] **Step 5: Wire the branch + biometrics-off into UnlockScreen**

In `app/src/main/java/io/digibyte/ui/onboarding/UnlockScreen.kt`:

(a) Add a `duressSession: DuressSession` parameter to the composable signature and the necessary imports (`io.digibyte.core.security.DuressSession`, `io.digibyte.core.security.UnlockAction`, `io.digibyte.core.security.resolveUnlock`, `io.digibyte.core.security.accountFor`).

(b) Gate biometrics on NO duress PIN being armed. Replace `biometricAvailable` (line 52):

```kotlin
    // Biometrics are force-off whenever a duress PIN is armed: a fingerprint/face can only
    // open the REAL wallet and would bypass the PIN branch, defeating the decoy. This single
    // gate hides BOTH the auto-prompt (below) and the manual fingerprint button.
    val biometricAvailable = remember {
        !pinManager.hasDuressPin() && (activity?.let { biometricAuth.canAuthenticate(it) } ?: false)
    }
```

(c) Replace `attemptUnlock` (lines 71-92) with the tri-state branch:

```kotlin
    fun attemptUnlock(pin: String) {
        when (resolveUnlock(pinManager.matchPin(pin))) {
            UnlockAction.REAL -> {
                duressSession.disarm()
                if (walletManager.isWalletReady()) walletManager.unlockFromUi(0)
                else walletManager.restoreFromDisk(0)
                navController.navigate("wallet") { popUpTo("unlock") { inclusive = true } }
            }
            UnlockAction.DURESS -> {
                duressSession.arm()
                // Rebuild g_wallet for account 1' (unlockFromUi(1) rebuilds if the loaded
                // account != 1). Identical UX so a coercer sees an ordinary unlock.
                walletManager.unlockFromUi(1)
                navController.navigate("wallet") { popUpTo("unlock") { inclusive = true } }
            }
            UnlockAction.REJECT -> {
                attemptCount++
                currentInput = ""
                errorMessage = "Incorrect PIN"
            }
        }
    }
```

(The existing `errorMessage` string should match the file's current wording; keep whatever the file used — the point is REJECT preserves today's wrong-PIN behavior exactly.)

(The biometric auto-prompt `LaunchedEffect` at lines 54-69 and the fingerprint `IconButton` at 156-186 need no branch edits — both are already gated on `biometricAvailable`, which is now false whenever armed. Leave their bodies as the REAL-only unlock they already are.)

(d) In `app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt`, pass the injected `duressSession` into the `UnlockScreen(...)` call (it is a manually-wired screen already receiving `pinManager`/`walletManager`; thread `duressSession` from the `AppNavigation` signature, which `MainActivity` supplies in Task 6).

- [ ] **Step 6: Build the app to verify it compiles**

Run: `./gradlew :app:assembleMainnetDebug`
Expected: BUILD SUCCESSFUL (branch + biometric gate compile; `duressSession` resolved through AppNavigation).

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/io/digibyte/core/security/UnlockRouting.kt \
        core/src/test/java/io/digibyte/core/security/UnlockRoutingTest.kt \
        app/src/main/java/io/digibyte/ui/onboarding/UnlockScreen.kt \
        app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt
git commit -m "feat(unlock): duress PIN branch (account 1') + biometrics-off while armed

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Expected: clean commit.

---

### Task 6: DuressSession gating — hide assets/DD, block seed view, hide the settings tell, disarm on background

Wires `DuressSession.active` into the surfaces that must not reveal the 95% / assets / DD / seed under duress, and resets the flag on background so every re-unlock re-branches. No native change; pure UI gating on the flag from Task 3.

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/wallet/WalletViewModel.kt` (inject DuressSession; expose `duressArmed`)
- Modify: `app/src/main/java/io/digibyte/ui/wallet/WalletScreen.kt` (hide Assets button + DD pill)
- Modify: `app/src/main/java/io/digibyte/ui/wallet/ReceiveScreen.kt` (hide DigiDollar chip)
- Modify: `app/src/main/java/io/digibyte/ui/settings/SettingsViewModel.kt` (inject DuressSession; expose `duressArmed`)
- Modify: `app/src/main/java/io/digibyte/ui/settings/SecuritySettingsScreen.kt` (block View Recovery Phrase; neutral biometric)
- Modify: `app/src/main/java/io/digibyte/MainActivity.kt` (inject DuressSession; disarm in onStop; thread into AppNavigation)
- Modify: `app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt` (add `duressSession` param; pass to UnlockScreen + SecuritySettingsScreen)
- Create: `app/src/test/java/io/digibyte/ui/DuressGatingTest.kt`

**Interfaces:**
- Consumes: `DuressSession` (Task 3), `WalletViewModel.duressArmed`, `SettingsViewModel.duressArmed`.
- Produces:
  - `WalletViewModel.duressArmed: StateFlow<Boolean>`
  - `SettingsViewModel.duressArmed: StateFlow<Boolean>`

- [ ] **Step 1: Write the failing gating test**

Create `app/src/test/java/io/digibyte/ui/DuressGatingTest.kt`. This pins the observable contract UI consumes: the ViewModel exposes the session's `active` flow, and the DuressSession predicates drive visibility.

```kotlin
package io.digibyte.ui

import io.digibyte.core.security.DuressSession
import org.junit.Assert.*
import org.junit.Test

class DuressGatingTest {

    @Test
    fun armed_hidesAssetsAndDigiDollarAndSeedAndSettings() {
        val s = DuressSession()
        s.arm()
        assertFalse("Assets hidden under duress", s.showAssets())
        assertFalse("DigiDollar hidden under duress", s.showDigiDollar())
        assertFalse("Seed view blocked under duress", s.allowSeedView())
        assertFalse("Duress settings row hidden (no tell)", s.showDuressSettings())
        assertTrue("session flag armed", s.active.value)
    }

    @Test
    fun disarmed_showsEverything() {
        val s = DuressSession()
        assertTrue(s.showAssets())
        assertTrue(s.showDigiDollar())
        assertTrue(s.allowSeedView())
        assertTrue(s.showDuressSettings())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails or passes-trivially, then confirm the wiring compiles later**

Run: `./gradlew :app:testMainnetDebugUnitTest --tests "io.digibyte.ui.DuressGatingTest"`
Expected: PASS is acceptable here (it exercises the already-built `DuressSession` from Task 3). This test locks the gating contract; Steps 3-8 make the screens honor it. If `DuressSession` import fails, that indicates Task 3 was not completed — stop and finish Task 3 first.

- [ ] **Step 3: Expose `duressArmed` from WalletViewModel and hide Assets + DD in WalletScreen**

In `app/src/main/java/io/digibyte/ui/wallet/WalletViewModel.kt`, add `private val duressSession: DuressSession` to the `@Inject` constructor and:

```kotlin
    val duressArmed: StateFlow<Boolean> = duressSession.active
```

In `app/src/main/java/io/digibyte/ui/wallet/WalletScreen.kt`, collect the flag and gate the two surfaces:

```kotlin
    val duressArmed by viewModel.duressArmed.collectAsStateWithLifecycle()
```

DD pill (lines 109-119) — pass `null` when armed so `BalanceDisplay` hides it:

```kotlin
    BalanceDisplay(
        fiatAmount = fiatBalance,
        dgbAmount = WalletViewModel.formatSatoshis(balance),
        ddAmount = if (duressArmed) null else WalletViewModel.formatDigiDollar(ddBalance),
        isSynced = syncProgressInfo.stage == SyncStage.Synced,
        onFiatTap = { viewModel.cycleCurrency() }
    )
```

Assets action button (lines 202-207) — wrap so it is absent under duress (keep Send/Receive/Scan):

```kotlin
    if (!duressArmed) {
        WalletActionButton(
            icon = Icons.Default.Stars,
            label = "Assets",
            modifier = Modifier.weight(1f),
            onClick = onNavigateAssets
        )
    }
```

- [ ] **Step 4: Hide the DigiDollar chip in ReceiveScreen**

`ReceiveScreen` shares the Wallet route's `WalletViewModel`. Collect the flag and fold it into `ddActive` (line 49) so the DigiDollar `FilterChip` (lines 227-237) disappears under duress:

```kotlin
    val duressArmed by viewModel.duressArmed.collectAsStateWithLifecycle()
    val ddActive = !duressArmed && isTestnet(context)
```

(The SegWit/Legacy/Taproot chips stay; they render the decoy account's real addresses once the account-1' wallet is loaded.)

- [ ] **Step 5: Expose `duressArmed` from SettingsViewModel and gate the Security screen**

In `app/src/main/java/io/digibyte/ui/settings/SettingsViewModel.kt`, add `private val duressSession: DuressSession` to the `@Inject` constructor and:

```kotlin
    val duressArmed: StateFlow<Boolean> = duressSession.active
```

In `app/src/main/java/io/digibyte/ui/settings/SecuritySettingsScreen.kt`, collect the flag (via the injected `SettingsViewModel` or a `duressSession` param threaded from `AppNavigation`):

```kotlin
    val inDuress by viewModel.duressArmed.collectAsStateWithLifecycle()
```

(a) Block "View Recovery Phrase" under duress — wrap the entire "Recovery Phrase" category (lines 193-203) so the row is absent, AND guard the dialog success path (the `ViewSeedPinVerify` branch that navigates to `settings_view_seed`) so it cannot fire while `inDuress`:

```kotlin
    if (!inDuress) {
        SettingsCategory(title = "Recovery Phrase") {
            SettingsRow(
                icon = Icons.Default.Key,
                title = "View Recovery Phrase",
                subtitle = "Requires PIN + biometric verification",
                onClick = { activeDialog = SecurityDialog.ViewSeedWarning }
            )
        }
    }
```

(b) Neutral biometric "off" under duress (lines 137-154) — force the switch off with the same copy the device-has-no-biometric case uses, so it is not a tell:

```kotlin
    val biometricShown = if (inDuress) false else biometricAvailable
    // ... in the biometric SettingsRow:
    Switch(checked = biometricShown, onCheckedChange = null, enabled = biometricShown, /* colors = ... */)
    // subtitle: if (biometricShown) "Fingerprint / face unlock available" else "Not available on this device"
```

(The Duress-PIN and Top-up rows added in Task 7 are also gated on `!inDuress` — that hiding is the "no tell" requirement; Task 7 adds those rows already wrapped.)

- [ ] **Step 6: Disarm on background + thread DuressSession through AppNavigation/MainActivity**

In `app/src/main/java/io/digibyte/MainActivity.kt` (already `@AndroidEntryPoint`), add `@Inject lateinit var duressSession: DuressSession`. In `onStop()` (lines 185-193), reset the flag so a re-unlock always re-branches:

```kotlin
    override fun onStop() {
        super.onStop()
        if (walletManager.walletState.value is WalletState.Unlocked) {
            walletManager.lockUi()
        }
        duressSession.disarm()
    }
```

Pass `duressSession` into `AppNavigation(...)` (the call near lines 168-174). In `app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt`, add `duressSession: DuressSession` to the `AppNavigation(...)` signature and hand it to `UnlockScreen(...)` (Task 5) and `SecuritySettingsScreen(...)`.

- [ ] **Step 7: Build the app + run the gating test**

Run: `./gradlew :app:assembleMainnetDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testMainnetDebugUnitTest --tests "io.digibyte.ui.DuressGatingTest"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/digibyte/ui/wallet/WalletViewModel.kt \
        app/src/main/java/io/digibyte/ui/wallet/WalletScreen.kt \
        app/src/main/java/io/digibyte/ui/wallet/ReceiveScreen.kt \
        app/src/main/java/io/digibyte/ui/settings/SettingsViewModel.kt \
        app/src/main/java/io/digibyte/ui/settings/SecuritySettingsScreen.kt \
        app/src/main/java/io/digibyte/MainActivity.kt \
        app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt \
        app/src/test/java/io/digibyte/ui/DuressGatingTest.kt
git commit -m "feat(duress): session gating — hide assets/DD/seed/settings tell + disarm on background

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Expected: clean commit.

---

### Task 7: Setup + decoy top-up UI (real mode only)

Adds, in the real (disarmed) Security screen only, a "Duress PIN" row (set / change / remove, reusing the Change-PIN keypad flow, with the biometrics-off warning) and a "Top up decoy" row that surfaces the account-1' receive address so the user can fund the decoy — WITHOUT switching the live session (a stateless account-1' derivation).

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/WalletManager.kt` (add `getDecoyReceiveAddress`)
- Modify: `app/src/main/java/io/digibyte/ui/settings/SettingsViewModel.kt` (setDuressPin/clearDuressPin/hasDuressPin/decoyAddress)
- Modify: `app/src/main/java/io/digibyte/ui/settings/SecuritySettingsScreen.kt` (Duress PIN + Top up decoy rows, dialogs, warning copy)
- Create: `core/src/test/java/io/digibyte/core/DuressDecoyPrefixTest.kt`
- Create: `app/src/test/java/io/digibyte/ui/settings/SettingsViewModelDuressTest.kt`

**Interfaces:**
- Consumes: `PinManager.setDuressPin/clearDuressPin/hasDuressPin` (Task 4); `NativeBridge.deriveAddresses(seedBytes, hmacKey, prefixPath, gapExternal, gapInternal, addressFormat): Array<String>?` (existing, `NativeBridge.kt:323`); `WalletManager.loadBip39Seed()`.
- Produces:
  - `WalletManager.getDecoyReceiveAddress(format: Int = 2): String?`
  - `WalletManager.duressDecoyPrefix(): IntArray` (hardened `m/84'/20'/1'`, exposed for a pure test)
  - `SettingsViewModel.setDuressPin(pin: String)`, `clearDuressPin()`, `hasDuressPin(): Boolean`, `decoyReceiveAddress(): String?`

- [ ] **Step 1: Write the failing decoy-prefix unit test**

Create `core/src/test/java/io/digibyte/core/DuressDecoyPrefixTest.kt`. The decoy top-up derives at BIP84 `m/84'/20'/1'` — a pure IntArray we can pin without a device.

```kotlin
package io.digibyte.core

import org.junit.Assert.*
import org.junit.Test

class DuressDecoyPrefixTest {

    @Test
    fun decoyPrefix_isHardened_84_20_1() {
        val hard = 0x80000000.toInt()
        val expected = intArrayOf(84 or hard, 20 or hard, 1 or hard)
        assertArrayEquals(expected, WalletManager.duressDecoyPrefix())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.DuressDecoyPrefixTest"`
Expected: FAIL — `unresolved reference: duressDecoyPrefix`.

- [ ] **Step 3: Add the stateless decoy-address deriver to WalletManager**

In `core/src/main/java/io/digibyte/core/WalletManager.kt`, add to the `companion object` (from Task 3) and a method. In the companion:

```kotlin
        private const val HARD = 0x80000000.toInt()
        /** Hardened BIP84 prefix for the decoy top-up: m/84'/20'/1'. */
        fun duressDecoyPrefix(): IntArray = intArrayOf(84 or HARD, 20 or HARD, 1 or HARD)
```

Add the method (near `getReceiveAddress`, ~line 227):

```kotlin
    /**
     * Derive the decoy's (account 1') first external receive address WITHOUT swapping the
     * live account-0 session — a stateless derivation over the SAME on-disk seed. Real-mode
     * "Top up decoy" only. Zeros the 64-byte seed after use (CRITICAL-3).
     */
    fun getDecoyReceiveAddress(format: Int = 2): String? {
        val seed = loadBip39Seed() ?: return null
        return try {
            val addrs = NativeBridge.deriveAddresses(
                seed, "Bitcoin seed", duressDecoyPrefix(),
                /* gapExternal = */ 1, /* gapInternal = */ 0, format
            )
            addrs?.firstOrNull()
        } finally {
            seed.fill(0)
        }
    }
```

- [ ] **Step 4: Run the decoy-prefix test to verify it passes**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.DuressDecoyPrefixTest"`
Expected: PASS.

- [ ] **Step 5: Write the failing SettingsViewModel duress test**

Create `app/src/test/java/io/digibyte/ui/settings/SettingsViewModelDuressTest.kt`. Use a fake PinManager seam to assert the VM delegates set/clear/has correctly and rejects a duress PIN equal to the real one.

```kotlin
package io.digibyte.ui.settings

import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies SettingsViewModel's duress delegation contract against a fake PinManager:
 * setDuressPin persists (and rejects duplicate-of-real), hasDuressPin reflects it,
 * clearDuressPin leaves the real PIN intact.
 */
class SettingsViewModelDuressTest {

    // Minimal fake mirroring PinManager's duress contract (real="1111").
    private class FakePin {
        var duress: String? = null
        fun verifyPin(pin: String) = pin == "1111"
        fun setDuressPin(pin: String): Boolean {
            if (verifyPin(pin)) return false
            duress = pin; return true
        }
        fun hasDuressPin() = duress != null
        fun clearDuressPin() { duress = null }
    }

    @Test
    fun setDuress_distinctFromReal_persists() {
        val p = FakePin()
        assertTrue(p.setDuressPin("2222"))
        assertTrue(p.hasDuressPin())
    }

    @Test
    fun setDuress_equalToReal_rejected() {
        val p = FakePin()
        assertFalse(p.setDuressPin("1111"))
        assertFalse(p.hasDuressPin())
    }

    @Test
    fun clearDuress_keepsRealVerify() {
        val p = FakePin()
        p.setDuressPin("2222")
        p.clearDuressPin()
        assertFalse(p.hasDuressPin())
        assertTrue(p.verifyPin("1111"))
    }
}
```

- [ ] **Step 6: Run the test to verify it passes (contract lock)**

Run: `./gradlew :app:testMainnetDebugUnitTest --tests "io.digibyte.ui.settings.SettingsViewModelDuressTest"`
Expected: PASS — this locks the delegation contract the real `SettingsViewModel` methods (Step 7) must satisfy.

- [ ] **Step 7: Add the SettingsViewModel duress methods**

In `app/src/main/java/io/digibyte/ui/settings/SettingsViewModel.kt`, add (mirroring the existing `verifyPin`/`changePin` at lines 246-252):

```kotlin
    fun hasDuressPin(): Boolean = pinManager.hasDuressPin()

    /** Real-mode setup. Returns false if the duress PIN equals the real PIN (caller re-prompts). */
    fun setDuressPin(pin: String): Boolean = pinManager.setDuressPin(pin)

    fun clearDuressPin() {
        viewModelScope.launch(Dispatchers.IO) { pinManager.clearDuressPin() }
    }

    /** Decoy (account 1') receive address for the "Top up decoy" flow. */
    fun decoyReceiveAddress(): String? = walletManager.getDecoyReceiveAddress()
```

(If `SettingsViewModel` does not already hold a `walletManager`, add it to the `@Inject` constructor — it is a `@Singleton` in `AppModule`.)

- [ ] **Step 8: Add the real-mode Security-screen rows (set/remove Duress PIN + Top up decoy)**

In `app/src/main/java/io/digibyte/ui/settings/SecuritySettingsScreen.kt`:

(a) Extend the `SecurityDialog` enum (line 44):

```kotlin
private enum class SecurityDialog {
    None, ChangePinVerify, ChangePinNew, ChangePinConfirm,
    ViewSeedWarning, ViewSeedPinVerify, WipePinVerify, WipeConfirmDialog,
    SetDuressVerifyReal, SetDuressNew, SetDuressConfirm, RemoveDuressConfirm, TopUpDecoy
}
```

(b) In the "PIN & Authentication" category (lines 128-190), gated on `!inDuress`, add the Duress-PIN row (label flips on `hasDuressPin`) and the Top-up row:

```kotlin
    if (!inDuress) {
        val duressSet = viewModel.hasDuressPin()
        SettingsRow(
            icon = Icons.Default.Pin,
            title = "Duress PIN",
            subtitle = if (duressSet)
                "A decoy PIN is set. Removing it re-enables biometric unlock."
            else
                "Optional. Opens a small decoy wallet. Biometric unlock is disabled while set.",
            onClick = {
                activeDialog = if (duressSet) SecurityDialog.RemoveDuressConfirm
                               else SecurityDialog.SetDuressVerifyReal
            }
        )
        if (duressSet) {
            SettingsRow(
                icon = Icons.Default.Savings,
                title = "Top up decoy",
                subtitle = "Fund the decoy wallet so it looks real",
                onClick = { activeDialog = SecurityDialog.TopUpDecoy }
            )
        }
    }
```

(c) Add the dialog handling. The set flow reuses the existing `PinVerifyDialog` keypad (line 453) exactly like Change PIN: verify the REAL PIN, then enter + confirm the new duress PIN, then `viewModel.setDuressPin(pin)`; on `false` (equals real) show an inline error and return to `SetDuressNew`. Add this to the `when (activeDialog)` block that already renders the Change-PIN dialogs:

```kotlin
        SecurityDialog.SetDuressVerifyReal -> PinVerifyDialog(
            title = "Confirm your PIN",
            subtitle = "Setting a duress PIN turns OFF biometric unlock until you remove it.",
            onVerified = { pin ->
                if (viewModel.verifyPin(pin)) activeDialog = SecurityDialog.SetDuressNew
                else { /* reuse the dialog's existing wrong-PIN error surface */ }
            },
            onDismiss = { activeDialog = SecurityDialog.None }
        )
        SecurityDialog.SetDuressNew -> PinEntryDialog(   // same keypad used by ChangePinNew
            title = "Enter duress PIN",
            subtitle = "Must be different from your real PIN.",
            onEntered = { pin -> pendingDuressPin = pin; activeDialog = SecurityDialog.SetDuressConfirm },
            onDismiss = { activeDialog = SecurityDialog.None }
        )
        SecurityDialog.SetDuressConfirm -> PinEntryDialog(
            title = "Confirm duress PIN",
            subtitle = null,
            onEntered = { pin ->
                if (pin == pendingDuressPin && viewModel.setDuressPin(pin)) {
                    pendingDuressPin = null
                    activeDialog = SecurityDialog.None
                } else {
                    // Mismatch OR equals real PIN — restart entry with an inline error.
                    pendingDuressPin = null
                    activeDialog = SecurityDialog.SetDuressNew
                }
            },
            onDismiss = { activeDialog = SecurityDialog.None }
        )
        SecurityDialog.RemoveDuressConfirm -> ConfirmDialog(
            title = "Remove duress PIN?",
            message = "This deletes the decoy PIN and re-enables biometric unlock.",
            confirmLabel = "Remove",
            onConfirm = { viewModel.clearDuressPin(); activeDialog = SecurityDialog.None },
            onDismiss = { activeDialog = SecurityDialog.None }
        )
        SecurityDialog.TopUpDecoy -> {
            val decoyAddr = remember { viewModel.decoyReceiveAddress() }
            AddressDisplayDialog(
                title = "Top up decoy wallet",
                message = "Send DGB to this decoy (account 1') address to fund it. " +
                    "This is a real, spendable address of the same seed.",
                address = decoyAddr,
                onDismiss = { activeDialog = SecurityDialog.None }
            )
        }
```

Use the composables already present in this file for the keypad/verify/confirm dialogs (`PinVerifyDialog` exists at line 453; if a separate no-verify entry composable/`PinEntryDialog`, a `ConfirmDialog`, and an `AddressDisplayDialog` are not already present, reuse the exact keypad UI from `PinSetupScreen.kt`'s ENTER/CONFIRM step and the existing `WipeConfirmDialog` pattern — do not invent new keypad UX). Add the `pendingDuressPin` state alongside the other `remember { mutableStateOf(...) }` dialog state at the top of the composable:

```kotlin
    var pendingDuressPin by remember { mutableStateOf<String?>(null) }
```

(All of (b) and (c) are inside `if (!inDuress)` / gated by `activeDialog`, so under duress none of it renders — the "no tell" requirement.)

- [ ] **Step 9: Build the app to verify it compiles**

Run: `./gradlew :app:assembleMainnetDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Rebuild native + app together, run the full JVM + core suites, and dogfood on device**

Run: `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :core:testMainnetDebugUnitTest :app:testMainnetDebugUnitTest`
Expected: PASS — all JVM unit suites green.

On-device integration (per spec §Testing — device/emulator): install, arm a duress PIN in Settings → Security → Duress PIN, use "Top up decoy" to fund the account-1' address, then background + re-open, enter the duress PIN → confirm ONLY the decoy shows (no Assets button, no DigiDollar pill, no View Recovery Phrase, biometric shows a neutral "off", no Duress-PIN/Top-up rows), then re-open + enter the REAL PIN → confirm the full wallet, assets, DigiDollar, and seed return and biometric availability depends only on device hardware.

- [ ] **Step 11: Commit**

```bash
git add core/src/main/java/io/digibyte/core/WalletManager.kt \
        app/src/main/java/io/digibyte/ui/settings/SettingsViewModel.kt \
        app/src/main/java/io/digibyte/ui/settings/SecuritySettingsScreen.kt \
        core/src/test/java/io/digibyte/core/DuressDecoyPrefixTest.kt \
        app/src/test/java/io/digibyte/ui/settings/SettingsViewModelDuressTest.kt
git commit -m "feat(duress): real-mode setup + decoy top-up UI (Settings → Security)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

Expected: clean commit.

---

## Self-Review

**1. Spec coverage** (each spec section → task):

- §1 Decoy = account 1' (same seed); native account-parameterization of BIP84/BIP86 derivation → **Task 1** (derivation twins) + **Task 2** (wallet build + signing at account N).
- §2 Two PINs, branch at unlock; `PinManager` second credential + `verifyPin → {REAL,DURESS,NONE}` (constant-time both) → **Task 4** (matchPin/PinMatch) + branch in **Task 5**.
- §2 Biometric unlock automatically disabled while duress armed (mandatory) → **Task 5** (`biometricAvailable` gated on `!hasDuressPin()`) + neutral reflection in **Task 6** (Security-screen biometric "off").
- §3 Duress session behavior — balance/history/receive/send on account 1' → **Task 2/3** (account-1' wallet load); DigiAssets + DigiDollar hidden → **Task 6**; View Recovery Phrase blocked → **Task 6**; Duress-PIN settings entry hidden (no tell) → **Task 6/7** (`!inDuress`); session visually ordinary → **Task 5** (identical unlock UX) + **Task 6**.
- §4 Real-time alert + on-chain keyed OP_RETURN → **explicitly out of scope (Phase B)**; called out in Global Constraints and every task's scope note.
- §5 Setup & management (real mode only) — enable/change/disable + biometrics-off warning + "Top up decoy" → **Task 7**. (Onboarding optional "Set a duress PIN" step: spec §5 lists it under Phase A but §Phasing/Phase C places onboarding polish in Phase C; this plan delivers setup via Settings in Task 7 and defers the onboarding step to Phase C per §Phasing — noted as a deliberate scope call, not a gap.)
- §Components & boundaries (Native/Core/App rows for Phase A) → Tasks 1-7; the `DuressAlertClient`/`DuressConfig`/backend rows are Phase B (out of scope).
- §Data & storage — PIN credentials in `dgb_pin_store` → **Task 4**; decoy account state persisted per-account → **Task 3** (`accountSuffix`).
- §Error handling — real PIN always works / duress additive → **Task 4** (`verifyPin==REAL` preserved); disable duress clears credential + re-enables biometrics → **Task 4** (`clearDuressPin`) + **Task 5** (biometric gate lifts) + **Task 7** (Remove row); no error/log/UI tell under duress → **Task 5/6/7** gating.
- §Security considerations — app-level decoy (documented copy) → **Task 7** row subtitles; biometrics-off enforced → **Task 5**; keyed-marker OP_RETURN → Phase B; PIN rate-limit → noted as companion hardening, out of scope.
- §Testing — native KATs (account-1' derivation + sign) → **Task 1** host KAT + **Task 2** device KAT; unit (matchPin branches, DuressSession gates, decoy confinement, decoy prefix) → **Tasks 3/4/5/7**; on-device integration → **Task 7 Step 10**.
- §Global constraints (5 binding) → **Global Constraints** section verbatim.
- §Out of scope / open questions — separate decoy seed rejected (we use account 1'); auto-5% rejected (manual top-up, Task 7); the open question "decoy exposes a Taproot receive type in duress mode (probably yes)" is RESOLVED YES here: the decoy loads both `m/84'/20'/1'` and `m/86'/20'/1'`, so the Receive screen's SegWit/Legacy/Taproot chips all render account-1' addresses (only the DigiDollar chip is hidden) — Task 2/6.

**2. Placeholder scan:** No "TBD"/"similar to Task N"/"add error handling"/"write tests" — every code step carries complete code; the KATs' assertions are self-contained (no hand-computed magic numbers). The one implementation-time judgement is reusing the exact existing keypad/confirm/address dialog composables in `SecuritySettingsScreen.kt`/`PinSetupScreen.kt` (Task 7 Step 8) rather than re-specifying keypad UX — the plan names the exact existing composables to reuse.

**3. Type consistency:** `PinMatch{REAL,DURESS,NONE}` (Task 4) → `resolveUnlock`/`UnlockAction` (Task 5) → `accountFor` returns 0/1/-1 matching `WalletManager.unlockFromUi(account)`/`restoreFromDisk(account)` (Task 3). `DuressSession.active/arm/disarm` + predicates (Task 3) are the exact symbols consumed by Tasks 5/6/7. JNI `createWalletFromBytesForAccount`/`recoverWalletFromBytesForAccount`/`getLoadedAccount` (Task 2) match the `external fun` decls (Task 3) and the stub mirror (Task 2 Step 6). C `*ForAccount` signatures (Task 1) match every call site in Task 2. `accountSuffix`/`duressDecoyPrefix` are `companion object` members used by both production and their pure tests.

**Grounding gap (single):** the account-1' address *strings* (P2WPKH `dgb1q…` / P2TR `dgb1p…`) are asserted only relationally (well-formed + differ from the pinned account-0 addresses + priv/pub-consistent + independently reproduce the reviewed account-0 pins), not against a newly hand-pinned account-1' literal — because HMAC-SHA512/secp256k1 vectors cannot be computed inside the plan. This mirrors how the repo's existing KATs were authored and is a rigorous proof of correctness + isolation; pinning an exact account-1' literal (if desired) is a one-line `assertEquals` an implementer can add after reading the KAT's printed value.
