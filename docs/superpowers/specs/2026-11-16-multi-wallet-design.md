# Multiple wallets in HoldFast Wallet — design

Status: for owner approval (2026-11-16); builder-checked. Step 2 amended 2026-11-17 with the owner's rulings (below). Follow-on, separate design: Coinomi-compatible Digi-ID.

> **Step 1 disposition (2026-11-16).** Plan: `docs/superpowers/plans/2026-11-16-multi-wallet-step1.md`.
> The pre-flight conflict scan found 32 defects in the plan against this spec; rulings **F1-F32**
> are recorded by number in `docs/superpowers/specs/2026-11-16-multi-wallet-step1-rulings.md`
> (F10 as modified by the owner: quick start offers DigiScope only as an explicit opt-in that
> states the addresses are sent and that it links the user's wallets). The spec text already
> carries the three rulings that changed a decided point and this banner does not restate them.
> Decisions made where the spec was silent or the code differed:
> - Legacy: the existing wallet is `legacy` (testnet unset: its record serves both networks, so
>   removing it removes both). A fresh install or a post-wipe first wallet is also `legacy`.
>   Every legacy store name is the 4.1.4 name. `wallet_network` is written only for non-legacy records.
> - Naming: `_<id>` goes before the network part; stores are classified WALLET, WALLET_AND_NETWORK
>   or NETWORK in `WalletStores`. `dgb_diag` is per wallet; `dgb_log_tag` per install.
>   `custom_rpc_endpoint` (in `dgb_reconcile`) stays device-wide: a legacy erase keeps that key.
> - Chain data and ChainTip are per wallet in step 1. A switch is a process restart.
> - Wipe: Settings -> Wipe Wallet wipes every wallet through one routine. The owed-wipe way out
>   decides on all-wallet records gone, after attempting every listed alias; a key with no record
>   does not block. Phase 2 deletes only listed wallets' aliases. "Last wallet" means the last on
>   the device, across both networks.
> - Removal: `RemoveResult.NotRemoved` (nothing written) vs `Incomplete` (REMOVING; housekeeping
>   finishes). Removing legacy first releases the DB password's legacy wrap. No concurrent add/remove.
> - Add: a cancelled add abandons its reserved slot; a duplicate cleans up its entry and alias.
>   Add-mode restore never clears the app PIN.
> - Web session: DigiStamp/WebView state is isolated by owner wallet at launch, before any WebView
>   exists (`WebSessionIsolation`, `dgb_web_session_owner`; a full wipe writes the sentinel owner
>   `wiped`; an upgrade with an absent owner adopts legacy rather than logging out). Fail closed otherwise.
> - Notifications: no incoming-payment notification exists, so the wallet name goes on the sync
>   foreground notification (localised, name read once off the main thread). It is private on the
>   lock screen, with a public version that shows the app name only.
> - Quick start: add mode only. Endpoint set -> [OWN_NODE, DIGISCOPE]; none -> [DIGISCOPE] behind
>   the opt-in screen above, skip being the default. An own-node choice pins the endpoint it
>   showed (`ownNodeOnly`, no fall-through to DigiScope). A capped scan shows no figure.
> - Rename rejects another wallet's shown default name.
> - Machine-written warnings in the non-English locales (wipe, removal, backup) need a native-speaker review
>   before release.

## Step 2 amendment (2026-11-17)

The owner's rulings for step 2, the defaults the lead took where the rulings are silent, and the
statements of this spec that proved false against the code (`develop` 52aa40000, core fc35768).
The Step 2 section and the lines named below are corrected in place. Where older text still
disagrees, this section wins.

### Owner rulings (Mark, 2026-11-17)

- **R1. Who builds the core.** This (Android) session builds the step-2 core engine and its new
  host KATs, as well as the bridge, app, Gradle and CMake work it owns anyway. That is an
  exception to `docs/development-process.md` ("Two sessions, one Android repo"), which gives new
  host-KAT work outside an adoption to the iOS-and-core session. This session writes a hand-off
  file for the iOS-and-core session under `docs/handoffs/`. (Unchanged by R1: the core PR goes
  first, with its host KAT, then the pins, rules 3, 4, 7 and 8; one registry PR at a time, rule 6.)
- **R2. One release.** Step 2 ships as one release: the shared engine and the in-process instant
  switch together. There is no 2a/2b split.
- **R3. Back-scan is a temporary band engine.** A lagging wallet (restored from an old birth date,
  inactive for more than about 5.7 days, given Coinomi accounts later, or rescanned on its own) is
  caught up by a second, temporary `BRPeerManager` that holds only that wallet. That is today's
  proven restore/resume path, over the wallet's own step-1 chain files. When the band's frontier
  reaches the shared frontier, the band hands the wallet to the shared engine: a hand-off rule, a
  quiesce, an attach under the shared lock, a rewind of the shared ledger to the band's frontier F
  if needed, and durability through an attach height in the registry. A wallet is never in two
  managers. One band runs at a time, and the device never holds more than today's 8 peer
  connections (the band's cap is 8 minus the shared engine's). The shared engine keeps ONE
  `cfLedger`, matched against the union of the attached wallets' elements. It has no per-wallet
  ledgers, so the 12 `LowestNeededHeight` sites, the roughly 520 `m->cfLedger` references in the
  host KATs and the 49 white-box suites stay unchanged. The design is in Step 2, "Scan ledger and
  back-scan bands".
- **R4. Public master keys at rest: approved.** Each wallet stores its PUBLIC master keys: the
  three of `BRWalletMasterKeys` (BIP84 `m/84'/20'/0'`, legacy `m/0H` "DigiByte seed", BIP86
  `m/86'/20'/0'`), plus Coinomi's BIP44 `m/44'/20'/0'` and BIP49 `m/49'/20'/0'` account keys when
  the wallet's `watchedAccounts` asks for them. They are encrypted at rest with an unbound Keystore
  key, like the database. No address lists. The record is written at create, restore and add, and
  at each wallet's next unlock as the active wallet, so existing wallets gain one lazily. Removing
  the wallet removes it, and so does the wipe.
- **R5. Seed lifetime unchanged.** The active seed stays resident until a switch or the wipe;
  locking does not zero it. The in-process switch zeroes the outgoing seed and loads the incoming
  one behind the app PIN, with the usual Keystore auth window.
- **R6. Server work for a wallet that is not active waits until it is active.** That is the asset
  receipt checks (`settleFromIndexer`), the server half of the parent walk, the transfer-rule
  refresh, metadata and IPFS, the confirmation reconcile and the post-upgrade reconcile. A wallet
  that is not active does on-device chain work only: CF matching, registration, heights, the gap
  limit, and local asset decoding and hold-out. What it touches is below.
- **R7. Release.** 4.1.5 ships multi-wallet step 1 once its gaps are fixed (separate PRs, in
  progress). PR #264 stays on `develop`, but nothing ships it until Mark has reviewed its seed and
  key changes (a summary is being prepared). Step 2 is not in 4.1.5.
- **R8. Security PRs wait for Mark.** A change of #264's kind (key material at rest, the seed swap
  on a switch) waits for Mark at merge, even with a green review and CI
  (`docs/development-process.md`, "Still Mark's, always"). The plan marks those PRs.

