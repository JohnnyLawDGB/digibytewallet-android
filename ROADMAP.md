# DigiByte Wallet — Roadmap

> ## Update — 2026-08-27 (distribution: Play is unblocked)
>
> **The Play Console developer account is approved.** Publishing is no longer waiting on
> anything external, which invalidates the "F-Droid before Play" sequencing recorded in
> Phase 4 — that order existed because Play was the uncertain channel, and it no longer is.
>
> **Play now goes first; F-Droid follows.** Real installs on real devices are worth more
> right now than the last ~10% of reproducible-build verification, and the listing's own
> timing condition ("at or after v4.0.0") was met long ago. F-Droid's rationale is
> untouched — it is simply no longer the gate. Phase 4 and the feature inventory are
> updated to match.
>
> **digiscope.me now leads its homepage with the wallet** (Native DigiByte, DigiDollar,
> DigiAssets, sovereign design, universal key restore, 50+ languages) and advertises
> "Google Play release approaching". This roadmap said "Not started, F-Droid first" while
> that copy was live; the two now agree.
>
> **Unchanged:** the third-party audit is still a gate, and Play's crypto-wallet review is
> still erratic — the threat model + audit report remain the mitigation, and review latency
> is now the only external unknown left in the channel.

---

> ## Update — 2026-08-22 (true-up to v4.0.46)
>
> Six releases since the last banner. The 2026-08-19 "NEXT" list below is **spent** — its
> first item shipped, and the rest were overtaken by defects found while shipping it.
>
> **Shipped**
>
> - **`cfcheckpt` active rejection (v4.0.41)** — the Phase 1 client remainder is DONE. A
>   filter-header batch crossing a pinned checkpoint is validated *before* commit; a peer
>   serving a divergent chain is rejected and banned rather than appended-then-logged, and a
>   checkpoint-confirmed chain vetoes the re-anchor path. Checkpoint table extended to
>   24,050,000. **The work already existed** on an unmerged branch whose submodule commits had
>   never been pushed — see the near-loss note under Guardrails.
> - **v4.0.43 — a regression in that same release, fixed.** Its never-brick path acted on ONE
>   disagreeing peer once a never-reset budget was spent, condemning 20k+ heights on a live
>   wallet. Now requires the same corroboration a re-anchor does.
> - **v4.0.42 — a send could report success while the network had refused it.** The JNI passed
>   a NULL publish callback, which is also how the C core reports failure; worse, a NULL
>   callback is skipped by the cleanup path, so those publishes never left the queue. Because
>   the wallet registers a transaction just before publishing, a refused send still marked its
>   inputs spent — minting local-only UTXOs that later sends built on. `clearStuckSends` now
>   also clears the orphans that produced.
> - **v4.0.44–46 — a "history gap" is recoverable and dismissible.** The wallet re-fetches just
>   the block headers under a gap (anchored to a compiled-in checkpoint, no third party) and
>   retires the marker as it goes; and a gap recorded with an unknown lower edge can finally
>   clear. Verified on-device.
> - **Asset transfer request URIs** (`digibyte:…?assetId=&assetAmount=`), fail-closed — the
>   deep-link half of the digistamp integration.
>
> **NEXT — NOT yet agreed, listed for a decision rather than as a plan:**
>
> 1. **Restore restructure** — onboarding split (written, unshipped, needs visual verification)
>    then the asset-aware sweep on the adoption design.
> 2. **The second abandonment cause** — `resume frontier below saved block window`, seen in the
>    same capture as the .41 regression. Pre-existing, untouched, unquantified.
> 3. **Digi-ID key isolation** — now correctly scoped as per-site derivation (linkability), NOT
>    key exposure; see the corrected entry in "Current state".
> 4. **`assets.digistamp.co`** — its gate (trustworthy asset balances) cleared in v4.0.39, and
>    the transfer-request URI landed in v4.0.42. Buying still needs PSBT, which does not exist.
>
> **Guardrails added 2026-08-22**, because each failure below already happened:
> `scripts/check-submodule-pin.sh` (a pin that resolves locally proves nothing — a shipped pin
> once existed only in two local worktrees) and `scripts/check-worktree-target.sh` (a cwd reset
> put a commit in the main checkout, on a stale branch, over an unrelated base).

---

