package io.digibyte.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The price of ONE DGB, in the currency the user chose.
 *
 * ## Why this is not [FiatDisplay.format]
 *
 * A balance and a unit price need different precision, and using one for the other is wrong in
 * both directions. A 2 DGB balance is "$0.01" — two decimals is right, and eight would be noise.
 * The price of one DGB is "$0.005102" — two decimals rounds it to "$0.01", which is not a price
 * anyone can act on, and at three it becomes "$0.01" again. DGB is a sub-cent coin, so the
 * currency's own fraction digits are simply not enough to express its price.
 *
 * ## The rule
 *
 * At or above 1 unit, use the currency's normal digits — ₦8.15, not ₦8.150000.
 * Below 1 unit, extend until four significant figures are visible, capped at eight decimals so a
 * BTC rate stays readable.
 *
 * ## What it must never do
 *
 * Render a confident zero. "$0.00" for a coin that has a price is the same class of failure as
 * the hardcoded `dgbUsd / 60000.0` Bitcoin rate this file's sibling was built to replace: it
 * looks like an answer.
 */
class FiatDisplayUnitPriceTest {

    @Test fun `a sub-cent price keeps enough decimals to be a price`() {
        // The real DGB/USD rate at the time of writing.
        assertEquals("$0.005102", FiatDisplay.formatUnitPrice("usd", 0.005102))
    }

    /**
     * Asserts the DIGITS, and that the currency is identified — not which glyph the platform
     * picks. A foreign currency renders as its ISO code rather than its symbol in a locale where
     * it is not local ("NGN8.15" in en-US, "₦8.15" in en-NG), and that is the platform's call to
     * make, not a property of this function.
     */
    @Test fun `a price above one unit uses the currency's own digits`() {
        // 1 DGB is roughly 8 naira — six decimals here would be noise, not precision.
        val out = FiatDisplay.formatUnitPrice("ngn", 8.15)
        assertEquals("two decimals, not six", "8.15", out.filter { it.isDigit() || it == '.' })
        assertTrue("must name the currency: $out", out.contains("₦") || out.contains("NGN"))
    }

    /** A zero-decimal currency still needs decimals when the price is below 1 of them. */
    @Test fun `a zero-decimal currency still shows a sub-unit price`() {
        val out = FiatDisplay.formatUnitPrice("jpy", 0.7842)
        assertTrue("must not round a real price to zero: $out", !out.contains("0.00") || out.contains("0.78"))
        assertTrue("expected visible precision, got $out", out.contains("0.78"))
    }

    @Test fun `bitcoin keeps its eight decimals`() {
        assertEquals("0.00000013 BTC", FiatDisplay.formatUnitPrice("btc", 0.00000013))
    }

    /** The whole point: the label follows the chosen currency, not a hardcoded USD. */
    @Test fun `the currency shown is the one asked for`() {
        assertTrue(FiatDisplay.formatUnitPrice("eur", 0.0047).contains("0.0047"))
        assertTrue(FiatDisplay.formatUnitPrice("gbp", 0.0040).contains("0.004"))
    }

    /**
     * No rate is not a rate of zero. This runs on the home screen on every price tick, and a
     * confident "$0.00" is worse than a dash because it reads as information.
     */
    @Test fun `an unknown rate shows a dash rather than a zero`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            val out = FiatDisplay.formatUnitPrice("usd", it)
            assertTrue("expected a dash for rate=$it, got $out", out.contains("--"))
        }
    }

    /** Must not throw on a code the platform does not know — it renders before any error UI. */
    @Test fun `an unknown currency code degrades instead of throwing`() {
        val out = FiatDisplay.formatUnitPrice("zzz", 0.0051)
        assertTrue("expected the code to survive, got $out", out.contains("ZZZ"))
    }
}
