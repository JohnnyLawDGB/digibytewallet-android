# DigiDollar Wallet-Wiring (SHOW + ddUtxos groundwork) — Design

**Date:** 2026-07-05
**Status:** PROPOSAL — pending user review.
**Repo:** `digibytewallet-android` (Android app + native C SPV core). The `Digi-Mobile` node app is out of scope.

## 1. Problem / goal

The DigiDollar SHOW **decoder** (`BRDigiDollar.c`) is built and reviewed, but nothing in the wallet
*calls* it: the wallet neither detects DD token outputs paying us, tracks a DD balance, nor shows it.
This increment wires DD into the live wallet:

- **Detect + account** DD token UTXOs the wallet receives, tracking a separate **DD balance in cents**
  (never mixed into the DGB satoshi balance).
- **Show** the DD balance as a **slim line on the wallet hero card**, under the DGB balance.
- **Lay the `ddUtxos` groundwork** now — the unspent-DD-UTXO set that the future SEND (transfer)
  coin-selection will consume.

**User decisions (2026-07-05):** hero-card line (DD is money, sits next to DGB); build the `ddUtxos`
structures now.

## 2. Non-goals (deferred)

- **SEND / transfer builder** — spec §9.2; the `ddUtxos` set here is its input, but the builder itself is a later increment.
- **DD address decode** (`TD…` Base58Check) — testnet26-gated fund-safety item (wire-format spec §8 Q2); not needed for SHOW (ownership is by taproot output key, not address).
- **On-chain proof** — needs a 9.26 testnet26 node + a real DD tx. The C code + host KAT are provable now against synthesized/pinned vectors; the live-credit proof is deferred to when a testnet26 node is reachable.
- No new persistence format for DD (recomputed from the existing tx set each balance update, exactly like DGB/asset UTXOs).

## 3. Architecture

Mirrors the existing DigiAsset precedent (`assetUtxos`) almost line-for-line. Additive throughout.

### 3.1 C core — `BRWallet`

- **Struct fields** (`BRWallet.c` struct, next to `assetUtxos`): `BRUTXO *ddUtxos;` and `uint64_t ddBalance;` (cents). Allocated in `BRWalletNew*` (mirror `assetUtxos` init), freed in `BRWalletFree`.
- **Detection branch in `_BRWalletUpdateBalance`** (the output loop, `BRWallet.c:244-267`): inside the existing `BRSetContains(wallet->allAddrs, output.address)` ownership gate, **before** the asset check, add:
  ```
  int64_t ddCents = BRDigiDollarOutputAmount(tx, j);   // >= 0 only for a DD token output of a DD-marked tx
  if (ddCents >= 0) {
      array_add(wallet->ddUtxos, ((BRUTXO){ tx->txHash, (uint32_t)j }));
      wallet->ddBalance += (uint64_t)ddCents;
      // balance += 0  — DD tokens are zero-value; never touch the DGB balance
  } else if (BRTxOutputIsAsset(tx, &tx->outputs[j])) { ...existing... }
  else { ...existing DGB... }
  ```
  Ordering rationale: DD and DigiAsset are mutually exclusive on the wire ("DD" vs "DA" OP_RETURN, and DD requires the `0x0770` nVersion marker), so order is safety not correctness; DD is checked first because it is the more specific (marker-gated) classification.
- **Spent-DD pruning:** `ddBalance` accumulated in the loop counts *every* DD output ever paid to us, including ones we later spent. The existing spent-prune loop (`:269-276`) only touches `utxos`. Add a parallel pass (after the tx loop, or alongside) over `ddUtxos`: for each entry in `wallet->spentOutputs`, remove it from `ddUtxos` and subtract its cents (re-derived via `BRDigiDollarOutputAmount(spentTx, n)`) from `ddBalance`. Result: `ddUtxos`/`ddBalance` reflect only **unspent** DD — exactly what SEND needs and what the user should see.
- **` array_clear(wallet->ddUtxos)` and `wallet->ddBalance = 0`** at the top of `_BRWalletUpdateBalance` (mirror the `array_clear(assetUtxos)` reset at `:185`).
- **Accessors (`BRWallet.h`):**
  - `uint64_t BRWalletDigiDollarBalance(BRWallet *wallet);` → `wallet->ddBalance` (cents), under lock. Mirrors `BRWalletBalance`.
  - `size_t BRWalletDigiDollarUTXOs(BRWallet *wallet, BRUTXO *utxos, size_t utxosCount);` → copy-out the `ddUtxos` set (count if `utxos==NULL`), mirrors `BRWalletUTXOs`. **This is the SEND groundwork** — coin selection will call it, pair each `{hash,n}` with `BRDigiDollarOutputAmount` for its cents.

