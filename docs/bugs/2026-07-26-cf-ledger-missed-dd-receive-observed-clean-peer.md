# CF-scan wedge → missed DigiDollar receive — observed on-device against a CLEAN own-node

**Date:** 2026-07-26
**Build:** v4.0.23 + CF scan ledger Phase 1 (observe-only, `CF_LEDGER_DRIVE_REREQUEST=0`)
**Device:** emulator-5554 (Android 15), diagnostic wallet, pinned **exclusive** to a local
DigiByte 8.26.2 node (`10.0.2.2:12024`) — canonical `main` chain, `basic block filter index`
synced to tip, ping 0.001s, never disconnected for the whole run.

## Result: the missed-DD-receive bug reproduced end-to-end, root-caused by the ledger

A real **$1.00 DigiDollar** was sent from a separate wallet (Galaxy S25 Ultra, v4.0.23) to the
emulator's DD address `DD1vVMpNp23PmowCdBGfqhRe9bY9yRy3VpgHn2A9DASw9f9YTGRq`.

On-chain (confirmed via the local node):
- txid `813d6e46e1788e00c3a262776c3d59ed9a944860521821959a849193321ec76e`
- **block 23,920,918**, blocktime 2026-07-26 11:24:33 UTC
- DD output = P2TR `dgb1pt8553gjh3kcj0nywg2erp48znpp72uhk0yeuzehjchrpfhnak4gsnuumxq`
  (`5120…de7db551`), **value 0.00000000** (0-value DD output)
- `OP_RETURN 6a02444401020164029001` → "DD" marker + amount `0x64 = 100` cents = **$1.00**

The wallet **never credited it** (DigiDollar stayed $0.00, "No transactions yet"), despite the
UI reading **"✓ Own node serving · Connected · Block 23,920,927"** — i.e. the header/UI layer
looked fully healthy.

## Why — the ledger names it

The cfilter *evaluation* wedged after the first ~1000-block batch and never recovered, even
though the peer was flawless:

```
cf-ledger: scannedThrough=23899999  outstanding=1001  gaveUp=0  pending=0   (frozen 11:10→11:27+)
last cfilter evaluated: block 23901000            (frozen since 11:10:03)
cfheaders chain: still extending to tip 23,920,927 (headers layer fine)
```

- `scannedThrough=23,899,999` = birth_floor(23,900,000) − 1: a **header-race hole at the very
  first block** blocks the contiguous high-water mark from advancing at all.
- `outstanding=1001`: filters requested for the birth-floor batch, dropped (header-race /
  verify / parse / the cursor advancing past them), and — Phase 1 — **never re-requested**.
- The DD's block (23,920,918) is ~20k blocks **above** the frozen cursor, so its filter is never
  even requested → the receive is invisible.

## Significance

This wedges against a **perfect peer** (0.001s ping, never dropped). That isolates the failure to
the **client-side cfilter auto-fetch cursor** (drop-and-never-requeue) — NOT peer quality, NOT
network. The header/UI layer masks it: "synced, own node serving" while receives are silently lost.

## Phase 2 hypothesis (the fix)

Flip `CF_LEDGER_DRIVE_REREQUEST` → 1 so `outstanding` filters are re-requested with backoff.
Expected on the same wallet/node: `outstanding` drains to 0, `scannedThrough` advances past the
floor to tip, block 23,920,918 is scanned, the taproot filter matches, the block is fetched, and
the **$1 DD is credited**. Retry constants to sanity-check against THIS hole shape: header-race
holes cluster at the birth floor (the dominant class here), batch ~1001.

## Follow-up: recovery path + two adjacent bugs (2026-07-26)

- **Reconcile recovered the DD — after a backend restart.** "Scan for missing funds"
  (`ChainReconciliation` / `DgbNodeClient` → `api.digiscope.me/api/wallet/reconcile`) first
  returned **HTTP 504 Gateway Timeout** (took ~3 min, then failed). After `pm2 restart
  digiscope-backend` on the VPS, a re-run finished in ~8s and imported 3 txs incl. the DD:
  `registerRawTransaction: registered tx at height=23920918` → `history-recovered 813d6e46…` →
  wallet shows **DigiDollar $1.00 + 271.21 DGB**. Proves the DD is genuinely at the wallet's
  address; ONLY the native CF scan failed to find it.
- **BUG (backend infra):** the reconcile endpoint 504s under load (known ElectrumX slot-leak,
  `project_backend_reconcile_slot_leak`). pm2 shows digiscope-backend at **10 restarts / 24h** —
  unstable. Restart clears leaked slots but recurs. Needs a real slot-timeout/pooling fix.
- **BUG (app UX):** the reconcile 504 is **silently swallowed** — the "Scan" button just resets
  with NO user-facing error (matches `feedback_silent_network_catch_anti_pattern`). User sees
  "nothing happened." Surface the HTTP failure.
