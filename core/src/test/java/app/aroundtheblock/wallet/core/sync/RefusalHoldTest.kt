package app.aroundtheblock.wallet.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refusal is acted on only once it has held long enough for every connected peer to answer, on one
 * peer manager.
 *
 * The core's "refused by the network" answer can be 1 moments after the wallet starts: two network
 * groups rejected the send before an honest peer had announced it or asked for it. The stranded-send
 * sweep removes a plain DGB send on that answer, which gives its coins back; taken at its first
 * sighting, a send the network accepts could be removed and paid again while the first still confirms.
 * So the sweep acts only on a refusal it has seen at two observations at least ten minutes apart, and
 * at every observation in between: an observation where it is not refused forgets when it was first
 * seen. The core keeps its relay evidence per peer manager, and a rebuilt manager starts with none, so
 * both observations must come from the same manager: a hold started on one never completes on another.
 */
class RefusalHoldTest {

    private var now = 1_000_000L
    private var manager: Long? = 1L
    private val hold = RefusalHold(clock = { now }, manager = { manager })
    private val tx = "a".repeat(64)
    private val other = "b".repeat(64)
    private val min = 60_000L
    private val rejected = PublishOutcome.of(PublishOutcome.EINVAL)

    private fun seen(txid: String = tx, refused: Boolean = true) = hold.observe(txid) { refused }

    @Test fun `a refusal seen once is not acted on`() {
        assertFalse("the first sighting of a refusal is acted on at once", seen())
    }

    @Test fun `the sweep keeps a terminally rejected plain send whose refusal was just seen`() {
        assertEquals("a refusal seen once removes the send and unlocks its coins",
            PublishOutcome.Action.KEEP, rejected.actionFor(plainDgb = true, refusedByPeers = seen()))
    }

    @Test fun `a refusal is acted on once it has held for ten minutes`() {
        assertFalse(seen())
        now += 10 * min - 1
        assertFalse("acted on before ten minutes had passed", seen())
        now += 1
        assertTrue("a refusal that held at two observations ten minutes apart is not acted on", seen())
        assertEquals(PublishOutcome.Action.REMOVE_AND_UNLOCK, rejected.actionFor(plainDgb = true, refusedByPeers = seen()))
    }

    @Test fun `ten minutes of observations that were each refused, at the sweep's cadence`() {
        val sightings = (0..7).map { i -> now = 1_000_000L + i * 90_000L; seen() }
        assertEquals("acted on inside the ten minutes", List(7) { false }, sightings.take(7))
        assertTrue("not acted on at the first sweep ten minutes after the first sighting", sightings.last())
    }

    @Test fun `an answer that turns false forgets when the refusal was first seen`() {
        assertFalse(seen())
        now += 5 * min
        assertFalse(seen(refused = false))
        now += 5 * min
        assertFalse("a refusal interrupted by a not-refused answer still counts from its first sighting", seen())
        now += 10 * min - 1
        assertFalse(seen())
        now += 1
        assertTrue(seen())
    }

    @Test fun `each send has its own first sighting`() {
        assertFalse(seen())
        now += 5 * min
        assertFalse(seen(other))
        now += 5 * min
        assertTrue(seen())
        assertFalse("one send's refusal time was used for another's", seen(other))
        now += 5 * min
        assertTrue(seen(other))
    }

    @Test fun `a clock that steps back never shortens the hold`() {
        assertFalse(seen())
        now -= 30 * min
        assertFalse("a clock stepping back was read as time passing", seen())
        now += 30 * min + 10 * min - 1
        assertFalse(seen())
        now += 1
        assertTrue(seen())
    }

    @Test fun `a new session remembers nothing`() {
        assertFalse(seen())
        now += 60 * min
        assertFalse("a refusal first seen in another session counted in this one",
            RefusalHold(clock = { now }, manager = { manager }).observe(tx) { true })
    }

    @Test fun `a forgotten send starts again`() {
        assertFalse(seen())
        now += 10 * min
        hold.forget(tx)
        assertFalse("a forgotten send kept its first sighting", seen())
    }

    @Test fun `the hold is ten minutes`() {
        assertEquals(10 * min, RefusalHold.HOLD_MS)
    }

    // ---- one peer manager: a hold started on one never completes on another ----

    @Test fun `a refusal first seen before the peer manager is rebuilt is not acted on after it`() {
        assertFalse(seen())
        manager = 2L                      // rebuilt: the new manager's relay records start empty
        now += 10 * min
        assertFalse("a refusal first seen on one peer manager was acted on from another's answer", seen())
        now += 10 * min - 1
        assertFalse("the hold did not restart on the rebuilt peer manager", seen())
        now += 1
        assertTrue("a refusal held ten minutes on one peer manager is not acted on", seen())
    }

    @Test fun `the sweep keeps a send whose refusal straddles a rebuilt peer manager`() {
        assertEquals(PublishOutcome.Action.KEEP, rejected.actionFor(plainDgb = true, refusedByPeers = seen()))
        manager = 2L
        now += 10 * min
        assertEquals("removed on a rebuilt peer manager's first answer",
            PublishOutcome.Action.KEEP, rejected.actionFor(plainDgb = true, refusedByPeers = seen()))
    }

    @Test fun `an answer read while the peer manager was rebuilt is no sighting`() {
        assertFalse(seen())
        now += 10 * min
        assertFalse("an answer read across a rebuild counted", hold.observe(tx) { manager = 2L; true })
        assertFalse("the hold did not restart after an answer read across a rebuild", seen())
        now += 10 * min
        assertTrue(seen())
    }

    @Test fun `an unknown peer manager never completes a hold`() {
        manager = null
        assertFalse(seen())
        now += 30 * min
        assertFalse("a refusal from an unknown peer manager was acted on", seen())
    }
}
