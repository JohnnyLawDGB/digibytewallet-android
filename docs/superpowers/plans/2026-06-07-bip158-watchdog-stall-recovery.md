# BIP158 Watchdog Stall-Recovery + Banner Accuracy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the BIP158 watchdog treat a transient block-header stall as recoverable (drop to bloom, then switch back to compact filters once caught up — silent, capped at 3 per session) and reserve the persistent privacy banner for genuine filter failures, with corrected banner text.

**Architecture:** All logic lives in `SyncService.startBip158Watchdog` (the polling coroutine) plus a one-line text change in `WalletScreen.kt`. No native/JNI changes — reuses existing `setSyncMode`/`getSyncMode`/`fallbackToBloom`. `fallbackToBloom()` preserves the filter chain, so switching back resumes cfheaders from the saved cfTip.

**Tech Stack:** Kotlin, Android service + Jetpack Compose. Spec: `docs/superpowers/specs/2026-06-07-bip158-watchdog-stall-recovery-design.md`.

**Testing reality:** `SyncService` has no unit-test harness (it drives `NativeBridge`/coroutines against a live peer manager). The watchdog state machine is verified by **compile + code review + on-device observation**. Block stalls are caused by nondeterministic peer churn, so the recovery path is observed opportunistically; the cap/banner path can be forced by temporarily lowering `MAX_BLOCK_STALL_RECOVERIES` (Task 3 notes this). This matches how the v3.6.0 watchdog/re-anchor wiring was validated.

---

## File Structure

- `app/src/main/java/io/digibyte/service/SyncService.kt` — watchdog state machine: new constant, two coroutine-local vars, top-of-loop bloom-recovery handler, block-stall cap branch.
- `app/src/main/java/io/digibyte/ui/wallet/WalletScreen.kt` — corrected bloom-fallback banner body text (one string).

Both files already exist and are large; only the watchdog coroutine and one banner string change. No restructuring.

---

### Task 1: Watchdog stall-recovery state machine (`SyncService.kt`)

**Files:**
- Modify: `app/src/main/java/io/digibyte/service/SyncService.kt`

All four edits below are in `startBip158Watchdog` / its companion. Apply them in order. Verify each `old_string` anchor exists exactly before replacing; if any differs, STOP and report NEEDS_CONTEXT.

- [ ] **Step 1: Add the `MAX_BLOCK_STALL_RECOVERIES` constant**

Find this exact block in the companion object (near `BLOCK_CATCHUP_GRACE`):

```kotlin
        private const val BLOCK_CATCHUP_GRACE = 50L
```

Replace it with:

```kotlin
        private const val BLOCK_CATCHUP_GRACE = 50L

        /** Max transient block-stall → bloom → back-to-filters recovery cycles per
         *  session. After this many, stay on bloom and surface the privacy banner. */
        private const val MAX_BLOCK_STALL_RECOVERIES = 3
```

- [ ] **Step 2: Add the two watchdog-coroutine-local state vars**

Find this exact block (just before the `while (true) {` loop):

```kotlin
            // Re-anchor recovery is attempted at most once per sync session so a
            // poll landing before the first re-anchored cfheaders append (cfTip
            // not yet jumped) can't re-fire it every poll.
            var reanchoredThisSession = false
            while (true) {
```

Replace it with:

```kotlin
            // Re-anchor recovery is attempted at most once per sync session so a
            // poll landing before the first re-anchored cfheaders append (cfTip
            // not yet jumped) can't re-fire it every poll.
            var reanchoredThisSession = false
            // Transient block-stall recovery: drop to bloom so any peer extends the
            // chain, then switch back to compact filters once caught up. Capped per
            // session so a flaky connection can't flap bloom↔filters forever.
            var blockStallRecoveries = 0
            var bloomRecoveryActive = false
            while (true) {
```

- [ ] **Step 3: Replace the top-of-loop BLOOM_ONLY handler with the recovery-aware version**

Find this exact block (right after the `getSyncMode()` call):

```kotlin
                if (mode == NativeBridge.SyncMode.BLOOM_ONLY) {
                    android.util.Log.i("SyncService",
                        "BIP158 watchdog: mode is BLOOM_ONLY, stopping poll")
                    return@launch
                }
```

Replace it with:

