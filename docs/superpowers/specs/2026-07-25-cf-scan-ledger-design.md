# CF Scan Ledger — per-height compact-filter completeness

**Date:** 2026-07-25
**Status:** Design **APPROVED** 2026-07-25 (decisions locked, §13). **Ships in two PRs: Phase 1 observe-only (this design), Phase 2 re-request (follow-up, gated on on-device confirmation).** Next: implementation plan → subagent-driven execution.
**Author context:** roots the "missed incoming DigiDollar receive" class in the CF-only live path. Grounded in the real submodule (`BRPeerManager.c`) + `FilterHeaderStore.kt` + the host-KAT tree.

---

## 1. Problem

In `COMPACT_FILTERS_ONLY` the wallet requests cfilters in forward batches and advances a **single monotonic cursor** the moment a request is *sent* — not when the filters are *evaluated*. There is no per-height completion record, so any height whose cfilter is dropped before evaluation is **never rescanned**.

The cursor is `manager->autoFetchCFiltersThrough` (`BRPeerManager.c:231` — "highest height already requested"). It advances at **`BRPeerManager.c:2376`**, inside `_peerRelayedCFHeaders`, immediately after `_BRPeerManagerRequestCFiltersLocked(...)` returns non-zero — i.e. the instant the `getcfilters` message is handed to a peer's socket:

```c
size_t n = _BRPeerManagerRequestCFiltersLocked(manager, reqStart, reqStop, peer);
if (n > 0) {
    manager->autoFetchCFiltersThrough = reqStop;   // ← advanced at SEND time
    ...
}
```

`_BRPeerManagerRequestCFiltersLocked` (`:3577`) returns `stopHeight - startHeight + 1` as soon as `BRPeerSendGetCFilters` is called (`:3611-3612`). "Requested" is conflated with "scanned."

Evaluation happens later, per height, in `_peerRelayedCFilter` (`:2398`), which **drops the height with no requeue** on four paths:

| Path | Line | Cause |
|---|---|---|
| unknown block | `2412-2416` | header for this cfilter's block isn't in `manager->blocks` yet (header race) |
| chain-verify fail | `2418-2424` | filter doesn't match the committed filter header → `_BRPeerManagerPeerMisbehavin` |
| GCS parse fail | `2431-2436` | malformed filter bytes |
| peer disconnect | `_peerDisconnected:919` | a batch was in flight to a peer that dropped; heights never arrive |

Because the cursor is monotonic and passed each of these heights at send time, a dropped height is a **permanent hole**. A hole over a block that pays the wallet = a **missed receive** (detection never fires, because detection is the cfilter match at `:2442`). This is why **"Scan for missing transactions" always heals it** — that path resets the cursor (`BRPeerManagerEnableAutoCompactFilterFetch:3657`, cursor → `start-1`) and re-walks — while the **live path cannot**, because it has no memory of which heights it skipped.

### 1a. The confirmation-side twin

`_peerRelayedBlockTxns` (`:1585`) has the same defect on the *confirmation* side. When a CF-driven full block delivers wallet txs but the block's **header isn't connected yet**, it returns early at **`:1596-1599`**:

```c
if (! b) { // header not synced yet; the block will be re-requested/re-relayed once it is
    pthread_mutex_unlock(&manager->lock);
    return;
}
```

The comment promises a re-request that **no code performs**. The `(blockHash → wallet txHashes)` association is dropped, so those txs can stay `TX_UNCONFIRMED` indefinitely — the cfilter cursor has already passed that height, so the block is never re-fetched on its own.

### 1b. Live confirmation of the mechanism (2026-07-25)

Observed on the emulator this session: `cfTip` frozen at height 22,823,117 while the block-header tip climbed to 22,861,879, repeated `cfheaders: whole filter set stalled on batch`, then `Connection reset by peer (104)` and a mass peer drop (`sync failed`, `_peerDisconnected:970`). The block-header layer kept moving; the **cfilter layer silently stopped and left everything above the frozen `cfTip` unscanned** — exactly the hole class this ledger makes visible.

---

## 2. Goals / non-goals

**Goals**
- A **per-height record** of CF scan state so the "scanned" high-water advances **only over evaluated heights**.
- **Targeted recovery** of holes (re-request the specific missed heights), rotating peers via the **existing** CF peer-rotation machinery.
- **Crash-safety**: outstanding heights survive process death (persist next to the CF header chain).
- **Observe-only first**: prove the theory on-device (holes appear where predicted, over a real DigiDollar receive) before changing any sync behavior.