- **BUG (doc copy):** the "Scan for missing funds" help text still says *"SPV (bloom filters +
  peer merkleblocks)"* — bloom was EXCISED in v4.0.0 (CF-only). Stale; should read compact filters.
- **NOTE (sovereignty):** reconcile defaults to `api.digiscope.me` (author backend), not the
  paired own-node. "Use my own node" exists but is off by default — the roadmap sovereignty gap.

## Hole-shape analysis (2026-07-26) — revises the Phase-2 driver model

**Cap-overflow check (was 1001 cap-clipped?): NO.** No `CF_OUTSTANDING_MAX` overflow / drop-oldest
LOGW fired anywhere in the captured logcat; max `outstanding` ever recorded = exactly 1001; the
4096 cap was never approached. **1001 is the genuine hole count — no Phase-1 fidelity gap.**

**The shape is a BULK FLOOR CLUSTER, not the tip-race trickle the design assumed.** The re-anchor
issued ONE ranged request — `getcfilters type 0 from height 23900001 to <stop ~23901000>` (~1000
filters in a single BIP157 startHeight→stopHash message). At re-anchor the cfheaders chain wasn't
yet built down to the floor, so ~1000 responses were header-race-dropped **at once** → 1001
outstanding in one shot. The design's 10s header-race retry was sized for a *single* header
arriving a beat late; the real failure is a *thousand heights outstanding from one wedged batch*.

**Consequences for Phase 2 (feed into the plan):**
1. **Re-request must COALESCE into ranges, not iterate heights.** The original request is already
   ranged (`getcfilters(startHeight, stopHash)`); the driver must re-issue ranged getcfilters over
   contiguous outstanding runs. Driver's unit of work = a RANGE, not a height. (1000 single-height
   retries would hammer peers and take forever.)
2. **Attempt-cap/backoff must be per-(range, peer) with FORCED rotation between attempts.** If one
   wedged peer caused the 1000 holes and the cap parks them all into gaveUp after 5 attempts at the
   SAME dead peer, Phase 2 recreates permanent loss with extra steps. Rotate peers between attempts.
3. **Re-anchor-forward abandons floor holes.** Observed: after the 11:38 reconcile registered txs
   at high heights, the CF cursor re-anchored to ~23,920,055 and outstanding drained to 0 — the
   floor holes were skipped, not scanned. The driver must own hole recovery so forward progress
   never silently abandons an un-scanned range.

## Phase-2 acceptance gate (write into the design doc verbatim)

Reproducible end-to-end test against a REAL mainnet tx: same seed, same pinned own-node
(`10.0.2.2:12024`, local node `listen=1`), same on-chain $1 DD (block 23,920,918, txid
`813d6e46…`). **Wipe wallet state → restore → sync.** PASS iff: the DD credits via **CF alone
(no reconcile)**, the ledger **drains to outstanding=0**, and `scannedThrough` reaches tip.
Steady-state metrics: `gaveUp` stays 0 over a normal week; `outstanding` drains to 0 within
minutes of any network blip; zero manual "Scan for missing funds" needed.

## Overflow-fidelity — DEFINITIVE (empirical, from the ledger count series)

The "loud overflow log" the design assumed **does not exist**: `_cfLedgerInsertOutstanding`
(BRCFScanLedger.c:111) is `void`, and `BRCFScanLedgerRecordRequested` just loops it — the caller
cannot detect a drop, so an overflow would be **SILENT** (a latent Phase-1 gap). So a log grep is
not the right instrument. The answer comes from the ledger's own `outstanding` count series
(getCfScanLedgerCounts, sampled ~15s):

```
11:04:30 outstanding=0     (before the re-anchor batch)
11:09:21 outstanding=1
11:09:51 outstanding=853   (batch filling)
11:10:06 outstanding=1001  (plateau)
11:17 / 11:27 / 11:37 outstanding=1001   (frozen ~28 min, cfilter eval frozen → nothing drains)
11:47 / 11:57 outstanding=0   (drained after 11:38 reconcile re-anchored the cursor forward)
```

`outstanding` is hard-capped at `CF_OUTSTANDING_MAX=4096` (drop-oldest keeps it ≤4096) and would
**pin at 4096** the instant overflow fired. It climbed monotonically to exactly 1001 and held there
for ~28 min with nothing draining, never approaching 4096. **Therefore overflow NEVER fired — 1001
is a true measurement, not a cap-clipped floor.** No fidelity gap in THIS capture.

**But the unwired log is a real gap for the future:** the item-3 20k-block re-anchor gap (§Hole-shape)
WOULD exceed 4096 if the cursor un-wedges before the driver drains. **Phase-2 fix (fold into the
back-pressure EDIT):** make `_cfLedgerInsertOutstanding` signal the drop (return the dropped height or
a bool) so the caller emits the loud `LOGW`, AND the `CF_OUTSTANDING_LOWWATER` gate keeps the count
away from the cap in normal operation. Silent truncation at 4096 must not be possible.
