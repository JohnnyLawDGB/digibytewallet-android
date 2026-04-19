# DigiByte Wallet — Roadmap

Current version: **v3.5.25** (April 2026). This roadmap supersedes the
feature-ordered roadmap previously in this file. It is reorganized around
what actually makes this wallet different from a BlueWallet-style SPV client:
**no trusted third party in the data path**.

## Principles, in priority order

1. **Sovereignty first.** Anything that removes a trusted intermediary or
   hardens the local trust model comes before feature breadth. Compact
   block filters (BIP 157/158), hardware-sealed key material, peer
   diversity beyond author-operated infrastructure, and Tor as a first-class
   (not opt-in-afterthought) transport are the work this wallet exists to do.
2. **Legibility second.** The wallet is the kind of software where the
   threat model must be stated in prose a hostile reviewer can audit.
   `ARCHITECTURE.md`, `THREAT_MODEL.md`, a BIP compliance matrix, and
   process-flow docs are treated as deliverables, not documentation. They
   land *with* the code they describe, not after.
3. **Feature velocity third.** Multisig/PSBT, Digi-ID polish, payment
   requests, coin control, watch-only, and contacts are sequenced so they
   compose cleanly on top of the sovereign data layer — not against it. A
   feature that would require a trusted backend to ship quickly doesn't
   get shipped.

## Current state — honest summary

**What is sovereign today:**
- Local key custody. Seed sealed with AES-256-GCM via a hardware-backed
  Android Keystore key (`KeyStoreManager.kt:44–51`, `core/…/security/`).
- SPV block validation. All transaction validity and balance computation
  happens on-device from block headers and merkle proofs; nothing is
  fetched from an explorer.
- Recovery. BIP39 mnemonic, in-app seed backup + verify, dual-scan
  recovery covers the legacy `m/0H` tree for pre-v3.4.0 wallets.
- Tor support. Real kmp-tor exec-mode integration with SOCKS5 into the
  C core; peer traffic, bloom seeder fetch, and HTTP calls route through
  it when enabled.

**What is not sovereign today, and this roadmap is built around:**
- **The peer-discovery layer is a soft trusted third party.**
  `api.digiscope.me/api/peers/bloom` is operated by the wallet author
  (`SyncService.kt:524`). Every cold start fetches the peer list from
  it, with hourly cached fallback. The seeder itself is narrowly
  scoped — it runs a DGB node used only for peer discovery; it does
  not serve blocks, filters, or any chain data. So the trust surface
  is *which* peers the wallet talks to, not *what* the chain looks
  like. That still matters: a compromised seeder could eclipse the
  wallet onto hostile peers that surveil the wallet's transaction set
  via bloom filter fingerprints. Phase 1 demotes this from a required
  bootstrap source to one of several.
- **The data layer leaks wallet state.** BIP 37 bloom filters
  (`BRPeer.c:1520`, `BRPeerManager.c:328–399`) are sent in plaintext to
  every peer the wallet connects to. With the default 0.005 false-positive
  rate, a wallet with 10k addresses probabilistically discloses ~50
  addresses per scan. Merkleblock responses further reveal the wallet's
  exact transaction set — even over Tor, the content leak remains.
- **Key sealing is PIN-gated at the application layer, not at the
  Keystore**. `setUserAuthenticationRequired` is deliberately false
  (`KeyStoreManager.kt:37–43`) because enabling it crashes on API 28/33/35
  in inconsistent ways. This was the right call at the time — but it
  means a compromised app process can decrypt the seed without the
  device being unlocked. PIN brute-force also has no rate-limit.
- **Digi-ID signs with the first wallet address** (`m/44'/20'/0'/0/0`),
  not an isolated key subtree. Digi-ID compromise = wallet compromise.
- **Tor silently falls back to clearnet on failure** (`SyncService.kt:106–124`),
  with no user-visible warning.

The gap between "sovereign for signing" and "sovereign for data" is the
central thing this roadmap closes.

## Anti-patterns we will not ship

Some shortcuts look like feature velocity but would dismantle the trust
model that makes the wallet worth using. They're named here so they
don't sneak back in during feature sprints.

