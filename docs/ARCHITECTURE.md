# Architecture

Kotlin + Jetpack Compose front end, a small Kotlin business-logic core,
and a C submodule inherited from the breadwallet-core lineage, bridged
into Kotlin via JNI. No explorer REST backend, no central server in the
transaction data path.

## Module layout

Four top-level Gradle modules. The separation is load-bearing: the
native submodule must not depend on Android APIs, the core must not
depend on Compose, and the game module must not depend on either core
or native.

| Module | Purpose | Depends on |
|--------|---------|-----------|
| `app/` | Compose UI, navigation, Hilt wiring, Android services, onboarding flows | `core`, `native`, `game` |
| `core/` | Wallet manager, UTXO management, transaction building, Digi-ID, DigiAsset detection, Tor client, Hub client, security primitives, price provider, Room persistence | `native` |
| `native/` | C core (breadwallet-core fork at `native/src/main/jni/digibytewallet-core/`) + JNI bridge (`native/src/main/jni/bridge/`) + secp256k1 submodule | none (pure JNI → Kotlin) |
| `game/` | DigiRunner sync-overlay mini-game — standalone Compose + physics, shown during initial sync | none |

### `app/` highlights

- `MainActivity.kt` — entry point, PIN gate, Digi-ID deep-link capture,
  foreground reconnect hook.
- `ui/navigation/AppNavigation.kt` — Compose navigation graph, bottom
  nav bar, wallet-unlock state observation.
- `ui/wallet/` — WalletScreen (home), SendScreen, ReceiveScreen,
  TransactionDetailScreen, plus their ViewModels.
- `ui/onboarding/` — SeedDisplayScreen, SeedVerifyScreen,
  MnemonicInputScreen, RecoveryDateScreen, PinSetupScreen, plus
  OnboardingViewModel.
- `ui/settings/` — AboutScreen, SecuritySettingsScreen, Tor controls.
- `service/SyncService.kt` — foreground service that runs SPV sync,
  keepalive loop, Tor wiring; also `SyncWorker.kt` for WorkManager
  background catch-ups.
- `di/AppModule.kt` — Hilt bindings (Room DB, OkHttp client, PinManager,
  TorManager, KeyStoreManager, WalletManager, etc.).

### `core/` highlights

- `WalletManager.kt` — wallet lifecycle (create, load, lock/unlock,
  wipe), sync state propagation, BIP84 upgrade orchestration.
- `UtxoManager.kt` — UTXO enumeration + selection for spending.
- `TransactionBuilder.kt` — builds, signs, and broadcasts tx via the
  native bridge.
- `bridge/NativeBridge.kt` — the single Kotlin ↔ C surface. Every
  native interaction goes through this object's `external fun`s.
- `security/KeyStoreManager.kt` — Android Keystore wrapper, wraps the
  AES-GCM master key that seals the BIP39 seed on disk.
- `security/PinManager.kt` — PIN hashing (Argon2id preferred, PBKDF2
  fallback) and verification; hash stored in EncryptedSharedPreferences.
- `security/BiometricAuth.kt` — biometric prompt wrapper; UI-gate only,
  does not bind to Keystore key unlock.
- `digiid/DigiIdManager.kt` — Digi-ID challenge/response signing.
- `tor/TorManager.kt` — kmp-tor exec-mode client, StateFlow lifecycle.
- `hub/HubWebSocket.kt` — pseudonymous chat/forum client
  (`wss://api.digiscope.me/api/hub/ws`).
- `asset/AssetManager.kt` — DigiAssets v2 OP_RETURN detection.
- `ipfs/IpfsClient.kt` — IPFS gateway client for DigiAsset metadata,
  with CID verification across three gateways.
- `PriceProvider.kt` — CoinGecko + Binance price aggregation.
- `db/` — Room entities and DAOs (WalletConfig, Transaction, leaderboard
  entries, etc.).

### `native/` highlights

- `native/src/main/jni/digibytewallet-core/` — the C core submodule.
  Forked from breadwallet-core, with DigiByte-specific constants
  (`BRChainParams.h`), protocol version, and v8.26 checkpoints.
  Submodule URL: `https://github.com/JohnnyLawDGB/digibytewallet-core.git`
  (branch `phase1-modernization`).
