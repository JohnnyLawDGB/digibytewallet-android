# PIN Rate-Limit — Design Spec (DRAFT — awaiting review)

> **Status:** DRAFT written autonomously 2026-07-16 while the operator was away.
> Nothing implemented. This is Phase 2.1 of the ROADMAP ("PIN rate-limit — first,
> small, unblocking"). Review the **Open decisions** section before I implement.

## Goal
Add a persisted, tamper-resistant rate-limit to the app PIN: **3 free attempts,
then 1 / 5 / 30 / 60-minute cooldowns**, plus an **optional wipe-after-N** behind a
Settings toggle. Closes the ROADMAP honest-state gap ("PIN brute-force has no rate
limit … the single cheapest high-value hardening item, and it gates the duress
PIN") and CRITICAL-1 residual.

## Threat model (what this defends)
- **Primary:** device-in-hand PIN brute-force (lost/stolen device, or a coercer who
  has the device but not the PIN). The app PIN gates the unlocked in-app wallet;
  today an attacker can try PINs as fast as the UI allows, unbounded.
- **Not in scope here:** seed extraction via a compromised OS/root (the seed is
  separately AES-256-GCM encrypted under a hardware Keystore key; the PIN gates the
  *app*, not the ciphertext) — that's the Keystore-auth-binding item (Phase 2.3).
- **Forward-looking:** the **duress PIN** (Phase 2.2) leans on this — a decoy door is
  theatre if the real PIN is free to brute-force. This design is built
  forward-compatible with the duress `{REAL|DURESS|NONE}` verify result.

## Current state (verified in code)
- `core/…/security/PinManager.kt` — `verifyPin(pin): Boolean`, Argon2id (PBKDF2
  fallback) + constant-time compare, stored in `dgb_pin_store`
  EncryptedSharedPreferences. **No attempt counter, no lockout, no backoff.**
- `UnlockScreen.kt:113` `attemptUnlock` — the only real unlock gate. Its
  `attemptCount`/`MAX_ATTEMPTS=5` is **cosmetic**: local Compose state, resets on
  recomposition/process restart, bypassed by biometric, and "Too many attempts,
  please wait" enforces nothing.
- `SecuritySettingsScreen.kt` — 3 more `verifyPin` re-auth gates (Change PIN:232,
  View Seed:353, Wipe:397).
- **Biometric** (`BiometricAuth.kt`) → on success calls the same
  `performUnlockAndNavigate()` and **never touches `PinManager`** — a separate,
  always-available credential with its own OS lockout.
- `PinManager` is `@Singleton`, but in-memory state does **not** survive force-stop —
  so the counter **must be persisted**.
- `StaleDataWiper` wipes the Room DB + sync blobs on crash-loop recovery but
  **preserves the seed** — so the counter must **not** live in the Room DB (a wipe
  would reset an attacker's count). `dgb_pin_store` is the right home (survives DB
  wipes, cleared atomically by `clearPin()`, never touched by `StaleDataWiper`).

## Design

### 1. State (persisted in `dgb_pin_store`, next to the hash)
| key | type | meaning |
|---|---|---|
| `pin_fail_count` | Int | consecutive failed attempts; reset to 0 on any success |
| `pin_lockout_until` | Long | wall-clock epoch-ms the lock expires; 0 = not locked |
| `pin_last_fail_at` | Long | wall-clock of last failure — backward-clock-jump guard |
| `pin_wipe_after_n` | Bool | Settings toggle; default **false** (destructive, opt-in) |

### 2. Backoff schedule (per ROADMAP)
```
failCount 1..3  → no cooldown (free)
failCount 4     → 1 min
failCount 5     → 5 min
failCount 6     → 30 min
failCount ≥ 7   → 60 min
if pin_wipe_after_n AND failCount ≥ WIPE_THRESHOLD (proposed 10) → WIPE
```
A pure `fun cooldownMsForFailCount(n): Long` — unit-testable in isolation.

### 3. `verifyPin` result (forward-compatible with duress)
Replace `Boolean` with:
```kotlin
sealed interface PinVerifyResult {
    object Success : PinVerifyResult                     // correct PIN
    data class Wrong(val failCount: Int,
                     val lockedUntil: Long?) : PinVerifyResult   // lockedUntil set iff this failure started a cooldown
    data class LockedOut(val until: Long) : PinVerifyResult      // already locked; PIN NOT checked
    object ShouldWipe : PinVerifyResult                  // wipe threshold reached (toggle on); caller performs the wipe
}
```
**Algorithm** (inside `PinManager.verifyPin`, all callers protected uniformly):
1. **Clock guard:** if `now < pin_last_fail_at` (clock moved backward), treat as
   tampering → force `pin_lockout_until = now + 60min`, return `LockedOut`.
2. **Lockout check:** if `now < pin_lockout_until` → return `LockedOut(until)`
   *without* running Argon2 (cheap; leaks nothing about the PIN).
3. **Compare** (existing constant-time Argon2/PBKDF2).
4. **Success** → reset `pin_fail_count=0`, `pin_lockout_until=0` → `Success`.
5. **Fail** → `pin_fail_count++`, `pin_last_fail_at=now`; if wipe-toggle & count ≥
   threshold → `ShouldWipe`; else set `pin_lockout_until = now +
   cooldownMsForFailCount(count)` → `Wrong(count, lockedUntil or null)`.

**Duress forward-compat:** when Phase 2.2 lands, step 3 matches against *both*
credentials and `Success` carries `REAL|DURESS`. The rate-limit logic is unchanged —
it only distinguishes *valid vs invalid*; any valid PIN (real or decoy) resets the
counter. No rework.

### 4. Biometric interaction
- Biometric stays a **separate credential** (OS handles its own lockout). A PIN
  brute-forcer has no finger, so gating the PIN is the correct defense for that
  vector — biometric is **not** disabled by PIN failures.
- **But biometric success must reset the PIN counter** (it's a valid unlock):
  `performUnlockAndNavigate()` calls `pinManager.onUnlockSuccess()` on the biometric
  path too. (Otherwise a legit user who used their fingerprint would still carry a
  stale lockout the next time they type the PIN.)

### 5. Wipe-after-N (opt-in, destructive)
- **Toggle** in `SecuritySettingsScreen` → "PIN & Authentication" (mirrors the
  existing Auto-Lock dropdown pattern). Default OFF. Enabling shows a strong
  confirmation requiring the user to acknowledge their recovery phrase is backed up
  (irreversible on-device).
- `PinManager` cannot wipe the wallet itself (it only owns `dgb_pin_store`). On
  `ShouldWipe`, the **caller** (UnlockScreen) performs the wallet wipe + navigates to
  onboarding — mirroring the manual `SettingsViewModel.wipeWallet()` →
  `navigate("onboarding"){popUpTo(0){inclusive=true}}` path.
- **Backstop against a kill between `ShouldWipe` and the wipe:** `PinManager` also
  sets a `pin_wipe_pending` flag; `BootGuard`/startup honors it (wipe on next launch).
  Eventually-consistent either way.
- **Wipe completeness (recommended, see Open decision D):** the security wipe should
  be a *complete* destructive wipe. `WalletManager.wipeWallet()` correctly destroys
  the seed (Keystore `dgb_wallet_master` + `dgb_wallet_seed` prefs) but LEAVES tx
  history / the full address set on disk: Room DB `wallet.db`, `dgb_watched_addrs`,
  `dgb_outgoing_tx`, `dgb_filter_headers*.bin`. For the wrench-attack threat, leaving
  that behind is a privacy leak. Recommend extending `WalletManager.wipeWallet()` to
  cover these (reuse `StaleDataWiper.wipeDatabase()` + explicit clears) so the manual
  AND auto wipe share one correct routine.

### 6. UI
- **UnlockScreen:** replace the cosmetic counter with the `PinVerifyResult`. Show
  "Incorrect PIN — N attempts before lockout", "Locked — try again in M:SS" (live
  countdown, keypad disabled), and route to onboarding on `ShouldWipe`. Biometric
  button also disabled while locked.
- **SecuritySettingsScreen:** the 3 re-auth gates consume the new result (surface the
  lockout there too). Add the wipe-after-N toggle + warning.

### 7. Clock manipulation — honest limitation
- Wall-clock lock survives reboot but a **forward** clock jump skips the cooldown; a
  **backward** jump is defeated by the step-1 guard.
- Forward-jump is an accepted residual for v1: it requires device access + intent +
  know-how; the wipe-after-N is the real backstop for the paranoid user; and the seed
  is separately Keystore-encrypted. Documented, not silently ignored.
- (Optional hardening, deferred: also track `SystemClock.elapsedRealtime()` and lock
  if *either* wall or monotonic says locked — but elapsed resets on reboot, so it's a
  partial belt-and-suspenders, not a fix. Left out of v1.)

## Files touched (estimate)
- `core/…/security/PinManager.kt` — result type, persisted counters, backoff, wipe
  signal (core logic).
- `core/…/security/PinManagerTest.kt` (new) — backoff schedule, reset-on-success,
  persisted-across-instances lockout, wipe threshold, clock-guard, constant-time.
- `app/…/ui/onboarding/UnlockScreen.kt` — consume result, countdown UI, biometric
  reset, wipe→onboarding.
- `app/…/ui/settings/SecuritySettingsScreen.kt` + `SettingsViewModel.kt` — re-auth
  result handling + wipe-after-N toggle.
- `core/…/WalletManager.kt` (if Open-decision D = "complete wipe") — extend
  `wipeWallet()` to cover DB + watched-addrs + outgoing-tx + filter-headers.

## Sequencing with 2.2 (duress) — IMPORTANT, read before building
The `feat/duress-pin-phase-a` branch (Phase A, substantially built — 12 commits:
account-parameterized native BIP32, account-1' decoy wallet + host KATs, tri-state
`matchPin`, session gating, setup UI) **forked from develop on 2026-07-12 and is now
40 commits behind** (all of v3.10.29–34: own-node pairing, the filter-header leak fix,
oracle-bootstrap, the g_isRescanning/keepalive/stack-overflow reliability series). It
**heavily overlaps the exact files 2.1 touches**: `PinManager.kt` (+88 — adds the
duress second credential + `verifyPin`→tri-state `REAL|DURESS|NONE`), `UnlockScreen.kt`
(+60), `SecuritySettingsScreen.kt` (+301), `SettingsViewModel.kt` (+50),
`WalletManager.kt` (+159, account-aware). It also modifies the same *native* files the
recent series rewrote (`jni_wallet.c`, `jni_peer.c`).

**Consequence:** duress is NOT "just merge it" — it needs a substantial, conflict-prone
rebase (or clean re-derivation) onto current develop first. And 2.1 + 2.2 both rewrite
`PinManager.verifyPin`, so they must compose: the final `verifyPin` returns a result
that is BOTH rate-limited AND tri-state. This spec's `PinVerifyResult` is already
designed for that (Success → REAL|DURESS), but the two changes still have to be merged
in one file.

**Recommended sequencing (Open-decision F):** build 2.1 on current develop now (it ships
first per the ROADMAP and gates duress), keeping the forward-compat result type; then
rebase/re-derive the duress branch on top of develop-with-2.1, reconciling the single
`PinManager.verifyPin` at that point. Given 40 commits of divergence on shared files, a
clean re-derivation of Phase A's Kotlin layer on top of current develop may be less
painful than a giant rebase — worth a look when duress becomes active. **Do not build
2.1 in a way that assumes the stale duress branch's shape; build against develop.**

## Open decisions (need your call before I build)
- **A. Wipe threshold N.** Proposed **10** total consecutive failures (3 free + a long
  climb through the cooldowns). Higher = safer against accidental self-wipe, lower =
  more aggressive. Your number?
- **B. Backoff exactness.** ROADMAP says "3 free, then 1/5/30/60 min." I mapped that to
  fails 4→1, 5→5, 6→30, 7+→60. Confirm, or want a different curve (e.g. keep doubling)?
- **C. Clock-manipulation stance.** Ship v1 with wall-clock + backward-guard and accept
  the forward-jump residual (documented), or invest in the elapsedRealtime
  belt-and-suspenders now?
- **D. Wipe completeness.** Fix the `wipeWallet()` privacy gaps (DB/addresses/outgoing/
  filter-headers) as part of this — recommended, since a security wipe that leaves tx
  history is half a wipe — or keep this PR minimal and file the wipe-hardening
  separately?
- **E. Biometric-on-PIN-lockout.** Confirm biometric stays available during a PIN
  lockout (my recommendation — attacker has no finger; a legit user shouldn't be
  locked out of their own fingerprint by someone else fat-fingering the PIN). Or should
  a PIN lockout also suspend biometric?
