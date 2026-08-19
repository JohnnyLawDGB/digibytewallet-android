package io.digibyte.core.sync

import android.content.Context
import io.digibyte.core.networkSuffix

/**
 * A contiguous range of block heights the B2 abandonment valve gave up on.
 *
 * [high] is exact — it is `abandonedBelow - 1`, read straight off the native
 * monotonic watermark. [low] is the best-known bottom of the band and is
 * trustworthy only when [lowKnown]; see [nextAbandonedBand] for why.
 */
data class AbandonedBand(
    val low: Long,
    val high: Long,
    /** False when the bottom of the band could not be observed (the app was not
     *  running while the valve decided). Callers must then say "blocks below
     *  [high] + 1" rather than inventing a lower bound. */
    val lowKnown: Boolean,
)

/**
 * Folds a freshly-observed `abandonedBelow` watermark into the recorded band.
 *
 * **Why this is derived by observation rather than read from native.** The native
 * side exposes `getAbandonedBelow()` (exact) and `getAbandonedCount()` — but the
 * latter is `abandonedBelow - ledger.start`, i.e. the size of the whole scanned
 * range BELOW the watermark, *not* the number of heights actually abandoned. After
 * abandoning a single deep height it reads as the entire scan range, so rendering
 * it as "N blocks abandoned" would be a straight lie. There is no native accessor
 * for the band's lower edge (`BRCFScanLedgerAbandonGaveUpBelow` returns [lo..hi] to
 * its caller for the WARN log and keeps nothing), so the bottom is captured from
 * the Kotlin side instead:
 *
 *  - `lowHint` is the CF scan frontier (`getLowestNeededHeight()`) observed while
 *    `getConvoyAbandonmentPending() > 0` — i.e. while the valve was mid-decision on
 *    the hole that PINS the frontier. The valve holds that frontier across
 *    CF_CONVOY_REARM_MAX re-arm cycles (~22 min) before it abandons, so a poll at
 *    any normal cadence sees it. That pinned height IS the bottom of the band.
 *  - If the process was not running through that window, `lowHint` is absent and
 *    the band records [lowKnown] = false: the UI degrades to "blocks below Y",
 *    which is true, rather than substituting the ledger start (which would claim
 *    the wallet's entire history was abandoned).
 *
 * A later abandonment EXTENDS the existing band upward and keeps the original
 * bottom, so one banner covers everything still un-recovered.
 */
internal fun nextAbandonedBand(
    existing: AbandonedBand?,
    abandonedBelow: Long,
    lowHint: Long,
): AbandonedBand? {
    if (abandonedBelow <= 0L) return existing
    val high = abandonedBelow - 1L
    if (high < 0L) return existing
    if (existing != null && high <= existing.high) return existing
    if (existing != null) return existing.copy(high = high)
    return if (lowHint in 1L..high) AbandonedBand(lowHint, high, lowKnown = true)
    else AbandonedBand(0L, high, lowKnown = false)
}

/**
 * Persistence for the abandoned compact-filter band and its **`abandonedBandRecovered`**
 * signal (paced-convoy fetch, spec Part E — operator GATE 3).
 *
 * The B2 valve may abandon a height that was in fact servable: it can only prove
 * refusal by the peers it is CURRENTLY connected to, never fleet-wide, and under
 * fleet saturation the one node that would serve a height may never be consulted.
 * That residual is acceptable ONLY because every abandoned band stays visible and
 * recoverable — which is what this store exists to guarantee.
 *
 * **Why a separate signal rather than `abandonedBelow > 0`.** `abandonedBelow` is a
 * monotonic hard floor inside the native ledger that clamps every CF request, and
 * NEITHER recovery path clears it in place:
 *  - a node reconcile is CF-independent and restores the funds without touching it;
 *  - a full rescan only clears it by re-`Init`ing the ledger from scratch.
 * So a Synced-gate or banner keyed on the watermark itself would be TERMINAL: after
 * a successful reconcile the funds are back but the wallet stays permanently
 * non-Synced and permanently nagging. The recovered flag here is what makes
 * abandonment *skipped-and-surfaced-and-recoverable* instead.
 *
 * Set by: [noteAbandonment] (records/extends a band and CLEARS recovered — a new
 * abandonment is a new gap, so its Phase-1 witness resets too). Cleared by:
 * [markRecovered], called on a successful `ChainReconciliationService.reconcile()`
 * whose address-history pass covered the owned set, and by the two-phase
 * [noteScanCoverage] when the ORDINARY scan is observed climbing THROUGH the band.
 * Wiped entirely by: [clear], called from
 * `WalletManager.rebuildFromChainRescan()` alongside `CfScanLedgerStore.delete` —
 * the rescan re-`Init`s the native ledger at `abandonedBelow = 0`, so the band it
 * described no longer exists.
 */
