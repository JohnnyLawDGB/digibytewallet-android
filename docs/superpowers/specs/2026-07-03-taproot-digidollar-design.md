# Taproot (P2TR key-path) + DigiDollar show/send/receive — Design

**Date:** 2026-07-03
**Status:** PROPOSAL — pending user review. No implementation until the plan is approved.
**Repo:** `digibytewallet-android` (Android app + native C SPV core). The separate
`Digi-Mobile` node app is explicitly **out of scope**.

## 1. Problem

DigiByte Core v9.26.4 shipped **DigiDollar**, a USD-pegged stablecoin built as a
**Taproot** layer. Our wallet's C SPV core (a breadwallet fork) has **no Taproot
support at all** — ECDSA only, BIP84 P2WPKH, bech32 (not bech32m). Base Taproot is
**already live on DigiByte mainnet** (activated in v8.26; real P2TR txs and
inscriptions exist), and DigiDollar rides on top of it (live on testnet26, pending
on mainnet). To hold/receive/send DigiDollar — and to support Taproot addresses at
all — the core needs P2TR **key-path** support.

## 2. Goals & non-goals

**Goals**
- **Taproot (P2TR) key-path support** in the C core: x-only keys, BIP340 Schnorr
  signing, bech32m addresses, BIP86 derivation, BIP341 key-path sighash.
- **Send + receive plain DGB** to/from Taproot (`dgb1p…`) addresses (general P2TR).
- **Hold + show** DigiDollar balances.
- **Receive + send** DigiDollar (the DigiDollar **transfer**, tx type 2).

**Non-goals (stay a DigiByte Core / desktop function)**
- **Mint** (locking DGB collateral) and **redeem**. This is the key simplification:
  it removes the need for **Tapscript script-path spends**, the **price oracle /
  MuSig2**, system-health, and any **node-RPC** dependency. This milestone is
  **key-path Taproot only**.
- Inscriptions/Ordinals handling (Taproot enables it, but not in scope here).
- Any dependency on the `Digi-Mobile` node app.

## 3. Key facts (verified)

- **Taproot is LIVE on DGB mainnet** (v8.26+; user has broadcast Taproot txs and
  inscriptions). BIP341 witness-v1 spends relay and confirm on mainnet today.
- **DigiDollar is NOT live on mainnet** — testnet26 only (Taproot is `ALWAYS_ACTIVE`
  there). It is `Phase 1` code in Core; wire details may still shift.
- **Bring-up:** general P2TR send/receive is provable **on mainnet now**; DigiDollar
  show/send is provable **on testnet26**.
- **Our core already runs multiple parallel script-type chains** (P2PKH + P2WPKH +
  4 legacy) off one BIP84 account xpub, sharing one `allAddrs` set, one bloom
  filter, one BIP158 element set, one balance. Adding BIP86 Taproot is a **new twin
  chain** on that exact template.
- **BIP158 detection is witness-version-agnostic** — `BRWalletGetFilterElements`
  emits raw scriptPubKeys and the matcher would flag an `OP_1<32>` receive with no
  new matcher code; it is simply never fed a taproot address today.
- `BRTxInput` already stores per-input **amount + prevout script** — the fields
  BIP341's all-inputs sighash needs.
- **secp256k1 is pre-0.1** (only ecdh + recovery modules; no schnorrsig/extrakeys).

## 4. Architecture (components)

Everything is additive and follows the existing multi-chain template; BIP84 P2WPKH
and BIP86 P2TR coexist in one `BRWallet` / one balance / one filter.

### 4.1 Crypto foundation
- **secp256k1 bump:** vendor a schnorrsig+extrakeys-capable secp256k1 from
  **DigiByte Core** (the version its node uses for Taproot), enable
  `ENABLE_MODULE_SCHNORRSIG` + `ENABLE_MODULE_EXTRAKEYS`. Provide shims/renames for
  the deprecated tweak API (`secp256k1_ec_privkey_tweak_add/_mul`,
  `_pubkey_tweak_add/_mul` at `BRKey.c:77/85/109/122`) so the existing ECDSA +
  Digi-ID (recoverable) paths still build. Reconcile the two vendored secp copies to
  one canonical copy. **The 42 security tests and Digi-ID must stay green.**
