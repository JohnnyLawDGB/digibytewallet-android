# Threat Model

This document states, in prose a hostile reviewer can audit, the
threats this wallet's design considers and the residual risks that
remain. It is maintained alongside the code, not after it.

## Scope

In scope: the running Android application, the seed and keys it
manages, the data it exchanges with DigiByte peers and the author's
DigiScope infrastructure, and the Android device it runs on.

Out of scope: supply-chain attacks on the Android toolchain, coercion
of the DigiByte consensus layer itself, memory forensics on a running
device with root access (though we try not to make it trivial), and
attacks on components fully documented as trusted third parties
(DigiScope Hub for pseudonymous social features).

## Assets

What the attacker is trying to reach or manipulate:

- **A1 — BIP39 seed.** Master key for every private key the wallet
  derives. Stealing this compromises all funds permanently.
- **A2 — Private keys in memory.** Transient; derived from A1 when
  signing. Not at rest.
- **A3 — Wallet address set.** The set of addresses this wallet owns.
  Not fund-critical on its own but is the key to de-anonymizing every
  transaction the wallet has ever signed.
- **A4 — Transaction history.** Which blocks the wallet cares about,
  who the user has paid, and when.
- **A5 — Balance information.** Less sensitive than A3/A4 but still a
  meaningful privacy leak.
- **A6 — Device-level credentials** (Digi-ID login sessions, Hub JWT,
  stored service tokens). Lower-value than A1 but high-volume.

## Adversaries

Ordered roughly from lowest capability to highest:

- **T1 — Casual observer.** Sees the device screen, has no physical
  access.
- **T2 — Network observer** (ISP, Wi-Fi operator). Sees encrypted
  traffic metadata: destinations, timing, volume.
- **T3 — Peer on the DigiByte network.** Runs a DigiByte node and
  receives whatever the wallet chooses to tell it over the P2P
  protocol.
- **T4 — Malicious peer.** Serves crafted responses to attempt DoS,
  eclipse, or inject false chain data.
- **T5 — Compromised bloom seeder** (api.digiscope.me). Can bias the
  wallet's peer selection toward hostile peers.
- **T6 — Compromised DigiScope backend.** Can lie about Hub state and
  Digi-ID sessions.
- **T7 — Attacker with unlocked device.** Physical access while the
  user has the app unlocked.
- **T8 — Attacker with locked device.** Physical access, device locked
  at OS level.
- **T9 — Attacker with locked device + PIN guesses.** Device seized,
  attacker has extensive time but limited guess attempts against
  wallet PIN.
- **T10 — Malicious app with sandbox bypass.** App running on the same
  device that escapes the Android sandbox.
- **T11 — Wallet author coercion.** Maintainer compelled to ship a
  malicious update or operate hostile infrastructure.

## Adversary × asset matrix

Each cell is the current mitigation (if any). `—` means no direct
exposure. Bold entries are known residual risks.

| | A1 seed | A3 addresses | A4 tx history |
|--|---------|---------------|---------------|
| T1 casual observer | `FLAG_SECURE` on seed screens blocks screenshots | — | — |
| T2 network observer | TLS on all HTTP; P2P traffic is encrypted-by-content only, not transport | TLS hides host, **P2P traffic leaks address set via bloom filter until Phase 1** | TLS hides host, **block request patterns leak tx interest** |
| T3 honest peer | — | **Bloom filterload reveals filtered address set to every connected peer** | **Merkleblock responses reveal wallet tx set to each peer** |
| T4 malicious peer | — | Same as T3 plus active correlation across connected peers | Same as T3 |
| T5 compromised bloom seeder | — | Can eclipse wallet onto hostile peers (then T3/T4 apply) | Same |
| T6 compromised DigiScope backend | — | Can correlate Digi-ID login events with IP address | — |
| T7 unlocked device | Seed in memory while app is alive, accessible to a sophisticated attacker | Same | Full access |
| T8 locked device | Sealed with Keystore-wrapped AES-GCM key; Keystore key is hardware-backed where supported | Same | Room DB encrypted with SQLCipher |
| T9 locked device + PIN guesses | PIN hashed with Argon2id (t=3, m=64MiB, p=4); **no rate-limit on failed guesses** | Same | Same |
| T10 sandboxed-bypass app | Keystore key is app-scoped; a full sandbox bypass reading `/data/data/io.digibyte/` can still lift the encrypted seed but not decrypt without the PIN | Same | Same |
| T11 author coercion | Signed releases + lineage means a forged APK can't trivially impersonate the existing install (Android refuses the update). Source is MIT-licensed and auditable | Same | Same |

## Mitigations by layer

### Seed layer

- Seed at rest: `AES/GCM/NoPadding`, 96-bit random IV per encryption,
  128-bit auth tag, key wrapped in Android Keystore. Code path:
  `core/…/security/KeyStoreManager.kt`.
- Seed in memory: `ByteArray` (not `String`, which would become an
  immutable interned object). Zeroed in a `finally` block after use.
