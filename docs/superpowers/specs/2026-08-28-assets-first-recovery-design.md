# Assets before DGB: deleting the fee reserve

**Status:** approved, implementing
**Supersedes:** the reserve half of the v4.0.68 DigiAsset recovery work

## The problem with the shipped order

v4.0.68 sweeps plain DGB first and moves DigiAssets second. Because the sweep would
otherwise take every coin the asset needs to pay its own transfer fee, `AssetFeeReserve`
holds some back — `DEFAULT_FEE_PER_ASSET` sats per asset, decided *before* anything knows
what the transfer will cost.

That constant was wrong when it shipped. It was 40,000 against a real cost of ~54,900–70,100,
and it was documented as "deliberately an over-estimate" while being an under-estimate. Fixing
it to 80,000 made it correct today and leaves it a number that has to be re-checked every time
the transaction shape, the fee rate, or the input-size assumption changes.

The reserve exists **only** because the sweep runs first. Nothing else needs it.

## The change

Move the assets, then sweep what is left.

1. Classify and partition once.
2. Plan the asset transfers against the **whole** plain-DGB set.
3. Broadcast them.
4. Sweep exactly the outpoints the transfers did not spend.

## Why this is not merely tidier

**An estimate becomes a fact.** Today the reserve guesses what the transfer will need before
the transfer exists. Assets-first, the plan is built first, so the set of spent outpoints is
known exactly. There is no quantity left to estimate, so there is no estimate left to get
wrong — the class of bug is removed rather than the instance fixed.

**A failed move no longer strands the asset.** Today, if the move fails after the sweep has
broadcast, the only DGB left is the reserve; if the reserve was mis-sized the asset cannot
move at all, and the user has to send funds back into a wallet they were leaving. Assets-first,
a failed move leaves the wallet untouched and the retry has everything available.

**The parent/descendant question is answered structurally.** The sweep spends confirmed
outpoints that the moves did not touch, so neither transaction is an ancestor of the other and
neither has unconfirmed ancestors. This holds today by accident of the reserve being disjoint;
here it holds because the sweep is *defined* as the complement of what the moves spent.

**One classification pass.** The move and the sweep each call `ForeignUtxoAssetClassifier`
today, and each fetches every parent transaction. The sequence classifies once.

## What is deleted

- `AssetFeeReserve` and `DEFAULT_FEE_PER_ASSET`
- `ReserveCoversTransferCostTest` — the seam it guarded no longer exists
- `rf_held_reserve`, `rf_held_reserve_body`, `rf_shortfall_title`, `rf_shortfall_body`,
  in all 13 languages
- the second `classify()` pass

## What replaces it

A set of outpoints the broadcast moves actually spent, and — for a move that FAILED — the
outpoints its plan named. The second set is still "held back", but it is held back because a
concrete plan asked for it, not because a constant guessed. The user is told the real figure.

## The cost

The sweep now depends on the moves' outcome, so the two become one operation rather than two
independent ones. That coupling is bought deliberately: it is what makes the sweep's input set
exact.

## User-facing shape

One action. The findings screen names the asset move **before** anything runs, because it is
irreversible:

> Recovering this wallet will also move 1 DigiAsset. Asset transfers are irreversible.

The result screen reports both outcomes, each asset named on failure as it is today.

## Invariants to gate

1. An outpoint spent by a move is never offered to the sweep.
2. A move that failed holds its planned inputs back from the sweep.
3. An asset-bearing outpoint is never swept as plain DGB. (Unchanged; still `SweepPartition`.)
4. The sweep and the moves spend disjoint input sets — the parent/descendant guarantee.
