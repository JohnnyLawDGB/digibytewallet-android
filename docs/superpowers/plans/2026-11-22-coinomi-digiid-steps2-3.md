# Coinomi-compatible Digi-ID, app steps 2 and 3: build plan for 4.1.6

> **Status 2026-11-22: PLAN, not started.** The owner's answers to the numbered questions at the
> end are not in yet. Nothing here is decided until they are recorded in this file. Precondition P0
> (the 4.1.5 release branch merged back into `develop`) is PR #298.

Plan only. No production code was written and no PR opened. Read at `origin/develop` `5bfcfe891`
(core pin `8014392`), 2026-11-22. Design: `docs/superpowers/specs/2026-11-17-coinomi-digiid-design.md`
(approved 2026-11-17, amended the same day by #267). Step 4 (watching Coinomi's fund paths) rides
multi-wallet step 2 and is out of scope.

**Row invariant.** A wallet restored from a Coinomi phrase signs each Digi-ID site with the identity
Coinomi used. Four parts carry it: the per-wallet site-key root, the restore question, the Settings
toggle, and the approval screen's address row.

---

## 0. What is already true (checked, not assumed)

| Fact | Evidence |
|---|---|
| Step 1 is merged and pinned: `BRDigiIdRoot` is a required argument of `BRDigiIdSiteKey`, `BRDigiIdSignSite`, `BRDigiIdAddress` | core `d636eb5` (#96), inside pin `8014392` |
| The "small core PR after step 1" is merged and pinned too: `BRDigiIdIsDigiScopeHost`, `BRDigiIdChooseIdentity`, `BR_DIGIID_DIGISCOPE_LOGIN_URI` | core `f233cd6` (#97), inside pin `8014392` |
| Q4 A is on `develop` (#268) and shipped in 4.1.5 | `IdentityKeyPolicy.kt`: two-host list, no history rule |
| The bridge still derives per-site keys itself with `"DigiByte seed"` | `jni_wallet.c:545` `seed_derive_identity_key` → `BRBIP32PrivKeyPath`; no bridge file includes `BRDigiId.h` |
| DigiScope is signed in three hard-wired places | `DigiIdManager.kt:148` (`signMessage(…,0)`), `DigiScopeClient.kt:46` (`signMessage(…,0)`), `HubIdentity.kt:17` (`signMessage(…,1)`) |
| `NativeBridge.identityAddress` has no Kotlin caller | removed with the identity card in #268 |
| No "Settings → Digi-ID" screen exists | `SettingsScreen.kt` has Security, Network, Appearance, Recovery, Info only |
| The seed record is one prefs file per wallet, written whole in one `commit()` | `WalletManager.storeSeedRecord` (`:1519`), file `dgb_wallet_seed[_<id>]` |
| First-run restore writes the record on the date screen; ADD mode writes it at `add_wallet_commit` | `RecoveryDateScreen.kt:193-221`, `OnboardingViewModel.commitAdd` |
| **4.1.5 is not merged back to `develop`** | `02621acf3` (tag `v4.1.5`) is not an ancestor of `origin/develop`; `develop` still says `versionName = "4.1.4"` |

**Consequence for the PR order.** Steps 2 and 3 need **no core PR and no pin move**. "Core first, then
pin, then app" reduces to: the core is done, the pin is done, the app work starts. One optional
core comment-only PR trails the app work (task C-doc).

### The vectors, and how they were verified independently

- **Source.** The three addresses in the design's table were read off Coinomi itself on 2026-11-17: a
  throwaway Coinomi wallet on the public BIP39 test phrase (`abandon` ×11 `about`, no passphrase)
  signed three challenges through a temporary DigiScope probe route, and each signature recovered the
  address Coinomi reported. They are committed as literals in
  `native/src/test/host/digiid_core_kat/digiid_core_kat_main.c` (`site_key_standard_coinomi_uris`).
- **Independent check, re-run for this plan.** A standard-library-only Python model that shares no
  code with the core or the bridge (PBKDF2 for BIP39, its own secp256k1 point arithmetic, BIP32 CKDpriv,
  SLIP-0013 path, RIPEMD160/Base58Check) reproduces every committed literal it was asked for. The
  script is at
  `docs/superpowers/plans/2026-11-22-coinomi-slip13-model.py` (task A0 moves it beside the host KATs).

  | Input (phrase `abandon…about`, index 0) | Root | Model answer | Committed literal it matches |
  |---|---|---|---|
  | `https://api.digiscope.me/api/digiid-probe/callback` | STANDARD | `DSSfzAosbs1mBJE4rZPtZHGuwjHorJDNau` | Coinomi, measured |
  | `https://api.digiscope.me/api/auth/digiid/callback` | STANDARD | `DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri` | Coinomi, measured |
  | `https://digiscope.me/api/digiid-probe/callback` | STANDARD | `DA3TaNPPTHwStZp3K98sGzk7aaQ8sh7sBo` | Coinomi, measured |
  | the same three | DIGIBYTE | `DHawr8VS…`, `DKBDZRPx…`, `DS4cZJP3…` | read off the real bridge |
  | `https://example.com/callback` | DIGIBYTE | `DPeEMYki92jeUSDgjkrfrTMVpvGWCHYd68` | bridge golden (`kat_main.c:110`) |
  | login callback at index 1, 2, `INT32_MAX` | STANDARD | `D6jnpnRo…`, `DT1N5cjf…`, `D7D31HYw…` | hand-off §5 |
  | shared `m/0'/0/0` | (DigiByte seed) | `DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn` | `SHARED_ADDRESS` |
  | BitID phrase, `http://bitid.bitcoin.blue/callback` | STANDARD | `1J34vj4wowwPYafbeibZGht3zy3qERoUM1` | BitID's published vector |

- **Model-only values** (nothing measured; never to be described as Coinomi's):
  `https://example.com/callback` STANDARD `DGytfsSEXBrMTaMvptEbhvVotuU4HH3G2k`;
  `https://id.digistamp.co/api/auth/digiid/callback` STANDARD `DSVWzkuZumhk7exaDm8Vu8jHhRAJysL5qY`,
  DIGIBYTE `DSiRPSLW6SnbTqf5mVcg8gDMuiHsPdz8o9`.
- **A limit that shapes the tests.** Through the choice (`BRDigiIdChooseIdentity`) a request to a
  DigiScope host never signs with its own callback. So of the three measured addresses only
  `DSsC7WWi…` is reachable through the app; the two probe addresses stay header-level vectors.
- **Gap this plan closes.** The Python model is cited in the KAT's comments but is not in the repo.
  Task A0 commits it and makes the KAT run it.

---

## 1. PR split and order

No squash merges. Each PR edits `ROADMAP.md` item 6 for what it finishes. "Gated" means it waits for
Mark even when review and CI pass (`docs/development-process.md`, "Still Mark's, always").

| # | PR | Gated | Why | Queues it sits in |
|---|---|---|---|---|
| P0 | Merge `release/digibyte-mobile-4.1.5` back into `develop` | Mark's (release) | precondition; not this plan's task | — |
| A0 | Host KAT: commit the Python model; freeze the bridge's answers as a golden file | no | test-only | host KAT |
| A1 | Per-wallet identity properties and their store | **yes** | changes what the seed record's writer writes | `WalletManager.kt` cluster (G9) |
| A2 | Bridge: choice-driven natives on `BRDigiId.h` | **yes** | key derivation path, seed accessor | bridge cluster head (G7); registry PR (rule 6) |
| A3 | App wiring: one signer, `DigiScopeIdentity`, old natives and the Kotlin list removed | **yes** | decides which key signs | G8 cluster; registry PR (rule 6); bridge cluster |
| U3 | Approval screen: the address row | no | display only | G8 cluster (`DigiIdViewModel.kt`); strings cluster |
| U1 | Restore: "Where is this phrase from?" | **yes** | identity choice written into the record | strings cluster; `AppNavigation.kt` cluster |
| U2 | Settings → Digi-ID: the toggle | **yes** | identity choice; ends a Hub session | strings cluster |
| T1 | Pre-publish scenario I | no | test-only | — |
| C-doc | Core: `BRDigiId.h` comment ("neither app calls it yet"), then a pin move | no | comment only; computes nothing | core, then pin |
| D1 | Hand-off to iOS; design banner; `CLAUDE.md` | no | docs | — |

Order: **P0 → A0 → A1 → A2 → A3 → U3 → U1 → U2 → T1**, with D1's pieces riding the PR each describes
and C-doc last. A1 and A2 share no file and may be open together; A3 needs both. U3 goes before U1
and U2 on purpose: once the address is on the approval screen, every later check (pre-publish, the
mule) reads the identity off the screen without signing anything. Suggested owner batches: **M-A**
(A1 + A2 + A3, reviewed together, merged in order) and **M-U** (U1 + U2).

Steps 2 (A1 to A3) change no address any wallet signs with: every wallet reads the upgrade row
(`DIGIBYTE` + `SHARED`) and there is no screen to change it until step 3.

Multi-wallet step 2 waits on this work: X3a (step 2 merged) blocks B1, B3, K3, K8; X3b (step 3
merged) blocks U3 and the strings cluster. Nothing in step 2's bridge or registry queue opens while
A2 or A3 is open.

Work in a worktree cut from `origin/develop`, never in the main checkout: it is five commits behind
`origin/develop` and its submodule sits at `f233cd6`, not at the pin.

---

## 2. The stores

**Recommended (question 1): the properties are four keys in the wallet's own seed-record prefs
file**, written in the record's one `commit()`.

| Store | Legacy name | Other wallet | Class | What this work does |
|---|---|---|---|---|
| Seed record | `dgb_wallet_seed` | `dgb_wallet_seed_<id>` | `StoreScope.WALLET` | adds four keys (below) |
| Hub session token | `dgb_hub_session` | `dgb_hub_session_<id>` | `WALLET` | erased by the toggle (this wallet's only) |
| Hub prefs | `dgb_digiscope` | `dgb_digiscope_<id>` | `WALLET` | erased by the toggle with the token |
| Digi-ID history (Room) | `wallet.db` / `wallet_testnet.db` | `wallet_<id>[_testnet].db` | `WALLET_AND_NETWORK` | rows written as today; **no schema change** |
| Wallet registry | `dgb_wallets` | shared | — | read only |
| Web session owner | `dgb_web_session_owner` | shared | — | only if question 9 is "yes" |

New keys in the seed-record file:

| Key | Values | Absent means |
|---|---|---|
| `digiid_site_key_root` | `"digibyte"` \| `"standard"` | `digibyte` |
| `digiid_digiscope_identity` | `"shared"` \| `"site"` | `shared` |
| `digiid_previous_site_key_root` | `"digibyte"` \| `"standard"` | none saved |
| `watched_accounts` | string set of `"coinomi_bip44"`, `"coinomi_bip49"` | empty |

Stored as names, never as the C enum's numbers; the numbers (`1`/`2`) are produced at the bridge call
only. No new prefs file, no new file in `filesDir`, no new Keystore alias, no Room migration.

Why the seed-record file and not a new `dgb_…` store:

- The design asks for "written with the wallet record, in the same commit". One file is the only way
  to get one commit. A separate store leaves a window where a Coinomi restore has a record and no
  properties, which reads as `DIGIBYTE` + `SHARED`: the wrong account, silently.
- **4.1.5's wipe already erases it.** A new store name would be unknown to every shipped build, the
  exact problem WIPE-RESERVED-NAMES fixed for `dgb_wallet_keys`. 4.1.5 reserved no name for this.
- Removal and the wipe need no new code: the record goes in phase 1 of `WalletStoreEraser.erase`.
- `WalletStores.WALLET_PREFS` stays the 4.1.4 store set, which its tests pin.

The legacy wallet's record serves both networks, so legacy has one set of properties for mainnet and
testnet. That matches its one seed.

### What the wipe and a removal must erase

| Item | Erased by | New code |
|---|---|---|
| The four keys | the record's erase (wipe: `eraseEveryRecord`; removal: phase 1) | none; tests assert it |
| The record's `.bak` | the same erase | none |
| This wallet's Hub token and prefs | already erased (they are `WALLET_PREFS`); the wipe also revokes (`revokeHubSessions`) | none |
| Anything in the Keystore | nothing new exists | none |

Tests (in A1): after a full wipe, and after removing one wallet, no file under `shared_prefs` holds
any of the four key names (`WipeLeavesNoWalletNamesTest`'s pattern); removing wallet B leaves wallet
A's four values byte-identical.

---

## 3. Tasks

Standard steps for every task: write the failing test; run it and see it fail **for the stated
reason**; implement; run the focused tests; run the full suites with
`./gradlew :core:testMainnetDebugUnitTest :app:testMainnetPlayDebugUnitTest :app:testMainnetSideloadDebugUnitTest --continue`;
commit only on a green exit code. A "red on `develop`" claim is re-checked on the PR's base when the
task starts.

### Task A0: the model in the repo, and the bridge frozen before it moves (test-only)

**Files**
- Create `native/src/test/host/digiid_core_kat/slip13_model.py` (the scratch script, tidied).
- Create `native/src/test/host/digiid_core_kat/bridge_golden.txt`.
- Modify `native/src/test/host/digiid_core_kat/run.sh`, `digiid_core_bridge_main.c`.

**Test first**
- A `MODEL` leg in `run.sh`: `python3 -I slip13_model.py` prints address lines; the leg fails unless
  each equals the literal in `digiid_core_kat_main.c` for the same input (the table in §0). Its red
  arm: the model run with `b"DigiByte seed"` for the STANDARD rows must be refused by name.
- A `GOLDEN` leg: every `ADDRESS …` and `SIGN …` line `digiid_core_bridge_main.c` prints today is
  written to `bridge_golden.txt` once, from the unchanged bridge, and compared on every run after.
  This is the characterization the repo's shared-crypto rule asks for. It matters more after A3: once
  the bridge calls the header, "bridge equals header" proves nothing, and the golden file is what
  still holds the bridge to its 4.1.5 answers.
- No missing `python3` passes silently: the leg fails, as the existing list gate does.

**Lane.** New host-KAT work is the iOS-and-core session's unless it is part of an adoption. This is
the adoption's own characterization (question 15).

### Task A1: the per-wallet identity properties and their store [gated]

**Files**
- Create `core/src/main/java/app/aroundtheblock/wallet/core/digiid/WalletIdentityProperties.kt`:
  `SiteKeyRoot { DIGIBYTE, STANDARD }`, `DigiScopeIdentityKind { SHARED, SITE }`,
  `WatchedAccount { COINOMI_BIP44, COINOMI_BIP49 }`, `PhraseSource { HOLDFAST, COINOMI, OTHER_WALLET,
  NOT_SURE }`, `data class WalletIdentityProperties(siteKeyRoot, digiScopeIdentity, watchedAccounts,
  previousSiteKeyRoot)`, and three pure functions: `forCreated()`, `forRestored(source)`,
  `withCoinomiSignIn(on: Boolean)`.
- Create `core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletIdentityStore.kt`: reads and
  writes the four keys in a given `SharedPreferences`. It takes the prefs (or a scope) as an
  argument and never reads `ActiveWallet`, so multi-wallet step 2's K8 gate (ruling G61) holds from
  the start.
- Modify `core/src/main/java/app/aroundtheblock/wallet/core/WalletManager.kt`:
  - `storeSeedRecord` takes `identity: WalletIdentityProperties?`. Non-null (a new record: create,
    restore, `storeRecordOnly`) removes the four keys and writes the given ones in the same edit.
    Null (a rewrite of the same wallet: `migrateLegacyNulPassphrase`, `migrateSeedKeyIfNeeded`) leaves
    them untouched.
  - `createWallet`, `recoverWallet`, `storeRecordOnly` gain an `identity` parameter with no default
    at the three internal call sites; a create passes `forCreated()`.
  - `identityProperties()`, and `setCoinomiSignIn(on)` (one checked `commit()`, `putBack` on a
    refused write, as the record's writer does).
- Modify `core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletProvisioner.kt`: `add(…,
  identity)` passes it to `storeRecordOnly`.
- Modify `CLAUDE.md` (SharedPreferences keys: the four keys under `dgb_wallet_seed`).

**Tests first** (all `core/src/test/java/app/aroundtheblock/wallet/core/…`)
- `digiid/WalletIdentityPropertiesTest.kt`: the design's six-row table, value for value; then the
  toggle:

  | From | Action | To | `previousSiteKeyRoot` after |
  |---|---|---|---|
  | `DIGIBYTE`+`SHARED` | On | `STANDARD`+`SITE` | `DIGIBYTE` |
  | that | Off | `DIGIBYTE`+`SHARED` | none |
  | `STANDARD`+`SITE`, none saved (restored as Coinomi) | Off | `STANDARD`+`SHARED` | none |
  | `STANDARD`+`SHARED` (another wallet, or the row above) | On | `STANDARD`+`SITE` | `STANDARD` |
  | that | Off | `STANDARD`+`SHARED` | none |
  | any | On or Off | `watchedAccounts` unchanged | — |

  Red arm for the last row: a toggle that clears `watchedAccounts` (multi-wallet U3 needs the same
  test).
- `wallets/WalletIdentityStoreTest.kt`: absent keys read as the upgrade row; round trip; the legacy
  name and a `_<id>` name; a value that is present but unknown reads as `Unreadable`, never as a
  default (question 7); a refused commit changes nothing in the process's view.
- `SeedRecordIdentityFieldsTest.kt`: a restore as Coinomi writes record and properties in one commit
  (a commit that fails leaves neither); a create over a file that still holds stale identity keys
  writes the created row; `migrateLegacyNulPassphrase` and the seed-key migration keep all four keys;
  `restoreFromDisk` writes none of them.
- Extend `wallets/UpgradeFrom414Test.kt`: a 4.1.4 record and a 4.1.5 `_<id>` record read as
  `DIGIBYTE`+`SHARED`+empty, and reading leaves the file byte-identical.
- Extend `WalletWipeEveryWalletTest.kt`, `wallets/WalletRemovalTest.kt`,
  `WipeLeavesNoWalletNamesTest.kt` as in §2.
- Extend `wallets/WalletProvisionerTest.kt`: an add writes the new wallet's properties and no other
  wallet's file changes; a failed or abandoned add leaves none.

### Task A2: the bridge on `BRDigiId.h` [gated · registry PR · bridge cluster]

**Recommended shape (question 2).** The natives take the request's host and callback and the
wallet's two properties, and the C side makes the choice and signs in one call. The app then cannot
sign with a key the choice did not name.

```kotlin
// NativeBridge.kt
external fun digiIdSign(message: String, host: String, callbackUri: String?, digiScopeIdentity: Int, siteKeyRoot: Int): String?   // "address|signature"
external fun digiIdAddress(host: String, callbackUri: String?, digiScopeIdentity: Int, siteKeyRoot: Int): String?
external fun digiIdIdentityKind(host: String, callbackUri: String?, digiScopeIdentity: Int, siteKeyRoot: Int): Int              // 0 refused, 1 shared, 2 site; no seed needed
external fun isDigiScopeHost(host: String): Boolean                                                                               // no seed needed
```

**Files**
- `native/src/main/jni/bridge/jni_wallet.c`, `jni_bridge.h`: two accessors beside `seed_derive_key`,
  `seed_digiid_sign_site(out, outCap, root, msg, msgLen, uri, uriLen)` and
  `seed_digiid_site_address(out, outCap, root, uri, uriLen)`, which call `BRDigiIdSignSite` and
  `BRDigiIdAddress` with `g_seed`. `g_seed` stays `static`. Multi-wallet B3 later gives each a `ctx`
  first argument.
- `native/src/main/jni/bridge/jni_wallet_sign.c`: the four natives. Each calls
  `BRDigiIdChooseIdentity` once and acts on the answer: `SHARED` takes today's shared path
  (`seed_derive_key(0,0)` + `sign_with_key`, unchanged: question 11); `SITE` calls the accessor with
  `choice.uri` and `choice.root`. The message is read with `message_utf8` as today; the callback with
  `GetStringUTFChars` as today, so the URI bytes rule is unchanged.
- `native/src/main/jni/bridge/jni_registry_gen.h`: regenerated by `scripts/gen-jni-registry.py`,
  never merged (rule 6).
- `core/src/main/java/app/aroundtheblock/wallet/core/bridge/NativeBridge.kt`.
- The three old natives stay in this PR, untouched, so no Kotlin caller changes yet.
- `native/src/test/host/digiid_core_kat/digiid_core_bridge_main.c`, `run.sh`; the `host_jni`
  stand-ins if a JNI call is new to them.

**Tests first** (host KAT, through the real bridge over the host JNI)
- **Neutrality.** For `SHARED` + `DIGIBYTE`, `digiIdSign` and `digiIdAddress` answer byte for byte what
  `signMessage`, `signIdentityMessage` and `identityAddress` answer (A0's golden lines).
- **Coinomi through the bridge.** `digiIdAddress("api.digiscope.me", NULL, SITE, STANDARD)` and the
  same for `digiscope.me` with any callback give `DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri`; `digiIdSign`
  over the KAT's `LOGIN` gives the whole committed answer `DSsC7WWi…|IMx5g483…lfI=`.
- **The address shown is the address signed** (the roadmap's done-criterion for step 2). Over a
  sweep of hosts on and off the list, both property values each, with and without a callback:
  `digiIdAddress` equals the address half of `digiIdSign`, and both equal the header's
  choose-then-derive.
- Off the list under STANDARD: BitID's published vector by its hash160; the model-only values in §0,
  labelled as the model's.
- **Refusals**, each null from all of sign and address: a property of 0, 3 or -1; a null or empty
  host; a host off the list with no callback; a locked session; no wallet. `digiIdIdentityKind` and
  `isDigiScopeHost` answer while locked.
- `isDigiScopeHost` over the KAT's list cases, value for value.
- **Comparison arms**, `-DDIGIID_BRIDGE_UNFIXED=<bit>` tested by value, each failing exactly its
  cases: 1 the address native passes `DIGIBYTE` whatever root it was given; 2 the sign native does;
  4 a list host with `SITE` takes the shared path; 8 the two properties are swapped at the header
  call; 16 a locked session still derives. Arms 1 and 2 are the defect the roadmap names: two natives
  wired to different roots, showing one address and signing with another.
- `python3 scripts/gen-jni-registry.py --check`.
- Create `core/src/test/java/app/aroundtheblock/wallet/core/digiid/IdentityWireValuesTest.kt`: the
  numbers Kotlin passes for the two enums equal `BR_DIGIID_ROOT_*` and `BR_DIGIID_SCOPE_*` as written
  in the pinned `BRDigiId.h`; it fails, not skips, when it cannot find them.

### Task A3: one signer, one DigiScope identity [gated · registry PR · G8 cluster]

**Files**
- Create `core/src/main/java/app/aroundtheblock/wallet/core/digiid/DigiIdSigner.kt`: the only Kotlin
  file that calls the Digi-ID natives. It holds `properties: () -> WalletIdentityProperties`
  (injected, read on every call, never cached) and offers `signRequest(request)`,
  `addressFor(request)`, `kindFor(request)`, `isDigiScopeHost(domain)`. Unreadable properties sign
  nothing.
- Create `core/src/main/java/app/aroundtheblock/wallet/core/digiscope/DigiScopeIdentity.kt`:
  `address()` and `sign(text)` for DigiScope with no request (host `api.digiscope.me`, no callback),
  on top of `DigiIdSigner`. "core" in the design means this Kotlin module, not the C core.
- Modify `core/…/digiid/DigiIdManager.kt` (signs through `DigiIdSigner`; records `legacy` or `site`
  from `kindFor`; asks `isDigiScopeHost` before `quickLogin`), `core/…/digiscope/DigiScopeClient.kt`
  (the constructor's signer becomes `DigiScopeIdentity::sign`),
  `app/…/ui/hub/ChatViewModel.kt` (`myAddress` and `sign` from `DigiScopeIdentity`),
  `app/…/di/AppModule.kt` (provides the signer; the properties provider is built from the session's
  scope there).
- Delete `app/…/ui/hub/HubIdentity.kt`, `core/…/digiid/IdentityKeyPolicy.kt` (with
  `IdentityKeyKind`), and `IdentityKeyPolicyTest.kt` (its list cases already run in
  `digiid_core_kat`, value for value).
- Remove the three old natives: `NativeBridge.kt`, `jni_wallet_sign.c`, `seed_derive_identity_key`
  (`jni_wallet.c`, `jni_bridge.h`), `bridge/slip13.c` and `slip13.h`, `native/CMakeLists.txt:142`;
  regenerate `jni_registry_gen.h`.
- Re-anchor the host KATs the removal breaks (part of the adoption): the link lines that name
  `slip13.c` in `asset_send_kat`, `bip39_nul_kat`, `callback_handler_swap_kat`, `digiid_core_kat`,
  `identity_sign_bytes_kat`, `outpoint_classify_kat`, `saved_tx_array_kat`,
  `signer_distinct_inputs_kat`, `wallet_read_guard_kat`; the suites that call the old natives by name
  (`bip39_nul_kat`, `identity_sign_bytes_kat`, `digiid_core_bridge_main.c`,
  `wallet_read_guard_kat`); `slip13_identity_kat` is retired (its assertions run in
  `digiid_core_kat`), with its line in `.github/kat-shards/timings.txt`. `digiid_core_kat_main.c`'s
  line-for-line model of the old bridge keeps a frozen copy of `slip13.c` beside it.
- Remove the KAT's Kotlin-list source gate and `digiscope_hosts.py`'s Kotlin reader, as
  `run.sh:405` instructs for this change.
- Modify `core/src/test/…/network/NetworkSourceGateTest.kt:101` (drop the `IdentityKeyPolicy.kt`
  allowance), `CLAUDE.md` (Digi-ID: "One copy of the rule" is now the core's; JNI section),
  `ROADMAP.md` item 6, `security/AUDIT-LOG.md` if it names the old natives as current.

**Tests first**
- `core/…/digiscope/DigiScopeIdentityTest.kt`, with a recording fake in place of the natives: for
  `SHARED` and for `SITE`, the QR login to `digiscope.me`, the QR login to `api.digiscope.me`, quick
  login and a chat post all sign as the one address `DigiScopeIdentity.address()` gives.
- **The silent account switch** (`HubSessionAfterDigiIdLoginTest`, extended): a `SITE` wallet's QR
  login is followed by a quick login that signs with the same identity, so the token stored is that
  account's. Red arm: quick login planted on the shared path.
- `core/…/digiid/DigiIdManagerSignsThroughSignerTest.kt`: the manager passes host, callback and the
  wallet's properties and decides nothing; off the list the callback is the request's own; the
  history row says `legacy` exactly when the kind is shared. Replaces `DigiIdIdentityChoiceTest`'s
  policy cases; its "history is written and never read" case stays.
- Rewrite `app/…/ui/hub/IdentitySigningSitesGateTest.kt`: the Digi-ID natives are called in exactly
  one file, `DigiIdSigner.kt`; the names `signMessage`, `signIdentityMessage`, `identityAddress`
  appear in no shipped Kotlin; Hub posts are still signed only in `ChatViewModel.sendMessage`.
- New source gate `core/…/digiid/OneDigiScopeListGateTest.kt`: no shipped Kotlin compares a Digi-ID
  host with a DigiScope host literal. It must first find its own planted violation in a temp copy.
- New source gate: `DigiIdSigner.kt`, `DigiScopeIdentity.kt` and `WalletIdentityStore.kt` contain no
  `ActiveWallet` read (G61).
- `app/…/ui/components/SpendGateWiringTest.kt` stays green unchanged: the approve, quick login and
  post paths keep their gate.
- Update the seven tests that build `DigiScopeClient` with `signWithSharedIdentity = …` and
  `HubPostSigningTest`, `ChatReconnectWaitTest` (mock type).
- A0's golden leg stays byte-identical: with every wallet on the upgrade row, nothing a wallet signs
  changes.

**One visible change rides here.** `HubIdentity.address()` returns the first unused receive address,
not the identity's, so the chat likely never marks the user's own messages. The design found this by
reading. Reproduce it on the mule or an emulator before the PR says "fixed" (question 16).

### Task U3: the approval screen's address row [not gated · strings cluster · G8 cluster]

**Files**
- `app/…/ui/digiid/DigiIdViewModel.kt`: `identity: StateFlow<ShownIdentity?>` (address, and whether
  Coinomi-compatible sign-in is on), loaded from `DigiIdSigner.addressFor(request)` off the main
  thread; `authenticate(request, shownAddress)`.
- `app/…/ui/digiid/DigiIdConfirmScreen.kt`: the row under the callback URL.
- `core/…/digiid/DigiIdManager.kt`: when a shown address is given and the signature's address
  differs, nothing is posted and the login fails (question 14).
- `strings_wallet.xml` ×13.

| Key | English |
|---|---|
| `did_signs_in_as` | `Signs in as` |
| `did_address_locked` | `Unlock the wallet to see the address` |
| `did_coinomi_signin_on` | `Coinomi-compatible sign-in is on for this wallet` |

**Tests first**
- `app/…/ui/digiid/DigiIdConfirmAddressRowTest.kt`: a list host shows `DigiScopeIdentity.address()`;
  any other host shows the per-site address of the request's callback under the wallet's root; a
  locked session shows the placeholder and no address; the Coinomi line shows exactly when the pair
  is `STANDARD`+`SITE` (question 6).
- `DigiIdManager`: a login whose signing address differs from the shown one posts nothing.
- `LocaleResourceParityTest`, `OnboardingHardcodedStringTest` (the screen is already in `COVERED`).

The address is derived before the spend gate, from the resident seed, as the 4.1.2 identity card did.
It is an address, not a signature.

### Task U1: the restore question [gated · strings cluster · `AppNavigation.kt` cluster]

**Files**
- Create `app/…/ui/onboarding/PhraseSourceScreen.kt`.
- `app/…/ui/onboarding/OnboardingRoutes.kt` (`RESTORE_SOURCE_ROUTE = "restore_source"`),
  `app/…/ui/navigation/AppNavigation.kt` (the composable; the setup-route list at `:75-83`).
- `app/…/ui/onboarding/MnemonicInputScreen.kt:260-278`: both modes go to `restore_source`; from
  there the first run continues to `recovery_scan` and ADD to `recovery_date`.
- `app/…/ui/onboarding/OnboardingViewModel.kt`: holds the answer; clears it when the phrase changes
  and in `wipeMnemonicFromMemory`; `recoverWallet` and `commitAdd` pass `forRestored(answer)`; a
  create passes `forCreated()`. A restore with no answer is refused, not defaulted.
- `strings_wallet.xml` ×13; `OnboardingHardcodedStringTest.COVERED` gains the new screen.

| Key | English |
|---|---|
| `restore_source_title` | `Where is this phrase from?` |
| `restore_source_body` | `This decides which accounts you sign in to with Digi-ID. Your coins are the same either way.` |
| `restore_source_holdfast` | `%1$s or the DigiByte app` (the argument is `app_name_display`) |
| `restore_source_holdfast_sub` | `Signs in to sites as this app always has.` |
| `restore_source_coinomi` | `Coinomi` |
| `restore_source_coinomi_sub` | `Signs in to sites, DigiScope included, as Coinomi did.` |
| `restore_source_other` | `Another wallet` |
| `restore_source_other_sub` | `Signs in to sites with the standard Digi-ID key.` |
| `restore_source_unsure` | `Not sure` |
| `restore_source_unsure_sub` | `Signs in as this app always has. You can turn on Coinomi-compatible sign-in later in Settings.` |
| `restore_source_continue` | `Continue` |

No option says funds will be watched: nothing watches `watchedAccounts` until step 4. The existing
"Phrase from a different wallet app?" card on `recovery_scan` stays as the way a first-run restore
finds Coinomi's coins.

**Tests first**
- `app/…/ui/onboarding/OnboardingPhraseSourceTest.kt`: first run, each of the four answers gives the
  table's row in the record that `recoverWallet` writes; a "Coinomi" answer writes
  `watched_accounts` = both; no answer, no restore.
- Extend `OnboardingCommitAddTest.kt`: ADD mode writes the answer's row into the new wallet's record
  only. Extend `AddModeGateTest.kt` for the new route.
- Changing the phrase after answering clears the answer. A create never shows the screen and writes
  the created row.
- Navigation: the route is in the setup list, so the lock gate treats it as the other restore steps.
- The two locale gates.

### Task U2: Settings → Digi-ID, the toggle [gated · strings cluster]

**Files**
- Create `app/…/ui/settings/DigiIdSettingsScreen.kt`, `DigiIdSettingsViewModel.kt`; route
  `settings_digiid` in `AppNavigation.kt`; a row in `SettingsScreen.kt` under Security (question 3).
- Uses `WalletManager.setCoinomiSignIn`, `DigiScopeClient.logout()`, `HubWebSocket` close.
- `strings_wallet.xml` ×13; `OnboardingHardcodedStringTest.COVERED` gains the screen;
  `SpendGateWiringTest` gains the action if question 4 is "yes".

**The action, in this order**
1. The dialog says what changes (two bodies: the root changes, or only DigiScope does).
2. PIN or biometric (question 4).
3. End this wallet's Hub session: revoke on the server (best effort), close the socket, erase this
   wallet's token store and read it back empty. If the erase does not read back, stop.
4. One `commit()` of the pair and `previousSiteKeyRoot`. If it does not land, the setting is as it
   was and the screen says so; the user is signed out of the Hub and nothing else moved.
5. If the root changed and question 9 is "yes": end this wallet's Market web session.

Signing out before the commit is the safe order: the other order can leave the old account's token
live while the wallet signs posts with the new key.

| Key | English |
|---|---|
| `set_digiid` | `Digi-ID` |
| `set_digiid_sub` | `How this wallet signs in to sites` |
| `digiid_settings_wallet` | `For %1$s` |
| `digiid_coinomi_signin` | `Coinomi-compatible sign-in` |
| `digiid_coinomi_signin_sub` | `Sign in to sites, DigiScope included, as Coinomi did with this phrase` |
| `digiid_coinomi_on_title` | `Turn on Coinomi-compatible sign-in?` |
| `digiid_coinomi_off_title` | `Turn off Coinomi-compatible sign-in?` |
| `digiid_change_scope_only` | `You will sign in to DigiScope as a different account, and you will be signed out of the Hub now. Other sites are not affected.` |
| `digiid_change_all_sites` | `You will sign in to every site, DigiScope included, as a different account, and you will be signed out of the Hub now. Sign-ins this wallet made the other way will stop working until you switch back.` |
| `digiid_change_confirm` | `Change sign-in` |
| `digiid_change_failed` | `The setting was not changed. You were signed out of the Hub.` |
| `digiid_auth_subtitle` | `Confirm to change how %1$s signs in` |

"Also watch Coinomi's accounts" is not on this screen in 4.1.6. Its three strings are already named
by multi-wallet U3 (`digiid_also_watch_coinomi…`) and ship with step 4.

**Tests first**
- `app/…/ui/settings/CoinomiSignInToggleTest.kt`: each transition of A1's table through the
  view-model; the pair and the saved root land in one commit; `watched_accounts` is byte-identical
  before and after (red arm: the toggle clears it); this wallet's token store is empty afterwards and
  a logout was sent; another wallet's record and token store are byte-identical; a failed token erase
  changes no setting; a failed commit leaves the setting and shows `digiid_change_failed`.
- Which body shows: `digiid_change_all_sites` exactly when `siteKeyRoot` changes.
- After the toggle, with no restart, the approval screen's address and the next signature use the new
  properties (the signer reads them on every call).
- The two locale gates; the spend-gate wiring test if gated.

### Task T1: pre-publish scenario I (test-only)

- `scripts/pre-publish-test.sh`, `scripts/test-helpers/…`: restore `abandon…about` answering
  "Coinomi"; open `digiid://api.digiscope.me/api/auth/digiid/callback?x=<nonce>`; assert the approval
  screen shows `DSsC7WWi7mgr4t7xEw9kmcZqgQq6YJjdri`; press Deny. Then turn the toggle off and assert
  `DGAf4MmtdP6D6QY1KREaznT3DZwxeAkWyn` on the same request.
- Nothing is signed or sent, so it needs no network and no account.
- `--self-test` gets a canned screen with the wrong address that must say FAIL.
  `PREPUB_SABOTAGE=I` restores as "HoldFast", and the scenario must then FAIL.
- The script header describes it; `CLAUDE.md`'s "8 scenarios (A to H)" becomes nine.

**Release gate beside it (not a PR).** One check against DigiScope with the public test phrase on
the Note 8 mule: restored as Coinomi, quick login lands in the account Coinomi's login made on
2026-11-17 (the account whose primary address is `DSsC7WWi…`), and a restart keeps it.

### Task C-doc: the core's comment, then the pin (optional, last)

`BRDigiId.h`'s rules say "Neither app calls it yet" and name `IdentityKeyPolicy.kt`. After A3 that is
false for Android. Comment-only core PR; then an Android pin PR to that commit once its `develop` run
is green (rule 7). It may ride the next pin move instead.

### Task D1: docs

- `docs/handoffs/2026-11-xx-coinomi-steps-2-3.md` for the iOS session: the four keys and their value
  names, the table, the toggle's transitions, the choice-driven call shape, the restore question's
  wording, and that a Coinomi phrase signs differently on iOS until iOS carries the properties.
- The design gets a banner: steps 2 and 3 shipped, with the deviations the owner approved below.
- `docs/handoffs/2026-11-17-android-q4a.md`: its "Left as it is" note on `identityAddress` is closed.

---

## 4. Localisation

Every key above ships in all 13 languages of `AppLocale.SUPPORTED` in the PR that adds its screen
(U3: 3 keys; U1: 11; U2: 12). Three places stay in step: `AppLocale.SUPPORTED`,
`xml/locales_config.xml`, the `values-xx/` directories (`values-b+pt+BR`, `values-b+fil` spellings).
"Coinomi", "DigiScope" and "Digi-ID" are names and stay untranslated. The app's own name is passed
as an argument (`app_name_display`), never written into a string. Both new screens join
`OnboardingHardcodedStringTest.COVERED`. The two identity warnings (`digiid_change_scope_only`,
`digiid_change_all_sites`) and the restore options decide which account a user reaches, so they go
on the native-speaker review list with the seed and wipe warnings. The strings cluster allows one
open PR at a time: U3, then U1, then U2.

---

## 5. Where the design is ambiguous or stale against today's code

1. **No core work is left.** The design's "Before step 2" items (Q4 A on Android; the core's choice)
   are both merged and pinned. Its "Core" note "exists once step 1's core PR merges" is past.
2. **The native shape predates the choice.** The design moves "the bridge's two per-site natives" and
   gates `signMessage` / `signIdentityMessage` call sites. With `BRDigiIdChooseIdentity` in the core,
   and the design's own rule that the whole choice lives there, the natives should take host,
   callback and the two properties (question 2).
3. **"Each wallet record"** does not say which store. The seed record, the registry entry and a new
   store all fit the words (question 1).
4. **"Settings → Digi-ID" does not exist** (question 3).
5. **`watchedAccounts` has no stored form.** This plan names two values; multi-wallet C15 reserves
   bits `0x1` and `0x2` for the same two accounts, and B9/U3 read this store.
6. **A wrong restore answer has no way back.** No toggle state returns a wallet restored as
   "Coinomi" or "another wallet" to the `DIGIBYTE` root (question 5).
7. **"For a Coinomi-mode wallet the screen also says so"**: a `STANDARD`+`SHARED` wallet signs other
   sites with Coinomi's keys but is not "Coinomi-compatible sign-in" as the UI defines it
   (question 6).
8. **An unreadable stored value** is not covered; "missing reads as the upgrade row" is (question 7).
9. **"Not sure" and no answer** are different things; the design lists only the first (question 8).
10. **The Market is not in the design.** Sign-in moved to `id.digistamp.co` on 2026-11-21. A wallet's
    identity there is per-site, so it moves with `siteKeyRoot`, while the WebView's cookie session
    stays signed in as the old identity (question 9). Coinomi parity for that callback is not
    measured and is not claimed; its vector is the model's.
11. **Multi-wallet step 1 shipped in 4.1.5.** Hub tokens are per wallet, so "clears this wallet's Hub
    session" is the active scope's store only (ruling G87 keeps logout for this toggle). A switch
    restarts the process, but the toggle does not, so the properties are read on every call. Wallets
    added in 4.1.5 from a Coinomi phrase are on the upgrade row and reach Coinomi's accounts through
    the toggle.
12. **`IdentitySigningSitesGateTest` pins three signing files**; the design's gate names two. A3
    rewrites it to one.
13. **The design's Hub bug** (`HubIdentity.address()`) was never reproduced (question 16).
14. **The Digi-ID history's `derivation` column** says `legacy` or `site` and cannot say which root.
    The row's address already shows it. No schema change is planned.
15. **`ROADMAP.md` item 6** still says the amended design is only in #267 (merged) and does not
    record core #97. A1 fixes it.
16. **The design's "Why HoldFast does not match today"** cites `IdentityKeyPolicy.offersChoice`,
    removed by #268.
17. **iOS.** The contract wants one phrase to sign the same on both apps. Whether iOS stores the
    properties yet was not checked in this run (question 10).
18. **Lanes.** Host-KAT work outside an adoption is the other session's (question 15).
19. **Gate X6** (Mark's review of #264's seed and key changes) is assumed passed because 4.1.5
    shipped multi-wallet step 1. A1 builds on those records; confirm before it merges.
20. **`develop` is behind the release.** P0 must land first, or the first 4.1.6 PRs are cut from a
    tree that says 4.1.4.
21. **Turning the toggle on for a wallet Coinomi never used** makes DigiScope provision a new account
    at the next login, inside its per-IP limit (a 403 when the limit is reached; multi-wallet plan,
    ambiguity 15). The warning text says "a different account"; it does not promise one exists.

---

## 6. Open questions for the owner

1. **Where do the properties live?** Recommended: four keys in the wallet's seed-record file
   (`dgb_wallet_seed[_<id>]`), written in the record's own commit. Truly atomic, already erased by
   every shipped wipe and by removal, no new store name. The alternative, a new per-wallet store,
   needs a reserved name that 4.1.5 never shipped.
2. **The native shape.** Recommended: four choice-driven natives replace `signMessage`,
   `signIdentityMessage` and `identityAddress`, so C makes the choice and signs in one call. The
   alternative is the design's literal text: keep the per-site natives, add a root, and let Kotlin
   route between them.
3. **Where is the toggle?** Recommended: a new "Digi-ID" row under Settings → Security, opening its
   own screen. The alternative is the Digi-ID tab.
4. **Does the toggle ask for the PIN or biometric?** Recommended: yes. It changes which key signs
   and ends a session.
5. **A wrong restore answer.** Recommended: add one plain row under the toggle, "Site sign-in keys:
   DigiByte app / Standard", with the same warning, so every state is reachable. As designed, a
   HoldFast phrase restored as "Coinomi" can only be fixed by removing the wallet and restoring it
   again (a full wipe on a one-wallet phone).
6. **When does the approval screen say "Coinomi-compatible sign-in is on"?** Recommended: exactly
   when the pair is `STANDARD`+`SITE`, matching the toggle. The address row shows the truth in every
   other state.
7. **A stored value this build cannot read.** Recommended: sign nothing for that wallet and say so.
   Falling back to the upgrade row would move a Coinomi wallet's identity without a word.
8. **The restore question's default.** Recommended: nothing preselected and Continue disabled until
   one of the four is chosen; "Not sure" is an explicit answer.
9. **When the site-key root changes, end the wallet's Market web session too?** Recommended: yes,
   only when the root changes, so no session stays open as an identity the wallet no longer signs
   with.
10. **Ship on Android before iOS stores the properties?** Recommended: yes, with the hand-off (D1).
    Until iOS follows, a Coinomi phrase reaches Coinomi's accounts on Android only.
11. **The shared identity's signing path.** Recommended: leave it on the bridge's `sign_with_key` in
    4.1.6. Moving it onto `BRDigiIdSignShared` re-anchors `msg_len_kat` and
    `identity_sign_bytes_kat` for no change in output; the golden file holds the two equal meanwhile.
12. **Review batches.** Recommended: M-A (A1, A2, A3) and M-U (U1, U2) wait for you; A0, U3, T1,
    C-doc and D1 merge on agent review and green CI.
13. **Pre-publish scenario I as a release gate, and the DigiScope check on the mule.** Recommended:
    yes to both; I run the mule check with the public test phrase.
14. **Refuse a login whose signing address differs from the one shown?** Recommended: yes.
15. **Who makes the host-KAT changes?** Recommended: the Android session, as part of this adoption,
    as you ruled for step 1.
16. **The chat's own-message address.** Recommended: fix it in A3, after reproducing it on a device;
    if it does not reproduce, the PR says "aligned", not "fixed".
