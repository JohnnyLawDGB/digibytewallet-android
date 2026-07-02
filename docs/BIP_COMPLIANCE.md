# BIP Compliance Matrix

Every BIP (and SLIP) this wallet is affected by, with current status
and file references. Kept in sync with `ROADMAP.md` phases.

## Matrix

| BIP / SLIP | Title | Status | Location |
|------------|-------|--------|----------|
| BIP 11 | M-of-N Standard Transactions | Planned (Phase 3 — multisig) | — |
| BIP 13 | P2SH addresses | Implemented | `native/…/BRAddress.{c,h}` |
| BIP 16 | P2SH evaluation | Implemented (via C core) | `native/…/BRTransaction.c` |
| BIP 21 | URI scheme (`digibyte:…`) | Implemented | `core/…/model/DigiByteUri.kt` |
| BIP 22 / 23 / 152 | Block template / Compact blocks | Not applicable (wallet, not miner/node) | — |
| BIP 32 | Hierarchical Deterministic Wallets | Implemented (non-standard variant — see detail §1) | `native/…/BRBIP32Sequence.{c,h}` |
| BIP 37 | Connection Bloom filtering | Implemented (current SPV data layer) | `native/…/BRBloomFilter.{c,h}`, `native/…/BRPeer.c`, `native/…/BRPeerManager.c` |
| BIP 39 | Mnemonic code for seeds | Implemented | `native/…/BRBIP39Mnemonic.{c,h}` + `BRBIP39WordsEn.h` |
| BIP 44 | Multi-account hierarchy | Partial — single account (`account'=0`); multi-account is Phase 4 | `native/…/BRBIP32Sequence.c` |
| BIP 49 | Derivation for P2WPKH-nested-in-P2SH | Not applicable — we use native SegWit (BIP 84) | — |
| BIP 84 | Derivation for P2WPKH (native SegWit) | Implemented (v3.4.0+) | `native/…/BRBIP32Sequence.c` (see detail §2) |
| BIP 111 | `NODE_BLOOM` service bit advertisement | Implemented (advertised, not yet enforced on selection) | `native/…/BRPeer.h` |
| BIP 125 | Opt-in Replace-by-Fee | Not started | Phase 3 |
| BIP 141 | Segregated Witness | Implemented (via C core) | `native/…/BRTransaction.c`, `BRAddress.c` |
| BIP 143 | Signature hash for SegWit | Implemented | `native/…/BRTransaction.c` |
| BIP 157 | Client-side block filtering (P2P) | **Planned — Phase 1** | `docs/roadmap/phase1-issues.md` |
| BIP 158 | Compact Block Filters for light clients | **Planned — Phase 1** | `docs/roadmap/phase1-issues.md` |
| BIP 173 | Bech32 encoding | Implemented | `native/…/BRBech32.{c,h}` |
| BIP 174 | Partially Signed Bitcoin Transactions (PSBT) | Not started — Phase 3 foundation | Planned at `core/…/psbt/` |
| BIP 340 / 341 / 342 | Schnorr + Taproot | Not planned — DigiByte protocol support is upstream-gated | — |
| SLIP-0010 | Universal private key derivation | Not applicable (BIP 32 variant used directly) | — |
| SLIP-0044 | Registered coin types | Implemented (coin type 20 for DigiByte) | `native/…/BRBIP32Sequence.c` |

## Details per BIP

### §1 — BIP 32 (non-standard variant)

This wallet's C core inherits a deviation from strict BIP 32 for the
pre-BIP84 wallet path: the HMAC-SHA512 key string for master key
generation is `"DigiByte seed"` instead of the BIP 32 canonical
`"Bitcoin seed"`. The derivation path is also `m/0'/chain/index`
instead of `m/44'/coin'/account'/chain/index`.

This is load-bearing legacy: every pre-v3.4.0 wallet was created with
this variant. Migrating to standard BIP 32 without a dual-scan recovery
path would silently lose funds for users restoring from seed. See
`docs/derivation/LEGACY_DERIVATION.md` for the full specification with
citations, test vectors, and a "what would break if you fixed this"
section.

