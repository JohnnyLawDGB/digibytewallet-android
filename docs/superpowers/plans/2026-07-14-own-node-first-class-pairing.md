# Own-Node First-Class Pairing — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the v3.10.1 own-node *primitive* (a buried toggle + host:port field, injected optimistically, evictable, unverified) into a first-class **pair → verify → pin → health** flow: QR-scan a `dgbnode://` URI, verify the node actually serves compact filters, pin it (never churn-evicted, always dialed), surface its health on the main screen, and offer an opt-in **exclusive** "only my node" posture — applied immediately (no forced restart).

**Architecture:** Two new native core predicates (`BRPeerPin.h`, `BRPeerCFStatus.h`, pure `static inline`, host-KAT-tested like `BRPeerPenalty.h`) plus their wiring into `BRPeerManager` (a pinned-peer slot + a per-peer compact-filter-served signal + a status accessor). Three new JNI wrappers (outer-repo `jni_peer.c`) + `external fun`s. Kotlin: a `dgbnode://` parser, extended `CustomNodePrefs`, an immediate-apply Service entry point reusing the existing keepalive reconnect triple, a health StateFlow, and Compose UI (QR pairing route + confirm screen, upgraded Network Info section, main-screen health chip + dark-node banner). Preserves the CF-only sovereignty invariant.

**Tech Stack:** C (breadwallet-fork submodule core), JNI, Kotlin, Jetpack Compose, Hilt, CameraX + ZXing, JUnit/MockK, host KAT (clang).

**Design spec:** `docs/superpowers/specs/2026-07-14-own-node-first-class-pairing.md`. Authoritative sequence doc: `docs/superpowers/specs/2026-07-11-cf-fleet-reliability-own-node-track.md`. Context (the shipped primitive): `docs/superpowers/plans/2026-07-08-own-node-cf-peer-and-mainnet-cf-gate.md`.

## Global Constraints

- **Submodule boundary.** Files under `native/src/main/jni/digibytewallet-core/` (`BR*.c/.h`, new `BRPeerPin.h`, `BRPeerCFStatus.h`) are the **submodule** — commit with the `GIT_DIR`/`GIT_WORK_TREE` pattern, push to `johnnylaw`, then bump the outer pin (memory `reference_submodule_commit_push_pattern`). Files under `native/src/main/jni/bridge/` (`jni_peer.c`, `jni_bridge.h`, `jni_wallet.c`), `native/src/test/host/**`, `native/CMakeLists.txt`, and all Kotlin are **outer-repo** plain commits. Use `--ignore-submodules=all` on outer git status/diff.
- **HARD ORDERING / PIN GATE.** Task 2 changes the submodule; its commit **must be pushed to `johnnylaw` and the outer pin bumped** before Task 3 (JNI, which calls the new core functions) and any app task builds. If the app builds against a pin lacking Task 2, the new JNI symbols are undefined → link/`UnsatisfiedLinkError` at first call. Verify the pin is at/after the Task-2 commit before Task 3.
- **KAT-first, header-only.** New native logic is expressed as pure `static inline` predicates in headers so the host KATs compile header-only (no `BRPeerManager.c` linkage — it drags pthreads/sockets/crypto). KAT `REPO_ROOT` is **five `../`** (`native/src/test/host/<kat>/` → repo root); compile line `clang -w -include stdint.h -I "$CORE_DIR" <main>.c -o <bin>` (model: `native/src/test/host/peer_penalty_kat/run.sh`).
- **Service bits (verbatim, `BRPeer.h:74-77`):** `SERVICES_NODE_NETWORK 0x01`, `SERVICES_NODE_BLOOM 0x04`, `SERVICES_NODE_COMPACT_FILTERS 0x40`. Own node is injected as `0x41` (`NODE_NETWORK|NODE_COMPACT_FILTERS`). Sync modes (`BRPeerManager.h:107-111`): `BR_SYNC_MODE_BLOOM_ONLY=0`, `BR_SYNC_MODE_COMPACT_FILTERS_ONLY=1`, `BR_SYNC_MODE_BOTH=2`; Kotlin mirror `NativeBridge.SyncMode` (`NativeBridge.kt:408-412`). `PEER_MAX_CONNECTIONS 8` (`BRPeerManager.h:46`).
- **IPv4-mapped `UInt128` (verbatim).** An IPv4 literal `a.b.c.d` maps to `UInt128 addr` via `addr = UINT128_ZERO; addr.u16[5] = 0xffff; addr.u32[3] = <inet_pton AF_INET s_addr, network byte order>;` (canonical at `jni_peer.c:462-465`, `addDandelionPeer` 1069-1073). Compare a target ip:port to a peer with `UInt128Eq(peer->address, a) && peer->port == p` (as `BRPeerPenaltyContains`, `BRPeerPenalty.h:53`).
- **Never call the new accessors from BRPeerManager callbacks.** They take `PEER_GUARD` (recursive `g_peerManagerMutex`); calling them from `bridge_sync*`/peer-thread callbacks risks the `BRPeerManagerFree`-join deadlock (`jni_bridge.h:61-66`). They are JVM-entry-only.
- **Preserve the CF-only invariant.** `syncModeFor` (`CustomNode.kt:48-49`) already unconditionally returns `COMPACT_FILTERS_ONLY`; the bloom watchdog is already dead (`_bloomFallbackActive` never set true, `fallbackToBloom()` never called). Do **not** re-introduce any bloom path. Own-node exclusive mode must never fall back to public peers *silently* — only via the explicit user escape hatch (Task 10).
- **Immediate-apply routes through a Service entry point**, reusing the existing keepalive triple `forceReconnect() → injectBloomPeers() → injectCustomNode() → startSync()` (`SyncService.kt:396-402`). Do **NOT** add a raw `NativeBridge.forceReconnect()` in a ViewModel — `injectCustomNode()` is `private` to the Service and the injection must run on the reconnect.
- **Verify = warn, never block.** A node that fails verification is still paired; health shows ⚠/✗ and retries. Pairing UI never hard-blocks on an unreachable/not-serving node.
- **Network-suffixed prefs.** New `CustomNodePrefs` keys use the existing `key(base, ctx) = base + networkSuffix(ctx)` pattern (`CustomNode.kt:57`). `dgb_settings` itself is never suffixed.
- **Clearnet only.** IPv4 literal or A-record hostname; no onion/IPv6 (deferred to Seq 2.5). The `dgbnode://` grammar reserves room for onion but the parser rejects it for now.
- **Docs same-PR** (house rule 5) and **security suite** re-run (house rule 4) before the PR.

## File Structure

**Submodule (`native/src/main/jni/digibytewallet-core/`) — create:**
- `BRPeerPin.h` — pure pinned-peer predicate.
- `BRPeerCFStatus.h` — pure CF-peer-status computation.

**Submodule — modify:**
- `BRPeerManager.c` / `BRPeerManager.h` — pinned-peer struct fields + set/clear + dial-first + eviction-exempt + exclusive dial suppression; per-peer CF-served table; `BRPeerManagerCompactFilterPeerStatus`.

**Outer-repo — create:**
- `native/src/test/host/own_node_pin_kat/{own_node_pin_kat_main.c,run.sh}`
- `native/src/test/host/cf_peer_status_kat/{cf_peer_status_kat_main.c,run.sh}`
- `core/src/main/java/io/digibyte/core/settings/OwnNodeUri.kt` + `core/src/test/java/io/digibyte/core/settings/OwnNodeUriTest.kt`
- `app/src/main/java/io/digibyte/ui/settings/NodePairConfirmScreen.kt`