- `native/src/main/jni/bridge/jni_wallet.c` — wallet JNI functions
  (createWallet, recoverWallet, getBalance, getTransactionDetails,
  signMessage, getDerivationPath, etc.).
- `native/src/main/jni/bridge/jni_peer.c` — peer/sync JNI functions
  (startSync, stopSync, getPeerCount, priority peer injection).
- `native/src/main/jni/secp256k1/` — upstream bitcoin-core/secp256k1,
  unmodified.

## Data flow

### SPV sync

```
Android foreground service (SyncService.kt)
    └─ starts Tor (if enabled) and wires SOCKS5 into C core
    └─ fetches peer list from api.digiscope.me/api/peers/bloom
    └─ calls NativeBridge.startSync()
          └─ JNI → jni_peer.c startSync
                └─ _injectPriorityPeer(digiscope.me + bloom peers)
                └─ BRPeerManagerConnect()
                      └─ opens TCP sockets to peers
                      └─ sends BIP 37 filterload with wallet addresses
                      └─ receives merkleblock + tx responses
                      └─ BRWalletRegisterTransaction for our txs
          └─ callbacks back to Kotlin via bridge_txStatusUpdate,
             bridge_saveBlocks, bridge_savePeers, bridge_syncStopped
```

Current SPV path is BIP 37 bloom filters. Phase 1 of the roadmap
replaces this with BIP 157/158 compact filters as the primary mode,
keeping bloom as a fallback.

### Send transaction

```
SendScreen (Compose)
    └─ user enters address + amount + optional custom fee
    └─ SendViewModel validates, computes fee estimate
    └─ Confirm button enabled only when peerCount > 0 (v3.5.10+)
    └─ SendViewModel.send()
          └─ TransactionBuilder.buildTransaction(address, amount, fee)
                └─ UtxoManager.selectUtxos(target)
                └─ NativeBridge.buildTransaction(inputs, outputs)
                      └─ C core builds raw tx bytes
          └─ TransactionBuilder.signAndBroadcast(rawTx)
                └─ NativeBridge.signTransaction(rawTx)
                      └─ C core signs with derived keys
                └─ NativeBridge.publishTransaction(signedTx)
                      └─ C core relays via BRPeerManager to peers
          └─ WalletViewModel.transactions flow refreshed on next poll
```

### Receive transaction

```
Peer gossips inv(MSG_TX or MSG_BLOCK) matching wallet bloom filter
    └─ C core: _peerRelayedPeers / BRPeerManager state update
    └─ bridge_txStatusUpdate callback fires
          └─ Kotlin NativeCallback.onTransactionStatusUpdate
          └─ WalletViewModel poll picks up the new tx next cycle
          └─ Compose UI recomposes balance + history
```

### Startup (unlocked wallet)

```
MainActivity.onCreate
    └─ Hilt injects dependencies
    └─ WalletManager.walletState observed
    └─ if state == Locked → PIN screen
    └─ on PIN correct → PinManager.verifyPin succeeds
          └─ KeyStoreManager.decryptSeed (AES-GCM)
          └─ NativeBridge.createWalletFromBytes(seed)
                └─ C core builds HD wallet tree, persists addresses
          └─ WalletManager.walletState → Unlocked
    └─ AppNavigation renders wallet UI
    └─ SyncService.startForegroundService (kicks off data flow above)
    └─ MainActivity.onResume checks peerCount, kicks startSync if 0
```

## Persistence layers

| Layer | Holds | Key/Path | Encryption |
|-------|-------|----------|-----------|
| Android Keystore | AES-256 master key (wraps the seed) | Alias `dgb_wallet_master` | Hardware-backed where available (TEE/StrongBox); key never leaves the secure element |
| EncryptedSharedPreferences | PIN hash (Argon2id + salt), KDF version | `dgb_pin_store` | AES-256-GCM with Keystore-wrapped key |
| SharedPreferences | Encrypted seed + IV + seed fingerprint | `dgb_wallet_seed` | Seed encrypted with Keystore master key (AES-GCM, 96-bit IV, 128-bit tag) |
| SharedPreferences | Saved blocks + peers + `has_synced` flag + last balance + saved tx blob | `dgb_sync_data` | Plaintext (public chain data) |
| SharedPreferences | DigiScope Hub JWT | `dgb_digiscope` | Plaintext |
| SharedPreferences | Bloom seeder cached response | `dgb_bloom_peers` | Plaintext |
| SharedPreferences | Room DB passphrase (encrypted) | `dgb_db_key` | Keystore-wrapped, fed to SQLCipher |
| Room / SQLCipher | WalletConfig, Transaction metadata, DigiAsset cache, leaderboard entries, forum cache | `dgb.db` | SQLCipher page-level encryption via the `dgb_db_key` passphrase |

