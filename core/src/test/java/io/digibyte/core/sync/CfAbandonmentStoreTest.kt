package io.digibyte.core.sync

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paced-convoy fetch, spec Part E — the `abandonedBandRecovered` signal that makes
 * abandonment non-terminal (operator GATE 3).
 *
 * The property under test is a safety coupling, not bookkeeping: the B2 valve is
 * allowed to abandon a height it could not get served ONLY because the resulting
 * band stays surfaced until a recovery covers it, and stops being surfaced the
 * moment one does.
 */
class CfAbandonmentStoreTest {

    // ── band derivation (pure) ────────────────────────────────────────────────

    @Test fun firstAbandonment_withPendingHint_recordsAnExactRange() {
        val b = nextAbandonedBand(existing = null, abandonedBelow = 23_900_125L, lowHint = 23_900_120L)!!
        assertEquals(23_900_120L, b.low)
        assertEquals(23_900_124L, b.high)   // abandonedBelow - 1, exact
        assertTrue(b.lowKnown)
    }

    /** No hint (the app was not running while the valve decided) → the bottom is
     *  UNKNOWN. It must not be invented; the UI degrades to "blocks below Y". */
    @Test fun firstAbandonment_withoutHint_marksLowUnknown() {
        val b = nextAbandonedBand(existing = null, abandonedBelow = 23_900_125L, lowHint = 0L)!!
        assertFalse(b.lowKnown)
        assertEquals(23_900_124L, b.high)
    }

    /** A hint above the watermark is nonsense (stale poll) — don't trust it. */
    @Test fun hintAboveWatermark_isRejected() {
        val b = nextAbandonedBand(existing = null, abandonedBelow = 1_000L, lowHint = 5_000L)!!
        assertFalse(b.lowKnown)
    }

    @Test fun laterAbandonment_extendsUpward_keepingTheOriginalBottom() {
        val first = nextAbandonedBand(null, 1_000L, 900L)!!
        val second = nextAbandonedBand(first, 1_500L, 1_400L)!!
        assertEquals(900L, second.low)      // one banner covers the whole gap
        assertEquals(1_499L, second.high)
        assertTrue(second.lowKnown)
    }

    @Test fun noAbandonment_leavesRecordUntouched() {
        assertNull(nextAbandonedBand(existing = null, abandonedBelow = 0L, lowHint = 5L))
        val existing = AbandonedBand(10L, 20L, true)
        assertEquals(existing, nextAbandonedBand(existing, abandonedBelow = 0L, lowHint = 5L))
        // A re-read of the SAME watermark must not churn the record (or reset
        // `recovered` on every poll, which would make the banner un-clearable).
        assertEquals(existing, nextAbandonedBand(existing, abandonedBelow = 21L, lowHint = 5L))
    }

    // ── the persisted recovered signal ────────────────────────────────────────

    @Test fun noBand_meansNothingToSurface() {
        val ctx = fakeContext()
        assertNull(CfAbandonmentStore.band(ctx))
        assertNull(CfAbandonmentStore.unrecoveredBand(ctx))
        // markRecovered on a healthy wallet is a no-op — no spurious writes.
        assertFalse(CfAbandonmentStore.markRecovered(ctx))
    }

    @Test fun abandonment_surfaces_untilRecovered_thenClears() {
        val ctx = fakeContext()
        assertTrue(CfAbandonmentStore.noteAbandonment(ctx, abandonedBelow = 1_000L, lowHint = 900L))

        val surfaced = CfAbandonmentStore.unrecoveredBand(ctx)!!
        assertEquals(900L, surfaced.low)
        assertEquals(999L, surfaced.high)

        // Recovery covered it → the banner clears and Synced may proceed.
        assertTrue(CfAbandonmentStore.markRecovered(ctx))
        assertNull(CfAbandonmentStore.unrecoveredBand(ctx))
        // The band itself is retained (history), only the surfacing stops.
        assertEquals(999L, CfAbandonmentStore.band(ctx)!!.high)
    }

