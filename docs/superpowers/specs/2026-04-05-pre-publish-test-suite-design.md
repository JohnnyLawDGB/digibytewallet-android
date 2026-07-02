# Pre-Publish Test Suite — Design Spec

## Goal

Bash script that builds the APK, boots Android emulators across multiple API levels, runs 8 test scenarios on each, and reports pass/fail. Run before every release to catch crashes, ANRs, and regressions.

## Problem

Every bug found by users in the field — Android 13 crash (no lock screen), ANR on sync complete, Room migration failure on upgrade, peers disconnecting — could have been caught by automated emulator testing before publishing. Manual testing on a single device misses version-specific issues.

## Architecture

Single bash script at `scripts/pre-publish-test.sh`. No external dependencies beyond the Android SDK (emulator, adb, avdmanager, sdkmanager). Test fixtures directory for the upgrade scenario.

## Emulator Strategy

Three API levels covering the key boundaries:
- **API 28** — matches the physical test device (Samsung SM-N950U, Android 9)
- **API 33** — Android 13, where Neel's crashes occurred
- **API 34** — modern Android 14, Google APIs

System images downloaded automatically if missing. AVDs created with consistent names (`dgb-test-api28`, `dgb-test-api33`, `dgb-test-api34`). Emulators run headless (`-no-window -no-audio -gpu swiftshader_indirect`).

Emulators boot sequentially — running all 3 simultaneously requires ~12GB RAM which may not be available. Each emulator is booted, tested, then killed before the next.

## Test Fixtures

`test-fixtures/previous-release.apk` — the APK from the previous release, used for upgrade testing. Gitignored (too large for git). Placed manually or via helper script. The upgrade test is skipped with a warning if the fixture is missing.

`test-fixtures/.gitkeep` — ensures the directory exists in git.

## Test Scenarios

All tests use logcat parsing to determine pass/fail. Each test clears logcat before starting, then checks for specific markers within a timeout.

### A. Fresh install, no lock screen

Verifies the app launches without crashing on a device with no PIN/pattern/password. This caught the `KeyStoreManager.setUserAuthenticationRequired(true)` crash.

1. Ensure no lock screen: `adb shell locksettings clear --old <any>`
2. Install APK: `adb install app-mainnet-debug.apk`
3. Launch: `adb shell am start -n io.digibyte/.MainActivity`
4. Wait 10 seconds
5. **Pass:** No `FATAL EXCEPTION` or `ANR in io.digibyte` in logcat
6. **Fail:** Any crash or ANR

### B. Fresh install, with lock screen

Same as A but with a PIN set. Verifies the normal path works.

1. Set PIN: `adb shell locksettings set-pin 123456`
2. Install and launch
3. Wait 10 seconds
4. **Pass:** `JNI_OnLoad: core-lib loaded` in logcat, no crashes
5. Cleanup: `adb shell locksettings clear --old 123456`

### C. Upgrade over previous version

Verifies installing a new APK over an old one doesn't crash (Room migration, SharedPreferences compatibility).

1. Install `test-fixtures/previous-release.apk`
2. Launch, wait 5 seconds (let it create DB/prefs)
3. Install new APK over it: `adb install -r app-mainnet-debug.apk`
4. Launch
5. Wait 10 seconds
6. **Pass:** No `FATAL EXCEPTION`, no `Room cannot verify the data integrity` in logcat
7. **Fail:** Any crash

### D. Wallet creation flow

Verifies mnemonic generation and wallet creation work end-to-end.

1. Fresh install, launch
2. Navigate: tap "Create New Wallet" → seed display → "I've Written It Down" → seed verify (skip if possible) → PIN setup → enter PIN
3. UI interaction via `adb shell input tap` at known coordinates for 1080x2220 resolution
4. **Pass:** `createWalletFromBytes: wallet created successfully` in logcat
5. **Fail:** Missing logcat marker or crash

### E. Wallet recovery flow

Verifies seed recovery routes to PIN setup, not the old unlock screen.