### Defaults taken by the lead (2026-11-17; the owner may override)

- **D1.** Watch-only wallets are attached only in a process the PIN has unlocked. That is today's
  behaviour, since no background start opens a wallet (`SyncWorker` retries when none is loaded),
  and it is like iOS ruling Q19 A.
- **D2.** The in-process switch restarts the process only if this process has created a WebView
  or used `CookieManager`. A new process latch records that; `DigistampWebViewHost.exists()`
  cannot, because it turns false after `destroy()`. Otherwise the switch re-runs
  `WebSessionIsolation` for the incoming wallet in-process, instead of the asynchronous cookie
  clear.
- **D3.** WebView `MULTI_PROFILE` is checked on the Note 8 and the Moto G 5G. The app has no
  `androidx.webkit` dependency today.
- **D4.** "Rescan this wallet" becomes a band for one wallet. "Repair the chain" is a near-tip
  recreate plus a full-locator getheaders. The engine rescan (`BRPeerManagerRescan`) gets no
  Android caller, and the post-first-sync rescan in `bridge_syncStopped` is deleted. See
  "Rebuild and rescan" below.

### Corrections against the code

1. **A watch-only wallet opens from stored keys, not from addresses** (Step 2, Bridge). The spec
   attached it "from its stored master public key and its pre-derived address set", and said the
   DigiDollar receive and Taproot keys need the seed.
   - The core opens a wallet from three public keys. `BRWalletOpen.h` says a platform may store
     `BRWalletMasterKeys` "to open the wallet -- and sync -- without reading the seed".
   - Every Taproot address derives from the BIP86 public key (`BRWalletSetTaprootKey`;
     `BRKeyTaprootOutputKey` tweaks a public key).
   - The DigiDollar receive key is the output key of the first external Taproot address,
     `m/86'/20'/0'/0/0`, as `getDigiDollarReceiveAddress` documents. Today's JNI reads the seed to
     get it, but the BIP86 public key gives the same value.
   - Gap extension derives from the keys as addresses are used, Taproot included, so a stored list
     could not grow. The wallet credits only addresses it derived ("credit if and only if
     derived", `BRWallet.c`).
   - For the plan: nothing serializes a `BRMasterPubKey` today (`BRBIP32SerializeMasterPubKey` and
     `BRBIP32ParseMasterPubKey` are stubs), and `g_mpk` holds only the BIP84 key and is never read.
2. **Locking does not zero the seed** (Step 2, Bridge). The spec kept the seed "while unlocked so
   background signing and DigiDollar keep working, zeroed by `lockSession()`".
   - The seed is copied in when the wallet is built and stays until the process ends. `onStop` and
     the inactivity lock call `lockUi`, which changes state only, and `WalletManager.lock()` has
     no caller.
   - `lockSession` (`seed_zero`) runs only in the wipe's quiesce, and the wipe then ends the
     process.
   - No background code reads the seed. Every native that does is reached only from a user action:
     a send, an asset send, a DigiDollar send, Digi-ID, Hub sign-in and chat, Recover funds, the
     Receive screen's DigiDollar address. `SyncService`, `SyncWorker` and `BootGuard` call none of
     them, and DigiDollar receipts are credited without the seed.
   - R5 keeps this lifetime.
3. **The network flag is not the core's only global** (Constraints). Also process-wide: the SOCKS
   proxy and the Tor-required flag (`BRPeer.c`), the manager lock-holder diagnostic
   (`BRPeerManager.c`), the filter-element stats (`BRWalletFilterElements.c`), the
   address-generation counter (`BRWallet.c`), the coin-claim table (`BRCoinReservation.c`), the
   log-tag key (`BRCrypto.c`) and the header-check counters (`BRMerkleBlock.c`). Only the
   filter-element stats belong to a wallet: one slot, for the last wallet that built elements.
   None stops a wallet set or a band beside the shared engine. The lock-holder slot is a
   diagnostic, and each manager also keeps its own copy.
4. **Seven bridge files use `g_wallet`, not about 20** (Constraints). In code: `jni_wallet.c`,
   `jni_transaction.c`, `jni_asset_send.c`, `jni_asset.c`, `jni_wallet_sign.c`, `jni_peer.c` and
   `jni_transaction_persist.c`. `jni_bridge.h` declares the globals; `jni_derive.c` and
   `wallet_spend_gate.h` name `g_wallet` only in comments. Also, the 69 lines of `BRPeerManager.c`
   that name `manager->wallet` call 19 distinct `BRWallet` functions, not about 25.
5. **The Send screen already has a MAX, for DigiDollar** (Found on the device). `send_max` is used
   by `AssetSendScreen` and by `SendScreen` in DigiDollar mode (`setDdAmountToMax`). DGB mode has
   none, so the DGB MAX is still needed.
6. **The chain is not shared yet** (Step 2, scan ledger). The spec said "shared chain state stays
   shared". Since step 1 the saved blocks, the filter-header chain, the CF ledger, the saved
   transactions and `dgb_sync_data` (which holds ChainTip) are per wallet (`WalletStores`,
   WALLET_AND_NETWORK). Step 2 makes the chain shared through its migration.
7. **The engine's callbacks cannot name a wallet through `info`** (Step 2, Bridge). They take one
   `info` for the whole manager (`BRPeerManagerSetCallbacks`, `BRPeerManagerSetSaveFilterHeaders`,
   `BRPeerManagerSetSaveCFLedger`), and the bridge passes `NULL`. Wallet-level callbacks can:
   `BRWalletSetCallbacks` takes its own `info` for each wallet, but the bridge never calls it.
   Under R3 the shared ledger belongs to no wallet, so its save names none, and a band's saves go
   to the band's own context.
8. **The Constraints section describes the code before step 1.** Step 1 replaced
   `networkSuffix()` with `storageSuffix` (`WalletScope.kt`) and added the removal of one wallet
   (`WalletRemoval`).
9. **Outside this file, `CLAUDE.md` says the rescan pins the priority peer through
   `BRPeerManagerSetFixedPeer`.** No production code calls it, and the bridge's own comment in
   `bridge_syncStopped` says not to. The first step-2 PR that changes the rebuild corrects
   `CLAUDE.md` in the same PR.

### Server work for a wallet that is not active (R6)

Today each process builds its objects for the one wallet it runs, so none of this runs for any
other wallet. Step 2 gives every attached wallet a session. Without a gate, each of these would run
for every wallet on the same ticks, and link the wallets at the server by IP address and time:

- the receipt check, `settleFromIndexer` (api.digiscope.me);
- the parent walk's fetch, `fetchRawTransactionBytes` (assets.digistamp.co, then
  api.digiscope.me). While the wallet is not active the walk uses its own copies only; a parent
  it lacks is `Unavailable`, and the walk keeps its place;
