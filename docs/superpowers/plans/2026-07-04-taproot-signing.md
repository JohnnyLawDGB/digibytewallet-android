# Taproot Key-Path Signing (spend P2TR) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** The wallet can **spend** its P2TR (BIP86 key-path) UTXOs — derive the taproot child private keys, compute the BIP341 key-path sighash, and produce a valid witness-v1 Schnorr signature with the tap-tweaked key. This completes send for plain-DGB Taproot and is the prerequisite for DigiDollar transfers.

**Architecture:** Additive to the existing signing path. `BRWalletSignTransaction` already matches inputs by address and derives per-chain keys; `BRTransactionSign` branches on input script shape (P2WPKH BIP143 is the template). We add: BIP86 privkey twins, a tap-tweaked Schnorr sign helper, a BIP341 sighash, and a witness-v1 branch (with a taproot-output-key match). Receive/derivation/watch already exist and are reviewed.

**Tech Stack:** C (breadwallet-core, `digibytewallet-core` submodule), secp256k1 (schnorrsig+extrakeys), host-C + instrumented KATs, node broadcast for the on-chain proof.

## Global Constraints

- **Correctness = fund-safety.** A wrong BIP341 sighash, a wrong witness format, or an untweaked/mis-paritied key yields signatures that fail verification → the UTXO is **unspendable** until fixed (funds stuck, not lost). Every crypto step is KAT'd against **authoritative BIP340/BIP341 vectors** (`github.com/bitcoin/bips` `bip-0341/wallet-test-vectors.json` keyPathSpending, `bip-0340/test-vectors.csv`) — never invent vector hex.
- **Two SHA traps, opposite of BIP143:** BIP341's `sha_prevouts/sha_amounts/sha_scriptpubkeys/sha_sequences/sha_outputs` are **single SHA256** (BIP143 uses double). The final digest is a **tagged** hash `TaggedHash("TapSighash", 0x00‖…)`. Getting single-vs-double SHA or the tag wrong = invalid sig.
- **Tap-tweaked key + parity:** the signing key is `d' = d + t` with BIP341 parity (negate `d` if the internal pubkey has odd Y). Use `secp256k1_keypair_create` then `secp256k1_keypair_xonly_tweak_add` (handles parity internally), NOT a hand-rolled add. `t = TaggedHash("TapTweak", x_only(P))` (BIP86: no merkle root), same as `BRKeyTaprootOutputKey`.
- **Right key:** taproot privkeys derive from `BRBIP32*BIP86` (m/86'), never BIP84.
- **Submodule:** commit C changes in `digibytewallet-core` via `git -C native/src/main/jni/digibytewallet-core commit -F -`; bump the pin in the root commit. **Do NOT push.** Branch `taproot-p2tr`.
- **No regression:** 42 security tests green; BIP84/P2WPKH + legacy signing byte-identical (the existing `BRTransactionSign` branches unchanged).

---

### Task 1: BIP86 private-key derivation twins

**Files:** (submodule) `BRBIP32Sequence.h` (decls), `BRBIP32Sequence.c`; (root) `native/src/test/host/bip86_privkey_kat/`

**Interfaces produced:** `void BRBIP32PrivKeyBIP86(BRKey *key, const void *seed, size_t seedLen, uint32_t chain, uint32_t index)` and `void BRBIP32PrivKeyListBIP86(BRKey keys[], size_t keysCount, const void *seed, size_t seedLen, uint32_t chain, const uint32_t indexes[])` — `m/86'/20'/0'/chain/index`.

- [ ] **Step 1 — Failing KAT.** Host-C KAT (Plan-1 pattern): for a fixed seed, derive `m/86'/20'/0'/0/i` privkey via `BRBIP32PrivKeyBIP86`, and assert its pubkey (`BRKeyPubKey`) equals the pubkey derived from the *public* path `BRBIP32PubKey(BRBIP32MasterPubKeyBIP86(seed), 0, i)` (priv/pub consistency), and that `BRKeyTaprootAddress` of that privkey equals the earlier Task-1 (Plan 2) pinned `dgb1p…`. RED: functions undefined.
- [ ] **Step 2 — Implement** as verbatim clones of `BRBIP32PrivKeyBIP84` (`BRBIP32Sequence.c:297-321`) and `BRBIP32PrivKeyListBIP84` (`:324-355`), changing ONLY `BIP84_PURPOSE`→`BIP86_PURPOSE` at the purpose CKDpriv step (lines 312 / 341). Keep `"Bitcoin seed"` (`BIP32_SEED_KEY_STANDARD`), `DGB_COIN_TYPE`, `BIP84_ACCOUNT`. Declare in the header.
- [ ] **Step 3 — GREEN** + 42 security tests green. **Commit** (submodule + root host-KAT + pin).

---

### Task 2: Tap-tweaked Schnorr sign helper (`BRKeyTaprootSchnorrSign`)

**Files:** (submodule) `BRKey.h`, `BRKey.c`; (root) `native/src/test/host/bip341_sign_kat/`

**Interfaces produced:** `size_t BRKeyTaprootSchnorrSign(BRKey *key, uint8_t *sig64, UInt256 md)` — signs `md` for the BIP86 key-path spend of `key`'s output key; writes 64 bytes; returns 64 or 0. `key` holds the UNtweaked child secret `d`; this applies the taptweak internally.

- [ ] **Step 1 — Failing KAT** from BIP341 `wallet-test-vectors.json` keyPathSpending: for a vector giving an internal privkey + the sighash + the expected 64-byte witness signature, assert `BRKeyTaprootSchnorrSign(key, sig, sighash)` equals the vector sig (BIP340 sign is deterministic with aux=NULL/zeros — confirm the vector's aux), and `secp256k1_schnorrsig_verify` accepts it under the tweaked output x-only key. RED: undefined.
- [ ] **Step 2 — Implement.** Sketch:
```c
size_t BRKeyTaprootSchnorrSign(BRKey *key, uint8_t *sig64, UInt256 md) {
    secp256k1_keypair kp; secp256k1_xonly_pubkey xo; uint8_t p32[32]; UInt256 t; size_t r = 0;
    pthread_once(&_ctx_once, _ctx_init);
    if (secp256k1_keypair_create(_ctx, &kp, key->secret.u8) &&
        secp256k1_keypair_xonly_pub(_ctx, &xo, NULL, &kp) &&
        secp256k1_xonly_pubkey_serialize(_ctx, p32, &xo)) {
        BRKeyTaggedHash("TapTweak", p32, sizeof(p32), &t);          // BIP86: no merkle root
        if (secp256k1_keypair_xonly_tweak_add(_ctx, &kp, t.u8) &&    // parity handled here
            secp256k1_schnorrsig_sign32(_ctx, sig64, md.u8, &kp, NULL)) r = 64;
    }
    var_clean(&kp); var_clean(&t);
    return r;
}
```
Confirm `secp256k1_keypair_xonly_pub` / `_keypair_xonly_tweak_add` are available (extrakeys module, enabled in Task-1 of the crypto plan).
- [ ] **Step 3 — GREEN** (vector sig matches, verify accepts) + 42 security green. **Commit** (submodule + root KAT + pin).

---

### Task 3: BIP341 key-path sighash (`_BRTransactionTaprootSighash`)

**Files:** (submodule) `BRTransaction.c`; (root) `native/src/test/host/bip341_sighash_kat/`

**Interfaces produced:** `static size_t _BRTransactionTaprootSighash(const BRTransaction *tx, uint8_t *data, size_t dataLen, size_t index, uint8_t hashType, UInt256 *out)` — computes the BIP341 key-path sighash for input `index`. For `SIGHASH_DEFAULT` (0x00), `out` = `TaggedHash("TapSighash", …)`.

- [ ] **Step 1 — Failing KAT** from BIP341 `wallet-test-vectors.json`: build a `BRTransaction` matching a keyPathSpending vector (utxos with `amount` + `scriptPubKey` on every input, the vector's outputs), compute the sighash for the vector's input index, assert it equals the vector's intermediary sigHash. RED.
- [ ] **Step 2 — Implement** the TapSighash for SIGHASH_DEFAULT/ALL. Concatenate: `epoch(0x00)`, `hash_type`, `nVersion(4 LE)`, `nLockTime(4 LE)`, then (not anyonecanpay) `sha_prevouts = SHA256(all outpoints)`, `sha_amounts = SHA256(all input amounts, 8 LE each)`, `sha_scriptpubkeys = SHA256(all prevout scriptPubKeys, each varint-len-prefixed)`, `sha_sequences = SHA256(all sequences)`, then (not SINGLE/NONE) `sha_outputs = SHA256(all outputs: value(8)+scriptlen(varint)+script)`, then `spend_type(0x00 key-path no-annex)`, `input_index(4 LE)`. **All `sha_*` are single SHA256.** Final: `BRKeyTaggedHash("TapSighash", buf, len, out)`. Reuse the existing `_BRTransactionWitnessData` (`:212`) ONLY as a structural reference — the hashing differs (single vs double SHA, tagged final, all-inputs amounts+scripts).
- [ ] **Step 3 — GREEN** (matches vector sigHash) + 42 security green. **Confirm** every input's `amount` + `script` is populated at sign time (they are set from the UTXO when the tx is built; a NULL/zero prevout script or amount = wrong hash — assert in the helper). **Commit** (submodule + root KAT + pin).

---

### Task 4: Witness-v1 sign branch + taproot chain-match

**Files:** (submodule) `BRTransaction.c` (`BRTransactionSign` `:687-761`), `BRWallet.c` (`BRWalletSignTransaction` `:1121-1209`); (root) instrumented signed-tx test

- [ ] **Step 1 — Failing test (instrumented).** Build a tx spending a synthetic P2TR UTXO owned by a fixed-seed wallet (scriptPubKey `{OP_1,0x20,X(Q)}`, a known amount), `BRWalletSignTransaction`, then assert the produced witness is a single 64-byte element that `secp256k1_schnorrsig_verify` accepts under `X(Q)` for the BIP341 sighash — and/or `NativeBridge.isRawTransactionSigned`. RED: taproot input unsigned (no branch / no key match).
- [ ] **Step 2 — `BRWalletSignTransaction` chain-match** (`:1135-1179`): add taproot loops matching `tx->inputs[i].address` (now correctly the `dgb1p…` after the char[76] fix) against `wallet->taprootExternalChain` / `taprootInternalChain` (gated on `wallet->hasTaprootKey`); collect indices; derive via `BRBIP32PrivKeyListBIP86` into `keys[]` (extend `totalKeys` + the `keyOff` assembly at `:1183-1200`). Keys clean at `:1204` already covers them.
- [ ] **Step 3 — `BRTransactionSign` witness-v1 branch** (`:700-751`): a P2TR input's scriptPubKey is `{OP_1,0x20,<32>}`, so the hash160 match at `:703-706` won't find it. Add a taproot path: detect `elemsCount==2 && *elems[0]==OP_1 && *elems[1]==32`, and match the key by **taproot output key** — find `j` where `BRKeyTaprootOutputKey(&keys[j], k32)` equals the input's 32-byte program (compute per candidate key). Then: `_BRTransactionTaprootSighash(tx, …, i, SIGHASH_DEFAULT, &md)`; `BRKeyTaprootSchnorrSign(&keys[j], sig64, md)`; build the witness = **one item, the 64-byte sig** (SIGHASH_DEFAULT → no trailing hashtype byte), empty scriptSig; `BRTxInputSetWitness` with the correctly item-counted witness (mirror how the P2WPKH witness is serialized — read `BRTransactionSerialize`'s witness format so the single 64-byte item is length/count-correct). Do NOT alter the existing P2WPKH/P2PKH branches.
- [ ] **Step 4 — GREEN** — the signed P2TR tx verifies; `getReceiveAddress(0,2)` BIP84 send path unchanged; 42 security green; existing taproot receive tests still pass. **Commit** (submodule + root test + pin).

---

### Task 5: On-chain P2TR self-send proof (mainnet)

**Files:** (root) a one-off signed-proof harness (delete after), like the foreign-seed proof.

- [ ] **Step 1** — expose a way to obtain a wallet `dgb1p…` (getReceiveAddress(0,3)) + build/sign a P2TR-input sweep of a funded taproot UTXO via the real signer, log the signed hex.
- [ ] **Step 2** — (USER-GATED, real funds) fund a wallet `dgb1p…` on mainnet (Taproot is live), then `testmempoolaccept` + `sendrawtransaction` the app-signed spend; confirm on-chain (UTXO spent, dest credited). Mirrors the v3.8.0 foreign-seed mainnet proof.
- [ ] **Step 3** — delete the one-off harness; do not commit it.

## Done when
- A P2TR-input transaction signs with a valid witness-v1 Schnorr signature (KAT'd against BIP341 vectors; verify accepts), BIP84/legacy signing is byte-identical, 42 security tests green, and a real P2TR spend confirms on mainnet.

## Open decisions / deferred
- **Change-to-P2TR:** once signing works, change *could* route to a wallet P2TR address (privacy). Deferred — a separate small follow-up (`BRWalletInternalChangeAddress` currently hardcodes scriptType 1 / P2WPKH); not required for spend correctness.
- **Script-path / DigiDollar mint-redeem:** still out of scope (key-path only).
