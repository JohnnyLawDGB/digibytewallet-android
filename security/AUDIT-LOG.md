# Security cycle log

Machine-read by `scripts/check-security-cycle.sh`. **Keep the marker line's format exactly** —
the gate parses it, and a reformatted line reads as "never audited".

<!-- LAST_AUDITED_VERSION_CODE: 40058 -->

The cycle runs **every 10 releases**. The gate fails a release build when the current
`versionCode` is 10 or more beyond the marker above.

## Why a gate rather than a reminder

The practice already existed and lapsed anyway. `AUDIT-SUMMARY.md` carries a v3.0.1 baseline and
a changed-surface audit at **v3.6.6 (2026-06-10)** — and then 52 v4 releases with nothing. Nobody
decided to stop; it simply never became urgent on any particular day. A calendar reminder would
have failed the same way, so the trigger is tied to the thing that actually moves: shipping.

Deferring is allowed. Recording the deferral is not optional — write it here with a reason, and
move the marker only when a cycle actually happened.

## What each cycle covers

Run `scripts/security-cycle.sh` for the automatable half; it prints a report to paste below.

| | |
|---|---|
| dependency CVEs | continuous, every CI build (`scripts/osv-scan.sh`) — not deferred to the cycle, because a new CVE arrives without anyone touching this repo |
| native hardening | PIE / NX / RELRO / stack canary / fortify on the shipped `.so` |
| embedded secrets | credential-shaped strings and unexpected hosts in the shipped dex |
| manifest posture | exported components, `allowBackup`, cleartext, permissions |
| R8 over-keep | did the keep rules leave more unobfuscated than intended? |
| changed surface | the manual half: diff since the last cycle across JNI boundary, native parsing, intents, crypto, network |

## Cycles

### v3.6.6 — 2026-06-10
Changed-surface audit, `v3.5.42 → v3.6.6`. See `AUDIT-SUMMARY.md`.

### v4.0.58 — 2026-08-25 (partial: automated half only)
First run of the automated checks, on the shipped v4.0.58 APK.

- **Dependencies:** 227 packages, **zero** known advisories. Detection verified against
  deliberately-vulnerable coordinates rather than trusted — log4j-core 2.14.1 returns 7,
  jackson-databind 2.9.0 returns 69.
- **Native hardening:** PIE, NX, **FULL RELRO**, stack canary all present; fortify active
  (`__memcpy_chk`, `__read_chk`, `__recvfrom_chk`).
- **Embedded secrets:** none. No API keys, tokens or credential-shaped strings.
- **Hosts in the dex:** 12, all expected — digiscope, digistamp, IPFS gateways, digibyte.org.
- **`allowBackup`:** false.
- **`android.permission.DUMP`:** appears in the merged manifest and is NOT requested — it is the
  guard on AndroidX WorkManager's diagnostics receiver, which is signature|privileged. Checked
  because it looked wrong at first glance; recorded so the next reader does not re-chase it.

The manual half of this cycle was completed the same day — see the entry below, which is what
advances the marker.

### v4.0.58 — 2026-08-25 (manual half: changed surface, MobSF, R8 over-keep)

Completes the cycle above. Changed surface reviewed across `v3.6.6 → v4.0.58` — 721 files, but
the security-relevant slice is 51: the JNI boundary, native parsing, the manifest, crypto, and
the network clients. **This is the entry that advances the marker to 40058.**

#### Scan provenance — read this before trusting a line below

MobSF ran against `app-mainnet-minifiedDebug.apk`, which reports `versionName 4.0.57`. The
`4.0.57 → 4.0.58` delta is asset display labelling and carries no security surface, so the
findings hold — but two consequences follow and both are recorded rather than smoothed over:

- MobSF's **"Application signed with debug certificate" (HIGH)** is an artifact of that build
  type, not a property of the release. `minifiedDebug` exists only to test obfuscation.
- With R8 on, scanner output arrives obfuscated (`C/AbstractC0047c.java`). Every finding below
  was de-obfuscated through `mapping.txt`. **Retaining mapping.txt per release is now
  load-bearing for security triage, not only for decoding crash reports.**

#### Findings

| # | Sev | What |
|---|-----|------|
| 1 | Medium | `jni_derive.c` zeroes a **caller-owned** JNI buffer |
| 2 | Low | R8 enum keep is broader than its own stated reason |
| 3 | Low | Digi-ID nonce logged at INFO, ships in release |
| 4 | Low | Two exported deep-link schemes with no intent handler |
| 5 | Info | A test JNI entry point is exported from the shipped `.so` |
| 6 | Info | Dead bread-wallet C sources still read as live JNI entry points |

**1 — `jni_derive.c` zeroes a buffer it does not own.** Every seed-taking function does
`secure_zero(seedRaw, seedLen)` and then `ReleaseByteArrayElements(..., JNI_ABORT)` (6 sites:
lines 218, 233, 306, 320, 415, 425, 498, 513, 547, 557, 571). `isCopy` is passed `NULL`, so the
code never learns whether `seedRaw` points at a private copy or at the live Java array. `JNI_ABORT`
protects the caller's array **only in the copy case**; in the direct-pointer case the zeroing has
already landed on the Java heap and cannot be undone.

