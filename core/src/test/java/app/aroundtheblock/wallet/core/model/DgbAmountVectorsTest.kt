package app.aroundtheblock.wallet.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * [DgbAmount.toSatsAscii] gives exactly the core's answers (BRAmountParse.h): every line of
 * `native/src/test/host/amount_vectors_kat/vectors.txt`, which the header itself wrote
 * (amount_vectors_kat/generate.c; the KAT fails when the file is not current). Nothing here computes
 * an expected value of its own. [DgbAmount.toSats], the reader for typed text, is that after the
 * digit mapping iOS also applies (UsdCents.asciiDigits, FiatText.asciiDigits); the last test holds
 * the two readers together on every vector.
 *
 * Covered: ASCII and Unicode whitespace around and inside an amount, non-ASCII digits (Arabic-Indic,
 * Persian, Devanagari, full width) and separators, bytes that are not UTF-8, embedded NULs, the
 * comma rule, eight and nine decimals, the supply and the Long ceiling, leading zeros, signs,
 * exponents up to and past the int bound, long text, 4,000 random strings over the characters the
 * parser treats specially, and ~400 amounts each written a dozen ways.
 *
 * One answer per line, with no allowance, and no mapping of the text first (a payment URI's amount
 * is read this way on both platforms).
 */
class DgbAmountVectorsTest {

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        error("$rel not found above ${System.getProperty("user.dir")}")
    }

    private val vectors: List<List<String>> by lazy {
        repoFile("native/src/test/host/amount_vectors_kat/vectors.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(' ') }
    }

    /** A "..." token: bytes, with \xHH escapes, read as UTF-8 (an invalid byte becomes U+FFFD). */
    private fun text(token: String): String {
        require(token.length >= 2 && token.first() == '"' && token.last() == '"') { "not a text token: $token" }
        val body = token.substring(1, token.length - 1)
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < body.length) {
            if (body[i] == '\\') {
                require(body[i + 1] == 'x') { "bad escape in $token" }
                out.write(body.substring(i + 2, i + 4).toInt(16))
                i += 4
            } else {
                out.write(body[i].code)
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun shown(line: List<String>): String = line.joinToString(" ").let { if (it.length > 120) it.take(120) + "..." else it }

    @Test
    fun everyVectorGivesTheCoresAnswer() {
        val counts = HashMap<String, Int>()
        for (f in vectors) {
            counts[f[0]] = (counts[f[0]] ?: 0) + 1
            when (f[0]) {
                "parse" -> assertEquals(shown(f), if (f[2] == "-") null else f[2].toLong(), DgbAmount.toSatsAscii(text(f[1])))
                "format" -> assertEquals(shown(f), text(f[2]), DgbAmount.format(f[1].toLong()))
                else -> error("unknown vector kind: ${shown(f)}")
            }
        }
        // An empty or truncated file must not pass: these are well below what generate.c writes.
        for ((kind, min) in mapOf("parse" to 9000, "format" to 200)) {
            assertTrue("$kind: ${counts[kind] ?: 0} vectors, expected at least $min", (counts[kind] ?: 0) >= min)
        }
    }

    /** Dropping one of these from the corpus is a failure, not a gap. */
    @Test
    fun theCorpusCarriesTheInputsThePortWasJudgedOn() {
        val parsed = vectors.filter { it[0] == "parse" }.associate { text(it[1]) to it[2] }
        val want = mapOf(
            " 5" to "-", "5 " to "-", "\u001c5" to "-", "　" + "5" to "-",
            "١" to "-", "٠.٥" to "-", "१.५" to "-", "１２" to "-",
            "1.123456789" to "-", "1.100000000" to "110000000", "0.000000000" to "0",
            "92233720368.54775807" to "9223372036854775807", "92233720368.54775808" to "-",
            "21000000000" to "2100000000000000000", "0e2147483647" to "0", "0e2147483648" to "-",
            "0,29" to "29000000", "1,234" to "-", "1,2,3" to "-", "-0" to "0", "-1" to "-", "" to "-",
            "1,000" to "-", "25,500" to "-", "0,123" to "-", " 1,000 " to "-", "1,000e2" to "-", ",123" to "-",
            "12,3456" to "1234560000", "1,0000" to "100000000", "1,000.50" to "100050000000",
            "1,234,567.8" to "123456780000000", "١,٠٠٠" to "-", "٠,٢٩" to "-",
        )
        for ((input, answer) in want) assertEquals("vector for \"$input\"", answer, parsed[input])
    }

    /**
     * Funds safety, on every vector: the reader for typed text as it is now ([DgbAmount.toSats]: the
     * digit mapping, then the core's rule) against the reader Android shipped before the port
     * ([bigDecimalReader]: trim(), the separator rewrite, Kotlin's toBigDecimalOrNull). Where they
     * differ, one of them says "no amount", never a different number, and the reason is one of:
     *  - now REFUSED: Unicode whitespace around it, or a zero whose exponent is beyond an int (the
     *    port to BRAmountParseDGB), or a lone comma (no dot) before exactly three digits ("1,000":
     *    a thousand or one?);
     *  - now ACCEPTED: the text has a non-ASCII decimal digit, read as its ASCII digit, and the
     *    answer is the core's for the mapped text.
     * And against the core's own answer (the vector, as a payment URI's amount is read): they differ
     * only where a mapped digit made an amount of what the core, reading it as given, refuses.
     */
    @Test
    fun noVectorChangesValueOnlyTheRefusalsAndTheMappedDigits() {
        var spaceOrZero = 0
        var comma = 0
        var digits = 0
        for (f in vectors.filter { it[0] == "parse" }) {
            val input = text(f[1])
            val core = if (f[2] == "-") null else f[2].toLong()
            val now = DgbAmount.toSats(input)
            val old = bigDecimalReader(input)

            if (now != core) {
                assertEquals("${shown(f)}: VALUE CHANGED from the core's $core to $now", null, core)
                assertTrue("${shown(f)}: only the typed reader accepts it, and it has no non-ASCII digit", hasNonAsciiDigit(input))
            }
            if (now == old) continue
            assertTrue("${shown(f)}: VALUE CHANGED from $old to $now", now == null || old == null)
            if (now == null) {
                val unicodeSpace = input.any { it.isWhitespace() && !DgbAmount.isAsciiSpace(it) }
                when {
                    commaBeforeThree(UsdCents.asciiDigits(input)) -> comma++
                    unicodeSpace || old == 0L -> spaceOrZero++
                    else -> error("${shown(f)}: newly refused (was $old), and it is not a comma before three digits, whitespace or a zero")
                }
            } else {
                assertTrue("${shown(f)}: newly accepted, and it has no non-ASCII decimal digit", hasNonAsciiDigit(input))
                assertEquals("${shown(f)}: newly accepted, and not as its ASCII digits read", DgbAmount.toSatsAscii(UsdCents.asciiDigits(input)), now)
                digits++
            }
        }
        assertTrue("the corpus carries Unicode-space amounts the old reader accepted ($spaceOrZero)", spaceOrZero > 0)
        assertTrue("the corpus carries ambiguous-comma amounts the old reader accepted ($comma)", comma >= 30)
        assertTrue("the corpus carries amounts in local digits the old reader refused ($digits)", digits >= 5)
    }

    /**
     * The same rule off the corpus: 300,000 seeded random strings over digits (ASCII and four local
     * scripts), the separators, signs, exponents and spaces. Where the typed reader and the old one
     * differ, one says "no amount", for one of the reasons above; no string changes value.
     */
    @Test
    fun noRandomTextChangesValue() {
        val alphabet = ("0123456789" + "0001112229" + ",,,..+-eE \t" + "\u00a0" +
            "\u0660\u0661\u0662\u0665\u0669" + "\u06f0\u06f1\u06f9" + "\u0966\u0967\u096f" +
            "\uff10\uff11\uff19" + "\u066b\u066c").toList()
        val rnd = java.util.Random(0x2026_1110L)
        var comma = 0
        var digits = 0
        repeat(300_000) {
            val sb = StringBuilder()
            repeat(1 + rnd.nextInt(12)) { sb.append(alphabet[rnd.nextInt(alphabet.size)]) }
            val input = sb.toString()
            val now = DgbAmount.toSats(input)
            val old = bigDecimalReader(input)
            if (now == old) return@repeat
            assertTrue("\"$input\": VALUE CHANGED from $old to $now", now == null || old == null)
            if (now == null) {
                val unicodeSpace = input.any { it.isWhitespace() && !DgbAmount.isAsciiSpace(it) }
                assertTrue("\"$input\": newly refused (was $old) for no reason the rule names",
                    commaBeforeThree(UsdCents.asciiDigits(input)) || unicodeSpace || old == 0L)
                if (commaBeforeThree(UsdCents.asciiDigits(input))) comma++
            } else {
                assertTrue("\"$input\": newly accepted, and it has no non-ASCII decimal digit", hasNonAsciiDigit(input))
                assertEquals("\"$input\"", DgbAmount.toSatsAscii(UsdCents.asciiDigits(input)), now)
                digits++
            }
        }
        assertTrue("random text reached the comma rule ($comma)", comma > 100)
        assertTrue("random text reached the digit mapping ($digits)", digits > 1000)
    }

    /** No dot, exactly one comma, and a run of exactly three ASCII digits right after it, in the trimmed text. */
    private fun commaBeforeThree(input: String): Boolean {
        val t = input.trim { DgbAmount.isAsciiSpace(it) }
        if ('.' in t || t.count { it == ',' } != 1) return false
        return t.substringAfter(',').takeWhile { it in '0'..'9' }.length == 3
    }

    private fun hasNonAsciiDigit(input: String): Boolean {
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            if (cp > 0x7f && Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * DgbAmount.toSats before the port to BRAmountParseDGB (develop c30ae3918), as it behaved when
     * it shipped: built with Kotlin 2.2, whose String.toBigDecimalOrNull screened the text with an
     * ASCII-only pattern. Kotlin 2.4 accepts any Char.isDigit() digit there, so the refusal is
     * written out here (KOTLIN-2.4); without it this "old reader" would read local digits too, and
     * the tests below would compare the typed reader against a reader that never shipped.
     */
    private fun bigDecimalReader(input: String): Long? {
        val maxDgb = BigDecimal.valueOf(Long.MAX_VALUE).movePointLeft(8)
        val text = DgbAmount.normalizeSeparators(input.trim()) ?: return null
        if (text.isEmpty()) return null
        if (text.any { it.isDigit() && it !in '0'..'9' }) return null
        val value = text.toBigDecimalOrNull() ?: return null
        if (value.signum() < 0) return null
        if (value.compareTo(maxDgb) > 0) return null
        if (value.stripTrailingZeros().scale() > 8) return null
        return try {
            value.movePointRight(8).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }
}
