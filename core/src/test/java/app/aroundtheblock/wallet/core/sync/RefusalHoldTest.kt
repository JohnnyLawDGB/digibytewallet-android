package app.aroundtheblock.wallet.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refusal is acted on only once it has held long enough for every connected peer to answer.
 *
 * The core's "refused by the network" answer can be 1 moments after the wallet starts: two network
 * groups rejected the send before an honest peer had announced it or asked for it. The stranded-send
 * sweep removes a plain DGB send on that answer, which gives its coins back; taken at its first
 * sighting, a send the network accepts could be removed and paid again while the first still confirms.
 * So the sweep acts only on a refusal it has seen at two observations at least ten minutes apart in
 * the same session, and seen at every observation in between: an observation where it is not refused
 * forgets when it was first seen.
 */
class RefusalHoldTest {

    private var now = 1_000_000L
    private val hold = RefusalHold(clock = { now })
    private val tx = "a".repeat(64)
    private val other = "b".repeat(64)
    private val min = 60_000L

    @Test fun `a refusal seen once is not acted on`() {
        assertFalse("the first sighting of a refusal is acted on at once", hold.observe(tx, refused = true))
    }

    @Test fun `the sweep keeps a terminally rejected plain send whose refusal was just seen`() {
        val action = PublishOutcome.of(PublishOutcome.EINVAL)
            .actionFor(plainDgb = true, refusedByPeers = hold.observe(tx, refused = true))
        assertEquals("a refusal seen once removes the send and unlocks its coins", PublishOutcome.Action.KEEP, action)
    }

    @Test fun `a refusal is acted on once it has held for ten minutes`() {
        assertFalse(hold.observe(tx, refused = true))
        now += 10 * min - 1
        assertFalse("acted on before ten minutes had passed", hold.observe(tx, refused = true))
        now += 1
        assertTrue("a refusal that held at two observations ten minutes apart is not acted on", hold.observe(tx, refused = true))
        assertEquals(
            PublishOutcome.Action.REMOVE_AND_UNLOCK,
            PublishOutcome.of(PublishOutcome.EINVAL).actionFor(plainDgb = true, refusedByPeers = hold.observe(tx, refused = true)),
        )
    }

    @Test fun `ten minutes of observations that were each refused, at the sweep's cadence`() {
        val seen = (0..7).map { i -> now = 1_000_000L + i * 90_000L; hold.observe(tx, refused = true) }
        assertEquals("acted on inside the ten minutes", List(7) { false }, seen.take(7))
        assertTrue("not acted on at the first sweep ten minutes after the first sighting", seen.last())
    }

    @Test fun `an answer that turns false forgets when the refusal was first seen`() {
        assertFalse(hold.observe(tx, refused = true))
        now += 5 * min
        assertFalse(hold.observe(tx, refused = false))
        now += 5 * min
        assertFalse("a refusal interrupted by a not-refused answer still counts from its first sighting", hold.observe(tx, refused = true))
        now += 10 * min - 1
        assertFalse(hold.observe(tx, refused = true))
        now += 1
        assertTrue(hold.observe(tx, refused = true))
    }

    @Test fun `each send has its own first sighting`() {
        assertFalse(hold.observe(tx, refused = true))
        now += 5 * min
        assertFalse(hold.observe(other, refused = true))
        now += 5 * min
        assertTrue(hold.observe(tx, refused = true))
        assertFalse("one send's refusal time was used for another's", hold.observe(other, refused = true))
        now += 5 * min
        assertTrue(hold.observe(other, refused = true))
    }

    @Test fun `a clock that steps back never shortens the hold`() {
        assertFalse(hold.observe(tx, refused = true))
        now -= 30 * min
        assertFalse("a clock stepping back was read as time passing", hold.observe(tx, refused = true))
        now += 30 * min + 10 * min - 1
        assertFalse(hold.observe(tx, refused = true))
        now += 1
        assertTrue(hold.observe(tx, refused = true))
    }

    @Test fun `a new session remembers nothing`() {
        assertFalse(hold.observe(tx, refused = true))
        now += 60 * min
        assertFalse("a refusal first seen in another session counted in this one", RefusalHold(clock = { now }).observe(tx, refused = true))
    }

    @Test fun `a forgotten send starts again`() {
        assertFalse(hold.observe(tx, refused = true))
        now += 10 * min
        hold.forget(tx)
        assertFalse("a forgotten send kept its first sighting", hold.observe(tx, refused = true))
    }

    @Test fun `the hold is ten minutes`() {
        assertEquals(10 * min, RefusalHold.HOLD_MS)
    }
}
