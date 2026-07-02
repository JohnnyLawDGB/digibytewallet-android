# Send Screen Fee Redesign

## Goal

Replace the Bitcoin-style 3-tier fee selector with a single default fee and optional custom fee input, reflecting DigiByte's 15-second blocks and empty mempool reality.

## Background

DigiByte has ~15-second block times and virtually no mempool congestion. The current 3-tier fee UI (Next Block / 5 Minutes / Economy) with 5x/2x/1x multipliers is borrowed from Bitcoin's fee market model. In practice, all three tiers confirm in the next block. The "Priority" tier (500 sat/byte) wastes user funds for zero benefit over the minimum relay fee (100 sat/byte).

**Test transaction (2026-04-01):** 0.000113 DGB fee (~80 sat/vbyte, Economy tier), confirmed in the next block with 18+ confirmations within 3 minutes. Mempool was empty.

**Node data (digiscope.me, v8.26.0):**
- `minrelaytxfee`: 0.001 DGB/KB (100 sat/byte)
- `mempoolminfee`: 0.001 DGB/KB
- `estimatesmartfee 1` == `estimatesmartfee 6`: 0.00375 DGB/KB (no fee pressure)
- Mempool: 0 transactions

## Design

### Default State

The send screen shows a single non-interactive fee line:

```
Network fee: 0.00014 DGB
Confirms in ~15 seconds
                              [Custom]
```

- Fee is calculated: `estimated_vsize × DEFAULT_FEE_PER_KB / 1000`
- `DEFAULT_FEE_PER_KB` = 100,000 sat/KB (100 sat/byte), defined in `BRWallet.h`
- `estimated_vsize` comes from creating the transaction via `NativeBridge.createTransaction` and reading its size (the C core already does this)
- "Confirms in ~15 seconds" is static text — always true when fee >= relay minimum
- "Custom" is a small text button that expands the custom input

### Custom State

When "Custom" is tapped:

```
Network fee: [  0.002121  ] DGB
Confirms in ~15 seconds
                             [Default]
```

- Text field accepting a DGB amount (decimal, up to 8 decimal places)
- "Default" button returns to the calculated default fee
- Pre-populated with the current default fee value when first expanded
- The entered value is the **total fee for the transaction**, not a rate

### Warning System

Based on the user's custom fee, calculate the effective fee rate:

```
effective_sat_per_vbyte = (custom_fee_dgb × 100,000,000) / estimated_vsize
```

Three states:

| Condition | UI | Send allowed? |
|-----------|-----|---------------|
| `effective_sat_per_vbyte >= 100` | "Confirms in ~15 seconds" | Yes |
| `0 < effective_sat_per_vbyte < 100` | "⚠ Below minimum relay fee — transaction may not broadcast" (amber) | Yes, with warning |
| `custom_fee_dgb <= 0` | "Fee required" (red) | No |

The amber warning does not block sending — power users may know their node accepts lower fees. But it clearly communicates the risk.

### Confirmation Dialog

The existing confirmation dialog already shows `Network fee: X.XXXXXXXX DGB`. No changes needed — it displays whatever fee was selected (default or custom).

## Files to Modify

### Remove
- `SendViewModel.kt`: `selectedFeeTier`, `FEE_DEFAULTS`, `feeTierLabel()`, fee tier mapping logic
- `SendScreen.kt`: Fee tier chip row (3 chips), sat/B and sat/KB display text

### Modify
- `SendViewModel.kt`: Add `isCustomFee: MutableStateFlow<Boolean>`, `customFeeInput: MutableStateFlow<String>`, computed `effectiveFee: StateFlow<Long>`, computed `feeWarning: StateFlow<FeeWarning>`. The fee flow: if default, use `DEFAULT_FEE_PER_KB × estimated_vsize`; if custom, parse user input and back-calculate the rate.
- `SendScreen.kt`: Replace fee tier chips with single fee display line + Custom toggle. When custom, show text field with DGB amount input and warning text. Remove sat/B and sat/KB display.
- `jni_transaction.c`: `getEstimatedFee` can be simplified to always return `DEFAULT_FEE_PER_KB` (single tier). Or remove the priority parameter entirely — but keeping the existing JNI signature avoids breaking changes.

### No changes needed
- `jni_wallet.c`, `jni_bridge.h`, `NativeBridge.kt` — the transaction creation and fee-setting JNI path is unchanged
- `BRWallet.h` — `DEFAULT_FEE_PER_KB` stays at 100,000 sat/KB
- Confirmation dialog — already displays the fee correctly

## Fee Calculation Flow

```
Default path:
  createTransaction(address, amount, DEFAULT_FEE_PER_KB)
    → C core builds tx, calculates fee from vsize × rate
    → Display: "Network fee: {fee} DGB"

Custom path:
  User enters total fee in DGB (e.g., 0.002121)
    → Back-calculate rate: (0.002121 × 1e8) / estimated_vsize = sat/vbyte
    → If rate < 100: show amber warning
    → Convert to sat/KB: rate × 1000
    → createTransaction(address, amount, calculated_sat_per_kb)
    → C core uses the custom rate
```

For the custom path, we need the estimated vsize before the user confirms. The approach: call `createTransaction` once with `DEFAULT_FEE_PER_KB` to get the tx size, then if the user enters a custom fee, re-call `createTransaction` with the back-calculated rate. The vsize won't change significantly between rates (same inputs/outputs, just the fee output changes slightly).

## What's Removed

- Three fee tier chips ("Next Block" / "5 Minutes" / "Economy")
- `FEE_DEFAULTS` array (20M / 10M / 5M sat/KB)
- `selectedFeeTier` state flow
- `feeTierLabel()` function
- "Estimated: XXX sat/KB" display text
- sat/B labels on chips
- C core fee multipliers (5x / 2x / 1x) — `getEstimatedFee` returns `DEFAULT_FEE_PER_KB` regardless of priority

## Testing

- Default fee send: verify tx creates with ~100 sat/vbyte rate, broadcasts, confirms
- Custom fee (above relay): enter 0.01 DGB, verify no warning, broadcasts fine
- Custom fee (below relay): enter 0.00001 DGB, verify amber warning appears
- Custom fee (zero/empty): verify send button disabled
- Custom fee toggle: verify switching between default and custom preserves other send fields
- Device test: send real transaction on mainnet with default fee, confirm in ~15 seconds
