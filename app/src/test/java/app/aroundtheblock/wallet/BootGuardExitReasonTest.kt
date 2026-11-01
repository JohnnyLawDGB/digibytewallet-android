package app.aroundtheblock.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A start that never reached "boot healthy" counts toward the crash-loop wipe only when its process crashed
 * (4.1.3). A background start the system froze and then killed is not a crash, and two of them wiped the sync
 * state of a healthy wallet (reproduced on the API 35 emulator; a Galaxy S25 Ultra re-synced from its birth twice).
 */
class BootGuardExitReasonTest {
    // android.app.ApplicationExitInfo.REASON_* values
    private val lowMemory = 3
    private val signaled = 2
    private val userRequested = 10
    private val freezer = 14
    private val packageUpdated = 16
    private val other = 13

    @Test fun `a crash, a native crash, an ANR or an initialization failure counts`() {
        for (r in listOf(BootGuard.REASON_CRASH, BootGuard.REASON_CRASH_NATIVE, BootGuard.REASON_ANR,
                         BootGuard.REASON_INITIALIZATION_FAILURE)) {
            assertTrue("reason $r", BootGuard.previousDeathCounts(r))
        }
    }

    @Test fun `a kill by the system or the user is not a dead boot`() {
        for (r in listOf(lowMemory, signaled, userRequested, freezer, packageUpdated, other)) {
            assertFalse("reason $r", BootGuard.previousDeathCounts(r))
        }
    }

    @Test fun `with no record, the old rule holds`() {
        assertTrue(BootGuard.previousDeathCounts(null))
    }

    @Test fun `the constants are the platform's`() {
        // ApplicationExitInfo: CRASH = 4, CRASH_NATIVE = 5, ANR = 6, INITIALIZATION_FAILURE = 7
        assertTrue(BootGuard.REASON_CRASH == 4 && BootGuard.REASON_CRASH_NATIVE == 5 &&
            BootGuard.REASON_ANR == 6 && BootGuard.REASON_INITIALIZATION_FAILURE == 7)
    }
}
