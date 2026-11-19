# Lost PIN: the recovery phrase first — design

Status: **DRAFT for the owner.** The direction is decided; six questions below (§7) are his.
Decision (Mark, 2026-11-19, answering O9 of `docs/superpowers/plans/2026-11-17-multi-wallet-step2.md`):
**"Switch to the iOS flow."** A wallet on the phone with no app PIN asks for its recovery phrase
before a new PIN can be set. He answered that sentence only: the multi-wallet rule (§4) is **not**
confirmed and is put to him here.
As recommended with the question, not yet said by him: its own PR, after the 4.1.5 cut, outside
step 2. It changes how the PIN is reset, so it is owner-gated (**[R8]**; `docs/development-process.md:54-56`, "Still Mark's, always").
Code read at `develop` c76c0887b and tag `v4.1.4`; iOS at `JohnnyLawDGB/digibytewallet-ios`
`develop` c771683 (cited as `ios:path:line`), through `gh api` only.

## 1. Today on Android

**The state:** a seed record is stored, the PIN store opens, and it holds no `pin_hash`. Today the
app reads "a record is stored" for the **active wallet only**; the design reads it for any wallet
(§3 step 1), because the PIN is one for the app.

**What the app does.** `WalletManager` starts `Locked` when a record exists (`WalletManager.kt:236-243`).
`startDestinationFor` sends `Locked` + readable store + no PIN to `pin_setup` with
`restoreForLostPin = true` (`LockGatePolicy.kt:144-151`; logged at `AppNavigation.kt:188-192`). The
`pin_setup` composable then loads the wallet from its record (`restoreFromDisk`,
`AppNavigation.kt:367-411`) and shows `PinSetupScreen` (`:418-422`), which stores whatever PIN is
typed (`PinSetupScreen.kt:124`) and, a wallet being stored, creates nothing (`:183-196`) and opens
it. `shouldRouteToUnlock` leaves this path alone (`LockGatePolicy.kt:57`) and the auto-lock never
fires with no PIN (`AutoLockPolicy.kt:20`, `:46`).

**What guards it.** Only the seed key. An auth-bound key outside its 300 s window raises one
device-credential prompt and one retry (`AppNavigation.kt:386-401`). Inside the window, on a legacy
unbound key, or on a phone with no lock screen (`SeedKeyBinding.NONE`): **nothing**. A key lost with
the device lock shows `unlock_device_lock_removed` instead of the PIN screen (`:404-410`, `:370-378`).

**How the state arises** (each traced; "both" = 4.1.4 and `develop`):

| # | Cause | Reachable |
|---|---|---|
| a | **A first-run restore stopped before the PIN.** The record is written on the date screen (`RecoveryDateScreen.kt:194-205` → `OnboardingViewModel.kt:359-388` → `WalletManager.kt:320-340`), after `clearPin` (`:385-387`); the PIN is set on the next screen. A kill, crash or reboot between the two leaves it. | both. 4.1.4 clears the PIN unconditionally (`v4.1.4:OnboardingViewModel.kt:257`); `develop` only when no record of any wallet exists (F31), so there a restore beside a kept PIN lands on `unlock`, not here. |
| b | **The same restore, PIN write refused.** `setPin` fails (`PinStoreWriteException`), the screen stays (`PinSetupScreen.kt:198-204`), the app is closed. | both |
| c | **The PIN store file is gone while the record stays.** "No file at all is an empty store" (`FilePinStore.kt:30`, `:229-233`), and with no old store either the migration opens a fresh one (`:364-374`). Nothing in the app deletes the file and keeps the record: `clearPin` runs only before a first-run restore (a) and after a *verified* wipe (`WalletManager.kt:1242-1251`). So this needs a filesystem fault or someone with root/adb-level access to app data. | both, not from the UI |
| d | **Historical.** Builds before d46e798a2 (between v3.0.11 and v3.0.16, package `io.digibyte`) deleted the PIN store file during database-failure recovery and kept the seed; the lost-PIN branch was written in that commit for them. | not in `app.aroundtheblock.wallet` (another application id, so that data cannot reach it); today `clearPin` has two callers (`OnboardingViewModel.kt:386`, `WalletManager.kt:1247`) and nothing else deletes the store |

