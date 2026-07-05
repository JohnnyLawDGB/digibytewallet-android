# DigiDollar SEND (transfer, tx type 2) — Design

**Date:** 2026-07-05
**Status:** PROPOSAL — pending user review. **No implementation until approved** (fund-moving code).
**Repo:** `digibytewallet-android` (Android app + native C SPV core). `Digi-Mobile` out of scope.

## 1. Goal / scope

Build and broadcast a DigiDollar **transfer** (tx type 2): move DD cents from the wallet to a
recipient `TD…` address, on-chain. This increment is **C-core builder + address decode + host KATs +
a real testnet26 on-chain proof** — single recipient. **JNI + SendScreen UI is a deliberate follow-up
increment.** Rationale: SEND is the only fund-moving DigiDollar code; prove the builder end-to-end on
testnet26 (build → sign → broadcast → confirm) before wiring a UI on top, exactly as we did for Taproot
key-path signing.

**Key de-risk (verified in the seam check):** the signer is **already built and mainnet-proven.**
`BRWalletSignTransaction` → `BRTransactionSign` already has the witness-v1 P2TR key-path branch
(matches our taproot chain → BIP86 key → `BRKeyTaprootSchnorrSign`) and the P2WPKH SIGHASH_ALL path for
fee inputs. So a correctly-*built* DD transfer signs through the existing proven path — **no new crypto.**
The new code is the **builder** and the **address decoder**.

## 2. Non-goals (deferred / out of scope)

- **JNI + SendScreen DD-send UI** — next increment (consumes the builder built here).
- **Multi-recipient** (`sendmanydigidollar`) — YAGNI; single recipient first. The OP_RETURN/positional
  format already supports N, so multi is a later, small extension.
- **Mint / redeem** — stay a Core/desktop function (established milestone boundary).
- No change to the DGB send path.

## 3. Architecture (components)

Additive, in the `digibytewallet-core` submodule. Two new units + reuse of the proven signer.

### 3.1 `BRDigiDollarAddressDecode` — TD/DD Base58Check → 32-byte taproot key

```c
// Decodes a DigiDollar address ("TD…" testnet / "DD…" mainnet, Base58Check) into its 32-byte
// taproot output key. Returns 1 on success (and writes key32), 0 on any failure (bad checksum,
// wrong version, wrong length) — fail closed. isTestnet selects the expected version bytes.
int BRDigiDollarAddressDecode(uint8_t key32[32], const char *addr, int isTestnet);
```
- Uses the existing `BRBase58CheckDecode` (verifies the 4-byte double-SHA256 checksum).
- Verifies the 2-byte version: testnet `{0xb1,0x29}` ("TD"), mainnet `{0x52,0x85}` ("DD") — spec §4,
  confirmed against the real address `TD2z1nkvx…` (version `b129`, 32-byte key, valid checksum).
- Payload must be exactly 34 bytes (2 version + 32 key); extract `key32`.
- **Golden KAT:** `TD2z1nkvxPfrny6TNBnukvzrK1kGGens8Ds4NNLWUrFPc6H8ZXoC` →
  `dcea6096993f4781402e763c9d360979c3cf66a43818c95b9087f088cf62631b`; plus negatives (bad checksum,
  wrong version, truncated).

### 3.2 `BRWalletCreateDigiDollarTransfer` — the builder

```c
// Builds an UNSIGNED DigiDollar transfer paying `cents` DD to `recipientKey32` (the decoded TD-address
// key). Selects the wallet's DD UTXOs to cover `cents` and DGB UTXOs to cover the fee, emits the DD
// outputs + OP_RETURN + change, sets nVersion=0x02000770. Returns the unsigned BRTransaction (caller
// signs with BRWalletSignTransaction), or NULL on failure (insufficient DD, insufficient DGB for fee,
// zero/oversize amount). Never partially/incorrectly builds.
BRTransaction *BRWalletCreateDigiDollarTransfer(BRWallet *wallet, const uint8_t recipientKey32[32],
                                                uint64_t cents);
```

**Inputs (coin selection):**
- **DD inputs:** enumerate `BRWalletDigiDollarUTXOs`, pair each with `BRDigiDollarOutputAmount(tx,n)` for
  its cents, sort **smallest-first**, accumulate until `sum ≥ cents`. Each DD input is added at its
  on-chain **value 0** with its `51 20 <ourKey>` scriptPubKey (so `BRWalletSignTransaction` matches it to
  our taproot chain and the BIP341 sighash commits amount 0). Fail (NULL) if the wallet's total DD < cents.
- **DGB fee inputs:** enumerate `BRWalletUTXOs` (DGB, disjoint from DD), smallest-first, accumulate to
  cover the fee estimate. Fail (NULL) if DGB < fee.

**Outputs (order is consensus-significant — positional binding, spec §3.2 / §6):**
1. `vout[0]` = **recipient DD output** — `51 20 <recipientKey32>`, value 0. Key used **verbatim, no
   re-tweak** (spec §3.1 — re-tweaking = fund loss).
