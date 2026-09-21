package io.digibyte.core.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Dollar text ↔ whole cents must be EXACT, and must read separators the way [DgbAmount] does.
 *
 * A DigiDollar amount is an integer number of cents. The text a user types either names such an
 * integer or it does not; it is never rounded into one. The two amount fields on the send screen
 * sit side by side, so they also have to agree on what a comma means.
 */
class UsdCentsTest {

    @Test fun `whole cents convert exactly`() {
        assertEquals(4000L, UsdCents.parse("40"))
        assertEquals(4000L, UsdCents.parse("40.00"))
        assertEquals(4050L, UsdCents.parse("40.50"))
        assertEquals(4050L, UsdCents.parse("40.5"))
        assertEquals(9L, UsdCents.parse("0.09"))
        assertEquals(50L, UsdCents.parse(".5"))
        assertEquals(100L, UsdCents.parse("1.00"))
        assertEquals(10_000_000L, UsdCents.parse("100000.00"))
        assertEquals(29L, UsdCents.parse("  0.29 "))
        assertEquals(0L, UsdCents.parse("0"))
    }

    @Test fun `more than two typed decimals is refused, never rounded`() {
        assertNull(UsdCents.parse("1.005"))
        assertNull(UsdCents.parse("1.0000"))
        assertNull(UsdCents.parse("0.001"))
        assertNull(UsdCents.parse("2.675"))
        assertNull(UsdCents.parse("40.500"))
    }

    /** One rule for both amount fields: see `DgbAmountTest` for the DGB side of it. */
    @Test fun `separators are read the way DGB amounts read them`() {
        assertEquals(150L, UsdCents.parse("1,50"))
        assertEquals(10L, UsdCents.parse("0,1"))
        assertEquals(123_450L, UsdCents.parse("1,234.50"))
        assertEquals(123_456_750L, UsdCents.parse("1,234,567.5"))
        // A lone comma is the decimal point, so this names three decimals — which cents do not have.
        assertNull(UsdCents.parse("1,234"))
        assertNull(UsdCents.parse("1,2,3"))
        assertNull(UsdCents.parse("1,234,567"))
    }

    @Test fun `exponents, signs and non-finite text are refused`() {
        assertNull(UsdCents.parse("1e5"))
        assertNull(UsdCents.parse("1E2"))
        assertNull(UsdCents.parse("NaN"))
        assertNull(UsdCents.parse("Infinity"))
        assertNull(UsdCents.parse("+1"))
        assertNull(UsdCents.parse("-1"))
        assertNull(UsdCents.parse("-0"))
        assertNull(UsdCents.parse("1.5f"))
        assertNull(UsdCents.parse("0x10"))
    }

    @Test fun `empty and non-numeric text is refused`() {
        assertNull(UsdCents.parse(""))
        assertNull(UsdCents.parse("   "))
        assertNull(UsdCents.parse("."))
        assertNull(UsdCents.parse("abc"))
        assertNull(UsdCents.parse("1.2.3"))
        assertNull(UsdCents.parse("1 000"))
        assertNull(UsdCents.parse("$5"))
    }

    @Test(timeout = 2_000) fun `out of range is refused and the exact ceiling is accepted`() {
        assertEquals(Long.MAX_VALUE, UsdCents.parse("92233720368547758.07"))
        assertNull(UsdCents.parse("92233720368547758.08"))
        assertNull(UsdCents.parse("99999999999999999999"))
        assertNull(UsdCents.parse("9".repeat(100_000)))
    }

    @Test fun `format is plain, two decimals, no grouping`() {
        assertEquals("50.00", UsdCents.format(5000L))
        assertEquals("0.09", UsdCents.format(9L))
        assertEquals("0.00", UsdCents.format(0L))
        assertEquals("1234.56", UsdCents.format(123_456L))
        assertEquals("100000.00", UsdCents.format(10_000_000L))
    }

    /** The text goes back into an amount field, so it cannot depend on the phone's language. */
    @Test fun `format does not follow the default locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("50.00", UsdCents.format(5000L))
            assertEquals("1234.56", UsdCents.format(123_456L))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun `every cent value survives the round trip`() {
        for (cents in 0L..200_000L) {
            assertEquals(cents, UsdCents.parse(UsdCents.format(cents)))
        }
        for (cents in listOf(10_000_000L, 123_456_789_012L, Long.MAX_VALUE)) {
            assertEquals(cents, UsdCents.parse(UsdCents.format(cents)))
        }
    }

    // ── The dollars line beside a typed DGB amount ───────────────────────────────────────────

    @Test fun `the preview reads the DGB text with the parser the send uses`() {
        assertEquals("1.00", UsdCents.previewForDgb("0,5", 2.0))
        assertEquals("1.00", UsdCents.previewForDgb("0.5", 2.0))
        assertEquals("1234.50", UsdCents.previewForDgb("1,234.5", 1.0))
        assertEquals("0.61", UsdCents.previewForDgb("100", 0.00612))
        assertEquals("0.00", UsdCents.previewForDgb("0", 0.00612))
    }

    @Test fun `the preview is empty when the text is not an amount or there is no price`() {
        assertEquals("", UsdCents.previewForDgb("", 0.01))
        assertEquals("", UsdCents.previewForDgb("abc", 0.01))
        assertEquals("", UsdCents.previewForDgb("1,2,3", 0.01))
        assertEquals("", UsdCents.previewForDgb("1", 0.0))
        assertEquals("", UsdCents.previewForDgb("1", -1.0))
        assertEquals("", UsdCents.previewForDgb("1", Double.NaN))
        assertEquals("", UsdCents.previewForDgb("1", Double.POSITIVE_INFINITY))
    }

    /** Both directions write into an editable amount field: plain decimals, never grouped. */
    @Test fun `text written into an amount field is plain and parses back to itself`() {
        assertEquals("1234.50", UsdCents.previewForDgb("1234.5", 1.0))
        assertEquals("1234", UsdCents.dgbTextFor("12.34", 0.01))
        assertEquals(123_400_000_000L, DgbAmount.toSats(UsdCents.dgbTextFor("12.34", 0.01)))
        assertEquals(123_450L, UsdCents.parse(UsdCents.previewForDgb("1234.5", 1.0)))
    }

    @Test fun `a typed dollar amount converts with the same separator rule`() {
        assertEquals("50", UsdCents.dgbTextFor("0,5", 0.01))
        assertEquals("50", UsdCents.dgbTextFor("0.50", 0.01))
        assertEquals("816.99346405", UsdCents.dgbTextFor("5", 0.00612))
        assertEquals("", UsdCents.dgbTextFor("", 0.01))
        assertEquals("", UsdCents.dgbTextFor("1.005", 0.01))
        assertEquals("", UsdCents.dgbTextFor("5", 0.0))
        assertEquals("", UsdCents.dgbTextFor("5", Double.NaN))
    }
}