**Outer-repo — modify:**
- `native/src/main/jni/bridge/jni_peer.c` (3 JNI fns + exclusive prepend suppression in `startSync`)
- `native/CMakeLists.txt` (list the 2 new headers)
- `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt` (3 `external fun`s)
- `core/src/main/java/io/digibyte/core/settings/CustomNode.kt` (`CustomNodePrefs`: `label`, `exclusive`)
- `app/src/main/java/io/digibyte/service/SyncService.kt` (`injectCustomNode` pin call; immediate-apply action; `ownNodeHealth` companion flow + poll)
- `app/src/main/java/io/digibyte/ui/settings/SettingsViewModel.kt` (pairing/label/exclusive state, `applyOwnNodeNow`, health)
- `app/src/main/java/io/digibyte/ui/settings/NetworkInfoScreen.kt` (QR button, verification readout, exclusive toggle, label, drop restart copy)
- `app/src/main/java/io/digibyte/ui/wallet/WalletScreen.kt` + `WalletViewModel.kt` (health chip + dark-node banner + escape hatch)
- `app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt` (`onNode` scan branch + `node_pair_confirm` route)
- `app/src/main/java/io/digibyte/ui/components/QrScannerScreen.kt` (`dgbnode://` dispatch branch)
- `ROADMAP.md` (check 1.1/1.2), docs pair-your-node note.

---

## Task 1: Native pure predicates + host KATs (KAT-first)

**Files:**
- Create: `native/src/main/jni/digibytewallet-core/BRPeerPin.h`, `native/src/main/jni/digibytewallet-core/BRPeerCFStatus.h` (submodule)
- Create: `native/src/test/host/own_node_pin_kat/{own_node_pin_kat_main.c,run.sh}`, `native/src/test/host/cf_peer_status_kat/{cf_peer_status_kat_main.c,run.sh}` (outer)
- Modify: `native/CMakeLists.txt` (outer)

**Interfaces:**
- Produces: `static inline int BRPeerIsPinned(UInt128 pinnedAddr, uint16_t pinnedPort, UInt128 a, uint16_t p)` (1 if a:p equals the pinned addr:port and a pin is set, else 0). Consumed by Task 2.
- Produces: `static inline int BRComputeCFPeerStatus(int inPool, int connected, int served)` → `0 UNKNOWN / 1 CONNECTING / 2 CONNECTED_NOT_SERVING / 3 SERVING`. Consumed by Task 2.

- [ ] **Step 1: Write the failing pin KAT** — `native/src/test/host/own_node_pin_kat/own_node_pin_kat_main.c`

```c
// Host KAT for BRPeerIsPinned — the pure pinned-peer match predicate.
#include <stdio.h>
#include <stdint.h>
#include "BRInt.h"
#include "BRPeerPin.h"

static int g_failures = 0;
static void check(int cond, const char *desc) {
    printf("%s: %s\n", cond ? "PASS" : "FAIL", desc);
    if (! cond) g_failures++;
}
static UInt128 make_addr(uint8_t a, uint8_t b, uint8_t c, uint8_t d) {
    UInt128 r = UINT128_ZERO; r.u16[5] = 0xffff;
    r.u8[12] = a; r.u8[13] = b; r.u8[14] = c; r.u8[15] = d;   // network-order low word
    return r;
}
int main(void) {
    UInt128 node = make_addr(10,0,0,5);
    UInt128 other = make_addr(1,2,3,4);
    UInt128 none = UINT128_ZERO;

    check(BRPeerIsPinned(node, 12024, node, 12024) == 1, "exact addr+port match is pinned");
    check(BRPeerIsPinned(node, 12024, node, 12099) == 0, "same addr different port not pinned");
    check(BRPeerIsPinned(node, 12024, other, 12024) == 0, "different addr not pinned");
    check(BRPeerIsPinned(none, 0, node, 12024) == 0, "no pin set (zero addr/port) never matches");
    check(BRPeerIsPinned(node, 12024, none, 0) == 0, "zero candidate never matches a set pin");

    printf(g_failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", g_failures);
    return g_failures ? 1 : 0;
}
```

- [ ] **Step 2: Write `own_node_pin_kat/run.sh`** (model `peer_penalty_kat/run.sh`; five `../`)

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
clang -w -include stdint.h -I "$CORE_DIR" "$SCRIPT_DIR/own_node_pin_kat_main.c" -o "$BUILD_DIR/own_node_pin_kat"
"$BUILD_DIR/own_node_pin_kat"
```

- [ ] **Step 3: Write the failing status KAT** — `native/src/test/host/cf_peer_status_kat/cf_peer_status_kat_main.c`

```c
// Host KAT for BRComputeCFPeerStatus — pure (inPool, connected, served) -> status.
#include <stdio.h>
#include <stdint.h>
#include "BRPeerCFStatus.h"