> ## Update — 2026-08-19 (true-up to v4.0.40)
>
> Records the 2026-08-16 handoff (`docs/specs/HANDOFF_2026-08-16.md`) and the five
> releases since. **Each of that handoff's three specs was investigation-first, and in
> each case the investigation refuted a load-bearing premise.** That is the headline,
> because two of the three would have shipped the wrong fix.
>
> **Shipped**
>
> - **DigiAsset balances are correct** (v4.0.36 → v4.0.39). The spec blamed
>   transaction-history replay drift; the actual causes were a missing implicit-change
>   rule (DigiAssets returns a partial transfer's remainder to the LAST output — neither
>   Kotlin nor native applied it, and the native half let a plain-DGB spend destroy an
>   asset) and a re-sent stuck send counting its change twice. The rule now is: a row
>   counts only if the native wallet still holds that exact output.
> - **The address set no longer leaves the device automatically** (v4.0.36). Asset-holding
>   lookups POSTed the whole address set to a backend. This was the live privacy hole the
>   handoff was written around, and it is closed.
> - **Peer re-dial penalties persist across launches** (v4.0.36), with the canon filter
>   fleet exempt so a cold start cannot starve itself.
> - **Sync no longer restarts from birth height** (v4.0.40, unplanned but user-facing).
>   Any mid-session peer-manager rebuild — network drop, 0-peer recovery, stalled filter
>   chain — floored the chain to the wallet's birth checkpoint and re-scanned over a
>   million blocks. Device-verified: four rebuilds in 44 seconds, all resuming at tip.
>
> **Refuted, and deliberately not built**
>
> - **"Sticky 2 ACTIVE + 1 STANDBY" peer retention.** Measurement says 44% of dials are
>   refused at the door by the peer — slots are scarce to GET, not squandered. Shrinking
>   to 3 walks into the 0-peer wedge and destroys the filter-header quorum the same spec
>   asks for. `docs/superpowers/specs/2026-08-16-peer-retention-findings.md`.
> - **Filter-based rescan of a restored seed's addresses.** Cannot work as specified: the
>   native invariant is *credit iff derived* — a watched address becomes a filter element
>   so its block IS fetched, then the transaction is discarded. Replaced by temporary seed
>   adoption (`docs/superpowers/specs/2026-08-17-restore-as-sovereign-sweep-design.md`).
>
> **Cancelled**
>
> - **Duress / decoy PIN** (decision 2026-08-16). Struck from Phase 2; no scaffolding had
>   shipped. Its two real findings are kept where they belong — Digi-ID key isolation now
>   stands on its own merits, and the covert OP_RETURN beacon stays a non-goal.
>
> **NEXT (decided 2026-08-19) — ⚠️ SUPERSEDED by the 2026-08-22 banner; item 1 shipped in v4.0.41:**
>
> 1. **`cfcheckpt` active rejection** — the Phase 1 client remainder, unchanged from the
>    2026-08-10 banner and still the honest "finish what v4.0.0 already claims". Chosen to
>    lead because it is entirely in-app: no derivation migration, no backend coordination,
>    no migration risk to existing users.
> 2. **Restore restructure**, in the split the handoff called for: land the onboarding
>    change (fresh-wallet-by-default — already written, unshipped pending visual
>    verification) first, then the asset-aware sweep on the adoption design.
> 3. **Digi-ID key isolation** (Phase 2). Inherits Phase 2's top slot now that duress is
>    cancelled, and the derivation namespace it was boxed out of is free again — but it is
>    the most coordination-heavy item in the phase (one-time migration plus a compatibility
>    window on `api.digiscope.me`), so it does not lead.
> 4. **`assets.digistamp.co`** is **unblocked** — its gate was trustworthy asset balances,
>    and those landed and are explorer-validated.
>
> Note on provenance: items 1–4 are pre-existing roadmap work, NOT products of the
> 2026-08-16 handoff. Digi-ID key isolation in particular comes from the security audit
> (CRITICAL-4 residual) and has been Phase 2 item 4 since the June sequencing. Note the
> audit text is wrong on the specifics: the path is `m/0'/0/0`, not `m/44'/20'/0'/0/0`, and
> the real cost is cross-site linkability rather than exposure of funded keys — see the
> corrected entry in "Current state" below.

---

