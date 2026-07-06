# DigiDollar fixtures — provenance

Copied from `dgb-support` repo, `packages/digidollar-js/test/fixtures/`,
commit `63621fdefefdb228ed1c447b212f6c9a9e6d3587` (2026-07).

These are `getrawtransaction`-verbose captures of transactions **built by
DigiByte Core v9.26.4 itself** on regtest (see dgb-support
`docs/discovery/regtest-oracle-findings.md`). The digidollar-js library was
differentially proven against them — its built transactions are
satoshi-for-satoshi identical to Core's. They therefore carry Core's
authority: byte-parity against these fixtures is this module's release gate
(ADR-0001).

| file | shape |
|---|---|
| `mint-tx.json` | Mint: $100 at tier 3 (6 months/350%), price 13,420 micro-USD, collateral 2,634,128,166,915 sats, P2WPKH-funded |
| `transfer-tx.json` | Transfer: $30 to recipient + $70 DigiDollar change |
| `redeem-mint-tx.json` | Mint at tier 0 (1 hour) — tier index encoded as empty CScriptNum push |
| `redeem-tx.json` | Redemption of that Mint: exact burn, **no OP_RETURN**, script-path spend |
| `spend-tx.json` | Ordinary (non-DigiDollar) P2WPKH spend, nVersion 2 — the negative control for envelope detection |

## Address-field caveat

The `address` fields (`dgbrt1p…`) are Core's **bech32m renderings of the raw
P2TR scriptPubKeys** as `decoderawtransaction` printed them — they are NOT
DigiDollar addresses. DigiDollar addresses are Base58Check over
`2-byte version || 32-byte FINAL tweaked output key`: `DD…` mainnet
{0x52,0x85}, `TD…` testnet {0xb1,0x29}, `RD…` regtest {0xa3,0xa4}. A decoded
DD-address key is already tweaked — **never re-tweak it** (fund-loss bug).
Tests must compare `scriptPubKey.hex`, never these address strings.

Do not edit these files. To regenerate, use dgb-support `scripts/regtest-stand.sh`.