**Non-goals**
- No change to the **cfheaders** (filter-header chain) layer — this is about **cfilters** (filter content) only.
- No new peer-selection heuristic and **no new peer-rotation primitive** — reuse `cfTriedPeers` / `_recordCFServed` / `_BRPeerManagerPeerCanServeFilters` / `_BRPeerManagerProbeOtherFilterPeersForCFHeaders`.
- No mempool/0-conf visibility change (BIP158 is mined-only by construction — see the 2026-07-09 CF pipeline audit §5).

---

## 3. Data model

A standalone, **pure, testable** module `BRCFScanLedger.{h,c}` (submodule), holding no locks and no sockets — the same shape as `BRPeerCFStatus.h`/`BRComputeCFPeerStatus`, so a host KAT can drive it directly without constructing a `BRPeerManager`. `BRPeerManager` owns one instance and calls into it **only while holding `manager->lock`** (every integration site below already does).

```c
// BRCFScanLedger.h  (submodule)

#define CF_OUTSTANDING_MAX      4096  // hard cap; overflow drops OLDEST + LOGWs its height range
#define CF_PENDING_CONFIRM_MAX   256  // blocks awaiting header-connect confirmation
#define CF_GAVEUP_MAX            512  // heights that exhausted retries — REPORTED, never dropped
// Phase-2 re-request backoff — PINNED 2026-07-25:
#define CF_REREQ_HEADERRACE_SECS  10  // header-race (2412) first retry — header connects quickly
#define CF_REREQ_BASE_SECS        30  // all other holes: base delay
#define CF_REREQ_BACKOFF_CAP_SECS 120 // delay = min(BASE << (attempt-1), CAP) → 30/60/120/120/120
#define CF_REREQ_MAX_ATTEMPTS      5  // per-height cap; on reaching it → gaveUp list (NEVER silent)

typedef struct {
    uint32_t height;
    UInt128  peer;         // peer the getcfilters was sent to (for rotate-away)
    uint16_t port;
    uint32_t requestedAt;  // unix secs; re-request timeout + "when hole opened" log
    uint8_t  attempts;     // capped at CF_REREQ_MAX_ATTEMPTS
    uint8_t  headerRace;   // dropped at 2412 (header not yet known) → 10s short-retry
} BRCFOutstanding;

typedef struct {
    UInt256  blockHash;
    UInt256  txHashes[/*small, capped*/];   // wallet txs awaiting this block's header
    uint16_t txCount;
    uint32_t recordedAt;
} BRCFPendingConfirm;

typedef struct {
    uint32_t start;              // mirrors autoFetchCFiltersStart (birth height, inclusive)
    uint32_t scannedThrough;     // contiguous high-water: EVERY height in [start..this]
                                 //   has been EVALUATED (matched or cleanly missed).
                                 //   Invariant: scannedThrough <= autoFetchCFiltersThrough.
    BRCFOutstanding    outstanding[CF_OUTSTANDING_MAX];   // sorted ascending by height
    size_t             outstandingCount;
    BRCFPendingConfirm pending[CF_PENDING_CONFIRM_MAX];
    size_t             pendingCount;
    uint32_t           gaveUp[CF_GAVEUP_MAX];  // heights past the attempt cap — reported, persisted,
    size_t             gaveUpCount;            //   NEVER silently dropped (else we rebuild the bug)
    uint32_t           lastDriveAt;   // re-request driver throttle (Phase 2)
} BRCFScanLedger;
```

The `gaveUp` list is load-bearing: a height that exhausts `CF_REREQ_MAX_ATTEMPTS` is **moved to `gaveUp`, not dropped**. `scannedThrough` still refuses to advance past it (it was never evaluated), and it is reported over JNI and persisted. Silently discarding an unrecoverable height is exactly the original bug with extra steps.

### Semantics

- `autoFetchCFiltersThrough` keeps its current meaning (**highest height requested**) and stays the forward-request cursor. We **add** `scannedThrough` alongside it; we do not repurpose the old field.
- A height `h` in `(scannedThrough, autoFetchCFiltersThrough]` is **evaluated ⇔ not in `outstanding`**. At request time every height in `[reqStart..reqStop]` is **added** to `outstanding`; on evaluation (match **or** clean non-match) the height is **removed**.
- `scannedThrough` advances by walking upward while `scannedThrough+1 <= autoFetchCFiltersThrough` **and** `scannedThrough+1 ∉ outstanding`. A dropped height stays in `outstanding`, so `scannedThrough` **cannot pass a hole** — the core invariant.

### Pure operations (host-testable)

