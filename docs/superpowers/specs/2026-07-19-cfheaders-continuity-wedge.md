# cfheaders continuity / fleet-reliability wedge — root cause + fix status

**Discovered:** 2026-07-19, live on S25 Ultra (v4.0.4) during a rescan-from-birth.
**Symptom:** block-header sync races to tip (8 healthy peers) but the compact-**filter**-header (cfheaders) sync wedges: `cfheaders: 2 peers disagree with our tip — chain is the outlier, re-anchoring (attempt 2/3)` + endless `rotating to untried filter peer for batch [N..N+2000]`, `cfTip=0`. In an earlier instance the peer pool drained to **0**. CF tx-detection never reaches the recent-tx blocks; balance stays correct (served by header sync + persisted state).

Root-caused by a 4-agent workflow (`cfheaders-continuity-investigation`). All line refs vs `native/.../BRPeerManager.c` @ the fix commit.

## Root cause — one causal chain, four broken links

1. **(a) Over-eager continuity quorum — PRIMARY TRIGGER.** The continuity test compares each peer only against **our** tip; `CF_CONTINUITY_REANCHOR_K = 2` treats 2-of-8 (25%) as "we're the outlier." Worse, `_BRPeerManagerProbeOtherFilterPeersForCFHeaders` (~2123) *manufactures* the 2nd disagreer by soliciting confirmers, and the two disagreers are never checked against **each other** (they may be on different forks). The pinned-checkpoint ground truth is observe-only. → falsely fires on a routine 15s/multi-algo reorg-transient.
2. **(b) Non-convergent re-anchor floor — CO-PRIMARY SUSTAINER.** `_BRPeerManagerReanchorAtFloorLocked(force=1)` re-anchors to `_BRPeerManagerBlockFloor` = the moving in-memory block-contiguity frontier (~143 blocks under tip), NOT the divergence height (~380k deep in the restored `FilterHeaderStore` chain). So it re-fetches the identical batch, climbs to the identical divergence, re-diverges. `autoFetchCFiltersStart` only advances on a successful append (never reached).
3. **(c) Unbounded peer drop/rotate — AMPLIFIER.** On a fully-tried unservable batch, `_BRPeerManagerDropStalledFilterPeer` (~2092) disconnects a peer with **no min-peer guard** → shreds the pool to 0, oscillating against the keepalive.
4. **(d) Watchdog blind — MISSED SAFETY-NET.** `startBip158Watchdog` short-circuits at `!blocksCaughtUp` while headers import; the `advanced` signal is fooled true by the native chain oscillating 0→N; `decidePostTimeoutAction` needs `hasReachedSynced` (false on rescan); `reanchorCompactFilterChainAtFloor(force=0)` is a proven no-op here. Stale in-code comments promise a bloom fallback — false (bloom excised v4.0.0). Terminal state after `CF_CONTINUITY_REANCHOR_MAX=3`: chain NULL, cfTip=0, permanent for the session.

## Implemented tonight (SAFE, high-value — converts "permanent fleet-wipe" → "bounded, auto-recovered stall")

These fire ONLY on the stall/disagree/misalign branches; normal CF sync (peers agree, append succeeds) is untouched — the real regression risk, and it IS emulator-reproducible.

- **FIX 1 (native, amplifier (c)):** floor-guard `_BRPeerManagerDropStalledFilterPeer` on `_BRPeerManagerConnectedFilterPeerCount(manager) > CF_MIN_FILTER_PEERS (2)`. The pool can never be shredded to 0; below the floor, keep retrying on survivors and let the keepalive grow it. `BRPeerManager.c` ~2088-2100 + `.h` `CF_MIN_FILTER_PEERS`.
- **FIX 3-leak (native, sustainer, small):** the height-alignment guard (`batchStart != expectedStart`, ~2203) now clears `cfHeadersRequestedThrough = 0` before returning (mirrors the continuity-mismatch path), so a rejected/misaligned response issues a FRESH request instead of pinning the same batch as a timeout-retry forever.
- **FIX 4/5 (Kotlin, safety-net (d)):** a `shouldRecoverFrozenCf` pure gate (unit-tested, `Bip158WatchdogPolicy.kt`) tracks the session running-MAX cfTip (immune to the 0↔N oscillation); if it makes no net progress for `CF_FROZEN_RECOVERY_MS (120s)` while block headers climb, the watchdog recovers ONCE per session: `FilterHeaderStore.delete()` + `forceReconnect()` + `injectPeers()` + `startSync()` — drops the diverged persisted chain and recreates the manager (fresh peers + calloc-fresh CF continuity budget + v4.0.2 sticky auto-fetch re-arm), re-fetching cfheaders from the floor. Placed BEFORE the fooled `advanced` short-circuit and independent of `blocksCaughtUp`.

## HELD for review + on-device fleet verification (changes the security-relevant "who is the outlier" decision — must not be shipped blind)

Cannot be safely verified without the fleet-disagreement scenario (peers transiently disagreeing at the tip boundary), which isn't emulator-reproducible. These STOP the wedge from happening (vs. the above, which make it recoverable).

- **FIX 2 (native, trigger (a)):** replace fixed-`K=2` with a strict majority of *currently-connected* filter peers + an absolute floor (≥3), require the disagreers to **agree with each other** (`_cfDisagreersShareSamePrev`), and promote the observe-only pinned-checkpoint cross-check into a **veto** (`_cfCheckpointVetoesReanchor`: a pinned checkpoint matching OUR header → never re-anchor, ground truth regardless of peer count). Remove/neuter the manufacturing probe (~2277). Strictly *more* conservative (re-anchor becomes harder). Keep the single-peer escape hatch but gate its re-anchor on the checkpoint veto too. Widen `cfDisagreedPeers[]`.
- **FIX 3-terminal-advance (native, sustainer (b)):** after `CF_CONTINUITY_REANCHOR_MAX` is spent, don't fall into permanent "not appending" — snap `autoFetchCFiltersStart` UP toward the nearest trusted pinned checkpoint at/below tip so CF detection resumes for the recent-tx blocks that matter (convergence-by-construction; the loop can't replay the same batch because the start advances). Snap target MUST be a trusted checkpoint, never a peer-supplied height. Delete the stale "falls back to bloom" comments.

**Recommended:** ship FIX 1 + FIX 3-leak + FIX 4/5 first (they make the wedge recoverable now). Then, after review, the FIX 2 checkpoint-veto is the safest+most authoritative single trigger fix; then the majority quorum; then FIX 3-terminal-advance.

## Verification notes
- Native FIX 1/3-leak are submodule edits → fork-push (johnnylaw) + pin-bump before they build into the app. Watch the `peer_log(BR_PEER_NONE)` stack-overflow trap near the re-anchor site — new log lines on those paths must use the peer-less `_peer_log`.
- MUST NOT regress the healthy path: verify a clean rescan (peers agree) still appends and reaches 100% on the emulator + both test devices.
- FIX 4/5: verify the net-max + 120s window does NOT fire on a slow-but-progressing rescan (unit-tested); the once-per-session latch prevents churn.
- Durable sovereign answer for an affected user: own-node CF pairing (consistent cfheaders, no fleet disagreement).
