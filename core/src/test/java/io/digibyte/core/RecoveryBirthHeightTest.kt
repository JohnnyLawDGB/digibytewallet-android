package io.digibyte.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the recovery/rescan compact-filter-floor persistence decision
 * ([cfBirthHeightToPersist]) — the fix for "restored wallet scans from genesis and
 * never finishes."
 *
 * The bug it prevents: on recovery the chosen year set a VOLATILE native
 * `g_walletCreationTime`, but nothing persisted a floor. On the next launch the wallet
 * rebuilt from the seed alone → creation-time reset to 0 →
 * `getWalletBirthCheckpointHeight()` returned the GENESIS checkpoint → the scan
 * re-floored at genesis (~23M blocks, 11-19h). The fix persists `cf_birth_height`, but
 * MUST NOT persist a 0/unknown height — writing 0 would itself floor at genesis. So the
 * invariant is: persist only a positive height; otherwise clear the pref and let
 * SyncService fall back to its saved-tip / birth-checkpoint default.
 */
class RecoveryBirthHeightTest {

    @Test fun `a real checkpoint height is persisted as-is`() {
        // 2026 → checkpoint ~block 21.5M. On-device this cut a 19h scan to ~12m.
        assertEquals(21_500_000L, cfBirthHeightToPersist(21_500_000L))
        assertEquals(23_000_000L, cfBirthHeightToPersist(23_000_000L))
    }

    @Test fun `zero height is NEVER persisted — that would floor at genesis`() {
        // The whole bug: a 0 floor = genesis = the multi-hour full-chain scan. Persisting
        // it would bake the bug in permanently. null => caller clears the pref instead.
        assertNull(cfBirthHeightToPersist(0L))
    }

    @Test fun `a negative or nonsense height is not persisted`() {
        assertNull(cfBirthHeightToPersist(-1L))
        assertNull(cfBirthHeightToPersist(Long.MIN_VALUE))
    }

    @Test fun `the smallest positive height is still a real floor and is persisted`() {
        // Boundary: 1 is a valid (if very low) floor — only <= 0 means "unknown".
        assertEquals(1L, cfBirthHeightToPersist(1L))
    }
}
