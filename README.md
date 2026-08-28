# DigiByte Wallet for Android

A full Kotlin rewrite of the DigiByte Android wallet — a **sovereignty-first** SPV wallet built on Jetpack Compose over a patched native C core. Compact-filters-only sync (BIP157/158), hardware-backed key custody, DigiDollar + Taproot, DigiAssets v2, Digi-ID authentication, and a Community Hub.

> **Status:** Active development — **v4.0.69**. BIP157/158 is the only sync path (the bloom wire path was removed in v4.0.0), DigiDollar and Taproot are wired end-to-end, own-node pairing shipped. v4.0.69 moves DigiAssets out of a wallet you are recovering from BEFORE sweeping its DGB, which removes the fee estimate the old order needed and with it a whole class of mis-sizing bug; verified on mainnet. v4.0.68 added French (13 languages) and moved the optional BIP39 passphrase onto its own screen after seed verification — storing a passphrase beside the recovery phrase makes it a single secret again, so the two are never recorded in one sitting. v4.0.66 added the optional BIP39 passphrase (12+1 / 24+1) on wallet creation, checked end-to-end against an independent implementation. v4.0.63–v4.0.65 make the wallet speak 12 languages — German, Spanish, Hindi, Chinese, Japanese, Portuguese (BR), Indonesian, Vietnamese, Turkish, Russian and Filipino alongside English — with a language picker on the first screen, before any wallet is created. **[Download APK](https://digiscope.me/downloads/)** · **[Latest GitHub release](https://github.com/JohnnyLawDGB/digibytewallet-android/releases/latest)**

## Download

| Source | Link |
|---|---|
| GitHub Releases (signed APK + SHA-256, every version) | **[github.com/JohnnyLawDGB/digibytewallet-android/releases/latest](https://github.com/JohnnyLawDGB/digibytewallet-android/releases/latest)** |
| digiscope.me (latest APK + release notes) | [digiscope.me/downloads](https://digiscope.me/downloads/) · [release notes](https://digiscope.me/wallet/releases/) |

Every release is built and signed in CI — release signing keys are never held locally. Verify
what you downloaded before installing:

```bash
sha256sum digibyte-wallet-v4.0.40.apk
```

and compare against the SHA-256 published on the release page. The app is not on the Play Store
yet (Play Store and F-Droid are Phase 4 on the roadmap), so a direct APK install is expected.

## Design principle: sovereignty first

Removing trusted third parties from the wallet's data path comes before feature breadth. Two consequences you can verify in the code:

- **The address set never leaves the device.** Sync runs BIP157/158 compact block filters exclusively. The bloom wire path (`filterload`/`filteradd`/`merkleblock`) was excised from the C core in v4.0.0 — the code that could leak your addresses to a peer no longer exists. One automatic disclosure outlived it: asset-holding lookups POSTed the whole address set to a backend. That was removed in v4.0.36, so no code path now discloses the address set without you explicitly asking for it.
- **Everything you see is computed on-device.** All transaction validity and balance computation happens locally from block headers and filters. Nothing in the UI is fetched from a block explorer. (Chain reconcile against a node remains an explicitly-labeled recovery path, never a balance-display path.)

## What's in v4

### Core wallet
- **Full SPV, no server dependency** — connects directly to the DigiByte peer network. Your keys, your coins.
- **DigiByte Core 9.26-compatible** — current protocol, DNS seeds, and consensus rules including the Taproot and DigiDollar softforks.
- **Hardware-backed key custody** — seed sealed with AES-256-GCM via a hardware-backed Android Keystore key (`KeyStoreManager.kt`). `loadSeed()` returns a `ByteArray` that is zeroed after use; the mnemonic never becomes an immutable JVM heap string, and the raw seed is `static` to the JNI translation unit behind an accessor API.
- **App-enforced PIN lock** — the wallet enforces its own PIN (Argon2id-hashed, PBKDF2 fallback) rather than binding the Keystore key to device auth, which avoids cross-API-level crashes on Android 9→15.
- **Single fast fee** — DigiByte confirms in ~15 seconds with no fee market. One default fee (DigiByte min-relay), with an optional custom-fee override and below-minimum / zero-fee warnings.
- **Modern Android** — Kotlin 2.0, Jetpack Compose, Material 3, SDK 35, Hilt DI, Room + SQLCipher.
- **16 KB page-size ready** — native libs stored uncompressed and page-aligned, verified on Android 15 flagships.

### Sovereign sync (BIP157/158)
- **Compact-filters-only** — native GCS filter decoder plus `cfheaders`/`cfilter` wire handlers and a file-backed filter-header chain. A birth-height floor bounds the historical scan so a restored wallet doesn't rescan from genesis.
- **Capability-aware peer discovery** — a fleet of hardcoded CF oracle nodes is preferred and pinned, with a capability-aware seeder (`api.digiscope.me/api/peers`) as fallback; only filter-serving peers are dialed.
- **Own-node pairing** — pair your own DigiByte 9.26 node by QR (`dgbnode://`) for first-class, self-hosted compact-filter service — the sovereign endgame for sync.
- **Dynamic peer-cap (v4.0.23)** — hold the full peer set while catching up, then drop to a lean count once genuinely at the tip, so idle wallets stop pinning slots on the shared filter fleet.
- **Resilient loop** — watchdogs recover from header-tip stalls, orphan-tip landings, network blips, and background-freeze on aggressive OEM battery managers; the foreground service revives the sync loop on resume.
- **Restore that converges (v4.0.35)** — a deep restore resumes its scan across restarts instead of resetting to the birth floor; the recovery watchdog is liveness-gated so it never stands down its own recovery during a stall; and a delivered block must verify against the header's committed merkle root before a height counts as scanned — closing a path where a peer could have hidden an incoming payment.
- **Sync resumes where it left off (v4.0.40)** — a mid-session peer-manager rebuild (network drop, 0-peer recovery, a stalled filter chain) used to restart the chain from the wallet's birth height and re-scan more than a million blocks. The rebuild now reloads the near-tip window first, keeps the record of which ranges were already scanned, and flushes live progress to disk before tearing the manager down. Verified on-device: four rebuilds in under a minute, all resuming at the tip.
- **The filter chain can't lie below a checkpoint (v4.0.41)** — filter headers are pinned against a table of operator-attested checkpoints (481 entries to height 24,050,000). A batch is validated *before* it is committed, so a peer whose chain diverges from a pin is rejected and banned instead of having its headers accepted and the mismatch merely logged. A checkpoint-confirmed chain also vetoes the re-anchor path, closing a hole where a single lying peer could pull the wallet onto its fork. If verification can't be satisfied at all — every peer disagreeing, an eclipse — the wallet parks at the last verified checkpoint and says so, rather than advancing on unverified data or wedging silently. Blocks above the last checkpoint remain trust-on-first-use plus quorum; tip trust is a separate piece of work.
- **One peer can't condemn your history (v4.0.43)** — v4.0.41 added a safeguard so the wallet never stops silently when it cannot verify the filter chain. It acted on a single disagreeing peer, and because its retry budget is spent gradually over a long session by ordinary chain churn, a wallet left running would eventually mark every block above the newest checkpoint permanently unverified — a "history gap" warning that no rescan can clear. The wallet still resumes from the trusted checkpoint, which is what the safeguard was for, but it now only declares history unverified when a majority of connected filter peers agree on the disagreement.
- **A history gap repairs itself (v4.0.44)** — when the wallet marked a range of blocks unverified, that marker could never be undone: the only cures were asking a server to re-check your addresses, or rebuilding the entire chain from your wallet's creation date. It now re-fetches just the block headers underneath the gap, a few thousand at a time, and retires the marker as it goes. Blocks are anchored to a checkpoint compiled into the app, so no third party is involved, and progress survives being interrupted.
- **…and a warning that could never be dismissed now clears (v4.0.46)** — a gap recorded while the wallet wasn't running has no known lower edge, and the check that retires it read that unknown as *the beginning of the chain* — something no wallet can ever disprove, so the warning was permanent. A gap cannot reach below the point the wallet started scanning from, so that point is now used as the lower edge. Where the lower edge **is** known it is still enforced strictly, because a wallet whose scan began above a gap has learned nothing about it.
- **A send can no longer report success when the network refused it (v4.0.42)** — the native bridge published transactions without a result callback, which was the only channel the core had for reporting a rejection. Worse, it made the publish invisible to the cleanup path that cancels failed sends. Because the transaction is registered with the wallet just before publishing, a send the network never accepted still marked its inputs spent, leaving the wallet holding outputs that existed nowhere else — and a later send built on one could never confirm. Failures are now reported, and **Clear stuck sends** can clear such a stranded transaction instead of requiring a full rebuild from chain.

### DigiDollar & Taproot
- **Taproot** — P2TR (`dgb1p…`) receive and BIP341 key-path signing, proven on mainnet; sighash paths are KAT-tested in the native host test suite.
- **DigiDollar** — send, receive, and balance for DigiByte's USD-denominated stable tokens (a Taproot softfork). DigiDollar amounts are USD-cents on-chain, so the DD balance needs no price feed. A dedicated `DD…` receive address is shown alongside the DGB address types.

### DigiAssets v2
- **Detect, decode, display, send, receive** — native OP_RETURN parser (issuance / transfer / burn, SFFC amount codec, FIXED/RANGE/BURN algorithms) with parent-provenance walking for transfer asset IDs.
- **Sovereign asset balances (v4.0.39)** — computed from the native watch-set, not a backend. A row counts only when the native wallet still holds that exact output; a row the wallet cannot confirm it holds is not counted on the strength of where it came from. Implicit change (the DigiAssets rule that sends the leftover of a partial transfer to the last output) is credited, and an abandoned send that was re-sent no longer counts its change twice.
- **UTXO protection** — asset-bearing UTXOs are segregated from DGB coin selection; you cannot accidentally spend an asset as a fee.
- **Trustless IPFS metadata** — asset metadata fetched from gateways with CID hash verification. No gateway trust, no on-device daemon.
- **In-app Market (DigiStamp)** — [assets.digistamp.co](https://assets.digistamp.co/) is housed in the wallet, origin-locked: only that host renders in-app and everything else is handed to the system browser. There is **no JavaScript bridge** — page code cannot ask the wallet for a signature, an address, or a balance. The only channel from page to wallet is navigation, which opens a native screen the user reads before acting.

### Digi-ID
- **Real message signing** — Bitcoin-style compact signatures via `BRKeyCompactSign` in the C core.
- **Scan to authenticate** — scan a `digiid://` QR to log in to any Digi-ID-enabled site; the callback domain is validated against the URI host and HTTP (`u=1`) callbacks are blocked.
- **Biometric gate** — BiometricPrompt confirmation before signing.

### Community Hub
- **Real-time chat** — channel-based WebSocket messaging (General, Trading, Development, Assets).
- **Forum threads** — longer-form discussion with replies and upvotes.
- **Enigma AI bot**, **inline DGB tipping**, and **pseudonymous handles** linked to a DGB address — no email, no phone.

### Privacy (opt-in)
- **Tor transport** — route the C core's peer connections through an embedded, no-exec Tor (SOCKS5). **Opt-in, default off** — enable in Settings → Network Info.
- **Dandelion++ stem submission** — stem each broadcast to a single seeder-tagged Dandelion peer with an embargo→fluff fallback so delivery is never sacrificed for privacy ([DIP #15](https://github.com/DigiByte-Core/dips/pull/15)). **Opt-in, default off.**

### DigiRunner mini-game
A standalone endless-runner (zero dependency on `:core`/`:native`) launched from the **Hub → Games** tab, with a cross-platform leaderboard tied to DigiScope identity.

## Architecture

```
┌─────────────────────────────────────────────┐
│          Presentation (Kotlin/Compose)       │
│   Material 3 · MVVM · Jetpack Navigation     │
├─────────────────────────────────────────────┤
│          Application (ViewModels/DI)         │
│  Hilt · StateFlow · Coroutines               │
├─────────────────────────────────────────────┤
│            Domain (Kotlin)                   │
│  WalletManager · AssetManager · CoinSelector │
│  DigiIdManager · TorManager · IpfsClient     │
├─────────────────────────────────────────────┤
│           Native Core (C/JNI)                │
│  digibytewallet-core (patched 9.26)          │
│  SPV Peer Manager · BIP157/158 GCS filters   │
│  secp256k1 · Taproot/BIP341 · multi-algo PoW │
├─────────────────────────────────────────────┤
│         Platform (Android)                   │
│  Keystore · BiometricPrompt · Room+SQLCipher │
└─────────────────────────────────────────────┘
```

All cryptographic operations and peer-to-peer networking happen in the native C core. Raw keys never cross the JNI boundary during normal operation.

## Module structure

```
digibytewallet-android/
├── app/        — Android app (Compose UI, navigation, services, DI)
├── core/       — Business logic (wallet, assets, IPFS, Digi-ID, security, Hub client)
├── native/     — C core + JNI bridge (SPV, crypto, secp256k1)  [C core is a git submodule]
├── game/       — DigiRunner mini-game (standalone)
└── docker/     — Reproducible build environment
```

## Building

### Prerequisites
- JDK 17
- Android SDK 35
- NDK 27.0.12077973
- CMake 3.22.1

### Quick build
```bash
git clone --recursive https://github.com/JohnnyLawDGB/digibytewallet-android.git
cd digibytewallet-android
./gradlew :app:assembleMainnetDebug
```

Install to a connected device:
```bash
adb install -r app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk
```

### Reproducible build (Docker)
```bash
docker build -t dgb-wallet-build docker/
docker run --rm -v "$(pwd)":/build dgb-wallet-build \
  bash -c "./gradlew :app:assembleMainnetRelease && \
  sha256sum app/build/outputs/apk/mainnet/release/app-mainnet-release-unsigned.apk"
```

See [VERIFICATION.md](VERIFICATION.md) for multi-party attestation instructions.

## Testing

```bash
# Unit tests (JVM)
./gradlew testMainnetDebugUnitTest

# Security tests only
./gradlew :core:testMainnetDebugUnitTest --tests "*.security.*"

# Pre-publish suite across API 28/33/34/35
./scripts/pre-publish-test.sh
```

**~510 unit tests, 0 failures**, including 50+ dedicated security tests covering:
- Native JNI bridge (mnemonic generation, address validation, asset detection)
- Seed handling (Keystore encrypt/decrypt, `ByteArray` zeroing, static-seed isolation)
- Room DAO operations (UTXO segregation, asset balances, migrations)
- DigiAsset decoder (OP_RETURN parsing on real mainnet data)
- Taproot / DigiDollar (BIP341 sighash, address encoding — KAT vectors)
- Digi-ID (URI parsing, callback-domain validation)
- Coin selection (asset protection, dust avoidance)

## Roadmap

`ROADMAP.md` is authoritative and sovereignty-first — removing trusted third parties from the data path comes before feature breadth.

| Phase | Theme | Status |
|-------|-------|--------|
| 0 | Legibility (`ARCHITECTURE` / `THREAT_MODEL` / `BIP_COMPLIANCE` / `PROCESS_FLOWS` docs) | ✅ Done |
| 1 | Sovereign data layer — BIP157/158 client, bloom removal, own-node pairing | ✅ Client shipped · 🚧 CF fleet reliability remainder |
| 1.5 | **v4.0.0** — bloom wire path fully excised, BIP157/158-only | ✅ Shipped |
| 2 | Key & trust hardening — PIN rate-limit, duress PIN, Keystore auth-binding, Digi-ID key isolation, Tor disposition | 🚧 In progress (PIN rate-limit shipped) |
| 3 | Feature velocity — PSBT, multisig, watch-only, coin control, RBF, WIF sweep, DigiAsset send | 🚧 In progress |
| 4 | Audit, distribution + hardware — third-party audit, F-Droid, Play Store, Coldcard QR, NFC | 🚧 Planned |

## Security

- **Bug bounty (up to 100,000 DGB):** [BUG-BOUNTY.md](BUG-BOUNTY.md)
- **Responsible disclosure:** [SECURITY.md](SECURITY.md)
- **Threat model:** [THREAT_MODEL.md](THREAT_MODEL.md)
- **Crypto inventory:** [CRYPTO_INVENTORY.md](CRYPTO_INVENTORY.md)
- **Reproducible builds:** [VERIFICATION.md](VERIFICATION.md)
- **Audit summary:** [security/AUDIT-SUMMARY.md](security/AUDIT-SUMMARY.md)

### Key security properties
- Seed encrypted at rest with a hardware-backed Android Keystore key
- Raw seed zeroed after use; never a long-lived JVM string; isolated to the JNI translation unit behind an accessor API
- App-enforced PIN, hashed with Argon2id (PBKDF2 fallback), with rate-limiting
- Database encrypted with SQLCipher (Keystore-derived passphrase)
- The address set never leaves the device — bloom is gone, sync is compact-filters-only
- No analytics, no telemetry, no proprietary dependencies
- `FLAG_SECURE` on seed display; `filterTouchesWhenObscured` on send confirmation

## Related

- **DigiStamp Marketplace:** [assets.digistamp.co](https://assets.digistamp.co/) — the DigiAsset marketplace the wallet integrates with, and the site served in the in-app Market section
- **DigiScope:** [digiscope.me](https://digiscope.me) — DigiByte community platform
- **Dandelion++ DIP:** [DigiByte-Core/dips#15](https://github.com/DigiByte-Core/dips/pull/15) — SPV stem-phase privacy
- **Original wallet:** [DigiByte-Core/digibytewallet-android](https://github.com/DigiByte-Core/digibytewallet-android)

## Contributing

Open-source contributions welcome via pull requests. Fork, create a feature branch, submit a PR with tests. Releases are tag-driven off the `develop` branch, and the pre-publish suite must pass before any tag.

## License

MIT — see [LICENSE](LICENSE)