> ## Update — 2026-08-10 (true-up to v4.0.35)
>
> This roadmap's body was written at v3.10.26 and had gone stale across the
> entire 4.0.x line — a legibility bug by Principle #2. Current version is
> **v4.0.35** (August 2026). The body below remains the authoritative
> *sequencing*; this banner records what actually shipped and re-points
> "what's next." Deltas:
>
> - **v4.0.0 was cut on the bloom-excision trigger alone** — *ahead* of the
>   Phase 1.5 plan, which said to cut it only once the "never stranded"
>   remainder (own-node + oracle-bootstrap + `cfcheckpt`) also landed. So the
>   major version already carries the headline claim while its second half is
>   still unfinished. Own-node pairing **did** ship (Seq 1.1/1.2); oracle-
>   bootstrap and active `cfcheckpt` rejection are **still open** — now the
>   top of the queue.
> - **The 4.0.x line (through v4.0.35) is a large sovereign-sync HARDENING
>   pass, not new scope.** Correctness + reliability of the CF client: honest
>   at-tip gating, liveness-gated recovery watchdogs, restore that resumes
>   across restarts instead of resetting, memory-safety fixes, and a
>   compact-filter **receive-verification security fix** (a delivered block
>   must verify against the header's merkle commitment before a height counts
>   as scanned — closed a peer-can-hide-a-receive path). Measured, not
>   asserted: the long-standing deep-restore "lock-starvation" was re-measured
>   on v4.0.35 and found to be ~95% the bugs since fixed (max 4.5s residual, no
>   wedge) — investigated and CLOSED, not a locking rewrite. See
>   `docs/superpowers/specs/2026-08-09-lock-starvation-residual-measurement.md`.
> - **DigiDollar is now LIVE on mainnet** (softfork activated 2026-07-18), so
>   oracle CF-enablement — a stated DigiDollar-mainnet *prerequisite* — is now
>   overdue, which promotes the Phase 1 fleet-reliability remainder onto
>   DigiDollar's critical path.
> - **Phase 2 status:** PIN rate-limit **shipped** (v3.10.35). Keystore
>   auth-binding, Digi-ID key isolation, and loud Tor fallback remain; the
>   duress PIN was **cancelled** 2026-08-16 (see the 2026-08-19 banner).
> - **NEXT (decided 2026-08-10):** finish Phase 1's client half —
>   `cfcheckpt` graduates from observe-and-log to active rejection — while the
>   oracle-operator CF checklist runs in parallel (ops-paced). Then Phase 2.

Current version: **v4.0.35** (August 2026). The revision below supersedes the
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
  screens), live on testnet and — since the softfork activated
  2026-07-18 — on **mainnet** too (send/receive/balance only; the vault
  lifecycle is still Phase 3).

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
  now the single cheapest high-value hardening item in the repo.
  **Shipped v3.10.35.**
- **Key sealing is PIN-gated at the application layer, not at the
  Keystore.** `setUserAuthenticationRequired` remains deliberately off
  (`core/…/security/KeyStoreManager.kt:37`) for API-level crash reasons.
  Right call at the time; still means a compromised app process can
  decrypt the seed without the device being unlocked.