```c
void     BRCFScanLedgerInit(BRCFScanLedger*, uint32_t start);
void     BRCFScanLedgerRecordRequested(BRCFScanLedger*, uint32_t startH, uint32_t stopH,
                                       UInt128 peer, uint16_t port, uint32_t now); // adds outstanding
void     BRCFScanLedgerMarkEvaluated(BRCFScanLedger*, uint32_t height);            // removes + advance
void     BRCFScanLedgerMarkHeaderRace(BRCFScanLedger*, uint32_t height);           // keep, flag short-retry
void     BRCFScanLedgerReArmPeer(BRCFScanLedger*, UInt128 peer, uint16_t port);     // disconnect: peer=0, keep height
int      BRCFScanLedgerNextRerequest(BRCFScanLedger*, uint32_t now, uint32_t* outHeight); // Phase 2 driver: applies
                                                                                          //   the pinned backoff; on
                                                                                          //   attempt cap → gaveUp
uint32_t BRCFScanLedgerScannedThrough(const BRCFScanLedger*);
size_t   BRCFScanLedgerOutstandingCount(const BRCFScanLedger*);
size_t   BRCFScanLedgerGaveUpCount(const BRCFScanLedger*);
size_t   BRCFScanLedgerHoleRanges(const BRCFScanLedger*, uint32_t* outStarts, uint32_t* outEnds, size_t cap); // coalesced ranges for the JNI/UI report
// pending-confirm
void     BRCFScanLedgerRecordPending(BRCFScanLedger*, UInt256 blockHash, const UInt256* txHashes, size_t n, uint32_t now);
size_t   BRCFScanLedgerTakePending(BRCFScanLedger*, UInt256 blockHash, UInt256* outTx, size_t cap); // drain on header-connect
// persistence
size_t   BRCFScanLedgerSerialize(const BRCFScanLedger*, uint8_t* buf, size_t buflen);
int      BRCFScanLedgerParse(BRCFScanLedger*, const uint8_t* buf, size_t buflen);
```

`outstanding` is a **sorted array**; cfilters within a batch arrive ascending, so `MarkEvaluated` is almost always a front removal. At the sizes involved (steady-state ≈ one in-flight batch) linear ops are fine; the array stays sorted so the `scannedThrough` walk is O(gap). Overflow at `CF_OUTSTANDING_MAX` drops the **oldest** entry with a loud log (bounds memory; never silently truncates coverage — see below).

---

## 4. Integration points (exact sites)

| Event | Site | Phase 1 (observe) | Phase 2 (act) |
|---|---|---|---|
| cfilters requested | `_peerRelayedCFHeaders:2374-2379`, `_BRPeerManagerRequestCFiltersLocked:3611` | `RecordRequested([reqStart..reqStop], target)` | + back-pressure gate (below) |
| cfilter evaluated (match/clean-miss) | `_peerRelayedCFilter` after `:2451` (match) and non-match | `MarkEvaluated(b->height)` | same |
| header-race drop | `_peerRelayedCFilter:2412-2416` | `MarkHeaderRace(height)` (keep outstanding, log) | driver short-retries it at `CF_REREQ_HEADERRACE_SECS` (10s) |
| verify-fail / parse-fail drop | `:2418-2424`, `:2431-2436` | leave outstanding, log hole + reason | driver re-requests from a **different** peer at `CF_REREQ_BASE_SECS`, doubling to the 120s cap |
| peer disconnect w/ batch in flight | `_peerDisconnected` (before `BRPeerFree:993`) | `ReArmPeer(peer,port)` (clears peer, logs) | driver re-requests |
| re-request driver | new, called from the existing sync tick under lock | **disabled** (gate off) | `NextRerequest` → `_BRPeerManagerRequestCFiltersLocked(h,h,target)`; height past `CF_REREQ_MAX_ATTEMPTS` → `gaveUp` (reported, not dropped) |
| block txns, header unknown | `_peerRelayedBlockTxns:1596-1599` | `RecordPending(blockHash,txHashes)` (log) | — |
| header connects | `_peerRelayedBlock` (after a block is added to `manager->blocks`) | log if a pending entry matches | `TakePending` → `_BRPeerManagerUpdateTx(hashes, b->height, b->timestamp)` |

**The header-race requeue (2412):** the height is already in `outstanding` (added at request time); the fix is to **not treat a dropped cfilter as evaluated** and to flag it `headerRace` so the Phase-2 driver retries it quickly (the header connects within seconds). No cfilter bytes are buffered — we re-request, per the author's instruction.

