# BIP158 Filter-Chain Re-Anchor — Design

**Date:** 2026-06-07
**Status:** Approved (design), pending implementation
**Component:** `native/` (BRPeerManager.c/.h, JNI bridge) + `app/` (SyncService.kt)
**Related:** `project_peer_pool_wipe_stuck_loop` (memory); core pin `abeb1f4` (filter-first + retention + serialization); watchdog in `SyncService.startBip158Watchdog`

## Problem

On wallets that ran before the BIP158 connectivity fixes, the persisted compact-filter
tip (`cfTip`) can sit far **below** the block-sync floor. Concrete repro on the Note8:
`cfTip = 23513856` while block sync resumes from the saved-block tail at
`blockFloor ≈ 23567563` and syncs *forward* — it never re-downloads the gap
`[cfTip, blockFloor]` (~54k blocks).

The cfheaders driver derives each batch's stop hash by walking `prevBlock` links from
`lastBlock` down to the target height. The gap blocks were never downloaded this
session, so `_BRPeerManagerBlockHashAtHeight` for the next batch
(`cfTip + 1 ... cfTip + MAX_CFHEADERS_RESULTS`) returns zero forever →
`cfheaders: no block hash for height H, deferring` → the watchdog eventually falls back
to bloom. The wallet still works (via bloom), but BIP158 never engages.

This divergence is a **scar** from the original no-filter-peer bug: cfheaders never kept
pace, so `cfTip` fell far behind block persistence. **Fresh wallets never hit it** — with
the four shipped fixes (filter-first connect, header retention, cfheaders serialization,
watchdog catch-up tolerance) `cfTip` stays in lockstep with `blockTip` and no gap forms.

## Goal

Let legacy deep-deficit wallets recover BIP158 by **re-anchoring** the filter-header
chain at the block floor and syncing filters forward from there — instead of falling back
to bloom permanently.

## Key decisions (settled)

1. **Skip the historical gap, do not re-download it.** The gap blocks were already scanned
   by bloom in prior sessions (`has_synced = true`; balance/txs reflect them). They are
   historical, not unaccounted-for. The re-anchor discards the stuck chain and starts a
   fresh one at the floor; the gap's filters are simply never fetched.
2. **Single-peer TOFU anchor.** Re-anchor exactly like the existing birth anchor: take one
   filter peer's `prevFilterHeader` at the floor on trust. No new trust assumption beyond
   what the initial anchor already makes. Worst case from a malicious peer is a filter
   mismatch (caught later → re-anchor/fallback), never a fund-safety issue. (`getcfcheckpt`
   cross-verification is a possible future hardening for both the birth anchor and the
   re-anchor, tracked separately — out of scope here.)
3. **Watchdog (Kotlin) owns the trigger + the `has_synced` safety gate.** `has_synced`
   lives in Kotlin and is the correctness gate (proof the gap was bloom-covered), so the
   trigger belongs there. The C core provides only the re-anchor mechanism.

## Architecture

### Component 1 — Trigger & safety gate (`SyncService.startBip158Watchdog`, Kotlin)

The watchdog already detects the exact condition: its **"headers caught up to the network
tip but cfTip stuck"** branch (the genuine-filter-failure path). That condition *is* "cfTip
is below the block floor" — cfheaders can't get its stop hash even though blocks are synced.

Change that branch so that, before falling back to bloom:

```
if (hasReachedSynced /* gap already bloom-scanned */ && !reanchoredThisSession) {
    val reanchored = NativeBridge.reanchorCompactFilterChainAtFloor()
    if (reanchored) {
        reanchoredThisSession = true
        // Kotlin owns SharedPreferences — clear the stale persisted chain so a
        // kill before the first re-anchored append doesn't restore the stuck cfTip.
        getSharedPreferences("dgb_sync_data", MODE_PRIVATE)
            .edit().remove("saved_filter_headers").apply()
        log("re-anchored filter chain at block floor — staying on filters")
        continue   // give cfheaders a fresh chance on the next poll
    }
}
// not synced, already re-anchored once, or not actually below floor → bloom fallback
fallbackToBloom(); ...
```

- `reanchoredThisSession` (a watchdog-coroutine-local `var`, reset per sync session) caps
  the attempt to **once per session** — so a poll landing before the first re-anchored
  append (cfTip not yet jumped) won't re-fire the re-anchor every 15s. After the single
  attempt, if cfheaders recovers, the watchdog's healthy-exit fires naturally (cfTip jumps
  to the floor on the first append → `cfAdvancedSinceStart` true → gap shrinks → healthy);
  if it doesn't recover, the next stuck cycle falls back to bloom as today.
