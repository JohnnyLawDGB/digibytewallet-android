# Multi-wallet Step 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let one install hold up to 8 named wallets per network, each with its own recovery phrase, stores, Keystore aliases, Hub session and Digi-ID identity, switched by restarting the process, while the wallet that exists today keeps every file, key and alias name it has now.

**Architecture:** Every store name goes through one function, `storageSuffix(scope, storeScope)`. Its `WalletScope` is the active wallet, resolved once per process from a new registry (`dgb_wallets`). For the reserved id `legacy` the function returns exactly today's suffix. The process-wide singletons (Room DB, `WalletManager`, `KeyStoreManager`, `HubTokenStore`) are bound to that scope at process start. Switching therefore means persisting a new active id and restarting, as the network toggle already does. Adding a wallet writes only the new wallet's seed record under a fresh id, so it never touches the native wallet or another wallet's stores. Removing a wallet erases one scope's stores. The PIN-failure wipe erases every scope.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room/SQLCipher, SharedPreferences, AndroidKeyStore, org.json (real implementation in core unit tests), JUnit4 + MockK, the existing pure-JVM harnesses `wipeTestContext` / `InMemoryPrefsFactory` (`core/src/test/.../WipeTestSupport.kt`) and `FakeVault` / `keyStoreManagerOver` (`KeyTestSupport.kt`).

**Spec:** `docs/superpowers/specs/2026-11-16-multi-wallet-design.md` (Step 1 only. Step 2 is the shared sync engine and is not in this plan.)

**Pre-flight:** `.superpowers/sdd/2026-11-16-multi-wallet-step1/preflight.md`. Its rulings F1–F32 are carried into the tasks below (F10 as modified by the owner: with no endpoint set the quick start offers DigiScope only, as an explicit opt-in). Where a task says "(F<n>)", that ruling is the reason.

## Global Constraints

- **The `legacy` wallet keeps every name it has in 4.1.4.** That covers prefs files, prefs keys, files, the DB file, and the Keystore aliases `dgb_wallet_master` / `dgb_wallet_master_v2`. Nothing is renamed and nothing is migrated. `LegacyStoreNameParityTest` (Task 1) pins every name.
- **A new wallet's id matches `^[0-9a-f]{16}$`** (from `SecureRandom`). `legacy` is reserved. No other id is ever accepted from disk.
- **At most 8 wallets per network** (`WalletRegistry.MAX_PER_NETWORK = 8`). The count includes `legacy` and entries still being added.
- **Every new or changed user-facing string ships in all 13 locales of `AppLocale.SUPPORTED` in the same commit.** The locales are en, hi, zh, ja, pt-BR (`values-b+pt+BR`), es, id, vi, tr, ru, de, fr and fil (`values-b+fil`). Keys go in `values*/strings_wallet.xml`. Every new screen file is added to `OnboardingHardcodedStringTest.COVERED`.
- **Seed handling is unchanged.** A mnemonic on the load, restore and sign path is a `ByteArray`, zeroed with `fill(0)` in `finally`. A passphrase `ByteArray` is owned and zeroed by its caller. `g_seed` is reached only through the accessor API. No new code logs a phrase, a seed, a fingerprint or an address.
- **Nothing deletes a database or a key outside a user's wipe.** The one new exception is the user's removal of one wallet, which deletes only that wallet's scope.
- **No address set leaves the device by default.** The quick start (Task 15) sends addresses only after the user picks it on a screen that names the server. For digiscope.me that screen also says the server can link this wallet to the user's other wallets.
- **One copy of each thing.** `storageSuffix` is the only place a store suffix is built, and `networkSuffix` is deleted. `WalletStores` is the only list of per-wallet stores, and both name resolution and the eraser read it. `AppRestart` and the wipe hand-over (`RelaunchInFreshProcess` in `FreshStartAfterWipe.kt`) are the only restart paths, and `AppRestart` reuses the hand-over's main-thread relaunch-then-`killProcess` (F16).
- **No Room schema change.** Each wallet gets its own DB file under the existing schema (v12) and the existing passphrase record `dgb_db_key` / alias `dgb_db_passphrase`.
- **Before every commit**, run `./gradlew --continue :core:testMainnetDebugUnitTest :app:testMainnetPlayDebugUnitTest :app:testMainnetSideloadDebugUnitTest`. Commit only when that command exits 0, and never chain the commit after an `echo`.
- **Stage with explicit paths** (`git add <paths>`), never `git add -A`. Work in a worktree. The pre-commit hook `check-worktree-target.sh` refuses commits to the main checkout.
- **Device work runs only on emulators, the Note 8 (SM-N950U) or the Moto G 5G. Never on the Galaxy S25 Ultra.**

## Review Focus

These are the failure modes most likely to reach a user that no task's ordinary tests would exercise. Each one has a named test in the task that owns it.

1. **Upgrade from 4.1.4 with an existing wallet.** A name drifts by a single character and the user's wallet opens empty, or re-syncs from birth. Covered by Task 6, `UpgradeFrom414Test`: a full 4.1.4 layout must come through an upgraded launch byte-identical, resolve as `legacy`, and be the only entry. On a device, by Task 17 step 2.
2. **Restoring a phrase that is already one of the wallets.** This includes the same mnemonic with and without a passphrase, which are two different wallets. Covered by Task 8, `WalletProvisionerTest`: `a phrase already in the app is refused and names the wallet it matches`, and `the same mnemonic with a passphrase is a different wallet`.
3. **The process dies mid-add, mid-switch or mid-remove.** The risks are a half-written registry, an orphan seed record, or an active id that points at a wallet with no record.
   - Task 2, `WalletRegistryTest`: `an unreadable registry is rebuilt from the seed records on disk` and `an active id whose record is gone resolves to the first wallet that has one`.
   - Task 8, `WalletHousekeepingTest`: `an add killed after its record landed is erased at the next launch`.
   - Task 11, `WalletRemovalTest`: `a removal killed mid-erase finishes at the next launch`.
4. **Removing the active wallet, or the `legacy` wallet.** There are three risks. The DB password record may still be wrapped under `dgb_wallet_master`, so deleting that key would lock every wallet's database. The custom endpoint `custom_rpc_endpoint` shares the `dgb_reconcile` file with legacy's reconcile bookkeeping. And the `legacy` seed is shared by both networks. Covered by Task 11, `WalletRemovalTest`:
   - `removing legacy keeps its key while the database key record depends on it`;
   - `removing legacy keeps the custom node endpoint`;
   - `removing the active wallet moves the active id, erases nothing, and the next launch erases it` (the live database is never deleted under the running process, F8);
   - `removing one wallet leaves every byte of the others`.
5. **The PIN-failure wipe with N wallets.** The wipe erases legacy and leaves wallet 2's phrase readable. Or the read-back reports "seed gone" while `dgb_wallet_master_<id>_v2` still exists. Or, with wallet 2 active, legacy's `dgb_wallet_master(_v2)` survives because the active `KeyStoreManager` is bound to wallet 2's alias (F4). Covered by Task 10, `WalletWipeEveryWalletTest`: `a wipe erases every wallet's record, stores and aliases`, `a wipe with another wallet active erases legacy's key too` and `a surviving alias of any wallet means the seed is not gone`.
6. **The Hub, Digi-ID or DigiStamp session leaking across a switch.** The Hub token store is per wallet. But the DigiStamp session lives in the app-wide WebView cookie jar (`IdentitySessionEraser.kt`), so without a clear on switch, wallet A's Digi-ID-signed DigiStamp session would greet wallet B. Covered by:
   - Task 4, `HubTokenStoreScopeTest`: `wallet B never reads wallet A's Hub token`;
   - Task 9, `WalletSwitcherTest`: `a switch ends the web session before it restarts`;
   - device check in Task 17 step 6.

## File Structure

**Created, in `core/src/main/java/app/aroundtheblock/wallet/core/wallets/`:**

| File | Responsibility |
|---|---|
| `WalletScope.kt` | `WalletScope`, `StoreScope`, `storageSuffix()` (the one suffix function), `WalletIds` |
| `WalletStores.kt` | The one list of per-wallet stores (prefs bases, files, DB name, dgb_settings keys, seed record, aliases), plus name resolution |
| `WalletRegistry.kt` | The `dgb_wallets` registry: entries, order, names, active id per network, cap, pending/removing states, rebuild from disk |
| `ActiveWallet.kt` | Resolves and caches this process's `WalletScope` |
| `WalletStoreEraser.kt` | Erases one wallet's scope in three phases (record, aliases, stores), with read-back |
| `WalletProvisioner.kt` | Add a wallet: reserve, duplicate check, write the record only, complete |
| `WalletHousekeeping.kt` | At launch, finishes or erases incomplete entries (pending add, interrupted removal) |
| `WalletRemoval.kt` | Removes one wallet, or hands over to the full wipe for the last one |
| `QuickStartStore.kt` | Per-wallet display-only "not yet verified" balance |

**Created in `app/src/main/java/app/aroundtheblock/wallet/`:**

| File | Responsibility |
|---|---|
| `AppRestart.kt` | The one "relaunch and end this process" path outside the wipe hand-over. It replaces `SettingsViewModel.restartApp` and the two copies in `ReconcileScreen.kt` |
| `WalletSwitcher.kt` | Persists the active id, ends the web session, restarts |
| `ui/wallets/WalletsViewModel.kt` | Switcher and manage state: entries, last-known balances, rename/reorder/remove |
| `ui/wallets/WalletChip.kt` | The name chip at the top of the Wallet tab |
| `ui/wallets/WalletSwitcherSheet.kt` | The sheet: names, balances, Add wallet, Manage |
| `ui/wallets/ManageWalletsScreen.kt` | Rename, reorder, remove |
| `ui/wallets/RemoveWalletDialog.kt` | Backup warning, typed-name confirm, PIN |
| `ui/onboarding/OnboardingRoutes.kt` | `OnboardingMode` and the route constants shared by the onboarding graph |
| `ui/onboarding/AddWalletCommitScreen.kt` | Commits the add, then switches |
| `ui/onboarding/QuickStartScreen.kt` | Opt-in quick start with its disclosure |

**Modified:**

- `core/.../core/NetworkState.kt`: `networkSuffix` is deleted; `isTestnet` stays.
- `core/.../core/WalletManager.kt`: gains `scope`, uses scoped names, gains `storeRecordOnly`, and `wipeWallet` covers every wallet.
- `core/.../core/WalletDataEraser.kt`: the full wipe also erases every non-legacy scope; `KeystoreAliases.aliases()`; it reaches every per-wallet base of `WalletStores` (reverse gate, Task 10).
- `core/.../core/security/KeyStoreManager.kt`: no API change. Production passes `WalletStores.keyAlias(scope)`.
- `core/.../core/digiscope/HubTokenStore.kt`, `DigiScopeClient.kt`: the token store is per scope.
- `core/.../core/sync/{SavedBlockStore,FilterHeaderStore,CfScanLedgerStore,SavedTransactionsStore,CfAbandonmentStore,ChainTipStore,HistoryRebuildOnUpgrade}.kt` (including their `deleteAllNetworks` / `clearAllNetworks` helpers), `core/.../core/reconcile/{PostUpgradeReconciler,DgbNodeClient}.kt`, `core/.../core/asset/{RemainderProof,AssetHistoryBackfill}.kt`, `core/.../core/{OutgoingTxStore,WalletTxPersister}.kt`, `core/.../core/settings/{CustomNode,DisplayCurrencyPrefs}.kt`: switch to scoped names.
- `app/.../di/AppModule.kt`: scoped DB file, `WalletManager`, `KeyStoreManager`, `WalletRegistry`, `WalletManagerFactory`, `WalletStoreEraser`, `WalletProvisioner`, `WalletSwitcher` and `WalletRemoval` providers.
- `app/.../service/{SyncService,SyncWorker}.kt`, `app/.../StaleDataWiper.kt`, `app/.../ui/wallet/{WalletViewModel,DisplayCurrencyStore,WalletScreen}.kt`: scoped names; the chip.
- `app/.../DigiByteApp.kt`: runs housekeeping at process start.
- `app/.../ui/settings/{SettingsViewModel,ReconcileScreen,SecuritySettingsScreen,SeedViewScreen}.kt`, `app/.../ui/hub/ProfileView.kt`: restart via `AppRestart`; wipe text; wallet name; the hardcoded literals of `ProfileView` and `SeedViewScreen` extracted.
- `app/.../ui/onboarding/{OnboardingViewModel,OnboardingScreen,PassphraseScreen,RecoveryDateScreen,MnemonicInputScreen,PinSetupScreen,UnlockScreen}.kt`, `app/.../ui/navigation/{AppNavigation,LockGatePolicy}.kt`: add mode; every `"onboarding"` route literal becomes `ONBOARDING_ROUTE` / `onboardingRoute(FIRST_RUN)`.
- Test fakes: the `FakeKeystore` classes in `WalletWipeTest`, `WalletSessionAfterWipeTest` and `WipeRemovesSetAsideDatabasesTest`; `KeyTestSupport.kt` (`FakeVault.asKeystoreAliases()`).
- `app/src/main/res/values*/strings_wallet.xml` (13 dirs).
- Tests listed per task.

**String keys.** Each is added in the task that first uses it, in all 13 locales:

