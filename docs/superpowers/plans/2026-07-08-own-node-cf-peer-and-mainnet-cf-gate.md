# Own-Node CF Peer + Mainnet CF-Gate (accept) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **This plan was adversarially reviewed (2026-07-08, verdict READY_WITH_FIXES) and all 14 findings are folded in.** Notable: the mainnet **gossip-retention** generalization was descoped to the oracle-bootstrap work (this feature injects the node, so only the *accept gate* is needed); the 120s bloom watchdog must be suppressed under the toggle; DNS is resolved in Kotlin (off the native lock).

**Goal:** Let a user point the wallet at their own DigiByte node as a priority compact-filter (BIP157/158) peer, and accept compact-filter-only nodes at connect on mainnet — the first buildable increment of the bloom-deprecation plan (Model C, Track A tier 1).

**Architecture:** One C-core change (generalize the mainnet *accept gate* that today requires `NODE_BLOOM` so it accepts a `NODE_COMPACT_FILTERS` peer whenever `syncMode != BLOOM_ONLY`, via a single testable predicate), reusing the existing `injectPeerByIp` path (with DNS resolved in Kotlin) to pin the user's node as a priority CF peer, plus Settings UI + persistence. When the own-node toggle is on, the wallet runs compact-filters-only, the 120s bloom-fallback watchdog is suppressed (no bloom leak), the user's node is prioritized, and public CF peers remain as fallback.

**Tech Stack:** C (breadwallet-fork core), JNI, Kotlin, Jetpack Compose, Hilt, JUnit/MockK, host KAT (clang).

**Design spec:** `docs/superpowers/specs/2026-07-08-bloom-deprecation-bip158-only.md` (Model C decided). Sibling: `docs/superpowers/specs/2026-07-08-oracle-bootstrap-peer-discovery.md` (the gossip-retention half of the gate work + Sybil hardening lands there).

## Global Constraints

- **Accept-gate generalization is sync-mode-gated, NOT network-gated.** The predicate is exactly: `(services & NODE_BLOOM) OR (syncMode != BR_SYNC_MODE_BLOOM_ONLY AND (services & NODE_COMPACT_FILTERS))`. Do **not** drop the existing `NODE_NETWORK` and `version >= 70011` checks in the accept gate.
- **Gossip retention is OUT OF SCOPE here.** Do **not** change `_peerRelayedPeers` (`BRPeerManager.c:1086-1096`) in this increment — the user's node is *injected*, not gossip-learned, so only the connect accept gate matters. Generalizing retention changes the peer pool for *all* default-BOTH mainnet wallets and opens a spoofable-`0x40` Sybil surface; it lands with the oracle-bootstrap implementation (same predicate helper, plus un-evictable/reserved-slot hardening).
- **Service bit values (verbatim):** `SERVICES_NODE_NETWORK 0x01`, `SERVICES_NODE_BLOOM 0x04`, `SERVICES_NODE_COMPACT_FILTERS 0x40` (`BRPeer.h:74-77`). Sync modes: `BR_SYNC_MODE_BLOOM_ONLY=0`, `BR_SYNC_MODE_COMPACT_FILTERS_ONLY=1`, `BR_SYNC_MODE_BOTH=2` (`BRPeerManager.h:92-95`); Kotlin mirror `NativeBridge.SyncMode.{BLOOM_ONLY=0,COMPACT_FILTERS_ONLY=1,BOTH=2}` (`NativeBridge.kt:399-403`). Default P2P ports: mainnet **12024**, testnet **12033**.
- **Resolve the node's hostname in Kotlin on `Dispatchers.IO` and inject an IPv4 literal.** Do NOT pass a hostname to `injectPeerByIp` — it resolves DNS *while holding the native peer lock* (`jni_peer.c` PEER_GUARD → main-thread ANR), and its live-manager re-add path uses `inet_pton`, which silently no-ops for a hostname.
- **Inject the resolved node with services `0x41L`** (`NODE_NETWORK|NODE_COMPACT_FILTERS`) — never `0`, which defaults to bloom-only (`jni_peer.c:301` `INJECT_DEFAULT_SERVICES`).
- **Reuse `NativeBridge.injectPeerByIp(host, port, servicesHex)`** (`NativeBridge.kt:129`; JNI `jni_peer.c:430-471`) — passing an IPv4 literal. Do **NOT** use `setFixedPeer` — it "disconnects everything and clears the peer array" (`jni_peer.c:102`) and targets the *legacy* `io.digibyte.wallet.BRPeerManager` surface, not the active `g_peerManager`.
- **Re-inject the node on every sync start** (`SyncService` injection sites), not from persisted `saved_peers` — the priority peer is re-prepended fresh each `startSync` and `forceReconnect` recreates the manager.
- **Own-node toggle ON → force `COMPACT_FILTERS_ONLY` AND suppress the 120s bloom-fallback watchdog.** Without the watchdog suppression, a CF-path stall would `fallbackToBloom()` → `setSyncMode(BLOOM_ONLY)` → put a bloom `filterload` (the address set) on the wire, defeating the whole point. Suppress it exactly like the existing testnet guard.
- **Persist custom-node settings in `dgb_settings` with network-suffixed KEYS** via the existing `networkSuffix(context)` helper (the `dgb_settings` file itself is not network-suffixed).
- **Setting is restart-to-apply** (matches `SyncModeScreen.kt:52`). UI copy must say "applies on next app restart", not "on reconnect" — a live reconnect/pull-to-refresh does NOT re-run the latched setup coroutine.
- **HARD ORDERING / PIN GATE:** Tasks 5-7 (Kotlin/UI) must NOT merge until Task 2's submodule commit is pushed to the `johnnylaw` fork **and** the outer-repo submodule pin is bumped to include it. If the app builds against a core pin lacking Task 2, the user's CF-only node is silently disconnected on mainnet (`"node doesn't support SPV mode"`) while `digiscope.me` keeps syncing — the feature appears to work but does nothing. Add a CI assertion that the core pin is at/after the Task-2 commit; the detectable runtime signal is a `"node doesn't support SPV mode"` log on the configured node's IP.
- **Submodule commits** (anything under `native/src/main/jni/digibytewallet-core/`) use the `GIT_DIR`/`GIT_WORK_TREE` pattern and push to `johnnylaw` before bumping the pin (memory `reference_submodule_commit_push_pattern`). Use `--ignore-submodules=all` on outer-repo git status/diff.
- **IPv6 unsupported** end-to-end (`BRPeer.c:400` drops IPv6); host input is an IPv4 literal or hostname (A-record) only.
- **The accept-gate change touches bloom-path invariants** — the full pre-publish suite (API 28/33/34/35) must pass and must not regress `BLOOM_ONLY` wallets before release.

