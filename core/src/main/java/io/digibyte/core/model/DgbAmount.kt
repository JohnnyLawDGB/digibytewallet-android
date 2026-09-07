package io.digibyte.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * DGB text ↔ satoshis, exactly.
 *
 * Every conversion in the wallet used to be `(text.toDouble() * 100_000_000).toLong()`. A
 * double cannot represent most decimal fractions and `toLong` truncates, so `0.29` became
 * 28,999,999 sats and a dust-coded deposit amount (`N.0000CCCC`, the digiscope tip-wallet
 * format) landed one satoshi low about one time in fifteen — a different code, an orphaned
 * deposit. Decimal arithmetic on the text the user (or the QR) actually supplied is the only
 * conversion that pays what was shown.
 *
 * Input is untrusted text: grouping commas and surrounding whitespace are tolerated, anything
 * that cannot be represented in eight decimals is rejected rather than rounded, and a negative
 * amount is rejected. Zero is returned as 0 so callers can decide what an empty amount means.
 */
object DgbAmount {
    const val SATS_PER_DGB = 100_000_000L
    private const val DECIMALS = 8

    /** Satoshis for a DGB amount typed or scanned as text, or null when it is not one. */
    fun toSats(input: String): Long? {
        val text = input.trim().replace(",", "")
        if (text.isEmpty()) return null
        val value = text.toBigDecimalOrNull() ?: return null
        if (value.signum() < 0) return null
        if (value.stripTrailingZeros().scale() > DECIMALS) return null
        return try {
            value.movePointRight(DECIMALS).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** Plain decimal text for [sats] — no exponent, no grouping, no trailing zeros. */
    fun format(sats: Long): String =
        BigDecimal.valueOf(sats).movePointLeft(DECIMALS).stripTrailingZeros().toPlainString()
}
