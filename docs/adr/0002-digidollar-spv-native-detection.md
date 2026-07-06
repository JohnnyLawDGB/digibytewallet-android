# DigiDollar detection is SPV-native; only the Oracle price comes from an API

## Amendment (2026-07, upstream reconciliation)

Confirmed by upstream's implementation: `JohnnyLawDGB/digibytewallet-android` v3.9.0
ships exactly this shape — DigiDollar detection and balance computation in the C SPV
layer (nVersion + OP_RETURN parsing on matched transactions), no indexer. One delta from
the text below: upstream addresses DigiDollar outputs with **Base58Check DD…/TD…/RD…
addresses encoding the final tweaked output key** (never bech32m, and a decoded DD-address
key is used as-is — re-tweaking it is a fund-loss bug). Detection remains filter-based and
address-private either way.

## Context

The wallet must detect DigiDollar outputs it owns and compute DigiDollar balances and Positions.
The dgb-support browser wallet solved this with a server-side indexer (its ADR-0003), which
requires uploading wallet addresses to a server — exactly the privacy property this wallet's
BIP157/158 work exists to avoid. This repo's ROADMAP is sovereignty-first.

Protocol facts make a sovereign path possible: a Mint pays a DD-token output to the Owner key's
P2TR script (derivable in advance, therefore matchable by bloom/BIP158 filters); the matched
transaction's OP_RETURN metadata carries amount, Lock tier, and unlock height; the Collateral
outpoint is always vout 0 of the Mint transaction; incoming Transfers pay our P2TR directly.
Only the Oracle price, DCA multiplier, and softfork deployment status genuinely require a node
RPC — none of which reveal anything about the wallet.

## Decision

Detection and balance computation are SPV-native: the wallet's BIP86 P2TR scripts join the
bloom/BIP158 filter-element set, matched transactions are parsed locally (nVersion + OP_RETURN,
positional amount pairing), and Positions are tracked on-device. The only network dependency is
a read-only price/status API (`api.digiscope.me` pattern: cert-pinned, user-overridable
endpoint), backed by a DigiByte Core v9.26.4 node. Broadcast uses the existing SPV peer path
(including Dandelion++ when enabled).

## Considered options

- **Indexer-backed (dgb-support model).** Rejected: uploads the wallet's addresses to a server —
  a privacy regression the compact-filter work just eliminated. Faster to build, wrong direction.
- **Hybrid (SPV + indexer reconciliation).** Deferred: more surface now; can be added later as a
  repair path (the ChainReconciliationService pattern) if SPV detection proves flaky in the field.
- **SPV-native + price API (chosen).** No address data leaves the device; the API learns only
  that someone fetched a price.

## Consequences

- The C filter/matching layer must learn taproot: filter-element extraction, output ownership
  matching, and bech32m address handling all gain P2TR support. This benefits the wallet beyond
  DigiDollar (future BIP86 receive addresses).
- A wrong Oracle price from the API cannot steal funds but can cause bounded loss by
  over-collateralization (a low price inflates the computed Collateral and consensus accepts the
  overpayment). Mitigation: before any Mint, the Oracle price is cross-checked against the
  wallet's independent fiat price source and the Mint is blocked past a divergence threshold;
  the confirm screen shows both prices.
- DigiDollar balances are only as current as SPV sync; there is no server to ask for instant
  state. This is the same trade-off the DGB balance already makes.
