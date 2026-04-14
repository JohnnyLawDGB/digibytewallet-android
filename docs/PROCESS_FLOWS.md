# Process Flows

Step-level flows for the operations that matter to sovereignty,
security, or incident response. Each step names the function or file
that executes it so a reviewer can trace the code path without
spelunking.

Flows covered:
- [Create new wallet](#create-new-wallet)
- [Recover from seed](#recover-from-seed)
- [Send transaction](#send-transaction)
- [Receive and confirm transaction](#receive-and-confirm-transaction)
- [App start with existing wallet (unlock → sync)](#app-start-with-existing-wallet)
- [Upgrade between versions](#upgrade-between-versions)

## Create new wallet

Path from first launch through seed backup to a unlocked home screen.

1. **First launch — no wallet detected.**
   `WalletManager.walletState` starts in `NoWallet`. MainActivity
   navigates to the onboarding graph.
2. **User taps "Create New Wallet"** on the landing screen.
   `OnboardingViewModel.startCreateWallet()` begins the flow.
3. **Generate entropy.** 16 bytes of `SecureRandom` output →
   `NativeBridge.generateMnemonic(entropy)` → C core emits a 12-word
   BIP 39 mnemonic using the English wordlist in
   `native/…/BRBIP39WordsEn.h`.
4. **Display seed to user.** `SeedDisplayScreen.kt` renders the
   mnemonic; window flagged `FLAG_SECURE` to block screenshot capture
   and recent-apps thumbnails. User confirms they've written it down.
5. **Verify seed.** `SeedVerifyScreen.kt` quizzes 3 random positions
   from the mnemonic. Failure to recall → back to step 4. Success →
   step 6.
6. **PIN setup.** `PinSetupScreen.kt` collects a 6-digit PIN twice.
   `PinManager.setPin(pin)`:
   - Generates 32-byte salt (`SecureRandom`).
   - Hashes PIN with Argon2id (t=3, m=64 MiB, p=4). Falls back to
     PBKDF2-HMAC-SHA256 (600k iterations) if Argon2 unavailable.
   - Stores `{ kdfVersion, salt, hash }` in
     `EncryptedSharedPreferences("dgb_pin_store")`.
7. **Biometric enrollment (optional).** If the device has a usable
   biometric enrollment, `BiometricAuth.enroll()` stores a preference;
   biometric later unlocks the UI but does not re-wrap the Keystore
   key.
8. **Seal the seed.**
   - Mnemonic → 64-byte BIP 39 seed via PBKDF2-HMAC-SHA512.
   - `KeyStoreManager.ensureMasterKey()` creates (or reuses) the
     AES-256 master key in the Android Keystore, alias
     `dgb_wallet_master`, hardware-backed where available.
   - `KeyStoreManager.encryptSeed(seed)`:
     `AES/GCM/NoPadding`, 96-bit random IV, 128-bit auth tag.
   - Stored in `SharedPreferences("dgb_wallet_seed")` as
     `IV || ciphertext || tag || fingerprint`.
   - Local `ByteArray` is zeroed in `finally`.
9. **Build the native wallet.**
   `NativeBridge.createWalletFromBytes(seed)` passes the `jbyteArray`
   to `jni_wallet.c`; the C core:
   - Computes BIP 32 master key via HMAC-SHA512 with `"Bitcoin seed"`
     key.
   - Derives BIP 84 account `m/84'/20'/0'`.
   - Builds the wallet, populates `allAddrs` with external + change
     addresses up to a gap limit.
   - Zeroes the seed buffer (`secure_zero()` on the C stack).
10. **Navigate to home.** `WalletManager.walletState` transitions to
    `Unlocked`. `MainActivity.startSyncService()` fires; sync begins
    (see [App start](#app-start-with-existing-wallet)).

## Recover from seed

Path from landing screen through mnemonic input to a populated wallet
at the chain tip.

1. **User taps "Recover Existing Wallet"** on landing.
   `OnboardingViewModel.startRecovery()` begins.
2. **Mnemonic entry.** `MnemonicInputScreen.kt` presents 12-word or
   24-word input. Per-word `WordInputField` components with live
   autocomplete against the BIP 39 English wordlist.
3. **Normalize and validate.** Mnemonic normalized to lowercase,
   stripped of extra whitespace. `NativeBridge.validateMnemonic` checks
   that each word is in the wordlist and the BIP 39 checksum is
   correct. Failure → inline error, user corrects.
4. **Recovery date.** `RecoveryDateScreen.kt` offers ranges
   ("Last month", "Last year", "I don't remember") that map to a
   `syncFromTime` timestamp. "I don't remember" means full rescan
   from genesis.
5. **PIN setup.** Same as create-new-wallet step 6.
6. **Dual-scan key tree creation.**
   - `NativeBridge.recoverWalletFromBytes(seed, syncFromTime,
     allowLegacy=true)` passes the seed to the C core.
   - The C core builds BOTH:
     - A BIP 84 address tree at `m/84'/20'/0'`.
     - A legacy tree at `m/0H/{0|1}/i` using HMAC key
       `"DigiByte seed"` (see `docs/derivation/LEGACY_DERIVATION.md`).
   - Gap-scan both trees against the incoming SPV data; whichever has
     on-chain history populates the wallet. `hasLegacyKey` is set
     true if the legacy tree has any matches.
7. **Seal seed.** Same as create-new-wallet step 8.
8. **Sync.** Same as [App start](#app-start-with-existing-wallet)
   with `syncFromTime` set to the recovery date, so the peer manager
   requests headers and filters only from that point forward.
9. **UI feedback.** Sync progress shown as percentage; transactions
   appear in the history list as the rescan encounters them.

## Send transaction

Path from "Send" button tap through broadcast to tx visible in history.

1. **Enter Send screen.** `SendScreen.kt`. `SendViewModel`
   observes `WalletViewModel.peerCount` — if zero, Review & Send
   button is disabled and a banner warns the user (see v3.5.10
   release notes).
2. **Address input.** User types, scans QR, or pastes. If a
   `digibyte:` URI, `DigiByteUri.parse` extracts amount/label/message.
   `NativeBridge.validateAddress(addr)` returns whether the address is
   valid bech32 or legacy P2PKH.
3. **Amount input.** DGB or fiat; `WalletViewModel.price` provides
   the conversion for display. Final amount is satoshis internally.
4. **Fee selection.**
   - Default: fixed tier at DigiByte's minimum relay fee (`DEFAULT_FEE_PER_KB`
     = 100 sat/byte). Confirms in ~15 s on mainnet.
   - Custom: user enters total fee in DGB; `SendViewModel` warns amber
     if below relay minimum, red if zero.
5. **User taps "Review & Send."** `SendViewModel.requestConfirm()`
   → `SendState.Confirming`. `SendConfirmationDialog` shows the final
   values.
6. **Biometric or PIN auth.** If biometric enabled and available,
   `BiometricAuth.authenticate()` prompts; on success, proceed. Else
   fall through to PIN prompt.
7. **Build transaction.**
   - `UtxoManager.selectUtxos(target)` picks UTXOs to spend. Current
     algorithm: largest-first (Phase 3 adds coin control).
   - `NativeBridge.buildTransaction(inputs, outputs, feeSat)`:
     - Constructs raw tx bytes in the C core.
     - Assigns change output to the wallet's next unused change
       address in the BIP 84 tree.
8. **Sign.** `NativeBridge.signTransaction(rawTx)`:
   - C core iterates tx inputs, derives each input's private key via
     the HD tree, signs with ECDSA + SIGHASH_ALL, builds the witness
     (for P2WPKH) or scriptSig (for P2PKH).
9. **Broadcast.** `NativeBridge.publishTransaction(signedTx)`:
   - C core: `BRPeerManagerPublishTx(manager, tx)`.
   - Peer manager sends `inv MSG_TX` to connected peers; on `getdata`
     response, sends the tx bytes.
   - Local wallet registers the tx via
     `BRWalletRegisterTransaction` — appears in `allTx` immediately,
     balance updates.
10. **UI feedback.** `SendState.Success` rendered with the txid and a
    confirmation screen.
11. **Post-broadcast.** `WalletViewModel.transactions` flow picks up
    the new tx on the next 5s poll cycle. The tx shows with 0
    confirmations until a peer relays back the block containing it.

## Receive and confirm transaction

Path from a sender broadcasting a tx to our address through to a
confirmed entry in history.

1. **Peer gossip.** Some peer on the network has the tx in its
   mempool and sends us `inv MSG_TX` during normal p2p chatter.
2. **Bloom match (current path).** The tx matches our bloom filter
   (output to one of our addresses, or spending a UTXO we
   previously owned). Peer sends the full tx.
3. **Wallet register.** `BRWalletRegisterTransaction` is called in
   the C core. The tx is added to `allTx`; `BRWalletBalance` updates
   on the next query.
4. **Callback to Kotlin.** `bridge_txStatusUpdate` callback fires;
   Kotlin `NativeCallback.onTransactionStatusUpdate` receives it.
5. **UI poll picks up the tx.** `WalletViewModel.pollNativeBalance`
   runs every 5 s; next cycle fetches `getBalance` and
   `getTransactionDetails`; new tx appears with 0 confirmations.
6. **Block confirmation.** Some time later, a miner includes the tx
   in a block. Our peer forwards the block header; C core's
   `BRPeerManager` processes the merkleblock; the tx's blockHeight
   is set to the confirming block.
7. **Confirmation count updates.** `WalletViewModel` computes
   confirmations as `currentHeight - txHeight + 1` on each poll
   cycle. Compose UI recomposes the tx row.
8. **Persistence.** On each `bridge_saveBlocks` callback (every few
   hundred blocks during sync, or as new blocks arrive at tip), the
   serialized block headers are hex-encoded and written to
   `SharedPreferences("dgb_sync_data")` under `saved_blocks`. The tx
   blob is persisted on `bridge_savePeers` / sync-complete via
   `getSerializedTransactions`.

## App start with existing wallet

Cold start through to data-flowing sync.

1. **Process start.** Android spawns `io.digibyte` process;
   `MainActivity.onCreate` runs.
2. **Hilt wiring.** `AppModule` provides
   `WalletManager`, `PinManager`, `KeyStoreManager`, `OkHttpClient`,
   `TorManager`, `WalletConfigDao`, etc.
3. **Room DB ready-check.** `AppModule.provideDatabase` attempts to
   open the SQLCipher-encrypted DB. On any failure (corrupt passphrase,
   schema mismatch): `wipeStaleData` clears DB + non-wallet prefs and
   retries once from a clean state. Seed prefs (`dgb_wallet_seed`)
   are preserved through this path.
4. **Wallet state check.** `WalletManager.walletState` is `Locked`
   while the seed is sealed. `MainActivity` renders the PIN screen.
5. **PIN entry.** User enters PIN. `PinManager.verifyPin(pin)`:
   - Loads `{ kdfVersion, salt, hash }` from `EncryptedSharedPreferences`.
   - Recomputes hash with Argon2id or PBKDF2 as appropriate.
   - Constant-time compare against stored hash.
6. **Seed unseal.** On match:
   - `KeyStoreManager.decryptSeed()` retrieves IV + ciphertext + tag
     from `dgb_wallet_seed` prefs; decrypts via the Keystore master
     key.
   - `NativeBridge.createWalletFromBytes(seed)` builds the native
     wallet. Seed buffer zeroed in `finally`.
   - `WalletManager.walletState` → `Unlocked`.
7. **UI renders wallet screen.** `AppNavigation` routes to the
   `Wallet` destination; `WalletScreen` collects
   `WalletViewModel.balance`, `.transactions`, `.peerCount`,
   `.syncState`.
8. **Sync service starts.** `MainActivity.startSyncService()`
   → `ContextCompat.startForegroundService(SyncService::class)`.
9. **Tor start (if enabled).** `SyncService.startSyncWithTor()`:
   - If Tor preference is enabled, `TorManager.start()` spins up
     kmp-tor's exec-mode daemon, waits up to 90s for bootstrap.
     On success, `NativeBridge.setSocksProxy("127.0.0.1", port)`.
   - On failure: `NativeBridge.clearSocksProxy()` and proceed on
     clearnet. Phase 2 adds a visible warning here.
10. **Peer injection.**
    - Fetch `https://api.digiscope.me/api/peers/bloom`, cached
      response OK up to an hour.
    - Priority-inject `digiscope.me` as a peer + all fetched peers
      via `NativeBridge.injectPriorityPeer`.
    - Hardcoded DNS seeds from `BRChainParams.h` remain as fallback.
11. **Sync start.** `NativeBridge.startSync()` → C core
    `BRPeerManagerConnect`. Peers open TCP connections, exchange
    handshakes, begin block/header/tx exchange.
12. **Keepalive loop.** `SyncService` launches a 10s coroutine that
    checks `peerCount` and re-injects peers if it drops to zero.
    Wrapped in try/catch (v3.5.10+) so a transient exception doesn't
    silently kill the loop.
13. **MainActivity.onResume hook.** (v3.5.10+) On every foreground
    return, if wallet is unlocked and `peerCount == 0`, fire a
    `NativeBridge.startSync()` directly — escape hatch in case
    the SyncService's keepalive has stalled through Doze.

## Upgrade between versions

Clean upgrade (old version → new version) from the user's
perspective.

1. **UpdateChecker polls.** `MainActivity` `LaunchedEffect` calls
   `UpdateChecker.checkForUpdate(currentVersion)` on first
   composition.
2. **GitHub `/releases/latest` query.** HTTP GET
   `https://api.github.com/repos/JohnnyLawDGB/digibytewallet-android/releases/latest`.
3. **Version compare.** `UpdateChecker.isNewer(remote, local)` parses
   semver; returns an `AppUpdate` if the remote is strictly newer.
4. **User sees UpdateDialog.** Clicks "Download"; launches a browser
   or `ACTION_VIEW` against the GitHub release APK URL or
   `digiscope.me/downloads/digibyte-wallet-latest.apk`.
5. **APK install.** User accepts the Android installer prompt.
   Android validates the APK signature against the installed app's
   signer:
   - For pre-v3.5.9 builds (debug-key-signed) updating to v3.5.9+:
     APK Scheme v3 lineage proves the new release key was
     authorized by the old debug key; install proceeds and the
     app's registered signer rotates to the release key on API 28+
     devices. Older Android devices (API 26/27) use the debug-key
     v1/v2 signature path and stay on the debug key.
   - For already-release-signed builds updating to a newer
     release-signed build: standard signature match, install
     proceeds.
6. **App first-launch post-upgrade.** Android starts the new process.
   Seed prefs, sync data, PIN hash — all preserved. Wallet unlocks
   normally.
7. **BIP 84 upgrade detection** (for users created pre-v3.4.0):
   On first unlock of a newer version, `WalletManager.onUpgrade`
   detects that the seed was created under the legacy `m/0H` tree
   and the wallet config flag indicates a pre-BIP84 wallet. No
   forced rescan (v3.5.5 fix) — saved txs are the source of truth.
   `hasLegacyKey` flag remains set; dual-key wallet logic handles
   the mixed address tree.
8. **Sync resumes.** Same as [App start](#app-start-with-existing-wallet)
   from step 8.

### What if the install fails

- **Signature mismatch on pre-v3.5.9 → v3.5.8 upgrade.** Exactly the
  bug that required v3.5.9 to ship with APK Scheme v3 lineage.
  Workaround for any user still in that state: install v3.5.9 or
  later directly — the lineage allows the upgrade without uninstall.
- **Corrupt persisted state.** `AppModule.provideDatabase` wipes DB +
  prefs (except seed) and retries on a fresh state. User sees a brief
  "initializing" then normal unlock flow.
- **Native crash on startup.** Seed remains sealed; user downgrades
  via APK install (signature-lineage permitting) or restores from
  seed phrase on a clean install.