**Peer rotation (reuse, do not add):** the Phase-2 driver picks its target with the existing `_BRPeerManagerPeerCanServeFilters` scan (as `_BRPeerManagerRequestCFiltersLocked:3600-3609` already does) and rotates away from the entry's recorded `peer` and from `cfTriedPeers`, exactly as the cfheaders stall path does at `:2074-2086`. `_recordCFServed` continues to mark responsive peers. No new selection primitive is introduced.

**Back-pressure (Phase 2 only):** new forward requests at `:2374` gate on `outstandingCount < CF_OUTSTANDING_MAX` low-water, so the ledger both bounds memory and stops the cursor from racing ahead of evaluation. In Phase 1 there is **no** gate (zero behavior change); memory is bounded solely by the overflow-drops-oldest rule.

---

## 5. Persistence

Mirror `FilterHeaderStore` exactly (file-backed, atomic tmp-write + rename, epoch-guarded against a concurrent reset, caller-coalesced) — **not** SharedPreferences (the 2026-07-14 heap-leak lesson).

- **Native:** a `saveCFLedger(info, serializedBytes)` callback on `BRPeerManager`, registered like `saveFilterHeaders` (`BRPeerManager.c:227-228`), fired on the same cadence as the filter-header save at `_peerRelayedCFHeaders:2358-2360` and coalesced by the caller.
- **Kotlin:** a new `CfScanLedgerStore` object (copy of `FilterHeaderStore`, file `saved_cf_ledger<net>.bin`), wired into SyncService's existing coalesced writer next to `pendingFilterHeaders` (`SyncService.kt:1893-1897`, flush `:2417`/`:2427`). Same `currentEpoch()`/`delete()` invalidation so a rescan/re-anchor reset can't resurrect a stale ledger.
- **What is persisted:** `scannedThrough`, the `outstanding` **heights + their `headerRace` flag**, and the **`gaveUp` list**. **Attempts and timestamps are NOT persisted** — on load, outstanding heights reset (`attempts = 0`, `requestedAt = now`, `peer = 0`). Rationale (operator): a fresh process gets fresh peers; stale attempt counts would prematurely park recoverable heights. The `gaveUp` list persists so it is **never lost from the report** across restarts. Whether a restart also re-arms `gaveUp` heights back into `outstanding` (the fresh-peers rationale argues yes) is a **Phase-2 detail** to confirm on-device — Phase 1 only persists + reports them.
- **Restore:** on `startSync`, load the serialized ledger and hand it to native alongside the filter-header restore (`SyncService.kt:1548`). `scannedThrough` becomes the resume floor; `outstanding` is re-armed as above. The forward cursor resumes from `max(scannedThrough, restored autoFetchCFiltersThrough)`.

Persistence is a restore optimization; best-effort I/O and a dropped oversized/garbled blob (ledger rebuilt by a re-anchor or a manual "Scan for missing") are acceptable, same posture as `FilterHeaderStore`.

---

## 6. Rollout — Phase gate

A **single, clearly-named compile-time constant** gates all behavior change:

```c
// BRCFScanLedger.h
#define CF_LEDGER_DRIVE_REREQUEST 0   // Phase 1: 0 = observe only. Phase 2 PR flips to 1.
```

### Phase 1 — OBSERVE-ONLY (this PR)
- Populate the ledger at every site in §4's "Phase 1" column: record requested ranges, mark evaluated, keep dropped/header-race/disconnected heights outstanding, record pending-confirm.
- **Log holes** with height ranges and drop reason, e.g. `cf-ledger: hole [22823118..22823140] reason=verify_fail peer=… (scannedThrough=22823117, requestedThrough=22861879)`.
- **Expose counts via JNI** getters (§7).
- **No re-request, no back-pressure, no pending-drain, no header-race retry.** The driver is compiled out by the gate; `scannedThrough` is computed and logged but does **not** gate the forward cursor. **Net behavior change: zero.** Purpose: confirm on-device, against a real DigiDollar receive, that holes appear where the theory predicts.
- **Why the header-race retry (2412) stays Phase 2 — not merely zero-behavior-change:** the header race is the **predicted dominant hole source**. If Phase 1 healed it, Phase 1's logs would lose their strongest signal. Observe-only must observe the **dominant failure mode too**, or the on-device confirmation is measuring the wrong thing. So Phase 1 *records* header-race holes loudly and *acts on none*.
- **Overflow is never silent:** on `CF_OUTSTANDING_MAX` overflow, drop the OLDEST entry and `LOGW` **its height range** — so even the overflow path never loses coverage information. Same rule for `CF_PENDING_CONFIRM_MAX`.

