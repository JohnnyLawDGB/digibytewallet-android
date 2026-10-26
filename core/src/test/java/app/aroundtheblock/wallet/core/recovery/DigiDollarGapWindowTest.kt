package app.aroundtheblock.wallet.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 4.1.2 row 19: the DigiDollar recovery scan widens a chain while its far end is in use. A wallet past
 * ten DigiDollar sends with change has its dollars beyond the old fixed window of 10 internal addresses.
 */
class DigiDollarGapWindowTest {

    @Test fun `an unused chain keeps its window`() {
        assertEquals(10, DigiDollarGapWindow.next(count = 10, highestUsed = null, gap = 10))
    }

    @Test fun `use near the end widens the window to a gap past it`() {
        // Twelve sends with change: internal indexes 0..11 used; index 9 is inside the first window.
        assertEquals(20, DigiDollarGapWindow.next(count = 10, highestUsed = 9, gap = 10))
        // The next pass sees index 11 used and goes on to 22.
        assertEquals(22, DigiDollarGapWindow.next(count = 20, highestUsed = 11, gap = 10))
    }

    @Test fun `a window already a gap past the last use stops growing`() {
        assertEquals(22, DigiDollarGapWindow.next(count = 22, highestUsed = 11, gap = 10))
    }

    @Test fun `the window never passes the cap`() {
        assertEquals(DigiDollarGapWindow.MAX, DigiDollarGapWindow.next(count = 995, highestUsed = 994, gap = 10))
    }

    @Test fun `twelve sends are found where the fixed window stopped at ten`() {
        // Simulates the scan loop: internal addresses 0..11 have history.
        val used = (0..11).toSet()
        var count = 10
        while (true) {
            val highest = used.filter { it < count }.maxOrNull()
            val next = DigiDollarGapWindow.next(count, highest, gap = 10)
            if (next == count) break
            count = next
        }
        assertEquals("every used index is inside the final window", true, used.all { it < count })
    }

    @Test fun `a window the cap stopped is reported as capped, one a gap past its last use is not`() {
        assertEquals(true, DigiDollarGapWindow.capped(highestUsed = 995, gap = 10))
        assertEquals(false, DigiDollarGapWindow.capped(highestUsed = 11, gap = 10))
        assertEquals(false, DigiDollarGapWindow.capped(highestUsed = null, gap = 10))
    }
}
