# Fanning out fee outputs so every asset can move

**Status:** approved, implementing

## The problem

A DigiAsset moves in its own transaction, and two transactions cannot spend the same UTXO. So
moving *N* assets needs *N* spendable DGB outputs. The transfer's change goes to the DESTINATION
wallet, so it does not come back to fund the next move.

A wallet holding 50 assets and one DGB output therefore moves exactly one asset, and the only
remedy is sending DGB back into a wallet the user is walking away from — the failure the deleted
fee reserve existed to prevent, reappearing one level up. Confirmed as a common real shape:
holders with 50+ assets and a single UTXO.

Today's tests never hit it because they had three DGB outputs and at most two assets.

## Why not simply combine the assets

An asset's identity comes from the UTXOs a transaction spends, not from its OP_RETURN, so one
transaction *can* carry several assets. It does not solve this:

- **It does not fit.** An instruction is 3 flag bits + a 5-bit output index + an SFFC amount —
  about 2 bytes at best. After the 4-byte header the 80-byte OP_RETURN leaves ~38 instructions,
  and 50 inputs need 50. A second transaction would still be needed, and a second DGB output to
  fund it, which is the original problem.
- **It concentrates the loss.** Every asset lands on one output. Spend that output as plain DGB
  and they all die together instead of one at a time.
- **It couples the failures.** One malformed instruction fails every move in the batch.

## Why not group by asset type

Grouping several UTXOs of the same asset into one transfer is what the wallet's own send path
already does. The foreign path cannot: `ForeignAssetQuantity` reads *units*, not identity.
Resolving an assetId means walking provenance back to issuance — multi-hop, networked, and
unresolvable for wallets that cannot be fully traced.

## The change

Split the DGB first, then move every asset in parallel.

```
1 UTXO ──fan-out──► N fee outputs ──► N asset transfers ──► sweep the remainder
```

1. Classify and partition.
2. If asset outpoints outnumber spendable plain outputs, build and broadcast a **fan-out**: one
   transaction spending the plain DGB and paying *N* fee outputs back to the SOURCE wallet's own
   addresses, plus change.
3. Wait for it to confirm, then **re-scan**.
4. Move every asset, each in its own transaction.
5. Sweep whatever the moves did not spend.

## Why this shape

**Nothing is concentrated.** Each asset keeps its own transaction, so one bad instruction cannot
take the others down and no single output ever holds the whole portfolio.

**Nothing is deeply chained.** The moves are children of the fan-out, not of each other.

**No new protocol assumptions.** The fan-out is an ordinary DigiByte send that happens to have
many outputs.

**It is restartable.** The fan-out pays the source wallet's own addresses, so if the app dies
after it confirms, re-running recovery simply finds more plain outputs and proceeds. Nothing is
stranded and nothing is double-spent.

## Why we wait for confirmation

DigiByte inherits Bitcoin's default `limitdescendantcount=25`. Fifty unconfirmed children of one
parent would be rejected outright. Waiting (~15s on DGB) makes each move independent rather than
part of a mempool package, and removes any question of eviction ordering. The alternative — cap
each round at 25 — trades a short wait for a second funding round, which is the thing being fixed.

## Sizing

Derived from the transaction actually built, never a constant. That mistake has been made once
here already: `AssetFeeReserve` shipped a 40,000-sat per-asset guess against a real cost of
54,900–70,100, described in its own comment as "deliberately an over-estimate".

Each fee output must cover: the transfer fee for a 1-asset-input, 1-fee-input, 3-output
transaction, plus a non-dust change output, less the 6,000 sats the asset's own marker
contributes. Priced through `AssetFeeEstimator`, the same estimator the transfer itself uses, so
the two cannot drift apart.

For 50 assets that is roughly 3,050,000 sats of outputs plus a ~186,000-sat fan-out fee — about
0.033 DGB.

## When it cannot work

If the wallet's plain DGB is less than the fan-out needs, no arrangement helps. This must be
stated BEFORE anything is broadcast, with the real figure — not discovered after three assets
have moved and the money has run out.

## Invariants to gate

1. No fan-out when spendable outputs already meet or exceed the asset count.
2. Every fee output the fan-out creates is large enough to fund a transfer — asserted against
   `ForeignAssetTransferPlan`, not against a constant.
3. The fan-out never spends an asset-bearing outpoint.
4. A shortfall is reported with its figure before any broadcast.
5. Fan-out outputs are payable to addresses the FOREIGN seed controls, or the moves cannot spend
   them.