That matters because `LegacySweepService.sweepFromSeed` loads the seed **once** and calls
`sweepOneProfile(seedBytes, …)` in a loop over profiles (`LegacySweepService.kt:109`), which
reaches `buildAndSignLegacySweep(seedBytes = seed, …)` (`:172`) on each pass. If ART hands back a
direct pointer, profile #1 wipes the shared seed and every later profile derives from 64 zero
bytes — signing with the wrong keys. Reachable through the foreign-seed sweep whenever a user has
funds under more than one derivation profile.

The dependence on unspecified JNI behaviour is **confirmed**; whether it currently bites on ART is
**not** — it was not reproduced on-device, and it must not be reported as if it were. The fix does
not require settling the question: copy the seed into a local buffer, zero the local, and release
the JNI array untouched. Correct either way, and it restores the ownership contract
`SeedProvider` already states ("the returned array is owned by the caller").

**2 — enum over-keep.** `-keepclassmembers enum * { …; public *; }` keeps the public members of
*every* enum. Its comment justifies this with `DisplayCurrency.valueOf` and with the `asset_source`
column — but `AssetSource` is an `object` of `String` constants (`AssetSource.kt:6`) and
`asset_source` is `TEXT` (`UtxoEntity.kt:17`), so half the stated reason describes something that
is not an enum. A source sweep finds exactly **one** enum round-tripped by name through storage:
`WalletViewModel.DisplayCurrency` (`WalletViewModel.kt:407`). The rule leaks the constant names of
23 of our enums — `SyncStage`, `CfRecoveryPolicy.Reason`, `PublishOutcome.Kind` — which are
descriptive by design. Narrowing `public *` to `DisplayCurrency` is a behaviour change and must be
verified by build + device run, not by reasoning.

**3 — Digi-ID nonce in the log.** `MainActivity.kt:366` logs the full challenge URI, nonce
included, at `Log.i`. There is no `-assumenosideeffects` rule, so it ships in release.
`DigiIdManager` already follows the opposite convention, logging only the domain and the response
code — this line is the outlier, not the norm. Exploiting it needs `READ_LOGS` or ADB, so severity
is low; the fix is one line and restores an existing convention.

**4 — exported schemes with no handler.** The manifest exports `digibyte:`, `digiid:` and
`digiscope:` as BROWSABLE into `MainActivity`, but `handleDigiIdIntent` acts only on `digiid://`.
`digiscope:` has no handler anywhere in the app. `digibyte:` is consumed only by the QR scanner
(`QrScannerScreen.kt:338`) and the digistamp WebView (`DigistampScreen.kt:119`) — never from an
intent — so a payment URI arriving from a browser launches the app and is silently dropped. No
sink means no injection path, so this is exported surface rather than a vulnerability; it is also
a real functional gap worth deciding about deliberately.

**5 — test hook in the shipped library.** `libcore-lib.so` exports
`Java_io_digibyte_native_1core_PeerTest_testPeerDiscovery` from `jni_test.c`. Its Java counterpart
lives in `androidTest` and is absent from the shipped dex (0 hits in `mapping.txt`). The function
only reads compiled-in constants — magic number, port, seed count, checkpoint count — and returns
a bitmask; it touches no wallet state and no key material. Benign, but it is test code in a
production binary, and excluding `jni_test.c` from the release target costs nothing.

**6 — dead bread-wallet C sources.** `JNIKey.c`, `JNIBase58.c` and `JNIBIP32Sequence.c` are
commented out of `native/CMakeLists.txt:127-129` and confirmed absent from the shipped `.so`.
They still *read* as live JNI entry points: they were the top hits in this cycle's own
missing-null-check sweep, and they carry unbalanced `GetByteArrayElements` calls that would be
genuine leaks if they were ever compiled. Deleting them removes a trap for the next auditor.

#### R8 over-keep check — the result the keep rules were meant to produce

| | |
|---|---|
| our classes in the mapping | 961 |
| renamed | 941 (**98%**) |
| kept | 20 |

Every one of the 20 is structurally required: `NativeBridge` / `NativeCallback` (JNI resolves by
name), the 11 Room entities plus `WalletDatabase`/`_Impl` (column and generated-code binding), and
`DigiByteApp` / `MainActivity` / `SyncService` / `SyncWorker` (the OS instantiates manifest
components by name). **No crypto, seed, PIN or Digi-ID class kept its name.**

Seeding is not the same as surviving, and the distinction was checked rather than assumed: 707
`io.digibyte` entries appear in `seeds.txt`, but the classes behind them were still renamed —
`AssetViewModel → z5.H`, `DigiIdViewModel → B5.r`, `SyncStage → o5.k`, `Aggregation → e5.a`. Those
are member-level keeps, which is what was intended. Finding 2 is the one place the intent and the
rule genuinely diverge.

#### MobSF — triage of everything it raised

Security score 66. Both HIGH findings resolve to something other than our code:

