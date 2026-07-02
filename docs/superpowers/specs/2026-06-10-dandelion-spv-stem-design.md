# SPV Dandelion++ Stem Submission — Design

**Date:** 2026-06-10
**Status:** Approved design → ready for implementation plan
**Author:** brainstorming session (mark + Claude)

## Goal

On broadcast, submit a transaction to a **single Dandelion-enabled stem node** as a
`dandeliontx` (stem phase) instead of flooding the `inv` to every connected peer.
This denies a network observer (or Sybil/eclipse peers in the wallet's own peer
set) the ability to fingerprint the wallet as the transaction's point of origin —
the tx-origin privacy gap that compact filters (address privacy) don't cover.

A hard embargo timer guarantees the transaction always broadcasts, even when
stemming is impossible or fails. **Privacy is best-effort; reliable delivery is
not negotiable.**

## Non-goals

- **Full epoch-based stem routing** (pinning one stem peer per ~10-minute epoch).
  An SPV wallet sends transactions rarely; per-send stem selection is sufficient.
  Deferred.
- **Multi-hop stem origination.** The wallet performs only first-hop stem
  submission; the network performs the subsequent stem/fluff relay.
- **Changing the receive/sync path.** This is broadcast-only.
- **IP-layer anonymity.** That is Tor/Orbot/VPN's job (see ROADMAP). Dandelion
  protects which *peer* first saw the tx, not which *IP* sent it.

## Background

- DigiByte v8.26 **already deploys Dandelion on the network** — there is no need to
  wait for v9.26 (the ROADMAP note to that effect is stale and will be corrected).
- The C core already carries the **wire plumbing**, dormant:
  - `BRTransaction.is_dandelion` flag (`BRTransaction.h:104`), always 0 today.
  - `MSG_DANDELION_TX "dandeliontx"` (`BRPeer.h:92`).
  - Send: `BRPeer.c:659` sends `MSG_DANDELION_TX` iff `tx->is_dandelion`, else `MSG_TX`.
  - Receive: `_BRPeerAcceptTxMessage(..., is_dandelion=1)` (`BRPeer.c:508`, dispatched
    at `BRPeer.c:1155`).
- **There is no Dandelion service bit.** The version handshake exposes only
  `NODE_NETWORK|BLOOM|WITNESS|COMPACT_FILTERS` (`BRPeer.h:74-77`). The wallet
  therefore **cannot** detect a Dandelion-capable peer from the handshake; capable
  nodes must be identified out-of-band (the seeder), which is why a Dandelion-enabled
  node must be explicitly present in the peer list.
- Today's broadcast (`BRPeerManagerPublishTx`, `BRPeerManager.c:2638`) floods the tx
  `inv` to all connected peers except the download peer, and even carries the TODO
  *"connect to a random peer ... just for publishing."* Origin privacy on broadcast
  is the acknowledged gap this design closes.

## Architecture

Four cooperating pieces. The C core owns the stem/embargo state machine; Kotlin owns
peer sourcing and UI; the seeder owns capability advertisement.

### 1. Seeder (backend) — new `dandelion` capability

- Extend the capability model (currently `filter|bloom`, see
  `reference_seeder_capability_endpoint`) with `dandelion`.
- `GET /api/peers?capability=dandelion` returns Dandelion-enabled nodes (same shape
  as the bloom/filter queries).
- Tag the VPS node and any other Dandelion-on nodes.
- **Prerequisite task:** confirm Dandelion is actually enabled in the VPS node's
  `digibyte.conf` (v8.26 supports it, but the flag must be on) and that it relays
  `dandeliontx`.

### 2. Wallet peer sourcing (Kotlin)

- On startup, fetch the Dandelion peer list from the seeder, cache it hourly in a new
  `dgb_dandelion_peers` SharedPreferences key (mirroring `dgb_bloom_peers`), inject
  the peers, and mark each as Dandelion-capable.
- The existing legacy priority peer `digiscope.me` (`jni_peer.c:413`) is marked
  Dandelion-capable as the **guaranteed fallback** — this reuses the one already-
  sanctioned hardcoded peer rather than adding a new one (consistent with
  `feedback_no_hardcoded_peers_in_wallet`).
- No new peers are hardcoded into the wallet; all Dandelion nodes beyond the existing
  priority peer come from the seeder.

### 3. C core — stem routing + embargo (the state machine)

- **Capability tracking:** `BRPeerManager` keeps a set of Dandelion-capable peer
  addresses, populated from the injected list + the priority peer. (Tagged by
  address because there is no service bit to read.)
- **Stem routing in `BRPeerManagerPublishTx`:**
  - If Dandelion is enabled **and** a Dandelion-capable peer is connected →
    set `tx->is_dandelion = 1` and route the stem submission to **only that one peer**
    (not the flood).
  - Otherwise → today's flood (this is the fluff path; identical to current behavior).
