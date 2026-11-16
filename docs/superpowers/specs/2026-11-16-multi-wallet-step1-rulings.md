# Multi-wallet step 1: pre-flight and execution rulings

Companion to `2026-11-16-multi-wallet-design.md`. Plan-defect decisions only, one line each.

## Pre-flight rulings (F1-F32)

- F1: ActiveWallet's cache is keyed by `context.applicationContext` identity; `resetForTest()` is public `@VisibleForTesting`.
- F2: `storeRecordOnly` requires "no store of the scope left" (prefs, files, DB), aliases excluded (`eraser.storesLeft`); `anythingLeft` = stores or aliases.
- F3: `WalletSwitcher` compares against the id this process runs, not the registry's active id, so an add restarts into the new wallet.
- F4: Once every record is gone, the wipe deletes every wallet alias (legacy included); `seedGone` = no readable record and no such alias.
- F5: The eraser exposes `eraseRecord`, `eraseAliases`, `eraseStores`; the wipe runs each phase across all scopes (every record before any key).
- F6: Housekeeping drops a REMOVING entry only when the erase verified; the legacy registry object gains a `state` (absent = READY).
- F7: `markRemoving` moves the active id on every network where it names the removed wallet.
- F8: Removing the active wallet marks it REMOVING then restarts; housekeeping erases it at next launch before the DB or SyncService opens. No `stopActiveSession`.
- F9: In add mode the restore skips `recovery_scan`; the quick start is the only server option and carries the linking text.
- F10 (as modified by the owner): quick start choices are endpoint set -> [OWN_NODE, DIGISCOPE]; none -> [DIGISCOPE] only, behind an explicit opt-in screen that states the addresses are sent and that it links the user's wallets; skipping is the default.
- F11: Network-scoped and legacy-reading stores resolve through `WalletStores.prefsName(base, WalletScope.legacy(t))`; the suffix gate also matches `"<base>$` templates.
- F12: The parity set is the 4.1.4 eraser prefs set minus `dgb_log_tag` and `dgb_db_key`, plus `dgb_quickstart` and `dgb_price`; a reverse gate checks the legacy wipe reaches every per-wallet base.
- F13: `WalletManager` gets defaulted native and sleep seams and passes `this.scope` to every store call.
- F14: Test fakes updated for `KeystoreAliases`; a `FakeVault.asKeystoreAliases()` adapter is added.
- F15: Per-wallet prefs are cleared and then deleted; "left" means the file exists on a device.
- F16: Every restart goes through `AppRestart` (including both `ReconcileScreen` calls); the gate reads "AppRestart plus the wipe hand-over".
- F17: `AppRestart` calls `BootGuard.markBootHealthy` before ending the process, so quick switches do not trip crash-loop recovery.
- F18: Task 8 provides the provisioner (taking `WalletManagerFactory`) and the eraser; Task 11 provides `WalletRemoval`.
- F19: A public core factory `DgbNodeClient.digiScopeOnly(context, client)` replaces an internal constructor parameter.
- F20: The add-mode gate accepts `ADD_COMMIT_ROUTE` or `QUICK_START_ROUTE`.
- F21: Every onboarding route literal becomes `ONBOARDING_ROUTE` / `onboardingRoute(FIRST_RUN)`; the new routes join `fullScreenRoutes`; the `LockGatePolicy` edit is dropped.
- F22: The six hardcoded literals in `ProfileView.kt` and `SeedViewScreen.kt` are extracted before those files join `COVERED`; the notification text is built by a pure function.
- F23: Name resolution is testable through an internal constructor taking a secure-prefs factory; the watch set is tested by seeding the second wallet's prefs.
- F24: On `Duplicate`, `add` erases the scope and drops the entry, as for `Failed`; `commitAdd` maps a null `prepareSeedKey` to `CapReached`.
- F25: One scan routine, parameterised by service and destination, serves both the recovery scan and the quick start.
- F26: App tests mock `WalletRegistry`; no third prefs harness.
- F27: Task 3 counts corrected (19 files); the test updates follow F1.
- F28: Task 6's negative proof expects the database-name / snapshot failure.
- F29: `wallet_network` joins the single `storeSeedRecord` commit.
- F30: `recordsOnDisk` / `allWalletIds` are injected in tests; production lists `shared_prefs`.
- F31: `canAdd` counts PENDING entries; first-run restore clears the PIN only when no wallet record exists.
- F32: Device-test records hold "match" or "mismatch" and counts only; figures go to the private repo.

## Execution rulings

- The seed record's `wallet_network` is the string "mainnet" or "testnet", written only for non-legacy records (the legacy record stays byte-identical to 4.1.4 and serves both networks).
- `AppModule` builds `WalletManager` with `ActiveWallet.scope(context)`; factory-built managers never load or unlock a seed.
- Per-wallet resolution applies to `HistoryRebuildOnUpgrade` and `StaleDataWiper`; the eraser and rebuild use `cfBirthHeightKey(scope)`.
- `prepareSeedKey(testnet, origin)` takes CREATED or RESTORED; the origin drives the Digi-ID recovery offer.
- `WalletProvisioner.abandon(id)` erases a cancelled add's scope and drops its entry.
- Web-session isolation: the owner wallet id is persisted (`dgb_web_session_owner`); at launch, before any WebView exists, a mismatch with the active wallet clears WebView data and verifies, failing closed. An absent owner with only legacy present adopts legacy. A full wipe writes the sentinel owner `wiped`.
- Owed-wipe way out: decided on all-wallet records gone, after attempting every listed alias; an undeletable key with no record does not block release. Phase 2 deletes only listed wallets' aliases.
- `RemoveResult.NotRemoved` (nothing written) is distinct from `Incomplete` (REMOVING; housekeeping finishes); cancellation is rethrown; add and remove never run concurrently.
- The sync foreground notification is private on the lock screen with a public version showing the app name only.
- Quick start: own-node choice pins the endpoint it showed (`ownNodeOnly`); a capped scan shows no figure.
- Registry rename rejects another wallet's shown default name.