### Phase 2 — RE-REQUEST (follow-up PR, gated on Phase-1 on-device confirmation)
Flip `CF_LEDGER_DRIVE_REREQUEST` to 1, enabling: the re-request driver, back-pressure, the header-race short-retry, and the pending-confirm drain. Adds an on-device regression proving a CF-only receive that lands in a hole is recovered without a manual scan.

---

## 7. JNI surface (outer repo — `jni_peer.c` + `NativeBridge.kt`) — Phase 1

**Phase-1 requirement (operator):** the report must be readable **on-device during the DD receive test without adb**, and both the enforce phase and the future supervisor need it anyway. **Split by access pattern (operator refinement):** the *counts* are what the supervisor polls at cadence; the *ranges* are what a human pulls occasionally. So two getters, not one:

**(a) Scalar counts — a pure, cheap read; designed to be bridge-mirrorable.** `scannedThrough`, `outstandingCount`, `gaveUpCount`, `pendingCount` are scalars. They can be published to bridge-level `_Atomic` mirrors (refreshed at the tail of the guarded ledger-mutating sites) and read lock-free — exactly the pattern the `seq/lockfree-status-reads` sequence applies to peer count / heights. So this getter is **PEER_GUARD-free / lock-free** and safe for cadence polling:

```c
JNIEXPORT jlongArray JNICALL ..._getCfScanLedgerCounts(...)   // [scannedThrough, outstanding, gaveUp, pending]
```                                                            //   reads bridge _Atomic mirrors, no lock, no deref
```kotlin
external fun getCfScanLedgerCounts(): LongArray   // [scannedThrough, outstanding, gaveUp, pending]
```

**(b) Hole ranges — a guarded structure walk; pulled occasionally by a human.** Coalescing the outstanding/gaveUp heights into `[start..end]` ranges walks the ledger arrays, so it dereferences `g_peerManager`→ledger and **keeps `PEER_GUARD`**:

```c
JNIEXPORT jlongArray JNICALL ..._getCfScanLedgerHoleRanges(...) // PEER_GUARD; [(start,end)…] coalesced
```

Surfaced in **Settings → Network Info** alongside `cfTip` / `Synced Block (filters)`: "Scanned through", "Outstanding (N)", "Gave up (N)" from the cheap counts getter (cadence-safe), and an expandable hole-range list from the guarded getter (on-demand). The Phase-1 confirmation run is done by eye on the device; the counts feed the enforce phase and the supervisor. **Forward link:** `seq/lockfree-status-reads` §6 mirrors these scalar counts and keeps the range walk guarded — consistent with this split.

---

## 8. Watchdog disposition

**This section is the canonical record of the watchdog analysis — the future `SyncSupervisor` sequence inherits it verbatim.** Per the author's request — stated, **not removed in this PR**:

- **`tipStallWatchdogJob` (`SyncService.kt:987`) — NOT subsumed.** It guards the **block-header** tip (`getLastBlockHeight`) freezing and peer connectivity (re-request headers → pin a canon filter peer → recreate the manager). That is a different layer from cfilter evaluation. Keep as-is.
- **`bip158WatchdogJob` (`SyncService.kt:1084`) — PARTIALLY subsumed, Phase 2 only.** It guards the **cfheaders chain** keeping pace with the block tip and does a blunt one-time **re-anchor** on stall. Once Phase 2's driver guarantees per-height cfilter completeness, the re-anchor's role in recovering the *cfilter-drop* class is replaced by the ledger's targeted re-request. But the watchdog still covers the class the ledger does **not** touch — the cfheaders chain not advancing at all (a peer that stops answering `getcfheaders`) — so it stays. Recommended follow-up (not this PR, not Phase 2): once the ledger is authoritative and persisted, let the watchdog read `scannedThrough` for a sharper progress signal than the current `cfTip`-vs-`blockTip` gap, then reconsider the re-anchor.

**Live corroboration (2026-07-25 baseline, `docs/bugs/2026-07-25-cf-scan-ledger-wedge-baseline.md`):** the emulator wedged with `cfTip` frozen at 22,823,117, block tip advancing to 22,861,879, **zero cfilters ever requested or evaluated** — a pure cfheaders-delivery stall. The ledger's `outstanding` set would have been **empty** (nothing requested → nothing dropped), so its re-request driver would have had nothing to do. Only the cfheaders rotation / re-anchor (the `bip158WatchdogJob` domain) recovers that class. This is the field proof that the ledger does **not** subsume `bip158WatchdogJob`.

---

