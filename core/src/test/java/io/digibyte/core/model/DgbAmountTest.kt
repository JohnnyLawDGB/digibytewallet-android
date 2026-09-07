package io.digibyte.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DGB text ↔ satoshis must be EXACT.
 *
 * Every conversion in the wallet used to be `(text.toDouble() * 100_000_000).toLong()`. A double
 * cannot hold most decimal fractions, and `toLong` truncates, so `0.29` became 28,999,999 sats and
 * one dust-code deposit amount in fifteen (`N.0000CCCC`, the digiscope tip-wallet format) landed
 * one satoshi low — a different code, an orphaned deposit. Reported 2026-09-07 after a user funded
 * the tip wallet from this app; the same defect under-paid every hand-typed 0.29 DGB send.
 */
class DgbAmountTest {

    @Test fun `plain amounts convert exactly, not one satoshi low`() {
        assertEquals(29_000_000L, DgbAmount.toSats("0.29"))
        assertEquals(57_000_000L, DgbAmount.toSats("0.57"))
        assertEquals(230_000_000L, DgbAmount.toSats("2.3"))
        assertEquals(1_070_000_000L, DgbAmount.toSats("10.7"))
        assertEquals(1_999_000_000L, DgbAmount.toSats("19.99"))
        assertEquals(150_000_000L, DgbAmount.toSats("1.5"))
        assertEquals(200_000_000L, DgbAmount.toSats("2"))
    }

    @Test fun `every four-digit dust code survives the round trip`() {
        for (whole in listOf(1L, 5L, 25L, 1000L)) {
            for (code in 0..9999) {
                val text = "$whole.0000" + code.toString().padStart(4, '0')
                val expected = whole * 100_000_000L + code
                assertEquals(text, expected, DgbAmount.toSats(text))
                assertEquals(expected, DgbAmount.toSats(DgbAmount.format(expected)))
            }
        }
    }

    @Test fun `grouping commas and surrounding whitespace are tolerated`() {
        assertEquals(123_450_000_000L, DgbAmount.toSats("1,234.5"))
        assertEquals(123_456_750_000_000L, DgbAmount.toSats("1,234,567.5"))
        assertEquals(29_000_000L, DgbAmount.toSats("  0.29 "))
    }

    /**
     * Seven of the app's languages write the decimal separator as a comma, and the amount field
     * uses the platform decimal keyboard. Stripping every comma read "0,29" as 29 DGB — a hundred
     * times what the confirmation row showed. A lone comma with no dot IS the decimal point; a
     * string with several commas and no dot is ambiguous and is refused rather than guessed.
     */
    @Test fun `a lone decimal comma is a decimal point, never a grouping separator`() {
        assertEquals(29_000_000L, DgbAmount.toSats("0,29"))
        assertEquals(14_100L, DgbAmount.toSats("0,00014100"))
        assertEquals(123_400_000L, DgbAmount.toSats("1,234"))
        assertNull(DgbAmount.toSats("1,2,3"))
        assertNull(DgbAmount.toSats("1,234,567"))
    }

    /** A QR is untrusted input and is parsed on the main thread; a huge exponent must not stall. */
    @Test(timeout = 2_000) fun `an absurd magnitude is rejected without materialising it`() {
        assertNull(DgbAmount.toSats("1e50000000"))
        assertNull(DgbAmount.toSats("1e999999999"))
        assertNull(DgbAmount.toSats("1e2147483647"))
        assertNull(DgbAmount.toSats("1e-50000000"))
    }

    @Test fun `long overflow is rejected and the exact ceiling is accepted`() {
        assertNull(DgbAmount.toSats("99999999999999999"))
        assertNull(DgbAmount.toSats("92233720368.54775808"))
        assertEquals(Long.MAX_VALUE, DgbAmount.toSats("92233720368.54775807"))
        assertEquals(10_000_000_000L, DgbAmount.toSats("1e2"))
    }

    @Test fun `more than eight decimals is rejected, trailing zeros are not`() {
        assertNull(DgbAmount.toSats("1.123456789"))
        assertEquals(110_000_000L, DgbAmount.toSats("1.100000000"))
        assertEquals(100_000_000L, DgbAmount.toSats("1.00000000"))
    }

    @Test fun `negative, empty and non-numeric are rejected, zero is allowed`() {
        assertNull(DgbAmount.toSats("-1"))
        assertNull(DgbAmount.toSats(""))
        assertNull(DgbAmount.toSats("   "))
        assertNull(DgbAmount.toSats("abc"))
        assertNull(DgbAmount.toSats("1.2.3"))
        assertEquals(0L, DgbAmount.toSats("0"))
    }

    @Test fun `format is plain decimal with no exponent and no grouping`() {
        assertEquals("0.29", DgbAmount.format(29_000_000L))
        assertEquals("5.00001234", DgbAmount.format(500_001_234L))
        assertEquals("1", DgbAmount.format(100_000_000L))
        assertEquals("1234.5", DgbAmount.format(123_450_000_000L))
        assertEquals("0.00000001", DgbAmount.format(1L))
        assertEquals("0", DgbAmount.format(0L))
    }
}