| Key | English | Task |
|---|---|---|
| `wallet_default_name` | `Wallet %1$d` | 13 |
| `wallet_switcher_title` | `Your wallets` | 13 |
| `wallet_switcher_add` | `Add wallet` | 13 |
| `wallet_switcher_manage` | `Manage` | 13 |
| `wallet_switcher_active` | `Active` | 13 |
| `wallet_switcher_balance_unknown` | `Not synced yet` | 13 |
| `wallet_switcher_cap` | `You can keep up to %1$d wallets on each network.` | 13 |
| `wallet_switching` | `Opening %1$s…` | 13 |
| `wallet_chip_cd` | `Wallet: %1$s. Tap to switch wallets.` | 13 |
| `wallet_manage_title` | `Manage wallets` | 13 |
| `wallet_manage_rename` | `Rename` | 13 |
| `wallet_manage_name_hint` | `Wallet name` | 13 |
| `wallet_manage_name_invalid` | `Use 1 to 32 characters, different from your other wallets\' names.` | 13 |
| `wallet_manage_move_up` | `Move up` | 13 |
| `wallet_manage_move_down` | `Move down` | 13 |
| `wallet_manage_remove` | `Remove` | 13 |
| `wallet_remove_title` | `Remove “%1$s”?` | 13 |
| `wallet_remove_warning` | `This deletes this wallet\'s recovery phrase, keys and history from this device. Its funds stay on the blockchain, and only its recovery phrase can reach them. Back up the phrase before you continue.` | 13 |
| `wallet_remove_both_networks` | `This wallet is also your testnet wallet. Removing it removes it there too.` | 13 |
| `wallet_remove_last` | `This is the only wallet on this device. Removing it wipes the app and starts fresh.` | 13 |
| `wallet_remove_type_name` | `Type the wallet\'s name to confirm` | 13 |
| `wallet_remove_confirm` | `Remove wallet` | 13 |
| `wallet_remove_auth_subtitle` | `Confirm to remove %1$s` | 13 |
| `wallet_remove_failed` | `The wallet was not fully removed. Open the app again to finish.` | 13 |
| `wallet_remove_db_key_kept` | `This wallet cannot be removed yet. Open the app once more, then try again.` | 13 |
| `wallet_add_title` | `Add a wallet` | 12 |
| `wallet_add_create` | `Create a new wallet` | 12 |
| `wallet_add_restore` | `Restore a wallet from its recovery phrase` | 12 |
| `wallet_add_pin_note` | `Your app PIN protects every wallet. You do not set a new one.` | 12 |
| `wallet_add_duplicate` | `This recovery phrase is already in the app as “%1$s”.` | 12 |
| `wallet_add_duplicate_switch` | `Open %1$s` | 12 |
| `wallet_add_cap` | `This network already has %1$d wallets. Remove one to add another.` | 12 |
| `wallet_add_saving` | `Adding the wallet…` | 12 |
| `wallet_add_failed` | `The wallet could not be added. Nothing was changed.` | 12 |
| `quickstart_title` | `See the balance sooner?` | 15 |
| `quickstart_body` | `This phone finds the wallet\'s history itself. That takes a while for an old wallet. A node can show a first balance in seconds. That balance is shown as not yet verified and cannot be spent until sync confirms it.` | 15 |
| `quickstart_own_node` | `Ask my node (%1$s)` | 15 |
| `quickstart_own_node_note` | `Your node sees this wallet\'s addresses.` | 15 |
| `quickstart_digiscope` | `Ask DigiScope (api.digiscope.me)` | 15 |
| `quickstart_digiscope_disclosure` | `This sends this wallet\'s addresses to DigiScope (api.digiscope.me). DigiScope can then link this wallet to your other wallets in this app, by your IP address and the time of the request.` | 15 |
| `quickstart_skip` | `No, find it privately on this phone` | 15 |
| `quickstart_unverified` | `Not yet verified: %1$s DGB` | 15 |
| `quickstart_unverified_sub` | `From %1$s. Not spendable until sync confirms it.` | 15 |
| `sec_wipe_after_on_all` | `Enabled: every wallet is erased after %1$d wrong PINs` (replaces `sec_wipe_after_on`) | 10 |
| `sec_wipe_after_body_all` | `When enabled, %1$d incorrect PIN entries in a row PERMANENTLY erase every wallet on this device: recovery phrases, keys and history.\n\nThis cannot be undone on the device. Funds can be recovered only from each wallet\'s recovery phrase.` (replaces `sec_wipe_after_body`) | 10 |
| `sec_wipe_all_body` | `This cannot be undone.\n\nEvery wallet on this device is deleted: recovery phrases, keys and transaction history.\n\nMake sure every wallet\'s recovery phrase is backed up before you continue.` (replaces `sec_wipe_wallet_body`) | 10 |
| `seedview_wallet_name` | `Recovery phrase of %1$s` | 14 |
| `seedview_title` | `Recovery Phrase` (extracted from `SeedViewScreen.kt:105`) | 14 |
| `seedview_done_hide` | `Done — Hide Phrase` (extracted from `SeedViewScreen.kt:297`) | 14 |
| `hub_handle_field_label` | `Handle` (extracted from `ProfileView.kt:258`) | 14 |
| `hub_handle_field_hint` | `e.g. DigiByte_Fan` (extracted from `ProfileView.kt:259`) | 14 |
| `hub_handle_check` | `Check` (extracted from `ProfileView.kt:367`) | 14 |
| `hub_handle_register` | `Register` (extracted from `ProfileView.kt:384`) | 14 |
| `hub_signed_in_wallet` | `Signed in with %1$s` | 14 |
| `sync_notif_title_wallet` | `%1$s · %2$s` | 14 |
| `sync_notif_synced` | plurals `one`: `Synced, %1$d peer connected`; `other`: `Synced, %1$d peers connected` | 14 |
| `sync_notif_connecting` | `Connecting to peers…` | 14 |
| `sync_notif_syncing` | plurals `one`: `Syncing %1$d%%, %2$d peer`; `other`: `Syncing %1$d%%, %2$d peers` | 14 |
| `sync_notif_via_tor` | ` · via Tor`, with its leading space. In XML the value is double-quoted (`" · via Tor"`), because aapt strips an unquoted leading space | 14 |

A replaced key is deleted from all 13 files in the same commit that adds its successor.

---

### Task 1: The storage-suffix function and the per-wallet store list

**Files:**
- Create: `core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletScope.kt`
- Create: `core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletStores.kt`
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/LegacyStoreNameParityTest.kt`
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/WalletScopeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
```kotlin
object WalletIds {
    const val LEGACY = "legacy"
    fun isValid(id: String): Boolean                       // id == LEGACY || id matches ^[0-9a-f]{16}$
    fun newId(random: java.security.SecureRandom): String  // 8 random bytes as lowercase hex
}
data class WalletScope(val walletId: String, val testnet: Boolean) {
    init { require(WalletIds.isValid(walletId)) }
    val isLegacy: Boolean get() = walletId == WalletIds.LEGACY
    companion object { fun legacy(testnet: Boolean): WalletScope }
}
enum class StoreScope {
    NETWORK,             // shared by every wallet: "" | "_testnet"
    WALLET_AND_NETWORK,  // legacy: "" | "_testnet"; other: "_<id>" | "_<id>_testnet"
    WALLET,              // legacy: ""; other: "_<id>"   (stores that 4.1.4 never network-suffixed)
}
fun storageSuffix(scope: WalletScope, store: StoreScope): String

object WalletStores {
    /** Prefs files owned by one wallet, per network. */
    val WALLET_AND_NETWORK_PREFS: List<String>  // "dgb_sync_data","dgb_cf_abandonment","dgb_history_rebuild","dgb_reconcile"
    /** Prefs files owned by one wallet, not network-suffixed in 4.1.4. */
    val WALLET_PREFS: List<String>              // "dgb_wallet_seed","dgb_watched_addrs","dgb_outgoing_tx","dgb_asset_backfill",
                                                // "dgb_asset_heal","dgb_diag","dgb_quickstart","dgb_digiscope","dgb_hub_session"
    /** File bases in filesDir, per network, each "<base><suffix>.bin" (+ ".bin.tmp"). */
    val WALLET_AND_NETWORK_FILES: List<String>  // "saved_filter_headers","saved_cf_ledger","saved_blocks","saved_transactions"
    /** Prefs files shared by every wallet, per network. */
    val NETWORK_PREFS: List<String>             // "dgb_bloom_peers","dgb_filter_peers","dgb_dandelion_peers","dgb_price"
    /** The key in dgb_reconcile<net> that belongs to the device, not to the wallet. */
    const val SHARED_RECONCILE_KEY = "custom_rpc_endpoint"

    fun prefsName(base: String, scope: WalletScope): String  // throws IllegalArgumentException for a base in no list.
                                                             // A reader that must see a per-wallet file under its
                                                             // network-only 4.1.4 name (DgbNodeClient's custom_rpc_endpoint,
                                                             // DisplayCurrencyPrefs.moveFromLegacy, the *AllNetworks helpers)
                                                             // passes WalletScope.legacy(testnet): no exemption is needed (F11)
    fun fileName(base: String, scope: WalletScope): String   // "<base><WALLET_AND_NETWORK suffix>.bin"
    fun databaseName(scope: WalletScope): String             // "wallet<WALLET_AND_NETWORK suffix>.db"
    fun seedRecordPrefs(scope: WalletScope): String          // "dgb_wallet_seed" | "dgb_wallet_seed_<id>"
    fun keyAlias(scope: WalletScope): String                 // "dgb_wallet_master" | "dgb_wallet_master_<id>"
    fun cfBirthHeightKey(scope: WalletScope): String         // "cf_birth_height" | "cf_birth_height_<id>"   (in dgb_settings)
    /** A Keystore alias of some wallet's seed key → its wallet id, or null. Strict: "dgb_wallet_master_v2" → LEGACY. */
    fun walletIdOfAlias(alias: String): String?
    /** A shared_prefs file name "dgb_wallet_seed_<id>.xml" → id, or null. */
    fun walletIdOfSeedPrefsFile(fileName: String): String?
}
```

- [ ] **Step 1: Write the failing tests.**
  - `LegacyStoreNameParityTest.every legacy name is the 4.1.4 name`. It walks every base in each list for `WalletScope.legacy(false)` and `legacy(true)` and asserts the results equal literal tables copied from 4.1.4. The tables come from the `git show v4.1.4:` sources, not from the new code:
    - mainnet `prefsName("dgb_sync_data") == "dgb_sync_data"`, testnet `== "dgb_sync_data_testnet"`;
    - `prefsName("dgb_watched_addrs")` == `"dgb_watched_addrs"` on both networks;
    - `fileName("saved_blocks")` == `"saved_blocks.bin"` / `"saved_blocks_testnet.bin"`;
    - `databaseName` == `"wallet.db"` / `"wallet_testnet.db"`;
    - `seedRecordPrefs` == `"dgb_wallet_seed"` on both networks;
    - `keyAlias` == `"dgb_wallet_master"`;
    - `cfBirthHeightKey` == `"cf_birth_height"` on both networks;
    - `prefsName("dgb_hub_session")` == `"dgb_hub_session"`.
  - `LegacyStoreNameParityTest.the table is the 4.1.4 store set` (F12). It asserts that the union of the four prefs lists equals a literal set: the **prefs** set named in `git show v4.1.4:core/src/main/java/app/aroundtheblock/wallet/core/WalletDataEraser.kt`, **minus** `dgb_log_tag` (per install) and `dgb_db_key` (shared DB password record), **plus** `dgb_quickstart` (new) and `dgb_price` (shared; the eraser keeps it on purpose). The file bases are compared the same way with the eraser's file set. A store missing from the lists fails here. The `cf_birth_height` key and the DB files are pinned by the name assertions above, not by this set. The reverse direction (every per-wallet base is reached by the full wipe) is Task 10's `WalletDataEraserReachTest`.
  - `WalletScopeTest.a new wallet's names carry its id before the network`. For id `0123456789abcdef` on testnet, assert `prefsName("dgb_sync_data") == "dgb_sync_data_0123456789abcdef_testnet"`, `prefsName("dgb_watched_addrs") == "dgb_watched_addrs_0123456789abcdef"`, `databaseName == "wallet_0123456789abcdef_testnet.db"`, `keyAlias == "dgb_wallet_master_0123456789abcdef"`, and `storageSuffix(scope, NETWORK) == "_testnet"`.
  - `WalletScopeTest.an id that is not legacy or 16 hex is refused`. `WalletScope("../x", false)`, `WalletScope("ABCDEF0123456789", false)` and `WalletScope("v2", false)` each throw `IllegalArgumentException`.
  - `WalletScopeTest.aliases parse strictly`:
    - `walletIdOfAlias("dgb_wallet_master") == "legacy"`;
    - `walletIdOfAlias("dgb_wallet_master_v2") == "legacy"`;
    - `walletIdOfAlias("dgb_wallet_master_0123456789abcdef_v2") == "0123456789abcdef"`;
    - `walletIdOfAlias("dgb_db_passphrase") == null`;
    - `walletIdOfAlias("dgb_wallet_master_xyz") == null`.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.core.wallets.*"`. Expected FAIL: compilation, the package does not exist.
- [ ] **Step 3: Implement** both files as specified. `storageSuffix` is a `when` over `store` and `scope.isLegacy`. The network part is `if (testnet) "_testnet" else ""`, the same literal as today's `networkSuffix`.
- [ ] **Step 4: Run** the same command. Expected PASS.
- [ ] **Step 5: Run the full suites** (`./gradlew --continue :core:testMainnetDebugUnitTest :app:testMainnetPlayDebugUnitTest :app:testMainnetSideloadDebugUnitTest`). Expected exit 0.
- [ ] **Step 6: Commit.** `git add core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletScope.kt core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletStores.kt core/src/test/java/app/aroundtheblock/wallet/core/wallets/LegacyStoreNameParityTest.kt core/src/test/java/app/aroundtheblock/wallet/core/wallets/WalletScopeTest.kt`, message `feat(wallets): one storage-suffix function; legacy resolves to today's names`.

### Task 2: The wallet registry and the active wallet

**Files:**
- Create: `core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletRegistry.kt`
- Create: `core/src/main/java/app/aroundtheblock/wallet/core/wallets/ActiveWallet.kt`
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/WalletRegistryTest.kt`
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/ActiveWalletTest.kt`

**Interfaces:**
- Consumes: `WalletScope`, `WalletIds`, `WalletStores.seedRecordPrefs` (Task 1).
- Produces:
```kotlin
enum class WalletOrigin { CREATED, RESTORED }
enum class EntryState { READY, PENDING, REMOVING }
data class WalletEntry(
    val id: String,
    val name: String?,          // null = the localised default "Wallet <number>"
    val number: Int,            // 1 for legacy; for a new wallet the lowest number its network does not use
    val testnet: Boolean?,      // null only for legacy, whose one seed serves both networks
    val createdAt: Long,        // unix seconds; 0 for legacy
    val origin: WalletOrigin,
    val order: Int,
    val state: EntryState,
)
class WalletRegistry(
    private val prefs: android.content.SharedPreferences,   // "dgb_wallets"
    private val recordExists: (WalletScope) -> Boolean,     // the scope's seed prefs hold encrypted_seed or encrypted_seed_v2
    private val recordsOnDisk: () -> List<Pair<String, Boolean?>>,  // (id, testnet) from shared_prefs/dgb_wallet_seed_<id>.xml
                                                            // and that record's wallet_network key (null when absent).
                                                            // Injected in tests; InMemoryPrefs never create files (F30)
    private val random: java.security.SecureRandom = java.security.SecureRandom(),
) {
    fun list(testnet: Boolean): List<WalletEntry>           // READY only, by order; legacy synthesised when its record exists
    fun incomplete(): List<WalletEntry>                     // PENDING and REMOVING, every network
    fun find(id: String): WalletEntry?
    fun activeId(testnet: Boolean): String                  // see resolution rule below
    fun setActive(testnet: Boolean, id: String): Boolean    // one commit()
    fun canReserve(testnet: Boolean): Boolean               // READY + PENDING on [testnet] < MAX_PER_NETWORK (F31)
    fun reserve(testnet: Boolean, origin: WalletOrigin, nowSecs: Long): WalletEntry?   // PENDING; null when !canReserve
    fun complete(id: String, makeActive: Boolean): Boolean  // PENDING → READY (+ active) in one commit()
    fun markRemoving(id: String): Boolean                   // REMOVING, and on EVERY network whose active id is [id]
                                                            // the active id moves to that network's next READY entry
                                                            // (absent when there is none), in one commit() (F7)
    fun anyRecordExists(): Boolean                          // legacy's record, or any recordsOnDisk() entry (F31)
    fun drop(id: String): Boolean
    fun rename(id: String, name: String): Boolean           // false on an invalid or duplicate name
    fun reorder(testnet: Boolean, ids: List<String>): Boolean
    companion object {
        const val PREFS = "dgb_wallets"
        const val MAX_PER_NETWORK = 8
        const val MAX_NAME_CHARS = 32
        fun validName(name: String, others: List<String>): Boolean   // trimmed 1..32, unique ignoring case
    }
}
object ActiveWallet {
    fun scope(context: android.content.Context): WalletScope        // resolved once per context.applicationContext
                                                                    // (identity), then cached: one entry in production,
                                                                    // a fresh one per mock context in tests (F1)
    fun registry(context: android.content.Context): WalletRegistry  // over "dgb_wallets" and File(context.dataDir, "shared_prefs")
    @androidx.annotation.VisibleForTesting fun resetForTest()       // public: app tests call it too (F1)
}
```
- **Storage format.** The registry is one JSON string under key `registry`, so every change is a single atomic `commit()`. The shape is `{"v":1,"entries":[…],"legacy":{"name":null,"order":0,"state":"…"},"active":{"mainnet":"…","testnet":"…"}}`. Legacy's `state` is absent unless it is REMOVING; absent means READY, so an upgrade's reads stay write-free (F6).
- **Resolution rule for `activeId(testnet)`.** Use the stored id if it is READY on that network and `recordExists`. Otherwise use the first entry in `list(testnet)` whose record exists. Otherwise `legacy`.
- **Upgrade.** With the file absent, `list` returns `[legacy]` when legacy's record exists and `[]` when it does not. Reading never writes.

