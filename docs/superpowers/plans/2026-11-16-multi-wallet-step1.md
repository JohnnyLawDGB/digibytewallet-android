# Multi-wallet Step 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let one install hold up to 8 named wallets per network, each with its own recovery phrase, stores, Keystore aliases, Hub session and Digi-ID identity, switched by restarting the process, while the wallet that exists today keeps every file, key and alias name it has now.

**Architecture:** Every store name goes through one function, `storageSuffix(scope, storeScope)`. Its `WalletScope` is the active wallet, resolved once per process from a new registry (`dgb_wallets`). For the reserved id `legacy` the function returns exactly today's suffix. The process-wide singletons (Room DB, `WalletManager`, `KeyStoreManager`, `HubTokenStore`) are bound to that scope at process start. Switching therefore means persisting a new active id and restarting, as the network toggle already does. Adding a wallet writes only the new wallet's seed record under a fresh id, so it never touches the native wallet or another wallet's stores. Removing a wallet erases one scope's stores. The PIN-failure wipe erases every scope.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room/SQLCipher, SharedPreferences, AndroidKeyStore, org.json (real implementation in core unit tests), JUnit4 + MockK, the existing pure-JVM harnesses `wipeTestContext` / `InMemoryPrefsFactory` (`core/src/test/.../WipeTestSupport.kt`) and `FakeVault` / `keyStoreManagerOver` (`KeyTestSupport.kt`).

**Spec:** `docs/superpowers/specs/2026-11-16-multi-wallet-design.md` (Step 1 only. Step 2 is the shared sync engine and is not in this plan.)

## Global Constraints

- **The `legacy` wallet keeps every name it has in 4.1.4.** That covers prefs files, prefs keys, files, the DB file, and the Keystore aliases `dgb_wallet_master` / `dgb_wallet_master_v2`. Nothing is renamed and nothing is migrated. `LegacyStoreNameParityTest` (Task 1) pins every name.
- **A new wallet's id matches `^[0-9a-f]{16}$`** (from `SecureRandom`). `legacy` is reserved. No other id is ever accepted from disk.
- **At most 8 wallets per network** (`WalletRegistry.MAX_PER_NETWORK = 8`). The count includes `legacy` and entries still being added.
- **Every new or changed user-facing string ships in all 13 locales of `AppLocale.SUPPORTED` in the same commit.** The locales are en, hi, zh, ja, pt-BR (`values-b+pt+BR`), es, id, vi, tr, ru, de, fr and fil (`values-b+fil`). Keys go in `values*/strings_wallet.xml`. Every new screen file is added to `OnboardingHardcodedStringTest.COVERED`.
- **Seed handling is unchanged.** A mnemonic on the load, restore and sign path is a `ByteArray`, zeroed with `fill(0)` in `finally`. A passphrase `ByteArray` is owned and zeroed by its caller. `g_seed` is reached only through the accessor API. No new code logs a phrase, a seed, a fingerprint or an address.
- **Nothing deletes a database or a key outside a user's wipe.** The one new exception is the user's removal of one wallet, which deletes only that wallet's scope.
- **No address set leaves the device by default.** The quick start (Task 15) sends addresses only after the user picks it on a screen that names the server. For digiscope.me that screen also says the server can link this wallet to the user's other wallets.
- **One copy of each thing.** `storageSuffix` is the only place a store suffix is built, and `networkSuffix` is deleted. `WalletStores` is the only list of per-wallet stores, and both name resolution and the eraser read it. `AppRestart` is the only restart path.
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
   - `removing the active wallet makes another wallet active before anything is erased`;
   - `removing one wallet leaves every byte of the others`.
5. **The PIN-failure wipe with N wallets.** The wipe erases legacy and leaves wallet 2's phrase readable. Or the read-back reports "seed gone" while `dgb_wallet_master_<id>_v2` still exists. Covered by Task 10, `WalletWipeEveryWalletTest`: `a wipe erases every wallet's record, stores and aliases` and `a surviving alias of any wallet means the seed is not gone`.
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
| `WalletStoreEraser.kt` | Erases one wallet's scope, with read-back |
| `WalletProvisioner.kt` | Add a wallet: reserve, duplicate check, write the record only, complete |
| `WalletHousekeeping.kt` | At launch, finishes or erases incomplete entries (pending add, interrupted removal) |
| `WalletRemoval.kt` | Removes one wallet, or hands over to the full wipe for the last one |
| `QuickStartStore.kt` | Per-wallet display-only "not yet verified" balance |

**Created in `app/src/main/java/app/aroundtheblock/wallet/`:**

| File | Responsibility |
|---|---|
| `AppRestart.kt` | The one "relaunch and end this process" path. It replaces `SettingsViewModel.restartApp` |
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
- `core/.../core/WalletDataEraser.kt`: the full wipe also erases every non-legacy scope; `KeystoreAliases.aliases()`.
- `core/.../core/security/KeyStoreManager.kt`: no API change. Production passes `WalletStores.keyAlias(scope)`.
- `core/.../core/digiscope/HubTokenStore.kt`, `DigiScopeClient.kt`: the token store is per scope.
- `core/.../core/sync/{SavedBlockStore,FilterHeaderStore,CfScanLedgerStore,SavedTransactionsStore,CfAbandonmentStore,ChainTipStore,HistoryRebuildOnUpgrade}.kt`, `core/.../core/reconcile/{PostUpgradeReconciler,DgbNodeClient}.kt`, `core/.../core/asset/{RemainderProof,AssetHistoryBackfill}.kt`, `core/.../core/{OutgoingTxStore,WalletTxPersister}.kt`, `core/.../core/settings/{CustomNode,DisplayCurrencyPrefs}.kt`: switch to scoped names.
- `app/.../di/AppModule.kt`: scoped DB file, `WalletManager`, `KeyStoreManager` and `WalletRegistry` providers.
- `app/.../service/{SyncService,SyncWorker}.kt`, `app/.../StaleDataWiper.kt`, `app/.../ui/wallet/{WalletViewModel,DisplayCurrencyStore,WalletScreen}.kt`: scoped names; the chip.
- `app/.../DigiByteApp.kt`: runs housekeeping at process start.
- `app/.../ui/settings/{SettingsViewModel,SecuritySettingsScreen,SeedViewScreen}.kt`, `app/.../ui/hub/ProfileView.kt`: restart via `AppRestart`; wipe text; wallet name.
- `app/.../ui/onboarding/{OnboardingViewModel,OnboardingScreen,PassphraseScreen,RecoveryDateScreen,MnemonicInputScreen}.kt`, `app/.../ui/navigation/{AppNavigation,LockGatePolicy}.kt`: add mode.
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
| `hub_signed_in_wallet` | `Signed in with %1$s` | 14 |
| `sync_notif_title_wallet` | `%1$s · %2$s` | 14 |
| `sync_notif_synced` | plurals `one`: `Synced, %1$d peer connected`; `other`: `Synced, %1$d peers connected` | 14 |
| `sync_notif_connecting` | `Connecting to peers…` | 14 |
| `sync_notif_syncing` | plurals `one`: `Syncing %1$d%%, %2$d peer`; `other`: `Syncing %1$d%%, %2$d peers` | 14 |
| `sync_notif_via_tor` | ` · via Tor` | 14 |

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

    fun prefsName(base: String, scope: WalletScope): String  // throws IllegalArgumentException for a base in no list
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
  - `LegacyStoreNameParityTest.the table is the 4.1.4 store set`. It asserts that the union of the lists equals the store set named in `git show v4.1.4:core/src/main/java/app/aroundtheblock/wallet/core/WalletDataEraser.kt`, plus `dgb_quickstart`, so a store missing from the list fails here.
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
                                                            // and that record's wallet_network key (null when absent)
    private val random: java.security.SecureRandom = java.security.SecureRandom(),
) {
    fun list(testnet: Boolean): List<WalletEntry>           // READY only, by order; legacy synthesised when its record exists
    fun incomplete(): List<WalletEntry>                     // PENDING and REMOVING, every network
    fun find(id: String): WalletEntry?
    fun activeId(testnet: Boolean): String                  // see resolution rule below
    fun setActive(testnet: Boolean, id: String): Boolean    // one commit()
    fun reserve(testnet: Boolean, origin: WalletOrigin, nowSecs: Long): WalletEntry?   // PENDING; null at the cap
    fun complete(id: String, makeActive: Boolean): Boolean  // PENDING → READY (+ active) in one commit()
    fun markRemoving(id: String, newActive: String?): Boolean  // REMOVING (+ active moved) in one commit()
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
    fun scope(context: android.content.Context): WalletScope        // resolved once per process, then cached
    fun registry(context: android.content.Context): WalletRegistry  // over "dgb_wallets" and the context's prefs dir
    internal fun resetForTest()
}
```
- **Storage format.** The registry is one JSON string under key `registry`, so every change is a single atomic `commit()`. The shape is `{"v":1,"entries":[…],"legacy":{"name":null,"order":0},"active":{"mainnet":"…","testnet":"…"}}`.
- **Resolution rule for `activeId(testnet)`.** Use the stored id if it is READY on that network and `recordExists`. Otherwise use the first entry in `list(testnet)` whose record exists. Otherwise `legacy`.
- **Upgrade.** With the file absent, `list` returns `[legacy]` when legacy's record exists and `[]` when it does not. Reading never writes.

