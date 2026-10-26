package app.aroundtheblock.wallet.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The bridge's `[kind, cents, changeCents]` read as a [DigiDollarEvent], and nothing else read as one. */
class DigiDollarEventTest {

    @Test fun `each kind the bridge reports reads as that kind`() {
        val kinds = mapOf(
            0L to DigiDollarEvent.Kind.NONE, 1L to DigiDollarEvent.Kind.MINTED, 2L to DigiDollarEvent.Kind.SENT,
            3L to DigiDollarEvent.Kind.RECEIVED, 4L to DigiDollarEvent.Kind.SELF_TRANSFER, 5L to DigiDollarEvent.Kind.REDEEMED,
        )
        for ((code, kind) in kinds) {
            assertEquals(DigiDollarEvent(kind, 6_000L, 4_000L), DigiDollarEvent.of(longArrayOf(code, 6_000L, 4_000L)))
        }
    }

    @Test fun `nothing that is not an event reads as one`() {
        assertNull(DigiDollarEvent.of(null))
        assertNull("an unknown kind", DigiDollarEvent.of(longArrayOf(6L, 100L, 0L)))
        assertNull("a negative amount", DigiDollarEvent.of(longArrayOf(2L, -1L, 0L)))
        assertNull("a negative change", DigiDollarEvent.of(longArrayOf(5L, 100L, -1L)))
        assertNull("the wrong shape", DigiDollarEvent.of(longArrayOf(2L, 100L)))
    }
}