- **Explorer REST for balance or history.** The moment the wallet hits
  an Insight/Blockbook/Esplora endpoint for anything a user can see in
  the UI, we've ceded the sovereignty claim. This includes "fast-path"
  balance on cold start.
- **Push notifications for incoming transactions.** Any implementation
  requires disclosing addresses to a server. Hard no.
- **Cloud seed backup**, even encrypted. Until there's a formally
  reviewed threshold model that doesn't leak metadata about wallet
  existence and usage, this is out of scope.
- **"Just ask the seeder if sync is slow."** The seeder is already a
  trust concession for bootstrap; turning it into a sync progress
  accelerator would be observably worse.
- **Explorer-backed transaction detail screens** (e.g., fee market
  context, confirmation ETAs). All context shown for a tx must be
  derivable from the data the SPV client already has.
- **Price feeds coupled to wallet state.** Price is cosmetic and may be
  fetched from CoinGecko/Binance — but never with a request body that
  could correlate to wallet activity (e.g., don't ask for quotes for the
  exact DGB amount being sent).
- **Hub-as-data-layer.** The DigiScope Hub is a pseudonymous chat / forum
  surface. It must not become a side-channel for transaction data or
  address metadata.
- **BIP 21 URIs with embedded server metadata** (callbacks to check
  invoice status, etc.). Keep payment URIs pure on-chain.

## Multisig placement: Phase 3, deliberately

Multisig + PSBT belongs to feature velocity, not sovereignty. The reason
to sequence it *after* BIP 157/158 is that multisig compounds the privacy
problem of bloom-filter SPV. Every co-signer's device independently
connects to peers and advertises its portion of the multisig address set
via `filterload`. With three co-signers, the wallet set is probabilistically
discoverable to any peer any of them connects to — and correlation across
devices narrows it dramatically.

On a compact-filter data layer, each co-signer downloads filters without
revealing which addresses they care about. Multisig then inherits the
privacy property users reasonably expect from it, rather than undermining
it. Shipping multisig on the current bloom layer would bake in a privacy
regression that's hard to undo.

## Phase summary

| Phase | Theme | Rough size | Ships with |
|-------|-------|-----------|------------|
| 0 | Legibility prerequisite | M | `ARCHITECTURE.md`, `THREAT_MODEL.md`, BIP matrix, process flows |
| 1 | Sovereign data layer | L | BIP 157/158 client, peer selection, bloom deprecation path |
| 2 | Key & trust hardening | M | Keystore auth binding, PIN rate-limit, Digi-ID isolation, Tor default-on |
| 3 | Feature velocity on sovereign layer | L | PSBT, multisig, watch-only, coin control, RBF, paper sweep |
| 4 | Distribution + hardware | M | Play Store, F-Droid, Coldcard QR, NFC |

---

## Phase 0 — Legibility prerequisite

**Why this now.** We cannot honestly describe what BIP 157/158 changes
if we have not first stated what the current trust model actually is.
Every subsequent phase's rationale refers back to claims in these docs.
Getting them into the repo first also forces the seeder-as-third-party
admission to be public — a prerequisite for users' informed consent
while Phase 1 is in flight.

**Deliverables**

- `docs/ARCHITECTURE.md` — module boundaries (`app/`, `core/`, `native/`),
  data flow for send/receive/sync, the boundary between what the C core
  owns and what the Kotlin layer owns, where state is persisted.
- `docs/THREAT_MODEL.md` — assets, adversaries (casual observer, peer,
  network observer, malicious peer, compromised seeder, device-theft
  with PIN, device-theft with biometric), current mitigations, known
  residual risks. Named explicitly: the bloom seeder as a trusted third
  party, the PIN rate-limit gap, and the Tor silent-fallback behavior.
- `docs/BIP_COMPLIANCE.md` — matrix of BIPs touched (39, 32, 44, 84, 21,
  111, 37, 157, 158, 174, 152 if ever, 32 test vectors), with status:
  Implemented / Partial / Planned / Not applicable, and file:line
  citations into the submodule or Kotlin layer for the implemented ones.