- [ ] **Step 1: Write the failing tests** in `WalletRegistryTest`, over `InMemoryPrefs`:
  - `an install from 4.1.4 lists exactly the legacy wallet and writes nothing`: `list(false).map { it.id } == listOf("legacy")`, `list(false)[0].number == 1`, `name == null`, `prefs.all.isEmpty()`.
  - `no record and no registry lists nothing`: `list(false) == emptyList<WalletEntry>()`.
  - `a reserved wallet is not listed until complete`: `reserve(...)` is non-null and its id matches `[0-9a-f]{16}`; `list` has no such id; `incomplete()` contains it; after `complete(id, makeActive = true)`, `list` contains it with `number == 2` and `activeId(false) == id`.
  - `the eighth wallet is the last`: legacy plus 7 reserved and completed, then `reserve(false, …) == null`; `reserve(true, …)` is not null, because the cap is per network.
  - `a pending entry counts toward the cap`: legacy plus 6 complete plus 1 pending, then `reserve == null`.
  - `an active id whose record is gone resolves to the first wallet that has one`: active = B, `recordExists(B) == false`, so `activeId(false) == "legacy"`.
  - `an unreadable registry is rebuilt from the seed records on disk`: seed `registry = "{not json"` with `recordsOnDisk = listOf("0123456789abcdef" to false, "fedcba9876543210" to null)`. Then `list(false)` returns ids `["legacy","0123456789abcdef","fedcba9876543210"]`. A record with no `wallet_network` is placed on mainnet, the only network a user can reach without the developer toggle. Each rebuilt entry has `name == null` and `origin == RESTORED`, and `activeId(false) == "legacy"`.
  - `names are trimmed, at most 32 chars and unique ignoring case`: `rename(B, " Savings ")` is true and the name reads back as `"Savings"`; `rename(C, "savings") == false`; `rename(C, "")` is false; `rename(C, "x".repeat(33))` is false.
  - `reorder keeps every id`: `reorder(false, [B, legacy])` is true and `list` returns `[B, legacy]`; `reorder(false, [B])` is false.
  - `a refused commit changes nothing`: with `refuseWritesTo("dgb_wallets")`, `setActive` returns false and `activeId` is unchanged.
- [ ] **Step 2: Write the failing tests** in `ActiveWalletTest`, using `wipeTestContext`:
  - `with no registry the scope is legacy on the selected network`: `dgb_settings.dgb_network_testnet = true` gives `ActiveWallet.scope(ctx) == WalletScope.legacy(true)`.
  - `the scope is resolved once per process`: resolve, then call `setActive` to another id, then resolve again. The scope is unchanged until `resetForTest()`.
- [ ] **Step 3: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.core.wallets.WalletRegistryTest" --tests "*.core.wallets.ActiveWalletTest"`. Expected FAIL: unresolved `WalletRegistry`.
- [ ] **Step 4: Implement.** `WalletRegistry` keeps no state between calls; it reads and parses `prefs` each time. `recordsOnDisk` in `ActiveWallet.registry` lists `File(context.applicationInfo.dataDir, "shared_prefs")` maps each name through `WalletStores.walletIdOfSeedPrefsFile`, and reads that record's `wallet_network`. `ActiveWallet.scope` is `@Volatile` and cached under a lock.
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
  - `core/.../settings/DisplayCurrencyPrefs.kt:34-74`. Callers pass `storageSuffix(scope, NETWORK)`. The legacy-file move `moveFromLegacy` reads only legacy's `dgb_sync_data<net>`;
  - `core/.../reconcile/DgbNodeClient.kt:117`. **NETWORK on purpose:** `custom_rpc_endpoint` is a device setting that every wallet must see, so the quick start can find it;
  - `app/.../ui/wallet/DisplayCurrencyStore.kt:37`;
  - `app/.../service/SyncWorker.kt:128,149`;
  - `app/.../service/SyncService.kt:1733,3261,3319,3516,3565`;
  - `app/.../StaleDataWiper.kt:44` for `dgb_bloom_peers`;
  - `core/.../WalletManager.kt:1645`, the `moveFromLegacy` call;
  - `core/.../WalletDataEraser.kt:76`. `WALLET_NETWORK_SUFFIXES` stays, as the list of network suffixes the full wipe walks for `legacy`.