```kotlin
                if (mode == NativeBridge.SyncMode.BLOOM_ONLY) {
                    if (bloomRecoveryActive) {
                        // We dropped to bloom to recover a block-header stall. Wait for
                        // block sync to catch up to the network tip, then switch back to
                        // compact filters and resume monitoring (cfTip is preserved
                        // across the switch — fallbackToBloom never freed the chain).
                        val bTip = try { NativeBridge.getLastBlockHeight() } catch (_: Throwable) { 0L }
                        val bEst = try { NativeBridge.getEstimatedBlockHeight() } catch (_: Throwable) { 0L }
                        if (bEst > 0L && bTip >= bEst - BLOCK_CATCHUP_GRACE) {
                            try {
                                NativeBridge.setSyncMode(NativeBridge.SyncMode.COMPACT_FILTERS_ONLY)
                            } catch (t: Throwable) {
                                android.util.Log.e("SyncService", "BIP158 watchdog: switch-back to filters threw", t)
                            }
                            bloomRecoveryActive = false
                            lastBlockTip = bTip
                            lastBlockProgressMs = System.currentTimeMillis()
                            android.util.Log.i("SyncService",
                                "BIP158 watchdog: block sync caught up via bloom — switching back to " +
                                "compact filters (recovery $blockStallRecoveries/$MAX_BLOCK_STALL_RECOVERIES)")
                        }
                        continue   // switched back, or still catching up — keep polling
                    }
                    android.util.Log.i("SyncService",
                        "BIP158 watchdog: mode is BLOOM_ONLY, stopping poll")
                    return@launch
                }
```

- [ ] **Step 4: Replace the block-stall fallback with the cap-3 recovery branch**

Find this exact block (inside `if (!blocksCaughtUp) { ... }`, the `stalledMs >= timeout` action):

```kotlin
                    android.util.Log.w("SyncService",
                        "BIP158 watchdog: block sync stalled below tip for ${stalledMs}ms " +
                        "(blockTip=$blockTip, est=$estHeight, cfTip stuck at $cfTipNow) " +
                        "— falling back to bloom")
                    try {
                        NativeBridge.fallbackToBloom()
                        _bloomFallbackActive.value = true
                    } catch (t: Throwable) {
                        android.util.Log.e("SyncService", "BIP158 watchdog: fallback failed", t)
                    }
                    return@launch
```

Replace it with:

```kotlin
                    if (blockStallRecoveries < MAX_BLOCK_STALL_RECOVERIES) {
                        blockStallRecoveries++
                        android.util.Log.w("SyncService",
                            "BIP158 watchdog: block sync stalled below tip for ${stalledMs}ms " +
                            "(blockTip=$blockTip, est=$estHeight) — bloom recovery " +
                            "$blockStallRecoveries/$MAX_BLOCK_STALL_RECOVERIES, will retry filters once caught up")
                        try { NativeBridge.fallbackToBloom() } catch (t: Throwable) {
                            android.util.Log.e("SyncService", "BIP158 watchdog: fallback failed", t)
                        }
                        bloomRecoveryActive = true
                        lastBlockProgressMs = System.currentTimeMillis()   // fresh stall window
                        continue   // NO banner — recover via bloom, switch back once caught up
                    }
                    android.util.Log.w("SyncService",
                        "BIP158 watchdog: block sync stalled $blockStallRecoveries times — " +
                        "staying on bloom for the session (blockTip=$blockTip, est=$estHeight)")
                    try {
                        NativeBridge.fallbackToBloom()
                        _bloomFallbackActive.value = true
                    } catch (t: Throwable) {
                        android.util.Log.e("SyncService", "BIP158 watchdog: fallback failed", t)
                    }
                    return@launch
```

(The caught-up-but-cfheaders-stuck branch immediately below — the re-anchor + genuine-failure path — is left UNCHANGED.)

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :app:compileMainnetDebugKotlin`
Expected: `BUILD SUCCESSFUL`. (Fixes a missing-reference or unresolved-symbol error if any anchor was mis-pasted.)

- [ ] **Step 6: Commit**

```bash
cd /home/polloloco/digibytewallet-android
git add app/src/main/java/io/digibyte/service/SyncService.kt
git commit -m "feat(bip158): watchdog recovers transient block-stalls instead of staying on bloom

A block-header stall (peer churn dropped the download peer) is connectivity, not
a filter failure — but the watchdog treated it like one: fell back to bloom, set
the privacy banner, and stayed degraded for the session. Now it drops to bloom to
let any peer extend the chain, then switches back to compact filters once block
sync catches up (cfTip is preserved; setSyncMode resumes cfheaders). Capped at 3
silent recoveries per session; the 4th stays on bloom and surfaces the banner.
The genuine caught-up-but-cfheaders-stuck path is unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Correct the bloom-fallback banner text (`WalletScreen.kt`)

