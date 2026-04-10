# DigiByte Android Wallet — Development Roadmap

## Current State (v3.0.12-beta, April 2026)

Core wallet functionality is production-tested on mainnet: send, receive, SPV sync with bloom peer discovery, Digi-ID, Community Hub, DigiRunner game. Seed security hardened (CRITICAL-2/3 remediated), 42 security tests, pre-publish test suite across 4 Android versions.

**Architecture:** Kotlin + Jetpack Compose UI, C native core (breadwallet-core lineage) via JNI, hardware-backed AES-256-GCM seed encryption, Room + SQLCipher database.

**Key insight from competitive research:** We are the only actively maintained wallet on the breadwallet-core C lineage (Litecoin Foundation abandoned theirs). No competing SPV wallet has implemented BIP157/158 compact block filters. Our bloom seeder is ahead of both Dogecoin and Litecoin wallets for peer discovery.

---

## Phase 1: Development Infrastructure (v3.1.0)

*Foundation work. No user-facing features — but everything after this ships faster and more reliably.*

### 1.1 Release Signing + Automated Pipeline
- Generate 25-year RSA release keystore (Play Store requirement)
- Store in GitHub Secrets, sign in CI — stop shipping debug APKs
- Enhance `release.yml`: `git tag v3.1.0` → build, sign, create GitHub Release, upload APK to digiscope.me, update download page hash — fully automated
- **Why:** We've been manually scp'ing APKs and sed'ing HTML. Every manual step is a crash risk (wrong hash, stale APK, forgot to bump version). Competitors automate this.

### 1.2 Conventional Commits + release-please
- Enforce commit format: `feat:`, `fix:`, `security:`, `test:`
- Google's `release-please` creates a "Release PR" accumulating changes
- Merge the PR → version bump + CHANGELOG.md generated automatically
- **Why:** We've been writing release notes by hand and forgetting changes. Commit messages already describe the work — automate the changelog.

### 1.3 Maestro E2E Test Suite
- Replace fragile bash tap-coordinate tests with YAML-based Maestro flows
- Finds UI elements by text/accessibility labels, not pixel positions
- Critical flows: `create-wallet.yaml`, `recover-wallet.yaml`, `send-dgb.yaml`, `receive-dgb.yaml`
- Run in CI via Maestro GitHub Action
- **Why:** Our bash test suite catches crashes but can't test user flows. The Todoist team went from 50% Espresso pass rates to 99%+ with Maestro. We need this to catch "crash after PIN setup" before users do.

### 1.4 Code Quality Gates
- **Detekt + ktlint** — Kotlin static analysis and formatting, fail CI on violations
- **Renovate** — automated dependency update PRs for Compose BOM, Kotlin, AndroidX
- **MobSF in CI** — automate the security scan that already exists as a manual report
- **Why:** Play Store review and F-Droid submission both benefit from clean, well-maintained code. Catches issues before they become bugs.

### 1.5 F-Droid Submission
- Create metadata YAML for fdroiddata repository
- Verify Docker reproducible build matches (already 90% there)
- Submit for packaging
- **Why:** Privacy-conscious crypto users expect F-Droid. Electrum is there. We should be too.

---

## Phase 2: User-Facing Features (v3.2.0)

*Features that every competing wallet has. Users switching from BlueWallet/Mycelium/Edge expect these.*

### 2.1 Transaction Detail Screen
- Tap a transaction → full breakdown: block height, confirmations, inputs/outputs, fee in DGB + sat/vB, fiat value at time of tx
- Transaction labels (user-editable, stored in Room)
- CSV export of transaction history
- **Model:** Mycelium's transaction detail view

### 2.2 Watch-Only Wallet
- Import xpub to monitor a cold storage wallet without exposing keys
- Shows balance and transaction history, no send capability
- Can be promoted to full wallet by importing seed later
- **Model:** BlueWallet's watch-only mode

### 2.3 DigiAsset Send
- Full UTXO transfers of DigiAssets (detection/display already complete)
- BitIO encoder, asset transaction builder, UTXO blacklist protection
- Design spec ready: `docs/superpowers/specs/2026-04-03-digiasset-send-design.md`
- **Unique to DigiByte** — no Bitcoin wallet needs this