static int g_failures = 0;
static void check(int cond, const char *desc) {
    printf("%s: %s\n", cond ? "PASS" : "FAIL", desc);
    if (! cond) g_failures++;
}
int main(void) {
    check(BRComputeCFPeerStatus(0,0,0) == BR_CF_PEER_UNKNOWN,               "absent -> UNKNOWN");
    check(BRComputeCFPeerStatus(1,0,0) == BR_CF_PEER_CONNECTING,           "in pool, not connected -> CONNECTING");
    check(BRComputeCFPeerStatus(1,1,0) == BR_CF_PEER_CONNECTED_NOT_SERVING,"connected, not served -> NOT_SERVING");
    check(BRComputeCFPeerStatus(1,1,1) == BR_CF_PEER_SERVING,             "connected + served -> SERVING");
    check(BRComputeCFPeerStatus(0,1,1) == BR_CF_PEER_UNKNOWN,             "not in pool dominates (defensive)");
    printf(g_failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", g_failures);
    return g_failures ? 1 : 0;
}
```

- [ ] **Step 4: Write `cf_peer_status_kat/run.sh`** — identical shape to Step 2 with `cf_peer_status_kat_main.c` (note: only includes `BRPeerCFStatus.h`, which needs only `<stdint.h>` — no `BRInt.h`).

- [ ] **Step 5: Run both KATs — verify they FAIL** (`fatal error: 'BRPeerPin.h' file not found` / `'BRPeerCFStatus.h' file not found`)

Run: `chmod +x native/src/test/host/own_node_pin_kat/run.sh native/src/test/host/cf_peer_status_kat/run.sh && native/src/test/host/own_node_pin_kat/run.sh; native/src/test/host/cf_peer_status_kat/run.sh`

- [ ] **Step 6: Create `BRPeerPin.h`** (submodule)

```c
#ifndef BRPeerPin_h
#define BRPeerPin_h

#include <stdint.h>
#include "BRInt.h"   // UInt128, UInt128Eq, UINT128_ZERO

// Pure predicate: is candidate addr:port the pinned own-node? A pin is "set" when
// pinnedPort != 0 (a zero addr/port means no pin). Modeled on BRPeerPenalty.h's
// static-inline-predicate shape so it is host-testable with no BRPeerManager.c.
static inline int BRPeerIsPinned(UInt128 pinnedAddr, uint16_t pinnedPort, UInt128 a, uint16_t p)
{
    if (pinnedPort == 0) return 0;                 // no pin configured
    return (UInt128Eq(pinnedAddr, a) && pinnedPort == p) ? 1 : 0;
}

#endif // BRPeerPin_h
```

- [ ] **Step 7: Create `BRPeerCFStatus.h`** (submodule)

```c
#ifndef BRPeerCFStatus_h
#define BRPeerCFStatus_h

#include <stdint.h>

// Compact-filter peer status, computed from three booleans the manager gathers:
//   inPool    — a peer with this addr:port exists in manager->peers
//   connected — that peer's status is Connected with an open socket
//   served    — that peer has answered a cfheaders/cfilter request this session
// Pure so the mapping is host-KAT-testable without the manager.
enum {
    BR_CF_PEER_UNKNOWN              = 0,
    BR_CF_PEER_CONNECTING          = 1,
    BR_CF_PEER_CONNECTED_NOT_SERVING = 2,
    BR_CF_PEER_SERVING             = 3,
};

static inline int BRComputeCFPeerStatus(int inPool, int connected, int served)
{
    if (! inPool) return BR_CF_PEER_UNKNOWN;
    if (! connected) return BR_CF_PEER_CONNECTING;
    return served ? BR_CF_PEER_SERVING : BR_CF_PEER_CONNECTED_NOT_SERVING;
}

#endif // BRPeerCFStatus_h
```

- [ ] **Step 8: Run both KATs — verify they PASS** (each prints its `check`s then `ALL PASSED`, exit 0).

- [ ] **Step 9: List both headers in `native/CMakeLists.txt`** alongside the other explicitly-listed `digibytewallet-core/*.h` (convention/IDE indexing; same-dir include already works). Add `src/main/jni/digibytewallet-core/BRPeerPin.h` and `.../BRPeerCFStatus.h`.

- [ ] **Step 10: Commit** — headers to the submodule, KATs + CMake to the outer repo

```bash
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
  git add BRPeerPin.h BRPeerCFStatus.h
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
  git commit -m "feat(peers): pure BRPeerIsPinned + BRComputeCFPeerStatus predicates"
git add native/src/test/host/own_node_pin_kat native/src/test/host/cf_peer_status_kat native/CMakeLists.txt
git commit -m "test(peers): host KATs for pinned-peer + CF-peer-status predicates"
```
(The submodule push + pin bump happen in Task 2, which also touches the submodule — bundle one push/pin bump covering Tasks 1+2 headers.)

---

## Task 2: Wire pinned peer + CF-served signal + status accessor into `BRPeerManager`

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRPeerManager.c`, `BRPeerManager.h` (submodule)

**Interfaces:**
- Consumes: `BRPeerIsPinned`, `BRComputeCFPeerStatus` (Task 1).
- Produces (declare in `BRPeerManager.h`):
  - `void BRPeerManagerSetPinnedPeer(BRPeerManager *manager, UInt128 addr, uint16_t port, int exclusive);`
  - `void BRPeerManagerClearPinnedPeer(BRPeerManager *manager);`
  - `int BRPeerManagerCompactFilterPeerStatus(BRPeerManager *manager, UInt128 addr, uint16_t port);`
  Consumed by Task 3 (JNI).

> **The implementer must Read the current `BRPeerManager.c` regions before editing** — line numbers below are from a 2026-07-14 read and may drift. Anchor on the named symbols.

- [ ] **Step 1: Add struct fields** in `struct BRPeerManagerStruct` (`BRPeerManager.c:199`), next to the penalty fields (`278-281`):

```c
    // Pinned own-node: a user-paired node kept as a reserved, never-churn-evicted
    // CF peer. pinnedPort == 0 means no pin. pinnedExclusive: dial ONLY this node.
    UInt128  pinnedAddr;
    uint16_t pinnedPort;
    int      pinnedExclusive;
    // Per-peer "answered cfheaders/cfilter this session" set (positive CF-served
    // signal; mirrors cfDisagreedPeers). Ring buffer, calloc-zeroed.
    UInt128  cfServedAddr[16];
    uint16_t cfServedPort[16];
    size_t   cfServedCount;
```
(All zero-initialized by the existing `calloc(1, sizeof(*manager))` at `2035` — no explicit init needed, matching the penalty arrays.)

- [ ] **Step 2: Add the includes** at the top of `BRPeerManager.c` (near the `BRPeerServices.h`/`BRPeerPenalty.h` includes):

```c
#include "BRPeerPin.h"
#include "BRPeerCFStatus.h"
```

- [ ] **Step 3: Add a private CF-served helper + recorder.** Near `_penalize` (`985`):

```c
// Record that `peer` answered a compact-filter request (positive served signal).
static void _recordCFServed(BRPeerManager *manager, BRPeer *peer)
{
    UInt128 a = BRPeerAddress(peer); uint16_t p = BRPeerPort(peer);
    for (size_t i = 0; i < manager->cfServedCount && i < 16; i++) {
        if (UInt128Eq(manager->cfServedAddr[i], a) && manager->cfServedPort[i] == p) return;
    }
    size_t idx = manager->cfServedCount % 16;
    manager->cfServedAddr[idx] = a; manager->cfServedPort[idx] = p;
    manager->cfServedCount++;
}
static int _cfServedContains(const BRPeerManager *manager, UInt128 a, uint16_t p)
{
    size_t n = manager->cfServedCount < 16 ? manager->cfServedCount : 16;
    for (size_t i = 0; i < n; i++)
        if (UInt128Eq(manager->cfServedAddr[i], a) && manager->cfServedPort[i] == p) return 1;
    return 0;
}
```
(Use the real peer addr/port accessors — confirm `BRPeerAddress`/`BRPeerPort` exist in `BRPeer.h`; if the code accesses `peer->address`/`peer->port` directly elsewhere, match that.)

- [ ] **Step 4: Call `_recordCFServed` on real CF responses.** In `_peerRelayedCFHeaders` (`2403`), on the success path where `ok` is proven (after the tip advance, ~`2569`): `_recordCFServed(manager, peer);`. In `_peerRelayedCFilter` (`2616`), after the filter verify passes (~`2636`, before the getdata at `2686`): `_recordCFServed(manager, peer);`. Both already hold `manager->lock` and have the live `peer`.

- [ ] **Step 5: Implement the three public functions** (place near `BRPeerManagerConnectStatus`, `2765`):

```c
void BRPeerManagerSetPinnedPeer(BRPeerManager *manager, UInt128 addr, uint16_t port, int exclusive)
{
    assert(manager != NULL);
    pthread_mutex_lock(&manager->lock);
    manager->pinnedAddr = addr;
    manager->pinnedPort = port;
    manager->pinnedExclusive = exclusive ? 1 : 0;
    pthread_mutex_unlock(&manager->lock);
}

void BRPeerManagerClearPinnedPeer(BRPeerManager *manager)
{
    assert(manager != NULL);
    pthread_mutex_lock(&manager->lock);
    manager->pinnedAddr = UINT128_ZERO;
    manager->pinnedPort = 0;
    manager->pinnedExclusive = 0;
    pthread_mutex_unlock(&manager->lock);
}

int BRPeerManagerCompactFilterPeerStatus(BRPeerManager *manager, UInt128 addr, uint16_t port)
{
    assert(manager != NULL);
    int inPool = 0, connected = 0, served = 0;
    pthread_mutex_lock(&manager->lock);
    for (size_t i = 0; i < array_count(manager->peers); i++) {
        if (! UInt128Eq(manager->peers[i].address, addr) || manager->peers[i].port != port) continue;
        inPool = 1; break;
    }
    for (size_t i = 0; inPool && i < array_count(manager->connectedPeers); i++) {
        BRPeer *p = manager->connectedPeers[i];
        if (! UInt128Eq(BRPeerAddress(p), addr) || BRPeerPort(p) != port) continue;
        connected = (BRPeerConnectStatus(p) == BRPeerStatusConnected && BRPeerIsSocketOpen(p)) ? 1 : 0;
        break;
    }
    served = _cfServedContains(manager, addr, port);
    pthread_mutex_unlock(&manager->lock);
    return BRComputeCFPeerStatus(inPool, connected, served);
}
```
(Confirm `manager->peers` / `manager->connectedPeers` are `array_*` lists and the exact element types — `peers` is a `BRPeer[]` value array, `connectedPeers` a `BRPeer*[]`. Adapt the addr/port access to match how each is stored. `BRPeerStatusConnected` / `BRPeerConnectStatus` / `BRPeerIsSocketOpen` per `BRPeer.h:201/205`.)

- [ ] **Step 6: Pinned dial-first.** In `BRPeerManagerConnect` (`2851`), at the top of the filter-first pre-pass (before the `2903-2938` loop), dial the pinned peer first if set, not connected, and not penalized. Add:

```c
    // Reserved slot: always dial the pinned own-node first (it can be buried past
    // the dial cutoff by timestamp/qsort otherwise). Skip if already connected.
    if (manager->pinnedPort != 0 && connectedPeers < manager->maxConnectCount) {
        int already = 0;
        for (size_t i = 0; i < array_count(manager->connectedPeers); i++)
            if (UInt128Eq(BRPeerAddress(manager->connectedPeers[i]), manager->pinnedAddr) &&
                BRPeerPort(manager->connectedPeers[i]) == manager->pinnedPort) { already = 1; break; }
        if (! already) {
            for (size_t k = 0; k < array_count(manager->peers); k++) {
                if (! UInt128Eq(manager->peers[k].address, manager->pinnedAddr) ||
                    manager->peers[k].port != manager->pinnedPort) continue;
                _BRPeerManagerBeginConnect(manager, &manager->peers[k]);  // confirm the real dial helper signature
                connectedPeers++;
                break;
            }
        }
    }
```
(Match `_BRPeerManagerBeginConnect`'s real signature/`2786`; `connectedPeers` is the local count the loop maintains — confirm its name.)

- [ ] **Step 7: Exclusive dial suppression.** In the filter-first pre-pass loop (`2903-2938`) and the shotgun fallback (`2940-3021`), when `manager->pinnedExclusive`, dial **only** the pinned peer — skip every non-pinned candidate. Guard both loops:

```c
        if (manager->pinnedExclusive &&
            ! BRPeerIsPinned(manager->pinnedAddr, manager->pinnedPort,
                             manager->peers[k].address, manager->peers[k].port)) continue;
```
(The pinned peer itself is dialed by Step 6; this makes exclusive mode contact only it.)

- [ ] **Step 8: Eviction exemption.** In `BRPeerManagerKeepAlive` (`3067`), guard the inbound-idle disconnect (`3089-3091`) so the pinned peer is never idle-evicted:

```c
        if (BRPeerIsPinned(manager->pinnedAddr, manager->pinnedPort, BRPeerAddress(p), BRPeerPort(p)))
            continue;   // never idle-evict the pinned own-node
        if (now - BRPeerLastRecvTime(p) > PEER_INBOUND_IDLE_LIMIT) BRPeerScheduleDisconnect(p, 0);
```
Also, in `_peerConnected`'s reject ladder (`1012-1043`) — do **not** `_penalize` the pinned peer (so a transient reject can't park the user's own node). Guard the `_penalize` calls (`1026`, `1041`) with `if (! BRPeerIsPinned(manager->pinnedAddr, manager->pinnedPort, BRPeerAddress(peer), BRPeerPort(peer))) _penalize(...)`. (A genuinely dead pinned socket still gets reaped by `_peerDisconnected` and re-dialed by Step 6 next `Connect` — that is the intended dark→recover cycle.)

- [ ] **Step 9: Declare the three functions in `BRPeerManager.h`** (near `BRPeerManagerConnectStatus`) and build:

Run: `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug 2>&1 | tail -6`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Re-run all peer KATs (regression)**

Run: `for k in own_node_pin_kat cf_peer_status_kat peer_penalty_kat cf_gate_kat; do native/src/test/host/$k/run.sh; done`
Expected: every KAT `ALL PASSED`.

- [ ] **Step 11: Commit (submodule) + push `johnnylaw` + bump the outer pin (HARD GATE)**

```bash
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
  git add BRPeerManager.c BRPeerManager.h
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
  git commit -m "feat(peers): pinned own-node (reserved dial slot + eviction exempt + exclusive) + CF-served status accessor"
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
  git push johnnylaw HEAD
git add native/src/main/jni/digibytewallet-core
git commit -m "chore(native): bump core pin — pinned own-node + CF-peer-status accessor"
```
**Do not start Task 3 until this pin bump is in place.**

---

## Task 3: JNI wrappers + `NativeBridge` declarations + exclusive prepend suppression

**Files:**
- Modify: `native/src/main/jni/bridge/jni_peer.c`, `core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt` (both outer)

**Interfaces:**
- Consumes: `BRPeerManagerSetPinnedPeer`, `BRPeerManagerClearPinnedPeer`, `BRPeerManagerCompactFilterPeerStatus` (Task 2).
- Produces: `NativeBridge.setPinnedPeer(ip, port, exclusive)`, `.clearPinnedPeer()`, `.compactFilterPeerStatus(ip, port): Int`. Consumed by Tasks 5/6/7.

- [ ] **Step 1: Add the three JNI functions in `jni_peer.c`** (next to `injectPeerByIp`, `430`; IP-literal parse modeled on `addDandelionPeer`, `1062-1078`; no DNS under the lock). Also add a bridge-side exclusive flag for the prepend suppression:

```c
// near the g_priorityPeer globals (jni_peer.c:49-50)
static int g_ownNodeExclusive = 0;   // when set, startSync suppresses the digiscope.me prepend

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_setPinnedPeer(JNIEnv *env, jobject thiz,
                                                        jstring ipStr, jint port, jboolean exclusive) {
    (void)thiz;
    PEER_GUARD();
    g_ownNodeExclusive = exclusive ? 1 : 0;
    if (! g_peerManager) return;
    const char *ip = (*env)->GetStringUTFChars(env, ipStr, NULL);
    struct in_addr ip4;
    if (ip && inet_pton(AF_INET, ip, &ip4) == 1) {
        UInt128 addr = UINT128_ZERO; addr.u16[5] = 0xffff; addr.u32[3] = ip4.s_addr;
        BRPeerManagerSetPinnedPeer(g_peerManager, addr, (uint16_t)port, exclusive ? 1 : 0);
    }
    if (ip) (*env)->ReleaseStringUTFChars(env, ipStr, ip);
}

JNIEXPORT void JNICALL
Java_io_digibyte_core_bridge_NativeBridge_clearPinnedPeer(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    PEER_GUARD();
    g_ownNodeExclusive = 0;
    if (g_peerManager) BRPeerManagerClearPinnedPeer(g_peerManager);
}

JNIEXPORT jint JNICALL
Java_io_digibyte_core_bridge_NativeBridge_compactFilterPeerStatus(JNIEnv *env, jobject thiz,
                                                                  jstring ipStr, jint port) {
    (void)thiz;
    PEER_GUARD();
    if (! g_peerManager) return 0; // BR_CF_PEER_UNKNOWN
    const char *ip = (*env)->GetStringUTFChars(env, ipStr, NULL);
    jint status = 0;
    struct in_addr ip4;
    if (ip && inet_pton(AF_INET, ip, &ip4) == 1) {
        UInt128 addr = UINT128_ZERO; addr.u16[5] = 0xffff; addr.u32[3] = ip4.s_addr;
        status = (jint)BRPeerManagerCompactFilterPeerStatus(g_peerManager, addr, (uint16_t)port);
    }
    if (ip) (*env)->ReleaseStringUTFChars(env, ipStr, ip);
    return status;
}
```
(Confirm the `inet_pton`/`in_addr` includes already present in `jni_peer.c` — they are, per `injectPeerByIp:462`.)

- [ ] **Step 2: Suppress the `digiscope.me` prepend under exclusive mode.** In `startSync` (`Java_..._startSync`, `494`), guard the priority-peer prepend (`_prependSavedPeerAddr(prioAddr, 12024, ...)`, `584`, and the testnet loop `577-582`) with `if (! g_ownNodeExclusive) { ...prepend... }`. In exclusive mode only the pinned own-node (re-injected by Kotlin `injectCustomNode` → `injectPeerByIp` + `setPinnedPeer`) is dialed.

- [ ] **Step 3: Add the `external fun`s in `NativeBridge.kt`** (peer/sync section, near `injectPeerByIp` at `134`):

```kotlin
    external fun setPinnedPeer(ip: String, port: Int, exclusive: Boolean)
    external fun clearPinnedPeer()
    /** 0=UNKNOWN, 1=CONNECTING, 2=CONNECTED_NOT_SERVING, 3=SERVING (BRComputeCFPeerStatus). */
    external fun compactFilterPeerStatus(ip: String, port: Int): Int
```

- [ ] **Step 4: Build native + app**

Run: `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug 2>&1 | tail -6`
Expected: `BUILD SUCCESSFUL` (no `UnsatisfiedLinkError` at build; confirms the pin includes Task 2).

- [ ] **Step 5: Commit**

```bash
git add native/src/main/jni/bridge/jni_peer.c core/src/main/java/io/digibyte/core/bridge/NativeBridge.kt
git commit -m "feat(peers): JNI setPinnedPeer/clearPinnedPeer/compactFilterPeerStatus + exclusive prepend suppression"
```

---

## Task 4: `OwnNodeUri` parser (`dgbnode://…`) + tests

**Files:**
- Create: `core/src/main/java/io/digibyte/core/settings/OwnNodeUri.kt`, `core/src/test/java/io/digibyte/core/settings/OwnNodeUriTest.kt`

**Interfaces:**
- Produces: `data class OwnNodeUri(node: CustomNode, label: String?, net: String?)`; `OwnNodeUri.parse(raw, defaultPort): OwnNodeUri?` (returns null on any malformed/unsupported input; delegates host:port to `CustomNode.parse`). Consumed by Tasks 6/9.

- [ ] **Step 1: Write the failing tests** — `core/src/test/java/io/digibyte/core/settings/OwnNodeUriTest.kt`

```kotlin
package io.digibyte.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnNodeUriTest {
    @Test fun parsesSchemeHostPort() {
        val u = OwnNodeUri.parse("dgbnode://10.0.0.5:12024", 12024)!!
        assertEquals(CustomNode("10.0.0.5", 12024), u.node); assertNull(u.label); assertNull(u.net)
    }
    @Test fun defaultsPortWhenAbsent() {
        assertEquals(CustomNode("node.example.com", 12024), OwnNodeUri.parse("dgbnode://node.example.com", 12024)!!.node)
    }
    @Test fun capturesNetAndLabel() {
        val u = OwnNodeUri.parse("dgbnode://10.0.0.5:12024?net=mainnet&label=My%20Node", 12024)!!
        assertEquals("mainnet", u.net); assertEquals("My Node", u.label)
    }
    @Test fun rawHostPortFallsThroughToCustomNode() {   // non-scheme input still works (manual field)
        assertEquals(CustomNode("10.0.0.5", 12024), OwnNodeUri.parse("10.0.0.5:12024", 12024)!!.node)
    }
    @Test fun rejectsOnionForNow() { assertNull(OwnNodeUri.parse("dgbnode://abcd.onion:12024", 12024)) }
    @Test fun rejectsBadPort() { assertNull(OwnNodeUri.parse("dgbnode://host:70000", 12024)) }
    @Test fun labelSanitizedAndCapped() {
        val u = OwnNodeUri.parse("dgbnode://h:1?label=" + "x".repeat(80), 12024)!!
        assertEquals(32, u.label!!.length)
    }
    @Test fun garbageIsNull() { assertNull(OwnNodeUri.parse("￿ not a uri  ", 12024)) }
    @Test fun blankIsNull() { assertNull(OwnNodeUri.parse("   ", 12024)) }
}
```

- [ ] **Step 2: Run — verify FAIL** (`OwnNodeUri` unresolved)

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.settings.OwnNodeUriTest" 2>&1 | tail -15`

- [ ] **Step 3: Implement** — `core/src/main/java/io/digibyte/core/settings/OwnNodeUri.kt`

```kotlin
package io.digibyte.core.settings

import java.net.URLDecoder

/**
 * A scanned/typed own-node reference. `dgbnode://host[:port][?net=&label=]`, or a raw
 * host[:port] (manual-field fallback). Host:port validation is delegated to CustomNode.parse
 * (IPv4/hostname; no IPv6/onion/URL-scheme-in-host). Returns null on any malformed input.
 */
data class OwnNodeUri(val node: CustomNode, val label: String?, val net: String?) {
    companion object {
        private const val SCHEME = "dgbnode://"
        private const val LABEL_MAX = 32

        fun parse(raw: String, defaultPort: Int): OwnNodeUri? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (! trimmed.startsWith(SCHEME)) {
                // raw host:port fallback — no metadata
                val node = CustomNode.parse(trimmed, defaultPort) ?: return null
                return OwnNodeUri(node, label = null, net = null)
            }
            val body = trimmed.substring(SCHEME.length)
            val qIdx = body.indexOf('?')
            val hostPort = (if (qIdx < 0) body else body.substring(0, qIdx)).trim()
            val query = if (qIdx < 0) "" else body.substring(qIdx + 1)
            if (hostPort.endsWith(".onion")) return null            // onion deferred (Seq 2.5)
            val node = CustomNode.parse(hostPort, defaultPort) ?: return null
            var net: String? = null; var label: String? = null
            for (pair in query.split('&')) {
                val eq = pair.indexOf('='); if (eq < 0) continue
                val k = pair.substring(0, eq); val v = decode(pair.substring(eq + 1))
                when (k) {
                    "net" -> if (v == "mainnet" || v == "testnet") net = v
                    "label" -> label = v.filter { it.isLetterOrDigit() || it.isWhitespace() || it in "-_." }
                        .trim().take(LABEL_MAX).ifEmpty { null }
                }
            }
            return OwnNodeUri(node, label, net)
        }

        private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
    }
}
```
(Note: `CustomNode.parse` already rejects a host containing `:` — an onion or IPv6 with an embedded colon returns null there too; the explicit `.onion` guard is belt-and-suspenders + intent.)

- [ ] **Step 4: Run — verify PASS.** Run the same command as Step 2; all green.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/digibyte/core/settings/OwnNodeUri.kt core/src/test/java/io/digibyte/core/settings/OwnNodeUriTest.kt
git commit -m "feat(settings): OwnNodeUri dgbnode:// parser (+ raw fallback, net/label) + tests"
```

