# CLAUDE.md — DigiByte Android Wallet

## Project Overview

Full Kotlin rewrite of the DigiByte Android SPV wallet. Jetpack Compose UI, C native core via JNI, hardware-backed security, DigiAssets v2 (detection), Digi-ID, Community Hub.

**Repo:** `JohnnyLawDGB/digibytewallet-android`
**Branch:** `phase1-modernization`
**Version:** v3.7.6  _(source of truth: `app/build.gradle.kts` `versionName`/`versionCode` — bump there on release and mirror here)_
**Test devices:** Samsung SM-N950U (Galaxy Note 8, Android 9, API 28); Galaxy S25 Ultra (Android 15, API 35) for 16 KB page-size / modern-API coverage

## Module Structure

```
app/     — Android app (Compose UI, navigation, services, DI)
core/    — Business logic (wallet, assets, IPFS, Digi-ID, security, Hub client)
native/  — C core + JNI bridge (SPV, crypto, secp256k1)
game/    — DigiRunner sync mini-game (standalone, no core dependency)
```

## Build & Deploy

```bash
# Build debug APK
./gradlew :app:assembleMainnetDebug

# Deploy to connected device
adb install -r app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk

# Run all tests (:digidollar is pure-JVM — testMainnetDebugUnitTest alone skips it)
./gradlew testMainnetDebugUnitTest :digidollar:test :digidollar:detekt

# Run security tests only
./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*"

# Run pre-publish test suite (API 28/33/34/35)
./scripts/pre-publish-test.sh

# Run pre-publish on single API level
./scripts/pre-publish-test.sh 33 --skip-build

# Native rebuild (after C changes)
./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug
```

## Key Architecture Decisions

### Seed Security (CRITICAL-2/3 Remediated)
- Seed encrypted with AES-256-GCM via Android Keystore (hardware-backed)
- **No `setUserAuthenticationRequired`** — removed to prevent crashes across API levels (28/33/35). App enforces its own PIN lock.
- `loadSeed()` returns `ByteArray` (not String) — mnemonic never becomes immutable JVM heap object
- `createWalletFromBytes` / `recoverWalletFromBytes` JNI accept `jbyteArray` with `secure_zero()` on C stack
- `g_seed` is `static` to `jni_wallet.c` — accessor API (`seed_sign_transaction`, `seed_derive_key`) prevents direct access from other compilation units
- `ByteArray.fill(0)` in `finally` blocks on all seed paths
- 42 security tests verify these properties

- **Sync modes (`dgb_settings/sync_mode`, `NativeBridge.SyncMode`):** default is `BOTH` — bloom + BIP158 compact filters run in parallel. Other modes: `COMPACT_FILTERS_ONLY` (max address privacy) and `BLOOM_ONLY`. User-selectable in Settings → Sync Mode (`SyncModeScreen.kt`).
- **BIP157/158 compact filters (shipped v3.5.39, default-on):** native GCS decoder + `cfheaders`/`cfilter` wire handlers + filter-header chain persistence. In the compact-filter path the wallet's address set never leaves the device. `cf_birth_height` (`dgb_settings`) bounds the scan.
- **120s BIP158→bloom watchdog:** if filter peers make no progress, the session falls back to `BLOOM_ONLY` (resets next launch so filters are retried).
- **Bloom path (parallel / fallback):** priority bloom peer `digiscope.me` injected on sync start; wallet fetches 10+ bloom peers from `api.digiscope.me/api/peers/bloom` (cached hourly). `PEER_MAX_CONNECTIONS = 5`.
- **Dandelion++ (opt-in, default OFF):** stem submission to a seeder-tagged peer with Kotlin embargo→fluff fallback. Toggle in Settings → Network Info (`Broadcaster` + `SyncService.injectDandelionPeers`).
- **Tor transport (opt-in, default OFF):** no-exec/dlopen kmp-tor, SOCKS5 into the C core (routing fixed v3.7.5, `SafeSocks=0`). Advanced toggle in Settings → Network Info.
- Rescan locks to priority peer via `BRPeerManagerSetFixedPeer` then clears after completion.
- `isWalletLoaded()` JNI prevents wallet double-free on unlock; `hasReachedSynced` initialized from persisted `has_synced` flag.
- **Service lifecycle:** SyncService drops the foreground notification after sync; peers stay connected while the app is open. WorkManager SyncWorker does 30-second catch-ups every 15 min when backgrounded.

### Fee Structure
- Single default fee: `DEFAULT_FEE_PER_KB` (100 sat/byte) — DigiByte min relay fee
- Confirms in ~15 seconds (no fee market on DGB)
- Optional custom fee toggle — user enters total fee in DGB
- Warning system: amber for below relay minimum, red for zero fee

### Defensive Startup
- `AppModule.provideDatabase()` wrapped in try/catch — if DB init fails for ANY reason, `wipeStaleData()` clears corrupt prefs/DB/keys and retries fresh. Wallet seed always preserved.
- Verbose logging at every init step: `adb logcat | grep AppModule`
- `fallbackToDestructiveMigration()` on Room DB for upgrade safety

### Digi-ID
- Real BRKeyCompactSign signing (Bitcoin message signing protocol)
- Callback domain validated against URI host before POST
- HTTP (u=1) callbacks blocked entirely
- Certificate pinning enabled for api.digiscope.me
- Response body and wallet address redacted from logs

### Community Hub
- Backend: `/opt/digiscope-backend/` on `digiscope.me` (PM2 id=5, port 3001)
- REST at `/api/hub/*`, WebSocket at `/api/hub/ws`
- Forum timestamps parsed from SQLite DATETIME strings (snake_case field names)