- Modify: `core/.../NetworkState.kt`. Delete `networkSuffix`; update the KDoc to point to `storageSuffix`.
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/StoreSuffixSourceGateTest.kt`
- Test: update `app/src/test/java/app/aroundtheblock/wallet/StaleDataWiperWalletResetTest.kt` and `core/src/test/java/app/aroundtheblock/wallet/core/sync/HistoryRebuildOnUpgradeTest.kt`, wherever they call `networkSuffix`.

**Interfaces:**
- Consumes: `storageSuffix`, `WalletStores`, `ActiveWallet.scope` (Tasks 1 and 2).
- Produces: `WalletManager` gains a trailing constructor parameter `scope: WalletScope = WalletScope.legacy(isTestnet(context))`, used by `prefs`, `watchedPrefs` and every store call in `clearSyncData` (`:1637-1667`). `OutgoingTxStore(context: Context, scope: WalletScope = ActiveWallet.scope(context))` and `AssetHistoryBackfill(…, scope: WalletScope = ActiveWallet.scope(context))` get the same parameter. Every store object above gains a trailing `scope: WalletScope = ActiveWallet.scope(ctx)` parameter. Examples: `SavedBlockStore.file(ctx: Context, scope: WalletScope = ActiveWallet.scope(ctx)): File`, `CfAbandonmentStore.clear(ctx: Context, scope: WalletScope = ActiveWallet.scope(ctx)): Boolean`, and `HistoryRebuildOnUpgrade.markNotNeeded(context: Context, forgetFloor: Boolean, scope: WalletScope = ActiveWallet.scope(context))`.

- [ ] **Step 1: Write the failing gate** `StoreSuffixSourceGateTest`. It scans `core/src/main/java` and `../app/src/main/java`, reusing the `statements()` helper style of `WalletWipeSourceGateTest`:
  - `networkSuffix is gone`: no `.kt` file contains `networkSuffix(`.
  - `no per-wallet store is named by a bare literal`: for every base in `WalletStores.WALLET_AND_NETWORK_PREFS + WALLET_PREFS`, no file outside `wallets/WalletStores.kt`, `WalletDataEraser.kt` and `HubTokenStore.kt` contains `getSharedPreferences("<base>"`. Bases held in constants (`PREFS_NAME`, `PREFS_BASE`, `LEGACY_PREFS`) must be passed to `WalletStores.prefsName`, so `getSharedPreferences(PREFS_NAME` followed by `+` also fails.
  - `the scan sees store opens at all`: at least 30 `getSharedPreferences(` statements were found, so a blind scanner fails.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.core.wallets.StoreSuffixSourceGateTest"`. Expected FAIL: `networkSuffix is gone` lists the 21 files.
- [ ] **Step 3: Implement** the sweep file by file, using the grouping above. In `clearSyncData`, pass `this.scope` to every store. Keep each line's existing `commit()`/`apply()` choice. `WalletWipeSourceGateTest` asserts on them.
- [ ] **Step 4: Run** the gate, then `./gradlew :core:testMainnetDebugUnitTest --tests "*.core.wallets.*"`. Expected PASS. `LegacyStoreNameParityTest` still passes, which proves the legacy names did not move.
- [ ] **Step 5: Run the full suites.** Expected exit 0. Source gates that assert `"dgb_sync_data" + …` shapes may need their regex widened to `WalletStores.prefsName("dgb_sync_data"`. Change the regex and keep what it asserts, and list every such gate in the commit message.
- [ ] **Step 6: Commit** the explicit paths of every file touched, message `refactor(wallets): every store resolves through storageSuffix; networkSuffix removed (legacy names unchanged)`.

### Task 4: The scan floor key and the Hub session become per wallet

**Files:**
Task 3 already routes the watch set, sends, asset state and diag through `WalletStores`. This task covers what is a key or a separate store type rather than a prefs file name.

- Modify: `core/.../WalletManager.kt:310-311` and `:816-817` (`cf_birth_height`).
- Modify: `app/.../service/SyncService.kt`, at the `cf_birth_height` reads and writes at `:2296,2424,2427,2428,2456-2457,2552-2553,4035`. Each becomes `val birthKey = WalletStores.cfBirthHeightKey(scope)`.
- Modify: `app/.../service/CfRestorePreflight.kt:92-94` (KDoc only).
- Modify: `core/.../digiscope/HubTokenStore.kt`. Add a constructor `HubTokenStore(context: Context, scope: WalletScope)` over `WalletStores.prefsName(LEGACY_PREFS_NAME, scope)` and `prefsName(SECURE_PREFS_NAME, scope)`. The existing `HubTokenStore(context)` delegates with `ActiveWallet.scope(context)`.
- Modify: `core/.../digiscope/DigiScopeClient.kt:85`, to `HubTokenStore(context, ActiveWallet.scope(context))`.
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/security/HubTokenStoreScopeTest.kt`
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/PerWalletStoresTest.kt`
- Test (update): `app/src/test/java/app/aroundtheblock/wallet/service/TransactionCheckpointPreflightTest.kt:71`, where the literal `putLong("cf_birth_height", birth)` becomes `putLong(birthKey, birth)`; `app/src/test/java/app/aroundtheblock/wallet/service/HistoryRebuildWiringTest.kt:154`, where `"cf_birth_height"` becomes `birthKey`; and `StaleDataWiperWalletResetTest.kt:332-350`, which uses `WalletStores.cfBirthHeightKey(WalletScope.legacy(false))`.

**Interfaces:**
- Consumes: Tasks 1 to 3.
- Produces: `HubTokenStore(context: Context, scope: WalletScope)`.

- [ ] **Step 1: Write the failing tests.**
  - `HubTokenStoreScopeTest.wallet B never reads wallet A's Hub token`. Use the internal constructor over two `InMemoryPrefs` pairs, resolved through `WalletStores.prefsName` for A=`legacy` and B=`0123456789abcdef`. After `A.save("tok-a")`, `B.load() == null` and `A.load() == "tok-a"`. The names used are `"dgb_hub_session"` for A and `"dgb_hub_session_0123456789abcdef"` for B.
  - `PerWalletStoresTest.a non-legacy wallet's watch set, sends and floor are its own`. Build `WalletManager(…, scope = B)` and `OutgoingTxStore(ctx, B)` over `wipeTestContext`. Record a send and a watched address in B. Then legacy's `dgb_watched_addrs` and `dgb_outgoing_tx` stay empty, and `dgb_settings` gains the key `cf_birth_height_0123456789abcdef` only.
  - `PerWalletStoresTest.legacy still writes cf_birth_height`. With scope legacy, the recover path writes `dgb_settings["cf_birth_height"]`.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.HubTokenStoreScopeTest" --tests "*.PerWalletStoresTest"`. Expected FAIL: no `HubTokenStore(Context, WalletScope)`.
- [ ] **Step 3: Implement** as listed. Read every `cf_birth_height` key through `WalletStores.cfBirthHeightKey(scope)`. The `scope` in `SyncService` is `ActiveWallet.scope(this)`, read once in `onCreate`.
- [ ] **Step 4: Run** the same command plus `./gradlew :app:testMainnetPlayDebugUnitTest --tests "*.TransactionCheckpointPreflightTest" --tests "*.HistoryRebuildWiringTest" --tests "*.StaleDataWiperWalletResetTest"`. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, message `feat(wallets): scan floor key and Hub session are per wallet`.

### Task 5: The per-wallet seed record and Keystore aliases

**Files:**
- Modify: `core/.../WalletManager.kt`. The `scope` parameter exists since Task 3.
- Add `fun storeRecordOnly(mnemonic: String, passphrase: ByteArray?, creationTimeSecs: Long, origin: WalletOrigin): Boolean`. It seals through `sealForNewRecord`, then `storeSeedRecord`. It does not call native create or recover, `clearSyncData` or rescan. It also writes `wallet_network` (`"mainnet"` / `"testnet"`) into the record, so the registry can be rebuilt from disk. It returns false and writes nothing when the scope's seed prefs or `dgb_sync_data` prefs are non-empty. Task 7 widens this check to `WalletStoreEraser.anythingLeft(scope)`.
- Add `fun recordIdentity(): String?`, the record's `seed_fingerprint_v2`, or null.
- Add `fun recordFingerprintV1(): String?`.
- Modify: `app/.../di/AppModule.kt:30` (`provideKeyStoreManager` → `KeyStoreManager(context, WalletStores.keyAlias(ActiveWallet.scope(context)))`), `:113-123` (`provideWalletManager` passes `scope = ActiveWallet.scope(context)`). Add `@Provides @Singleton fun provideWalletRegistry(@ApplicationContext context: Context): WalletRegistry = ActiveWallet.registry(context)`. Add `fun interface WalletManagerFactory { fun forScope(scope: WalletScope): WalletManager }`, provided as a lambda that builds `WalletManager(context, KeyStoreManager(context, WalletStores.keyAlias(scope)), um, scope = scope)`.
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/ScopedSeedRecordTest.kt`
- Test (update): `core/src/test/java/app/aroundtheblock/wallet/core/KeyTestSupport.kt`. Change to `keyStoreManagerOver(vault: FakeVault, authBound: Boolean = true, alias: String = LEGACY_ALIAS)`, so the bound alias becomes `alias + "_v2"`.

**Interfaces:**
- Consumes: Tasks 1 to 4; `KeyStoreManager(context, alias)` (unchanged, `KeyStoreManager.kt:37-40`).
- Produces: `WalletManager.storeRecordOnly(...)`, `WalletManager.recordIdentity()`, `WalletManager.recordFingerprintV1()`, `WalletManagerFactory`.

- [ ] **Step 1: Write the failing tests** in `ScopedSeedRecordTest`, using `keyTestContext`, `FakeVault` and `seedNative = { _, _ -> ByteArray(64) { 7 } }`:
  - `a new wallet's record lands in its own prefs under its own aliases`. For scope B, `storeRecordOnly("abandon … about", null, 1_704_067_200L, RESTORED)` is true. Then:
    - `prefsFor("dgb_wallet_seed_0123456789abcdef")` contains `encrypted_seed_v2`;
    - `prefsFor("dgb_wallet_seed").all.isEmpty()`;
    - `vault.keys == setOf("dgb_wallet_master_0123456789abcdef_v2")`;
    - `wallet_creation_time == 1_704_067_200L`;
    - `wallet_origin == "restored"`.
  - `storing a record touches no native wallet and no other store`. `createNative`, `recoverNative` and `rescanNative` are fakes that fail the test if called. Every prefs name the factory opened, minus `dgb_wallet_seed_0123456789abcdef`, holds what it held before.
  - `a record is never stored over existing state of its scope`. Seed `dgb_sync_data_0123456789abcdef` with `has_synced=true`; `storeRecordOnly(...) == false` and nothing is written.
  - `restoreFromDisk for B reads B's record and does not clear B's sync data`. After `storeRecordOnly`, seed B's `dgb_sync_data_…` with `has_synced=true` and call `restoreFromDisk()` with `restoreNative` faked to true. `has_synced` stays true, because the fingerprint verdict compares B's record with B's own fingerprint.
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
  - `UpgradeFrom414Test.a restore of the legacy wallet after upgrade opens its sync state`. `restoreFromDisk()` with native faked leaves `dgb_sync_data.has_synced == true`, and `SavedBlockStore.file(ctx).exists()` is still true.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.UpgradeFrom414Test"`. Expected PASS on first run: this task is a proof, not a change. Then prove that it can fail. Temporarily change `storageSuffix`'s legacy WALLET_AND_NETWORK branch to `"_legacy"`. It must FAIL with `has_synced` false and the database name `"wallet_legacy.db"`. Revert the change.
- [ ] **Step 3: Run the full suites.** Expected exit 0.
- [ ] **Step 4: Commit** the test and fixture, message `test(wallets): upgrade from 4.1.4 keeps every store, key and alias of the existing wallet`.

### Task 7: Erase one wallet's scope

**Files:**
- Create: `core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletStoreEraser.kt`
- Modify: `core/.../WalletDataEraser.kt:113-122`. Add `fun aliases(): List<String>` to `KeystoreAliases`, implemented with `keyStore().aliases().toList()`.
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/WalletStoreEraserTest.kt`

**Interfaces:**
- Consumes: `WalletStores`, `KeystoreAliases`, `HubTokenStore(context, scope)`, `DatabaseFiles.everyFileOf`, `DatabaseKeyProvisioner.releaseLegacyWrap`.
- Produces:
```kotlin
class WalletStoreEraser internal constructor(
    private val context: Context,
    private val keystore: KeystoreAliases,
    private val releaseLegacyDbWrap: () -> Boolean,   // DatabaseKeyProvisioner.forContext(context).releaseLegacyWrap()
) {
    constructor(context: Context)
    /** Seed record first, then aliases, then every other store of [scope]. For legacy: both networks.
     *  Shared stores are never touched: NETWORK_PREFS, dgb_settings except the scope's floor key, the
     *  custom_rpc_endpoint key of legacy's dgb_reconcile<net>, dgb_db_key, dgb_pin_store, dgb_wallets. */
    fun erase(scope: WalletScope): EraseReport
    /** Read-back only: true when any prefs file, file, DB file or alias of [scope] is still present. */
    fun anythingLeft(scope: WalletScope): Boolean
}
data class EraseReport(val recordGone: Boolean, val aliasesGone: Boolean, val storesGone: Boolean, val dbKeyHeldLegacyKey: Boolean) {
    val verified: Boolean get() = recordGone && aliasesGone && storesGone
}
```
- Rules: the legacy aliases are deleted only after `releaseLegacyDbWrap()` returns true. Otherwise they stay, and `dbKeyHeldLegacyKey = true`. Each prefs clear uses `commit()`, and its result is part of the report, as in `AndroidWalletDataEraser.clearPrefs`.

- [ ] **Step 1: Write the failing tests:**
  - `erasing B leaves every byte of legacy and C`. Populate three scopes (legacy, B, C) across every `WalletStores` list, plus a database file each, plus aliases. After `erase(B).verified == true`, every store of legacy and C equals its snapshot, `anythingLeft(B) == false`, and the vault's aliases are `{dgb_wallet_master, dgb_wallet_master_v2, dgb_wallet_master_<C>_v2}`.
  - `shared stores survive`: `dgb_bloom_peers`, `dgb_price`, `dgb_settings.app_language`, `dgb_db_key`, `dgb_wallets` and `dgb_pin_store` are unchanged.
  - `erasing legacy keeps the custom node endpoint`. Seed `dgb_reconcile` with `custom_rpc_endpoint=https://node.example` and `reconcile_done=true`. After `erase(legacy)`, `dgb_reconcile` equals exactly `{custom_rpc_endpoint=https://node.example}`.
  - `erasing legacy removes it on both networks`: no `dgb_sync_data_testnet` and no `wallet_testnet.db` remain.
  - `legacy's key stays while the database record depends on it`. With `releaseLegacyDbWrap = { false }`, the result has `dbKeyHeldLegacyKey == true` and `aliasesGone == false`, and `dgb_wallet_master` is still in the vault.
  - `a refused commit is reported`: `refuseWritesTo("dgb_sync_data_<B>")` gives `storesGone == false`.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.WalletStoreEraserTest"`. Expected FAIL: unresolved class.
- [ ] **Step 3: Implement.** Iterate the `WalletStores` lists, so there is no second list. Widen `WalletManager.storeRecordOnly`'s precondition to `!eraser.anythingLeft(scope)`; `ScopedSeedRecordTest` must still pass. `WalletWipeSourceGateTest` applies its "no discarded commit" rule to this file too: add it to that gate's scanned files.
- [ ] **Step 4: Run** the same command plus `--tests "*.WalletWipeSourceGateTest"`. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, message `feat(wallets): erase one wallet's scope with read-back; shared stores untouched`.

### Task 8: Add a wallet (core): reserve, duplicate check, record, complete, launch housekeeping

**Files:**
- Create: `core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletProvisioner.kt`
- Create: `core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletHousekeeping.kt`
- Modify: `app/.../DigiByteApp.kt:52-58`. After `applyNetworkSelection()` and before `HistoryRebuildOnUpgrade.runAtProcessStart`, add `runCatching { WalletHousekeeping(this).run() }`.
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/WalletProvisionerTest.kt`
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/WalletHousekeepingTest.kt`

**Interfaces:**
- Consumes: `WalletRegistry`, `WalletManagerFactory`, `WalletStoreEraser`, `SeedFingerprint.v1/v2`, `NativeBridge.mnemonicToSeed` (injected).
- Produces:
```kotlin
sealed class AddResult {
    data class Added(val entry: WalletEntry) : AddResult()
    data class Duplicate(val existing: WalletEntry) : AddResult()
    object CapReached : AddResult()
    object Failed : AddResult()
}
class WalletProvisioner(
    private val registry: WalletRegistry,
    private val managerFor: (WalletScope) -> WalletManager,
    private val eraser: WalletStoreEraser,
    private val deriveSeed: (ByteArray, ByteArray?) -> ByteArray?,   // NativeBridge.mnemonicToSeed
    private val nowSecs: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** The READY wallet on [testnet] this phrase+passphrase opens, or null. Zeroes what it derives. */
    fun duplicateOf(testnet: Boolean, mnemonic: String, passphrase: ByteArray?): WalletEntry?
    /** Prepares the new scope's seed key (KS-BIND) before [add]; same contract as WalletManager.prepareSeedKeyForNewRecord. */
    fun prepareSeedKey(testnet: Boolean): Pair<WalletEntry, SeedKeyReadiness>?   // null at the cap; reserves the entry
    /** Writes the record for [entry] and completes it as the active wallet. Caller owns and zeroes [passphrase]. */
    fun add(entry: WalletEntry, mnemonic: String, passphrase: ByteArray?, creationTimeSecs: Long): AddResult
}
class WalletHousekeeping(context: Context /* + internal ctor taking registry and eraser */) {
    /** PENDING → erase its scope and drop it. REMOVING → erase its scope and drop it (Task 11 relies on this). */
    fun run(): Int   // entries finished
}
```
- The duplicate rule compares `SeedFingerprint.v2(deriveSeed(m, p))` with each READY entry's `recordIdentity()`. For a record without v2 and `p == null`, it compares `SeedFingerprint.v1(m)` with `recordFingerprintV1()`. Every derived buffer is zeroed in `finally`.
- The creation time for a create is `nowSecs()`. For a restore it is the chosen `RecoveryDateOption.timestamp`, which is always > 0 (`RecoveryDateScreen.kt:110,349-410`), so `restoreFromDisk` builds the new wallet from the right birth checkpoint after the restart. `SyncService.compactFilterBirthHeight` falls back to `walletBirth` when no floor key exists, so no native call is needed before the restart.

- [ ] **Step 1: Write the failing tests** in `WalletProvisionerTest`, over `wipeTestContext`, `FakeVault`, and a `deriveSeed` that returns `sha256(mnemonic + passphrase)` padded to 64 bytes:
  - `adding a restored wallet stores its record and makes it active`: `add(entry, PHRASE_B, null, 1_704_067_200L)` is `Added`; `registry.activeId(false) == entry.id`; `entry.state` reads back as READY; the record's creation time is `1_704_067_200L`.
  - `a phrase already in the app is refused and names the wallet it matches`. With legacy holding `PHRASE_A`, `duplicateOf(false, PHRASE_A, null)?.id == "legacy"`, and `add` for `PHRASE_A` returns `Duplicate(legacy)` and writes no `dgb_wallet_seed_<id>`.
  - `the same mnemonic with a passphrase is a different wallet`: `duplicateOf(false, PHRASE_A, "x".toByteArray()) == null`.
  - `a legacy record from before fingerprint v2 is still matched`. Remove legacy's `seed_fingerprint_v2` and keep `seed_fingerprint`; `duplicateOf(false, PHRASE_A, null)?.id == "legacy"`.
  - `the ninth wallet is refused before anything is written`: with 8 READY, `prepareSeedKey(false) == null`.
  - `a failed record write leaves no entry and no store`. `refuseWritesTo("dgb_wallet_seed_<id>")` gives `Failed`; afterwards `registry.incomplete().isEmpty()` and `eraser.anythingLeft(scope) == false`.
  - `adding never touches the active wallet's stores`. Snapshot legacy's stores, `add` B, and compare: they are equal.
- [ ] **Step 2: Write the failing tests** in `WalletHousekeepingTest`:
  - `an add killed after its record landed is erased at the next launch`. Do `reserve` and `storeRecordOnly`, but not `complete`. `run() == 1`; then `incomplete().isEmpty()`, `anythingLeft(scope) == false`, and `list(false)` has ids `["legacy"]`.
  - `housekeeping with nothing incomplete writes nothing`. On the 4.1.4 layout, `run() == 0` and `dgb_wallets` is still absent. Then add `WalletHousekeeping(ctx).run()` to `UpgradeFrom414Test`'s launch sequence, which must still find every snapshot unchanged.
- [ ] **Step 3: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.WalletProvisionerTest" --tests "*.WalletHousekeepingTest" --tests "*.UpgradeFrom414Test"`. Expected FAIL: unresolved classes.
- [ ] **Step 4: Implement** as specified. `add` order: `managerFor(scope).storeRecordOnly(...)`, then `registry.complete(id, makeActive = true)`. On a false result from either step, call `eraser.erase(scope)` and `registry.drop(id)`, then return `Failed`.
- [ ] **Step 5: Run** the same command. Expected PASS.
- [ ] **Step 6: Run the full suites.** Expected exit 0.
- [ ] **Step 7: Commit** the explicit paths, message `feat(wallets): add a wallet by writing only its own record; duplicate phrases refused; interrupted adds erased at launch`.

### Task 9: Switching: persist the active id, end the web session, restart

**Files:**
- Create: `app/src/main/java/app/aroundtheblock/wallet/AppRestart.kt`
- Create: `app/src/main/java/app/aroundtheblock/wallet/WalletSwitcher.kt`
- Modify: `app/.../ui/settings/SettingsViewModel.kt:214-228`. `setNetworkTestnet` calls `AppRestart.restart(context)`; `restartApp()` is deleted.
- Modify: `app/.../IdentitySessionEraser.kt`. Expose `fun endWebSessionOnly(): Boolean`, which runs only the `endWebSession` part on the main thread.
- Modify: `app/.../di/AppModule.kt`. Provide `WalletSwitcher`.
- Test: `app/src/test/java/app/aroundtheblock/wallet/WalletSwitcherTest.kt`
- Test: `app/src/test/java/app/aroundtheblock/wallet/AppRestartGateTest.kt`

**Interfaces:**
- Consumes: `WalletRegistry.setActive`, `IdentitySessionEraser`.
- Produces:
```kotlin
fun interface AppRestart {
    fun restart(context: Context)
    companion object : AppRestart {   // the one production path: SettingsViewModel's former restartApp body
        override fun restart(context: Context)
    }
}
class WalletSwitcher internal constructor(
    private val registry: WalletRegistry,
    private val testnet: () -> Boolean,
    private val endWebSession: () -> Boolean,
    private val restart: () -> Unit,
) {
    enum class Result { RESTARTING, ALREADY_ACTIVE, UNKNOWN_WALLET, NOT_SAVED }
    fun switchTo(id: String): Result
}
```
- Order inside `switchTo`: validate that the id is READY on this network. Then `registry.setActive(...)`; if the commit fails, return `NOT_SAVED`. Then `endWebSession()`. Its result is logged by outcome only and never blocks the switch, because the new process starts with the new wallet either way. Then `restart()`. Hub logout is deliberately **not** called: wallet A's server session stays valid for A, and its token is in A's store.

- [ ] **Step 1: Write the failing tests:**
  - `WalletSwitcherTest.a switch persists the id, ends the web session, then restarts`. Record calls into a list; for id B the list is `["setActive:B", "endWebSession", "restart"]`.
  - `WalletSwitcherTest.a switch ends the web session before it restarts`. When `endWebSession` returns false, the order is the same and the result is `RESTARTING`.
  - `WalletSwitcherTest.an unsaved id never restarts`. With `refuseWritesTo("dgb_wallets")` the result is `NOT_SAVED`, `restart` is not called, and `activeId` is unchanged.
  - `WalletSwitcherTest.the active wallet is not restarted into`: `ALREADY_ACTIVE`, and no calls.
  - `AppRestartGateTest.one restart path`. A source scan of `app/src/main/java` finds `Runtime.getRuntime().exit(` only in `AppRestart.kt`.
- [ ] **Step 2: Run** `./gradlew :app:testMainnetPlayDebugUnitTest --tests "*.WalletSwitcherTest" --tests "*.AppRestartGateTest"`. Expected FAIL: unresolved classes.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run** the same command. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, message `feat(wallets): switch wallets by persisting the active id and restarting; DigiStamp web session ended on switch`.

### Task 10: The wipe (PIN-failure and Settings) removes every wallet

**Files:**
- Modify: `core/.../WalletManager.kt:940-1003`, `wipeWallet`:
  - after the legacy `eraseSeedCiphertext()`, erase every non-legacy record **first**, in one loop over `registry ids ∪ seed-prefs files on disk ∪ walletIdOfAlias(keystore aliases)`;
  - then delete those aliases;
  - then erase every non-legacy scope's other stores through `WalletStoreEraser`;
  - then clear `dgb_wallets`.
  - `recordGone` becomes "no seed record of any wallet is readable". `seedGone` adds "no alias for which `walletIdOfAlias != null`".
  - New constructor parameters: `allWalletIds: () -> List<String>` and `scopeEraser: WalletStoreEraser`, both defaulted so pure-JVM constructions compile.
- Modify: `core/.../WalletDataEraser.kt:99-102`. KDoc: one wipe for every wallet.
- Modify: `app/.../ui/settings/SecuritySettingsScreen.kt:228,528,563`. Use `R.string.sec_wipe_after_on_all`, `sec_wipe_all_body` and `sec_wipe_after_body_all`.
- Modify: `app/src/main/res/values*/strings_wallet.xml` (13 dirs). Add the three keys and delete `sec_wipe_after_on`, `sec_wipe_after_body` and `sec_wipe_wallet_body`.
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/WalletWipeEveryWalletTest.kt`
- Test (update): `core/src/test/java/app/aroundtheblock/wallet/core/WalletWipeTest.kt:398-420`. Add `dgb_wallets` to the stores that must be empty after a wipe.

**Interfaces:**
- Consumes: Tasks 1, 2, 5 and 7.
- Produces: no new public API. `WipeReport` semantics now cover every wallet.

- [ ] **Step 1: Write the failing tests** in `WalletWipeEveryWalletTest`:
  - `a wipe erases every wallet's record, stores and aliases`. Populate legacy, B and C (records, stores, DBs, aliases `…_v2`, registry); `wipeThenReleasePin(pinManager).verified == true`. Afterwards no `dgb_wallet_seed*` prefs hold a key, `vault.aliases().none { WalletStores.walletIdOfAlias(it) != null }` holds, no `wallet*.db*` file exists, `dgb_wallets` is empty, and the PIN store is cleared.
  - `a surviving alias of any wallet means the seed is not gone`. Make `vault.delete("dgb_wallet_master_<C>_v2")` a no-op; `seedGone == false`, and the PIN store is kept.
  - `every record goes before any key`. The recorded event order has every `clear dgb_wallet_seed*` before the first `delete dgb_wallet_master*`.
  - `a wallet missing from the registry is still wiped`: a `dgb_wallet_seed_<D>` on disk that is not in `dgb_wallets` is erased.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.WalletWipeEveryWalletTest" --tests "*.WalletWipeTest"`. Expected FAIL: B's record survives.
- [ ] **Step 3: Implement** as specified, then the strings in all 13 locales. `LocaleResourceParityTest` must pass.
- [ ] **Step 4: Run** the same command plus `./gradlew :app:testMainnetPlayDebugUnitTest --tests "*.LocaleResourceParityTest" --tests "*.OnboardingHardcodedStringTest" --tests "*.WipeCallSiteGateTest"`. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, including all 13 `strings_wallet.xml`, message `feat(wallets): the PIN-failure and Settings wipe remove every wallet; Security text says so (13 locales)`.

### Task 11: Remove one wallet (core)

**Files:**
- Create: `core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletRemoval.kt`
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/WalletRemovalTest.kt`

**Interfaces:**
- Consumes: `WalletRegistry.markRemoving/drop/list`, `WalletStoreEraser.erase`, `WalletHousekeeping` (resumes REMOVING), `WalletManager.wipeThenReleasePin`.
- Produces:
```kotlin
sealed class RemoveResult {
    object Removed : RemoveResult()                     // a non-active wallet: no restart needed
    object RemovedRestartNeeded : RemoveResult()        // the active wallet: the caller restarts via AppRestart
    data class WipedEverything(val report: WipeReport) : RemoveResult()   // it was the last wallet on the device
    object DatabaseKeyStillOnLegacy : RemoveResult()    // legacy kept: try after the next launch
    object Incomplete : RemoveResult()                  // housekeeping finishes it at the next launch
    object NameMismatch : RemoveResult()
}
class WalletRemoval(
    private val registry: WalletRegistry,
    private val eraser: WalletStoreEraser,
    private val activeScope: WalletScope,
    private val stopActiveSession: () -> Unit,           // WalletManager's runningSessions stop + quiesceNative
    private val wipeAll: suspend () -> WipeReport,       // walletManager.wipeThenReleasePin(pinManager)
) {
    /** [typedName] must equal the entry's shown name (trimmed, exact). */
    suspend fun remove(id: String, typedName: String, shownName: String): RemoveResult
}
```
- Order:
  1. Check the name.
  2. If this is the last READY wallet across both networks, run `wipeAll()`.
  3. Otherwise, call `markRemoving(id, newActive = next READY on that network or null)` as one commit. The entry disappears from the list and the active id moves before anything is erased.
  4. If the id is active, call `stopActiveSession()`.
  5. Call `eraser.erase(scope)`.
  6. If verified, call `drop(id)`.
  7. If `dbKeyHeldLegacyKey`, return `DatabaseKeyStillOnLegacy` and leave the entry REMOVING; housekeeping retries at launch, after `WalletDatabaseOpener` has moved the record.
- For `legacy`, both networks' legacy stores go, because there is one seed.

- [ ] **Step 1: Write the failing tests:**
  - `removing one wallet leaves every byte of the others`: remove B (not active) gives `Removed`; snapshots of legacy and C are equal; `list(false)` ids are `["legacy", C]`.
  - `removing the active wallet makes another wallet active before anything is erased`. With B active, record events; `markRemoving(B, newActive=legacy)` comes before the first erase, and the result is `RemovedRestartNeeded`.
  - `a removal killed mid-erase finishes at the next launch`. Make the eraser throw after the record. `remove` gives `Incomplete`, and `registry.find(B)!!.state == REMOVING`. Then `WalletHousekeeping.run() == 1`, and `anythingLeft(B) == false`.
  - `removing the last wallet is the full wipe`. With only legacy, `remove("legacy", "Wallet 1", "Wallet 1")` is `WipedEverything` and `wipeAll` was called once.
  - `removing legacy keeps its key while the database key record depends on it`: the result is `DatabaseKeyStillOnLegacy`; `dgb_wallet_master` is still in the vault; the entry is REMOVING and not listed.
  - `removing legacy keeps the custom node endpoint`: `dgb_reconcile.custom_rpc_endpoint` is unchanged.
  - `a wrong typed name removes nothing`: `remove(B, "Wallet", "Wallet 2") == NameMismatch`, with no writes.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.WalletRemovalTest"`. Expected FAIL: unresolved class.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run** the same command. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, message `feat(wallets): remove one wallet; the active one hands over first; the last one is the full wipe`.

### Task 12: Onboarding in add mode (create and restore, no PIN setup)

**Files:**
- Create: `app/src/main/java/app/aroundtheblock/wallet/ui/onboarding/OnboardingRoutes.kt`
- Modify: `app/.../ui/onboarding/OnboardingScreen.kt:29-155`. In ADD mode it shows `wallet_add_title` and `wallet_add_pin_note`, labels its two buttons `wallet_add_create` / `wallet_add_restore`, keeps the word-count toggle, and shows `wallet_add_cap` (both buttons disabled) when the cap is reached.
- Create: `app/src/main/java/app/aroundtheblock/wallet/ui/onboarding/AddWalletCommitScreen.kt`
- Modify: `app/.../ui/onboarding/OnboardingViewModel.kt`:
  - inject `SavedStateHandle`, `WalletProvisioner` and `WalletSwitcher`;
  - add `val mode: OnboardingMode`;
  - `recoverWallet` at `:236-286` must **not** call `pinManager.clearPin()` (`:252`) when `mode == ADD`. In ADD mode it is never reached; add `check(mode == FIRST_RUN)`;
  - add `fun checkDuplicate(): WalletEntry?`;
  - add `fun commitAdd(seedKeyPrompt: (suspend () -> Unit)?, onResult: (AddResult) -> Unit)`, which uses `_mnemonic`, `_passphrase` and `_recoveryTimestamp` (or now, for a create), and zeroes and clears as `createWallet:198-229` does.
- Modify: `app/.../ui/onboarding/PassphraseScreen.kt:120`. In ADD mode, navigate to `ADD_COMMIT_ROUTE`.
- Modify: `app/.../ui/onboarding/RecoveryDateScreen.kt:187-206`. In ADD mode, store the timestamp and navigate to `QUICK_START_ROUTE` (Task 15). Until Task 15, navigate to `ADD_COMMIT_ROUTE`. The screen does not call `viewModel.recoverWallet`.
- Modify: `app/.../ui/onboarding/MnemonicInputScreen.kt:228`. In ADD mode, call `checkDuplicate()` first. If it finds one, show `wallet_add_duplicate` with an `wallet_add_duplicate_switch` button that calls `WalletSwitcher.switchTo(existing.id)`.
- Modify: `app/.../ui/navigation/AppNavigation.kt:260-335`:
  - `composable(ONBOARDING_ROUTE, arguments = listOf(navArgument("mode") { defaultValue = "first_run" }))`;
  - every `getBackStackEntry("onboarding")` becomes `getBackStackEntry(ONBOARDING_ROUTE)`;
  - add `composable(ADD_COMMIT_ROUTE)`.
- Modify: `app/.../ui/navigation/LockGatePolicy.kt:10-16`. Add `add_wallet_commit` and `restore_quick_start` to `PRE_WALLET_ROUTES`, so a lock mid-add routes to unlock, because a wallet is stored.
- Modify: `app/src/test/.../ui/locale/OnboardingHardcodedStringTest.kt`. Add `AddWalletCommitScreen.kt` to `COVERED` (`OnboardingScreen.kt` is already there), and the new route literals to `ROUTES`.
- Strings: the `wallet_add_*` keys (see the table), in 13 locales.
- Test: `app/src/test/java/app/aroundtheblock/wallet/ui/onboarding/AddModeGateTest.kt`
- Test: `app/src/test/java/app/aroundtheblock/wallet/ui/navigation/LockGatePolicyTest.kt` (extend it).

**Interfaces:**
- Consumes: `WalletProvisioner`, `WalletSwitcher`, `AddResult`.
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
    - `add mode never reaches PIN setup`: in `PassphraseScreen.kt` and `RecoveryDateScreen.kt`, every `navigate("pin_setup"` is inside an `if (viewModel.mode == OnboardingMode.FIRST_RUN)` / `else` branch whose other arm navigates to `ADD_COMMIT_ROUTE`.
    - `add mode never clears the PIN`: `OnboardingViewModel.commitAdd` contains no `pinManager.`.
    - `add mode never creates through PIN setup`: `AddWalletCommitScreen.kt` contains no `pinSetupCreatesWallet`, `createWallet(` or `recoverWallet(`.
  - `LockGatePolicyTest.a lock during an add routes to unlock`: `shouldRouteToUnlock(Locked, hasPin=true, currentRoute="add_wallet_commit", walletStored=true) == true`.
- [ ] **Step 2: Run** `./gradlew :app:testMainnetPlayDebugUnitTest --tests "*.AddModeGateTest" --tests "*.LockGatePolicyTest"`. Expected FAIL: no `OnboardingMode`.
- [ ] **Step 3: Implement** as specified:
  - `OnboardingScreen` in ADD mode: as described under Files. The create and restore paths below it are the existing screens, sharing the `ONBOARDING_ROUTE` ViewModel.
  - `AddWalletCommitScreen` shows `wallet_add_saving`, then calls `commitAdd(seedKeyPromptFor(…))`. It maps `Added` to `WalletSwitcher.switchTo(entry.id)`, `Duplicate` to the duplicate message, and `CapReached`/`Failed` to `wallet_add_cap`/`wallet_add_failed`.
  - Add the strings to all 13 locales.
- [ ] **Step 4: Run** the same command plus `--tests "*.LocaleResourceParityTest" --tests "*.OnboardingHardcodedStringTest" --tests "*.PinSetupCreateGateTest"`. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths (code, tests, 13 strings files), message `feat(wallets): add a wallet through onboarding in add mode; no PIN setup, no PIN clear (13 locales)`.

### Task 13: Switcher UI: chip, sheet, manage (rename, reorder, remove)

**Files:**
- Create: `app/src/main/java/app/aroundtheblock/wallet/ui/wallets/WalletsViewModel.kt`, `WalletChip.kt`, `WalletSwitcherSheet.kt`, `ManageWalletsScreen.kt`, `RemoveWalletDialog.kt`
- Modify: `app/.../ui/wallet/WalletScreen.kt:107-108`. Put `WalletChip` above the hero card as the first `item {}`.
- Modify: `app/.../ui/navigation/AppNavigation.kt`. Add `composable("manage_wallets")`; the sheet's Add navigates to `onboardingRoute(OnboardingMode.ADD)`.
- Modify: `OnboardingHardcodedStringTest.COVERED`. Add the five new files.
- Strings: the `wallet_switcher_*`, `wallet_chip_cd`, `wallet_manage_*`, `wallet_remove_*` and `wallet_default_name` keys, in 13 locales.
- Test: `app/src/test/java/app/aroundtheblock/wallet/ui/wallets/WalletsViewModelTest.kt`

**Interfaces:**
- Consumes: `WalletRegistry`, `WalletSwitcher`, `WalletRemoval`, `SpendAuth.authorize(activity, title, subtitle)` (`SpendAuth.kt:151`), `AppRestart`, `FreshStartAfterWipe.afterWipe`.
- Produces:
```kotlin
data class WalletRow(val id: String, val shownName: String, val active: Boolean,
                     val lastBalanceSat: Long?, val lastDdCents: Long?, val alsoTestnet: Boolean)
@HiltViewModel class WalletsViewModel @Inject constructor(...) : ViewModel() {
    val rows: StateFlow<List<WalletRow>>   // last_balance / last_dd_balance read from each wallet's
                                           // WalletStores.prefsName("dgb_sync_data", scope); null when has_synced is false
    val canAdd: StateFlow<Boolean>         // list size < MAX_PER_NETWORK
    fun switchTo(id: String)
    fun rename(id: String, name: String): Boolean
    fun move(id: String, delta: Int): Boolean   // -1 up, +1 down
    suspend fun remove(id: String, typedName: String): RemoveResult
}
internal fun shownName(entry: WalletEntry, defaultName: (Int) -> String): String   // entry.name ?: defaultName(entry.number)
```
- Removal UI order: the backup warning, then typed-name confirmation (the confirm button stays disabled until the text equals `shownName`), then `SpendAuth.authorize(..., subtitle = wallet_remove_auth_subtitle)`, then `remove`. The results map as follows: `RemovedRestartNeeded` → `AppRestart.restart`; `WipedEverything` → `FreshStartAfterWipe.afterWipe`; `DatabaseKeyStillOnLegacy` → `wallet_remove_db_key_kept`; `Incomplete` → `wallet_remove_failed`.

- [ ] **Step 1: Write the failing tests** in `WalletsViewModelTest`, over a registry on `InMemoryPrefs` and fake stores:
  - `rows show each wallet's own last balance`. With legacy `last_balance=150000000`, `has_synced=true`, and B `last_balance=7`, `has_synced=true`: `rows` balances are `[150000000, 7]`.
  - `a wallet that has never synced shows no balance`: B with `has_synced` absent has `lastBalanceSat == null`.
  - `default names follow the wallet number, not the order`. After reordering B before legacy, the shown names are `["Wallet 2", "Wallet 1"]`.
  - `the eighth wallet hides Add`: `canAdd == false` at 8.
  - `legacy is marked as also the testnet wallet`: `alsoTestnet == true` for legacy and false for B.
- [ ] **Step 2: Run** `./gradlew :app:testMainnetPlayDebugUnitTest --tests "*.WalletsViewModelTest"`. Expected FAIL: unresolved class.
- [ ] **Step 3: Implement** the ViewModel, then the composables within the existing theme. The chip shows `shownName` and opens the sheet. The sheet lists rows with the DGB balance and, when non-zero, the DigiDollar balance, then Add and Manage. Add the strings to all 13 locales.
- [ ] **Step 4: Run** the same command plus `--tests "*.LocaleResourceParityTest" --tests "*.OnboardingHardcodedStringTest"`. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, message `feat(wallets): wallet chip, switcher sheet and manage screen (13 locales)`.

### Task 14: Show the wallet's name in the Hub, the recovery phrase screen and the sync notification

**Files:**
- Modify: `app/.../ui/hub/ProfileView.kt:47-140` (`HubAccountStrip`). Under the handle, show `hub_signed_in_wallet` with the active wallet's shown name.
- Modify: `app/.../ui/settings/SeedViewScreen.kt:45`. Add a header `seedview_wallet_name` with the active wallet's shown name.
- Modify: `app/.../service/SyncService.kt:4101-4128` (`buildNotification`). The content title is `sync_notif_title_wallet(app_name_display, shownName)` when more than one wallet is listed, and `app_name_display` otherwise. The content text moves from the hardcoded English to `sync_notif_synced` / `sync_notif_connecting` / `sync_notif_syncing` / `sync_notif_via_tor`.
- Modify: `OnboardingHardcodedStringTest.COVERED`. Add `ui/hub/ProfileView.kt` and `ui/settings/SeedViewScreen.kt`, and check that `service/SyncService.kt`'s notification literals are gone.
- Strings: `hub_signed_in_wallet`, `seedview_wallet_name`, `sync_notif_*`, in 13 locales.
- Test: `app/src/test/java/app/aroundtheblock/wallet/service/SyncNotificationTextTest.kt`

**Interfaces:**
- Consumes: `WalletRegistry`, `shownName` (Task 13), `ActiveWallet.scope`.
- Produces: `internal fun notificationTitle(appName: String, walletName: String, walletCount: Int, format: (String, String) -> String): String`, a pure function in `SyncService.kt`.

- [ ] **Step 1: Write the failing test:**
  - `SyncNotificationTextTest.one wallet shows the app name only`: `notificationTitle("HoldFast", "Wallet 1", 1, ::fmt) == "HoldFast"`.
  - `SyncNotificationTextTest.several wallets name the active one`: `notificationTitle("HoldFast", "Savings", 2, ::fmt) == "HoldFast · Savings"`.
- [ ] **Step 2: Run** `./gradlew :app:testMainnetPlayDebugUnitTest --tests "*.SyncNotificationTextTest"`. Expected FAIL: unresolved function.
- [ ] **Step 3: Implement** as specified, with the strings in all 13 locales.
- [ ] **Step 4: Run** the same command plus `--tests "*.LocaleResourceParityTest" --tests "*.OnboardingHardcodedStringTest"`. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, message `feat(wallets): Hub, recovery phrase and sync notification name the wallet (13 locales)`.

### Task 15: Quick start for a restored wallet (display-only, opt-in)

**Files:**
- Create: `core/src/main/java/app/aroundtheblock/wallet/core/wallets/QuickStartStore.kt`
- Create: `app/src/main/java/app/aroundtheblock/wallet/ui/onboarding/QuickStartScreen.kt`
- Modify: `core/.../reconcile/DgbNodeClient.kt:119-121`. Add `fun customEndpoint(): String?`, the stored `custom_rpc_endpoint` trimmed, or null.
- Modify: `app/.../ui/onboarding/OnboardingViewModel.kt`. Add `fun runQuickStart(source: QuickStartSource)`. It runs `RecoveryScanService(utxoSource, profiles = DerivationProfile.BUILT_INS.filter { it.isNative }).scan(phrase, passCopy)` and keeps `Done.nativeResult?.totalSat` and `Done.digiDollar` in memory. `commitAdd` writes them to the new scope's `QuickStartStore` after `Added` and before the switch.
- Modify: `app/.../ui/onboarding/RecoveryDateScreen.kt`. In ADD mode, navigate to `QUICK_START_ROUTE` (replacing Task 12's interim `ADD_COMMIT_ROUTE`).
- Modify: `app/.../ui/wallet/WalletScreen.kt` and `WalletViewModel.kt`. Add `val unverified: StateFlow<QuickStart?>`. While it is non-null, render `quickstart_unverified` / `quickstart_unverified_sub` **below** the balance, never inside it. It clears once `dgb_sync_data<suffix>.has_synced` is true (`QuickStartStore.clearIfSynced`).
- Modify: `app/.../ui/navigation/AppNavigation.kt`. Add `composable(QUICK_START_ROUTE)`.
- Modify: `OnboardingHardcodedStringTest.COVERED`. Add `QuickStartScreen.kt`.
- Strings: `quickstart_*`, in 13 locales.
- Test: `core/src/test/java/app/aroundtheblock/wallet/core/wallets/QuickStartStoreTest.kt`
- Test: `app/src/test/java/app/aroundtheblock/wallet/ui/onboarding/QuickStartGateTest.kt`

**Interfaces:**
- Consumes: `RecoveryScanService` (`core/.../recovery/RecoveryScanService.kt:25-30,370`), `DgbNodeClient`, `WalletStores.prefsName("dgb_quickstart", scope)`.
- Produces:
```kotlin
enum class QuickStartSource { OWN_NODE, DIGISCOPE }
data class QuickStart(val unverifiedSat: Long, val unverifiedDdCents: Long?, val sourceLabel: String, val atSecs: Long)
object QuickStartStore {
    fun write(context: Context, scope: WalletScope, q: QuickStart): Boolean   // commit()
    fun read(context: Context, scope: WalletScope): QuickStart?
    fun clearIfSynced(context: Context, scope: WalletScope): Boolean          // true when it cleared
}
/** Which choices the screen offers. Own node only when one is set; DigiScope only as an explicit, disclosed choice. */
internal fun quickStartChoices(customEndpoint: String?): List<QuickStartSource>   // null → [DIGISCOPE]; set → [OWN_NODE, DIGISCOPE]
```
- **Nothing unproven reaches the send path or the balance.** `QuickStartStore` is read only by `WalletViewModel.unverified`. No balance, UTXO or send code reads `dgb_quickstart`.
- The screen's default action is `quickstart_skip`. With `OWN_NODE` it shows the host (`DgbNodeClient.redactUrl`-style, without credentials) and `quickstart_own_node_note`. `DIGISCOPE` shows `quickstart_digiscope_disclosure` as its own confirm step.
- **Endpoint routing:** `DgbNodeClient.endpoint()` already returns the custom endpoint when it is set. For `DIGISCOPE` with a custom endpoint set, the scan runs on a `DgbNodeClient` whose endpoint is forced to `DEFAULT_BASE_URL`. Add an internal constructor parameter `forcedEndpoint: String? = null`.

- [ ] **Step 1: Write the failing tests:**
  - `QuickStartStoreTest.a quick start is kept until the wallet has synced`. Write it for B; `read(B)` is non-null; set B `has_synced=true`; `clearIfSynced(B) == true` and `read(B) == null`.
  - `QuickStartStoreTest.a quick start is the wallet's own`: after `write(B)`, `read(legacy) == null`.
  - `QuickStartGateTest.no money path reads the quick start`. A source scan of `core/src/main/java` and `app/src/main/java` finds `QuickStartStore` and `"dgb_quickstart"` only in `QuickStartStore.kt`, `WalletStores.kt`, `WalletViewModel.kt`, `OnboardingViewModel.kt` and `WalletStoreEraser.kt`.
  - `QuickStartGateTest.own node is offered only when set`: `quickStartChoices(null) == listOf(DIGISCOPE)` and `quickStartChoices("https://n") == listOf(OWN_NODE, DIGISCOPE)`.
  - `QuickStartGateTest.nothing is sent before a choice`. In `QuickStartScreen.kt`, `runQuickStart(` appears only inside the `onClick` of the own-node button and of the DigiScope disclosure's confirm button.
- [ ] **Step 2: Run** `./gradlew :core:testMainnetDebugUnitTest --tests "*.QuickStartStoreTest"` and `./gradlew :app:testMainnetPlayDebugUnitTest --tests "*.QuickStartGateTest"`. Expected FAIL: unresolved classes.
- [ ] **Step 3: Implement** as specified, with the strings in all 13 locales.
- [ ] **Step 4: Run** both commands plus `--tests "*.LocaleResourceParityTest" --tests "*.OnboardingHardcodedStringTest" --tests "*.NetworkLeakTest"`. Expected PASS.
- [ ] **Step 5: Run the full suites.** Expected exit 0.
- [ ] **Step 6: Commit** the explicit paths, message `feat(wallets): opt-in quick start for a restored wallet from the user's node; display-only until sync proves it (13 locales)`.

### Task 16: Spec and documentation true-up

**Files:**
- Modify: `CLAUDE.md`, under "SharedPreferences Keys". Add `dgb_wallets`, the per-wallet suffix rule (`_<id>` before the network part; legacy unchanged) and `dgb_quickstart`. Under "Seed Security", add one line: per-wallet aliases `dgb_wallet_master_<id>`, legacy unsuffixed.
- Modify: `core/.../NetworkState.kt` KDoc. It names the stores that are shared and points at `WalletStores`.
- Modify: `docs/superpowers/specs/2026-11-16-multi-wallet-design.md`. Add a step-1 disposition banner recording the decisions this plan made where the spec was silent or the code differed (see "Ambiguities" below).
- Test: none. This task only changes documentation.

- [ ] **Step 1: Make the edits.**
- [ ] **Step 2: Run the full suites.** Expected exit 0.
- [ ] **Step 3: Commit** `CLAUDE.md`, `NetworkState.kt` and the spec, message `docs(wallets): step 1 store naming and decisions recorded`.

### Task 17: Device and emulator verification

Never use the Galaxy S25 Ultra. Phrases come only from `~/device-backups/seeds/`. Read them on the host when typing a restore. Never copy a phrase into this plan, the repo, a commit, a log excerpt or a report. `adb input tap` is ignored by the Compose UI, so use `adb shell input swipe X Y X Y 120`.

**Files:**
- Create: `test-results/multi-wallet-step1/<date>-<device>.md`. Record per check: pass or fail, wallet names, balances, txids **counts** only, and screenshots of non-secret screens.

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
  - Record its balance, its transaction count, its Hub handle (if signed in) and `adb shell run-as app.aroundtheblock.wallet.debug ls shared_prefs files databases` (names only).
  - Install the branch debug build with `adb install -r`, without uninstalling.
  - The check passes when the chip shows "Wallet 1", the balance and transaction count are identical, there is no re-sync from birth (logcat: no `CF-SCAN` floor reset, `has_synced` still true), and the `ls` listing has the same names plus at most `dgb_wallets.xml`.
- [ ] **Step 3: Restore every test wallet onto one device, each as its own wallet** (same device, after step 2):
  - for each phrase file in `~/device-backups/seeds/`, use Add wallet → Restore with the birth date recorded beside it (or "I don't remember");
  - name each wallet after its file's purpose;
  - restoring a phrase that is already present must show `wallet_add_duplicate` naming that wallet;
  - for each wallet, after it reaches Synced, its balance (DGB and DigiDollar), transaction count and asset count must match what that wallet shows on its original emulator or device. Record both figures side by side.
- [ ] **Step 4: Separation.** Switch through every wallet twice. Each time, check that the activity list, assets and DigiDollar rows are that wallet's own. In logcat, the sync of a wallet switched back to resumes near its saved tip and does not start from its birth.
- [ ] **Step 5: Cap.** If fewer than 8 wallets exist on mainnet, fill up to 8 with Add wallet → Create. The sheet then hides Add, and the add route shows `wallet_add_cap`. Remove one; Add comes back.
- [ ] **Step 6: Hub and Digi-ID identity differ per wallet.**
  - Sign in to the Hub in two wallets. Each Chat tab shows its own handle and `hub_signed_in_wallet` with its own wallet name. Switching shows the other account with no sign-in prompt.
  - Approve a Digi-ID login on the same test site from two wallets. The site sees two different addresses.
  - Open DigiStamp signed in from wallet A, switch to B, and open DigiStamp again: it is signed out.
- [ ] **Step 7: Removal.** Remove a non-active wallet: the others' balances and histories are unchanged after a restart. Remove the active wallet: the app restarts into the next wallet. Restore the removed wallet's phrase again and check that it comes back with its history.
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
  - `WalletManagerFactory`: Task 5;
  - `EraseReport`: Task 7;
  - `AddResult`: Task 8;
  - `RemoveResult`: Task 11;
  - `OnboardingMode`: Task 12;
  - `WalletRow` and `shownName`: Task 13;
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
