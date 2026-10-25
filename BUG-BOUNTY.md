# HoldFast Wallet: Bug Bounty Program

**Effective:** 2026-05-03
**Maximum reward:** 100,000 DGB
**Bounty target:** **HoldFast Wallet 4.1.1** (`app.aroundtheblock.wallet`), from its release date.
4.1.0 (published as ATB Wallet for DigiByte (DGB)) stays in scope for **14 days** after that date
under the overlap rule below. Before you start, read "Known issues being addressed for 4.1.2"
below: those are already on our list.

This is a small, self-funded, open-source project. Rewards are the ceilings below and most
reports earn named credit rather than payment; please read the tiers before spending time on a
report.

This program rewards security researchers who report verifiable vulnerabilities
in HoldFast Wallet (`app.aroundtheblock.wallet`), the Android wallet published by
Around The Block LLC. The same app was formerly named ATB Wallet for DigiByte (DGB), and
DigiByte Mobile in v4.0.88 and v4.0.89; the package is unchanged and each rename updated it in
place. It was previously published as DigiByte Wallet (`io.digibyte`), whose final release was
v4.0.87.2. HoldFast Wallet is independent: it is not an official DigiByte app. The wallet is a non-custodial, sovereignty-first
SPV wallet that holds user funds — bugs that compromise user funds, secrets,
or privacy are taken seriously and rewarded accordingly.

---

## In Scope

### Application

**The wallet is now HoldFast Wallet, `app.aroundtheblock.wallet`.** The old app,
`io.digibyte`, reached its final release at **v4.0.87.2 on 2026-10-10** and is **out of
scope**: it receives no further fixes, so a report against it cannot be acted on. Several
reports have already been closed for testing a retired or frozen version. Test the current
HoldFast Wallet release and name its exact version.

| Asset | Where to get it |
|-------|-----------------|
| HoldFast Wallet from Google Play (`app.aroundtheblock.wallet`) | https://play.google.com/store/apps/details?id=app.aroundtheblock.wallet |
| `aroundtheblock-wallet-v4.1.1.apk` (release signed, same key as Play) | https://github.com/JohnnyLawDGB/aroundtheblock-wallet-releases/releases/tag/v4.1.1 |
| Source code | https://github.com/JohnnyLawDGB/digibytewallet-android at the latest release tag (`develop` follows it). The `phase1-modernization` branch is frozen at v3.9.2 and out of scope. |
| C core (submodule) | https://github.com/JohnnyLawDGB/digibytewallet-core |

### DigiScope services the app uses

The app signs in to and talks to DigiScope at `api.digiscope.me` (the Hub, Digi-ID sign-in,
quick login, asset metadata). The **server side of those services is in scope**: sign-in and
session handling, taking over another user's account, reading or changing another user's data,
and anything that moves value the service holds for its users. These are scored like app
findings, by the value actually at risk. Features with no security consequence, such as content,
leaderboards and statistics, are Informational at most.

Test only with accounts you own, and never against another user's account or balance. The Safe
Harbor rules below apply in full, including no denial-of-service testing.

_This was already stated in SECURITY.md's scope list but missing here, and some server reports were
closed as out of scope on 2026-10-13. Those reports have been reassessed under this section._

### The bounty target is one named version, and it holds still

**Test the bounty target named at the top of this file.** It is a single published version, not
"whatever is newest", and it does not move while you are working. When a new target is adopted,
the previous one stays in scope for **14 days** afterwards, so a report begun under the old
target is never wasted.

We ship quickly, and the old wording — "only the latest released tag is in scope" — made that
our contributors' problem rather than ours: a release landing mid-investigation could turn good
work into an out-of-scope report on a technicality, and several reports were closed exactly that
way. So:

- A finding that **reproduces on the bounty target is in scope**, even if a newer release exists
  by the time you send it. If it is already fixed on the line we develop we will tell you, and it
  still gets the first-or-duplicate decision on its merits.
- A finding that **never existed on the target** is out of scope, however real it looks against
  an older build. Reports against `v3.9.2`, the frozen `phase1-modernization` branch and the
  retired `io.digibyte` app have all been closed for this.
