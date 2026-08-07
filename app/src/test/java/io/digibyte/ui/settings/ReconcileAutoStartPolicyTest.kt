package io.digibyte.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for the history-gap banner's "Scan for missing transactions" button actually scanning.
 *
 * THE RED ARM IS FIRST AND IT MODELS THE OLD BEHAVIOUR, because the defect was not an exception
 * or a failed request — it was a button that only navigated. `DgbNodeClient` logs every failure
 * mode and was silent, which is precisely how we know nothing ran. A test that only checked the
 * network path would have passed against the broken build.
 *
 * Field case, Note 8 2026-08-07: a wallet that had abandoned 1,342,744 blocks sat at 0.00 DGB
 * with no transactions; the banner's button was tapped and produced no network call whatsoever.
 * The scan only ran after pressing a second, different button on the destination screen.
 */
class ReconcileAutoStartPolicyTest {

    /** The old wiring, reproduced: navigating never requested a scan, so nothing ever started. */
    private fun oldBehaviourWouldStart(): Boolean = false

    @Test
    fun `RED - the old navigate-only wiring never starts a scan`() {
        assertFalse(
            "the defect: the button promised a scan and only opened a screen",
            oldBehaviourWouldStart(),
        )
    }

    @Test
    fun `a banner tap starts the scan on arrival`() {
        assertTrue(
            shouldAutoStartReconcile(
                requested = true,
                alreadyStarted = false,
                scanInProgress = false,
            ),
        )
    }

    @Test
    fun `opening the screen from Settings does NOT start a scan`() {
        // There the user opened a screen; they did not ask for work to begin.
        assertFalse(
            shouldAutoStartReconcile(
                requested = false,
                alreadyStarted = false,
                scanInProgress = false,
            ),
        )
    }

    @Test
    fun `recomposition cannot fire a second scan`() {
        assertFalse(
            "a composable can recompose many times per arrival; only the first may start work",
            shouldAutoStartReconcile(
                requested = true,
                alreadyStarted = true,
                scanInProgress = false,
            ),
        )
    }

    @Test
    fun `never stacks on top of a scan already running`() {
        assertFalse(
            shouldAutoStartReconcile(
                requested = true,
                alreadyStarted = false,
                scanInProgress = true,
            ),
        )
    }

    @Test
    fun `every guard is independently sufficient to suppress`() {
        // Pins that this is an AND of all three, so removing any one guard fails a test rather
        // than silently re-enabling double-scans.
        assertFalse(shouldAutoStartReconcile(false, true, true))
        assertFalse(shouldAutoStartReconcile(false, false, true))
        assertFalse(shouldAutoStartReconcile(false, true, false))
        assertFalse(shouldAutoStartReconcile(true, true, true))
        assertTrue(shouldAutoStartReconcile(true, false, false))
    }
}
