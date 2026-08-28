package io.digibyte.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a restore says when a passphrase finds nothing.
 *
 * ## The hazard
 *
 * A BIP39 passphrase has no checksum. Mistype it and the wallet derives a different, perfectly
 * valid, EMPTY wallet — there is no error, nothing is wrong, and the scan honestly finds no
 * funds. To the person restoring, that is indistinguishable from their coins being gone.
 *
 * A bare "No recoverable funds found" is therefore the single most damaging sentence this screen
 * could show. It is a confident answer to a question that was only asked one way.
 *
 * ## The cheap signal
 *
 * Scanning the same mnemonic WITHOUT the passphrase costs one more pass and answers the question
 * that actually matters: are there funds on this phrase at all? If there are, the passphrase is
 * almost certainly mistyped — or was never needed — and we can say so instead of implying loss.
 *
 * This is the same class of defect as the `anyBackendUnreachable` fix: a confident answer to an
 * unfinished question, about someone's money.
 */
class PassphraseScanVerdictTest {

    @Test fun `funds under the passphrase is simply a success`() {
        assertEquals(
            PassphraseScanVerdict.Outcome.FOUND,
            PassphraseScanVerdict.of(withPassphraseSat = 500L, withoutPassphraseSat = 0L, incomplete = false),
        )
    }

    /** THE case this exists for. */
    @Test fun `nothing with the passphrase but funds without it reads as a typo`() {
        assertEquals(
            PassphraseScanVerdict.Outcome.LIKELY_TYPO,
            PassphraseScanVerdict.of(withPassphraseSat = 0L, withoutPassphraseSat = 900L, incomplete = false),
        )
    }

    @Test fun `nothing either way is an honest empty result`() {
        assertEquals(
            PassphraseScanVerdict.Outcome.NONE_ANYWHERE,
            PassphraseScanVerdict.of(withPassphraseSat = 0L, withoutPassphraseSat = 0L, incomplete = false),
        )
    }

    /**
     * An incomplete scan cannot support "found nothing" — that is a negative claim about paths
     * the wallet never checked.
     */
    @Test fun `an incomplete scan cannot claim nothing was found`() {
        assertEquals(
            PassphraseScanVerdict.Outcome.INCOMPLETE,
            PassphraseScanVerdict.of(withPassphraseSat = 0L, withoutPassphraseSat = 0L, incomplete = true),
        )
    }

    /**
     * CORRECTED after device testing. Incompleteness must NOT suppress a positive finding.
     *
     * The original rule let INCOMPLETE outrank everything, which sounded conservative and was
     * wrong: BIP49 fails against this backend on every scan, so `incomplete` is effectively
     * always true, and the typo hint could never fire for anyone. The most useful sentence in
     * the flow was unreachable in production while passing its unit test.
     *
     * "This phrase has funds without a passphrase" is a POSITIVE observation. It does not depend
     * on the paths that failed, so an unchecked BIP49 cannot make it untrue. Only the negative
     * conclusion needs a complete scan.
     */
    @Test fun `a positive finding survives an incomplete scan`() {
        assertEquals(
            "funds under the passphrase are found, whatever else went unchecked",
            PassphraseScanVerdict.Outcome.FOUND,
            PassphraseScanVerdict.of(withPassphraseSat = 500L, withoutPassphraseSat = 0L, incomplete = true),
        )
        assertEquals(
            "funds WITHOUT the passphrase are equally observed, and far more actionable",
            PassphraseScanVerdict.Outcome.LIKELY_TYPO,
            PassphraseScanVerdict.of(withPassphraseSat = 0L, withoutPassphraseSat = 900L, incomplete = true),
        )
    }

    /**
     * When no second scan was run — because no passphrase was supplied — there is nothing to
     * compare against and the typo verdict must not be reachable.
     */
    @Test fun `without a comparison scan the typo verdict cannot fire`() {
        assertEquals(
            PassphraseScanVerdict.Outcome.NONE_ANYWHERE,
            PassphraseScanVerdict.of(withPassphraseSat = 0L, withoutPassphraseSat = null, incomplete = false),
        )
    }

    @Test fun `a found result does not need the comparison`() {
        assertEquals(
            PassphraseScanVerdict.Outcome.FOUND,
            PassphraseScanVerdict.of(withPassphraseSat = 1L, withoutPassphraseSat = null, incomplete = false),
        )
    }
}
