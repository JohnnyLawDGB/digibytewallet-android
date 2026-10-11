package app.aroundtheblock.wallet.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The store's two views of its keys: every recorded send, and the ones the stranded-send sweep
 * still looks after. A settled send keeps its record (the activity list reads it) but leaves the
 * sweep's set. Pure — the key layout is the contract, no Context needed.
 */
class OutgoingTxStorePendingTest {

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    private val keys = listOf(
        "$a.sent", "$a.fee", "$a.to", "$a.self",
        "$b.sent", "$b.fee", "$b.to", "$b.self", "$b.settled", "$b.sweeps", "$b.sweptAt",
        "$c.sent", "$c.sweeps", "$c.sweptAt",
        "$a.settled".removeSuffix(".settled") + ".sentinel", // a stray key that ends in neither suffix
    )

    @Test fun `every recorded send is listed, settled or not`() {
        assertEquals(setOf(a, b, c), OutgoingTxStore.recordedTxidsOf(keys))
    }

    @Test fun `a settled send leaves the pending set but keeps its record`() {
        assertEquals(setOf(a, c), OutgoingTxStore.pendingTxidsOf(keys))
        assertEquals(setOf(a, b, c), OutgoingTxStore.recordedTxidsOf(keys))
    }

    @Test fun `a settled mark without a record lists nothing`() {
        assertEquals(emptySet<String>(), OutgoingTxStore.pendingTxidsOf(listOf("$b.settled")))
        assertEquals(emptySet<String>(), OutgoingTxStore.recordedTxidsOf(listOf("$b.settled")))
    }

    @Test fun `attempt counters alone are not a record`() {
        assertEquals(emptySet<String>(), OutgoingTxStore.pendingTxidsOf(listOf("$c.sweeps", "$c.sweptAt")))
    }
}
