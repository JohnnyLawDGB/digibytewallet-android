# BIP84 Standard Derivation Path — Design Spec

## Goal

Make the wallet fully BIP84-compliant so addresses match Ian Coleman's BIP39 tool and any standard DigiByte wallet. Existing users' funds remain spendable via dual-scan recovery.

## Problem

Two incompatibilities prevent interoperability with standard wallets:

1. **HMAC key:** C core uses `"DigiByte seed"` for BIP32 root key derivation. The BIP32 standard (and Ian Coleman) uses `"Bitcoin seed"` for all coins.
2. **Derivation path:** C core uses `m/0H/chain/index` (breadwallet legacy). BIP84 for DigiByte is `m/84'/20'/0'/chain/index` (purpose=84, coin_type=20, account=0).

Same mnemonic → completely different root key → completely different addresses at every level. Users cannot recover funds using Ian Coleman or any BIP84-compliant wallet.

## Solution

- **New wallets:** BIP84 only — `"Bitcoin seed"` HMAC + `m/84'/20'/0'/chain/index`
- **Recovery:** Dual-scan — derive both BIP84 and legacy key trees from the same seed, scan both for UTXOs, merge into one wallet. New receive/change addresses use BIP84. Old UTXOs are spendable and naturally migrate as the user spends.

## Architecture

### C Core Changes (digibytewallet-core submodule)

#### BRBIP32Sequence.c — Parameterized HMAC Key + BIP84 Path

Current constant:
```c
#define BRBIP32_SEED_KEY "DigiByte seed"
```

Change to:
```c
#define BRBIP32_SEED_KEY        "Bitcoin seed"     // BIP84 standard
#define BRBIP32_LEGACY_SEED_KEY "DigiByte seed"    // breadwallet legacy
```

New functions (BIP84 path: `m/84'/20'/0'/chain/index`):

```c
// Derive master pub key at m/84'/20'/0' using "Bitcoin seed" HMAC
BRMasterPubKey BRBIP32MasterPubKeyBIP84(const void *seed, size_t seedLen);

// Derive child public key from BIP84 master: m/84'/20'/0'/chain/index
size_t BRBIP32PubKeyBIP84(void *pubKey, size_t pubKeyLen, BRMasterPubKey mpk,
                          uint32_t chain, uint32_t index);

// Derive child private key: full path m/84'/20'/0'/chain/index
void BRBIP32PrivKeyBIP84(BRKey *key, const void *seed, size_t seedLen,
                         uint32_t chain, uint32_t index);

// Batch private key derivation for transaction signing
void BRBIP32PrivKeyListBIP84(BRKey keys[], size_t keysCount, const void *seed,
                             size_t seedLen, uint32_t chain, const uint32_t indexes[]);
```

Legacy functions renamed for clarity:

```c
// Existing m/0H path with "DigiByte seed" HMAC — used for recovery scan only
BRMasterPubKey BRBIP32MasterPubKeyLegacy(const void *seed, size_t seedLen);
```

The existing `BRBIP32MasterPubKey` becomes an alias for `BRBIP32MasterPubKeyBIP84` (new default).

#### BIP84 Derivation Path

```
BIP39 Mnemonic
    ↓
PBKDF2-SHA512("mnemonic", 2048 iterations)
    ↓
512-bit Seed
    ↓
HMAC-SHA512("Bitcoin seed", seed)          ← changed from "DigiByte seed"
    ↓
Master Secret + Master Chain Code
    ↓
m/84' (CKDpriv, hardened)                  ← purpose: native segwit
    ↓
m/84'/20' (CKDpriv, hardened)              ← coin type: DigiByte (SLIP44)
    ↓
m/84'/20'/0' (CKDpriv, hardened)           ← account 0
    ↓
m/84'/20'/0'/chain (CKDpub, non-hardened)  ← 0=receive, 1=change
    ↓
m/84'/20'/0'/chain/index (CKDpub)          ← address index
    ↓
P2WPKH Address (bech32 "dgb1...")
```

#### BRWallet.c — Dual Key Tree Support

New wallet constructor for recovery:

```c
BRWallet *BRWalletNewDual(BRTransaction *transactions[], size_t txCount,
                          BRMasterPubKey mpkBIP84, BRMasterPubKey mpkLegacy);
```

Behavior:
- Pre-generates addresses from BOTH master pub keys across all four chains (legacy receive/change, bech32 receive/change) × 2 key trees
- Scans transaction history against all generated addresses
- New receive/change addresses use BIP84 master pub key only
- Legacy addresses are tracked for spending but never generated for receive
- `BRWalletSignTransaction` tries BIP84 private keys first, falls back to legacy for old UTXOs

