# Type-aware transaction amounts — DESIGN

**Date:** 2026-07-19
**Status:** operator-approved direction ("both together, one native release"). Native + app.
**Requirement (operator):** On the transaction list, each row should show its amount in the units of its *type*, not the near-zero on-chain DGB value:
- Native DGB tx → DGB amount (unchanged)
- **DigiDollar** tx → the **dollar value** ("$1"), not "0.00 DGB"
- **DigiAsset** tx → the **token count** ("1 Token", "20 Tokens"), not "0.000 DGB"

Classification is already correct (the type **chip** is right) — this is purely the **amount** shown. No classifier change.

## Why the DGB amount is ~0 today

DigiDollar and DigiAsset outputs are **0-value on-chain** — the value is encoded in the OP_RETURN (DD: USD-cents behind the `0x0770` marker; asset: token quantity behind the "DA" marker). The row renders net-DGB movement, which for these txs is ≈ the fee. So we must source the type amount from the tx's payload, not its output value.

## Data sources (verified 2026-07-19)

- **DD value (B) — needs a NEW native accessor.** Only the *total* `getDigiDollarBalance(): Long` (cents) is exposed; there is no per-tx DD amount. Native `BRDigiDollarOutputAmount(tx, j)` / `BRDigiDollarDecodeAmounts` decode per-output DD cents (marker-gated). → add a JNI `digiDollarTxAmount(txHashHex): Long` (cents), modeled on `digiDollarTxType` (`jni_transaction.c:505-520`). **Submodule → fork-push + pin-bump + native rebuild.**
- **Token count (C) — app-side.** The wallet already stores asset quantities: Room `UtxoEntity.asset_quantity` + `AssetManager` per-tx `quantityForOutput` (from `getTransactionOutputsForHash`). → a DAO query for the tx's owned asset quantity (receive), and the asset-transfer quantity for a send.
- **Render:** `formatDigiDollar(cents)` already yields "$X.XX". Token count formats as "N Token(s)".

## Architecture

**Native (submodule):** add `digiDollarTxAmount(txHashHex): Long`.
- Reverse BE→LE txid, `BRWalletTransactionForHash`, return the **wallet-relevant DD magnitude in cents** for the tx: sum `BRDigiDollarOutputAmount(tx, j)` over DD outputs the wallet owns (`BRWalletContainsAddress` on the output script) → that's a **receive**; if the wallet owns none (pure send funded by our inputs), sum the DD output amounts (the transferred value) → a **send**. Returns 0 for a non-DD / unknown tx (fail-safe, like `digiDollarTxType`). Direction (+/−) is taken from the existing DGB `tx.amount` sign in the UI, so the accessor returns an unsigned magnitude.
- JNI mirror in `core/bridge/NativeBridge.kt`: `external fun digiDollarTxAmount(txHashHex: String): Long`.

**App:**
- `UtxoDao`: `getAssetQuantityForTx(txid): Long?` = `SELECT SUM(asset_quantity) FROM utxos WHERE txid = :txid AND is_asset = 1`. Covers a **receive** (our received asset UTXO). For a **send**, the wallet's rows for that txid are the asset *change*; the sent quantity is derived from `AssetManager`'s per-tx parse (`getTransactionOutputsForHash` + header `quantityForOutput`), exposed as `AssetManager.assetAmountForTx(txid, isSend): Long?`.
- `WalletViewModel`: alongside `_txKinds`, build `_txTypedAmounts: Map<String,String>` — for each non-DGB tx, the pre-formatted type amount:
  - DIGIDOLLAR → `formatDigiDollar(NativeBridge.digiDollarTxAmount(txid))`
  - DIGIASSET → `"${count} " + if (count == 1L) "Token" else "Tokens"`
  - null/absent → row falls back to DGB (safe default; also covers a 0/last-resort).
- `TransactionItem(tx, onClick, modifier, kind, typedAmount: String? = null)`: if `kind != DGB && typedAmount != null` render `"$amountPrefix$typedAmount"` (no "DGB" suffix); else the existing `"$amountPrefix$amountFormatted DGB"`. Send/receive prefix + color unchanged.
- `WalletScreen.kt:302` area passes `typedAmount = txTypedAmounts[tx.txid]` next to `kind = txKinds[...]`.

## Reused / unchanged
`classifyTxKind`, the type chip, `formatDigiDollar`, the send/receive direction + color logic, `getDigiDollarBalance` (still drives the header balance). No classifier change (labels are already correct).

## Edge cases
- DD amount accessor returns 0 (unknown/failed decode) → `formatDigiDollar(0)` = "$0.00"; guard: if the native call returns ≤0, fall back to the DGB amount rather than show "$0.00". Same for token count ≤ 0.
- A tx that is both fee-bearing DGB and DD (real transfer where we only paid the fee) → shows the DD value it carries, which is the intent.
- List is display-capped, so per-row native/DB calls stay cheap (already true for `digiDollarTxType`).

## Blast radius + on-device verification
- No balance/classification behavior changes; only the **string shown** in the amount column for non-DGB rows.
- Verify on-device (operator wallet + adb): a known DD receive shows "$X" matching its value; a DigiAsset receive shows the right token count; native DGB rows unchanged; send rows show the sent value/count with the "−" prefix.

## Ship
Native change → submodule fork-push (flag first) + pin-bump + native rebuild + app. One release (v4.1.0 — a user-facing feature batch — or a patch per operator preference).
