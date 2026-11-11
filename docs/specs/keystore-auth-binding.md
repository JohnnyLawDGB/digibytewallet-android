# Keystore auth-binding — the seed key requires a recent device unlock

**Status:** In progress (branch `feat/keystore-auth-binding`)
**Provenance:** Audit CRITICAL-1 open half → THREAT_MODEL known-limitation #4 →
ROADMAP Phase 2 ("Keystore auth-binding", next after Digi-ID key isolation, shipped PR #66).

## Problem

The AES-256-GCM Keystore key wrapping the seed (`dgb_wallet_master`) is created with no
`setUserAuthenticationRequired` — any code that reaches `keyStoreManager.decrypt()` can
decrypt the seed with the device locked. The app-level PIN is bypassable by a compromised
app process. Binding was tried once (10s window, v3.0.x) and removed in `256522c2` after
real crashes: API 28 `UserNotAuthenticatedException` on encrypt-before-auth, API 33
keygen `InvalidAlgorithmParameterException` with no lock screen, API 35 auth-state
inconsistency. The roadmap's ask: revisit **with per-API-level probing** — never crash.

## Design

### Binding policy (pure function, unit-tested)

`seedKeyBindingFor(apiLevel, deviceSecure)`:
| Condition | Binding |
|---|---|
| `!deviceSecure` (no PIN/pattern/password on the DEVICE) | `NONE` — keygen would throw (the API 33 crash); unbound key, exactly today's behavior |
| API 30+ | `setUserAuthenticationParameters(300, DEVICE_CREDENTIAL \| BIOMETRIC_STRONG)` |
| API 26–29 | `setUserAuthenticationRequired(true)` + `setUserAuthenticationValidityDurationSeconds(300)` |

300s window: every seed decrypt in the app is a foreground flow (UnlockScreen post-PIN,
lost-PIN restore, SeedView, recovery/sweep — verified; the background-capable Keystore
user is the SQLCipher DB key `dgb_db_passphrase`, a different alias, deliberately
untouched), and the user has nearly always unlocked the device within 5 minutes of
reaching them. When they haven't, the typed-exception path below prompts instead of
crashing — the API 28 failure was the *absence* of that path, not the binding.

### New key + blob, migration by re-wrap (never in place)

- New alias `<alias>_v2`, new prefs keys `encrypted_seed_v2`/`encrypted_seed_iv_v2`
  (+ `encrypted_pass_v2`/iv for the stored BIP39 passphrase — same door as the mnemonic).
- **New wallets** (create/recover): try the auth-bound key first; if keygen or the first
  encrypt fails for ANY reason, fall back to the legacy unbound path — onboarding must
  never crash or block (the API 28 lesson).
- **Existing wallets** migrate inside `restoreFromDisk()` — post-PIN foreground, mnemonic
  just decrypted anyway: create v2 key → encrypt → **verify round-trip decrypt equals** →
  re-wrap passphrase blob if present → commit v2 prefs → only then delete the legacy
  blob + legacy Keystore key. Any failure aborts quietly and retries next unlock; the
  wallet is never left between states (v2 written atomically before legacy removal;
  a crash between the two leaves both blobs and v2 wins on next load).

### Typed failure instead of a crash

`KeyStoreManager` wraps auth-bound ops: `UserNotAuthenticatedException` →
`KeystoreUserAuthRequiredException`; `KeyPermanentlyInvalidatedException` →
`KeystoreKeyInvalidatedException`. `loadSeed()` propagates them (today it swallows to
null → "unlock failed", indistinguishable from corruption). UnlockScreen catches the
auth-required case, runs a `BiometricPrompt` with
`DEVICE_CREDENTIAL | BIOMETRIC_STRONG` (no negative button — required by the API when
DEVICE_CREDENTIAL is allowed; this is also what refreshes a timeout-bound key's window),
and retries the restore once. The lost-PIN path does the same.

### The honest trade-off (recorded, not hidden)

A timeout-bound key is **permanently invalidated when the user removes their device lock
screen**. From then the v2 blob is undecryptable and the wallet must be restored from
the written recovery phrase. This is the standard price of auth binding (the recovery
phrase is the recovery); the invalidated case surfaces as its own message ("device lock
was removed — restore from your recovery phrase"), never as a generic unlock failure.
Devices with no lock screen never migrate (policy `NONE`), so nobody is moved onto a key
they could invalidate without ever having chosen a lock screen.

## Deliverables

1. `SeedKeyBinding` policy (pure) + unit tests; source-gate test that `createAuthBoundKey`
   probes and binds (same pattern as `KeyStoreHygieneSourceGateTest`).
2. `KeyStoreManager`: `createAuthBoundKey/encryptAuthBound/decryptAuthBound/hasAuthBoundKey`,
   typed exceptions, `deleteKey()` clears both aliases.
3. `WalletManager`: v2-aware `persistSeed`/`loadSeed`/`loadPassphrase`, migration in
   `restoreFromDisk`, public `decryptStoredMnemonic/decryptStoredPassphrase` (SeedView
   stops reading prefs raw).
4. `BiometricAuth.authenticateDeviceCredential`; UnlockScreen + lost-PIN retry paths.
5. `KeyStoreManagerTest` additions (auth-bound round-trip under `assumeTrue(deviceSecure)`).
6. THREAT_MODEL #4 + ROADMAP updated in-PR. Device test on the Note 8 (API 28 — the
   historically crashy level) including the live migration of a real wallet.

## KS-BIND — bound from creation (owner security decision)

**Invariant.** When the device has a secure lock screen at wallet creation or restore, the seed
is wrapped under the auth-bound `_v2` key when the record is first written, not only after a later
unlock migrates it. When the device has no secure lock screen, the app says so: a notice at setup,
and a status line in Security settings ("Wallet key") showing whether the seed key is tied to the
device lock.

**What the `_v2` window allows.** The key opens for `SEED_KEY_AUTH_WINDOW_SECS` (300 s) after *any*
device authentication: the keyguard unlock that opened the phone counts, and so does a
`DEVICE_CREDENTIAL` prompt. `sealRecord` already tried the bound key first, but a create or restore
that reaches its record write more than 300 s after the phone was unlocked (writing 24 words down
takes longer than that) got `UserNotAuthenticatedException` on the first encrypt and fell back to
the legacy key without saying anything. The wallet then stayed unbound until the next unlock.

**Design.** `WalletManager.prepareSeedKeyForNewRecord()` probes the bound key with a throwaway
encryption just before the record write and answers a `SeedKeyReadiness`
(`seedKeyReadinessFor(deviceSecure, probe)`, pure):

| Probe | Readiness | The step does |
|---|---|---|
| device not secure | `NO_LOCK_SCREEN` | no keygen at all (the API 33 crash); legacy key; notice at the end of setup |
| `READY` (window open) | `READY` | writes with no prompt: the usual case |
| `AUTH_REQUIRED` (window lapsed; the API 28 encrypt-before-auth case) | `NEEDS_DEVICE_CREDENTIAL` | ONE device-credential prompt, then writes |
| `UNAVAILABLE` / `INVALIDATED` (keygen refused, the API 35 inconsistency, vendor quirks) | `UNAVAILABLE` | no prompt, since it would not help; legacy fallback |

The prompt sits **at the step that writes the record**: PIN setup for a create (after the PIN is
confirmed, before `createWallet`) and the restore's start button for a restore (the restore writes
its record *before* PIN setup, so a prompt at PIN setup would come too late). In both places no
wallet is open yet, so the prompt's `onStop` locks nothing. Nothing here blocks onboarding: a
declined prompt, no activity or any error lets the write go ahead, `sealRecord` falls back exactly
as before, and the existing migration still moves the wallet the next time the app opens the
wallet's key. On API 28–29 the
prompt allows `BIOMETRIC_WEAK`, and a weak-biometric success opens no Keystore window, so that case
also falls back without crashing.

The probe writes nothing to the wallet record and reads no secret. **Existing wallets are
unchanged**: no new migration is added, and the unlock-time migration is untouched. An invalidated
`_v2` key that no record uses (a lock screen removed during an abandoned onboarding) is *not*
deleted by the probe, because nothing deletes a key outside the user's wipe. New creates then fall
back to the legacy key until a wipe. This is recorded as an owner decision.

The decision is one function, `prepareSeedKeyThenPrompt(prepare, prompt)` in core
(`SeedKeyPreparation.kt`): it runs the probe off the main thread and, on `NEEDS_DEVICE_CREDENTIAL`
only, runs the prompt once and awaits it. The app's `prepareSeedKeyBeforeRecordWrite` only supplies
the prompt (it needs an activity); the core tests call the same function with a fake prompt. The PIN
keypad takes no input while the wallet is being created, so a second create cannot start in the gap
the probe and prompt open; the restore button is guarded the same way.

Keystore auth failures are recognised anywhere in the cause chain (`keystoreAuthFailureOf`), since
some vendor Keystores raise not-authenticated at `doFinal`, wrapped as an `IllegalBlockSizeException`
whose cause is a `KeyStoreException`. Read only at the top level, that probe answered `UNAVAILABLE`
and the create fell back without a prompt.

**Status line.** `WalletManager.seedKeyStatus()` →
`seedKeyStatusFor(hasWallet, recordBound, boundKey, deviceSecure)` (pure). The record alone is not
evidence: removing the device lock invalidates the `_v2` key for good (API 31+ deletes it), and a
lock set again later does not bring it back. So the key is asked too (`BoundKeyState`: a throwaway
ENCRYPT that succeeds or refuses with not-authenticated is `USABLE`; absent or permanently
invalidated is not; a Keystore that cannot be asked is `UNKNOWN`, which is not read as a removed
lock). States: `BOUND` (bound record, key opens, device has a lock), `LOCK_REMOVED` (bound record
whose key no longer opens, or no lock now: the text tells the user to check the phrase or move funds
while the wallet is still open, since the next cold start needs the phrase), `PENDING` (legacy key
on a secure device; moved the next time the app starts and opens the wallet's key: the warm unlock
does not decrypt the seed, so it does not migrate), `CANNOT_BIND` (legacy record beside an
invalidated leftover `_v2` key: the move can never happen on this install, so `PENDING` would be a
false promise; a wipe and restore binds it), `NO_LOCK_SCREEN`, `NO_WALLET`. The row is not
clickable.

**No-lock notice.** Shown once per install. PIN setup marks it due when it finds no lock screen, and
dismissing it acknowledges it for good (`NoScreenLockNoticeFlags`, `dgb_settings`). A notice that
was due but never dismissed (the app was backgrounded while it was up and the lock screen took over)
is shown again by `MainActivity` at the next unlock. A wallet that never passes through PIN setup is
never marked, so existing wallets see nothing new.

**Owner decision (flagged, not built).** Binding at creation is best effort. A declined or cancelled
prompt, a weak-biometric success on API 28–29, a missing activity, a refused keygen or an
invalidated leftover `_v2` key all land the new record on the legacy key, and setup says nothing
(the status line reads `PENDING`, or `CANNOT_BIND` for the leftover key). Options: (a) accept the
silent fallback (built); (b) show a short "not tied yet" notice whenever the readiness was
`NEEDS_DEVICE_CREDENTIAL` and the record still landed legacy; (c) delete an invalidated `_v2` key
that no stored record references (it wraps nothing), so new creates can bind. (c) touches the rule
that nothing deletes a key outside the user's wipe, so it is the owner's.

Tests: `SeedKeyBindingTest` (literal rows for API 26–37 × secure/insecure, readiness, and every
record × key × lock status combination), `KeystoreAuthErrorMappingTest` (wrapped forms),
`SeedKeyBindAtCreationTest` (through `prepareSeedKeyThenPrompt`: create and restore with the window
open or lapsed, a declined prompt, no activity, a throwing probe, no lock screen, refused keygen, an
invalidated key, an existing wallet left unchanged, and the status line against a deleted,
invalidated, lapsed or unaskable key), the app's `SeedKeyBindingStepTest` (the shipped prompt
decision with mocks) and `SeedKeyBindAtCreationWiringTest` (call order and awaiting at both call
sites, notice flags, keypad guard, the status row).
