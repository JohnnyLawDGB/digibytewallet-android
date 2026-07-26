# Wedge baseline — "hole in the wild" — 2026-07-25 ~10:19 UTC

Captured on emulator-5554 (fresh restore of the diagnostic seed, birth=2026, v4.0.23)
BEFORE any relaunch, per operator instruction. Primary artifact: `full-logcat.txt` (74,106 lines).

## State at capture
- **app pid:** 17439 (alive, foreground) — NOT crashed.
- **real peer sockets** (`/proc/net/tcp` :2EF8=12024, :32E1=13025): **0**.
- **UI (stale):** "Syncing · 8 peers · 95%", "Block 22,823,117 of 23,914,836". The 8-peers/95% is a stale cached mirror; the loop is dead.

## Heights (last BIP158 watchdog line, 10:05:50)
| signal | value | source |
|---|---|---|
| cfTip (filter-header chain tip) | **22,823,117** — FROZEN | `getCFChainTipHeight` |
| blockTip (header chain) | 22,861,879 — still advancing | `getLastBlockHeight` |
| estimated tip | 23,914,847 | `getEstimatedBlockHeight` |
| cf gap to est | ~1.09M blocks unscanned | — |

## Timeline
- 10:03:59–10:04:21 — cfheaders chain extended in 2000-header batches 22,811,117 → **22,823,117** (last success from peer 147.93.171.46). 6 batches.
- 10:04:21 onward — **cfTip frozen at 22,823,117.** Watchdog logs "header sync still catching up" for 289s→319s.
- 10:05:46 / 10:05:51 — cfheaders rotating to untried filter peers (101.103.12.129, then 109.123.231.205) for the NEXT batch `[22823118..22825117]` — none delivered.
- 10:05:51 — `SyncService: Sync error (104): Connection reset by peer`.
- 10:05:57 — mass `sync failed` across all peers (`_peerDisconnected:970`, connectFailureCount == MAX).
- since 10:05:57 — `bread` silent; SyncService poll loop silent since 10:05:51; 0 sockets.

## Mechanism — READ THIS (scoping-critical)
This wedge is a **cfheaders-delivery stall**, NOT the cfilter-cursor drop the CF scan ledger targets. Evidence:
- `cfheaders:` log lines: **65** (chain built to 22,823,117 then stuck fetching the next batch).
- `cfilters: auto-requested` lines: **0** — no cfilter was ever REQUESTED this session.
- `cfilter:` lines: **0** — no cfilter was ever EVALUATED this session.

So the entire region above cfTip is unscanned because the **filter-HEADER chain** couldn't advance (peers stopped answering `getcfheaders` for `[22823118..]`), then all peers reset and the manager gave up. The cfilter (filter-CONTENT) layer never ran.

**Implication for the ledger design (validates §8 watchdog scoping):**
- Had the ledger existed, it would make this OBSERVABLE — `scannedThrough` frozen near the birth floor, a ~1.09M unscanned gap to est — but its `outstanding` set would be **empty** (nothing was requested, so nothing was dropped). The ledger's re-request driver would have **nothing to re-request** here.
- The fix for THIS wedge is upstream: cfheaders rotation / re-anchor + peer-reset recovery — i.e. `bip158WatchdogJob`'s domain. This is the live proof that the ledger does **NOT** subsume `bip158WatchdogJob`.
- The ledger's target (cfilter responses dropped at 2412/2418/2431 while cfheaders advances fine) is a **sibling** failure the DD receive test must exercise with a healthy cfheaders chain — i.e. run the Phase-1 build to the tip so cfilters actually flow, then receive DD and watch for `scannedThrough` holes.

## Tangential open thread (NOT part of the ledger; note for later)
Why were cfilters never auto-requested despite cfheaders extending 12k blocks? Most likely benign: `reqStart = autoFetchCFiltersThrough+1` was still **above** `chainTip` because the birth floor (2026) sits above 22,823,117, so filters correctly weren't requested below birth. Worth a glance when the Phase-1 build reaches a region above the floor — if cfilters STILL don't fire there, that's a separate defect. Track separately from `seq/cf-scan-ledger-observe`.

## Next
Do NOT relaunch until the Phase-1 (observe-only ledger) build is installed. Then relaunch, sync to a region above the birth floor so cfilters flow, and run the DD/asset/DGB receive test against the ledger's hole logs + the new JNI counters.
