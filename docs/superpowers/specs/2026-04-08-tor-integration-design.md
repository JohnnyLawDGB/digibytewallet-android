# Tor Integration via kmp-tor — Design Spec

## Goal

Embed Tor into the wallet so all network traffic (P2P peer connections and HTTP API calls) can be routed through Tor when the user enables it. Hides the user's IP address from DigiByte nodes and API servers.

## Library

- `io.matthewnelson.kmp-tor:runtime:2.6.0` — Kotlin coroutines API for Tor lifecycle
- `io.matthewnelson.kmp-tor:resource-exec-tor:409.5.0` — Tor binary (exec variant, separate process)
- Published on Maven Central — no custom repository needed
- Adds ~6-8MB to APK (arm64-v8a)
- Min SDK 24 (our minSdk is 26 — compatible)

## Architecture

### TorManager Rewrite

Replace the current stub `TorManager` with a real implementation:

```
TorManager
  ├── start() → starts kmp-tor runtime, waits for bootstrap
  ├── stop() → stops Tor daemon
  ├── state: StateFlow<TorState> (Disabled/Starting/Connected/Failed)
  ├── socksPort: Int? (available after Connected)
  ├── isEnabled: Boolean (persisted in SharedPreferences)
  └── bootstrapProgress: StateFlow<Int> (0-100)
```

kmp-tor's `TorRuntime` is configured with:
- `SocksPort auto` — auto-assigns an available port
- `SafeSocks 1` — rejects connections that would leak DNS
- Exec mode — Tor runs in a separate process for crash isolation

### Network Routing

**P2P (DigiByte nodes):**
- `NativeBridge.setSocksProxy("127.0.0.1", socksPort)` — already exists in C core
- `BRPeerSetSocksProxy` routes all peer TCP connections through SOCKS5
- Called in `SyncService.startSyncWithTor()` — already wired, just needs real port

**HTTP (OkHttp — DigiScope API, CoinGecko, bloom seeder, update checker):**
- Rebuild OkHttpClient with SOCKS proxy when Tor is enabled:
  ```kotlin
  OkHttpClient.Builder()
      .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
      .dns { emptyList() }  // force SOCKS5 remote DNS resolution
      .connectTimeout(60, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
  ```
- The empty DNS resolver prevents local DNS queries from leaking

**DNS Leak Prevention:**
- OkHttp: custom `Dns { emptyList() }` forces hostname resolution through Tor
- C core: `BRPeerSetSocksProxy` handles DNS via SOCKS5 CONNECT with hostname
- torrc: `SafeSocks 1` rejects leaky connections, `TestSocks 1` logs warnings

### User Experience

**Settings → Privacy → Tor Routing:**
- Toggle switch (off by default — opt-in)
- When toggled on: "Connecting to Tor..." progress bar (10-30 seconds)
- When connected: "Connected via Tor" with onion icon
- When toggled off: stops Tor, clears proxy, reconnects peers directly

**Wallet screen indicator:**
- Existing Tor badge already shows when `TorState.Connected`
- Just needs real state from the new TorManager

**Sync behavior:**
- `SyncService.startSyncWithTor()` already checks `torManager.isEnabled`
- If enabled: start Tor → wait for bootstrap → set SOCKS proxy → start sync
- If disabled: clear proxy → start sync directly
- Graceful degradation: if Tor fails to start, clear proxy and sync directly

### Bootstrap Progress

kmp-tor fires `TorEvent` entries during bootstrap:
```
BOOTSTRAP PROGRESS=10 TAG=conn_done SUMMARY=Connected to a relay
BOOTSTRAP PROGRESS=50 TAG=loading_descriptors SUMMARY=Loading relay descriptors
BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=Done
```

Mapped to `TorManager.bootstrapProgress: StateFlow<Int>` and displayed in the Settings toggle area during startup.

## Files to Modify

| Action | File |
|--------|------|
| Modify | `core/build.gradle.kts` — add kmp-tor dependencies |
| Modify | `app/build.gradle.kts` — add `jniLibs.useLegacyPackaging = true` |
| Modify | `gradle.properties` — add `android.bundle.enableUncompressedNativeLibs=false` |
| Rewrite | `core/src/main/java/io/digibyte/core/tor/TorManager.kt` — real kmp-tor integration |
| Modify | `app/src/main/java/io/digibyte/di/NetworkModule.kt` — proxy-aware OkHttpClient |
| Modify | `app/src/main/java/io/digibyte/service/SyncService.kt` — bootstrap progress |
| Modify | `app/src/main/java/io/digibyte/ui/settings/SecuritySettingsScreen.kt` — Tor toggle UI |

## Testing

- Toggle Tor on → verify SOCKS proxy port is set → check `adb logcat` for "setSocksProxy"
- Send a transaction with Tor enabled → verify it broadcasts through Tor
- Check DNS leaks: `torrc` `TestSocks 1` logs warnings for leaked DNS
- Toggle Tor off → verify proxy cleared → peers reconnect directly
- Tor bootstrap failure → verify graceful degradation (sync works without Tor)
- Kill Tor process → verify wallet continues functioning (exec mode isolation)

## What's NOT in Scope

- .onion hidden service DigiByte nodes
- Tor bridges / pluggable transports (censorship circumvention)
- Stream isolation (separate circuits per connection)
- Tor for WebSocket (Hub chat) — uses OkHttp which gets the proxy automatically
