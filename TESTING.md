# DigiByte Android Wallet — Test Suite

**Version:** v3.10.27 (`app/build.gradle.kts` `versionName` — source of truth)
**Branch:** `develop` (canonical integration branch)
**Test devices:** Samsung SM-N950U (Galaxy Note 8, Android 9, API 28); Galaxy S25 Ultra (Android 15, API 35, 16 KB page-size coverage)

This document describes the repeatable test suite that ships in the repo. It is a description of *what tests exist and how to run them*, not a point-in-time pass/fail report for any single build.

## Test Tiers at a Glance

| Tier | Location | Files | `@Test` methods | Runs on |
|------|----------|-------|-----------------|---------|
| JVM unit tests | `app/src/test`, `core/src/test` | 43 | 348 | Host JVM (no device) |
| — security subset | `core/src/test/.../security` | 4 | 42 | Host JVM (no device) |
| Instrumented tests | `core/src/androidTest`, `native/src/androidTest` | 19 | 73 | Device / emulator |
| Native host KATs | `native/src/test/host` | 22 dirs (21 runners) | — (C, exit 0/1) | Host with clang |
| Pre-publish suite | `scripts/pre-publish-test.sh` | 8 scenarios (A–H) | — (UI/emulator) | Emulators API 28/33/34/35 |

Total Kotlin `*Test.kt` files: **62** (348 unit + 73 instrumented `@Test` methods). Counts above are current as of v3.10.27; re-derive with the `grep -c '@Test'` / `find` commands rather than trusting the numbers if the suite has grown.

## JVM Unit Tests (host, no device)

```bash
# All unit tests (mainnet debug variant)
./gradlew testMainnetDebugUnitTest
```

43 test files, 348 `@Test` methods across `app/src/test` (9 files, 64 methods) and `core/src/test` (34 files, 284 methods). Coverage spans:

- **DigiAsset v2** — decoder, encoder, decoder fuzz, asset rules, bit reader, coin selector (+ security variant), history backfill
- **IPFS / metadata** — CID verifier, IPFS client, asset-metadata sanitization
- **Recovery / sweep** — legacy derivation vectors, foreign-seed sweep selection, sweep destination/outcome/inputs, amount-provenance gate, UTXO source, scan-classify
- **Wallet core** — BIP84 derivation, coin selection, price provider, outgoing-tx-store override, post-upgrade reconciler
- **Sync / network policy** — sync frontier, BIP158 watchdog policy, block-persistence policy, peer-services policy, foreground-readiness policy, DigiScope pins, custom node, dandelion broadcast policy, Tor manager
- **UI logic** — DigiDollar format + send validation, asset image resolver, external-URL safety, bug-report link
- **Security** — see below

## Security Suite (subset of the unit tests)

```bash
# Security tests only (host JVM)
./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*"
```

**42 security tests** across 4 files in `core/src/test/java/io/digibyte/core/security/`:

| Test class | `@Test` | Coverage |
|------------|---------|----------|
| `NativeMemorySecurityTest` | 17 | C `secure_zero`, `BRKeyClean`, volatile + compiler barrier, `/dev/urandom`, `g_seed` encapsulation |
| `SeedIsolationTest` | 11 | JNI API surface — no method returns seed/key material; accessor-only `g_seed` |
| `ManifestSecurityTest` | 8 | `allowBackup=false`, no exported components, minimal permissions |
| `NetworkLeakTest` | 6 | No seed references in HTTP / WebSocket / JSON code paths |

(Instrumented security tests — `KeyStoreManagerTest`, `PinManagerTest` — live under `core/src/androidTest/.../security` and are counted in the instrumented tier, not the 42.)

## Instrumented Tests (device / emulator)

Require a connected device or emulator (`adb devices`). 19 files, 73 `@Test` methods:

- `core/src/androidTest` (5 files, 31 methods) — Room DB migration, `TransactionDao`, `UtxoDao`, `KeyStoreManager`, `PinManager`
- `native/src/androidTest` (14 files, 42 methods) — JNI bridge / asset / proxy, legacy sweep (derivation, amount guard, SegWit KAT, signed-tx KAT), Taproot (receive address, watch-set, sign transaction, reload balance), Universal Restore, wallet birth checkpoint, peer

## Native Host KATs (C, run on host with clang)

