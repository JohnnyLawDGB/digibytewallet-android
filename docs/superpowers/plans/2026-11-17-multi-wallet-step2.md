# Multi-wallet Step 2 Implementation Plan

> **Pre-flight disposition (2026-11-19).** Task A2's conflict scan is done. Five area scanners
> (core engine and band; bridge; app; security and owner gates; tests, landing and process) checked
> this plan against the amended design, R1-R8, D1-D4, the code (`develop` 52aa40000, core fc35768)
> and the project's invariants, and found 134 conflicts. Conflicts 0-57 were each judged by
> skeptics; 58-133 were verified by the area consolidators against the cited lines. The result is
> 94 rulings, **G1-G94**, over 122 conflicts; 8 conflicts dropped as not real; 1 duplicate
> folded; 3 owner questions (O14-O16, in "Open for Mark"). The rulings, the dropped list and the
> evidence are in `docs/superpowers/specs/2026-11-17-multi-wallet-step2-rulings.md`.
> An independent review of the rulings on conflicts 58-133 (2026-11-19) returned 29 findings, all
> upheld: 26 rulings were amended in place, three contradictions between rulings were resolved,
> and one owner question was added (O17). The rulings file's "Reviewed" entry lists them.
> Two owner answers of 2026-11-19 then added two rulings: **G95** (O2: removing the active wallet
> revokes its Hub session; task X-HUB is built) and **G96** (O7: the shared chain lives under the
> legacy wallet's 4.1.4 names, which reverses PD13's names and its migration). Each task G96
> changes carries an "**Amended by O7**" note. Four points G96 leaves to be designed are listed in
> the ruling and in task E1; none is decided in this plan. Of O1-O17 none is open in step 2: O9 was
> answered in direction on 2026-11-19 (the phrase first) and its multi-wallet rule is open as Q1 of
> the design (Mark answered its Q1-Q6 on 2026-11-19).
> **Where this plan's text and a ruling differ, the ruling wins.**
> - Each affected task and section carries a "**Rulings:**" line. Read those rulings before the
>   task's text.
> - Task text is edited only where a ruling reverses a PD or the task order; each such edit names
>   its ruling. Every other change a ruling makes to a task is in the rulings file only.
> - The rulings add five tasks: CKPT, K8a, L1, B8a and U5b (stubs below, in place), and PD28.
> - The rulings are numbered G, not "S2-F" as the Pre-flight paragraph below says. On a
>   "**Rulings:**" line and in every "Amended by" note a G number is a ruling, never one of the
>   Stage 1 tasks G1-G6.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task is one pull request unless it says otherwise.

**Goal:** One compact-filter engine for every wallet of a network, and an in-process instant switch, shipped together in one release (R2). A wallet that lags the shared engine catches up in a temporary one-wallet band and is handed over under a core rule (R3). A wallet that is not active syncs watch-only from its stored public master keys (R4) and does no server work until it is active (R6). Step 2 also brings MAX for DGB, "catching up" wording instead of first-sync wording, and Coinomi step 4.

**Architecture:**
- **Core.** `BRPeerManager` holds a bounded set of wallets in a slot array. Each wallet-facing call is either "for each wallet" or "for the wallet that owns the transaction". The engine keeps one `cfLedger`, matched against the union of the attached wallets' elements (R3). A new holding constructor serves the shared engine and the band. The existing constructors stay one-wallet sets that compute exactly today's results, for iOS and the host KATs. The hand-off rule, the band cap and order, the recovery scope, the rebuild sequences, the catching-up stage, the key-record format and the send-all builder are core rules, each with a host KAT.
- **Bridge.** Per-wallet contexts hold the wallet, its public keys and its creation time. There is one sync context per manager, each with its own guard. One static seed buffer is owned by the active wallet's context. Natives take a wallet handle.
- **App.**
  - A `WalletSessions` holder replaces the per-wallet singletons.
  - `SyncService` runs the shared engine and at most one band.
  - Every READY wallet has a durable frontier record.
  - Server work for a wallet that is not active waits (R6).
  - ViewModels rebind to the active session.
  - The switch zeroes the outgoing seed and loads the incoming one behind the app PIN (R5).
- **Landing.**
  - Test-only gates go on `develop` now.
  - Core PRs land after the 4.1.5 cut, each followed by its own pin PR.
  - Behaviour-neutral Android refactors go on `develop` after the cut.
  - Every behaviour change goes on a CI-enabled integration branch, which is merged into `develop` once.

**Tech Stack:** C (core and bridge), host KATs under `native/src/test/host` (gcc/clang, ASan/LSan/TSan, `-m32`), Kotlin, Jetpack Compose, Hilt, Room/SQLCipher, AndroidKeyStore. Tests: JUnit4 + MockK, with the existing JVM harnesses `wipeTestContext`, `InMemoryPrefsFactory`, `FakeVault` and `keyStoreManagerOver`. Scripts: `scripts/run-host-kats.sh`, `scripts/gen-jni-registry.py`, `scripts/check-kat-level-pins.sh`, `scripts/check-submodule-pin.sh`.

**Spec:** `docs/superpowers/specs/2026-11-16-multi-wallet-design.md`, Step 2 as amended on 2026-11-17 (`e227b4e60`: "Step 2 amendment"; it wins where older text disagrees). Coinomi step 4 is specified in `docs/superpowers/specs/2026-11-17-coinomi-digiid-design.md`, as amended on PR #267.

**Research:** the 2026-11-17 maps, critic and answers. Every decision they support is restated in this plan or in its rulings file, so no task depends on reading them.

**Pre-flight:** Task A2 runs the conflict scan before any code. Its rulings (S2-F1 onwards) go into the rulings file, and the same PR edits every task a ruling changes. Where a task says "(PDn)", that decision of this plan is the reason (see "Decisions this plan takes").

**Path legend:**

| Prefix | Means |
|---|---|
| `core:` | `native/src/main/jni/digibytewallet-core/` (the core repo) |
| `bridge:` | `native/src/main/jni/bridge/` |
| `host:` | `native/src/test/host/` |
| `kt-core:` / `kt-core-test:` | `core/src/{main,test}/java/app/aroundtheblock/wallet/core/` |
| `kt-core-at:` | `core/src/androidTest/java/app/aroundtheblock/wallet/core/` (compiled in CI, not run) |
| `kt-app:` / `kt-app-test:` | `app/src/{main,test}/java/app/aroundtheblock/wallet/` |
| `native-at:` | `native/src/androidTest/java/app/aroundtheblock/wallet/` (not compiled in CI until A3 merges, and never run there: G19) |

## Rulings this plan builds on

**Owner rulings (Mark, 2026-11-17).** These are recorded in the design amendment and carried here as given. No task re-opens them.

- **R1.** This Android session builds the step-2 core engine and its new host KATs, plus the bridge, app, Gradle and CMake work, with a hand-off file for the iOS-and-core session. This is an exception to `docs/development-process.md:83-86`. Rules 3, 4, 6, 7 and 8 are unchanged. See "Lanes and hand-offs".
- **R2.** One release: the shared engine and the in-process switch together. No 2a/2b split.
- **R3.** Back-scan is a temporary band engine:
  - one band at a time;
  - the device never holds more than 8 peers;
  - one `cfLedger` on the shared engine, union-matched;
  - the 12 `LowestNeededHeight` sites, the roughly 520 `m->cfLedger` KAT references and the 49 white-box suites stay unchanged.
- **R4.** Each wallet's public master keys are stored encrypted under an unbound Keystore key. That is the three trees of `BRWalletMasterKeys`, plus Coinomi's two account keys when `watchedAccounts` asks for them. No address lists. They are written at create, restore and add, and at each wallet's next unlock as active. Removal and the wipe delete them.
- **R5.** Seed lifetime is unchanged: the active seed stays resident until a switch or the wipe. The switch zeroes the outgoing seed and loads the incoming one behind the app PIN.
- **R6.** Server work for a wallet that is not active waits until it is active. A wallet that is not active does chain work on the device only.
- **R7.** 4.1.5 ships step 1 after its gap fixes. PR #264 stays on `develop`, but nothing ships it until Mark has reviewed its seed and key changes. Step 2 is not in 4.1.5.
- **R8.** Changes of #264's kind wait for Mark at merge, even with a green review and CI. That covers key material at rest and the seed swap on a switch. Every such task is marked **[R8]**.

**Lead defaults (2026-11-17; the owner may override):**

- **D1.** Watch-only wallets attach only in a process the PIN has unlocked.
- **D2.** The in-process switch restarts only if this process has created a WebView or used `CookieManager` (a process latch). Otherwise it re-runs `WebSessionIsolation` in-process.
- **D3.** WebView `MULTI_PROFILE` is checked on the Note 8 and the Moto G 5G.
- **D4.** "Rescan this wallet" becomes a band for one wallet, and "Repair the chain" a near-tip recreate plus a full-locator getheaders. `BRPeerManagerRescan` gets no Android caller.

This plan's own decisions are PD1-PD28, listed below. A1 records them, one line each, in the rulings file.

## Global Constraints

**Rulings:** G1, G2, G3, G4, G5, G11, G26, G61, G76, G96

- **The core change is additive and one-wallet neutral.**
  - No public signature, struct, field order, enum value, constant or blob format that iOS builds against changes. G6 freezes them.
  - A manager built through the existing constructors computes exactly today's results. G5 and every white-box suite prove it, with no assertion edited; seam re-anchors are allowed, and each is shown still red.
  - A change to what a one-wallet manager computes ships in neither app until both pins carry it (rule 4; the iOS contract §4 item 3). This plan makes none (PD10).
- **Funds in the core.** Selection, signing, building a transaction, the hand-off rule (it decides what a wallet holds), recovery scope, rebuild order and the sync stage are core rules with host KATs. A Kotlin mirror exists only where a test binds it to the core.
- **New core code is header-only or goes inside an existing `.c` file.** The KAT runners list their sources by hand: 124 list `BRWallet.c`, 53 the `BRPeerManager.c` closure. A new `.c` would also need `native/CMakeLists.txt`.
- **Red arms.**
  - Every new arm is named `*_UNFIXED`. The two existing `CF_ELEMS_CACHE_*` arms keep their names (PD21).
  - Every new suite builds 64-bit, `-m32` and ASan+LSan wherever its harness allows. The app ships armeabi-v7a.
  - Every arm expected to fault runs under `bounded()`. An arm that must die on a signal under ASan sets `ASAN_OPTIONS=abort_on_error=1`.
