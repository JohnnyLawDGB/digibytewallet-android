# Lock-free status reads — take PEER_GUARD off the UI poll path

**Date:** 2026-07-25
**Status:** Design — awaiting review. **Bridge-only PR. Sequenced AFTER `seq/cf-scan-ledger-observe` merges; rebase on develop.**
**Scope guard:** ZERO submodule changes expected (addendum item 2). The one place that tempts a submodule getter (`getSyncProgress`) is resolved below without one.

---

## 1. Problem

The C core **already** solved lock-free status reads. `BRPeerManager` keeps `_Atomic` mirrors (`cachedPeerCount`, `cachedLastHeight`, `cachedEstimatedHeight`, `cachedSyncStartHeight`, `cachedCFTip`, `cachedHasDownloadPeer`, `cachedSyncMode`) refreshed under `manager->lock` by `_BRPeerManagerRefreshCachedStatus` (`BRPeerManager.c:301`), explicitly so the JNI accessors can "read the mirrors WITHOUT the lock and never block behind a heavy compact-filter sync." Every public accessor is already a pure `atomic_load` — no `manager->lock`:

- `BRPeerManagerPeerCount` (`:3141`), `BRPeerManagerLastBlockHeight` (`:3098`), `BRPeerManagerEstimatedBlockHeight` (`:3087`), `BRPeerManagerSyncProgress` (`:3113`), `BRPeerManagerCFChainTipHeight` (`:3418`), `BRPeerManagerGetSyncMode` (`:3402`).

**The JNI layer throws that away.** Each getter wraps the already-lock-free read in `PEER_GUARD()` — the global recursive `g_peerManagerMutex`, a **different** lock from `manager->lock`, held for the entire body of `startSync`, `forceReconnect`, `setMaxPeerConnections`, `rescan`, and `BRPeerManagerFree`:

| JNI getter | line | body |
|---|---|---|
| `getSyncProgress` | `jni_peer.c:846` | `PEER_GUARD(); … BRPeerManagerSyncProgress(g_peerManager,0)` |
| `getPeerCount` | `:858` | `PEER_GUARD(); … BRPeerManagerPeerCount(g_peerManager)` |
| `getEstimatedBlockHeight` | `:881` | `PEER_GUARD(); … BRPeerManagerEstimatedBlockHeight(g_peerManager)` |
| `getLastBlockHeight` | `:893` | `PEER_GUARD(); … BRPeerManagerLastBlockHeight(g_peerManager)` |
| `getCFChainTipHeight` | `:1303` | `PEER_GUARD(); … BRPeerManagerCFChainTipHeight(g_peerManager)` |
| `getSyncMode` | `:1257` | `PEER_GUARD(); … BRPeerManagerGetSyncMode(g_peerManager)` |

So every status poll still serializes behind teardown/rebuild. This is the documented root of the failure in `SyncService.kt:164-172`: a native call blocks → `Dispatchers.Default` starves → the polling keepalive and watchdogs freeze. The `recoveryScope` on `Dispatchers.IO` (`SyncService.kt:173`) is a workaround for a lock that should not be on the read path. It is also the PIN-screen ANR: `getPeerCount()`/`getSyncProgress()` on the main thread block on `g_peerManagerMutex` while a heavy CF sync holds it across `startSync`.

## 2. Why the guard is there — and why the mirror removes the need

`PEER_GUARD()` provides two things to these getters:

1. **Serialization behind teardown** — which for a *pure read of an atomic* is pure harm. We don't need it.
2. **UAF-window closure on `g_peerManager`** — the getter does `if (!g_peerManager) …; BRPeerManagerX(g_peerManager)`. Without the guard, `startSync`→`BRPeerManagerFree` on another thread could free the pointer between the null-check and the deref → use-after-free → SIGSEGV. This is real, and it is the *only* reason the guard is needed here.

**The fix removes BOTH needs at once by not dereferencing `g_peerManager` on the read path.** A bridge-level `_Atomic` mirror is read instead. No pointer deref → **no UAF window** → no reason to hold the guard. This is the safety argument for dropping the lock, and it must be stated explicitly (operator request): *we can drop `PEER_GUARD` precisely because we simultaneously stop touching the freeable pointer.*

## 3. Design

### Bridge-level mirrors (`jni_peer.c`, all `_Atomic`, `memory_order_relaxed`)

