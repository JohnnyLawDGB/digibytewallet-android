# CLAUDE.md — DigiByte Android Wallet

## Project Overview

Full Kotlin rewrite of the DigiByte Android SPV wallet. Jetpack Compose UI, C native core via JNI, hardware-backed security, DigiAssets v2 (detection), Digi-ID, Community Hub.

**Repo:** `JohnnyLawDGB/digibytewallet-android`
**Branch:** `develop`  _(canonical/default integration branch; releases are tag-driven off `develop`. The old `phase1-modernization` branch is retired/stale — do not work from it.)_
**Version:** v4.0.62 (versionCode 40062)  _(source of truth: `app/build.gradle.kts` `versionName`/`versionCode` — bump there on release and mirror here)_
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

- **Compact-filters-only sync (`NativeBridge.SyncMode`, `syncModeFor` in `CustomNode.kt`):** the wallet ALWAYS runs BIP157/158 compact filters. `syncModeFor(...)` unconditionally returns `COMPACT_FILTERS_ONLY` and ignores the stored `dgb_settings/sync_mode` pref; the old Sync Mode toggle/screen (`SyncModeScreen.kt`) is removed. Bloom (BIP37) is gone as a data path — no `filterload` ever goes on the wire, so the wallet's address set never leaves the device. **Bloom (BIP37) was fully EXCISED in v4.0.0 — BIP157/158 compact filters are the ONLY sync path.** `BRBloomFilter.c/.h`, the filterload/filteradd/merkleblock-message handlers, the bloom filter loader, and the `fallbackToBloom` path are all deleted; the `SyncMode` enum keeps its 3 values as an ABI constant but `COMPACT_FILTERS_ONLY` is the only reachable mode. The address set can never leave the device under any condition. The sovereign fallback is the user's own node (own-node CF pairing), not bloom.
- **BIP157/158 compact filters (shipped v3.5.39):** native GCS decoder + `cfheaders`/`cfilter` wire handlers + filter-header chain persistence. `cf_birth_height` (`dgb_settings`) bounds the scan.
- **BIP158 watchdog (`SyncService.startBip158Watchdog`, `BIP158_FALLBACK_TIMEOUT_MS = 120s`):** if the cfheaders chain stalls, its only recovery is a one-time chain re-anchor at the block floor (`reanchorCompactFilterChainAtFloor`) — it NEVER degrades to bloom (every former bloom-fallback branch now stays on filters). Testnet26 and a configured own-node likewise force CF-only.
- **Peer discovery:** priority peer `digiscope.me` injected on sync start; the wallet fetches filter-capable peers from the capability-aware seeder `api.digiscope.me/api/peers` (cached hourly; falls through to bloom-capable peers only when no filter peers are advertised, but still dials them as CF peers). Native `PEER_MAX_CONNECTIONS = 8`.
- **Dandelion++ (opt-in, default OFF):** stem submission to a seeder-tagged peer with Kotlin embargo→fluff fallback. Toggle in Settings → Network Info (`Broadcaster` + `SyncService.injectDandelionPeers`).
- **Tor transport (opt-in, default OFF):** no-exec/dlopen kmp-tor, SOCKS5 into the C core (routing fixed v3.7.5, `SafeSocks=0`). Advanced toggle in Settings → Network Info.
- Rescan locks to priority peer via `BRPeerManagerSetFixedPeer` then clears after completion.
- `isWalletLoaded()` JNI prevents wallet double-free on unlock; `hasReachedSynced` initialized from persisted `has_synced` flag.
- **Service lifecycle:** SyncService drops the foreground notification after sync; peers stay connected while the app is open. WorkManager SyncWorker does 30-second catch-ups every 15 min when backgrounded.

