package app.aroundtheblock.wallet.core.model

import java.math.BigDecimal

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
 * The text a person types here is the amount that gets signed, so it must mean the same number
 * of satoshis on Android and iOS. The C source of truth is the core's BRAmountParse.h. [toSatsAscii]
 * is BRAmountParseDGB line for line (docs/ios-port/push-down-recipe.md), and DgbAmountVectorsTest
 * holds it to the header's own answers, written down by native/src/test/host/amount_vectors_kat.
 * [toSats], the reader for typed text, first maps every Unicode decimal digit to ASCII
 * ([UsdCents.asciiDigits], the mapping dollar text gets) and then calls it: iOS does the same
 * (Policy/AmountText.swift, WalletSend.swift, FiatText.previewForDgb, all through
 * FiatText.asciiDigits). A payment URI's amount is read by [toSatsAscii] with no mapping, as the
 * core's BRPaymentUriParse reads it on iOS.
 *
 * Input is untrusted text, sometimes parsed on the main thread straight off a QR code:
 *  - only ASCII whitespace (space, tab, LF, CR, FF, VT) surrounds an amount: the no-break space and
 *    other Unicode spaces are refused, as on iOS;
 *  - typed text: Arabic-Indic, Persian, Devanagari, full-width and every other decimal digit
 *    (category Nd) is read as its ASCII digit; the Arabic decimal and thousands separators
 *    (U+066B, U+066C) are not mapped and are refused. The core itself reads ASCII digits only;
 *  - a dot is the decimal point and commas around it are grouping ("1,234.5");
 *  - a LONE comma with no dot is the decimal point ("0,29" — seven of the app's languages and
 *    the platform decimal keyboard write it that way; stripping it read 29 DGB for a shown 0,29);
 *  - except that a lone comma followed by EXACTLY three digits is ambiguous and refused ("1,000"
 *    is a thousand in English and one in German; "25,500", "0,123"). The three are the run of ASCII
 *    digits right after the comma once surrounding whitespace is dropped, whatever follows it (end
 *    of text, an exponent): " 1,000 ", "1,000e2" and ",123" are refused, "1,0000" and "1,00e2" are not;
 *  - several commas with no dot are ambiguous and refused;
 *  - the number follows java.math.BigDecimal's grammar: an optional sign, digits with an optional
 *    point ("5", "5.", ".5"), an optional exponent ("1e2" is 100 DGB) that fits an int;
 *  - anything eight decimals cannot represent is rejected rather than rounded;
 *  - a negative amount is rejected; zero (including "-0") is returned as 0 so callers decide what
 *    it means;
 *  - the magnitude is bounded BEFORE any scaling: "1e50000000" is cheap to hold and to compare
 *    but took 48 s and 350 MB to materialise as an integer, which is an app freeze from a QR.
 */
object DgbAmount {
    const val SATS_PER_DGB = 100_000_000L
    private const val DECIMALS = 8

    /** BRAmountParseDGB's `char digits[24]`: a valid amount never has more than 19 significant digits. */
    private const val SIGNIFICANT_DIGITS_BUFFER = 24

    /** The most digits a satoshi count that fits a Long can have. */
    private const val MAX_SATS_DIGITS = 19

    /**
     * Satoshis for a DGB amount typed as text, or null when it is not one: every Unicode decimal
     * digit mapped to ASCII ([UsdCents.asciiDigits]), then [toSatsAscii]. As iOS reads a typed
     * amount (FiatText.asciiDigits, then BRAmountParseDGB).
     */
    fun toSats(input: String): Long? = toSatsAscii(UsdCents.asciiDigits(input))