```c
static _Atomic int      g_mirrorPeerCount;
static _Atomic uint32_t g_mirrorLastHeight;
static _Atomic uint32_t g_mirrorEstimatedHeight;
static _Atomic uint32_t g_mirrorCFTip;
static _Atomic int      g_mirrorSyncMode;
static _Atomic int64_t  g_mirrorLastRefreshMonotonicMs;  // CLOCK_MONOTONIC ms; 0 = never
```

Only scalars with an existing public lock-free accessor are mirrored (so this stays **zero-submodule** — see §5 for why `syncStart`/`hasDownloadPeer` are deliberately absent).

### Refresh helper

```c
// Copies g_peerManager's OWN lock-free atomic mirrors into the bridge mirrors.
// SAFE to call ONLY from: (a) a BRPeerManager callback (runs on a live manager's
// peer thread — BRPeerManagerFree joins that thread, so the manager can't be freed
// underneath it), or (b) the tail of a PEER_GUARD-holding JNI call. NEVER from an
// unguarded, non-callback context. Reads the global with a null-check exactly as the
// existing callbacks already do (jni_peer.c:71,149,151) — never dereferences a freed
// pointer, because the global no longer points at a manager being freed.
static void _refreshBridgeStatusMirror(void) {
    BRPeerManager *m = g_peerManager;
    if (m) {
        atomic_store_explicit(&g_mirrorPeerCount,       (int)BRPeerManagerPeerCount(m),           memory_order_relaxed);
        atomic_store_explicit(&g_mirrorLastHeight,      BRPeerManagerLastBlockHeight(m),          memory_order_relaxed);
        atomic_store_explicit(&g_mirrorEstimatedHeight, BRPeerManagerEstimatedBlockHeight(m),     memory_order_relaxed);
        atomic_store_explicit(&g_mirrorCFTip,           BRPeerManagerCFChainTipHeight(m),         memory_order_relaxed);
        atomic_store_explicit(&g_mirrorSyncMode,        (int)BRPeerManagerGetSyncMode(m),         memory_order_relaxed);
    }
    atomic_store_explicit(&g_mirrorLastRefreshMonotonicMs, _nowMonotonicMs(), memory_order_relaxed);
}
```

### Refresh triggers (existing safe sites — no new threads)

- **Bridge callbacks** (peer threads, already read `g_peerManager` safely): tail of `bridge_syncStarted` (`:64`), `bridge_syncStopped` (`:81`), `bridge_txStatusUpdate` (`:134`), `bridge_saveBlocks` (`:170`). During active sync these fire frequently; on DGB even an idle tip mines ~every 15s, so block-relay callbacks keep it warm.
- **Guarded-mutation tails** (already hold `PEER_GUARD`, `g_peerManager` stable): tail of `startSync`, `stopSync`, `forceReconnect`, `setMaxPeerConnections`, `injectPeerByIp`, `setPinnedPeer`, `rescan`, `setSyncMode`, **`keepAlivePeers`**. `keepAlivePeers` runs every ~10s from SyncService and is the **idle heartbeat** that keeps the mirror fresh when no blocks arrive.

### Pure-read accessors — rewritten, NO lock, NO deref

```c
JNIEXPORT jint JNICALL ..._getPeerCount(JNIEnv *e, jobject t) {
    (void)e;(void)t;
    return (jint)atomic_load_explicit(&g_mirrorPeerCount, memory_order_relaxed);
}
```
…identically for `getLastBlockHeight`, `getEstimatedBlockHeight`, `getCFChainTipHeight`, `getSyncMode`. `getSyncProgress` is handled per §5.

## 4. Staleness bound

A refresh timestamp lets the (future) supervisor tell **"0 peers"** from **"no fresh sample"** — the frozen-loop signature is a mirror that stops updating.

```c
JNIEXPORT jboolean JNICALL ..._isStatusStale(JNIEnv *e, jobject t) {
    (void)e;(void)t;
    int64_t last = atomic_load_explicit(&g_mirrorLastRefreshMonotonicMs, memory_order_relaxed);
    if (last == 0) return JNI_TRUE;                         // never refreshed
    return (_nowMonotonicMs() - last) > STATUS_STALE_MS ? JNI_TRUE : JNI_FALSE;
}
```

