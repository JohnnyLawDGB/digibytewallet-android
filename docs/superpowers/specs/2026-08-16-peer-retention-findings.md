# Compact-filter peer retention — investigation findings

**Status:** Investigation complete 2026-08-16. One item built, the rest not recommended.
**Spec under review:** `compact-filter-peer-retention.md` (P2 in the 2026-08-16 handoff).

The spec is investigation-first and says so: *"If the code disagrees with the spec, the
code wins — report the discrepancy rather than bending the implementation."* It does
disagree, on most points.

---

## 1. The premises, checked

| Spec claim | What the code does |
|---|---|
| "Churns through roughly eight peers, cycling connections" | `PEER_MAX_CONNECTIONS = 8` is a CONCURRENT pool, not a carousel. There is no elapsed-time rotation in `BRPeerManager.c`. The only "rotate" is picking a different filter peer after a `PROTOCOL_TIMEOUT` — which is the spec's own failure-driven rule. |
| "Proactively pinging to assess quality" | The keepalive is a liveness ping (`BRPeerManagerKeepAlive`, ~10s), sent so idle filter peers aren't dropped by NAT/remote inactivity. It measures nothing and rejects nobody. |
| "Persist proven-good peers across app sessions" (§5) | Already done — `bridge_savePeers` persists the pool (observed live serializing 281–285 peers). |
| "Fail over on actual failure, not on a timer" (§3) | Already the design. |
| "Backoff with expiry, escalating" (§2) | Exists: `PEER_PENALTY_SECONDS` (10 min) via `BRPeerPenalty.h`, applied to peers that reject us as "node isn't synced". Was **not** persisted — the one real gap; see §3. |

## 2. What actually causes the churn

From this repo's own measurement run
(`docs/superpowers/specs/2026-08-08-disconnect-ledger-findings.md`):

> Refused at the door (`handshake=0`, `in=0b`) — **35 / 80 = 44%** … the fleet we built
> refuses us 44% of the time.

TCP connects, we send our 128-byte version message, we receive **zero bytes**. The wallet
is not discarding earned trust — it is being turned away. That inverts the spec's model:
it assumes connection slots are plentiful and we squander them, when the measurement says
slots are scarce and hard to *get*. Holding fewer connections does not conserve a
plentiful resource; it thins a starved one.

## 3. Why 2 ACTIVE + 1 STANDBY would make things worse

- **It removes the quorum the same spec asks for.** §4 wants filter-header disagreement
  detected and tiebroken. The shipped CF work sizes the `cfDisagreed` arrays to the full
  peer pool specifically so a **majority is detectable at 6–8 filter peers**. At 2 ACTIVE
  there is no majority to detect and nothing to tiebreak with.
- **It shrinks the pool against a 44% door-refusal rate.** Targeting 3 connections where
  nearly half of dials are refused outright puts the wallet one refusal away from the
  0-peer wedge class this codebase has repeatedly had to dig out of.
- **One peer already serves all filter traffic** at a time; the others are the redundancy
  that makes a failover instant. That redundancy is the thing being proposed for removal.

## 4. What was built instead

Spec 2 §5, the genuine gap: **the penalty set now persists across restarts.**

It was session-scoped by construction (`BRPeerManager.c:64`, "not persisted across process
restarts"), so every cold start re-dialled peers the previous session had already learned
were behind — the "one peer dialled 122× while the wallet held 0 peers" churn, paid again
once per launch.

- `BRPeerPenaltySerialize` / `BRPeerPenaltyDeserialize` — pure helpers beside the existing
  predicate in `BRPeerPenalty.h`, so the wire format is testable standalone
  (`peer_penalty_kat`, red before green).
- Deadlines are **absolute**, and both directions drop lapsed entries: a blob written days
  ago expires on read rather than re-penalizing a peer that has long since recovered.
- Fails closed on bad input — an undersized buffer writes nothing rather than a truncated
  blob; a short or malformed blob restores nothing. This input comes off disk.
- Held at bridge level next to the own-node pin, so a `forceReconnect` recreate inherits
  it. Saved on teardown **and** every 30 ticks, because a process the OS kills never
  reaches `onDestroy` — and that wallet is the one that most needs to come back up without
  re-dialling known-bad peers.

## 5. If the battery goal is still wanted

The spec's underlying goal — less radio wake time — is legitimate; its proposed mechanism
just targets a behavior the wallet does not have. Directions that fit what the code
actually does:

1. **Measure first.** No radio-wake or connection-count baseline exists for a fixed sync
   workload. Every number in §6's acceptance criteria is currently unmeasurable.
2. **Attack the door-refusal rate, not the pool size** — that is where connections are
   being wasted (the capacity levers already queued in the v4.0.35 stabilization spec).
3. **Reduce keepalive traffic**, not connection count: the 10s ping across up to 8 peers
   is the standing radio cost, and it is tunable without touching redundancy or quorum.
