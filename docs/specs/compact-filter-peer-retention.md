> ## Disposition — INVESTIGATED; most premises refuted; one real gap built
>
> This spec is investigation-first and says the code wins if they disagree. **They disagree
> on most points**, and the code won:
>
> - "Churns through ~8 peers, cycling connections" — `PEER_MAX_CONNECTIONS = 8` is a
>   CONCURRENT pool, not a carousel. There is no elapsed-time rotation to remove.
> - "Proactively pinging to assess quality" — the keepalive is a liveness ping. It measures
>   nothing and rejects nobody.
> - "Persist proven-good peers across sessions" — already shipped.
> - **"Sticky 2 ACTIVE + 1 STANDBY" was NOT built, deliberately.** Measurement showed 44% of
>   connection attempts are refused at the door by the peer — slots are scarce to GET, not
>   plentiful and squandered. Shrinking the pool to 3 against that refusal rate walks into
>   the 0-peer wedge, and 2 peers destroys the filter-header quorum this same spec asks for
>   in §4.
>
> **Built:** §5's genuine gap — re-dial penalties now persist across launches, with the
> canon filter fleet exempt so a cold start can never starve itself (v4.0.36).
>
> The battery goal remains legitimate; the mechanism was aimed at a behaviour the wallet
> does not have. Findings and the directions that do fit:
> `docs/superpowers/specs/2026-08-16-peer-retention-findings.md`.

# Spec: Compact Filter Peer Retention

**Status:** Draft / investigation-first
**Priority:** P2 — independently landable, nothing depends on it

---

## 1. Problem

The BIP157/158 sync layer churns through roughly eight peers, cycling connections and
proactively pinging to assess quality. On mobile this is the wrong shape:

- **Battery and radio cost.** Connection setup, handshake, and keepalive traffic across
  many peers keeps the radio awake far more than holding two stable sockets.
- **Wasted warm state.** A peer that has proven it serves filters promptly and correctly
  is a scarce resource. Dropping it to try another discards earned trust.
- **Churn masks real failure.** When cycling is normal, an actual bad peer is harder to
  distinguish from routine rotation.
- **Redundant validation.** Every new peer requires re-establishing filter header
  agreement before its data is trustworthy.

---

## 2. Target design

**Hold two proven-good filter peers. Keep one warm standby. Fail over on actual failure,
not on a timer.**

### Peer states

| State | Meaning |
|---|---|
| `ACTIVE` | Connected, validated, serving filters. Target count: **2**. |
| `STANDBY` | Connected, handshake complete, filter headers agreed, but not being asked for filters. Target count: **1**. |
| `CANDIDATE` | Known address, not yet connected or not yet validated. |
| `PENALIZED` | Failed. Backed off with an expiry; not eligible for promotion until it clears. |

### Lifecycle

1. On sync start, connect out to candidates until 2 ACTIVE + 1 STANDBY are established.
2. ACTIVE peers serve all filter requests. Hold these connections indefinitely — there is
   no rotation timer and no proactive quality re-probe.
3. STANDBY stays connected and keeps its filter headers current, so promotion is instant
   and requires no re-validation. It is warm, not merely known.
4. On an ACTIVE peer failing (see triggers below), demote it to PENALIZED, promote
   STANDBY to ACTIVE immediately, and begin establishing a replacement STANDBY in the
   background.
5. A PENALIZED peer becomes a CANDIDATE again after backoff expiry. Repeated penalties
   escalate the backoff.

### Why two active rather than one

Two gives cross-validation of filter headers without the cost of a crowd. If the two
ACTIVE peers disagree on a filter header for a given height, that is a signal worth
acting on — see §4.

---

## 3. Failure triggers (what actually counts as failure)

Failover fires on demonstrated failure, not on suspicion:

- Socket close / connection reset.
- Request timeout exceeding threshold, N consecutive times (define N; suggest 2–3, not 1
  — mobile networks produce transient stalls that are not the peer's fault).
- Malformed or unparseable filter response.
- Filter header mismatch against the other ACTIVE peer or against a checkpoint.
- Peer advertises insufficient service bits for BIP157 (`NODE_COMPACT_FILTERS`).
- Sustained throughput below a floor while the device has a healthy connection.

**Explicitly not a trigger:** elapsed time on the connection, or a routine latency
measurement that is merely worse than another peer's.

### Network transitions

A device moving from WiFi to cellular, or waking from doze, will drop sockets. This must
not be interpreted as peer failure and must not penalize peers. Detect the transition and
reconnect to the *same* peer set first before treating anything as a peer fault.

---

## 4. Disagreement handling

If the two ACTIVE peers return different filter headers for the same height:

1. Do not silently prefer either.
2. Promote STANDBY and use it as a tiebreaker.
3. Penalize the minority peer, hard.
4. Surface the event in diagnostics/logs — persistent disagreement is a signal about the
   network, not just a connection nuisance, and given the Groestl-era fork history it is
   worth being able to see.

Investigation: confirm whether filter header checkpoints are currently compiled into the
app. If so, a peer disagreeing with a checkpoint is an immediate hard penalty and no
tiebreaker is needed.

---

## 5. Peer selection and persistence

- **Persist proven-good peers across app sessions.** A peer that served us well last
  session should be the first candidate this session. This is where the retention model
  pays off most — cold start goes straight to known-good.
- Persist per-peer: address, last-good timestamp, cumulative success/failure counts,
  observed service bits, current backoff expiry.
- Prefer peers that support BIP157 *and* are reachable over the wallet's configured
  transport.

### Tor interaction

The wallet is Tor-by-default. Circuit establishment cost is significant, which
**strengthens** the case for sticky connections — each connection is more expensive to
build, so discarding one is more wasteful. Two things to confirm:

- Whether ACTIVE peers should be isolated on separate circuits (stream isolation) to
  avoid correlating our filter requests across peers.
- Whether circuit failure is being conflated with peer failure. It should not be; a dead
  circuit to a good peer should trigger circuit rebuild, not peer penalization.

---

## 6. Acceptance criteria

1. Steady-state sync holds exactly 2 ACTIVE + 1 STANDBY, with no rotation over a long
   idle period.
2. Killing one ACTIVE peer results in failover to STANDBY with no user-visible sync
   interruption, and a replacement STANDBY established in the background.
3. Killing both ACTIVE peers simultaneously degrades gracefully and recovers.
4. WiFi→cellular transition reconnects to the same peers without penalizing them.
5. Measured reduction in connection count and radio wake time versus current behavior
   over a fixed sync workload.
6. Injected filter header disagreement triggers tiebreak, penalty, and a diagnostic entry.
7. Known-good peers are reused on cold start after app restart.

---

## 7. Out of scope

- BIP37 SPV fallback path behavior.
- Changes to filter matching or block download logic.
- Peer discovery mechanism itself (DNS seeds, hardcoded, etc.) unless investigation shows
  it prevents persistence.
