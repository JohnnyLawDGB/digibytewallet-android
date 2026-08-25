# Security cycle log

Machine-read by `scripts/check-security-cycle.sh`. **Keep the marker line's format exactly** —
the gate parses it, and a reformatted line reads as "never audited".

<!-- LAST_AUDITED_VERSION_CODE: 30606 -->

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

**Not yet done for this cycle:** the manual changed-surface review (`v3.6.6 → v4.0.58` is a very
large diff), MobSF re-scan against the now-obfuscated APK, and a jadx pass checking whether the
R8 keep rules over-kept. The marker is therefore NOT advanced by this entry.
