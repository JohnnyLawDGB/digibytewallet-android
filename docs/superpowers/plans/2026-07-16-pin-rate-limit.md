# PIN Rate-Limit — Implementation Plan (DRAFT — awaiting design approval)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development
> (or executing-plans). Steps use `- [ ]` checkboxes.
>
> **Status:** DRAFT companion to `docs/superpowers/specs/2026-07-16-pin-rate-limit-design.md`.
> Written autonomously 2026-07-16; **do not execute until the operator confirms the
> spec's Open decisions.** Task 4 exists only if Open-decision D = "complete wipe."

**Goal:** Persisted, tamper-resistant PIN rate-limit (3 free → 1/5/30/60-min
cooldowns) + optional wipe-after-N, gating every `verifyPin` caller.

**Architecture:** Rate-limit lives in `PinManager` (core) so all callers (UnlockScreen
+ 3 SecuritySettings re-auth sites) are protected uniformly. Counter/lockout persist
in `dgb_pin_store` EncryptedSharedPreferences (survives force-stop AND the Room-DB
crash-wipe). `verifyPin` returns a `PinVerifyResult` sealed type, forward-compatible
with the duress `{REAL|DURESS|NONE}` result.

**Tech stack:** Kotlin, AndroidX Security (EncryptedSharedPreferences), JUnit/Robolectric
for the core tests.

## Global Constraints
- Counter + lockout persist in `dgb_pin_store` ONLY (never the Room DB — `StaleDataWiper`
  wipes it). Cleared atomically by the existing `clearPin()`.
- `verifyPin` compare stays constant-time (unchanged Argon2id/PBKDF2 path). The lockout
  check runs BEFORE the compare and must not branch on PIN correctness.
- Assumed defaults pending confirmation: WIPE_THRESHOLD = 10; backoff fails 4→1min,
  5→5min, 6→30min, 7+→60min; wall-clock + backward-jump guard; wipe-after-N default OFF.
- Biometric success resets the counter; biometric stays available during a PIN lockout.
- No new dependency. Behavior with no stored counters = unlocked, count 0 (back-compat).

---

### Task 1: PinManager rate-limit core + tests
**Files:** Modify `core/src/main/java/io/digibyte/core/security/PinManager.kt`;
Create `core/src/test/java/io/digibyte/core/security/PinRateLimitTest.kt`.

**Produces:**
- `sealed interface PinVerifyResult { Success; Wrong(failCount:Int, lockedUntil:Long?);
  LockedOut(until:Long); ShouldWipe }`
- `fun verifyPin(pin, nowMs = System.currentTimeMillis()): PinVerifyResult` (the `nowMs`
  param is injectable for tests — never passed in prod).
- `fun cooldownMsForFailCount(n:Int): Long` (internal, testable): n≤3→0, 4→60_000,
  5→300_000, 6→1_800_000, ≥7→3_600_000.
- `fun onUnlockSuccess()` (reset counters — called by the biometric path).
- `fun isWipeAfterNEnabled(): Boolean` / `fun setWipeAfterN(enabled:Boolean)`.
- Keep a `verifyPinBoolean(pin): Boolean` shim ONLY if needed to stage caller updates.

**Steps (TDD):**
- [ ] Write `PinRateLimitTest`: (a) `cooldownMsForFailCount` schedule; (b) 3 wrong then
  Success resets count; (c) 4th wrong → `Wrong` with `lockedUntil≈now+60_000`; (d) a
  fresh `PinManager` instance (simulating force-stop) still sees the persisted lockout
  → `LockedOut`; (e) attempt during lockout returns `LockedOut` and does NOT increment;
  (f) backward `nowMs` → `LockedOut` (clock guard); (g) wipe-toggle on + count≥threshold
  → `ShouldWipe`; (h) correct-PIN timing unaffected (compare still runs only when not
  locked). Use a Robolectric context for EncryptedSharedPreferences, or abstract the
  store behind an interface for pure JVM tests (preferred — decouples from AndroidX).
- [ ] Run → fails (methods absent).
- [ ] Implement the persisted counters + `verifyPin` algorithm (spec §3) + helpers.
- [ ] Run → pass. Then `./gradlew :core:testMainnetDebugUnitTest --tests "*PinRateLimit*"`.
- [ ] Commit.

### Task 2: UnlockScreen consumes the result
**Files:** Modify `app/src/main/java/io/digibyte/ui/onboarding/UnlockScreen.kt`.

**Consumes:** `PinVerifyResult` from Task 1.