- The target is published here and a change is announced here. Watch this file, not the releases
  page.

Fixes for accepted findings land on our development line and ship in a later release, which may
be several versions ahead of the target. That is expected and does not affect eligibility.

### Vulnerability classes we pay for

The bounty pays for **exploitable** security vulnerabilities. Examples of
issues that have qualified, ranked by severity:

#### Critical — funds and seed compromise — up to 100,000 DGB

- Extraction of the BIP39 seed or any derived private key without explicit
  user authentication
- Bypass of Android Keystore protection (e.g., decrypt the seed blob without
  the user's PIN being entered)
- Persistent unauthorized access to wallet funds (silent transaction
  signing, replacement of the user's intended recipient)
- Remote code execution via a maliciously crafted asset, transaction, or
  network response delivered through SPV
- Memory disclosure of `g_seed` or any derived signing key from a
  non-rooted device

#### High — funds at rest — up to 50,000 DGB

- Coin-selector or signing-path bug that lets a network adversary cause a
  user's send to go to an attacker-controlled address
- Forged DigiAsset transfer the wallet credits to the user without
  on-chain backing (false-positive receive)
- M3 parent-walk produces a wrong asset-ID such that the wallet displays
  asset A as asset B in a way that materially misleads the user
- PIN bypass without seed extraction (unauthorized in-app actions)

#### Medium — auth bypass, privacy leak — up to 15,000 DGB

- Biometric prompt bypassable on standard Android configurations
- Clear-text leak of wallet addresses, transaction history, or balance to
  a non-cert-pinned destination when Tor routing is enabled
- Address-to-IP linkage observable on the network when the user has
  explicitly enabled Tor routing
- Successful MITM despite cert pinning (e.g., pinning bypass on
  `api.digiscope.me` for a user with a clean device)
- Persistent denial of sync (peer-driven SPV crash that requires app
  reinstall to recover)

#### Low — DoS, info disclosure, DigiAsset display integrity — up to 3,000 DGB

- App crashes triggerable by a malicious peer or asset issuer that don't
  require user interaction beyond normal use
- Wallet log files containing data that helps de-anonymize the user
- Build-time hardening gap with a concrete attack scenario

#### Informational / hardening — credit, no payout

Thanks + named credit in the release notes. Examples: defense-in-depth
gaps, dependency upgrades, missing input length caps without a concrete
exploit chain.

### Reward calculation

The numbers above are *ceilings*, not floors. Severity within a tier is
determined by:

- **Reproducibility** — works on first try (P0) vs. requires complex setup (P2)
- **User interaction required** — none < tap a URL < send a tx < install side-loaded malware
- **Mitigations defeated** — bypassing cert pinning + Keystore is more valuable than bypassing one
- **Mainnet impact** — a bug that triggers on real DigiByte mainnet is worth more than one that requires testnet-specific setup
- **Value actually at risk** — severity follows what a user can lose, not the name of the
  subsystem. DigiAssets carry no market value today, so a forged credit, a wrong asset ID or a
  misattributed transfer is a **display-integrity** bug and scores Low, however alarming the
  mechanism reads. It rises only when it reaches spendable value: DGB, DigiDollar, or the seed.
  Earlier asset findings have been re-scored on this basis.
- **Quality of report** — clear PoC + exact reproduction steps + suggested fix is worth more than a vague write-up

A novel critical-tier finding with a working PoC against the latest release on
a stock Pixel 7 running Android 15: 100,000 DGB.

A theoretical critical-tier finding without a PoC: scored as Medium until a PoC
lands.

Payment is in DGB to an address you provide. We do not pay in fiat or other
cryptocurrencies. This is the HoldFast Wallet bounty, paid in DigiByte.

One further rule, because most of what we now receive arrives in machine-generated batches: a
reward is **per unique vulnerability, not per email**. Seven reports of one root cause are one
reward. Paid findings are capped at **three per reporter per wave**, and a report against a version
that is not in scope — see "The bounty target is one named version" above, which is the only
statement of that rule — is closed without triage.

A **reporter** is a person or team, not an email address. Reports sent from several addresses by
the same person or team — shared numbering, cross-references to "my earlier report" sent from
another address, the same credit name — count as one reporter for the per-wave cap and for
first-or-duplicate decisions. If you work as a team, say so and name one contact.

---

## Out of Scope

Reports about the following are **closed without payout**:

### Not security issues

- UI/UX confusion that doesn't cause a security harm (button placement,
  text alignment, dark mode bugs, animation glitches)
- Spelling, grammar, or localization errors
- Accessibility issues (please report these via GitHub Issues — they
  matter, but they're not a bounty class)
- Performance issues without a security implication (slow sync, battery
  drain)

### Already known / accepted

- The retired io.digibyte app's signature lineage starts from a debug certificate (Scheme v3
  rotation by design). io.digibyte is out of scope; HoldFast Wallet is signed by a single release
  key with no lineage, and its build refuses an artifact with any other signer.
- AndroidX `WorkManager` / `ProfileInstaller` exported components
  protected by signature-level system permissions (third-party,
  required by the libraries)
- `minSdk = 26` accepts known-vulnerable Android 8.0 devices (intentional
  for Galaxy Note 8 / API 26 support)
- The wallet trusts system CAs for non-pinned endpoints (defense-in-depth
  layer; cert pinning is targeted to specific endpoints)
- Asset metadata may contain attacker-controlled strings (sanitization
  layer in `AssetMetadataService` keeps visible text only: control, format,
  separator, surrogate and private-use code points are stripped; finding a
  bypass IS in scope, but flagging the attack class generically, or
  look-alike letters that are valid text, is not)

### Known to us, but not yet public

The list above is closed: documented, deliberate trade-offs, and reports of them are out of
scope. This is a different case — a real vulnerability that we had already found ourselves but
had **not** fixed in a release, published, or described anywhere you could have seen:

- From **2026-10-13**, a report of something already known to us earns **credit, not a reward**,
  whether or not it was public. Most reports now arrive in waves produced with automated review
  tools, often several on the same root cause within hours, and the reward is for finding what we
  had not.
- Reports received **before 2026-10-13** keep the earlier rule: the first valid external report
  earned half of its tier's reward.
- Later reports of the same root cause are duplicates: credit, no payment.
- When we apply this we tell you what we already knew — by tracking ID and the invariant the fix
  establishes — so the reduction can be checked from your side rather than taken on trust.

_The half-tier rule was applied from the 2026-10 waves but was not written here until 2026-10-11,
after a reporter rightly pointed out that the published rules did not cover it. It was replaced on
2026-10-13 as above._

### Known issues being addressed for 4.1.2

These are on our list for the next release. A report that only restates one of them is a
duplicate: credit, no payment. A report that shows one of them reaching **DGB, DigiDollar or the
seed** in a way not described here is new, and is assessed as new.

- **DigiAsset transactions with uncommon shapes.** Some valid but unusual DigiAsset layouts
  (unrecognised instructions, several asset carriers in one transaction, issuance with transfer
  rules, transfers whose result cannot be decided from the transaction alone) are handled by
  holding the affected outputs rather than spending them. The wallet fails closed: such an output
  can show as unspendable until a later release settles it. A report that the hold happens is known; a
  report that it can be avoided, or that it loses funds, is not.
- **Chain trust.** Choosing between competing chains by accumulated work, and cross-checking the
  data one peer serves against others, are scheduled structural changes.
- **Wallets holding very many small coins.** A send whose coins would not fit in one transaction
  is refused, and the message reads as an insufficient balance; "send max" is refused the same way.
  A clearer message and a way to consolidate coins are planned.
- **DigiDollar sends and Dandelion.** DigiDollar sends do not use Dandelion++ stem routing.
  Dandelion is off by default.
- **Confirmation status from a server.** Confirmation status that a server reports, rather than
  the wallet's own chain, is being taken out of the decision about when a payment counts as
  confirmed and spendable.
- **Tor coverage.** Some connections made while Tor is enabled or starting are not yet routed
  through Tor.
- **Release-build logging.** Some transaction details still reach the device log in release builds.
- **DigiDollar recovery from another wallet.** Fee coins, the range of addresses scanned, and the
  status shown after a move that does not confirm.
- **Confirmations and recovery screens.** A user-initiated way to cancel a send stuck
  unconfirmed; protected-screen and confirmation treatment for wallet wipe, Digi-ID and node
  pairing prompts; recovery of every DigiDollar and asset case from another wallet.

This list is about what the wallet does, not how to trigger it, and it changes when a release
ships. Watch this file.

### Generic / requires adversarial conditions outside threat model

- Issues requiring physical access to an unlocked device
- Issues requiring root, ADB-over-USB, or a side-loaded malicious app
  with arbitrary permissions
- Issues requiring the user to install a forked / modified APK
- Social engineering attacks against the user
- Phishing sites that mimic our domain
- Brute-forcing a user's BIP39 seed, PIN, or biometric
- Reports about third-party dependencies without a working exploit chain
  through our code (please file upstream first)
- Reports based on outdated app versions

### Theoretical without PoC

- "It's possible that…" without a working demonstration
- Tool output dumps from MobSF, Drozer, OWASP ZAP, etc. without
  human triage and an exploit chain
- AI-generated reports without independent verification by the submitter

---

## Submission Process

### How to submit

1. Email `security@aroundtheblock.us` with subject prefix `[BOUNTY]`
2. Or open a GitHub Security Advisory at
   https://github.com/JohnnyLawDGB/digibytewallet-android/security/advisories/new

**Do not** open public GitHub Issues for security reports. We will close
them and ask you to resubmit privately, which delays your payout.

### What to include

Reports without these elements are returned for missing information:

- **Title** — one-line summary
- **Severity claim** — your initial Critical/High/Medium/Low assessment
- **Affected version** — the exact tag (e.g. `v4.1.1`) you tested against
- **Affected platform** — Android version, device model, ROM
- **Repro steps** — numbered, deterministic; copy-pasteable commands where possible
- **PoC** — minimal artifact that triggers the bug. For network bugs: a `curl` or
  `mitmproxy` script. For on-device bugs: an ADB session log. For asset bugs:
  the malicious tx hex or metadata JSON.
- **Impact statement** — what an attacker gains
- **Suggested fix** (optional but valued — affects payout tier)
- **DGB payout address** — we pay in DGB to an address you provide, so include one. We
  checksum-verify it before sending anything, and we will ask you to confirm it in your own words
  at payment time: a bounty payment is an irreversible on-chain send.
- **How you want to be credited** — your real name, a handle, or anonymous. We name reporters in
  the release notes and in the Hall of Fame below, and we only do that with your permission, so if
  you do not tell us we cannot credit you. Asking to stay anonymous does not affect eligibility,
  and duplicates and hardening findings are credited too, even where they earn no payment.

### Disclosure timeline

- **Acknowledgement** — within 3 business days of submission
- **Initial triage** — within 7 business days, with a severity assessment and
  estimated fix timeline
- **Fix landing** — Critical: 7 days from triage. High: 14 days. Medium: 30 days.
  Low: 90 days. Negotiable based on complexity.
- **Public disclosure** — coordinated with you, typically 30 days after the
  fix ships in a release. We will name you in the release notes unless you
  request anonymity.
- **Payment** — within 30 days of public disclosure (or earlier if the fix
  ships before disclosure). Paid in DGB to an address you provide.

### Eligibility

- Researchers must be **18 or older** (or have parent/guardian consent)
- Researchers must **not** be a current or former employee or contractor
  of Around The Block LLC
- One reward per unique vulnerability (first valid report wins; duplicates
  receive thanks + credit)
- Researchers must comply with all applicable laws

---

## Safe Harbor

We will not pursue legal action against researchers who:

- Make a **good-faith effort** to comply with this policy
- **Do not** access, modify, or delete data that doesn't belong to them
  (no live wallets other than your own test wallets)
- **Do not** disrupt service for other users (no DoS testing on
  `api.digiscope.me` or any production peer)
- **Do not** publicly disclose findings before coordinated disclosure
- **Do not** demand additional payment as a condition of disclosure

If you accidentally cause a service issue while testing in good faith,
notify us immediately. We'd rather hear from you than from monitoring.

---

## Out-of-band — what to do if you find something today

Critical findings should be reported as soon as you have a PoC, even if
you haven't finished writing the report. Send an email with the title and
a one-paragraph summary; we'll work with you on the formal write-up.

We do not require an NDA. We do not require you to use a specific bounty
platform — direct submission is welcomed and treated identically.

---

## Hall of Fame

Researchers who have reported valid vulnerabilities, with their permission,
will be listed here. Tell us in your report how you would like to appear.

Names only, each with that researcher's permission and in the form they chose. What each one
found is credited in the notes of the release that fixes it, and not before: several of these
findings are fixed but not yet released, and naming them here would disclose them.

@0x1cb · 0xasuma · 0xradu · Advena · Akbarrsw · apienz · AsrHardi · Astung · Danu Fandiyana ·
Eula Lawrence · folox · FZN research · @haikaljim9 · iblitzc0de · Jackai · Jo Arsy · Keyzo · Ki ·
@Lord99900 (Bima maulana) · Lou · Luis_MZ · Mr Fz · Muhammad Harley · naresaelvara · oncodewa ·
Redi Sinaga · Rensisme · Ricki Ariadi · rreiissz · sayligood · Toshit · Tsaqif · Umiroh123 ·
VanSecQA · w4ke · xbyteid · Yudha Eka Saputra

*Reporters from the 2026-09 and 2026-10 waves who have not yet answered the credit question are
not listed. Anonymity, or no answer, never affects payment.*

---

## Change Log

- **2026-05-03 — Program launched.** Initial scope: v3.5.31 and later.
  Maximum reward 100,000 DGB.
- **2026-10-10 — Scope and scoring.** One named bounty target that holds still, with a 14-day
  overlap when it moves. DigiByte Mobile (`app.aroundtheblock.wallet`) is in scope; io.digibyte,
  retired at v4.0.87.2, is not. Severity follows the value actually at risk (DigiAssets carry no
  market value, so asset findings are display integrity unless they reach DGB, DigiDollar or the
  seed). One reward per unique vulnerability; three paid findings per reporter per wave. Payout
  address and credit preference requested with every report.
- **2026-10-11 — Rules written down that were already applied.** "Known to us, but not yet
  public" (half the tier for the first external report). The scope rule is stated once instead
  of twice. The io.digibyte signing-lineage item corrected. Hall of Fame populated with the
  names of researchers who gave permission.
- **2026-10-13 — Known-to-us earns credit.** From 2026-10-13 a report of something already known
  to us earns credit, not a reward. Reports received earlier keep the half-tier rule.
- **4.1.0 — target moved.** Bounty target ATB Wallet 4.1.0 (4.0.89 overlaps for 14 days). A
  published list of what is being addressed for 4.1.1.
- **4.1.0: renamed.** The app is now ATB Wallet for DigiByte (DGB), formerly DigiByte Mobile.
  The package (`app.aroundtheblock.wallet`), the signing key and every scope rule are unchanged.
- **2026-10-21 — DigiScope services stated.** The server side of the DigiScope services the app
  uses was already in SECURITY.md's scope list; it is now stated here too, with what counts.
  Reports closed as out of scope on that ground on 2026-10-13 have been reassessed. SECURITY.md
  no longer restates the version rule ("latest released tag") and points here instead.
- **2026-10-23: renamed again.** The app is now HoldFast Wallet, formerly ATB Wallet for
  DigiByte (DGB) and DigiByte Mobile. The package (`app.aroundtheblock.wallet`), the signing key
  and every scope rule are unchanged, and an installed copy updates in place.
- **4.1.1 — target moved.** Bounty target HoldFast Wallet 4.1.1 (4.1.0 overlaps for 14 days). The
  known-issues list now names what is being addressed for 4.1.2.
- **2026-10-24 — one reporter, several addresses.** A person or team sending from several addresses
  counts as one reporter for the per-wave cap and duplicate decisions. Applied from the 2026-10-24
  intake on.
