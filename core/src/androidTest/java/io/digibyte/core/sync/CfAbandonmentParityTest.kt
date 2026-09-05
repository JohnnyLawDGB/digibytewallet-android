package io.digibyte.core.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.digibyte.core.bridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Binds Kotlin's [nextAbandonedBand], [CfAbandonmentStore.bandIsRetired] and
 * [CfAbandonmentStore.coverageIsProven] to `BRCFAbandonment.h`.
 *
 * ## Why a parity test instead of one implementation
 *
 * The C header is the SOURCE OF TRUTH — iOS imports it directly. The Kotlin survives as a
 * mirror because [CfAbandonmentStoreTest] and [AbandonedBandRetirementTest] run on the host
 * JVM, and NativeBridge's static initializer throws `UnsatisfiedLinkError` there. So the
 * duplicate is deliberate, and this test is what makes it safe: a drift becomes a failing
 * test rather than two platforms disagreeing on whether a range of heights is still
 * condemned.
 *
 * ## What is actually at stake
 *
 * `coverageIsProven` decides when an abandoned band may be declared recovered. Its
 * ledger-start qualifier is the whole point: `scannedThrough` is contiguous FROM the
 * ledger's start, so a ledger re-initialised above the band proves nothing about it, and a
 * mirror that drops that qualifier clears a warning over heights nobody ever scanned — a
 * silent balance under-report. The C KAT's RED gate is exactly that shape; this test makes
 * sure Kotlin has not drifted into it.
 */
@RunWith(AndroidJUnit4::class)
class CfAbandonmentParityTest {

    // The Note 8 numbers from AbandonedBandRetirementTest, so the three suites (Kotlin host,
    // C host KAT, this) are visibly the same cases.
    private companion object {
        const val BAND_LOW = 24_050_000L
        const val BAND_HIGH = 24_066_882L
        const val START = 24_000_000L
        const val SCANNED = 24_074_267L
        const val FLOOR_LIVE = 24_070_273L
    }

    private fun cNext(existing: AbandonedBand?, abandonedBelow: Long, lowHint: Long): AbandonedBand? {
        val r = NativeBridge.cfAbandonedBandNext(
            existing != null, existing?.low ?: 0L, existing?.high ?: 0L, existing?.lowKnown ?: false,
            abandonedBelow, lowHint,
        )
        assertNotNull("C returned no array", r)
        r!!
        // changed == 0 means "the record is unchanged" — Kotlin expresses that by returning
        // the existing object (or null when there was none).
        if (r[0] == 0L) return existing
        return AbandonedBand(low = r[1], high = r[2], lowKnown = r[3] != 0L)
    }

    private fun assertNextAgrees(existing: AbandonedBand?, abandonedBelow: Long, lowHint: Long) {
        assertEquals(
            "nextAbandonedBand(existing=$existing, abandonedBelow=$abandonedBelow, lowHint=$lowHint) " +
                "drifted from BRCFAbandonedBandNext",
            nextAbandonedBand(existing, abandonedBelow, lowHint),
            cNext(existing, abandonedBelow, lowHint),
        )
    }

    @Test
    fun bandFoldingAgrees() {
        // The CfAbandonmentStoreTest cases.
        assertNextAgrees(null, 23_900_125L, 23_900_120L)     // exact range, low known
        assertNextAgrees(null, 23_900_125L, 0L)              // no hint → low unknown
        assertNextAgrees(null, 1_000L, 5_000L)               // hint above watermark → rejected
        assertNextAgrees(null, 1_000L, 999L)                 // hint at the top → accepted
        assertNextAgrees(null, 1_000L, 1_000L)               // hint == watermark → rejected
        val first = nextAbandonedBand(null, 1_000L, 900L)
        assertNextAgrees(first, 1_500L, 1_400L)              // extends upward, keeps bottom
        assertNextAgrees(AbandonedBand(0L, 999L, false), 1_500L, 1_400L)  // unknown stays unknown
        val existing = AbandonedBand(10L, 20L, true)
        assertNextAgrees(null, 0L, 5L)                       // nothing abandoned
        assertNextAgrees(existing, 0L, 5L)
        assertNextAgrees(existing, 21L, 5L)                  // same watermark: no churn
        assertNextAgrees(existing, 15L, 5L)                  // older watermark: no churn
        assertNextAgrees(existing, 22L, 5L)                  // one higher: a change
    }

