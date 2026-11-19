# Lost PIN: the recovery phrase first — design

Status: **DRAFT for the owner.** The direction is decided; four questions below are his.
Decision (Mark, 2026-11-19, answering O9 of `docs/superpowers/plans/2026-11-17-multi-wallet-step2.md`):
**"Switch to the iOS flow."** A wallet on the phone with no app PIN asks for its recovery phrase
before a new PIN can be set. He answered that sentence only: the multi-wallet rule (§4) is **not**
confirmed and is put to him here.
Landing: its own PR, after the 4.1.5 cut, outside step 2. It changes how the PIN is reset, so it is
owner-gated (**[R8]**; `docs/development-process.md:54-56`, "Still Mark's, always").
Code read at `develop` c76c0887b and tag `v4.1.4`; iOS at `JohnnyLawDGB/digibytewallet-ios`
`develop` c771683 (cited as `ios:path:line`), through `gh api` only.

## 1. Today on Android

**The state:** a seed record is stored, the PIN store opens, and it holds no `pin_hash`.

**What the app does.** `WalletManager` starts `Locked` when a record exists (`WalletManager.kt:236-243`).
`startDestinationFor` sends `Locked` + readable store + no PIN to `pin_setup` with
`restoreForLostPin = true` (`LockGatePolicy.kt:144-151`; logged at `AppNavigation.kt:188-192`). The
`pin_setup` composable then loads the wallet from its record (`restoreFromDisk`,
`AppNavigation.kt:367-411`) and shows `PinSetupScreen` (`:413-417`), which stores whatever PIN is
typed (`PinSetupScreen.kt:124`) and, a wallet being stored, creates nothing (`:183-196`) and opens
it. `shouldRouteToUnlock` leaves this path alone (`LockGatePolicy.kt:57`) and the auto-lock never
fires with no PIN (`AutoLockPolicy.kt:20`, `:46`).

**What guards it.** Only the seed key. An auth-bound key outside its 300 s window raises one
device-credential prompt and one retry (`AppNavigation.kt:386-401`). Inside the window, on a legacy
unbound key, or on a phone with no lock screen (`SeedKeyBinding.NONE`): **nothing**. A key lost with
the device lock shows `unlock_device_lock_removed` instead of the PIN screen (`:402-408`, `:370-378`).

**How the state arises** (each traced; "both" = 4.1.4 and `develop`):

| # | Cause | Reachable |
|---|---|---|
| a | **A first-run restore stopped before the PIN.** The record is written on the date screen (`RecoveryDateScreen.kt:194-205` → `OnboardingViewModel.kt:359-388` → `WalletManager.kt:320-340`), after `clearPin` (`:385-387`); the PIN is set on the next screen. A kill, crash or reboot between the two leaves it. | both. 4.1.4 clears the PIN unconditionally (`v4.1.4:OnboardingViewModel.kt:257`); `develop` only when no record of any wallet exists (F31), so there a restore beside a kept PIN lands on `unlock`, not here. |
| b | **The same restore, PIN write refused.** `setPin` fails (`PinStoreWriteException`), the screen stays (`PinSetupScreen.kt:198-204`), the app is closed. | both |
| c | **The PIN store file is gone while the record stays.** "No file at all is an empty store" (`FilePinStore.kt:30`, `:229-233`), and with no old store either the migration opens a fresh one (`:364-374`). Nothing in the app deletes the file and keeps the record: `clearPin` runs only before a first-run restore (a) and after a *verified* wipe (`WalletManager.kt:1242-1251`). So this needs a filesystem fault or someone with root/adb-level access to app data. | both, not from the UI |
| d | **An old install whose store never held a PIN.** The branch dates from d46e798a2 ("restore wallet when PIN lost"). No current build writes a record without a PIN step. | not verified (§8) |

**A create cannot cause it.** First-run create is PIN first: `setPin`, then `createWallet`
(`PinSetupScreen.kt:124`, `:183-194`; `OnboardingViewModel.kt:319-337`). A stop between them leaves a
PIN and no wallet, which starts on onboarding. An ADD (`develop`) writes a record beside an existing
PIN and sets none (`PassphraseScreen.kt:119-124`). So KS-BIND's "record sealed before the PIN" is true
of the **restore** only; in a create the record is sealed before the *native create*
(`WalletManager.kt:298-304`), after the PIN.