**A create cannot cause it.** First-run create is PIN first: `setPin`, then `createWallet`
(`PinSetupScreen.kt:124`, `:183-194`; `OnboardingViewModel.kt:319-337`). A stop between them leaves a
PIN and no wallet, which starts on onboarding. An ADD (`develop`) writes a record beside an existing
PIN and sets none (`PassphraseScreen.kt:119-124`). So KS-BIND's "record sealed before the PIN" is true
of the **restore** only; in a create the record is sealed before the *native create*
(`WalletManager.kt:298-304`), after the PIN.

**Warm variant.** After (a)'s restore the process holds the wallet `Unlocked` with no PIN; the start
decision for that is `pin_setup` with no reload (`LockGatePolicy.kt:150`). `MainActivity.onStop` locks
the UI only (`MainActivity.kt:441-457`), so an Activity recreated in that window (a language change,
"Don't keep activities") computes `Locked` + no PIN and takes the lost-PIN branch. The **same**
Activity, reopened, stays on `pin_setup`: the start is computed once and nothing re-routes a no-PIN
`Locked` app (`LockGatePolicy.kt:57`).

**The state is per active wallet today, and that leaves a hole.** `walletState` is the active scope's
(`WalletManager.kt:235-243`; `ActiveWallet.kt:14-23`; `WalletRegistry.kt:227-231` falls back to
`legacy` when the selected network has no READY wallet). With wallets on the *other* network and none
on the selected one, the start is first-run onboarding whatever the PIN store holds
(`LockGatePolicy.kt:145`). A create there stores a PIN first (`PinSetupScreen.kt:124`), a restore
after its record (`RecoveryDateScreen.kt:203`), and that PIN opens every wallet
(`2026-11-16-multi-wallet-design.md:343`). With no PIN stored, that is this document's state reached
without the phrase (§3 step 1 closes it). With a PIN stored, it replaces that PIN without asking for
it: Q6.

**Not this state:** a **PIN store that cannot be read** (4.1.2 row 23) goes to
`PIN_STORE_UNAVAILABLE_ROUTE`, never "no PIN" (`LockGatePolicy.kt:146-148`; `PinManager.kt:300-304`);
a **first run with no record of any wallet on either network** goes to onboarding. An owed wipe runs
at launch before any screen (`MainActivity.kt:155-162`), and backups and device transfer are excluded
(`AndroidManifest.xml:22`), so a record never arrives without its PIN store that way.

## 2. What iOS does

- **D-3** (`ios:docs/decisions.md:240`): "a wallet with no PIN asks for its own recovery phrase before
  a new PIN opens it." Beside it, **D-5** (`:238-239`): the PIN is stored before the phrase on create
  *and* restore, so "a stop between the two leaves no wallet". iOS therefore reaches D-3 only from
  "an older build's, or a kept earlier install's whose PIN record is gone"
  (`ios:DigiByteWallet/App/LockController.swift:19-21`).
- **Launch:** `PinFirst.launch` returns `.phraseForPin` for wallet + no PIN (`ios:Policy/PinFirst.swift:34-39`);
  an unreadable PIN store counts as holding a PIN (`:33`). `ContentView` shows
  `ForgotPinView(cancellable: false)` (`ios:DigiByteWallet/App/ContentView.swift:37-39`): no way back, no erase.
