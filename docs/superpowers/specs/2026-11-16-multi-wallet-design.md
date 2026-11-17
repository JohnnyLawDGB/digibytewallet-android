# Multiple wallets in HoldFast Wallet — design

Status: for owner approval (2026-11-16); builder-checked. Follow-on, separate design: Coinomi-compatible Digi-ID.

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
>
> **Waits for the owner (2026-11-17): a READY wallet with no record, erased at launch.** Launch
> housekeeping (`WalletHousekeeping`) also erases the seed key aliases, the database and every other
> store of a non-legacy READY wallet whose seed record is gone, and drops its entry once that erase
> verified, so the wallet stops holding one of its network's 8 slots. It acts only when no sealed
> seed can be read and no record file (`.xml` or `.xml.bak`) is on disk. Every other erase of a
> wallet's seed key and database follows a request: a wipe, a removal the user confirmed, an add
> that was abandoned. This one acts on what it finds at launch, so it changes when they are wiped: a
> security decision that stays with the owner (`docs/development-process.md`, "Still Mark's,
> always"). By reading, only a wipe leaves that state: it erases every record first and clears the
> registry afterwards. The PR that brings this waits for his answer even with review and CI green;
> the answer replaces this paragraph, with its date.


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

- **The C core is instance-based.** `BRWalletOpenWithKeys` and `BPPeerManagerMainNetNew(wallet, …)`
  create instances. The only global is the network flag in `BRNetwork.c`.
- **The peer manager takes exactly one `BRWallet*`.** It is used at about 70 sites through about 25
  calls (`BRWalletTransactionForHash`, `BRWalletUnusedAddrs`, `BRWalletSetBlockHeight`,
  `BRWalletRegisterTransactionOutcome`, `BRWalletGetFilterElements`, …).
- **The JNI bridge is all process globals.**
  - `jni_wallet.c`: `g_wallet`, `g_peerManager`, `static g_seed[64]`, `g_mpk`, the creation time and
    the rwlock.
  - `jni_peer.c`: `g_savedBlocks*`, `g_savedPeers`, the status mirrors and the sync flags.
  - Callbacks are registered with `info=NULL` and read the globals.
  - About 20 JNI files use `g_wallet` / `WALLET_READ_GUARD`.
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
| C | One sync engine per wallet | N× peers, data and battery for no gain over A | Rejected |

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
  - watched addresses, outgoing txs, birth height, the asset backfill/heal state, the CF ledger,
    saved transactions, abandonment, reconcile and history rebuild;
  - the Hub session and Digi-ID history.
- **Shared** (chain data from step 2 on; in step 1 it is per wallet, see below):
  - chain data: filter headers, block checkpoints, ChainTip;
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
- Step 2 merges chain data into one shared chain. Its migration keeps every wallet's CF ledger
  and drops the duplicate header chains.

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

**Per-wallet scan ledger and back-scan.**
- The CF ledger (scanned-through height, gave-up bands, abandonment) becomes per wallet.
- Shared chain state stays shared: the filter-header chain and saved blocks near the tip.
- A newly attached wallet below the tip gets a back-scan band from its birth height. Old filters are
  fetched and matched against that wallet's elements only, while the forward fetch at the tip keeps
  matching every wallet.
- Recovery paths (recreate near tip, re-anchor, reorg rewind) keep their existing guarantees for
  every wallet in the set.

**Bridge.**
- The globals move into a per-wallet context struct (wallet, master public key, creation time and
  seed handling through the accessor API) plus one shared sync context (peer manager, saved blocks,
  peers, status).
- Callbacks receive the wallet handle through `info`.
- JNI calls take a wallet handle (the registry id resolved to a context).
- **Keys in memory (owner decision 2026-11-16, option 2): only the ACTIVE wallet's seed is resident.**
  - The active wallet's seed lives in the process exactly as today: copied in at unlock, kept
    while unlocked so background signing and DigiDollar keep working, zeroed by `lockSession()`.
  - Every other wallet syncs **watch-only**. It is attached to the engine from its stored master
    public key and its pre-derived address set, and it can never sign.
  - Each wallet's master public key, and the addresses that need the seed to derive (DigiDollar
    receive / Taproot keys), are derived and stored while that wallet's seed is unlocked. Those
    are its creation, its restore and its turns as active.
  - The stored material is public-key data, encrypted at rest with the same non-auth-bound
    protection as the database. It can watch addresses but cannot move funds.
  - Switching wallets zeroes the outgoing seed and loads the incoming one behind the app PIN,
    with the usual Keystore auth window.
  - Spending, Digi-ID approval, sweeps and Hub sign-in happen only from the active wallet.
  - Exposure stays at one resident seed, as today.

**App.**
- A wallet session holder replaces the `@Singleton` database. It exposes per-wallet repositories.
- SyncService runs one engine with every registered wallet attached.
- ViewModels observe the active wallet id and rebind without a restart.
- Background sync (SyncWorker) catches up every wallet. BootGuard and the recreate sequence operate
  on the shared engine.

**Found on the device in step 1 (owner, 2026-11-16 sweep of a retired test wallet on the Note 8).**
- **The DGB Send screen needs MAX (send-all).** Today it has none: the `send_max` string exists
  but only `AssetSendScreen` uses it. Emptying a wallet means working out balance minus fee by
  hand, and an amount typed by eye leaves change behind in the wallet being emptied (the sweep
  took two sends because of it). MAX spends every spendable output to one output with no change,
  the fee taken from the amount. Moving funds out of a wallet before removing it is now a normal
  multi-wallet task, so this belongs with step 2 even though it is not engine work.
- **A switch must not look like a re-sync.** In step 1 a switch restarts the process, and the
  switched-to wallet shows "Scanning… 99% · N blocks remaining" while it catches up the blocks
  since it was last active (717 in the test, done in seconds). That is a resume near the tip
  (`syncedResume=true`), but to the user it reads as a full re-sync. Step 2's single engine keeps
  every wallet near the tip, so most of this goes away. What remains (a newly attached wallet's
  back-scan, a watch-only wallet behind after a long background freeze) is labelled as catching
  up, with a count of blocks and no percentage, and never uses the wording of a first sync.

## Left to the builder

- Exact file and prefs key spellings, beyond the rule that the legacy wallet keeps its current
  names.
- How step 1's per-wallet suffix is threaded: a `WalletScope` passed down or an active-wallet
  provider. The rule is that every store resolves through one function.
- The internal shape of the per-wallet context in the bridge, and the order of the JNI signature
  migration.
- The switcher sheet's visual design, within the existing theme.
- Test plan. At minimum:
  - an upgrade test from 4.1.4 showing the existing wallet's data is untouched;
  - host KATs for the multi-wallet peer manager: a transaction for wallet B is never credited to
    wallet A; a back-scan band for a new wallet is independent of the forward scan; a reorg rewinds
    every wallet;
  - a removal test showing other wallets' files survive;
  - the 13-locale gates for every new string.