---

## Task 5: `CustomNodePrefs` (label + exclusive) + `injectCustomNode` pin call + immediate-apply action

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/settings/CustomNode.kt`, `app/src/main/java/io/digibyte/service/SyncService.kt`

**Interfaces:**
- Produces: `CustomNodePrefs.{label(ctx), setLabel(ctx,String?), isExclusive(ctx), setExclusive(ctx,Boolean)}`; `SyncService` action `ACTION_APPLY_OWN_NODE` that runs the reconnect triple. Consumed by Tasks 6/7/8.

- [ ] **Step 1: Extend `CustomNodePrefs`** (`CustomNode.kt:51`) with network-suffixed `label` + `exclusive` keys:

```kotlin
    private const val KEY_LABEL = "custom_node_label"
    private const val KEY_EXCLUSIVE = "custom_node_exclusive"

    fun label(ctx: Context): String? = prefs(ctx).getString(key(KEY_LABEL, ctx), null)
    fun setLabel(ctx: Context, label: String?) =
        prefs(ctx).edit().apply { if (label.isNullOrBlank()) remove(key(KEY_LABEL, ctx)) else putString(key(KEY_LABEL, ctx), label.trim()) }.apply()
    fun isExclusive(ctx: Context): Boolean = prefs(ctx).getBoolean(key(KEY_EXCLUSIVE, ctx), false)
    fun setExclusive(ctx: Context, exclusive: Boolean) =
        prefs(ctx).edit().putBoolean(key(KEY_EXCLUSIVE, ctx), exclusive).apply()
