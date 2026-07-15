# DigiByte Wallet — Roadmap

Current version: **v3.10.26** (July 2026). This revision supersedes the
June 2026 roadmap, which had fallen behind the code in the good
direction: BIP 157/158 is no longer the goal of Phase 1 — it is the
*only* sync path, bloom having been excised from the wire entirely in
v3.10.5–3.10.15. The wallet the old roadmap was working toward is,
at the data layer, the wallet that now exists. This revision records
that, declares the major version it earned, and resequences what
remains around the two things that now matter most: **never stranding
a CF-only wallet** and **DigiDollar as a sovereign light client**.

## Principles, in priority order

1. **Sovereignty first.** Anything that removes a trusted intermediary or
   hardens the local trust model comes before feature breadth. The
   compact-filter-only data layer, hardware-sealed key material, peer
   diversity beyond author-operated infrastructure, and coercion
   resistance are the work this wallet exists to do.
2. **Legibility second.** The wallet is the kind of software where the
   threat model must be stated in prose a hostile reviewer can audit.
   `docs/ARCHITECTURE.md`, `docs/THREAT_MODEL.md`, `docs/BIP_COMPLIANCE.md`,
   and `docs/PROCESS_FLOWS.md` shipped with Phase 0 and are maintained
   *with* the code they describe. **This roadmap is itself a legibility
   deliverable** — a stale roadmap is a legibility bug, and the June
   revision had become one (it described `SyncMode.BOTH` as the default
   months after bloom was removed). Corollary adopted with this
   revision: the roadmap header version and the feature-inventory
   appendix are updated in the same PR as any release that changes them.
3. **Feature velocity third.** PSBT, watch-only, vault management,
   coin control, and contacts are sequenced so they compose cleanly on
   top of the sovereign data layer — not against it. A feature that
   would require a trusted backend to ship quickly doesn't get shipped.

## Current state — honest summary

**What is sovereign today:**

- **The data layer.** BIP 157/158 compact block filters are the only
  sync path. The bloom wire path (`filterload` and friends) was removed
  from the C core in v3.10.5–3.10.15; the Sync Mode setting is gone
  because there is nothing to choose. The wallet's address set cannot
  leave the device via the sync path — the code that could leak it no
  longer exists. Filter-chain integrity cross-checking against built-in
  checkpoints landed (observe-and-log) in v3.10.25.
- **Transaction-origin privacy.** Dandelion++ stem submission shipped
  v3.7.0 (opt-in) — seeder-tagged dandelion peers with random
  embargo/fluff fallback so delivery is never sacrificed.
- Local key custody. Seed sealed with AES-256-GCM via a hardware-backed
  Android Keystore key (`core/…/security/KeyStoreManager.kt`).
- SPV block validation. All transaction validity and balance computation
  happens on-device from block headers; nothing a user sees in the UI is
  fetched from an explorer. (`reconcile` remains an explicitly-labeled
  recovery/safety-net path, not a balance display path.)
- Recovery. BIP39 mnemonic with checksum validation at entry (v3.6.4),
  Universal Restore across every historical DigiByte derivation path
  with auto-sweep (v3.5.15), foreign-seed sweep (v3.9.0).
- Taproot. P2TR receive shipped v3.10.0; BIP341 sighash and DigiDollar
  address/send paths are KAT-tested in `native/src/test/host/`.
- **DigiDollar, testnet-complete.** Send/receive/balance wired end to
  end (`core/…/WalletManager.kt:325`, `NativeBridge.kt`, Send/Receive
  screens), live on testnet, inert on mainnet until softfork activation.

**What is not sovereign today, and this roadmap is built around:**

- **CF peer availability is the architectural residual.** Shipping
  v3.10.18 proved the dialer good enough on-device while making the
  real problem undeniable: CF-only correctly rejects the mostly
  bloom-off network majority, leaving a thin ~16-node filter-serving
  fleet that churns, and the manager can stall at 0 peers. This is an
  **infrastructure** problem, not a peer-selection one. See
  `docs/superpowers/specs/2026-07-11-cf-fleet-reliability-own-node-track.md`.
  **Explicit non-goal restated:** no more filter-peer-selection
  heuristics as the fix for slow CF sync. The fixes are the own-node
  track and oracle-operator CF enablement (Phase 1 remainder, below).