## 9. Host tests — `native/src/test/host/cf_scan_ledger_kat/`

Follow the `cf_peer_status_kat` idiom: a `cf_scan_ledger_kat_main.c` + `run.sh` compiling `BRCFScanLedger.c` with `clang -w -I $CORE_DIR` (needs `BRInt.h` for `UInt128/UInt256`; no submodule `.c` linking, no pthread). Cases:

1. **Cursor does not advance past an unevaluated height.** Record `[100..110]`; `MarkEvaluated` 100–104 and 106–110 (105 dropped). Assert `ScannedThrough == 104` (not 110), `OutstandingCount == 1`.
2. **Header-race height is requeued, not dropped.** Record `[200..200]`; `MarkHeaderRace(200)`. Assert 200 still outstanding, flagged `headerRace`; `ScannedThrough` unchanged; `NextRerequest` (with `CF_LEDGER_DRIVE_REREQUEST` logic under test) returns 200.
3. **Peer disconnect re-arms in-flight heights.** Record `[300..305]` to peer A; `ReArmPeer(A)`. Assert 300–305 still outstanding with peer cleared and `attempts` unchanged; a subsequent `NextRerequest` offers them and does not target A.
4. **Attempt cap holds.** Drive `NextRerequest`/re-arm on one height `CF_REREQUEST_MAX_ATTEMPTS` times; assert it stops being offered after the cap (becomes a logged permanent hole), and `ScannedThrough` still refuses to advance past it.
5. **Serialize/Parse round-trip** (persistence correctness): a ledger with holes + pending survives `Serialize`→`Parse` byte-identical.

Wire `cf_scan_ledger_kat/run.sh` into the host-KAT set the pre-publish suite runs.

---

## 10. Doc updates (same PR)

`docs/ARCHITECTURE.md` and `docs/PROCESS_FLOWS.md` still describe **BIP 37 bloom as the current SPV path** (`ARCHITECTURE.md:88-103,128,162,224`; `PROCESS_FLOWS.md:161-174,226`) — wrong since v4.0.0. Update the SPV sync sections to: BIP157/158 compact-filters-only; the cfheaders → cfilter → matched-block → register → confirm-by-depth flow; and the CF scan ledger's role in completeness. Replace the `api.digiscope.me/api/peers/bloom` / `dgb_bloom_peers` / `filterload`+`merkleblock` references with the capability-aware `/api/peers` + canon-oracle model.

---

## 11. Submodule / build mechanics

- **Submodule (fork-push):** `BRCFScanLedger.{h,c}` (new), `BRPeerManager.{c,h}` (struct field + integration + `saveCFLedger` callback), `CMakeLists.txt` (add `BRCFScanLedger.c`). Fork branch **`seq/cf-scan-ledger-observe`** (mirrors the app branch name); commit + push `johnnylaw` first, then bump the pin **in the same app PR** per usual discipline.
- **Outer (native rebuild, no fork-push):** `jni_peer.c` getters, `NativeBridge.kt`, `CfScanLedgerStore.kt`, `SyncService.kt` wiring, Network Info UI, the docs, and the KAT.
- Native rebuild required (`:native:assembleMainnetDebug :app:assembleMainnetDebug`).

---

## 12. Risks / edge cases

- **Ledger vs. re-anchor races:** a cfheaders re-anchor (`bip158WatchdogJob`) or rescan rewrites the CF chain; the ledger must reset with it (epoch bump via `CfScanLedgerStore.delete`, and native re-init of `scannedThrough`/`outstanding` when `BRPeerManagerEnableAutoCompactFilterFetch` runs). Reuse the filter-header reset sites (`SyncService.kt:1203/1270/1315`).
- **Reorg below `scannedThrough`:** if a reorg invalidates blocks at/under the scanned high-water, `scannedThrough` must roll back to the fork point and re-arm the affected heights. Phase 1 only logs this; Phase 2 handles it. Flag as an explicit Phase-2 case.
- **Overflow honesty:** `CF_OUTSTANDING_MAX` overflow drops the oldest entry — this is a **coverage cap**; it must `LOGW` the dropped height range so an observation run never reads as "fully covered" when it wasn't (per the project's no-silent-caps rule).
- **Pending-confirm unbounded pathological case:** many wallet-paying blocks arriving before their headers is unlikely; `CF_PENDING_CONFIRM_MAX` with oldest-drop + log bounds it.

---

## 13. Resolved decisions (operator, 2026-07-25)

