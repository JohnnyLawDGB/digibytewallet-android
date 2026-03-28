# DigiByte Wallet v3.0.3-beta — Test Report

**Date:** 2026-03-28
**APK SHA-256:** `6ffef7a6b6a50431469b424e68fcc0666182ed52dd67192f212ec9eca30e214d`
**Device:** Samsung SM-N950U (Galaxy Note 8, Android 9, API 28)

## Test Summary

| Suite | Passed | Failed | Total |
|-------|--------|--------|-------|
| Unit Tests | 120 | 0 | 120 |
| Security Tests | 34 | 0 | 34 |
| Functional (API) | 10 | 1 | 11 |
| MobSF Static Analysis | — | — | Clean |
| **Total** | **164** | **1** | **165** |

## Unit Tests (120/120)

```bash
./gradlew testMainnetDebugUnitTest
```

| Test Class | Tests | Status |
|------------|-------|--------|
| DigiAssetDecoderTest | 26 | PASS |
| BitReaderTest | 15 | PASS |
| CidVerifierTest | 11 | PASS |
| NativeMemorySecurityTest | 11 | PASS |
| SeedIsolationTest | 9 | PASS |
| DigiIdRequestTest | 8 | PASS |
| ManifestSecurityTest | 8 | PASS |
| DigiAssetRulesTest | 7 | PASS |
| CoinSelectorTest | 7 | PASS |
| IpfsClientTest | 7 | PASS |
| NetworkLeakTest | 6 | PASS |
| PriceProviderTest | 5 | PASS |

## Security Tests (34/34)

```bash
./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*"
```

| Test Class | Tests | Coverage |
|------------|-------|----------|
| SeedIsolationTest | 9 | JNI API surface — no methods return seed/key material |
| NativeMemorySecurityTest | 11 | C code: secure_zero, BRKeyClean, volatile+barrier, /dev/urandom |
| ManifestSecurityTest | 8 | allowBackup=false, no exports, minimal permissions |
| NetworkLeakTest | 6 | No seed references in HTTP/WS/JSON code |

## MobSF Static Analysis

- **Trackers detected:** 0
- **Secrets leaked:** 0 (only secp256k1 curve constants and localization strings)
- **Exported components:** 1 (Compose PreviewActivity — debug build only)
- **Backup:** Disabled
- **Cleartext traffic:** Disabled

Full report: `security/reports/mobsf-report.json`

## Functional Tests (10/11)

Tested against live DigiScope backend (`api.digiscope.me`) and on-chain via DigiByte node.

| # | Test | Result | Notes |
|---|------|--------|-------|
| 1 | On-chain balance scan | PASS | 2.00 DGB confirmed at `dgb1q3e7w9u...` (block 23,195,908) |
| 2 | Hub channels (8 channels) | PASS | All seeded channels returned |
| 3 | Hub profile (JLawTest) | PASS | custom_username displayed correctly |
| 4 | Forum — create thread | PASS | Thread ID 2 created in Support channel |
| 5 | Forum — reply to thread | PASS | Reply ID 1 |
| 6 | Forum — read back thread + reply | PASS | Title + 1 reply verified |
| 7 | Forum — upvote | PASS | `{"upvoted": true}` |
| 8 | Chat messages (General) | PASS | 3 messages retrieved |
| 9 | Handle availability | CHECK | Returns `available:true` for existing custom_username — handle vs custom_username column mismatch |
| 10 | SPV sync complete | PASS | `bridge_syncStopped: sync complete` |
| 11 | Digi-ID session validity | PASS | HTTP 200 on authenticated endpoint |

## What Has Been Tested On-Device

- Wallet creation (new mnemonic generation)
- Wallet recovery (restore from mnemonic)
- PIN entry and biometric unlock
- QR code scanning (Digi-ID and DigiByte addresses)
- Digi-ID authentication flow (scan → confirm → biometric → sign → callback)
- DigiScope Hub login (auto-JWT capture)
- Community Hub chat (Enigma AI bot responded)
- Community Hub forum (thread creation, display)
- SPV sync to chain tip (block 23.2M+)
- Block/peer persistence across restarts
- Transaction detection via bloom filter rescan (2 DGB found)
- DigiRunner mini-game (sprint, jump, coin collection, BTC obstacles)
- Balance display and "Verifying transactions..." UX

## What Has NOT Been Fully Tested

> **These features need thorough testing before production. Use at your own risk.**

| Feature | Status | Risk |
|---------|--------|------|
| **Send DGB** | Built, not tested on mainnet | MEDIUM — could lose funds if tx construction is wrong |
| **Receive DGB** | Partially tested (2 DGB received) | LOW — address generation verified |
| **DigiAsset send/receive** | Built, NOT tested | HIGH — UTXO protection untested on real assets |
| **Wallet restore on new device** | Not tested | MEDIUM — seed backup/restore path unverified |
| **Tor routing** | Built, basic connection tested | MEDIUM — privacy guarantees unverified |
| **Multi-device sync** | Not tested | LOW — SPV is stateless per-device |
| **Large balance handling** | Not tested | LOW — coin selection tested in unit tests only |
| **Fee estimation** | Built, not tested against real mempool | MEDIUM |
| **Edge cases** | Network loss mid-sync, low battery, app kill during tx | UNKNOWN |

## Security Audit Status

Full audit: `security/AUDIT-SUMMARY.md`

| Finding | Severity | Status |
|---------|----------|--------|
| KeyStore key auth-bound | CRITICAL | **FIXED** — `setUserAuthenticationRequired(true)` |
| Digi-ID callback domain validation | CRITICAL | **FIXED** — host checked against URI domain |
| `g_seed` process-lifetime global | CRITICAL | **OPEN** — inherent to SPV model, documented |
| Seed as Java String on heap | CRITICAL | **OPEN** — needs ByteArray refactor |
| Certificate pinning | HIGH | **FIXED** — leaf + intermediate CA pins |
| HTTP callbacks blocked | HIGH | **FIXED** |
| Address redacted from logs | HIGH | **FIXED** |
| Seed String on JVM heap | HIGH | **OPEN** — same root as CRITICAL-3 |
| secure_zero LTO-proof | MEDIUM | **FIXED** — compiler barrier added |
| Response body redacted | MEDIUM | **FIXED** |
| Non-atomic wipe | MEDIUM | **FIXED** — prefs cleared before key deletion |
