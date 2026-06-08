# BIP158 Continuity-Failure Re-Anchor Recovery — Design

**Date:** 2026-06-08
**Status:** Approved (design), pending implementation
**Component:** `native/` (BRPeerManager.c — C core submodule). No JNI/Kotlin changes.
**Target release:** v3.6.2 (patch)
**Related:** filter-chain re-anchor (v3.6.0, pin abeb1f4→b18b78c), watchdog stall-recovery (v3.6.1). Memory: `project_peer_pool_wipe_stuck_loop`.

## Problem

On the Note8, after a reboot, the persisted cfheaders chain restores **divergent**: its tip header at cfTip 23631150 is `b6aa8508…` while every honest filter peer computes the canonical `541fc1de…`. Confirmed by on-device DIAG instrumentation:

```
chain start=23489010 count=142141 next=23631151
peerPrev=541fc1de   chainTip=b6aa8508   (every peer disagrees with our chain)
```

The chain is built **TOFU-style** in `_peerRelayedCFHeaders`: the first cfheaders response anchors the chain at the peer's claimed `prevFilterHeader` with no cross-verification, and subsequent batches only checked against the running tip. If a single divergent/buggy filter peer seeds (or extends) the chain, the whole chain diverges from the canonical majority.

When that happens, every honest peer's `BRCompactFilterChainAppend` fails the continuity check. The current code treats this as the **peer** misbehaving — it marks each one and disconnects it — so the wallet **burns its whole filter pool and falls to bloom**. There is no recovery: the divergent chain persists, and **every restart repeats the failure** (the user's reboot banner). The existing re-anchor doesn't fire because cfTip is near the tip, not below the block floor.

The C serialize/deserialize round-trips correctly (unit test passes) and the Kotlin hex is lossless, so the divergence is in how the chain is **built** (unverified TOFU), not how it's stored. Storage is a separate concern (the 9.2 MB SharedPreferences blob is broken design — unbounded, slow, corruption-prone — tracked as a follow-up, NOT in this fix).

## Goal

When the peer **majority** rejects our chain, recognize that *we* are the outlier and recover (discard + re-sync filters) instead of punishing honest peers and degrading to bloom forever.

## Key decisions (settled)

1. **Recovery only.** Re-anchor when ≥ K distinct peers reject our current tip. Cross-verifying the anchor (prevent divergence) and bounding the persisted chain size are separate follow-ups. The seeder should also validate filter *correctness* before distributing a peer (separate infra task).
2. **K = 2 distinct peers**, **N = 3 re-anchors per sync session** — responsive but safe (re-anchor is self-correcting, so erring low is fine; both tunable constants).
3. **Re-anchor at the block floor**, reusing the existing mechanism. Cheap here: header retention keeps only recent blocks, so a caught-up wallet's floor is near the tip → re-anchor re-syncs only a small recent range. The discarded (divergent) history was already covered by the bloom fallback this bug forces, so no tx-completeness loss.

## Architecture (C core only)

### Component 1 — Refactor the re-anchor into a lock-held helper

Today `BRPeerManagerReanchorCompactFilterChainAtFloor(manager)` takes the lock and inlines the body, guarded by `next >= floor → return 0`. Extract the body:

```c
// Assumes manager->lock is held. If force != 0, re-anchor even when cfTip is
// at/above the block floor (used by the continuity-failure recovery path, where
// the chain is divergent regardless of where cfTip sits). Returns 1 if re-anchored.
static int _BRPeerManagerReanchorAtFloorLocked(BRPeerManager *manager, int force)
{
    if (manager->syncMode == BR_SYNC_MODE_BLOOM_ONLY || !manager->compactFilterChain) return 0;
    uint32_t next  = BRCompactFilterChainNextHeight(manager->compactFilterChain);
    uint32_t floor = _BRPeerManagerBlockFloor(manager);
    if (floor == 0) return 0;
    if (!force && next >= floor) return 0;   // normal path keeps the cfTip<floor guard

    peer_log(&BR_PEER_NONE, "cfheaders: re-anchoring filter chain (force=%d) from tip %u to floor %u",
             force, next > 0 ? next - 1 : 0, floor);
    BRCompactFilterChainFree(manager->compactFilterChain);
    manager->compactFilterChain = NULL;
    manager->autoFetchCFiltersEnabled  = 1;
    manager->autoFetchCFiltersStart    = floor;
    manager->autoFetchCFiltersThrough  = floor > 0 ? floor - 1 : 0;
    manager->cfHeadersRequestedThrough = 0;
    manager->cfDisagreedCount = 0;            // fresh disagreement window
    BRPeer *fp = _BRPeerManagerAnyFilterCapablePeer(manager);
    if (fp) _BRPeerManagerRequestNextCFHeaders(manager, fp);
    return 1;
}
```