2. `vout[1]` = **DD change** (only if `selectedDD > cents`) — `51 20 <ourTaprootChangeKey>`, value 0.
   Key = a fresh **internal** taproot address's output key (BIP86-tweaked; we hold the internal key so we
   can spend it later). Change cents = `selectedDD − cents`, must be `≥ 1` cent (see conservation).
3. `vout[next]` = **DGB change** (P2WPKH `0014<20>`) if `dgbIn − fee ≥ DUST`.
4. `vout[last]` = **OP_RETURN** — `6a 02 4444 01 02 <cents CScriptNum> [<ddChangeCents CScriptNum>]`
   (one minimal CScriptNum per DD output in vout order: recipient, then DD change). **No count field.**

**Header:** `version = 0x02000770`, `lockTime = 0`, every input `sequence = 0xffffffff`.

### 3.3 Signing — reuse the proven path (no new code)

Caller signs the returned unsigned tx with **`BRWalletSignTransaction(wallet, tx, 0, seed, seedLen)`**:
DD (P2TR) inputs → key-path Schnorr (empty 64-byte witness) via the mainnet-proven branch; DGB (P2WPKH)
fee inputs → BIP143 SIGHASH_ALL. Broadcast the serialized bytes.

## 4. Fund-safety invariants (fail closed on every violation)

- **Strict DD conservation:** `sum(selected DD cents) == cents (recipient) + ddChangeCents`. The DD change
  captures the **entire** remainder — **never drop a 1..99-cent dust remainder** (spec §5.4: consensus
  enforces strict equality; the reference builder's dust-drop would be consensus-*rejected*). If the
  remainder is unavoidably tiny, it still becomes a DD change output; we never silently burn it.
- **Recipient key verbatim:** emit `51 20 <recipientKey32>` with no tweak (§3.1).
- **DD change to a key we own** (internal taproot chain) so funds return to us and stay spendable.
- **DD inputs enter signing at value 0** (on-chain nValue) — the BIP341 sighash commits input amounts;
  a wrong value = invalid sig. (Handled by adding the input at value 0; the source's stale "dummy
  value=1" comment is wrong — wire-format spec §6.)
- **Fee inputs SIGHASH_ALL** (P2WPKH v0), never DEFAULT (§6, source-resolved).
- Builder returns **NULL** (never a half-built tx) on: cents==0, DD balance < cents, DGB < fee, address
  decode failed, or no taproot key installed.
- Seed handling unchanged (existing `BRWalletSignTransaction` zeroing discipline).

## 5. Fee

Model on the existing DGB builder (`BRWalletFeeForTxSize` / `DEFAULT_FEE_PER_KB`): estimate the signed
size (DD inputs ≈ 1 P2TR witness each ~57 vB; fee inputs P2WPKH; outputs), compute `size × feePerKb`, and
apply a floor to match the reference builder's `MIN_DD_FEE` (spec §6 cites 0.1 DGB). Exact floor confirmed
by `testmempoolaccept` on the testnet26 node during the proof (adjust to whatever the node accepts).

## 6. Data flow

`TD… + cents` → `BRDigiDollarAddressDecode` → `key32` → `BRWalletCreateDigiDollarTransfer` (coin-select,
outputs, conservation) → unsigned tx → `BRWalletSignTransaction` → serialize → broadcast to the testnet26
node → confirm.

## 7. Testing

- **Host KATs** (new `digidollar_send_kat`, same clang recipe): 
  - address decode: the real `TD2z1nkvx…` golden vector + negatives.
  - builder on a synthetic wallet (taproot key installed, DD UTXOs credited via the wiring KAT pattern):
    assert `version==0x02000770`; recipient output `51 20 <recipientKey32>` value 0; DD change present
    with the right cents when `selectedDD > cents` and absent when exact; OP_RETURN bytes
    `6a 02 4444 01 02 <cents> [<change>]`; **strict conservation** (`Σin == Σout` DD cents); NULL on
    insufficient DD / insufficient DGB / cents==0.
  - sign+verify: run the built tx through `BRWalletSignTransaction`, assert `BRTransactionIsSigned`, and
    (as in the taproot signing KAT) `secp256k1_schnorrsig_verify` the DD input witness under X(Q).
- **On-chain proof (testnet26):** fund a fresh wallet with testnet **DGB (fee)** + **DD** — from the
  johnnylaw machine or mined on testnet — build+sign a real transfer to a `TD…` recipient (e.g. the node's
  `getdigidollaraddress`), `testmempoolaccept` → `senddigidollar`-free raw broadcast → confirm the transfer,
  and verify with the node's `getdigidollarbalance` that the recipient credited and our change returned.
- **Regression:** DGB send path untouched; 42 security tests + app build green.

## 8. Suggested task order (for the plan)

A. `BRDigiDollarAddressDecode` (TD/DD Base58Check → key) + host KAT (real golden vector + negatives).
B. `BRWalletCreateDigiDollarTransfer` builder — coin selection + outputs + OP_RETURN + strict conservation
   + DD change key derivation — host KAT (structure + conservation + NULL cases).
C. Sign+serialize KAT: built tx → `BRWalletSignTransaction` → witness verifies under X(Q); serialize round-trip.
D. testnet26 on-chain proof (USER-GATED funding): fund → build → sign → broadcast → confirm on the node.