- **The peer-discovery layer is still a soft trusted third party.**
  `api.digiscope.me/api/peers` is operated by the wallet author
  (`app/…/service/SyncService.kt:1714`). Narrowly scoped — peer
  discovery only, no chain data — but a compromised seeder could still
  eclipse the wallet onto hostile peers. The oracle-bootstrap design
  (`docs/superpowers/specs/2026-07-08-oracle-bootstrap-peer-discovery.md`)
  demotes it from required to accelerant.
- **The price feed is a trusted third party.** The DGB/USD rate comes
  from CoinGecko → Binance (`core/…/PriceProvider.kt:130`) — external
  APIs on the default data path. Low-stakes (display-only; never touches
  consensus, balances, or spending) but still a third party the wallet
  trusts, and offline when both APIs are. The sovereign replacement —
  verifying the DigiDollar oracles' on-chain Schnorr price attestations —
  is already scaffolded (`OraclePriceProvider` / `PriceSource.ORACLE`)
  and scheduled in Phase 3, gated on oracle liveness.
- **PIN brute-force has no rate limit.** `core/…/security/PinManager.kt:43`
  verifies with no backoff, no attempt counter, no wipe policy. This is
  now the single cheapest high-value hardening item in the repo, and it
  gates the duress PIN (a decoy PIN is theater while the real PIN is
  free to brute-force).
- **Key sealing is PIN-gated at the application layer, not at the
  Keystore.** `setUserAuthenticationRequired` remains deliberately off
  (`core/…/security/KeyStoreManager.kt:37`) for API-level crash reasons.
  Right call at the time; still means a compromised app process can
  decrypt the seed without the device being unlocked.
- **Digi-ID signs with the first wallet address.**
  `core/…/digiid/DigiIdManager.kt:49` calls `signMessage(uri, 0)` —
  index 0 of the main account. Digi-ID compromise = wallet compromise,
  and (new consequence, see duress spec addendum) the Hub identity is
  cryptographically the wallet identity, which a duress session must
  therefore sever.
- **Tor is opt-in, OFF by default** (product decision 2026-07-02, stands).
  Routing works (`SafeSocks=0`, v3.7.5); in-app privacy is carried by
  BIP158 (address privacy, default) + Dandelion++ (tx-origin, opt-in);
  Orbot / system VPN remain the recommended IP-anonymity path. Residual
  open bug: no automatic clearnet fallback when a user-enabled Tor
  bootstrap fails — loud-fallback fix scheduled in Phase 2.

The gap the June roadmap was built around — "sovereign for signing but
not for data" — is closed. The gap this revision is built around is
**"sovereign but strandable"** (CF fleet thinness) and **"sovereign for
DGB but not yet for dollars"** (DigiDollar vault lifecycle).

## Anti-patterns we will not ship

Unchanged from the June revision, and they held — bloom was removed
rather than kept warm "for speed," which is exactly what this list was
for. Restated so they don't sneak back in during the DigiDollar sprint:

- **Explorer REST for balance or history** visible in the UI. Includes
  "fast-path" balance on cold start. (`reconcile` stays a labeled
  recovery tool, not a display path.)
- **Push notifications for incoming transactions.** Requires disclosing
  addresses to a server. Hard no.
- **Cloud seed backup**, even encrypted.
- **"Just ask the seeder."** The seeder is a bootstrap concession, not
  a data source, and the oracle-bootstrap track shrinks it further.
- **Explorer-backed transaction detail.** All context shown for a tx
  must be derivable from data the SPV client already has. (This rule is
  also what *admits* the multi-algo security dashboard — see Phase 3 —
  because algo bits and nBits are already in every validated header.)