    /**
     * Satoshis for DGB text exactly as the core reads it, with no digit mapping, or null when it is
     * not an amount. A payment URI's amount= is read this way (BRPaymentUriParse).
     *
     * BRAmountParseDGB, line for line. The C reads UTF-8 bytes and this reads UTF-16 chars; the
     * answer is the same because every character it accepts is ASCII, and every non-ASCII character
     * (each of whose UTF-8 bytes is >= 0x80) is refused wherever it appears — and it ends a run of
     * digits after a comma in both.
     */
    fun toSatsAscii(input: String): Long? {
        val text = input
        var start = 0
        var end = text.length
        var commas = 0
        var dots = 0
        var negative = false
        var sawDigit = false
        var sawPoint = false
        var inExponent = false
        var expNegative = false
        var expDigits = 0
        var signAllowed = true
        val digits = CharArray(SIGNIFICANT_DIGITS_BUFFER) // significant digits
        var nd = 0
        var pendingZeros = 0L // zeros after the last nonzero digit, not yet known to be significant
        var fracDigits = 0L
        var exponent = 0L

        while (start < end && isAsciiSpace(text[start])) start++
        while (end > start && isAsciiSpace(text[end - 1])) end--
        if (start == end) return null

        for (i in start until end) {
            if (text[i] == ',') commas++ else if (text[i] == '.') dots++
        }

        // BigDecimal grammar: [+-] (digits [. digits*] | . digits) [(e|E) [+-] digits], read after
        // the one separator rule: commas are grouping around a point, a lone comma IS the point,
        // and several commas with no point, or a lone comma before exactly three digits, are
        // ambiguous.
        for (i in start until end) {
            var c = text[i]

            if (c == ',') {
                if (dots > 0) continue
                if (commas != 1) return null
                var j = i + 1
                while (j < end && text[j] in '0'..'9') j++
                if (j - (i + 1) == 3) return null // "1,000": a thousand or one? Refused.
                c = '.'
            }
            if (inExponent) {
                if ((c == '+' || c == '-') && signAllowed) {
                    expNegative = c == '-'
                    signAllowed = false
                    continue
                }
                if (c < '0' || c > '9') return null
                signAllowed = false
                expDigits++
                exponent = exponent * 10 + (c - '0')
                if (exponent > 2147483648L) return null // beyond an int exponent
                continue
            }
            if ((c == '+' || c == '-') && signAllowed) {
                negative = c == '-'
                signAllowed = false
                continue
            }
            signAllowed = false
            if (c in '0'..'9') {
                sawDigit = true
                if (sawPoint) fracDigits++
                if (c == '0') {
                    if (nd > 0) pendingZeros++ // maybe trailing, maybe interior
                } else {
                    while (pendingZeros > 0) { // they were interior: significant
                        if (nd >= digits.size) return null // > 19 significant digits: never valid
                        digits[nd++] = '0'
                        pendingZeros--
                    }
                    if (nd >= digits.size) return null
                    digits[nd++] = c
                }
            } else if (c == '.') {
                if (sawPoint) return null
                sawPoint = true
            } else if ((c == 'e' || c == 'E') && sawDigit) {
                inExponent = true
                signAllowed = true
            } else {
                return null
            }
        }
        if (!sawDigit) return null
        if (inExponent) {
            if (expDigits == 0) return null
            if (expNegative) exponent = -exponent
            if (exponent > Int.MAX_VALUE || exponent < Int.MIN_VALUE) return null
        }
        // BigDecimal's scale (fraction digits minus exponent) must fit an int as well.
        if (fracDigits - exponent > Int.MAX_VALUE || fracDigits - exponent < Int.MIN_VALUE) return null

        if (nd == 0) return 0L // zero, "-0" included
        if (negative) return null

        // value = digits x 10^(pendingZeros + exponent - fracDigits); trailing zeros are not decimals.
        val shift = pendingZeros + exponent - fracDigits + DECIMALS // into satoshis
        if (shift < 0) return null // more than eight decimals: refused, not rounded
        if (nd + shift > MAX_SATS_DIGITS) return null // cannot fit a signed 64-bit count of satoshis

        var v = 0L
        for (k in 0 until nd) {
            val d = (digits[k] - '0').toLong()
            if (v > (Long.MAX_VALUE - d) / 10) return null
            v = v * 10 + d
        }
        for (k in 0 until shift) {
            if (v > Long.MAX_VALUE / 10) return null
            v *= 10
        }
        return v
    }

    /** BRAmountParse.h's _BRAmountIsSpace: ASCII's six, not Unicode spaces, not U+001C..U+001F. */
    internal fun isAsciiSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C' || c == '\u000B'

    /**
     * The separator rule as a text rewrite, for [AssetQuantity] (which has no core counterpart yet):
     * dot-decimal text with no commas, or null when the commas are ambiguous. [UsdCents.parse]
     * applies the same rule inside its port of the core's parser; [toSatsAscii] applies it too, and
     * also refuses a lone comma before exactly three digits, which this rewrite does not.
     */
    internal fun normalizeSeparators(s: String): String? {
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
