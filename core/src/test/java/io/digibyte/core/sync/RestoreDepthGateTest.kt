package io.digibyte.core.sync

import io.digibyte.core.sync.RestoreDepthGate.isRestoreTooDeep
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the deep-restore depth-gate decision (spec Part 3c): refuse a CF restore
 * whose scan span exceeds the native CF-retention ceiling
 * (CF_RETENTION_MAX_SPAN = 30000), rather than sync to a wrong balance or OOM.
 */
class RestoreDepthGateTest {

    private val limit = 30_000L // mirrors CF_RETENTION_MAX_SPAN for the test only

    @Test fun `a shallow restore is allowed`() {
        assertFalse(isRestoreTooDeep(0L, limit))
        assertFalse(isRestoreTooDeep(1L, limit))
        assertFalse(isRestoreTooDeep(29_999L, limit))
    }

    @Test fun `exactly at the limit is allowed`() {
        assertFalse(isRestoreTooDeep(30_000L, limit))
    }

    @Test fun `one block over the limit is refused`() {
        assertTrue(isRestoreTooDeep(30_001L, limit))
    }

    @Test fun `a genesis-deep restore is refused`() {
        // ~23.66M blocks (highest checkpoint − genesis) — the "I don't remember"
        // full-history case, far beyond what this build can scan on-device.
        assertTrue(isRestoreTooDeep(23_660_000L, limit))
    }

    @Test fun `zero or negative depth is allowed (fresh or near-tip birth)`() {
        assertFalse(isRestoreTooDeep(0L, limit))
        assertFalse(isRestoreTooDeep(-1L, limit))
        assertFalse(isRestoreTooDeep(Long.MIN_VALUE, limit))
    }

    @Test fun `a non-positive limit disables the gate (never refuse)`() {
        // Defensive: a broken/zero native limit must not block every restore.
        assertFalse(isRestoreTooDeep(1_000_000L, 0L))
        assertFalse(isRestoreTooDeep(1_000_000L, -5L))
    }
}
