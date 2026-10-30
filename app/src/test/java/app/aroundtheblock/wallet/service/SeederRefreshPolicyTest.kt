package app.aroundtheblock.wallet.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeederRefreshPolicyTest {
    private val hour = 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    @Test fun `a list is stale when empty or over the interval old`() {
        assertTrue(SeederRefreshPolicy.isStale(now, now, poolEmpty = true, intervalMs = hour))
        assertTrue(SeederRefreshPolicy.isStale(now - hour - 1, now, poolEmpty = false, intervalMs = hour))
        assertFalse(SeederRefreshPolicy.isStale(now - hour, now, poolEmpty = false, intervalMs = hour))
        assertTrue("never fetched", SeederRefreshPolicy.isStale(0L, now, poolEmpty = false, intervalMs = hour))
    }

    @Test fun `a failed refresh is not retried inside the back-off`() {
        val b = SeederRefreshPolicy.RETRY_BACKOFF_MS
        assertTrue("never attempted", SeederRefreshPolicy.mayAttempt(0L, now))
        assertFalse(SeederRefreshPolicy.mayAttempt(now - 1, now))
        assertFalse(SeederRefreshPolicy.mayAttempt(now - b + 1, now))
        assertTrue(SeederRefreshPolicy.mayAttempt(now - b, now))
        assertTrue("clock went backwards", SeederRefreshPolicy.mayAttempt(now + 10_000, now))
    }
}