- the transfer-rule refresh, `refreshRules` (assets.digistamp.co, then api.digiscope.me);
- metadata, through `IpfsClient` and the provider fallback (api.digiscope.me, then public IPFS
  gateways);
- the confirmation reconcile, `maybeRunConfirmationReconcile` (the custom endpoint, or
  api.digiscope.me);
- `PostUpgradeReconciler`.

Two changes go together:

- **A handle.** Every native call on these paths names its wallet. Today none does, and each one
  reads `g_wallet`. Without the handle, a non-active wallet's asset pass reads the active wallet's
  native state: it writes that wallet's rows into its own database, and prunes its own rows,
  because the owned set comes from `dumpAllAddresses`.
- **A gate at the call that sends.** A handle alone does not stop the requests:
  `retryMissingAssetMetadata` picks its assets from Room.

This is safe for funds. An unchecked asset row never counts and is never offered for sending. An
asset whose issuance has not been walked reads UNKNOWN, and the transfer-rule gate refuses
UNKNOWN. A watch-only wallet cannot spend. The pre-spend pass makes no network call.

The cost: until a wallet is active, its asset receipts from other people stay uncounted and its
assets may stay unnamed. A DigiDollar receive that only the reconcile would confirm keeps its
credit withheld, so the switcher's DigiDollar figure for that wallet can read low. The first sweep
after a switch runs what waited.

Not covered by R6, and open for Mark: the Hub revokes. Removing a wallet that is not active
revokes its session, and the wipe revokes every wallet's session in one pass (step 1).

### Rebuild and rescan (D4)

Today:

- The user's "Full rebuild from chain" (`WalletManager.rebuildFromChainRescan`) stops the whole
  engine, clears the chain stores (`clearTransactionCacheForRebuild`: saved blocks, filter
  headers, the CF ledger, the abandonment record) and restarts the app. The wallet then climbs
  from its birth.
- `BRPeerManagerRescan` moves `lastBlock` back to the birth checkpoint. Its production callers are
  the post-first-sync rescan in `bridge_syncStopped`, written for bloom sync and suppressed by
  `markInitialSyncDone` only on a synced resume, and `NativeBridge.rescan`, called from
  `WalletManager`'s create and restore.
- On a shared engine both would cost every wallet. The clear makes the shared chain climb again
  (the class `CLAUDE.md` measured at 24,052,509 to 22,650,000, about 6 hours), and the rescan
  rewinds every wallet's height with `lastBlock`.

In step 2:

- **Rescan this wallet**, and its from-block form. The wallet leaves the shared engine; only its
  own history, ledger, abandonment record and outgoing store are cleared; it runs as a band from
  its floor. No chain store is cleared, the engine keeps running and the app does not restart.
- **Repair the chain.** The shared engine's near-tip recreate (`ACTION_RECREATE_NEAR_TIP`,
  `recreatePeerManagerResumingNearTip`), then a full-locator getheaders
  (`rerequestHeadersFromTip`). It keeps the scan ledger and the saved block window, and does not
  restart the app.
- **The engine rescan** has no Android caller. The post-first-sync rescan, `markInitialSyncDone`
  and `NativeBridge.rescan` go (a registry PR). Create and restore attach the new wallet, at the
  tip or as a band. `BRPeerManagerRescan` stays in the core for one-wallet managers.
