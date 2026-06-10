package io.digibyte.core.dandelion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DandelionBroadcastPolicyTest {
    @Test fun `stem only when enabled and a capable peer is present`() {
        assertTrue(shouldStem(enabled = true, hasDandelionPeer = true))
        assertFalse(shouldStem(enabled = true, hasDandelionPeer = false))
        assertFalse(shouldStem(enabled = false, hasDandelionPeer = true))
    }

    @Test fun `fluff exactly when the network has not relayed it back`() {
        assertTrue(shouldFluffAfterEmbargo(relayCount = 0))
        assertFalse(shouldFluffAfterEmbargo(relayCount = 1))
        assertFalse(shouldFluffAfterEmbargo(relayCount = 5))
    }

    @Test fun `embargo delay is within the 10-30s window for all rng inputs`() {
        for (r in listOf(0.0, 0.5, 0.999, 1.0)) {
            val ms = embargoDelayMs(r)
            assertTrue("$ms out of range", ms in EMBARGO_MIN_MS..EMBARGO_MAX_MS)
        }
    }
}