- `docs/PROCESS_FLOWS.md` — diagrammed flows for
  "create new wallet", "recover from seed", "send transaction",
  "receive and confirm", "upgrade path". Each flow cites the specific
  functions in the C core and Kotlin that execute each step.

**Files that change:** only new files under `docs/`. No code churn.

**Core-side work required:** none.

**Effort:** M (~1 week of focused writing). The facts to document are
already known from this session's audits — the work is articulation,
not research.

---

## Phase 1 — Sovereign data layer (BIP 157/158)

**Why this now.** This is the single change that most directly answers
"what's the point of this wallet." It removes the plaintext address
leakage of bloom filters, makes the per-peer correlation attack
infeasible, and — critically — relegates `api.digiscope.me` from a
soft *requirement* to one of several optional bootstrap sources. It is
the technical underpinning of every other sovereignty claim.

**Client-side deliverables (C core + bridge)**

- **GCS filter decoder** in the C submodule. SipHash + Golomb-Rice
  decoding. Estimated ~400 lines of new C, no external deps. Lives at
  `native/src/main/jni/digibytewallet-core/BRGCSFilter.{c,h}` (new).
- **BIP 157 message handlers.** `getcfilters`, `cfilter`, `getcfheaders`,
  `cfheaders`, `getcfcheckpt`, `cfcheckpt`. Wired into `BRPeer.c` and
  `BRPeerManager.c` alongside the existing handlers.
- **Filter header checkpoint array — as bootstrap hint, not authority.**
  Separate from the block checkpoint array at `BRChainParams.h:72–130`.
  Hardcoded checkpoints every **10,000 blocks** (≈83 KB APK impact vs
  830 KB at 1000-block cadence; verifying the intermediate 10 k headers
  against an anchor is milliseconds of SHA-256). Lives at
  `BRFilterHeaders.{c,h}` (new). The shipped values are treated as
  bootstrap hints only — at runtime, the client *always* cross-verifies
  against a peer-quorum `cfcheckpt` response. Mismatch means loud sync
  failure, not silent trust. This is the neutrino model; it makes a
  malicious shipped checkpoint detectable rather than authoritative.
- **Sync state machine.** Dual-mode: if at least `PEER_MAX_CONNECTIONS/2`
  connected peers advertise `NODE_COMPACT_FILTERS` (0x40), use compact
  filters exclusively and never send a `filterload`. Otherwise fall back
  to bloom. The fallback exists solely to avoid dead-ending users during
  network rollout — **the long-term goal is removing the bloom path
  entirely** once enough peers advertise 0x40.
- **Peer selection by service bits.** The current peer manager connects
  to any peer that handshakes. Extend the bloom-prioritization code
  (`BRPeerManager.c:1853` region) to prefer `NODE_COMPACT_FILTERS`
  candidates ahead of `NODE_BLOOM`.
- **Bloom seeder demotion.** The seeder at `api.digiscope.me` currently
  returns an opaque list of peers presumed to support bloom. Extend its
  output to include the advertised service bits for each peer, and
  extend the wallet's consumption (`SyncService.kt:490–520`) to classify
  them. Meanwhile, add two other bootstrap sources: a community-maintained
  seeder mesh (at least one endpoint not operated by the wallet author)
  and a richer hardcoded seed list curated by `NODE_COMPACT_FILTERS`
  support.

**Server-side / Core-side work**

- DigiByte Core 8.26 = Bitcoin Core v26.2 rebase. BIP 157/158 serving
  stack is fully present, including the `NODE_COMPACT_FILTERS`
  (0x40) service bit, P2P handlers (`net_processing.cpp:3399–3507`),
  and the `getblockfilter` / `getindexinfo` / `scanblocks` RPCs. No
  upstream Core PR needed. Nodes need `blockfilterindex=basic`
  + `peerblockfilters=1` in `digibyte.conf` and a restart. Real cost
  on DGB (extrapolated from BTC figures): ~6–12 GB of extra disk for
  the filter index, 4–12 hours to build from a synced node. Not
  compatible with `prune=<n>`.