- **The same split wherever a chain store is cleared for one wallet:**
  `clearTransactionCacheForRebuild` (shared with `HistoryRebuildOnUpgrade`), `clearSyncData`, the
  CF restore preflight and `StaleDataWiper.wipeSyncBlobs`. Each becomes a per-wallet clear and a
  separate chain clear. A per-wallet reason (`LOWER_FLOOR_TO`, `TRANSACTION_CHECKPOINT_SHORT`,
  `MISSING_TRANSACTION_CHECKPOINT`, the `HistoryRebuildOnUpgrade` floor, one wallet's
  `WALLET_RESET`) becomes a band for that wallet. Removing a wallet, legacy included, never
  deletes the shared chain.
- **The order of steps is core.** `BRChainRebuild.h` gains the per-wallet rescan and the repair
  as new sequences with their KATs. Its existing steps stay until both apps' pins carry the new
  ones. The adoption PR moves the Android-source tokens that `scan_floor_kat` checks.
- **Text.** The "Full rebuild from chain" heading is a hardcoded English literal in
  `ReconcileScreen`, and `reconcile_rebuild_body` promises a restart and a rescan from birth. The
  new actions' text replaces both, in all 13 locales.

### What follows for the plan

- **Order against work in flight (2026-11-17):**
  - Coinomi step 1, the core `BRDigiId.h` root (branch `feat/digiid-site-key-root`), lands first,
    under the owner's dispatch-run ruling for rule 7. The core identity-choice header follows it.
  - `watchedAccounts` is defined by the Coinomi design as amended on PR #267, which is not merged
    yet. Q4 A is PR #268. Coinomi's step 4 (watching `watchedAccounts`, and "Also watch Coinomi's
    accounts") ships with this step 2.
  - A release is cut from `develop` (`RELEASING.md` §2) and step 2 is not in 4.1.5 (R7), so no
    step-2 slice that changes what the app does reaches `develop` before 4.1.5 is cut.
  - Draft PR #150, the prepared-send adoption, is redone from `develop` before MAX, so MAX changes
    one copy.
  - `jni_registry_gen.h` changes in one PR at a time (rule 6).
- **Waits for Mark at merge (R8):** the key record and its Keystore key (R4), Coinomi's account
  keys included; the bridge's seed-owner rule and the seed-only load; the in-process switch.
- **Open for Mark:** what a switch does when the incoming seed's keys differ from the wallet's key
  record (refuse it, or replace the record); the Hub revokes (above).
- **The hand-off file (R1)** states the core API step 2 adds, its KATs, and whether the key record
  becomes a core format (versioned, with a KAT) or stays platform storage.


## Intent

The owner keeps several DigiByte wallets for different purposes. For example, one is for Digi-ID
sign-ins and DigiAssets, and another is for DigiDollar and large DGB balances. The app should hold
several wallets at once, each with its own recovery phrase. The user names them, switches between
them, adds them (create or restore) and removes them. Purposes are informal: the user decides what
each wallet is for, and the app does not enforce them.

**Every wallet is fully featured:** DGB, DigiAssets, DigiDollar, Digi-ID, the Hub, the own node,
Dandelion and Tor. Wallets differ only in how they are used.

## Goals

- N wallets on one install, each with its own recovery phrase (and optional passphrase), name and
  history.
- All wallets stay synced. Switching is instant once step 2 ships.
- **One PIN for the app** ("one PIN to rule them all"). The same unlock and spend authentication
  cover every wallet.
- **One compact-filter sync engine for all wallets.** Each block's filter is downloaded once and
  matched on the device against the union of every wallet's scripts.
- Add a wallet by **create** or **restore**, rename it, remove it, and see each wallet's balance in
  the switcher.
- **Restore defaults to a compact-filter back-scan** from the wallet's birth date, while the other
  wallets keep running at the tip.
- **Optional quick start for a restored wallet from the user's own paired node.** It reads the UTXOs
  and shows the balance in seconds, while the back-scan proves the history.

## Non-goals

- Coinomi-compatible Digi-ID: per-site SLIP-0013 identities, `m/13'/…`. It has its own design next.
  Coinomi wallets may also hold funds on BIP44 legacy paths (`m/44'/20'/…`), which today's wallet
  does not watch. That design decides it.
- Moving funds between your own wallets in one tap. A normal send to the other wallet's receive
  address works. An "own wallets" shortcut in the recipient picker is a nice-to-have, not in scope.
- Per-wallet PINs or per-wallet lock policy.
- iOS. The shared core change must not break the iOS build, but the iOS UI is not in scope.

## Anti-goals (a failure even if it works)

- **No wallet's address set leaves the device by default.** Restore never calls a server lookup
  unless the user explicitly chooses it on a screen that says the addresses go to that server.
  It must also say that this links the wallet to the user's other wallets.
- **No cross-wallet linking by our own services.** Each wallet has its own Hub session and
  Digi-ID identity. Nothing sends two wallets' identifiers in one request. One sync engine is fine:
  compact filters reveal nothing about any wallet.
- **Upgrading never touches the existing wallet's data.** The existing wallet keeps every file name,
  prefs key and Keystore alias it has today. See *Storage* below.
- **Removing one wallet never deletes another wallet's data.** The only exception is the PIN-failure
  wipe, which removes everything; see below.
- No wallet is ever shown another wallet's transactions, assets, DigiDollar outputs or Digi-ID
  history.

## Constraints from the codebase (recon 2026-11-16, develop)

This is the code before step 1. Step 1 replaced `networkSuffix()` with `storageSuffix` and added
the removal of one wallet. Three lines were corrected on 2026-11-17 (see the amendment).

- **The C core is instance-based.** `BRWalletOpenWithKeys` and `BPPeerManagerMainNetNew(wallet, …)`
  create instances. Besides the network flag in `BRNetwork.c` the core has a few process globals,
  and none of them stops a wallet set or a second manager (corrected 2026-11-17).
- **The peer manager takes exactly one `BRWallet*`.** As `manager->wallet` it is named on 69 lines,
  which call 19 distinct functions (`BRWalletTransactionForHash`, `BRWalletUnusedAddrs`,
  `BRWalletSetBlockHeight`, `BRWalletRegisterTransactionOutcome`, `BRWalletGetFilterElements`, …)
  (corrected 2026-11-17).