- **Digi-ID has no dedicated identity path** — corrected 2026-08-19,
  traced through the JNI rather than inferred. `DigiIdManager.kt:49`
  calls `signMessage(uri, addressFormat)` (the second argument is the
  address FORMAT, not an index); the JNI hardcodes
  `seed_derive_key(&key, 0, 0)` → `BRBIP32PrivKey(chain=0, index=0)` →
  **`m/0'/0/0`**, the legacy bread-wallet tree.

  **What this is NOT.** Message signing emits a signature, never a key,
  and the `\x19DigiByte Signed Message:\n` prefix domain-separates the
  payload from a transaction sighash, so a hostile site cannot disguise
  a transaction as a login. `m/0'` is also hardened at the first level,
  as are `m/84'/20'/0'` and `m/86'/…`, so the identity pubkey cannot be
  used to derive or correlate any funded branch. And `m/0'` is only
  *pregenerated and watched* for bread-wallet compatibility
  (`BRWallet.c:491-507`) — every address the app hands out comes from
  the BIP84/BIP86 trees via `BRWalletReceiveAddress`. For a wallet this
  app created, `m/0'/0/0` is never handed out and holds nothing.

  **What it actually costs.** (1) Every Digi-ID site sees the SAME
  identity address, so sites can correlate a user with each other —
  BitID's per-site `m/13'/…` derivation exists precisely to avoid this;
  the current code chose one deterministic address on purpose ("a
  deterministic address for Digi-ID across sessions"). (2) A restored
  bread-wallet seed whose `m/0'/0/0` DID hold funds gets an identity
  with public on-chain history. (3) The recoverable-signature scheme
  publishes that pubkey permanently.

  So this is a **linkability fix, not a key-exposure fix** — real, but
  smaller than "Digi-ID compromise = wallet compromise" implied.
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
| 1 | Sovereign data layer | L | ✅ Client shipped (v3.5.39) & hardened through v4.0.46, bloom **removed** (v3.10.x), own-node pairing shipped, **`cfcheckpt` active rejection SHIPPED v4.0.41**. 🚧 Remainder: oracle-bootstrap (seeder demotion) |
| 1.5 | **v4.0.0** | S | ✅ **Shipped** (bloom major) — cut on the bloom trigger *ahead* of the never-stranded remainder; now at **v4.0.35** |
| 2 | Key & trust hardening | S–M | 🚧 PIN rate-limit ✅ **shipped** (v3.10.35); in-app spend gate + inactivity lock ✅ (2026-08-30 follow-ups); Keystore hardware-backing **probed/logged, not enforced**; duress PIN **cancelled** (2026-08-16); next: Digi-ID key isolation → Keystore auth-binding → loud Tor fallback |
| 3 | Feature velocity on the sovereign layer | L | 🚧 PSBT pulled forward as the **DigiDollar vault enabler**; security dashboard added; multisig stays last |
| 4 | Audit, distribution + hardware | M | 🚧 Third-party audit **gates** DigiDollar-mainnet-in-wallet; **Play Console account approved 2026-08-27 — Play first, F-Droid follows** |

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
3. ~~**`cfcheckpt` checkpoint verification.**~~ **SHIPPED v4.0.41.** Filter
   headers are validated against the pinned table BEFORE commit; a divergent
   batch is rejected and the peer banned, and a checkpoint-confirmed chain
   vetoes the re-anchor. Landed after v4.0.0 rather than before it, so the
   major version shipped its trust story incomplete — noted for honesty. A
   regression in the same release (acting on ONE disagreeing peer) was fixed
   in v4.0.43.

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

Resequenced. The June ordering listed these as independent; they were
not — but the coupling ran through the duress PIN, which was cancelled
2026-08-16. What remains is genuinely independent, ordered by risk:

1. **PIN rate-limit (first, small, unblocking).** Exponential backoff:
   3 attempts free, then 1/5/30/60-minute cooldowns, optional
   wipe-after-N behind a settings toggle. `core/…/security/PinManager.kt:43`.
   Shipped v3.10.35.
2. **Duress / decoy PIN — CANCELLED 2026-08-16.** Not proceeding. The
   decision is recorded in `docs/specs/HANDOFF_2026-08-16.md`; the design
   (`docs/superpowers/specs/2026-07-12-duress-pin-design.md`) is kept as
   a record of why, not as a queued item. No scaffolding shipped, so this
   is a strike-through rather than a removal task. **Two consequences of
   the cancellation are worth keeping**, because they were real findings
   and they outlive the feature:
   - Digi-ID signing with the main wallet's first address
     (`DigiIdManager.kt:49`) means any "separate session" concept
     authenticates as the real user. That is now purely an argument for
     item 4 below, on its own merits.
   - The covert on-chain OP_RETURN beacon was analysed as unreadable but
     not *invisible* — a distinguishable output shape, documented in a
     public repo. Do not resurrect it under another name.

3. **Keystore user-auth binding, per-API.** Partial (2026-08-30):
   hardware backing is now verified and logged at key creation
   (`KeyInfo.isInsideSecureHardware`), and every spend-class action is
   gated by the in-app PIN/biometric — but the key itself is still not
   auth-bound, so a compromised app process can decrypt the seed. Revisit
   `setUserAuthenticationRequired` with per-API probing; enable on
   modern APIs if stable, keep the app-PIN as sole gate on older ones.
   `core/…/security/KeyStoreManager.kt:37`.
