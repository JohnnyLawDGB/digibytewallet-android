# CLAUDE.md — DigiByte Android Wallet

## Project Overview

Full Kotlin rewrite of the DigiByte Android SPV wallet. Jetpack Compose UI, C native core via JNI, hardware-backed security, DigiAssets v2 (detection), Digi-ID, Community Hub.

**Repo:** `JohnnyLawDGB/digibytewallet-android`
**Branch:** `phase1-modernization`
**Version:** v3.0.12-beta
**Test device:** Samsung SM-N950U (Galaxy Note 8, Android 9, API 28)

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

# Run all tests
./gradlew testMainnetDebugUnitTest

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

### SPV Sync
- Priority bloom peer: `digiscope.me` injected on every sync start
- **Bloom seeder integration:** wallet fetches 10+ bloom peers from `api.digiscope.me/api/peers/bloom` on startup (cached hourly)
- `PEER_MAX_CONNECTIONS = 5` (increased from 3)
- Rescan locks to priority peer via `BRPeerManagerSetFixedPeer` then clears after completion
- Block/peer persistence: async hex encoding to SharedPreferences
- `isWalletLoaded()` JNI prevents wallet double-free on unlock
- `hasReachedSynced` initialized from persisted `has_synced` flag
- **Service lifecycle:** SyncService drops foreground notification after sync, peers stay connected while app is open. WorkManager SyncWorker does 30-second catch-ups every 15 min when app is backgrounded.

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
- `dgb_sync_data` — saved blocks (hex), saved peers (hex), has_synced flag, last_balance, saved_transactions
- `dgb_digiscope` — JWT session token for Hub
- `dgb_db_key` — encrypted DB passphrase
- `dgb_bloom_peers` — cached bloom peer list from seeder API
- `dgb_pin_store` — EncryptedSharedPreferences for PIN hash

### Versioning Policy
- `3.0.X` — patch (every publish gets a bump)
- `3.X.0` — minor (new features)
- `X.0.0` — major (protocol changes)
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
- CRITICAL-1: Resolved (auth not required — app uses own PIN)
- CRITICAL-2: Resolved (g_seed static, accessor API)
- CRITICAL-3: Resolved (ByteArray path, zeroed after use)
- CRITICAL-4: Resolved (Digi-ID callback domain validation)

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
See `ROADMAP.md` for the full development plan:
- Phase 1: Infrastructure (release signing, Maestro, code quality, F-Droid)
- Phase 2: Features (tx detail, watch-only, DigiAsset send, coin control)
- Phase 3: Privacy (BIP157/158, Tor, Dandelion++, v9.26)
- Phase 4: Distribution (Play Store, hardware wallets)