- **The JNI bridge is all process globals.**
  - `jni_wallet.c`: `g_wallet`, `g_peerManager`, `static g_seed[64]`, `g_mpk`, the creation time and
    the rwlock.
  - `jni_peer.c`: `g_savedBlocks*`, `g_savedPeers`, the status mirrors and the sync flags.
  - Callbacks are registered with `info=NULL` and read the globals.
  - Seven JNI `.c` files use `g_wallet` / `WALLET_READ_GUARD` in code (corrected 2026-11-17).
- **Stores are namespaced by network only, through `NetworkState.networkSuffix()`** (about 50 call
  sites). They cover `wallet<net>.db`, `dgb_sync_data`, saved blocks, filter headers, the CF ledger,
  saved transactions, abandonment, history rebuild, reconcile, peer caches, ChainTip, CustomNode and
  DisplayCurrency.
- **Some stores are not namespaced at all:** `dgb_watched_addrs`, `dgb_outgoing_tx`,
  `cf_birth_height` (in `dgb_settings`), `dgb_asset_backfill`, `dgb_asset_heal`,
  `dgb_hub_session` / `dgb_digiscope`, and the DB key `dgb_db_key` / alias `dgb_db_passphrase`.
- **Room has no wallet column,** and `WalletDatabase` is a Hilt `@Singleton`.
- **The seed is a single record.** It lives in the `dgb_wallet_seed` prefs and the Keystore
  `dgb_wallet_master` (`_v2` for the auth-bound key). `KeyStoreManager` already takes an alias.
  `hasSavedWallet()` means that record exists.
- **The PIN is per app** (`dgb_pin_store`). That matches the owner's one-PIN decision.
- **The wipe (`WalletManager.wipeWallet`) erases every store of every network** and then kills the
  process. Nothing today removes one wallet.
- **Precedent for switching: the mainnet/testnet toggle.** It persists a selector in `dgb_settings`
  and applies it by restarting the process (`SettingsViewModel.setNetworkTestnet`). An in-place
  network switch was rejected there.
- **Precedent for a non-active seed:** `jni_derive.c` derives and signs statelessly for a seed that
  is not `g_wallet`'s (Recover Funds sweep).
- **Invariants that must survive:**
  - the recreate-near-tip sequence (`RecreateSequence`, `CfRecoveryPolicy`,
    `flushLiveStateBeforeRecreate`);
  - the DigiAsset counting rule ("a row counts iff the native wallet still holds that exact output");
  - the transfer-rule gate;
  - seed handling: `ByteArray` paths, zeroing, and `g_seed` access only through the accessor API;
  - the 13-locale rule for every string.

## Approaches considered

| | Approach | Trade-off | Decision |
|---|---|---|---|
| A | **One sync engine watching every wallet**, all wallets loaded, instant switch | Core peer manager holds a wallet set; bridge becomes per-wallet; storage per wallet. Biggest build, best result | **Chosen by the owner**, built in two steps |
| B | One active wallet, switch by process restart (network-toggle pattern) | Cheapest; inactive wallets fall behind; switch takes seconds | Rejected as the end state; it is step 1's interim switching mechanism |
| C | One sync engine per wallet | N× peers, data and battery for no gain over A | Rejected. A temporary one-wallet band for back-scan is allowed (R3, 2026-11-17) |

## The design (decided)

### Step 1: storage, the wallet list and switching (shippable on its own)

**Wallet registry.**
- Stored in a new, un-namespaced prefs file `dgb_wallets`.
- Each entry holds:
  - `id`: an opaque random 16-hex string;
  - `name`;
  - `network`;
  - `createdAt`;
  - `origin`: created or restored;
  - `order`.
- The file also records the active id per network.
- The existing wallet becomes the first entry, with the **reserved id `legacy`**. Its default name is
  "Wallet 1", localised.

**Storage namespacing.**
- `networkSuffix()` is generalised to a wallet-plus-network suffix. For the `legacy` wallet the
  suffix equals today's network suffix, so **no existing file, key or alias is renamed**.
- Each new wallet gets `_<id>` before the network part.
- The un-namespaced per-wallet stores listed above become per-wallet the same way. The legacy
  wallet keeps their current names.