    /** A NEW abandonment after a recovery is a NEW gap — it must re-surface. */
    @Test fun abandonmentAfterRecovery_reSurfaces() {
        val ctx = fakeContext()
        CfAbandonmentStore.noteAbandonment(ctx, 1_000L, 900L)
        CfAbandonmentStore.markRecovered(ctx)
        assertNull(CfAbandonmentStore.unrecoveredBand(ctx))

        assertTrue(CfAbandonmentStore.noteAbandonment(ctx, 2_000L, 1_900L))
        val again = CfAbandonmentStore.unrecoveredBand(ctx)!!
        assertEquals(1_999L, again.high)
        assertEquals(900L, again.low)       // still the original bottom
        assertFalse(CfAbandonmentStore.isRecovered(ctx))
    }

    /** Re-polling the same watermark must NOT reset `recovered` — otherwise the
     *  banner would come back 5 seconds after every successful reconcile and
     *  recovery would be terminal after all. */
    @Test fun rePollingTheSameWatermark_doesNotUnRecover() {
        val ctx = fakeContext()
        CfAbandonmentStore.noteAbandonment(ctx, 1_000L, 900L)
        CfAbandonmentStore.markRecovered(ctx)
        repeat(5) { assertFalse(CfAbandonmentStore.noteAbandonment(ctx, 1_000L, 900L)) }
        assertTrue(CfAbandonmentStore.isRecovered(ctx))
        assertNull(CfAbandonmentStore.unrecoveredBand(ctx))
    }

    // ── recovery by the ordinary CF scan (watchdog-heal path) ────────────────
    //
    // SyncService's frozen-CF recovery (:1345), corrupt-chain heal (:1421) and
    // post-timeout re-anchor (:1477) each delete CfScanLedgerStore and re-Init the
    // native ledger at the floor. That drops `abandonedBelow` back to 0 and lets the
    // ORDINARY CF scan re-cover the band — with no reconcile and no rescan, so
    // nothing on the reconcile path is ever called. Without an explicit coverage
    // check the banner would nag and Synced would be withheld FOREVER over a gap
    // that no longer exists.
    //
    // The proof is two conjuncts, and both are load-bearing: `abandonedBelow == 0`
    // alone is not coverage (the re-anchor floor can sit ABOVE the band, in which
    // case those heights are still unscanned and a blanket clear would be exactly
    // the silent loss this whole mechanism exists to prevent).
    //
    // ...and (fix wave R1) "frontier > high" alone is not the other conjunct either:
    // it is TWO-PHASE. See the long note on scanFrontierNeverSeenInsideTheBand below.

    @Test fun scanPassedTheBandAfterReInit_countsAsRecovery() {
        val ctx = fakeContext()
        CfAbandonmentStore.noteAbandonment(ctx, abandonedBelow = 1_000L, lowHint = 900L)
        // The heal re-Init'd BELOW the band and the scan is climbing THROUGH it —
        // Phase 1. This is the observation a legitimate auto-clear always produces
        // and the reconnect-at-the-same-floor case never can.
        assertFalse(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 950L))
        assertTrue(CfAbandonmentStore.sawFrontierInBand(ctx))
        assertNotNull("still surfaced — the scan is only part-way through", CfAbandonmentStore.unrecoveredBand(ctx))

