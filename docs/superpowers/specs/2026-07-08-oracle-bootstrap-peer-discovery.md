# Seederless Sovereign Peer Discovery via Oracle-Node Bootstrap

**Status:** Design note — Phase 1 (ROADMAP: "peer diversity beyond author infrastructure" + "bloom-deprecation path")
**Audience:** DigiDollar oracle-node operators + wallet engineers
**Scope:** Android wallet (`digibytewallet-android`, branch `develop`). Digi-Mobile out of scope.
**No code herein** — architecture, mechanism, prerequisites, trust model, sequencing.

## Problem

The wallet keeps a user's addresses private on the wire via BIP157/158 compact filters (default-on since v3.5.39). But it still discovers filter-serving peers through a **single author-operated seeder**, `api.digiscope.me`. That one trusted, author-run component is the last real Phase-1 sovereignty gap: compact filters protect address privacy, yet the wallet trusts one party to *find* the peers that serve those filters. `digiscope.me` is doubly privileged — it is both the top compiled DNS seed (BRChainParams.h:50) **and** the injected priority peer (jni_peer.c:523/584).

## Proposed architecture

Bootstrap from a **multi-operator** set that is already committed to being online for a different reason, then let the standard P2P network grow the pool organically:

1. **DigiDollar oracle nodes as bootstrap peers.** DD oracle operators must run a mainnet DigiByte Core 9.26 full node anyway to serve the DD price/status oracle. Hardcode that oracle set as the wallet's bootstrap / priority peers, replacing the single `digiscope.me` priority injection. This is multi-operator by construction — not author infrastructure.
   > **UPDATE (2026-07-08, user): the oracle set is not hypothetical — it already exists as the compact-filter seeder's persistent peers.** "the persistent peers that are in compact block seeders are all the oracles." So the CF seeder's persistent/filter-tagged peers (`/api/peers?capability=filter`) ARE the DD oracle nodes, and they are **already proven filter-serving** (they wouldn't be filter-tagged otherwise). Consequences: (a) the hardcoded bootstrap list is concrete and derivable from the current seeder, not a future TBD; (b) for that set the operator prerequisite (`blockfilterindex=1` + `peerblockfilters=1`) is **already satisfied**; (c) it tightens Open-Question #2 (operator independence) — the diversity is exactly whatever the current oracle fleet's is; audit ASN/host spread of that known set. Path A vs B (do they ALSO run bloom?) is still the open operator decision. Cross-check against [[reference_filter_node_fleet_and_tunnels]] to see which are author-owned vs independent-oracle.
2. **Organic growth via peer gossip.** From the reliable oracle seeds, standard `addr` gossip (`_peerRelayedPeers`) grows the connected pool. The network "blooms" from the oracle seeds.
3. **Select compact-filter peers by service bit.** The app prefers peers advertising `NODE_COMPACT_FILTERS` (0x40, BIP157), using the bit as a **dial-priority hint** and confirming it against the peer's real version-message services on connect.
4. **DNS seeds remain a *bloom/liveness* fallback — not a compact-filter fallback.** The 6 mainnet DNS seeds (BRChainParams.h:48-63) keep the wallet able to reach *some* full node so it never fully strands. **But they contribute zero CF peers** (see below): DNS resolution is not a backstop for filter discovery.

Net effect: the author seeder is **demoted from required to optional**, replaced by an **author-curated, multi-operator allowlist baked into the binary** (see Trust model — this is better, but not trustless and not user-configurable).

## What already exists (mapped to code)

Most of this design is already implemented. Verified against the tree:

- **Gossiped `addr` carries full 8-byte per-peer services, including 0x40.** `_BRPeerAcceptAddrMessage` parses `p.services` (BRPeer.c:391) and only requires `NODE_NETWORK` (BRPeer.c:398), so the compact-filter bit survives gossip into `manager->peers`. (Legacy `addr` only — there is no `addrv2`/BIP155 handler; IPv6 peers are dropped at BRPeer.c:400.)
- **Service-bit constants match BIP157:** `NODE_NETWORK` 0x01, `NODE_BLOOM` 0x04, `NODE_WITNESS` 0x08, `NODE_COMPACT_FILTERS` 0x40 (BRPeer.h:74-77).
- **The filter-first dial loop already runs on mainnet.** BRPeerManager.c:2444 dials every `manager->peers` entry carrying 0x40 up front, gated only on `syncMode != BLOOM_ONLY` — **not** testnet-gated. It consumes the gossiped/injected 0x40 hint directly.
- **Verify-on-connect is already the code's behavior.** The version handshake overwrites `peer->services` with the peer's real advertised services (BRPeer.c:300) *before* `_peerConnected` gates on it (BRPeerManager.c:904-923). A spoofed 0x40 in gossip cannot fake a peer into staying connected without really advertising 0x40 — but see the Trust model for what this does *not* buy.
- **`getcfilters` keys off real connected-peer services**, not gossiped hints (BRPeerManager.c ~3134-3140) — correct, and must stay that way.
- **DNS-resolved peers are synthetically stamped bloom-only.** `_BRPeerManagerFindPeers` hard-stamps every DNS-resolved peer with `NODE_NETWORK|NODE_BLOOM` (BRPeerManager.c:837; `params->services == 0` on mainnet, BRChainParams.h:156) and **never 0x40**. So DNS peers are never picked by the filter-first loop, and under Path B a DNS node whose *real* services are CF-only is disconnected at the accept gate. CF discovery therefore has exactly **two** sources: hardcoded oracle injection and `addr` gossip.
- **Multi-node hardcoded priority injection already exists** — for testnet. jni_peer.c defines `TESTNET_PRIORITY_PEER_IPS` (a 3-node array, :328) tagged `NODE_NETWORK|NODE_COMPACT_FILTERS` (:312) and prepends each (:580). This is the exact pattern to replicate for the mainnet oracle set. Mainnet today resolves the single `digiscope.me` hostname (:523) tagged `PRIORITY_PEER_SERVICES = NODE_NETWORK|NODE_BLOOM|NODE_COMPACT_FILTERS` (:307) and prepends it (:584). `_prependSavedPeerAddr` unions services and never downgrades (:385).
- **Seeder injection already threads the 0x40 bit.** `SyncService.injectBloomPeers()` fetches `api.digiscope.me/api/peers`, parses per-peer `services_hex` (including 0x40), and injects via `NativeBridge.injectPeerByIp(ip, port, services)`. Filter-capable seeder peers already reach the filter-first loop today.
- **Block-header chain is checkpoint-anchored and PoW-verified** — `BRMainNetCheckpoints` enforced by `BRMerkleBlockVerifyDifficulty` (`_BRPeerManagerVerifyBlock`, BRPeerManager.c:1395-1409). This is the real, strong trust bound (see Trust model). **Note: only *block* headers are checkpointed; *filter* headers are not.**

## The one genuinely new piece — and a premise correction

The task framed "gossip-driven CF selection with verify-on-connect" as the single new piece. **The code contradicts that framing.** Selection *and* verify-on-connect already exist and already run on mainnet (BRPeerManager.c:2444 and :904-923 / BRPeer.c:300). Building a new confirmation layer is unnecessary.

The genuinely-new/required work is instead **letting CF-capable-but-non-bloom oracle peers survive two mainnet gates** so they ever reach the existing loop — plus hardcoding the oracle set. Both gates today embed a **testnet-only** compact-filter exception that must generalize to mainnet:

1. **Accept gate (BRPeerManager.c:904-923).** For version ≥ 70011, a peer lacking `NODE_BLOOM` is disconnected as "doesn't support SPV mode" *unless* `BRNetworkIsTestnet() && NODE_COMPACT_FILTERS`. On mainnet a compact-filter-only 9.26 node handshakes and is then **disconnected**. This gate is the **master switch** for whether *any* CF-only mainnet peer — from injection, gossip, *or* DNS — can stay connected; its blast radius is all CF-only mainnet peers, not just gossip growth. (Note the earlier `params->services` clause is a no-op: mainnet `services == 0`, BRChainParams.h:156.)
2. **Gossip retention (`_peerRelayedPeers`, BRPeerManager.c:1091-1093).** A gossiped peer is added to `manager->peers` only if it advertises `NODE_BLOOM`, with the `NODE_COMPACT_FILTERS` keep-exception again `BRNetworkIsTestnet()`-guarded. On mainnet, CF-only peers learned via gossip are **silently discarded before they can be dialed** — so the network cannot "bloom" from CF-only oracle seeds at all.

The correct generalization is **sync-mode-gated, not network-gated**: accept/retain a CF-only peer when `syncMode != BLOOM_ONLY` (rather than `if testnet`). Gating on testnet would wrongly admit unusable CF-only peers for `BLOOM_ONLY` users.

Whether these two gate changes are needed **at all** hinges entirely on the operator prerequisite below.

## Operator prerequisite (decision required before sequencing)

The stated prerequisite — `blockfilterindex=1` + `peerblockfilters=1` so nodes advertise 0x40 — is **necessary but, as stated, self-blocking on mainnet.** Modern DigiByte Core ships bloom **off by default**. A node with filters on but bloom off advertises 0x40 without 0x04, which the current mainnet accept gate rejects.

Two mutually exclusive paths — **pick one and tell operators precisely**:

- **Path A — operators also enable `peerbloomfilters=1`.** Oracle nodes then advertise `0x04|0x40`, pass every existing mainnet gate unchanged, and are selected by the existing filter-first loop. **Only code change: hardcode the oracle set in jni_peer.c. Zero core-gate changes.** Simplest to ship; keeps a bloom node population alive (relevant to the still-unresolved bloom-deprecation path).
- **Path B — operators run CF-only (`peerblockfilters=1`, bloom off).** Cleaner long-term/bloom-deprecation posture, but **requires both mainnet gate generalizations** (accept gate + retention filter) as small mirrors of the existing testnet exceptions.

**Give operators a verifiable one-line self-check** (e.g. confirm the 0x40 bit in `getnetworkinfo` local services, or a remote handshake probe) so "I enabled it" is provable, not assumed — the silent "forgot `peerblockfilters`" failure mode (below) is otherwise invisible.

## Trust model

State this plainly to operators, because it is the whole point.

**What is cryptographically bounded (cannot be forged):**
- A malicious or eclipsing filter peer **cannot forge a balance or fabricate a payment.** Every block the wallet downloads is PoW-, checkpoint-, and merkle-validated against checkpoint-anchored *block* headers (`_BRPeerManagerVerifyBlock`, BRPeerManager.c:1395-1409), and every filter is checked against its committed filter-header (`BRCompactFilterChainVerifyFilter`, BRPeerManager.c:2258).

**What is NOT bounded (the real threat is censorship/eclipse, not forgery):**
- **A lying filter peer can cause an undetectable false negative** — omit the user's scriptPubKey from a GCS filter so the wallet skips a block and misses an incoming tx. This is invisible at the crypto layer.
- **Filter headers are NOT checkpoint-verified** (correcting the project premise). There is no compact-filter-header checkpoint in the tree; mainnet *block* checkpoints stop at height 23,660,000, and CF headers above the last checkpoint are TOFU. The cfheader chain is anchored **TOFU** at wallet-birth height from the first peer's claimed `prevFilterHeader`, then defended by a **cross-peer quorum re-anchor** (`CF_CONTINUITY_REANCHOR_K`, K distinct disagreeing peers). The `cfcheckpt` message is parsed but **not** consensus-pinned (BRPeerManager.c:2294).
- **The single-peer re-anchor escape hatch actively violates the "≥K before trusting" goal.** `CF_SINGLE_PEER_REANCHOR_ROUNDS = 3` (BRPeerManager.c:2039, 2164-2176) will re-anchor from **one** peer's claimed chain when <2 filter peers are connected — the code's own comment admits it "may TOFU-accept a lying peer's chain." The filter-first loop dials the priority peer **first** (:2444), so on a fresh wallet, before ≥K distinct oracle peers arrive, a lying first-dialed peer can anchor a malicious frontier. **There is no code gate holding cfheader acceptance until K distinct filter peers are present** — the multi-operator quorum benefit is *aspirational* until one is added (see Sequencing).

**Curation, not trustlessness:**
- The oracle allowlist is **author-selected and baked into the binary**; changing it needs an author release. Trust moves from "author's seeder" to "author's chosen operator allowlist + those operators' uptime + independence" — genuinely multi-party and better, but **not trustless and not user-configurable.** State this crisply so no one reads a larger sovereignty win than the code supports.

**Design requirements that follow:**
- Hardcode enough oracle nodes that **≥K (≥2-3) from distinct operators** connect concurrently at first sync. Because DNS contributes zero CF peers and gossip cannot help before the first CF peer connects, the quorum property depends **entirely** on ≥2 distinct, reachable oracle nodes at cold start (chicken-and-egg).
- **Separate the two oracle roles explicitly.** Serving as a bootstrap peer grants **no** authority over balances or validation; trusting a node's filters implies nothing about its DD price feed, and vice-versa. Operators and users must not conflate the DD oracle API with the peer role.

## Failure modes & mitigations

| Failure mode | Covered? | Mitigation / reality |
|---|---|---|
| **Forge a false chain / fabricate a balance** | Yes (crypto) | PoW+checkpoint+merkle on every downloaded block (BRPeerManager.c:1395-1409); filters checked against committed header (:2258). Not possible. |
| **All oracle nodes offline** | Partial — CF gap | DNS seeds keep the wallet reachable for **bloom/liveness only** (peers stamped bloom-only, BRPeerManager.c:837); block headers stay checkpoint-verified. **DNS provides no CF backstop** — with oracles down and no gossiped 0x40 peer yet, filters are unavailable and the session degrades to bloom (address set on the wire) until an oracle or gossiped CF peer appears. |
| **Stale/spoofed 0x40 in gossiped `addr`** | Partial — NOT free | Verify-on-connect (BRPeer.c:300) only disconnects peers that fail to advertise *real* 0x40; it does **nothing** against a peer that genuinely serves filters but lies by omission in GCS content. Under Path-B retention, an attacker can also flood `manager->peers` (cap 2500) with fake-0x40 entries the filter-first loop dials **first**, exhausting CF dial slots with sinkholes — the current NODE_BLOOM retention gate dampens this; Path B removes that damping. Un-evictable oracle slots (below) are the counter. |
| **CF-node scarcity** (few 0x40 peers) | Partial — sharpest real risk | The injected oracle set is the **only** guaranteed CF floor (DNS gives none). Keep enough operators reachable that ≥2 connect. Otherwise the 120s watchdog degrades to `BLOOM_ONLY` for the session. |
| **Attacker-forced privacy downgrade** | **No — attacker-triggerable by design** | A hostile peer that advertises real 0x40 (passing verify-on-connect) then serves nothing/diverges stalls the CF path, trips the **120s BIP158→bloom watchdog**, and forces the session onto bloom — deanonymizing the user via the exact leak filters prevent. Oracle bootstrap does **not** close this; any selected filter peer can grief the CF path. Mitigation is detection (census + bloom-downgrade alarm, below) + un-evictable oracle peers, not prevention. |
| **Eclipse / Sybil + filter-frontier forgery** | Partial | Attacker gossips only its own CF nodes and TOFU/single-peer-anchors a divergent frontier. Parallel bloom is **not** a reliable backstop under coordinated eclipse — an eclipsing peer can withhold merkleblocks/txs in the bloom path just as easily (bloom has no false negatives *by construction*, but a peer can simply not relay). Real backstops: bounded re-anchor budget, multi-operator injected set, **un-evictable oracle peers**, and — decisively — a **shipped cfheader checkpoint** (below). |
| **Operator runs node but forgets `peerblockfilters`** | **No — silent** | Node passes as a plain full node; wallet quietly never gets filters from it and falls back to bloom. Needs operator self-check tooling **+** the app-side CF-peer census; otherwise indistinguishable from healthy operation while silently costing privacy. |
| **Bloom fallback privacy leak** | Bounded | Every "degrade to bloom" branch puts the address set on the wire — session-scoped, retries CF next launch. **Every oracle node that forgets `peerblockfilters` pushes some users onto bloom** — the strongest incentive lever for operator diligence. |

**Instrument before shipping:** add a census metric for connected-peers-with-0x40 vs total, and gossip-surfaced-0x40 count — **and alarm whenever the session is forced to `BLOOM_ONLY`**, since that is the observable signature of *both* the misconfig failure and the deliberate-downgrade attack. Same lesson as the BIP158 "1/2 disagree" wedge that cost a release to find.

## Hardening the residual (beyond the table)

Three changes convert aspirational design goals into enforced invariants:

1. **Gate cfheader acceptance/re-anchor until ≥K distinct-operator filter peers are connected** — turn the "≥K before trusting the frontier" *requirement* into an actual latch, and neuter or K-gate the single-peer re-anchor escape hatch (BRPeerManager.c:2164-2176). Without this, the single-peer path violates the whole multi-operator premise at cold start.
2. **Make injected oracle peers un-evictable** from the connected set, so a gossip/Sybil flood cannot displace the honest CF floor or capture all dial slots.
3. **Ship a cfheader checkpoint** (elevated from open-question to *the real fix for the residual*). A pinned filter-header checkpoint — using the already-parsed `cfcheckpt` message, made consensus-pinned — is the **only** measure that closes both the single-peer-escape window and the full-eclipse window up to the checkpointed height. It converts first-sync TOFU into a pinned anchor. Everything else in this note narrows the window; only this closes it.

## Open questions

1. **Path A vs Path B** (does the oracle set run `peerbloomfilters=1`?). Determines whether this is ~10 lines in jni_peer.c or that plus two core-gate generalizations. **Resolve with operators first.**
2. **Operator independence.** Quorum counts distinct IPs; if operators share an ASN / hosting provider / person, "K distinct disagreers" can be one adversary. How independent are the DD oracle operators? Should quorum weight by ASN/subnet diversity rather than distinct IP?
3. **Hostname vs raw IP for oracle nodes.** Hardcoded IPs require an app release on operator IP churn (same cost as today's testnet array). Stable hostnames (resolved like `digiscope.me`) would survive churn without a client update. Recommended for the oracle set.
4. **addrv2 gap.** No BIP155 handler exists; IPv6/onion-only oracle nodes are invisible to gossip. IPv4 oracle nodes are fine. Forward risk, not a blocker.
5. **Bloom-deprecation interaction.** The 120s BIP158→bloom watchdog and the mainnet bloom path assume a bloom peer population exists — and DNS seeds only replenish *bloom* peers. If oracle nodes go CF-only (Path B) and bloom peers dwindle, that fallback degrades. The roadmap's bloom-deprecation path needs its own sequencing decision, independent of oracle bootstrap.
6. **`digiscope.me` full demotion.** "Author demoted from required to optional" is only fully true once the oracle set is hardcoded **and** `digiscope.me` is reduced to a peer-among-equals in **both** the injected priority set (jni_peer.c) and the DNS-seed order (BRChainParams.h:50) — and even then the *allowlist* remains author-curated (see Trust model).

## Sequencing

1. **Operators decide Path A vs B** and enable `blockfilterindex=1` + `peerblockfilters=1` (+ `peerbloomfilters=1` if Path A). Ship the operator runbook + the verifiable 0x40 self-check.
2. **(Path B only)** Land the two mainnet gate generalizations — accept gate (BRPeerManager.c:904-923) and gossip retention (BRPeerManager.c:1091-1093) — as sync-mode-gated (`syncMode != BLOOM_ONLY`) mirrors of the existing testnet exceptions. The accept-gate change is the master switch for *all* CF-only mainnet peers, not just gossip growth; without both, gossip can never grow the CF pool on mainnet and every later step is a no-op.
3. **Hardcode the oracle set in jni_peer.c**, cloning the testnet multi-node pattern (`TESTNET_PRIORITY_PEER_IPS` array + prepend loop). Prefer stable hostnames over IP literals. Tag each `NODE_NETWORK|NODE_COMPACT_FILTERS` (+ `NODE_BLOOM` under Path A). `digiscope.me` stays as one entry among N. Steps 2-3 ship in one native rebuild.
4. **Land the residual-hardening latch(es):** the ≥K-filter-peer gate on cfheader re-anchor + un-evictable oracle peers (Hardening #1-#2). These make the multi-operator property real rather than aspirational and should not lag the hardcoding.
5. **Demote the seeder in SyncService.kt.** `injectBloomPeers()` / the `api.digiscope.me` fetch become optional enrichment that fail-soft (the cached-pool logic already survives a down seeder). Extend that tolerance to survive an indefinitely-down seeder once oracle bootstrap is primary.
6. **Add the CF-peer census metric + bloom-downgrade alarm** (from Instrumentation) before wide release.
7. **Scope and schedule the cfheader checkpoint** (Hardening #3) — the one measure that closes the eclipse/single-peer residual. Can follow the bootstrap rollout but should be tracked as *the* closing item, not an open question.
8. **Full pre-publish suite** across API 28/33/34/35 — the gate edits touch bloom-path invariants and must not regress `BLOOM_ONLY` wallets.
