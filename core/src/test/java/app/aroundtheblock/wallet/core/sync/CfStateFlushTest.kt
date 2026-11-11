package app.aroundtheblock.wallet.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** STORE-DURABILITY: a store write that did not land is reported, so the caller re-marks dirty and retries. */
class CfStateFlushTest {

    @Test fun `both landed`() {
        val o = CfStateFlush.flush({ true }, { true }, { true })
        assertTrue(o.allLanded)
    }

    @Test fun `a failed header write is reported and the ledger is still written`() {
        var ledgerWrites = 0
        val o = CfStateFlush.flush({ false }, { ledgerWrites++; true }, { true })
        assertEquals(CfStateFlush.Outcome(headersLanded = false, ledgerLanded = true), o)
        assertFalse(o.allLanded)
        assertEquals(1, ledgerWrites)
    }

    @Test fun `a failed ledger write is reported`() {
        val o = CfStateFlush.flush({ true }, { false }, { true })
        assertEquals(CfStateFlush.Outcome(headersLanded = true, ledgerLanded = false), o)
    }

    @Test fun `a shut checkpoint gate does not write the ledger and counts as not landed`() {
        var ledgerWrites = 0
        val o = CfStateFlush.flush(null, { ledgerWrites++; true }, { false })
        assertEquals(0, ledgerWrites)
        assertFalse(o.ledgerLanded)
    }

    @Test fun `the gate is asked only when a ledger is pending`() {
        var asked = 0
        val o = CfStateFlush.flush({ true }, null, { asked++; true })
        assertEquals(0, asked)
        assertTrue(o.allLanded)
    }

    @Test fun `a write that throws counts as not landed`() {
        val o = CfStateFlush.flush({ error("x") }, { error("y") }, { true })
        assertEquals(CfStateFlush.Outcome(headersLanded = false, ledgerLanded = false), o)
    }
}