### 3.2 JNI — `jni_wallet.c`

- `getDigiDollarBalance()` → `jlong`, mirrors `getBalance` (`:562-571`): `return g_wallet ? (jlong)BRWalletDigiDollarBalance(g_wallet) : 0;` (cents). A `getDigiDollarUtxos`-style method is deferred to the SEND increment (the C accessor exists now; no JNI consumer yet).

### 3.3 Kotlin — bridge, ViewModel, UI

- **`NativeBridge.kt`:** add `external fun getDigiDollarBalance(): Long` (cents). **Additive** — does not alter any existing signature (the `SeedIsolationTest`-asserted contract stays intact).
- **`WalletViewModel.kt`:** a `_ddBalance = MutableStateFlow(prefs.getLong("last_dd_balance", 0L))` seeded from prefs (mirror the DGB `_balance` pattern, `:46-49`); poll `NativeBridge.getDigiDollarBalance()` in the existing `pollNativeBalance()` loop (`:432-465`) and persist. A `formatDigiDollar(cents: Long): String` companion formatter → `"$" + cents/100.0` with 2 fraction digits (mirrors `formatSatoshis`, `:651-658`). DD is a USD-pegged stablecoin → format as USD cents, **not** sats→DGB.
- **`WalletScreen.kt` / `BalanceDisplay.kt`:** add a slim secondary line under the DGB hero amount showing the formatted DD balance. Hidden (or shown as `$0.00` — decide in the plan) when `ddBalance == 0` so mainnet wallets (no DD) don't show a confusing empty line. The composable stays render-only; the ViewModel supplies the pre-formatted string, matching the existing `fiatAmount`/`dgbAmount` split.

## 4. Data flow

tx registered → `BRWalletRegisterTransaction` → `_BRWalletUpdateBalance` reruns → DD outputs paying our
taproot addresses routed to `ddUtxos` + `ddBalance` (spent ones pruned) → `pollNativeBalance()` reads
`getDigiDollarBalance()` every 5 s → `WalletViewModel._ddBalance` → hero-card DD line.

Detection lives in `_BRWalletUpdateBalance` (reruns on every registration) — deliberately **not** the
per-tx `onTransactionReceived` callback, which the seam map found to be dead wiring.

## 5. Correctness / edge cases

- **Zero-value never counts as DGB:** DD outputs are 0 sats; routing them to `ddUtxos` (not `utxos`) keeps them out of the DGB balance and prevents the wallet from ever selecting a DD token as a 0-value DGB input.
- **Ownership:** a DD output is credited only if its taproot address is in `allAddrs` (ours) **and** `BRDigiDollarOutputAmount ≥ 0` (its parent tx carries the `0x0770` marker + a positionally-bound "DD" OP_RETURN amount). A bare zero-value P2TR to us in a non-DD tx is **not** credited (decoder returns −1).
- **Spent DD:** pruned (see §3.1) so the shown balance is spendable, not cumulative.
- **Unconfirmed DD:** treated like any unconfirmed tx by the surrounding pending/invalid logic already in `_BRWalletUpdateBalance`; no special-casing.
- **Cents vs sats:** `ddBalance` is cents (USD), DGB `balance` is sats. They never mix; the UI formats them with different formatters and units.

## 6. Testing

- **Host KAT** (extend the `digidollar_decode_kat` harness or a new `digidollar_wallet_kat`, same clang recipe): build a wallet, install a taproot key (`BRWalletSetTaprootKey`) so a known `m/86'/…/0` address is watched, register a synthesized DD transfer tx paying that address, assert `BRWalletDigiDollarBalance` == the expected cents and `BRWalletDigiDollarUTXOs` count == 1. Add: a DD output to a **non-wallet** key → not credited; a **spent** DD UTXO → excluded from balance and set; a DGB regression check (DGB `balance` unchanged by the DD tx).
- **Regression:** `:app:assembleMainnetDebug` green; 42 security tests green; DGB balance path untouched.
- **On-chain (deferred, testnet26):** register a real testnet26 DD tx received at a wallet P2TR address; confirm the hero-card line shows the right dollar amount.

## 7. Suggested task order (for the plan)

A. C core: struct fields + init/free + `_BRWalletUpdateBalance` DD branch + spent-DD prune + `BRWalletDigiDollarBalance` — host KAT (credit, non-ours, spent, DGB-regression).
B. C core: `BRWalletDigiDollarUTXOs` enumeration accessor (SEND groundwork) — host KAT (count + copy-out).
C. JNI `getDigiDollarBalance` + `NativeBridge.kt` external fun.
D. Kotlin `WalletViewModel` DD balance StateFlow + poll + `formatDigiDollar`.
E. UI hero-card DD line (`WalletScreen`/`BalanceDisplay`).