### 2.4 Coin Control / UTXO Management
- View all UTXOs with amounts, ages, addresses
- Freeze individual UTXOs (excluded from automatic coin selection)
- Manual UTXO selection in the send flow
- **Model:** BlueWallet and Electrum's coin control

### 2.5 Sweep Paper Wallet
- Scan a WIF private key QR code
- Create a transaction sweeping all funds to the wallet's receive address
- **Model:** Dogecoin wallet's sweep feature

---

## Phase 3: Privacy + Network (v4.0.0)

*Fundamental protocol improvements. Makes the wallet genuinely ahead of competitors, not just at parity.*

### 3.1 BIP157/158 Compact Block Filters
- First mobile UTXO wallet with native compact filter support
- Client never reveals wallet addresses to any peer (unlike bloom filters)
- GCS filter decoder in C core (~400 lines)
- BIP157 message handlers + sync state machine in BRPeerManager
- Dual-mode: compact filters when available, bloom fallback
- Bloom seeder extended to discover `NODE_COMPACT_FILTERS` (0x40) peers
- **Challenge:** DigiByte's ~20M blocks need checkpointed filter headers for mobile
- **Prerequisite:** Enable `blockfilterindex=1` + `peerblockfilters=1` on digiscope.me node, measure filter sizes

### 3.2 Tor Integration ✅
- kmp-tor 2.4.0 exec mode — Tor runs as a separate process for crash isolation
- TorManager wraps TorRuntime with StateFlow lifecycle, 90s bootstrap timeout
- P2P peers routed via SOCKS5 (`NativeBridge.setSocksProxy`), HTTP via OkHttp proxy
- DNS leak prevention: custom Dns resolver + SafeSocks 1 + OkHttp unresolved SOCKS addressing
- User toggle in Network Info → Privacy, wallet badge when connected
- Graceful degradation: wallet syncs directly if Tor fails
- **Remaining:** verify no DNS leaks on-device, test circuit isolation, combine with BIP157/158

### 3.3 Dandelion++ Integration (v9.26)
- Transaction broadcast privacy — obscures which node originated a transaction
- Requires v9.26 node support
- Combined with Tor + compact filters = strong privacy stack

### 3.4 v9.26 Core Integration
- Update C core for DigiByte v9.26 protocol changes
- Dandelion++ support
- Pricing oracles
- Default bloom filters ON (reduces dependency on bloom seeder)

---

## Phase 4: Distribution (v4.1.0)

### 4.1 Google Play Store
- Requires: release signing (Phase 1.1), passing Play Store review
- Fastlane for automated Play Store deployment
- Internal → beta → production track progression

### 4.2 Hardware Wallet Support
- PSBT (BIP 174) for air-gapped signing
- Coldcard via QR code workflow (no USB needed)
- Watch-only wallet (Phase 2.2) + PSBT = full cold storage workflow

### 4.3 Multi-Account Support
- Multiple HD accounts under one seed
- Different derivation paths for different purposes
- **Model:** Mycelium's account types

---

## Testing Strategy

| Layer | Tool | Runs When |
|-------|------|-----------|
| Unit tests | JUnit + MockK | Every CI push |
| Security tests | Custom (42 tests) | Every CI push |
| Static analysis | Detekt + ktlint | Every CI push |
| Security scan | MobSF | Every CI push |
| E2E flows | Maestro | Every CI push (API 33 emulator) |
| Multi-version | pre-publish-test.sh | Before every release tag |
| Device matrix | Firebase Test Lab | Before milestone releases |
| Mainnet | Manual on physical device | Before milestone releases |

---

## Versioning Policy

- **3.0.X** — patch releases (bug fixes, stability) — increment on every publish
- **3.X.0** — minor releases (new features, Phase 2 items)
- **X.0.0** — major releases (protocol changes, Phase 3/4)
- Every publish gets a version bump — no reusing version numbers
- Pre-publish test suite must pass before any release

---

## What We're NOT Building

- Multi-coin support (this is a DigiByte wallet, not Edge)
- Built-in exchange/swap (can link to external services)
- Custodial Lightning-style L2 (DigiByte doesn't have Lightning)
- Server-dependent sync (we stay true SPV — no Blockbook dependency)
- Closed-source anything (MIT license, full transparency)