**Steps:**
- [ ] Replace cosmetic `attemptCount`/`MAX_ATTEMPTS` with `when(pinManager.verifyPin(pin))`:
  `Success`→`performUnlockAndNavigate()`; `Wrong`→error "Incorrect PIN — N attempts
  before lockout" (or "…locked for M:SS" when `lockedUntil!=null`); `LockedOut(until)`→
  disabled keypad + a live `M:SS` countdown (a `LaunchedEffect`/`while` ticking to
  `until`); `ShouldWipe`→ Task 5 wipe path.
- [ ] Call `pinManager.onUnlockSuccess()` in `performUnlockAndNavigate()` (covers the
  biometric success path so a fingerprint unlock clears any stale lockout).
- [ ] On enter, if `pinManager.currentLockout() > now`, start disabled+countdown.
- [ ] Build `:app:assembleMainnetDebug`. Manual check: wrong PIN ×4 disables keypad with
  countdown; correct PIN after cooldown unlocks; biometric still works during lockout.
- [ ] Commit.

### Task 3: SecuritySettings re-auth + wipe-after-N toggle
**Files:** Modify `app/…/ui/settings/SecuritySettingsScreen.kt`,
`app/…/ui/settings/SettingsViewModel.kt`.

**Steps:**
- [ ] Update the 3 re-auth gates (Change PIN:232, View Seed:353, Wipe:397) + the VM's
  `verifyPin` wrapper to the new result (surface `LockedOut`/`Wrong` there too — a
  brute-forcer must not bypass the limit via Settings).
- [ ] Add a "Wipe wallet after N failed PIN attempts" `Switch` in the "PIN &
  Authentication" `SettingsCategory` (mirror the Auto-Lock dropdown pattern). Wire to
  `pinManager.setWipeAfterN`. Enabling shows a confirmation requiring a "my recovery
  phrase is backed up" acknowledgement (irreversible on-device).
- [ ] Build. Commit.

### Task 4 (ONLY if Open-decision D = "complete wipe"): harden `wipeWallet()`
**Files:** Modify `core/…/WalletManager.kt` `wipeWallet()`.

**Steps:**
- [ ] Write/extend a wipe test asserting post-wipe: seed gone (Keystore
  `dgb_wallet_master` + `dgb_wallet_seed`), Room DB deleted, `dgb_watched_addrs`,
  `dgb_outgoing_tx`, `dgb_filter_headers*.bin` all cleared.
- [ ] Extend `wipeWallet()` to also `StaleDataWiper.wipeDatabase(context)` + clear
  `dgb_watched_addrs` + `OutgoingTxStore.clearAll()` + `FilterHeaderStore.delete()`
  (order: seed ciphertext first — the existing crash-safety invariant). Keep the manual
  Settings wipe pointed at the same routine.
- [ ] Run tests, build, commit.

### Task 5: Wire wipe-after-N end to end + startup backstop
**Files:** Modify `UnlockScreen.kt`, `SettingsViewModel.kt` (or a small wipe helper),
`app/…/BootGuard.kt` (or the startup path).

**Steps:**
- [ ] On `ShouldWipe` in UnlockScreen: call the destructive wipe (Task 4 routine via
  `SettingsViewModel.wipeWallet()` / WalletManager) then
  `navigate("onboarding"){popUpTo(0){inclusive=true}}`.
- [ ] `PinManager` sets `pin_wipe_pending=true` when it returns `ShouldWipe`; clear it
  after a successful wipe. On startup, if `pin_wipe_pending` is set (app was killed
  mid-wipe), complete the wipe before showing any unlock UI.
- [ ] Build. Manual: enable toggle, exhaust attempts → wallet wipes → onboarding; verify
  a mid-wipe force-stop still completes on next launch.
- [ ] Commit.

## Self-review checklist
- [ ] Counter/lockout ONLY in `dgb_pin_store`; survives force-stop + Room-DB wipe.
- [ ] `verifyPin` compare still constant-time; lockout check precedes it.
- [ ] Every `verifyPin` caller (unlock + 3 settings gates) enforces the limit.
- [ ] Biometric success resets; biometric available during PIN lockout.
- [ ] Wipe-after-N default OFF, gated by explicit backup acknowledgement, and the
  destructive wipe truly destroys the seed (+ privacy data if Task 4).
- [ ] Forward-compat: `Success` cleanly extends to `REAL|DURESS` for Phase 2.2.

## Notes for the reviewer (operator)
Confirm spec Open-decisions A–E; they change: WIPE_THRESHOLD (A), the cooldown curve
(B), whether the elapsedRealtime hardening is in scope (C), whether Task 4 exists (D),
and the biometric-during-lockout behavior (E). Everything above assumes the
recommended defaults.