```

- [ ] **Step 2: Extend `injectCustomNode()`** (`SyncService.kt:1393`) to pin (and set exclusive) after the existing inject, and clear the pin when the toggle is off. Replace the tail (after the `injectPeerByIp` at `1411`):

```kotlin
        NativeBridge.injectPeerByIp(ip, node.port, 0x41L)   // NODE_NETWORK|NODE_COMPACT_FILTERS
        NativeBridge.setPinnedPeer(ip, node.port, CustomNodePrefs.isExclusive(this@SyncService))
        android.util.Log.i("SyncService",
            "own node injected + pinned as priority CF peer: $ip:${node.port} (${node.host}) exclusive=${CustomNodePrefs.isExclusive(this@SyncService)}")
```
And at the top, when disabled, clear any stale pin (replace the `line 1394` early return):

```kotlin
        if (!CustomNodePrefs.isEnabled(this@SyncService)) { NativeBridge.clearPinnedPeer(); return }
```

- [ ] **Step 3: Add an immediate-apply entry point.** Add an action constant + `onStartCommand` branch that runs the existing keepalive triple (`396-402` shape). Near the other action handling in `onStartCommand`:

```kotlin
        // companion object:
        const val ACTION_APPLY_OWN_NODE = "io.digibyte.service.APPLY_OWN_NODE"