- **Price feeds coupled to wallet state.** Cosmetic price is fine;
  request bodies that correlate to wallet activity are not. This rule
  gains teeth with DigiDollar: never fetch a quote for the exact
  collateral or mint amount in flight.
- **Hub-as-data-layer.** The Hub must not become a side-channel for
  transaction or address metadata.
- **DigiDollar-specific addition — no hosted vault dashboard in the
  data path.** Vault state (collateral, time-lock height, mint
  capacity) must be derived from the user's own filter-matched blocks
  and headers, not from a DigiScope API. DigiScope may *mirror* vault
  state for the web experience; the wallet must never *depend* on it.

## Phase summary

| Phase | Theme | Rough size | Status / ships with |
|-------|-------|-----------|---------------------|
| 0 | Legibility prerequisite | M | ✅ **Done** — `ARCHITECTURE.md`, `THREAT_MODEL.md`, `BIP_COMPLIANCE.md`, `PROCESS_FLOWS.md` in `docs/` |
| 1 | Sovereign data layer | L | ✅ Client shipped (v3.5.39), bloom **removed** (v3.10.x), own-node pairing flow shipped (Seq 1.1/1.2). 🚧 Remainder: oracle CF enablement + seeder demotion + `cfcheckpt` |
| 1.5 | **v4.0.0** | S | Declared when Phase 1 remainder lands — see Versioning |
| 2 | Key & trust hardening | M | 🚧 Resequenced: PIN rate-limit → duress PIN A → Keystore binding → Digi-ID isolation → loud Tor fallback |
| 3 | Feature velocity on the sovereign layer | L | 🚧 PSBT pulled forward as the **DigiDollar vault enabler**; security dashboard added; multisig stays last |
| 4 | Audit, distribution + hardware | M | 🚧 Third-party audit **gates** DigiDollar-mainnet-in-wallet and duress-PIN promotion; F-Droid before Play |

---

## Phase 0 — Legibility prerequisite ✅

Complete. `docs/ARCHITECTURE.md`, `docs/THREAT_MODEL.md`,
`docs/BIP_COMPLIANCE.md`, and `docs/PROCESS_FLOWS.md` exist and named
the seeder-as-third-party, PIN rate-limit gap, and Tor fallback
behavior in public before the fixes landed. Standing obligation (not a
phase): these documents update in the same PR as code that changes
their claims. Immediate debts under that rule: THREAT_MODEL's peer/
observer adversary rows must reflect bloom removal as *complete*
mitigation with filter-header-source trust as the named residual, and
BIP_COMPLIANCE must show 37 as **Removed** (not Implemented), 157/158
as Implemented-and-only, 341/342 as Partial (receive + DigiDollar
paths), 174 as Planned (Phase 3).

---

## Phase 1 — Sovereign data layer

**Client: shipped and hardened.** Native GCS decoder,
`cfheaders`/`cfilter` handlers, filter-header persistence and
continuity re-anchor, dead-socket peer floor, responsive-peer filter
download with failover, checkpoint cross-check groundwork (v3.10.25).
Bloom is gone from the wire. Historical rationale lives in the June
roadmap in git history; it does not need to be re-litigated here.

**Remainder — the strandability problem.** Three coordinated tracks,
already designed, now sequenced as the top of the whole roadmap because
every sovereignty claim is hollow if a fresh install can sit at 0 peers:

1. **Own-node track, Model C (decided). Pairing flow shipped (Seq
   1.1/1.2).** Tiered: the user's own DigiByte node as a pinned CF peer
   first (Model B semantics), with wallet↔node JSON-RPC (Model A) as a
   later tier for capabilities CF can't express. v3.10.1 shipped the
   primitive (Settings → Network Info → custom node); the first-class,
   one-screen "pair with your node" flow has now shipped on top of it:
   QR-scan a `dgbnode://host:port?net=&label=` URI, verify the node
   actually serves `NODE_COMPACT_FILTERS` (native CF-peer status
   accessor: SERVING / CONNECTING / dark), pin it (reserved dial slot,
   churn-eviction-exempt), and surface health on the main screen — with
   a loud banner and a "use public peers" escape hatch if a pinned node
   goes dark. Additive by default (own node prioritized, public fleet
   stays as backup); an opt-in **exclusive** mode talks to the paired
   node only. **Clearnet host:port only** — the `dgbnode://` grammar
   reserves an onion host but the parser rejects it today; Tor onion
   pairing is deferred to Sequence 2.5 (couples to the Tor
   no-clearnet-fallback gap). Spec:
   `docs/superpowers/specs/2026-07-14-own-node-first-class-pairing.md`
   (builds on `docs/superpowers/specs/2026-07-11-cf-fleet-reliability-own-node-track.md`).