### Peer discovery + sync-loop lifecycle (CANONICAL infra design — do NOT re-derive)
The wallet keeps re-hitting the same "won't sync / no confirms for days" class. The intended design and the failure modes, pinned so they persist:
- **Peer tiering (the canon):** the wallet's ONLY good CF peers are, in order: (1) the **16 hardcoded CF oracle nodes** in `MAINNET_PRIORITY_PEER_IPS` (`jni_peer.c`, `MAINNET_PRIORITY_PEER_SERVICES` requires COMPACT_FILTERS) — the **CANON**; (2) the capability-aware **seeder crawler** `api.digiscope.me/api/peers` (returns filter-validated `/9.26.4/` peers, uptime/composite-scored) — the **FALLBACK only when the canon fails**. The general DHT/addr-relayed pool is **JUNK** (old `/7.17//8.22/` nodes tens-of-thousands of blocks behind → rejected `node isn't synced`; non-SPV `/8.26/` → `doesn't support SPV mode`). **The wallet must PREFER/PIN the canon and NOT roam the junk pool.** Today it injects the 16 + seeder but ALSO dials the junk pool and roams without pinning — the gap behind fragile real-time sync. Rescan pins the priority peer (`BRPeerManagerSetFixedPeer`, line 63) which is exactly why rescan is robust and real-time roaming is not.
- **Sync-loop lifecycle:** the SPV loop runs in `SyncService` `serviceScope`. It MUST survive OS background-freeze (**Doze / Samsung One UI / Pixel aggressive battery mgmt**) and **REVIVE on foreground/screen-on**. A foreground `SyncService` whose loop DIED (`dumpsys` shows `isForeground=true` but `lastActivity` minutes stale, 0 peers, `bread` silent) = the **0-peer dead wedge**. Device-specific by design: aggressive-OS devices (Ultra One UI, Pixel 7 Android 17) freeze hardest; "stable on other devices" just means those don't freeze.
- **Three wedge classes**, all presenting as "stuck sync": **(a) peer-quality roaming** — no filter peer held because the canon isn't pinned; **(b) dead-branch/orphan tip** — a multi-algo (~15s) reorg lands the tip on an abandoned fork; the wallet then rejects canonical peers as `node isn't synced` (its dead-tip height ≥ theirs). Recovery is REACTIVE (v4.0.5 orphan re-anchor + v4.0.14 tip-stall watchdog); **(c) 0-peer dead loop** — OS freeze kills the loop, the foreground service doesn't revive it. **Rescan cures (a)+(b)** (pins the canon + rebuilds canonical from a floor); **force-stop cures (c)**. The durable fixes: pin-the-canon peer selection (a), resume-time loop revival (c).

- **Recreate must resume near tip (FIXED v4.0.40 — do NOT reintroduce).** Every recovery path used to call `forceReconnect()` + `startSync()`, which rebuilds the manager from the **stale cold-start `g_savedBlocks`** — loaded once at launch, never refreshed — flooring `manager->lastBlock` to the birth checkpoint and re-arming auto-fetch at `cf_birth_height`. Measured: 24,052,509 → 22,650,000 and ~6h to climb back. The fix is ordering plus retention, and all three parts are load-bearing: **(1)** refresh the near-tip window BEFORE the rebuild consumes it and restore the CF ledger AFTER the new manager exists (`core/sync/RecreateSequence`); **(2)** routine recovery keeps the scan ledger — only `FILTER_CHAIN_CORRUPT` / `SCAN_LEDGER_CORRUPT` / `WALLET_RESET` may drop it (`core/sync/CfRecoveryPolicy`); **(3)** flush the live block window and ledger to disk BEFORE the teardown, since both restore steps read the last *persisted* snapshot (`flushLiveStateBeforeRecreate`). Parts 1+2 alone leave a one-save-interval give-back charged on every recovery. Any new recovery path must go through `recreatePeerManagerResumingNearTip()`.

### Localisation (12 languages — NOT optional per-change)
Any user-facing string ships in **all 12 languages in the same commit** — English plus the 11 in `AppLocale.SUPPORTED` (de, es, hi, zh, ja, pt-BR, id, vi, tr, ru, fil). English-first means broken in eleven languages until a user reports it screen by screen, which is exactly how the onboarding gaps were found.
- Keys live in `app/src/main/res/values*/strings_wallet.xml`, deliberately separate from the dead bread-wallet `strings.xml`.
- Locale dirs use **Android qualifiers, not BCP-47**: `values-b+pt+BR`, `values-b+fil`. The BCP-47 spelling compiles, ships, and never loads.
- Three places must agree: `AppLocale.SUPPORTED`, `xml/locales_config.xml`, `values-xx/`.
- Two gates in `app/src/test/java/io/digibyte/ui/locale/`: `LocaleResourceParityTest` (a key missing from a language) and `OnboardingHardcodedStringTest` (a string never extracted — parity cannot see these, as there is no key to be missing). **Add each screen to `COVERED` as it is localised**; the gate only sees files listed there.
- Language picker: Settings → Appearance, and a `LanguageChip` on onboarding. The onboarding one is load-bearing — Settings sits behind wallet creation.
- Translations are machine-assisted and unreviewed; the picker says so. Seed-phrase and wallet-wipe warnings need a native speaker before they can be called done.

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
- Launches from the Hub → Games tab (v3.10.28+). Removed from the sync overlay: rendering it during a fresh full sync could exhaust process memory on long-history wallets.

### DigiAsset balance accounting (settled v4.0.39 — do NOT re-derive)
The counting rule is one line: **a row counts if and only if the native wallet still holds
that exact output.** Nothing counts because of where its record came from — the earlier
"provenance rescue" is what let phantom rows survive, and removing it is the fix.

Two accounting rules the parser must apply, both fund-safety-critical:
- **Implicit change.** DigiAssets returns the leftover of a partial transfer to the **LAST
  output**. Missing this under-counts in Kotlin and, in native, lets a plain-DGB spend
  destroy an asset. `AssetTxQuantity.implicitChange()`.
- **CONFLICTED state.** An abandoned broadcast that was re-sent still reads as HELD —
  nothing spends the change of an attempt that never confirmed — so it double-counts.
  `AssetSpentState.CONFLICTED`.

**Process rule earned the hard way:** four releases were spent inferring the bug from
balance *totals*. It was found in minutes once the per-row COUNT/drop decision was logged.
When an asset count is wrong, **log the rows first** — do not reason from the total.

### The handoff specs (`docs/specs/`)
The 2026-08-16 handoff and its three specs live here, each with a **disposition banner**
recording what shipped and which premises the code refuted. All three were
investigation-first, and in each case investigation overturned something load-bearing — so
treat them as history plus decisions, not as instructions:
- `digiasset-balance-accounting.md` — ✅ shipped; the "history-replay drift" mechanism was wrong
- `compact-filter-peer-retention.md` — 🟡 premises refuted; "sticky 2 + standby" deliberately NOT built
- `restore-flow-asset-aware.md` — 🟡 privacy hole closed; the filter-rescan mechanism cannot work (`credit iff derived`)

## Important Patterns

### Guardrails (run these; they exist because each one already bit us)

```bash
./scripts/check-submodule-pin.sh          # is the shipped pin durable?  (also in CI)
ln -sf ../../scripts/check-worktree-target.sh .git/hooks/pre-commit   # once per clone
```

- **`check-submodule-pin.sh`** — asks the FORK whether the submodule pin is reachable from a
  durable branch (`develop`/`master`). A pin that resolves locally proves nothing: the object
  is already in your checkout. Two real incidents: a pin that had never been pushed at all
  (surviving only in two local worktrees — a `git worktree prune` would have destroyed it),
  and four shipped releases whose pins existed only on deletable feature branches. **Push
  core to `develop`, not just a feature branch.** Wired into CI.
- **`check-worktree-target.sh`** — refuses a commit in the MAIN checkout while worktrees
  exist. The shell's cwd can reset between commands, and a `git add -A` intended for a
  worktree then lands on whatever branch main is on, over an unrelated base, sweeping up
  whatever was already dirty. Override with `ALLOW_MAIN_COMMIT=1` for deliberate
  merge/release work.
- **Prefer `git add <explicit paths>` over `git add -A`** when a worktree is in play.

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
- `dgb_sync_data` — saved blocks (hex), saved peers (hex), has_synced flag, last_balance, saved_transactions. NOTE: the compact-filter header chain moved OUT of this prefs blob to a plain file `filesDir/saved_filter_headers<net>.bin` in v3.10.29 (`core/…/sync/FilterHeaderStore.kt`) — a hex String here was pinned in the SharedPreferences in-memory map and re-grown on every cfheaders batch (a 512MB heap leak that OOM-looped long-history wallets so they never fully synced).
- `dgb_settings` — `sync_mode` (legacy key, now ignored — sync is always compact-filters-only; see `syncModeFor`), `cf_birth_height` (compact-filter scan floor)
- `dgb_digiscope` — JWT session token for Hub
- `dgb_db_key` — encrypted DB passphrase
- `dgb_bloom_peers` — cached bloom peer list from seeder API
- `dgb_dandelion` — `enabled` flag for Dandelion++ stem submission (default off / opt-in)
- `dgb_dandelion_peers` — cached Dandelion-capable peer list from seeder
- `dgb_pin_store` — EncryptedSharedPreferences for PIN hash

### Versioning Policy
- `3.X.Y` — current line (at v3.10.29). `Y` = patch (every publish bumps at least the patch); `X` = minor (feature batches, e.g. 3.5→3.6→3.7).
- `X.0.0` — major: reserved for a wire-protocol change or removal of the legacy bloom path. **v4.0.0 = the bloom-path removal** (BIP37 excised, BIP157/158-only) — the fresh trigger that earned the major after the retired "4.0.0 = BIP158 lands" trigger. Gates were oracle-bootstrap (v3.10.33) + own-node pairing, both shipped. The next major needs its own fresh trigger.
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
- CRITICAL-4: Resolved (Digi-ID callback domain validation). RESIDUAL (ROADMAP Phase 2): Digi-ID has no dedicated identity path. **Corrected 2026-08-19 — the old text here was wrong twice.** The path is `m/0'/0/0` (the legacy bread-wallet tree), not `m/44'/20'/0'/0/0`: `signMessage`'s second arg is the address FORMAT, and the JNI hardcodes `seed_derive_key(&key, 0, 0)`. And the residual is **linkability, not key exposure** — signing emits a signature not a key, the `\x19DigiByte Signed Message:\n` prefix blocks sighash confusion, `m/0'` is hardened so it cannot be correlated to the BIP84/BIP86 trees, and `m/0'` is only watched for bread-wallet compat (`BRWalletReceiveAddress` hands out BIP84/86 only), so for an app-created wallet that address holds nothing. What remains: every site sees the same identity address (BitID derives per-site from the URI), and a restored bread-wallet seed could have funded that address.

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
`ROADMAP.md` is authoritative and **sovereignty-first** (removing trusted third parties from the data path comes before feature breadth). Read its **top banner first** — it carries the dated true-up and the current "NEXT", which is the part that goes stale. The list below is a pointer only — do **not** maintain a second copy here:

**Working next (per the 2026-08-19 true-up):** `cfcheckpt` active rejection → restore restructure (onboarding split, then asset-aware sweep) → Digi-ID key isolation → `assets.digistamp.co` (now unblocked). These are pre-existing roadmap items, not products of the 2026-08-16 handoff. The **duress PIN is cancelled** — do not resurrect it or build against it.

- Phase 0: Legibility (ARCHITECTURE / THREAT_MODEL / BIP-compliance docs)
- Phase 1: Sovereign data layer — BIP157/158 (**client shipped & default since v3.5.39**; remaining: peer diversity beyond author infra + bloom-deprecation path)
- Phase 2: Key & trust hardening (Keystore auth-binding, PIN rate-limit, Digi-ID key isolation, Tor disposition)
- Phase 3: Feature velocity on the sovereign layer (PSBT, multisig, watch-only, coin control, RBF, WIF sweep, DigiAsset send)
- Phase 4: Distribution + hardware (Play Store, F-Droid, Coldcard QR, NFC)

The older feature-ordered "Phase 1 = Infrastructure / Phase 3 = Privacy" plan is **superseded** (ROADMAP.md:3-6). Do not sequence work from it.