- **Embargo timer** (random, ~10–30 s, per-tx): after a stem submission, arm a timer.
  - The tx is **relayed back** by other peers (the network fluffed it) before the
    timer fires → success; cancel the timer. Reuse the existing relay-back detection
    (`BRPeerManagerRelayCount` and the "see if it propagates back" mechanism).
  - The timer **expires** → self-fluff: flood the tx to all peers (today's path).
  - The stem peer **disconnects** before relaying → self-fluff immediately.
- **Reliability invariant:** every accepted transaction is broadcast within at most
  `embargo + one flood` — stemming never strands a tx.

### 4. Kotlin wiring + UI

- Pass a Dandelion-enabled preference into the broadcast call; the C core decides
  stem-vs-fluff.
- A privacy setting **"Dandelion broadcast" — default ON**, toggleable off.
- A subtle send-time indicator ("Broadcasting privately…"); when no Dandelion peer is
  reachable, the broadcast transparently fluffs and may note "private broadcast
  unavailable" rather than failing.

## Data flow

```
send → sign → PublishTx
   ├─ dandelion ON + a dandelion-capable peer connected:
   │     tx.is_dandelion = 1
   │     stem-submit  → [single stem node]      (dandeliontx)
   │     arm embargo timer (~10–30 s)
   │        ├─ tx relayed back by other peers → FLUFFED ✓  (cancel timer)
   │        ├─ embargo expires               → self-fluff (flood all) ✓
   │        └─ stem peer disconnects         → self-fluff (flood all) ✓
   └─ else (disabled, or no capable peer):
         normal flood — today's behavior      ✓
```

## Reliability & edge cases

| Case | Behavior |
|------|----------|
| No Dandelion peer reachable | Fluff normally; tx sends, no privacy benefit, optional UI note |
| Stem peer drops mid-stem | Self-fluff immediately |
| Embargo expires (stem node silently dropped tx) | Self-fluff; tx still broadcasts |
| App force-stopped right after stem-submit | Tx is persisted on broadcast (per `project_v3541_post_broadcast_coherence`); on relaunch it re-broadcasts (fluff). Must verify the re-broadcast path doesn't double-arm an embargo or leave `is_dandelion` set in a way that re-stems a tx the network already has. |
| Dandelion disabled in settings | Always flood (today's behavior) |
| Stem node is malicious (drops, or reveals origin) | Bounded: it learns we *relayed* a tx (as any first-hop relay would); embargo ensures delivery; rotating across seeder-supplied nodes limits any single node's view |

## Security considerations

- **Stem node trust:** the stem node is the one peer that knows the tx entered the
  network through us. This is strictly better than today (where *all* our peers learn
  that simultaneously). Sourcing stem nodes from the seeder pool (not always our VPS)
  spreads that first-hop knowledge across operators.
- **No new attacker-controlled parsing:** `dandeliontx` reuses `BRTransactionParse`,
  already hardened and fuzzed. The new code paths are routing/timer logic, not new
  byte parsers.
- **Embargo randomness:** must use a CSPRNG-derived jitter (not predictable), so an
  observer can't time the self-fluff. Reuse the wallet's existing secure RNG.
- **Fail-open on delivery, fail-closed on privacy:** if anything is uncertain, we
  fluff (deliver). We never hold a tx for privacy.

## Open item resolved as plan task 1

The exact DGB stem **wire mechanic**: does the originator *push* `dandeliontx`
directly to the stem node, or `inv → getdata → dandeliontx`? The current plumbing
only emits `dandeliontx` in response to a `getdata`. Resolve against DGB Core's
Dandelion source and DIP #15 (DigiByte-Core/dips#15) before writing the C routing
code. This changes an implementation detail (push vs. pull), **not** the architecture
above.

## Testing strategy

- **C unit tests:** stem-routing decision (capable-peer present vs. absent, enabled
  vs. disabled) and the embargo state machine (relay-back cancels; timeout fluffs;
  disconnect fluffs) as pure-ish logic, mirroring `BRCompactFilterChainTests` style.
- **Kotlin unit tests:** the Dandelion-peer fetch/cache and the broadcast-preference
  plumbing, as pure functions where possible (per the watchdog-policy pattern).
- **Emulator / device:** send a real tx with Dandelion on; confirm in logs (tag
  `bread`) that exactly one `dandeliontx` goes to the stem node, that it's relayed
  back (fluffed), and that the embargo cancels — plus a disabled-Dandelion control
  that floods as before. Verify a forced embargo-timeout path self-fluffs.

## Scope boundaries (deferred, noted for future)

- Full epoch-based stem-peer routing.
- A Dandelion service bit / BIP156-style feature negotiation (depends on upstream).
- Per-asset / DigiAsset stem nuances (DigiAsset sends ride the same broadcast path;
  no special handling planned).

## Files expected to change (informs the plan)

- `native/.../BRPeerManager.c` / `.h` — capability set, stem routing in PublishTx,
  embargo timer + fluff fallback.
- `native/.../BRPeer.c` — only if the wire mechanic requires a direct stem push path.
- `native/.../bridge/jni_*.c` + `NativeBridge.kt` — enable/disable Dandelion, inject
  Dandelion peers, mark capability.
- `core/.../` seeder client — fetch `?capability=dandelion`, cache `dgb_dandelion_peers`.
- `app/.../service/SyncService.kt` — inject Dandelion peers on sync start (alongside
  bloom peers).
- `app/.../ui/settings/` — "Dandelion broadcast" toggle; send-screen indicator.
- Seeder backend (separate repo / VPS) — `dandelion` capability + tag the VPS node.
- `ROADMAP.md` — correct the stale "depends on 9.26" note.
