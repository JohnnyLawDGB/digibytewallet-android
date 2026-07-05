# DigiDollar Send UI — Design

**Date:** 2026-07-05
**Status:** PROPOSAL. Wraps the already-reviewed + proven C-core SEND (builder + signing). Fund-moving
surface is the thin JNI orchestration; the underlying build/sign is done.
**Repo:** `digibytewallet-android`. `Digi-Mobile` out of scope.

## 1. Goal / scope

Let a user send DigiDollar from the app: pick a `TD…` recipient + a USD amount → the wallet builds,
signs, and broadcasts a DD transfer. Delivered as a **DGB ⇄ DD toggle inside the existing `SendScreen`**,
visible only when the wallet holds DD (`ddBalance > 0` — i.e. testnet26 builds; mainnet has no DD).
Single recipient. Reuses the existing send scaffolding (QR/paste, fee display, send button).

## 2. Non-goals (deferred)

- **Multi-recipient** DD send — YAGNI; single first.
- **DD receive-address display** (encode our taproot key → our `TD…`) — a closely-related but separate
  small increment (needs a `BRDigiDollarAddressEncode` + a receive-address JNI). Not in this increment;
  for testing, DD is sent to the wallet out-of-band (a core-dev/faucet DD transfer to our taproot key).
- **Mint / redeem** — Core/desktop only.

## 3. Architecture

### 3.1 C / JNI — one method, build+sign+publish in-memory

**Why one method (not the DGB 3-step create→sign→publish):** the DGB flow serializes the unsigned tx
between build and sign, but a serialized tx **loses per-input amounts + prevout scripts** — which the
BIP341 taproot sighash requires (DD inputs are value-0 P2TR). A single method keeps the in-memory tx
(with amounts/scripts) intact from build through sign.

- `jni_transaction.c`:
  ```
  jstring sendDigiDollar(JNIEnv, jobject, jstring tdAddress, jlong cents):
    if !g_wallet || !session-unlocked (seed) -> return NULL
    key32 = BRDigiDollarAddressDecode(tdAddress, isTestnet)   // isTestnet from BITCOIN_TESTNET
      -> NULL on decode failure
    tx = BRWalletCreateDigiDollarTransfer(g_wallet, key32, (uint64_t)cents)  // NULL on shortfall/bounds
    seed = <the loaded ByteArray seed, existing seed-access path>
    r = BRWalletSignTransaction(g_wallet, tx, 0, seed, seedLen); zero seed
    if r != 1 || !BRTransactionIsSigned(tx) -> free tx, return NULL
    <publish via the same path publishTransaction uses (BRPeerManagerPublishTx / register+broadcast)>
    return txid (hex, reversed display order)
  ```
  Model the seed access + publish on the existing `signTransaction` (`jni_transaction.c:82`) +
  `publishTransaction` (`:156`). Fail closed (NULL) at every step; free the tx on failure.
- `isValidDigiDollarAddress(jstring addr) -> jboolean` — thin wrapper over `BRDigiDollarAddressDecode`
  (isTestnet from build flag) for live UI validation, mirroring `isValidAddress`.

### 3.2 Kotlin — NativeBridge (additive)

```kotlin
external fun sendDigiDollar(tdAddress: String, cents: Long): String?   // txid, or null on failure
external fun isValidDigiDollarAddress(addr: String): Boolean
```
Additive — no existing signature changes (SeedIsolationTest contract intact). Mirror in the
`native/src/androidTest` NativeBridge stub.

### 3.3 SendViewModel — a DD mode

- `sendMode: StateFlow<SendMode>` (`DGB` | `DD`), default `DGB`. A `toggleSendMode()`.
- `ddBalance: StateFlow<Long>` (cents) — collected from the wallet VM / `getDigiDollarBalance` so the
  UI can gate the toggle's visibility and validate `amount ≤ ddBalance`.
- DD address validation: `isValidDigiDollarAddress(addr)` (live, like the DGB `addressValid`).
- DD amount: entered in **USD** (e.g. "40.00" → 4000 cents); validate `100 ≤ cents ≤ 10000000` and
  `cents ≤ ddBalance`. Reuse the amount field; the unit label flips to `$`.
- `sendDigiDollar()` action: `viewModelScope.launch(Dispatchers.IO)` → `NativeBridge.sendDigiDollar(td, cents)`
  → on non-null txid, success (navigate back / toast); on null, a clear error (insufficient DD/DGB-fee,
  bad address, or broadcast failure). Mirror the existing `send()` (`SendViewModel.kt:210`) structure.

### 3.4 SendScreen — the toggle

- When `ddBalance > 0`: show a compact **DGB ⇄ DD** segmented toggle at the top of the send card.
  In `DD` mode: the address field validates `TD…` (label "DigiDollar address", error "Invalid DigiDollar
  address"); the amount field shows `$` USD; the fee note reads "network fee paid in DGB"; the send
  button calls `sendDigiDollar()`. In `DGB` mode: unchanged.
- When `ddBalance == 0`: no toggle, screen is exactly today's DGB send (mainnet unaffected).

## 4. Data flow

user picks DD mode → types `TD…` (validated live) + USD amount → Send →
`SendViewModel.sendDigiDollar()` → `NativeBridge.sendDigiDollar(td, cents)` → (C) decode → build → sign
→ broadcast → txid → success UI. Errors surface as a message; nothing is half-sent (C fails closed to NULL).

## 5. Errors / fund-safety

- All fund-safety is in the C-core (reviewed): verbatim recipient key, strict conservation, `[100,10M]`c
  bounds, fail-closed NULL. The UI never constructs tx bytes.
- The UI must not enable Send unless: TD address valid, `100 ≤ cents ≤ 10000000`, `cents ≤ ddBalance`.
- A null txid → show the failure; do not claim success. (The C path already logged the specific reason.)
- Seed handling unchanged (`sendDigiDollar` uses the existing loaded-seed + zero-after-use discipline).

## 6. Testing

- **JNI/UI is build-green + manual/on-chain** (Compose + JNI glue aren't unit-tested in isolation).
- **ViewModel**: a JVM unit test for the USD→cents parse + the `[100,10M]` / `≤ ddBalance` validation.
- The fund-moving path is host-KAT-proven (C-core SEND); the live end-to-end send is the pending Task-4
  on-chain proof (funding-gated) — once funds land, a real send from the app closes it.
- Regression: DGB send path untouched when `ddBalance == 0`; 42 security tests + app build green.

## 7. Suggested task order (for the plan)

A. JNI `sendDigiDollar` + `isValidDigiDollarAddress` + NativeBridge external funs (+ androidTest stub).
B. SendViewModel DD mode (sendMode, DD validation, USD→cents, `sendDigiDollar()` action) + a validation unit test.
C. SendScreen DGB⇄DD toggle + field/label adaptation, gated on `ddBalance > 0`.
