> ## Disposition — SHIPPED (v4.0.36 → v4.0.39), with a root-cause correction
>
> **The failure is fixed.** The reproducible case in this spec (issue 10, send 1, wallet
> reports 18) was reproduced, root-caused, and closed. Verified on two devices against the
> DigiAsset explorer, per this spec's own "validate against ground truth" rule.
>
> **The spec's hypothesis was wrong about the mechanism, and the code won.** This spec
> attributes the drift to *transaction-history replay* accounting. It is not that. Two
> distinct bugs produced the inflation:
>
> 1. **The implicit-change rule was missing.** DigiAssets returns the leftover of a partial
>    transfer to the LAST output. Neither the Kotlin nor the native side applied it, so a
>    partial send lost track of the retained balance. (Fixed v4.0.36. The native half was
>    worse than a display bug: a plain-DGB spend could destroy an asset.)
> 2. **A re-sent stuck send counted its change twice.** An abandoned broadcast still read as
>    HELD, because nothing ever spends the change of an attempt that never confirmed. Fixed
>    by modelling a CONFLICTED state (v4.0.36).
>
> **The "count from the UTXO set, not replay" instinct was still right**, and is now the
> rule: a row counts only when the native wallet still holds that exact output. v4.0.37 and
> v4.0.38 were a wrong turn and a revert; v4.0.39 landed the rule. What made the difference
> was logging the per-row decision instead of inferring it from totals — four releases were
> spent guessing at the total before that.
>
> Design: `docs/superpowers/specs/2026-08-16-digiasset-implicit-change-design.md`.

# Spec: DigiAsset Balance Accounting — UTXO-Derived

**Status:** Draft / investigation-first
**Priority:** P0
**Blocks:** `assets.digistamp.co` marketplace integration; asset-aware sweep (restore spec)

---

## 1. Problem

DigiAsset balances are wrong. They appear to be accumulated by replaying transaction
history during sync, which produces drift that never self-corrects.

### Motivating failure case

1. Issue a DigiAsset with supply **10**. Wallet, chain, and explorer all agree: 10.
2. Send **1** unit to an external address.
3. Chain and DigiScope explorer agree: **9** held, **1** at the destination.
4. Wallet reports: **18**.

### Leading hypothesis

18 is not a random number — it is arithmetically explainable, which makes it a strong
lead. The likely sequence:

```
starting tally        = 10
debit transferred amt = 10 - 1  =  9    ← wrong: debits the *transferred* amount
credit change output  =  9 + 9  = 18    ← then credits the change output as if new
```

The bug is that spending an asset-bearing input debits only the amount sent, rather than
removing the **entire consumed input** from the tally, and then separately credits the
change output's remainder. The change quantity is therefore counted twice.

**This is a hypothesis, not a finding.** Confirm it against the code before refactoring.
Competing explanations that should be ruled out:

- The issuance output is counted once at issuance and again on rescan (would give 19, not 18 — likely ruled out, but confirm the arithmetic against real logs).
- Asset quantity is resolved per *transaction* rather than per *output*, so a tx with both a transfer and a change output double-attributes.
- Reorg or duplicate-block handling replays the same tx twice with partial idempotency.

---

## 2. Root cause (architectural)

History-replay accounting requires perfectly ordered, exactly-once processing of every
input debit and output credit across the entire chain. Any missed spend, duplicated
block, reorg, or out-of-order delivery corrupts the tally permanently. There is no
reconciliation step, so a single error compounds forever.

This is the wrong model for a mobile wallet on a filter-sync substrate, where block
delivery is inherently partial and out-of-order.

---

## 3. Target design

**Compute asset balances statelessly from the current UTXO set.**

```
balance(assetId) = Σ quantity(assetId, utxo) for utxo in unspentOutputs
```

Properties this buys us:

- **Correct by construction.** A spent output is simply absent from the set; its assets
  disappear automatically with no debit bookkeeping.
- **Self-healing.** Any transient error is erased on the next recomputation. There is no
  accumulated state to corrupt.
- **Reorg-safe.** UTXO set changes, balance follows. No replay compensation logic.
- **Change outputs stop being special.** A change output is just another UTXO carrying a
  quantity. Nothing is credited or debited.

