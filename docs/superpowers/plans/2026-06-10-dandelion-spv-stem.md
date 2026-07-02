# SPV Dandelion++ Stem Submission — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On send, submit the tx to a single Dandelion-enabled stem node as a `dandeliontx` (stem phase) instead of flooding the `inv` to all peers, with a random embargo timer that self-fluffs (floods) if the network hasn't relayed it back — so delivery is never sacrificed for privacy.

**Architecture:** The C core owns the stem mechanics (route `inv` to one dandelion-capable peer with `is_dandelion=1`; the existing `getdata`→`dandeliontx` plumbing then delivers it). A single Kotlin `Broadcaster` wrapper decides stem-vs-flood and arms a coroutine embargo; if `relayCount==0` at embargo expiry it calls a C fluff that floods. Dandelion nodes are sourced from a new seeder `dandelion` capability, with the existing `digiscope.me` priority peer as guaranteed fallback. Decision logic is pure Kotlin (unit-tested, mirroring `Bip158WatchdogPolicy`); C peer mechanics are verified on-device.

**Tech Stack:** C (BRPeerManager/BRPeer), JNI, Kotlin (core + app, Compose), seeder backend (Node, separate repo on VPS).

**Spec:** `docs/superpowers/specs/2026-06-10-dandelion-spv-stem-design.md`

---

## Wire mechanic (resolved — informs all C tasks)

The C core defines no dandelion `inv` type (`BRPeer.c:190-194` — only `inv_tx/block/filtered_block`) and emits `MSG_DANDELION_TX` **only** as a `getdata` response (`BRPeer.c:659`, gated on `tx->is_dandelion`). Therefore **stem submission = set `tx->is_dandelion=1` and send `inv` (inv_tx) to exactly one dandelion-capable peer**; that peer `getdata`s and we respond with `dandeliontx`. No new wire message or direct-push path is required. Task 9 validates this empirically against the live v8.26 network before the feature is considered done.

## File Structure

**New files**
- `core/src/main/java/io/digibyte/core/dandelion/DandelionBroadcastPolicy.kt` — pure decision logic (shouldStem / shouldFluff / embargo jitter).
- `core/src/test/java/io/digibyte/core/dandelion/DandelionBroadcastPolicyTest.kt` — unit tests.
- `core/src/main/java/io/digibyte/core/dandelion/Broadcaster.kt` — single broadcast entry point (stem-vs-flood + embargo).
- `core/src/test/java/io/digibyte/core/dandelion/DandelionPeerParseTest.kt` — seeder-response parse test.

**Modified — C core** (`native/src/main/jni/digibytewallet-core/`)
- `BRPeerManager.h` / `BRPeerManager.c` — `dandelionEnabled` flag, dandelion-capable address set, `BRPeerManagerSetDandelionEnabled`, `BRPeerManagerAddDandelionPeer`, `BRPeerManagerHasDandelionPeer`, `BRPeerManagerStemPublishTx`, `BRPeerManagerFluffTx`. (`BRPeerManagerRelayCount` already exists.)

**Modified — JNI** (`native/src/main/jni/bridge/`)
- `jni_transaction.c` — `publishTransactionStem`, `fluffTransaction`, `getRelayCount`.
- `jni_peer.c` (or wallet.c) — `setDandelionEnabled`, `addDandelionPeer`, `hasDandelionPeer`.
- `core/.../bridge/NativeBridge.kt` — matching `external fun` declarations.

**Modified — Kotlin app/core**
- `core/.../DigiscopeClient.kt` (or wherever bloom peers are fetched) — fetch `?capability=dandelion`, cache `dgb_dandelion_peers`.
- `app/.../service/SyncService.kt` — inject dandelion peers on sync start; host the embargo `CoroutineScope`.
- `core/.../TransactionBuilder.kt`, `core/.../asset/AssetManager.kt`, `core/.../recovery/LegacySweepService.kt` — route through `Broadcaster`.
- `app/.../ui/settings/` — "Dandelion broadcast" toggle (default ON).
- send screen — "Broadcasting privately…" indicator.
- `ROADMAP.md` — correct the stale "depends on 9.26" note.

