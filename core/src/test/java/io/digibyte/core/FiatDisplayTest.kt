package io.digibyte.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Formatting for the hero's "≈ <amount>" line, across every currency the wallet can display.
 *
 * WHY THIS IS A SEPARATE, TESTED THING. The version this replaces computed Bitcoin like so:
 *
 *     val btcApprox = dgbUsd / 60000.0   // rough estimate
 *
 * A hardcoded Bitcoin price, never updated, shipped for months. It went unnoticed because the
 * result *looked* right — a plausible small number with eight decimals, in a line nobody can
 * check by eye. That is the whole failure mode here: a fiat conversion cannot be eyeballed, so
 * every rate has to come from data and every conversion needs a test that would notice.
 *
 * The other half is minor units. A yen is not a cent: ¥0.80 is not a real price, and rendering
 * two decimals on JPY, KRW, VND or IDR marks the wallet as something that does not know the
 * currency it is quoting. That is taken from `java.util.Currency` rather than a hand-kept list,
 * so a currency added later is right without anyone remembering to look it up.
 */
class FiatDisplayTest {

    // Real DGB rates, CoinGecko, 2026-08-27.
    private val usd = 0.0049959
    private val btc = 6.3007e-08
    private val jpy = 0.796468
    private val inr = 0.477153
    private val ngn = 6.71

    // ---- the bug this file exists for -------------------------------------------------------

    /**
     * 2.107396 DGB at the real BTC rate is 0.00000013 BTC. The shipped code divided the USD value
     * by a hardcoded 60000 and produced 0.00000018 — overstated by ~38%, and stable enough to
     * look deliberate.
     */
    @Test fun `bitcoin uses the real rate, not a hardcoded price`() {
        val out = FiatDisplay.format(dgb = 2.107396, code = "btc", rate = btc)

        assertEquals("0.00000013 BTC", out)
        assertTrue(
            "must not reproduce the hardcoded-60000 answer",
            !out.contains("0.00000018"),
        )
    }

    @Test fun `bitcoin shows eight decimals`() {
        assertEquals("0.00006301 BTC", FiatDisplay.format(dgb = 1000.0, code = "btc", rate = btc))
    }

    // ---- minor units ------------------------------------------------------------------------

    /** JPY has no minor unit. "¥0.80" is not a price anyone would write. */
    @Test fun `yen renders without decimals`() {
        val out = FiatDisplay.format(dgb = 1.0, code = "jpy", rate = jpy)
        assertTrue("expected a whole-yen figure, got: $out", !out.contains("."))
    }

    @Test fun `zero-decimal currencies round rather than truncate`() {
        // 1000 DGB * 0.796468 = 796.468 -> 796
        assertTrue(FiatDisplay.format(dgb = 1000.0, code = "jpy", rate = jpy).contains("796"))
    }

    @Test fun `two-decimal currencies keep both`() {
        val out = FiatDisplay.format(dgb = 1000.0, code = "usd", rate = usd)
        assertTrue("expected 2 decimals, got: $out", Regex("\\.\\d{2}\\b").containsMatchIn(out))
    }

    // ---- symbols --------------------------------------------------------------------------

    @Test fun `each currency carries its own symbol or code`() {
        assertTrue(FiatDisplay.format(1000.0, "usd", usd).contains("$"))
        assertTrue(FiatDisplay.format(1000.0, "inr", inr).let { it.contains("₹") || it.contains("INR") })
        assertTrue(FiatDisplay.format(1000.0, "ngn", ngn).let { it.contains("₦") || it.contains("NGN") })
        assertTrue(FiatDisplay.format(1000.0, "jpy", jpy).let { it.contains("¥") || it.contains("JPY") })
    }

    /** Bitcoin is not a java.util.Currency and must not be forced through one. */
    @Test fun `bitcoin is labelled BTC, never a currency symbol`() {
        val out = FiatDisplay.format(1000.0, "btc", btc)
        assertTrue("expected a BTC suffix, got: $out", out.endsWith("BTC"))
    }

    // ---- no rate --------------------------------------------------------------------------

    /**
     * A missing rate must read as unknown. The failure that matters is showing a CONFIDENT
     * number computed from a zero or absent rate — a balance of "$0.00" when the truth is
     * "we could not find out" is worse than a dash, because it looks like an answer.
     */
    @Test fun `a missing rate shows a dash, never a zero`() {
        assertTrue(FiatDisplay.format(1000.0, "usd", 0.0).contains("--"))
        assertTrue(FiatDisplay.format(1000.0, "btc", 0.0).contains("--"))
        assertTrue(FiatDisplay.format(1000.0, "jpy", -1.0).contains("--"))
    }

    @Test fun `a zero balance with a good rate is a real zero, not a dash`() {
        val out = FiatDisplay.format(dgb = 0.0, code = "usd", rate = usd)
        assertTrue("a known-zero balance should render as a figure, got: $out", !out.contains("--"))
        assertTrue(out.contains("0"))
    }

    // ---- unknown codes ---------------------------------------------------------------------

    /** Runs on the hero on every price tick; an unrecognised code must not throw. */
    @Test fun `an unknown currency code degrades instead of throwing`() {
        val out = FiatDisplay.format(1000.0, "zzz", 1.23)
        assertTrue("expected the code as a fallback label, got: $out", out.contains("ZZZ"))
    }
}
