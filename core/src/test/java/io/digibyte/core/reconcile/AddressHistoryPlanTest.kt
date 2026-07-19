package io.digibyte.core.reconcile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure planning coverage for the address-history backstop (build #1): which
 * txids to import given per-address histories and the wallet's already-known
 * set. No network, no NativeBridge (which can't load on the host JVM).
 */
class AddressHistoryPlanTest {

    @Test fun extracts_known_txids_from_details() {
        val details = """
            aaa|100|1|500|1700|0|100
            bbb|0|1|2147483647|1700|0|0
        """.trimIndent()
        assertEquals(setOf("aaa", "bbb"), extractKnownTxids(details))
    }

    @Test fun blank_lines_ignored() {
        assertEquals(emptySet<String>(), extractKnownTxids("\n  \n"))
    }

    @Test fun plan_drops_known_and_dedups_across_addresses() {
        val a = listOf(AddressTx("known", 10), AddressTx("new1", 20))
        val b = listOf(AddressTx("new1", 20), AddressTx("new2", 30))
        val plan = planHistoryImport(listOf(a, b), setOf("known"))
        assertEquals(listOf("new1", "new2"), plan.map { it.txid })
    }

    @Test fun plan_keeps_highest_height_for_duplicate_txid() {
        val plan = planHistoryImport(
            listOf(listOf(AddressTx("t", 0)), listOf(AddressTx("t", 99))),
            emptySet(),
        )
        assertEquals(1, plan.size)
        assertEquals(99L, plan[0].height)
    }

    @Test fun empty_history_yields_empty_plan() {
        assertEquals(emptyList<AddressTx>(), planHistoryImport(emptyList(), setOf("x")))
    }
}