1. Fresh install, launch
2. Navigate: tap "Recover Existing Wallet" → enter seed words → select date → recover → PIN setup
3. Seed words entered via `adb shell input text`
4. **Pass:** `recoverWalletFromBytes: wallet recovered` in logcat, followed by PIN setup screen (no "Enter Your 6-digit PIN to Unlock")
5. **Fail:** Missing recovery marker, or old PIN screen appears

### F. Sync completes without ANR

Verifies the sync service completes and drops its notification without freezing.

1. After wallet creation (from test D), wait up to 60 seconds
2. **Pass:** `Sync complete — dropping foreground notification` in logcat, no `ANR in io.digibyte`
3. **Fail:** ANR, or sync never completes within timeout

### G. Send transaction broadcasts

Verifies a self-send transaction creates, signs, and broadcasts successfully.

1. After sync completes, navigate to Send screen
2. Enter wallet's own receive address and small amount
3. Tap "Review & Send" → confirm
4. **Pass:** `publishTransaction: broadcast succeeded` in logcat
5. **Fail:** `publishTransaction: callback error` or no broadcast log

Note: This test requires the emulator to have network connectivity to reach DigiByte peers. If peers can't connect (no bloom peers reachable from emulator), the test is marked as SKIP with a warning.

### H. 60-second stability soak

Verifies the app doesn't crash during normal idle after sync.

1. After sync completes, leave the app running for 60 seconds
2. **Pass:** No `FATAL EXCEPTION` or `ANR` in logcat during the soak period
3. **Fail:** Any crash or ANR

## Script Structure

```bash
scripts/
├── pre-publish-test.sh          # Main entry point
└── test-helpers/
    ├── emulator-setup.sh        # Download images, create AVDs
    ├── test-scenarios.sh         # Individual test functions (test_A through test_H)
    └── report.sh                # Collect results, print summary
```

Keeping helpers in separate files avoids a single 500-line script. The main script sources them.

## Usage

```bash
# Run all API levels (builds APK first)
./scripts/pre-publish-test.sh

# Run only API 33
./scripts/pre-publish-test.sh 33

# Skip APK build (use existing)
./scripts/pre-publish-test.sh --skip-build

# Run specific test only
./scripts/pre-publish-test.sh --test D
```

## Output

```
=== DigiByte Wallet Pre-Publish Test ===
APK: app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk
Date: 2026-04-05 16:00:00

--- API 28 (Android 9) ---
  [A] Fresh install, no lock screen     PASS  (2.1s)
  [B] Fresh install, with lock screen   PASS  (2.3s)
  [C] Upgrade from previous version     PASS  (4.5s)
  [D] Wallet creation flow              PASS  (8.2s)
  [E] Wallet recovery flow              PASS  (7.8s)
  [F] Sync without ANR                  PASS  (15.1s)
  [G] Send transaction                  SKIP  (no peers)
  [H] 60-second stability soak          PASS  (60.0s)
  Result: 7/8 PASS, 1 SKIP

--- API 33 (Android 13) ---
  [A] Fresh install, no lock screen     PASS  (2.0s)
  ...

=== SUMMARY ===
API 28: 7/8 PASS  (1 SKIP)
API 33: 8/8 PASS
API 34: 8/8 PASS
Overall: PASS
```

Results also saved to `test-results/YYYY-MM-DD-HHMMSS.txt` for historical tracking.

## Known Limitations

- **UI interaction coordinates** are hardcoded for 1080x2220 resolution (Pixel 3a density). If emulator resolution changes or UI layout changes significantly, tap coordinates need updating.
- **Test G (send)** requires bloom peers reachable from the emulator. May not work on restricted networks. Marked SKIP rather than FAIL when peers can't connect.
- **Test E (recovery)** needs a valid seed phrase. The script uses a test mnemonic that produces a known wallet (no real funds).
- **Sequential execution** — ~5 minutes per API level, ~15 minutes total for all 3. Parallel would be faster but requires more RAM.

## What's NOT in Scope

- CI/CD integration (GitHub Actions) — local-only for now
- Performance benchmarking
- UI screenshot comparison
- Automated Play Store upload