**Warm variant.** After (a)'s restore the process holds the wallet `Unlocked` with no PIN; the start
decision for that is `pin_setup` with no reload (`LockGatePolicy.kt:150`). `MainActivity.onStop` locks
(`MainActivity.kt:441-456`), so an Activity recreated in that window (a language change, "Don't keep
activities") computes `Locked` + no PIN and takes the lost-PIN branch too.

**Not this state, and not to be confused with it:**
- **PIN store unreadable** (4.1.2 row 23): the file is there and does not open, or `hasPin()` throws →
  `PIN_STORE_UNAVAILABLE_ROUTE`, never "no PIN" (`LockGatePolicy.kt:146-148`; `PinManager.kt:300-304`).
- **A wipe owed:** run at launch before any screen (`MainActivity.kt:155-162`; `PinManager.kt:616`).
- **First run, no wallet:** `NoWallet` → onboarding, whatever the PIN store holds (`LockGatePolicy.kt:145`).
- **Onboarding in progress** in the process that typed the phrase (the warm variant's first half).
- **Another device's data:** backups and device transfer are excluded (`AndroidManifest.xml:22`,
  `res/xml/data_extraction_rules.xml`), so a record never arrives without its PIN store that way.

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

**Decided by the owner's answer:** in the state of §1, no new PIN without the wallet's phrase.

**Flow.**
1. **Launch.** `startDestinationFor` returns a new route `lost_pin_phrase` where it returns
   `pin_setup`/`restoreForLostPin` today. **Nothing is loaded from the seed yet** (today the wallet is
   loaded before the PIN screen).
2. **Phrase screen.** The restore's own word entry (`MnemonicInputScreen`) and passphrase field
   (`RestorePassphraseField`), a title and one paragraph, Continue. No Back, no Skip, screenshots
   blocked as on the restore (`SecureWindow`, `MnemonicInputScreen.kt:51`).
3. **Check** (off the main thread; §"The check"). Mismatch → an error line, words kept, try again.
4. **Match →** `restoreFromDisk`, exactly as today's lost-PIN restore: the BootGuard bracket, one
   device-credential prompt if the key asks, the key-lost message. It is given the typed identity and
   refuses to open a record whose sealed seed derives a different one.
5. **`pin_setup`** as today → Unlocked → biometric offer → wallet.

**The check.** The record already holds the wallet's identity in plain prefs:
`seed_fingerprint_v2` = SHA-256 of the 64-byte BIP39 seed (`SeedFingerprint.kt:37-38`; written at
`WalletManager.kt:1481`), and on older records `seed_fingerprint` (v1) = SHA-256 of the mnemonic
bytes (`:34-35`). The duplicate check compares a typed phrase with exactly these
(`WalletProvisioner.kt:156-177`). The lost-PIN check is that comparison against one record:
- v2 present: derive the typed seed (`seedNative`, PBKDF2) and compare. **The BIP39 passphrase is
  needed** for a passphrase wallet, because v2 is over the derived seed. The field is the restore's
  optional one; the mismatch text names the passphrase.
- v1 only (a record from before the passphrase work, so no passphrase): compare the mnemonic hash.
- neither: fall back to the sealed seed (load it, derive, compare), which may raise the
  device-credential prompt. This is iOS's "no stored keys" case.
- A wrong phrase therefore **never touches the Keystore**. The fingerprint is a filter, not the
  authority: it is unauthenticated plain prefs, so step 4 confirms the typed identity against the
  sealed seed before the PIN screen is offered.
- One core function, shared with `WalletProvisioner.match`, not a second copy.

**Mismatch.** As iOS: no attempt limit, no cooldown, not counted toward wipe-after-N. There is no PIN
record to count against, and 2048-round PBKDF2 over a 128-bit space needs no limiter. A `Match.Unknown`
(derivation threw) says "could not be checked, nothing was changed", never "wrong".

**A user without the phrase** cannot open the wallet in this app. The only way on is the system's
"Clear storage" or an uninstall, which destroys the wallet; the screen says so plainly. This is the
cost of the decision: today the phone's lock (or nothing) lets that person in; afterwards nothing
does. See Q3.

**Seed handling.** The words are Strings while they sit in the text fields, as on the restore screen
(AUDIT-SUMMARY P2, unchanged). From Continue on they are one `ByteArray`, zeroed in `finally` with the
passphrase bytes and the derived seed, as `WalletProvisioner.match` does (`:173-176`). Nothing typed
is kept in a ViewModel, a nav argument or saved state; only the v2 hash crosses to step 4.

**Onboarding interrupted: no dead end for a new wallet.** A wallet **created** on this phone cannot
be in this state: its PIN is stored before its record (§1). The interrupted case is always a
**restore** (cause a/b), and a restore's phrase is by definition in the user's hands: they typed it
minutes earlier. The record says which it was (`wallet_origin`, `WalletManager.kt:92-96`), but the
design does not need to branch on it. What must not happen is asking for the phrase again *inside the
process that just took it*: a process-only flag ("this process stored or proved this record"), set by
a successful first-run restore and by a match, makes the warm variant of §1 start on `pin_setup` as
today. It lives in memory only; a cold start always asks.

**Unchanged:** the store-unavailable screen, the owed wipe (it runs first; like iOS, a match does not
proceed while `isWipePending()`), first run, ADD, `unlock`.

**Strings.** A title, a body, the mismatch, "could not be checked" and the no-phrase paragraph, in
every language of `AppLocale.SUPPORTED` in the same commit; the screen joins `COVERED` in
`OnboardingHardcodedStringTest`. The no-phrase paragraph is a wallet-loss warning and needs a native
reader before it is called done.

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
The wallet loaded after the PIN is the active one, as today (PD22), whichever phrase matched.

## 5. Step 2 (PD22, E2b) and CRITICAL-1

- **PD22 / E2b hold, and get stronger.** Nothing attaches until the new PIN is set and the app is
  Unlocked. Before a match nothing is loaded at all; after it only the active wallet is, from its
  seed. E2b's `AttachOnlyAfterUnlockTest` gains the phrase screen as a state that attaches nothing.
  Under option C, E2b must also skip a wallet that still owes its phrase.
- **File order.** This PR and K2, K3, E2b all edit `AppNavigation.kt`; by G9 only one is open at a
  time. This one is small and should land before E2b opens that file.
- **CRITICAL-1 (wipe-after-N).** Not touched. Phrase attempts are not PIN attempts. The setting
  lives in the PIN store, which is empty in this state, so the new PIN starts with it off, as today.
- **No lock screen (`SeedKeyBinding.NONE`).** Today this state is unguarded on such a phone; the
  phrase becomes its only guard. That is the largest single gain.

## 6. Tests, gates, landing

Red first, each failing on today's code:
- `LockGatePolicyTest`: `Locked` + readable store + no PIN starts on `lost_pin_phrase`, never
  `pin_setup`; with the process flag it starts on `pin_setup`; the row-23 and ordinary starts unchanged.
- `LostPinPhraseCheckTest` (core): right phrase; wrong phrase; right phrase with a wrong or missing
  passphrase; v1-only record; neither fingerprint → the seed fallback; derivation throws → Unknown;
  every buffer zeroed; a wrong phrase makes no Keystore call.
- `restoreFromDisk` refuses when the sealed seed's identity is not the typed one (an edited fingerprint).
- Source gate: no `composable` reaches `PinSetupScreen` over a stored wallet with no PIN except
  through a match or the process flag (iOS's is `ios:DigiByteWalletTests/PinFirstTests.swift:287`).
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
  which a restored wallet sits open with no PIN.
- **Q3. Someone without the phrase: should the screen offer "Erase this wallet"?** (a) no button:
  the text says only the phrase opens it and that clearing the app's storage erases it (iOS has no
  button); (b) an erase button behind a typed confirmation. **Recommended: (a).** An erase with no
  PIN and no phrase is a way for anyone holding the phone to destroy the wallet from inside the app.
- **Q4. "Forgot PIN?" on the unlock screen** (iOS has it: the phrase resets a PIN that *is* set).
  (a) not in this change; (b) add it now, it is the same screen. **Recommended: (a)**: it lets the
  phrase bypass the PIN cooldown and wipe-after-N on purpose, which is a separate decision.

## 8. Not verified

- Cause (d): whether any shipped build ever stored a wallet without a PIN step.
- Cause (c) on a device: that deleting only `dgb_pin_store.bin` reproduces the state.
- Nothing here was run on a device or emulator; every Android claim is from reading the code.
- On `develop`, a first run on a network with no wallet while another network's wallets keep their
  PIN (F31) appears to reach `pin_setup` and replace that PIN without asking for it
  (`RecoveryDateScreen.kt:203`, `PinSetupScreen.kt:124`). Read, not reproduced; a neighbour of this
  change, not part of it.