Internal tracking:
```c
BRMasterPubKey masterPubKey;        // BIP84 (primary — new addresses)
BRMasterPubKey legacyPubKey;        // Legacy (recovery scan only)
int hasLegacyPubKey;                // 0 for new wallets, 1 for recovered wallets
```

#### Gap Limits

| Path | External (receive) | Internal (change) |
|------|-------------------|-------------------|
| BIP84 (new) | 20 | 10 |
| Legacy (recovery scan) | 30 | 10 |

BIP84 limits match the standard. Legacy scan uses 30 to cover the bloom filter's ~30 address range.

### JNI Bridge Changes (jni_wallet.c)

**createWalletFromBytes:**
- Uses `BRBIP32MasterPubKeyBIP84(seed)` only
- Calls `BRWalletNew(txs, count, mpkBIP84)` — single key tree
- New wallets are BIP84-only, no legacy scan needed

**recoverWalletFromBytes:**
- Derives both: `BRBIP32MasterPubKeyBIP84(seed)` + `BRBIP32MasterPubKeyLegacy(seed)`
- Calls `BRWalletNewDual(txs, count, mpkBIP84, mpkLegacy)` — scans both trees
- Old-path UTXOs are found and spendable

**seed_sign_transaction:**
- Wallet internally knows which key tree each UTXO belongs to
- Signs with BIP84 keys for new UTXOs, legacy keys for old UTXOs
- No change needed in JNI — `BRWalletSignTransaction` handles it internally

**New JNI methods:**
```c
// Returns "m/84'/20'/0'" for UI display
Java_..._getDerivationPath() → jstring

// Returns 1 if wallet has UTXOs on the legacy m/0H path
Java_..._hasLegacyFunds() → jboolean
```

### Kotlin / UI Changes

Minimal — the C core handles all derivation complexity.

- **AboutScreen.kt** — Display `getDerivationPath()` instead of hardcoded `m/44'/20'/0'`
- **WalletScreen.kt** — If `hasLegacyFunds()`, show subtle text: "Legacy funds detected — will migrate as you spend"
- **NetworkInfoScreen.kt** — Show derivation path in wallet info

No changes to SyncService, SendScreen, ReceiveScreen, TorManager, or any other component.

### Address Format

BIP84 specifies P2WPKH (native segwit) only. The wallet already generates bech32 addresses with HRP `"dgb"` (mainnet) / `"dgbt"` (testnet). No address format changes needed — just the derivation path that produces the keys behind those addresses.

Legacy P2PKH ("D" addresses) from the old path remain spendable during the dual-scan recovery, but new addresses are always bech32.

## Testing

### Ian Coleman Verification
1. Generate a 12-word mnemonic
2. In Ian Coleman's tool: select DigiByte, BIP84, coin type 20
3. Note the first 5 receive addresses (`m/84'/20'/0'/0/0` through `m/84'/20'/0'/0/4`)
4. Create a new wallet in the app with the same mnemonic
5. Verify the receive addresses match exactly

### Recovery Dual-Scan Test
1. Build the OLD version (pre-BIP84) and create a wallet
2. Send DGB to it, note the balance
3. Export the seed phrase
4. Build the NEW version (BIP84) and recover using the same seed
5. Verify the balance appears (dual-scan found the old-path UTXOs)
6. Verify the receive address is DIFFERENT (now BIP84-derived)
7. Send a transaction — verify it signs correctly (uses legacy private keys for old UTXOs)

### New Wallet Test
1. Create fresh wallet on new build
2. Verify receive address matches Ian Coleman BIP84 for the same seed
3. Send DGB to it, verify it appears
4. Send it back — verify change address is also BIP84-derived

### Signing Cross-Path Test
1. Recovered wallet with UTXOs on both paths (old + new)
2. Create a transaction that spends from both an old-path UTXO and a new-path UTXO
3. Verify the transaction signs correctly (mixed private key derivation)

## What's NOT in Scope

- BIP44 (`m/44'/20'/0'`) legacy address support — not needed, the old path isn't BIP44 anyway
- BIP49 (`m/49'/20'/0'`) P2SH-P2WPKH — DigiByte doesn't need wrapped segwit
- Multi-account (`m/84'/20'/1'`, `m/84'/20'/2'`) — future feature, not this change
- BIP39 passphrase support — currently always NULL, keep it that way
- Custom derivation path UI — users shouldn't need to know about derivation paths

## Migration Timeline

- v3.4.0 (current): Legacy path `m/0H` with "DigiByte seed"
- v3.5.0 (this change): BIP84 `m/84'/20'/0'` with "Bitcoin seed", dual-scan recovery
- Future: Consider deprecation warning for legacy path after N releases
