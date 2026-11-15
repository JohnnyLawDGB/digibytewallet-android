package app.aroundtheblock.wallet.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STORE-DURABILITY: only a write that FAILED is reported for retry. A snapshot a delete made stale is
 * not a failure and does not run the checkpoint gate. Order: ledger, then the caller's block window,
 * then the header chain.
 */
class CfStateFlushTest {

    private fun ledger(result: StoreWrite = StoreWrite.LANDED, obsolete: Boolean = false, writes: MutableList<String>? = null) =
        CfStateFlush.PendingLedger({ obsolete }, { writes?.add("ledger"); result })

    @Test fun `both landed`() {
        val o = CfStateFlush.flush(ledger(), { true }, { StoreWrite.LANDED })
        assertTrue(o.allLanded)
    }

    @Test fun `the order is gate, ledger, the caller's write, headers`() {
        val order = mutableListOf<String>()
        CfStateFlush.flush(ledger(writes = order), { order += "gate"; true }, { order += "headers"; StoreWrite.LANDED },
            beforeHeaders = { order += "blocks" })
        assertEquals(listOf("gate", "ledger", "blocks", "headers"), order)
    }

    @Test fun `the caller's write runs even with nothing pending`() {
        var ran = false
        assertEquals(CfStateFlush.Outcome.NOTHING, CfStateFlush.flush(null, { true }, null, beforeHeaders = { ran = true }))
        assertTrue(ran)
    }

    @Test fun `a failed header write is reported and the ledger is still written`() {
        val writes = mutableListOf<String>()
        val o = CfStateFlush.flush(ledger(writes = writes), { true }, { StoreWrite.FAILED })
        assertEquals(CfStateFlush.Outcome(StoreWrite.LANDED, StoreWrite.FAILED), o)
        assertFalse(o.allLanded)
        assertEquals(listOf("ledger"), writes)
    }

    @Test fun `a failed ledger write is reported`() {
        val o = CfStateFlush.flush(ledger(StoreWrite.FAILED), { true }, { StoreWrite.LANDED })
        assertFalse(o.ledgerLanded)
        assertFalse(o.allLanded)
    }

    @Test fun `a shut checkpoint gate does not write the ledger and counts as failed`() {
        val writes = mutableListOf<String>()
        val o = CfStateFlush.flush(ledger(writes = writes), { false }, null)
        assertEquals(emptyList<String>(), writes)
        assertEquals(StoreWrite.FAILED, o.ledger)
    }

    @Test fun `a stale ledger is neither gated nor written, and is not a failure`() {
        var asked = 0
        val writes = mutableListOf<String>()
        val o = CfStateFlush.flush(ledger(obsolete = true, writes = writes), { asked++; true }, null)
        assertEquals(0, asked)
        assertEquals(emptyList<String>(), writes)
        assertEquals(StoreWrite.OBSOLETE, o.ledger)
        assertTrue("a stale snapshot is not retried", o.allLanded)
    }

    @Test fun `a write the store drops as stale is not a failure`() {
        val o = CfStateFlush.flush(ledger(StoreWrite.OBSOLETE), { true }, { StoreWrite.OBSOLETE })
        assertTrue(o.allLanded)
    }

    @Test fun `the gate is asked only when a ledger is pending`() {
        var asked = 0
        val o = CfStateFlush.flush(null, { asked++; true }, { StoreWrite.LANDED })
        assertEquals(0, asked)
        assertTrue(o.allLanded)
    }

    @Test fun `a write that throws counts as failed`() {
        val o = CfStateFlush.flush(CfStateFlush.PendingLedger({ false }, { error("y") }), { true }, { error("x") })
        assertEquals(CfStateFlush.Outcome(StoreWrite.FAILED, StoreWrite.FAILED), o)
    }
}
