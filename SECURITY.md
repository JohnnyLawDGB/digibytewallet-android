# Security Policy

We take the security of the DigiByte Wallet seriously. This document
explains how to report vulnerabilities and what you can expect in return.

## Reporting a Vulnerability

**Please do not open public GitHub Issues for security findings.** Public
issues are visible to everyone, including users who haven't yet upgraded.

Instead, choose one of:

1. **Email** `security@digiscope.me` with the subject prefix `[BOUNTY]`
   if you're reporting under the bounty program, or `[SECURITY]` for
   non-bounty disclosures.

2. **GitHub Security Advisory** —
   <https://github.com/JohnnyLawDGB/digibytewallet-android/security/advisories/new>

We acknowledge reports within 3 business days and provide a triage
verdict within 7 business days.

## Bug Bounty Program

Verifiable security vulnerabilities in the latest release are eligible
for cash rewards in DGB, up to **100,000 DGB** for critical findings.

See [BUG-BOUNTY.md](./BUG-BOUNTY.md) for:

- Reward tiers and severity rubric
- In-scope assets and out-of-scope items
- Submission requirements
- Disclosure timeline
- Safe harbor language

## Supported Versions

| Version | Status |
|---------|--------|
| latest released tag | ✅ supported, in scope |
| any earlier tag | ❌ not supported, please upgrade and re-test |

The latest tag is always linked at
<https://github.com/JohnnyLawDGB/digibytewallet-android/releases/latest>.

## Scope Quick Reference

**In scope:**

- Native C core (`digibytewallet-core` submodule)
- JNI bridge layer
- Android application code (Kotlin + native)
- Key management and storage (Android Keystore, BIP39 seed handling)
- Transaction building and signing
- Network communication (SPV peers, IPFS, digiscope.me API)
- Address validation and derivation
- DigiAsset decoder, M3 parent-walk, asset send flow
- Tor integration

**Out of scope (closed without payout):**

- UI/UX confusion that doesn't lead to a security harm
- Spelling, grammar, accessibility, performance issues without
  a security implication
- Third-party dependency advisories without a working exploit chain
- Findings against the development branch fixed in the latest release
- Issues requiring physical device access, root, ADB-over-USB, or a
  side-loaded malicious app
- DigiByte Core full-node software (report to
  [DigiByte-Core/digibyte](https://github.com/DigiByte-Core/digibyte))

A complete out-of-scope list with examples is in [BUG-BOUNTY.md](./BUG-BOUNTY.md).

## Safe Harbor

Researchers acting in good faith — making reasonable efforts to avoid
data destruction, service disruption, or privacy violations — will not
face legal action from us. Full safe-harbor language in
[BUG-BOUNTY.md](./BUG-BOUNTY.md).

## Past Audits

- **2026-03-28** — Internal audit, 4 CRITICALs (3 remediated, 1 design-level
  documented). See `security/AUDIT-SUMMARY.md`.
- **2026-05-02** — MobSF v3.5.30 re-scan, score 67/100, one real finding
  fixed in v3.5.31 (insecure RNG in seed-verify quiz → SecureRandom).
  Full triage in `security/AUDIT-SUMMARY.md`.
