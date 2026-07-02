package io.digibyte.core.recovery

import io.digibyte.core.recovery.LegacySweepService.BroadcastState
import io.digibyte.core.recovery.LegacySweepService.Result
import io.digibyte.core.recovery.LegacySweepService.SweepOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepOutcomeStateTest {

    private val profile =
        DerivationProfile.BUILT_INS.first { it.label == "Legacy DigiByte mobile wallet" }

    /** A returned txid means the tx reached local relay only — PENDING, never
     *  confirmed. Result must not claim confirmed success on local relay alone. */
    @Test
    fun returnedTxid_isPendingNotConfirmed() {
        val outcome = SweepOutcome(
            profile = profile,
            txHex = "00",
            txid = "ab".repeat(32),
            sweptSat = 1_000L,
            inputCount = 1,
            failureReason = null,
            broadcastState = BroadcastState.PENDING,
        )
        assertEquals(BroadcastState.PENDING, outcome.broadcastState)
        val result = Result(listOf(outcome))
        assertTrue("a submitted tx counts as submitted", result.allSubmitted)
        assertTrue("a pending tx is surfaced as pending", result.anyPending)
    }

    @Test
    fun nullTxid_isFailed_andNotSubmitted() {
        val outcome = SweepOutcome(
            profile = profile,
            txHex = null,
            txid = null,
            sweptSat = 0L,
            inputCount = 0,
            failureReason = "broadcast failed — no peer accepted the sweep",
            broadcastState = BroadcastState.FAILED,
        )
        val result = Result(listOf(outcome))
        assertFalse("a failed broadcast is not 'submitted'", result.allSubmitted)
        assertFalse(result.anyPending)
    }
}