- **The check** (`ios:DigiByteWallet/App/PinViews.swift:388-433`): BIP39-validate, derive the typed
  phrase + optional passphrase to master **public** keys, compare with the stored ones
  (`SeedStore.isThisWallet`, `ios:DigiByteWallet/Wallet/SeedStore.swift:462-466`: "Reads no protected
  item, so it never prompts"). A wallet with no stored keys is checked against its stored seed behind
  one system prompt (`ios:Policy/WalletPhraseCheck.swift:6-10`, `:43-56`).
- **A mismatch** shows "These words are not this wallet's recovery phrase…" and keeps the words
  (`PinViews.swift:425-426`). **No attempt limit, no cooldown, nothing counted toward wipe-after-N:**
  "the phrase is 128 bits or more, and whoever holds it holds the funds already"
  (`ios:Policy/PinReset.swift:21-22`). Nothing is wiped (`:13-18`).
- **A match** goes to the new-PIN screen, refused while a wipe is owed or the PIN store cannot be
  read (`LockController.swift:373-377`; `PinReset.swift:19-20`, `:24`).
- The same screen serves **"Forgot PIN?"** on the unlock screen (`PinReset.swift:1-4`), which Android
  does not have. Out of scope here (Q4).

Not read or verified: anything on a device; the tests' bodies; the new-PIN view and
`PinManager.replaceWithNewPin` (taken from `PinReset.swift`'s comment).

## 3. The Android design

**Decided by the owner's answer:** in the state of §1, no new PIN without a wallet's phrase.

**Flow.**
1. **Launch.** The state is: **any seed record of any wallet on either network**
   (`registry.anyRecordExists()`, `WalletRegistry.kt:134`) + readable store + no PIN.
   `startDestinationFor` takes that as an input and returns a new route `lost_pin_phrase`, both where
   it returns `pin_setup`/`restoreForLostPin` today and where it returns onboarding for a selected
   network with no wallet. First-run onboarding never calls `setPin` while a record exists (with a
   PIN stored: Q6). `lost_pin_phrase` joins `fullScreenRoutes` (no bottom bar; in this state nothing
   else pulls a tab back, `LockGatePolicy.kt:57`, `AutoLockPolicy.kt:46`) and `PRE_WALLET_ROUTES`,
   so `SETUP_ROUTES`. Nothing native is loaded yet (today the wallet is loaded before the PIN screen).
2. **Phrase screen.** The restore's own word entry (`MnemonicInputScreen`) and passphrase field
   (`RestorePassphraseField`), a title and one paragraph, Continue. No Skip; Back leaves the app.
   Screenshots blocked as on the restore (`SecureWindow`, `MnemonicInputScreen.kt:51`).
3. **Check** against the stored fingerprints (off the main thread; "The check"). Mismatch → an error
   line, words kept, try again. A match names one record (which one may match: §4).
4. **Match →**
   - **4a. Confirm against the sealed seed of the record that matched**
     (`managerFactory.forScope(matched scope)`): decrypt it, derive, compare with the typed identity,
     zero it. Loads nothing native; may raise that wallet's own device-credential prompt. This is the
     authority: the fingerprint is plain, unauthenticated prefs.
   - **4b. `restoreFromDisk` of the active wallet, unchanged** (the BootGuard bracket, one
     device-credential prompt if its key asks, the key-lost message). It takes no typed identity, so
     the unlock path's v2 rewrite (`WalletManager.kt:480-484`) is untouched. When the matched record
     *is* the active one (always under §4 option B), 4a and 4b read the same record. When it is not,
     that is a second Keystore read and possibly a second prompt. When the selected network holds no
     wallet, 4b loads nothing and, once the PIN is set, the app goes on to that network's onboarding;
     `PinSetupScreen` must then create nothing.
5. **`pin_setup`**, only on the outcome "opened" below → Unlocked → biometric offer → wallet. The
   phrase entry is replaced by `pin_setup` (`popUpTo` inclusive), and `PinSetupScreen`'s exit pops
   `pin_setup` itself: today it pops the start destination (`PinSetupScreen.kt:145-147`), which would
   no longer be on the stack. Back on either screen leaves the app.

**Outcomes of step 4.** No PIN is stored unless the wallet opened. Today the result of the restore
is ignored and the PIN is stored over a wallet that did not open (`AppNavigation.kt:387`, `:411-412`;
`PinSetupScreen.kt:196`, `:171-175`).

| Outcome | Shown | `pin_setup` |
|---|---|---|
| 4a confirms, 4b opened (or had nothing to load) | — | yes |
| Device prompt declined or cancelled (4a or 4b) | stay on the phrase screen, words kept, retry | no |
| Key lost with the device lock (`KeystoreKeyInvalidatedException`) | `unlock_device_lock_removed`, as today | no |
| Record incomplete, the AND-005 contradiction, or the seed does not read (`WalletManager.kt:421`, `:429-433`, `:441-446`) | a new message: the wallet on this phone could not be read, the funds are safe with the words, erase the app's storage and restore (iOS: `PinViews.swift:427-428`) | no |
| 4a: the sealed seed is another identity (an edited fingerprint) | the mismatch line; nothing changed | no |

**The check.** The record holds the wallet's identity in plain prefs: `seed_fingerprint_v2` =
SHA-256 of the 64-byte BIP39 seed (`SeedFingerprint.kt:37-38`; written at `WalletManager.kt:1481`),
and on older records `seed_fingerprint` (v1) = SHA-256 of the mnemonic bytes (`:34-35`). The
duplicate check compares a typed phrase with exactly these (`WalletProvisioner.kt:156-177`); this is
that comparison, one core function shared with `WalletProvisioner.match`, not a second copy.
- v2 present: derive the typed seed (`seedNative`, PBKDF2) and compare. **The BIP39 passphrase is
  needed** for a passphrase wallet, because v2 is over the derived seed; the mismatch text names it.
- v1 only (a record from before the passphrase work, so no passphrase): compare the mnemonic hash.
- neither (an old record, or one whose identity derivation failed at store time:
  `WalletManager.kt:1470`, `:1481`): compare with the sealed seed, behind the seed key's own
  protection. As iOS does for missing keys (`WalletPhraseCheck.swift:6-10`, `:49-54`), v2 is written
  from the seed after a match, so the next check needs no decrypt.
- **With a stored fingerprint, a wrong phrase never touches the Keystore. With none, every attempt
  decrypts the seed to compare** (no prompt inside the 300 s window or on an unbound key); the bytes
  are zeroed in `finally` and nothing native is loaded.
- **A v2 that does not match is a mismatch; there is no fallback to the sealed seed** (iOS falls back
  only for missing or damaged keys). A fallback would decrypt the seed on every wrong guess. The
  cost: a record whose v2 is stale while its seed is sound refuses its own phrase, and the way on is
  to erase and restore with that phrase. The unlock path rewrites a differing v2, so this needs an
  edited or damaged record.

**Mismatch.** As iOS: no attempt limit, no cooldown, not counted toward wipe-after-N; there is no PIN
record to count against. The mnemonic is 128 bits or more. For a passphrase wallet the screen answers
passphrase guesses for someone who already holds the mnemonic; that person can already test guesses
against the chain, and v2 in plain prefs already allows it offline, so the screen adds nothing. A
`Match.Unknown` (derivation threw) says "could not be checked, nothing was changed", never "wrong".

**What the stored fingerprints expose** to someone holding the prefs file: v1 is an unsalted hash of
the mnemonic (useless without 128 bits of guessing); v2 is a passphrase oracle only for someone who
holds the mnemonic. This design stores no new value.

**A user without the phrase** cannot open the wallet in this app. The only way on is the system's
"Clear storage" or an uninstall, which destroys the wallet; the screen says so plainly. This is the
cost of the decision: today the phone's lock (or nothing) lets that person in; afterwards nothing
does (Q3). On a phone with no lock screen (`SeedKeyBinding.NONE`) the state is unguarded today and
the phrase becomes its only guard: the largest single gain. Wipe-after-N (CRITICAL-1) is untouched:
its setting lives in the PIN store, empty in this state, so the new PIN starts with it off, as today.

**Seed handling.** The words are Strings while they sit in the text fields, as on the restore screen
(AUDIT-SUMMARY P2, unchanged). From Continue on they are one `ByteArray`, zeroed in `finally` with the
passphrase bytes and the derived seed, as `WalletProvisioner.match` does (`:173-176`). Nothing typed
is kept in a ViewModel, a nav argument or saved state; only the v2 hash crosses to step 4.

**Onboarding interrupted: the process exception.** A wallet **created** on this phone cannot be in
this state (its PIN is stored before its record, §1). The interrupted case is a **restore** (cause
a/b), whose phrase the user typed in this process. Asking for it again inside that process is the
dead end to avoid, so a process-only mark makes the warm variant start on `pin_setup` as today. The
mark is not a bare flag: it holds **the record's id and v2 identity**, is set by a successful
first-run restore and by step 4, is honoured only while that record still has that identity, and is
cleared when `setPin` lands and on any wipe. It lives in memory only; a cold start always asks. **It
has no time bound in this draft**, and the process is long-lived (a foreground `SyncService`,
WorkManager catch-ups; `onStop` only locks the UI): a restore left on `pin_setup` can be finished by
whoever next opens the app, hours later, with no phrase and no device prompt. iOS has no such case
(D-5). Whether to bound it is Q5.

**Unchanged:** the store-unavailable screen, the owed wipe (it runs first; like iOS, a match does not
proceed while `isWipePending()`), ADD, `unlock`.

**Strings.** A title, a body, the mismatch, "could not be checked", "could not be read" and the
no-phrase paragraph, in every language of `AppLocale.SUPPORTED` in the same commit; the screen joins
`COVERED` in `OnboardingHardcodedStringTest`. The no-phrase paragraph is a wallet-loss warning and
needs a native reader before it is called done.

## 4. Multi-wallet: one PIN, several wallets — **OPEN FOR MARK**

On `develop` one PIN guards every wallet on both networks. With the PIN gone, whose phrase resets it?

| Option | Meaning | A wallet whose phrase is not entered |
|---|---|---|
| **A. Any one READY wallet's phrase** | One phrase proves one wallet; the new PIN then opens all. | Opens with the new PIN, unproven. |
| **B. The active wallet's phrase only** | As A, but only the wallet the app would load. | Opens with the new PIN. A user who has another wallet's phrase but not the active one's is locked out of all. |
| **C. Every wallet's phrase** | Each wallet stays closed until its own phrase is typed; the PIN is set after the first. | Stays closed (not attached, not shown as usable) until proven, or until it is removed. Needs a per-wallet "phrase owed" mark in the registry and a new state in step 2's attach. |

**Recommendation: A, mainnet first.** A mainnet wallet's phrase is accepted; a testnet wallet's only
when the phone holds no mainnet wallet (testnet coins have no value, so that phrase must not open
mainnet funds). Reasons: the PIN is already one secret for all wallets, so C adds a boundary the app
has nowhere else; B is A with a worse failure; C leaves funds stranded behind a phrase the owner may
have for every wallet but one. What A gives up, stated plainly: with A the phrase of one wallet opens
wallets whose phrases were never shown, which is weaker than D-3's "its own phrase" read per wallet.
The wallet loaded after the PIN is the active one, as today (PD22), whichever phrase matched: under
A (and under "mainnet first" with testnet selected) the matched record is confirmed in step 4a and
the active one is loaded in 4b, at the cost of a second Keystore read and possibly a second prompt.
Under B the two are the same record, and B has no answer when the selected network holds no wallet.

## 5. Step 2 (PD22, E2b) and file order

- **PD22 / E2b hold, and get stronger.** Nothing attaches until the new PIN is set and the app is
  Unlocked. Before a match nothing native is loaded (with no stored fingerprint the seed is decrypted
  to compare, §3); after it only the active wallet is, from its seed. E2b's
  `AttachOnlyAfterUnlockTest` gains the phrase screen as a state that attaches nothing. Under option
  C, E2b must also skip a wallet that still owes its phrase.
- **File order.** This PR edits `AppNavigation.kt` (G9: K2, K3, E2b), `WalletManager.kt` (G9: K0,
  K2, B6, S1, S2, E2b, E4, U3), `LockGatePolicy.kt` and `WalletProvisioner.kt`. It is never open
  beside a member of either cluster. Its slot relative to K2 is fixed when it is scheduled; K2 makes
  the lock state app-wide, and if this lands after K2, step 1 is rebased on that state. K2's "not
  R8" note does not cover this PR. The plan's G9 line names this PR in both clusters.

## 6. Tests, gates, landing

Red first, each failing on today's code:
- `LockGatePolicyTest`: `Locked` + readable store + no PIN starts on `lost_pin_phrase`, never
  `pin_setup`; so does `NoWallet` on the selected network with a record on the other; with the
  process mark for that record it starts on `pin_setup`, with a mark for another record it does not;
  the row-23 and ordinary starts unchanged; `shouldRouteToUnlock` rows for `lost_pin_phrase` and
  for a tab route under `Locked` + no PIN.
- `LostPinPhraseCheckTest` (core): right phrase; wrong phrase; right phrase with a wrong or missing
  passphrase; v1-only record; neither fingerprint → the seed is decrypted, zeroed on a mismatch, and
  v2 written on a match; a stale v2 over a sound seed → mismatch, no decrypt; derivation throws →
  Unknown; every buffer zeroed; with a stored fingerprint a wrong phrase makes no Keystore call.
- Step 4a: the phrase of non-active wallet B with B's fingerprint edited is refused; B's phrase,
  unedited, opens the active wallet A; the same for the active wallet alone.
- Step 4 outcomes: for each row of the table, whether `pin_setup` is shown and that no PIN is stored
  unless the wallet opened.
- Source gate: no call of `PinManager.setPin` is reachable with a stored record on either network
  and no PIN except after a match or under the process mark. It covers `PinSetupScreen` and
  `SettingsViewModel.changePin` (`SettingsViewModel.kt:399-402`); iOS's is
  `ios:DigiByteWalletTests/PinFirstTests.swift:287`.
- Extend `LostPinKeyLostGateTest` (the key-lost message still wins after a match),
  `NavigationRouteIntegrityTest`, `LocaleResourceParityTest`, `OnboardingHardcodedStringTest.COVERED`,
  `NetworkLeakTest`/`LogRedactionSourceGateTest` (no log line on the new path names the phrase).
- Full core + app unit suites with `--continue`; device check on the Note 8 (causes a and c, with and
  without a lock screen) and one API 35 emulator.

Landing: one PR into `develop` after the 4.1.5 cut, independent review + green CI, then **Mark
([R8])**. Not in 4.1.5. The iOS repo's `PinFirst.swift:5-8` comment about Android is updated once it ships.

## 7. Open questions for Mark

- **Q1. Several wallets, one PIN: whose phrase resets it?** (a) any one wallet's, mainnet before
  testnet; (b) only the active wallet's; (c) every wallet's, each closed until its own phrase is
  typed. **Recommended: (a).** Until answered the PR cannot be finished for `develop`.
- **Q2. Should the restore become PIN-first, like iOS (D-5)?** Then a stopped restore leaves no
  wallet at all and this screen is only ever seen after a fault. (a) yes, as a second small [R8]
  change after this one; (b) no. **Recommended: (a), separately.** It also closes the window in
  which a restored wallet sits open with no PIN (Q5).
- **Q3. Someone without the phrase: should the screen offer "Erase this wallet"?** (a) no button:
  the text says only the phrase opens it and that clearing the app's storage erases it (iOS has no
  button); (b) an erase button behind a typed confirmation. **Recommended: (a).** An erase with no
  PIN and no phrase is a way for anyone holding the phone to destroy the wallet from inside the app.
- **Q4. "Forgot PIN?" on the unlock screen** (iOS has it: the phrase resets a PIN that *is* set).
  (a) not in this change; (b) add it now, it is the same screen. **Recommended: (a)**: it lets the
  phrase bypass the PIN cooldown and wipe-after-N on purpose, which is a separate decision.
- **Q5. A restore that has not yet set its PIN can be finished without the phrase for as long as
  the app's process lives** (hours, with sync running), by anyone who opens the app. (a) accept it
  until Q2's PIN-first restore removes the case; (b) bound it: after the app has been in the
  background for a set time (say 5 minutes) the phrase is asked again; (c) do Q2 first, and build
  this with no exception at all. **Recommended: (a) if Q2 is yes, otherwise (b).** The window
  exists today with no bound and no phrase; (b) costs a re-check on resume that today's code has not.
- **Q6. On `develop` today, a first run on a network with no wallet, while the other network's
  wallets keep their PIN, sets a new PIN without asking for the old one** (create:
  `PinSetupScreen.kt:124`; restore: `RecoveryDateScreen.kt:203`). Read, not reproduced. It is a
  PIN-replace path, so it is yours. (a) that onboarding asks for the current PIN first and keeps
  it, setting none; (b) it asks for a wallet's phrase, as this screen does; (c) leave it.
  **Recommended: (a)**, in this PR or its own, before a release ships multi-wallet.

## 8. Not verified

- Cause (d): that no `app.aroundtheblock.wallet` build ever shipped the PIN-store delete (the first
  build of the new package was not checked; `develop` and 4.1.4 do not have it).
- Cause (c) on a device: that deleting only `dgb_pin_store.bin` reproduces the state.
- Nothing here was run on a device or emulator; every Android claim is from reading the code.