From v3.4.0 onward, new wallets derive via standard BIP 32 +
BIP 84 (see §2). Recovery from a pre-v3.4.0 seed goes through the
legacy path; recovery from a v3.4.0+ seed uses BIP 84. Dual-scan on
recovery determines which is in use and sets the wallet's
`hasLegacyKey` flag accordingly.

### §2 — BIP 84 (native SegWit)

New wallets (v3.4.0+) derive at `m/84'/20'/0'/chain/index` with
standard `"Bitcoin seed"` HMAC key. This yields addresses compatible
with Ian Coleman's BIP39 tool, Electrum, and any other BIP 84–aware
wallet given the same seed.

Address encoding: bech32 (BIP 173) with HRP `dgb`, producing
`dgb1q…` addresses for external/receive and `dgb1q…` for change.
Legacy P2PKH (`D…`) remains the selectable "legacy" format on the
Receive screen but isn't the default.

### §3 — BIP 37 (current SPV, being phased out)

Currently the wallet's data layer. On each peer connection,
`BRPeerManager.c` constructs a bloom filter from the wallet's address
set and calls `BRPeerSendFilterload`. Peers respond with merkleblocks
and relevant transactions.

Known privacy limitations (see `THREAT_MODEL.md` residual risk 1):
the bloom filter probabilistically reveals the wallet's address set
to every peer it connects to. The Phase 1 migration to BIP 157/158
closes this leak; BIP 37 remains implemented as a fallback during
network rollout and will be retirement-flagged once the
`NODE_COMPACT_FILTERS` (0x40) peer population is healthy.

### §4 — BIP 157 / 158 (planned Phase 1)

Target: the wallet becomes the first mobile SPV client for
DigiByte on compact block filters. Design decisions encoded in
`docs/roadmap/phase1-issues.md`:

- GCS decoder ported verbatim from Bitcoin Core v26.2 (inherited in
  DGB Core 8.26) rather than reimplemented from scratch.
- 10,000-block cadence for shipped filter-header checkpoint
  bootstrap hints (vs BIP 157's 1000-block native `cfcheckpt`
  cadence).
- Runtime peer-quorum verification of all filter-header values,
  following the neutrino model. Shipped checkpoints are detected as
  wrong via this quorum; they are bootstrap hints, not authority.
- Dual-mode fallback: compact when ≥ `PEER_MAX_CONNECTIONS/2` peers
  advertise 0x40, bloom otherwise, transitional only.

See `docs/roadmap/phase1-issues.md` for the full 20-issue breakdown.

### §5 — BIP 21 (payment URIs)

`core/…/model/DigiByteUri.kt` parses `digibyte:<address>` URIs with
the standard query parameters: `amount` (DGB, not satoshis),
`label`, `message`, `r` (not honored — we do not follow BIP 70
payment-request URLs for sovereignty reasons; see `ROADMAP.md`
anti-patterns).

QR scanner handles both raw DigiByte addresses and `digibyte:` URIs;
the receive screen generates `digibyte:` URIs in QR form when the
user has entered an amount.

### §6 — BIP 174 (PSBT, planned Phase 3)

Foundation for multisig, watch-only wallets with external signing,
and hardware wallet integration (Coldcard QR, future Trezor/Ledger).
Must land before multisig ships — see `ROADMAP.md` Phase 3 ordering.

Implementation plan:
- PSBT codec (encode, decode, validate) at `core/…/psbt/`.
- PSBT-aware signing flow in the Kotlin layer; the C core signs a
  single input at a time, Kotlin orchestrates the combine/finalize
  steps.
- QR transport for the typical air-gapped signer workflow.

## How this doc is kept honest

Every time a BIP's status moves between Planned / Partial /
Implemented, this file and the relevant phase in `ROADMAP.md` are
updated in the same commit. A row flipped to Implemented should have
file:line citations or a reference to the module; a PR without them
hasn't actually shipped the thing.