```
```kotlin
        // in onStartCommand, before the normal start path:
        if (intent?.action == ACTION_APPLY_OWN_NODE) {
            serviceScope.launch {
                try { NativeBridge.forceReconnect() } catch (_: Throwable) {}
                injectBloomPeers()
                injectCustomNode()   // re-injects + pins (or clears) with the new prefs
                NativeBridge.startSync()
            } 
            return START_STICKY
        }
```
(Confirm `injectBloomPeers()`/`injectCustomNode()` are reachable from this scope — both are private members; the launch runs on `serviceScope`. Match the existing `onStartCommand` structure exactly — Read it first.)

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleMainnetDebug 2>&1 | tail -6`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/digibyte/core/settings/CustomNode.kt app/src/main/java/io/digibyte/service/SyncService.kt
git commit -m "feat(sync): pin own node on inject + immediate-apply action + label/exclusive prefs"
```

---

## Task 6: `SettingsViewModel` — pairing, label, exclusive, `applyOwnNodeNow`

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/settings/SettingsViewModel.kt`

**Interfaces:**
- Consumes: `CustomNodePrefs`, `OwnNodeUri`, `CustomNode`, `SyncService.ACTION_APPLY_OWN_NODE`, `isTestnet`.
- Produces: `customNodeLabel: StateFlow<String?>`, `customNodeExclusive: StateFlow<Boolean>`; `pairFromUri(raw): PairResult`; `setCustomNodeExclusive(Boolean)`; `applyOwnNodeNow()`. Consumed by Tasks 8/9.

- [ ] **Step 1: Add state + setters** (mirror the `dandelionEnabled` idiom, `107-124`; extend the existing custom-node block `166-189`):

```kotlin
    private val _customNodeLabel = MutableStateFlow(CustomNodePrefs.label(context))
    val customNodeLabel: StateFlow<String?> = _customNodeLabel.asStateFlow()
    private val _customNodeExclusive = MutableStateFlow(CustomNodePrefs.isExclusive(context))
    val customNodeExclusive: StateFlow<Boolean> = _customNodeExclusive.asStateFlow()

    enum class PairResult { OK, INVALID, NET_MISMATCH }

    /** Parse a dgbnode:// (or raw host:port), persist, enable. NET_MISMATCH is a soft warn (still persisted). */
    fun pairFromUri(raw: String): PairResult {
        val defaultPort = if (isTestnet(context)) CustomNode.TESTNET_DEFAULT_PORT else CustomNode.MAINNET_DEFAULT_PORT
        val uri = OwnNodeUri.parse(raw, defaultPort) ?: return PairResult.INVALID
        CustomNodePrefs.setHostPort(context, uri.node.asHostPort())
        CustomNodePrefs.setLabel(context, uri.label)
        CustomNodePrefs.setEnabled(context, true)
        _customNodeHostPort.value = uri.node.asHostPort()
        _customNodeLabel.value = uri.label
        _customNodeEnabled.value = true
        val mismatch = uri.net != null && (uri.net == "testnet") != isTestnet(context)
        return if (mismatch) PairResult.NET_MISMATCH else PairResult.OK
    }

    fun setCustomNodeExclusive(exclusive: Boolean) {
        CustomNodePrefs.setExclusive(context, exclusive)
        _customNodeExclusive.value = exclusive
    }

    /** Apply own-node config immediately (no restart) via the Service reconnect triple. */
    fun applyOwnNodeNow() {
        val i = Intent(context, SyncService::class.java).setAction(SyncService.ACTION_APPLY_OWN_NODE)
        androidx.core.content.ContextCompat.startForegroundService(context, i)
    }
```
(Update `setCustomNodeEnabled` to also `applyOwnNodeNow()`-or-clear as appropriate, and drop the "restart-to-apply" comment at `176-178`. Imports: `android.content.Intent`.)

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileMainnetDebugKotlin 2>&1 | tail -6`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/digibyte/ui/settings/SettingsViewModel.kt
git commit -m "feat(settings): own-node pairFromUri + exclusive + immediate applyOwnNodeNow"
```

---

## Task 7: Own-node health flow (`SyncService.ownNodeHealth`) + poll

**Files:**
- Modify: `app/src/main/java/io/digibyte/service/SyncService.kt`

**Interfaces:**
- Produces: `SyncService.ownNodeHealth: StateFlow<OwnNodeHealth>` (companion) where `enum OwnNodeHealth { UNPAIRED, CONNECTING, SERVING, DARK }`. Consumed by Tasks 8/10.

- [ ] **Step 1: Add the companion StateFlow** (mirror `bloomFallbackActive`, `1781-1784`):

```kotlin
        enum class OwnNodeHealth { UNPAIRED, CONNECTING, SERVING, DARK }
        private val _ownNodeHealth = MutableStateFlow(OwnNodeHealth.UNPAIRED)
        val ownNodeHealth: StateFlow<OwnNodeHealth> = _ownNodeHealth.asStateFlow()
```

- [ ] **Step 2: Poll the native status** on the existing peer/status cadence (reuse the loop that already runs while synced — Read the current status/keepalive loop and add, guarded by the toggle):

```kotlin
    private fun refreshOwnNodeHealth() {
        if (!CustomNodePrefs.isEnabled(this)) { _ownNodeHealth.value = OwnNodeHealth.UNPAIRED; return }
        val raw = CustomNodePrefs.hostPort(this) ?: return
        val defaultPort = if (isTestnet(this)) CustomNode.TESTNET_DEFAULT_PORT else CustomNode.MAINNET_DEFAULT_PORT
        val node = CustomNode.parse(raw, defaultPort) ?: return
        // Resolve on IO; status accessor takes an IPv4 literal.
        serviceScope.launch(Dispatchers.IO) {
            val ip = try { java.net.InetAddress.getAllByName(node.host).firstOrNull { it is java.net.Inet4Address }?.hostAddress }
                     catch (_: Exception) { null } ?: return@launch
            _ownNodeHealth.value = when (NativeBridge.compactFilterPeerStatus(ip, node.port)) {
                3 -> OwnNodeHealth.SERVING              // connected + answered cfheaders
                1 -> OwnNodeHealth.CONNECTING           // in pool, socket not yet up
                else -> OwnNodeHealth.DARK              // 0 UNKNOWN (not in pool) OR 2 CONNECTED_NOT_SERVING
                // 2 → DARK is deliberate: a node running WITHOUT peerblockfilters=1 connects but never
                // serves filters — surfacing that as ⚠ is exactly the misconfiguration we want to catch.
            }
        }
    }
```
Call `refreshOwnNodeHealth()` from the same periodic tick that updates peer/sync status (do not add a new timer if one exists). **Read the current status loop and hook in; do not poll faster than ~every few seconds.**

