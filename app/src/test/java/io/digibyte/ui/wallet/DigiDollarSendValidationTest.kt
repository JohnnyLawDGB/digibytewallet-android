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

    /** A DigiDollar amount is a whole number of cents: text either names one or is refused. */
    @Test fun usdTextIsWholeCentsOrRefused() {
        assertEquals(null,  SendViewModel.parseUsdToCents("1.005"))
        assertEquals(null,  SendViewModel.parseUsdToCents("1.0000"))
        assertEquals(null,  SendViewModel.parseUsdToCents("1e5"))
        assertEquals(null,  SendViewModel.parseUsdToCents("NaN"))
        assertEquals(null,  SendViewModel.parseUsdToCents("Infinity"))
        assertEquals(100L,  SendViewModel.parseUsdToCents("1.00"))
        assertEquals(10_000_000L, SendViewModel.parseUsdToCents("100000.00"))
    }

    /** The dollar field reads a comma the way the DGB field beside it does. */
    @Test fun usdTextReadsSeparatorsLikeTheDgbField() {
        assertEquals(150L,     SendViewModel.parseUsdToCents("1,50"))
        assertEquals(123_450L, SendViewModel.parseUsdToCents("1,234.50"))
        assertEquals(null,     SendViewModel.parseUsdToCents("1,234"))
        assertEquals(null,     SendViewModel.parseUsdToCents("1,2,3"))
    }

    /** MAX writes into the amount field, so its text cannot follow the phone's language. */
    @Test fun maxFillsTheFieldWithPlainTextInEveryLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("50.00", SendViewModel.ddCentsToPlainUsd(5000))
            assertEquals(5000L, SendViewModel.parseUsdToCents(SendViewModel.ddCentsToPlainUsd(5000)))
            assertEquals("$1,234.56", SendViewModel.formatDdUsd(123456))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
