# BIP158 Watchdog Stall-Recovery + Banner Accuracy — Design

**Date:** 2026-06-07
**Status:** Approved (design), pending implementation
**Component:** `app/` (SyncService.startBip158Watchdog, WalletScreen banner)
**Target release:** v3.6.1 (patch)
**Related:** `project_peer_pool_wipe_stuck_loop` (memory); v3.6.0 watchdog (catch-up tolerance), filter-chain re-anchor.

## Problem

The BIP158 watchdog falls back to bloom in two distinct situations but treats them identically — both call `fallbackToBloom()`, set the `bloomFallbackActive` banner, and `return@launch` (exit the watchdog for the session):

1. **Block-stall** (`SyncService.kt` `!blocksCaughtUp` branch): block *headers* stopped advancing while below the network tip. This is a connectivity hiccup (peer churn dropped the download peer) — **not** a compact-filter problem. Observed on-device: cfTip was already at the network tip (23631150) while blockTip froze at 23449344; the wallet fell back to bloom and showed the privacy banner for the rest of the session even though it recovered block sync seconds later.

2. **Genuine filter failure** (caught-up-but-cfheaders-stuck branch): block headers *are* at the tip but cfheaders can't progress even after a re-anchor attempt. Compact filters genuinely don't work this session.

The banner text — *"Block filter peers were unreachable, so the wallet fell back to bloom filters"* — is **wrong for case 1** (filters were fine; block sync stalled), and the wallet stays degraded for the whole session over a transient hiccup.

## Goal

Treat a transient block-stall as recoverable: fall back to bloom to extend the chain, then **switch back to compact filters** once block sync catches up — silently, no banner. Reserve the persistent privacy banner (with accurate wording) for genuine filter failures and for giving up after repeated stalls.

## Key decisions (settled)

1. **Differentiate the two fallback causes** and retry filters on the transient one.
2. **Cap at 3 silent recoveries**, then on the 4th block-stall stay on bloom + show the banner.
3. **Single Boolean banner state** (no reason enum) — since the banner is now set only in terminal cases (cap-exhausted, genuine failure), one accurate message covers both.
4. **No new JNI** — `setSyncMode`/`getSyncMode` already exist; `fallbackToBloom()` preserves the filter chain (verified), so switching back resumes cfheaders from the saved cfTip.

## Architecture

All changes are in `SyncService.startBip158Watchdog` (the polling coroutine) plus a one-line text fix in `WalletScreen.kt`. No native/JNI changes.

### New watchdog-coroutine-local state

Declared before the `while (true)` loop, alongside the existing `reanchoredThisSession`:

```kotlin
var blockStallRecoveries = 0      // transient block-stall recoveries used this session
var bloomRecoveryActive = false   // true while dropped to bloom to recover a stall, awaiting switch-back
```

Constant (companion object, near `BIP158_FALLBACK_TIMEOUT_MS`):

```kotlin
private const val MAX_BLOCK_STALL_RECOVERIES = 3
```

### Top-of-loop: handle the bloom-recovery wait

Today the loop starts:
```kotlin
val mode = NativeBridge.getSyncMode()
if (mode == NativeBridge.SyncMode.BLOOM_ONLY) { return@launch }
```
`fallbackToBloom()` sets `syncMode = BLOOM_ONLY`, so without this change the watchdog would exit the moment we drop to bloom for recovery. Replace the `BLOOM_ONLY` handling with:

```kotlin
if (mode == NativeBridge.SyncMode.BLOOM_ONLY) {
    if (bloomRecoveryActive) {
        // We dropped to bloom to recover a block-header stall. Wait for block
        // sync to catch up to the network tip, then switch back to compact
        // filters and resume monitoring (cfTip is preserved across the switch).
        val blockTip = try { NativeBridge.getLastBlockHeight() } catch (_: Throwable) { 0L }
        val est = try { NativeBridge.getEstimatedBlockHeight() } catch (_: Throwable) { 0L }
        val caughtUp = est > 0L && blockTip >= est - BLOCK_CATCHUP_GRACE
        if (caughtUp) {
            try { NativeBridge.setSyncMode(NativeBridge.SyncMode.COMPACT_FILTERS_ONLY) }
            catch (t: Throwable) { android.util.Log.e("SyncService", "switch-back to filters threw", t) }
            bloomRecoveryActive = false
            lastBlockTip = blockTip
            lastBlockProgressMs = System.currentTimeMillis()
            android.util.Log.i("SyncService",
                "BIP158 watchdog: block sync caught up via bloom — switching back to " +
                "compact filters (recovery $blockStallRecoveries/$MAX_BLOCK_STALL_RECOVERIES)")
        }
        continue   // either switched back, or still catching up — keep polling
    }
    android.util.Log.i("SyncService", "BIP158 watchdog: mode is BLOOM_ONLY, stopping poll")
    return@launch
}
```

`NativeBridge.SyncMode.{BLOOM_ONLY,COMPACT_FILTERS_ONLY}` are plain `Int` constants (`0`/`1`); `getSyncMode(): Int` and `setSyncMode(mode: Int)`. So `mode` is an `Int`, the comparisons are `Int == Int`, and `setSyncMode(NativeBridge.SyncMode.COMPACT_FILTERS_ONLY)` passes the `Int` constant — matching the existing `SyncService` usage at lines 393/396/636.