## File Structure

**Create:**
- `native/src/main/jni/digibytewallet-core/BRPeerServices.h` — the single-source SPV-usability predicate (submodule).
- `native/src/test/host/cf_gate_kat/cf_gate_kat_main.c` + `run.sh` — host KAT for the predicate.
- `core/src/main/java/io/digibyte/core/settings/CustomNode.kt` — pure parse/validate model + `syncModeFor(...)` + thin `CustomNodePrefs` Android wrapper.
- `core/src/test/java/io/digibyte/core/settings/CustomNodeTest.kt` — JVM unit tests.

**Modify:**
- `native/src/main/jni/digibytewallet-core/BRPeerManager.c` — include the header; wire the accept gate (`905-925`) to the predicate (submodule). **Retention `1086-1096` untouched.**
- `native/CMakeLists.txt` — list the new header (convention/IDE indexing).
- `app/src/main/java/io/digibyte/ui/settings/SettingsViewModel.kt` — custom-node state + setters.
- `app/src/main/java/io/digibyte/ui/settings/NetworkInfoScreen.kt` — toggle + host:port field + coarse status.
- `app/src/main/java/io/digibyte/service/SyncService.kt` — `injectCustomNode()`, call at injection sites, use `syncModeFor(...)` from the `dgb_settings` handle, and suppress the bloom watchdog under the toggle.

---

## Task 1: C-core SPV-usability predicate + host KAT

**Files:**
- Create: `native/src/main/jni/digibytewallet-core/BRPeerServices.h`
- Create: `native/src/test/host/cf_gate_kat/cf_gate_kat_main.c`, `native/src/test/host/cf_gate_kat/run.sh`
- Modify: `native/CMakeLists.txt`

**Interfaces:**
- Produces: `static inline int BRPeerServicesAllowedForSyncMode(uint64_t services, int syncMode)` — returns 1 if the service set is usable for that sync mode, else 0. Consumed by Task 2.

- [ ] **Step 1: Write the failing KAT** — `native/src/test/host/cf_gate_kat/cf_gate_kat_main.c`

```c
// Host KAT for BRPeerServicesAllowedForSyncMode — the sync-mode-gated
// generalization of the former testnet-only compact-filter accept exception.
#include <stdio.h>
#include <stdint.h>
#include "BRPeer.h"
#include "BRPeerManager.h"
#include "BRPeerServices.h"

static int g_failures = 0;
static void check(int cond, const char *desc) {
    printf("%s: %s\n", cond ? "PASS" : "FAIL", desc);
    if (! cond) g_failures++;
}

int main(void) {
    const uint64_t BLOOM   = SERVICES_NODE_NETWORK | SERVICES_NODE_BLOOM;             // 0x05
    const uint64_t CFONLY  = SERVICES_NODE_NETWORK | SERVICES_NODE_COMPACT_FILTERS;   // 0x41
    const uint64_t BOTHSVC = SERVICES_NODE_NETWORK | SERVICES_NODE_BLOOM | SERVICES_NODE_COMPACT_FILTERS; // 0x45

    // Bloom peers are always usable, in every mode (bloom-only path unchanged).
    check(BRPeerServicesAllowedForSyncMode(BLOOM, BR_SYNC_MODE_BLOOM_ONLY) == 1, "bloom usable in BLOOM_ONLY");
    check(BRPeerServicesAllowedForSyncMode(BLOOM, BR_SYNC_MODE_BOTH) == 1, "bloom usable in BOTH");

    // CF-only peers: usable in CF and BOTH, NOT in BLOOM_ONLY. This is the change.
    check(BRPeerServicesAllowedForSyncMode(CFONLY, BR_SYNC_MODE_COMPACT_FILTERS_ONLY) == 1, "cf-only usable in COMPACT_FILTERS_ONLY");
    check(BRPeerServicesAllowedForSyncMode(CFONLY, BR_SYNC_MODE_BOTH) == 1, "cf-only usable in BOTH (mainnet accepts now)");
    check(BRPeerServicesAllowedForSyncMode(CFONLY, BR_SYNC_MODE_BLOOM_ONLY) == 0, "cf-only NOT usable in BLOOM_ONLY");

    // Dual-capable peers usable everywhere.
    check(BRPeerServicesAllowedForSyncMode(BOTHSVC, BR_SYNC_MODE_BLOOM_ONLY) == 1, "bloom+cf usable in BLOOM_ONLY");

    // A peer advertising neither bloom nor CF is never usable.
    check(BRPeerServicesAllowedForSyncMode(SERVICES_NODE_NETWORK, BR_SYNC_MODE_BOTH) == 0, "network-only NOT usable");

    printf(g_failures ? "\n%d FAILURE(S)\n" : "\nALL PASSED\n", g_failures);
    return g_failures ? 1 : 0;
}
```