- [ ] **Step 1: Write the failing tests** in `WalletRegistryTest`, over `InMemoryPrefs`:
  - `an install from 4.1.4 lists exactly the legacy wallet and writes nothing`: `list(false).map { it.id } == listOf("legacy")`, `list(false)[0].number == 1`, `name == null`, `prefs.all.isEmpty()`.
  - `no record and no registry lists nothing`: `list(false) == emptyList<WalletEntry>()`.
  - `a reserved wallet is not listed until complete`: `reserve(...)` is non-null and its id matches `[0-9a-f]{16}`; `list` has no such id; `incomplete()` contains it; after `complete(id, makeActive = true)`, `list` contains it with `number == 2` and `activeId(false) == id`.
  - `the eighth wallet is the last`: legacy plus 7 reserved and completed, then `reserve(false, …) == null`; `reserve(true, …)` is not null, because the cap is per network.
  - `a pending entry counts toward the cap`: legacy plus 6 complete plus 1 pending, then `canReserve(false) == false` and `reserve == null`.
  - `an active id whose record is gone resolves to the first wallet that has one`: active = B, `recordExists(B) == false`, so `activeId(false) == "legacy"`.
  - `an unreadable registry is rebuilt from the seed records on disk`: seed `registry = "{not json"` with `recordsOnDisk = listOf("0123456789abcdef" to false, "fedcba9876543210" to null)`. Then `list(false)` returns ids `["legacy","0123456789abcdef","fedcba9876543210"]`. A record with no `wallet_network` is placed on mainnet, the only network a user can reach without the developer toggle. Each rebuilt entry has `name == null` and `origin == RESTORED`, and `activeId(false) == "legacy"`.
  - `names are trimmed, at most 32 chars and unique ignoring case`: `rename(B, " Savings ")` is true and the name reads back as `"Savings"`; `rename(C, "savings") == false`; `rename(C, "")` is false; `rename(C, "x".repeat(33))` is false.
  - `reorder keeps every id`: `reorder(false, [B, legacy])` is true and `list` returns `[B, legacy]`; `reorder(false, [B])` is false.
  - `a refused commit changes nothing`: with `refuseWritesTo("dgb_wallets")`, `setActive` returns false and `activeId` is unchanged.
  - `removing a wallet moves the active id on every network that names it` (F7). Legacy active on both networks, B READY on mainnet, T READY on testnet. `markRemoving("legacy")` is true; then `activeId(false) == B`, `activeId(true) == T`, `find("legacy")!!.state == REMOVING`, `list(false).none { it.id == "legacy" }`, and the stored JSON's `legacy.state == "REMOVING"`.
  - `legacy reads as READY when its state is absent` (F6): a registry JSON whose `legacy` object has no `state` lists legacy.
  - `any record means a wallet exists` (F31): `anyRecordExists() == false` with no records; true with only `recordsOnDisk = listOf("0123456789abcdef" to true)`.
- [ ] **Step 2: Write the failing tests** in `ActiveWalletTest`, using `wipeTestContext`:
  - `with no registry the scope is legacy on the selected network`: `dgb_settings.dgb_network_testnet = true` gives `ActiveWallet.scope(ctx) == WalletScope.legacy(true)`.
  - `the scope is resolved once per application context`: resolve, then call `setActive` to another id, then resolve again with the same context. The scope is unchanged until `resetForTest()`, after which it is the new id.
  - `a different application context resolves afresh` (F1): a second `wipeTestContext` with `dgb_network_testnet = true` resolves `legacy(true)` without `resetForTest()`, while the first context still resolves `legacy(false)`.
- [ ] **Step 3: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.core.wallets.WalletRegistryTest" --tests "*.core.wallets.ActiveWalletTest"`. Expected FAIL: unresolved `WalletRegistry`.
- [ ] **Step 4: Implement.** `WalletRegistry` keeps no state between calls; it reads and parses `prefs` each time. `recordsOnDisk` in `ActiveWallet.registry` lists `File(context.dataDir, "shared_prefs")` (a null or unreadable listing is an empty list, which is what the relaxed-mock `wipeTestContext` gives), maps each name through `WalletStores.walletIdOfSeedPrefsFile`, and reads that record's `wallet_network`. `ActiveWallet.scope` caches one `(applicationContext, WalletScope)` pair under a lock and re-resolves when the application context is a different object (F1).
- [ ] **Step 5: Run** the same command. Expected PASS.
- [ ] **Step 6: Run the full suites.** Expected exit 0.
- [ ] **Step 7: Commit** `WalletRegistry.kt`, `ActiveWallet.kt` and both tests, message `feat(wallets): wallet registry dgb_wallets with reserved legacy id and per-network cap`.

### Task 3: Route every store suffix through `storageSuffix`

This task changes no behaviour: every scope resolves to `legacy`. It replaces every `networkSuffix(…)` call and deletes the function. Each store object gains a `scope: WalletScope = ActiveWallet.scope(ctx)` parameter, so Tasks 7 to 11 can address a wallet that is not active.

**Files (call sites, grouped by the scope each becomes):**
- `StoreScope.WALLET_AND_NETWORK` (per wallet):
  - `core/.../sync/SavedBlockStore.kt:50,52,55` (`file`, `tmpFile`, legacy prefs), plus `delete(ctx, scope)` / `load(ctx, scope)`;
  - `core/.../sync/FilterHeaderStore.kt:44,46,49`;
  - `core/.../sync/CfScanLedgerStore.kt:30,32`;
  - `core/.../sync/SavedTransactionsStore.kt:42,44,47`;
  - `core/.../sync/CfAbandonmentStore.kt:109`;
  - `core/.../sync/ChainTipStore.kt:39`. It lives in `dgb_sync_data`, so it is per wallet in step 1;
  - `core/.../sync/HistoryRebuildOnUpgrade.kt:219,222,519`, and `:247,263`, where the `evaluated` map is keyed by `storageSuffix(scope, WALLET_AND_NETWORK)`;
  - `core/.../reconcile/PostUpgradeReconciler.kt:60,68,119,164`;
  - `core/.../asset/RemainderProof.kt:37`;
  - `core/.../WalletManager.kt:406,416,1646`;
  - `app/.../ui/wallet/WalletViewModel.kt:56`;
  - `app/.../di/AppModule.kt:55`, now `WalletStores.databaseName(ActiveWallet.scope(context))`;
  - `app/.../StaleDataWiper.kt:44` for `dgb_sync_data`, and `:124` for the DB;
  - `app/.../service/SyncService.kt:496,2231,2572,2745,2891,3196,3996,4030,4048`.
- `StoreScope.WALLET` (per wallet, never network-suffixed in 4.1.4). These are literal names today, not `networkSuffix` calls. They join the sweep so that the gate below holds:
  - `core/.../WalletManager.kt:183` (`dgb_wallet_seed` → `WalletStores.seedRecordPrefs(scope)`) and `:189` (`dgb_watched_addrs`);
  - `core/.../OutgoingTxStore.kt:121`, `core/.../asset/AssetHistoryBackfill.kt:98` and `core/.../WalletTxPersister.kt:91` (`dgb_diag`);
  - `app/.../service/SyncService.kt:1254` (`dgb_asset_heal`), `:2303` (`dgb_diag`) and `:2475` (`dgb_watched_addrs`).
- `StoreScope.NETWORK` (shared):
  - `core/.../settings/CustomNode.kt:64`;
  - `core/.../settings/DisplayCurrencyPrefs.kt:34-74`. Callers pass `storageSuffix(scope, NETWORK)`;
  - `app/.../ui/wallet/DisplayCurrencyStore.kt:37`;
  - `app/.../service/SyncWorker.kt:128,149`;
  - `app/.../service/SyncService.kt:1733,3261,3319,3516,3565`;
  - `app/.../StaleDataWiper.kt:44` for `dgb_bloom_peers`;
  - `core/.../WalletManager.kt:1645`, the `moveFromLegacy` call;
  - `core/.../WalletDataEraser.kt:76`. `WALLET_NETWORK_SUFFIXES` stays, as the list of network suffixes the full wipe walks for `legacy`.
- **A per-wallet file read under its network-only 4.1.4 name** (F11). Each resolves through `WalletStores.prefsName(base, WalletScope.legacy(testnet))` (or `fileName(base, WalletScope.legacy(testnet))`), which equals today's shared name, so the gate needs no exemption:
  - `core/.../reconcile/DgbNodeClient.kt:117`: `custom_rpc_endpoint` is a device setting every wallet must see, so the quick start can find it. It stays in legacy's `dgb_reconcile<net>`;
  - `core/.../settings/DisplayCurrencyPrefs.kt`, `moveFromLegacy`: reads legacy's `dgb_sync_data<net>`;
  - the all-networks helpers, which walk `legacy(false)` and `legacy(true)`: `SavedBlockStore.deleteAllNetworks` (`:130`), `FilterHeaderStore.deleteAllNetworks` (`:117`), `CfScanLedgerStore.deleteAllNetworks` (`:80`), `SavedTransactionsStore.deleteAllNetworks` (`:140`), `CfAbandonmentStore.clearAllNetworks` (`:333`);
  - the string templates `"dgb_sync_data$suffix"` in `core/.../sync/HistoryRebuildOnUpgrade.kt:520` and `app/.../StaleDataWiper.kt:50`.