- The `has_synced` gate ensures we only skip the gap when bloom already covered it.
- This reuses the watchdog's existing detection/gating and never fires during initial
  header catch-up (a different branch stays on filters while headers climb).

### Component 2 — Re-anchor mechanism (`BRPeerManager.c`, C core)

New public function:

```c
// Returns 1 if it re-anchored the compact-filter chain at the current block
// floor (cfTip was below the lowest contiguous downloaded block), 0 otherwise.
int BRPeerManagerReanchorCompactFilterChainAtFloor(BRPeerManager *manager);
```

Behaviour (under `manager->lock`):
1. If `syncMode == BLOOM_ONLY` or `!compactFilterChain` → return 0.
2. Compute the **block floor**: walk `prevBlock` from `manager->lastBlock` through
   `manager->blocks` until the walk dead-ends; the last reachable block's height is the
   floor. One-time `O(chain length)` cost, only at re-anchor.
3. Let `next = BRCompactFilterChainNextHeight(compactFilterChain)` (= cfTip + 1).
   If `next >= floor` → not actually below the floor → return 0 (defensive; shouldn't
   happen when called from the watchdog branch).
4. Otherwise **re-anchor**:
   - `BRCompactFilterChainFree(compactFilterChain); compactFilterChain = NULL;`
   - `autoFetchCFiltersStart = floor;`
   - `autoFetchCFiltersThrough = floor - 1;` (so cfilters re-fetch from the floor forward)
   - `cfHeadersRequestedThrough = 0;` (clear the serialization in-flight guard)
   - Kick a cfheaders request at a filter-capable peer (`_BRPeerManagerAnyFilterCapablePeer`
     → `_BRPeerManagerRequestNextCFHeaders`) so recovery starts immediately.
   - Return 1.

### Component 3 — Forward recovery & persistence

After the discard, the **existing lazy-create path** in `_peerRelayedCFHeaders` does the
rest with no new code: the next `getcfheaders [floor..]` response TOFU-creates a fresh
chain anchored at `floor` with the peer's `prevFilterHeader` (lines ~1944-1948 today —
identical to the birth anchor). cfheaders then syncs forward to tip; retention +
serialization (already shipped) keep that clean. `saveFilterHeaders` overwrites the stale
persisted chain on the first append.

To avoid restoring the stuck cfTip if the app is killed in the ~1s before that first append,
the **Kotlin watchdog** clears the `saved_filter_headers` SharedPreferences key on a
successful re-anchor (Component 1) — Kotlin owns SharedPreferences, so the C core does not
touch it. Self-healing would otherwise re-fire the re-anchor on the next launch anyway, but
clearing is tidier.

### Component 4 — JNI bridge

- `jni_peer.c`: `Java_..._reanchorCompactFilterChainAtFloor` → guards `g_peerManager`
  non-null, calls `BRPeerManagerReanchorCompactFilterChainAtFloor`, returns its `jint`
  result. No pref access from the C side (Kotlin clears the pref per Component 1).
- `NativeBridge.kt`: `external fun reanchorCompactFilterChainAtFloor(): Boolean`.

## Error handling / edge cases

- **Premature re-anchor:** impossible — the trigger only fires from the watchdog's
  caught-up-but-stuck branch, never during initial header catch-up.
- **Re-anchor doesn't help (bad floor, bad peer):** the `reanchoredThisSession` flag caps
  it to one attempt per session; the watchdog's normal stuck timer then expires next cycle
  and falls back to bloom. No infinite loop.
- **No filter-capable peer connected at re-anchor time:** the kick is a no-op; the next
  block-extend kick retries once a filter peer connects (filter-first makes that prompt).
- **`has_synced == false`:** never re-anchor; fall back to bloom (don't risk skipping
  unscanned gap txs).

## Testing

- **Live repro:** the Note8 legacy wallet (`cfTip 23513856`, floor ~23567563). Success =
  `cfheaders: chain extended` climbing from ~the floor to tip, watchdog reaching `healthy`,
  **no bloom fallback**, and the privacy-degraded banner staying off.
- **Unit (C):** extend `BRCompactFilterChainTests` to cover discard + lazy re-create
  (anchor at a new start height, append continues). Re-anchor floor computation can be
  exercised with a synthetic sparse block set.
- **Regression:** confirm a healthy wallet (cfTip ≈ blockTip) never triggers the re-anchor
  branch (the `next >= floor` guard returns 0).

## Out of scope

- `getcfcheckpt` cross-verification of anchors (future hardening, applies to birth anchor too).
- Re-downloading/re-scanning the historical gap (explicitly rejected — relies on prior bloom scan).
- Any change to fresh-wallet behaviour (already correct via the four shipped fixes).