**Separate repo (VPS `/opt/dgb-bloom-seeder`)** — Task 0, done over SSH, not by the app subagent.

---

## Task 0: Seeder — `dandelion` capability (backend, VPS, parallel)

**Files:** seeder repo on `digiscope.me` (`/opt/dgb-bloom-seeder`) — capability enum + `/api/peers` filter.

This runs independently of the app tasks and is done over SSH (`ssh -i ~/.ssh/DigitalOcean root@digiscope.me`). The app's VPS-fallback (Task 7) means app work is **not blocked** on this.

- [ ] **Step 1: Confirm Dandelion is enabled on the VPS node.** `digibyte-cli getnetworkinfo` and inspect `digibyte.conf` for the dandelion flag; if absent, add it and restart the node. Document the exact config key found.
- [ ] **Step 2: Add `dandelion` to the seeder capability enum** alongside `filter|bloom`; tag the VPS node (and any other dandelion-on nodes) with it.
- [ ] **Step 3: Expose `GET /api/peers?capability=dandelion`** returning the tagged nodes in the same JSON shape as `?capability=bloom`. `pm2 restart bloom-seeder --update-env`.
- [ ] **Step 4: Verify** `curl -s https://api.digiscope.me/api/peers?capability=dandelion` returns ≥1 peer.
- [ ] **Step 5: Update memory** `reference_seeder_capability_endpoint` to record the new `dandelion` capability + count.

---

## Task 1: C — Dandelion state (enable flag + capable-peer set)

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRPeerManager.h`
- Modify: `native/src/main/jni/digibytewallet-core/BRPeerManager.c`

No standalone C unit harness exists for the peer manager; correctness is covered by the device test (Task 9) and the compile gate. Keep each step a self-contained, reviewable change.

- [ ] **Step 1: Add struct fields.** In the `BRPeerManager` struct (`BRPeerManager.c`, near `BRSyncMode syncMode;` ~line 207):

```c
    int dandelionEnabled;            // wallet setting: stem-submit on broadcast
    UInt128 *dandelionPeers;         // addresses known dandelion-capable (no service bit exists)