- Modify: `core/.../NetworkState.kt`. Delete `networkSuffix`; update the KDoc to point to `storageSuffix`.
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/StoreSuffixSourceGateTest.kt`
- Test (update): `core/src/test/java/app/aroundtheblock/wallet/core/sync/HistoryRebuildOnUpgradeTest.kt` and `app/src/test/java/app/aroundtheblock/wallet/StaleDataWiperWalletResetTest.kt`. No test calls `networkSuffix`; they change because every store's default scope is now `ActiveWallet.scope(ctx)`, which is cached per application context (F1, F27). `HistoryRebuildOnUpgradeTest.perNetwork_stateAndCacheAreSeparate` (`:442-455`) flips testnet within one context, so it calls `ActiveWallet.resetForTest()` right after the flip. Any other test that flips `dgb_network_testnet` on a context it already used does the same.

**Interfaces:**
- Consumes: `storageSuffix`, `WalletStores`, `ActiveWallet.scope` (Tasks 1 and 2).
- Produces: `WalletManager` gains a trailing constructor parameter `scope: WalletScope = WalletScope.legacy(isTestnet(context))`, used by `prefs`, `watchedPrefs` and **every** store call inside `WalletManager`: `clearSyncData` (`:1637-1667`) and `restoreFromDisk` (`:363-490`, its `HistoryRebuildOnUpgrade.*` and `SavedTransactionsStore.load` calls), so no store call in the class falls back to the default `ActiveWallet` scope (F13). `OutgoingTxStore(context: Context, scope: WalletScope = ActiveWallet.scope(context))` and `AssetHistoryBackfill(…, scope: WalletScope = ActiveWallet.scope(context))` get the same parameter. Every store object above gains a trailing `scope: WalletScope = ActiveWallet.scope(ctx)` parameter. Examples: `SavedBlockStore.file(ctx: Context, scope: WalletScope = ActiveWallet.scope(ctx)): File`, `CfAbandonmentStore.clear(ctx: Context, scope: WalletScope = ActiveWallet.scope(ctx)): Boolean`, and `HistoryRebuildOnUpgrade.markNotNeeded(context: Context, forgetFloor: Boolean, scope: WalletScope = ActiveWallet.scope(context))`.

- [ ] **Step 1: Write the failing gate** `StoreSuffixSourceGateTest`. It scans `core/src/main/java` and `../app/src/main/java`, reusing the `statements()` helper style of `WalletWipeSourceGateTest`:
  - `networkSuffix is gone`: no `.kt` file contains `networkSuffix(`.
  - `no per-wallet store is named by a bare literal`: for every base in `WalletStores.WALLET_AND_NETWORK_PREFS + WALLET_PREFS`, no file outside `wallets/WalletStores.kt`, `WalletDataEraser.kt` and `HubTokenStore.kt` contains `getSharedPreferences("<base>"`, `"<base>" +` or the template start `"<base>$` (F11). Bases held in constants (`PREFS_NAME`, `PREFS_BASE`, `LEGACY_PREFS`) must be passed to `WalletStores.prefsName`, so `getSharedPreferences(<CONST>` followed by `+` also fails. There is no per-file exemption beyond those three files: the network-only readers resolve through `WalletScope.legacy(testnet)`.
  - `the scan sees store opens at all`: at least 30 `getSharedPreferences(` statements were found, so a blind scanner fails.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.core.wallets.StoreSuffixSourceGateTest"`. Expected FAIL: `networkSuffix is gone` lists the 19 files that contain `networkSuffix(` (F27).
- [ ] **Step 3: Implement** the sweep file by file, using the grouping above. Inside `WalletManager`, pass `this.scope` to every store call (`clearSyncData` and `restoreFromDisk` included). Keep each line's existing `commit()`/`apply()` choice. `WalletWipeSourceGateTest` asserts on them.
- [ ] **Step 4: Run** the gate, then `./gradlew :core:testMainnetDebugUnitTest --tests "*.core.wallets.*"`. Expected PASS. `LegacyStoreNameParityTest` still passes, which proves the legacy names did not move.
- [ ] **Step 5: Run the full suites.** Expected exit 0. Source gates that assert `"dgb_sync_data" + …` shapes may need their regex widened to `WalletStores.prefsName("dgb_sync_data"`. Change the regex and keep what it asserts, and list every such gate in the commit message.
- [ ] **Step 6: Commit** the explicit paths of every file touched, message `refactor(wallets): every store resolves through storageSuffix; networkSuffix removed (legacy names unchanged)`.

### Task 4: The scan floor key and the Hub session become per wallet

**Files:**
Task 3 already routes the watch set, sends, asset state and diag through `WalletStores`. This task covers what is a key or a separate store type rather than a prefs file name.

- Modify: `core/.../WalletManager.kt:310-311` and `:816-817` (`cf_birth_height`).
- Modify: `app/.../service/SyncService.kt`, at the `cf_birth_height` reads and writes at `:2296,2424,2427,2428,2456-2457,2552-2553,4035`. Each becomes `val birthKey = WalletStores.cfBirthHeightKey(scope)`.
- Modify: `app/.../service/CfRestorePreflight.kt:92-94` (KDoc only).
- Modify: `core/.../digiscope/HubTokenStore.kt`. Add an internal constructor `HubTokenStore(context: Context, scope: WalletScope, secureFactory: (String) -> SharedPreferences)` that opens the plain store `WalletStores.prefsName(LEGACY_PREFS_NAME, scope)` through `context.getSharedPreferences`, the encrypted store `prefsName(SECURE_PREFS_NAME, scope)` through `secureFactory`, and resets by deleting that same scoped secure name (F23). The public `HubTokenStore(context: Context, scope: WalletScope)` delegates with `secureFactory = { name -> encryptedPrefs(context.applicationContext, name) }` (`encryptedPrefs` gains the file-name parameter). The existing `HubTokenStore(context)` delegates with `ActiveWallet.scope(context)`. The existing internal `(legacy, secureProvider, resetSecure)` constructor stays for the existing tests.
- Modify: `core/.../digiscope/DigiScopeClient.kt:85`, to `HubTokenStore(context, ActiveWallet.scope(context))`.
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/security/HubTokenStoreScopeTest.kt`
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/PerWalletStoresTest.kt`
- Test (update): `app/src/test/java/app/aroundtheblock/wallet/service/TransactionCheckpointPreflightTest.kt:71`, where the literal `putLong("cf_birth_height", birth)` becomes `putLong(birthKey, birth)`; `app/src/test/java/app/aroundtheblock/wallet/service/HistoryRebuildWiringTest.kt:154`, where `"cf_birth_height"` becomes `birthKey`; and `StaleDataWiperWalletResetTest.kt:332-350`, which uses `WalletStores.cfBirthHeightKey(WalletScope.legacy(false))`.

**Interfaces:**
- Consumes: Tasks 1 to 3.
- Produces: `HubTokenStore(context: Context, scope: WalletScope)`; internal `HubTokenStore(context: Context, scope: WalletScope, secureFactory: (String) -> SharedPreferences)`.

- [ ] **Step 1: Write the failing tests.**
  - `HubTokenStoreScopeTest.each wallet opens its own Hub stores` (F23). Build the store through the new internal `(context, scope, secureFactory)` constructor over one `wipeTestContext`, with a `secureFactory` that records the names it is asked for and returns `InMemoryPrefs`. For A=`legacy` the names opened are exactly `"dgb_digiscope"` (plain, read from the context's opened-names log) and `"dgb_hub_session"` (secure); for B=`0123456789abcdef` they are `"dgb_digiscope_0123456789abcdef"` and `"dgb_hub_session_0123456789abcdef"`.
  - `HubTokenStoreScopeTest.wallet B never reads wallet A's Hub token`. Same construction, both stores over the one context and one name-keyed `InMemoryPrefs` map: after `A.save("tok-a")`, `B.load() == null` and `A.load() == "tok-a"`. This is not isolated by construction, because both stores draw from the same map; only the names keep them apart.
  - `PerWalletStoresTest.a non-legacy wallet's watch set, sends and floor are its own`. Over `wipeTestContext`, seed `dgb_watched_addrs_0123456789abcdef` with `addrs = {"dgb1qb…"}` (a dummy), then build `WalletManager(…, scope = B)` and `WalletManager(…, scope = legacy)`: B's `watchedReceiveAddresses() == setOf("dgb1qb…")` and legacy's is empty (F23). Record a send through `OutgoingTxStore(ctx, B)`: legacy's `dgb_outgoing_tx` stays empty. The recover path with `birthHeightNative` faked writes `dgb_settings` key `cf_birth_height_0123456789abcdef` only.
  - `PerWalletStoresTest.legacy still writes cf_birth_height`. With scope legacy, the recover path writes `dgb_settings["cf_birth_height"]`.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.HubTokenStoreScopeTest" --tests "*.PerWalletStoresTest"`. Expected FAIL: no `HubTokenStore(Context, WalletScope)`.
- [ ] **Step 3: Implement** as listed. Read every `cf_birth_height` key through `WalletStores.cfBirthHeightKey(scope)`. The `scope` in `SyncService` is `ActiveWallet.scope(this)`, read once in `onCreate`.
- [ ] **Step 4: Run** the same command plus `./gradlew :app:testMainnetPlayDebugUnitTest --tests "*.TransactionCheckpointPreflightTest" --tests "*.HistoryRebuildWiringTest" --tests "*.StaleDataWiperWalletResetTest"`. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, message `feat(wallets): scan floor key and Hub session are per wallet`.

### Task 5: The per-wallet seed record and Keystore aliases

**Files:**
- Modify: `core/.../WalletManager.kt`. The `scope` parameter exists since Task 3.
- Add `fun storeRecordOnly(mnemonic: String, passphrase: ByteArray?, creationTimeSecs: Long, origin: WalletOrigin): Boolean`. It seals through `sealForNewRecord`, then `storeSeedRecord`. It does not call native create or recover, `clearSyncData` or rescan. It returns false and writes nothing when the scope's seed prefs or `dgb_sync_data` prefs are non-empty. Task 7 widens this check to `!WalletStoreEraser.storesLeft(scope)`, which counts every store of the scope but **not** its aliases, because `prepareSeedKey` creates `dgb_wallet_master_<id>_v2` before the record is written (F2).
- `wallet_network` (`"mainnet"` / `"testnet"`) joins `SEED_RECORD_FIELDS` (`WalletManager.kt:99`) and is written in the **one** `storeSeedRecord` commit, so the registry can be rebuilt from disk and the record stays a single write (F29). `SeedRecordWriteTest`, which pins exactly one write, must still pass unchanged.
- Add defaulted native seams to the constructor, used by `restoreFromDisk` (`:363-490`) in place of its direct `NativeBridge` calls, so Tasks 5 and 6 run on the JVM (F13): `restoreNative: (ByteArray, Long, ByteArray?) -> Boolean = NativeBridge::restoreWalletFromBytes`, `stopSyncNative: () -> Unit = NativeBridge::stopSync`, `peerCountNative: () -> Int = NativeBridge::getPeerCount`, `loadTxNative: (ByteArray) -> Int = NativeBridge::loadSerializedTransactions`, `sleep: (Long) -> Unit = Thread::sleep` (the 100 ms peer-drain poll and the 300 ms settle).
- Add `fun recordIdentity(): String?`, the record's `seed_fingerprint_v2`, or null.
- Add `fun recordFingerprintV1(): String?`.
- Modify: `app/.../di/AppModule.kt:30` (`provideKeyStoreManager` → `KeyStoreManager(context, WalletStores.keyAlias(ActiveWallet.scope(context)))`), `:113-123` (`provideWalletManager` passes `scope = ActiveWallet.scope(context)`). Add `@Provides @Singleton fun provideWalletRegistry(@ApplicationContext context: Context): WalletRegistry = ActiveWallet.registry(context)`. Add `fun interface WalletManagerFactory { fun forScope(scope: WalletScope): WalletManager }`, provided as a lambda that builds `WalletManager(context, KeyStoreManager(context, WalletStores.keyAlias(scope)), um, scope = scope)`.
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/ScopedSeedRecordTest.kt`
- Test (update): `core/src/test/java/app/aroundtheblock/wallet/core/KeyTestSupport.kt`. Change to `keyStoreManagerOver(vault: FakeVault, authBound: Boolean = true, alias: String = LEGACY_ALIAS)`, so the bound alias becomes `alias + "_v2"`.

**Interfaces:**
- Consumes: Tasks 1 to 4; `KeyStoreManager(context, alias)` (unchanged, `KeyStoreManager.kt:37-40`).
- Produces: `WalletManager.storeRecordOnly(...)`, `WalletManager.recordIdentity()`, `WalletManager.recordFingerprintV1()`, the seams `restoreNative` / `stopSyncNative` / `peerCountNative` / `loadTxNative` / `sleep`, `WalletManagerFactory` (consumed by Task 8's `WalletProvisioner`).

- [ ] **Step 1: Write the failing tests** in `ScopedSeedRecordTest`, using `keyTestContext`, `FakeVault` and `seedNative = { _, _ -> ByteArray(64) { 7 } }`:
  - `a new wallet's record lands in its own prefs under its own aliases`. For scope B, `storeRecordOnly("abandon … about", null, 1_704_067_200L, RESTORED)` is true. Then:
    - `prefsFor("dgb_wallet_seed_0123456789abcdef")` contains `encrypted_seed_v2`;
    - `prefsFor("dgb_wallet_seed").all.isEmpty()`;
    - `vault.keys.keys == setOf("dgb_wallet_master_0123456789abcdef_v2")` (the vault's `keys` is a Map, F14);
    - `wallet_creation_time == 1_704_067_200L`;
    - `wallet_origin == "restored"`;
    - `wallet_network == "mainnet"`, and the seed prefs saw exactly one `commit()` (F29).
  - `storing a record touches no native wallet and no other store`. `createNative`, `recoverNative` and `rescanNative` are fakes that fail the test if called. Every prefs name the factory opened, minus `dgb_wallet_seed_0123456789abcdef`, holds what it held before.
  - `a record is never stored over existing state of its scope`. Seed `dgb_sync_data_0123456789abcdef` with `has_synced=true`; `storeRecordOnly(...) == false` and nothing is written.
  - `restoreFromDisk for B reads B's record and does not clear B's sync data`. After `storeRecordOnly`, seed B's `dgb_sync_data_…` with `has_synced=true` and call `restoreFromDisk()` with `restoreNative = { _, _, _ -> true }`, `stopSyncNative = {}`, `peerCountNative = { 0 }`, `loadTxNative = { 0 }` and `sleep = {}`. `has_synced` stays true, because the fingerprint verdict compares B's record with B's own fingerprint, and the `ByteArray` passed to `restoreNative` is zeroed after the call.
  - `the legacy scope is byte-for-byte the old manager`. A `WalletManager` with `scope = WalletScope.legacy(false)` over a legacy record behaves exactly as before. All of `SeedRecordWriteTest` is re-run unchanged through the default parameter, so no new assertion is needed. State that in the test KDoc.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.ScopedSeedRecordTest"`. Expected FAIL: no parameter named `scope`.
- [ ] **Step 3: Implement** as listed. The mnemonic bytes in `storeRecordOnly` are zeroed in `finally`, as in `createWallet:248-268`.
- [ ] **Step 4: Run** the same command plus `--tests "*.SeedRecordWriteTest" --tests "*.SeedRecordIdentityTest" --tests "*.SeedKeyBindAtCreationTest" --tests "*.KeyStoreHygieneSourceGateTest"`. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, message `feat(wallets): per-wallet seed record and Keystore aliases; legacy keeps dgb_wallet_master`.

### Task 6: Upgrade from 4.1.4 leaves the existing wallet untouched

**Files:**
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/UpgradeFrom414Test.kt`
- Test fixture: `core/src/test/resources/upgrade-4.1.4-layout.txt`. It lists one store per line: `prefs <name> <key>=<typed value>` or `file <name> <hex>`. Generate it once from `git show v4.1.4:` names. It holds no real phrase or address; the values are dummies.

**Interfaces:**
- Consumes: everything from Tasks 1 to 5.
- Produces: nothing new.

- [ ] **Step 1: Write the test.**
  - `UpgradeFrom414Test.an upgraded launch reads the 4.1.4 wallet as legacy and writes nothing`.
    - Seed `wipeTestContext` from the fixture. It covers every prefs file and file of `WalletStores` with legacy names on both networks, `dgb_settings` with `cf_birth_height=21000000L`, `app_language=de` and `dgb_network_testnet=false`, a `dgb_wallet_seed` record sealed through `keyStoreManagerOver(vault)`, and four `wallet.db*` files.
    - Snapshot every prefs map and every file's bytes. Run the launch-time reads in production order: `ActiveWallet.scope(ctx)`, `ActiveWallet.registry(ctx).list(false)`, `WalletStores.databaseName(scope)` and `WalletManager(…, scope).hasSavedWallet()`. Task 8 inserts `WalletHousekeeping(ctx).run()` after `registry(ctx).list(false)`.
    - Assert:
      - `scope == WalletScope.legacy(false)`;
      - the list's ids are `["legacy"]`;
      - `hasSavedWallet()` is true;
      - the database name is `"wallet.db"`;
      - every snapshot is equal to before, with `dgb_wallets` absent or empty;
      - the vault's aliases are still `{dgb_wallet_master, dgb_wallet_master_v2}` as seeded.
  - `UpgradeFrom414Test.a restore of the legacy wallet after upgrade opens its sync state`. `restoreFromDisk()` with Task 5's seams faked (`restoreNative = { _, _, _ -> true }`, `stopSyncNative = {}`, `peerCountNative = { 0 }`, `loadTxNative = { 0 }`, `sleep = {}`) leaves `dgb_sync_data.has_synced == true`, and `SavedBlockStore.file(ctx).exists()` is still true.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.UpgradeFrom414Test"`. Expected PASS on first run: this task is a proof, not a change. Then prove that it can fail. Temporarily change `storageSuffix`'s legacy WALLET_AND_NETWORK branch to `"_legacy"`. It must FAIL on the database-name assertion (`"wallet_legacy.db"` instead of `"wallet.db"`) and on the snapshot equality of the first test; test 1 never reads `has_synced`, so that is not the symptom to look for (F28). Revert the change.
- [ ] **Step 3: Run the full suites.** Expected exit 0.
- [ ] **Step 4: Commit** the test and fixture, message `test(wallets): upgrade from 4.1.4 keeps every store, key and alias of the existing wallet`.

### Task 7: Erase one wallet's scope

**Files:**
- Create: `core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletStoreEraser.kt`
- Modify: `core/.../WalletDataEraser.kt:113-122`. Add `fun aliases(): List<String>` to `KeystoreAliases`, implemented with `keyStore().aliases().toList()`.
- Modify: `core/.../WalletManager.kt`. `storeRecordOnly`'s precondition becomes `!storesLeft(scope)`, through a new defaulted constructor parameter `storesLeft: (WalletScope) -> Boolean = { WalletStoreEraser(context).storesLeft(it) }` (F2).
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/WalletStoreEraserTest.kt`
- Test (update, F14): the `FakeKeystore` classes in `core/src/test/.../WalletWipeTest.kt`, `WalletSessionAfterWipeTest.kt` and `WipeRemovesSetAsideDatabasesTest.kt` implement `aliases()`. `core/src/test/.../KeyTestSupport.kt` gains `fun FakeVault.asKeystoreAliases(): KeystoreAliases`, so an eraser and a `KeyStoreManager` can share one vault. Assertions on the vault use `vault.keys.keys` (a Set), never `vault.keys` (a Map).

**Interfaces:**
- Consumes: `WalletStores`, `KeystoreAliases`, `HubTokenStore(context, scope)`, `DatabaseFiles.everyFileOf`, `DatabaseKeyProvisioner.releaseLegacyWrap`.
- Produces:
```kotlin
enum class AliasOutcome { GONE, KEPT_FOR_DB_KEY, LEFT }
class WalletStoreEraser internal constructor(
    private val context: Context,
    private val keystore: KeystoreAliases,
    private val releaseLegacyDbWrap: () -> Boolean,   // DatabaseKeyProvisioner.forContext(context).releaseLegacyWrap()
    private val prefsLeft: (String) -> Boolean,       // production: shared_prefs/<name>.xml exists;
                                                      // JVM harness: that name's map is non-empty (F15)
) {
    constructor(context: Context)
    /** Phase 1: the scope's seed record (both networks for legacy). True when it reads back gone. */
    fun eraseRecord(scope: WalletScope): Boolean
    /** Phase 2: the scope's aliases (`dgb_wallet_master[_<id>]` and `_v2`). Legacy's only after releaseLegacyDbWrap() is true. */
    fun eraseAliases(scope: WalletScope): AliasOutcome
    /** Phase 3: every other store of [scope]. For legacy: both networks. Shared stores are never touched: NETWORK_PREFS,
     *  dgb_settings except the scope's floor key, the custom_rpc_endpoint key of legacy's dgb_reconcile<net>,
     *  dgb_db_key, dgb_pin_store, dgb_wallets. */
    fun eraseStores(scope: WalletScope): Boolean
    /** eraseRecord, then eraseAliases, then eraseStores (F5). */
    fun erase(scope: WalletScope): EraseReport
    /** Read-back: any prefs file (the seed record included), file or DB file of [scope] is still present. Aliases excluded (F2). */
    fun storesLeft(scope: WalletScope): Boolean
    fun aliasesLeft(scope: WalletScope): Boolean
    fun anythingLeft(scope: WalletScope): Boolean     // storesLeft(scope) || aliasesLeft(scope)
}
data class EraseReport(val recordGone: Boolean, val aliasesGone: Boolean, val storesGone: Boolean, val dbKeyHeldLegacyKey: Boolean) {
    val verified: Boolean get() = recordGone && aliasesGone && storesGone
}
```
- Rules:
  - The legacy aliases are deleted only after `releaseLegacyDbWrap()` returns true. Otherwise they stay: `eraseAliases` returns `KEPT_FOR_DB_KEY`, and `erase` reports `aliasesGone = false`, `dbKeyHeldLegacyKey = true`.
  - Each per-wallet prefs file is cleared with `commit()` and then removed with `context.deleteSharedPreferences(name)` (F15). Both results are part of the report, as in `AndroidWalletDataEraser.clearPrefs`. That also removes the Tink keyset entries `EncryptedSharedPreferences` leaves in `dgb_hub_session[_<id>]`, which a clear alone keeps.
  - "Left" for a prefs file means `prefsLeft(name)`. Legacy's `dgb_reconcile<net>` is cleared except `custom_rpc_endpoint` and never deleted, so for it "left" means any key other than `custom_rpc_endpoint`.

- [ ] **Step 1: Write the failing tests:**
  - `erasing B leaves every byte of legacy and C`. Populate three scopes (legacy, B, C) across every `WalletStores` list, plus a database file each, plus aliases. After `erase(B).verified == true`, every store of legacy and C equals its snapshot, `anythingLeft(B) == false`, and `vault.keys.keys == setOf("dgb_wallet_master", "dgb_wallet_master_v2", "dgb_wallet_master_<C>_v2")`.
  - `shared stores survive`: `dgb_bloom_peers`, `dgb_price`, `dgb_settings.app_language`, `dgb_db_key`, `dgb_wallets` and `dgb_pin_store` are unchanged.
  - `erasing legacy keeps the custom node endpoint`. Seed `dgb_reconcile` with `custom_rpc_endpoint=https://node.example` and `reconcile_done=true`. After `erase(legacy)`, `dgb_reconcile` equals exactly `{custom_rpc_endpoint=https://node.example}`, and `storesLeft(legacy) == false`.
  - `erasing legacy removes it on both networks`: no `dgb_sync_data_testnet` and no `wallet_testnet.db` remain.
  - `legacy's key stays while the database record depends on it`. With `releaseLegacyDbWrap = { false }`, `eraseAliases(legacy) == KEPT_FOR_DB_KEY`, the report has `dbKeyHeldLegacyKey == true` and `aliasesGone == false`, and `"dgb_wallet_master" in vault.keys.keys`.
  - `an alias alone is not a store` (F2). With only `dgb_wallet_master_<B>_v2` in the vault and no prefs or files of B, `storesLeft(B) == false`, `aliasesLeft(B) == true` and `anythingLeft(B) == true`.
  - `each phase runs alone` (F5). `eraseRecord(B)` leaves B's aliases and `dgb_sync_data_<B>`; `eraseAliases(B)` then leaves `dgb_sync_data_<B>`; `eraseStores(B)` then leaves nothing.
  - `a cleared prefs file is deleted, not just emptied` (F15). After `erase(B)`, the mock context recorded `deleteSharedPreferences` for every per-wallet prefs name of B, `dgb_hub_session_<B>` included.
  - `a refused commit is reported`: `refuseWritesTo("dgb_sync_data_<B>")` gives `storesGone == false`.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.WalletStoreEraserTest"`. Expected FAIL: unresolved class.
- [ ] **Step 3: Implement.** Iterate the `WalletStores` lists, so there is no second list. Tests build the eraser through the internal constructor with `keystore = vault.asKeystoreAliases()` and `prefsLeft = { name -> prefsFor(name).all.isNotEmpty() }`. Widen `WalletManager.storeRecordOnly`'s precondition to `!storesLeft(scope)`; `ScopedSeedRecordTest` must still pass. Update the three `FakeKeystore` classes. `WalletWipeSourceGateTest` applies its "no discarded commit" rule to this file too: add it to that gate's scanned files.
- [ ] **Step 4: Run** the same command plus `--tests "*.WalletWipeSourceGateTest" --tests "*.ScopedSeedRecordTest" --tests "*.WalletWipeTest" --tests "*.WalletSessionAfterWipeTest" --tests "*.WipeRemovesSetAsideDatabasesTest"`. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths (the three updated test files and `KeyTestSupport.kt` included), message `feat(wallets): erase one wallet's scope in three phases with read-back; shared stores untouched`.

### Task 8: Add a wallet (core): reserve, duplicate check, record, complete, launch housekeeping

**Files:**
- Create: `core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletProvisioner.kt`
- Create: `core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletHousekeeping.kt`
- Modify: `app/.../DigiByteApp.kt:52-58`. After `applyNetworkSelection()` and before `HistoryRebuildOnUpgrade.runAtProcessStart`, add `runCatching { WalletHousekeeping(this).run() }`. It must run before anything opens the Room database or starts `SyncService`, because Task 11 leaves the removed active wallet for it to erase (F8). Today's injected fields (`workerFactory`, `okHttpClient`, `ipfsClient`, `torManager`) open neither; the step 4 check keeps it so.
- Modify: `app/.../di/AppModule.kt` (F18). Provide `WalletStoreEraser(context)` and `WalletProvisioner(registry, walletManagerFactory, eraser, deriveSeed = NativeBridge::mnemonicToSeed)`, both `@Singleton`.
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/WalletProvisionerTest.kt`
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/WalletHousekeepingTest.kt`

**Interfaces:**
- Consumes: `WalletRegistry` (`canReserve`, `reserve`, `complete`, `drop`, `incomplete`), `WalletManagerFactory` (Task 5), `WalletStoreEraser.erase` / `anythingLeft` (Task 7), `SeedFingerprint.v1/v2`, `NativeBridge.mnemonicToSeed` (injected).
- Produces:
```kotlin
sealed class AddResult {
    data class Added(val entry: WalletEntry) : AddResult()
    data class Duplicate(val existing: WalletEntry) : AddResult()
    object CapReached : AddResult()   // never returned by add(); OnboardingViewModel.commitAdd maps prepareSeedKey == null to it (F24)
    object Failed : AddResult()
}
class WalletProvisioner(
    private val registry: WalletRegistry,
    private val managerFactory: WalletManagerFactory,   // Task 5's fun interface, not a lambda (F18)
    private val eraser: WalletStoreEraser,
    private val deriveSeed: (ByteArray, ByteArray?) -> ByteArray?,   // NativeBridge.mnemonicToSeed
    private val nowSecs: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** The READY wallet on [testnet] this phrase+passphrase opens, or null. Zeroes what it derives. */
    fun duplicateOf(testnet: Boolean, mnemonic: String, passphrase: ByteArray?): WalletEntry?
    /** Prepares the new scope's seed key (KS-BIND) before [add]; same contract as WalletManager.prepareSeedKeyForNewRecord. */
    fun prepareSeedKey(testnet: Boolean): Pair<WalletEntry, SeedKeyReadiness>?   // null at the cap; reserves the entry
    /** Checks for a duplicate, writes the record for [entry] and completes it as the active wallet. On Duplicate or
     *  Failed it erases the scope (the alias prepareSeedKey made included) and drops the entry (F24).
     *  Caller owns and zeroes [passphrase]. */
    fun add(entry: WalletEntry, mnemonic: String, passphrase: ByteArray?, creationTimeSecs: Long): AddResult
}
class WalletHousekeeping internal constructor(private val registry: WalletRegistry, private val eraser: WalletStoreEraser) {
    constructor(context: Context)   // ActiveWallet.registry(context), WalletStoreEraser(context)
    /** For each PENDING or REMOVING entry: erase its scope; drop the entry only when erase(...).verified (F6).
     *  An entry whose erase does not verify keeps its state and is retried at the next launch (Task 11 relies on this). */
    fun run(): Int   // entries dropped
}
```
- The duplicate rule compares `SeedFingerprint.v2(deriveSeed(m, p))` with each READY entry's `recordIdentity()`. For a record without v2 and `p == null`, it compares `SeedFingerprint.v1(m)` with `recordFingerprintV1()`. Every derived buffer is zeroed in `finally`.
- The creation time for a create is `nowSecs()`. For a restore it is the chosen `RecoveryDateOption.timestamp`, which is always > 0 (`RecoveryDateScreen.kt:110,349-410`), so `restoreFromDisk` builds the new wallet from the right birth checkpoint after the restart. The top-level `compactFilterBirthHeight` (`app/.../service/CfRestorePreflight.kt:76-84`) falls back to `walletBirth` when no floor key exists, so no native call is needed before the restart.

- [ ] **Step 1: Write the failing tests** in `WalletProvisionerTest`, over `wipeTestContext`, `FakeVault`, and a `deriveSeed` that returns `sha256(mnemonic + passphrase)` padded to 64 bytes:
  - `adding a restored wallet stores its record and makes it active`: `add(entry, PHRASE_B, null, 1_704_067_200L)` is `Added`; `registry.activeId(false) == entry.id`; `entry.state` reads back as READY; the record's creation time is `1_704_067_200L`.
  - `a phrase already in the app is refused and names the wallet it matches`. With legacy holding `PHRASE_A`, `duplicateOf(false, PHRASE_A, null)?.id == "legacy"`, and `add` (after `prepareSeedKey`) for `PHRASE_A` returns `Duplicate(legacy)` and writes no `dgb_wallet_seed_<id>`.
  - `a duplicate found at add leaves no entry and no alias` (F24). After that `Duplicate`, `registry.incomplete().isEmpty()`, `eraser.anythingLeft(scope) == false`, and `vault.keys.keys` holds no `dgb_wallet_master_<id>*`.
  - `the same mnemonic with a passphrase is a different wallet`: `duplicateOf(false, PHRASE_A, "x".toByteArray()) == null`.
  - `a legacy record from before fingerprint v2 is still matched`. Remove legacy's `seed_fingerprint_v2` and keep `seed_fingerprint`; `duplicateOf(false, PHRASE_A, null)?.id == "legacy"`.
  - `the ninth wallet is refused before anything is written`: with 8 READY, `prepareSeedKey(false) == null` and `vault.keys.keys` is unchanged.
  - `a prepared key does not block the add` (F2): after `prepareSeedKey(false)` created `dgb_wallet_master_<id>_v2`, `add(...)` for a new phrase is `Added`.
  - `a failed record write leaves no entry and no store`. `refuseWritesTo("dgb_wallet_seed_<id>")` gives `Failed`; afterwards `registry.incomplete().isEmpty()` and `eraser.anythingLeft(scope) == false`.
  - `adding never touches the active wallet's stores`. Snapshot legacy's stores, `add` B, and compare: they are equal.
- [ ] **Step 2: Write the failing tests** in `WalletHousekeepingTest`:
  - `an add killed after its record landed is erased at the next launch`. Do `reserve` and `storeRecordOnly`, but not `complete`. `run() == 1`; then `incomplete().isEmpty()`, `anythingLeft(scope) == false`, and `list(false)` has ids `["legacy"]`.
  - `a REMOVING entry whose erase does not verify is kept for the next launch` (F6). Mark legacy REMOVING (other wallets exist) with `releaseLegacyDbWrap = { false }`: `run() == 0`, `find("legacy")!!.state == REMOVING`. With `releaseLegacyDbWrap = { true }`, a second `run() == 1` and `find("legacy") == null`.
  - `housekeeping with nothing incomplete writes nothing`. On the 4.1.4 layout, `run() == 0` and `dgb_wallets` is still absent. Then add `WalletHousekeeping(ctx).run()` to `UpgradeFrom414Test`'s launch sequence, which must still find every snapshot unchanged.
- [ ] **Step 3: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.WalletProvisionerTest" --tests "*.WalletHousekeepingTest" --tests "*.UpgradeFrom414Test"`. Expected FAIL: unresolved classes.
- [ ] **Step 4: Implement** as specified. `add` order: `duplicateOf(...)`, then `managerFactory.forScope(scope).storeRecordOnly(...)`, then `registry.complete(id, makeActive = true)`. On a duplicate, or a false result from either step, call `eraser.erase(scope)` and `registry.drop(id)`, then return `Duplicate(existing)` or `Failed` (F24). Add the two `AppModule` providers. Check that no field injected into `DigiByteApp` opens the database (F8).
- [ ] **Step 5: Run** the same command. Expected PASS.
- [ ] **Step 6: Run the full suites.** Expected exit 0.
- [ ] **Step 7: Commit** the explicit paths (`UpgradeFrom414Test.kt` and `AppModule.kt` included), message `feat(wallets): add a wallet by writing only its own record; duplicate phrases refused; interrupted adds erased at launch`.

### Task 9: Switching: persist the active id, end the web session, restart

**Files:**
- Create: `app/src/main/java/app/aroundtheblock/wallet/AppRestart.kt`
- Create: `app/src/main/java/app/aroundtheblock/wallet/WalletSwitcher.kt`
- Modify: `app/.../ui/settings/SettingsViewModel.kt:214-228`. `setNetworkTestnet` calls `AppRestart.restart(context)`; `restartApp()` is deleted.
- Modify: `app/.../ui/settings/ReconcileScreen.kt:385,572`. Both verbatim copies of `restartApp` (`startActivity` + `Runtime.getRuntime().exit(0)`) become `AppRestart.restart(context)` (F16).
- Modify: `app/.../FreshStartAfterWipe.kt:243-264`. `RelaunchInFreshProcess`'s relaunch routine takes the intent extras as a parameter, so `AppRestart` calls the same routine with none (F16).
- Modify: `app/.../BootGuard.kt:162`. `markBootHealthy(context: Context, sync: Boolean = false)`; with `sync = true` the counter reset is `commit()`ed, so it lands before the process ends (F17).
- Modify: `app/.../IdentitySessionEraser.kt`. Expose `fun endWebSessionOnly(): Boolean`, which runs only the `endWebSession` part on the main thread.
- Modify: `app/.../di/AppModule.kt`. Provide `WalletSwitcher` with `currentId = { ActiveWallet.scope(context).walletId }`.
- Test: `app/src/test/java/app/aroundtheblock/wallet/WalletSwitcherTest.kt`
- Test: `app/src/test/java/app/aroundtheblock/wallet/AppRestartGateTest.kt`

**Interfaces:**
- Consumes: `WalletRegistry.setActive` / `find`, `ActiveWallet.scope`, `IdentitySessionEraser`, `RelaunchInFreshProcess` (`FreshStartAfterWipe.kt:243`), `BootGuard.markBootHealthy`.
- Produces:
```kotlin
fun interface AppRestart {
    fun restart(context: Context)
    companion object : AppRestart {   // the one production path outside the wipe hand-over
        override fun restart(context: Context)   // BootGuard.markBootHealthy(context, sync = true), then
                                                 // RelaunchInFreshProcess's main-thread relaunch-then-killProcess (F16, F17)
    }
}
class WalletSwitcher internal constructor(
    private val registry: WalletRegistry,
    private val testnet: () -> Boolean,
    private val currentId: () -> String,      // the wallet THIS process runs: ActiveWallet.scope(ctx).walletId (F3)
    private val endWebSession: () -> Boolean,
    private val restart: () -> Unit,
) {
    enum class Result { RESTARTING, ALREADY_ACTIVE, UNKNOWN_WALLET, NOT_SAVED }
    fun switchTo(id: String): Result
}
```
- Order inside `switchTo`: validate that the id is READY on this network, else `UNKNOWN_WALLET`. If `id == currentId()`, return `ALREADY_ACTIVE`. This compares with the running process, not with the registry's active id, because `add` already made the new wallet active in the registry (F3). Then `registry.setActive(...)`; if the commit fails, return `NOT_SAVED`. Then `endWebSession()`. Its result is logged by outcome only and never blocks the switch, because the new process starts with the new wallet either way. Then `restart()`. Hub logout is deliberately **not** called: wallet A's server session stays valid for A, and its token is in A's store.
- `AppRestart` relaunches without the wipe extras; the hand-over's `EXTRA_STARTED_AFTER_WIPE` is not set. Reuse means one relaunch routine, parameterised, not a second copy of `relaunch`.

- [ ] **Step 1: Write the failing tests.** The registry is a mockk `WalletRegistry` (no app-side prefs harness, F26):
  - `WalletSwitcherTest.a switch persists the id, ends the web session, then restarts`. Record calls into a list; for id B (READY, `currentId = { "legacy" }`) the list is `["setActive:B", "endWebSession", "restart"]`.
  - `WalletSwitcherTest.a switch ends the web session before it restarts`. When `endWebSession` returns false, the order is the same and the result is `RESTARTING`.
  - `WalletSwitcherTest.an unsaved id never restarts`. With `every { registry.setActive(false, B) } returns false` the result is `NOT_SAVED` and `restart` is not called.
  - `WalletSwitcherTest.the wallet this process runs is not restarted into`: `switchTo("legacy")` with `currentId = { "legacy" }` is `ALREADY_ACTIVE`, and no calls.
  - `WalletSwitcherTest.a wallet the registry already made active is still restarted into` (F3). With `registry.activeId(false) == B` (as after an add) and `currentId = { "legacy" }`, `switchTo(B) == RESTARTING` and `restart` was called once.
  - `AppRestartGateTest.one restart path` (F16). A source scan of `app/src/main/java` finds `Runtime.getRuntime().exit(` in no file, and `Process.killProcess(` only in `FreshStartAfterWipe.kt` (the wipe hand-over, which `AppRestart` reuses). It also asserts that the scan read at least 80 `.kt` files, so a blind scanner fails. On today's tree it is red on `SettingsViewModel.kt:227` and `ReconcileScreen.kt:385,572`.
- [ ] **Step 2: Run** `./gradlew :app:testMainnetPlayDebugUnitTest --tests "*.WalletSwitcherTest" --tests "*.AppRestartGateTest"`. Expected FAIL: unresolved classes, and the gate lists the three `exit(0)` sites.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run** the same command. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, message `feat(wallets): switch wallets by persisting the active id and restarting; one restart path; DigiStamp web session ended on switch`.

### Task 10: The wipe (PIN-failure and Settings) removes every wallet

**Files:**
- Modify: `core/.../WalletManager.kt:940-1003`, `wipeWallet`. It runs the eraser's phases across **every** scope, one phase at a time, never a per-scope `erase()` (F5):
  - the id set is `allWalletIds()` = registry ids ∪ seed-prefs files on disk (`walletIdOfSeedPrefsFile`) ∪ `walletIdOfAlias(keystore aliases)`;
  - phase 1, records: legacy's `eraseSeedCiphertext()` as today, then `scopeEraser.eraseRecord(scope)` for every non-legacy id;
  - phase 2, keys: only once every record reads back gone, delete **every** alias with `WalletStores.walletIdOfAlias(alias) != null`, legacy's included, through `KeystoreAliases` (F4). Not through `keyStoreManager.deleteKey()`, which is bound to the active wallet's alias: with wallet B active it would delete B's key and leave `dgb_wallet_master(_v2)`;
  - phase 3, stores: `scopeEraser.eraseStores(scope)` for every non-legacy id; legacy's stores through `AndroidWalletDataEraser` as today;
  - then clear `dgb_wallets`.
  - `recordGone` becomes "no seed record of any wallet is readable". `seedGone` = `recordGone` and no alias for which `walletIdOfAlias != null` remains (F4).
  - New constructor parameters, defaulted so pure-JVM constructions compile: `allWalletIds: () -> List<String>` (injected in tests, where InMemoryPrefs create no files; production lists `File(context.dataDir, "shared_prefs")`, F30), `scopeEraser: WalletStoreEraser` and `keystoreAliases: KeystoreAliases`.
- Modify: `core/.../WalletDataEraser.kt:99-102`. KDoc: one wipe for every wallet. Its legacy prefs list gains `dgb_quickstart`, so it reaches every per-wallet base of `WalletStores` (F12).
- Modify: `app/.../ui/settings/SecuritySettingsScreen.kt:228,528,563`. Use `R.string.sec_wipe_after_on_all`, `sec_wipe_all_body` and `sec_wipe_after_body_all`.
- Modify: `app/src/main/res/values*/strings_wallet.xml` (13 dirs). Add the three keys and delete `sec_wipe_after_on`, `sec_wipe_after_body` and `sec_wipe_wallet_body`.
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/WalletWipeEveryWalletTest.kt`
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/WalletDataEraserReachTest.kt` (F12)
- Test (update): `core/src/test/java/app/aroundtheblock/wallet/core/WalletWipeTest.kt:398-420`. Add `dgb_wallets` to the stores that must be empty after a wipe.

**Interfaces:**
- Consumes: Tasks 1, 2, 5 and 7 (`eraseRecord`, `eraseStores`, `KeystoreAliases.aliases()`, `FakeVault.asKeystoreAliases()`).
- Produces: no new public API. `WipeReport` semantics now cover every wallet.

- [ ] **Step 1: Write the failing tests** in `WalletWipeEveryWalletTest`. Every test injects `allWalletIds` and builds the eraser over `vault.asKeystoreAliases()`:
  - `a wipe erases every wallet's record, stores and aliases`. Populate legacy, B and C (records, stores, DBs, aliases `…_v2`, registry); `wipeThenReleasePin(pinManager).verified == true`. Afterwards no `dgb_wallet_seed*` prefs hold a key, `vault.keys.keys.none { WalletStores.walletIdOfAlias(it) != null }` holds (F14), no `wallet*.db*` file exists, `dgb_wallets` is empty, and the PIN store is cleared.
  - `a wipe with another wallet active erases legacy's key too` (F4). Build the `WalletManager` with `scope = B` and its `KeyStoreManager` bound to `dgb_wallet_master_<B>`. After the wipe, `"dgb_wallet_master" !in vault.keys.keys`, `"dgb_wallet_master_v2" !in vault.keys.keys`, `seedGone == true`, and the PIN store is cleared.
  - `a surviving alias of any wallet means the seed is not gone`. Make `vault.delete("dgb_wallet_master_<C>_v2")` a no-op; `seedGone == false`, and the PIN store is kept.
  - `every record goes before any key` (F5). The recorded event order has every `clear dgb_wallet_seed*` before the first `delete dgb_wallet_master*`.
  - `a wallet missing from the registry is still wiped` (F30). `allWalletIds` returns `[legacy, B, D]`, `dgb_wallets` names only legacy and B, and `prefsFor("dgb_wallet_seed_<D>")` holds a record: after the wipe it is empty.
- [ ] **Step 2: Write the failing test** `WalletDataEraserReachTest.every per-wallet store is reached by the full wipe` (F12). For each base in `WALLET_AND_NETWORK_PREFS + WALLET_PREFS` and each file base in `WALLET_AND_NETWORK_FILES`, populate legacy's name on both networks, run `AndroidWalletDataEraser`, and assert each is empty or absent. It fails today on `dgb_quickstart`. A per-wallet base added to `WalletStores` later fails here until the full wipe reaches it.
- [ ] **Step 3: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.WalletWipeEveryWalletTest" --tests "*.WalletWipeTest" --tests "*.WalletDataEraserReachTest"`. Expected FAIL: B's record survives, and the reach test names `dgb_quickstart`.
- [ ] **Step 4: Implement** as specified, then the strings in all 13 locales. `LocaleResourceParityTest` must pass.
- [ ] **Step 5: Run** the same command plus `./gradlew :app:testMainnetPlayDebugUnitTest --tests "*.LocaleResourceParityTest" --tests "*.OnboardingHardcodedStringTest" --tests "*.WipeCallSiteGateTest"`. Expected PASS.
- [ ] **Step 6: Run the full suites.** Expected exit 0.
- [ ] **Step 7: Commit** the explicit paths, including all 13 `strings_wallet.xml`, message `feat(wallets): the PIN-failure and Settings wipe remove every wallet; Security text says so (13 locales)`.

### Task 11: Remove one wallet (core)

**Files:**
- Create: `core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletRemoval.kt`
- Modify: `app/.../di/AppModule.kt`. Provide `WalletRemoval(registry, eraser, activeScope = ActiveWallet.scope(context), wipeAll = { walletManager.wipeThenReleasePin(pinManager) })` (F18).
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/WalletRemovalTest.kt`

**Interfaces:**
- Consumes: `WalletRegistry.markRemoving(id)` / `drop` / `list` / `find` (Task 2), `WalletStoreEraser.erase` / `anythingLeft` (Task 7), `WalletHousekeeping` (Task 8: finishes REMOVING, drops only when verified), `WalletManager.wipeThenReleasePin`.
- Produces:
```kotlin
sealed class RemoveResult {
    object Removed : RemoveResult()                     // a non-active wallet: erased now, no restart needed
    object RemovedRestartNeeded : RemoveResult()        // the active wallet: marked REMOVING, nothing erased yet;
                                                        // the caller restarts via AppRestart and housekeeping erases it (F8)
    data class WipedEverything(val report: WipeReport) : RemoveResult()   // it was the last wallet on the device
    object DatabaseKeyStillOnLegacy : RemoveResult()    // legacy kept REMOVING: housekeeping retries at each launch
    object Incomplete : RemoveResult()                  // housekeeping finishes it at the next launch
    object NameMismatch : RemoveResult()
}
class WalletRemoval(
    private val registry: WalletRegistry,
    private val eraser: WalletStoreEraser,
    private val activeScope: WalletScope,                // the wallet this process runs
    private val wipeAll: suspend () -> WipeReport,       // walletManager.wipeThenReleasePin(pinManager)
) {
    /** [typedName] must equal the entry's shown name (trimmed, exact). */
    suspend fun remove(id: String, typedName: String, shownName: String): RemoveResult
}
```
- There is no `stopActiveSession` parameter: the active wallet's live Room database and native session are never erased under the running process (F8).
- Order:
  1. Check the name, else `NameMismatch` with no writes.
  2. If this is the last READY wallet across both networks, run `wipeAll()` and return `WipedEverything`.
  3. Otherwise, call `markRemoving(id)` as one commit. The entry disappears from the list, and every network whose active id names it moves to that network's next READY entry (for legacy, both networks, F7), before anything is erased.
  4. If `id == activeScope.walletId`, return `RemovedRestartNeeded` and erase nothing. The caller restarts; at the next launch `WalletHousekeeping` erases the scope before the database or `SyncService` opens (F8).
  5. Otherwise call `eraser.erase(scope)`.
  6. If verified, call `drop(id)` and return `Removed`.
  7. If `dbKeyHeldLegacyKey`, return `DatabaseKeyStillOnLegacy` and leave the entry REMOVING. Housekeeping retries at each launch and drops the entry only once `erase` verifies (F6). The eraser calls `releaseLegacyWrap()` itself, so the retry needs nothing else.
  8. Otherwise (an exception or an unverified erase), return `Incomplete` and leave the entry REMOVING.
- For `legacy`, both networks' legacy stores go, because there is one seed.

- [ ] **Step 1: Write the failing tests:**
  - `removing one wallet leaves every byte of the others`: remove B (not active) gives `Removed`; snapshots of legacy and C are equal; `list(false)` ids are `["legacy", C]`.
  - `removing the active wallet moves the active id, erases nothing, and the next launch erases it` (F8). With `activeScope = B` and B active in the registry, `remove(B, …) == RemovedRestartNeeded`; `registry.activeId(false) == "legacy"`; `find(B)!!.state == REMOVING`; every store and alias of B is still present (no erase event was recorded). Then `WalletHousekeeping(registry, eraser).run() == 1`, `anythingLeft(B) == false` and `find(B) == null`.
  - `a removal killed mid-erase finishes at the next launch`. Make the eraser throw after the record. `remove` gives `Incomplete`, and `registry.find(B)!!.state == REMOVING`. Then `WalletHousekeeping.run() == 1`, and `anythingLeft(B) == false`.
  - `removing the last wallet is the full wipe`. With only legacy, `remove("legacy", "Wallet 1", "Wallet 1")` is `WipedEverything` and `wipeAll` was called once.
  - `removing legacy keeps its key while the database key record depends on it`. With C active and `releaseLegacyDbWrap = { false }`: the result is `DatabaseKeyStillOnLegacy`; `"dgb_wallet_master" in vault.keys.keys`; the entry is REMOVING and not listed; `WalletHousekeeping.run() == 0` keeps it REMOVING (F6).
  - `removing legacy moves both networks' active id` (F7). Legacy active on both networks, C READY on mainnet, T READY on testnet, `activeScope = C`: after `remove("legacy", …)`, `activeId(false) == C` and `activeId(true) == T`.
  - `removing legacy keeps the custom node endpoint`: `dgb_reconcile.custom_rpc_endpoint` is unchanged.
  - `a wrong typed name removes nothing`: `remove(B, "Wallet", "Wallet 2") == NameMismatch`, with no writes.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.WalletRemovalTest"`. Expected FAIL: unresolved class.
- [ ] **Step 3: Implement** as specified, with the `AppModule` provider.
- [ ] **Step 4: Run** the same command plus `--tests "*.WalletHousekeepingTest"`. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, message `feat(wallets): remove one wallet; the active one is erased by the next launch; the last one is the full wipe`.

### Task 12: Onboarding in add mode (create and restore, no PIN setup)

**Files:**
- Create: `app/src/main/java/app/aroundtheblock/wallet/ui/onboarding/OnboardingRoutes.kt`
- Modify: `app/.../ui/onboarding/OnboardingScreen.kt:29-155`. In ADD mode it shows `wallet_add_title` and `wallet_add_pin_note`, labels its two buttons `wallet_add_create` / `wallet_add_restore`, keeps the word-count toggle, and shows `wallet_add_cap` (both buttons disabled) when `registry.canReserve(testnet)` is false.
- Create: `app/src/main/java/app/aroundtheblock/wallet/ui/onboarding/AddWalletCommitScreen.kt`
- Modify: `app/.../ui/onboarding/OnboardingViewModel.kt`:
  - inject `SavedStateHandle`, `WalletProvisioner`, `WalletSwitcher` and `WalletRegistry`;
  - add `val mode: OnboardingMode`;
  - `recoverWallet` at `:236-286`: add `check(mode == FIRST_RUN)`, since ADD mode never reaches it. Its `pinManager.clearPin()` (`:252`) runs only when `!registry.anyRecordExists()`. With legacy removed and only testnet wallets left, mainnet starts on onboarding while a PIN protects those wallets, and the clear would erase it (F31);
  - add `fun checkDuplicate(): WalletEntry?`;
  - add `fun commitAdd(seedKeyPrompt: (suspend () -> Unit)?, onResult: (AddResult) -> Unit)`, which uses `_mnemonic`, `_passphrase` and `_recoveryTimestamp` (or now, for a create), and zeroes and clears as `createWallet:198-229` does. It calls `provisioner.prepareSeedKey(testnet)` first and maps `null` to `AddResult.CapReached` (F24); otherwise it prompts for the seed key as `recoverWallet` does, then calls `provisioner.add(...)`.
- Modify: `app/.../ui/onboarding/PassphraseScreen.kt:120`. In ADD mode, navigate to `ADD_COMMIT_ROUTE`.
- Modify: `app/.../ui/onboarding/RecoveryDateScreen.kt:187-206`. In ADD mode, store the timestamp and navigate to `ADD_COMMIT_ROUTE`; Task 15 changes this arm to `QUICK_START_ROUTE`. The screen does not call `viewModel.recoverWallet` in ADD mode. Its `popUpTo("onboarding")` (`:203`) becomes `popUpTo(ONBOARDING_ROUTE)`.
- Modify: `app/.../ui/onboarding/MnemonicInputScreen.kt:220-232`. In ADD mode, call `checkDuplicate()` first. If it finds one, show `wallet_add_duplicate` with an `wallet_add_duplicate_switch` button that calls `WalletSwitcher.switchTo(existing.id)`. Otherwise navigate **straight to `recovery_date`**, not to `recovery_scan`: that screen is the existing opt-in api.digiscope.me scan, and its disclosure does not say it links wallets, so in ADD mode the quick start (Task 15) is the only server option (F9). FIRST_RUN keeps `navigate("recovery_scan")`.
- Modify: every bare `"onboarding"` route literal (F21). Navigations use `onboardingRoute(OnboardingMode.FIRST_RUN)`, back-stack lookups and `popUpTo` use `ONBOARDING_ROUTE`:
  - `app/.../ui/onboarding/PinSetupScreen.kt:244,252` (`popUpTo`);
  - `app/.../ui/onboarding/UnlockScreen.kt:166,178,293` (`navigate`);
  - `app/.../ui/settings/SecuritySettingsScreen.kt:109` (`navigate`);
  - `app/.../ui/navigation/AppNavigation.kt:165` (`navigate`);
  - `app/.../ui/navigation/LockGatePolicy.kt:143`, `StartDecision(onboardingRoute(OnboardingMode.FIRST_RUN), …)`.
- Modify: `app/.../ui/navigation/AppNavigation.kt`:
  - `composable(ONBOARDING_ROUTE, arguments = listOf(navArgument("mode") { defaultValue = "first_run" }))`;
  - every `getBackStackEntry("onboarding")` (`:260-335`) becomes `getBackStackEntry(ONBOARDING_ROUTE)`;
  - add `composable(ADD_COMMIT_ROUTE)`;
  - `fullScreenRoutes` (`:72`): replace `"onboarding"` with `ONBOARDING_ROUTE` and add `ADD_COMMIT_ROUTE` and `QUICK_START_ROUTE`, so the bottom bar stays hidden during an add (F21).
- `LockGatePolicy.PRE_WALLET_ROUTES` is **not** changed. `add_wallet_commit` is not in it today, so a lock mid-add already routes to unlock (F21).
- Modify: `app/src/test/.../ui/locale/OnboardingHardcodedStringTest.kt`. Add `AddWalletCommitScreen.kt` to `COVERED` (`OnboardingScreen.kt` is already there), and the new route literals to `ROUTES`.
- Strings: the `wallet_add_*` keys (see the table), in 13 locales.
- Test: `app/src/test/java/app/aroundtheblock/wallet/ui/onboarding/AddModeGateTest.kt`
- Test: `app/src/test/java/app/aroundtheblock/wallet/ui/navigation/LockGatePolicyTest.kt` (extend it; update any expectation of `StartDecision("onboarding", …)` to `onboardingRoute(FIRST_RUN)`).

**Interfaces:**
- Consumes: `WalletProvisioner` (`prepareSeedKey`, `duplicateOf`, `add`), `WalletSwitcher` (switches into an `Added` wallet because it compares with the running process, F3), `AddResult`, `WalletRegistry.canReserve` / `anyRecordExists`.
- Produces:
```kotlin
enum class OnboardingMode { FIRST_RUN, ADD; companion object { fun of(arg: String?): OnboardingMode } }  // "add" → ADD
const val ONBOARDING_ROUTE = "onboarding?mode={mode}"
fun onboardingRoute(mode: OnboardingMode): String   // "onboarding?mode=first_run" | "onboarding?mode=add"
const val ADD_COMMIT_ROUTE = "add_wallet_commit"
const val QUICK_START_ROUTE = "restore_quick_start"
```

- [ ] **Step 1: Write the failing tests.**
  - `AddModeGateTest` is a source gate over `KotlinSourceGate`, as `PinSetupCreateGateTest` is:
    - `add mode never reaches PIN setup`: in `PassphraseScreen.kt` and `RecoveryDateScreen.kt`, every `navigate("pin_setup"` is inside an `if (viewModel.mode == OnboardingMode.FIRST_RUN)` / `else` branch whose other arm navigates to `ADD_COMMIT_ROUTE` or `QUICK_START_ROUTE` (F20).
    - `add mode never reaches the recovery scan` (F9): in `MnemonicInputScreen.kt`, `navigate("recovery_scan"` is only inside the `FIRST_RUN` arm, and the ADD arm navigates to `"recovery_date"`.
    - `add mode never clears the PIN`: `OnboardingViewModel.commitAdd` contains no `pinManager.`.
    - `a restore clears the PIN only when no wallet exists` (F31): in `OnboardingViewModel.recoverWallet`, `pinManager.clearPin()` sits inside `if (!registry.anyRecordExists())`.
    - `add mode never creates through PIN setup`: `AddWalletCommitScreen.kt` contains no `pinSetupCreatesWallet`, `createWallet(` or `recoverWallet(`.
    - `no bare onboarding route` (F21): no file under `app/src/main/java` contains `navigate("onboarding"`, `popUpTo("onboarding"`, `getBackStackEntry("onboarding"` or `StartDecision("onboarding"`.
  - `LockGatePolicyTest.a lock during an add routes to unlock`: `shouldRouteToUnlock(Locked, hasPin=true, currentRoute="add_wallet_commit", walletStored=true) == true`. This is a **pin of today's behaviour**, green before and after this task, and its KDoc says so: it guards against a later edit adding the add routes to `PRE_WALLET_ROUTES` (F21).
- [ ] **Step 2: Run** `./gradlew :app:testMainnetPlayDebugUnitTest --tests "*.AddModeGateTest" --tests "*.LockGatePolicyTest"`. Expected FAIL: `AddModeGateTest` does not compile (no `OnboardingMode`). `LockGatePolicyTest.a lock during an add routes to unlock` passes already, as stated.
- [ ] **Step 3: Implement** as specified:
  - `OnboardingScreen` in ADD mode: as described under Files. The create and restore paths below it are the existing screens, sharing the `ONBOARDING_ROUTE` ViewModel.
  - `AddWalletCommitScreen` shows `wallet_add_saving`, then calls `commitAdd(seedKeyPromptFor(…))`. It maps `Added` to `WalletSwitcher.switchTo(entry.id)` (which restarts, F3), `Duplicate` to the duplicate message, and `CapReached`/`Failed` to `wallet_add_cap`/`wallet_add_failed`.
  - Replace the route literals as listed.
  - Add the strings to all 13 locales.
- [ ] **Step 4: Run** the same command plus `--tests "*.LocaleResourceParityTest" --tests "*.OnboardingHardcodedStringTest" --tests "*.PinSetupCreateGateTest"`. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths (code, tests, 13 strings files), message `feat(wallets): add a wallet through onboarding in add mode; no PIN setup, no PIN clear, no recovery scan (13 locales)`.

### Task 13: Switcher UI: chip, sheet, manage (rename, reorder, remove)

**Files:**
- Create: `app/src/main/java/app/aroundtheblock/wallet/ui/wallets/WalletsViewModel.kt`, `WalletChip.kt`, `WalletSwitcherSheet.kt`, `ManageWalletsScreen.kt`, `RemoveWalletDialog.kt`
- Modify: `app/.../ui/wallet/WalletScreen.kt:107-108`. Put `WalletChip` above the hero card as the first `item {}`.
- Modify: `app/.../ui/navigation/AppNavigation.kt`. Add `composable("manage_wallets")`; the sheet's Add navigates to `onboardingRoute(OnboardingMode.ADD)`.
- Modify: `OnboardingHardcodedStringTest.COVERED`. Add the five new files.
- Strings: the `wallet_switcher_*`, `wallet_chip_cd`, `wallet_manage_*`, `wallet_remove_*` and `wallet_default_name` keys, in 13 locales.
- Test: `app/src/test/java/app/aroundtheblock/wallet/ui/wallets/WalletsViewModelTest.kt`

**Interfaces:**
- Consumes: `WalletRegistry` (`list`, `canReserve`, `rename`, `reorder`), `WalletSwitcher` (Task 9), `WalletRemoval` (Task 11, provided in `AppModule` there), `SpendAuth.authorize(activity, title, subtitle)` (`SpendAuth.kt:151`), `AppRestart`, `FreshStartAfterWipe.afterWipe`.
- Produces:
```kotlin
data class WalletRow(val id: String, val shownName: String, val active: Boolean,
                     val lastBalanceSat: Long?, val lastDdCents: Long?, val alsoTestnet: Boolean)
data class LastKnown(val balanceSat: Long?, val ddCents: Long?)   // null fields when has_synced is false
@HiltViewModel class WalletsViewModel @Inject constructor(...) : ViewModel() {
    internal constructor(registry: WalletRegistry, switcher: WalletSwitcher, removal: WalletRemoval,
                         testnet: Boolean, lastKnown: (WalletScope) -> LastKnown, defaultName: (Int) -> String)
    val rows: StateFlow<List<WalletRow>>   // production lastKnown reads last_balance / last_dd_balance / has_synced from
                                           // WalletStores.prefsName("dgb_sync_data", scope)
    val canAdd: StateFlow<Boolean>         // registry.canReserve(testnet): READY + PENDING < MAX_PER_NETWORK (F31)
    fun switchTo(id: String)
    fun rename(id: String, name: String): Boolean
    fun move(id: String, delta: Int): Boolean   // -1 up, +1 down
    suspend fun remove(id: String, typedName: String): RemoveResult
}
internal fun shownName(entry: WalletEntry, defaultName: (Int) -> String): String   // entry.name ?: defaultName(entry.number)
```
- Removal UI order: the backup warning, then typed-name confirmation (the confirm button stays disabled until the text equals `shownName`), then `SpendAuth.authorize(..., subtitle = wallet_remove_auth_subtitle)`, then `remove`. The results map as follows: `Removed` → the row disappears; `RemovedRestartNeeded` → `AppRestart.restart` (the next launch erases the wallet, F8); `WipedEverything` → `FreshStartAfterWipe.afterWipe`; `DatabaseKeyStillOnLegacy` → `wallet_remove_db_key_kept`; `Incomplete` → `wallet_remove_failed`.

- [ ] **Step 1: Write the failing tests** in `WalletsViewModelTest`, through the internal constructor, over a mockk `WalletRegistry` and a `lastKnown` map (no app-side prefs harness, F26):
  - `rows show each wallet's own last balance`. With `lastKnown(legacy) = LastKnown(150000000, null)` and `lastKnown(B) = LastKnown(7, null)`: `rows` balances are `[150000000, 7]`.
  - `a wallet that has never synced shows no balance`: `lastKnown(B) = LastKnown(null, null)` gives `lastBalanceSat == null`.
  - `the production reader is per wallet and honours has_synced`. A unit test of the production `lastKnown` function over a mockk `Context` whose `getSharedPreferences` answers per name: `dgb_sync_data_<B>` with `last_balance=7` and no `has_synced` reads `LastKnown(null, null)`, and with `has_synced=true` reads `LastKnown(7, …)`; legacy's `dgb_sync_data` is never opened for B.
  - `default names follow the wallet number, not the order`. After reordering B before legacy, the shown names are `["Wallet 2", "Wallet 1"]`.
  - `the eighth wallet hides Add`: `canAdd == false` when `registry.canReserve(false)` is false, which counts a PENDING entry (F31).
  - `legacy is marked as also the testnet wallet`: `alsoTestnet == true` for legacy and false for B.
- [ ] **Step 2: Run** `./gradlew :app:testMainnetPlayDebugUnitTest --tests "*.WalletsViewModelTest"`. Expected FAIL: unresolved class.
- [ ] **Step 3: Implement** the ViewModel, then the composables within the existing theme. The chip shows `shownName` and opens the sheet. The sheet lists rows with the DGB balance and, when non-zero, the DigiDollar balance, then Add and Manage. Add the strings to all 13 locales.
- [ ] **Step 4: Run** the same command plus `--tests "*.LocaleResourceParityTest" --tests "*.OnboardingHardcodedStringTest"`. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, message `feat(wallets): wallet chip, switcher sheet and manage screen (13 locales)`.

### Task 14: Show the wallet's name in the Hub, the recovery phrase screen and the sync notification

**Files:**
- Modify: `app/.../ui/hub/ProfileView.kt:47-140` (`HubAccountStrip`). Under the handle, show `hub_signed_in_wallet` with the active wallet's shown name. Extract the four hardcoded literals the coverage gate would flag (F22): `"Handle"` (`:258`) → `hub_handle_field_label`, `"e.g. DigiByte_Fan"` (`:259`) → `hub_handle_field_hint`, `"Check"` (`:367`) → `hub_handle_check`, `"Register"` (`:384`) → `hub_handle_register`.
- Modify: `app/.../ui/settings/SeedViewScreen.kt:45`. Add a header `seedview_wallet_name` with the active wallet's shown name. Extract `"Recovery Phrase"` (`:105`) → `seedview_title` and `"Done — Hide Phrase"` (`:297`) → `seedview_done_hide` (F22). Any further literal the gate reports in these two files is extracted the same way, in all 13 locales.
- Modify: `app/.../service/SyncService.kt:4101-4128` (`buildNotification`). The content title is `notificationTitle(...)`, and the content text is `notificationText(...)`, two pure functions in the same file. The text moves from the hardcoded English to `sync_notif_synced` / `sync_notif_connecting` / `sync_notif_syncing` / `sync_notif_via_tor` (F22).
- Modify: `OnboardingHardcodedStringTest.COVERED`. Add `ui/hub/ProfileView.kt` and `ui/settings/SeedViewScreen.kt`. `SyncService.kt` is not added (it is a service, not a screen); its notification text is checked by `SyncNotificationTextTest` instead.
- Strings: `hub_signed_in_wallet`, `seedview_wallet_name`, `sync_notif_*`, and the six extracted keys `hub_handle_field_label`, `hub_handle_field_hint`, `hub_handle_check`, `hub_handle_register`, `seedview_title`, `seedview_done_hide`, in 13 locales. `sync_notif_via_tor` is double-quoted in every file (`" · via Tor"`, translated after the space), so aapt keeps the leading space.
- Test: `app/src/test/java/app/aroundtheblock/wallet/service/SyncNotificationTextTest.kt`

**Interfaces:**
- Consumes: `WalletRegistry`, `shownName` (Task 13), `ActiveWallet.scope`.
- Produces, pure functions in `SyncService.kt`:
```kotlin
internal fun notificationTitle(appName: String, walletName: String, walletCount: Int, format: (String, String) -> String): String
internal interface NotificationStrings {
    fun synced(peers: Int): String          // plurals sync_notif_synced
    fun connecting(): String                // sync_notif_connecting
    fun syncing(pct: Int, peers: Int): String  // plurals sync_notif_syncing
    fun viaTor(): String                    // sync_notif_via_tor, leading space included
}
internal fun notificationText(pct: Int, peerCount: Int, viaTor: Boolean, strings: NotificationStrings): String
    // pct >= 100 → synced; peerCount == 0 → connecting; else syncing; + viaTor() when viaTor
```

- [ ] **Step 1: Write the failing tests:**
  - `SyncNotificationTextTest.one wallet shows the app name only`: `notificationTitle("HoldFast", "Wallet 1", 1, ::fmt) == "HoldFast"`.
  - `SyncNotificationTextTest.several wallets name the active one`: `notificationTitle("HoldFast", "Savings", 2, ::fmt) == "HoldFast · Savings"`.
  - `SyncNotificationTextTest.the text follows the sync state`, with an English fake `NotificationStrings`: `notificationText(100, 3, false, en) == en.synced(3)`; `notificationText(0, 0, false, en) == en.connecting()`; `notificationText(42, 2, true, en) == en.syncing(42, 2) + en.viaTor()`.
  - `SyncNotificationTextTest.the Tor suffix keeps its leading space in every locale`. It parses `sync_notif_via_tor` from all 13 `values*/strings_wallet.xml` and asserts each raw value starts with `"` followed by a space.
  - `SyncNotificationTextTest.no notification literal is left`: `SyncService.kt` contains none of `"Synced — `, `"Connecting to peers…` or `" · via Tor"`.
- [ ] **Step 2: Run** `./gradlew :app:testMainnetPlayDebugUnitTest --tests "*.SyncNotificationTextTest"`. Expected FAIL: unresolved functions.
- [ ] **Step 3: Implement** as specified, with the strings in all 13 locales.
- [ ] **Step 4: Run** the same command plus `--tests "*.LocaleResourceParityTest" --tests "*.OnboardingHardcodedStringTest"`. Expected PASS; the coverage gate passes on `ProfileView.kt` and `SeedViewScreen.kt` only because their literals were extracted.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, message `feat(wallets): Hub, recovery phrase and sync notification name the wallet (13 locales)`.

### Task 15: Quick start for a restored wallet (display-only, opt-in)

**Files:**
- Create: `core/src/main/java/app/aroundtheblock/wallet/core/wallets/QuickStartStore.kt`
- Create: `app/src/main/java/app/aroundtheblock/wallet/ui/onboarding/QuickStartScreen.kt`
- Modify: `core/.../reconcile/DgbNodeClient.kt:119-121`. Add `fun customEndpoint(): String?`, the stored `custom_rpc_endpoint` trimmed, or null. Add a public factory `companion object { fun digiScopeOnly(context: Context, client: OkHttpClient): DgbNodeClient }` whose `endpoint()` is always `DEFAULT_BASE_URL` (pinned client), whatever `custom_rpc_endpoint` holds. It is public because the app module calls it, and an `internal` core parameter is invisible there (F19).
- Modify: `app/.../ui/onboarding/OnboardingViewModel.kt`. Factor the body of `runRecoveryScan` (`:140-…`: generation guard, passphrase copy-and-zero, state updates) into one private routine `runScan(service: RecoveryScanService, onDone: (RecoveryScanService.State) -> Unit)`. `runRecoveryScan()` calls it with today's service and `_scanResults` as destination; `fun runQuickStart(source: QuickStartSource)` calls it with `RecoveryScanService(utxoSource, profiles = DerivationProfile.BUILT_INS.filter { it.isNative })` over `DgbNodeClient(…)` for `OWN_NODE` or `DgbNodeClient.digiScopeOnly(…)` for `DIGISCOPE`, and keeps `Done.nativeResult?.totalSat` and `Done.digiDollar` in memory (F25). There is one copy of the passphrase zeroing. `commitAdd` writes the kept result to the new scope's `QuickStartStore` after `Added` and before the switch.
- Modify: `app/.../ui/onboarding/RecoveryDateScreen.kt`. In ADD mode, navigate to `QUICK_START_ROUTE` (replacing Task 12's interim `ADD_COMMIT_ROUTE`). `AddModeGateTest` already accepts either (F20).
- Modify: `app/.../ui/wallet/WalletScreen.kt` and `WalletViewModel.kt`. Add `val unverified: StateFlow<QuickStart?>`. While it is non-null, render `quickstart_unverified` / `quickstart_unverified_sub` **below** the balance, never inside it. It clears once `dgb_sync_data<suffix>.has_synced` is true (`QuickStartStore.clearIfSynced`).
- Modify: `app/.../ui/navigation/AppNavigation.kt`. Add `composable(QUICK_START_ROUTE)`.
- Modify: `OnboardingHardcodedStringTest.COVERED`. Add `QuickStartScreen.kt`.
- Strings: `quickstart_*`, in 13 locales.
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/QuickStartStoreTest.kt`
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/reconcile/DgbNodeClientDigiScopeOnlyTest.kt`
- Test: `app/src/test/java/app/aroundtheblock/wallet/ui/onboarding/QuickStartGateTest.kt`
- Test (run, unchanged): `AddModeGateTest` (Task 12), which accepts `QUICK_START_ROUTE` in RecoveryDateScreen's ADD arm (F20).

**Interfaces:**
- Consumes: `RecoveryScanService` (`core/.../recovery/RecoveryScanService.kt:25-30,370`), `DgbNodeClient`, `WalletStores.prefsName("dgb_quickstart", scope)`, Task 12's `commitAdd` and `QUICK_START_ROUTE`.
- Produces:
```kotlin
enum class QuickStartSource { OWN_NODE, DIGISCOPE }
data class QuickStart(val unverifiedSat: Long, val unverifiedDdCents: Long?, val sourceLabel: String, val atSecs: Long)
object QuickStartStore {
    fun write(context: Context, scope: WalletScope, q: QuickStart): Boolean   // commit()
    fun read(context: Context, scope: WalletScope): QuickStart?
    fun clearIfSynced(context: Context, scope: WalletScope): Boolean          // true when it cleared
}
// in DgbNodeClient's companion, public (F19):
//     fun digiScopeOnly(context: Context, client: OkHttpClient): DgbNodeClient
/** Which choices the screen offers. Own node only when one is set; DigiScope always, as an explicit, disclosed opt-in. */
internal fun quickStartChoices(customEndpoint: String?): List<QuickStartSource>   // set → [OWN_NODE, DIGISCOPE]; null → [DIGISCOPE]
```
- **Nothing unproven reaches the send path or the balance.** `QuickStartStore` is read only by `WalletViewModel.unverified`. No balance, UTXO or send code reads `dgb_quickstart`.
- **The screen (F10, as modified by the owner).** Skipping is the default action and one tap: `quickstart_skip` is the first, primary button, and it navigates to `ADD_COMMIT_ROUTE` with nothing sent.
  - With an endpoint set, the screen offers `OWN_NODE` (showing the host, `DgbNodeClient.redactUrl`-style, without credentials, and `quickstart_own_node_note`) and `DIGISCOPE`.
  - With no endpoint set, the screen still opens, and offers `DIGISCOPE` only. It is an explicit opt-in: the screen's own text (`quickstart_digiscope_disclosure`) says the wallet's addresses are sent to digiscope.me and that this can link the wallet to the user's other wallets.
  - Picking `DIGISCOPE` shows `quickstart_digiscope_disclosure` again as its own confirm step; only that confirm sends anything.
- **Endpoint routing:** `DgbNodeClient.endpoint()` already returns the custom endpoint when it is set. `DIGISCOPE` always runs on `DgbNodeClient.digiScopeOnly(…)`, so a custom endpoint never receives a scan the user addressed to DigiScope, and DigiScope never receives one addressed to the own node.

- [ ] **Step 1: Write the failing tests:**
  - `QuickStartStoreTest.a quick start is kept until the wallet has synced`. Write it for B; `read(B)` is non-null; set B `has_synced=true`; `clearIfSynced(B) == true` and `read(B) == null`.
  - `QuickStartStoreTest.a quick start is the wallet's own`: after `write(B)`, `read(legacy) == null`.
  - `DgbNodeClientDigiScopeOnlyTest.digiscope only ignores the custom endpoint` (F19): with `custom_rpc_endpoint = "https://node.example"`, `DgbNodeClient.digiScopeOnly(ctx, client).endpoint() == DEFAULT_BASE_URL`, and `DgbNodeClient(ctx, …).customEndpoint() == "https://node.example"`.
  - `QuickStartGateTest.no money path reads the quick start`. A source scan of `core/src/main/java` and `app/src/main/java` finds `QuickStartStore` and `"dgb_quickstart"` only in `QuickStartStore.kt`, `WalletStores.kt`, `WalletViewModel.kt`, `OnboardingViewModel.kt` and `WalletStoreEraser.kt`.
  - `QuickStartGateTest.own node is offered only when set` (F10): `quickStartChoices("https://x") == listOf(OWN_NODE, DIGISCOPE)` and `quickStartChoices(null) == listOf(DIGISCOPE)`.
  - `QuickStartGateTest.nothing is sent before a choice`. In `QuickStartScreen.kt`, `runQuickStart(` appears only inside the `onClick` of the own-node button and of the DigiScope disclosure's confirm button.
  - `QuickStartGateTest.skipping is the default and sends nothing`. In `QuickStartScreen.kt`, the first button declared is the `quickstart_skip` one, and its `onClick` contains no `runQuickStart(`.
  - `QuickStartGateTest.one scan routine` (F25). In `OnboardingViewModel.kt`, `.scan(` appears exactly once, inside `runScan`, and `passBytes?.fill(0)` exactly once.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.QuickStartStoreTest" --tests "*.DgbNodeClientDigiScopeOnlyTest"` and `./gradlew :app:testMainnetPlayDebugUnitTest --tests "*.QuickStartGateTest"`. Expected FAIL: unresolved classes.
- [ ] **Step 3: Implement** as specified, with the strings in all 13 locales.
- [ ] **Step 4: Run** both commands plus `--tests "*.AddModeGateTest" --tests "*.LocaleResourceParityTest" --tests "*.OnboardingHardcodedStringTest" --tests "*.NetworkLeakTest"`. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, message `feat(wallets): opt-in quick start for a restored wallet from the user's node or, disclosed, DigiScope; display-only until sync proves it (13 locales)`.

### Task 16: Spec and documentation true-up

**Files:**
- Modify: `CLAUDE.md`, under "SharedPreferences Keys". Add `dgb_wallets`, the per-wallet suffix rule (`_<id>` before the network part; legacy unchanged) and `dgb_quickstart`. Under "Seed Security", add one line: per-wallet aliases `dgb_wallet_master_<id>`, legacy unsuffixed.
- Modify: `core/.../NetworkState.kt` KDoc. It names the stores that are shared and points at `WalletStores`.
- Modify: `docs/superpowers/specs/2026-11-16-multi-wallet-design.md`. Add a step-1 disposition banner recording the decisions this plan made where the spec was silent or the code differed (see "Ambiguities" below), and the pre-flight rulings F1–F32 by number with a pointer to `.superpowers/sdd/2026-11-16-multi-wallet-step1/preflight.md` (F10 as modified by the owner). The spec text already carries the three rulings that changed a decided point (F10's quick-start wording, F8's removal of the active wallet, ChainTip per wallet in step 1); the banner does not restate them.
- Test: none. This task only changes documentation.

- [ ] **Step 1: Make the edits.**
- [ ] **Step 2: Run the full suites.** Expected exit 0.
- [ ] **Step 3: Commit** `CLAUDE.md`, `NetworkState.kt` and the spec, message `docs(wallets): step 1 store naming and decisions recorded`.

### Task 17: Device and emulator verification

Never use the Galaxy S25 Ultra. Phrases come only from `~/device-backups/seeds/`. Read them on the host when typing a restore. Never copy a phrase into this plan, the repo, a commit, a log excerpt or a report. `adb input tap` is ignored by the Compose UI, so use `adb shell input swipe X Y X Y 120`.

**Files:**
- Create: `test-results/multi-wallet-step1/<date>-<device>.md`. Record per check: pass or fail, wallet names, "match" or "mismatch", transaction and asset **counts** only, and screenshots of non-secret screens that show no balance or address. **No balance figure, txid or address goes into this public repo**: balances are on-chain fingerprints of the owner's wallets (F32). Any figures are recorded in the private repo (`~/dgb-wallet-private/`), never here.

- [ ] **Step 1: Emulator smoke test, API 28 and API 35** (`dgb-test-api28`, `dgb-test-api35`):
  - build `./gradlew :app:assembleMainnetSideloadDebug`;
  - create a wallet on a fresh install;
  - add a second by create and a third by restore;
  - switch between them;
  - remove the third;
  - run `./scripts/pre-publish-test.sh 28 --skip-build` and `./scripts/pre-publish-test.sh 35 --skip-build`. Both must pass.
- [ ] **Step 2: Upgrade in place, then legacy check (emulator, then the mule).**
  - The debug build uses applicationId `app.aroundtheblock.wallet.debug` (`app/build.gradle.kts:140`), so it upgrades only a debug 4.1.4 install. On the device chosen for step 3 (the Note 8 SM-N950U, API 28, or the Moto G 5G), check with `adb shell pm list packages | grep aroundtheblock`.
  - If a `.debug` 4.1.4 install with a wallet exists, that wallet is `legacy`. Otherwise build v4.1.4 debug from a worktree at tag `v4.1.4`, install it, restore one wallet from `~/device-backups/seeds/`, and let it reach Synced.
  - Record its balance (private repo only, F32), its transaction count, whether a Hub session exists (not the handle) and `adb shell run-as app.aroundtheblock.wallet.debug ls shared_prefs files databases` (names only).
  - Install the branch debug build with `adb install -r`, without uninstalling.
  - The check passes when the chip shows "Wallet 1", the balance and transaction count are identical, there is no re-sync from birth (logcat: no `CF-SCAN` floor reset, `has_synced` still true), and the `ls` listing has the same names plus at most `dgb_wallets.xml`.
- [ ] **Step 3: Restore every test wallet onto one device, each as its own wallet** (same device, after step 2):
  - for each phrase file in `~/device-backups/seeds/`, use Add wallet → Restore with the birth date recorded beside it (or "I don't remember");
  - name each wallet after its file's purpose;
  - restoring a phrase that is already present must show `wallet_add_duplicate` naming that wallet;
  - for each wallet, after it reaches Synced, its balance (DGB and DigiDollar), transaction count and asset count must match what that wallet shows on its original emulator or device. Record "match" or "mismatch" and the counts in the results file; the balance figures, if kept, go to the private repo only (F32).
- [ ] **Step 4: Separation.** Switch through every wallet twice. Each time, check that the activity list, assets and DigiDollar rows are that wallet's own. In logcat, the sync of a wallet switched back to resumes near its saved tip and does not start from its birth.
- [ ] **Step 5: Cap.** If fewer than 8 wallets exist on mainnet, fill up to 8 with Add wallet → Create. The sheet then hides Add, and the add route shows `wallet_add_cap`. Remove one; Add comes back.
- [ ] **Step 6: Hub and Digi-ID identity differ per wallet.**
  - Sign in to the Hub in two wallets. Each Chat tab shows its own handle and `hub_signed_in_wallet` with its own wallet name. Switching shows the other account with no sign-in prompt.
  - Approve a Digi-ID login on the same test site from two wallets. The site sees two different addresses.
  - Open DigiStamp signed in from wallet A, switch to B, and open DigiStamp again: it is signed out.
- [ ] **Step 7: Removal.** Remove a non-active wallet: the others' balances and histories are unchanged after a restart. Remove the active wallet: the app restarts into the next wallet, and after that restart `adb shell run-as … ls shared_prefs files databases` shows none of the removed wallet's `_<id>` names (housekeeping erased them before the database opened, F8). Restore the removed wallet's phrase again and check that it comes back with its history.
- [ ] **Step 8: PIN-failure wipe on an emulator only, never on the mule's real wallets.** On `dgb-test-api33` with three wallets and wipe-after-N on: the Security screen says every wallet is erased; N wrong PINs return the app to onboarding, and `adb shell run-as … ls shared_prefs` shows no `dgb_wallet_seed*`.
- [ ] **Step 9: Write the results file and commit it.** `git add test-results/multi-wallet-step1/<file>.md`, message `test(wallets): step 1 device verification (<device>, API <n>)`. Run the full suites before the commit, as for every commit.

---

## Self-review notes (resolved inline)

- **Coverage against the spec's Step 1:**
  - registry: Task 2;
  - suffix: Tasks 1 and 3;
  - un-namespaced stores: Task 4;
  - seed record and aliases: Task 5;
  - chain data per wallet, with `clearSyncData` only on the new wallet's scope: Tasks 3 and 5. An add never calls `clearSyncData` at all, because the new scope is empty by construction;
  - switching: Task 9;
  - add, create and restore with no PIN setup and no `pinSetupCreatesWallet` or fingerprint clear: Tasks 8 and 12;
  - restore back-scan from birth: Task 8 via the record creation time;
  - quick start: Task 15;
  - remove: Tasks 11 and 13;
  - PIN-failure wipe and Security text: Task 10;
  - switcher UI and the 8 cap: Tasks 2 and 13;
  - notifications, View recovery phrase and Hub: Task 14;
  - per-wallet Hub and Digi-ID: Tasks 4, 5 and 9;
  - upgrade test: Task 6;
  - removal test: Tasks 7 and 11;
  - locale gates: every UI task.
- **Types:** every type a task consumes is produced by an earlier task:
  - `WalletScope` and `StoreScope`: Task 1;
  - `WalletEntry`, `WalletOrigin` and `EntryState`: Task 2;
  - `WalletManagerFactory` and the `restoreFromDisk` seams: Task 5;
  - `EraseReport` and `AliasOutcome`: Task 7;
  - `AddResult`: Task 8;
  - `RemoveResult`: Task 11;
  - `OnboardingMode`: Task 12;
  - `WalletRow`, `LastKnown` and `shownName`: Task 13;
  - `NotificationStrings`: Task 14;
  - `QuickStart` and `QuickStartSource`: Task 15.
- **Order:** every task leaves the app working with legacy-only behaviour until Task 8. Task 6 runs before any feature that can create a second wallet.

### Ambiguities between the spec and the code (decided here)

1. The spec lists "chain data: filter headers, block checkpoints, ChainTip" as shared, then says chain data is per wallet in step 1. The plan follows the later section. `ChainTipStore` lives in `dgb_sync_data` (`ChainTipStore.kt:32,39`), so it is per wallet in step 1 by construction.
2. `custom_rpc_endpoint` lives in `dgb_reconcile<net>` (`DgbNodeClient.kt:45-46,117`). That is the same file as the reconcile bookkeeping the spec makes per wallet (`PostUpgradeReconciler.kt:34`, `RemainderProof.kt:51`). The plan keeps `DgbNodeClient` on the network-only name, and a legacy erase keeps that one key.
3. The legacy seed record serves both networks (`WalletDataEraser.kt:176-180`; `dgb_wallet_seed` is never network-suffixed). So the spec's per-entry `network` cannot describe legacy. The plan gives legacy `testnet = null`, and removing legacy removes it on both networks.
4. The spec says "notifications for incoming payments name the wallet", but no incoming-payment notification exists. The only builder is the sync foreground notification (`SyncService.kt:4101-4128`), and its text is hardcoded English. The plan names the wallet there and localises it.
5. Legacy's `cf_birth_height`, `dgb_watched_addrs`, `dgb_outgoing_tx`, `dgb_asset_*`, `dgb_diag` and the Hub stores are not network-suffixed today (`WalletManager.kt:189,310`). That is why `StoreScope.WALLET` exists.
6. The DB password record may still be wrapped under the legacy seed key (`DatabaseKeyProvisioner.kt:247,296-326`). Removing legacy while other wallets remain must first call `releaseLegacyWrap()`.
7. The spec does not say what Settings → Wipe Wallet does with N wallets. The plan wipes every wallet, keeping one wipe routine for every entry point (`WipeCallSiteGateTest`).
8. "Removing the last wallet" is read as the last wallet on the device, across both networks.
9. The DigiStamp session is app-wide WebView state (`IdentitySessionEraser.kt`). The spec does not mention it. The plan ends it on every switch.
10. The spec only names the existing wallet as `legacy`. The plan also gives `legacy` to the first wallet of a fresh install (and after a wipe), so first-run onboarding is unchanged.
11. `OnboardingViewModel.recoverWallet` calls `pinManager.clearPin()` (`OnboardingViewModel.kt:252`). In add mode that would erase the app PIN, so add mode never reaches it.
12. The spec does not say who owns `dgb_diag` and `dgb_log_tag`. The plan makes `dgb_diag` per wallet (it records one wallet's scan resets) and keeps `dgb_log_tag` per install.
13. The quick start is offered only in add mode, where the linking disclosure is meaningful. First-run restore is unchanged.