- **Per wallet:**
  - the Room DB file, same DB passphrase key;
  - the seed record;
  - the Keystore aliases: `dgb_wallet_master_<id>` and `_v2`, with the legacy wallet keeping its
    unsuffixed aliases;
  - watched addresses, outgoing txs, birth height, the asset backfill/heal state, the CF ledger
    (from step 2 only a band's, until its hand-off; R3), saved transactions, abandonment,
    reconcile and history rebuild;
  - the Hub session and Digi-ID history;
  - from step 2, the public key record (R4).
- **Shared** (chain data from step 2 on; in step 1 it is per wallet, see below):
  - chain data: filter headers, block checkpoints, ChainTip and, from step 2, the CF scan ledger
    (R3);
  - peer caches;
  - display currency, price settings, language;
  - the own-node pairing;
  - Tor and Dandelion settings;
  - the PIN store.

**Chain data in step 1 is per wallet (decided, builder check 2026-11-16).**
- In step 1, saved blocks, the filter-header chain, the CF ledger and ChainTip (it lives in
  `dgb_sync_data`) stay per wallet, under the wallet suffix. Each wallet syncs on its own while it is active. A wallet switched back to
  catches up from where it stopped, as the app does today after days closed. A new wallet starts
  from its birth checkpoint, as a restore does today.
- So `clearSyncData()` runs only for the new wallet's own stores. It never clears another
  wallet's stores.
- Step 2 merges chain data into one shared chain (amended 2026-11-17, R3). Step 1 ships first
  (R7), so the migration starts from up to 8 per-wallet chains per network. One of them seeds the
  shared engine. Every other wallet joins it by the hand-off rule (Step 2 below): at once when the
  rule already holds, otherwise as a band from its own step-1 files, which it keeps until its
  hand-off. A wallet with no key record (R4) waits for its next turn as active. No wallet's ledger
  or chain files are deleted before its hand-off is durable.

**Switching in step 1.**
- Persist the new active id and restart the process, as the network toggle does.
- The app comes back to the unlock screen. Unlocking is still one PIN.

**Add a wallet.** From the switcher: *Create new* or *Restore*.
- Both reuse the onboarding screens with a mode that skips PIN setup.
- **Fix:** `pinSetupCreatesWallet` and the seed-fingerprint "clear sync data" check must not fire
  for an added wallet.

**Restore.**
- The wallet asks for the birth date, as today, and back-scans with compact filters from that date.
  This is the default and is private.
- **Quick start (decided, builder check 2026-11-16; amended at pre-flight F10).** Own-node pairing
  (`dgbnode://`) is P2P only and cannot look up UTXOs by address. So the own-node quick start uses
  the existing custom endpoint setting `custom_rpc_endpoint` (the "Use my own node" field of Scan
  for missing funds), and is offered only when that setting is set. With it set, the choices are
  the own node and digiscope.me; without it, digiscope.me only. Skipping is the default and one
  tap.
- **The quick-start balance is display-only,** labelled "not yet verified". It is not spendable
  and is not counted in the wallet's balance until the compact-filter back-scan proves the
  outputs. This keeps the counting rule ("a row counts iff the native wallet holds that exact
  output"), and nothing unproven reaches the send path.
- digiscope.me is offered only as an explicit opt-in. Its screen says the wallet's addresses are
  sent to digiscope.me and that this can link the wallet to the user's other wallets. This is the
  same disclosure family as "Scan for missing funds".

**Remove a wallet.**
- The user confirms by typing the wallet's name, after a warning to back up the phrase first.
- Removal erases only that wallet's per-wallet stores and Keystore aliases.
- Removing the **active** wallet marks it as being removed, makes another wallet active and
  restarts. The next launch erases it before the database or the sync service opens, so the live
  database is never deleted under the running process (pre-flight F8).
- Removing the last wallet is the existing full wipe and fresh start.

**One PIN.**
- The unlock, the spend gate, the 300 s Keystore auth window and biometrics are unchanged and
  app-wide. Each wallet's `_v2` key is bound the same way.
- **The PIN-failure wipe after N wrong PINs removes every wallet.** There is one PIN, so there is one
  thing to guess. The Security screen says so.

**Switcher UI.**
- The wallet name sits at the top of the Wallet tab as a chip. Tapping it opens a sheet with each
  wallet's name and last-known balance (DGB plus DigiDollar), *Add wallet*, and *Manage*
  (rename, reorder, remove).
- Notifications for incoming payments name the wallet.
- Settings → Security → View recovery phrase applies to the active wallet and names it.
- **The maximum is 8 wallets per network.**

**Digi-ID and the Hub.**
- Each wallet signs with its own identity, the same `m/0'/0/0` derivation as today, from its own
  seed.
- Each wallet has its own Hub session. Switching wallets switches the Hub account. The chat shows
  which wallet it is signed in as.

### Step 2: one sync engine for every wallet, instant switching

Amended 2026-11-17: see "Step 2 amendment" above. Step 2 ships as one release, the shared engine
and the instant switch together (R2).

**Core.**
- `BRPeerManager` holds a set of wallets, attached and detached at runtime.
- Each wallet-facing call becomes "for each wallet":
  - relevance and ownership: a transaction is registered with every wallet that owns an input or
    output;
  - block height updates go to all wallets;
  - filter elements are the union of every wallet's elements;
  - each wallet's gap-limit extension runs on its own.
- Publishing a transaction goes through the wallet that built it.
- The existing single-wallet constructors stay as one-wallet sets, so iOS and the KATs keep working.
  A band (below) is one of these one-wallet managers.
- The core change only adds functions, structs and formats. It changes none that iOS uses, so iOS
  builds as it is. A change to what a one-wallet manager computes ships in neither app until both
  pins carry it (`docs/development-process.md`, rule 4).
- Growth in any attached wallet invalidates the union element cache and starts the gap rescan,
  which rewinds the one shared ledger. An attach and a detach invalidate the cache too. Today the
  cache key and the gap-rescan count are read from `manager->wallet` alone, and the buffered drain
  builds its own element set from it. No gate on `develop` proves that the cache invalidates: no
  KAT builds its `CF_ELEMS_CACHE_*` red arms.

**Scan ledger and back-scan bands (amended 2026-11-17, R3).**
- **One forward ledger, union-matched.** The shared engine keeps one CF scan ledger, `cfLedger` as
  today, and matches each filter against the union of the attached wallets' elements. At one
  frontier every ledger event is the same for each wallet: a miss on the union is a miss for all
  of them; a match fetches the block, and each wallet takes what it owns; holes, reorg rewinds and
  abandonment are per height. The union costs only liveness: one wallet's matched block holds the
  frontier for all until it arrives.
- **No per-wallet ledgers on a shared cursor.** A ledger advances only over requests that were
  contiguous for it (`_cfLedgerAdvance`), so one cursor feeding several ledgers would carry each
  past heights never evaluated for its wallet: a silent missed receive. The convoy, retention,
  the re-anchor and C-1 also key on a single frontier (the 12 `LowestNeededHeight` sites).
- **Why a band.** A manager evaluates a height only inside its resident block window and its
  filter-header chain, and both grow forward only. After a resume the shared engine holds about
  the last 32,768 blocks (`SAVE_BLOCK_COUNT`, about 5.7 days at 15 s), and a ledger restored below
  that is marked abandoned ("resume frontier below saved block window"). A wallet further behind
  needs a manager whose window starts at its own frontier.
- **The band** is a second, temporary `BRPeerManager` holding only the lagging wallet. Today's
  restore/resume path builds it from that wallet's own step-1 chain files (saved blocks, filter
  headers, ledger, `dgb_sync_data`), which the wallet keeps until its hand-off. Bands catch up:
  - a restore whose birth is below the shared window;
  - a wallet that has fallen behind the shared window (inactive for more than about 5.7 days);
  - a synced wallet whose Coinomi accounts are added;
  - "Rescan this wallet".
- A wallet created on this device starts at the tip and attaches directly. A restore with no other
  wallet on its network needs no band: the shared engine starts at its birth, as a restore does
  today. A wallet with no key record yet (R4) can be opened only from its seed, so it waits,
  unsynced, for its next turn as active.
- Each wallet is in exactly one place: attached to the shared engine, held by the band, or not
  attached yet (waiting for the band, or for its key record).
- **The hand-off rule** is a core function with a host KAT, because it decides what the wallet
  holds.
  - **Trigger.** F is the band's lowest needed height, `max(scannedThrough + 1, abandonedBelow)`,
    so it never passes a hole. Hand off when F is at least H_min and at least S - 1000. H_min is
    the highest of the shared engine's block floor, filter-chain start, ledger start and
    `abandonedBelow`. S is the shared ledger's lowest needed height. 1000 is one getcfilters
    batch (`MAX_CFILTERS_RESULTS`), so a rewind holds back the other wallets' tip matching by one
    batch at most.
  - **Quiesce.** Flush the band's blocks, filter headers and ledger to the wallet's files. Stop
    routing the wallet's calls to the band. Disconnect and free it. The wallet is attached only
    once the band manager is gone (`BRPeerManagerFree` returned 1, or its deferred free has run),
    because a parked manager's threads can still reach its wallet. No publish may be in flight in
    the band, since freeing it fails them: wait, or publish again through the shared engine.
  - **Attach, in one critical section under the shared manager's lock.** Re-check H_min. Require
    the shared chain's block at F - 1 to be the band's; otherwise refuse, and the band resumes.
    Add the wallet to the set and re-key the union element cache. Pre-generate its address
    window. Set its height to the shared tip. If F is at or below the shared ledger's requested
    height, rewind the shared ledger to F and its fetch cursor to F - 1. That is the shape the gap
    rescan already uses (`_BRPeerManagerCFRescanAfterGrowthLocked`, gated by `cf_gap_rescan_kat`).
  - **Durability.** Persist the shared ledger at once; its writer otherwise coalesces for 20 s.
    Write the wallet's attach height F to the registry, per wallet and network (legacy serves
    both), before its band files are deleted. On a cold start, rewind the restored shared ledger
    to any recorded F whose band files still exist. Then delete the wallet's chain files. Its
    abandonment record stays.
  - **Exclusive hold.** A wallet is never in two managers. Growth in a wallet's address set is
    noticed only by the manager that delivered the block, so a wallet held by two would miss its
    own receives. Moving an attached wallet into a band (Coinomi accounts, "Rescan this wallet")
    detaches it under the lock first and leaves the shared ledger alone. The bridge stops routing
    the wallet's calls before the detach, because the lock alone does not stop every use
    (`BRPeerManagerApplyServerHeight` reads the wallet before it takes the lock). A detach also
    drops the wallet's entries from the publish list, the one manager structure that points into
    wallet memory.
  - **Peers.** One band at a time. The device never holds more than today's 8 connections
    (`PEER_MAX_CONNECTIONS`): the band's cap is 8 minus the shared engine's. With today's caps (8
    while catching up, 3 once stably synced) a band gets 5 while the shared engine is stably
    synced, and pauses while it catches up. A band follows the shared engine's peer policy, Tor
    (process-wide in the core) and the own-node pin included, so a user who pinned their own node
    gets no other peer through a band. Like a restore today, it sends peers heights and block
    hashes, never addresses.
