package io.digibyte.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How wide an activity row's amount column may be: the pure half of the row layout, whose wiring
 * TransactionRowLayoutGateTest pins.
 */
class TransactionRowLayoutTest {

    @Test fun `a row with a kind chip leaves the left column more than half its width`() {
        for (available in listOf(400, 708, 1080, 1440)) {
            val cap = amountColumnMaxWidth(available, hasChip = true)
            assertTrue("cap $cap of $available", cap in 1 until available / 2 + 1)
            assertTrue(available - cap > available / 2)
        }
    }

    @Test fun `a plain DGB row lets the amount take more, never all`() {
        val available = 708
        val plain = amountColumnMaxWidth(available, hasChip = false)
        assertTrue(plain > amountColumnMaxWidth(available, hasChip = true))
        assertTrue(plain < available)
    }

    @Test fun `no width gives no width`() {
        assertEquals(0, amountColumnMaxWidth(0, hasChip = true))
        assertEquals(0, amountColumnMaxWidth(-5, hasChip = false))
    }
}