2. **Oracle-bootstrap peer discovery.** Hardcode the multi-operator
   DigiDollar oracle-node set as CF bootstrap peers; let the pool bloom
   from `addr` gossip; demote `api.digiscope.me` to optional accelerant.
   Spec: `docs/superpowers/specs/2026-07-08-oracle-bootstrap-peer-discovery.md`.
   **Promoted to the DigiDollar critical path:** oracle operators
   enabling `blockfilterindex=1` + `peerblockfilters=1` is now a
   *launch prerequisite* for DigiDollar mainnet support in this wallet,
   not a nice-to-have — one operator checklist simultaneously fixes CF
   fleet thinness and gives the stablecoin's own infrastructure a
   second job. The two mainnet gates embedding the testnet-only CF
   exception must generalize (or operators run Path B) before
   activation; resolve the Path A/B open decision when the oracle
   roster is frozen.
3. **`cfcheckpt` checkpoint verification.** Filter headers are
   TOFU-plus-quorum today; v3.10.25's observe-and-log cross-check
   graduates to actively rejecting a misbehaving filter chain. This is
   the real residual named in the oracle-bootstrap spec, and it should
   land before v4.0.0 so the major version's trust story is clean.

**Effort:** M remaining (the L was the client, and it's done).

---

## Phase 1.5 — Declare v4.0.0

The June roadmap named its own trigger: *"bloom excision is the
wire-path removal that finally triggers a major (X.0.0) bump."* The
trigger fired in v3.10.5–3.10.15; the bump didn't. That's now a
sequencing choice rather than an oversight: **v4.0.0 = compact-filters-
only AND never stranded.** Cut it when Phase 1's remainder lands (own-
node pairing + oracle bootstrap + active checkpoint rejection), so the
release that carries the headline claim — *the sync path that could
leak your addresses does not exist in this codebase, and losing our
infrastructure cannot strand you* — is true in both halves. v4.0.0 is
also the natural release vehicle for the F-Droid submission and the
digiscope.me `/wallet` page relaunch.

---

## Phase 2 — Key & trust hardening

Resequenced. The June ordering listed these as independent; they are
not quite — the duress PIN's threat model leans on the rate limit, and
the duress session's identity handling leans on Digi-ID isolation
being at least designed. New order:

1. **PIN rate-limit (first, small, unblocking).** Exponential backoff:
   3 attempts free, then 1/5/30/60-minute cooldowns, optional
   wipe-after-N behind a settings toggle. `core/…/security/PinManager.kt:43`.
   A duress PIN shipped before this is a decoy door on a house with no
   lock; this lands first.
2. **Duress / decoy PIN — Phase A (wallet protection).** As designed in
   `docs/superpowers/specs/2026-07-12-duress-pin-design.md`: second PIN
   opens decoy account 1' (`m/84'/20'/1'`, `m/86'/20'/1'`) pre-funded
   ~5%; main funds/DigiAssets/DigiDollar and seed unreachable in
   session; biometrics auto-disabled; no UI tell. **Two spec addenda
   adopted with this revision:**
   - **A duress session must sever the Hub/Digi-ID identity.** Because
     Digi-ID currently signs with the main wallet's first address
     (`DigiIdManager.kt:49`), a decoy session that can still one-tap
     into DigiScope authenticates as the *real* user — real handle,
     chat history, education-portal earnings — collapsing the "small
     plausible wallet" story. Duress Phase A ships with Hub/Digi-ID
     either disabled-with-plausible-cover (e.g., logged-out state) or
     swapped to a decoy identity derived from account 1'. Coordinate
     the derivation namespace with item 4 below.
   - **The covert OP_RETURN alert (Phase B) is downgraded to
     research.** An extra OP_RETURN on duress sends is unreadable
     (keyed HMAC) but not *invisible* — a coercer inspecting the tx
     preview or the broadcast sees an output shape this wallet's normal
     sends don't produce, and the mechanism is documented in this
     open-source repo. The app-ping alert path ships as Phase B; the
     on-chain beacon needs a design that makes duress sends
     shape-identical to normal sends (or explicit acceptance that the
     beacon trades deniability for notification) before it ships.
