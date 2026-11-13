package app.aroundtheblock.wallet.core.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEED-VIEW-AUTH (decision A): the onStop lock is skipped only while the app's own
 * device-credential prompt is up, for at most MAX_EXEMPTION_MS, and once per prompt.
 */
class OwnCredentialPromptExemptionTest {

    private val max = OwnCredentialPromptExemption.MAX_EXEMPTION_MS

    @After fun reset() = OwnCredentialPromptExemption.end()

    // ---- the pure rule ----

    @Test fun `no prompt - onStop locks`() {
        assertTrue(OwnCredentialPromptExemption.lockOnStop(null, 1_000L))
    }

    @Test fun `prompt just started - onStop does not lock`() {
        assertFalse(OwnCredentialPromptExemption.lockOnStop(1_000L, 1_000L))
        assertFalse(OwnCredentialPromptExemption.lockOnStop(1_000L, 1_000L + max - 1))
    }

    @Test fun `prompt older than the bound - onStop locks`() {
        assertTrue(OwnCredentialPromptExemption.lockOnStop(1_000L, 1_000L + max))
        assertTrue(OwnCredentialPromptExemption.lockOnStop(1_000L, 1_000L + 10 * max))
    }

    @Test fun `a start in the future (clock fault) - onStop locks`() {
        assertTrue(OwnCredentialPromptExemption.lockOnStop(5_000L, 4_999L))
    }

    @Test fun `the bound is short`() {
        assertTrue("exemption bound must stay at or below 60 s", max in 1L..60_000L)
    }

    @Test fun `remaining time counts down to zero and never goes negative`() {
        assertEquals(max, OwnCredentialPromptExemption.remainingMs(1_000L, 1_000L))
        assertEquals(1L, OwnCredentialPromptExemption.remainingMs(1_000L, 1_000L + max - 1))
        assertEquals(0L, OwnCredentialPromptExemption.remainingMs(1_000L, 1_000L + max + 5))
        assertEquals(0L, OwnCredentialPromptExemption.remainingMs(5_000L, 4_000L))
    }

    // ---- the holder ----

    @Test fun `no begin - consumeForStop says lock`() {
        assertNull(OwnCredentialPromptExemption.consumeForStop(1_000L))
    }

    @Test fun `begin then stop inside the bound - exempt, once`() {
        OwnCredentialPromptExemption.begin(1_000L)
        assertEquals(1_000L, OwnCredentialPromptExemption.consumeForStop(2_000L))
        // a second backgrounding during the same prompt is not ours: it locks
        assertNull(OwnCredentialPromptExemption.consumeForStop(3_000L))
    }

    @Test fun `begin then stop after the bound - locks`() {
        OwnCredentialPromptExemption.begin(1_000L)
        assertNull(OwnCredentialPromptExemption.consumeForStop(1_000L + max))
    }

    @Test fun `end clears - a later stop locks`() {
        OwnCredentialPromptExemption.begin(1_000L)
        OwnCredentialPromptExemption.end()
        assertFalse(OwnCredentialPromptExemption.isActive(1_001L))
        assertNull(OwnCredentialPromptExemption.consumeForStop(1_001L))
    }

    @Test fun `isActive is bounded in time even with no end`() {
        OwnCredentialPromptExemption.begin(1_000L)
        assertTrue(OwnCredentialPromptExemption.isActive(1_000L + max - 1))
        assertFalse(OwnCredentialPromptExemption.isActive(1_000L + max))
    }

    @Test fun `home during the prompt - the deferred lock fires at the bound`() {
        // our activity stopped for the confirm screen (exempt); the user then pressed Home,
        // so no second onStop arrives and no result returns. The deferred check at the bound locks.
        OwnCredentialPromptExemption.begin(1_000L)
        val start = OwnCredentialPromptExemption.consumeForStop(1_500L)
        assertEquals(1_000L, start)
        val delay = OwnCredentialPromptExemption.remainingMs(start!!, 1_500L)
        assertEquals(max - 500L, delay)
        assertTrue(OwnCredentialPromptExemption.lockOnStop(start, 1_500L + delay))
        // and a return before the bound (prompt answered) does not lock
        assertFalse(OwnCredentialPromptExemption.lockOnStop(start, 1_500L + delay - 1))
    }
}