### Block-stall branch: cap-3 transient recovery

The current `!blocksCaughtUp` branch, on `stalledMs >= BIP158_FALLBACK_TIMEOUT_MS`, does `fallbackToBloom()` + banner + `return@launch`. Replace that terminal action with:

```kotlin
if (blockStallRecoveries < MAX_BLOCK_STALL_RECOVERIES) {
    blockStallRecoveries++
    android.util.Log.w("SyncService",
        "BIP158 watchdog: block sync stalled below tip (blockTip=$blockTip, est=$estHeight) " +
        "— bloom recovery $blockStallRecoveries/$MAX_BLOCK_STALL_RECOVERIES, will retry filters once caught up")
    try { NativeBridge.fallbackToBloom() } catch (t: Throwable) {
        android.util.Log.e("SyncService", "BIP158 watchdog: fallback failed", t)
    }
    bloomRecoveryActive = true
    lastBlockProgressMs = System.currentTimeMillis()   // reset stall timer
    continue   // NO banner — stay alive, switch back once caught up
}
// exhausted retries — stay on bloom for the session, surface the banner
android.util.Log.w("SyncService",
    "BIP158 watchdog: block sync stalled $blockStallRecoveries times — staying on bloom for the session")
try {
    NativeBridge.fallbackToBloom()
    _bloomFallbackActive.value = true
} catch (t: Throwable) {
    android.util.Log.e("SyncService", "BIP158 watchdog: fallback failed", t)
}
return@launch
```

### Genuine-filter-failure branch: unchanged

The caught-up-but-cfheaders-stuck branch (after the re-anchor attempt) keeps its current flow: `fallbackToBloom()` + `_bloomFallbackActive.value = true` + `return@launch`. This is a true degraded state.

### Banner text (`WalletScreen.kt`)

`bloomFallbackActive` is now set only in the two terminal spots (cap-exhausted, genuine failure), both of which mean "compact filters didn't hold this session." Replace the body text (currently "Block filter peers were unreachable…") with:

```kotlin
text = "Compact-filter (private) sync was unavailable this session, so the " +
       "wallet fell back to bloom filters — your addresses are visible to " +
       "peers. Restart the app to retry private sync.",
```

The title ("Privacy degraded for this session") and styling stay.

## Data flow

```
poll → syncMode?
  ├─ BLOOM_ONLY & bloomRecoveryActive:
  │     caught up? → setSyncMode(COMPACT_FILTERS); bloomRecoveryActive=false   (silent)
  │     not yet?   → keep waiting
  ├─ BLOOM_ONLY & !bloomRecoveryActive → exit (genuine/user bloom)
  └─ COMPACT_FILTERS:
        healthy (cfTip caught blockTip) → exit healthy
        cfTip advancing                  → keep polling
        block-stall (blocks not at tip, stalled):
            recoveries<3 → fallbackToBloom; bloomRecoveryActive=true; recoveries++  (silent, retry)
            else         → fallbackToBloom; banner; exit
        caught-up but cfheaders stuck (post re-anchor) → fallbackToBloom; banner; exit
```

## Error handling / edge cases

- **Switch-back resumes cfheaders:** `setSyncMode(COMPACT_FILTERS_ONLY)` flips the mode; the cfheaders driver fires on the next block-extend with a connected filter peer (filter-first makes one prompt). cfTip is preserved (fallback never freed the chain).
- **No tight flapping:** resetting `lastBlockProgressMs` on both fallback and switch-back gives a fresh stall window each cycle; the 3-cap bounds total flips.
- **Genuine failure still terminal:** if, after switching back, cfheaders genuinely can't progress, the caught-up-but-stuck branch shows the banner and exits as before.
- **User-set bloom unaffected:** if the user chose bloom-only mode, `bloomRecoveryActive` is false, so the top-of-loop exits immediately (unchanged behavior).
- **Bloom filter left loaded after switch-back:** harmless — cfheaders drives tx detection in compact mode; a stale bloom load on peers doesn't break it.

## Testing

- **On-device (primary):** with v3.6.1, observe a block-stall (the same scenario seen this session) and confirm: bloom recovery fires with NO banner, block sync catches up, the watchdog switches back to compact filters, and cfheaders resumes — banner never appears. Force/observe ≥4 stalls (or temporarily lower the cap) to confirm the 4th shows the corrected banner text.
- **Logs to assert:** `bloom recovery N/3`, `switching back to compact filters`, and absence of `_bloomFallbackActive` until cap/genuine-failure.
- **Regression:** a healthy wallet never enters recovery; a user-set BLOOM_ONLY wallet exits the watchdog immediately.

## Out of scope

- Reason-differentiated banner text (collapsed to one accurate message by design).
- Any native/JNI change (reuses existing `setSyncMode`/`getSyncMode`/`fallbackToBloom`).
- Changing the underlying cause of block-stalls (peer churn) — separate connectivity concern.