    @Test
    fun retirementAgrees() {
        listOf(BAND_LOW, 0L, 23_000_000L, 24_060_000L, FLOOR_LIVE, 24_055_000L, BAND_LOW + 1).forEach { floor ->
            assertEquals(
                "bandIsRetired(bandLow=$BAND_LOW, abandonedBelow=$floor) drifted from C",
                CfAbandonmentStore.bandIsRetired(BAND_LOW, floor),
                NativeBridge.cfAbandonedBandIsRetired(BAND_LOW, floor),
            )
        }
    }

    private fun assertCoverageAgrees(
        bandLow: Long, bandHigh: Long, lowKnown: Boolean,
        ledgerStart: Long, scannedThrough: Long, abandonedBelow: Long, gaveUp: Long,
    ) {
        assertEquals(
            "coverageIsProven(low=$bandLow, high=$bandHigh, lowKnown=$lowKnown, start=$ledgerStart, " +
                "scanned=$scannedThrough, floor=$abandonedBelow, gaveUp=$gaveUp) drifted from C",
            CfAbandonmentStore.coverageIsProven(
                bandLow = bandLow, bandHigh = bandHigh, lowKnown = lowKnown,
                ledgerStart = ledgerStart, scannedThrough = scannedThrough,
                abandonedBelow = abandonedBelow, gaveUp = gaveUp,
            ),
            NativeBridge.cfAbandonedBandCoverageIsProven(
                bandLow, bandHigh, lowKnown, ledgerStart, scannedThrough, abandonedBelow, gaveUp,
            ),
        )
    }

    @Test
    fun provenCoverageAgrees() {
        // The AbandonedBandRetirementTest cases.
        assertCoverageAgrees(BAND_LOW, BAND_HIGH, true, START, SCANNED, 0L, 0L)          // proven
        assertCoverageAgrees(BAND_LOW, BAND_HIGH, true, 24_070_000L, SCANNED, 0L, 0L)    // ledger above band
        assertCoverageAgrees(BAND_LOW, BAND_HIGH, true, START, 24_060_000L, 0L, 0L)      // not reached top
        assertCoverageAgrees(BAND_LOW, BAND_HIGH, true, START, BAND_HIGH, 0L, 0L)        // exactly at top
        assertCoverageAgrees(BAND_LOW, BAND_HIGH, true, START, BAND_HIGH - 1, 0L, 0L)    // one short
        assertCoverageAgrees(BAND_LOW, BAND_HIGH, true, START, SCANNED, FLOOR_LIVE, 0L)  // still clamping
        assertCoverageAgrees(BAND_LOW, BAND_HIGH, true, START, SCANNED, 0L, 12L)         // gave up
        assertCoverageAgrees(BAND_LOW, BAND_HIGH, true, 0L, SCANNED, 0L, 0L)             // zero start
        assertCoverageAgrees(BAND_LOW, BAND_HIGH, true, START, 0L, 0L, 0L)               // zero scanned
        assertCoverageAgrees(BAND_LOW, BAND_HIGH, true, BAND_LOW, SCANNED, 0L, 0L)       // start == low
        assertCoverageAgrees(0L, BAND_HIGH, false, START, SCANNED, 0L, 0L)               // unknown low
        assertCoverageAgrees(23_000_000L, BAND_HIGH, true, START, SCANNED, 0L, 0L)       // known low below start
        assertCoverageAgrees(0L, BAND_HIGH, false, START, 24_060_000L, 0L, 0L)           // unknown low, short scan
    }

    /** The case the RED gate guards, stated on its own so a drift here has its own name. */
    @Test
    fun aLedgerStartedAboveTheBandProvesNothingOnBothSides() {
        assertEquals(false, CfAbandonmentStore.coverageIsProven(
            bandLow = BAND_LOW, bandHigh = BAND_HIGH, ledgerStart = 24_070_000L,
            scannedThrough = SCANNED, abandonedBelow = 0L, gaveUp = 0L,
        ))
        assertEquals(false, NativeBridge.cfAbandonedBandCoverageIsProven(
            BAND_LOW, BAND_HIGH, true, 24_070_000L, SCANNED, 0L, 0L,
        ))
    }
}