- **Header-check level.** A suite copied from `cf_reorg_rewind_kat` or `cf_scan_ledger_drive_kat` inherits `-DDGB_HEADER_DIFF_CHECK=1`. Re-derive it: build at the shipped level (`cf_gap_rescan_kat`'s `shipped_level`) unless the suite proves the pin is load-bearing. Run `scripts/check-kat-level-pins.sh` on every new suite before its PR merges.
- **A wallet is in exactly one place:** attached to the shared engine, held by the band, or not attached (waiting for a band or for its key record). Never in two managers.
- **Seed.**
  - There is one `static uint8_t g_seed[64]` in `bridge:jni_wallet.c`, reached only through the accessors and owned by the active wallet's context (B4).
  - Mnemonics and passphrases are `ByteArray`s, zeroed in `finally`. Bridge buffers are zeroed with `secure_zero`, and a caller's array is never written through.
  - No new code logs a phrase, seed, key, fingerprint or address.
  - Lifetime as R5.
- **The shared chain is never deleted for one wallet.** No removal deletes it (legacy's included), and neither does a rescan or a per-wallet recovery reason. Only the full wipe and a chain-scoped recovery reason do (C11).
- **The legacy wallet keeps every 4.1.4 name.** Step 2 only adds names, from A1's reserved list. No Room schema change.
  - **Amended by O7 (2026-11-19, G96):** the shared chain itself lives under legacy's 4.1.4 chain names (PD13). Step 2 adds no chain file and no chain prefs file, and a 4.1.4 upgrade moves no file. So a per-wallet clear, a rescan, a hand-off or the removal of legacy must leave those files and keys in place; only the full wipe and a chain-scoped recovery reason erase them.
- **No address set leaves the device by default.** A native that reveals addresses answers for one wallet, never for the union. While a wallet is not active, nothing is sent to a server for it (K5).
- **Every new or changed user-facing string ships in all 13 locales of `AppLocale.SUPPORTED` in the same commit**, under `values*/strings_wallet.xml`. Every screen that gains text is added to `OnboardingHardcodedStringTest.COVERED`.
- **Process.**
  - Every PR needs CI green on its head and an independent review posted on the PR (`docs/development-process.md` rules 1 and 2). An [R8] PR also waits for Mark at merge, and only after X6.
  - Merge commit or fast-forward, never a squash. Core work lands by merge, never by cherry-pick (`RELEASING.md` §3).
  - Before every commit, run the full suites and the affected KATs. Commit only on exit 0, and never chain the commit after an `echo`.
  - Stage with explicit paths, in a worktree; the `check-worktree-target.sh` hook refuses commits in the main checkout.
- **Status moves with the code.** Each PR edits its ROADMAP sub-item. Each core PR adds its line to the hand-off file.
- **Tracks that touch the same files never run at the same time** (clusters in "Order against the work in flight").
- **Devices.** Device work runs only on emulators, the Note 8 (SM-N950U) or the Moto G 5G, never the Galaxy S25 Ultra. Device records hold "match"/"mismatch" and counts only; figures go to the private repo (F32).

**Standard steps** (tasks refer to these by name):

- **Full suites:** `./gradlew --continue :core:testMainnetDebugUnitTest :app:testMainnetPlayDebugUnitTest :app:testMainnetSideloadDebugUnitTest`, exit 0.
- **KAT sweep:** `scripts/run-host-kats.sh` runs every suite and every arm, ASan and `-m32` included, with zero failures. Then `./gradlew :native:assembleMainnetDebug :app:assembleMainnetDebug` for the NDK build.
- **Core landing:**
  1. A core PR whose body has an `Android-Branch:` line naming the companion branch that carries the new KAT. Core CI runs that companion's KATs on the PR head.
  2. Merge by merge commit, then wait until core `develop`'s push run is green against Android `develop` (rule 7).
  3. The companion becomes the Android pin+KAT PR. It also carries G6's grown snapshot and, after the suite's first green run, its line in both kat-shards timing files. `scripts/check-submodule-pin.sh` passes.
  4. Add a status line to the hand-off file.
  5. The next core PR of the same track merges only after this pin+KAT PR is on Android `develop`, so core `develop`'s merge runs always see every step-2 KAT so far.
- **Registry:** merge the PR's base into the PR branch: `develop` for a `develop` PR; for an integration-branch PR, first merge `develop` into the integration branch, then the integration branch into the PR branch. Run `python3 scripts/gen-jni-registry.py`, push, and wait for CI. `--check` passes, and the `native-at:` NativeBridge copy carries identical signatures. One registry PR at a time across both sessions; the session that merges second regenerates.

## Review Focus

**Rulings:** G61, G72, G75, G96

These are the failure modes most likely to reach a user that ordinary tests would not exercise. Each has a named test in the task that owns it.

1. **A missed `manager->wallet` site serves only slot 0.** Every other wallet then silently misses its receives.
   - C3 deletes the field, so the compiler lists every site, and its `pm_wallet_sites_kat` fails on an untagged call.
   - C5's `pm_multi_wallet_credit_kat`: `a block paying only B is credited to B and never to A`.
2. **A per-wallet reason or a removal deletes the shared chain.** Every wallet then climbs again; the measured class is 24,052,509 to 22,650,000, about 6 hours.
   - K0's split, red on `develop`.
   - C11's `CF_RECOVERY_WALLET_RESET_SHARED_UNFIXED`.
   - E1's `WalletRemovalTest`: `removing legacy keeps the shared chain`.
   - E4's `RebuildSplitTest`.
   - Since G96 the shared chain is the legacy wallet's own 4.1.4 files, so the nearest way to this failure is a clear, rescan, hand-off or removal of legacy that still treats them as legacy's. Today `clearSyncData` clears the whole of `dgb_sync_data<net>` and deletes all three files (`kt-core:WalletManager.kt:1854-1855`, `:1866`, `:1874-1875`). K0's and E1's legacy cases.
3. **The shared ledger is persisted ahead of an attached wallet's saved history.** After a crash, a receive is silently missed. E2's `SharedLedgerCheckpointGateTest`, red on `develop`'s single-wallet gate.
4. **A wallet attaches at the shared frontier after the ledger moved on without it.** The heights in between are never evaluated for it. E2's `FrontierRecordTest`: `a wallet not attached this session rejoins at its own frontier and the receive in between is found`. Red arm: attach at the shared frontier (PD14).
5. **A hand-off with no rewind, past a hole, or across a fork.** C9's arms and C10's `band_handoff_engine_kat`: `a payment at a height in (F, requestedThrough] is found only with the rewind`.
6. **A wallet in two managers, or a parked manager reaching a wallet.** C8's `PEER_PARK_KEEPS_WALLETS_UNFIXED` and `MW_ATTACH_NO_HOLD_CHECK_UNFIXED`, B6's `wallet_hold_kat`, and B7's `band_context_kat`.
7. **Two seeds resident at a switch, or none, or a sign for the wrong wallet.** B4's `seed_owner_kat`, B8's `seed_activate_kat`, and S3's `SwitchSeedOrderTest`.
8. **Asset destruction or misattribution once several sessions exist.**
   - K4's `SpendPreflightPerSessionTest`: the last `AssetManager` built must not guard the active wallet's spends.
   - K6's `AssetSweepHandleTest`: B's sweep with A active writes no A row into B and prunes none of B's rows.
   - E2b's `ExclusionReplayTest`.
9. **Server work for wallets that are not active links them by IP and time.** K5's `NonActiveServerWorkGateTest` and its source gate.
10. **After an in-process switch, B shows A's list, balance, phrase or Digi-ID choice.** K7's `SwitchRebindTest` and `SeedViewSessionTest`.
11. **A Hub or web session leaks across a switch.** K8's `HubSwitchTest` and `WebViewLatchTest`.
12. **Upgrade from 4.1.4, and from the 4.1.5 step-1 layout.**
    - E1's `UpgradeFrom414Test` and `ChainMigrationTest`.
    - E3's `MigrationBandsEndToEndTest`: one wallet days behind, one weeks behind.
    - V1's device checks.
13. **Removing a wallet that is not active races its live writers.** E7's `WalletRemovalTest`: `nothing writes the wallet's files after a verified erase`.
14. **The wipe while a band runs.** E3's `WipeStopsBandTest`: no band writer recreates a file after the wipe's verified erase.

## Lanes and hand-offs (R1)

**Rulings:** G5, G6, G17

**What this session builds** (R1, recorded with its date in the rulings file and the hand-off):
- the core PRs C1-C15 in the core repo;
- every new host KAT in this plan (G1-G6 and each C and B task's suites);
- the timing lines for those suites in both kat-shards copies: the core's `.github/kat-shards/timings.txt` and this repo's `.github/kat-shards/timings.txt`.

`development-process.md:86` gives the shard lists to the iOS-and-core session. This plan reads R1 to cover two things R1 does not spell out:
- the timing lines of the suites this session adds, because a suite is not done until it is timed. It edits nothing else in the shard lists;
- the gates G1-G6, which are the step-2 engine's own gates.

A1 records both readings in the rulings file beside R1, and the hand-off asks the iOS-and-core session to say so if it reads R1 otherwise. The bridge, app, Gradle, CMake and the adoption re-anchors are this session's anyway (`:78-82`).

**Unchanged by R1:**
- core PR first, with its host KAT, then the pins (rules 3, 4, 7 and 8);
- one registry PR at a time across both sessions (rule 6);
- only the iOS-and-core session touches the test iPhone or moves the iOS pin;
- `docs/development-process.md` is not edited (its rules are Mark's).

**The hand-off file** is `docs/handoffs/2026-11-17-multi-wallet-step2-core.md`, opened by A1, finished by V2. It is the only channel to the other session; there are no chat summaries. Its sections:

1. **The contract.**
   - Additive only, frozen by `core_api_freeze_kat` (G6).
   - A one-wallet manager built through the existing constructors computes today's results (G5 and the white-box suites).
   - The holding constructor (C8) and the band are Android's until iOS adopts them.
   - `BRPeerManagerRescan` and `BRChainRebuildSteps` stay.
2. **The last core commit before step 2.** It is named the day C3 merges, so iOS can stay below it through its 12-10 submission. Moving past C3 is safe by construction, but it is the iOS session's call.
3. **One status line per core PR:** PR number, merge commit, core `develop` run, Android pin PR, the Mac arms (item 4), and any iOS action.
4. **Mac-only arms.** About 21 suites' `run.sh` files carry `xcrun` iOS arm64 strict-compile and Swift-import checks, which Linux CI skips (`digiid_core_kat/run.sh:201-215`). For each step-2 core PR that touches a header iOS includes, this session runs the Linux strict compile (`-std=c11 -Wall -Wextra -Wpedantic -Werror`, 64- and 32-bit). The hand-off asks the iOS-and-core session to run the Mac arms on that commit before its pin passes it, and to record the result.
5. **New headers stay out of iOS `PUBLIC_HEADERS`** (`build-core-xcframework.sh` in the iOS repo) until iOS adopts them: `BRWalletKeyRecord.h`, `BRDigiDollarReceiveKey.h`, `BRBandHandoff.h`.
6. **Parity items for iOS:** the catching-up stage (C14), send-all (C13), the rescan and repair sequences (C12), the recovery scope (C11), the Coinomi chains (C15), and the key-record format (C1, PD9). iOS may keep its 212-byte `MasterKeysRecord`; C1 records whether v1 matches it.
7. **Fixed in step 2:** the stale `SetFixedPeer` claims at `core:BRPeerManager.c:8061-8063` and `host:peer_penalty_shotgun_kat_main.c:9-10` (C12).
8. **Noted, not built:** the unbuilt seams `CF_RETENTION_NO_SPAN_CLAMP`, `ASSET_QUANTITY_OVERFLOW_UNGUARDED` and `SAVED_BLOCKS_COUNT_UNGUARDED`, inventoried by G1.

**Booked time on the other side.** The iOS-and-core core lane is booked until the iOS submission (stage 5b 11-23 to 11-30, 5c 11-30 to 12-07, submission 12-10). Nothing in this plan waits on that session except the Mac arms (item 4). Those run at its pace and block only its own pin moves.

## Order against the work in flight

**Rulings:** G7, G8, G9, G10, G11, G22, G26, G76

External gates (in flight elsewhere, not tasks here):

| Gate | What must be true | Blocks |
|---|---|---|
| **X1** | Coinomi step 1 has landed. The core `BRDigiId.h` root on `feat/digiid-site-key-root` is merged; the owner-ruled dispatch run of core CI on `develop` against the companion branch is green (rule 7); its Android pin is merged. Until then core `develop`'s merge runs are red on `digiid_core_kat`. | every step-2 core merge |
| **X2** | PR #267 is merged. It defines `watchedAccounts`: add-only, never cleared by the sign-in toggle, accounts as derived chains with stored account keys. | C15, B9, U3 |
| **X3** | Coinomi steps 2 and 3 are merged. Step 2 is a registry PR that moves `signIdentityMessage` and `identityAddress` onto `BRDigiId.h` and adds `DigiScopeIdentity` and the per-wallet properties. Step 3 is the restore question, the Settings toggle and the approval row, with strings. | B1 and B3 (`jni_wallet_sign.c`), K8, U3, and the head of the registry queue |
| **X4** | The core identity-choice header (`BRDigiIdUsesSharedIdentity`) and its Android read. It follows X1, shares no file with the engine, and is in the registry queue only if it adds a native. | pin order only |
| **X5** | **The 4.1.5 cut.** The train has landed on public `develop` and the landing-sync freeze is lifted on both repos (`RELEASING.md` §3). On `develop`: the train's rows and the step-1 gap fixes `fix/wallets-ready-slot-and-gate-hygiene` and `fix/wallets-device-wide-settings`. The rows are named only in the private scope file, never in this plan: the 4.1.5 scope stays private until it ships (`ROADMAP.md`). Because every Android production slice and every core merge waits for X5, none of them runs alongside the train's changes, whatever files those touch. | every core merge (O4); every Android production slice |
| **X6** | Mark has reviewed #264's seed and key changes (R7). Step 2 builds on #264's per-wallet aliases and seed records. | the merge of every [R8] task |

Amended by the pre-flight rulings:
- **X3 splits** (G10): X3a (Coinomi step 2 merged) blocks B1, B3, K3, K8 and the head of the registry queue; X3b (step 3 merged) blocks U3 and the strings cluster.
- **T is the first commit on public `develop` that holds both the landed 4.1.5 train with the freeze lifted (X5 as written) and the outcome of Mark's #264 review** (G11, as amended by the review of 2026-11-19: the build commit can predate the landing and sit on no public branch, so it cannot be T). Until T is fixed no step-2 core PR merges and no step-2 production slice or pin reaches `develop`; test-only Stage 1 gates are unaffected. K1-K3 wait for X6 with the [R8] tasks.
- **CKPT**, the checkpoint refresh, may interleave with any C track (G26).
- **X5's sentence** "none of them runs alongside the train's changes" covers production slices and core merges only; a Stage 1 gate may merge before the cut after its private pre-cut check (G22).

Already settled:
- **#268 (Q4 A)** merged into `develop` on 2026-11-17 (`27cc9bf0e`), with `docs/handoffs/2026-11-17-android-q4a.md`. K3 and K8 build on it.
- **Draft #150** (prepared-send adoption) is redone by X150 before MAX.
- **The M9 bridge adoptions** (the iOS hand-off of 2026-11-10: A1's `g_unprovableServerHeight` and the bridge's own gate-header copies) never run alongside X150, B1, B2 or B3 in `jni_transaction.c`. The lead orders them before B1 or after B3.
- **Draft #109** (peer-penalty restore through the core) touches what B7 reads. If it is revived, it lands before B7.

Same-file clusters (never two at once):
- **`core:BRPeerManager.c`:** C3 → C4 → C5 → C6 → C7 → C8 → C10 → C12; C15's pregen edit after C8.
- **`core:BRWallet.c`:** C8's hold field → C13 → C15.
- **`bridge:jni_wallet.c`, `jni_peer.c`, `jni_transaction.c`, `jni_wallet_sign.c`, `jni_bridge.h`** (G7): Coinomi step 2, X150, B1, B2, B3, B4, B5, B6, B7, E6, B8, B9. Never two open at once; among PRs that do not build on each other the order is readiness (G12), and an [R8] PR never holds the cluster: B5, B6, B7 and E6 may overtake B4; B8 may not.
- **`kt-app:service/SyncService.kt`:** K1, K2, K5, K6, E2, B7's caller, E3, E4, E5, E6, E2b, E7, U2, U4.
- **The 13 `strings_wallet.xml`:** whatever 4.1.5 changes there (X5), then Coinomi step 3, then L1, E3, E4, S3, U1, U2, U3, U4, U5, one at a time.
- **Added by G9** (never two members open at once): `WalletManager.kt`: K0, then K2, B6, S1, S2, E2b, E4, U3. `HistoryRebuildOnUpgrade.kt` and `StaleDataWiper.kt`: K0, then K1. `TransactionBuilder.kt` and `SpendPreflightOrderTest`: B3, K4, B5. `AssetManager.kt`: B2, B3, K4, K5, K6. `host:scan_floor_kat`: K0, C12's companion, E4. `host:prepared_send_core_kat`: X150, then C13's companion. `AppNavigation.kt`: K2, K3, E2b. `UnlockScreen.kt`: K2, S2, E5. The lost-PIN PR (outside step 2; `docs/superpowers/specs/2026-11-19-lost-pin-phrase-first-design.md` §5) edits `AppNavigation.kt` and `WalletManager.kt` and is never open beside a member of either cluster; its slot is fixed when it is scheduled. The `SyncService.kt` cluster gains K0 and K3. Added by the review of 2026-11-19, from the tasks' own Files lists: the `SyncService.kt` cluster also gains B2, B3, B6 and S2; `HistoryRebuildOnUpgrade.kt` is K0, then K1, E4; `StaleDataWiper.kt` is K0, then K1, E5.
- **Added by G8:** `AppModule.kt`, `DigiScopeClient.kt`, `DigiIdManager.kt`, `HubIdentity.kt`, `ChatViewModel.kt`, `DigiIdViewModel.kt`: Coinomi step 2, K3, K7, K8. Coinomi step 3 joins this cluster only if its PR edits one of the six files (G8 and G10, as amended by the review).

## Registry PRs (rule 6)

**Rulings:** G12, G78

The step-2 queue, after whatever is open in either session:
1. [Coinomi step 2]
2. [the identity-choice read, if it adds a native]
3. **B2** reads (`develop`)
4. **B3** signer and publish handles (`develop`)
5. **B5** MAX native (`develop`)
6. **B6** wallet set and the rescan deletion (integration)
7. **B7** band natives (integration)
8. **B8** watch-only attach and activation (integration, [R8])
9. **B9** Coinomi account keys (integration, [R8])

An [R8] registry PR takes the merge-`develop`-and-regenerate slot only after Mark's OK, so waiting for him never holds the queue for either session. A later registry PR may overtake an [R8] one only if it does not build on it; B9 builds on B8.

Amended by the pre-flight rulings: the slot goes by readiness, not by the list's numbers. Any ready registry PR may go ahead of an earlier-listed one it does not build on, [R8] or not; B6 does not wait for B5 (G12). **B8a** (`deriveWalletKeys` alone, [R8 · M1]) joins the queue after B3 (G78). A sync of `develop` into the integration branch is a merge-only PR and serves as the Registry step's first merge (G12).

## What waits for Mark

**Rulings:** G1, G7, G78, G95, G96

**[R8] batches**, each put to him as one packet with a summary. M1 goes first, because E2b waits on it:

| Batch | Tasks | What he decides |
|---|---|---|
| **M1** | C1, B8a (G78), S1 | Key material at rest: the record format, its unbound Keystore key, the wipe's reach and read-back (R4) |
| **M2** | B4 (B3 shown for context) | The bridge's seed-owner rule: only the active wallet's context signs or derives |
| **M3** | B8, S2, S3 | The seed-only load and the in-process switch (R5) |
| **M4** | B9, U3 | Coinomi's account keys: derived from the resident seed and stored (R4) |

Also his:
- **V3**, the merge of the integration branch into `develop`, which makes step 2 the next release's content;
- **X-ROLLBACK (c)**, adopted (O10) and narrowed by G96 to the key-record names: a 4.1.5 wipe change;
- **X-HUB**: ruled (O2, G95) and not [R8]. Nothing of it waits for him, unless its PR has to edit a seed or wipe gate;
- **E2b**, only if he overrides D1.

The open questions O1-O17 are listed at the end; after 2026-11-19 none is open in step 2 (O9 was answered in direction that day; its multi-wallet rule was answered that day as Q1 of the lost-PIN design). O15 goes in the M2 packet and O16 in the M3 packet; O17 (whether an allow-list edit to a seed or wipe gate is [R8]) goes in A1's digest, because B6 waits on it. One test decides whether a PR that touches the wipe is [R8] (G1). M2 does not gate stage 4's start (G7).

## Stages, target dates and exit checks

**Rulings:** G7, G11, G14, G15, G22, G93, G95

T is the 4.1.5 cut (X5), which is not known today. A1 writes these into `ROADMAP.md` with dates relative to T, and its follow-up writes absolute dates the day T is known. Mark sets or changes them: scope and target are his. A stage that passes its date by more than half its planned length goes back to him with what slipped, why, and a new date or a cut (`docs/development-process.md`, "An overrun stops for a re-plan").

| Stage | Tasks | Starts | Planned length | Target | Exit check |
|---|---|---|---|---|---|
| 0 Records | A1-A5 | 2026-11-17 | 3 working days | 2026-11-20 | A1 merged; A2's rulings recorded; A3's checks seen on a throwaway PR; A4's and A5's answers recorded |
| 1 Gates | G1-G6 | 2026-11-18 | 7 working days | 2026-11-27, or the day the landing-sync freeze starts if sooner | G1-G6 merged on `develop`, every red arm's run pasted in its PR, core `develop`'s next push run green with them |
| 2 Core | C1-C15 | T (branches from 2026-11-18) | 20 working days | T+20 | every C PR merged on core `develop` and its pin+KAT PR on Android `develop`; G6 green; the white-box suites' assertions unedited; hand-off status lines complete; M1 put to Mark |
| 3 Android, neutral | X150, B1-B5, K0-K8 | T | 20 working days | T+20 | merged on `develop`; full suites and every KAT shard green; with one session, behaviour unchanged (each reviewer checks); M2 put to Mark |
| 4 Integration | B6-B9, E1-E7, E2b, S1-S3, U1-U5, X-ROLLBACK (a), X-HUB (G95) | T+10 | 25 working days | T+35 | merged on the integration branch with green CI and passed reviews; M1-M4 approved |
| 5 Verify and merge | V1-V3 | T+35 | 5 working days | T+40 | V1 recorded on the Note 8 and the Moto G 5G, and on the API 28/33/34/35 emulators; the pre-publish suite passes; V3 merged |

Amended by the pre-flight rulings: T is defined by G11 (see "Order against the work in flight"). Stage 1 and Stage 2 branches start the day A2 merges, Stage 0's CI proof runs on a probe branch, and no target date is written into `ROADMAP.md` before Mark answers O11 (G14). Step-2 merges pause during each train's landing-sync freeze and the stage dates move by the freeze's length (O14). Acceptance rows 2, 7, 8 and 11 change as G75 and G27 say.

## Acceptance index

**Rulings:** G27, G75

The design's test plan (`2026-11-16-multi-wallet-design.md`, "Left to the builder", Test plan) and this plan's own proofs, each owned by one task. Each is red before it is green, on `develop` or under a named `*_UNFIXED` arm.

| # | Proof | Owner |
|---|---|---|
| 1 | Upgrade from 4.1.4 leaves the existing wallet's data untouched | E1 `UpgradeFrom414Test`; V1 |
| 2 | Migration from the step-1 layout, one wallet days behind and one weeks behind | E1 `ChainMigrationTest`; E3 `MigrationBandsEndToEndTest`; V1 |
| 3 | B's transaction is never credited to A | C5 `pm_multi_wallet_credit_kat`; K6 `CrossWalletPersistTest`, `AssetSweepHandleTest`; V1 (A-to-B send) |
| 4 | A band runs while the shared tip advances, and hands its wallet over only under the rule | C9 `band_handoff_kat`; C10 `band_handoff_engine_kat` |
| 5 | A hand-off whose F is at or below the shared requested height rewinds and misses no height | C10 |
| 6 | A wallet is never in two managers | C8 `pm_wallet_detach_kat`; C10; B6 `wallet_hold_kat`; B7 `band_context_kat` |
| 7 | A reorg rewinds every wallet | C4 `pm_multi_wallet_kat`, `cf_reorg_rewind_kat` two-wallet case |
| 8 | The union element cache is invalidated by an attach, a detach and growth in either wallet | G1 (the gate is built for the first time); C7 |
| 9 | Removal keeps the other wallets' files and the shared chain, legacy's removal included | E1 `WalletRemovalTest`; E7 |
| 10 | Every new string ships in all 13 locales | each UI task: `LocaleResourceParityTest`, `OnboardingHardcodedStringTest` |
| 11 | A one-wallet set computes today's results, and the public API only grows | G5, G6, C3-C8 (white-box suites unedited) |
| 12 | Ledger durability: the shared ledger is never ahead of an attached wallet's saved history; frontier records; the cold-start rewind; a kill at every hand-off step | E2, E3, C9 |
| 13 | Exactly one seed after every switch path; a sign for a context that is not the owner is refused | B4, B8, S3 |
| 14 | Server work waits until the wallet is active | K5 |
| 15 | No other wallet's list, balance, phrase, Hub or web session after an in-process switch | K7, K8, S3 |

## Decisions this plan takes (PD1-PD28)

**Rulings:** G12, G20, G30, G31, G32, G36, G37, G38, G41, G42, G49, G65, G68, G69, G72, G80, G81, G84, G85, G86, G93, G96

The design leaves these to the builder, or the code required a choice. Each is a default the owner may override. A1 records them by number.

- **PD1 Switch order.** Zero A's seed, then decrypt B's seed record (its mnemonic, under B's `_v2` key), then activate B. If anything fails, no seed is resident and B shows watch-only with "Unlock to send". That reads R5 and "one resident seed" literally. **Amended by G86:** the app PIN comes first and a declined or failed PIN changes nothing of either wallet; "no seed resident, B watch-only" applies only to failures after the zeroing.
- **PD2 Key mismatch at activation.** If the keys derived from B's seed differ from B's key record, activation is refused, the seed is zeroed, and B stays watch-only. iOS does the same (`SeedStore.swift:327-335`). Mark ruled refuse (O1, 2026-11-19). **Amended by G85:** the refusal applies only to `activateWallet` on a context built from the record (the switch); at a cold-start unlock a record that differs from the seed is marked refused and never stops the unlock.
- **PD3** Removing the active wallet keeps F8's mark-and-restart.
- **PD4** Stranded-send rebroadcast runs for the active wallet only.
- **PD5 Band order.** The active wallet first, then the one fewest blocks behind, then registry order. A running band is preempted (quiesced, never discarded) only when the active wallet changes to one that needs a band. No band runs during a `SyncWorker` catch-up. **Amended by G37:** any band need of the active wallet preempts a non-active wallet's band whenever it arises (a rescan, Coinomi accounts, a lagging frontier), not only at a switch.
- **PD6 Catching-up wording.** It covers every band, a restore's included. Only a first climb on a device where no wallet has ever synced keeps the first-sync wording.
- **PD7 MAX.**
  - It spends the effective-value set to one output, with no change.
  - It refuses below the dust line, over 512 inputs or over `TX_MAX_SIZE`, with "too many coins: send part first". It never sends "the largest set that fits".
  - A custom fee gives an amount of the total minus the typed fee, raised to the relay floor and flagged.
- **PD8 The key record's key.** A new device-wide unbound alias, `dgb_wallet_keys_wrap`.
  - Not `dgb_db_passphrase`: `WalletDatabaseOpener` can re-provision that alias in place, which would silently invalidate every record.
  - Not per-wallet aliases: they carry wallet ids, and the wipe's read-back would have to learn them.
  - Removal deletes the wallet's record. The wipe deletes every record and the alias, and reads both back.
  - **Amended by G80:** the records and the alias count toward a verified wipe (`everythingCleared`), never toward `seedGone` or the owed-wipe release.
- **PD9** The key record is a versioned core format with a KAT (C1). Encryption at rest stays on the platform.
- **PD10** The post-reorg height lag (`core:BRPeerManager.c:4693`; `wallet->blockHeight` stays at the join until the next block) stays as today. Changing it changes one-wallet results and would need both pins.
- **PD11 The integration branch** is `integration/multi-wallet-step2`. It is created from `develop` at the 4.1.5 cut, made CI-enabled by A3, merged with `develop` at least weekly and before every registry PR, and merged into `develop` once (V3). The alternative, a stack of PRs into `develop` merged in one sitting, is O3. **Amended by G12:** each sync of `develop` into it is a merge-only PR (conflict resolution only, CI green, a review of the resolutions).
- **PD12** When the device lock is removed, every `_v2` key is invalidated and spending needs each wallet's phrase. The unbound key records stay, so balances keep showing. Mark ruled keep (O5, 2026-11-19).
- **PD13 Shared chain names and the migration. Reversed by O7 (Mark, 2026-11-19; G96).**
  - The shared chain lives under the legacy wallet's 4.1.4 names, per network: the files `saved_blocks<net>.bin`, `saved_filter_headers<net>.bin` and `saved_cf_ledger<net>.bin`, and the chain keys in `dgb_sync_data<net>`. These are the names `WalletStores` resolves for the legacy scope (`kt-core:wallets/WalletStores.kt:16-17`, `:51-54`; `kt-core:wallets/WalletScope.kt:45-52`), and they are the shared chain's whether or not a legacy wallet exists on the device.
  - The migration moves, links, copies and renames nothing. Legacy's chain is the shared chain as it stands, when it passes today's restore preflight (G69's test, applied to legacy's chain alone). No other wallet's chain is ranked against it, even one that is ahead.
  - Every other wallet's step-1 chain stores become its band files until its hand-off is durable, and are deleted then, with its own chain keys (G38). Its abandonment record stays. A hand-off never deletes anything under legacy's names.
  - **Withdrawn:** "new NETWORK-scoped names (A1's list)", the seed "from the step-1 chain with the highest verified saved tip", the hard link and its copy fallback, and G69's ranking of candidates.
  - **Accepted costs (Mark, 2026-11-19):** the chain starts from legacy's even when another wallet is ahead; on a rollback build legacy reads a ledger that was evaluated while it was not attached, so it can miss receives until it is rescanned; removal, the eraser and every per-wallet clear treat legacy's chain names specially.
  - **To be designed, not decided here** (G96; E1's note has the detail; the first is decided by G97): the names of legacy's own band files (G97: the `_legacy` spelling); which keys of `dgb_sync_data<net>` are the chain's and which are legacy's; what the chain starts from when legacy's names hold none that passes; one store target per file.
- **PD14 Frontier records.**
  - Every READY wallet has a durable frontier per network in the registry: the lowest height not yet proven evaluated for it, with the main-chain hash below it.
  - The record is raised only while the wallet is attached: at a hand-off, and after each durable shared-ledger persist.
  - Every attach goes through the hand-off rule from that frontier.
  - The shared engine may run and persist with no wallet attached.
  - **Amended by G68, G32, G72 and G65:** a record is a (height, hashBelow) pair, or the mark "unscanned"; it is written from the ledger snapshot's own wallet set and may be lowered (a rewind, a refused fork), so "raised only" goes; it rises for a wallet only after that wallet's own checkpoint landed; missing or unreadable means the birth floor.
- **PD15 Holding managers.** The shared engine and the band are built through the new holding constructor. Such a manager detaches every wallet under its lock inside `Free`, before it can park, so a parked manager reaches no wallet and a recreate can re-attach the whole set at once. The existing constructors keep today's `Free` behaviour exactly. **Amended by G31:** `Free` on a holding manager also swaps every callback except `threadCleanup` for a no-op before it can park, and the bridge drops a callback from a manager that is no longer its context's current one.
  - This replaces the design's "attached only once the band manager is gone", with the same guarantee.
- **PD16 Address pregeneration at attach.** The address window is pre-generated before the shared lock, and the same bounded pregeneration runs again inside the critical section. The second run is a no-op unless the set grew, so the design's "pre-generate inside the critical section" still holds. Nothing can grow the set in between: during a hand-off no manager holds the wallet and the bridge has stopped routing its calls.
- **PD17 Band peers.** The band starts from a read-only snapshot of the shared engine's saved peers and penalties, and never writes either. It dials a peer set disjoint from the shared engine's. A4 confirms this or changes it. **Amended by G20:** the plan claims no disjoint peer set until A4 has chosen between no rule and a core exclusion rule.
- **PD18 Chain-level reset.** A reset that drops the shared ledger resets every READY wallet's frontier record to its birth. It restarts as one climb from the lowest attached birth, with every attachable wallet attached. A running band is not folded in: it pauses at cap 0 and hands off under the same rule (verdict WAIT until then) once the climb's chain holds its F-1. **Amended by G42 and G41:** the birth reset applies to a cold-start reset that loses the window and to a wallet whose history was dropped; an in-session recovery that drops the shared ledger lowers attached frontiers only to the re-armed start and starts no band.
- **PD19 `HistoryRebuildOnUpgrade`'s floor.** When every attached wallet needs it, one chain-scoped climb. Otherwise one band for each wallet that needs it, queued in PD5 order and persisted in the registry. **Amended by G84:** the choice is made at attach time; at process start only the per-wallet clear runs, never a chain clear.
- **PD20 Spending during a hand-off.**
  - A new send, asset send, DigiDollar send or sweep for a wallet in its hand-off window is refused with a typed BUSY and "try again in a few seconds".
  - The hand-off waits, within a bound, for publishes already in flight. One still in flight is published again through the shared engine after the attach.
  - Signing-only actions (Digi-ID, Hub) are unaffected.
  - **Amended by G37 and G36:** the typed BUSY, with its own string, also answers a spend from a wallet that no manager holds or whose band is paused; the wait for publishes comes before the band is freed.
- **PD21** The `CF_ELEMS_CACHE_NOINVALIDATE` and `CF_ELEMS_CACHE_COUNT_ONLY` arm names stay through step 2. Renaming them would turn core `develop`'s runs red against Android `develop`.
- **PD22 The lost-PIN path** (`kt-app:ui/navigation/AppNavigation.kt:178-195`, `:345-387`). No watch-only wallet attaches until the new PIN is set and the app reaches Unlocked. The restore deferred there opens only the active wallet from its seed, as today. iOS's D-3 (ask for the phrase first) was put to Mark as O9 and answered on 2026-11-19: Android switches to it (`docs/superpowers/specs/2026-11-19-lost-pin-phrase-first-design.md`; as recommended with the question, outside step 2 and after the 4.1.5 cut). PD22 holds under either flow; under the design's option C, E2b must also skip a wallet that still owes its phrase.
- **PD23** The spend-auth prompt names the wallet that spends. Whether every spend screen names it too is O6. U5 runs only on his answer. **Amended by G93:** U5 (the prompt) is built unconditionally; the per-screen naming (U5b) is built too: Mark answered O6 yes (2026-11-19).
- **PD24 The wallet handle** is a `jlong`: `(slot << 32) | generation`. A stale or unknown handle is refused, never resolved to the active context. **Amended by G49:** handle 0 is reserved; generations start at 1 and skip 0 when they wrap.
- **PD25** No publish-time copies. An A-to-B send shows in B when it confirms (about 15 s), as today.
- **PD26** A detached `BRWallet` is freed only after the core detach has returned. PD15 guarantees that no holding manager still reaches it.
- **PD27 Switching to a wallet with no key record.**
  1. Open a keyless context from its saved transactions, creation time and watched-address pins; load its seed and build the wallet from the seed's keys with that history (G81).
  2. Write its record (S1).
  3. Attach it under the hand-off rule from its frontier record, or band it; never at the tip (G65, G81).

  The engine is never rebuilt.
- **PD28 Relay checks on a holding manager** (G30). The unrelayed-transaction request and the at-tip sweep run only for the wallet the bridge names, the active one (R6); a holding manager with none named runs neither.

## File Structure

**Rulings:** G76 (the String keys table), G96 (`ChainMigration.kt`)

**Created in the core repo** (header-only or inside existing files):

| File | Responsibility | Task |
|---|---|---|
| `core:BRWalletKeyRecord.h` | Versioned key-record format; `BRWalletMasterKeysEqual` | C1 |
| `core:BRDigiDollarReceiveKey.h` | DigiDollar receive key and address from the BIP86 public key | C2 |
| `core:BRBandHandoff.h` | Hand-off rule, frontier, steps as data, cold-start rule, band order and preemption | C9 |

Modified in the core: `BRPeerManager.h/.c` (C3-C8, C10, C12's comment), `BRWallet.h/.c` (C8 hold field, C13, C15), `BRWalletOpen.h` (C15), `BRAddress.h/.c` (C15), `BRWalletFilterElements.c` (C7 hook), `BRPeerCapPolicy.h` (C9), `BRCFRecoveryPolicy.h`, `BRCFRestorePreflight.h` (C11), `BRChainRebuild.h` (C12), `BRSyncFrontier.h` (C14).

**New host KATs** (this repo, `host:`): `core_api_freeze_kat` (G6), `pm_wallet_class_kat` (G5), `dd_receive_key_kat` (G5, C2), `rescan_removal_kat` (G5, B6), `wallet_key_record_kat` (C1), `pm_wallet_sites_kat` (C3), `pm_multi_wallet_kat` (C4), `pm_multi_wallet_credit_kat` (C5), `pm_wallet_detach_kat` (C8), `band_handoff_kat` (C9), `band_handoff_engine_kat` (C10), `send_all_kat` (C13), `wallet_handle_kat` (B2), `seed_owner_kat` (B4), `wallet_hold_kat` (B6), `band_context_kat` (B7), `seed_activate_kat` (B8). Cases are added to the existing suites named in each task.

**Created in this repo** (Kotlin):

| File | Responsibility | Task |
|---|---|---|
| `kt-app:AppLock.kt` | App-wide lock state | K2 |
| `kt-app:session/WalletSessions.kt`, `WalletSession.kt` | One session per READY wallet; active session and generation | K3 |
| `kt-core:sync/ServerWorkGate.kt` | R6: may this session send? | K5 |
| `kt-app:WebViewProcessLatch.kt` | D2 latch | K8 |
| `kt-core:wallets/ChainMigration.kt` | Step-1 to step-2 migration: each wallet's frontier record. It moves no chain file (G96) | E1 |
| `kt-app:service/BandScheduler.kt`, `BandHandoffExecutor.kt` | Band order, cap and preemption; the hand-off steps | E3 |
| `kt-core:sync/EnginePersistence.kt` | Process-level save sink for `SyncService` and `SyncWorker` | E6 |
| `kt-core:wallets/WalletKeyRecordStore.kt` | R4 record at rest | S1 |

**Docs:**
- `docs/superpowers/specs/2026-11-17-multi-wallet-step2-rulings.md` (A1);
- `docs/handoffs/2026-11-17-multi-wallet-step2-core.md` (A1, V2);
- `test-results/multi-wallet-step2/<date>-<device>.md` (V1).

**String keys.** Each is added in the task that first uses it, in all 13 locales. A replaced key is deleted from all 13 files in the same commit.

| Key | English | Task |
|---|---|---|
| `wallet_handoff_busy` | `This wallet is finishing catching up. Try again in a few seconds.` | E3 |
| `reconcile_rescan_title` | `Rescan this wallet` (replaces the hardcoded "Full rebuild from chain") | E4 |
| `reconcile_rescan_body` | `Finds this wallet's history again from its birth date. Your other wallets keep syncing, and the app does not restart.` (replaces `reconcile_rebuild_body`) | E4 |
| `reconcile_rescan_from_label` | `Start from block` | E4 |
| `reconcile_repair_title` | `Repair the chain` | E4 |
| `reconcile_repair_body` | `Reconnects and checks the newest blocks again for every wallet. Nothing is deleted, and the app does not restart.` | E4 |
| `wallet_switch_auth_subtitle` | `Confirm to open %1$s` | S3 |
| `wallet_switch_watch_only` | `%1$s is open to view. Unlock it to send.` | S3 |
| `wallet_switch_unlock_to_send` | `Unlock to send` | S3 |
| `wallet_switch_key_mismatch` | `This wallet's recovery phrase does not match its saved keys, so it stays view-only. Restore it from its recovery phrase.` | S3 |
| `wallet_switch_busy_sending` | `Wait for the send to finish before you switch wallets.` | S3 |
| `wallet_switch_restart_notice` | `Opening %1$s restarts the app, to keep DigiStamp sessions apart.` | S3 |
| `send_available_dgb` | `Available to send: %1$s DGB` | U1 |
| `send_max_left_unconfirmed` | `Payments still confirming stay behind until they confirm.` | U1 |
| `send_max_left_assets` | `Your DigiAssets and DigiDollar stay in this wallet, with no DGB left to pay their fees.` | U1 |
| `send_max_too_many_coins` | `This wallet has too many small coins for one transaction. Send part of the balance first, then the rest.` | U1 |
| `send_max_below_dust` | `The balance is too small to send after the fee.` | U1 |
| `sync_catching_up_title` | `Catching up` | U2 |
| `sync_catching_up_blocks` | plurals `one`: `Catching up · %1$d block`; `other`: `Catching up · %1$d blocks` | U2 |
| `sync_notif_catching_up` | plurals `one`: `Catching up, %1$d block left`; `other`: `Catching up, %1$d blocks left` | U2 |
| `digiid_also_watch_coinomi` | `Also watch Coinomi's accounts` | U3 |
| `digiid_also_watch_coinomi_body` | `Finds DGB that Coinomi received with this phrase, privately on this phone. This wallet catches up from its birth date while your other wallets keep syncing.` | U3 |
| `digiid_watching_coinomi` | `Watching Coinomi's accounts` | U3 |
| `wallet_switcher_waiting` | `Waiting to catch up` | U4 |
| `wallet_switcher_opens_once` | `Syncs after you open it once` | U4 |
| `spend_auth_from_wallet` | `From %1$s` | U5 |

The existing `send_max`, the `KeystoreKeyInvalidated` message and the step-1 switcher keys are reused, never copied.

---

## Stage 0: records, pre-flight, measurements

### Task A1: Land the plan, the rulings file, the core hand-off and the ROADMAP item

**Rulings:** G3, G4, G5, G6, G13, G14, G15, G16, G17, G18, G30, G48, G96

**Landing:** a docs PR from `feat/multi-wallet-step2` into `develop`. It carries the design amendment `e227b4e60` and this plan. It may land before the 4.1.5 cut, outside the landing-sync freeze. Not R8; not a registry PR; no pin.
**Depends on:** none. Land it first: the names it reserves must be fixed before the 4.1.5 cut for X-ROLLBACK (c).

**Status (review of 2026-11-19, G18).** PR #273 is this task's first PR together with A2's: it carries the design amendment, this plan and the rulings file, and nothing else. Still owed, in a second A1 PR (the records PR) that merges before any code task starts: the `CLAUDE.md` correction (G16: lines 76, 82 and 84), the hand-off file (G17), the `ROADMAP.md` item (G15), the rulings file's index of R1-R8, D1-D4 and the PDs, and when and where Mark gave each of R1-R8 (G18). #273 was opened ready for review, not as a draft; before it merges its body says what it carries and names the records PR.

**Files:**
- Create `docs/superpowers/specs/2026-11-17-multi-wallet-step2-rulings.md`:
  - R1-R8 and D1-D4 cited by ID, one line each, pointing at the design amendment;
  - PD1-PD27, one line each;
  - R1's application to the timing lines (see "Lanes and hand-offs");
  - a section for A2's rulings S2-F1 onwards;
  - a section for A4's and A5's answers.
- Create `docs/handoffs/2026-11-17-multi-wallet-step2-core.md` with the sections in "Lanes and hand-offs".
- Modify `ROADMAP.md`. Under the current NEXT, add an item "Multi-wallet step 2" with sub-items: shared engine and bands; instant switch; MAX; catching-up wording; Coinomi step 4. Add the stage table with its exit checks. "Done" names V1's checks.
- This plan.

**Names reserved here.** This is the one copy; E1, S1 and X-ROLLBACK use it.
- **Amended by O7 (2026-11-19, G96): no chain name is reserved.** The two lines that stood here are withdrawn: the files `chain_saved_blocks<net>.bin`, `chain_filter_headers<net>.bin` and `chain_cf_ledger<net>.bin`, and the prefs file `dgb_chain<net>`. No step-2 build writes them. The shared chain uses legacy's 4.1.4 names (PD13): `saved_blocks<net>.bin`, `saved_filter_headers<net>.bin`, `saved_cf_ledger<net>.bin`, and in `dgb_sync_data<net>` the keys `saved_blocks_tip`, `last_chain_tip`, `sync_start_height`, `saved_peers` and `saved_peer_penalties`. The one new chain key, `chain_synced`, is a key in that same file, not a new file.
- **Legacy's own band stores (G97, 2026-11-19; G96's first open point, decided by the lead).** A band for legacy uses the per-wallet name rule with the id spelled out: every WALLET_AND_NETWORK store of `WalletStores` with `_legacy` where another wallet has `_<id>`, before the network part. Reserved here:
  - files `saved_filter_headers_legacy<net>.bin`, `saved_cf_ledger_legacy<net>.bin`, `saved_blocks_legacy<net>.bin` (each with its `.bin.tmp`);
  - prefs `dgb_sync_data_legacy<net>` (the band's chain keys only) and `dgb_cf_abandonment_legacy<net>`.
  - Not reserved: `saved_transactions_legacy`, `dgb_history_rebuild_legacy`, `dgb_reconcile_legacy` and `wallet_legacy.db`. Those stores are the wallet's own, not a chain's, and legacy keeps its 4.1.4 names for them.
  - 4.1.4 never wrote any of these names, `legacy` is not sixteen hex characters, so no wallet id can spell them, and `storageSuffix` stays the one place a suffix is built (the band asks it for legacy's band spelling; no second builder).
  - 4.1.5's wipe erases them on both networks (O10; private row WIPE-RESERVED-NAMES), beside `dgb_wallet_keys*` and the alias below.
- Prefs base `dgb_wallet_keys` (WALLET class: legacy `dgb_wallet_keys`, others `dgb_wallet_keys_<id>`).
- Alias `dgb_wallet_keys_wrap`.
- Registry fields `frontier`, `holder`, `band` (per entry and network) and `chainMigration` (per network). `"v"` stays `1`.
- The planned spellings at the three sites G2's mutants and needle touch, for its scratch copies:
  - C3 rewrites the wallet argument to `PM_ENGINE(manager)`;
  - C6 rewrites it to `PM_OWNER(manager, ptx)` at the publish-list sites and to `w` inside `PM_EACH(manager, w)` at the sweep.

- [ ] **Step 1: Merge** current `develop` (`27cc9bf0e` or later) into `feat/multi-wallet-step2`.
- [ ] **Step 2: Write** the rulings file, the hand-off and the ROADMAP item. Every ruling is cited by ID, and no rule of `development-process.md` is restated.
- [ ] **Step 3: Run** `scripts/check-doc-version.sh`. Expected PASS.
- [ ] **Step 4: Open the PR.** Brief the independent reviewer to check:
  - every cited line against the design amendment, `develop` and core `fc35768`;
  - that every PD has a task that implements it, and every task's dependencies exist;
  - that the ROADMAP item names the tests and device checks that will mark it done.
- [ ] **Step 5: Merge** on green CI and a passed review. Put to Mark in the digest: O3, O4, O7, O8, O10 (time-sensitive: before the 4.1.5 cut) and O11. (He answered all six on 2026-11-19; see "Open for Mark".)

### Task A2: Pre-flight conflict scan, before any code

**Rulings:** G14

**Landing:** a docs PR into `develop` (the rulings file and the task edits). No code task starts before it merges.
**Depends on:** A1.

Step 1's scan found 32 defects in its plan (F1-F32). This one does the same for step 2.

**Status: done by the pre-flight scan of 2026-11-19.** 134 conflicts, 94 rulings (G1-G94, not "S2-F"), 8 dropped, 1 duplicate, 3 owner questions (O14-O16); see the banner at the top and the rulings file. Steps 1 and 2 are done by that scan, with five area scanners in place of one reviewer and skeptics on conflicts 0-57. Step 3: the rulings ride PR #273 with A1's first part (G18). Its independent review of the rulings on conflicts 58-133, which no skeptic had judged, ran on 2026-11-19 against the merged head (c72ad23fa, core d636eb5) and returned 29 findings (13 medium, 16 low; two repeat another). Each was re-checked against its cited source and all 29 were upheld: 26 rulings are amended in place, each marked "Amended by the review" where its rule changed (G1, G2, G3, G8, G9, G10, G11, G16, G17, G18, G19, G22, G26, G27, G29, G52, G56, G63, G64, G69, G76, G79, G81, G86, G93, G94); three contradictions between rulings are resolved (G27 against G3, G29 against G27, G8 against G10); the stale code-line cites are covered by a note in the Evidence header; and one finding needs Mark and is O17. No ruling was withdrawn and none of R1-R8 or D1-D4 was touched. Still owed in step 3: the merge of #273, after its body names what it carries (see A1's status), and A1's records PR.

- [x] **Step 1: Brief a reviewer agent** with no part in writing this plan. Give it:
  - the goal;
  - this plan, the amended design and the Coinomi design;
  - `develop`'s head and core `develop`'s head;
  - what is ruled out: R1-R8 are not re-opened, and D1-D4 change only by Mark's word.

  It returns defects, each with a concrete failure scenario: a task that contradicts the code, a missing dependency, a test that could not fail, a file two tracks touch at once.
- [x] **Step 2: Decide** each defect with one numbered ruling (S2-F1 onwards) in the rulings file, and edit the tasks it changes.
- [ ] **Step 3: Open the PR**, get an independent review (a different agent from step 1), and merge.

### Task A3: CI runs on the integration branch

**Rulings:** G14, G19, G24

**Landing:** a `develop` PR, any time outside the landing-sync freeze. Not R8.
**Depends on:** none.

**Files:**
- Modify `.github/workflows/ci.yml`: add `integration/multi-wallet-step2` to `push.branches` and `pull_request.branches`.
- Modify `.github/workflows/codeql.yml` and `.github/workflows/gitleaks.yml`: the same, for both triggers.

Today all three run only for `main`/`develop`, so PRs into an integration branch would merge with no CI, against rule 1.

- [ ] **Step 1: Make the edits.**
- [ ] **Step 2: Owner action.** Hand Mark a short `! bash ~/x.sh` script that applies `develop`'s ruleset to `integration/**`: a PR required, required status checks, no force-push, no deletion. Agents cannot edit rulesets.
- [ ] **Step 3: Prove it.** Once the branch exists (at T), open a throwaway PR into it. Record in the PR that every `ci.yml` job, CodeQL and gitleaks ran. Close the throwaway.
- [ ] **Step 4: Merge** on green CI and a passed review.

### Task A4: Measure two connections from one device

**Rulings:** G20

**Landing:** no code PR. Results go into the rulings file through a docs PR; figures go to the private repo (F32). Not R8.
**Depends on:** none. It must be answered before B7's peer rules are final.

The design marks this "not measured yet". The answer decides PD17's disjoint-peer default and B7's own-node rule.

- [ ] **Step 1: Canon node.** Two emulators behind one public IP run the `develop` build against the same canon node. Hand Mark a short `! bash ~/x.sh` that reads that node's `getpeerinfo` and the relevant `debug.log` lines, with no restart; ask which node first. Record:
  - whether DigiByte Core accepts the second inbound, evicts it sooner (netgroup eviction) or penalises it;
  - whether the pair counts as one netgroup;
  - whether it adds pressure on the canon's slots.
- [ ] **Step 2: Paired own node.** Repeat against a paired own node with the exclusive pin on. An exclusive pin puts the shared engine and a band on one node.
- [ ] **Step 3: Record** match/mismatch and counts in the rulings file, the figures in `~/dgb-wallet-private/`, and the decision:
  - **disjoint peers** (default), or the measured exception;
  - **refused second connection to an own node:** the band waits ("Waiting to catch up"), and the lead brings the case to Mark with the numbers.

### Task A5: D3, WebView `MULTI_PROFILE` on the Note 8 and the Moto G 5G

**Rulings:** G21

**Landing:** no code PR. Results go into the rulings file. Never run on the Ultra.
**Depends on:** none.

- [ ] **Step 1:** Run `adb shell dumpsys webviewupdate` on both devices and record the WebView provider and version.
- [ ] **Step 2:** In a throwaway debug build that is never committed, add `androidx.webkit` and log `WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)` on both devices. Record match/mismatch.
- [ ] **Step 3:** Record the decision. Step 2 adds no `androidx.webkit` dependency unless both devices support the feature and the lead rules profiles over D2's latch. K8 does not depend on the answer.

## Stage 1: gates on `develop` (test-only; may land before the 4.1.5 cut)

### Task G1: Port the element-cache invalidation gate, building its red arms for the first time

**Rulings:** G5, G6, G22

**Landing:** a test-only `develop` PR. Not R8; not a registry PR. After its first green run, add its timing line to both kat-shards copies (R1).
**Depends on:** none.

**Files:**
- Modify: `host:cf_confirm_kat/cf_confirm_kat_main.c`, `host:cf_confirm_kat/run.sh`.
- Modify: `host:seam_macros_unbuilt_kat/run.sh`.

The cache has shipped since v4.0.31-beta, and no KAT, script or CI job on `develop` builds either arm. The gate exists only on `fix/cf-cursor-freeze` (PR #41, closed unmerged).

- [ ] **Step 1: Port `test6` and `test7`** from `1242ffb38` and `2f13e750c` into `develop`'s file, after `test5`. Leave out `test8`: it calls `_BRPeerManagerDriveCFiltersLocked`, which `develop` lacks. Leave out its `-DDEBUG` too.
  - The arms become RED 3 (`-DCF_ELEMS_CACHE_NOINVALIDATE`) and RED 4 (`-DCF_ELEMS_CACHE_COUNT_ONLY`). Their names stay (PD21).
  - Each case prints a `RESULT <case> pass|fail` line, and `run.sh` declares each arm's must-fail set, as `addr_set_distinct_kat` does.
  - `run.sh` checks that both seam names exist in `core:BRPeerManager.c`.
  - The test holds `MGR_LOCK` around the direct cache calls, which is the function's contract (`BRPeerManager.c:6807-6809`).
  - Add `-m32` and ASan builds. The existing null-guard arm runs with `ASAN_OPTIONS=abort_on_error=1`, so it still dies on its signal.
- [ ] **Step 2: Widen the seam inventory.** `seam_macros_unbuilt_kat` finds seams by the `*_UNFIXED`/`*_RED` pattern, so five seams escape it today: `CF_ELEMS_CACHE_NOINVALIDATE`, `CF_ELEMS_CACHE_COUNT_ONLY`, `CF_RETENTION_NO_SPAN_CLAMP`, `SAVED_BLOCKS_COUNT_UNGUARDED` and `ASSET_QUANTITY_OVERFLOW_UNGUARDED`. Add them as an explicit `EXTRA_SEAMS` list.
  - Each name joins the planted-define comparison arm.
  - Each must still appear in a preprocessor condition under `native/src/main/jni`, so a stale list fails.
- [ ] **Step 3: Run** `host:cf_confirm_kat/run.sh` at 64 and 32 bits. Expected:
  - GREEN on core `fc35768`;
  - NOINVALIDATE fails exactly {test6 stale set, test6 grew, test7 rebuilt};
  - COUNT_ONLY fails exactly {test7 rebuilt}.

  These sets were predicted by reading; the PR records the measured ones, and any other failure set fails the gate.
- [ ] **Step 4: Prove the inventory.** In a scratch build input, add `-DCF_ELEMS_CACHE_NOINVALIDATE`; `seam_macros_unbuilt_kat` turns red. Revert.
- [ ] **Step 5: KAT sweep, then open the PR** with both arms' runs pasted.

### Task G2: Name-keyed gates fail loudly on a rename; mutants ignore the wallet argument's spelling

**Rulings:** G3, G6, G13, G22, G48

**Landing:** a test-only `develop` PR. It must be on Android `develop` before C3 merges in core, or core `develop`'s merge run deadlocks. Not R8: it hardens seed gates and changes no seed code.
**Depends on:** A1 (the planned spellings).

**Files:**
- `host:wallet_read_guard_kat/run.sh`, `host:dandelion_state_kat/run.sh`, `host:tx_publish_ownership_kat/run.sh`, `host:publish_list_ownership_kat/run.sh` (the mutants at `:172-193`), `host:tipsweep_token_stake_kat/run.sh` (the needle at `:89`).
- `kt-core-test:WipeNativeStepsGateTest.kt`, `kt-core-test:security/NativeMemorySecurityTest.kt`, `kt-core-test:security/SeedIsolationTest.kt`.

- [ ] **Step 1: Re-anchor each gate on structure, with a non-vacuity floor.** Each scan counts the anchors it found and fails below today's count.
  - `wallet_read_guard_kat`: the unguarded-reader scan keys on the guard or resolve call, not on the text `g_wallet`.
  - `WipeNativeStepsGateTest`:
    - drop the stale `BRWalletNew` regex and the `?: continue` that makes half of it vacuous;
    - add an explicit, empty allow-list where B6's deliberate detach native will go;
    - the wipe still never unloads a wallet.
  - `dandelion_state_kat:37`: re-anchor the negative check.
  - `NativeMemorySecurityTest`, made exact:
    - exactly one `static uint8_t\s+g_seed\[64\];` in `jni_wallet.c`;
    - no seed-sized array in any struct of a bridge header;
    - the seed symbol used only inside the accessor functions.
  - `tx_publish_ownership_kat`: replace the literal `g_wallet` regexes with the resolve call.
  - `SeedIsolationTest`: the "no seed type on a seed entry point" checks key on parameter types, not names.
- [ ] **Step 2: Make the mutants and the needle spelling-agnostic in the wallet argument.**
  - Each matches any expression in the wallet position, keeps the rest of the statement pinned, and must apply exactly once.
  - Examples: `BRWalletTransactionForHash\(.*, tx->txHash\) != tx\);`, and the sweep's `BRWalletUpdateTransactionsUnproven\(.*, &txHash, 1, height, timestamp\);`.
  - A mutant that applies zero times fails the runner, so the gate cannot go vacuous.
- [ ] **Step 3: Prove each gate red.**
  - A planted violation per gate turns it red: an unguarded reader under a new name, a renamed swap, a second 64-byte seed buffer in `jni_bridge.h`, a reordered wipe.
  - A scratch rename of `g_wallet`, `g_seed` and `g_peerManager` leaves the OLD gate green (vacuous) and turns the NEW gate red (floor).
  - Paste both runs in the PR.
- [ ] **Step 4: Prove the mutants.** On today's core and on scratch copies carrying A1's planned C3 and C6 spellings, each mutant applies exactly once and its arm still fails.
- [ ] **Step 5: Full suites, KAT sweep, then open the PR.**

### Task G3: JNI callback names and descriptors checked at build time

**Rulings:** G46

**Landing:** a test-only `develop` PR. Not R8.
**Depends on:** none.

**Files:**
- Create: `kt-core-test:bridge/NativeCallbackDescriptorGateTest.kt`.
- It reads `bridge:jni_*.c`, `kt-core:bridge/NativeCallback.kt` and `native-at:core/bridge/NativeCallback.kt`.

A `GetMethodID` miss only logs today (`jni_peer.c:1613-1620`), so a changed `onSaveCfLedger` would silently stop every ledger save. B6 and B7 change these descriptors.

- [ ] **Step 1: Write the gate.** Every `GetMethodID(env, cls, "<name>", "<descriptor>")` in the bridge must match a method with that JVM signature in both `NativeCallback` copies.
- [ ] **Step 2: Prove it.** It is red on a planted wrong descriptor, and red on a planted Kotlin parameter-type change. It is green on `develop`.
- [ ] **Step 3: Full suites, then open the PR.**

### Task G4: Fail when `jni_peer.c` drifts from the mirrors the recreate KATs test

**Rulings:** G22, G23

**Landing:** a test-only `develop` PR. Not R8.
**Depends on:** none.

**Files:**
- Modify: `host:recreate_resumes_near_tip_kat/run.sh`, `host:saved_blocks_reentrant_kat/run.sh`.
- Create: `scripts/kat-mirror-hash.sh`, a normaliser that strips comments and whitespace.

Both KATs test hand-copied mirrors of `jni_peer.c`'s `startSync`, `loadSavedBlocks`, `forceReconnect` and `_applyPendingBip158State`, not the real file.

- [ ] **Step 1:** Record each real body's normalised hash beside its mirror. Each run recomputes it and fails with "update the mirror" when a body changed and its recorded hash did not.
- [ ] **Step 2: Prove it.** A scratch edit to one statement of `startSync` turns both red. Green on `develop`.
- [ ] **Step 3: KAT sweep, then open the PR.** From now on, every bridge PR that edits those bodies carries its mirror changes.

### Task G5: Characterise today's one-wallet engine per class, the DigiDollar receive key, and the engine rescan

**Rulings:** G22, G24

**Landing:** a test-only `develop` PR. Not R8.
**Depends on:** none.

**Files:**
- Create: `host:pm_wallet_class_kat/`. It is white-box (`#include` of `BRPeerManager.c`) on `cf_gap_rescan_kat`'s 32-bit-capable BIP158 builder, at the shipped header-check level.
- Create: `host:dd_receive_key_kat/`. It compiles `bridge:jni_wallet.c` with a resident seed.
- Create: `host:rescan_removal_kat/`, part 1.

- [ ] **Step 1: Record today's one-wallet outcome for each of the 15 classes of `manager->wallet` sites:**
  - block heights on block add, reorg and rescan;
  - fee;
  - filter elements;
  - relevance and the register outcome (REGISTER-RESULT);
  - block-delivery confirm or hold;
  - gap growth leading to the gap rescan;
  - the publish entry's owner and owned flag;
  - hasTx, requested and rejected;
  - the unrelayed request;
  - the at-tip sweep's removal and stake rules;
  - remove;
  - server height;
  - reorg un-confirm at the join, with heights left at the join until the next block (PD10).
- [ ] **Step 2: Prove each case is load-bearing.** A `run.sh` sed mutant per class deletes or redirects that class's wallet call on a scratch copy. Each applies exactly once and turns exactly its own case red (bidirectional verification).
- [ ] **Step 3: Pin the DigiDollar key.** Pin `getDigiDollarReceiveAddress` and `getDigiDollarTaprootKeyHex` for the public BIP39 test phrase (`abandon` ×11 `about`, no passphrase) on mainnet and testnet. A mutant deriving index 1 fails, and so does one deriving the internal chain.
- [ ] **Step 4: Pin the engine rescan.** `rescan_removal_kat` part 1, green on today's code: `bridge_syncStopped`'s post-first-sync `BRPeerManagerRescan` leaves the CF ledger bytes and the filter-header chain tip unchanged. B6 adds part 2.
- [ ] **Step 5: KAT sweep, then open the PR.**

### Task G6: Freeze the core API iOS builds against

**Rulings:** G22

**Landing:** a test-only `develop` PR. Each later core PR's companion grows the snapshot. Not R8.
**Depends on:** none.

**Files:** create `host:core_api_freeze_kat/run.sh` and `host:core_api_freeze_kat/api.snapshot`.

- [ ] **Step 1: Write the extractor.** It writes a normalised list of every public prototype, every field list iOS builds or stores, and these constants:
  - `core:BRPeerManager.h`, `core:BRWallet.h`;
  - `core:BRWalletOpen.h`: `BRWalletMasterKeys`, `BR_WALLET_OPEN_TREES`;
  - `core:BRCFRecoveryPolicy.h`: the `BRCFRecoveryReason` values and `BRCFRecoveryDecision`;
  - `core:BRCFRestorePreflight.h`;
  - `core:BRCFScanLedger.h`: the blob version and size constants;
  - `core:BRChainRebuild.h`: the `BRChainRebuildStep` values, the `BR_CHAIN_REBUILD_*` counts and the three step arrays;
  - `core:BRSyncFrontier.h`, `core:BRPeerCapPolicy.h`;
  - `core:BRDerivationProfiles.h`: the table iOS imports.
- [ ] **Step 2: Compare** with the committed snapshot. A removed or changed line fails; an added line passes.
- [ ] **Step 3: Prove it red** on each planted change:
  - an extra parameter on `BRPeerManagerPublishTx`;
  - reordered `BRWalletMasterKeys` fields;
  - a changed `BR_WALLET_OPEN_TREES`;
  - a new field in `BRCFRecoveryDecision`;
  - a value inserted mid-`BRChainRebuildStep`.

  A new function passes. Green on `fc35768`.
- [ ] **Step 4: KAT sweep, then open the PR.**

## Work related to the work in flight

### Task X150: Redo draft #150 from `develop`, before MAX

**Rulings:** G9

**Landing:** a `develop` adoption PR after the 4.1.5 cut. Behaviour-neutral, and not a registry PR. A pin conflict resolves to core `develop` (rule 8). Close #150. Skip this task if the session that owns #150 has already redone it. Not R8.
**Depends on:** X5. The M9 adoptions do not run alongside it.

**Files:**
- `bridge:jni_transaction.c` includes `core:BRPreparedSend.h`.
- Delete `bridge:prepared_send.h`.
- `host:fee_quote_kat/run.sh`, `host:seam_macros_unbuilt_kat/run.sh`, `host:prepared_send_core_kat/`.

#150 is 519 commits behind, has three conflicts and pins an older core (`8f132da`). `prepared_send_core_kat` (Android #260) already holds the core header to the bridge copy's results.

- [ ] **Step 1: Before the delete,** a planted divergence in `core:BRPreparedSend.h` (drop the no-change-output rule) turns `prepared_send_core_kat` red. Revert.
- [ ] **Step 2:** Switch the include, delete the bridge copy, and re-anchor the two runners.
- [ ] **Step 3: After the delete,** the same planted divergence turns `fee_quote_kat` red, which proves the bridge now compiles the core copy. A source check fails while `bridge:prepared_send.h` exists.
- [ ] **Step 4: Full suites and KAT sweep;** `FeeShownIsFeeSignedGateTest` stays green. Open the PR.

### Task X-ROLLBACK: A pulled step-2 release leaves no wrong balance and no orphaned key material

**Rulings:** G1, G25, G38, G68, G96

**Landing:** in three parts.
- (a) Tests on the integration branch, with E1.
- (b) The device check, in V1.
- (c) Only on Mark's yes (O10; given on 2026-11-19): a 4.1.5 step-1 gap PR before the cut. It is a wipe change, so **[R8]**, and a 4.1.5 scope addition (R7).

**Depends on:** A1 (names), E1, S1.

Play cannot downgrade, so a pulled release is replaced by a higher `versionCode` built from older code. That code then reads a step-2 layout.

**Amended by O7 (2026-11-19, G96).** With the shared chain under legacy's names, this task's title no longer holds for the legacy wallet on a rollback build: it can show a balance that misses receives until it is rescanned. Mark was shown that cost and accepted it; in his words, "the sync progress would be lost and they would need a rescan. That's fine". Steps 3 and 4 are rewritten for it. Nothing is built to detect a rollback, or to rescan legacy without the user.

- [ ] **Step 1 (a): Registry.** Write `kt-core-test:wallets/RegistryForwardCompatTest.kt`. Every key and type that `git show v4.1.5:core/src/main/java/app/aroundtheblock/wallet/core/wallets/WalletRegistry.kt` reads keeps its name, type and meaning in a step-2 registry, and `"v"` stays `1`. The keys are copied as literals from that source, as `LegacyStoreNameParityTest` copies 4.1.4's.
- [ ] **Step 2 (a): Layout fixture.** Commit `core/src/test/resources/step2-layout.txt`, listing every name a step-2 install holds (dummy values). V1 uses it.
- [ ] **Step 3 (b): Document** what a rollback build (4.1.5 code, with a bumped `versionCode`) does with a step-2 layout. Write it in the rulings file and the hand-off:
  - every wallet but legacy re-syncs from its birth, because its chain files and chain keys are gone after its hand-off (G38). Funds are not lost;
  - legacy reads the shared chain as its own, because it is under legacy's names. Where the shared ledger advanced while legacy was not attached (no key record yet, a band of its own, a process the PIN had not unlocked), legacy misses the receives at those heights until the user runs 4.1.5's "Full rebuild from chain". This is O7's accepted cost; the record says that the release body of any rollback build must tell users to run it;
  - with no legacy wallet on the device, nothing reads the files under legacy's names;
  - the shared chain is not orphaned: every wipe since 4.1.4 erases legacy's names on both networks, legacy listed or not (`kt-core:WalletManager.kt:1149-1159`; `kt-core:WalletDataEraser.kt:239`, `:257`, `:262`, `:267`);
  - the key records and `dgb_wallet_keys_wrap` are orphaned, and a 4.1.5 wipe leaves them behind unless (c) shipped.
- [ ] **Step 4 (c), on Mark's yes (given, O10):** 4.1.5's full wipe also erases the reserved step-2 names if they exist. **Amended by O7 (G96):** those are now `dgb_wallet_keys*` and the alias `dgb_wallet_keys_wrap` only. `chain_*` files and `dgb_chain*` leave the list, because no step-2 build writes them. Any name reserved later for legacy's own band files (A1) joins it if it is fixed before the cut. `WalletWipeEveryWalletTest` reads them back. A step-1 gap PR, [R8].

### Task X-HUB: Removing the active wallet revokes its Hub session (ruled by Mark, O2; G95)

**Rulings:** G1, G95

**Landing:** the integration branch. It uses no step-2 code, so it could ride a step-1 release instead, but only if Mark adds it to that release's scope (R7).
**Not [R8],** by G1's test as O17 amended it. It changes nothing that the wipe or a removal erases of seed, key or PIN material, none of the wipe's native steps, and no seed or wipe gate's allow-list. It adds one server request before a restart, and the Hub token is erased exactly as today. It becomes [R8] only if its PR has to edit such a gate.
**Depends on:** none. X-HUB and E7 both edit `WalletRemoval.kt`, so they are never open at the same time; the second one merges the first.

**Files:**
- `kt-core:wallets/WalletRemoval.kt`; `kt-app:ui/wallets/WalletsViewModel.kt`.
- `kt-core-test:wallets/WalletRemovalTest.kt`; `kt-app-test:ui/wallets/WalletsViewModelTest.kt`.

Today (this branch's head):
- Removing a wallet that is not active revokes its session before the erase (`WalletRemoval.kt:82-88`).
- Removing the active wallet returns `RemovedRestartNeeded` one line earlier (`WalletRemoval.kt:81`), and the next launch's housekeeping erases its token with no revoke (`WalletHousekeeping.kt:28`). Its session stays live on the server until it expires.
- The wipe revokes every wallet's session in one pass (`WalletManager.kt:1101-1110`), which links them at the server.

What is built (Mark, 2026-11-19): only the active-wallet gap.
- After the REMOVING mark has landed and before `RemovedRestartNeeded` is returned, the running wallet's stored session is revoked. It is best effort, as for a wallet that is not active: a failed or undelivered revoke changes nothing of the removal, and that session expires on its own.
- The request must leave before the process ends. `revokeStoredSession` only queues it (`DigiScopeClient.kt:515`), and the restart follows at once (`WalletsViewModel.kt:191`). The removal gives the request a bounded time to be sent before the restart.
- The running client's in-memory token and socket end with the restart (PD3). If removing the active wallet ever stops restarting, this task's revoke must also close them.
- Removing a wallet that is not active is unchanged. The wipe is unchanged, its one-pass burst included: he was not asked to change it. None of this is covered by R6.

- [ ] **Step 1: Write the failing tests.**
  - `WalletRemovalTest`: `removing the running wallet revokes its Hub session before the restart is asked for`. Red on `develop`: `WalletRemoval.kt:81` returns first.
  - `WalletRemovalTest`: `a failed revoke still returns RemovedRestartNeeded`, and `a wallet that could not be marked is not revoked`.
  - `WalletsViewModelTest`: the restart is not called until the revoke request was sent or the bound has passed.
  - Unchanged and green: `removing a wallet that is not running revokes its Hub session first, and a failed revoke does not stop it`, and the wipe's revoke tests.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR.**

### Task CKPT: The checkpoint refresh, a standing task (added by G26)

**Rulings:** G26

**Landing:** a core PR that regenerates both checkpoint tables. Amended by the review of 2026-11-19 (G26): it is due when a new grid row becomes addable (the chain tip at least 100,000 above the newest row: 24,600,000 for today's table) and never later than 7 days before the red date (2026-11-25; the current table turns red on 2026-12-02), not when `checkpoint_staleness_kat`'s 14-day warning shows, which can show right after a refresh. The generator's default target is the VPS, which this session cannot reach: Mark runs the one command the lead hands him, or it runs against a local node. Its pin move rides the open companion, or its own pin PR when none is open (rule 8). Not R8.
**Depends on:** none. It may interleave with any C track: Core landing step 5 excepts it.

- [ ] The day its pin PR merges, sync `develop` into the integration branch and merge that into every open PR branch. The 4.1.5 train's refresh counts as the first.

## Stage 2: the core (R1: this session; core repo; one pin+KAT PR per core PR)

**Rulings:** G11, G14

All C tasks merge after X1 and X5 (O4: Mark may relax X5 for neutral PRs). Their branches start now.

### Task C1: The versioned wallet key record, and key equality [R8 · M1]

**Rulings:** G17, G44

**Landing:** core landing. **[R8]:** waits for Mark at merge, because it fixes the format of key material at rest (R4); batch M1 with S1; after X6. A pure header, built alongside the `BRPeerManager.c` track.
**Depends on:** X1, G6.

**Files:**
- Create `core:BRWalletKeyRecord.h` (header-only). `BRWalletMasterKeys`, `BRWalletOpenWithKeys` and `BR_WALLET_OPEN_TREES` are untouched.
- Create `host:wallet_key_record_kat/`.
- Add cases to `host:wallet_open_kat/`.
- Grow `host:core_api_freeze_kat/api.snapshot`.

**Interfaces:**
```c
// core:BRWalletKeyRecord.h
#define BR_WALLET_KEY_RECORD_V1        1
#define BR_WALLET_ACCOUNT_COINOMI_44   0x1u   // m/44'/20'/0', "Bitcoin seed"
#define BR_WALLET_ACCOUNT_COINOMI_49   0x2u   // m/49'/20'/0', "Bitcoin seed"
typedef struct {
    BRWalletMasterKeys trees;         // bip84 m/84'/20'/0', legacy m/0H, bip86 m/86'/20'/0'
    uint32_t accounts;                // 0 or BR_WALLET_ACCOUNT_* bits; the keys below are NONE unless set
    BRMasterPubKey bip44, bip49;
} BRWalletKeyRecord;
size_t BRWalletKeyRecordSerialize(const BRWalletKeyRecord *r, uint8_t *out, size_t outLen); // 0 = refused
int    BRWalletKeyRecordParse(BRWalletKeyRecord *r, const uint8_t *in, size_t inLen);        // 1 = ok, 0 = refused
int    BRWalletMasterKeysEqual(const BRWalletMasterKeys *a, const BRWalletMasterKeys *b);  // fingerprint, chain code, pubkey
int    BRWalletKeyRecordEqual(const BRWalletKeyRecord *a, const BRWalletKeyRecord *b);
```

- [ ] **Step 1: Write the failing cases.**
  - `wallet_open_kat`: a wallet opened from parsed keys has the same address **set** as one opened from the seed. Today the KAT compares counts only (`wallet_open_kat_main.c:237-241`). The sets match on every chain, legacy and P2TR included, before and after gap growth: P2TR derives from `taprootPubKey`, `BRWallet.c:974-976`. That pins the refuted claim that watch-only Taproot needs the seed.
  - `wallet_key_record_kat`: round trip; vectors for the BIP39 test phrase. Each arm fails only its case:
    - `WALLET_KEYS_PARSE_DROPS_LEGACY_UNFIXED`: the set differs;
    - `WALLET_KEYS_PARSE_ACCEPTS_NONE_UNFIXED`: a NONE key opens a wallet missing a tree;
    - `WALLET_KEYS_IGNORE_VERSION_UNFIXED`: a record with an accounts block is read as one without;
    - `WALLET_KEYS_EQUAL_IGNORES_CHAINCODE_UNFIXED`.
  - Refused: `BR_MASTER_PUBKEY_NONE`, a public key secp256k1 rejects, a wrong version or length, a truncated record, trailing bytes.
- [ ] **Step 2: Run** both suites. Expected FAIL: `BRWalletKeyRecord.h` does not exist.
- [ ] **Step 3: Implement** the header. Re-derive (do not assume) whether v1 matches iOS's 212-byte `MasterKeysRecord` (`BRWalletOpen.h:56-60`), and record which in the hand-off.
- [ ] **Step 4: Run** both suites at 64/32 bits under ASan. Expected PASS; every arm fails exactly its case.
- [ ] **Step 5: KAT sweep and the Linux strict compile;** hand-off item 4.
- [ ] **Step 6: Land** (core landing), after Mark's OK.

### Task C2: The DigiDollar receive key from the BIP86 public key

**Rulings:** G17

**Landing:** core landing, as its own small PR so B3 does not wait on M1. Not R8: it reduces seed reads and stores nothing.
**Depends on:** X1, G5.

**Files:** create `core:BRDigiDollarReceiveKey.h` (header-only); add cases to `host:dd_receive_key_kat/`.

**Interfaces:**
```c
// m/86'/20'/0'/0/0's output key, BRKeyTaprootOutputKey(BRBIP32PubKey(bip86, 0, 0)); 0 on a NONE key
int    BRDigiDollarReceiveOutputKey(const BRMasterPubKey *bip86, uint8_t outXOnly[32]);
// the exact string getDigiDollarReceiveAddress returns today
size_t BRDigiDollarReceiveAddress(const BRMasterPubKey *bip86, char *out, size_t outLen);
```

- [ ] **Step 1: Write the cases.** The new functions equal G5's seed-derived vectors on both networks. `DD_RECEIVE_INDEX1_UNFIXED` and `DD_RECEIVE_INTERNAL_UNFIXED` each fail.
- [ ] **Step 2: Run.** Expected FAIL: no header.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected PASS. **Step 5: KAT sweep. Step 6: Land.**

### Task C3: The manager's wallet becomes a slot array (mechanical, one wallet)

**Rulings:** G3, G6, G17, G27

**Landing:** core landing; the first in the `BRPeerManager.c` track. The hand-off names the last core commit before it. Not R8.
**Depends on:** X1, G2 on Android `develop`, G5, G6.

**Files:**
- `core:BRPeerManager.h`: only `#define BR_PEER_MANAGER_WALLETS_MAX 8`.
- `core:BRPeerManager.c`:
  - the struct;
  - `BRPeerManagerNewEx`;
  - the 60 production sites;
  - the 8 comparison-arm sites at 1044, 1339, 1340, 2758, 2915, 2954, 4647 and 11117;
  - the `RemoveTransaction` assert at 10009.
- Create `host:pm_wallet_sites_kat/` (a source gate).

**Interfaces (private to `BRPeerManager.c`):**
```c
typedef struct {
    BRWallet *wallet;          // borrowed; the manager never frees it
    uint32_t attachedAt;       // 0 for the constructor's wallet
} BRPeerManagerWalletSlot;
// in BRPeerManagerStruct, replacing `BRWallet *wallet;` (deleted, so the compiler lists every site):
BRPeerManagerWalletSlot wallets[BR_PEER_MANAGER_WALLETS_MAX];
size_t   walletCount;          // 1 from every existing constructor
uint64_t walletSetGen;         // bumped by attach and detach (C8)
#define PM_EACH(m, w)    /* loop over attached wallets: EACH-class sites */
#define PM_ENGINE(m)     /* slot 0: what the existing public functions act on */
#define PM_OWNER(m, ptx) /* the publishing wallet: slot 0 until C6 */
```

- [ ] **Step 1: Write the failing gate.** `pm_wallet_sites_kat` requires:
  - no `manager->wallet` in `BRPeerManager.c`;
  - every `BRWallet*(` call's wallet argument is `w` inside `PM_EACH`, or `PM_ENGINE(...)`, or `PM_OWNER(...)`;
  - (R3) the struct holds exactly one `BRCFScanLedger cfLedger`, and every `BRCFScanLedgerLowestNeededHeight` decision site reads `&manager->cfLedger`. The design names 12; the PR lists them by function.

  A planted bare slot-0 access fails it, and so does a planted per-slot ledger field.
- [ ] **Step 2: Run.** Expected FAIL: 69 `manager->wallet` lines on today's core.
- [ ] **Step 3: Implement.**
  - Replace the field and port each site through its tagged accessor.
  - Keep every field name a white-box suite reads: `cfLedger`, `autoFetchCFilters*`, `cfElems*`, `cfAbandonedHeightsTotal`, `cfSolicitedBlocks`, `saveCFLedger*`.
  - No public signature changes.
  - The PR tabulates every site with its class (EACH, OWNER, ENGINE) and lock context, listing every wallet read outside `MGR_LOCK` for C8, starting with `BRPeerManagerApplyServerHeight`.
- [ ] **Step 4: Run** the gate. Expected PASS.
- [ ] **Step 5: Neutrality proof.**
  - The KAT sweep's verdicts are unchanged.
  - G5 and G6 are green.
  - Every white-box suite compiles and passes with no assertion edited; the PR lists the suites that include `BRPeerManager.c`.
  - G2's mutants each apply exactly once.
  - The NDK build passes.
- [ ] **Step 6: Land.** Record the last pre-C3 core commit in the hand-off.

### Task C4: Heights, fee, reorg un-confirm and gap look-ahead reach every wallet

**Rulings:** G3, G27

**Landing:** core landing; `BRPeerManager.c` track, after C3's pin. Not R8.
**Depends on:** C3.

**Files:**
- `core:BRPeerManager.c`, the EACH-class sites:
  - `BRWalletSetBlockHeight` at :4428, :5533 and :9631;
  - `_peerSetFeePerKb`;
  - `BRWalletSetTxUnconfirmedAfter` in the reorg;
  - `BRWalletUnusedAddrs` look-ahead and pregen.
- Create `host:pm_multi_wallet_kat/` (white-box; a second wallet put in slot 1 directly).
- Add a two-wallet case to `host:cf_reorg_rewind_kat/`, at the shipped header-check level or with the pin proven load-bearing.

- [ ] **Step 1: Write the failing cases** with two slots:
  - heights reach both wallets;
  - a reorg un-confirms both above the fork and rewinds the one ledger once;
  - the fee reaches both.

  Arm `MW_EACH_SLOT0_ONLY_UNFIXED` loops over slot 0 only and fails every case.
- [ ] **Step 2: Run.** Expected FAIL: the slot-1 wallet's height never moves.
- [ ] **Step 3: Implement**, keeping today's per-wallet semantics, the lag at :4693 included (PD10).
- [ ] **Step 4: Run.** Expected PASS. **Step 5: KAT sweep;** G5 and the white-box suites unchanged. **Step 6: Land.**

### Task C5: A transaction is registered with every wallet that owns it, and with no other

**Rulings:** G27, G28

**Landing:** core landing; `BRPeerManager.c` track. Not R8.
**Depends on:** C4.

**Files:**
- `core:BRPeerManager.c`:
  - `_peerRelayedTx`;
  - `_peerRelayedBlockTxns`;
  - the wallet half of `_BRPeerManagerUpdateTx`;
  - the growth checks at 2714-2720 and 2815-2822;
  - the timestamp decision at 2828.
- Create `host:pm_multi_wallet_credit_kat/` on `cf_gap_rescan_kat`'s real-BIP158 harness (64/32-bit, ASan+LSan).

- [ ] **Step 1: Write the failing cases:**
  - a block paying only B is credited to B and never to A, through block delivery and through a getdata echo;
  - a filter matching only B's script fetches the block;
  - an A-to-B transaction ends up in both wallets as distinct objects, with no double free when both wallets are freed;
  - growth in B during a delivery rewinds the one ledger, and B's next receive in the same batch is found;
  - a foreign unconfirmed record that A keeps never appears in B;
  - block heights reach every wallet.

  Arms:
  - `MW_REGISTER_PRIMARY_ONLY_UNFIXED`: B is never credited;
  - `MW_REGISTER_SHARED_OBJECT_UNFIXED`: one object for both, an ASan double free;
  - `MW_REGISTER_NO_OWNERSHIP_GATE_UNFIXED`: B's unconfirmed transaction becomes a foreign record in A;
  - `MW_GAP_PRIMARY_ONLY_UNFIXED`: B's second receive is missed.
- [ ] **Step 2: Run.** Expected FAIL.
- [ ] **Step 3: Implement.**
  - Compute delivery trust once.
  - Before any wallet takes the parsed object, make private `BRTransactionCopy` copies for the other wallets (K2).
  - Per wallet: apply the gate (`syncStartHeight == 0 || contains(w)`), the trust and the register outcome (REGISTER-RESULT), and free each refused copy.
  - Growth in any wallet runs the one shared gap rescan (`_BRPeerManagerCFRescanAfterGrowthLocked`, unchanged).
  - Block delivery confirms or holds per wallet, and completes a scan height once.
  - The block-completion gate (only a solicited, merkle-checked, main-chain block completes a height; the core's "C1" rule, not task C1) and INT-2026-10-09-D are unchanged.
- [ ] **Step 4: Run.** Expected PASS. **Step 5: KAT sweep. Step 6: Land.**

### Task C6: One owner per published transaction, across wallets

**Rulings:** G3, G13, G27, G29, G30

**Landing:** core landing; `BRPeerManager.c` track. Not R8.
**Depends on:** C5.

**Files:**
- `core:BRPeerManager.c`:
  - `BRPublishedTx` gains an appended `owner` (appending keeps the positional initializer at :994);
  - `_BRPeerManagerSnapshotHeld`, `_PurgeReleasedRecords` and `_OwnershipFollowsObject` work by owner;
  - `_peerHasTx` and `_peerRequestedTx` register into the owner only;
  - `_peerRejectedTx` is judged in the sender;
  - the unrelayed request and the at-tip sweep run per wallet over that wallet's own unconfirmed list.
- `core:BRPeerManager.h`: additive wallet-addressed functions.
- Two-wallet cases in `host:publish_list_ownership_kat`, `host:tx_publish_ownership_kat`, `host:publish_cancel_survivor_kat`.

**Interfaces** (the names extend no existing name; the `tipsweep` `fn_body` gate matches by prefix):
```c
void BRPeerManagerWalletPublishTx(BRPeerManager *m, BRWallet *owner, BRTransaction *tx, void *info,
                                  void (*callback)(void *info, int error));
int  BRPeerManagerWalletStemPublishTx(BRPeerManager *m, BRWallet *owner, BRTransaction *tx, void *info,
                                      void (*callback)(void *info, int error));   // returns as BRPeerManagerStemPublishTx does
void BRPeerManagerWalletRemoveTransaction(BRPeerManager *m, BRWallet *owner, UInt256 txHash);
BRServerHeightResult BRPeerManagerWalletApplyServerHeight(BRPeerManager *m, BRWallet *owner, UInt256 txHash,
                                                          uint32_t height, uint32_t timestamp);
// The existing four keep their signatures and act on PM_ENGINE(m).
```

- [ ] **Step 1: Write the failing cases** with two wallets:
  - B's entry is owned by B, and its callback is answered exactly once, outside the lock;
  - A's remove never cancels B's entry;
  - a rejected B send is judged in B;
  - the pong queue stays a leaf lock: per-wallet work reached from the ping-done callbacks (`:1490-1506`, `:9748`) runs outside `pongLock`.

  G2's spelling-agnostic mutants still each fail exactly their cases. Arm `MW_PUBLISH_OWNER_IGNORED_UNFIXED` uses slot 0.
- [ ] **Step 2: Run.** Expected FAIL.
- [ ] **Step 3: Implement.** **Step 4: Run.** Expected PASS. **Step 5: KAT sweep. Step 6: Land.**

### Task C7: The union element cache, the buffered drain, and capacity at 8 wallets

**Rulings:** G24, G27

**Landing:** core landing; `BRPeerManager.c` track. Not R8.
**Depends on:** G1, C6.

**Files:**
- `core:BRPeerManager.c`:
  - `_BRPeerManagerFilterElementsLocked` returns the union;
  - the per-slot caches;
  - the KeepAlive drain at 8667 uses the cache;
  - `struct _cfDrainCtx` is kept (`int_2026_10_09_d_kat:488`).
- `core:BRWalletFilterElements.c`: a failure-injection hook named `BR_FILTER_ELEMENTS_KAT_FAIL_NEXT`, which G1's inventory then covers.
- Union cases in `host:cf_confirm_kat`, beside G1's.

- [ ] **Step 1: Write the failing cases:**
  - attach B;
  - growth in B: a watched address, newly derived addresses, a transaction registered mid-scan;
  - growth in A;
  - detach B: B's elements are gone and A's kept;
  - a same-count swap from B to an older C;
  - a network switch with two wallets;
  - one wallet's NULL build;
  - a buffered filter matching only B yields a getdata;
  - a filter paying B's new script yields a getdata (on the `cf_gap_rescan` builder);
  - **capacity:** 8 wallets with Coinomi-sized element sets, and a run of matched blocks that fills the solicitation table (256) and the held-confirmation list (1024). Eviction re-requests; no height completes without delivery; no wallet is credited a block it does not own.

  Arms:
  - `CF_ELEMS_UNION_PRIMARY_KEY_UNFIXED`: the key is read from slot 0, as at 6864 today;
  - `CF_ELEMS_UNION_AGGREGATE_KEY_UNFIXED`: a summed count and maximum generation miss the older-C swap;
  - `CF_ELEMS_UNION_NO_SETGEN_UNFIXED`: the detach case fails;
  - `CF_DRAIN_PRIMARY_ONLY_UNFIXED`;
  - `CF_ELEMS_UNION_NULL_STAMPS_UNFIXED`.

  G1's RED 3/4 still fail `test6` and `test7`.
- [ ] **Step 2: Run.** Expected FAIL.
- [ ] **Step 3: Implement.** The key is `walletSetGen`, plus every slot's (`addrGen`, `addrCount`), plus `isTestnet`, never an aggregate. A NULL build from one wallet never stamps the union valid.
- [ ] **Step 4: Run.** Expected PASS. **Step 5: KAT sweep. Step 6: Land.**

### Task C8: The holding constructor, attach and detach, the empty set, and parked managers that reach no wallet

**Rulings:** G3, G27, G29, G31, G32, G64, G83

**Landing:** core landing; `BRPeerManager.c` track. The public attach API comes last, so nothing can attach to a half-converted engine. Not R8.
**Depends on:** C7.

**Files:**
- `core:BRPeerManager.h/.c`: the new API, and the out-of-lock wallet reads C3 listed, each fixed or proven unreachable.
- `core:BRWallet.c/.h`: a hold field, internal, behind `wallet->lock`.
- Create `host:pm_wallet_detach_kat/` (ASan, TSan).
- Cases in `host:peer_free_live_thread_kat`, `host:late_peer_callback_kat` and `host:cf_peer_pong_race_kat` (two wallets).
- Clauses added to `host:pm_wallet_sites_kat`.

**Interfaces:**
```c
// A manager that HOLDS its wallets: AttachWallet refuses a wallet another holding manager holds, and Free
// detaches every wallet under MGR_LOCK before it can park (PD15). The existing constructors take no hold and
// keep today's Free exactly.
BRPeerManager *BRPeerManagerCreateHolding(const BRChainParams *params, BRWallet *wallets[], size_t walletCount,
                                          uint32_t earliestKeyTime, BRMerkleBlock *blocks[], size_t blocksCount,
                                          const BRPeer peers[], size_t peersCount, BRMerkleBlock *startBlock);
int    BRPeerManagerAttachWallet(BRPeerManager *m, BRWallet *w);  // at the current frontier: a wallet with no history below it
int    BRPeerManagerDetachWallet(BRPeerManager *m, BRWallet *w);  // 1 = detached; no manager thread reaches w afterwards
size_t BRPeerManagerWalletCount(BRPeerManager *m);
```

- [ ] **Step 1: Write the failing cases.**
  - Detaching with a pending owned=0 publish answers it once, outside the lock, and leaves no entry (ASan).
  - Detaching while a peer callback is in flight, then freeing the wallet, is clean under ASan and TSan.
  - **Empty set:** after the last detach the engine keeps syncing, every filter misses and nothing is credited.
  - Attaching a wallet that another holding manager holds is refused.
  - A recreate through `Free` plus a new holding manager succeeds while the old manager parks, and the parked manager reaches no wallet.
  - Attach's address generation runs outside `MGR_LOCK` (a lock-holder counter shows it). The in-lock re-run is a no-op when nothing grew (PD16).
  - Two wallets in `cf_peer_pong_race_kat`: the wallet set is mutated only under `MGR_LOCK`.
  - The existing constructors' `Free` is byte-for-byte today's (G5 green).

  Arms:
  - `MW_DETACH_KEEPS_PUBLISH_UNFIXED`: a use-after-free;
  - `MW_DETACH_OUTSIDE_LOCK_UNFIXED`;
  - `MW_APPLY_SERVER_HEIGHT_READS_BEFORE_LOCK_UNFIXED`;
  - `MW_EMPTY_SET_DEREF_UNFIXED`: assumes slot 0;
  - `MW_ATTACH_NO_HOLD_CHECK_UNFIXED`;
  - `PEER_PARK_KEEPS_WALLETS_UNFIXED`.

  The `pm_wallet_sites_kat` clause is red on an untagged call.
- [ ] **Step 2: Run.** Expected FAIL.
- [ ] **Step 3: Implement.**
  - **Attach:** generate the window before the lock. Then, under `MGR_LOCK`: add the slot, set its height to the tip, re-run the bounded pregeneration, re-key the cache, bump `walletSetGen`.
  - **Detach:** under `MGR_LOCK`, drop the owned=0 entries the wallet owns (each answered once, outside the lock), clear its owned=1 entries, free its cache slot, bump `walletSetGen`.
  - `earliestKeyTime` is the minimum over attached wallets.
- [ ] **Step 4: Run.** Expected PASS. **Step 5: KAT sweep. Step 6: Land.**

### Task C9: The hand-off rule, frontier records, the band cap, and band order and preemption

**Rulings:** G17, G33, G34, G35, G36, G37, G38, G65, G68, G70

**Landing:** core landing. A pure header, built alongside C3-C8. Not R8.
**Depends on:** X1, G6. A4 feeds the band peer defaults.

**Files:**
- Create `core:BRBandHandoff.h` (header-only; the steps as data, in the `BRRecreateSequence.h` pattern).
- `core:BRPeerCapPolicy.h`: `BRPeerCapForBand`.
- Create `host:band_handoff_kat/`; add cases to `host:peer_cap_kat`.

**Interfaces:**
```c
typedef struct {                         // the shared engine, read under its lock (C10 accessors)
    uint32_t blockFloor, filterChainStart, ledgerStart, abandonedBelow;
    uint32_t lowestNeeded;               // S
    uint32_t requestedThrough;
    uint32_t tip;
} BRSharedFrontier;
uint32_t BRBandFrontier(uint32_t scannedThrough, uint32_t abandonedBelow);   // F = max(scannedThrough + 1, abandonedBelow)
uint32_t BRSharedHMin(const BRSharedFrontier *s);                            // max of the four floors
typedef enum { BR_HANDOFF_WAIT, BR_HANDOFF_ATTACH, BR_HANDOFF_ATTACH_REWIND, BR_HANDOFF_BAND,
               BR_HANDOFF_REFUSE_FORK } BRHandoffVerdict;
typedef struct { BRHandoffVerdict verdict; uint32_t rewindTo; uint32_t cursorTo; } BRHandoffDecision;
// One rule for a band's F and for a band-less wallet's recorded frontier (PD14).
// sharedHasBlockAtF: the shared main chain holds the block at height F (its prevBlock is compared with hashBeforeF).
BRHandoffDecision BRBandHandoffDecide(const BRSharedFrontier *s, uint32_t F, int sharedHasBlockAtF, int prevHashMatches);
typedef enum { BR_HANDOFF_STEP_TAKE_SPEND_LATCH, BR_HANDOFF_STEP_FLUSH_BAND, BR_HANDOFF_STEP_STOP_ROUTING,
               BR_HANDOFF_STEP_DISCONNECT_AND_FREE, BR_HANDOFF_STEP_NO_PUBLISH_IN_FLIGHT, BR_HANDOFF_STEP_ATTACH_UNDER_LOCK,
               BR_HANDOFF_STEP_PERSIST_SHARED_LEDGER_NOW, BR_HANDOFF_STEP_WRITE_FRONTIER, BR_HANDOFF_STEP_DELETE_BAND_FILES,
               BR_HANDOFF_STEP_RELEASE_SPEND_LATCH } BRHandoffStep;
static const BRHandoffStep BRBandHandoffSteps[] = { /* the enum's values, in this order */ };
uint32_t BRColdStartRewindTarget(uint32_t restoredLowestNeeded, const uint32_t *frontiersOfAttaching, size_t n,
                                 uint32_t hMin);           // the lowest frontier at or above hMin; below hMin that wallet bands
typedef struct { int isActive; uint32_t blocksBehind; uint32_t registryOrder; int needsBand; } BRBandCandidate;
int BRBandNextIndex(const BRBandCandidate *c, size_t n);            // PD5
int BRBandShouldPreempt(const BRBandCandidate *running, const BRBandCandidate *candidate);  // only for the new active wallet
size_t BRPeerCapForBand(size_t sharedCap);                // PEER_MAX_CONNECTIONS - sharedCap; 0 pauses the band
```

The decision:
- **BAND:** F < H_min, or F < S - 1000 (`MAX_CFILTERS_RESULTS`).
- **WAIT:** the shared chain does not hold the block at F yet (PD18's climb).
- **REFUSE_FORK:** the shared block at F does not link to the band's hash at F-1. The check compares that block's `prevBlock`, so it also works when F == H_min and F-1 lies below the shared window.
- **ATTACH_REWIND:** F ≤ `requestedThrough`. The ledger is rewound to F and the cursor to F-1.
- **ATTACH:** otherwise.

- [ ] **Step 1: Write the failing cases.** Each arm fails exactly its cases, at 64 and 32 bits:
  - `BAND_HANDOFF_F_FROM_REQUESTED_UNFIXED`: F passes a matched-outstanding hole;
  - `BAND_HANDOFF_IGNORES_HMIN_UNFIXED`;
  - `BAND_HANDOFF_IGNORES_ABANDONED_UNFIXED`;
  - `BAND_HANDOFF_NO_BATCH_SLACK_UNFIXED`: the band never hands off while the tip advances;
  - `BAND_HANDOFF_EAGER_UNFIXED`: hands off on F ≥ H_min alone;
  - `BAND_HANDOFF_REWIND_PLUS_ONE_UNFIXED`: the rewind goes to F+1 and misses F;
  - `BAND_HANDOFF_NO_FORK_CHECK_UNFIXED`;
  - `BAND_HANDOFF_STEPS_DELETE_FIRST_UNFIXED`: the band files are deleted before the frontier is written;
  - `BAND_COLDSTART_IGNORES_FRONTIERS_UNFIXED`;
  - `BAND_PREEMPT_NON_ACTIVE_UNFIXED`;
  - `PEER_CAP_BAND_FIXED_UNFIXED`: the band gets 8 and the device total exceeds 8.

  Explicit cases:
  - F == H_min;
  - a reorg between quiesce and attach (a mismatch at F-1);
  - WAIT while the shared engine climbs;
  - a band-less wallet re-attaching from its recorded frontier;
  - band order and preemption;
  - a band cap of 5 while the shared engine holds 3, and 0 while it holds 8.
- [ ] **Step 2: Run.** Expected FAIL: no header.
- [ ] **Step 3: Implement.** **Step 4: Run.** Expected PASS. **Step 5: KAT sweep and the Linux strict compile. Step 6: Land.**

### Task C10: Hand-off under the shared lock, proven with two managers in one process

**Rulings:** G3, G20, G27, G33, G34, G35, G39, G40, G71, G83

**Landing:** core landing; `BRPeerManager.c` track. Not R8.
**Depends on:** C8, C9.

**Files:**
- `core:BRPeerManager.h/.c`:
  - `BRPeerManagerHandOffWallet`;
  - read accessors for the `BRSharedFrontier` parts and for the main-chain block at a height;
  - `BRPeerManagerRewindScanLedgerTo`, the gap-rescan shape: the ledger to F, the cursor to F-1.
- Create `host:band_handoff_engine_kat/` (white-box; two holding managers in one process, on `cf_gap_rescan`'s harness).

**Interfaces:**
```c
BRHandoffDecision BRPeerManagerHandOffWallet(BRPeerManager *shared, BRWallet *w, uint32_t F, UInt256 hashBeforeF);
int  BRPeerManagerSharedFrontier(BRPeerManager *m, BRSharedFrontier *out);
int  BRPeerManagerMainChainBlockAt(BRPeerManager *m, uint32_t height, UInt256 *hash, UInt256 *prevHash);
void BRPeerManagerRewindScanLedgerTo(BRPeerManager *m, uint32_t F);
```

- [ ] **Step 1: Write the failing cases.** The band is a one-wallet holding manager over its own chain.
  - The band runs while the shared tip keeps advancing, and hands W over only under the rule.
  - With F above the shared requested height, W attaches with no rewind.
  - With F at or below it, the ledger rewinds, and B's payment at F+5, already evaluated for A alone, is found.
  - A fork at F-1 is refused, and the band resumes.
  - H_min moved above F between decision and attach is refused.
  - A publish in flight in the band at quiesce is answered once, and the transaction stays in W for rebroadcast.
  - At every step W is reachable from one manager only.
  - A reorg after the hand-off rewinds every wallet.

  Arms:
  - `BAND_ATTACH_NO_REWIND_UNFIXED`: the payment at F+5 is missed;
  - `BAND_ATTACH_NO_LINK_CHECK_UNFIXED`;
  - `CF_LEDGER_REWIND_NOOP_UNFIXED`;
  - `BAND_ATTACH_WHILE_HELD_UNFIXED`.
- [ ] **Step 2: Run.** Expected FAIL.
- [ ] **Step 3: Implement** one critical section:
  1. re-check H_min through `BRBandHandoffDecide`;
  2. check the shared chain at F;
  3. attach as C8 does;
  4. set W's height to the shared tip;
  5. rewind when told to;
  6. fire the ledger save callback before returning.
- [ ] **Step 4: Run.** Expected PASS. **Step 5: KAT sweep. Step 6: Land.**

### Task C11: Recovery reasons get a scope; a reset while a band runs; the upgrade floor

**Rulings:** G17, G41, G42, G67

**Landing:** core landing (pure header, alongside the `BRPeerManager.c` track). The Kotlin mirror goes in the pin PR. Not R8.
**Depends on:** X1, G6.

**Files:**
- `core:BRCFRecoveryPolicy.h` and `core:BRCFRestorePreflight.h`: new functions only. `BRCFRecoveryDecision` and the enum values are unchanged, because iOS builds them field by field.
- `host:cf_recovery_policy_kat`.
- `kt-core:sync/CfRecoveryPolicy.kt` (mirror), `kt-core-test:sync/CfRecoveryPolicyTest.kt`.
- `kt-core-at:sync/CfRecoveryPolicyParityTest.kt` still compiles.

**Interfaces:**
```c
typedef enum { BR_CF_SCOPE_SHARED_CHAIN = 1, BR_CF_SCOPE_ONE_WALLET = 2 } BRCFRecoveryScope;
BRCFRecoveryScope BRCFRecoveryScopeOf(BRCFRecoveryReason r, int ledgerIsShared);
BRCFRecoveryScope BRCFRestorePreflightScopeOf(int preflightReason, int ledgerIsShared);
int BRCFSharedResetResetsFrontiers(BRCFRecoveryReason r);     // 1: every READY wallet's frontier goes to its birth (PD18)
typedef enum { BR_REBUILD_FLOOR_NONE, BR_REBUILD_FLOOR_CLIMB, BR_REBUILD_FLOOR_BANDS } BRRebuildFloorPlan;
BRRebuildFloorPlan BRRebuildFloorPlanFor(size_t attached, size_t needingFloor);   // PD19
```

Scope of each reason:
- **SHARED_CHAIN:**
  - `FilterChainWedged`, `Reanchored` and `FilterChainCorrupt` on the shared engine;
  - a corrupt shared ledger;
  - `INVALID_SAVED_BLOCKS`, `HEADER_WINDOW_MISSING`, and a missing or invalid shared ledger;
  - BootGuard's chain tier.
- **ONE_WALLET:**
  - `LOWER_FLOOR_TO`, `TRANSACTION_CHECKPOINT_SHORT`, `MISSING_TRANSACTION_CHECKPOINT`;
  - the `HistoryRebuildOnUpgrade` floor;
  - one wallet's `WalletReset`;
  - a band's corrupt ledger.

- [ ] **Step 1: Write the failing cases:**
  - every reason pinned to its scope;
  - a shared reason that drops the ledger resets frontiers (PD18);
  - `BRRebuildFloorPlanFor(3, 3) == CLIMB` and `(3, 1) == BANDS`.

  Arms:
  - `CF_RECOVERY_WALLET_RESET_SHARED_UNFIXED`: one wallet's reset drops the shared chain, as today's `WalletReset` row does;
  - `CF_RECOVERY_SHARED_RESET_KEEPS_FRONTIERS_UNFIXED`.

  `CfRecoveryPolicyTest` matches C case for case, and is red on a mismatched row.
- [ ] **Step 2: Run.** Expected FAIL.
- [ ] **Step 3: Implement.** **Step 4: Run.** Expected PASS. **Step 5: KAT sweep, full suites. Step 6: Land.**

### Task C12: "Rescan this wallet" and "Repair the chain" as new rebuild sequences

**Rulings:** G3, G9, G27, G38, G43, G96

**Landing:** core landing; `BRPeerManager.c` track (for the comment and the white-box case). E4 adopts it. Not R8.
**Depends on:** C10, C11.

**Amended by O7 (2026-11-19, G96).** The core lists are unchanged: they name what a rescan or a repair never clears, not where it is stored. G38's "the never-list names only the shared `dgb_chain<net>` keys" now reads "the shared chain keys in legacy's `dgb_sync_data<net>`", and that file also holds legacy's own keys, which a rescan of legacy does clear (G96's second open point).

**Files:**
- `core:BRChainRebuild.h`: new enum types and arrays.
  - `BRChainRebuildStep`, `BRChainRebuildSteps`, `FromSteps` and `ReloadSteps` stay untouched. iOS runs them, and editing them in place would deadlock rule 7.
- Regenerate `host:scan_floor_kat` vectors with `generate.c`.
- Correct the stale claims at `core:BRPeerManager.c:8061-8063` and `host:peer_penalty_shotgun_kat_main.c:9-10`.

**Interfaces:**
```c
typedef enum { BR_WALLET_RESCAN_LOWER_FLOOR_TO, BR_WALLET_RESCAN_DETACH_WALLET, BR_WALLET_RESCAN_ARM_RERUN,
               BR_WALLET_RESCAN_FLOOR_FROM_CACHE, BR_WALLET_RESCAN_CLEAR_TX_CACHE, BR_WALLET_RESCAN_CLEAR_WALLET_SYNC_STATE,
               BR_WALLET_RESCAN_CLEAR_WALLET_LEDGER, BR_WALLET_RESCAN_CLEAR_ABANDONMENT, BR_WALLET_RESCAN_CLEAR_OUTGOING_STORE,
               BR_WALLET_RESCAN_SET_FLOOR_AT_BIRTH, BR_WALLET_RESCAN_REATTACH_AS_BAND } BRWalletRescanStep;
typedef enum { BR_CHAIN_REPAIR_FLUSH, BR_CHAIN_REPAIR_RELOAD_NEAR_TIP, BR_CHAIN_REPAIR_FORCE_RECONNECT,
               BR_CHAIN_REPAIR_START_SYNC, BR_CHAIN_REPAIR_RESTORE_LEDGER_AND_SNAP, BR_CHAIN_REPAIR_REREQUEST_HEADERS_FROM_TIP,
               BR_CHAIN_REPAIR_PIN_CANON_NONEXCLUSIVE } BRChainRepairStep;
// BRWalletRescanSteps[], BRWalletRescanFromSteps[] (LOWER_FLOOR_TO first), BRChainRepairSteps[],
// and "never" lists: a wallet rescan never clears the filter chain, the saved blocks or saved_blocks_tip,
// never stops the engine, never restarts; a repair never clears the ledger, the saved blocks or the filter chain,
// never rewinds to a floor, never calls SetFixedPeer, never pins over an own node, never restarts.
```

- [ ] **Step 1: Write the failing cases** in `scan_floor_kat`.
  - `WALLET_RESCAN_CLEARS_CHAIN_UNFIXED`: a wallet rescan contains a chain clear or a stop.
  - `CHAIN_REPAIR_DROPS_LEDGER_UNFIXED`.
  - **Repair, white-box:** a dead-branch tip within the handshake tolerance (at most 10 blocks above every peer) follows the canonical chain after `RELOAD_NEAR_TIP` and `REREQUEST`.
  - **More than 10 blocks above:** measure it, and decide in the PR between a documented limit and a reload that drops the top headers. It is refused at the handshake today (`BRPeerManager.c:2136-2147`).

  The existing rebuild arms are unchanged.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run.** Expected PASS. **Step 5: KAT sweep. Step 6: Land.**

### Task C13: A send-all builder for MAX

**Rulings:** G9

**Landing:** core landing; `BRWallet.c` track, after C8's hold field. Not R8.
**Depends on:** X1, C8.

**Files:**
- `core:BRWallet.h/.c`: add `BRWalletCreateSendAllTransaction`. `BRWalletMaxOutputAmount` keeps its meaning, because `effective_value_kat`, `unconfirmed_select_kat`, `one_family_kat`, `money_range_kat` and `tx_rows_amount_kat` depend on it.
- Create `host:send_all_kat/`; add a case to `host:prepared_send_core_kat`.

**Interfaces:**
```c
typedef enum { BR_SEND_ALL_OK = 0, BR_SEND_ALL_NOTHING_SELECTABLE, BR_SEND_ALL_BELOW_DUST,
               BR_SEND_ALL_TOO_MANY_INPUTS /* > 512 */, BR_SEND_ALL_TOO_LARGE /* > TX_MAX_SIZE */ } BRSendAllStatus;
typedef struct { BRSendAllStatus status; uint64_t amount, fee; int feeRaisedToFloor; } BRSendAllResult;
BRTransaction *BRWalletCreateSendAllTransaction(BRWallet *w, const char *addr, uint64_t feePerKb,
                                               uint64_t customTotalFee /* 0 = rate */, BRSendAllResult *out);
```

- [ ] **Step 1: Write the failing cases:**
  - one output, no change;
  - the fee equals the built transaction's own quote, and `prepared_send_check` accepts the result unchanged;
  - every selectable output is spent;
  - never spent: asset carriers, implicit-change overrides, DigiDollar outputs, ONE-FAMILY-held, unsignable and foreign unconfirmed outputs;
  - uneconomic coins stay behind under the effective-value rule (PD7);
  - each refusal;
  - a custom total gives the total minus the fee, raised to the floor and flagged.

  Arms:
  - `SEND_ALL_VIA_MAX_OUTPUT_UNFIXED`: today's `BRWalletMaxOutputAmount` plus `BRWalletCreateTransaction` leaves change or overpays on segwit coins;
  - `SEND_ALL_TAKES_CARRIERS_UNFIXED`;
  - `SEND_ALL_NO_INPUT_CAP_UNFIXED`: 600 inputs, refused only at signing;
  - `SEND_ALL_NO_SIZE_CAP_UNFIXED`;
  - `SEND_ALL_FEE_TWO_OUTPUTS_UNFIXED`.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run.** Expected PASS. **Step 5: KAT sweep. Step 6: Land.**

### Task C14: The catching-up classification

**Rulings:** G17, G67

**Landing:** core landing (pure header); the Kotlin mirror goes in the pin PR. An iOS parity item. Not R8.
**Depends on:** X1, G6.

**Files:**
- `core:BRSyncFrontier.h`: a new function. The existing structs and enums are unchanged.
- `host:sync_frontier_kat`.
- `kt-core:model/SyncProgress.kt` (mirror), `kt-core-test:model/SyncFrontierTest.kt`.

**Interfaces:**
```c
typedef enum { BR_BAND_NONE = 0, BR_BAND_RUNNING, BR_BAND_WAITING } BRBandState;
typedef struct { int catchingUp; uint32_t blocks; } BRSyncCatchUp;
BRSyncCatchUp BRSyncCatchUpFor(const BRSyncFrontier *f, int anyWalletSyncedBefore, int thisWalletSyncedBefore,
                              BRBandState band, uint32_t blocksBehind, int abandonedHold);
```

- [ ] **Step 1: Write the failing cases:**
  - a resume 717 blocks behind is catching up, N = 717, with no percentage;
  - a band's wallet and a waiting wallet are catching up, N = the gap;
  - a restore's band is catching up (PD6);
  - the first climb on a device where no wallet ever synced is a first sync;
  - an abandoned-band hold stays a hold;
  - every existing case is unchanged.

  Arms: `CATCHUP_AS_FIRST_SYNC_UNFIXED`, `CATCHUP_ON_FIRST_SYNC_UNFIXED`. `SyncFrontierTest` matches C.
- [ ] **Steps 2-6:** run (FAIL), implement, run (PASS), KAT sweep and full suites, land.

### Task C15: Coinomi's BIP44 and BIP49 accounts as derived chains (Coinomi step 4, engine)

**Rulings:** G44

**Landing:** core landing; `BRWallet.c` track (after C13) and `BRPeerManager.c` (after C8). Not R8 itself: it stores nothing. The account keys at rest are B9 and U3.
**Depends on:** X2, C5, C7, C8, C13.

**Files:**
- `core:BRWallet.h/.c`: two optional account trees, added at every site that lists chains:
  - the struct;
  - `array_new`;
  - the `UnusedAddrs` script types and the realloc rebuild;
  - `_BRWalletCollectAddrsLocked` (its `chains[10]`/`counts[10]` are full and grow);
  - the register-time extension and the watched-resolve loop;
  - `Free`;
  - an `addrGen` bump on every append;
  - "derived" origin counting.

  Key collection in `BRWalletSignTransaction` changes in the same PR.
- `core:BRPeerManager.c`: `_BRPeerManagerPregenAddrWindow`.
- `core:BRWalletOpen.h`: a new entry point. `BR_WALLET_OPEN_TREES` stays `0x7`.
- `core:BRAddress.h/.c`: a network-aware P2SH-P2WPKH encoder.
- `core:BRDerivationProfiles.h`: unchanged. "Native" becomes a per-wallet overlay in the app (U3).
- KATs: `wallet_open_kat`, `filter_elements_kat`, `bip49_sign_kat`, `sign_regression_kat`, `legacy_gap_uaf_kat` (realloc), `addr_set_distinct_kat`, `derivation_profiles_kat` (unchanged), `wallet_key_record_kat` (the stored account keys equal the seed's).

**Interfaces:**
```c
int  BRWalletAccountKeysFromSeed(const void *seed, size_t seedLen, uint32_t accounts,
                                 BRMasterPubKey *bip44, BRMasterPubKey *bip49);   // "Bitcoin seed", hardened
void BRWalletSetAccountKeys(BRWallet *w, uint32_t accounts, const BRMasterPubKey *bip44, const BRMasterPubKey *bip49);
BRWallet *BRWalletOpenWithAccounts(BRTransaction *txs[], size_t txCount, BRWalletMasterKeys trees,  // by value, as
                                   uint32_t accounts, const BRMasterPubKey *bip44, const BRMasterPubKey *bip49); // OpenWithKeys
```

- [ ] **Step 1: Write the failing cases:**
  - on the public test phrase, the account addresses match the derivation vectors and the mainnet proof in `docs/RESTORE-VERIFICATION.md`;
  - with the accounts on, BIP44 and BIP49 scripts are in the element set, and coins on both chains are credited, selected and signed through `BRWalletSignTransaction`;
  - with the accounts off, they are absent, as today.

  Arms:
  - `ACCOUNT_SIGN_KEYS_MISSING_UNFIXED`: a credited coin fails to sign;
  - `ACCOUNT_CHAINS_NOT_IN_ALLADDRS_UNFIXED`: after a realloc the funds vanish (the legacy precedent, `BRWallet.c:1056-1058`);
  - `ACCOUNT_P2SH_MAINNET_ONLY_UNFIXED`: version 63 on testnet, so nothing is ever credited;
  - `ACCOUNT_KEYS_NO_ADDRGEN_BUMP_UNFIXED`: the union cache stays stale;
  - `ACCOUNT_KEYS_DIGIBYTE_ROOT_UNFIXED`: the first addresses differ from `BRRecoveryDerive.h`'s.
- [ ] **Steps 2-6:** run (FAIL), implement, run (PASS), KAT sweep and the Linux strict compile, land.

## Stage 3: Android, behaviour-neutral, on `develop` after the 4.1.5 cut

**Rulings:** G11

### Task B1: Bridge, a per-wallet context table and per-manager sync contexts (one live slot; no JNI change)

**Rulings:** G10, G45, G46, G47, G48, G49, G50

**Landing:** a `develop` PR after X5. Not a registry PR (the header is unchanged). Not R8: the seed slot is untouched.
**Depends on:** G2, G3, G4, X150, X3 (Coinomi step 2 edits `jni_wallet_sign.c`). The M9 adoptions do not run alongside it.

**Files:**
- `bridge:jni_bridge.h`, `jni_wallet.c`, `jni_peer.c`, `jni_transaction.c`, `jni_transaction_persist.c`, `jni_asset.c`, `jni_asset_send.c`, `jni_wallet_sign.c`.
- Re-anchors:
  - the 10 suites that compile bridge units (`asset_send`, `bip39_nul`, `digiid_core`, `identity_sign_bytes`, `outpoint_classify`, `saved_tx_array`, `wallet_read_guard`, `server_tx_import`, `derivation_profiles`, `legacy_sweep`);
  - the text and order pins (`dandelion_state`, `tx_publish_ownership`, `publish_list_ownership`);
  - `checkpoint_time_kat`'s needle;
  - the two recreate mirrors (G4).
- `kt-core-test:sync/PeerManagerGenerationGateTest.kt`, `TransactionRowsVectorsTest.kt`, `asset/ParentIdAndDistinctInputsSourceGateTest.kt`.

**Interfaces (bridge-internal):**
```c
// bridge:jni_bridge.h -- opaque outside their .c files
typedef struct BRWalletContext BRWalletContext;   // jni_wallet.c: 8 slots (six KATs link it without jni_peer.c)
typedef struct BRSyncContext   BRSyncContext;     // jni_peer.c: SHARED, and BAND (empty until B7)
#define BR_WALLET_CONTEXTS_MAX 8
// A wallet context: registry id, generation, the owned BRWallet*, the full BRWalletMasterKeys (replacing the
// unread g_mpk), creation time, holder (NONE/SHARED/BAND, used from B6), the pending saved-tx load, unprovableServerHeight.
// A sync context: its own guard, manager, saved blocks and peers, status mirrors, pending and remembered BIP158
// state, own-node pin, Dandelion, rescan flags. s_peerManagerGeneration stays its own static (the shared one).
```

- [ ] **Step 1: Prove the gates still see.** G2's gates are green and non-vacuous on the new names. `wallet_read_guard_kat`'s TSan arm gains a concurrent JNI read, table write and callback, with no deadlock. Red arm: a callback that resolves through the table rwlock.
- [ ] **Step 2: Implement.**
  - Write down the lock order: sync guard, then table lock, then `manager->lock`, then `wallet->lock`. Callbacks resolve their context without the table lock, and never take a guard.
  - Callbacks are registered with their sync context as `info`.
  - Drop the `g_wallet` read in `bridge_txStatusUpdate`: its consumer, `onBalanceChanged`, is a no-op.
  - Run `loadSavedBlocks`, `loadSavedPeers`, `loadSerializedTransactions` and `setCallbackHandler` under the guard.
  - Update the mirrors in the same PR.
- [ ] **Step 3: Run** every KAT shard, the full suites (security included), and `gen-jni-registry.py --check` with the header unchanged. Expected PASS.
- [ ] **Step 4: Open the PR.**

### Task B2: Read natives take a wallet handle (registry PR, 3rd in the queue)

**Rulings:** G9, G19, G45, G49, G51, G72

**Landing:** a registry PR into `develop`. Behaviour is identical with one context. Not R8.
**Depends on:** B1; the registry queue.

**Files:**
- `bridge:jni_wallet.c`, `jni_asset.c`, `jni_transaction.c`, `jni_transaction_persist.c`; `bridge:jni_registry_gen.h`.
- `kt-core:bridge/NativeBridge.kt` and `native-at:core/bridge/NativeBridge.kt`.
- The Kotlin callers: `WalletTxPersister` first, then `AssetManager`, `UtxoManager`, `ChainReconciliationService`, `SyncService`, `WalletViewModel`.
- Create `host:wallet_handle_kat/`; two-context cases in `outpoint_classify_kat` and `saved_tx_array_kat`.
- `kt-core-test:security/SeedIsolationTest.kt`.

**Interfaces:**
```kotlin
external fun walletHandle(walletId: String): Long      // 0 = no such context; (slot shl 32) or generation (PD24)
// A leading `wallet: Long` on: getReceiveAddress, getChangeAddress, the balances, getAllTransactionHashes,
// getTransactionDetails, dumpAllAddresses, addWatchedAddresses, getFilterElementStats, outpointSpentState,
// registerAssetOutpoint, classifyOutpoints, jni_asset.c's three, createTransaction, quoteTransaction,
// isTransactionValid, digiDollarTxType, digiDollarEvent, previewDigiDollar, getSerializedTransactions,
// getSpendableDigiByteUtxos, getWalletBirthCheckpointHeight.
```

- [ ] **Step 1: Write the failing cases.** In `wallet_handle_kat`:
  - a stale handle is refused (`WALLET_HANDLE_NO_GENERATION_UNFIXED` reads a reused slot's wallet);
  - a slot-1 handle never reads slot 0 (`WALLET_HANDLE_SLOT0_UNFIXED`);
  - `classifyOutpoints` and `outpointSpentState` for B say "not held" for an output A holds;
  - `dumpAllAddresses` for B holds none of A's addresses, and never the union;
  - a wallet is never built from another context's saved records (`saved_tx_array` rule 2, per context).

  `SeedIsolationTest`: a handle is allowed on `getReceiveAddress`; no String or seed type appears on a seed entry point.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites, KAT sweep.** Expected PASS.
- [ ] **Step 5: Registry** (standard), then merge.

### Task B3: Signer and publish natives take a handle (registry PR, 4th; plumbing)

**Rulings:** G9, G10, G19, G29, G51, G52

**Landing:** a registry PR into `develop`. With one context, who can sign is unchanged. Not R8 by itself; shown to Mark with M2.
**Depends on:** B2; the C2 pin; X3; X5.

**Files:**
- `bridge:jni_transaction.c`, `jni_asset_send.c`, `jni_wallet_sign.c`, `jni_wallet.c`, `jni_derive.c`; `bridge:jni_registry_gen.h`; NativeBridge and its `native-at:` copy.
- The callers in `SendViewModel`, `TransactionBuilder`, `AssetManager`, `DigiScopeIdentity` (X3), `DigiIdManager`, `HubIdentity`, `ChatViewModel`, `RecoverFundsViewModel`, `Broadcaster`, `SyncService` (stranded sends).
- `native-at:native_core/{TaprootSignTransactionTest,UniversalRestoreTest,SeedBufferOwnershipTest}.kt`.
- `kt-app-test:ui/wallet/{SpendPreflightAppWiringTest,FeeShownIsFeeSignedGateTest}.kt`.

What changes:
- **A handle on:** `signPreparedTransaction`, `sendApprovedDigiDollar`, `buildAndSignAssetTransferTx`, `signMessage`, `signIdentityMessage`, `identityAddress` (on `BRDigiId.h` since X3), `publishTransaction`, the stem publish, `removeTransaction`, `registerRawTransaction`, `confirmTransaction`, `takeUnprovableServerHeight`.
- `getDigiDollarReceiveAddress` and `getDigiDollarTaprootKeyHex` come from the context's BIP86 key (C2) and read no seed.
- **Evaluated against the handle's context:** `spend_guard`, the signed-spend gate, `prepared_send_check`, `dd_send_check`, the asset sign gate and the one-family gate. `wallet_signed_spend_gate` checks every attached wallet's spent set, because a sweep may be of another attached wallet's phrase.
- The Kotlin flows capture the handle when they prepare.
- **Deleted, with no production caller:**
  - `signTransaction`. Update the `native-at:` copy and drop the native's name from the two app gates' lists. Rewrite `TaprootSignTransactionTest` onto `signPreparedTransaction`, or delete its two cases if a host KAT already signs P2TR through `BRWalletSignTransaction`; name that KAT in the PR.
  - `derivePrivateKeyWIF`. Once account public keys are stored, a non-hardened child private key would expose the account. Update the `native-at:` copy; delete `UniversalRestoreTest.derivePrivateKeyWIF_returnsValidWIF` and `SeedBufferOwnershipTest.derivePrivateKeyWIF_leavesTheCallersSeedIntact`. `seed_buffer_ownership_kat` keeps `jni_derive.c`'s other patterns.

- [ ] **Step 1: Write the failing cases.** A prepared send carries the handle it was built with; red if the handle is re-resolved at sign time. `dd_receive_key_kat` is unchanged across the move off the seed. `spend_guard_kat`, `coin_reservation_kat`, `asset_sign_gate_kat`, `one_family_kat`, `digiid_core_kat` and `tx_publish_ownership_kat` are green with re-anchored needles.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites, KAT sweep.** Expected PASS.
- [ ] **Step 5: Registry,** then merge.

### Task B4: Only the seed owner signs or derives [R8 · M2]

**Rulings:** G7, G48, G52, G82

**Landing:** a `develop` PR. Body-only: no registry change, so it never holds the queue. **[R8]:** waits for Mark at merge; after X6.
**Depends on:** B3.

**Files:**
- `bridge:jni_wallet.c`.
- `bridge:jni_bridge.h` (the accessor signatures).
- `bridge:jni_wallet_sign.c`, `jni_asset_send.c`, `jni_transaction.c`, `wallet_spend_gate.h`.
- Create `host:seed_owner_kat/`; a two-wallet case in `host:recovery_spend_gate_kat`.
- `kt-core-test:security/{NativeMemorySecurityTest,SeedIsolationTest}.kt`.

**Interfaces:**
```c
// jni_wallet.c, file-static: static uint8_t g_seed[64]; static int64_t g_seedOwner; static pthread_mutex_t g_seedLock;
int  seed_is_valid(const BRWalletContext *ctx);                       // 0 unless ctx owns the resident seed
int  seed_sign_transaction(const BRWalletContext *ctx, BRTransaction *tx, int forkId);
int  seed_derive_key(const BRWalletContext *ctx, BRKey *outKey, uint32_t chain, uint32_t index);
// and every accessor Coinomi step 2 leaves for the identity natives, with ctx first
void seed_zero(void);                                                  // zeroes and clears the owner, whoever owns it
```

- [ ] **Step 1: Write the failing cases** in `seed_owner_kat`:
  - a sign or derive for a context that does not own the seed is refused, with no output and the seed unchanged;
  - after `lockSession` no context can sign, and the owner is cleared;
  - installing a second seed while one is resident fails closed.

  Arms:
  - `SEED_OWNER_UNCHECKED_UNFIXED`: B's Digi-ID key is derived from A's seed;
  - `LOCK_ZERO_OWNER_ONLY_UNFIXED`;
  - `SPEND_GATE_ACTIVE_ONLY_UNFIXED`: a coin reserved in another attached wallet is signed again.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected PASS. `NativeMemorySecurityTest`'s exact checks, `SeedIsolationTest`, `WipeNativeStepsGateTest`, `seed_buffer_ownership_kat`, `spend_guard_kat` and `coin_reservation_kat` are green. Full suites, KAT sweep.
- [ ] **Step 5: Open the PR** with an M2 summary for Mark. Merge on his OK.

### Task B5: The MAX native and `prepareSendAll` (registry PR, 5th)

**Rulings:** G7, G9, G12, G19

**Landing:** a registry PR into `develop`. It adds a native that nothing calls until U1. Not R8.
**Depends on:** the C13 pin, X150, B2.

**Files:**
- `bridge:jni_transaction.c`: `createSendAllTransaction(handle, …)` under `WALLET_READ_GUARD`, returning network-format bytes.
- `bridge:jni_registry_gen.h`; NativeBridge and its `native-at:` copy.
- `kt-core:TransactionBuilder.kt`: `SpendNative.createSendAll`, its bridge implementation, `RecordingSpendNative`, and `prepareSendAll`. `prepareSendAll` runs SpendPreflight first, then the dust and custom-total rules.
- `kt-core:model/ApprovedSend.kt`: a core-internal factory from the built amount, with the KDoc at :17-19 updated.
- `kt-core-test:{PreparedSendTest,SpendPreflightOrderTest}.kt`; create `kt-core-test:SendAllPreparedTest.kt`.

**Interfaces:**
```kotlin
external fun createSendAllTransaction(wallet: Long, address: String, feePerKb: Long, customTotalFee: Long,
                                      outResult: IntArray /* status, feeRaised */): ByteArray?
fun TransactionBuilder.prepareSendAll(address: String, feePerKb: Long, customTotalFee: Long?): PreparedSendResult
```

- [ ] **Step 1: Write the failing tests.**
  - `SendAllPreparedTest`: the approved amount is the built output, and the fee is the signed fee. Red against a builder made from `BRWalletMaxOutputAmount` plus `createTransaction`.
  - `SpendPreflightOrderTest`: MAX runs the pre-spend pass before building. Red when the pass runs after.
  - In a wallet that holds a carrier, MAX leaves the carrier out.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites, KAT sweep;** `fee_quote_kat` and `prepared_send_core_kat` green. **Step 5: Registry,** merge.

### Task K0: Split each per-wallet clear from the chain clear

**Rulings:** G2, G9, G84

**Landing:** a `develop` PR after X5. In the step-1 layout the chain scope is the wallet's own, so the same files are deleted as today. Not R8.
**Depends on:** X5.

**Amended by O7 (2026-11-19, G96).** After E1 the chain scope's names are the legacy wallet's own names, so the split must also hold when the wallet is legacy: a per-wallet clear of legacy leaves the three chain files and the chain keys of `dgb_sync_data<net>`. Today `clearSyncData` clears that whole prefs file and deletes all three files (`kt-core:WalletManager.kt:1854-1855`, `:1866`, `:1874-1875`). K0 stays behaviour-neutral on `develop`, where each wallet's chain is still its own. It shapes `clearWalletHistory` so that the keys and files it clears are given as lists, and E1 supplies legacy's. Which keys of that file are the chain's is G96's second open point; K0 does not decide it.

**Files:**
- `kt-core:sync/HistoryRebuildOnUpgrade.kt` (`clearTransactionCacheForRebuild`).
- `kt-core:WalletManager.kt` (`clearSyncData`, the seed-change verdict, `rebuildFromChainRescan[From]`).
- `kt-app:service/CfRestorePreflight.kt` and the preflight reset in `SyncService.kt`.
- `kt-app:StaleDataWiper.kt` (`wipeSyncBlobs`).
- `kt-core:wallets/WalletStoreEraser.kt`.
- `host:scan_floor_kat`'s Android-source tokens.

- [ ] **Step 1: Write the failing test.** With a chain scope distinct from the wallet scope, none of `clearSyncData`, the rebuild clear, the preflight reset and `wipeSyncBlobs` for wallet X touches scope Y's chain files. Red on `develop`: each deletes `SavedBlockStore` and `FilterHeaderStore` under the wallet's own scope.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement** `clearWalletHistory(scope)` plus `clearChain(chainScope)`.
- [ ] **Step 4: Run.** Unchanged and green: `WalletManagerClearSyncDataTest`, `HistoryRebuildOnUpgradeTest`, `StaleDataWiperWalletResetTest`, `CfRestorePreflightTest`, `WalletManagerRescanClearsCfLedgerTest`. `scan_floor_kat` is green with its tokens re-anchored and its order the same.
- [ ] **Step 5: Full suites, KAT sweep, open the PR.**

### Task K1: An explicit wallet scope at every store call; write epochs per scope

**Rulings:** G9, G11, G53, G54, G96

**Landing:** a `develop` PR after X5. Not R8.
**Depends on:** X5.

**Amended by O7 (2026-11-19, G96).** G53 keys each store's epoch and io lock by target. Since G96, `Wallet(legacy)` and E1's `Chain(testnet)` would name the same three files. K1 changes nothing for this on `develop`, where `Wallet(scope)` is the only target; the rule that one file has one target is G96's fourth open point and is E1's to build.

**Files:**
- `kt-core:sync/{SavedBlockStore,FilterHeaderStore,CfScanLedgerStore,SavedTransactionsStore,CfAbandonmentStore,ChainTipStore,SavedTransactionsCheckpoint,CfStateFlush,HistoryRebuildOnUpgrade}.kt`.
- `kt-core:{OutgoingTxStore,WalletTxPersister}.kt`.
- About 35 app call sites (`SyncService`, `SyncWorker`, `StaleDataWiper`, `WalletViewModel`, …).
- `kt-core-test:wallets/StoreSuffixSourceGateTest.kt` (widened); create `kt-core-test:sync/CfScanLedgerStoreEpochTest.kt`.

- [ ] **Step 1: Write the failing tests.**
  - The widened gate fails any call relying on `scope = ActiveWallet.scope(ctx)` and lists the 59 defaulted functions. Red on `develop`.
  - `CfScanLedgerStoreEpochTest`: deleting A's ledger never marks B's pending write OBSOLETE. Red on `develop`'s one epoch per store.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement:** remove the defaults, pass the scope, and make the epochs and io locks per scope.
- [ ] **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR.**

### Task K2: The app lock state becomes app-wide

**Rulings:** G2, G9, G11, G54, G64, G89

**Landing:** a `develop` PR after X5. Not R8: what unlocks, and when the seed is zeroed, are unchanged. The reviewer confirms this; if review finds any change to when the app locks or unlocks, the PR becomes [R8].
**Depends on:** `fix/wallets-device-wide-settings` merged (X5).

**Files:**
- Create `kt-app:AppLock.kt`.
- `kt-core:WalletManager.kt` loses its lock state.
- `kt-app:ui/navigation/{AppNavigation,LockGatePolicy}.kt`, `AutoLockPolicy`, `kt-app:MainActivity.kt`, `kt-app:ui/onboarding/UnlockScreen.kt`.
- `kt-app:service/SyncService.kt`: the keepalive waits on the app lock.
- `kt-app-test:ui/navigation/{LockGatePolicyTest,AutoLockPolicyTest}.kt`; create `LockStateSurvivesSessionSwapTest.kt`.

- [ ] **Step 1: Write the failing tests.**
  - Building a second `WalletManager` in-process leaves the app Unlocked. Red on `develop`: its init sets Locked, and the gate routes to unlock.
  - A source gate pins R5: only the wipe's quiesce (and, from S3, the switch) reaches seed zeroing. `lockUi`, `onStop` and the auto-lock never do.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected PASS; `LockGatePolicyTest` and `AutoLockPolicyTest` keep their verdicts. Full suites. **Step 5: Open the PR.**

### Task K3: A `WalletSessions` holder replaces the per-wallet singletons (active-only; switching still restarts)

**Rulings:** G8, G9, G10, G11, G45, G55, G56, G57

**Landing:** a `develop` PR after X5. Not R8.
**Depends on:** K1, K2; #268 (merged); `fix/wallets-device-wide-settings` (X5).

**Files:**
- Create `kt-app:session/{WalletSessions,WalletSession}.kt`.
- `kt-app:di/AppModule.kt`: the per-wallet `@Provides` are removed.
- `MainActivity`, `SyncService`, the 11 ViewModels, the 2 EntryPoints, and AppNavigation's parameters.
- `kt-core:wallets/ActiveWallet.kt`: the active id becomes live; `registry.setActive` and the holder change together.
- `kt-core:PriceProvider.kt`: its cache follows the holder.
- `kt-core-test:db/DatabaseOpenPlanTest.kt`, `kt-app-test:di/DatabaseKeyPlanTest.kt`, `kt-core-test:wallets/ActiveWalletTest.kt`; create `kt-app-test:session/PerWalletInjectionSourceGateTest.kt`.

**Interfaces:**
```kotlin
class WalletSession(val scope: WalletScope, val handle: Long, val db: WalletDatabase, val walletManager: WalletManager,
                    val keyStore: KeyStoreManager, val outgoing: OutgoingTxStore, val persister: WalletTxPersister,
                    val utxos: UtxoManager, val assets: AssetManager, val metadata: AssetMetadataService,
                    val provenance: ProvenanceStore, val backfill: AssetHistoryBackfill,
                    val remainderProof: PrefsRemainderProofStore, val hubTokens: HubTokenStore) { fun close() }
@Singleton class WalletSessions @Inject constructor(...) {
    val active: StateFlow<WalletSession?>
    val generation: StateFlow<Long>
    fun attached(): List<WalletSession>          // the active one only until E2b
    fun sessionFor(id: String): WalletSession?
}
```

- [ ] **Step 1: Write the failing tests.**
  - The gate refuses any `@Inject` or `@Provides` of a per-wallet type outside the holder. Red on `develop`.
  - `DatabaseOpenPlanTest` and `DatabaseKeyPlanTest`: two databases open against the one password record, and setting one aside never touches the other.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** Only the active session is built.
- [ ] **Step 4: Run, full suites.** Expected PASS; behaviour identical. **Step 5: Open the PR.**

### Task K4: One SpendPreflight pass per session

**Rulings:** G9

**Landing:** a `develop` PR after X5. It must land before any second `AssetManager` exists (E2b). Not R8.
**Depends on:** K3.

**Files:**
- `kt-core:TransactionBuilder.kt` (SpendPreflight), `kt-core:asset/AssetManager.kt` (the install at :636-641).
- `kt-core-test:{SpendPreflightWiringTest,SpendPreflightOrderTest,asset/AssetPreSpendPassTest}.kt`, `kt-app-test:ui/wallet/SpendPreflightAppWiringTest.kt`; create `kt-core-test:SpendPreflightPerSessionTest.kt`.

- [ ] **Step 1: Write the failing test.** With A active and B's `AssetManager` built after A's, A's plain-DGB spend runs A's pass and A's carriers are held out. Red on `develop`: the one global slot holds the last one built. A spend with no pass installed is refused.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** The pass belongs to its session; `TransactionBuilder` is reachable only from the active session.
- [ ] **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR.**

### Task K5: R6, server work for a wallet that is not active waits until it is active

**Rulings:** G9, G58, G59

**Landing:** a `develop` PR after X5. Only the active session exists then, so the gate always passes. It must land before E2b. Not R8.
**Depends on:** K3.

**Files:**
- Create `kt-core:sync/ServerWorkGate.kt`.
- `kt-core:asset/AssetManager.kt`: `settleFromIndexer`, the server half of `fetchRawTransactionBytes`, `refreshRules`, metadata, `retryMissingAssetMetadata`, and the provider fallback.
- `kt-core:ipfs/{IpfsClient,AssetMetadataService}.kt`.
- `kt-core:reconcile/{ChainReconciliationService,PostUpgradeReconciler}.kt`.
- `kt-app:service/SyncService.kt`: `maybeRunConfirmationReconcile` at the 30-tick site and at sync complete.
- `kt-app:MainActivity.kt`: the post-upgrade start.
- Create `kt-core-test:asset/NonActiveServerWorkGateTest.kt` and `kt-core-test:ServerCallSiteGateTest.kt`.

- [ ] **Step 1: Write the failing tests.** `NonActiveServerWorkGateTest` uses recording fakes for `MultiEndpointAssetClient`, `DigiScopeAssetClient`, `DigistampAssetClient`, `IpfsClient` and `DgbNodeClient`.
  - A non-active session's full sweep and reconcile make zero requests, while its local asset rows still update. Red on `develop`: `retryMissingAssetMetadata` calls IPFS, and the walk sends the wallet's own txids to assets.digistamp.co.
  - Once the session is active, the first sweep after one sweep's dwell (about 30 s) sends the deferred requests.
  - A switch cancels the outgoing wallet's maintenance that is still running.
  - `ServerCallSiteGateTest` fails on any server entry point in these classes outside the gate.
- [ ] **Step 2: Run.** Expected FAIL.
- [ ] **Step 3: Implement.** The gate sits at each call that sends, keyed on "is this session active".
  - While a wallet is not active, its parent walk uses its own copies only: a missing parent is `Unavailable`, and the walk keeps its place.
  - The post-upgrade reconcile runs at the wallet's first turn as active after the version bump.
  - Correct the wrong comments "discloses only txids we broadcast ourselves" (`SyncService.kt` at the reconcile's KDoc; `PostUpgradeReconciler.kt:153-154`). `pendingTxids` sends every unconfirmed row, receives included.
- [ ] **Step 4: Run, full suites.** Expected PASS; `AssetHeldMeansNativeHoldsItTest` and the transfer-rule tests unchanged. **Step 5: Open the PR.**

### Task K6: Each per-wallet object reads its own native wallet; callbacks route by wallet

**Rulings:** G9, G45, G46, G51

**Landing:** a `develop` PR after X5 (one session, so behaviour-neutral). Not R8.
**Depends on:** B2, K3.

**Files:**
- `kt-core:asset/AssetManager.kt` (every native read through its session's handle), `kt-core:asset/AssetMaintenanceGate.kt` (per session, with its own synced flag and frontier).
- `kt-core:WalletTxPersister.kt` (its `Source` bound to a handle), `kt-core:UtxoManager.kt`, `kt-core:reconcile/ChainReconciliationService.kt`.
- `kt-core:dandelion/Broadcaster.kt`: the embargo's fluff target is the publishing wallet's handle.
- `kt-app:service/SyncService.kt`: the callback router.
- Process objects keyed by wallet: `HistoryRebuildOnUpgrade.lastOutcome` and its notice dismissals, `PostUpgradeReconciler`'s failure flag, `RecoveryScanService.state` and `lastClassify`, `SyncFrontierSession`.
- Create `kt-core-test:CrossWalletPersistTest.kt` and `kt-core-test:asset/AssetSweepHandleTest.kt`.

- [ ] **Step 1: Write the failing tests.**
  - `CrossWalletPersistTest`: a transaction registered only in B's native wallet lands in B's database and saved transactions, never A's. Red with today's handle-less `Source`.
  - `AssetSweepHandleTest`: B's sweep, with A active, writes no A row into B and prunes none of B's rows. Red today: `pruneUnownedAssetRows` uses A's owned set, from `dumpAllAddresses`, and deletes every B row.
  - A callback tagged B writes only B's database.
  - A source gate refuses `ActiveWallet` reads in per-session classes.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR.**

### Task K7: ViewModels rebind when the active session changes, tested with a fake in-process switch

**Rulings:** G8, G60, G61, G87

**Landing:** a `develop` PR after X5. Production still restarts on a switch. Not R8.
**Depends on:** K3.

**Files:**
- `kt-app:ui/wallet/{WalletViewModel,SendViewModel}.kt`, `ui/asset/AssetViewModel.kt`, `ui/digiid/DigiIdViewModel.kt`, `ui/hub/{ChatViewModel,ProfileViewModel}.kt`, `ui/recovery/RecoverFundsViewModel.kt`, `ui/wallets/WalletsViewModel.kt`, `ui/settings/SeedViewScreen.kt`, `rememberActiveWalletName`.
- Create `kt-app-test:ui/SwitchRebindTest.kt` and `kt-app-test:ui/settings/SeedViewSessionTest.kt`.

Each ViewModel observes the active session (`flatMapLatest` plus the generation) and drops every wallet-derived value on a change. Resetting the nav graph on a switch is an acceptable alternative; the tests are the same.

- [ ] **Step 1: Write the failing tests.** After a fake switch from A to B:
  - B, with no rows, shows an empty list and B's balances. Red on `develop`: the list is replaced only when the new one is non-empty (`WalletViewModel.kt:879-916`), and the anti-flash guard (`:683`) keeps A's balance and writes it to A's prefs;
  - a late result from A's generation is dropped;
  - Send and Asset cancel Confirming, the DigiDollar review and the asset approval;
  - a switch while Sending is refused;
  - `DigiIdViewModel`'s choice and `ChatViewModel`'s address reset;
  - Recover funds cancels;
  - `WalletsViewModel`'s running id stays live.

  `SeedViewSessionTest`: the phrase and the name come from one session. Red when the name comes from the registry and the phrase from a stale `WalletManager`.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR.**

### Task K8a: `HubWebSocket.connect()` closes the socket it replaces (added by G62)

**Rulings:** G62

**Landing:** a `develop` PR after T, labelled a behaviour change that may ship before step 2, with its own ROADMAP line. Not R8.
**Depends on:** X5.

- [ ] A test red on `develop`: `connect()` replaces the socket without closing it. K8 depends on this task and keeps only neutral changes.

### Task K8: The Hub, Digi-ID and the web session follow the active session (D2)

**Rulings:** G8, G10, G61, G62, G63, G86, G87, G89

**Landing:** a `develop` PR after X5. Not R8.
**Depends on:** K3, K7, A5; X5; X3 (`DigiScopeIdentity`).

**Files:**
- `kt-core:digiscope/DigiScopeClient.kt`: no captured token store.
- `kt-core:hub/HubWebSocket.kt`: close before a token swap; each socket bound to the token it opened with.
- `kt-core:digiid/DigiIdManager.kt`; `kt-app:ui/hub/ChatViewModel.kt`.
- `kt-app:{WebSessionIsolation,IdentitySessionEraser}.kt`, `kt-app:ui/digistamp/DigistampWebViewHost.kt`.
- Create `kt-app:WebViewProcessLatch.kt`.
- The `SeedViewGate.revoke` hook.
- `kt-app-test:{WebSessionIsolationTest,WebSessionIsolationGateTest,IdentitySessionEraserTest}.kt`, `kt-core-test:security/{HubTokenStoreScopeTest,NetworkLeakTest}.kt`; create `kt-app-test:HubSwitchTest.kt` and `kt-app-test:WebViewLatchTest.kt`.

- [ ] **Step 1: Write the failing tests.**
  - **Hub:** a chat post signed by B never goes out on A's socket. Red on `develop`: `connect()` replaces the socket without closing it. B's quick login never writes A's token store. A 401 clears the store of the wallet that sent the request. Responses from an older generation are dropped.
  - **D2 latch:** it is set by any WebView construction and by any `CookieManager` use, including `logSessionCookieNames` and the eraser. `destroy()` does not clear it; `DigistampWebViewHost.exists()` turns false after `destroy()` and so cannot serve.
  - **Latch clear:** `WebSessionIsolation` runs for the incoming wallet in-process, before any WebView exists. It never calls `endWebSessionOnly`, which touches `CookieManager`.
  - **Latch set:** the switch restarts through `AppRestart`.
  - A source gate fails a planted `CookieManager.getInstance()` that skips the latch.
  - `NetworkLeakTest`'s "phrase" ban is kept. It also matches "passphrase"; rename locals rather than weaken it.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR.**

### Task L1: Extract `ReconcileScreen.kt`'s remaining literals (added by G76)

**Rulings:** G76

**Landing:** a `develop` PR after T, in the strings cluster before E3. Not R8.
**Depends on:** X5.

- [ ] Extract the screen's remaining hardcoded literals in all 13 locales and add it to `OnboardingHardcodedStringTest.COVERED`. E4 depends on this task. Any screen that joins `COVERED` in step 2 gets the same prior PR.

## Stage 4: behaviour changes on `integration/multi-wallet-step2`

**Rulings:** G15

Every PR here goes into the integration branch with CI (A3), an independent review and, where marked, Mark. The branch merges `develop` at least weekly. Device testing starts after E3; neither intermediate state reaches `develop`:
- between E2 and E3, a wallet that lags the shared window cannot sync;
- between B6 and E4, the old Full rebuild still runs, and from E1 on its chain clear means the shared chain.

### Task B6: Adopt the wallet-set engine; delete the engine rescan (registry PR, 6th)

**Rulings:** G1, G7, G9, G12, G16, G19, G29, G30, G31, G32, G34, G43, G45, G46, G48, G50, G64, G65, G66, G67, G70, G82

**Landing:** a registry PR into the integration branch. It is the first step-2 PR to change the rebuild or rescan, so it carries `CLAUDE.md` correction 9. Not R8.
**Depends on:** the C8 and C10 pins, B2, B3.

**Files:**
- `bridge:jni_peer.c`, `jni_wallet.c`, `jni_bridge.h`, `jni_registry_gen.h`.
- NativeBridge and NativeCallback, with their `native-at:` copies.
- `kt-core:WalletManager.kt` (the rescan calls in create and restore); `kt-app:service/SyncService.kt` (the `markInitialSyncDone` caller).
- The `recreate_resumes_near_tip_kat` and `saved_blocks_reentrant_kat` mirrors, for N wallets.
- Create `host:wallet_hold_kat/`; `host:rescan_removal_kat` part 2.
- `kt-core-test:WipeNativeStepsGateTest.kt` (allows exactly the detach native).
- `CLAUDE.md` lines 76, 82 and 84.

**Interfaces:**
```kotlin
external fun attachWallet(wallet: Long): Int      // C8 attach for a wallet at the tip; never sets needsRecreate
external fun attachWalletAt(wallet: Long, frontier: Long, hashBelow: ByteArray?): Int  // C10 under the C9 rule, from a
                                                  // frontier record (PD14); returns the verdict; never sets needsRecreate
external fun detachWallet(wallet: Long): Int      // stops routing, then the core detach, then frees (PD26)
external fun engineHasWallets(): Boolean           // replaces isWalletLoaded for SyncService and SyncWorker
external fun activeWalletReady(): Boolean          // the active context is attached and owns the resident seed
// deleted: rescan(), markInitialSyncDone()
```

What changes:
- `startSync` builds the shared manager through `BRPeerManagerCreateHolding` over the attached contexts; only the active one until E2b. `earliestKeyTime` is the lowest attached creation time, which matters only with no saved window.
- Each context has a holder (NONE, SHARED, BAND).
- Per-wallet upcalls carry the wallet id; G3 checks their descriptors.
- The wipe's quiesce stops every sync context.
- **Deleted:** `bridge_syncStopped`'s post-first-sync `BRPeerManagerRescan`, `g_initialSyncDone`, `markInitialSyncDone`, `NativeBridge.rescan` and their callers. Create and restore attach instead (E7). `BRPeerManagerRescan` stays in the core.

- [ ] **Step 1: Write the failing cases.**
  - `wallet_hold_kat`: attaching a wallet the band holds is refused. Red arm: the holder check removed.
  - The recreate mirror: a recreate with three attached wallets resumes near the tip and restores the one ledger. Red: the pre-fix order arm.
  - `rescan_removal_kat` part 2: no bridge caller of `BRPeerManagerRescan` remains.
  - A first sync with `has_synced=false` no longer rewinds `lastBlock`. Red on today's bridge.
  - `WipeNativeStepsGateTest` allows exactly the detach native.
- [ ] **Step 2: Run.** Expected FAIL.
- [ ] **Step 3: Implement**, and correct `CLAUDE.md` (no rescan pins a peer; the canon is pinned by the tip-stall FAST tier and the corrupt-chain heal; Repair and Rescan replace the Full rebuild).
- [ ] **Step 4: Run, full suites, KAT sweep.** Expected PASS. **Step 5: Registry,** merge.

### Task E1: Shared chain stores, frontier records, and the migration from step 1

**Rulings:** G1, G32, G53, G65, G68, G69, G91, G96

**Landing:** the integration branch, in the same PR as E2 or immediately before it. Its migration is not invoked at launch until E2. Not R8.
**Depends on:** K0, K1; `fix/wallets-ready-slot-and-gate-hygiene` (X5); the four design points below.

**Amended by O7 (2026-11-19, G96).** The shared chain gets no new names. It lives under the legacy wallet's 4.1.4 names (PD13, as reversed). What that changes in this task:
- `ChainStores` resolves to legacy's names: the three files that `WalletStores.fileName` gives for the legacy scope, and `dgb_sync_data<net>` for the chain keys. It still hands no File to a writer (G53).
- The migration links, copies, renames and ranks nothing. It writes each READY wallet's frontier from that wallet's own ledger (G68). `chainMigration` stays a reserved registry field, for E1 to record that the migration ran.
- A removal of legacy, a hand-off of legacy and every per-wallet clear of legacy leave the three files and the chain keys. The full wipe and a chain-scoped reason erase them, as the wipe does today for legacy.
- Withdrawn: the hard-link test and its timing record; the red arms "rename instead of link or copy" and "seed from the active wallet instead of the highest tip"; the three `ChainMigrationTest` cases G69 added about ranked candidates.
- G1's "E1 erases chain files only, and says so" stands. E1 also changes what the removal of legacy erases, but only of chain files and chain keys, never of seed, key or PIN material, so it is still not [R8]; its PR says so.

**To be designed before this task is written** (G96). None is decided in this plan, because each can change what a wallet shows or what a removal or the wipe erases:
1. **Legacy's own band files. Decided by G97 (2026-11-19): the `_legacy` band names reserved in A1; what follows is the reason they are needed.** The design forbids a band sharing a file with the shared engine (spec:708-710), so a band for legacy (Rescan this wallet, Coinomi's accounts, a lagging frontier, a rejoin below the window) cannot use legacy's names. It needs file names and chain keys that 4.1.4 never wrote. They must be in `WalletStores`' lists, so that removal, the eraser, the wipe and `WipeLeavesNoWalletNamesTest` reach them, and G38's retire step must never run on the shared keys. If they are new names, A1 reserves them before the 4.1.5 cut (O10). B7, E3, E4 and E5 wait on this for the legacy wallet only.
2. **Whose key is whose in `dgb_sync_data<net>`.** That file now holds the chain's keys and legacy's own (`has_synced`, `last_balance`, the checkpoint keys). Needed: an exhaustive list, pinned by a test that fails on a key in neither part; how a per-wallet clear and the removal of legacy, with its read-back, work on a file that must remain; how a chain-scoped clear keeps legacy's own keys. The eraser already keeps one shared key in a legacy-named file (`custom_rpc_endpoint` in `dgb_reconcile<net>`: `kt-core:wallets/WalletStoreEraser.kt:96`, `:214`).
3. **No usable chain under legacy's names.** Legacy was removed under step 1, never synced on this network, or its chain fails the preflight. G69's fallback is the floor: the chain is not seeded, the engine arms as G64 says for no stored chain, and each wallet joins from its own frontier or bands over its own files. Whether another wallet's chain may instead be copied under legacy's names is not decided.
4. **One store target per file.** G53 keys each store's epoch and io lock by target. `Wallet(legacy)` and `Chain(testnet)` would name the same file under two locks. After E1 only the chain target may reach the three files.

**Files:**
- `kt-core:wallets/{WalletStores,WalletScope,WalletRegistry,WalletStoreEraser}.kt`; create `kt-core:wallets/ChainMigration.kt`.
- `kt-core:sync` stores; `kt-core:WalletDataEraser.kt`.
- `kt-app:DigiByteApp.kt`: the migration runs beside housekeeping, before the database or SyncService opens.
- Tests: `kt-core-test:wallets/{UpgradeFrom414Test,LegacyStoreNameParityTest,PerWalletStoresTest,WalletDataEraserReachTest,WalletStoreEraserTest,WalletRemovalTest,WalletRegistryTest}.kt`, `kt-core-test:WipeLeavesNoWalletNamesTest.kt`; create `kt-core-test:wallets/{ChainMigrationTest,FrontierRecordStoreTest,PeerStoresSharedTest}.kt`; X-ROLLBACK (a).

**Interfaces:**
```kotlin
object ChainStores {        // NETWORK scope; resolves to legacy's 4.1.4 names, which stay as they are (G96)
    fun savedBlocks(ctx: Context, testnet: Boolean): File
    fun filterHeaders(ctx: Context, testnet: Boolean): File
    fun cfLedger(ctx: Context, testnet: Boolean): File
    fun prefs(ctx: Context, testnet: Boolean): SharedPreferences          // legacy's dgb_sync_data<net> (G96)
}
data class Frontier(val height: Long, val hashBelow: String?, val holder: Holder)   // PD14
enum class Holder { SHARED, BAND, NONE }
// WalletRegistry gains: frontier(id, testnet), recordFrontiers(testnet, ids, height, hash) (one commit, raises only),
// setHolder(id, testnet, holder), bandPending(id, testnet) / setBandPending(...), chainMigration(testnet).
class ChainMigration(...) { fun run(testnet: Boolean): MigrationState }   // idempotent
```

- [ ] **Step 1: Write the failing tests.**
  - **`UpgradeFrom414Test`, re-anchored:**
    - the migration creates, moves, links, copies and deletes no file, and legacy's three chain files are byte-identical after it (G96);
    - every legacy store outside the chain is byte-identical;
    - legacy's names are unchanged;
    - the shared engine resumes from legacy's chain with no re-sync (legacy's frontier equals the shared one).
  - **`ChainMigrationTest`,** from the step-1 layout with four wallets: legacy at the tip, B days behind (inside the window), C weeks behind, D with no key record.
    - The shared chain is legacy's chain, where it stands. No file moves, and B's and C's chains are not ranked against it (G96).
    - Each wallet's frontier is written from its own ledger.
    - B attaches or bands, C bands, D waits.
    - Nothing is deleted before its frontier is durable.
    - A kill after each step resumes to the same end state.

    Red arms: delete before the frontier; a hand-off or a per-wallet clear of legacy that deletes a chain file or a chain key (G96).
  - **`ChainMigrationTest`, a wallet ahead of legacy (G96):** the engine starts from legacy's tip, and the wallet that is ahead keeps its own files and waits or bands under C9's rule. Nothing of its chain is copied.
  - **Hard link:** withdrawn by G96. Nothing is linked or copied.
  - **`FrontierRecordStoreTest`:** `recordFrontiers` only raises; a wallet not listed keeps its record.
  - **`WalletRemovalTest`:** removing legacy keeps the shared chain and every byte of the other wallets. Since G96 that means the three files under legacy's names and the chain keys stay, while legacy's own keys and files go and read back gone.
  - **`PeerStoresSharedTest`:** `saved_peers` and `saved_peer_penalties` live with the shared chain keys, in legacy's `dgb_sync_data<net>` (G96), and no per-wallet path writes them (PD17).
  - **X-ROLLBACK (a):** `RegistryForwardCompatTest`.
- [ ] **Step 2: Run.** Expected FAIL.
- [ ] **Step 3: Implement.**
  - `clearChain` (K0) now means the shared chain.
  - A per-wallet reason detaches the wallet and marks it for a band.
  - Removal never deletes the shared chain; the full wipe does.
- [ ] **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR** (with E2, or right before it).

### Task E2: `SyncService` runs the shared engine: the active wallet attached, frontier records kept

**Rulings:** G2, G32, G41, G42, G53, G55, G64, G65, G67, G68, G70, G71, G72, G85, G96

**Landing:** the integration branch. E2 and E3 land back to back. Not R8 (D1 is today's behaviour).
**Depends on:** E1, B6, K4, K5, K6, the C9, C10 and C11 pins.

**Amended by O7 (2026-11-19, G96).** The arming inputs G64 names (`chain_synced`, `sync_start_height`, `saved_blocks_tip`) are read from legacy's `dgb_sync_data<net>`, not from `dgb_chain<net>`. `StoreDurabilityCallerGateTest` refuses a direct write to the three legacy-named chain files, not to `chain_*` (G53).

**Files:**
- `kt-app:service/SyncService.kt`: the shared writers, and routing through K6.
- `kt-core:sync/{RecreateSequence,CfStateFlush,CfRecoveryPolicy}.kt`.
- `kt-app:service/{CfRestorePreflight,Bip158WatchdogPolicy}.kt`; `kt-core:asset/AssetMaintenanceGate.kt`.
- The 20 `SyncService.kt` source gates, re-anchored deliberately and none deleted.
- Tests: `kt-core-test:sync/{RecreateSequenceTest,CfStateFlushTest}.kt`; `kt-app-test:service/{StoreDurabilityCallerGateTest,TransactionCheckpointPreflightTest,CfRestorePreflightTest}.kt`; `kt-app-test:ui/wallet/PullToRefreshKeepsChainGateTest.kt`.
- Create `kt-app-test:service/{SharedLedgerCheckpointGateTest,FrontierRecordTest,EmptyEngineTest,AbandonmentFanOutTest}.kt`.

What changes:
- **The engine** is built from the shared chain. The active wallet, opened from its seed as today, is attached through `attachWalletAt` (B6) from its frontier once C9's rule says so; otherwise it waits and E3 bands it. The others are not attached yet.
- **One shared-ledger writer.** It persists only when every attached wallet's transaction checkpoint covers the ledger's frontier, so has_synced implies a durable ledger. After each durable persist it raises the frontier record of every wallet attached at that persist, and only theirs (PD14).
- **An engine with no wallet attached** runs and persists.
- **A cold start** rewinds the restored shared ledger to the lowest frontier among the wallets it will attach, when that is at or above H_min (C9). A wallet whose frontier is below H_min bands.
- **Recreate.** `RecreateSequence` flushes the shared state and every attached wallet's checkpoint, rebuilds through `recreatePeerManagerResumingNearTip`, then restores the one ledger and re-attaches the set.
- **The watchdogs** (BIP158, tip-stall, zero-peer, keepalive revival) read the shared frontier.
- **An abandonment** on the shared ledger is written to the record of every wallet attached at that moment.
- **The attach list** comes from the registry's READY list, never from `allEntryIds()`.
- **Stranded-send rebroadcast** runs for the active wallet only (PD4).
- **The restore preflight** answers by C11's scope.

- [ ] **Step 1: Write the failing tests.**
  - **`SharedLedgerCheckpointGateTest`:** two attached wallets, X's checkpoint lagging, and the shared ledger write waits. Red on `develop`: only the active wallet's gate exists.
  - **`FrontierRecordTest`:** a wallet attached last session that cannot attach this session keeps its frontier while the shared ledger advances. At its later attach the ledger rewinds to it, and a receive in between is found. Red arm: attach at the shared frontier.
  - **`EmptyEngineTest`:** with zero attached wallets the engine runs and persists, and no frontier moves.
  - **Recreate:** with three attached wallets it restores one ledger near the tip, and every wallet's checkpoint.
  - **`AbandonmentFanOutTest`:** an abandonment reaches every attached wallet's record. Red arm: active wallet only.
  - **`CfRestorePreflightTest`:** `TRANSACTION_CHECKPOINT_SHORT` for B leaves every shared chain file. Red today.
  - **The rule:** an active wallet that lags the shared window is not attached. Red arm: attached without the rule.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR.**

### Task B7: The band's sync context and the hand-off natives (registry PR, 7th)

**Rulings:** G7, G19, G20, G23, G29, G31, G33, G34, G35, G36, G38, G46, G48, G50, G67, G71, G73, G74, G96

**Landing:** a registry PR into the integration branch. Not R8.
**Depends on:** B6, the C9 and C10 pins, A4; #109 first if revived.

**Amended by O7 (2026-11-19, G96).** "The wallet's band files" are the wallet's step-1 names for every wallet but legacy. Legacy's names are the shared chain, so legacy's band cannot use them; what it uses is G96's first open point, decided by G97 (the `_legacy` band names in A1), and `startBand` for legacy waits on it. `band_context_kat`'s "band saves never touch the shared files" gains the legacy case.

**Files:**
- `bridge:jni_peer.c`, `jni_bridge.h`, `jni_registry_gen.h`.
- NativeBridge and NativeCallback, with their `native-at:` copies.
- Create `host:band_context_kat/` (compiles `jni_peer.c`).

**Interfaces:**
```kotlin
external fun startBand(wallet: Long, birthOrFrontier: Long): Int   // today's restore/resume path over the wallet's band files
external fun bandStatus(): LongArray                               // [F, held wallet handle, peers, state]
external fun quiesceBand(republishInFlight: Boolean): Int          // flush, stop routing, Disconnect, Free; BUSY while a publish is in flight
external fun handOffBand(wallet: Long): Int                        // BRPeerManagerHandOffWallet; returns the C9 verdict
```

The band context:
- It has its own guard, saved blocks and peers, status mirror, BIP158 state and its own manager generation counter.
- It is built with the holding constructor, and its callbacks carry it as `info`, so its saves go to the wallet's band files.
- C9's cap is applied at every poll.
- **Peer policy:**
  - Tor, which is process-wide;
  - the own-node pin: an exclusive pin lets no other peer in through the band. A4 decides the two-connection case;
  - the canon first;
  - a peer set disjoint from the shared engine's (PD17);
  - saved peers and penalties come from a read-only snapshot of the shared engine's, and the band never writes them.
- The wipe's quiesce stops it.

- [ ] **Step 1: Write the failing cases** in `band_context_kat`.
  - A band teardown (a 5 s disconnect) never holds the shared guard: TSan, plus a shared keepalive tick that completes during it. Red arm: one guard for both.
  - Attaching while a holding manager holds W is refused.
  - The device total stays at or below 8.
  - Band saves never touch the shared files. Red arm: the band callback's `info` is NULL.
  - The band never writes peers or penalties.
  - With an exclusive own-node pin, the band dials no other peer.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites, KAT sweep.** Expected PASS. **Step 5: Registry,** merge.

### Task E3: Bands: scheduling, preemption, the hand-off executor, durability, spending and the wipe

**Rulings:** G1, G31, G34, G35, G36, G37, G38, G42, G48, G64, G67, G73, G74, G75, G77, G83, G91, G96

**Landing:** the integration branch, immediately after E2. Device testing starts after it. Not R8.
**Depends on:** E2, B7.

**Amended by O7 (2026-11-19, G96).** Step 6 of the executor ("Delete the band files") and G38's retire of the wallet's chain keys never touch anything under legacy's names: for legacy they would delete the shared chain. What legacy's hand-off deletes is G96's first open point, decided by G97 (the `_legacy` band names in A1). `BandHandoffExecutorTest` adds: a hand-off of legacy leaves the three chain files and every chain key.

**Files:**
- Create `kt-app:service/{BandScheduler,BandHandoffExecutor}.kt`.
- `kt-app:service/SyncService.kt`; `kt-core:wallets/WalletRegistry.kt`.
- `host:band_handoff_kat`: a source-token check that the executor's step order equals `BRBandHandoffSteps`, in the `scan_floor_kat` pattern.
- `values*/strings_wallet.xml` ×13 (`wallet_handoff_busy`).
- Create `kt-app-test:service/{BandSchedulerTest,BandHandoffExecutorTest,MigrationBandsEndToEndTest,WipeStopsBandTest,ChainResetWithBandTest}.kt`.

What it does:
- **Order and cap.** One band at a time, in PD5's order, and none during `SyncWorker`. Preemption follows C9, and a preempted band's files are kept. C9's cap is applied at every poll, so the band pauses while the shared engine catches up.
- **Recovery and liveness.** A band recovers through `RecreateSequence` over its own files, and has its own liveness, apart from the shared watchdogs.
- **The executor** runs C9's steps in order:
  1. Take the wallet's spend latch (PD20: new spend-class actions get BUSY).
  2. Quiesce the band; wait, within a bound, for publishes in flight.
  3. Attach.
  4. Persist the shared ledger at once.
  5. Write the frontier.
  6. Delete the band files.
  7. Release the latch.
  8. Publish again through the shared engine anything still in flight.
- **A cold start** finishes an interrupted hand-off from its records.
- **The quick-start display balance** clears only when the wallet's own frontier proves its outputs.
- **A chain-level reset** while a band runs follows PD18: the band pauses at cap 0 and hands off under WAIT.
- **The wipe** stops the band before its erase.
- Watch-only bands need E2b.

- [ ] **Step 1: Write the failing tests.**
  - **`BandHandoffExecutorTest`:** the order equals the core's (red arm: delete before frontier). A kill injected between every two steps leaves no height in [F, S) unevaluated for W after the restart.
  - **`BandSchedulerTest`:**
    - two waiting wallets never get two bands;
    - a switch to a wallet that needs a band preempts a running band, which later resumes from its files with F unchanged;
    - a non-active wallet never preempts;
    - `SyncWorker` never starts a band.
  - **`MigrationBandsEndToEndTest`,** from E1's layout: B and then C each run a band and hand off while the shared tip advances. Legacy, with no record and an active wallet behind: the shared engine runs empty while the active wallet bands, and legacy's frontier is kept.
  - **BUSY:** a send started during the window is refused with BUSY and its string, and nothing is reserved or signed.
  - **`WipeStopsBandTest`:** no band writer recreates a band or shared file after the wipe's verified erase.
  - **`ChainResetWithBandTest`:** PD18.
  - Peer totals at or below 8, via B7's KAT and V1.
  - The locale gates.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites, KAT sweep.** Expected PASS. **Step 5: Open the PR.**

### Task E4: "Rescan this wallet" and "Repair the chain" replace the Full rebuild

**Rulings:** G9, G43, G66, G76, G84, G96

**Landing:** the integration branch. Not R8.
**Depends on:** the C12 pin, E3; X5.

**Amended by O7 (2026-11-19, G96).** "Rescan this wallet" on legacy is a band for legacy, so it needs legacy's own band files (G96's first open point, decided by G97 (the `_legacy` band names in A1)) and clears only legacy's own keys in `dgb_sync_data<net>` (the second). `RebuildSplitTest` adds: rescanning legacy leaves every shared chain file and chain key.

**Files:**
- `kt-core:WalletManager.kt`: `rebuildFromChainRescan[From]` becomes a band rescan, and a repair entry point is added.
- `kt-core:sync/HistoryRebuildOnUpgrade.kt`: its floor follows PD19.
- `kt-app:ui/settings/ReconcileScreen.kt`: the hardcoded "Full rebuild from chain" heading goes, and so does the restart.
- `kt-app:service/SyncService.kt`: `ACTION_RECREATE_NEAR_TIP`, then `rerequestHeadersFromTip`, then the non-exclusive canon pin unless an own node holds the slot.
- `values*/strings_wallet.xml` ×13; `OnboardingHardcodedStringTest.COVERED` gains `ReconcileScreen`.
- `host:scan_floor_kat` tokens.
- `kt-app-test:ui/settings/RebuildResultGateTest.kt`; `kt-core-test:{WalletManagerChainRebuildParityTest,WalletManagerRescanClearsCfLedgerTest}.kt`; `kt-core-at:WalletManagerRescanTest.kt` (compiles); create `kt-core-test:RebuildSplitTest.kt`.

- [ ] **Step 1: Write the failing tests.**
  - `scan_floor_kat`'s order checks run against the new executors (C12's arms).
  - `RebuildSplitTest`:
    - rescanning B leaves every shared chain file and A's bytes untouched, and does not restart (red today);
    - the from-block form lowers the floor first;
    - a repair keeps the ledger and the window and requests full-locator headers.
  - The parity tests and the locale gates.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites, KAT sweep.** Expected PASS. **Step 5: Open the PR.**

### Task E5: BootGuard and StaleDataWiper on the shared engine

**Rulings:** G2, G9, G41, G77, G88, G96

**Landing:** the integration branch. Not R8.
**Depends on:** E2, the C11 pin.

**Amended by O7 (2026-11-19, G96).** For legacy the per-wallet tier never reaches the files and keys under legacy's chain names; it covers legacy's database, legacy's own keys in `dgb_sync_data<net>` and legacy's band files (G96's first two open points). The test below gains the case "a crash bracketed on legacy's attach leaves the shared chain".

**Files:**
- `kt-app:{BootGuard,StaleDataWiper}.kt`; `kt-app:ui/onboarding/UnlockScreen.kt` (the bracket); `kt-app:service/SyncService.kt`.
- `kt-app-test:{BootGuardRestoreBracketTest,BootGuardExitReasonTest,StaleDataWiperWalletResetTest,StaleDataWiperFilterHeaderTest,RestoreRefusalBracketTest}.kt`.

What changes:
- The restore bracket records which wallet's attach or seed load it covers, and is closed after an in-process seed load.
- **Chain tier:** the shared chain and ledger; C11 and PD18 decide the climb.
- **Per-wallet tier:** that wallet's database (set aside, never deleted) and its band files; then the wallet bands.

- [ ] **Step 1: Write the failing tests.**
  - A crash bracketed on B's attach wipes only B's tier. Red on `develop`: it wipes the active scope's sync blobs, which after E1 would be the shared chain.
  - A bracket left open after an in-process seed load is red.
  - Frozen background starts on Samsung still never count as crashes.
  - The chain tier keeps every wallet's seed record, database and abandonment record.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR.**

### Task E6: `SyncWorker` catch-ups persist through a process-level sink

**Rulings:** G2, G7, G47, G74

**Landing:** the integration branch. Not R8.
**Depends on:** E2.

**Files:**
- `kt-app:service/{SyncWorker,SyncService}.kt`; create `kt-core:sync/EnginePersistence.kt`.
- `bridge:jni_peer.c`, `jni_wallet.c`: the handler set under the guard, null accepted, and the cached save-callback method IDs reset when the handler changes. The JNI signature is unchanged; Kotlin's parameter becomes nullable.
- `kt-app-test:service/SyncWorkerConstructionTest.kt` (the single constructor kept); create `kt-app-test:service/SyncWorkerPersistenceTest.kt`.

Today a worker catch-up after the service has died persists only the active wallet's transaction checkpoint, `sync_start_height` and the notification, because the saves run on the dead service's scope. Per D1, the worker catches up only the wallets attached in its process, attaches none itself and runs no band.

- [ ] **Step 1: Write the failing test.** With the service destroyed, a worker catch-up persists the shared saved blocks, the ledger and every attached wallet's checkpoint, and raises their frontiers. Red on `develop`. The worker never attaches a wallet in a fresh process.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** `SyncService.onDestroy` hands persistence to the sink and re-points or clears the native handler.
- [ ] **Step 4: Run, full suites, KAT sweep.** Expected PASS. **Step 5: Open the PR.**

### Task B8a: `deriveWalletKeys`, the key-record producer (registry PR) [R8 · M1] (added by G78)

**Rulings:** G78, G79

**Landing:** its own registry PR, after the C1 pin and B3. [R8 · M1].
**Depends on:** the C1 pin, B3.

- [ ] `deriveWalletKeys(phrase, passphrase, accounts)` alone, stateless, in `jni_derive.c`; accounts are refused until B9. It also returns SHA-256 of the 64-byte seed it derived from, computed in the same call (G79); its case 'the returned fingerprint is the derived seed's' lives in the suite this PR creates, `host:derive_wallet_keys_kat/`, not in B8's `seed_activate_kat`. The "derived record equals C1's" case and the `seed_buffer_ownership_kat` pattern move here from B8. S1 depends on this task; B8 keeps `openWatchOnly`, `activateWallet` and `deactivateWallet`.

### Task S1: The wallet key record at rest (R4) [R8 · M1]

**Rulings:** G9, G78, G79, G80

**Landing:** the integration branch. **[R8]:** waits for Mark at merge (key material at rest and its Keystore key); batch M1 with C1; after X6. He answered O5 (keep) and O8 (yes, the inert writer may ship early) on 2026-11-19.
**Depends on:** the C1 pin, K1.

**Files:**
- Create `kt-core:wallets/WalletKeyRecordStore.kt`.
- `kt-core:security/KeyStoreManager.kt`: the `KeyVault` seam, with `dgb_wallet_keys_wrap` (PD8).
- `kt-core:WalletManager.kt`: the record is written at `createWallet`, `recoverWallet`, `storeRecordOnly`, `restoreFromDisk` and the switch load, after the seed record lands.
- `kt-core:wallets/{WalletStores,WalletStoreEraser}.kt`, `kt-core:WalletDataEraser.kt`; the wipe's alias read-back (`deleteSeedKeys`, `seedOfEveryWalletGone`).
- Docs: `CLAUDE.md` Seed Security; `THREAT_MODEL.md` and `docs/THREAT_MODEL.md` (their item A3, not task A3: every wallet's addresses, future ones included, are readable without the PIN); `docs/specs/keystore-auth-binding.md` (a new user of an unbound key); the stale BIP49 KDoc at `NativeBridge.kt:885`.
- Tests: `kt-core-test:wallets/{LegacyStoreNameParityTest,WalletDataEraserReachTest,WalletRemovalTest}.kt`, `kt-core-test:{WipeLeavesNoWalletNamesTest,WalletWipeEveryWalletTest}.kt`; create `kt-core-test:wallets/WalletKeyRecordStoreTest.kt`.

**Interfaces:**
```kotlin
sealed interface KeyRecordRead { class Present(val record: ByteArray) : KeyRecordRead; data class NotAttachable(val why: String) : KeyRecordRead }
class WalletKeyRecordStore(context: Context, vault: KeyVault, alias: String = "dgb_wallet_keys_wrap") {
    fun write(scope: WalletScope, record: ByteArray, seedFingerprintV2: String): Boolean  // AES-GCM, AAD = id + version + fingerprint
    fun read(scope: WalletScope, expectedFingerprintV2: String): KeyRecordRead           // absent/unreadable/mismatched: NotAttachable
    fun erase(scope: WalletScope): Boolean
}
```

- [ ] **Step 1: Write the failing tests** (FakeVault).
  - X's record never opens as Y's. Red arm: AAD without the wallet id, the receive-address substitution case.
  - A fingerprint that differs from the seed record's reads NotAttachable.
  - A re-provisioned alias makes every record NotAttachable, and the unlock proceeds.
  - Removal and the wipe reach the record and the alias, with read-back. Red: the alias missing from the read-back.
  - Legacy's 4.1.4 names are unchanged; the new base is an addition.
  - The record holds no address list.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** Existing wallets gain a record at their next unlock as active.
- [ ] **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR** with the M1 summary. Merge on Mark's OK.

### Task B8: Watch-only attach and seed-only activation (registry PR, 8th) [R8 · M3]

**Rulings:** G7, G19, G30, G45, G78, G81, G82, G84, G85

**Landing:** a registry PR into the integration branch. **[R8]:** waits for Mark at merge (the seed-only load, and the use of key material); batch M3; after X6. It takes the regeneration slot only after his OK.
**Depends on:** B4, B7 (queue), the C1 pin.

**Files:**
- `bridge:jni_wallet.c` (with the `jni_seed_buffer.h` and `passphrase_copy` patterns), `bridge:jni_registry_gen.h`.
- NativeBridge and its `native-at:` copy.
- Create `host:seed_activate_kat/`; cases in `host:{seed_owner_kat,saved_tx_array_kat,bip39_nul_kat,seed_buffer_ownership_kat}`.
- `kt-core-test:security/{SeedIsolationTest,NativeMemorySecurityTest}.kt`.

**Interfaces:**
```kotlin
external fun deriveWalletKeys(phrase: ByteArray, passphrase: ByteArray?, accounts: Int): ByteArray?   // C1 record bytes
external fun openWatchOnly(walletId: String, keyRecord: ByteArray, savedTx: ByteArray?, creationTime: Long): Long  // a handle
external fun activateWallet(wallet: Long, phrase: ByteArray, passphrase: ByteArray?): Int   // 0 ok; MISMATCH; FAILED
external fun deactivateWallet(wallet: Long): Int                                            // zeroes the seed if wallet owns it
```

What the natives do:
- `openWatchOnly` builds a watch-only context from its own record and saved transactions only, and attaches it to no manager. Attaching is B6's `attachWalletAt` or a band (B7). It replaces the load-then-restore pending global, which makes `saved_tx_array` rule 2 structural.
- `activateWallet` derives the seed into a bridge buffer and compares `BRWalletMasterKeysFromSeed(seed)` with the context's keys (C1).
  - On a mismatch it zeroes the seed and refuses (PD2).
  - For a context with no keys yet, it builds the wallet from the seed's keys (PD27) without touching the engine.
  - It never calls `BRWalletFree` or `BRWalletOpenWithKeys` on an attached context, and never sets `needsRecreate` or `g_walletSwapped`.
- Inputs are `jbyteArray`s, copied into bridge-owned buffers and zeroed with `secure_zero`; the caller's array is never written through.

- [ ] **Step 1: Write the failing cases** in `seed_activate_kat`.
  - A wrong phrase for a handle is refused, and no seed is left resident. Red arm: `ACTIVATE_INSTALL_BEFORE_COMPARE_UNFIXED`.
  - Activation rebuilds neither the wallet nor the manager; the generation is unchanged. Red arm: `ACTIVATE_VIA_RESTORE_UNFIXED`.
  - A watch-only context signs nothing.
  - The derived record equals C1's for the same phrase.
  - The record round-trips through `openWatchOnly`.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites, KAT sweep.** Expected PASS.
- [ ] **Step 5: Open the PR** with the M3 summary. Merge on Mark's OK; registry standard.

### Task S2: A seed-only load in `WalletManager` [R8 · M3]

**Rulings:** G9, G57, G82, G85

**Landing:** the integration branch. **[R8]:** waits for Mark at merge; batch M3; after X6.
**Depends on:** B8, S1.

**Files:**
- `kt-core:WalletManager.kt`:
  - the seed-only load is split from the wallet build;
  - `isWalletReady` means the active session is attached and its seed is resident;
  - the seed-change verdict clears only the wallet's own stores;
  - `restoreFromDisk`'s `stopSync`, 0-peer wait and 300 ms settle are removed.
- `kt-app:ui/onboarding/{UnlockScreen,PinSetupScreen}.kt`, `kt-app:service/{SyncWorker,SyncService}.kt`, `rememberWatchedAddress`.
- `kt-core-test:{SeedRecordWriteTest,SeedRecordIdentityTest,SeedKeyBindAtCreationTest,security/KeystoreAuthErrorMappingTest}.kt`; create `kt-core-test:UnlockAttachTest.kt`.

One opening gate serves both paths and is not re-implemented: `storedPassphrase` with its Unknown refusal, `identityContradicts`, `migrateSeedKeyIfNeeded` and `migrateLegacyNulPassphrase`.
- `ByteArray` in, zeroed in `finally`.
- `KeystoreUserAuthRequiredException` leads to the device-credential prompt and one retry.
- KeyInvalidated gets its own message, and the wallet stays watch-only.
- At unlock as active, a wallet with no key record writes one; with a record, the keys are compared (PD2).

- [ ] **Step 1: Write the failing tests.**
  - The AND-005 cases run through the seed-only path too. Red arm: the seed-only path skipping the gate.
  - `UnlockAttachTest`: with watch-only contexts attached, `isWalletReady` stays false until the active seed is resident. Red: today's `isWalletLoaded` would flip the UI to Unlocked.
  - Unlock never calls `stopSync`. Red on `develop`.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR** (M3); merge on Mark's OK.

### Task E2b: Attach every READY wallet watch-only after the PIN unlock (D1)

**Rulings:** G9, G56, G66, G70, G75, G77, G83, G84, G85

**Landing:** the integration branch. Not R8, although it uses R8 pieces. D1 is not overridden (O12, answered 2026-11-19).
**Depends on:** E3, B4, B8, S1, S2.

**Files:**
- `kt-app:service/SyncService.kt`; `kt-app:session/WalletSessions.kt` (builds each attached wallet's session).
- `kt-core:WalletManager.kt` (attach from the record).
- `kt-app:ui/navigation/AppNavigation.kt` (the lost-PIN path, PD22).
- Create `kt-app-test:service/{ExclusionReplayTest,AttachOnlyAfterUnlockTest}.kt`.

Once the PIN has unlocked the process:
- Every READY wallet of the network that has a key record is opened watch-only (`openWatchOnly`, B8) and attached by C9's rule from its frontier record (`attachWalletAt`, B6); the others band (E3).
- At attach, its watched-address pins, saved transactions, creation time, asset-outpoint exclusions and birth floor are replayed into its own native wallet.
- Asset maintenance and the prune gate run per wallet, behind K5.
- A wallet with no record waits for its next turn as active.

- [ ] **Step 1: Write the failing tests.**
  - A receive for B lands only in B's database and `AssetManager`.
  - `ExclusionReplayTest`: B's exclusions are in B's native wallet before B could be spent, and a plain or MAX send from B never selects a carrier.
  - `AttachOnlyAfterUnlockTest`: a cold start before the PIN attaches nothing, and neither does the lost-PIN path until its new PIN is set (PD22).
  - Native work per tick stays bounded across 8 wallets, the ANR class.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR.**

### Task S3: The in-process switch [R8 · M3]

**Rulings:** G2, G30, G55, G56, G58, G59, G81, G86, G87, G88, G89, G90

**Landing:** the integration branch. **[R8]:** waits for Mark at merge; batch M3; after X6.
**Depends on:** S2, K7, K8, E2b.

**Files:**
- `kt-app:WalletSwitcher.kt`; the holder's `switchTo`.
- `kt-app:ui/components/SpendAuth.kt` (the gate); `kt-app:ui/onboarding/AddWalletCommitScreen.kt` and the duplicate "Open" button; `kt-app:ui/settings/SeedViewGate.kt`; the BootGuard bracket; the sync notification.
- `values*/strings_wallet.xml` ×13.
- Tests: `kt-app-test:WalletSwitcherTest.kt` (rewritten), `kt-app-test:{AppRestartGateTest,WebSessionIsolationTest}.kt`, `kt-app-test:ui/wallets/WalletsViewModelTest.kt`, `kt-core-test:wallets/ActiveWalletTest.kt` (no longer a process constant); create `kt-app-test:SwitchSeedOrderTest.kt`.

The switch, in order:
1. Refuse while Sending (`wallet_switch_busy_sending`); cancel approvals (K7); end the Hub session (K8); handle the web session per D2: restart only when the latch is set.
2. Present the app PIN or biometric through `SpendAuth`. A wrong PIN goes through `PinManager`'s rate limit and counts toward the wipe-after-N, as at unlock (CRITICAL-1).
3. Zero the outgoing seed (PD1).
4. Inside an opened-then-closed BootGuard bracket, decrypt B's seed record (its mnemonic) through `KeyStoreManager` with B's `_v2` alias: the device-credential prompt and one retry, and KeyInvalidated with its own message. B's key record (public keys, unbound key) was already read when B was opened watch-only.
5. Activate B with the key check (B8). A key mismatch is refused (PD2, `wallet_switch_key_mismatch`). A wallet with no record follows PD27.
6. Check that B's exclusions are in B's native wallet before B can spend.
7. Swap the session: the generation and `registry.setActive` together. Revoke the seed-view pass, update the notification, and start the dwell before deferred server work (K5).

If anything fails: no seed is resident, and B shows watch-only with "Unlock to send". The engine is never rebuilt.

Amended by the rulings (review of 2026-11-19):
- **O16, option (a), until Mark answers it:** while `pinManager.isWipePending()` the switch takes step 1's path, `setActive(B)` and AppRestart, with nothing decrypted in-process. It is a second restart reason beside D2's latch (G86 step 1). `SwitchSeedOrderTest` adds 'a pending wipe decrypts nothing in-process: the switch restarts'.
- **A no-record B whose activation fails** (G86, G81): a wallet with no key record cannot be shown watch-only. When the decrypt or the activation fails for it, the session is not swapped: A stays active and shown, with no seed resident. `SwitchSeedOrderTest` adds 'a declined prompt on a no-record wallet leaves A active and shown, with no seed resident'.

- [ ] **Step 1: Write the failing tests.**
  - **`SwitchSeedOrderTest`:**
    - the outgoing seed is zeroed before any incoming bytes exist (recorded native calls; red arm: decrypt first);
    - a declined prompt leaves no seed and B watch-only;
    - exactly one seed or none after every path.
  - **Keystore:** `KeystoreUserAuthRequiredException` leads to a prompt and a retry; KeyInvalidated shows its message, with no crash.
  - **PIN:** a wrong PIN at the switch prompt increments the failure counter, and N in a row run the wipe (emulator test harness).
  - **Refusals and timing:** a switch is refused while Sending; a late result from A never shows after the switch.
  - **No rebuild:** no recreate and no `stopSync` on a switch; the generation is unchanged.
  - **Latch:** set, the switch restarts; clear, it does not.
  - The locale gates.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR** (M3); merge on Mark's OK.

### Task E7: Live add, and live removal of a wallet that is not active

**Rulings:** G2, G31, G55, G56, G64, G65, G83, G91, G92, G95, G96

**Landing:** the integration branch. Not R8: the eraser and what it erases are unchanged. Mark sees it in the digest.
**Depends on:** E3, S1, S3. X-HUB is ruled (G95) and E7 does not wait for it; both edit `WalletRemoval.kt`, so they are never open at the same time.

**Amended by O7 (2026-11-19, G96).** "The eraser and what it erases are unchanged" holds for this task: the change for legacy (its removal keeps the shared chain's files and keys, which are under legacy's names) is E1's. E7's removal of legacy runs that eraser.

**Files:**
- `kt-core:wallets/{WalletRemoval,WalletProvisioner,WalletHousekeeping}.kt`.
- `kt-app:ui/onboarding/{AddWalletCommitScreen,OnboardingViewModel}.kt`, `kt-app:ui/wallets/WalletsViewModel.kt`, `kt-app:service/SyncService.kt`.
- `kt-core-test:wallets/{WalletRemovalTest,WalletHousekeepingTest}.kt`, `kt-app-test:ui/wallets/WalletsViewModelTest.kt`, `kt-app-test:ui/onboarding/OnboardingCommitAddTest.kt`.

**Add:**
- The new wallet's key record is written.
- A created wallet attaches at the tip.
- A restore with no other wallet on its network starts the shared engine at its birth; any other restore runs as a band from its birth.
- Then the in-process switch.

**Removal of a wallet that is not active,** in order:
1. stop routing its calls;
2. detach it, or stop its band;
3. stand down its writers;
4. close its database;
5. erase and verify.

The shared chain is never touched. Removing the active wallet keeps F8 (PD3), and since X-HUB it revokes that wallet's Hub session before the restart (G95). Removing a wallet that is not active revokes as in step 1.

- [ ] **Step 1: Write the failing tests.**
  - Removing an attached wallet that is not active detaches it with no restart, and nothing writes its files after a verified erase. Red on `develop`: the erase races the live writers.
  - Every byte of the other wallets and the shared chain survives, legacy's removal included.
  - An add attaches live and switches without `AppRestart`. Red today.
  - A restore's band starts at its birth.
  - Add mode never clears the PIN.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR.**

### Task U1: MAX on the DGB Send screen

**Landing:** the integration branch: step 2 is one release (R2) and not in 4.1.5 (R7). Not R8.
**Depends on:** B5, K4, K7.

**Files:**
- `kt-app:ui/wallet/{SendViewModel,SendScreen}.kt`; `values*/strings_wallet.xml` ×13 (`send_max` exists).
- `kt-app-test:ui/wallet/{FeeShownIsFeeSignedGateTest,SendAmountBindingWiringTest,SpendPreflightAppWiringTest}.kt`, widened to MAX; create `kt-app-test:ui/wallet/MaxModeBindingTest.kt`.

MAX mode:
- MAX sets it. Any amount edit, prefill, QR amount or DigiDollar address clears it.
- `requestConfirm` branches to `prepareSendAll`, and `send()` keeps its identity, amount and fee checks.
- The DGB branch of the screen gets the MAX button, the "available to send" line, the left-behind notes, and the refusals (PD7).

- [ ] **Step 1: Write the failing tests.** `MaxModeBindingTest`: a stale typed amount is never approved in MAX mode, and a stale MAX amount is never approved after an edit. `FeeShownIsFeeSignedGateTest` fails until the MAX path goes through `prepareSendAll` and the signed-fee check.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites,** the locale gates. Expected PASS. **Step 5: Open the PR.** V1 holds the device check.

### Task U2: Catching up, never a re-sync

**Landing:** the integration branch. Not R8.
**Depends on:** the C14 pin, E3, K7.

**Files:**
- `kt-core:model/{SyncProgress,SyncFrontierSession}.kt`: the C14 mirror, per wallet.
- `kt-app:ui/wallet/{WalletScreen,WalletViewModel,SendScreen}.kt`, the header `SyncIndicator`, `kt-app:ui/settings/NetworkInfoScreen.kt`, and the notification text in `SyncService`.
- `values*/strings_wallet.xml` ×13; additions to `COVERED`.
- `kt-core-test:model/SyncFrontierTest.kt`, `kt-app-test:service/SyncNotificationTextTest.kt`; create `kt-app-test:ui/wallet/CatchUpWordingTest.kt`.

- [ ] **Step 1: Write the failing tests.** `CatchUpWordingTest`: a resumed wallet 717 blocks behind, a banded wallet, a waiting wallet and the shared engine after a long freeze each show "Catching up · N blocks", with no percentage and no first-sync wording. Red today: Syncing with a percentage.
  - Covered places: the card headline, the progress line, "Found N", the recovery-window banner, the header indicator, the Send wait banner, the notification, Network Info.
  - Plus `SyncNotificationTextTest` cases and the locale gates.
- [ ] **Step 2: Run.** Expected FAIL. **Step 3: Implement.** **Step 4: Run, full suites.** Expected PASS. **Step 5: Open the PR.**

### Task B9: Coinomi account keys in the watch set (registry PR, 9th) [R8 · M4]

**Rulings:** G7, G19, G44, G66

**Landing:** a registry PR into the integration branch. **[R8]:** it derives key material for storage; batch M4 with U3; after X6.
**Depends on:** the C15 pin, B8, X2.

**Files:**
- `bridge:jni_wallet.c`: the record's accounts block is passed to `BRWalletOpenWithAccounts`; `deriveAccountKeys(handle, accounts)` serves the seed owner only.
- `bridge:jni_registry_gen.h`; NativeBridge and its `native-at:` copy.
- A host case over C15.

- [ ] **Step 1: Write the failing cases.**
  - An attached watch-only wallet with account keys credits a BIP49 receive.
  - `deriveAccountKeys` is refused for a context that does not own the seed.
  - Red arm: the record parsed without its accounts block.
- [ ] **Steps 2-4:** run (FAIL), implement, run (PASS), KAT sweep. **Step 5: Open the PR** (M4); merge on Mark's OK; registry standard.

### Task U3: Coinomi step 4: watch `watchedAccounts`, and "Also watch Coinomi's accounts" [R8 · M4]

**Rulings:** G9, G10, G66

**Landing:** the integration branch. **[R8]:** the account keys at rest (R4 names them); batch M4; after X6.
**Depends on:** B9, S1, E3, X2, X3.

**Files:**
- The `watchedAccounts` store, written by Coinomi steps 2-3.
- S1's record (its accounts block).
- `kt-core:WalletManager.kt`: account keys are derived while the seed is resident, at:
  - a "Coinomi" restore;
  - the Settings action for the active wallet;
  - the next activation of a wallet flagged before step 4.
- The Digi-ID settings screen: the action, also offered when Coinomi-compatible sign-in is turned on.
- Per-wallet "native" flags, leaving `BRDerivationProfiles.h` unchanged: `RecoveryScanService.nonNativeWithFunds`, `QuickStartScans`, `RecoverFundsViewModel`, `ForeignSweep`, `OnboardingViewModel.pendingLegacyRecovery`.
- `values*/strings_wallet.xml` ×13.
- Create `kt-core-test:wallets/{CoinomiWatchTest,AlsoWatchCoinomiTest}.kt` and `kt-core-test:recovery/RecoverFundsNativeOverlayTest.kt`. Add an upgrade test from a steps 1-3 build with `watchedAccounts` set.

Watching follows `watchedAccounts` only. It is add-only, the sign-in toggle never removes from it, and nothing goes to a server. Adding accounts to an attached wallet detaches it into a band from its birth (E3).

- [ ] **Step 1: Write the failing tests.**
  - `CoinomiWatchTest`: a wallet watching Coinomi's two accounts credits a BIP44 and a BIP49 receive by compact filters, and spends them.
  - Turning Coinomi sign-in off removes nothing. Red arm: the toggle clears watching.
  - A wallet flagged before step 4 gains its account keys at its next activation, and bands from its birth.
  - `AlsoWatchCoinomiTest`: add-only, and a band from birth.
  - `RecoverFundsNativeOverlayTest`: Recover funds does not offer the watched accounts as foreign for that wallet.
  - The locale gates.
- [ ] **Steps 2-4:** run (FAIL), implement, run (PASS), full suites. **Step 5: Open the PR** (M4); merge on Mark's OK.

### Task U4: The switcher's live wallet states, the notification, and abandonment for wallets not active

**Rulings:** G71

**Landing:** the integration branch. Not R8.
**Depends on:** K6, U2, S3.

**Files:**
- `kt-app:ui/wallets/{WalletsViewModel,WalletSwitcherSheet,WalletChip}.kt`; `kt-app:service/SyncService.kt` (the notification title observes the holder and the registry).
- `values*/strings_wallet.xml` ×13.
- `kt-app-test:ui/wallets/WalletsViewModelTest.kt`, `kt-app-test:service/SyncNotificationTextTest.kt`.

Each row reads its own wallet's handle, and shows its balance and one of: synced; catching up · N blocks; waiting to catch up; "syncs after you open it once" (no key record). Its DigiDollar figure may read low until the wallet is active (R6). The notification's public version shows only the app name.

- [ ] **Step 1: Write the failing tests.**
  - Each row reads its own handle. Red: the active handle for every row.
  - A banded wallet reads "catching up" with its count and never a percentage.
  - A wallet without a record reads the waiting text.
  - A rename or a switch updates the notification title. Red: read once per start.
  - An abandonment written to a wallet's record while it was not active shows on its row, and as the usual banner at its next activation.
  - The locale gates.
- [ ] **Steps 2-4:** run (FAIL), implement, run (PASS), full suites. **Step 5: Open the PR.**

### Task U5: Name the spending wallet in every spend-auth prompt (unconditional, G93)

**Rulings:** G93

**Landing:** the integration branch. Not R8.
**Depends on:** S3.

Edited by G93: this task no longer waits for Mark's answer to O6. `SpendAuth`'s prompt carries `spend_auth_from_wallet` ("From %1$s") at every spend-class call site (PD23), in all 13 locales. Not every `SpendAuth` call site is one (G93, as amended by the review of 2026-11-19): the label is absent from the removal prompt in Manage wallets, from node pairing and from the switch prompt, and the test asserts that too. Whether the Send, asset send, DigiDollar send, Digi-ID, Recover funds and Hub sign-in screens name the wallet too is O6, and that part is U5b. An instant switch makes sending from the wrong wallet easier.

- [ ] Build the prompt naming, with a test that every spend-class prompt names the active wallet, and the locale gates.

### Task U5b: Name the spending wallet on every spend screen (runs only on Mark's answer, O6; added by G93)

**Rulings:** G93

**Landing:** an integration-branch PR, only if Mark answers O6 with yes. Not R8.
**Depends on:** U5.

- [ ] The per-screen naming: the Send, asset send, DigiDollar send, Digi-ID, Recover funds and Hub sign-in screens name the wallet, with the locale gates. U5 itself is unconditional: the prompt naming on every `SpendAuth` call site, in 13 locales. V1 and the stage 4 exit check depend on U5, and on U5b only if Mark answered yes before V1 starts.

## Stage 5: verification and merge

### Task V1: Device and emulator verification

**Rulings:** G25, G93, G94, G96

**Landing:** the results and a pre-publish PR into the integration branch.
**Depends on:** every Stage 4 task.

Rules for this task:
- Never use the Galaxy S25 Ultra.
- Phrases come only from `~/device-backups/seeds/`. Read them on the host when typing a restore, and never copy one into this repo, a commit, a log excerpt or a report.
- `adb input tap` is ignored by the Compose UI, so use `adb shell input swipe X Y X Y 120`.

**Files:** create `test-results/multi-wallet-step2/<date>-<device>.md`. It records match/mismatch and counts only; figures go to `~/dgb-wallet-private/` (F32). Each check's expected result is written before the build runs, and a failure goes back to the task that owns it.

- [ ] **Step 1: Upgrade from 4.1.4** with one funded legacy wallet (emulator, then the Note 8). No file is renamed, legacy attaches at once, there is no re-sync from birth, and the balance and history are unchanged.
- [ ] **Step 2: Upgrade from the 4.1.5 step-1 layout** with one wallet days behind, one weeks behind and one with no record:
  - the first attaches or bands;
  - the second bands and hands off while the tip advances, with no missed receive;
  - the third waits, then attaches after one visit;
  - device peers stay at or below 8 (log counts).
- [ ] **Step 3: Separation.**
  - An A-to-B send credits B and never A.
  - MAX from A to B leaves A at 0 with no change.
  - An in-process switch shows no other wallet's list, balance or phrase, and no "Scanning %".
  - The Hub session is per wallet (the server's per-IP limit, a 403 on a second account, is noted, not a failure).
  - DigiStamp after a switch is signed out, or the switch restarted on the latch.
- [ ] **Step 4: Recovery and actions.**
  - A recreate near the tip after a Doze/One UI freeze (wedge class c).
  - "Rescan this wallet" and "Repair the chain".
  - Removing a wallet that is not active, and removing legacy, leave the other wallets and the shared chain.
  - A BootGuard false crash on the Note 8 (Samsung) does not re-sync.
- [ ] **Step 5: Keys and prompts.**
  - Switch prompts on API 28: does a BIOMETRIC_STRONG success without a CryptoObject refresh the validity window? On API 35 (emulator) too.
  - On an emulator only, never on the mule's real wallets: a wrong PIN at the switch prompt counts toward the wipe.
  - Coinomi's accounts on the public test phrase.
- [ ] **Step 6: Peers.** With Tor on and an own node pinned, a band dials no other peer.
- [ ] **Step 7: Capacity.** With 8 wallets: memory (`dumpsys meminfo`), `[CF-SLOW]` lock holds, and the union's filter false-positive rate (union matches per 1,000 blocks; the estimate is 1-1.5%).
- [ ] **Step 8: Rollback (X-ROLLBACK b).** On an emulator, install a rollback build (4.1.5 code with a bumped `versionCode`) over step 2. Record the outcome against the fixture. Since G96 the record covers legacy by name: whether it shows every receive, and that 4.1.5's "Full rebuild from chain" restores any it missed.
- [ ] **Step 9: Pre-publish suite.** `./scripts/pre-publish-test.sh` on API 28, 33, 34 and 35. Each must pass.
- [ ] **Step 10: Write the results file** and commit it with the explicit path, after the full suites pass.

### Task V2: Close-out: timings, hand-off, roadmap, CLAUDE.md, the native-speaker list

**Rulings:** G5, G76

**Landing:** docs PRs: one into the integration branch, and a core PR if the core's own docs need a line. Not R8.
**Depends on:** V1.

- [ ] **Step 1:** Time every new suite in both kat-shards copies after its first green run (R1).
- [ ] **Step 2:** Finish the hand-off: the final API list and KATs, the key-record decision, the last pre-step-2 core commit, the Mac-arm results, and the iOS parity items.
- [ ] **Step 3:** Mark the `ROADMAP.md` item done, naming V1's checks. Add a pointer in `CLAUDE.md`'s Key Architecture Decisions: the shared engine, bands, the frontier records, the key record and the seed owner, linking the design without restating it.
- [ ] **Step 4:** Hand Mark the native-speaker review list for the new warnings (switch, key mismatch, rescan, repair, MAX refusals) as a release gate.

### Task V3: Merge the integration branch into `develop` (waits for Mark's go)

**Rulings:** G94

**Landing:** one PR from `integration/multi-wallet-step2` into `develop`, merged with a merge commit. It waits for Mark's go: it makes step 2 the next release's content (R2, R7). The version bump and the release stay his.
**Depends on:** V1, V2, X5.

- [ ] **Step 1:** Merge `develop` into the branch and regenerate the registry.
- [ ] **Step 2:** On the merged head, all green: the full suites, every KAT shard, `gen-jni-registry.py --check`, `check-submodule-pin.sh` and `check-doc-version.sh`.
- [ ] **Step 3:** An independent review of the whole diff, posted on the PR.
- [ ] **Step 4:** Merge on Mark's go.

---

## Self-review notes (resolved inline)

**Coverage against the amended Step 2:**

| Design element | Tasks |
|---|---|
| Core wallet set | C3-C8 |
| "For each wallet" semantics | C4, C5, C7 |
| Publishing through the builder | C6 |
| Single-wallet constructors stay | C3, C8, G5 |
| Additive only | G6 |
| Union cache and gap rescan | G1, C5, C7 |
| One forward ledger | C3's R3 clause |
| Bands, the hand-off rule, quiesce, attach under the lock, rewind, durability, exclusive hold | C8, C9, C10, B7, E3 |
| Peers | A4, C9, B7 |
| Abandonment per wallet | E2, U4 |
| Recovery | C11, E2, E5 |
| Bridge contexts and guards | B1, B7 |
| The seed owner | B4 |
| Callbacks | B1, G3, B6 |
| Handles | B2, B3 |
| Keys in memory and at rest | C1, S1, B8, S2 |
| The seed-only load | B8, S2 |
| The switch | S3 |
| Session holder | K3 |
| `SyncService` | E2, E2b, E3 |
| Exclusions replay | E2b |
| ViewModels | K7 |
| D2 | K8 |
| `SyncWorker` | E6 |
| BootGuard | E5 |
| R6 | K5 |
| Removal | E7, E1 |
| Rebuild and rescan (D4) | C12, B6, E4 |
| MAX | C13, B5, U1 |
| Catching up | C14, U2, U4 |
| Coinomi step 4 | C15, B9, U3 |
| Migration | E1, E3 |
| Test plan | the Acceptance index |

**Types and APIs.** Every interface a task consumes is produced earlier:

| Interface | Produced by |
|---|---|
| `BRWalletKeyRecord` | C1 |
| `BRDigiDollarReceive*` | C2 |
| The slot accessors | C3 |
| `BRPeerManagerWallet*` | C6 |
| The holding constructor, attach and detach | C8 |
| `BRBandHandoff.h` | C9 |
| `BRPeerManagerHandOffWallet` | C10 |
| The scope functions | C11 |
| The rescan and repair enums | C12 |
| `BRWalletCreateSendAllTransaction` | C13 |
| `BRSyncCatchUpFor` | C14 |
| The account APIs | C15 |
| `walletHandle` | B2 |
| `createSendAllTransaction` and `prepareSendAll` | B5 |
| `attachWallet`, `attachWalletAt`, `detachWallet` and the readiness natives | B6 |
| The band natives | B7 |
| `deriveWalletKeys`, `openWatchOnly`, `activateWallet` | B8 |
| `deriveAccountKeys` | B9 |
| `WalletSessions` | K3 |
| `ServerWorkGate` | K5 |
| `WebViewProcessLatch` | K8 |
| `ChainStores`, `Frontier`, `ChainMigration` | E1 |
| `EnginePersistence` | E6 |
| `WalletKeyRecordStore` | S1 |

**Order.**
- Every Stage 1 and Stage 3 PR leaves `develop` releasable, with behaviour unchanged.
- No step-2 behaviour reaches `develop` before V3.
- No core PR merges before X1 and X5.
- No [R8] PR merges before X6 and Mark's OK.
- G2 lands before C3; K4 and K5 before E2b; E1's migration is not invoked before E2; device testing starts after E3.

**Judges' grafts carried in:**
- CI on the integration branch (A3);
- the API freeze (G6);
- the acceptance index;
- the wipe stopping both contexts (B6, B7, E3);
- the empty set (C8, E2);
- parked managers releasing their wallets (C8, PD15);
- own-node measurement (A4);
- MULTI_PROFILE from a throwaway build (A5);
- the #264 review gate (X6);
- R8 registry PRs taking the slot only after Mark's OK;
- the registry forward-compat test and the hard link (E1, X-ROLLBACK); the hard link was withdrawn by G96;
- the R6 dwell and the comment corrections (K5);
- the C9 and C10 edge cases;
- the "opens once" state (U4) and O8;
- the D1-override note (E2b);
- the `PUBLIC_HEADERS` rule (hand-off);
- the BD1 alternative (O7), which Mark chose on 2026-11-19 (G96);
- the 4.1.5 branch names (X5);
- R3 clauses in the source gate (C3);
- `AssetSweepHandleTest` (K6);
- the pre-flight scan (A2);
- one pin per core PR (Core landing);
- the `derivePrivateKeyWIF` test updates (B3);
- the band generation counter (B7);
- switching to a wallet with no record (PD27, S3);
- `rescan_removal_kat` (G5, B6).

**Gaps every candidate plan missed, now closed:**
- frontier records for wallets that are not attached (PD14, E2);
- spending during a hand-off (PD20, E3);
- peer-penalty and saved-peer ownership with two managers (PD17, E1, B7);
- the release-pull test (X-ROLLBACK);
- stage dates and exit checks;
- the `pongLock` rule (C6, C8);
- Mac-only arms (hand-off item 4);
- band preemption (PD5, C9, E3);
- the lost-PIN path (PD22, E2b);
- naming the spending wallet (U5, O6);
- abandonment for wallets not active (U4);
- capacity at 8 wallets (C7);
- header-check levels for new suites (Global Constraints);
- the switch prompt's PIN rate limit (S3);
- a chain-level reset during a band (PD18, C11, E3);
- the pregeneration ruling (PD16);
- the upgrade-floor queue (PD19, C11).

## Ambiguities between the spec and the code (decided here)

**Rulings:** G16, G70, G96

1. **"Attached only once the band manager is gone (Free returned 1, or its deferred free has run)."** The reason is that a parked manager's threads can still reach its wallet. PD15 removes that reason for holding managers, which detach every wallet under the lock inside `Free`, and keeps the old behaviour for every other manager. That is equivalent and does not stall a recreate.
2. **"Pre-generate its address window" inside the one critical section** would breach the lock budget (about 645 addresses per wallet against the 2000 ms `[CF-SLOW]` threshold). PD16 generates outside and re-runs inside, as a no-op unless the set grew.
3. **"Write the wallet's attach height F to the registry"** covers only the band. A wallet that was attached and is not attachable this session (unreadable record, KeyInvalidated, a failed attach, a process the PIN has not unlocked) needs the same record, or it rejoins at the shared frontier and misses receives. PD14 generalises the attach height to a frontier record, raised only while the wallet is attached.
4. **"On a cold start, rewind to any recorded F whose band files still exist"** becomes: rewind to the lowest frontier of the wallets being attached, when it is at or above H_min (C9), and finish any hand-off whose band files remain.
5. **"Upgrading never touches the existing wallet's data" against "then delete the wallet's chain files".** The amendment states the deletion explicitly. This plan reads the anti-goal as: names are never renamed, chain data is linked or copied and read back before anything is deleted, and deletion waits until the hand-off is durable (PD13). The alternative layout is O7. **Amended by O7 (2026-11-19, G96):** Mark chose that layout. Legacy's chain files are never linked, copied or deleted: they are the shared chain. The deletion the amendment states applies to every other wallet's chain files, after its hand-off is durable.
6. **The shared chain's file names** were left to the builder: A1's list. **Amended by O7 (2026-11-19, G96):** legacy's 4.1.4 names. The design's rule that a band never shares a file with the shared engine (spec:708-710) then means legacy's own band needs other names, which G96 leaves to be designed.
7. **"SyncWorker catches up the wallets attached in its process."** In a fresh process none are attached (D1), so it does nothing, as today (E6).
8. **`BRPeerManagerApplyServerHeight` reads the wallet before the lock.** C3 lists every such read, and C8 fixes each one or proves it unreachable.
9. **The design's test plan omits element-cache invalidation.** G1 and C7 add it.
10. **"Notifications for incoming payments name the wallet":** no such notification exists. Step 1's decision stands, and U4 keeps the sync notification right across switches.
11. **`CLAUDE.md`'s `SetFixedPeer` claims** are corrected by the first PR that changes the rescan (B6, amendment item 9). The core's own comments are fixed in C12.
12. **iOS rulings Q3 A and Q19 A** do not bind Android. D1 matches Q19 A, and S1's model matches Q3 A's. Both are cited as precedent only.
13. **`watchedAccounts`** exists only on open PR #267. C15, B9 and U3 wait for it (X2).
14. **The design's "the switcher's DigiDollar figure can read low"** is a stated R6 cost. U4 shows the last-known figure and does not hide it.
15. **The Hub's per-IP account limit** (a second wallet's sign-in gets a 403) is server policy, outside this plan. V1 notes it.
16. **Seven bridge files, not about 20; 69 `manager->wallet` lines, not about 25 functions** (amendment items 4 and 2). The tasks use the corrected figures.

## Open for Mark

- **O1** Key mismatch at activation: refuse (PD2) or replace the record.
  - **Answered (Mark, 2026-11-19):** refuse (PD2 stands). A record that differs from the seed is refused, unused and unreplaced.
- **O2** The Hub revokes on removal and on the wipe (X-HUB).
  - **Answered (Mark, 2026-11-19):** close the active-wallet gap only. Removing the active wallet revokes its Hub session on the server, so a removed wallet's session does not stay live. Removing a wallet that is not active revokes as today, and the wipe is unchanged: it still revokes every wallet's session in one pass (G95; X-HUB is built, and is not [R8]).
- **O3** The integration branch (PD11), or a stack of PRs into `develop` merged in one sitting.
  - **Answered (Mark, 2026-11-19):** the integration branch (PD11).
- **O4** Whether step-2 core PRs may merge before the 4.1.5 cut. Default: no.
  - **Answered (Mark, 2026-11-19):** no. No step-2 core PR and no neutral slice merges before the 4.1.5 cut or while #264's review is open (G11's default).
- **O5** Device-lock removal: keep the watch-only records (PD12) or drop them.
  - **Answered (Mark, 2026-11-19):** keep the watch-only records (PD12).
- **O6** Naming the spending wallet on every spend screen (U5).
  - **Answered (Mark, 2026-11-19):** yes. Every spend screen names the spending wallet (U5b is built).
- **O7** The shared chain under legacy's 4.1.4 names instead of new ones. That would make a 4.1.4 upgrade move no file. The costs:
  - seeding from legacy even when another wallet is ahead;
  - a rollback in which legacy reads a ledger evaluated while it was not attached, so missed receives (X-ROLLBACK);
  - removal and the eraser special-casing legacy's names.
  - **Answered (Mark, 2026-11-19):** yes, legacy's 4.1.4 names. The shared chain gets no new name and a 4.1.4 upgrade moves no file. He was shown the three costs above and accepted them; of the second, in his words, "the sync progress would be lost and they would need a rescan. That's fine". His words name lost sync progress and a rescan; the cost as listed is that legacy alone can show a balance that misses receives, with nothing on the device saying a rescan is needed (X-ROLLBACK step 3). This reverses PD13 and withdraws A1's chain names (G96). Four points are left to be designed and are not decided in this plan (G96; task E1): legacy's own band files, the split of `dgb_sync_data<net>`, what the chain starts from when legacy's names hold none that passes, and one store target per file.
- **O8** Whether S1's inert record writer may ship in a release before step 2, so more wallets hold a record when the engine ships.
  - **Answered (Mark, 2026-11-19):** yes. S1's inert record writer may ship in a release before step 2. It is still [R8]: its PR waits for him.
- **O9** The lost-PIN path. iOS's D-3 asks for the phrase first; Android does not. This predates step 2 and is not built here (PD22).
  - **Answered (Mark, 2026-11-19):** "Switch to the iOS flow" (the phrase first). As recommended with the question, not said by him: built outside step 2, after the 4.1.5 cut. He answered Q1-Q6 of the design the same day, `docs/superpowers/specs/2026-11-19-lost-pin-phrase-first-design.md`.
- **O10** (before the 4.1.5 cut) Should 4.1.5's wipe erase the reserved step-2 names (X-ROLLBACK c)?
  - **Answered (Mark, 2026-11-19):** yes. A wipe clears every file, so 4.1.5's wipe erases the reserved step-2 names (X-ROLLBACK c). It rides the 4.1.5 train as a row of the private scope file.
  - **Narrowed by O7 (2026-11-19, G96):** his yes stands, over a shorter list. Still reserved, and still to be erased by 4.1.5's wipe: the key-record prefs `dgb_wallet_keys` and `dgb_wallet_keys_<id>`, and the Keystore alias `dgb_wallet_keys_wrap`. No longer reserved: the `chain_*` files and `dgb_chain<net>`, which no step-2 build writes. The shared chain needs nothing from 4.1.5: its wipe already erases legacy's names on both networks. The private 4.1.5 scope row is to be narrowed to match. One thing can lengthen the list again: names for legacy's own band files, if that design (G96) needs new ones and fixes them before the cut.
- **O11** The stage target dates.
  - **Answered (Mark, 2026-11-19):** as soon as possible. No calendar date is set; each stage starts when its gate opens, and `ROADMAP.md` carries no step-2 date (G14).
- **O12** If D1 is overridden, attaching before the PIN unlock becomes [R8] (E2b).
  - **Answered (Mark, 2026-11-19):** not applicable. D1 is not overridden.
- **O13** The Mac-only arms run at the iOS-and-core session's pace (hand-off item 4). Confirm that this blocks only its own pin moves.
  - **Answered (Mark, 2026-11-19):** confirmed. The Mac-only arms block only their own pin moves.

Added by the pre-flight scan (2026-11-19):

- **O14** (conflict 117; Stages, V3) From T to V3 (about eight weeks) develop holds step 2's behaviour-neutral slices: the rewritten engine (C3-C10, through the pin), the bridge context table and handles (B1-B5) and K0-K8. A release cut from develop in that window ships them on KATs, unit tests and the standard release gates, because V1's device checks run only at the end, on the integration branch. May such a release carry them, and on what checks?
  - Options: (a) Yes, with extra gates on that release commit: V1 step 1 (upgrade), V1 step 4's recreate after a freeze on the Note 8, and the pre-publish suite. (b) No: an interim release is built from a line that stops at T, with fixes merged there and forward to develop. (c) No step-2 slice on develop before V3: everything goes to the integration branch (O3's variant).
  - Recommended: (a). Nothing a user can see changes before V3, so R2 holds, and the neutral slices get device time early. Whatever he answers, the plan records that step-2 merges pause during each train's landing-sync freeze (RELEASING.md:94-96) and that the stage dates move by the freeze's length; 4.1.3 and 4.1.4 shipped twelve days apart.
  - **Answered (Mark, 2026-11-19): yes.** A release cut from `develop` in that window may carry the behaviour-neutral slices, on the checks this question names: the host KATs, the unit suites and the standard release gates (`RELEASING.md`). He named no extra check; V1's device checks stay where they are.
- **O15** (conflict 37; B4, in the M2 packet) A sweep or foreign transfer signed from another wallet's phrase is registered only in the publishing wallet, and its coin claim ends at publish (jni_transaction.c:540-541), so the swept wallet can sign the same coins again until a peer relays the sweep back: two conflicting sends, both shown as sent. The planned check (every attached wallet's spent set, plan:1382, moved to B4 by G52) covers only the other order, and only attached wallets. When Recover funds is given a phrase that is itself a wallet on this device, should the signer refuse it, or allow it and track the spend across wallets?
  - Options: (A) Refuse: the recovery signers refuse a phrase that is a wallet on this device (an identity match in the app; natively, any input that an open context holds, credited or spent, whatever its holder), with one new message in 13 locales telling the user to switch to that wallet and send. (B) Allow: at publish, also record the spend in every open context that owns an input, and keep the coin claim until then; a wallet not open in this process stays uncovered. (C) Keep the planned check and record the remaining double-sign window as accepted.
  - Recommended: (A). It is the only option that also covers a wallet held by the band or not open, and the design already names the way to move such funds: a normal send from that wallet (spec:305-306), which MAX makes one step. B4 is written with (A) and cannot merge before his M2 answer; the question goes in the M2 packet. recovery_spend_gate_kat gets both orders, red on the planned check alone, and the check runs over every context in the table.
  - **Answered (Mark, 2026-11-19): refuse (option A).** The signer refuses a phrase that is itself a wallet on this device, with one message in every supported language telling the user to switch to that wallet and send. B4 is written with it.
- **O16** (conflict 87; S3, in the M3 packet) While a PIN-failure wipe is owed and could not complete (pin_wipe_pending set, the unlock screen stood down, the wallet opened with its PIN), what does a wallet switch do? In step 1 every switch restarted, so the launch backstop (MainActivity.kt:151-157) and the unlock screen's hold (UnlockScreen.kt:144, :207) retried the wipe before another wallet opened. The in-process switch (S3) passes neither.
  - Options: (a) While a wipe is owed the switch takes step 1's path: setActive(B) and AppRestart, nothing decrypted in-process. This is a second restart reason beside D2's latch. (b) Refuse the switch until the wipe is no longer owed: on a device whose wipe keeps failing, the other wallets cannot be opened. (c) Proceed in-process behind the switch PIN, as the plan is written: the wipe is retried only at launches and unlock-screen entries.
  - Recommended: (a). It keeps step 1's behaviour exactly on CRITICAL-1's path and never strands a wallet. S3 is written with (a) and cannot merge before his M3 answer; the question goes in the M3 packet, and SwitchSeedOrderTest adds 'a pending wipe decrypts nothing in-process: the switch restarts'.
  - **Answered (Mark, 2026-11-19): restart the app (option a).** While a wipe is owed, a switch takes step 1's path: `setActive(B)` and `AppRestart`, nothing decrypted in-process, so the launch backstop and the unlock hold retry the wipe first. S3 is written with it.

Added by the independent review of the rulings (2026-11-19):

- **O17** (conflict 119; G1, B6, E3; in A1's digest) G1 decides [R8] for a PR that touches the wipe by one test: it changes what the wipe or a removal erases of seed, key or PIN material, or the wipe's native steps that `WipeNativeStepsGateTest` pins. Conflict 119 also proposed a third trigger, an edit to a seed or wipe gate's allow-list, and G1 left it out. B6 needs such an edit (the detach native) and makes the wipe's quiesce stop every sync context, and is "Not R8" on that reading of his rule (`docs/development-process.md:54-56`). Does G1's two-part test stand, or is an allow-list edit to a seed or wipe gate [R8] too?
  - Options: (a) The third trigger applies: an allow-list edit to a seed or wipe gate is [R8]; B6's entry lands as its own small [R8] PR ahead of B6, so B6 itself does not wait. (b) G1's test stands as written: B6 and E3 are not [R8] while the pinned quiesce order is unedited. (c) B6 as a whole is [R8].
  - Recommended: (a). It costs one small PR and keeps every loosening of a seed or wipe gate in front of him. Until he answers, (a) is what the plan does.
  - **Answered (Mark, 2026-11-19): option (a).** An edit to a seed or wipe gate's allow-list is itself [R8]; B6's `WipeNativeStepsGateTest` entry lands as its own small [R8] PR ahead of B6.

Open after 2026-11-19: **none in step 2.** O9 was answered in direction by Mark that day (the phrase first); its multi-wallet rule and five more questions, all answered by him that day, are Q1-Q6 of `docs/superpowers/specs/2026-11-19-lost-pin-phrase-first-design.md`. O2 and O7 were answered by Mark that day and are recorded as G95 and G96.

Answered by a ruling: none. No ruling settles an existing question, because each is Mark's. Four are changed in scope:

- **O1** gains the unlock case (a record that differs from the seed at a cold-start unlock is marked refused, unused and unreplaced, until he rules) and the note that a replace resets the wallet's frontier to its birth (G85; the switch's messages, G90).
- **O4** is widened: may neutral slices start at the train's landing while #264's review is still open? Default: no (G11).
- **O6** now covers only the per-screen naming (U5b). The prompt naming (U5) is built by default (G93).
- **O10**: part (c) depends only on A1's names and his yes; if the landing-sync freeze has begun when he answers, it can only ride the train as a row of its private scope file, which is his call (G25). G96 shortens A1's names to the key-record ones.
