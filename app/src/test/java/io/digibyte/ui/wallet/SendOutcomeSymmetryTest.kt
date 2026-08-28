package io.digibyte.ui.wallet

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed send must be as hard to miss as a successful one.
 *
 * ## What went wrong
 *
 * Success rendered a full-screen overlay that blocked the app until acknowledged. Failure
 * rendered one line of `bodySmall` red text at the bottom of a scrolling form, below the button
 * and below the fold if the form happened to be scrolled, with nothing to dismiss and nothing
 * recorded.
 *
 * Reproduced on a Note 8: the confirm dialog closed, the form returned to exactly its previous
 * state, and the entire report was the words "Insufficient balance". From the outside that is
 * indistinguishable from nothing having happened — which is how three real sends came to be
 * described as having "not registered anywhere, no stuck, no failure".
 *
 * The asymmetry is the defect. An outcome someone can walk past without noticing is an outcome
 * they will assume went the other way, and for a payment the wrong assumption is expensive.
 *
 * This gate exists because nothing else would fail if the overlay were folded back into the form
 * "to save a screen".
 */
class SendOutcomeSymmetryTest {

    private fun source(): String =
        File("src/main/java/io/digibyte/ui/wallet/SendScreen.kt").let {
            assertTrue("SendScreen.kt is missing — this gate is watching a file that moved", it.exists())
            it.readLines()
                .filterNot { l -> l.trimStart().startsWith("//") || l.trimStart().startsWith("*") }
                .filterNot { l -> l.trimStart().startsWith("/*") }
                .joinToString("\n")
        }

    @Test fun `a failed send gets its own blocking screen`() {
        assertTrue(
            "SendFailureScreen is gone — a failed send is being reported some quieter way",
            source().contains("SendFailureScreen"),
        )
    }

    /**
     * Both overlays `return` out of the composable, which is what makes them blocking. A failure
     * branch that falls through renders behind the form instead of replacing it.
     */
    @Test fun `the failure branch blocks the rest of the screen`() {
        val src = source()
        val idx = src.indexOf("if (sendState is SendState.Error)")
        assertTrue("no failure branch found at all", idx > 0)
        val branch = src.substring(idx, minOf(idx + 400, src.length))
        assertTrue(
            "the failure branch does not return — it will render behind the form rather than " +
                "replacing it, which is the quiet treatment this gate exists to prevent",
            branch.contains("return"),
        )
    }

    /** The old inline treatment must not come back alongside the overlay. */
    @Test fun `the failure is not rendered as an inline form line`() {
        val src = source()
        val inline = Regex("""SendState\.Error\)\.message,\s*\n\s*style = MaterialTheme\.typography\.bodySmall""")
        assertTrue(
            "the error is being drawn as small text inside the form again",
            !inline.containsMatchIn(src),
        )
    }

    /** Success must stay blocking too — the symmetry runs both ways. */
    @Test fun `a successful send still gets its own blocking screen`() {
        assertTrue(source().contains("SendSuccessScreen"))
    }
}
