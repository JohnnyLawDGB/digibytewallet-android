# Bloom Deprecation — BIP 157/158-Only Sync, Own-Node as the Backup

**Status:** Design note / plan — Phase 1 (ROADMAP: "bloom-deprecation path"). **DRAFT — one open decision (own-node model) pending user pick.**
**Audience:** wallet engineers + (for the operator prerequisite) DigiDollar oracle-node operators.
**Scope:** Android wallet (`digibytewallet-android`, branch `develop`). Digi-Mobile out of scope.
**No code herein** — direction, dependency analysis, sequencing, and the one open decision.

## Decision

**Bloom (BIP 37) is being deprecated. The wallet moves to BIP 157/158 compact filters as the *only* network sync path.** The backup for a user who cannot or will not rely on public compact-filter peers is **their own node**, not bloom. Rationale: bloom puts the wallet's address set on the wire (the exact leak compact filters exist to prevent), so it is a privacy liability everywhere it still runs — keeping it "as a fallback" means every fallback event silently deanonymizes the user. Better to have no bloom and a sovereign escape hatch.

This is the sibling of the [oracle-bootstrap peer-discovery note](2026-07-08-oracle-bootstrap-peer-discovery.md): that note makes public CF peers *diverse and reachable*; this note removes the bloom path *once they are*.

## What bloom secretly does today (each needs a replacement before removal)

Bloom is not just the `BLOOM_ONLY` mode a user can select. It is load-bearing in three places, and removing it naively strands people:

1. **It is what the 120s watchdog falls back to.** `SyncService` (`BIP158_FALLBACK_TIMEOUT_MS`, ~line 489) flips `syncMode → BLOOM_ONLY` in the C core when filter peers make no header progress. Remove bloom and this fallback has nowhere to go.
2. **It is what the 6 DNS seeds replenish.** `_BRPeerManagerFindPeers` hard-stamps every DNS-resolved peer `NODE_NETWORK|NODE_BLOOM`, never `0x40` (BRPeerManager.c:837). DNS today yields **zero** CF peers — so DNS is a bloom-only lifeline. Remove bloom and DNS bootstrap yields nothing usable unless CF-only peers are accepted.
3. **It is what the two mainnet gates require.** The accept gate (BRPeerManager.c:917-924) disconnects a version≥70011 peer lacking `NODE_BLOOM` unless `BRNetworkIsTestnet() && NODE_COMPACT_FILTERS`; gossip retention `_peerRelayedPeers` (BRPeerManager.c:1091-1093) drops non-bloom gossiped peers with the same testnet-only CF exception. On mainnet today a CF-only node is refused at connect and discarded from gossip.

**Implication:** BIP158-only is not "delete bloom." It is "replace all three of bloom's jobs, *then* delete it." Jobs #2 and #3 are exactly the **Path B** gate/DNS work already scoped in the oracle-bootstrap note. Job #1 is what the own-node backup is for.

## The replacements

| Bloom's job | BIP158-only replacement |
|---|---|
| **#1 Watchdog fallback** | The watchdog stops flipping to `BLOOM_ONLY`. Instead: keep trying CF peers, surface a clear "can't reach filter peers" state, and offer the **own-node** path as the sovereign escape hatch. No silent privacy downgrade — ever. |
| **#2 DNS replenishment** | Generalize the accept gate + retention exceptions (job #3) so DNS-resolved nodes whose *real* version services are CF-capable survive on mainnet; pair with the **oracle-bootstrap** injected CF set as the guaranteed CF floor. (DNS still can't *advertise* 0x40 pre-connect — its value is post-handshake once the gate accepts CF-only.) |
| **#3 Gate requirement** | Generalize both `BRNetworkIsTestnet()`-guarded CF exceptions to **sync-mode-gated** (`syncMode != BLOOM_ONLY`), the mainnet unlock already specified in the oracle-bootstrap note. Once bloom is fully gone, these gates simplify further (accept requires `NODE_COMPACT_FILTERS`, full stop). |

## The one open decision — the own-node model

The user's words were "connect via **rpc** to their own node." Two very different builds satisfy that phrasing, and history shows they get conflated (see [custom-node note](../../../MEMORY.md) → `project_roadmap_electrum_and_custom_node`). This note is built on the **tiered** spine (below) so it is robust to the pick, but the pick decides how deep Track A goes.