- **`STATUS_STALE_MS = 10_000`** (operator default: 2× the fastest planned supervisor cadence).
- Exposed as a **separate boolean getter** (`isStatusStale()`), **not** an in-band sentinel — a `-1` "0 peers" sentinel would be misread as a real zero somewhere (operator default, item 4).
- **Value getters always return the last mirrored value**, never a sentinel; the boolean is the only staleness channel.
- **Known interaction (documented, not silently tuned):** the idle heartbeat is `keepAlivePeers` at ~10s and the bound is 10s, so at an idle tip the mirror age can graze the bound and momentarily read stale. That is acceptable for this PR — the flag exists for the supervisor, and a frozen loop (the real target) goes and stays stale. If idle false-stale proves noisy when the supervisor lands, the fix is to widen `STATUS_STALE_MS` or add a dedicated refresh tick — **deferred to the supervisor sequence**, not tuned blindly here.

## 5. `getSyncProgress` — the one non-trivial accessor

`BRPeerManagerSyncProgress` derives from **four** values (`last`, `est`, `syncStart`, `hasDownloadPeer`), not two. `syncStart` (`cachedSyncStartHeight`) and `hasDownloadPeer` (`cachedHasDownloadPeer`) have **no public submodule accessor**. Exposing them would be a **submodule change**, which this branch must not make (addendum item 2). Resolution, zero-submodule:

- **Primary (push, already exists):** `bridge_txStatusUpdate`/`bridge_syncStarted` already compute the float in-callback (a consistent snapshot, where `g_peerManager` is safely readable) and push it via `onSyncProgress(progress, height)`. Cache that in a Kotlin `StateFlow<Float>`; pollers read the flow instead of calling native. This satisfies the operator's intent (no blocking pull, no racy multi-input float mirror) **better** than mirroring raw heights, since the callback snapshot is internally consistent.
- **Fallback (cold poll, no recent push):** compute in Kotlin from the mirrored `last`/`est` + a **Kotlin-tracked `syncStart`** (captured as `last` at the `onSyncProgress(0.0/-1.0)` sync-start signal) + `peerCount > 0` as the `hasDownloadPeer` proxy. Port the `BRPeerManagerSyncProgress:3122-3135` formula verbatim.
- Native `getSyncProgress` is **retired** from the JNI surface (its 4 callers — `NativeBridge.kt:185`, `AssetManager.kt:1752`, `SyncService.kt:853,940` — move to the StateFlow / Kotlin compute).

