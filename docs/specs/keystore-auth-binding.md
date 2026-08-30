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