### DigiRunner Game
- 4 files: GameState.kt, GamePhysics.kt, GameRenderer.kt, DigiRunnerGame.kt
- Game module has ZERO dependency on `:core` or `:native`
- Shows during sync overlay, hidden when sync complete

## Important Patterns

### Submodule (digibytewallet-core)
The C core is a git submodule. Use explicit GIT_DIR for submodule commits:
```bash
GIT_DIR=.git/modules/native/src/main/jni/digibytewallet-core \
GIT_WORK_TREE=native/src/main/jni/digibytewallet-core \
git <command>
```

### JNI Naming Convention
All JNI functions follow: `Java_io_digibyte_core_bridge_NativeBridge_<methodName>`

### SharedPreferences Keys
- `dgb_wallet_seed` — encrypted mnemonic + IV + seed fingerprint
- `dgb_sync_data` — saved blocks (hex), saved peers (hex), `saved_filter_headers` (compact-filter header chain, hex), has_synced flag, last_balance, saved_transactions
- `dgb_settings` — `sync_mode` (BOTH / COMPACT_FILTERS_ONLY / BLOOM_ONLY), `cf_birth_height` (compact-filter scan floor)
- `dgb_digiscope` — JWT session token for Hub
- `dgb_db_key` — encrypted DB passphrase
- `dgb_bloom_peers` — cached bloom peer list from seeder API
- `dgb_dandelion` — `enabled` flag for Dandelion++ stem submission (default off / opt-in)
- `dgb_dandelion_peers` — cached Dandelion-capable peer list from seeder
- `dgb_pin_store` — EncryptedSharedPreferences for PIN hash

### Versioning Policy
- `3.X.Y` — current line (at v3.9.0, upstream). `Y` = patch (every publish bumps at least the patch); `X` = minor (feature batches, e.g. 3.5→3.6→3.7).
- `X.0.0` — major: DECIDED (2026-07) — 4.0.0 is the release that unlocks DigiDollar on mainnet at softfork activation (see ROADMAP → Versioning). Testnet-gated DigiDollar work rides the 3.9.x+ minor line until then.
- Pre-publish test suite must pass before any release
- Never reuse version numbers

## VPS (digiscope.me)
- **SSH:** `ssh -i ~/.ssh/DigitalOcean root@digiscope.me`
- **DGB Node:** v8.26.0, port 12024, `peerbloomfilters=1`, maxconnections=60
- **Backend restart:** `pm2 restart digiscope-backend --update-env`
- **DB:** `/opt/digiscope-backend/data/digibyte.db` (NOT digiscope.db)
- **Bloom seeder:** PM2 `bloom-seeder`, port 8025, `/opt/dgb-bloom-seeder`
- **Downloads:** `/var/www/digiscope-downloads/` (symlinked from `/var/www/digiscope/downloads/`)
- **CRITICAL:** Frontend deploys wipe `/var/www/digiscope/downloads/` — symlinks must be recreated

## Security Audit
- 42 security tests in `core/src/test/java/io/digibyte/core/security/`
- MobSF report at `security/reports/mobsf-report.json`
- Audit summary at `security/AUDIT-SUMMARY.md`
- CRITICAL-1: Resolved as designed (auth not required — app uses own PIN). RESIDUAL (ROADMAP Phase 2): no Keystore user-auth binding + no PIN rate-limit — a compromised app process can decrypt the seed without device unlock.
- CRITICAL-2: Resolved (g_seed static, accessor API)
- CRITICAL-3: Resolved (ByteArray path, zeroed after use)
- CRITICAL-4: Resolved (Digi-ID callback domain validation). RESIDUAL (ROADMAP Phase 2): Digi-ID still signs with the first wallet address (`m/44'/20'/0'/0/0`), not an isolated subtree — key isolation pending.

## Pre-Publish Test Suite
- `./scripts/pre-publish-test.sh` — 8 scenarios across API 28/33/34/35
- Tests: fresh install (no lock screen + with lock screen), upgrade, wallet creation, recovery, sync, send, stability soak
- Must pass before any version tag
- Results saved to `test-results/`

## Testing Notes
- `./gradlew :game:compileMainnetDebugKotlin` — fast check for game-only changes
- Device tests need USB connected: `adb devices` should show SM-N950U
- After native C changes, must rebuild native module before app
- Emulator AVDs: `dgb-test-api28`, `dgb-test-api33`, `dgb-test-api34`, `dgb-test-api35`

## Roadmap
`ROADMAP.md` is authoritative and **sovereignty-first** (removing trusted third parties from the data path comes before feature breadth). The list below is a pointer only — do **not** maintain a second copy here:
- Phase 0: Legibility (ARCHITECTURE / THREAT_MODEL / BIP-compliance docs)
- Phase 1: Sovereign data layer — BIP157/158 (**client shipped & default since v3.5.39**; remaining: peer diversity beyond author infra + bloom-deprecation path)
- Phase 2: Key & trust hardening (Keystore auth-binding, PIN rate-limit, Digi-ID key isolation, Tor disposition)
- Phase 3: Feature velocity on the sovereign layer (PSBT, multisig, watch-only, coin control, RBF, WIF sweep, DigiAsset send)
- Phase 4: Distribution + hardware (Play Store, F-Droid, Coldcard QR, NFC)

The older feature-ordered "Phase 1 = Infrastructure / Phase 3 = Privacy" plan is **superseded** (ROADMAP.md:3-6). Do not sequence work from it.