- [ ] **Step 2: Write `run.sh`** — model on `native/src/test/host/network_switch_kat/run.sh` (verify the `../` count for `REPO_ROOT` matches this dir depth `native/src/test/host/cf_gate_kat/` — 5 levels up)

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
CORE_DIR="$REPO_ROOT/native/src/main/jni/digibytewallet-core"
BUILD_DIR="$(mktemp -d)"; trap 'rm -rf "$BUILD_DIR"' EXIT
clang -w -include stdint.h -I "$CORE_DIR" \
  "$SCRIPT_DIR/cf_gate_kat_main.c" \
  -o "$BUILD_DIR/cf_gate_kat"
"$BUILD_DIR/cf_gate_kat"
```

- [ ] **Step 3: Run the KAT — verify it FAILS**

Run: `chmod +x native/src/test/host/cf_gate_kat/run.sh && native/src/test/host/cf_gate_kat/run.sh`
Expected: FAIL — `fatal error: 'BRPeerServices.h' file not found`.
(If instead it fails because `BRPeerManager.h` pulls a heavy/unresolved include when preprocessed standalone, add the minimal missing include/`-D` to `run.sh` — do not weaken the predicate. As a fallback, the predicate can be validated by including only `BRPeer.h` and passing the sync-mode int with a local `enum` shadow; prefer the real header.)

- [ ] **Step 4: Create the predicate header** — `native/src/main/jni/digibytewallet-core/BRPeerServices.h`

```c
#ifndef BRPeerServices_h
#define BRPeerServices_h

#include <stdint.h>
#include "BRPeer.h"         // SERVICES_NODE_BLOOM / _NETWORK / _COMPACT_FILTERS
#include "BRPeerManager.h"  // BRSyncMode (BR_SYNC_MODE_BLOOM_ONLY)

// Is a peer's advertised service set usable for the current sync mode?
// A peer is usable if it serves bloom, OR — when the wallet is running
// BIP157/158 (any mode other than BLOOM_ONLY) — if it serves compact filters.
//
// This is the sync-mode-gated generalization of the former testnet-only
// compact-filter exception at the connect accept gate. It lets compact-filter-only
// nodes — modern DigiByte Core ships bloom OFF by default — be accepted on mainnet
// whenever the wallet is not in the legacy bloom-only mode.
static inline int BRPeerServicesAllowedForSyncMode(uint64_t services, int syncMode)
{
    if ((services & SERVICES_NODE_BLOOM) == SERVICES_NODE_BLOOM) return 1;
    if (syncMode != BR_SYNC_MODE_BLOOM_ONLY &&
        (services & SERVICES_NODE_COMPACT_FILTERS) == SERVICES_NODE_COMPACT_FILTERS) return 1;
    return 0;
}