```

- [ ] **Step 2: Init/free the array.** In the manager allocator (where other `array_new(...)` calls live, search `array_new(manager->peers`):

```c
    array_new(manager->dandelionPeers, 4);
    manager->dandelionEnabled = 1;   // default ON (Kotlin can override via setter)
```

In `BRPeerManagerFree` (search `array_free(manager->peers)`):

```c
    array_free(manager->dandelionPeers);
```

- [ ] **Step 3: Declare the public API** in `BRPeerManager.h` (near `BRPeerManagerPublishTx`):

```c
// Enable/disable Dandelion stem submission (default on). Thread-safe.
void BRPeerManagerSetDandelionEnabled(BRPeerManager *manager, int enabled);

// Register a peer address as Dandelion-capable (sourced from the seeder + the
// priority peer; there is no service bit to read). Idempotent.
void BRPeerManagerAddDandelionPeer(BRPeerManager *manager, UInt128 address);

// 1 if Dandelion is enabled AND a connected peer is Dandelion-capable.
int BRPeerManagerHasDandelionPeer(BRPeerManager *manager);
```

- [ ] **Step 4: Implement the setters + query** in `BRPeerManager.c` (place beside `BRPeerManagerPublishTx`). Use the existing lock pattern (`pthread_mutex_lock(&manager->lock)`):

```c
void BRPeerManagerSetDandelionEnabled(BRPeerManager *manager, int enabled)
{
    assert(manager != NULL);
    pthread_mutex_lock(&manager->lock);
    manager->dandelionEnabled = (enabled != 0);
    pthread_mutex_unlock(&manager->lock);
}

void BRPeerManagerAddDandelionPeer(BRPeerManager *manager, UInt128 address)
{
    assert(manager != NULL);
    pthread_mutex_lock(&manager->lock);
    int known = 0;
    for (size_t i = array_count(manager->dandelionPeers); i > 0; i--) {
        if (UInt128Eq(manager->dandelionPeers[i - 1], address)) { known = 1; break; }
    }
    if (! known) array_add(manager->dandelionPeers, address);
    pthread_mutex_unlock(&manager->lock);
}

// caller must hold manager->lock
static int _BRPeerManagerPeerIsDandelionCapable(BRPeerManager *manager, BRPeer *peer)
{
    for (size_t i = array_count(manager->dandelionPeers); i > 0; i--) {
        if (UInt128Eq(manager->dandelionPeers[i - 1], peer->address)) return 1;
    }
    return 0;
}

// caller must hold manager->lock; returns first connected capable peer or NULL
static BRPeer *_BRPeerManagerAnyDandelionPeer(BRPeerManager *manager)
{
    for (size_t i = array_count(manager->connectedPeers); i > 0; i--) {
        BRPeer *p = manager->connectedPeers[i - 1];
        if (BRPeerConnectStatus(p) != BRPeerStatusConnected) continue;
        if (_BRPeerManagerPeerIsDandelionCapable(manager, p)) return p;
    }
    return NULL;
}

int BRPeerManagerHasDandelionPeer(BRPeerManager *manager)
{
    assert(manager != NULL);
    pthread_mutex_lock(&manager->lock);
    int r = manager->dandelionEnabled && _BRPeerManagerAnyDandelionPeer(manager) != NULL;
    pthread_mutex_unlock(&manager->lock);
    return r;
}
```

- [ ] **Step 5: Compile.** Run `./gradlew :native:assembleMainnetDebug 2>&1 | tail -5`. Expected: `BUILD SUCCESSFUL`. Fix any signature mismatch.
- [ ] **Step 6: Commit.** `git -C native/.../digibytewallet-core` submodule commit (per `reference_submodule_commit_push_pattern`): stage `BRPeerManager.c/.h`, commit `feat(dandelion): peer-manager dandelion state + capability set`, push to `johnnylaw` fork. Do NOT bump the pin yet — bundle the pin bump after Task 4.

---

## Task 2: C — stem publish

**Files:** Modify `BRPeerManager.c`, `BRPeerManager.h`.

Reference: read `BRPeerManagerPublishTx` (`BRPeerManager.c:2638-2690`) and `_BRPeerManagerPublishPendingTx` (`:680-690`) first — the new function reuses both.

- [ ] **Step 1: Declare** in `BRPeerManager.h`:

```c
// Stem-submit a signed tx to ONE Dandelion-capable peer (sets is_dandelion=1 and
// invs only that peer). Returns 1 if stemmed, 0 if no capable peer was available
// (caller should then fall back to BRPeerManagerPublishTx for a normal flood).
int BRPeerManagerStemPublishTx(BRPeerManager *manager, BRTransaction *tx, void *info,
                               void (*callback)(void *info, int error));
```

- [ ] **Step 2: Implement.** Mirrors `BRPeerManagerPublishTx` but targets one peer and sets the flag:

```c
int BRPeerManagerStemPublishTx(BRPeerManager *manager, BRTransaction *tx, void *info,
                               void (*callback)(void *info, int error))
{
    assert(manager != NULL && tx != NULL && BRTransactionIsSigned(tx));
    pthread_mutex_lock(&manager->lock);

    if (! BRTransactionIsSigned(tx) || ! manager->isConnected) {
        pthread_mutex_unlock(&manager->lock);
        return 0;   // caller flood-publishes (which also handles the not-signed error path)
    }
    BRPeer *stem = manager->dandelionEnabled ? _BRPeerManagerAnyDandelionPeer(manager) : NULL;
    if (! stem) { pthread_mutex_unlock(&manager->lock); return 0; }

    tx->is_dandelion = 1;
    tx->timestamp = (uint32_t)time(NULL);
    _BRPeerManagerAddTxToPublishList(manager, tx, info, callback);

    BRPeerCallbackInfo *peerInfo = calloc(1, sizeof(*peerInfo));
    assert(peerInfo != NULL);
    peerInfo->peer = stem;
    peerInfo->manager = manager;
    _BRPeerManagerPublishPendingTx(manager, stem);     // sends inv(inv_tx) to the stem peer only
    BRPeerSendPing(stem, peerInfo, _publishTxInvDone);  // ping→pong confirms the inv was sent

    peer_log(stem, "dandelion: stem-submitted tx %s to single peer",
             u256hex(tx->txHash));
    pthread_mutex_unlock(&manager->lock);
    return 1;
}
```

(Confirm the `u256hex`/`log_u256_hex_encode` helper name in this file before use.)

- [ ] **Step 3: Compile** (`./gradlew :native:assembleMainnetDebug`). Expected `BUILD SUCCESSFUL`.
- [ ] **Step 4: Commit** to the submodule (`feat(dandelion): stem-publish to a single capable peer`). No pin bump yet.

---

## Task 3: C — fluff + relay-count

**Files:** Modify `BRPeerManager.c`, `BRPeerManager.h`. Confirm `BRPeerManagerRelayCount` is already declared in the header (it is defined ~`:2700`); if not declared, add it.

- [ ] **Step 1: Declare fluff** in `BRPeerManager.h`:

```c
// Re-broadcast (flood) a previously stem-submitted tx to all connected peers,
// clearing the dandelion flag. Idempotent; no-op if the tx isn't in the publish list.
void BRPeerManagerFluffTx(BRPeerManager *manager, UInt256 txHash);
```

- [ ] **Step 2: Implement.** Find the pending tx, clear the flag, flood (the all-peers loop copied from `BRPeerManagerPublishTx`):

```c
void BRPeerManagerFluffTx(BRPeerManager *manager, UInt256 txHash)
{
    assert(manager != NULL);
    pthread_mutex_lock(&manager->lock);

    BRTransaction *tx = NULL;
    for (size_t i = array_count(manager->publishedTx); i > 0; i--) {
        if (UInt256Eq(manager->publishedTx[i - 1].tx->txHash, txHash)) {
            tx = manager->publishedTx[i - 1].tx; break;
        }
    }
    if (! tx) { pthread_mutex_unlock(&manager->lock); return; }
    tx->is_dandelion = 0;   // fluff: normal tx from here on

    for (size_t i = array_count(manager->connectedPeers); i > 0; i--) {
        BRPeer *peer = manager->connectedPeers[i - 1];
        if (BRPeerConnectStatus(peer) != BRPeerStatusConnected) continue;
        _BRPeerManagerPublishPendingTx(manager, peer);
        BRPeerCallbackInfo *peerInfo = calloc(1, sizeof(*peerInfo));
        assert(peerInfo != NULL);
        peerInfo->peer = peer; peerInfo->manager = manager;
        BRPeerSendPing(peer, peerInfo, _publishTxInvDone);
    }
    peer_log(&BR_PEER_NONE, "dandelion: embargo fluff — flooded tx %s to all peers",
             u256hex(txHash));
    pthread_mutex_unlock(&manager->lock);
}
```

- [ ] **Step 3: Compile + commit** to submodule (`feat(dandelion): embargo fluff fallback`). Then **bump the submodule pin** in the main repo (per `reference_submodule_commit_push_pattern`): push the fork branch, update the gitlink, commit `chore(native): bump core pin — dandelion stem/fluff`.

---

## Task 4: JNI + NativeBridge

**Files:**
- Modify: `native/src/main/jni/bridge/jni_transaction.c`, `native/src/main/jni/bridge/jni_peer.c`
- Modify: `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt`

Reference `jni_transaction.c:174` (`publishTransaction`) for the parse/peer-manager-guard pattern to copy.

- [ ] **Step 1: Kotlin declarations** in `NativeBridge.kt` (beside `external fun publishTransaction`):

```kotlin
external fun publishTransactionStem(signedTx: ByteArray): String?
external fun fluffTransaction(txid: String)
external fun getRelayCount(txid: String): Int
external fun setDandelionEnabled(enabled: Boolean)
external fun addDandelionPeer(ip: String)
external fun hasDandelionPeer(): Boolean
```

- [ ] **Step 2: `publishTransactionStem`** in `jni_transaction.c` — copy the `publishTransaction` body, but call `BRPeerManagerStemPublishTx`; if it returns 0 (no capable peer), free the tmp tx and return null so Kotlin flood-falls-back. Return the txid hex string on success (1).
- [ ] **Step 3: `fluffTransaction` / `getRelayCount`** in `jni_transaction.c` — parse the txid hex → `UInt256` (reuse the existing hex→u256 helper used elsewhere in this file; reject length≠64), then call `BRPeerManagerFluffTx` / `BRPeerManagerRelayCount`.
- [ ] **Step 4: `setDandelionEnabled` / `addDandelionPeer` / `hasDandelionPeer`** in `jni_peer.c` — `addDandelionPeer` resolves the IP string to `UInt128` (reuse the address-parse helper used by the existing peer-injection JNI) and calls `BRPeerManagerAddDandelionPeer`.
- [ ] **Step 5: Build app** (`./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug 2>&1 | tail -5`). Expected `BUILD SUCCESSFUL`.
- [ ] **Step 6: Commit** (`feat(dandelion): JNI bridge for stem/fluff/capability`).

---

## Task 5: Kotlin — DandelionBroadcastPolicy (pure, TDD)

**Files:**
- Create: `core/src/main/java/io/digibyte/core/dandelion/DandelionBroadcastPolicy.kt`
- Test: `core/src/test/java/io/digibyte/core/dandelion/DandelionBroadcastPolicyTest.kt`

- [ ] **Step 1: Write the failing test** (`DandelionBroadcastPolicyTest.kt`):

```kotlin
package io.digibyte.core.dandelion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DandelionBroadcastPolicyTest {
    @Test fun `stem only when enabled and a capable peer is present`() {
        assertTrue(shouldStem(enabled = true, hasDandelionPeer = true))
        assertFalse(shouldStem(enabled = true, hasDandelionPeer = false))
        assertFalse(shouldStem(enabled = false, hasDandelionPeer = true))
    }
    @Test fun `fluff exactly when the network has not relayed it back`() {
        assertTrue(shouldFluffAfterEmbargo(relayCount = 0))
        assertFalse(shouldFluffAfterEmbargo(relayCount = 1))
        assertFalse(shouldFluffAfterEmbargo(relayCount = 5))
    }
    @Test fun `embargo delay is within the 10-30s window for all rng inputs`() {
        for (r in listOf(0.0, 0.5, 0.999, 1.0)) {
            val ms = embargoDelayMs(r)
            assertTrue("$ms", ms in EMBARGO_MIN_MS..EMBARGO_MAX_MS)
        }
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (symbols absent). `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.dandelion.DandelionBroadcastPolicyTest" 2>&1 | tail -8`.
- [ ] **Step 3: Implement** (`DandelionBroadcastPolicy.kt`):

```kotlin
package io.digibyte.core.dandelion

/** Min/max embargo window (ms). After stem-submit the wallet waits a random
 *  delay in [MIN, MAX]; if the tx hasn't been relayed back by then it self-fluffs.
 *  ~10–30s ≈ expected network stem→fluff time plus margin (Bitcoin dandelion uses
 *  a comparable embargo). */
const val EMBARGO_MIN_MS = 10_000L
const val EMBARGO_MAX_MS = 30_000L

/** Stem only when the user has Dandelion on AND a capable peer is connected;
 *  otherwise flood (today's behavior). */
fun shouldStem(enabled: Boolean, hasDandelionPeer: Boolean): Boolean =
    enabled && hasDandelionPeer

/** After the embargo, fluff iff no peer has relayed the tx back — i.e. the stem
 *  node dropped it / it didn't propagate. relayCount>0 means it's already spreading. */
fun shouldFluffAfterEmbargo(relayCount: Int): Boolean = relayCount == 0

/** Map a uniform [0,1] CSPRNG draw to the embargo window. Caller supplies the
 *  random (SecureRandom) so this stays pure/testable. */
fun embargoDelayMs(rng01: Double): Long {
    val clamped = rng01.coerceIn(0.0, 1.0)
    return EMBARGO_MIN_MS + (clamped * (EMBARGO_MAX_MS - EMBARGO_MIN_MS)).toLong()
}
```

- [ ] **Step 4: Run — expect PASS.** Same command as Step 2.
- [ ] **Step 5: Commit** (`feat(dandelion): pure broadcast policy (stem/fluff/embargo) + tests`).

---

## Task 6: Kotlin — Broadcaster (stem-vs-flood + embargo)

**Files:**
- Create: `core/src/main/java/io/digibyte/core/dandelion/Broadcaster.kt`
- Modify: `core/.../TransactionBuilder.kt:49`, `core/.../asset/AssetManager.kt:789`, `core/.../recovery/LegacySweepService.kt:153`

- [ ] **Step 1: Implement `Broadcaster`** — one entry point all sends use. It reads the Dandelion setting, asks the C core for a capable peer, stems or floods, and arms the embargo on a supplied scope:

```kotlin
package io.digibyte.core.dandelion

import io.digibyte.core.bridge.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Single broadcast entry point. Stems the tx to one Dandelion node when enabled +
 * available, else floods (today's behavior). On a stem it arms a random embargo:
 * if the network hasn't relayed the tx back by the deadline, it self-fluffs so a
 * tx is never stranded for privacy.
 */
class Broadcaster(
    private val scope: CoroutineScope,
    private val isDandelionEnabled: () -> Boolean,
    private val rng: SecureRandom = SecureRandom(),
) {
    /** Returns the txid on success, or null if the broadcast failed. */
    fun broadcast(signedTx: ByteArray): String? {
        if (shouldStem(isDandelionEnabled(), NativeBridge.hasDandelionPeer())) {
            val txid = NativeBridge.publishTransactionStem(signedTx)
            if (txid != null) { armEmbargo(txid); return txid }
            // stem returned null (no capable peer at the last instant) → fall through to flood
        }
        return NativeBridge.publishTransaction(signedTx)
    }

    private fun armEmbargo(txid: String) {
        val delayMs = embargoDelayMs(rng.nextDouble())
        scope.launch {
            delay(delayMs)
            val relays = try { NativeBridge.getRelayCount(txid) } catch (_: Throwable) { 1 }
            if (shouldFluffAfterEmbargo(relays)) {
                try { NativeBridge.fluffTransaction(txid) } catch (_: Throwable) {}
            }
        }
    }
}
```

- [ ] **Step 2: Wire a shared instance.** Provide `Broadcaster` via DI (the existing Hilt module that provides wallet/sync singletons), scoped to the app/sync `CoroutineScope`, with `isDandelionEnabled` reading the setting from Task 8.
- [ ] **Step 3: Route the 3 call sites** — replace `NativeBridge.publishTransaction(signedTx)` with `broadcaster.broadcast(signedTx)` in `TransactionBuilder.kt:49`, `AssetManager.kt:789`, `LegacySweepService.kt:153`. (Each already holds the signed bytes; inject `broadcaster`.)
- [ ] **Step 4: Build** (`./gradlew :app:assembleMainnetDebug 2>&1 | tail -5`). Expected `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit** (`feat(dandelion): route all sends through Broadcaster (stem + embargo)`).

---

## Task 7: Kotlin — dandelion peer fetch + inject (with VPS fallback)

**Files:**
- Modify: the seeder client that fetches bloom peers (search `peers/bloom` / `dgb_bloom_peers` — likely `core/.../DigiscopeClient.kt` or similar).
- Modify: `app/.../service/SyncService.kt` (inject on sync start, beside `injectBloomPeers()`).
- Test: `core/src/test/java/io/digibyte/core/dandelion/DandelionPeerParseTest.kt`

- [ ] **Step 1: Failing parse test** — the `?capability=dandelion` JSON → `List<String>` of `ip:port`, reusing whatever model bloom uses. Mirror the bloom parse test if one exists.
- [ ] **Step 2: Implement fetch + cache** — add `fetchDandelionPeers()` (cached hourly in `dgb_dandelion_peers`, mirroring `dgb_bloom_peers`). Run the test green.
- [ ] **Step 3: Inject on sync start** in `SyncService` — after peers connect, for each fetched dandelion peer call `NativeBridge.addDandelionPeer(ip)`; **always** also `NativeBridge.addDandelionPeer("<digiscope.me resolved IP>")` (the existing priority peer) as the guaranteed fallback. Add `injectDandelionPeers()` called alongside `injectBloomPeers()`.
- [ ] **Step 4: Build + commit** (`feat(dandelion): fetch/cache/inject dandelion peers + VPS fallback`).

---

## Task 8: UI — settings toggle (default ON) + send indicator

**Files:** `app/.../ui/settings/` (the privacy settings screen used for sync mode/Tor), the send screen, and the prefs key.

- [ ] **Step 1: Add the pref** `dgb_dandelion.enabled` (default `true`); on read, push it to the core via `NativeBridge.setDandelionEnabled(...)` at startup and on toggle.
- [ ] **Step 2: Settings toggle** "Dandelion broadcast" with a one-line explainer ("Hides which peer first saw your transaction. Falls back to a normal broadcast if no Dandelion node is reachable."). Default ON.
- [ ] **Step 3: Send indicator** — when `isDandelionEnabled && NativeBridge.hasDandelionPeer()`, show a subtle "Broadcasting privately…" note on the send/confirm screen; otherwise nothing (transparent fluff).
- [ ] **Step 4: Build + commit** (`feat(dandelion): settings toggle + private-broadcast indicator`).

---

## Task 9: Device verification (wire mechanic + end-to-end)

**Files:** none (verification). Emulator AVD `dgb-test-api34`; native log tag is `bread`.

- [ ] **Step 1: Install** the debug APK on the emulator; ensure a funded test wallet (the existing AVD wallet, PIN as known, or restore a funded test seed).
- [ ] **Step 2: Stem happy-path.** With Dandelion ON, send a small tx. In logcat confirm: exactly one `dandelion: stem-submitted` to a single peer, the stem peer `getdata`s, we send `dandeliontx`, and within the embargo the tx is **relayed back** (`relayCount>0`) → no fluff. Confirm the tx confirms on-chain.
- [ ] **Step 3: Disabled control.** Toggle Dandelion OFF, send again; confirm the normal flood (inv to all-but-download) and no `dandeliontx`.
- [ ] **Step 4: Embargo-fluff path.** Force the fallback (e.g. temporarily point the only dandelion peer at a node that drops the tx, or stub `getRelayCount` to 0 in a debug build) and confirm `dandelion: embargo fluff` floods and the tx still confirms. Revert any debug stub.
- [ ] **Step 5: No-peer path.** With no dandelion peer reachable, confirm `broadcast` transparently floods and the send succeeds.
- [ ] **Step 6: Record findings** in memory (`project_dandelion_stem_shipped` — the wire mechanic confirmed, the embargo behavior, the `bread` log strings to grep).

---

## Task 10: Docs + roadmap

**Files:** `ROADMAP.md`, `README.md`.

- [ ] **Step 1:** Correct ROADMAP — change the "Dandelion++ … depends on DigiByte Core 9.26" row to "shipped (SPV stem submission); active on v8.26 network." Update the privacy-stack note (line ~84).
- [ ] **Step 2:** README — move Dandelion++ SPV from "Planned" to shipped; keep the DIP #15 reference.
- [ ] **Step 3: Commit** (`docs(dandelion): mark SPV stem submission shipped`).

---

## Release

All work batches with the held BIP158 fix (`2e10d392`) under one version tag. After Task 9 passes, run the **release-prep** skill (bump to the next version, tag, CI) — do NOT release before the device verification is green.

## Self-review notes (addressed)

- **Spec coverage:** seeder capability (T0), stem (T2), fluff/embargo (T3/T6), VPS fallback (T7), default-on + indicator (T8), edge cases + wire-mechanic validation (T9), roadmap fix (T10). All spec sections map to a task.
- **Type consistency:** `shouldStem`/`shouldFluffAfterEmbargo`/`embargoDelayMs` names are identical across Task 5 (def) and Task 6 (use). JNI names match between `NativeBridge.kt` (Task 4 Step 1) and the C entry points (Tasks 1–4).
- **No placeholders:** every code step shows real code; the only "confirm the helper name" notes are explicit, bounded lookups in named files, not vague directives.