- [ ] **Step 3: Build + commit**

Run: `./gradlew :app:assembleMainnetDebug 2>&1 | tail -6` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/java/io/digibyte/service/SyncService.kt
git commit -m "feat(sync): ownNodeHealth StateFlow from compactFilterPeerStatus poll"
```

---

## Task 8: NetworkInfoScreen — QR pair button, verification readout, exclusive toggle, label

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/settings/NetworkInfoScreen.kt`

**Interfaces:**
- Consumes: `SettingsViewModel.{customNodeEnabled, customNodeHostPort, customNodeLabel, customNodeExclusive, setCustomNodeEnabled, setCustomNodeExclusive, applyOwnNodeNow, saveCustomNodeHostPort}`, `SyncService.ownNodeHealth`, an `onScanNode: () -> Unit` nav callback (added in Task 9).

- [ ] **Step 1: Collect the new state** near the existing collects (`40-47`):

```kotlin
    val customNodeLabel by viewModel.customNodeLabel.collectAsStateWithLifecycle()
    val customNodeExclusive by viewModel.customNodeExclusive.collectAsStateWithLifecycle()
    val ownNodeHealth by io.digibyte.service.SyncService.ownNodeHealth.collectAsStateWithLifecycle()
```

- [ ] **Step 2: Rework the "Own node" `item {}` block** (`269-316`). Replace the "Applies on next app restart" copy; add a **Scan QR** button, the **health readout**, and an **exclusive** switch. Keep the manual field + Save (Save now also calls `applyOwnNodeNow()`), and the toggle now calls `applyOwnNodeNow()` instead of promising a restart. Health readout maps `ownNodeHealth`:

```kotlin
                    // health line (replaces the old "restart the app" text)
                    val (healthText, healthColor) = when (ownNodeHealth) {
                        SyncService.OwnNodeHealth.SERVING     -> "✓ Serving compact filters" to DigiByteGreen
                        SyncService.OwnNodeHealth.CONNECTING  -> "Connecting…" to MaterialTheme.colorScheme.onSurfaceVariant
                        SyncService.OwnNodeHealth.DARK        -> "⚠ Not reachable / not serving filters" to Color(0xFFFFCC66)
                        SyncService.OwnNodeHealth.UNPAIRED    -> "" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    if (customNodeEnabled && healthText.isNotEmpty())
                        Text(healthText + (customNodeLabel?.let { "  ·  $it" } ?: ""),
                             color = healthColor, style = MaterialTheme.typography.bodySmall)
```
Add a `TextButton(onClick = onScanNode) { Icon(Icons.Filled.QrCodeScanner, null); Text("Scan node QR") }` above the manual field, and an exclusive `SettingsRow`/switch (visible when `customNodeEnabled`):

```kotlin
                        SettingsRowDivider()
                        SettingsRow(
                            icon = Icons.Filled.Shield, iconTint = DigiByteAccent,
                            title = "Only my node (exclusive)",
                            subtitle = "Sync solely through your node. If it goes offline the wallet has no other peers until you re-enable public peers.",
                            onClick = { viewModel.setCustomNodeExclusive(!customNodeExclusive); viewModel.applyOwnNodeNow() },
                            trailing = { Switch(checked = customNodeExclusive,
                                onCheckedChange = { viewModel.setCustomNodeExclusive(it); viewModel.applyOwnNodeNow() }) }
                        )
```
(Imports: `Icons.Filled.QrCodeScanner`, `Icons.Filled.Shield`, `DigiByteGreen`. Thread `onScanNode` through the `NetworkInfoScreen(...)` signature — added in Task 9. Update the subtitle at `276-277` to drop "Applies on next app restart".)

- [ ] **Step 3: Build + install; visual check**

Run: `./gradlew :app:assembleMainnetDebug 2>&1 | tail -6` → `BUILD SUCCESSFUL`. Manual: toggle shows Scan button + exclusive switch + a live health line; saving a node applies without a restart prompt.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/digibyte/ui/settings/NetworkInfoScreen.kt
git commit -m "feat(settings): NetworkInfo own-node QR/verify/exclusive UI + live health"
```

---

## Task 9: QR pairing route + `NodePairConfirmScreen`

**Files:**
- Create: `app/src/main/java/io/digibyte/ui/settings/NodePairConfirmScreen.kt`
- Modify: `app/src/main/java/io/digibyte/ui/components/QrScannerScreen.kt`, `app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt`

**Interfaces:**
- Consumes: `OwnNodeUri`, `SettingsViewModel.pairFromUri`, `applyOwnNodeNow`.

- [ ] **Step 1: Add a `dgbnode://` branch to `dispatchScanResult`** (`QrScannerScreen.kt:318`): add an `onNode: (String) -> Unit` param and, in the `when`, `trimmed.startsWith("dgbnode://") -> onNode(trimmed)` (before the raw-address fallback).

- [ ] **Step 2: Wire the scan route** in `AppNavigation.kt` (`composable("qr_scanner")`, `545-567`): pass `onNode = { raw -> navController.navigate("node_pair_confirm/${Uri.encode(raw)}") { popUpTo("qr_scanner") { inclusive = true } } }` (mirror the `digiid_confirm/{uri}` handler at `570-584`). Add the route constant and a `node_pair_confirm/{uri}` `composable` that decodes the arg and shows `NodePairConfirmScreen`. Add an `onScanNode = { navController.navigate("qr_scanner") }` param to the `NetworkInfoScreen(...)` call site.

- [ ] **Step 3: Create `NodePairConfirmScreen`** — parse, show the node + label + net-mismatch warning, confirm/cancel:

