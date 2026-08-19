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
| BIP 37 | Connection Bloom filtering | **Removed** as a data path — never loaded (see §3); C code retained pending X.0.0 deletion | `native/…/BRBloomFilter.{c,h}` (dead on the app path) |
| BIP 39 | Mnemonic code for seeds | Implemented | `native/…/BRBIP39Mnemonic.{c,h}` + `BRBIP39WordsEn.h` |
| BIP 44 | Multi-account hierarchy | Partial — single account (`account'=0`); multi-account is Phase 4 | `native/…/BRBIP32Sequence.c` |
| BIP 49 | Derivation for P2WPKH-nested-in-P2SH | Not applicable — we use native SegWit (BIP 84) | — |
| BIP 84 | Derivation for P2WPKH (native SegWit) | Implemented (v3.4.0+) | `native/…/BRBIP32Sequence.c` (see detail §2) |
| BIP 86 | Derivation for single-key P2TR | Implemented — receive only (`m/86'/20'/0'`); see §7 | `native/…/BRBIP32Sequence.c`, `app/…/ui/wallet/ReceiveScreen.kt` |
| BIP 111 | `NODE_BLOOM` service bit advertisement | Implemented (advertised, not yet enforced on selection) | `native/…/BRPeer.h` |
| BIP 125 | Opt-in Replace-by-Fee | Not started | Phase 3 |
| BIP 141 | Segregated Witness | Implemented (via C core) | `native/…/BRTransaction.c`, `BRAddress.c` |
| BIP 143 | Signature hash for SegWit | Implemented | `native/…/BRTransaction.c` |
| BIP 157 | Client-side block filtering (P2P) | **Implemented — the wallet's sole sync path** (see §4) | `native/…/BRCompactFilterChain.{c,h}`, `native/…/BRPeer.c` (cfheaders/cfilter handlers) |
| BIP 158 | Compact Block Filters for light clients | **Implemented** — GCS filter decoder (see §4) | `native/…/BRGCSFilter.{c,h}` |
| BIP 173 | Bech32 encoding | Implemented | `native/…/BRBech32.{c,h}` |
| BIP 174 | Partially Signed Bitcoin Transactions (PSBT) | **Planned — Phase 3** (not implemented; no code exists yet — see §6) | — |
| BIP 340 | Schnorr Signatures | Implemented | `native/…/BRKey.c` (`BRKeySchnorrSign`), `native/…/secp256k1` (schnorrsig module) |
| BIP 341 / 342 | Taproot / Tapscript | **Partial** — BIP 341 key-path (BIP 86) receive + signing shipped; no BIP 342 tapscript, no general Taproot spend UX (see §7) | `native/…/BRTransaction.c`, `native/…/BRKey.c`, `native/…/BRAddress.c` |
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

### §3 — BIP 37 (removed data path)

Bloom filtering was the wallet's SPV data layer through the v3.4–
v3.10.4 line. It was removed as a data path across the v3.10.5–
v3.10.15 releases when the wallet moved to compact-filters-only, and
the Sync Mode UI toggle was removed with it.

Removal is enforced at two layers:

- Kotlin: `syncModeFor` (`core/…/settings/CustomNode.kt`)
  unconditionally returns `COMPACT_FILTERS_ONLY`. The stored
  `sync_mode` pref is ignored; the enum members
  (`BLOOM_ONLY`, `BOTH`) are retained only for call-site compatibility.
- Native: `_BRPeerManagerLoadBloomFilter` (`native/…/BRPeerManager.c`)
  early-returns when `syncMode == BR_SYNC_MODE_COMPACT_FILTERS_ONLY`,
  so `BRPeerSendFilterload` is never reached and the address set never
  leaves the device via a bloom filterload.

There is no longer a bloom fallback or a 120s bloom watchdog — the old
"compact when peers advertise 0x40, bloom otherwise" transitional
scheme is gone. The C bloom implementation (`BRBloomFilter.{c,h}` and
the `_BRPeerManagerLoadBloomFilter` code path) still compiles but is
dead on the app path; physical deletion is deferred to the next major
(X.0.0), which is the trigger reserved for removing the legacy bloom
code.

### §4 — BIP 157 / 158 (shipped; sole sync path)

Compact block filters have been default-on since v3.5.39 and are the
wallet's only sync path since the bloom removal in the v3.10.x line.
On the compact-filter path the wallet's address set never leaves the
device. Implementation:

