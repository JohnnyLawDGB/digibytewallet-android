package io.digibyte.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class MaskedAmountTest {
    @Test fun `visible shows the real value`() {
        assertEquals("1,234.567", maskedAmount(hidden = false, value = "1,234.567"))
        assertEquals("$12.34", maskedAmount(hidden = false, value = "$12.34"))
    }

    @Test fun `hidden shows the mask regardless of the value`() {
        assertEquals(BALANCE_MASK, maskedAmount(hidden = true, value = "1,234.567"))
        assertEquals(BALANCE_MASK, maskedAmount(hidden = true, value = "$0.00"))
        assertEquals(BALANCE_MASK, maskedAmount(hidden = true, value = ""))
    }
}