#endif // BRPeerServices_h
```

- [ ] **Step 5: Run the KAT — verify it PASSES**

Run: `native/src/test/host/cf_gate_kat/run.sh`
Expected: 7× `PASS`, then `ALL PASSED`, exit 0.

- [ ] **Step 6: Add the header to `native/CMakeLists.txt`** alongside the other explicitly-listed core headers (the build already works via same-dir include; this is for convention + IDE indexing). Find the block listing `digibytewallet-core/*.h` and add `src/main/jni/digibytewallet-core/BRPeerServices.h`.

- [ ] **Step 7: Commit** (submodule for the header; outer repo for the KAT + CMake)

```bash
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git add BRPeerServices.h && GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git commit -m "feat(peers): sync-mode-gated SPV-usability predicate (BRPeerServices.h)"
git add native/src/test/host/cf_gate_kat native/CMakeLists.txt
git commit -m "test(peers): host KAT for BRPeerServicesAllowedForSyncMode"
```

---

## Task 2: Wire the mainnet ACCEPT GATE to the predicate

**Files:**
- Modify: `native/src/main/jni/digibytewallet-core/BRPeerManager.c` (accept gate `905-925`; add include). **Do NOT touch retention `1086-1096`.**

**Interfaces:**
- Consumes: `BRPeerServicesAllowedForSyncMode(uint64_t, int)` (Task 1).

- [ ] **Step 1: Add the include** near the other core includes at the top of `BRPeerManager.c`

```c
#include "BRPeerServices.h"
```

- [ ] **Step 2: Replace the accept-gate SPV check** — `BRPeerManager.c:917-925`. **The real block contains a 4-line interior comment (`918-921`) — re-Read the exact current lines before editing; do not rely on an elided paste.** The current block is (comment lines abbreviated here with `…`):

```c
    else if (BRPeerVersion(peer) >= 70011 && (peer->services & SERVICES_NODE_BLOOM) != SERVICES_NODE_BLOOM &&
             // Testnet26 nodes serve BIP157/158 compact filters but NOT bloom (bloom is
             // deprecated/off by default on modern Core). Accept a compact-filter-capable
             // peer as SPV-usable on testnet so the block-filter path can sync; mainnet
             // is unchanged (still requires NODE_BLOOM for its bloom peers).
             !(BRNetworkIsTestnet() && (peer->services & SERVICES_NODE_COMPACT_FILTERS) == SERVICES_NODE_COMPACT_FILTERS)) {
        peer_log(peer, "node doesn't support SPV mode");
        BRPeerDisconnect(peer);
    }
```

Replace the whole `else if (…) { … }` with:

```c
    else if (BRPeerVersion(peer) >= 70011 &&
             ! BRPeerServicesAllowedForSyncMode(peer->services, manager->syncMode)) {
        // Bloom OR (compact filters while not in bloom-only mode). Generalizes the
        // former testnet-only compact-filter exception to mainnet so CF-only nodes
        // (bloom off by default on modern Core) are SPV-usable on the filter path.
        // NOTE: this also means testnet no longer keeps CF peers under BLOOM_ONLY —
        // intentional and unreachable, since the app always forces testnet to
        // COMPACT_FILTERS_ONLY. Gossip retention (BRPeerManager.c:1086-1096) is left
        // testnet-only here; its mainnet generalization ships with oracle-bootstrap.
        peer_log(peer, "node doesn't support SPV mode");
        BRPeerDisconnect(peer);
    }
```

- [ ] **Step 3: Build the native module + app to verify it compiles**

Run: `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Re-run the Task-1 KAT and the network KAT (regression guard)**

Run: `native/src/test/host/cf_gate_kat/run.sh && native/src/test/host/network_switch_kat/run.sh`
Expected: both `ALL PASSED`.

- [ ] **Step 5: Commit (submodule) + bump the outer pin (HARD GATE for Tasks 5-7)**

```bash
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git add BRPeerManager.c && GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git commit -m "feat(peers): accept compact-filter-only peers at connect on mainnet when syncMode != BLOOM_ONLY

Generalizes the testnet-only CF accept exception via BRPeerServicesAllowedForSyncMode.
Bloom-only wallets unchanged. Gossip retention untouched (ships with oracle-bootstrap)."
# push submodule to johnnylaw fork, then bump the pin in the outer repo:
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git push johnnylaw HEAD
git add native/src/main/jni/digibytewallet-core   # records new submodule SHA
git commit -m "chore(native): bump core pin — mainnet CF accept-gate generalization"
```
**Do not start Tasks 5-7 until this pin bump is in place** (see Global Constraints → HARD ORDERING). Note in BOTH mode this means a real seeder-injected CF-only mainnet peer is now accepted rather than dropped — strictly more CF availability, aligned with oracle-bootstrap; no Sybil surface is added because gossip retention is unchanged.

---

## Task 3: `CustomNode` parse/validate model + `syncModeFor`

**Files:**
- Create: `core/src/main/java/io/digibyte/core/settings/CustomNode.kt`
- Create: `core/src/test/java/io/digibyte/core/settings/CustomNodeTest.kt`

**Interfaces:**
- Produces: `data class CustomNode(host, port)`; `CustomNode.parse(raw, defaultPort): CustomNode?`; `CustomNode.asHostPort(): String`; `syncModeFor(pref, customNodeEnabled, isTestnet): Int`; consts `MAINNET_DEFAULT_PORT=12024`, `TESTNET_DEFAULT_PORT=12033`. Consumed by Tasks 4/5/7.

- [ ] **Step 1: Write the failing tests** — `core/src/test/java/io/digibyte/core/settings/CustomNodeTest.kt`

```kotlin
package io.digibyte.core.settings

import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomNodeTest {
    @Test fun bareHostGetsDefaultPort() {
        assertEquals(CustomNode("node.example.com", 12024), CustomNode.parse("node.example.com", 12024))
    }
    @Test fun hostWithPortParses() {
        assertEquals(CustomNode("10.0.0.5", 12099), CustomNode.parse("  10.0.0.5:12099 ", 12024))
    }
    @Test fun blankIsNull() { assertNull(CustomNode.parse("   ", 12024)) }
    @Test fun emptyHostIsNull() { assertNull(CustomNode.parse(":12024", 12024)) }
    @Test fun badPortIsNull() { assertNull(CustomNode.parse("host:notaport", 12024)) }
    @Test fun outOfRangePortIsNull() {
        assertNull(CustomNode.parse("host:0", 12024))
        assertNull(CustomNode.parse("host:70000", 12024))
    }
    @Test fun schemePrefixRejected() { assertNull(CustomNode.parse("http://host:12024", 12024)) }
    @Test fun ipv6Rejected() { assertNull(CustomNode.parse("2001:db8::1", 12024)) }
    @Test fun asHostPortRoundTrips() {
        assertEquals("host:12024", CustomNode("host", 12024).asHostPort())
    }
    @Test fun customNodeForcesCompactFiltersOnly() {
        assertEquals(NativeBridge.SyncMode.COMPACT_FILTERS_ONLY,
            syncModeFor(NativeBridge.SyncMode.BOTH, customNodeEnabled = true, isTestnet = false))
    }
    @Test fun testnetForcesCompactFiltersOnly() {
        assertEquals(NativeBridge.SyncMode.COMPACT_FILTERS_ONLY,
            syncModeFor(NativeBridge.SyncMode.BLOOM_ONLY, customNodeEnabled = false, isTestnet = true))
    }
    @Test fun otherwisePrefIsRespected() {
        assertEquals(NativeBridge.SyncMode.BOTH,
            syncModeFor(NativeBridge.SyncMode.BOTH, customNodeEnabled = false, isTestnet = false))
    }
}
```

- [ ] **Step 2: Run tests — verify they FAIL**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.settings.CustomNodeTest" 2>&1 | tail -15`
Expected: FAIL — `CustomNode` / `syncModeFor` unresolved.

- [ ] **Step 3: Implement** — `core/src/main/java/io/digibyte/core/settings/CustomNode.kt`

```kotlin
package io.digibyte.core.settings

import io.digibyte.core.bridge.NativeBridge

/** A user-configured DigiByte node to use as a priority compact-filter peer. */
data class CustomNode(val host: String, val port: Int) {
    fun asHostPort(): String = "$host:$port"

