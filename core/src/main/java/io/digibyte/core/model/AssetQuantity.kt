package io.digibyte.core.model

import java.math.BigDecimal

/**
 * Asset quantity text ↔ whole asset units, exactly.
 *
 * An asset moves in whole units. Its divisibility — fixed by its issuance — says how many decimals
 * a shown quantity has: 150 units of an asset with two decimals read "1.5". Text either names a
 * positive whole number of units at that divisibility or it is refused: nothing here rounds, and no
 * binary floating-point value ever holds a quantity.
 *
 *  - separators are read by the rule [DgbAmount] uses — every amount field in the wallet agrees on
 *    what a comma means;
 *  - no more decimals may be TYPED than the asset has: "1.234" is not a quantity of an asset with
 *    two decimals, and "1.0" is not a quantity of an asset with none;
 *  - only digits and one decimal point: no sign, no exponent, no "NaN";
 *  - zero is not a quantity, so there is no "0 units" for a caller to forget to refuse;
 *  - the length is bounded before anything is built from the text.
 */
object AssetQuantity {
    /** The most decimals an asset can have. */
    const val MAX_DIVISIBILITY = 8

    /** Longer than any text that fits a Long of units, grouping included. Checked before parsing. */
    private const val MAX_TEXT_LENGTH = 32

    /**
     * Whole units for a quantity typed as text, for an asset with [divisibility] decimals, or null
     * when the text is not a positive quantity of such an asset.
     */
    fun parse(input: String, divisibility: Int): Long? {
        if (divisibility !in 0..MAX_DIVISIBILITY) return null
        val text = DgbAmount.normalizeSeparators(input.trim()) ?: return null
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH) return null
        if (!text.all { it.isDigit() || it == '.' }) return null
        val value = text.toBigDecimalOrNull() ?: return null
        // With only digits and a point in the text, the scale IS the number of typed decimals.
        if (value.scale() > divisibility) return null
        return try {
            value.movePointRight(divisibility).longValueExact().takeIf { it > 0L }
        } catch (_: ArithmeticException) {
            null
        }
    }

    /**
     * Plain decimal text for [units] of an asset with [divisibility] decimals — no grouping, no
     * exponent, no padding zeros, no locale. [parse] reads it back as the same units.
     */
    fun format(units: Long, divisibility: Int): String =
        BigDecimal.valueOf(units, divisibility.coerceAtLeast(0)).stripTrailingZeros().toPlainString()
}
