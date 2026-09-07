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
 * Input is untrusted text, sometimes parsed on the main thread straight off a QR code:
 *  - a dot is the decimal point and commas around it are grouping ("1,234.5");
 *  - a LONE comma with no dot is the decimal point ("0,29" — seven of the app's languages and
 *    the platform decimal keyboard write it that way; stripping it read 29 DGB for a shown 0,29);
 *  - several commas with no dot are ambiguous and refused;
 *  - anything eight decimals cannot represent is rejected rather than rounded;
 *  - a negative amount is rejected; zero is returned as 0 so callers decide what it means;
 *  - the magnitude is bounded BEFORE any scaling: "1e50000000" is cheap to hold and to compare
 *    but took 48 s and 350 MB to materialise as an integer, which is an app freeze from a QR.
 */
object DgbAmount {
    const val SATS_PER_DGB = 100_000_000L
    private const val DECIMALS = 8

    /** The largest DGB amount whose satoshi count fits a Long. Compared, never scaled. */
    private val MAX_DGB: BigDecimal = BigDecimal.valueOf(Long.MAX_VALUE).movePointLeft(DECIMALS)

    /** Satoshis for a DGB amount typed or scanned as text, or null when it is not one. */
    fun toSats(input: String): Long? {
        val text = normalizeSeparators(input.trim()) ?: return null
        if (text.isEmpty()) return null
        val value = text.toBigDecimalOrNull() ?: return null
        if (value.signum() < 0) return null
        if (value.compareTo(MAX_DGB) > 0) return null
        if (value.stripTrailingZeros().scale() > DECIMALS) return null
        return try {
            value.movePointRight(DECIMALS).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun normalizeSeparators(s: String): String? {
        val commas = s.count { it == ',' }
        return when {
            commas == 0 -> s
            s.contains('.') -> s.replace(",", "")
            commas == 1 -> s.replace(',', '.')
            else -> null
        }
    }

    /** Plain decimal text for [sats] — no exponent, no grouping, no trailing zeros. */
    fun format(sats: Long): String =
        BigDecimal.valueOf(sats).movePointLeft(DECIMALS).stripTrailingZeros().toPlainString()
}