**Process-death hole in the fallback (operator catch — MUST fix):** after process death mid-sync, no callback has fired yet, so Kotlin's `syncStart` is whatever it was seeded with, and the **first cold poll reports garbage progress** — the exact "reverts to Syncing 0%" class already fought via `hasReachedSynced` persistence (`SyncService.kt:1672-1674`, whose comment literally says progress callbacks must not "revert 'Connected' back to 'Syncing 0%' on restart near the chain tip"). Two-part fix:
1. **Persist `syncStart` alongside `hasReachedSynced`** (same prefs, same restore path) so a cold start has the real anchor.
2. **Until the first real `onSyncProgress` callback of the session arrives, `isStatusStale()` reads `true`** (the mirror's `last==0`/never-refreshed condition already gives this) so consumers know the computed float is **provisional**, not authoritative.

**Escape hatch (the legitimate zero-submodule exception):** if the persist-`syncStart` route proves genuinely uglier than expected, a **two-line submodule getter `BRPeerManagerSyncStartHeight()`** (reads `cachedSyncStartHeight`, mirrors the existing lock-free accessors) is an acceptable exception to the zero-submodule rule — but **only** if persistence is actually worse, and flagged to the operator before taking it.

**Operator flag (not a blocker — no submodule change, no fund path):** the brief's "mirror the two heights it derives from" undercounts the formula's inputs by two; the above is the zero-submodule way to honor "compute on the Kotlin side." Calling it out per the decide-and-proceed rule.

## 6. Accessor audit (addendum items 2 & 3)

| accessor | verdict | action |
|---|---|---|
| `getPeerCount`, `getLastBlockHeight`, `getEstimatedBlockHeight`, `getCFChainTipHeight`, `getSyncMode` | pure scalar mirror read | **mirror, drop `PEER_GUARD`** |
| `getSyncProgress` | pure but 4-input, 2 inputs have no public getter | **retire native; StateFlow + Kotlin compute** (§5) |
| `getSavedBlocksTip` (`:906`) | already lock-free, reads `g_savedBlocks` (not `g_peerManager`) | leave as-is (already no `PEER_GUARD`) |
| **cf-scan-ledger counts getter** `getCfScanLedgerCounts` (from `seq/cf-scan-ledger-observe`) | scalar counts (outstanding, gaveUp, scannedThrough, pending) — what the supervisor **polls at cadence** | **MIRROR, drop `PEER_GUARD`** — same treatment as peer count / heights. The ledger PR already publishes these as bridge `_Atomic` mirrors (its §7); this sequence just confirms they read lock-free. |
| **cf-scan-ledger hole-ranges getter** `getCfScanLedgerHoleRanges` (from `seq/cf-scan-ledger-observe`) | **walks ledger structures** (coalesces ranges) — dereferences `g_peerManager`→ledger; pulled **occasionally by a human** | **KEEP `PEER_GUARD`.** The range walk can't be a scalar mirror. Counts (cadence) vs ranges (on-demand) is the operator's split. (When that PR merges, this branch rebases onto it; no change to that getter here.) |
| **mutators** — `startSync`, `stopSync`, `forceReconnect`, `setMaxPeerConnections`, `injectPeerByIp`, `setPinnedPeer`, `rescan`, `setSyncMode`, `keepAlivePeers` | side-effecting / deref | **UNCHANGED — keep `PEER_GUARD`**; only add the mirror-refresh at their tail |

## 7. Out of scope for this PR (noted, not done)

- **`recoveryScope` removal.** Once reads are non-blocking, the `Dispatchers.IO` `recoveryScope` (`SyncService.kt:173`) is a workaround for a starvation that no longer happens on the read path. Review it for removal in a follow-up — **do not remove here** (it still guards against a serviceScope thread parked inside a *mutating* guarded call, which this PR does not change).

## 8. Tests

- **Host KAT** `native/src/test/host/status_staleness_kat/` — extract the staleness predicate as a pure function `bridge_status_is_stale(int64_t lastMs, int64_t nowMs, int64_t boundMs)` (header-only, `BRPeerCFStatus.h` idiom) and KAT: never-refreshed (last=0) → stale; within bound → fresh; past bound → stale; exactly-at-bound boundary.
- **On-device gate (the regression bar):** reproduce **PIN screen during a heavy CF sync**. Before: main-thread `getPeerCount()`/`getSyncProgress()` block on `g_peerManagerMutex` → ANR. After: the reads never touch the guard → no ANR while `startSync` holds it. This must be demonstrated before merge — it is both the fix and the thing not to reintroduce.
- Existing `Bip158WatchdogPolicyTest` (calls `getCFChainTipHeight()`) must stay green — signatures are unchanged.

## 9. Doc amendments (addendum item 1)

`ARCHITECTURE.md` / `PROCESS_FLOWS.md` SPV sections were just corrected for CF-only in the cf-scan-ledger PR. **Amend, do not rewrite:** add that the UI/watchdog status reads are lock-free bridge-mirror reads (peer count, heights, CF tip, sync mode) with a staleness flag, decoupled from `PEER_GUARD`/teardown.

## 10. Rollout / mechanics

- **Bridge-only, outer repo:** `jni_peer.c` (mirrors + refresh helper + rewritten getters + `isStatusStale`), `NativeBridge.kt` (retire `getSyncProgress`, add `isStatusStale`), the Kotlin progress StateFlow + caller migration, docs, KAT. **No fork-push.** Native rebuild required (`:native:assembleMainnetDebug :app:assembleMainnetDebug`).
- **Zero submodule changes.** If implementation surfaces a genuine need for one (it should not), STOP and report why before making it (addendum item 2).
- **Sequencing:** rebase on develop after `seq/cf-scan-ledger-observe` merges; keep that PR's ledger getter guarded (§6).

## 11. Resolved defaults (operator, item 4)

1. `STATUS_STALE_MS = 10_000` (2× fastest supervisor cadence).
2. Stale surfaces as a **separate boolean getter**, never an in-band sentinel.
3. `getSyncProgress`: retire native; compute Kotlin-side from mirrored heights (§5) — flagged the 4-input nuance, resolved without a submodule change.