- **Model A — wallet ↔ node JSON-RPC (bypass SPV for that user).** The wallet pulls balance/UTXOs/history and broadcasts through the node's RPC. Most sovereign; matches "via RPC" literally. **Catch:** a stock node's RPC cannot answer "my UTXOs for these addresses" — it needs `txindex`/an address index, or a descriptor + `scantxoutset` flow. That indexing/descriptor path is the real work, and it is a *new data-source abstraction* (a `UtxoSource` seam), not a small patch.
- **Model B — own node as a fixed/priority CF peer.** Point the existing P2P layer at the user's node as a pinned peer; still BIP158 over P2P, just a peer they control. Cheapest — reuses the injection/prepend plumbing. **Caveats:** the node must run `peerblockfilters=1`; and prefer the **priority-injection/prepend** path over `BRPeerManagerSetFixedPeer` (jni_peer.c:102 warns `SetFixedPeer` "disconnects everything and clears" the pool — too blunt for a fallback the user toggles).
- **Model C — tiered (RECOMMENDED): ship B first, design A later.** B is a small, safe replacement for bloom's watchdog fallback (#1) and unblocks bloom removal quickly; A becomes the deeper sovereignty tier delivered on the `UtxoSource` seam afterward. This lets bloom die on the near-term timeline without waiting for the full RPC backend.

**Recommendation: Model C.** It gives the fastest *safe* path to BIP158-only while keeping "full own-node RPC" as the stated end-goal rather than a blocker.

## Dependency structure & sequencing

Bloom removal is **gated** — do it last, or you dead-end users who can't reach a CF peer and have no bloom and no own node.

1. **Prerequisite — CF peer diversity.** The oracle-bootstrap work (injected multi-operator CF set + gossip growth). Without enough reachable CF peers, BIP158-only strands users. *(Separate note; operator prerequisite in progress.)*
2. **Mainnet CF gate generalization** (accept gate + retention, sync-mode-gated). Shared with Path B of the oracle-bootstrap note — the true blocking core change. Full pre-publish suite (API 28/33/34/35): must not regress existing `BLOOM_ONLY` wallets *while both paths still exist*.
3. **Own-node fallback lands (Track A).** Under Model C: the fixed-CF-peer toggle in Settings (Network Info), pinned via priority injection, node reachable + `peerblockfilters=1`. This is the escape hatch that replaces bloom's watchdog fallback.
4. **Flip the default + re-point the watchdog.** Default `syncMode` → `COMPACT_FILTERS_ONLY`. The 120s watchdog no longer flips to `BLOOM_ONLY`; instead it surfaces the connectivity state and nudges the own-node path. `bloomFallbackActive` UI state retires. Remove `BLOOM_ONLY`/`BOTH` from user-selectable Sync Mode (leave the enum values for one release as a safety valve).
5. **Excise the bloom code (Track B — the actual deprecation).** Remove `filterload` sends, the bloom branches in the two gates (they now simply require `NODE_COMPACT_FILTERS`), the `BRBloomFilter` construction/rebuild path, and `SyncMode.BLOOM_ONLY`/`BOTH`. This is the **wire-protocol/behavior change that finally justifies a major version bump** (per CLAUDE.md the "next major needs a fresh trigger" — removing the legacy bloom path is exactly that trigger).
6. **Deep RPC backend (Model A / Track A tier 2), if chosen** — build on the `UtxoSource` seam after bloom is gone.

## Open questions

1. **Own-node model — A vs B vs C** (this note recommends C). Decides Track A's depth. *This is the one blocking decision.*
2. **Watchdog end state.** When CF peers are unreachable and the user has no own node configured, what does the wallet show? (Proposed: an explicit "no filter peers — sync paused, configure your own node or wait" state, never a silent bloom downgrade.) Do we auto-offer the own-node setup at that moment?
3. **Major-version trigger.** Bloom removal (step 5) is a legacy-path removal — confirm it lands as `X.0.0` and coordinate release notes. Steps 1-4 are minor/patch and mainnet-safe with bloom still compiled.
4. **Migration for `BLOOM_ONLY`-pinned wallets.** Any wallet whose persisted `sync_mode` is `BLOOM_ONLY` must be migrated to `COMPACT_FILTERS_ONLY` on upgrade, not left on a removed mode.
5. **Node-capability probe.** For Model B, the wallet should verify the user's node actually serves `cfheaders` (not merely reachable) and give a clear error if `peerblockfilters` is off — same "verifiable, not assumed" principle as the oracle operator self-check.
6. **Retain a compiled-but-unreachable bloom for one transition release?** Keeping `BLOOM_ONLY` selectable-by-dev-flag for one release de-risks the flip before the code excision. Decide whether that safety valve is worth the surface.

## Relationship to the oracle-bootstrap note

These are two halves of the same Phase-1 close-out. Oracle-bootstrap **supplies** enough diverse CF peers; bloom-deprecation **relies on** that supply to remove the leaky legacy path. Ship order: peer diversity → gate generalization (shared) → own-node fallback → default flip → bloom excision (major bump).