- Enable those flags on the digiscope.me DGB node plus at least one
  other community-operated node (the existing bloom-seeder operators
  are the natural first targets since they already run 8.26 with
  `peerbloomfilters=1`). Measure real-world filter sizes over the DGB
  chain to tune the checkpoint cadence before the client-side
  checkpoint array is frozen.
- Extend the `dgb-bloom-seeder` on digiscope.me to track
  `NODE_COMPACT_FILTERS` (0x40) alongside `NODE_BLOOM` (0x04) in the
  peer-capability index it builds, and return service bits per peer
  in its JSON response. The wallet will use this to classify peers
  into "compact-filter-capable" vs "bloom-only" without having to
  re-probe every peer on cold start.

**Kotlin layer changes**

- `SyncService.kt` — adjust startup to dispatch into compact-filter
  sync when enough peers support 0x40.
- `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt` — add
  native methods for filter header chain progress, filter-match counts,
  and sync mode reporting (so the UI can distinguish "compact filter
  sync" from "bloom sync" from "no peers").
- `WalletScreen` — update the sync UI copy to reflect mode; when on
  bloom fallback, surface a non-scary banner ("bootstrap via bloom —
  your privacy is lower until enough peers support compact filters").

**Docs update alongside code**

- `ARCHITECTURE.md` — add data-layer section documenting both modes.
- `THREAT_MODEL.md` — update the peer/observer adversary rows to reflect
  the compact-filter mitigation and explicitly note what's now residual
  (e.g., filter-header source trust, block header chain trust).
- `BIP_COMPLIANCE.md` — flip 157/158 from Planned to Implemented.
- `PROCESS_FLOWS.md` — add a filter-sync flow diagram.

**Effort:** L. Realistically 4–6 weeks including test coverage and
mainnet verification. The GCS decoder and message handlers are the
straightforward part; the checkpoint-cadence tuning and the dual-mode
fallback's edge cases (what if peers drop mid-sync and we have to switch
modes mid-stream?) are where the time goes.

**Anti-pattern reminders specific to this phase**

- Do not ship a "I'll just run a filter-proxy server for mobile clients"
  shortcut. Filters must come from peers, full stop. Running our own
  filter-serving node is fine; making the wallet depend on it is not.
- Do not keep the bloom path warm "for speed" after compact filters are
  viable. The bloom path exists as a transitional safety net; it must
  be removable by config flag, and flagged off by default once the
  service-bit population crosses a threshold.

---

## Phase 2 — Key & trust hardening

**Why this now.** BIP 157/158 closes the data-layer leak. This phase
closes the key-layer and peer-trust gaps that the Phase 0 threat model
will have made legible. It's sized deliberately smaller than Phase 1
because each item is independent — they can land as separate merges.

**Deliverables**

- **Keystore user-auth binding, per-API.** Revisit `setUserAuthenticationRequired`
  with per-API-level probing. If it can be made to work on modern API
  levels (33+) without the crashes that forced it off, enable it there
  and keep the app-PIN gate as the sole mechanism on older APIs.
  `core/src/main/java/io/digibyte/core/security/KeyStoreManager.kt:37–43`.
- **PIN rate-limit.** Exponential backoff on failed attempts:
  3 attempts free, then 1/5/30/60 minute cooldowns, then optional
  "wipe on N failed attempts" policy behind a settings toggle.
  `core/src/main/java/io/digibyte/core/security/PinManager.kt:43–58`.
- **Digi-ID key isolation.** Derive Digi-ID signing from a distinct
  BIP44 subtree (e.g., `m/44'/20'/1'/0/0` for account 1, or a dedicated
  purpose code) so a Digi-ID signature never exposes main-wallet keys.
  Requires a one-time migration and a compatibility path for existing
  Digi-ID-linked accounts on `api.digiscope.me`.
  `core/src/main/java/io/digibyte/core/digiid/DigiIdManager.kt:49`,
  `native/src/main/jni/digibytewallet-core/BRBIP32Sequence.c:224–226`.
- **Tor default-on for new installs + visible clearnet-fallback
  banner.** Silent fallback becomes a warning surfaced in the main
  wallet screen. Users who explicitly disable Tor see no banner;
  users who had Tor fail see the warning and a retry action.
  `SyncService.kt:106–124`, Compose wallet screen.
- **Peer diversity widening.** Reduce hard dependency on the
  digiscope.me seeder by (a) increasing the hardcoded DNS seed list
  weight in cold-start peer discovery, (b) accepting a user-provided
  additional seeder URL in settings, (c) making the seeder fetch
  tolerate cold-start without a cached JSON. The goal is that an
  air-gapped fresh install can reach a good peer population with or
  without the author's infrastructure.

**Files that change:** `core/…/security/*`, `core/…/digiid/*`,
`app/…/service/SyncService.kt`, new settings screens, DigiScope backend
for Digi-ID migration (out-of-repo).

**Core-side work:** DigiScope backend (Hub) needs to accept the new
Digi-ID subtree address during the transition. Coordination but not
blocking — can be done as a rolling migration.

**Effort:** M. The Keystore auth-binding work is the longest item;
the rest are each a few days.

**Anti-pattern reminder:** don't paper over the silent Tor fallback by
disabling the fallback entirely. Users stuck in places Tor is blocked
need a path out of the app. The fix is *loud* fallback, not *no*
fallback.

---

## Phase 3 — Feature velocity on the sovereign layer

**Why this now.** With the data layer and trust layer fixed, new features
compose cleanly. PSBT, multisig, watch-only, and coin control all benefit
from compact filters — and shipping them earlier would have been costly
rework. Each item here is sized as an independent increment.

**Deliverables, in rough suggested order**

- **PSBT (BIP 174) foundation.** Read/decode/validate PSBT from QR or
  file, surface signing UI, produce a finalized PSBT suitable for
  broadcast. The PSBT codec is the foundation for multisig, watch-only
  with cold signer, and future hardware wallet integration. Lives in
  a new `core/src/main/java/io/digibyte/core/psbt/` package.
- **Watch-only wallets.** `xpub` or descriptor import, read-only address
  derivation, transaction history, ability to construct an *unsigned*
  PSBT that a cold signer (hardware wallet, air-gapped machine) can
  sign. Existing flow in `OnboardingViewModel` extended.
- **Multisig (2-of-3, N-of-M).** Script generation, descriptor-driven
  address derivation, PSBT round-trip between co-signers via QR. UI
  supports adding co-signer xpubs, viewing the unsigned tx, producing
  the partial signature for broadcast by another co-signer.
- **Coin control / UTXO management.** List UTXOs, freeze selected ones,
  manual-select inputs for a send. Compose on top of the existing
  `UtxoManager`. `core/src/main/java/io/digibyte/core/UtxoManager.kt`.
- **Replace-by-fee (RBF).** UI toggle on send, sets the nSequence on all
  inputs, and a "bump fee" action on pending txs in history.
- **Sweep paper wallet (WIF).** Scan a WIF private key, derive its
  address, query the SPV layer for UTXOs (now possible privately because
  of compact filters), construct a sweep tx to the wallet's first
  receive address.
- **Address book / labeled addresses.** Contacts-style surface for
  repeated sends; labels on received addresses. Kept local only — no
  cloud sync in this roadmap.
- **DigiAsset send completion.** The existing detection-only DigiAsset
  code (`core/…/asset/AssetManager.kt:44`) gets its send path finished.
  Design spec already exists at
  `docs/superpowers/specs/2026-04-03-digiasset-send-design.md`.

**Files that change:** many across `app/…/ui/` and `core/…/`. New
packages for PSBT, multisig, descriptors.

**Core-side work:** none per item — PSBT and multisig are fully defined
at the protocol level; we're implementing existing standards.

**Effort:** L overall. PSBT foundation alone is M; multisig on top is
M; the rest are each S. Sequence them as independent PRs so progress is
visible.

**Multisig deferred from Phase 2:** see rationale above — shipping
multisig on the bloom data layer would bake in a privacy regression.

---

## Phase 4 — Distribution + hardware

**Why this now.** Sovereignty and legibility are done; the app is
genuinely trustworthy; *now* we expand reach and external-device
support.

**Deliverables**

- **Google Play Store release.** Play review tends to reject
  self-custodial crypto wallets erratically; having the Phase 0
  threat model doc and a clean compliance matrix helps.
- **F-Droid submission.** Reproducible build verification already ~90%
  there; finish the metadata YAML and submit.
- **Coldcard via QR** (Phase 3's PSBT foundation is the prerequisite).
  Scan animated QR from Coldcard to import xpub, produce animated QR
  of unsigned PSBT, scan animated QR of signed PSBT.
- **NFC tap-to-pay.** Android's HCE APIs allow the wallet to present a
  payment request over NFC; other wallets with NFC readers can receive
  it. Read-side (tap-to-receive from a payment terminal) is more niche
  and can be deferred.
- **Multiple accounts / profiles.** Separate BIP44 account trees under
  the same seed, switchable from the main screen.

**Effort:** M. Each item is a week or two of work, mostly integration.

---

## Appendix — Feature inventory

Baseline against a modern self-custodial wallet. Status reflects
v3.5.11.

| Feature | Status | Notes |
|---------|--------|-------|
| Send (SPV broadcast) | Shipped | `TransactionBuilder.kt:39` |
| Receive (address + QR) | Shipped | `ReceiveScreen.kt` |
| Fee selection | Partial | Fixed tiers + custom; no network-derived or RBF |
| Address book | Not started | Phase 3 |
| BIP 21 payment URIs | Shipped | `DigiByteUri.kt:10` |
| QR scanning | Shipped | `QrScannerScreen.kt` |
| PIN lock | Shipped | Argon2id, but no rate-limit — Phase 2 |
| Biometric unlock | Shipped | UI-gate only; not seed-binding — Phase 2 |
| Seed backup | Shipped | `SeedDisplayScreen.kt`, `SeedVerifyScreen.kt` |
| Seed recovery | Shipped | BIP84 + legacy dual-scan |
| Tx history | Shipped | `WalletScreen.kt` |
| Tx detail screen | Shipped | `TransactionDetailScreen.kt` |
| Multi-network (mainnet/testnet) | Shipped | `digiTestnet` flavor |
| Regtest flavor | Not started | Low priority; Phase 4 if demanded |
| Digi-ID | Shipped | Key isolation pending — Phase 2 |
| Multisig | Not started | Phase 3 (deliberately) |
| PSBT (BIP 174) | Not started | Phase 3 foundation |
| NFC | Not started | Phase 4 |
| Watch-only (xpub) | Not started | Phase 3 |
| Coin control | Not started | Phase 3 |
| Sweep paper wallet | Not started | Phase 3 |
| DigiAsset send | Partial | Detection shipped; send stubbed |
| Hardware wallet (Coldcard QR) | Not started | Phase 4, PSBT-dependent |
| Hardware wallet (USB) | Out of scope | Android USB OTG fragility, QR preferred |
| CSV export | Not started | Phase 3 small increment |
| Multiple accounts | Not started | Phase 4 |
| Tor as transport | Shipped | Silent-fallback gap — Phase 2 |
| BIP 157/158 compact filters | **Not started** | **Phase 1 — the point of this roadmap** |
| Dandelion++ broadcast privacy | Not started | Future; depends on DigiByte Core 9.26 |

## Versioning

- **3.0.X** — patch (currently — bug fixes, sovereignty hardening
  increments that ship without UI-visible change).
- **3.X.0** — minor (Phase 2 hardening items, Phase 3 feature drops).
- **4.0.0** — major (BIP 157/158 lands — Phase 1 is significant
  enough to warrant the major bump).
- **4.X.0 / 5.0.0** — Phases 3 & 4.

## What this roadmap is not

- Not a sprint plan. Effort sizes are rough; dependencies are called out
  but dates are not.
- Not exhaustive. Minor UX fixes, translation updates, detekt hygiene,
  and dependency bumps happen continuously and don't belong here.
- Not a commitment to ship in this exact order. If a critical user-reported
  bug (like the send-history-vanishing bug v3.5.11 fixed) appears mid-phase,
  it takes priority.