- Seed on-screen: `FLAG_SECURE` flag set on seed display and verify
  activities; blocks screenshot capture and excludes from recent-apps
  thumbnail.
- Seed never leaves the device. No cloud backup, no analytics, no
  crash-report payload that could include it.

### Key derivation

- BIP39 → 64-byte seed → BIP32 master key with HMAC-SHA512.
- BIP84 derivation path `m/84'/20'/0'/0/0…` for new wallets.
- Dual-scan recovery: checks both BIP84 and the legacy
  breadwallet-inherited `m/0H/chain/index` path, handles mixed
  state via `hasLegacyKey` flag.

### App auth

- PIN: Argon2id (t=3, m=64MiB, p=4) or PBKDF2-HMAC-SHA256 600k fallback.
  Hash + salt stored in EncryptedSharedPreferences.
- Biometric: UI-gate only. Unlocks the app but does not unwrap the
  Keystore key; the PIN hash path is still the authoritative gate to
  seed access.

### Data layer

- **Current (bloom, BIP 37):** wallet constructs a filter containing
  its address hashes and sends it to each connected peer; peers reply
  with merkleblocks and transactions. **Leaks the address set
  probabilistically to every peer.**
- **Phase 1 (compact filters, BIP 157/158):** filters are constructed
  by the peer and served to the wallet; the wallet matches filters
  locally and requests full blocks on match, never sending wallet
  state to peers. **Closes the plaintext address leak.**

### Network layer

- Tor (optional, opt-in today): kmp-tor exec mode, separate process
  for crash isolation. Routes P2P traffic and bloom-seeder HTTP
  through SOCKS5 when enabled. **Silent fallback to clearnet on Tor
  failure is a known gap — Phase 2 surfaces a loud warning.**
- Certificate pinning on `api.digiscope.me` to defend against
  upstream MitM on the Hub and seeder endpoints.

### Update integrity

- APK signed with the DigiByte Wallet release key.
- v3.5.9+ uses APK Scheme v3 signing lineage so existing debug-key
  installs can rotate to the release key without uninstalling.
- In-app UpdateChecker verifies via `github.com/.../releases/latest`
  (GitHub's cert chain; no custom trust anchor).

## Known residual risks

Named explicitly so they don't go unfixed by being unspoken.

1. **Bloom filter address leakage (current data layer).** Any peer we
   connect to learns a probabilistic view of our address set. Phase 1
   closes this.
2. **Bloom seeder as soft trusted third party.** `api.digiscope.me` is
   operated by the wallet author. Compromise or coercion could eclipse
   users onto hostile peers. Scope is narrow: the seeder does not
   serve chain data itself, only peer addresses. Phase 1 demotes
   this from a required bootstrap to one of several optional sources.
3. **PIN has no rate-limit.** An attacker with APK access and
   sustained compute can brute-force the PIN against the encrypted
   seed blob. Argon2id makes this expensive; exponential backoff
   would make it infeasible. Phase 2.
4. **Biometric is cosmetic.** Biometric unlock does not bind the
   Keystore key's user-authentication requirement, because
   `setUserAuthenticationRequired=true` crashes on API 28/33/35 in
   inconsistent ways. As a result, a compromised app process can
   decrypt the seed without the device being unlocked. Phase 2
   revisits with per-API-level probing.
5. **Tor silent fallback.** If Tor fails to start or the daemon dies,
   the wallet silently uses clearnet. Phase 2 changes this to a loud
   warning banner.
6. **Digi-ID key is the main wallet's first address.** Digi-ID signs
   with `m/44'/20'/0'/0/0`. A Digi-ID signature produced in response
   to a malicious callback exposes the first address's signing
   capability; isolation to a separate subtree is Phase 2.
7. **Block-request correlation.** Even in compact-filter mode, the
   wallet still fetches full blocks on filter match. A surveillant
   peer observing the wallet's block requests over time can infer
   which filters matched even without seeing the filter contents.
   Mitigated by wallet-birthday scoping (only request filters for
   the birthday range) and by Tor. Residual risk documented but not
   further mitigated.

## Explicitly out of scope

- **Pre-install supply-chain attacks.** A malicious APK served by a
  compromised app-store channel is out of scope; users who install
  from digiscope.me verify SHA-256; users who install from GitHub
  trust GitHub's signature on the release.
- **OS-level persistence malware.** An attacker with root on the
  device can do anything; we treat the Android sandbox as part of
  the trusted base.
- **Hardware side channels.** TEE/StrongBox side channels during key
  use. We trust the hardware to the extent Android does.
- **DigiByte consensus itself.** We assume the DigiByte chain follows
  its rules; a 51% attack or consensus fork is out of scope.

## Change log

- 2026-04-13 — initial version, documents pre-Phase-1 state.
- (Phase 1) — updates the data-layer rows when compact filters land;
  closes residual risk 1; narrows residual risk 2.
- (Phase 2) — closes residual risks 3, 4, 5, 6.