3. **Keystore user-auth binding, per-API.** Revisit
   `setUserAuthenticationRequired` with per-API probing; enable on
   modern APIs if stable, keep the app-PIN as sole gate on older ones.
   `core/…/security/KeyStoreManager.kt:37`.
4. **Digi-ID key isolation.** Distinct subtree (dedicated purpose code
   or account) so a Digi-ID signature never exposes main-wallet keys;
   one-time migration with a compatibility window on `api.digiscope.me`.
   Namespace rule (from the duress spec, now binding): the decoy owns
   purpose 84'/86' account 1'; Digi-ID isolation must not collide, and
   any future subtree claims get recorded in `docs/derivation/`.
   `core/…/digiid/DigiIdManager.kt:49`.
5. **Loud Tor fallback.** Tor stays opt-in/OFF by default (2026-07-02
   decision stands), but a user-enabled Tor that fails bootstrap must
   fall back to clearnet *loudly* — main-screen banner + retry — never
   sit at 0 peers, never fall back silently.

**Effort:** M. Items 1 and 5 are days; 2 is the largest; 3 is the
riskiest (device-matrix testing); 4 is coordination-heavy.

---

## Phase 3 — Feature velocity on the sovereign layer

Reframed. The June roadmap sequenced PSBT as generic feature velocity
with multisig as the motivating consumer. The motivating consumer is
now **DigiDollar vault management** — the flow where users lock
meaningful DGB collateral behind Taproot time-locks is exactly where
cold-key signing stops being an enthusiast feature and becomes table
stakes. "Open, mint against, and redeem a decentralized-stablecoin
vault from a phone, with keys that never touch the phone" is not a
feature parity item; nothing else on any chain does it.

**Deliverables, in order:**

1. **PSBT (BIP 174) foundation.** Decode/validate from QR or file,
   signing UI, finalized-PSBT output. New `core/…/psbt/` package.
   Must handle the Taproot (BIP 371 fields) cases DigiDollar vault
   scripts require, not just legacy/SegWit — that's the reason it moved
   up, so it's in scope from the first PR.
2. **Watch-only wallets.** `xpub`/descriptor import, read-only
   derivation and history (privately, via the CF layer), construct
   unsigned PSBTs for a cold signer.
3. **DigiDollar vault lifecycle.** Open vault (lock DGB collateral
   under the Taproot time-lock template), mint, redeem, monitor
   collateral ratio — with vault state derived on-device from
   filter-matched blocks per the anti-patterns rule, and every
   signing path expressible as hot-key *or* PSBT-round-trip to a cold
   signer. Builds on the shipped send/wire-format work
   (`docs/superpowers/specs/2026-07-03…07-05` series). Mainnet
   activation gating: see Phase 4 audit note.
4. **Multi-algo security dashboard.** Live per-algo difficulty and
   DigiShield adjustment, effective chainwork, blocks-per-algo cadence
   — computed entirely from the version bits and nBits in headers the
   SPV client already validates. Passes the "derivable from data the
   client already has" rule with zero network additions; it is the
   wallet-native companion to DigiScope's Gauntlet and the cheapest
   genuine differentiation in this phase. No SPV wallet on any chain
   shows users the live security model of their own chain.
