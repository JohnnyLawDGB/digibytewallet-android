package io.digibyte.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the backfill retires a band, the banner must go away.
 *
 * `noteScanCoverage` bailed on `abandonedBelow != 0`, which was correct while the watermark
 * had no lowering path at all — it could only ever mean "the floor is still clamping". Now
 * the backfill lowers it, so a band can be fully retired while the watermark is still
 * non-zero from an OLDER, deeper band. Requiring 0 would leave the user staring at a warning
 * about a range that has already been recovered.
 *
 * The condition that actually matters is whether the floor still covers THIS band:
 * `abandonedBelow <= band.low` means every height in it is requestable again.
 */
class AbandonedBandRetirementTest {

    @Test fun a_floor_at_or_below_the_band_low_means_the_band_is_retired() {
        assertTrue(CfAbandonmentStore.bandIsRetired(bandLow = 24_050_000L, abandonedBelow = 24_050_000L))
        assertTrue(CfAbandonmentStore.bandIsRetired(bandLow = 24_050_000L, abandonedBelow = 0L))
        // A deeper band still clamping below ours does not make ours unrecovered.
        assertTrue(CfAbandonmentStore.bandIsRetired(bandLow = 24_050_000L, abandonedBelow = 23_000_000L))
    }

    @Test fun a_floor_still_inside_the_band_means_it_is_not_retired() {
        assertFalse(CfAbandonmentStore.bandIsRetired(bandLow = 24_050_000L, abandonedBelow = 24_060_000L))
        assertFalse(CfAbandonmentStore.bandIsRetired(bandLow = 24_050_000L, abandonedBelow = 24_070_273L))
    }

    /** Partial progress is not recovery. Retiring half the band must not clear the warning,
     *  or the user is told everything is fine while heights are still condemned. */
    @Test fun partial_retirement_does_not_count_as_recovered() {
        assertFalse(
            "half-retired is still a gap",
            CfAbandonmentStore.bandIsRetired(bandLow = 24_050_000L, abandonedBelow = 24_055_000L),
        )
    }

    // ── Proven coverage: the route that clears a banner the two-phase witness can't ──
    //
    // Observed on a Note 8 running v4.0.44: the banner was displayed while native reported
    // abandonedBelow == 0. The band lives in a PERSISTED Kotlin record; the native ledger
    // had since been re-initialised, so nothing was clamping — yet the banner could never
    // clear, because noteScanCoverage requires witnessing the scan frontier INSIDE the band
    // (Phase 1) before it will accept Phase 2. By the time the floor cleared, the scan was
    // already well past the band, so Phase 1 could never fire again. Permanently stuck on a
    // range that had in fact been scanned.
    //
    // scannedThrough makes the argument directly: it is a CONTIGUOUS high-water mark — every
    // height from the ledger's start up to it was evaluated, and it never advances past an
    // outstanding or given-up hole. So if it has passed the band, the band was evaluated.

    @Test fun a_band_below_a_contiguous_scan_is_proven_covered() {
        assertTrue(
            CfAbandonmentStore.coverageIsProven(
                bandLow = 24_050_000L, bandHigh = 24_066_882L,
                ledgerStart = 24_000_000L, scannedThrough = 24_074_267L,
                abandonedBelow = 0L, gaveUp = 0L,
            )
        )
    }

    /** THE false-"all clear" this must never give. A ledger re-initialised ABOVE the band
     *  has a scannedThrough that says nothing about it — contiguity starts at `start`. */
    @Test fun a_ledger_started_above_the_band_proves_nothing_about_it() {
        assertFalse(
            "scannedThrough is contiguous FROM start; a band below start was never looked at",
            CfAbandonmentStore.coverageIsProven(
                bandLow = 24_050_000L, bandHigh = 24_066_882L,
                ledgerStart = 24_070_000L, scannedThrough = 24_074_267L,
                abandonedBelow = 0L, gaveUp = 0L,
            )
        )
    }

    @Test fun a_scan_that_has_not_reached_the_band_top_proves_nothing() {
        assertFalse(
            CfAbandonmentStore.coverageIsProven(
                bandLow = 24_050_000L, bandHigh = 24_066_882L,
                ledgerStart = 24_000_000L, scannedThrough = 24_060_000L,
                abandonedBelow = 0L, gaveUp = 0L,
            )
        )
    }

    /** A live clamp means heights really are condemned — that is the backfill's job, not a
     *  reason to declare coverage. */
    @Test fun a_floor_still_clamping_is_not_proven_coverage() {
        assertFalse(
            CfAbandonmentStore.coverageIsProven(
                bandLow = 24_050_000L, bandHigh = 24_066_882L,
                ledgerStart = 24_000_000L, scannedThrough = 24_074_267L,
                abandonedBelow = 24_070_273L, gaveUp = 0L,
            )
        )
    }

    /** Anything given up is a real hole. scannedThrough cannot have passed it, but read the
     *  counter anyway rather than rely on that invariant holding forever. */
    @Test fun heights_given_up_block_the_claim() {
        assertFalse(
            CfAbandonmentStore.coverageIsProven(
                bandLow = 24_050_000L, bandHigh = 24_066_882L,
                ledgerStart = 24_000_000L, scannedThrough = 24_074_267L,
                abandonedBelow = 0L, gaveUp = 12L,
            )
        )
    }

    /** Failed native reads arrive as 0. None of them may be read as evidence. */
    @Test fun zero_readings_are_never_evidence() {
        assertFalse(
            CfAbandonmentStore.coverageIsProven(
                bandLow = 24_050_000L, bandHigh = 24_066_882L,
                ledgerStart = 0L, scannedThrough = 24_074_267L,
                abandonedBelow = 0L, gaveUp = 0L,
            )
        )
        assertFalse(
            CfAbandonmentStore.coverageIsProven(
                bandLow = 24_050_000L, bandHigh = 24_066_882L,
                ledgerStart = 24_000_000L, scannedThrough = 0L,
                abandonedBelow = 0L, gaveUp = 0L,
            )
        )
    }
}