# CF peer fan-out + per-peer retry keying

> ## ⚠ PROVISIONAL — PREMISE LARGELY REFUTED, DO NOT IMPLEMENT AS WRITTEN
>
> Written before Stage 0 and the receive-path instrumentation landed. Both undercut it:
>
> - **Peers are not the bottleneck.** A bare Python socket pulls **1000/1000 filters in
>   0.2–1.3 s** from these same oracles (6/6 peers, 100%). One peer can saturate us
>   instantly; fanning out to more would not help.
> - **Nothing is lost on receipt.** `-DCF_RECV_DIAG` accounting shows
>   `recv == evaluated` EXACTLY across 5,890 filters, with every one of the six exit
>   counters at zero and `unaccounted=0`.
> - **The real defect is the progress model, not the transport.** Measured on hardware:
>   **5,259 filters evaluated → 348 blocks credited (15:1)**, with a single healthy
>   outstanding height (`h=23900348`, `hashResolvable=1`, `attempts=2/5`) gating ~4,900
>   completed blocks, because `_cfLedgerAdvance` caps `scannedThrough` at
>   `min(outstanding[0], gaveUp[0]) - 1`.
>
> The `(height, peer)` retry keying in §3.3 may still be worth salvaging — asking a
> second peer beats waiting out a 30/60/120 s ladder. Everything else here targets a
> bottleneck that measurement says does not exist.
>
> Superseded by: credit completed ranges above a hole while recording the gap
> explicitly, so completeness stays provable without one straggler masking thousands of
> finished blocks.

**Status:** spec, not implemented. Written 2026-08-02 against measurements from a fresh-wallet
run on a Note 8 (SM-N950U), build v4.0.31 with the forward-gap gate removed.

**Problem in one line:** every `getcfilters` goes to ONE peer, and retry backoff is keyed on
the HEIGHT rather than on `(height, peer)` — so a single unanswered height parks the whole
scan for minutes while other connected CF peers sit idle.

---

## 1. The measurement that motivates this

Fresh wallet, floor 23,900,000, tip ~23,928,000. Frontier froze at `scannedThrough=23900723`
for >3 minutes on a single height:

```
[CF-PIN] h=23900724 where=outstanding offerable=1 attempts=3/5 cycles=0
         due=1(reqAt=1785673756 now=1785673798) blockFloor=23900580 belowFloor=0
         hashResolvable=1 cfPeers=4 tip=23928924 scanned=23900723 outstanding=3682 gaveUp=0
```

Everything on our side is healthy: the block is resident, the hash resolves, the height is
below no floor, it is due, and FOUR CF-capable peers are connected. The height simply is not
answered, and we wait.

Cost accounting, from `BRCFScanLedger.h:155-157`:

```
CF_REREQ_BASE_SECS        30
CF_REREQ_BACKOFF_CAP_SECS 120     delay = min(BASE << attempts, CAP) → 30/60/120/120/120
CF_REREQ_MAX_ATTEMPTS      5
```

=> **~7.5 minutes of wall clock before one stubborn height even reaches `gaveUp`**, and only
then can the B2 valve abandon it and release the frontier. Observed sustained scan rate across
the run: ~317 blocks/min, in bursts punctuated by these multi-minute single-height stalls.

Meanwhile ~3,700 filters sit fetched-and-evaluated but uncredited, because `_cfLedgerAdvance`
caps `scannedThrough` at `min(outstanding[0], gaveUp[0]) - 1` — one hole at the prefix edge
gates everything above it.

**This is not bandwidth, CPU, or verification.** Arrival health was already proven (F3
refuted: 1181/1181 evaluated, 0 failures). It is serialisation plus deliberate waiting.

## 2. Where the serialisation lives (verified)

- `_BRPeerManagerRequestCFiltersWithStopHashLocked` (**BRPeerManager.c:5550-5559**): target =
  `preferred` if serviceable, else scan `connectedPeers` BACKWARDS and **break on the first**
  CF-capable peer. With a stable peer list that is the same socket every time.
- Forward driver (**:4538-4546**): identical break-first loop.
- Residual driver Pass A (**:4356-4364**): has rotation, but only "avoid the peer this hole's
  lowest height last went to", then break-first again. Not round-robin.
- **No per-peer in-flight counter exists.** `CF_OUTSTANDING_MAX` (4096) /
  `CF_OUTSTANDING_LOWWATER` (3072) are GLOBAL. `PEER_MAX_CONNECTIONS = 8`.

Prior art (neutrino, Wasabi, Kyoto) all cap in-flight at **one batch per peer** and fan across
every connected peer. We have the inverse on both axes.