- **debug certificate** — the `minifiedDebug` build type, as recorded above.
- **CBC with PKCS5/PKCS7 padding** — de-obfuscates into `androidx.camera.core` (72 classes map
  into that package). **Our code contains no CBC at all**: every `Cipher.getInstance` in the repo
  is `AES/GCM/NoPadding` (`KeyStoreManager.kt:60,68`, `AppModule.kt:81`), and the Keystore spec
  sets `BLOCK_MODE_GCM` with `ENCRYPTION_PADDING_NONE`.

- **"insecure Random Number Generator"** — all 8 sites are library code: `androidx.camera.core`,
  `HandlerScheduledExecutorService`, `okhttp3.internal.ws.RealWebSocket`/`WebSocketWriter`, and a
  CameraX lambda in `QrScannerScreenKt`. None generates key material. Ours: wallet entropy comes
  from `/dev/urandom` read directly, with an explicit note that `BRRand` is not cryptographic, a
  short-read check, and `secure_zero` on the failure path (`jni_wallet.c:85-99`); the Kotlin side
  uses `SecureRandom` for the DB passphrase, the PIN salt and Dandelion.
- **hardcoded secrets / IP disclosure / raw SQL** — the known baseline: curve constants, the 16
  hardcoded CF oracle IPs, Room `@Query`. See `reference/mobsf-false-positive-baseline`.
- **WorkManager + ProfileInstaller receiver permission levels** — AndroidX components, the same
  `android.permission.DUMP` guard already triaged in the entry above.

MobSF's 6 SECURE findings are worth naming because they are the controls this app depends on:
cleartext disallowed globally *and* per-domain, certificate pinning present **with no expiry**,
tapjacking protection, and no privacy trackers.

Pin expiry deserves a note rather than silence. No expiry means the pins never quietly stop being
enforced — the right call for security — but it also means a rotation at `assets.digistamp.co`
breaks the app rather than degrading it. This has already happened once on a different host
(`project/digiasset-metadata-offline-stale-pin`), so it is an operational risk with precedent, not
a hypothetical. The platform pin-set and `DigistampPins` must be rotated together.

#### Verified clean

- **Manifest**, `v3.6.6 → HEAD`: adds `ACCESS_NETWORK_STATE` (for the network-regained reconnect
  callback) and `largeHeap`. **No new exported component, no new intent-filter.**
- **Seed and key hygiene** in the reviewed native code: `secure_zero` on every exit path in
  `jni_wallet.c` (17 sites) and `jni_derive.c`; `BRKeyClean` on every `BRKey` on every path
  including the error paths; `JNI_ABORT` on release so nothing is copied back. Finding 1 is about
  *which buffer* is zeroed, not about whether zeroing happens.
- **PIN rate-limit** (`PinManager`, +270 lines since v3.6.6) closes the CRITICAL-1 residual:
  3 free attempts then 1/5/30/60-minute cooldowns, a backward-clock guard that forces a maximum
  lockout, counters held in `dgb_pin_store` **specifically so `StaleDataWiper` cannot reset them**,
  and a `pin_wipe_pending` backstop so a kill mid-wipe completes on next launch.
- **JNI array/string handling** in the live files is balanced once `Get*Region` calls are excluded
  from the count. `jni_asset_send.c` cross-checks every array length against `nIn`/`nOut` before
  use and bounds every hex decode with `sizeof`.
- **Attack surface removed** since v3.6.6: `wallet.c` (−1110), `PeerManager.c` (−534), `core.c`
  (−259), plus the bread resource excision.

Missing `NULL` checks after `GetStringUTFChars`/`Get*ArrayElements` exist in the live files
(`jni_asset_send.c:140,152,194,211`, `jni_derive.c:150,211,299,408,430,449,504`, `jni_peer.c:699,734`,
`jni_transaction.c:618,700`, `jni_wallet.c:584`). These return `NULL` only under allocation
failure, and the inputs come from our own Kotlin rather than from a peer, so this is robustness
rather than an attack path — but this app has a documented OOM history and ships `largeHeap=true`,
so it is not purely theoretical. Recorded, not fixed.

#### Disposition

Findings 1–6 are **open**. Recording them here is what the cycle is for; none of them blocks a
release on its own, and finding 1 is the one that should be scheduled rather than left to drift,
because its failure mode is silent wrong-key signing on a funds path.

#### Trend against the last cycle

| | v3.6.6 | v4.0.58 |
|---|---|---|
| MobSF score | 68 | 66 |
| HIGH | 1 | 2 |
| WARNING | 10 | 11 |
| SECURE | 5 | 6 |

Both cycles carry the same debug-certificate HIGH for the same reason. The **new** HIGH is the
CameraX CBC usage — it entered with a dependency, not with our code, which is the useful thing the
comparison shows: across 52 releases the score moved two points and neither point came from
wallet logic. The new SECURE is the `assets.digistamp.co` domain pin added in v4.0.51.

Reports for both are in `security/reports/` (`mobsf-report-v4.0.58.json`, scorecard alongside).
