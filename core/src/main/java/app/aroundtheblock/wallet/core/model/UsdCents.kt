package app.aroundtheblock.wallet.core.model

import java.math.BigDecimal
import java.math.MathContext
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
 *
 * The C source of truth is the core's BRFiatAmount.h, which iOS uses directly; this is its Kotlin
 * mirror (docs/ios-port/push-down-recipe.md), and UsdCentsVectorsTest holds every answer here to
 * the header's own, written down by native/src/test/host/fiat_vectors_kat. The one deliberate
 * difference is the header's: it reads only ASCII whitespace and digits, while [parse] still takes
 * what Kotlin's trim() and isDigit() take.
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
     * stay integers. The core's BRFiatPreviewForDgb (BRFiatAmount.h) is the source of truth.
     */
    fun previewForDgb(dgbText: String, priceUsd: Double): String {
        val sats = DgbAmount.toSats(dgbText) ?: return ""
        val rate = rateFromDouble(priceUsd) ?: return ""
        return centsForSats(sats, rate)?.let { format(it) } ?: ""
    }

    /**
     * The DGB amount a typed dollar amount buys, as amount-field text, or "" when there is none.
     * Plain decimals, never grouped: the text lands in an editable field and is parsed again.
     * The core's BRFiatDgbTextFor is the source of truth.
     */
    fun dgbTextFor(usdText: String, priceUsd: Double): String {
        val cents = parse(usdText) ?: return ""
        val rate = rateFromDouble(priceUsd) ?: return ""
        return satsForCents(cents, rate)?.let { DgbAmount.format(it) } ?: ""
    }

    /** Cents [sats] buy at [rate] dollars per DGB: HALF_EVEN(sats / 1e8 x rate x 100). BRFiatCentsForSats. */
    internal fun centsForSats(sats: Long, rate: BigDecimal): Long? {
        if (sats < 0 || rate.signum() <= 0) return null
        val cents = BigDecimal.valueOf(sats).multiply(rate).movePointLeft(6).setScale(0, RoundingMode.HALF_EVEN)
        return exactLong(cents)
    }

    /** Satoshis [cents] buy at [rate] dollars per DGB: HALF_EVEN(cents / 100 / rate x 1e8). BRFiatSatsForCents. */
    internal fun satsForCents(cents: Long, rate: BigDecimal): Long? {
        if (cents < 0 || rate.signum() <= 0) return null
        val sats = BigDecimal.valueOf(cents).movePointRight(6).divide(rate, 0, RoundingMode.HALF_EVEN)
        return exactLong(sats)
    }

    private fun exactLong(v: BigDecimal): Long? = try {
        v.longValueExact()
    } catch (_: ArithmeticException) {
        null
    }

    /**
     * The exact decimal for a price, or null when it is not usable (NaN, infinite, zero, negative):
     * BRFiatRateFromDouble, rule for rule. The fewest significant digits (1..17) that read back as
     * the double — at each length the correctly rounded decimal first, then the next one up (the
     * rounding interval is wider above than below at a power of two, so 2^-24 is
     * 5.960464477539063E-8, not ...062) — and a one-digit answer replaced by the two-digit
     * rounding when that reads back too (4.9E-324, not 5E-324). Trailing zeros dropped.
     *
     * Not `BigDecimal.valueOf(price)`: that is `Double.toString`, whose digits differ between JDK
     * 17 (the unit tests' JVM), JDK 19+ and ART, and for some doubles name a different value
     * (JDK-4511638). Everything here is exact BigDecimal arithmetic on the double's own value,
     * including "reads back", which is decided against the double's rounding interval rather than
     * by parsing, so no platform's float formatting or parsing is involved.
     */
    internal fun rateFromDouble(price: Double): BigDecimal? {
        if (!(price > 0.0) || price > Double.MAX_VALUE) return null
        val exact = BigDecimal(price)
        var found: BigDecimal? = null
        var foundDigits = 0
        for (n in 1..MAX_RATE_DIGITS) {
            val nearest = exact.round(MathContext(n, RoundingMode.HALF_EVEN))
            if (readsBackAs(nearest, price)) {
                found = nearest
            } else {
                val above = nearest.add(unitInLastPlace(nearest, n))
                if (readsBackAs(above, price)) found = above
            }
            if (found != null) {
                foundDigits = n
                break
            }
        }
        val shortest = found ?: return null // 17 digits always read back; never reached
        if (foundDigits == 1) {
            val two = exact.round(MathContext(2, RoundingMode.HALF_EVEN))
            if (readsBackAs(two, price)) return two.stripTrailingZeros()
        }
        return shortest.stripTrailingZeros()
    }

    private const val MAX_RATE_DIGITS = 17
    private val HALF = BigDecimal("0.5")

    /** 10^(position of the n-th significant digit of [v]): what C's `digits + 1` adds. */
    private fun unitInLastPlace(v: BigDecimal, n: Int): BigDecimal {
        val leading = v.precision() - v.scale() - 1 // the exponent of v's leading digit
        return BigDecimal.ONE.scaleByPowerOfTen(leading - (n - 1))
    }

    /**
     * Whether correctly rounded parsing (round half to even, as strtod does) reads [d] as exactly
     * [x], for a positive finite [x]: [d] lies strictly inside x's rounding interval, or on its
     * edge when x's significand is even. Exact; the neighbours come from the bit pattern.
     */
    private fun readsBackAs(d: BigDecimal, x: Double): Boolean {
        val bits = x.toRawBits()
        val even = (bits and 1L) == 0L
        val here = BigDecimal(x)
        val below = BigDecimal(Double.fromBits(bits - 1)) // x > 0: bits - 1 is the next double down, or 0.0
        val low = here.add(below).multiply(HALF)
        val high = if (x == Double.MAX_VALUE) {
            here.add(BigDecimal(Math.ulp(x)).multiply(HALF)) // from here up, it reads as infinity
        } else {
            here.add(BigDecimal(Double.fromBits(bits + 1))).multiply(HALF)
        }
        val vsLow = d.compareTo(low)
        val vsHigh = d.compareTo(high)
        return (vsLow > 0 || (vsLow == 0 && even)) && (vsHigh < 0 || (vsHigh == 0 && even))
    }
}