- **Abandonment stays per wallet** (`dgb_cf_abandonment` already is). An abandonment on the shared
  ledger is written to the record of every wallet attached at that moment, and a band's to its own
  wallet's record.
- **Recovery.** The shared engine runs recreate-near-tip, re-anchor and reorg rewind on its one
  ledger, as today. A band runs them as a one-wallet manager does today, over its own files.
- **Costs.** A banded wallet is not matched at the tip until its hand-off, as in a restore today;
  on a restore the opt-in quick start can show a balance meanwhile. A band downloads its own
  headers and filter headers, and a hand-off rewind fetches up to 1000 filters again.
- **Approach C, bounded.** A band is the one place where two managers run: temporary, one wallet,
  inside today's peer total.

**Bridge.**
- The globals move into a per-wallet context struct (the wallet, its public master keys and its
  creation time) plus a sync context per manager: the shared engine's, and the band's while one
  runs (peer manager, saved blocks, peers, status). Each sync context has its own guard, so a
  band's teardown (its disconnect can wait 5 s) never holds the guard the shared engine needs.
- The seed stays one static buffer in `jni_wallet.c`, reached only through the accessor API and
  owned by the active wallet. A sign or derive for any other wallet is refused.
- Each manager's callbacks carry its sync context as `info`. Per-wallet events can carry the
  wallet's context through `BRWalletSetCallbacks`, which takes an `info` of its own (corrected
  2026-11-17).