### Hard rules

1. **Never count inputs.** Only unspent outputs contribute to balance.
2. **Never maintain a running asset tally as mutable synced state.** Derive on read, or
   cache with the UTXO set as the sole source of truth and invalidate on any set change.
3. **Quantity is a property of an output, not of a transaction or an address.**

---

## 4. Reconciling with BIP157/158 filter sync

Filter sync gives us *which outputs are ours*. It does not necessarily give us *how many
units of an asset each output carries*, because DigiAssets v2 encodes transfer
instructions that may require walking back toward the issuance to resolve a quantity.

Two-layer model:

**Layer 1 — Ownership (private, unchanged).**
BIP158 filters are downloaded and tested locally against our script set. Matching blocks
are fetched. This produces the UTXO set. No change to existing behavior. No address
disclosure.

**Layer 2 — Asset quantity resolution (targeted).**
For each UTXO the parser flags as asset-bearing, resolve its asset ID and quantity.
Asset-bearing outputs are a small minority of a typical wallet's UTXOs, so the lookup
surface is small and bounded — a very different privacy profile from dumping the full
address set at an ElectrumX server.

### Investigation required

- Where does the parser currently source asset data? Fully local from the OP_RETURN
  payload in the fetched block, or does it call out?
- If it calls out, to what, and with what granularity — per asset ID, per txid, or per
  address? Per-address lookups are a leak and need redesign.
- Can quantity be resolved from the transfer instructions in the fetched block alone for
  the common case, with a back-walk only for edge cases?
- What does the resolution path do offline / when the source is unreachable? Balance
  should degrade to "unknown", never to a wrong number.

### Caching

Asset *definitions* (ID, divisibility, aggregation policy, metadata) are immutable and
should be cached aggressively and indefinitely. Asset *quantities per UTXO* should be
cached keyed on outpoint, and invalidated only when that outpoint is spent — which never
happens, since a spent outpoint just leaves the set. This means resolution cost is paid
once per output, ever.

---

## 5. Edge cases to handle (or explicitly defer)

- **Multiple assets on one output.** A single UTXO can carry more than one asset ID.
  Quantity resolution must return a collection, not a scalar.
- **Aggregation policy.** DigiAssets v2 distinguishes aggregatable / hybrid / dispersed.
  Confirm whether this affects how units on separate UTXOs of the same asset ID should be
  summed for display.
- **Divisibility.** Display units vs. base units. Ensure the tally is in base units and
  only formatted for display.
- **Locked vs. unlocked issuance.** Unlocked assets can be re-issued, so total supply is
  not fixed. Wallet balance is unaffected but any "of N total" display is.
- **Burns.** Confirm the parser recognizes burn instructions and that burned quantities
  correctly vanish rather than lingering as phantom balance.
- **Dust / asset-bearing outputs below dust threshold.** Ensure coin selection for plain
  DGB spends can never select an asset-bearing UTXO. This is the single most dangerous
  interaction in the codebase — an asset-bearing output spent as ordinary DGB destroys
  the asset. If a hard exclusion filter does not already exist in coin selection, add it
  as part of this work regardless of scope.

---

## 6. Acceptance criteria

1. Reproduce the failure: a test that issues supply 10, sends 1, and asserts the wallet
   reports 9. It must fail before the change and pass after.
2. Balances agree with the DigiScope DigiAsset explorer for the same asset ID across:
   issuance, single transfer, multi-transfer, transfer to self, and a full send (balance
   → 0).
3. Balance is recomputed correctly after a forced resync from scratch, with no divergence
   from the pre-resync value.
4. Simulated out-of-order and duplicate block delivery produces no drift.
5. Coin selection provably cannot select an asset-bearing UTXO for a plain DGB spend.
6. Balance resolution failure surfaces as an explicit unknown/pending state in the UI,
   never as a silently wrong number.

---

## 7. Out of scope

- Marketplace integration (`assets.digistamp.co`) — separate, gated on this landing.
- Asset issuance flow changes.
- Any change to the BIP158 filter sync layer itself.