    companion object {
        const val MAINNET_DEFAULT_PORT = 12024
        const val TESTNET_DEFAULT_PORT = 12033

        /**
         * Parse "host" or "host:port" (IPv4 literal or A-record hostname; IPv6 and
         * URL schemes are rejected — the SPV core drops IPv6 peers). Returns null if
         * the host is blank/malformed or the port is not in 1..65535.
         */
        fun parse(raw: String, defaultPort: Int): CustomNode? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val idx = trimmed.lastIndexOf(':')
            val host: String
            val port: Int
            if (idx < 0) {
                host = trimmed; port = defaultPort
            } else {
                host = trimmed.substring(0, idx).trim()
                port = trimmed.substring(idx + 1).trim().toIntOrNull() ?: return null
                if (port !in 1..65535) return null
            }
            if (host.isEmpty()) return null
            // Reject URL schemes ("//") and IPv6 literals (a residual ':' after the split).
            if (host.contains("//") || host.contains(':')) return null
            return CustomNode(host, port)
        }
    }
}

/**
 * The effective sync mode. A configured own-node (or testnet) forces
 * COMPACT_FILTERS_ONLY so no bloom filterload — and thus no address-set leak —
 * ever goes on the wire; otherwise the user's stored sync_mode pref wins.
 */
fun syncModeFor(pref: Int, customNodeEnabled: Boolean, isTestnet: Boolean): Int =
    if (isTestnet || customNodeEnabled) NativeBridge.SyncMode.COMPACT_FILTERS_ONLY else pref
```

- [ ] **Step 4: Run tests — verify they PASS**

Run: `./gradlew :core:testMainnetDebugUnitTest --tests "io.digibyte.core.settings.CustomNodeTest" 2>&1 | tail -8`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/digibyte/core/settings/CustomNode.kt core/src/test/java/io/digibyte/core/settings/CustomNodeTest.kt
git commit -m "feat(settings): CustomNode parse/validate + syncModeFor policy (+ tests)"
```

---

## Task 4: `CustomNodePrefs` — persistence wrapper

**Files:**
- Modify: `core/src/main/java/io/digibyte/core/settings/CustomNode.kt` (append the Android wrapper + imports at top)

**Interfaces:**
- Consumes: `io.digibyte.core.networkSuffix(context)` (the helper `SyncService` imports for `dgb_sync_data`; match its exact package when implementing).
- Produces: `object CustomNodePrefs { isEnabled(ctx); hostPort(ctx): String?; setEnabled(ctx, Boolean); setHostPort(ctx, String) }`. Consumed by Tasks 5/7.

- [ ] **Step 1: Add imports** at the top of `CustomNode.kt` (with the existing import):

```kotlin
import android.content.Context
import io.digibyte.core.networkSuffix
```

- [ ] **Step 2: Append the wrapper**

