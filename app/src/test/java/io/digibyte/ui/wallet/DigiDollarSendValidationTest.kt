package io.digibyte.ui.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class DigiDollarSendValidationTest {
    @Test fun usdParsesToCents() {
        assertEquals(4000L, SendViewModel.parseUsdToCents("40"))
        assertEquals(4000L, SendViewModel.parseUsdToCents("40.00"))
        assertEquals(4050L, SendViewModel.parseUsdToCents("40.50"))
        assertEquals(9L,    SendViewModel.parseUsdToCents("0.09"))
        assertEquals(null,  SendViewModel.parseUsdToCents(""))
        assertEquals(null,  SendViewModel.parseUsdToCents("abc"))
        assertEquals(null,  SendViewModel.parseUsdToCents("-5"))
    }
    @Test fun ddAmountBoundsAndBalance() {
        // valid: within [100,10000000] and <= ddBalance
        org.junit.Assert.assertTrue(SendViewModel.ddAmountValid(4000, ddBalance = 10000))
        // below $1
        org.junit.Assert.assertFalse(SendViewModel.ddAmountValid(50, ddBalance = 10000))
        // above $100k
        org.junit.Assert.assertFalse(SendViewModel.ddAmountValid(10000001, ddBalance = 20000000))
        // exceeds balance
        org.junit.Assert.assertFalse(SendViewModel.ddAmountValid(4000, ddBalance = 1000))
    }
}