## 3. Design

### 3.1 Per-peer in-flight cap
Add `uint8_t cfInFlightBatches` per connected peer (or a small side table keyed by
`(address, port)` so it survives the peer-object churn). Cap at **1** batch
(`MAX_CFILTERS_RESULTS` heights) in flight per peer. Decrement on the answering exit of
`_peerRelayedCFilter` — note the existing in-flight decrement bug class: it must sit ABOVE
every early return, not at the bottom of the function.

### 3.2 Round-robin assignment
Replace both break-first loops with a rotating cursor over `connectedPeers`, skipping peers
that are not CF-capable, not socket-open, or already at their in-flight cap. A tick that has
N eligible peers issues up to N batches to N distinct sockets.

### 3.3 `(height, peer)` retry keying — the bigger win
Today `outstanding[i]` carries one `attempts` counter and one `requestedAt`. Backoff therefore
punishes the HEIGHT for a specific peer's silence.

Change to: record which peers have been *asked and not answered* for this height (a small
bitmask/short list is enough — we only ever hold up to `PEER_MAX_CONNECTIONS` = 8). Retry
policy becomes:

1. If an un-asked eligible peer exists → ask it **immediately** (no backoff).
2. Only when every currently-connected eligible peer has been asked and failed → start the
   existing exponential backoff, and clear the asked-set when the peer set changes materially.

Expected effect on the measured case: 4 peers tried in seconds instead of 30/60/120s against
effectively one. A height that is genuinely unservable still reaches `gaveUp` and the valve,
just far sooner and with real evidence behind it (`offersReachedLivePeer` becomes meaningful —
it currently reads 0 in the trace above).

## 4. Risks, explicitly

**R1 — retention floor.** `_BRPeerManagerClearMemory` keys retained block headers on
`BRCFScanLedgerLowestNeededHeight`. Fan-out deliberately makes the contiguous prefix lag
FURTHER behind the fetch cursor, so the retained header span grows. `CF_RETENTION_SPAN_MAX`
(150000) clamps it, but the clamp buys its bound by making sub-floor heights unrequestable —
i.e. it trades memory for coverage. **This interaction must get a host KAT before shipping**,
not an assumption.

**R2 — do not add a cursor brake keyed on the frontier.** That was the forward-gap gate,
removed 2026-08-02: it deadlocked because a frozen frontier latched it shut permanently. The
surviving brake (`outstanding < CF_OUTSTANDING_LOWWATER`) is correct precisely because it keys
on a quantity that drains on every arrival. Any new throttle must have the same property.

**R3 — fairness vs the abandonment valve.** B2 abandons a height "refused by every connected
CF peer across N re-arm cycles". Asking more peers faster makes that evidence stronger, but
`CF_CONVOY_REARM_MAX` (2) may now be reached much sooner in wall-clock terms. Re-check that a
merely-slow height cannot be abandoned just because we asked everyone quickly.

**R4 — request amplification.** N peers × 1 batch each is N× the outbound rate. Bounded by
`PEER_MAX_CONNECTIONS` (8) and the existing LOWWATER, but worth logging.

## 5. Gates (red-before-green, per project discipline)

1. **Fan-out actually fans out** — one tick with N eligible peers issues batches to N DISTINCT
   `(address, port)`. RED on the current break-first shape (all to one peer).
2. **Per-peer cap holds** — a peer at its cap is skipped, never double-loaded. RED without the
   counter.
3. **Un-asked peer beats backoff** — a height whose only asked peer went silent is re-offered
   IMMEDIATELY while an un-asked eligible peer exists. RED on the current height-keyed backoff
   (must wait `CF_REREQ_BASE_SECS`).
4. **Backoff still applies once exhausted** — with every peer asked and silent, the 30/60/120
   ladder resumes. Guards against turning the fix into an unthrottled retry storm.
5. **Retention bound under fan-out** (R1) — deep descent with N peers keeps resident headers
   bounded. This is the one most likely to fail.

## 6. What this does NOT address

- `getcfcheckpt` is still unused (`BRPeerSendGetCFCheckpt` has zero call sites repo-wide).
- The 476 CF checkpoints remain observe-only (`BRPeerManager.c:2930`), so the filter-header
  chain is still TOFU-anchored on one peer's claimed `prevFilterHeader`.
- The strict contiguous-prefix watermark stays. Parallel FETCH is in scope; parallel
  COMPLETION ACCOUNTING is not, and should not be — the single watermark is what proves no
  height was silently skipped.