1. **Retry/backoff (Phase-2 constants, pinned here):** header-race → 10s first retry; all other holes → 30s doubling to a 120s cap (30/60/120/120/120); attempt cap 5. On cap, the height moves to a **persisted `gaveUp` list that is reported** — never a silent drop. (§3 constants, §4 driver row.)
2. **Phase-1 JNI exposure: yes.** One structured getter → outstanding count + `scannedThrough` + gaveUp count + coalesced hole ranges, surfaced in Network Info so it's readable on-device during the DD receive test without adb. The enforce phase and the supervisor reuse it. (§7.)
3. **Persist attempts across restart: no.** Persist heights + `headerRace` flag + the `gaveUp` list; reset attempts/timestamps on load (fresh process → fresh peers). (§5.)
4. **Phasing:** header-race requeue and pending-confirm drain stay **Phase 2** — Phase 1 must observe the dominant failure mode (the header race), not heal it. (§6.)
5. **Submodule:** fork branch `seq/cf-scan-ledger-observe`, pin-bump in the same app PR. (§11.)

---

## Phase 2 — REVISED IMPLEMENTATION MODEL (2026-07-26, after on-device observe + code re-map)

**This section SUPERSEDES the brief "Phase 2 — RE-REQUEST" above where they conflict.** Triggered by
the item-3 on-device observe (`docs/bugs/2026-07-26-cf-ledger-missed-dd-receive-observed-clean-peer.md`),
which reproduced the miss against a flawless local node and revealed the real hole shape, plus a
ground-truth re-map of the merged code (post #29/#31; the §4 line numbers below the drift table are stale).

### Revised hole model — BULK FLOOR CLUSTER, not a tip-race trickle
§4 assumed a single header arriving a beat after its cfilter (→ short 10s retry catches it). OBSERVED:
one re-anchor fired ONE ranged `getcfilters(23900001 → stop~23901000)`; the cfheaders chain wasn't
built down to the floor yet, so ~1000 responses were header-race-dropped **at once** → `outstanding=1001`
in a single shot. **Cap-overflow checked: NO `CF_OUTSTANDING_MAX` drop fired; 1001 is genuine, no
fidelity gap.** Consequence: **the driver's unit of work is a RANGE, not a height.**

### Driver design — RANGE-COALESCING (supersedes single-height re-requests at the call site)
- Add a pure primitive `BRCFScanLedgerNextRerequestRange(l, now, *outStart, *outStop)`: returns the
  lowest CONTIGUOUS run of outstanding heights that are ALL due (backoff elapsed) and share the same
  recorded rotate-away peer, capped at `MAX_CFILTERS_RESULTS` (1000). Bumps `attempts` for every height
  in the run in lockstep; heights hitting `CF_REREQ_MAX_ATTEMPTS` → `gaveUp`. Returns 0 when nothing due.
  Keep the existing single-height `NextRerequest` for the pure unit test / degenerate case.
- Call site (in `BRPeerManagerKeepAlive`, under `manager->lock`): loop the range primitive up to
  `CF_REREQ_BATCH_PER_TICK` RANGES/tick; per range, pick a filter peer ≠ the range's recorded peer
  (forced rotation), issue ONE `_BRPeerManagerRequestCFiltersLocked(start, stop, rotatedPeer)`, then
  `RecordRequested([start..stop], rotatedPeer, now)` to refresh the rotate-away key. Range-coalescing
  drains the 1001 floor in ~2 ranged requests (~2 ticks), vs. 1000 single-height messages that would
  hammer the peer and take minutes.

### Per-(range, peer) attempts + FORCED rotation
A contiguous outstanding run shares one recorded peer (requested together). Rotation is per-range: the
whole run re-requests from ONE new peer ≠ the recorded one; attempts bump per-height but in lockstep.
**This is load-bearing:** it prevents the failure the operator flagged — one wedged peer causing 1000
holes must NOT park all 1000 into `gaveUp` at the same dead peer (= permanent loss with extra steps).
`BRCFScanLedgerReArmPeer` (drop site, disconnect) already clears the recorded peer so the driver re-picks.

### Header-race wiring — the §5-D correction (design §4 was UNIMPLEMENTABLE)
At the drop site `b == NULL` (height unknown — that IS the race), so `MarkHeaderRace(height)` cannot be
called there; and `BRCFScanLedgerMarkHeaderRace` INSERTS-if-absent (calling it at header-connect for every
block would inject phantom holes). FIX (EDITs 0/1/2a): record the **blockHash** in a hash-keyed header-race
ring at the drop site (height-free), resolve at header-connect — `TakeHeaderRaceHash(blockHash)==1` for
genuinely-raced blocks only → then `MarkHeaderRace(block->height)` (height now known) → 10s fast retry.
**NOTE:** the driver already heals header-race holes at the 30s BASE without the hash-set (they stay
outstanding; the re-request succeeds once the header connects). The hash-set is a LATENCY optimization for
the dominant class (10s vs 30s). Ship behind the same gate; **separable if it slips schedule.**

### Back-pressure — LOW-WATER, not `< CF_OUTSTANDING_MAX`
`_cfLedgerInsertOutstanding` drops the OLDEST = lowest = floor holes (the receive-bearing ones) on overflow.
Gating forward requests at `< 4096` still permits reaching 4096 then dropping exactly those. Add
`CF_OUTSTANDING_LOWWATER` (¾ cap = 3072); the forward cursor pauses there so the driver reserves headroom
for the floor cluster. Watch `CF_OUTSTANDING_MAX 4096` on large-gap re-anchors (the item-3 20k-block gap
would blow past 4096 if the cursor un-wedges before the driver drains).

### Driver home + peer selection
- Home: `BRPeerManagerKeepAlive` — the only recurring wall-clock entry point wholly under `manager->lock`.
  NOT the event-driven cfheaders kicks, NOT `BRPeerManagerRerequestHeadersFromTip` (the N-min tip-stall
  watchdog). Respect the `KEEPALIVE_TICK_BUDGET` already spent on the ping loop → hence the per-tick cap.
- **Liveness dependency (for the SyncSupervisor sequence):** the driver inherits keepalive's liveness —
  keepalive is precisely the mechanism that historically DIED (v4.0.22 dead-keepalive fix + its 0-peer
  watchdog revival exist because of it). If keepalive wedges, the re-request driver wedges with it.
  Acceptable NOW because the `recoveryScope` 0-peer watchdog guards keepalive, but the future
  **SyncSupervisor MUST treat "driver last ticked at T" as a monitored liveness signal** like every
  other health stamp — the driver silently not-ticking is indistinguishable from no-holes-to-drain
  without it. One line here so the supervisor sequence (runway item 6) inherits the requirement.
- Selection: reuse the inline `connectedPeers` + `_BRPeerManagerPeerCanServeFilters` walk with the ledger's
  per-range `.peer` as the rotate-away key. Do NOT reuse `cfTriedPeers`/`_BRPeerManagerNextUntriedFilterPeer`
  — that set is owned by the cfheaders batch rotation; sharing it corrupts cfheaders rotation state.

### Monitored risk (not a blocker)
`NextRerequest[Range]` bumps `attempts` even when the send fails (unresolvable stop-hash = header still not
connected). A height whose header stalls >~5.7 min could prematurely `gaveUp`. In practice headers connect
sub-second << 30s BASE, so the first retry lands after the header connects. If it bites on-device: pure-module
`peek`/`commit` split (offer-without-bump, commit-on-successful-send). Follow-up — do NOT pre-build.

### Persistence across the wipe/restore acceptance test
`gaveUp` persists (never lost from the report); outstanding heights + `headerRace` flag persist; attempts/
timestamps reset on load (fresh peers). The acceptance test wipes SAVED state (blocks/filter-headers/cf-ledger)
and restores the SEED → the ledger rebuilds from the re-anchor, reproducing the floor cluster; the driver then
drains it. The header-race hash-ring is transient (not persisted) — same posture as `pending`.

### ACCEPTANCE GATE (Phase-2 regression, verbatim — reproducible against a REAL mainnet tx)
Same seed, same pinned own-node (`10.0.2.2:12024`, local node `listen=1`, filter-index synced), same on-chain
$1 DD (block **23,920,918**, txid `813d6e46e1788e00c3a262776c3d59ed9a944860521821959a849193321ec76e`).
**Wipe wallet state → restore seed → sync.** PASS iff: the DD credits via **CF alone (no reconcile)**, the
ledger **drains to `outstanding=0`**, and `scannedThrough` reaches the block tip (past 23,920,918).
Steady-state metrics: `gaveUp` stays 0 over a normal week; `outstanding` drains to 0 within minutes of any
network blip; ZERO manual "Scan for missing funds" needed; no native SIGSEGV.

### New constants (add to BRCFScanLedger.h next to the Phase-2 block)
`CF_OUTSTANDING_LOWWATER 3072` (¾ of MAX) · `CF_REREQ_BATCH_PER_TICK 64` (ranges/tick). Existing pinned
constants unchanged (header-race 10s, base 30, cap 120, MAX_ATTEMPTS 5) — sanity-checked against the real
floor-cluster shape and retained.
