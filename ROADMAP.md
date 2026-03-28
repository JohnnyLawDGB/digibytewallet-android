# DigiByte Wallet — Development Roadmap

> **Current status: v3.0.3-beta — VERY EARLY BETA**
> This wallet is under active development. Only move small amounts of DGB. Do not transfer DigiAssets to it.

## Completed (v3.0.0 — v3.0.3)

### Phase 1: Core Wallet
- [x] Full Kotlin/Compose rewrite
- [x] SPV peer-to-peer sync (no server dependency)
- [x] Send, receive, QR scanning
- [x] Hardware-backed seed encryption (Android Keystore, AES-256-GCM)
- [x] BiometricPrompt + Argon2id PIN
- [x] BIP39 mnemonic generation and recovery
- [x] DigiByte Core 8.26+ protocol compatibility (v70019)

### Phase 2: Features
- [x] DigiAssets v2 detection and display (OP_RETURN decoder)
- [x] Digi-ID authentication with real BRKeyCompactSign signing
- [x] IPFS metadata with CID hash verification
- [x] Tor routing (SOCKS5 proxy in C core)
- [x] DigiScope integration (Hub auto-login)

### Phase 3: Community Hub
- [x] Real-time chat via WebSocket (5 channels + Enigma AI)
- [x] Forum threads with replies and upvotes
- [x] Pseudonymous handle registration
- [x] Inline DGB tipping

### Phase 2.5: Sync Stability
- [x] Block & peer persistence (SharedPreferences, async hex encoding)
- [x] Priority bloom peer injection (digiscope.me)
- [x] Crash-safe wallet restore (isWalletLoaded, peer drain polling)
- [x] Wallet creation timestamp persistence for correct rescan
- [x] "Verifying transactions..." UX during rescan
- [x] Connected state on restart (no Syncing 0%)

### Phase 3.5: Security Hardening
- [x] KeyStore key bound to user authentication
- [x] Digi-ID callback domain validation
- [x] Certificate pinning for api.digiscope.me
- [x] HTTP callbacks blocked
- [x] Log redaction (address, response body)
- [x] secure_zero compiler barrier
- [x] Atomic wallet wipe
- [x] 34-test security suite published

### DigiRunner v2
- [x] Digi-Robot character (chrome body, LED visor, piston legs)
- [x] Sprint + crouch + charged jump mechanics
- [x] 3D Y-axis spinning DGB coins (official brand colors)
- [x] Bitcoin coin stack obstacles (-2 coin penalty)
- [x] Mario-style coin patterns (6 pattern types)
- [x] Progressive difficulty ramp
- [x] DGB logo moon (official SVG path)

---

## In Progress / Next Up

### Testing (Immediate Priority)
- [ ] **Send DGB** — end-to-end send on mainnet with fee estimation
- [ ] **Receive DGB** — generate address, share QR, confirm receipt
- [ ] **Wallet restore** — wipe device, restore from 12/24 words, verify balance recovered
- [ ] **Multi-address receive** — verify address rotation works correctly
- [ ] **Fee estimation** — verify against real mempool conditions
- [ ] **Edge cases** — network loss mid-sync, app kill during transaction, low battery
- [ ] **Instrumented tests on device** — 54 existing tests need CI runner

### DigiAsset Support (Phase 4)
- [ ] **Asset send** — construct asset transfer transactions with UTXO protection
- [ ] **Asset receive** — detect incoming assets via bloom filter
- [ ] **Asset display** — rich metadata from IPFS (images, names, descriptions)
- [ ] **UTXO segregation verification** — ensure 700-sat asset markers never spent as fees
- [ ] **Asset history** — transaction list with asset type indicators
- [ ] **DigiNexum marketplace link** — view assets on diginexum.trade

### Tor Verification
- [ ] **Privacy audit** — verify all connections route through Tor when enabled
- [ ] **Tor circuit monitoring** — show connection status in settings
- [ ] **DNS leak prevention** — ensure no cleartext DNS queries escape
- [ ] **Tor bridge support** — for censored networks
- [ ] **Performance benchmarks** — sync speed over Tor vs direct

---

## Planned (Phase 5+)

### Bloom Filter / SPV Infrastructure
- [ ] **Additional bloom nodes** — deploy 2-3 more `peerbloomfilters=1` nodes globally
- [ ] **Community node registry** — let users contribute SPV-enabled nodes
- [ ] **Node health monitoring** — track uptime and bloom support of known nodes
- [ ] **Automatic node discovery** — prefer nodes advertising NODE_BLOOM in service bits

### Personal Node Configuration
- [ ] **Connect to own node** — settings screen to enter IP:port of personal DigiByte node
- [ ] **Trusted node mode** — skip peer discovery, connect exclusively to user's node
- [ ] **Local network discovery** — auto-detect DigiByte nodes on LAN
- [ ] **RPC integration** — optional RPC connection for enhanced features (address indexing, etc.)

### DigiByte Core v9.26 Integration
- [ ] **Dandelion++ SPV stem submission** — [DIP filed](https://github.com/DigiByte-Core/dips/pull/15). Route transactions through stem phase for origin privacy. First-in-class for any UTXO chain SPV wallet.
- [ ] **Pricing oracles** — fetch DGB/USD price from v9.26's oracle system (DigiDollar infrastructure)
- [ ] **BIP157/158 compact block filters** — replace bloom filters with privacy-preserving block filters. Eliminates the `peerbloomfilters` dependency entirely. This is the long-term fix for SPV privacy and peer compatibility.
- [ ] **v9.26 protocol handshake** — update to support new protocol features when v9.26 ships

### Security (Remaining)
- [ ] **CRITICAL-2 mitigation** — minimize `g_seed` lifetime in process memory
- [ ] **CRITICAL-3 fix** — refactor `loadSeed()` to use `ByteArray` with explicit zeroing
- [ ] **Root/emulator detection** — warn users on rooted devices
- [ ] **FLAG_SECURE audit** — verify all sensitive screens block screenshots
- [ ] **Third-party security audit** — engage external auditor before production release
- [ ] **Frida dynamic analysis** — verify seed cannot be extracted at runtime
- [ ] **Reproducible release builds** — Docker build environment for deterministic APKs

### UX Polish
- [ ] **Transaction history** — full send/receive history with confirmations
- [ ] **Address book** — save frequently-used addresses
- [ ] **Currency conversion** — live DGB/USD/EUR display
- [ ] **Push notifications** — incoming transaction alerts
- [ ] **Widget** — home screen balance widget
- [ ] **Dark/light theme** — theme toggle in settings
- [ ] **Localization** — multi-language support
- [ ] **Onboarding** — guided first-run experience

### Platform
- [ ] **Google Play Store** — publish to Play Store after production audit
- [ ] **F-Droid** — publish to F-Droid for privacy-focused users
- [ ] **iOS port** — evaluate React Native or native Swift rewrite
- [ ] **Desktop companion** — optional desktop sync via QR pairing

---

## Contributing

This is open source. Contributions welcome:
1. Fork the repo
2. Create a feature branch
3. Submit a PR with tests
4. All builds must be reproducible

**Priority areas for contributors:**
- Bloom filter node operators (run `peerbloomfilters=1`)
- DigiAsset testing (asset holders willing to test transfers)
- Security review (cryptography, JNI boundary, network analysis)
- UI/UX feedback (especially on mobile usability)
- Localization (translation PRs welcome)

**Report bugs:** [GitHub Issues](https://github.com/JohnnyLawDGB/digibytewallet-android/issues)

**Discuss:** [DigiScope Hub](https://digiscope.me) or [DigiByte Telegram](https://t.me/DigiByteCoin)
