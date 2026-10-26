package app.aroundtheblock.wallet.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.1.2 row 30 (Aguz Laks 1a14a72d): a send the core calls invalid is removed only once the verdict has
 * held. It was removed at the first "invalid", though an unconfirmed send that lost an ordering race to
 * another unconfirmed wallet transaction is marked invalid too, and can still be the one that confirms.
 */
class InvalidSendRemovalTest {

    private var now = 0L
    private val hold = RefusalHold(clock = { now }, manager = { 1L }, holdMs = 600_000L)

    @Test fun `the first invalid reading removes nothing`() {
        assertFalse(InvalidSendRemoval.mayRemove(hold, "t") { false })
    }

    @Test fun `an invalid verdict that holds for the hold time may be removed`() {
        assertFalse(InvalidSendRemoval.mayRemove(hold, "t") { false })
        now = 600_000L
        assertTrue(InvalidSendRemoval.mayRemove(hold, "t") { false })
    }

    @Test fun `a send that reads valid again starts over`() {
        assertFalse(InvalidSendRemoval.mayRemove(hold, "t") { false })
        now = 300_000L
        assertFalse(InvalidSendRemoval.mayRemove(hold, "t") { true })     // it won the race
        now = 600_000L
        assertFalse(InvalidSendRemoval.mayRemove(hold, "t") { false })    // invalid again: a new first sighting
    }

    @Test fun `its sightings never mix with a refusal sighting of the same send`() {
        assertFalse(hold.observe("t") { true })                       // a peer-refusal sighting
        now = 600_000L
        assertFalse("an invalid verdict borrowed the refusal's first sighting",
            InvalidSendRemoval.mayRemove(hold, "t") { false })
    }
}
