package app.aroundtheblock.wallet.core.model

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
        assertEquals(150_000_000L, DgbAmount.toSats("1,5"))
        assertEquals(1_234_560_000L, DgbAmount.toSats("12,3456"))
        assertNull(DgbAmount.toSats("1,2,3"))
        assertNull(DgbAmount.toSats("1,234,567"))
    }

    /**
     * "1,000" is a thousand DGB to an English reader and one DGB to a German one; the field cannot
     * know which was meant, so a lone comma (no dot) before EXACTLY three digits is no amount, and
     * the field says "Enter a valid amount". The three are the run of digits right after the comma,
     * in the text with its surrounding whitespace dropped, whatever follows them.
     */
    @Test fun `a lone comma before exactly three digits is ambiguous and refused`() {
        for (t in listOf("1,000", "25,500", "0,123", "1,234", " 1,000 ", "1,000e2", "1,000E-2", ",123", "0,000",
            "+1,000", "١,٠٠٠")) {
            assertNull(t, DgbAmount.toSats(t))
        }
        // Not exactly three after the comma: it stays the decimal point.
        assertEquals(100_000_000L, DgbAmount.toSats("1,0000"))
        assertEquals(123_000_000L, DgbAmount.toSats("1,23"))
        assertEquals(100_010_000L, DgbAmount.toSats("1,0001"))
        assertEquals(10_000_000_000L, DgbAmount.toSats("1,00e2"))
        assertEquals(150_000_000_000L, DgbAmount.toSats("1,5e3"))
        assertEquals(100_000_000L, DgbAmount.toSats("1,"))
        // Commas around a dot are grouping, three digits or not.
        assertEquals(100_050_000_000L, DgbAmount.toSats("1,000.50"))
        assertEquals(123_456_780_000_000L, DgbAmount.toSats("1,234,567.8"))
        assertEquals(100_000_000_000L, DgbAmount.toSats("1,000.0"))
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

    /**
     * The text typed is the amount signed, and iOS reads it with the core's BRAmountParseDGB.
     * Kotlin's trim() took the no-break space and every other Unicode space, so "5 " was 5 DGB
     * here and no amount on iOS; only ASCII whitespace surrounds an amount now.
     */
    @Test fun `only ASCII whitespace, as the core and iOS read it`() {
        assertEquals(500_000_000L, DgbAmount.toSats(" \t\n\r\u000C\u000B5 \t\n\r\u000C\u000B"))
        assertNull(DgbAmount.toSats("5\u00a0")) // no-break space
        assertNull(DgbAmount.toSats("\u202f0.29")) // narrow no-break space
        assertNull(DgbAmount.toSats("\u30005")) // ideographic space
        assertNull(DgbAmount.toSats("\u001c5")) // U+001C..U+001F are separators, not whitespace
        assertNull(DgbAmount.toSats("1\u0000")) // an embedded NUL is a character
    }

    /**
     * A local keyboard types local digits. Every Unicode decimal digit is read as its ASCII digit
     * before the core's rule (UsdCents.asciiDigits, the mapping dollar text gets; iOS
     * FiatText.asciiDigits), so "٠٫٢٩" is no amount but "٠.٢٩" and "۰,۲۹" are 0.29 DGB. The Arabic
     * decimal and thousands separators are not mapped. A payment URI's amount is not mapped either:
     * it is read as the core reads it (toSatsAscii, BRPaymentUriParse on iOS).
     */
    @Test fun `local decimal digits are read as ASCII digits`() {
        assertEquals(100_000_000L, DgbAmount.toSats("\u0661")) // Arabic-Indic one
        assertEquals(50_000_000L, DgbAmount.toSats("\u0660.\u0665"))
        assertEquals(29_000_000L, DgbAmount.toSats("\u06f0,\u06f2\u06f9")) // Persian, decimal comma
        assertEquals(150_000_000L, DgbAmount.toSats("\u0967.\u096b")) // Devanagari
        assertEquals(1_200_000_000L, DgbAmount.toSats("\uff11\uff12")) // full width
        assertEquals(100_000_000L, DgbAmount.toSats("\ud835\udfcf")) // U+1D7CF mathematical bold one
        assertEquals(1_100_000_000L, DgbAmount.toSats("1\u0661"))
        assertEquals(10_000_000_000L, DgbAmount.toSats("1e\u0662"))
        assertEquals(29_000_000L, DgbAmount.toSats(" \u0660.\u0662\u0669 "))
        assertNull(DgbAmount.toSats("\u0665\u066b\u0665")) // U+066B Arabic decimal separator: not mapped
        assertNull(DgbAmount.toSats("1\u066c000")) // U+066C Arabic thousands separator: not mapped
        assertNull(DgbAmount.toSats("\u00bd")) // a fraction is not a decimal digit
        assertNull(DgbAmount.toSats("-\u0661")) // mapped, then the core's rules: negative
        assertNull(DgbAmount.toSats("\u0661.\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669")) // nine decimals
        assertEquals("0.01", UsdCents.previewForDgb("\u0661", 0.01))
        // The core's own reader, which a URI amount goes through, maps nothing.
        assertNull(DgbAmount.toSatsAscii("\u0661"))
        assertNull(DgbAmount.toSatsAscii("\u0660.\u0665"))
        assertEquals(29_000_000L, DgbAmount.toSatsAscii("0,29"))
        assertNull(DgbAmount.toSatsAscii("1,000"))
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