**Files:**
- Modify: `app/src/main/java/io/digibyte/ui/wallet/WalletScreen.kt` (the `bloomFallbackActive` banner, ~line 722)

Since the banner's `bloomFallbackActive` flag is now set only in terminal cases (cap-exhausted or genuine filter failure), one accurate message covers both — and the old "filter peers were unreachable" wording (wrong for block-stalls) is removed.

- [ ] **Step 1: Replace the banner body text**

Find this exact block:

```kotlin
                text = "Block filter peers were unreachable, so the wallet fell back " +
                       "to bloom filters. Your addresses are visible to peers until " +
                       "you restart the app.",
```

Replace it with:

```kotlin
                text = "Compact-filter (private) sync was unavailable this session, so " +
                       "the wallet fell back to bloom filters — your addresses are " +
                       "visible to peers. Restart the app to retry private sync.",
```

(Leave the title "Privacy degraded for this session" and all styling unchanged.)

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileMainnetDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/digibyte/ui/wallet/WalletScreen.kt
git commit -m "fix(bip158): accurate bloom-fallback banner text

The banner is now shown only for genuine compact-filter failures (transient
block-stalls recover silently), so drop the inaccurate \"filter peers were
unreachable\" wording for a message that's correct in every case it appears.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Build, install, and verify

**Files:** none (verification). Both devices currently run a 3.6.0 debug build; this installs the updated debug build in place (preserves wallets).

- [ ] **Step 1: Build the debug APK**

Run: `./gradlew :app:assembleMainnetDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install in place on the Note8 (wallet preserved)**

```bash
adb -s ce061716640b191c017e install -r app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk
```
Expected: `Success`. (Same debug signing key → no wipe.)

- [ ] **Step 3: Launch and watch the watchdog logs**

```bash
D=ce061716640b191c017e
adb -s $D shell monkey -p io.digibyte -c android.intent.category.LAUNCHER 1
adb -s $D shell input keyevent KEYCODE_WAKEUP
# (unlock with PIN on device)
adb -s $D logcat -c
timeout 180 adb -s $D logcat SyncService:* '*:S' > /tmp/dgb_stallrec.log
grep -iE "bloom recovery|switching back to compact filters|staying on bloom|stopping poll|healthy" /tmp/dgb_stallrec.log
```

Expected behaviors to confirm (opportunistic — depends on whether a stall occurs):
- If a block-stall occurs: a `bloom recovery N/3` line with NO `_bloomFallbackActive` banner, followed (once block sync catches up) by `switching back to compact filters`.
- A healthy session ends with `watchdog: healthy` and never shows the banner.
- The banner (if it appears at all) only follows `staying on bloom for the session` (cap) or the caught-up-but-stuck genuine-failure line.

- [ ] **Step 4: (Optional) Force the cap path for a deterministic check**

If you want to verify the cap → banner path deterministically without waiting for 4 real stalls, temporarily set `MAX_BLOCK_STALL_RECOVERIES = 0` in a throwaway build, install, and confirm the first block-stall immediately shows the corrected banner; then revert. Do NOT commit the lowered cap.

---

## Self-Review notes

- **Spec coverage:** new constant (Task 1 Step 1) ✓; state vars (Step 2) ✓; top-of-loop recovery + switch-back (Step 3) ✓; block-stall cap branch (Step 4) ✓; genuine-failure unchanged ✓; banner text (Task 2) ✓; testing (Task 3) ✓.
- **Type consistency:** `blockStallRecoveries: Int`, `bloomRecoveryActive: Boolean`, `MAX_BLOCK_STALL_RECOVERIES: Int`, `bloomRecoveryActive`/`blockStallRecoveries` referenced identically across Steps 2–4. `NativeBridge.SyncMode.{BLOOM_ONLY,COMPACT_FILTERS_ONLY}` are `Int` constants; `setSyncMode(Int)`/`getSyncMode():Int` — comparisons and the switch-back call are all `Int`, matching existing usage (SyncService lines 393/396/636). The top-of-loop handler uses local `bTip`/`bEst` to avoid shadowing the later per-poll `blockTip`/`estHeight`.
- **No placeholders:** every step shows complete code and exact commands.

## Release

After Tasks 1–3 pass, cut **v3.6.1** via the **release-prep** ritual (version bump 3.6.0→3.6.1 / versionCode 30061→30062, commit, tag `v3.6.1`, push, watch CI, verify the signed APK). Not part of this plan's commits.