4. **Digi-ID key isolation.** Distinct subtree (dedicated purpose code
   or account) so one identity address is not reused across every site;
   a per-site index derived from the callback URI, as BitID does, is the
   shape to copy. NOT a key-exposure fix — see "Current state" for what
   the current `m/0'/0/0` path does and does not leak;
   one-time migration with a compatibility window on `api.digiscope.me`.
   Namespace note: the duress design's reservation of purpose 84'/86'
   account 1' for a decoy is **released** with that cancellation, so
   Digi-ID isolation is no longer boxed out of it. The standing rule
   survives the feature — any subtree claim gets recorded in
   `docs/derivation/` before it ships.
   `core/…/digiid/DigiIdManager.kt:49`.
5. **Loud Tor fallback.** Tor stays opt-in/OFF by default (2026-07-02
   decision stands), but a user-enabled Tor that fails bootstrap must
   fall back to clearnet *loudly* — main-screen banner + retry — never
   sit at 0 peers, never fall back silently.

**Effort:** S–M, reduced by the duress cancellation. Item 1 is shipped;
5 is days; 3 is the riskiest (device-matrix testing); 4 is
coordination-heavy and is now the phase's centre of gravity.

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
   On `core/…/UtxoManager.kt`. Urgency now comes from vaults (choosing
   collateral UTXOs) and from DigiAsset sends, which must never select an
   asset-bearing output as an ordinary input.
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
- ~~**Public promotion of the duress PIN.**~~ Moot — the feature was
  cancelled 2026-08-16. The underlying rule still binds anything like it:
  a coercion-safety feature that fails adversarial review is worse than
  its absence.

**Distribution, resequenced again (2026-08-27): Play first, F-Droid
follows.** The Play Console developer account is **approved**, so
publishing is no longer gated on anything external — which removes the
constraint that put F-Droid first. Getting real installs on real devices
now beats waiting on the last ~10% of reproducible-build verification,
and digiscope.me already leads its homepage with the wallet. The Play
listing's timing condition is met: v4.0.0 shipped, so the listing carries
the strongest true claims. Play's crypto-wallet review is still erratic;
the threat model + audit report remain the mitigation.

F-Droid follows, and its rationale is unchanged — the sovereignty
audience lives there and its review process rewards exactly the
legibility docs this repo already has. It is simply no longer the gate.

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
**v4.0.35** (rows dated v3.10.x still describe when a feature landed).

| Feature | Status | Notes |
|---------|--------|-------|
| Send (SPV broadcast) | Shipped | Native-SegWit fee-sizing bug fixed v3.10.15 |
| Receive (address + QR) | Shipped | SegWit, Legacy, **Taproot P2TR (v3.10.0)** |
| Fee selection | Partial | Fixed tiers + custom; RBF in Phase 3 |
| BIP 21 payment URIs | Shipped | |
| QR scanning | Shipped | |
| PIN lock | Shipped | Argon2id; **rate-limit shipped v3.10.35** (exponential backoff) |
| Duress / decoy PIN | ⛔ Cancelled | Decision 2026-08-16 (`docs/specs/HANDOFF_2026-08-16.md`); design kept as a record only |
| Biometric unlock | Shipped | UI-gate only; Keystore binding — Phase 2 |
| Seed backup / verify | Shipped | |
| Seed recovery | Shipped | Universal Restore + foreign-seed sweep (v3.9.0) |
| Self-healing startup | Shipped | v3.10.23 |
| Tx history / detail | Shipped | |
| Multi-network | Shipped | mainnet / testnet26 flavors, network toggle |
| Digi-ID | Shipped | One-tap DigiScope login; **key isolation pending — Phase 2 item 4** |
| DigiAsset send/receive | **Shipped** | v3.5.21–3.5.28 (June appendix was stale) |
| DigiDollar send/receive | **Shipped** | Live on **mainnet** since the softfork activated 2026-07-18 (send/receive/balance); vault lifecycle is Phase 3 |
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
| Google Play | **Unblocked** — developer account approved 2026-08-27 | Phase 4, now first; listing not yet submitted. Review latency is the remaining unknown |
| F-Droid | Not started | Phase 4, after Play; reproducible-build verification ~90% |
| Third-party audit | Not started | Phase 4 **gate** for DigiDollar mainnet |

## Versioning

- **4.0.X** — current line (at **v4.0.35**). `Y` = patch; `X` = minor
  feature batches. (The 3.X.Y line closed at v3.10.x; v4.0.0 = the bloom
  wire-path removal, the major it earned.)
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