Known-answer tests under `native/src/test/host/`. Each KAT is a self-contained directory that compiles the **real** submodule C sources directly out of the tree with `clang` (representative of the NDK build) and exits `0` on all-pass / `1` on any failure. There is no aggregate runner — run each KAT's `run.sh` individually:

```bash
# Example: BIP-340 Schnorr signing KAT
./native/src/test/host/bip340_kat/run.sh
```

**22 KAT directories** (21 have a `run.sh`; `gcs_match_kat` is a `*_main.c` source without a runner):

- **Address / bech32** — `bech32m_kat`, `taproot_addr_kat`
- **Taproot / BIP-340/341** — `bip340_kat` (Schnorr), `bip341_sighash_kat`, `bip341_sign_kat`, `bip341_signtx_kat`
- **BIP-86 key derivation** — `bip86_derivation_kat`, `bip86_privkey_kat`
- **Compact filters (BIP157/158)** — `cf_confirm_kat`, `cf_gate_kat`, `gcs_match_kat`
- **Peer / network** — `peer_keepalive_kat`, `peer_penalty_kat`, `network_switch_kat`, `watched_addr_kat`
- **Transactions / fees** — `tx_fee_vsize_kat`, `legacy_gap_uaf_kat`
- **DigiDollar** — `digidollar_addr_encode_kat`, `digidollar_decode_kat`, `digidollar_send_kat`, `digidollar_realtx_kat`, `digidollar_wallet_kat`

## Pre-Publish Suite (emulator, API 28/33/34/35)

```bash
./scripts/pre-publish-test.sh              # all API levels
./scripts/pre-publish-test.sh 33           # only API 33
./scripts/pre-publish-test.sh 33 --skip-build
./scripts/pre-publish-test.sh --test D     # single scenario
```

8 scenarios run across emulators for API 28, 33, 34, 35 (AVDs `dgb-test-api28/33/34/35`). Results are written to `test-results/<timestamp>.txt`.

| Scenario | Description |
|----------|-------------|
| A | Fresh install, no lock screen |
| B | Fresh install, with lock screen |
| C | Upgrade from previous version (`test-fixtures/previous-release.apk`) |
| D | Wallet creation flow |
| E | Wallet recovery flow |
| F | Sync without ANR (compact-filter sync to tip) |
| G | Send transaction (SKIP on emulator — needs a funded wallet / physical device) |
| H | 60-second stability soak (crash watch) |

The suite must pass before any release tag. Sync in scenario F is compact-filter-only (BIP157/158) — the bloom wire path and the Sync Mode toggle were removed across v3.10.5–3.10.15.

## Static Analysis (MobSF)

MobSF report: `security/reports/mobsf-report.json` (versioned snapshots alongside it, e.g. `mobsf-report-v3.6.6.json`, `mobsf-scorecard-v3.6.6.json`).

Baseline expectations (see the MobSF false-positive baseline notes): 0 trackers; secrets flagged are only secp256k1 curve constants / localization strings; the single "exported component" is the Compose `PreviewActivity` (debug build only); backup disabled; cleartext traffic disabled.

## Security Audit

Authoritative audit: **`security/AUDIT-SUMMARY.md`** (do not maintain a second copy of the finding statuses here). Current CRITICAL dispositions:

- **CRITICAL-1** — Resolved as designed: KeyStore key is *not* bound to user authentication (`setUserAuthenticationRequired` removed to avoid cross-API crashes); the app enforces its own PIN lock. **Residual (Phase 2):** no Keystore user-auth binding and no PIN rate-limit — a compromised app process can decrypt the seed without a device unlock.
- **CRITICAL-2** — Resolved: `g_seed` is `static` to `jni_wallet.c` with an accessor-only API (`seed_sign_transaction`, `seed_derive_key`, `seed_is_valid`, `seed_zero`).
- **CRITICAL-3** — Resolved: `loadSeed()` returns a `ByteArray` zeroed after use; `createWalletFromBytes` / `recoverWalletFromBytes` accept `jbyteArray` with `secure_zero()` on the C stack. The mnemonic never becomes an immutable JVM `String` on the restore path.
- **CRITICAL-4** — Resolved: Digi-ID callback domain validated against the URI host; HTTP (`u=1`) callbacks blocked. **Residual (Phase 2):** Digi-ID still signs with the first wallet address (`m/44'/20'/0'/0/0`) rather than an isolated subtree.

A bug bounty program (up to 100K DGB) covers v3.5.31+; report to `security@digiscope.me`.
