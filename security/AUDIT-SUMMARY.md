# DigiByte Wallet v3.0.1 — Security Audit Summary

**Date:** 2026-03-28
**Version:** v3.0.1 (phase1-modernization branch)
**Package:** io.digibyte
**APK SHA256:** `5dabf3a5f74d9aed814e3827c0b1e8fc3174f2deeaebd59730cd99a460aa6c95`

## Overview

This audit covers the security posture of the DigiByte Android wallet with focus on seed isolation, JNI boundary safety, network leak prevention, and manifest configuration. The wallet stores a BIP39 mnemonic encrypted with AES-256-GCM via Android Keystore.

## Findings Summary

| Severity | Count | Status |
|----------|-------|--------|
| CRITICAL | 4 | 2 Remediated, 2 Open |
| HIGH | 4 | Open — remediation planned |
| MEDIUM | 4 | Open |
| LOW/INFO | 5 | Informational (positive findings) |

## CRITICAL Findings

### CRITICAL-1: KeyStore key not bound to user authentication
**File:** `KeyStoreManager.kt:44`

`setUserAuthenticationRequired(false)` means the AES-256-GCM key protecting the seed is accessible without hardware-backed biometric/PIN challenge. Any code path that reaches `keyStoreManager.decrypt()` can silently decrypt the seed. The comment says auth is handled "at app level" but app-level checks are bypassable.

**Fix:** Set `.setUserAuthenticationRequired(true)` and use `BiometricPrompt` to get a `CryptoObject` wrapping the cipher before each decrypt call.

### CRITICAL-2: `g_seed` is a process-lifetime global
**File:** `jni_wallet.c:140-141`

The 512-bit derived seed is copied into the process-global `g_seed[64]` during `createWallet`/`recoverWallet` and persists until `lockSession`. Any native code in the process can read it. On a rooted device, `g_seed` is readable via `/proc/<pid>/mem`.

**Fix:** Minimize the window — zero `g_seed` after peer manager is created and only re-derive for signing operations. Design-level limitation of the BRWallet SPV model.

**Status:** Remediated — `g_seed` is now `static` to `jni_wallet.c` with controlled accessor API (`seed_sign_transaction`, `seed_derive_key`, `seed_is_valid`, `seed_zero`). External compilation units cannot access the seed directly. 7 new security tests verify encapsulation.

### CRITICAL-3: Seed flows through JVM heap as un-zeroed String
**File:** `WalletManager.kt:207`, `jni_wallet.c:241`

`loadSeed()` returns `String(decrypted, Charsets.UTF_8)` — Java `String` is immutable and may be interned. The plaintext mnemonic can persist in GC heap indefinitely. `unlockSession` accepts a raw 64-byte seed as `ByteArray`, also not zeroed after JNI call.

**Fix:** Change `loadSeed()` to return `ByteArray`, add `createWalletFromBytes` JNI variant, call `byteArray.fill(0)` immediately after JNI returns.

**Status:** Remediated — `loadSeed()` returns `ByteArray` (zeroed after use via `fill(0)` in `finally` blocks). `createWalletFromBytes`/`recoverWalletFromBytes` JNI functions accept `jbyteArray` with `secure_zero()` on the C stack copy. The mnemonic never becomes an immutable Java `String` on the restore path. 42 security tests passing (8 new).

### CRITICAL-4: Digi-ID callback URL is attacker-controlled
**File:** `DigiIdManager.kt:50-57`

The `callbackUrl` is parsed from the scanned QR code. The wallet POSTs `{uri, address, signature}` to any HTTPS URL without domain validation. A malicious QR can harvest wallet address proofs from arbitrary users.

**Fix:** Validate that `callbackUrl` host matches the domain in the `digiid://` URI. Block or prominently warn on `u=1` (HTTP) callbacks.

## HIGH Findings

### HIGH-1: Wallet address logged on every sign operation
**File:** `jni_wallet_sign.c:153`, `DigiIdManager.kt:36`

BIP32 index-0 address logged at INFO on every sign. Full `signResult` logged at ERROR on parse failure. Address is a permanent identifier linkable to all on-chain activity.

**Fix:** Remove address from log, log only status codes and lengths.

### HIGH-2: Certificate pinning commented out
**File:** `DigiScopeClient.kt:38-46`

Signatures and JWT tokens sent to `api.digiscope.me` over unpinned TLS. MITM via corporate proxy or rogue CA can intercept.

