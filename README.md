# DigiByte Wallet for Android — v3.0 Development Fork

A complete modernization of the official DigiByte Android wallet. Full Kotlin rewrite with Jetpack Compose, patched native C core for DigiByte Core 8.26+ compatibility, hardware-backed security, DigiAssets v2, Digi-ID authentication, privacy-by-default Tor routing, and reproducible builds.

> **Status:** Active development. Phase 1-3 complete. DigiRunner v2 with leaderboard, one-tap DigiScope login, security audit (34 tests), 120 unit tests passing. **[Download Beta APK](https://digiscope.me/downloads/)**

## What's New in v3.0

This is not a patch — it's a ground-up rebuild of the 2021 Java wallet into a modern, secure, privacy-first application.

### Core Wallet
- **Full SPV** — No server dependencies. Connects directly to the DigiByte peer network. Your keys, your coins.
- **DigiByte Core 8.26+ compatible** — Updated protocol version (70019), DNS seeds, checkpoints through block 23M, corrected fee constants.
- **Hardware-backed security** — Seed encrypted with AES-256-GCM via Android Keystore (TEE/Strongbox). BiometricPrompt unlock. Argon2id PIN hashing.
- **Modern Android** — Kotlin 2.0, Jetpack Compose, Material 3, SDK 35, Hilt DI, Room + SQLCipher.
- **Reproducible builds** — Docker build environment, multi-party attestation workflow, no proprietary dependencies.

### DigiAssets v2
- **Full asset support** — Detect, decode, display, send, and receive DigiAssets v2 tokens directly in the wallet.
- **BitIO decoder** — Complete OP_RETURN parser ported from digiasset-core and digibyte-js. Handles issuance, transfer, and burn operations.
- **UTXO protection** — Asset-bearing UTXOs (700-sat markers) are segregated from DGB coin selection. You cannot accidentally spend your assets as fees.
- **Trustless IPFS** — Asset metadata fetched from IPFS gateways with CID hash verification. No gateway trust required. No IPFS daemon on device.
- **Marketplace link** — View assets on [DigiNexum](https://diginexum.trade/) directly from the wallet.

### Digi-ID
- **Real message signing** — Bitcoin-style compact signatures via BRKeyCompactSign in the C core. No placeholder signatures.
- **Scan to authenticate** — Scan a `digiid://` QR code to log in to any Digi-ID-enabled website.
- **Shared QR scanner** — CameraX + ZXing barcode scanner with proper YUV row stride handling and thread-safe callbacks.
- **DigiScope auto-login** — Scanning a DigiScope QR authenticates both the website and the wallet's Hub connection in one flow.
- **Biometric gate** — BiometricPrompt confirmation before signing (FragmentActivity compatible).
- **Login history** — Track your Digi-ID authentication history.

### Privacy
- **Tor by default** — All network connections routed through Tor (SOCKS5 proxy in the C core). New installs have Tor enabled by default.
- **Multi-peer random submission** — Transactions submitted to a randomly chosen peer to obscure origin.
- **Trusted full node relay** — Optionally connect exclusively to your own node with Dandelion++ for maximum privacy.
- **Dandelion++ SPV stem submission** — Shipped in `v3.7.0`: each broadcast stem-submits to a single Dandelion node ([DIP #15](https://github.com/DigiByte-Core/dips/pull/15)) with an embargo/fluff fallback so delivery is never sacrificed for privacy. First-in-class for any UTXO chain.

### Community Hub
- **Real-time chat** — Channel-based messaging via WebSocket. General, Trading, Development, Assets channels.
- **Enigma AI bot** — Ask Enigma anything about DigiByte directly in the wallet. Same AI as the Telegram bot.
- **Forum threads** — Longer-form discussion with replies and upvotes. Announcements, Proposals, Support channels.
- **Inline tipping** — Tap any user to send them a DGB tip. Powered by DigiScope's tip bot infrastructure.
- **Pseudonymous identity** — Register a handle linked to your DGB address. No email, no phone number.
- **Chat messages ephemeral** — 30-day retention for chat. Forum threads permanent.

### Sync Experience
- **Block & peer persistence** — Blockchain state serialized to disk via async JNI callbacks with fast hex encoding. Seed fingerprint check prevents clearing saved blocks on restart.
- **Priority bloom peer** — digiscope.me injected as a priority peer on every sync start. Rescan phase locks to the bloom peer via BRPeerManagerSetFixedPeer to guarantee transaction detection.
- **Crash-safe wallet restore** — Polls peer disconnection before wallet replace to prevent SIGSEGV. `isWalletLoaded()` JNI check prevents double-free on unlock.
- **Connected on restart** — Previously-synced wallets show "Connected" immediately, no misleading "Syncing 0%".

### DigiRunner v2
- **Digi-Robot character** — Chrome metallic robot with LED visor, piston legs, DGB logo chest. Visor brightens on sprint, flickers red on stumble.
- **Sprint + Crouch + Spring Jump** — Hold to sprint and crouch, release to spring jump. Longer hold = higher jump. Momentum carries through the air.
- **3 hearts / game over** — Lose a heart per BTC stack hit. 0 hearts = game over with score breakdown.
- **Combined scoring** — Distance points + coin bonus (×5). Score breakdown on game over screen.
- **Cross-platform leaderboard** — Submit scores tied to DigiScope identity. Leaderboard in app + on [digiscope.me/digirunner](https://digiscope.me/digirunner). All Time / Weekly / Daily periods.
- **3D spinning DGB coins** — Y-axis rotation with official brand colors. 6 Mario-style coin patterns tuned to jump physics.
- **Bitcoin stack obstacles** — 1-3 stacked orange BTC coins. Taller stacks need charged jumps. -2 coins + stumble on hit.
- **Progressive difficulty** — Base speed ramps up over distance. Sprint stacks on difficulty.
- **Standalone play** — Play from Hub "Games" tab or wallet screen. Dedicated touch zone with visual cues.
- **DGB moon** — Official DigiByte logo as a glowing moon over the cyber city skyline.

### DigiScope Integration
- **One-tap login** — "Connect to DigiScope" button in Hub profile. No QR scan needed — wallet requests challenge, signs locally, authenticates directly.
- **Auto session management** — Expired sessions detected (401) and cleared automatically. Re-login prompts shown.
- **PIN lock on background** — Wallet locks UI when app goes to background. SyncService continues running.

## Architecture

```
┌─────────────────────────────────────────────┐
│          Presentation (Kotlin/Compose)       │
│   Material 3 · MVVM · Jetpack Navigation    │
├─────────────────────────────────────────────┤
│          Application (ViewModels/DI)         │
│  Hilt · StateFlow · Coroutines              │
├─────────────────────────────────────────────┤
│            Domain (Kotlin)                   │
│  WalletManager · AssetManager · CoinSelector│
│  DigiIdManager · TorManager · IpfsClient    │
├─────────────────────────────────────────────┤
│           Native Core (C/JNI)               │
│  digibytewallet-core (patched 8.26+)        │
│  SPV Peer Manager · secp256k1 · 5-algo PoW  │
├─────────────────────────────────────────────┤
│         Platform (Android)                  │
│  Keystore · BiometricPrompt · Room+SQLCipher│
└─────────────────────────────────────────────┘
```

All cryptographic operations and peer-to-peer networking happen in the native C core. Raw keys never cross the JNI boundary during normal operation.

## Building

### Prerequisites
- JDK 17
- Android SDK 35
- NDK 27.0.12077973
- CMake 3.22.1

### Quick Build
```bash
git clone --recursive https://github.com/JohnnyLawDGB/digibytewallet-android.git
cd digibytewallet-android
./gradlew :app:assembleMainnetDebug
```

### Reproducible Build (Docker)
```bash
docker build -t dgb-wallet-build docker/
docker run --rm -v "$(pwd)":/build dgb-wallet-build \
  bash -c "./gradlew :app:assembleMainnetRelease && \
  sha256sum app/build/outputs/apk/mainnet/release/app-mainnet-release-unsigned.apk"
```

See [VERIFICATION.md](VERIFICATION.md) for multi-party attestation instructions.

## Module Structure

```
digibytewallet-android/
├── app/        — Android app (Compose UI, navigation, services)
├── core/       — Business logic (wallet, assets, IPFS, Digi-ID, security)
├── native/     — C core + JNI bridge (SPV, crypto, secp256k1)
├── game/       — DigiRunner sync mini-game
└── docker/     — Reproducible build environment
```

## Testing

```bash
# Unit tests (JVM)
./gradlew testMainnetDebugUnitTest

# Instrumented tests (requires device/emulator)
./gradlew connectedMainnetDebugAndroidTest
```

**154 tests, 0 failures** (120 unit + 34 security) across:
- Native JNI bridge (mnemonic generation, address validation, asset detection, proxy)
- Room DAO operations (UTXO segregation, asset balances, migrations v1→v2→v3)
- Security (Keystore encrypt/decrypt, PIN hashing)
- DigiAsset decoder (BitIO parsing, real mainnet transaction data)
- IPFS client (CID verification, gateway fallback)
- Digi-ID (URI parsing, domain extraction)
- Coin selection (asset protection, dust avoidance)
- Price provider (multi-API fallback, oracle interface)

## Development Phases

| Phase | Status | Tag | Features |
|-------|--------|-----|----------|
| 1 — Alpha | Complete | `v3.0.0-alpha1` | Core wallet: send, receive, sync, security, DigiRunner |
| 2 — Beta | Complete | `v3.0.0-beta1` | DigiAssets v2, Digi-ID, IPFS, Tor, DigiScope |
| 3 — Production | Complete | `v3.0.0` | Community Hub, Digi-ID signing, DigiRunner v2, sync stability |
| 3.5 — Hardening | Complete | `v3.0.3` | Security audit, leaderboard, one-tap login, PIN lock, balance caching |
| 4 — Post-launch | Planned | — | Dandelion++ SPV, BIP157/158, v9.26 features, DigiAsset send/receive |

## Security

- **Bug bounty (up to 100,000 DGB):** [BUG-BOUNTY.md](BUG-BOUNTY.md)
- **Responsible disclosure:** [SECURITY.md](SECURITY.md)
- **Threat model:** [THREAT_MODEL.md](THREAT_MODEL.md)
- **Crypto inventory:** [CRYPTO_INVENTORY.md](CRYPTO_INVENTORY.md)
- **Reproducible builds:** [VERIFICATION.md](VERIFICATION.md)
- **Audit summary:** [security/AUDIT-SUMMARY.md](security/AUDIT-SUMMARY.md)

### Key Security Properties
- Seed encrypted at rest with Android Keystore (TEE/Strongbox)
- Key invalidated on biometric enrollment change
- PIN hashed with Argon2id (PBKDF2 fallback)
- Database encrypted with SQLCipher (Keystore-derived passphrase)
- No analytics, no telemetry, no proprietary dependencies
- FLAG_SECURE on seed display, filterTouchesWhenObscured on send confirmation
- R8 shrinking only — no obfuscation, for auditability

## Related

- **Dandelion++ DIP:** [DigiByte-Core/dips#15](https://github.com/DigiByte-Core/dips/pull/15) — Extending stem-phase privacy to SPV wallets
- **DigiNexum Marketplace:** [diginexum.trade](https://diginexum.trade/) — DigiAsset marketplace by RenzoDGB
- **DigiScope:** [digiscope.me](https://digiscope.me) — DigiByte community platform
- **Original wallet:** [DigiByte-Core/digibytewallet-android](https://github.com/DigiByte-Core/digibytewallet-android)

## Contributing

This is an open-source project. Contributions welcome via pull requests.

- Fork the repo
- Create a feature branch
- Submit a PR with tests
- All builds must be reproducible

## License

MIT — see [LICENSE](LICENSE)
