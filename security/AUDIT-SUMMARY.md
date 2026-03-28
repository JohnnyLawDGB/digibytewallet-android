# DigiByte Wallet v3.0.1 — Security Audit Summary

**Date:** 2026-03-28
**Version:** v3.0.1 (phase1-modernization branch)
**Package:** io.digibyte
**APK SHA256:** `5dabf3a5f74d9aed814e3827c0b1e8fc3174f2deeaebd59730cd99a460aa6c95`

## Overview

This audit covers the security posture of the DigiByte Android wallet with focus on seed isolation, JNI boundary safety, network leak prevention, and manifest configuration. The wallet stores a BIP39 mnemonic encrypted with AES-256-GCM via Android Keystore.

## Automated Scans

### MobSF Static Analysis
- **Trackers detected:** 0
- **Exported components:** 1 activity (Compose PreviewActivity — debug-only, not in release)
- **Secrets found:** 0 actual secrets (only localization strings and secp256k1 curve constants)
- **Cleartext traffic:** Disabled
- **Backup:** Disabled (`allowBackup=false`, `fullBackupContent=false`)

### Custom Security Test Suite (34 tests, 34 passing)

| Test Class | Tests | Status | Coverage |
|------------|-------|--------|----------|
| `SeedIsolationTest` | 9 | PASS | NativeBridge API surface — no seed/key return methods |
| `ManifestSecurityTest` | 8 | PASS | Backup, exports, permissions, network config |
| `NetworkLeakTest` | 6 | PASS | DigiScopeClient, HubWebSocket, DigiIdManager — no seed refs |
| `NativeMemorySecurityTest` | 11 | PASS | C code secure_zero, BRKeyClean, volatile, /dev/urandom |

## Architecture Security Properties

### Seed Storage
- Mnemonic encrypted with AES-256-GCM via Android Keystore (TEE/Strongbox when available)
- Stored in SharedPreferences `dgb_wallet_seed` (MODE_PRIVATE)
- `allowBackup=false` prevents backup extraction
- Seed fingerprint (SHA-256 of mnemonic) stored separately for sync data management — not the seed itself

### JNI Boundary
- Raw seed crosses JNI boundary only during `createWallet()` and `recoverWallet()` — both accept the seed as input, never return it
- `signMessage()` returns `"address|base64signature"` — private key is derived, used, and cleaned within the C core
- `g_seed` (global seed buffer) is:
  - Zeroed on `lockSession()` via `secure_zero()`
  - Never passed to `NewStringUTF` or `SetByteArrayRegion`
  - `secure_zero` uses volatile pointer to prevent compiler optimization

### Network Isolation
- DigiScopeClient, HubWebSocket, and DigiIdManager never reference seed/mnemonic/entropy
- Only `address` and `signature` (compact recoverable sig) are sent over the network
- JSON payloads never contain seed-related fields
- No log statements reference seed material

### Manifest Configuration
- `android:allowBackup="false"`
- `android:fullBackupContent="false"`
- SyncService: `android:exported="false"`
- No content providers
- Network security config defined
- Minimal permissions: INTERNET, CAMERA, BIOMETRIC, FOREGROUND_SERVICE, NOTIFICATIONS

## Known Limitations

1. **Debug build analyzed** — PreviewActivity exported in debug APK (stripped in release)
2. **Certificate pinning** — Commented out in DigiScopeClient (TODO for deployment)
3. **unlockSession accepts raw 64-byte seed** — designed for Keystore-decrypted blob, but no validation that the bytes came from Keystore
4. **No root/emulator detection** — Wallet runs on rooted devices without warning
5. **FLAG_SECURE** — Applied to seed display screen but not verified for all sensitive screens

## Recommendations

1. **Enable certificate pinning** for api.digiscope.me before production release
2. **Add root detection** with user warning (not blocking — users may legitimately run on rooted devices)
3. **Audit FLAG_SECURE** coverage on all screens showing balance, addresses, or transaction details
4. **Release build scan** — Re-run MobSF on the signed release APK (ProGuard/R8 may affect findings)
5. **Penetration test** — Dynamic analysis with Frida to verify seed cannot be extracted at runtime

## Test Execution

```bash
# Run the full security test suite
./gradlew :core:testMainnetDebugUnitTest \
  --tests "*.SeedIsolationTest" \
  --tests "*.NativeMemorySecurityTest" \
  --tests "*.NetworkLeakTest" \
  --tests "*.ManifestSecurityTest"
```

## Files

```
security/
├── AUDIT-SUMMARY.md                    ← This file
├── reports/
│   └── mobsf-report.json               ← MobSF static analysis (full)
└── tests/ (in core/src/test/java/io/digibyte/core/security/)
    ├── SeedIsolationTest.kt            ← JNI API surface analysis
    ├── ManifestSecurityTest.kt         ← Manifest/config checks
    ├── NetworkLeakTest.kt              ← Network leak prevention
    └── NativeMemorySecurityTest.kt     ← C memory safety verification
```