- JNI calls take a wallet handle (the registry id resolved to a context).
- **Keys in memory (owner decision 2026-11-16, option 2; amended by R4 and R5, 2026-11-17): only
  the ACTIVE wallet's seed is resident.**
  - The active wallet's seed lives in the process exactly as today: copied in when the wallet is
    unlocked, and kept until a switch or the wipe. Locking does not zero it (R5).
  - Every other wallet syncs **watch-only**. It is opened from its stored public master keys
    (`BRWalletOpenWithKeys`), with its saved transactions, its creation time (a plain value in its
    seed record) and its watched-address pins, and it can never sign.
  - The key record (R4) holds the three keys of `BRWalletMasterKeys`, plus Coinomi's two account
    keys when `watchedAccounts` asks for them. It holds no addresses: the Taproot chains, the
    DigiDollar receive key and gap extension all derive from these keys. Coinomi's account paths
    are hardened, so those two keys need the seed once, when the accounts are added.
  - The record is written at create, restore and add, and at the wallet's next unlock as active,
    so a wallet from before step 2 gains one then. It is public-key data, encrypted at rest with
    an unbound Keystore key, like the database. It can watch addresses but cannot move funds.
  - It is a `WalletStores` WALLET store, so removal and the wipe reach it. It lives outside
    `dgb_sync_data`, which `clearSyncData` clears whole, and outside the Room database, which can
    be set aside. If it gets a Keystore key of its own, the eraser and the wipe's read-back list
    that alias too.
  - Switching wallets zeroes the outgoing seed and loads the incoming one behind the app PIN,
    with the usual Keystore auth window (R5). The switch loads only the seed: the incoming wallet
    is already attached from its keys, so nothing rebuilds it or the engine. Today's
    `restoreWalletFromBytes` rebuilds `g_wallet` and arms a manager recreate, so the switch needs
    a seed-only load.
  - Spending, Digi-ID approval, sweeps and Hub sign-in happen only from the active wallet.
  - Exposure stays at one resident seed, as today.

**App.**
- A wallet session holder replaces the `@Singleton` database. It exposes per-wallet repositories.
- SyncService runs the shared engine with every wallet at the shared frontier attached, and at
  most one band. Wallets are attached only in a process the PIN has unlocked (D1).
- Each wallet's asset-outpoint exclusions are replayed into its own native wallet when it is
  opened, since they do not survive a restart, and before a switch makes it spendable.
- ViewModels observe the active wallet id and rebind without a restart. A switch restarts the
  process only when this process has created a WebView or used `CookieManager` (D2).
- Background sync (SyncWorker) catches up the wallets attached in its process, and attaches none
  itself (D1). Today a worker catch-up after the service has died persists only the active
  wallet's transaction checkpoint, because the block and ledger saves run on the dead service's
  scope. The worker needs a persistence path of its own for the shared engine.
- BootGuard and the recreate sequence operate on the shared engine. A band recovers through the
  same sequence over its own files.
- Server work for a wallet that is not active waits until it is active (R6; see the amendment).
- Removing a wallet takes it out of its manager and stops its writers before its stores are
  erased. It never deletes the shared chain, legacy's removal included.

**Found on the device in step 1 (owner, 2026-11-16 sweep of a retired test wallet on the Note 8).**
- **The DGB Send screen needs MAX (send-all).** DGB mode has none: the `send_max` string is used
  by `AssetSendScreen` and by the Send screen's DigiDollar mode only (corrected 2026-11-17).
  Emptying a wallet means working out balance minus fee by hand, and an amount typed by eye leaves
  change behind in the wallet being emptied (the sweep took two sends because of it). MAX spends
  every spendable output to one output with no change, the fee taken from the amount. No native
  exposes `BRWalletMaxOutputAmount`, and it sizes a send with two outputs, so the send-all builder
  is core work with its host KAT ("Funds in the core"). Moving funds out of a wallet before
  removing it is now a normal multi-wallet task, so this belongs with step 2 even though it is not
  engine work.
- **A switch must not look like a re-sync.** In step 1 a switch restarts the process, and the
  switched-to wallet shows "Scanning… 99% · N blocks remaining" while it catches up the blocks
  since it was last active (717 in the test, done in seconds). That is a resume near the tip
  (`syncedResume=true`), but to the user it reads as a full re-sync. Step 2's single engine keeps
  every attached wallet at one frontier, so most of this goes away. What remains (a wallet in a
  band or waiting for one, and the shared engine itself after a long background freeze) is
  labelled as catching up, with a count of blocks and no percentage, and never uses the wording of
  a first sync.

## Left to the builder

- Exact file and prefs key spellings, beyond the rule that the legacy wallet keeps its current
  names.
- How step 1's per-wallet suffix is threaded: a `WalletScope` passed down or an active-wallet
  provider. The rule is that every store resolves through one function.
- The internal shape of the per-wallet context in the bridge, and the order of the JNI signature
  migration.
- The switcher sheet's visual design, within the existing theme.
- Step 2 (added 2026-11-17):
  - which step-1 chain seeds the shared engine at the migration, and the shared chain's file
    names. Legacy's 4.1.4 names are never renamed, and a band never shares a file with the shared
    engine. The attach checks the shared chain's block at F - 1, so in practice the seed is the
    chain with the highest saved tip;
  - the order in which waiting wallets get the band, and whether a band runs during SyncWorker's
    catch-ups;
  - how a chain-level reset (a corrupt shared chain or ledger, BootGuard's wipe) restarts the
    shared engine. Proposed: one climb from the lowest birth with every wallet attached, since
    every wallet has lost its scan state;
  - not measured yet: how DigiByte Core nodes treat two connections from one device (the shared
    engine and a band).
- Test plan. At minimum:
  - an upgrade test from 4.1.4 showing the existing wallet's data is untouched, and a migration
    test from the step-1 layout with one wallet days behind and one weeks behind;
  - host KATs for the multi-wallet peer manager: a transaction for wallet B is never credited to
    wallet A; a band runs while the shared tip keeps advancing, and hands its wallet over only
    under the hand-off rule; a hand-off whose F is below the shared ledger's requested height
    rewinds the shared ledger to F and misses no height; a wallet is never in two managers; a
    reorg rewinds every wallet;
  - a red-before-green KAT for the union element cache: an attach, a detach, and growth in either
    wallet each invalidate it;
  - a removal test showing other wallets' files survive, and the shared chain too, legacy's
    removal included;
  - the 13-locale gates for every new string.