`BRPeerManagerReanchorCompactFilterChainAtFloor(manager)` becomes: lock → `_BRPeerManagerReanchorAtFloorLocked(manager, 0)` → unlock → return result (unchanged external behaviour for the watchdog path).

### Component 2 — Disagreement tracking (BRPeerManager struct)

Add near `cfHeadersRequestedThrough`:

```c
// Distinct peers that have failed the cfheaders continuity check since the last
// successful append. If CF_CONTINUITY_REANCHOR_K of them disagree with our tip,
// WE are the outlier (our chain diverged) — re-anchor instead of punishing them.
UInt128  cfDisagreedPeers[CF_CONTINUITY_REANCHOR_K];
uint8_t  cfDisagreedCount;
uint8_t  cfReanchorCount;   // continuity-triggered re-anchors this session (bounded)
```

Constants must go in **`BRPeerManager.h`** (it is `#include`d at the top of `BRPeerManager.c`, before the `struct BRPeerManagerStruct` definition at line 183, which uses `CF_CONTINUITY_REANCHOR_K` as the `cfDisagreedPeers` array size):
```c
#define CF_CONTINUITY_REANCHOR_K   2   // distinct peers that must disagree
#define CF_CONTINUITY_REANCHOR_MAX 3   // re-anchors per session before giving up to the watchdog
```
The struct is `calloc`'d (BRPeerManagerNewEx), so the new fields zero-init automatically. `UInt128Eq` (BRInt.h) and `peer->address` (BRPeer.h, `UInt128`) both exist.

### Component 3 — Rewrite the continuity-fail branch (`_peerRelayedCFHeaders`)

Replace the current "mark misbehavin' + disconnect" action with record-and-maybe-re-anchor:

```c
int ok = BRCompactFilterChainAppend(manager->compactFilterChain, prevFilterHeader, filterHashes, count);
if (!ok) {
    // Record this peer as one that disagrees with our tip (dedup by address).
    int known = 0;
    for (uint8_t i = 0; i < manager->cfDisagreedCount; i++)
        if (UInt128Eq(manager->cfDisagreedPeers[i], peer->address)) { known = 1; break; }
    if (!known && manager->cfDisagreedCount < CF_CONTINUITY_REANCHOR_K)
        manager->cfDisagreedPeers[manager->cfDisagreedCount++] = peer->address;

    manager->cfHeadersRequestedThrough = 0;   // let another peer be tried

    if (manager->cfDisagreedCount >= CF_CONTINUITY_REANCHOR_K &&
        manager->cfReanchorCount < CF_CONTINUITY_REANCHOR_MAX) {
        // The majority disagrees with us → our chain diverged. Discard + re-sync
        // rather than burning honest peers. cfDisagreedCount is reset inside.
        manager->cfReanchorCount++;
        peer_log(peer, "cfheaders: %u peers disagree with our tip — chain is the outlier, "
                 "re-anchoring (attempt %u/%u)",
                 manager->cfDisagreedCount, manager->cfReanchorCount, CF_CONTINUITY_REANCHOR_MAX);
        _BRPeerManagerReanchorAtFloorLocked(manager, 1);
        pthread_mutex_unlock(&manager->lock);
        return;
    }

    // Below the K threshold, or re-anchor budget exhausted: don't append, don't
    // punish (the peer is probably honest). If the budget is exhausted the chain
    // simply stops advancing and the SyncService watchdog falls back to bloom as
    // today — but we never burned the filter pool.
    peer_log(peer, "cfheaders: continuity mismatch (%u/%u disagree, reanchors %u/%u) — not appending",
             manager->cfDisagreedCount, CF_CONTINUITY_REANCHOR_K,
             manager->cfReanchorCount, CF_CONTINUITY_REANCHOR_MAX);
    pthread_mutex_unlock(&manager->lock);
    return;
}
// success: clear the disagreement window (and the temporary DIAG logging is removed)
manager->cfDisagreedCount = 0;
```

Notes:
- We **no longer call `_BRPeerManagerPeerMisbehavin` / disconnect** on continuity failure — that was the peer-burning bug. Genuinely misbehaving peers are bounded by other mechanisms; here the cost of not punishing is at most a wasted batch.
- Resetting `cfHeadersRequestedThrough = 0` on every failure lets the driver re-request, naturally rotating to other peers so distinct disagreers accumulate.
- `cfReanchorCount` is per session (cleared only at sync start / not persisted) so a permanently-divergent peer can't loop forever — after N it stops and the watchdog handles the terminal bloom fallback.

### Component 4 — Remove the temporary DIAG instrumentation