```kotlin
package io.digibyte.ui.settings

import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import io.digibyte.core.settings.OwnNodeUri
// … standard Compose scaffold imports …

@Composable
fun NodePairConfirmScreen(rawUri: String, onDone: () -> Unit, onCancel: () -> Unit,
                          viewModel: SettingsViewModel = hiltViewModel()) {
    // Show the parsed host:port + label; a net-mismatch caution; Pair / Cancel.
    // Pair → viewModel.pairFromUri(rawUri) → (OK|NET_MISMATCH → persist + applyOwnNodeNow() → onDone;
    //        INVALID → inline error, stay). NET_MISMATCH shows a confirm-again caption but still allows pairing.
    // (Implement the scaffold following an existing settings confirm screen, e.g. ReconcileScreen.kt layout.)
}
```
(Full layout to match the app's existing confirm-screen style — Read `ReconcileScreen.kt` / `DigiIdConfirmScreen` for the scaffold, top bar, and button styling; keep the logic exactly as the comment specifies.)

- [ ] **Step 4: Build + install; smoke** — scan a `dgbnode://` QR (generate one with `qrencode`) → confirm screen shows host/label → Pair → returns to a synced wallet with the node pairing applied.

Run: `./gradlew :app:assembleMainnetDebug 2>&1 | tail -6` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/digibyte/ui/settings/NodePairConfirmScreen.kt app/src/main/java/io/digibyte/ui/components/QrScannerScreen.kt app/src/main/java/io/digibyte/ui/navigation/AppNavigation.kt
git commit -m "feat(settings): QR node pairing route + NodePairConfirmScreen"
```

---

## Task 10: Main-screen health chip + dark-node banner + escape hatch

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/wallet/WalletScreen.kt`, `app/src/main/java/io/digibyte/ui/wallet/WalletViewModel.kt`

**Interfaces:**
- Consumes: `SyncService.ownNodeHealth`. Produces: `WalletViewModel.ownNodeHealth` (re-exposed, mirror `bloomFallbackActive` at `262`); `WalletViewModel.usedPublicPeersEscape()` (session escape hatch).

- [ ] **Step 1: Re-expose the flow** in `WalletViewModel.kt` (mirror `262/268`):

```kotlin
    val ownNodeHealth: StateFlow<io.digibyte.service.SyncService.OwnNodeHealth> =
        io.digibyte.service.SyncService.ownNodeHealth
```

- [ ] **Step 2: Add the escape hatch** — a session-only flip to additive that keeps the node pinned but non-exclusive, WITHOUT writing prefs (so next launch still honors the user's persisted exclusive choice). Add a Service action (keeps native calls off the VM, mirroring Task 5's `ACTION_APPLY_OWN_NODE`):

In `SyncService` (companion + `onStartCommand`):
```kotlin
        const val ACTION_OWN_NODE_ADDITIVE_SESSION = "io.digibyte.service.OWN_NODE_ADDITIVE_SESSION"
```
```kotlin
        if (intent?.action == ACTION_OWN_NODE_ADDITIVE_SESSION) {
            serviceScope.launch {
                // Re-pin the same node but non-exclusive for THIS session; prefs untouched.
                val raw = CustomNodePrefs.hostPort(this@SyncService)
                val defaultPort = if (isTestnet(this@SyncService)) CustomNode.TESTNET_DEFAULT_PORT else CustomNode.MAINNET_DEFAULT_PORT
                val node = raw?.let { CustomNode.parse(it, defaultPort) }
                val ip = node?.let { withContext(Dispatchers.IO) {
                    try { java.net.InetAddress.getAllByName(it.host).firstOrNull { a -> a is java.net.Inet4Address }?.hostAddress } catch (_: Exception) { null } } }
                try { NativeBridge.forceReconnect() } catch (_: Throwable) {}
                injectBloomPeers()
                if (ip != null) { NativeBridge.injectPeerByIp(ip, node.port, 0x41L); NativeBridge.setPinnedPeer(ip, node.port, false) }
                NativeBridge.startSync()
            }
            return START_STICKY
        }
```
In `WalletViewModel` (fires the action — mirror however the VM already starts `SyncService`; it has `@ApplicationContext` for its poll loop):
```kotlin
    /** Session escape: temporarily use public peers (exclusive OFF this run; persisted pref unchanged). */
    fun temporarilyUsePublicPeers() {
        val i = Intent(context, io.digibyte.service.SyncService::class.java)
            .setAction(io.digibyte.service.SyncService.ACTION_OWN_NODE_ADDITIVE_SESSION)
        androidx.core.content.ContextCompat.startForegroundService(context, i)
    }
```
(Confirm `WalletViewModel` has an injected `context`; if it instead holds a `WalletManager`/service handle, route the intent through that. Read the VM's existing service-start call before wiring.)

- [ ] **Step 3: Render chip + banner** in `WalletScreen.kt` (mirror the banner pattern at `147-174`):

```kotlin
    val ownNodeHealth by viewModel.ownNodeHealth.collectAsStateWithLifecycle()
    // …
    if (ownNodeHealth == SyncService.OwnNodeHealth.DARK) {
        item { OwnNodeDarkBanner(onUsePublicPeers = { viewModel.temporarilyUsePublicPeers() },
                                 onOpenSettings = onNavigateNetworkInfo) }
    }
```
Add a private `OwnNodeDarkBanner(...)` modeled on `ReconcileFailedBanner` (`700`) — amber card, "Your node is offline", a **"Use public peers"** action (shown only when exclusive; drives the escape hatch) + a "Node settings" action. Add a compact **health chip** near `SyncProgressCard` (`304`) showing ✓ when `SERVING` (small, non-intrusive; only when a node is paired).

- [ ] **Step 4: Build + install; verify** — dark the node (stop it / point at a bad host) → banner appears; in exclusive mode the "Use public peers" action restores sync; re-serving flips the chip back to ✓.

Run: `./gradlew :app:assembleMainnetDebug 2>&1 | tail -6` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/digibyte/ui/wallet/WalletScreen.kt app/src/main/java/io/digibyte/ui/wallet/WalletViewModel.kt
git commit -m "feat(wallet): own-node health chip + dark-node banner + public-peer escape hatch"
```

---

## Task 11: Docs + ROADMAP + verification checklist

**Files:**
- Modify: `ROADMAP.md`; add a "Pair your own node" note to the wallet docs.

- [ ] **Step 1: Check ROADMAP 1.1 + 1.2** (Sequence 1) — mark the pairing-flow + pinned-node-behavior items delivered; leave 1.3–1.6 open. Keep the header/appendix accurate (Step 0's standing rule).
- [ ] **Step 2: Add a short "Pair your own node" doc** — the `qrencode "dgbnode://$HOST:$PORT?net=mainnet&label=$(hostname)"` one-liner + the `peerblockfilters=1` requirement + the exclusive-mode caveat.
- [ ] **Step 3: Commit**

```bash
git add ROADMAP.md docs/
git commit -m "docs: own-node pairing — mark ROADMAP 1.1/1.2 + pair-your-node guide"
```

---

## Manual verification checklist (before merge)

- [ ] All host KATs `ALL PASSED`: `for k in own_node_pin_kat cf_peer_status_kat peer_penalty_kat cf_gate_kat; do native/src/test/host/$k/run.sh; done`.
- [ ] `./gradlew :core:testMainnetDebugUnitTest --tests "*.OwnNodeUriTest" --tests "*.CustomNodeTest"` green.
- [ ] `./gradlew :app:assembleMainnetDebug :app:assembleDigiTestnetDebug` both `BUILD SUCCESSFUL`.
- [ ] Security suite green: `./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*"` (house rule 4).
- [ ] **Core pin includes the Task-2 commit** (else `compactFilterPeerStatus`/`setPinnedPeer` `UnsatisfiedLinkError`). Submodule pushed to `johnnylaw`.
- [ ] **On-device (mainnet, own node with `peerblockfilters=1`):** QR pair → health reaches ✓ SERVING **without an app restart**; verify-warn on a bad/down node (paired but ⚠, never blocked); pinned node survives churn (not idle-evicted, re-dialed after a drop); exclusive → `/proc/net/tcp` shows only the node's connection, no `digiscope.me`; kill the node → dark banner + "Use public peers" restores sync; toggle off → `clearPinnedPeer` and normal public sync resumes.
- [ ] **Regression:** a wallet with the toggle OFF is completely unaffected (public CF sync unchanged; no pinned peer); CF-only invariant intact (no `filterload` ever on the wire).
- [ ] Full pre-publish suite (API 28/33/34/35) before the release tag.
- [ ] ROADMAP + docs updated in this PR (house rule 5).

## Out of scope (explicit follow-ups)

- Tor **onion** pairing (Seq 2.5, with loud Tor fallback). The `dgbnode://` grammar reserves an onion host; the parser rejects it today.
- **Oracle-bootstrap** peer diversity + gossip-retention mainnet generalization (Seq 1.3–1.4).
- **`cfcheckpt` enforcement** (Seq 1.5).
- **Multi-node** pairing / failover among several own nodes.
- **Model A** wallet↔node JSON-RPC backend (`UtxoSource` seam).
