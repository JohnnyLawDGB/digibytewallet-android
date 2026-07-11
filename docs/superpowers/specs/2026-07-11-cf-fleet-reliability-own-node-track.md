# CF fleet-reliability — the own-node track (empirical motivation)

**Date:** 2026-07-11
**Status:** Roadmap note / decision driver — Phase 1 (Sovereign data layer)
**Type:** Empirical validation of the own-node / oracle-bootstrap track
**Trigger:** on-device findings while shipping **v3.10.18** (native pin `7b5f1bd`),
the dead-socket filter-peer floor fix — see memory
`project_cfheaders_deadsocket_zombie_wedge`.

## One-line

More dialer patches will not make compact-filter sync robust on a weak device
against the self-hosted ~16-node filter fleet. v3.10.18 is where the fixes crossed
the "3+ fixes → question the architecture" line. The answer is a **reliable filter
peer** (own-node / oracle-bootstrap), not more peer-selection heuristics.

## What v3.10.18 fixed (and closed)

The dead-socket-zombie wedge: filter peers whose socket died (`socket<0`) stayed
`status==Connected` and unevictable (`BRPeerDisconnect` no-ops on `socket<0`),
pinning `connectedPeers` at `maxConnectCount` so the filter-first dial pre-pass was
gated out and fresh filter peers were never dialed. Fixed with a live-filter-peer
floor (dial gated on open-socket occupancy, not total count), a phantom-peer
penalty, a cfheaders-throttle re-arm, and a `BR_PEER_NONE` OOB-write cleanup.
**Proven on-device (Note 8):** cfheaders climbed in real 2000-header batches
(23,282,001 → 23,290,001), the floor cycled **8 distinct** seeder filter peers, the
`whole filter set stalled` busy-loop dropped 14,838 → ~120, no crash.

## What it could NOT fix — the architectural residual

The same on-device session made the underlying fragility explicit. These are
**pre-existing** and **not** addressable by the dialer:

1. **Thin usable pool by construction.** In `COMPACT_FILTERS_ONLY`,
   `BRPeerServicesAllowedForSyncMode` correctly rejects any peer advertising
   neither bloom nor `0x40`. Modern DigiByte Core ships bloom **off** by default,
   so the majority of the reachable network is (correctly) unusable — leaving only
   the ~16 seeder-advertised filter nodes. Observed: **34+** peers disconnected as
   "node doesn't support SPV mode" in minutes.
2. **Phantom filter tags.** Peers arrive in the candidate pool tagged `0x40` via
   `addr` gossip / relay but advertise no `0x40` in their own `version` message;
   they connect then get dropped. Without the v3.10.18 penalty the dialer re-picked
   one such peer 36×. The penalty is a mitigation, not a cure — gossip tags are
   simply unreliable.
3. **Fleet churn.** Real seeder filter peers (e.g. `109.123.231.205`) connect,
   serve a burst, then hit `Connection reset by peer` and drop. CF progress is
   therefore bursty — climb, stall, re-dial, climb.
4. **0-peer stuck manager.** After a churn burst the manager can reach 0 peers and
   stall until a *fresh* manager reconnects (see memory
   `project_stuck_manager_reconnect_wakeup`). On this session the tail-end 0-peer
   stall was actually the **Note 8 losing WiFi** (`SyncService: Sync error (101):
   Network is unreachable`) — but the class of failure is real.

Net: even with a perfect dialer, a weak device syncing CF-only against a small,
churny, mostly-single-operator fleet will be slow and flaky. That is an
**infrastructure** problem, not a peer-selection problem.

## The track (this is not new work — it's prioritization)

The fix already exists on paper across three prior notes; v3.10.18 is the empirical
evidence that these are now the **critical path**, not nice-to-haves:

- **Reliable filter peer via own node** —
  `docs/superpowers/specs/2026-07-08-own-node-cf-peer-and-mainnet-cf-gate.md`.
  A user-configured node the wallet pins as a priority CF peer removes dependence
  on the churny public fleet entirely. **Open decision (user's call): own-node
  model A (wallet↔node JSON-RPC) / B (own node as fixed CF peer) / C (tiered
  B-then-A, recommended).** This pick is the gating decision for the whole track.
- **Peer diversity beyond author infra** —
  `docs/superpowers/specs/2026-07-08-oracle-bootstrap-peer-discovery.md`. Hardcode
  the multi-operator DigiDollar oracle set as CF bootstrap peers so the fleet is
  not single-operator and not solely `api.digiscope.me`. Operator prerequisite
  (in progress): oracles enable `blockfilterindex=1` + `peerblockfilters=1`.
- **CF-sync peer reliability** —
  `docs/superpowers/specs/2026-07-08-cf-sync-peer-reliability.md` (the dialer-side
  reliability work; v3.10.18's floor + penalty + throttle land under this heading
  and are now effectively **done** — further dialer tuning has diminishing
  returns).

## Weak-device stance (until the track lands)

`reconcile` ("Scan for missing funds", node-query balance recovery) remains the
**fast path** for balance on weak hardware — it does not depend on maintaining a
CF peer at all (see memory `project_wallet_restored_via_node_reconcile`). CF-only
deep sync stays the sovereign path but should not be the thing a user waits on for
balance on a device like the Note 8.

## Explicit non-goal

**Do not** ship further filter-peer-selection heuristics (rotation schemes,
scoring, aggressive redial) as the answer to "CF sync is slow on the Note 8." That
is fixing the symptom at the wrong layer. The dialer is now good enough; the fleet
is the problem. Route effort to the own-node model decision (A/B/C) and
oracle-bootstrap operator enablement.