```kotlin
object CustomNodePrefs {
    private const val PREFS = "dgb_settings"
    private const val KEY_ENABLED = "custom_node_enabled"
    private const val KEY_HOSTPORT = "custom_node_hostport"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(base: String, ctx: Context) = base + networkSuffix(ctx)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(key(KEY_ENABLED, ctx), false)
    fun hostPort(ctx: Context): String? = prefs(ctx).getString(key(KEY_HOSTPORT, ctx), null)
    fun setEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(key(KEY_ENABLED, ctx), enabled).apply()
    fun setHostPort(ctx: Context, hostPort: String) =
        prefs(ctx).edit().putString(key(KEY_HOSTPORT, ctx), hostPort.trim()).apply()
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :core:compileMainnetDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`. (No new unit test — thin SharedPreferences wrapper; the parse/validate logic it stores is covered by Task 3. If `networkSuffix` is not in `io.digibyte.core`, grep `SyncService.kt` for its import and match.)

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/io/digibyte/core/settings/CustomNode.kt
git commit -m "feat(settings): CustomNodePrefs — network-suffixed dgb_settings persistence"
```

---

## Task 5: SettingsViewModel — custom-node state + setters

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/settings/SettingsViewModel.kt`

**Interfaces:**
- Consumes: `CustomNodePrefs`, `CustomNode`, `io.digibyte.core.isTestnet(context)`.
- Produces: `customNodeEnabled: StateFlow<Boolean>`; `customNodeHostPort: StateFlow<String>`; `fun setCustomNodeEnabled(Boolean)`; `fun saveCustomNodeHostPort(String): Boolean` (false if invalid).

- [ ] **Step 1: Add state + setters** (follow the existing `dandelionEnabled` MutableStateFlow idiom, `SettingsViewModel.kt:79-85`; `context` is already injected)

```kotlin
private val _customNodeEnabled = MutableStateFlow(CustomNodePrefs.isEnabled(context))
val customNodeEnabled: StateFlow<Boolean> = _customNodeEnabled.asStateFlow()

private val _customNodeHostPort = MutableStateFlow(CustomNodePrefs.hostPort(context) ?: "")
val customNodeHostPort: StateFlow<String> = _customNodeHostPort.asStateFlow()

fun setCustomNodeEnabled(enabled: Boolean) {
    CustomNodePrefs.setEnabled(context, enabled)
    _customNodeEnabled.value = enabled
    // Restart-to-apply (matches Sync Mode). The injection + CF-only mode + watchdog
    // suppression run inside SyncService's latched setup coroutine, which a live
    // reconnect does NOT re-run — so the UI must tell the user to restart the app.
}

/** Validate + persist the host:port. Returns false (persists nothing) if invalid. */
fun saveCustomNodeHostPort(raw: String): Boolean {
    val defaultPort = if (isTestnet(context)) CustomNode.TESTNET_DEFAULT_PORT
                      else CustomNode.MAINNET_DEFAULT_PORT
    val parsed = CustomNode.parse(raw, defaultPort) ?: return false
    CustomNodePrefs.setHostPort(context, parsed.asHostPort())
    _customNodeHostPort.value = parsed.asHostPort()
    return true
}
```
(Enhancement, out of scope: actively `stopSync()+forceReconnect()+startSync()` or restart `SyncService` for immediate apply — deferred because mid-session manager surgery is fragile per the v3.7.x lifecycle history.)

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileMainnetDebugKotlin 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/digibyte/ui/settings/SettingsViewModel.kt
git commit -m "feat(settings): SettingsViewModel own-node enable + host:port state"
```

---

## Task 6: NetworkInfoScreen — "Use my own node" UI

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/settings/NetworkInfoScreen.kt`

**Interfaces:**
- Consumes: `SettingsViewModel.customNodeEnabled`, `.customNodeHostPort`, `.setCustomNodeEnabled`, `.saveCustomNodeHostPort`, `.peerCount` (existing StateFlow, for the coarse status).

- [ ] **Step 1: Collect the new state** near the other `collectAsStateWithLifecycle()` calls (`NetworkInfoScreen.kt:36-42`)

```kotlin
val customNodeEnabled by viewModel.customNodeEnabled.collectAsStateWithLifecycle()
val customNodeHostPort by viewModel.customNodeHostPort.collectAsStateWithLifecycle()
val peerCount by viewModel.peerCount.collectAsStateWithLifecycle()   // existing flow — coarse status
```

- [ ] **Step 2: Add an "Own node" category** — the screen body is a **LazyColumn**, so every category is wrapped in `item { }` (`NetworkInfoScreen.kt:83`). Model the toggle row on the Tor row (`189-244`), including its `iconTint` (required — `SettingsRow` has no default for it, `SettingsScreen.kt:253`); model the host field on `ReconcileScreen.kt:172-196`.

