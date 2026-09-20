package io.digibyte.core.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Asset quantity text ↔ whole asset units must be EXACT, at the asset's own divisibility, and must
 * read separators the way [DgbAmount] does.
 *
 * An asset moves in whole units, and its divisibility says how many decimals a shown quantity has.
 * The text a user types either names a positive whole number of units or it does not; it is never
 * rounded into one, and it is never read by a looser rule than the two other amount fields use.
 */
class AssetQuantityTest {

    @Test fun `a quantity converts exactly at divisibility 0`() {
        assertEquals(1L, AssetQuantity.parse("1", 0))
        assertEquals(100L, AssetQuantity.parse("100", 0))
        assertEquals(100L, AssetQuantity.parse("0100", 0))
        assertEquals(3L, AssetQuantity.parse(" 3 ", 0))
        assertEquals(1_234_567L, AssetQuantity.parse("1234567", 0))
    }

    @Test fun `a quantity converts exactly at divisibility 2`() {
        assertEquals(100L, AssetQuantity.parse("1", 2))
        assertEquals(150L, AssetQuantity.parse("1.5", 2))
        assertEquals(150L, AssetQuantity.parse("1.50", 2))
        assertEquals(150L, AssetQuantity.parse("01.5", 2))
        assertEquals(1L, AssetQuantity.parse("0.01", 2))
        assertEquals(29L, AssetQuantity.parse("0.29", 2))
        assertEquals(50L, AssetQuantity.parse(".5", 2))
        assertEquals(300L, AssetQuantity.parse(" 3 ", 2))
    }

    @Test fun `a quantity converts exactly at divisibility 8`() {
        assertEquals(100_000_000L, AssetQuantity.parse("1", 8))
        assertEquals(1L, AssetQuantity.parse("0.00000001", 8))
        assertEquals(29_000_000L, AssetQuantity.parse("0.29", 8))
        assertEquals(123_450_000_000L, AssetQuantity.parse("1234.5", 8))
        assertEquals(300_000_000L, AssetQuantity.parse(" 3 ", 8))
    }

    /** One rule for every amount field: see `DgbAmountTest` and `UsdCentsTest` for the other two. */
    @Test fun `separators are read the way DGB amounts read them`() {
        assertEquals(150L, AssetQuantity.parse("1,5", 2))
        assertEquals(15L, AssetQuantity.parse("1,5", 1))
        assertEquals(150_000_000L, AssetQuantity.parse("1,5", 8))
        assertEquals(123_450L, AssetQuantity.parse("1,234.5", 2))
        assertEquals(123_456_750L, AssetQuantity.parse("1,234,567.5", 2))
        // A lone comma is the decimal point, so this names three decimals.
        assertEquals(1_234L, AssetQuantity.parse("1,234", 3))
        assertNull(AssetQuantity.parse("1,234", 2))
        // It is the decimal point for an asset with no decimals too — never grouping.
        assertNull(AssetQuantity.parse("1,5", 0))
        assertNull(AssetQuantity.parse("1,234", 0))
        assertNull(AssetQuantity.parse("1,2,3", 2))
        assertNull(AssetQuantity.parse("1,234,567", 0))
    }

    @Test fun `exponents, signs and non-finite text are refused`() {
        for (divisibility in listOf(0, 2, 8)) {
            for (text in listOf("1e2", "1E2", "1e0", "1e-2", "NaN", "Infinity", "+1", "-1", "-0", "1.5f", "0x10", "1_000")) {
                assertNull("$text at $divisibility", AssetQuantity.parse(text, divisibility))
            }
        }
    }

    @Test fun `zero is not a quantity`() {
        assertNull(AssetQuantity.parse("0", 0))
        assertNull(AssetQuantity.parse("00", 0))
        assertNull(AssetQuantity.parse("0", 2))
        assertNull(AssetQuantity.parse("0.0", 2))
        assertNull(AssetQuantity.parse("0.00", 2))
        assertNull(AssetQuantity.parse("0,00", 2))
        assertNull(AssetQuantity.parse("0.00000000", 8))
    }