object CfAbandonmentStore {
    private const val PREFS_BASE = "dgb_cf_abandonment"
    private const val KEY_LOW = "band_low"
    private const val KEY_HIGH = "band_high"
    private const val KEY_LOW_KNOWN = "band_low_known"
    private const val KEY_RECOVERED = "band_recovered"

    /**
     * Phase 1 of [noteScanCoverage]: the app has, at some point since this band was
     * recorded, actually OBSERVED the CF scan frontier sitting INSIDE the band with
     * the hard floor gone. Persisted, because the observation and the later
     * pass-the-top can be separated by a process death.
     */
    private const val KEY_SAW_IN_BAND = "band_saw_frontier_in_band"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_BASE + networkSuffix(ctx), Context.MODE_PRIVATE)

    /** The recorded band, recovered or not. Null when nothing was ever abandoned. */
    fun band(ctx: Context): AbandonedBand? {
        val p = prefs(ctx)
        val high = p.getLong(KEY_HIGH, -1L)
        if (high < 0L) return null
        return AbandonedBand(
            low = p.getLong(KEY_LOW, 0L),
            high = high,
            lowKnown = p.getBoolean(KEY_LOW_KNOWN, false),
        )
    }

    /** True once a recovery covered the recorded band. Meaningless without a band. */
    fun isRecovered(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_RECOVERED, false)

    /**
     * The band that still needs recovering — what the banner and the Synced gate
     * key on. Null when nothing was abandoned OR recovery already covered it.
     */
    fun unrecoveredBand(ctx: Context): AbandonedBand? =
        band(ctx)?.takeIf { !isRecovered(ctx) }

    /**
     * Fold a freshly-polled `abandonedBelow` into the record. `lowHint` is the CF
     * scan frontier last observed while the valve was mid-decision (0 if unknown).
     * Returns true iff the stored band changed — a NEW gap, so `recovered` resets.
     *
     * This funnel is cause-agnostic by design: the B2 abandonment valve and the
     * checkpoint re-anchor-budget-exhaustion never-brick path (native
     * `_BRPeerManagerSurfaceUnscannableLocked`, cfcheckpt-active-rejection task 6)
     * both raise `abandonedBelow` the same way, so both surface through the same
     * band/banner. Native carries a human-readable `why` for the latter, but it is
     * log-only today — no JNI accessor exposes it, so the banner's cause-agnostic
     * "couldn't be verified from the filter fleet" copy is what's shown either way,
     * and it reads correctly for a filter-checkpoint-verification failure too.
     * Surfacing the specific reason in the UI is an optional future follow-up that
     * would need new native → Kotlin plumbing; none was added here (YAGNI).
     */
    fun noteAbandonment(ctx: Context, abandonedBelow: Long, lowHint: Long): Boolean {
        val existing = band(ctx)
        val next = nextAbandonedBand(existing, abandonedBelow, lowHint) ?: return false
        if (next == existing) return false
        prefs(ctx).edit()
            .putLong(KEY_LOW, next.low)
            .putLong(KEY_HIGH, next.high)
            .putBoolean(KEY_LOW_KNOWN, next.lowKnown)
            .putBoolean(KEY_RECOVERED, false)     // a new gap is not recovered
            .putBoolean(KEY_SAW_IN_BAND, false)   // ...and nothing has been observed about it yet
            .apply()
        return true
    }

    /**
     * Phase-1 witness for [noteScanCoverage] — see [KEY_SAW_IN_BAND]. Exposed for the
     * store's own tests; production only ever reaches it through [noteScanCoverage].
     */
    internal fun sawFrontierInBand(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SAW_IN_BAND, false)

    /**
     * Record that a recovery covered the abandoned band — the `abandonedBandRecovered`
     * signal. The banner clears and Synced may proceed. No-op (returns false) when
     * there is no band to recover, so a routine reconcile on a healthy wallet writes
     * nothing.
     */
    fun markRecovered(ctx: Context): Boolean {
        if (band(ctx) == null) return false
        if (isRecovered(ctx)) return false
        prefs(ctx).edit().putBoolean(KEY_RECOVERED, true).apply()
        return true
    }

    /**
     * The ORDINARY CF scan re-covered the band — the third recovery path, and the
     * only one nobody explicitly triggers.
     *
     * `SyncService`'s frozen-CF recovery (`:1345`), corrupt-filter-chain heal
     * (`:1421`) and post-timeout re-anchor (`:1477`) each `CfScanLedgerStore.delete()`
     * and recreate the manager, so native re-`Init`s the ledger at the floor and
     * `abandonedBelow` genuinely returns to 0. The hard floor is gone, the scan
     * descends through the abandoned heights again, and the gap closes — with no
     * reconcile and no rescan, so nothing on either of those paths is ever called.
     * Without this check the banner would nag and "Synced" would be withheld
     * FOREVER over a gap that no longer exists.
     *
     * **Both conjuncts are load-bearing.** `abandonedBelow == 0` alone is NOT
     * coverage: the re-anchor floor can land ABOVE the band, in which case those
     * heights are still unscanned and clearing the surfacing would be precisely the
     * silent loss this mechanism exists to prevent. And a high `scanFrontier` while
     * the watermark still stands is just the hard floor reading back at us, not
     * progress. So both must hold: the floor is gone AND the scan frontier has moved
     * PAST the top of the band.
     *
     * **...and "frontier > high" ALONE IS NOT A PASS — it is TWO-PHASE (fix wave R1).**
     * The two conjuncts above were sufficient only for VALVE-shaped bands, whose top
     * sits at `frontier - 1` while every re-`Init` floor lands ~144 BELOW the frontier —
     * so a floor landing above the band really was the exceptional case the second
     * conjunct rejected. A band surfaced by the RESUME path INVERTS that geometry: its
     * top is exactly `floor - 1`, so ANY later re-`Init` at that same floor makes
     * `scanFrontier == floor > high` on the very next poll. Concretely: a deep restore
     * surfaces `[S..A-1]` and banners → a network blip fires `onAvailable` →
     * `forceReconnect()` + `startSync()` → fresh manager → the sticky re-arm clamps to
     * `A` and re-`Init`s there → `abandonedBelow = 0`, frontier `= A` → the next 5-second
     * poll would clear the banner, allow Synced, and leave ~CF_CONVOY_WINDOW heights
     * never scanned. A routine reconnect silently un-surfacing the band breaks the
     * GATE-1/GATE-3 coupling the whole abandonment valve rests on.
     *
     * So recovery additionally requires that the app has ACTUALLY OBSERVED the scan
     * frontier INSIDE the band while the floor was gone ([KEY_SAW_IN_BAND], persisted
     * across process death) BEFORE seeing it pass the top. That is precisely what
     * distinguishes the two cases: a genuine heal re-`Init`s BELOW the band and the
     * scan CLIMBS THROUGH it — so a poll lands on `frontier <= high` — whereas a floor
     * landing ABOVE the band never produces such an observation, ever.
     *
     * This cannot be done natively: the recreate FREES the manager, so the pre-recreate
     * frontier no longer exists in memory to compare against.
     *
     * A missed Phase-1 poll (a very narrow band healed faster than the 5s poll) fails
     * SAFE — the banner stays up and the explicit recovery paths (`markRecovered` on a
     * reconcile, [clear] on a rescan) still clear it. The opposite default would be a
     * silent balance under-report.
     *
     * `scanFrontier` is `getLowestNeededHeight()` — the lowest height still NEEDED —
     * so it must be strictly greater than [AbandonedBand.high] for `high` itself to
     * have been scanned.
     *
     * Returns true iff this call set the recovered signal.
     */
    fun noteScanCoverage(ctx: Context, abandonedBelow: Long, scanFrontier: Long): Boolean {
        val band = unrecoveredBand(ctx) ?: return false
        if (abandonedBelow != 0L) return false        // hard floor still clamping
        // A frontier of 0 is "not up yet" / a failed native read, NOT an observation.
        // Without this guard a JNI failure would satisfy Phase 1 for free (0 <= high
        // always) and hand the floor-lands-above-the-band case its witness.
        if (scanFrontier <= 0L) return false
        if (scanFrontier <= band.high) {
            // PHASE 1 — the scan is genuinely inside the band with the floor gone.
            if (!sawFrontierInBand(ctx)) {
                prefs(ctx).edit().putBoolean(KEY_SAW_IN_BAND, true).apply()
            }
            return false                              // scan hasn't passed the band yet
        }
        // PHASE 2 — past the top. Only counts if Phase 1 ever happened for THIS band.
        if (!sawFrontierInBand(ctx)) return false
        return markRecovered(ctx)
    }

    /**
     * Forget the band entirely. Used by the full rescan, which deletes the persisted
     * native ledger so `abandonedBelow` genuinely returns to 0 — there is no band
     * left to surface or recover. commit() (synchronous), NOT apply(): the rescan
     * caller kills the process immediately afterwards to force a clean reload, and an
     * async apply() would be dropped, resurrecting the banner for a band that no
     * longer exists.
     */
    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().commit()
    }
}
