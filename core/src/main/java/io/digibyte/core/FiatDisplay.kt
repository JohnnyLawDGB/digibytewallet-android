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
}