**Fix:** Extract leaf/intermediate CA pin and enable `CertificatePinner`.

### HIGH-3: HTTP callbacks allowed with no warning
**File:** `DigiIdRequest.kt:26`, `DigiIdManager.kt:57`

`u=1` Digi-ID URIs construct `http://` callback URLs. Signature sent in cleartext.

**Fix:** Block HTTP callbacks or require explicit user confirmation with prominent warning.

### HIGH-4: `loadSeed()` returns plaintext mnemonic as `String`
**File:** `WalletManager.kt:207`

Same root cause as CRITICAL-3. Java `String` has no controlled lifetime.

**Fix:** Use `ByteArray` throughout, zero after use.

## MEDIUM Findings

### MEDIUM-1: `secure_zero` may be elided by LTO
**File:** `jni_bridge.h:92`

The `volatile` cast prevents optimization in the current TU but is not guaranteed safe with Link-Time Optimization across NDK upgrades.

**Fix:** Replace with `explicit_bzero()` (available in Bionic since API 17).

### MEDIUM-2: Full server response logged unconditionally
**File:** `DigiIdManager.kt:61`

Response body from potentially attacker-controlled servers logged at INFO level.

**Fix:** Log only status code, not response body.

### MEDIUM-3: No UI distinction for attacker-controlled callback URL
**File:** `DigiIdConfirmScreen.kt:263`

The confirmation screen displays the raw callback URL but doesn't highlight that it could differ from the site domain.

### MEDIUM-4: Non-atomic wallet wipe
**File:** `WalletManager.kt:177`

`wipeWallet()` deletes KeyStore key before clearing SharedPreferences. If process is killed between these steps, encrypted seed remains but key is gone — permanent funds loss.

**Fix:** Clear prefs first, then delete KeyStore key.

## Positive Findings (INFO)

| Finding | Status |
|---------|--------|
| `allowBackup="false"` + `fullBackupContent="false"` | PASS |
| Game module has zero access to wallet internals (no `:core` dependency) | PASS |
| SyncService `exported="false"`, no ContentProviders or BroadcastReceivers | PASS |
| `FLAG_SECURE` on seed display/verify/view screens | PASS |
| 0 trackers detected (MobSF) | PASS |
| Entropy sourced from `/dev/urandom`, not `BRRand` | PASS |
| `secure_zero` uses volatile pointer, `BRKeyClean` called after signing | PASS |
| DigiScopeClient/HubWebSocket/DigiIdManager never reference seed material | PASS |

## Automated Test Suite (42 tests, 42 passing)

| Test Class | Tests | Coverage |
|------------|-------|----------|
| `SeedIsolationTest` | 11 | NativeBridge API surface — no seed/key return methods, ByteArray variants |
| `ManifestSecurityTest` | 8 | Backup, exports, permissions, network config |
| `NetworkLeakTest` | 6 | HTTP/WS/JSON payloads contain no seed references |
| `NativeMemorySecurityTest` | 17 | C code secure_zero, BRKeyClean, volatile, /dev/urandom, g_seed encapsulation |

## MobSF Static Analysis

- **Trackers detected:** 0
- **Secrets found:** 0 actual secrets (secp256k1 constants, localization strings only)
- **Exported components:** 1 debug-only (Compose PreviewActivity — stripped in release)
- **Cleartext traffic:** Disabled
- **Backup:** Disabled

## Remediation Priority

1. **CRITICAL-1** — Enable `setUserAuthenticationRequired(true)` on KeyStore key (single-line change, highest impact)
2. **CRITICAL-4** — Validate Digi-ID callback domain matches URI host (prevents remote signature harvesting)
3. **HIGH-2** — Enable certificate pinning for api.digiscope.me
4. **HIGH-3** — Block or warn on HTTP Digi-ID callbacks
5. **CRITICAL-3/HIGH-4** — Refactor `loadSeed()` to use `ByteArray` with explicit zeroing
6. **MEDIUM-2** — Redact server response from logs

## Test Execution

```bash
# Run the full security test suite
./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*"
```

## Files

```
security/
├── AUDIT-SUMMARY.md                    ← This file
├── reports/
│   └── mobsf-report.json               ← MobSF static analysis (full)
└── tests/ (in core/src/test/java/io/digibyte/core/security/)
    ├── SeedIsolationTest.kt
    ├── ManifestSecurityTest.kt
    ├── NetworkLeakTest.kt
    └── NativeMemorySecurityTest.kt
```