### What the seed goes through on persist

1. BIP39 mnemonic generated (or accepted from recovery input).
2. Converted to 64-byte PBKDF2-HMAC-SHA512 seed.
3. Encrypted with the Keystore master key: `AES/GCM/NoPadding`, 96-bit
   random IV, 128-bit tag.
4. Concatenated: `IV || ciphertext || tag || seedFingerprint`.
5. Stored in SharedPreferences under `dgb_wallet_seed`.
6. In-memory `ByteArray` is zeroed in a `finally` block once the native
   wallet has been built.

On load, the reverse: decrypt via Keystore, pass as `jbyteArray` to
`createWalletFromBytes` / `recoverWalletFromBytes`, zero the local
buffer.

## C core ↔ Kotlin boundary

Single Kotlin object — `core.bridge.NativeBridge` — owns every JNI
entry point. All native calls are `external fun` on this object.
Callbacks from C code into Kotlin go through a registered
`NativeCallback` interface (set via `NativeBridge.setCallbackHandler`);
the C side caches the JNIEnv pointer in `JNI_OnLoad` at
`jni_wallet.c:36`.

Ownership boundaries:

- **C core owns** the wallet tree, UTXO set, tx registry, peer manager,
  merkle proof validation, address derivation, signing. Persistence of
  blocks/peers/txs is driven from the C side via callbacks that hand
  opaque byte arrays up to Kotlin for storage.
- **Kotlin owns** the seed blob at rest, the Android lifecycle, the UI
  state, the Room DB for display/metadata, network I/O for non-P2P
  services (Hub, price feeds, IPFS, seeder).

The bridge intentionally keeps the native surface small (currently
~20 `external fun`s). New capabilities lean on extending existing
methods where possible.

## Build flavors

`app/build.gradle.kts` declares a `network` flavor dimension:

- `mainnet` — production. Points native build at the mainnet chain
  params. Default for all releases.
- `digiTestnet` — testnet. Application ID suffix `.testnet` so both can
  coexist on a device. Points native build at testnet params.

CMake flags flow through as compile-time constants; the C core inspects
them to select between `BRMainNetParams` and `BRTestNetParams`.

No regtest flavor currently; considered low-priority, tracked in the
roadmap.

## External network dependencies

| Endpoint | Purpose | Trust model |
|----------|---------|-----------|
| DigiByte P2P peers (port 12024) | Blockchain data (headers, blocks, filters, tx) | SPV — trust verified cryptographically via block-header chain and (Phase 1) filter-header quorum |
| `api.digiscope.me/api/peers/bloom` | Peer discovery bootstrap | Soft-trust: wallet author operates. Compromise delays peer discovery but doesn't forge chain data. Demoted in Phase 1 |
| `api.digiscope.me/api/hub/*` | DigiScope community forum + chat | Pseudonymous; certificate-pinned; no tx data sent |
| `api.coingecko.com`, `api.binance.com` | DGB/USD and DGB/PHP rates | Cosmetic; price is display-only, not used for signing |
| `trustless-gateway.link`, `dweb.link`, `ipfs.io` | DigiAsset metadata (IPFS) | CID-verified cryptographically; gateway compromise cannot inject wrong content |
| `github.com/JohnnyLawDGB/digibytewallet-android/releases/latest` | Update checker | Fetches release metadata only; version string is public info |
| Tor control/SOCKS port (local) | Optional traffic anonymization | kmp-tor exec-mode, separate process |

Everything not in this table is out of bounds — see `ROADMAP.md`
"Anti-patterns we will not ship" for the lines we don't cross.