Revert the two `[DIAG]` log additions made during diagnosis: the enhanced continuity log in `BRPeerManager.c` and the restore log in `jni_peer.c`. (The new continuity logs above replace the diagnostic one.)

## Data flow

```
cfheaders response → Append continuity check
  ok → append; clear cfDisagreedCount
  fail →
     record peer in cfDisagreedPeers (dedup); reset cfHeadersRequestedThrough
     distinct disagreers >= K  AND  reanchors < N ?
        yes → reanchorCount++; _BRPeerManagerReanchorAtFloorLocked(force=1); return
        no  → return without appending or punishing (watchdog handles terminal bloom)
```

## Error handling / edge cases

- **We're actually right, a minority peer is bad:** with K=2, two *distinct* bad peers are needed to trigger a (safe) re-anchor; a single bad peer just gets ignored (not appended, not punished). A spurious re-anchor self-corrects by re-syncing to the majority view.
- **Persistently divergent first-responder:** after N re-anchors the chain still can't advance → the SyncService watchdog's genuine-failure path falls back to bloom (today's outcome) — but the honest pool was never burned. The seeder-side filter-correctness validation is the real prevention (separate task).
- **Re-anchor at floor when cfTip > floor:** intended — `force=1` skips the `next >= floor` guard. Floor is recent (retention), so the re-sync is small.
- **Lock discipline:** `_BRPeerManagerReanchorAtFloorLocked` assumes the lock is held; `_peerRelayedCFHeaders` already holds it. The public wrapper takes/releases it.

## Testing

- **Chain unit test** (`BRCompactFilterChainTests`): a chain whose tip diverges from an incoming batch's `prevFilterHeader` returns `0` from `BRCompactFilterChainAppend` (the continuity signal the recovery keys on). The discard+recreate-at-floor data-structure path is already covered by `test_reanchor_restart_at_higher_start`.
- **Device (primary)**: the Note8 reboot repro. Expected: on restart the divergent chain triggers `peers disagree … re-anchoring`, the chain re-syncs from the recent floor, cfheaders advances, and the wallet **stays on compact filters — no privacy banner** and no filter-peer disconnect storm. The manager-level recovery has no unit harness, so this is device-verified (consistent with prior BIP158 watchdog/re-anchor work).

## Refinement — active probe + alignment guard (implemented & device-verified 2026-06-08)

Device testing on the Note8 revealed the K=2 threshold was unreachable with passive
peer rotation alone: after the persisted divergent chain restores, the block rescan
resets the block tip *below* cfTip, so the cfheaders driver goes dormant (`next > tip`)
and never polls a 2nd distinct peer. The disagreement count stalled at 1/2 and the
watchdog fell back to bloom every session (the user's reboot banner).

Two additions fixed it (both in `_peerRelayedCFHeaders`, no JNI/Kotlin change):

1. **Active probe** (`_BRPeerManagerProbeOtherFilterPeersForCFHeaders`). On the *first*
   continuity mismatch (one disagreer recorded, still below K), immediately replay the
   exact `getcfheaders` the current peer just answered to every *other* connected
   filter peer not already in the disagreed set. Their replies are continuity-checked
   against our divergent tip too, so distinct disagreers reach K within ~one round trip
   instead of never. Gated on a fresh add below K and deduped inside the probe, so it
   can't storm.

2. **Height-alignment guard** (top of `_peerRelayedCFHeaders`). Compute the batch's
   claimed start (`stopHeight − count + 1`) and reject any response whose start ≠ where
   the chain expects the next batch. This kills the race where a stale probe reply for
   the *old* contested range lands after the re-anchor has already moved the chain start
   to the block floor — without it, the lazy-create path would anchor a fresh chain at
   the floor but fill it with the contested range's hashes, mislabeling their heights.
   Enforced only when the stop block is known and the batch is non-empty.

**On-device verification (Note8, 2026-06-08):** divergent chain restored at cfTip
23633666 → first mismatch from peer A → active probe to peer B → "2 peers disagree …
re-anchoring (attempt 1/3)" in ~73 ms → re-anchor to block floor 23630123 → alignment
guard rejected the stale in-flight probe reply (`batch start 23633667 != expected
23630123 — stale/misaligned, ignoring`) → chain rebuilt cleanly → `watchdog: healthy
(gap=0)` → sync complete **on compact filters, no bloom fallback, no privacy banner**.
A subsequent relaunch with the now-clean persisted chain went healthy in 15 s.

## Out of scope (separate follow-ups)

- Anchor cross-verification via `getcfcheckpt` (prevent divergence client-side).
- Bounding/relocating the persisted chain (the 9.2 MB SharedPreferences blob).
- Seeder-side filter-correctness validation (infra; prevents distributing divergent peers).
