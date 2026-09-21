package io.digibyte.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Dollar text ↔ whole cents, exactly.
 *
 * A DigiDollar amount is an integer number of cents. Text either names such an integer or it is
 * refused: nothing here rounds, and no binary floating-point value ever holds an amount.
 *
 *  - separators are read by the rule [DgbAmount] uses — the two amount fields sit side by side on
 *    the send screen and must agree on what a comma means;
 *  - at most two decimals may be TYPED: "1.005" is not an amount of cents, and neither is
 *    "1.0000";
 *  - only digits and one decimal point: no sign, no exponent, no "NaN";
 *  - the length is bounded before anything is built from the text.
 */
object UsdCents {
    private const val DECIMALS = 2

    /** Longer than any text that fits a Long of cents, grouping included. Checked before parsing. */
    private const val MAX_TEXT_LENGTH = 32

    /** Cents for a dollar amount typed as text, or null when it is not one. Zero is returned as 0. */
    fun parse(input: String): Long? {
        val text = DgbAmount.normalizeSeparators(input.trim()) ?: return null
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH) return null
        if (!text.all { it.isDigit() || it == '.' }) return null
        val value = text.toBigDecimalOrNull() ?: return null
        // With only digits and a point in the text, the scale IS the number of typed decimals.
        if (value.scale() > DECIMALS) return null
        return try {
            value.movePointRight(DECIMALS).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** Plain decimal text for [cents] — always two decimals, no grouping, no exponent, no locale. */
    fun format(cents: Long): String = BigDecimal.valueOf(cents, DECIMALS).toPlainString()

    /**
     * The dollars line shown beside a typed DGB amount, or "" when there is nothing to show.
     *
     * The DGB text is read by [DgbAmount.toSats] — the parser the send itself uses — so the line
     * can never describe a different number than the one that would be sent. [priceUsd] is a
     * market rate and the result an approximation by nature; the amounts on either side of it
     * stay integers.
     */
    fun previewForDgb(dgbText: String, priceUsd: Double): String {
        val sats = DgbAmount.toSats(dgbText) ?: return ""
        val price = usablePrice(priceUsd) ?: return ""
        // cents = sats / 1e8 * price * 100
        val cents = BigDecimal.valueOf(sats).multiply(price).movePointLeft(6)
            .setScale(0, RoundingMode.HALF_EVEN)
        return try {
            format(cents.longValueExact())
        } catch (_: ArithmeticException) {
            ""
        }
    }

    /**
     * The DGB amount a typed dollar amount buys, as amount-field text, or "" when there is none.
     * Plain decimals, never grouped: the text lands in an editable field and is parsed again.
     */
    fun dgbTextFor(usdText: String, priceUsd: Double): String {
        val cents = parse(usdText) ?: return ""
        val price = usablePrice(priceUsd) ?: return ""
        return try {
            // sats = cents / 100 / price * 1e8
            val sats = BigDecimal.valueOf(cents).movePointRight(6).divide(price, 0, RoundingMode.HALF_EVEN)
            DgbAmount.format(sats.longValueExact())
        } catch (_: ArithmeticException) {
            ""
        }
    }

    private fun usablePrice(priceUsd: Double): BigDecimal? =
        if (priceUsd.isNaN() || priceUsd.isInfinite() || priceUsd <= 0.0) null else BigDecimal.valueOf(priceUsd)
}