```kotlin
item {
    SettingsCategory(title = "Own node") {
        SettingsRow(
            icon = Icons.Filled.Dns,
            iconTint = DigiByteAccent,          // required arg — match the Tor/Dandelion rows
            title = "Use my own node",
            subtitle = "Sync only through your DigiByte node (compact filters). " +
                       "Your node must run peerblockfilters=1. Applies on next app restart.",
            onClick = { viewModel.setCustomNodeEnabled(!customNodeEnabled) },
            trailing = {
                Switch(checked = customNodeEnabled,
                       onCheckedChange = { viewModel.setCustomNodeEnabled(it) })
            }
        )
        if (customNodeEnabled) {
            SettingsRowDivider()
            var draft by remember(customNodeHostPort) { mutableStateOf(customNodeHostPort) }
            var error by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; error = false },
                    singleLine = true,
                    isError = error,
                    label = { Text("Node address (host or host:port)") },
                    placeholder = { Text("10.0.0.5  or  node.example.com:12024") }
                )
                if (error) {
                    Text("Enter a valid host, optionally :port (1–65535). IPv6/URLs not supported.",
                         color = MaterialTheme.colorScheme.error,
                         style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { error = ! viewModel.saveCustomNodeHostPort(draft) }) {
                    Text("Save")
                }
                // Coarse reachability signal (full "your node is serving filters" check is a
                // follow-up needing a native CF-peer-census accessor).
                Text(
                    if (peerCount > 0) "Connected · $peerCount peer(s)"
                    else "Not connected — restart the app after saving; check your node is reachable and serving filters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```
(Import `androidx.compose.material.icons.filled.Dns`, `OutlinedTextField`, `TextButton`, `DigiByteAccent`, and the `remember`/`mutableStateOf`/`getValue`/`setValue` delegates as needed; match the file's import style.)

- [ ] **Step 3: Build + install; verify visually**

Run: `./gradlew :app:assembleMainnetDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`. Manual: Settings → Network Info shows the toggle; enabling reveals the field + status; an invalid entry shows the error; a valid entry saves and persists across a revisit.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/digibyte/ui/settings/NetworkInfoScreen.kt
git commit -m "feat(settings): NetworkInfoScreen 'Use my own node' toggle + host:port + status"
```

---

## Task 7: SyncService — inject the node, apply CF-only, suppress the bloom watchdog

**Files:**
- Modify: `app/src/main/java/io/digibyte/service/SyncService.kt`

**Interfaces:**
- Consumes: `CustomNodePrefs`, `CustomNode`, `syncModeFor(...)`, `io.digibyte.core.isTestnet`, `NativeBridge.injectPeerByIp`.

- [ ] **Step 1: Add `injectCustomNode()`** near `injectTestnetPeers()` (`SyncService.kt:1288-1296`). **Resolve DNS in Kotlin on `Dispatchers.IO` and inject an IPv4 literal** (never a hostname — see Global Constraints).

```kotlin
/**
 * Inject the user's own node as a priority compact-filter peer (services
 * 0x41 = NODE_NETWORK|NODE_COMPACT_FILTERS). Re-run every sync start. Resolves
 * the hostname off the native peer lock (injectPeerByIp resolves under PEER_GUARD)
 * and passes an IPv4 literal (its live-manager re-add path uses inet_pton). No-op
 * unless the toggle is on, the address parses, and it resolves to an IPv4.
 */
private suspend fun injectCustomNode() {
    if (! CustomNodePrefs.isEnabled(this)) return
    val raw = CustomNodePrefs.hostPort(this) ?: return
    val defaultPort = if (isTestnet(this)) CustomNode.TESTNET_DEFAULT_PORT
                      else CustomNode.MAINNET_DEFAULT_PORT
    val node = CustomNode.parse(raw, defaultPort) ?: run {
        Log.w(TAG, "custom node enabled but address unparseable: '$raw'"); return
    }
    val ip = withContext(Dispatchers.IO) {
        try {
            java.net.InetAddress.getAllByName(node.host)
                .firstOrNull { it is java.net.Inet4Address }?.hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "custom node DNS resolve failed: ${node.host}", e); null
        }
    } ?: return
    NativeBridge.injectPeerByIp(ip, node.port, 0x41L)
    Log.i(TAG, "custom node injected as priority CF peer: $ip:${node.port} (${node.host})")
}
```

- [ ] **Step 2: Call it at each injection site** — immediately after each `injectBloomPeers()` (`SyncService.kt:883`, `:770`, `:382`). These are already in suspend/coroutine context, so `injectCustomNode()` (suspend) can be called directly. Example at 883:

```kotlin
injectBloomPeers()
injectCustomNode()
```

- [ ] **Step 3: Apply the effective sync mode from the CORRECT prefs handle** — at the `sync_mode` read + `setSyncMode` region (`SyncService.kt:898-903`). **Read from the `settings` (dgb_settings) handle, NOT `prefs` (dgb_sync_data, which has no `sync_mode` key).** Preserve the surrounding filter-header restore / `enableAutoCompactFilterFetch` (it runs when `syncMode != BLOOM_ONLY`).

```kotlin
val pref = settings.getInt("sync_mode", NativeBridge.SyncMode.BOTH)   // settings == dgb_settings handle (SyncService.kt:892/:901)
val syncMode = syncModeFor(
    pref = pref,
    customNodeEnabled = CustomNodePrefs.isEnabled(this),
    isTestnet = isTestnet(this)
)
NativeBridge.setSyncMode(syncMode)
```

- [ ] **Step 4: Suppress the bloom-fallback watchdog under the toggle** — in the 120s BIP158→bloom watchdog (`runBip158Watchdog`, the bloom-fallback decision is currently guarded to skip on testnet at ~`:624/:704`, and reads `sync_mode` at ~`:967`). Extend that guard so bloom fallback is ALSO skipped when the own-node toggle is on, and read the mode through `syncModeFor(...)`:

```kotlin
// existing guard was: if (! isTestnet(this)) { ...fallbackToBloom()... }
val suppressBloomFallback = isTestnet(this) || CustomNodePrefs.isEnabled(this)
if (! suppressBloomFallback) {
    NativeBridge.fallbackToBloom()
    // ...existing bloom-fallback bookkeeping...
} else {
    Log.i(TAG, "CF stall under own-node/testnet — retrying filters, NOT falling back to bloom")
    // keep retrying/re-anchoring the filter path; never put the address set on the wire
}
```
Apply the same `suppressBloomFallback` condition at every `fallbackToBloom()` site the watchdog reaches (the review cited ~`:624/:704`), and route the `:967` `sync_mode` read through `syncModeFor(...)` as in Step 3. **The implementer must Read the current watchdog body and adapt these to the real control flow — the exact structure differs from the sketch above.**

- [ ] **Step 5: Build; verify it compiles**

Run: `./gradlew :app:assembleMainnetDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: On-device smoke test** (mainnet; own node reachable + `peerblockfilters=1`; **core pin MUST include Task 2**)

