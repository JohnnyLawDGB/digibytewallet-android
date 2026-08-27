package io.digibyte.core

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Renders a DGB amount in a chosen currency for the hero's "≈ …" line.
 *
 * ## Why this is its own tested unit
 *
 * The code this replaces computed Bitcoin as `dgbUsd / 60000.0`, with the comment
 * "rough estimate". A hardcoded Bitcoin price that never updated, shipped for months. Nobody
 * caught it because the output *looked* correct — a small number with eight decimals, on a line
 * no one can check by eye.
 *
 * That is the risk this file is built around. A fiat figure cannot be sanity-checked by looking
 * at it, so every rate must come from data and every conversion needs a test that would notice
 * if it stopped being true.
 *
 * ## Minor units are not decoration
 *
 * A yen is not a cent. `¥0.80` is not a price anyone would write, and quoting two decimals on
 * JPY, KRW, VND or IDR marks the wallet as not knowing the currency it is naming. The digit
 * count comes from [Currency.getDefaultFractionDigits] rather than a hand-maintained list, so a
 * currency added later is correct without anyone remembering to look it up.
 */
object FiatDisplay {

    /** Bitcoin is a rate the wallet displays but is not a [Currency]; it gets its own path. */
    private const val BTC = "btc"
    private const val BTC_DECIMALS = 8

    /**
     * @param dgb   the amount in DGB.
     * @param code  lower- or upper-case currency code ("usd", "jpy", "btc").
     * @param rate  units of [code] per 1 DGB. Zero or negative means "not known".
     *
     * @return a display string, or one containing "--" when the rate is unknown.
     */
    fun format(dgb: Double, code: String, rate: Double): String {
        val normalised = code.trim().lowercase(Locale.US)
        val label = normalised.uppercase(Locale.US)

        // No rate is not the same as a rate of zero. Rendering a confident "$0.00" when the
        // truth is "we could not find out" is worse than a dash: it looks like an answer, and
        // a balance is exactly the thing a person will act on.
        if (rate <= 0.0 || rate.isNaN() || rate.isInfinite()) return "$label --"

        val value = dgb * rate
        if (value.isNaN() || value.isInfinite()) return "$label --"

        if (normalised == BTC) {
            val fmt = NumberFormat.getNumberInstance(Locale.US).apply {
                minimumFractionDigits = BTC_DECIMALS
                maximumFractionDigits = BTC_DECIMALS
                isGroupingUsed = false
            }
            return "${fmt.format(value)} BTC"
        }

        // An unrecognised code must not throw — this runs on the hero on every price tick.
        val currency = runCatching { Currency.getInstance(label) }.getOrNull()
            ?: return NumberFormat.getNumberInstance(Locale.US).let {
                it.minimumFractionDigits = 2
                it.maximumFractionDigits = 2
                "${it.format(value)} $label"
            }

        val digits = currency.defaultFractionDigits.coerceAtLeast(0)
        val fmt = NumberFormat.getCurrencyInstance(Locale.US).apply {
            this.currency = currency
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }
        return fmt.format(value)
    }

    /**
     * The price of ONE DGB in [code], for the home screen's price card.
     *
     * Separate from [format] because a balance and a unit price need different precision, and
     * borrowing one for the other is wrong both ways. Two decimals is right for a balance and
     * rounds a sub-cent coin's price to "$0.01"; eight decimals is right for a BTC rate and turns
     * a naira price into "₦8.150000".
     *
     * At or above one unit, the currency's own digits are correct. Below one unit, extend until
     * four significant figures show — enough to read a real change in the price — capped at eight
     * so a BTC rate stays a number rather than a ruler.
     *
     * @param rate units of [code] per 1 DGB. Zero or negative means "not known".
     */
    fun formatUnitPrice(code: String, rate: Double): String {
        val normalised = code.trim().lowercase(Locale.US)
        val label = normalised.uppercase(Locale.US)

        // Same rule as [format]: a missing rate is not a rate of zero, and "$0.00" for a coin
        // that has a price reads as an answer rather than as an absence.
        if (rate <= 0.0 || rate.isNaN() || rate.isInfinite()) return "$label --"

        if (normalised == BTC) {
            val fmt = NumberFormat.getNumberInstance(Locale.US).apply {
                minimumFractionDigits = BTC_DECIMALS
                maximumFractionDigits = BTC_DECIMALS
                isGroupingUsed = false
            }
            return "${fmt.format(rate)} BTC"
        }

        val currency = runCatching { Currency.getInstance(label) }.getOrNull()
        val baseDigits = currency?.defaultFractionDigits?.coerceAtLeast(0) ?: 2
        val digits = significantDigitsFor(rate, baseDigits)

        if (currency == null) {
            val fmt = NumberFormat.getNumberInstance(Locale.US).apply {
                minimumFractionDigits = digits
                maximumFractionDigits = digits
            }
            return "${fmt.format(rate)} $label"
        }

        val fmt = NumberFormat.getCurrencyInstance(Locale.US).apply {
            this.currency = currency
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }
        return fmt.format(rate)
    }

    /** Significant figures wanted below one unit — enough to see the price move. */
    private const val SIGNIFICANT = 4
    private const val MAX_DECIMALS = 8

    /**
     * Decimals needed to show [SIGNIFICANT] significant figures of [value], never fewer than
     * [baseDigits]. At or above 1 the currency's own digits already suffice.
     */
    private fun significantDigitsFor(value: Double, baseDigits: Int): Int {
        if (value >= 1.0) return baseDigits
        // leadingZeros = how many zeros sit between the point and the first significant digit.
        var leadingZeros = 0
        var v = value
        while (v < 0.1 && leadingZeros < MAX_DECIMALS) {
            v *= 10
            leadingZeros++
        }
        return (leadingZeros + SIGNIFICANT).coerceIn(baseDigits, MAX_DECIMALS)
    }
}