- **Taproot key primitives (`BRKey`):** a tagged-hash helper; `BRKeyTaprootAddress`
  (33-byte pubkey → x-only → BIP86 taptweak `Q = P + int(tagged_hash("TapTweak",
  P))·G` via `secp256k1_xonly_pubkey_tweak_add` → `{OP_1,0x20,X(Q)}` → bech32m),
  mirroring `BRKeySegwitAddress`; `BRKeySchnorrSign` (BIP340 over a keypair,
  signing with the **tap-tweaked** private key `d' = d + t`).

### 4.2 Addressing — bech32m (BIP350)
- Version-aware encoder/decoder: constant `1` for witness v0, `0x2bc830a3` for v1+
  (polymod identical; only the final XOR differs) in both `BRBech32Encode` and
  `BRBech32Decode`. Wire `BRAddressFromScriptPubKey` (v1 branch),
  `BRAddressScriptPubKey`, and address validation to select the constant by witness
  version. Without this the wallet can neither render its own `dgb1p…` receive
  address nor decode/send to external Taproot addresses.

### 4.3 Derivation — BIP86 twin chains
- `BRBIP32MasterPubKeyBIP86` (`m/86'/20'/0'`), `BRBIP32PrivKeyBIP86` /
  `…ListBIP86`; `BIP86_PURPOSE = 86` + P2TR gap-limit constants. Child pubkeys reuse
  `BRBIP32PubKey` unchanged; the taptweak is applied at address-render and sign time.
- Add a **second account xpub** field (`taprootPubKey BRMasterPubKey`) to the wallet
  struct, plus internal/external taproot chains, because the single
  `wallet->masterPubKey` is the `m/84'` xpub and cannot derive `m/86'` children.

### 4.4 Wallet integration
- Generalize `BRWalletUnusedAddrs`'s `nativeSegwit` int to a **script-type enum**
  with a P2TR branch off `taprootPubKey`; include the new chains in the `allAddrs`
  rebuild loop, `BRWalletAllAddrs` partitioning, and the **gap+100** pre-gen callers
  (missing one causes intermittent missed receives past the gap — the
  post-upgrade-zero-balance failure class).
- **Address-string canonicalization:** the bech32m string produced by derivation and
  by `BRAddressFromScriptPubKey` on a received output must be **byte-identical**, or
  `BRSetContains(allAddrs, addr)` misses and the receive is never credited (silent
  fund-visibility bug).

### 4.5 Receive + detection
- BIP158 is the primary path and is nearly free once P2TR addresses exist in
  `allAddrs`. Verify the P2TR scriptPubKey is emitted as a filter element and that a
  matched output is credited.
- **Bloom is P2TR-blind as-is** (inserts 20-byte hash160 + a hardcoded P2WPKH
  program; `BRAddressHash160` rejects a 32-byte program). BLOOM_ONLY sessions and the
  120s BIP158→bloom watchdog fallback would not see P2TR/DD receives. Generalizing
  bloom to insert the 32-byte output key is **optional** and decided separately.