- GCS filter decoder in `native/…/BRGCSFilter.{c,h}`; the
  `getcfheaders`/`cfheaders` and `getcfilters`/`cfilter` wire handlers
  live in `native/…/BRPeer.c`, and the filter-header chain is persisted
  by `native/…/BRCompactFilterChain.{c,h}` (`dgb_sync_data` →
  `saved_filter_headers`). `cf_birth_height` (`dgb_settings`) bounds
  the scan.
- Filter-header trust is continuity-anchored (trust-on-first-use): the
  chain extends from a stored anchor header, and a run of peers that
  disagree with our tip is recorded rather than banned (an honest
  majority disagreeing means our chain is the outlier).
- Hardcoded operator-generated filter-header checkpoints ship in
  `native/…/BRCompactFilterCheckpoints.h` (476 headers at 50,000-block
  spacing, DGB mainnet). As of v3.10.27 they are cross-checked in
  **OBSERVE-ONLY mode** (`BRPeerManager.c`, R1 of the Neutrino review):
  each in-range checkpoint is compared against the wallet's own
  computed header and logged as MATCH/MISMATCH, but never rejected or
  banned. **Superseded on this branch:** enforcement is now ACTIVE — a
  checkpoint-crossing batch is validated *before* commit, and a mismatch
  rejects the batch and bans the peer, so a divergent header is never
  committed. A checkpoint-confirmed chain also vetoes the re-anchor path,
  closing the single-peer-liar hole. This is Phase **1** remainder (the
  sovereign data layer), not Phase 2 as this section previously said.
  The claim it earns is bounded: *the filter chain cannot lie to you
  below the last checkpoint* (currently height 23,800,000). Blocks above
  it remain TOFU + continuity + quorum — tip trust is the oracle-bootstrap
  / own-node track.
- Peer-quorum verification exists for the continuity re-anchor and now
  requires an **agreeing majority with a floor of 3** distinct disagreers
  (was K=2 any-disagree, which could false-fire on an honest reorg). A
  `getcfcheckpt` wire message exists (`BRPeerSendGetCFCheckpt`) but has no
  callers — it is deliberately NOT driven: peer-supplied anchors are
  Sybil-bait without a pin to check them against.
- If filter peers stall, the session does not fall back to bloom (that
  path was removed); it keeps retrying compact-filter peers.

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

### §7 — BIP 340 / 341 / 342 (Taproot, partial)

What is shipped:

- **BIP 340 (Schnorr):** fully implemented. The vendored secp256k1
  amalgamation is built with the `schnorrsig` + `extrakeys` modules,
  and `BRKeySchnorrSign` / `BRKeyTaggedHash` (`native/…/BRKey.c`) sign
  BIP-340 messages. Covered by the `bip340_kat` host known-answer test.
- **BIP 341 key-path (via BIP 86):** implemented and KAT-tested. The
  C core computes the BIP-341 key-path sighash
  (`_BRTransactionTaprootSighash`, `native/…/BRTransaction.c`), applies
  the BIP-86 taptweak and Schnorr-signs it (`BRKeyTaprootSchnorrSign`,
  `BRKeyTaprootOutputKey`), and recognizes/spends witness-v1 key-path
  inputs by matching the output key X(Q). Host KATs:
  `bip341_sighash_kat`, `bip341_sign_kat`, `bip341_signtx_kat`,
  `taproot_addr_kat`, `bech32m_kat`, `bip86_derivation_kat`,
  `bip86_privkey_kat`.
- **P2TR receive (UI):** shipped in v3.10.0. The Receive screen exposes
  a "Taproot (dgb1p…)" address derived at `m/86'/20'/0'`
  (`app/…/ui/wallet/ReceiveScreen.kt`, `NativeBridge.getReceiveAddress`).
- DigiDollar (a Taproot construct) reuses this key-path signer; its
  address codec and sighash paths carry their own KATs
  (`digidollar_addr_encode_kat`, `digidollar_send_kat`, etc.).

What is NOT implemented (why this is Partial):

- **No BIP 342 (Tapscript):** there is no tapscript execution,
  `OP_CHECKSIGADD`, tapleaf/control-block handling, or script-path
  spend. Only single-key key-path Taproot is supported.
- **No general Taproot spend UX:** the Send screen has no Taproot
  spend flow. Key-path signing exists in the C core (and is exercised
  by DigiDollar), but sweeping/spending arbitrary P2TR from the wallet
  UI is not wired up.

## How this doc is kept honest

Every time a BIP's status moves between Planned / Partial /
Implemented, this file and the relevant phase in `ROADMAP.md` are
updated in the same commit. A row flipped to Implemented should have
file:line citations or a reference to the module; a PR without them
hasn't actually shipped the thing.