    @Test fun `more typed decimals than the asset has is refused, never rounded`() {
        assertNull(AssetQuantity.parse("1.5", 0))
        assertNull(AssetQuantity.parse("1.0", 0))
        assertNull(AssetQuantity.parse("1.234", 2))
        assertNull(AssetQuantity.parse("1.500", 2))
        assertNull(AssetQuantity.parse("0.001", 2))
        assertNull(AssetQuantity.parse("0.000000001", 8))
        assertNull(AssetQuantity.parse("1.000000000", 8))
    }

    @Test fun `empty and non-numeric text is refused`() {
        for (divisibility in listOf(0, 2, 8)) {
            for (text in listOf("", "   ", ".", ",", "abc", "1.2.3", "1 000", "5 tokens", "#5")) {
                assertNull("'$text' at $divisibility", AssetQuantity.parse(text, divisibility))
            }
        }
    }

    @Test(timeout = 2_000) fun `out of range is refused and the exact ceiling is accepted`() {
        assertEquals(Long.MAX_VALUE, AssetQuantity.parse("9223372036854775807", 0))
        assertNull(AssetQuantity.parse("9223372036854775808", 0))
        assertEquals(Long.MAX_VALUE, AssetQuantity.parse("92233720368547758.07", 2))
        assertNull(AssetQuantity.parse("92233720368547758.08", 2))
        assertEquals(Long.MAX_VALUE, AssetQuantity.parse("92233720368.54775807", 8))
        assertNull(AssetQuantity.parse("92233720368.54775808", 8))
        assertNull(AssetQuantity.parse("99999999999999999999", 0))
        assertNull(AssetQuantity.parse("9".repeat(100_000), 0))
        assertNull(AssetQuantity.parse("9".repeat(100_000), 8))
    }

    @Test fun `a divisibility no asset has reads nothing`() {
        assertNull(AssetQuantity.parse("1", -1))
        assertNull(AssetQuantity.parse("1", Int.MIN_VALUE))
        assertNull(AssetQuantity.parse("1", 9))
        assertNull(AssetQuantity.parse("0.000000001", 9))
        assertNull(AssetQuantity.parse("1", 18))
    }

    @Test fun `format is plain - no grouping, no exponent, no padding zeros`() {
        assertEquals("5", AssetQuantity.format(5L, 0))
        assertEquals("100", AssetQuantity.format(100L, 0))
        assertEquals("1000000", AssetQuantity.format(1_000_000L, 0))
        assertEquals("1.5", AssetQuantity.format(150L, 2))
        assertEquals("1", AssetQuantity.format(100L, 2))
        assertEquals("1000", AssetQuantity.format(100_000L, 2))
        assertEquals("0.01", AssetQuantity.format(1L, 2))
        assertEquals("1234.56", AssetQuantity.format(123_456L, 2))
        assertEquals("0.00000001", AssetQuantity.format(1L, 8))
        assertEquals("1", AssetQuantity.format(100_000_000L, 8))
        assertEquals("92233720368.54775807", AssetQuantity.format(Long.MAX_VALUE, 8))
        assertEquals("9223372036854775807", AssetQuantity.format(Long.MAX_VALUE, 0))
    }

    /** The text is shown for approval and goes into a quantity field: it cannot follow the phone's language. */
    @Test fun `format does not follow the default locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5", AssetQuantity.format(150L, 2))
            assertEquals("1234.56", AssetQuantity.format(123_456L, 2))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun `every quantity survives the round trip at every divisibility`() {
        for (divisibility in 0..8) {
            for (units in 1L..20_000L) {
                assertEquals(units, AssetQuantity.parse(AssetQuantity.format(units, divisibility), divisibility))
            }
            for (units in listOf(100_000_000L, 123_456_789_012L, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
                assertEquals(units, AssetQuantity.parse(AssetQuantity.format(units, divisibility), divisibility))
            }
        }
    }
}
