# CLAUDE.md — DigiByte Android Wallet

## Project Overview

Full Kotlin rewrite of the DigiByte Android SPV wallet. Jetpack Compose UI, C native core via JNI, hardware-backed security, DigiAssets v2, Digi-ID, Tor routing, Community Hub.

**Repo:** `JohnnyLawDGB/digibytewallet-android`
**Branch:** `phase1-modernization`
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

# Native rebuild (after C changes)
./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug
```

## Key Architecture Decisions

### Seed Security
- Seed encrypted with AES-256-GCM via Android Keystore (`setUserAuthenticationRequired(true)`, 10s validity)
- Raw seed crosses JNI only during `createWallet()` / `recoverWallet()` — never returned
- `g_seed` global zeroed on `lockSession()` via `secure_zero()` (volatile + compiler barrier)
- `signMessage()` returns `"address|base64sig"` — private key derived, used, cleaned within C core
- `BRKeyClean()` called after every signing operation

### SPV Sync
- Priority bloom peer: `digiscope.me` injected into peer pool on every sync start via `_injectPriorityPeer()`
- Rescan locks to priority peer via `BRPeerManagerSetFixedPeer` then clears after completion
- Block/peer persistence: async hex encoding to SharedPreferences, seed fingerprint prevents clearing on restart
- `isWalletLoaded()` JNI prevents wallet double-free on unlock
- `hasReachedSynced` initialized from persisted `has_synced` flag to avoid "Syncing 0%" on restart
- `g_peerManagerNeedsRecreate` cleared after peer manager creation; guarded during rescan

### Digi-ID
- Real BRKeyCompactSign signing (Bitcoin message signing protocol)
- Callback domain validated against URI host before POST
- HTTP (u=1) callbacks blocked entirely
- Certificate pinning enabled for api.digiscope.me
- Response body and wallet address redacted from logs

### Community Hub
- Backend: `/opt/digiscope-backend/` on `digiscope.me` (PM2 id=5, port 3001)
- REST at `/api/hub/*`, WebSocket at `/api/hub/ws`
- Forum channels: 6=Announcements, 7=Proposals, 8=Support
- Chat channels: 1=General, 2=Trading, 3=Dev, 4=Assets, 5=Ask Enigma
- Wallet gets its own session via `/api/auth/digiid/callback` (not the browser's session)
- Profile uses `COALESCE(custom_username, username)` for display name

### DigiRunner Game
- 4 files: GameState.kt, GamePhysics.kt, GameRenderer.kt, DigiRunnerGame.kt
- Game module has ZERO dependency on `:core` or `:native` — completely isolated
- Character: Digi-Robot (chrome, LED visor, piston legs)
- Input: hold=sprint+crouch, release=spring jump, momentum carries airborne
- DGB coins: 3D Y-axis rotation, official brand colors (#0066CC, #002352)
- Obstacles: BTC coin stacks (1-3 high), -2 coins on hit
- DGB moon: official logo SVG path traced as glowing moon in sky

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
- `dgb_sync_data` — saved blocks (hex), saved peers (hex), has_synced flag, last_balance
- `dgb_digiscope` — JWT session token for Hub

## VPS (digiscope.me)
- **SSH:** `ssh -i ~/.ssh/DigitalOcean root@digiscope.me`
- **DGB Node:** v8.26.0, port 12024, `peerbloomfilters=1`, maxconnections=60
- **Backend restart:** `pm2 restart digiscope-backend --update-env`
- **DB:** `/opt/digiscope-backend/data/digibyte.db` (NOT digiscope.db)

## Security Audit
- 34 security tests in `core/src/test/java/io/digibyte/core/security/`
- MobSF report at `security/reports/mobsf-report.json`
- Audit summary at `security/AUDIT-SUMMARY.md`
- Remaining: CRITICAL-2 (g_seed process global), CRITICAL-3 (loadSeed returns String)

## Testing Notes
- `./gradlew :game:compileMainnetDebugKotlin` — fast check for game-only changes
- Device tests need USB connected: `adb devices` should show SM-N950U
- After native C changes, must rebuild native module before app
- The `drawHud` canvas text crashes if canvas size < 50px — guard is in place
