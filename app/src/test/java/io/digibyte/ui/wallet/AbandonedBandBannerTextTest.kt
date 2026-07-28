package io.digibyte.ui.wallet

import io.digibyte.core.sync.AbandonedBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paced-convoy fetch, spec Part E — what the persistent recover-me banner actually
 * SAYS (operator GATE 3).
 *
 * This is a truthfulness test, not a copy test. The banner is the only user-visible
 * evidence that a slice of history was never verified, so a number in it that
 * over- or under-states the gap is worse than no banner: it either panics the user
 * or lets them dismiss a real loss.
 */
class AbandonedBandBannerTextTest {

    @Test fun observedBand_namesTheExactInclusiveRange() {
        val msg = abandonedBandMessage(AbandonedBand(low = 23_900_120L, high = 23_900_124L, lowKnown = true))
        assertEquals(
            "Part of your history (blocks 23,900,120–23,900,124) couldn't be verified " +
                "from the filter fleet — tap to scan for missing transactions.",
            msg,
        )
    }

    /** Bottom not observed → say "below <watermark>", which is TRUE, instead of
     *  substituting the ledger start (which would claim the wallet's whole history
     *  was abandoned). `high + 1` is exactly the native `abandonedBelow`. */
    @Test fun unobservedBottom_saysBelowTheWatermark_notAFabricatedRange() {
        val msg = abandonedBandMessage(AbandonedBand(low = 0L, high = 23_900_124L, lowKnown = false))
        assertEquals(
            "Part of your history (blocks below 23,900,125) couldn't be verified " +
                "from the filter fleet — tap to scan for missing transactions.",
            msg,
        )
        assertFalse("must not invent a lower bound", msg.contains("0–"))
    }

    /** A low of 0 with lowKnown set (corrupt/legacy pref) must degrade to the
     *  honest "below" form rather than rendering "blocks 0–23,900,124". */
    @Test fun zeroLow_degradesToBelowForm_evenIfFlaggedKnown() {
        val msg = abandonedBandMessage(AbandonedBand(low = 0L, high = 23_900_124L, lowKnown = true))
        assertTrue(msg.contains("blocks below 23,900,125"))
    }

    /**
     * The banner must NEVER render `getAbandonedCount()` as "N blocks abandoned".
     * That accessor returns `abandonedBelow - ledger.start` — the size of the whole
     * scanned range below the watermark, NOT the number of heights actually
     * abandoned — so after abandoning a single deep height it reads as the wallet's
     * entire scanned history. This pins the range-not-count decision.
     */
    @Test fun neverRendersACount() {
        val msg = abandonedBandMessage(AbandonedBand(low = 23_900_124L, high = 23_900_124L, lowKnown = true))
        assertFalse(msg.contains("abandoned"))
        assertFalse(msg.lowercase().contains("blocks abandoned"))
        // A single abandoned height still reads as a range, not "1 block".
        assertTrue(msg.contains("blocks 23,900,124–23,900,124"))
    }

    /** The call to action names the recovery the user can actually take. */
    @Test fun alwaysOffersTheRecovery() {
        listOf(
            AbandonedBand(1L, 10L, true),
            AbandonedBand(0L, 10L, false),
        ).forEach {
            assertTrue(abandonedBandMessage(it).contains("scan for missing transactions"))
        }
    }
}
