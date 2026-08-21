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
}