### 4.6 Key-path signing
- `_BRTransactionTaprootSighash`: tagged `SHA256("TapSighash")` over epoch,
  hash_type, nVersion, nLockTime, `sha_prevouts`, `sha_amounts`, `sha_scriptpubkeys`,
  `sha_sequences`, `sha_outputs`, spend_type, input_index (thread every input's
  amount+prevout-script into the digest — the fields exist on `BRTxInput`; verify
  each input's `script` holds the prevout scriptPubKey at signing time).
- Witness-v1 sign branch in `BRTransactionSign`: on `elems==2 && OP_1 && push32`,
  compute the key-path sighash (`SIGHASH_DEFAULT` = 0x00), sign with
  `BRKeySchnorrSign` using the **tap-tweaked** key, set witness = single 64-byte sig
  (65 with a non-default hashtype), empty scriptSig. Update
  `BRWalletSignTransaction` chain-matching to recognize taproot-chain inputs and
  derive+tweak the key.

### 4.7 DigiDollar SHOW
- A DD `OP_RETURN` decoder modeled on `BRDigiAsset.c` (`BRTxOutputIsAsset`): on tx
  registration, over the already-fetched full tx, verify the DD `nVersion` marker,
  parse the `OP_RETURN "DD"` metadata, and associate each cent amount with its
  zero-value P2TR output by index. Expose DD balance via JNI for Kotlin display.

### 4.8 DigiDollar SEND
- A tx-builder path for a DD **transfer** (type 2): select DD-token UTXOs + DGB for
  fees, emit one zero-value DD P2TR per recipient + `OP_RETURN("DD", 2, amount1..N)`
  + DGB change, set the `nVersion` type-2 marker, enforce DD-value conservation, and
  sign via the new P2TR key-path signer.

### 4.9 JNI + Kotlin surface
- Widen `getReceiveAddress(format)` so `format==3` → P2TR (segwit is `2`); confirm
  the Kotlin `NativeBridge` contract. Surface DD balance for display and a DD-send
  entry in the send UI. Decide change-output type (see §7).

## 5. DigiDollar wire format (from source recon — must be pinned before SHOW/SEND)

Working model from the DigiByte Core recon: a DD token is a **zero-value P2TR
output**; the cent amount(s) live in an `OP_RETURN` push of `"DD"` followed by a tx
type and per-output amounts; the creating tx is flagged by
`(nVersion & 0xFFFF) == 0x0770`, with type in the high byte (1=mint, 2=transfer,
3=redeem). Amounts are minimal-encoded `CScriptNum` cents.

**Required before implementing the SHOW decoder and SEND builder:** pin the exact
byte-level `OP_RETURN` encoding and the DD-token key derivation against a **real
testnet26 DigiDollar transfer transaction** (DigiDollar is `Phase 1` code; do not
implement fund-moving logic against an inferred format).

## 6. Security

- **Tap-tweaked signing:** key-path spends MUST sign with `d' = d + t`; signing with
  the untweaked `d` yields signatures that fail verification (the wrong-key
  fund-bug class from the legacy-sweep hardening).
- **Canonicalization:** derived vs decoded bech32m strings must match byte-for-byte.
- **No regressions:** the secp bump must keep ECDSA, Digi-ID (recoverable), and the
  42 security tests green.
- Seed handling unchanged: BIP86 derivation reuses the existing `ByteArray`
  seed-zeroing discipline; no new persistence of key material.

## 7. Open decisions (for the plan)

- **Change-output policy** for taproot-involving sends: P2TR, stay P2WPKH, or match
  the input type (privacy vs gap-limit accounting).
- **Bloom P2TR coverage:** accept BLOOM_ONLY blindness to P2TR (BIP158 required for
  DD detection), or invest in the bloom generalization.
- **Release split:** ship P2TR receive/show first (prove receive on mainnet), then
  DD send — or one milestone. (Recommended: receive/show first.)
- **DD format pinning** against a live testnet26 DD tx (§5) before SHOW/SEND code.

## 8. Testing

- **KATs:** BIP340/BIP341 signing test vectors; a `dgb1p…` bech32m round-trip; a
  BIP86 `m/86'/20'/0'/0/0` derivation known-answer vector (mirrors the recent
  legacy-derivation vector test).
- **Regression:** the 42 security tests + Digi-ID stay green across the secp bump.
- **On-chain proof (mainnet):** receive DGB at a wallet P2TR address (credited via
  BIP158) and a P2TR self-send that confirms — Taproot is live on mainnet, so this
  is provable now.
- **On-chain proof (testnet26):** receive and send a DigiDollar transfer; verify the
  decoder balance and the send confirms.
- Pre-publish suite must pass before any release; release gates on the on-chain
  proofs.

## 9. Suggested phase order (for the plan)

A. Crypto foundation (secp bump + taproot key primitives + bech32m) — verified
against BIP340/341 vectors.
B. BIP86 derivation + wallet integration (2nd xpub, chains, gap-limit) — derivation
KAT.
C. P2TR receive + detection — mainnet receive proof.
D. Key-path signing — mainnet P2TR self-send proof.
E. DigiDollar SHOW — pin format on testnet26, decoder, JNI display.
F. DigiDollar SEND — transfer builder + testnet26 send proof.
