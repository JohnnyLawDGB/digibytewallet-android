package io.digibyte.ui.asset

import io.digibyte.core.asset.AssetTxAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which count an asset quantity's plural is chosen by, and how the quantity is written. */
class AssetPluralCountTest {

    @Test fun `a plural is chosen by whole units`() {
        assertEquals(1, assetPluralCount(1, 0))
        assertEquals(0, assetPluralCount(0, 0))
        assertEquals(21, assetPluralCount(21, 0))
        assertEquals(2, assetPluralCount(250, 2))     // "2.50"
        assertEquals(0, assetPluralCount(5, 2))       // "0.05"
        assertEquals(0, assetPluralCount(-3, 0))
    }

    @Test fun `a count beyond Int keeps the digits plural rules read`() {
        val big = 12_345_678_901L
        val chosen = assetPluralCount(big, 0)
        assertEquals(big % 10, chosen % 10L)
        assertEquals(big % 100, chosen % 100L)
        assertEquals(big % 1_000_000, chosen % 1_000_000L)
        assertTrue(chosen > 1)
        assertEquals(1_000_000, assetPluralCount(5_000_000_000L, 0))   // a whole number of millions stays one
    }

    @Test fun `an asset amount is written at its divisibility`() {
        assertEquals("50.00", AssetTxAmount(units = 5000, decimals = 2, label = null, name = null).quantityText)
        assertEquals("1", AssetTxAmount(units = 1, decimals = 0, label = null, name = null).quantityText)
    }
}