5. **Sovereign price feed (oracle Schnorr blockstamps).** The DGB/USD
   rate is the last trusted third party in the default data path — today
   it comes from CoinGecko → Binance. Once the DigiDollar oracles are
   live, read it instead from their Schnorr-signed price attestations
   stamped on-chain: verify the signatures against the known oracle
   pubkey set on-device, then demote the CoinGecko/Binance APIs to a
   clearly-labeled fallback. Same "derivable from data the client already
   validates, zero trusted-API additions" rule as the dashboard above —
   and the wallet already dials these same oracle nodes for compact
   filters (Phase 1 oracle-bootstrap), so it adds no new trust surface.
   The client seam already exists (`OraclePriceProvider` /
   `PriceSource.ORACLE` in `core/…/PriceProvider.kt`, plus the Settings
   toggle that already notes "requires DigiByte v9 on mainnet"); only the
   concrete signature-verifying provider is missing. Gated on oracle
   liveness (testnet26 now → mainnet).
6. **Coin control / UTXO management.** List, freeze, manual-select.
   On `core/…/UtxoManager.kt`. Gains urgency from the duress PIN
   (funding the decoy cleanly) and vaults (choosing collateral UTXOs).
7. **Replace-by-fee (RBF).** Send-screen toggle + bump action.
8. **Sweep paper wallet (WIF).** Privately queryable now via CF.
9. **Address book / labels.** Local only; no cloud sync.
10. **Multisig (2-of-3, N-of-M).** Still last, and now for a cleaner
   reason than the June revision's: the bloom-privacy objection is
   moot (bloom is gone), but multisig composes on descriptors +
   PSBT + watch-only, all of which vault work exercises and hardens
   first. Vault-grade collateral custody is also multisig's natural
   first customer.

Shipped out of the June ordering and removed from this list:
**DigiAsset send** (v3.5.21–3.5.28, with per-asset history and image
rendering) — the June appendix still called it stubbed.

**Effort:** L overall. PSBT-with-Taproot is M; vault lifecycle is M
(client-side; protocol work is in the DigiDollar repos); dashboard is
S–M; the rest are each S.

---

## Phase 4 — Audit, distribution + hardware

**The third-party audit is a gate, not a deliverable.** Two things do
not ship to general users before an independent audit completes:

- **DigiDollar on mainnet in this wallet.** The moment balances are
  dollar-denominated, the trust bar changes category. Wallet-side
  mainnet activation of Phase 3's vault features waits for the audit
  even if the softfork activates first (testnet + expert-mode mainnet
  behind a flag are acceptable in the interim).
- **Public promotion of the duress PIN.** A coercion-safety feature
  that fails under adversarial review is worse than its absence. It
  can *exist* in releases pre-audit (labeled experimental); it does not
  get marketed until an auditor has tried to break the "no UI tell /
  no stored flag / biometric kill" properties.

**Distribution, resequenced: F-Droid before Play.** Reproducible-build
verification is ~90% there, the sovereignty audience lives on F-Droid,
and its review process rewards exactly the legibility docs this repo
already has. Play Store follows (its crypto-wallet review is erratic;
the threat model + audit report are the mitigation), timed with or
after v4.0.0 so the listing carries the strongest true claims.

**Hardware & reach, after:**

- **Coldcard via animated QR** — direct consumer of Phase 3's PSBT
  work; vault users are its natural first audience.
- **NFC tap-to-pay** (present payment request over HCE; read-side
  deferred).
- **Multiple accounts / profiles** — respecting the derivation
  namespace registry established in Phase 2.

**Effort:** M, dominated by audit scheduling (external) and Play review
latency (external).

---

## Appendix — Feature inventory

Baseline against a modern self-custodial wallet. Status reflects
**v3.10.26**.