Enable "Use my own node", enter the node's `host:port`, **restart the app**. Verify:
- `adb logcat | grep -i "custom node injected"` shows the resolved IPv4 injection.
- The node is NOT rejected: `adb logcat | grep "node doesn't support SPV mode"` shows nothing for the node's IP (if it does, the core pin lacks Task 2).
- `/proc/net/tcp` shows a connection to the node's port; sync progresses in `COMPACT_FILTERS_ONLY` (`getSyncMode()` == 1); no `filterload`/bloom.
- Force a filter stall (e.g. point at an unreachable node) → watchdog does NOT flip to bloom (no `filterload` on the wire); logs the "NOT falling back" line.
- Disable the toggle, restart, confirm normal public-peer sync resumes.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/digibyte/service/SyncService.kt
git commit -m "feat(sync): inject own node (resolved IPv4) as priority CF peer; force CF-only + suppress bloom watchdog when enabled"
```

---

## Out of scope (explicit follow-ups)

- **Gossip-retention mainnet generalization + Sybil hardening** — the other half of the CF-gate work, deferred to the oracle-bootstrap implementation (same predicate helper, plus reserved bloom-dial slots / un-evictable injected peers so spoofable `0x40` gossip can't starve bloom seeders).
- **Strict-first / exclusive "only my node" dial ordering.** Native `startSync` re-prepends `digiscope.me` after the Kotlin injection, and `_prependSavedPeerAddr` stamps `time(NULL)` — so if the seeder injects ≥4 CF peers in the same wall-clock second, the non-stable qsort could order the node past dial slot 5 and it isn't dialed that launch (low probability). The node is a *high-priority* CF peer, not guaranteed first. A dedicated priority slot / newer timestamp / suppressing the digiscope prepend under the toggle is the real fix — needs a small native hook. Restate the guarantee to users as "dialed as one of up to 5 CF peers."
- **Full CF-peer health indicator.** The coarse "N peers" status ships here; a precise "your node is serving filters" readout needs a new native accessor (count peers passing `_BRPeerManagerPeerSupportsCompactFilters`, ideally a "cfheaders responded" flag) — aligns with the oracle-bootstrap census metric.
- **Immediate-apply on toggle** (mid-session `stopSync`+`forceReconnect`+`startSync` or service restart) instead of restart-to-apply.
- **Model A (full wallet↔node JSON-RPC backend)** on a `UtxoSource` seam — Track A tier 2, after bloom excision.
- **Bloom code excision + `X.0.0` major bump** — later step of the bloom-deprecation plan; gated on peer diversity + this own-node fallback.

## Manual verification checklist (before merge)

- [ ] `native/src/test/host/cf_gate_kat/run.sh` and `network_switch_kat/run.sh` both `ALL PASSED`.
- [ ] `./gradlew :core:testMainnetDebugUnitTest --tests "*.CustomNodeTest"` green.
- [ ] `./gradlew :app:assembleMainnetDebug :app:assembleDigiTestnetDebug` both `BUILD SUCCESSFUL`.
- [ ] Existing security tests still pass (`./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*"`).
- [ ] **Core pin includes the Task-2 commit** (CI assertion; else the feature silently no-ops on mainnet). Submodule pushed to `johnnylaw`.
- [ ] On-device: own-node CF sync works on mainnet; the watchdog does NOT fall back to bloom under the toggle even on a forced stall.
- [ ] **Regression:** a default-BOTH mainnet wallet still holds its bloom seeder peers (accept-gate change must not have disturbed the bloom pool), and a `BLOOM_ONLY` wallet (toggle off, `sync_mode=BLOOM_ONLY`) syncs unchanged — confirm the `sync_mode` pref is honored (guards against the wrong-prefs-handle regression).
- [ ] Full pre-publish suite (API 28/33/34/35) before any release tag.
