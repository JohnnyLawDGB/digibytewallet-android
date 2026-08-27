package io.digibyte.ui.wallet

import io.digibyte.core.sync.AbandonedBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Paced-convoy fetch, spec Part E — what the persistent recover-me banner actually
 * SAYS (operator GATE 3).
 *
 * This is a truthfulness test, not a copy test. The banner is the only user-visible
 * evidence that a slice of history was never verified, so a number in it that
 * over- or under-states the gap is worse than no banner: it either panics the user
 * or lets them dismiss a real loss.
 *
 * ## Why it no longer asserts the sentence
 *
 * The banner is now localised, so the prose lives in `strings_wallet.xml` and only the
 * RANGE is computed in Kotlin. That split follows what this test always said it was
 * about: which range gets named, not the wording around it. Asserting the English
 * sentence here would have made this file fail on a translation — a copy test wearing
 * a truthfulness test's name. The two properties that are genuinely about copy are
 * checked against the resource file at the bottom, where the copy now is.
 */
class AbandonedBandBannerTextTest {

    @Test fun observedBand_namesTheExactInclusiveRange() {
        val r = abandonedBandRange(AbandonedBand(low = 23_900_120L, high = 23_900_124L, lowKnown = true))
        assertEquals(AbandonedRange.Between("23,900,120", "23,900,124"), r)
    }

    /** Bottom not observed → say "below <watermark>", which is TRUE, instead of
     *  substituting the ledger start (which would claim the wallet's whole history
     *  was abandoned). `high + 1` is exactly the native `abandonedBelow`. */
    @Test fun unobservedBottom_saysBelowTheWatermark_notAFabricatedRange() {
        val r = abandonedBandRange(AbandonedBand(low = 0L, high = 23_900_124L, lowKnown = false))
        assertEquals(AbandonedRange.Below("23,900,125"), r)
    }

    /** A low of 0 with lowKnown set (corrupt/legacy pref) must degrade to the
     *  honest "below" form rather than rendering "blocks 0–23,900,124". */
    @Test fun zeroLow_degradesToBelowForm_evenIfFlaggedKnown() {
        val r = abandonedBandRange(AbandonedBand(low = 0L, high = 23_900_124L, lowKnown = true))
        assertEquals(AbandonedRange.Below("23,900,125"), r)
    }

    /**
     * The banner must NEVER render `getAbandonedCount()` as "N blocks abandoned".
     * That accessor returns `abandonedBelow - ledger.start` — the size of the whole
     * scanned range below the watermark, NOT the number of heights actually
     * abandoned — so after abandoning a single deep height it reads as the wallet's
     * entire scanned history. This pins the range-not-count decision.
     */
    @Test fun neverRendersACount() {
        // A single abandoned height still reads as a range, not "1 block". The return
        // type carries no count field at all, which is what makes this structural.
        val r = abandonedBandRange(AbandonedBand(low = 23_900_124L, high = 23_900_124L, lowKnown = true))
        assertEquals(AbandonedRange.Between("23,900,124", "23,900,124"), r)
    }

    // ---- the copy properties, checked where the copy now lives ------------------------------

    private val english = File("src/main/res/values/strings_wallet.xml").readText()

    /** The call to action names the recovery the user can actually take. */
    @Test fun alwaysOffersTheRecovery() {
        assertTrue("no strings file found", english.contains("wallet_gap_body"))
        val body = Regex("""<string name="wallet_gap_body">(.*?)</string>""")
            .find(english)!!.groupValues[1]
        assertTrue("banner must name the recovery", body.contains("scan for missing transactions"))
    }

    /** The word "abandoned" must not reach the user — see [neverRendersACount]. */
    @Test fun theCopyNeverSaysAbandoned() {
        listOf("wallet_gap_body", "wallet_gap_range_between", "wallet_gap_range_below").forEach { key ->
            val v = Regex("""<string name="$key">(.*?)</string>""").find(english)!!.groupValues[1]
            assertFalse("$key must not say 'abandoned'", v.lowercase().contains("abandoned"))
        }
    }
}