| Feature | Status | Notes |
|---------|--------|-------|
| Send (SPV broadcast) | Shipped | Native-SegWit fee-sizing bug fixed v3.10.15 |
| Receive (address + QR) | Shipped | SegWit, Legacy, **Taproot P2TR (v3.10.0)** |
| Fee selection | Partial | Fixed tiers + custom; RBF in Phase 3 |
| BIP 21 payment URIs | Shipped | |
| QR scanning | Shipped | |
| PIN lock | Shipped | Argon2id; **no rate-limit — Phase 2 item 1** |
| Duress / decoy PIN | Designed | Spec approved 2026-07-12; Phase 2 item 2 |
| Biometric unlock | Shipped | UI-gate only; Keystore binding — Phase 2 |
| Seed backup / verify | Shipped | |
| Seed recovery | Shipped | Universal Restore + foreign-seed sweep (v3.9.0) |
| Self-healing startup | Shipped | v3.10.23 |
| Tx history / detail | Shipped | |
| Multi-network | Shipped | mainnet / testnet26 flavors, network toggle |
| Digi-ID | Shipped | One-tap DigiScope login; **key isolation pending — Phase 2 item 4** |
| DigiAsset send/receive | **Shipped** | v3.5.21–3.5.28 (June appendix was stale) |
| DigiDollar send/receive | Testnet | Mainnet at softfork activation, **audit-gated** |
| DigiDollar vault lifecycle | Not started | Phase 3 item 3 — the flagship |
| PSBT (BIP 174 + Taproot fields) | Not started | Phase 3 item 1 |
| Watch-only (xpub/descriptor) | Not started | Phase 3 item 2 |
| Multi-algo security dashboard | Not started | Phase 3 item 4 |
| Coin control | Not started | Phase 3 |
| RBF | Not started | Phase 3 |
| Sweep paper wallet | Not started | Phase 3 |
| Address book | Not started | Phase 3, local-only |
| Multisig | Not started | Phase 3, last — post-PSBT/vaults |
| Hardware wallet (Coldcard QR) | Not started | Phase 4 |
| Hardware wallet (USB) | Out of scope | QR preferred |
| Multiple accounts | Not started | Phase 4 |
| BIP 157/158 compact filters | **Shipped — only sync path** | Bloom wire path removed v3.10.5–3.10.15; checkpoint cross-check (observe) v3.10.25 |
| BIP 37 bloom | **Removed** | Not fallback — removed |
| Dandelion++ | Shipped v3.7.0 | Opt-in stem submission |
| Tor as transport | Shipped, opt-in OFF | 2026-07-02 decision; loud-fallback fix — Phase 2 item 5 |
| Own-node pairing | **Shipped** — first-class flow | Primitive v3.10.1 + QR-scan/verify/pin/health (Seq 1.1/1.2), additive default + exclusive mode. Clearnet only — onion pairing, oracle-bootstrap, `cfcheckpt` remain Phase 1 remainder |
| In-app bug reporting | Shipped v3.10.26 | Pre-filled device/sync context; DGB bounties |
| CSV export | Not started | Phase 3 small increment |
| F-Droid / Play Store | Not started | Phase 4; F-Droid first |
| Third-party audit | Not started | Phase 4 **gate** for DigiDollar mainnet + duress promotion |

## Versioning

- **3.X.Y** — current line (at v3.10.26). `Y` = patch; `X` = minor
  feature batches.
- **4.0.0 — trigger resolved.** The June revision left the major
  trigger as an open decision after "4.0.0 = BIP157/158 lands" died
  (it shipped inside 3.5.x). Its own fallback candidate — removal of
  the legacy bloom path — has since *happened* (v3.10.5–3.10.15).
  Decision: **v4.0.0 ships when Phase 1's remainder lands** (own-node
  pairing, oracle bootstrap, active `cfcheckpt` rejection), so the
  major version means "cannot leak, cannot be stranded" — both halves
  true. Subsequent major candidates: the multisig/PSBT line as 5.0.0
  if it warrants it.

## What this roadmap is not

- Not a sprint plan. Effort sizes are rough; dependencies are called
  out but dates are not.
- Not exhaustive. UX fixes, translations, detekt hygiene, and
  dependency bumps happen continuously and don't belong here.
- Not a commitment to this exact order. A critical user-reported bug
  takes priority mid-phase — v3.10.22's sync-freeze fix over a planned
  feature is the standing example of that rule working.