        // Phase 2: the scan has now climbed past the band top (999). MUST recover —
        // this is the legitimate auto-clear and R1 must not regress it.
        assertTrue(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 1_000L))
        assertNull(CfAbandonmentStore.unrecoveredBand(ctx))
        assertTrue(CfAbandonmentStore.isRecovered(ctx))
    }

    /**
     * R1, THE REQUIRED FIX. A band surfaced by the RESUME path has `high == floor-1`,
     * so ANY later re-`Init` at that SAME floor reads back as `scanFrontier > high` on
     * the very next poll — the exact shape the "a re-anchor floor above the band must
     * NOT clear it" guard was written to reject, arriving through the guard's own
     * happy path. Concretely: a deep restore surfaces `[S..A-1]` and banners → a
     * network blip → `onAvailable` → `forceReconnect()` + `startSync()` → fresh
     * manager → sticky re-arm clamps to `A` and re-`Init`s there → `abandonedBelow=0`,
     * frontier `= A` → banner gone, Synced allowed, ~10,000 heights never scanned.
     *
     * Without a prior in-band observation this MUST NOT recover. (Fails pre-fix.)
     */
    @Test fun scanFrontierNeverSeenInsideTheBand_isNotRecovery() {
        val ctx = fakeContext()
        // A resume-surfaced band: top == floor-1, i.e. floor == 1_000.
        CfAbandonmentStore.noteAbandonment(ctx, abandonedBelow = 1_000L, lowHint = 900L)
        assertFalse("nothing observed yet", CfAbandonmentStore.sawFrontierInBand(ctx))

        // The reconnect: fresh manager, re-Init at the SAME floor. abandonedBelow is
        // back to 0 and the frontier reads as the floor — one height above the band.
        repeat(5) {
            assertFalse(
                "a floor landing AT the top of the band is not coverage",
                CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 1_000L),
            )
        }
        assertNotNull("the band must still be surfaced", CfAbandonmentStore.unrecoveredBand(ctx))
        assertFalse(CfAbandonmentStore.isRecovered(ctx))

        // And it stays rejected however far the scan then climbs — those heights were
        // never covered, so no amount of progress ABOVE them is evidence about them.
        assertFalse(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 500_000L))
        assertNotNull(CfAbandonmentStore.unrecoveredBand(ctx))
    }

    /** A failed native read (`getLowestNeededHeight()` → 0 via `getOrDefault`) is not
     *  an observation. Without this guard `0 <= high` would hand the reconnect case a
     *  free Phase-1 witness and re-open R1 through the error path. */
    @Test fun aZeroFrontier_isNotAnObservation() {
        val ctx = fakeContext()
        CfAbandonmentStore.noteAbandonment(ctx, abandonedBelow = 1_000L, lowHint = 900L)
        assertFalse(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 0L))
        assertFalse("a 0 frontier must not witness anything", CfAbandonmentStore.sawFrontierInBand(ctx))
        assertFalse(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 1_000L))
        assertNotNull(CfAbandonmentStore.unrecoveredBand(ctx))
    }

    /** The witness must survive process death: Phase 1 in one session, Phase 2 in the
     *  next. (The store is pure SharedPreferences, so a re-read through a fresh
     *  accessor on the same backing map is the honest model of that.) */
    @Test fun theInBandObservation_persistsAcrossPhases() {
        val ctx = fakeContext()
        CfAbandonmentStore.noteAbandonment(ctx, abandonedBelow = 1_000L, lowHint = 900L)
        CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 900L)
        assertTrue(CfAbandonmentStore.sawFrontierInBand(ctx))
        // ...process dies, app relaunches, the scan finishes climbing.
        assertTrue(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 1_000L))
        assertNull(CfAbandonmentStore.unrecoveredBand(ctx))
    }

    /** A NEW abandonment resets the witness: the observation was about the OLD
     *  geometry, and the extended band's added heights were never observed at all. */
    @Test fun aNewAbandonment_resetsTheInBandObservation() {
        val ctx = fakeContext()
        CfAbandonmentStore.noteAbandonment(ctx, abandonedBelow = 1_000L, lowHint = 900L)
        CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 950L)
        assertTrue(CfAbandonmentStore.sawFrontierInBand(ctx))

        // The valve abandons more; the band now extends up to 1_499.
        assertTrue(CfAbandonmentStore.noteAbandonment(ctx, abandonedBelow = 1_500L, lowHint = 1_400L))
        assertFalse(CfAbandonmentStore.sawFrontierInBand(ctx))
        assertFalse(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 1_500L))
        assertNotNull(CfAbandonmentStore.unrecoveredBand(ctx))
    }

    /** R1 must behave the same for a 1-height band (the shape an abrupt kill used to
     *  produce before R2 made it resolvable) as for a ~10,000-height one: the geometry
     *  differs, the rule does not. */
    @Test fun aOneHeightBand_stillNeedsTheInBandObservation() {
        val ctx = fakeContext()
        // band == [1_000 .. 1_000]
        CfAbandonmentStore.noteAbandonment(ctx, abandonedBelow = 1_001L, lowHint = 1_000L)
        val band = CfAbandonmentStore.unrecoveredBand(ctx)!!
        assertEquals(1_000L, band.low)
        assertEquals(1_000L, band.high)

        // Re-Init at the same floor (1_001) — nothing observed, nothing cleared.
        assertFalse(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 1_001L))
        assertNotNull(CfAbandonmentStore.unrecoveredBand(ctx))

        // A real heal re-Inits below it; the single poll that lands on the one height
        // in the band is the witness, and the next one clears.
        assertFalse(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 1_000L))
        assertTrue(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 1_001L))
        assertNull(CfAbandonmentStore.unrecoveredBand(ctx))
    }

    @Test fun watermarkClearedButScanNotYetPastTheBand_isNotRecovery() {
        val ctx = fakeContext()
        CfAbandonmentStore.noteAbandonment(ctx, abandonedBelow = 1_000L, lowHint = 900L)
        // Re-anchored at a floor ABOVE the band: those heights are still unscanned.
        assertFalse(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 500L))
        assertNotNull(CfAbandonmentStore.unrecoveredBand(ctx))
        // Exactly at the band top is NOT past it — LowestNeededHeight is the lowest
        // height still NEEDED, so it must exceed `high` for `high` to be scanned.
        assertFalse(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 999L))
        assertNotNull(CfAbandonmentStore.unrecoveredBand(ctx))
    }

    /** While the watermark still stands, the hard floor still clamps every CF
     *  request — a high scan frontier there is the floor itself, not coverage. */
    @Test fun watermarkStillStanding_isNeverRecovery() {
        val ctx = fakeContext()
        CfAbandonmentStore.noteAbandonment(ctx, abandonedBelow = 1_000L, lowHint = 900L)
        assertFalse(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 1_000L, scanFrontier = 9_999L))
        assertNotNull(CfAbandonmentStore.unrecoveredBand(ctx))
    }

    /** No band, or an already-recovered one → nothing to do, no writes. */
    @Test fun scanCoverage_isANoOpWithoutAnUnrecoveredBand() {
        val ctx = fakeContext()
        assertFalse(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 9_999L))
        CfAbandonmentStore.noteAbandonment(ctx, 1_000L, 900L)
        CfAbandonmentStore.markRecovered(ctx)
        assertFalse(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 9_999L))
        // ...and a poll landing "inside" an already-recovered band writes no witness
        // either, so a LATER new abandonment cannot inherit one.
        assertFalse(CfAbandonmentStore.noteScanCoverage(ctx, abandonedBelow = 0L, scanFrontier = 950L))
        assertFalse(CfAbandonmentStore.sawFrontierInBand(ctx))
    }

    /** The full rescan re-`Init`s the native ledger at abandonedBelow = 0, so the
     *  band it described is gone — not "recovered", GONE. */
    @Test fun clear_forgetsTheBandEntirely() {
        val ctx = fakeContext()
        CfAbandonmentStore.noteAbandonment(ctx, 1_000L, 900L)
        CfAbandonmentStore.clear(ctx)
        assertNull(CfAbandonmentStore.band(ctx))
        assertNull(CfAbandonmentStore.unrecoveredBand(ctx))
        assertFalse(CfAbandonmentStore.isRecovered(ctx))
    }
}

/** Context whose SharedPreferences are real in-memory maps (per file name). */
internal fun fakeContext(): Context {
    val stores = HashMap<String, FakeSharedPreferences>()
    val ctx = mockk<Context>(relaxed = true)
    every { ctx.getSharedPreferences(any(), any()) } answers {
        stores.getOrPut(firstArg()) { FakeSharedPreferences() }
    }
    return ctx
}

/** Minimal in-memory SharedPreferences — enough for the stores under test. */
internal class FakeSharedPreferences : SharedPreferences {
    val map = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?) = map[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)
    override fun getInt(key: String?, defValue: Int) = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = map[key] as? Boolean ?: defValue
    override fun contains(key: String?) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clearAll = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = REMOVE }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean { flush(); return true }
        override fun apply() { flush() }
        private fun flush() {
            if (clearAll) map.clear()
            pending.forEach { (k, v) -> if (v === REMOVE) map.remove(k) else map[k] = v }
            pending.clear()
            clearAll = false
        }
    }

    private companion object { val REMOVE = Any() }
}
