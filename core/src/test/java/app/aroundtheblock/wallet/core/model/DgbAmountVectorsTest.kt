package app.aroundtheblock.wallet.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * [DgbAmount] gives exactly the core's answers (BRAmountParse.h, which iOS calls with the text as
 * typed): every line of `native/src/test/host/amount_vectors_kat/vectors.txt`, which the header
 * itself wrote (amount_vectors_kat/generate.c; the KAT fails when the file is not current). Nothing
 * here computes an expected value of its own.
 *
 * Covered: ASCII and Unicode whitespace around and inside an amount, non-ASCII digits (Arabic-Indic,
 * Persian, Devanagari, full width) and separators, bytes that are not UTF-8, embedded NULs, the
 * comma rule, eight and nine decimals, the supply and the Long ceiling, leading zeros, signs,
 * exponents up to and past the int bound, long text, 4,000 random strings over the characters the
 * parser treats specially, and ~400 amounts each written a dozen ways.
 *
 * One answer per line, with no allowance, and no mapping of the text first: iOS maps none for DGB.
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
                "parse" -> assertEquals(shown(f), if (f[2] == "-") null else f[2].toLong(), DgbAmount.toSats(text(f[1])))
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
            "0,29" to "29000000", "1,234" to "123400000", "1,2,3" to "-", "-0" to "0", "-1" to "-", "" to "-",
        )
        for ((input, answer) in want) assertEquals("vector for \"$input\"", answer, parsed[input])
    }

    /**
     * Funds safety of the port: on every vector, the reader it replaced (trim(), the separator rewrite,
     * then Kotlin's toBigDecimalOrNull, kept here as [bigDecimalReader]) either gives the same
     * satoshis or refuses what the core accepts. Where only the old reader accepted, the text is one
     * the core reads as no amount, and the only kinds there are: Unicode whitespace around it, or a
     * zero whose exponent is beyond an int. No vector that both accept changes value.
     */
    @Test
    fun noVectorChangesValueFromTheReaderItReplaced() {
        var nowRefused = 0
        for (f in vectors.filter { it[0] == "parse" }) {
            val input = text(f[1])
            val core = if (f[2] == "-") null else f[2].toLong()
            val old = bigDecimalReader(input)
            if (old == core) continue
            assertEquals("${shown(f)}: the core refuses it, and the old reader said ${old ?: "no"}", null, core)
            val unicodeSpace = input.any { it.isWhitespace() && !DgbAmount.isAsciiSpace(it) }
            assertTrue("${shown(f)}: only the old reader accepts it, and it is not whitespace or a zero", unicodeSpace || old == 0L)
            nowRefused++
        }
        assertTrue("the corpus carries Unicode-space amounts the old reader accepted ($nowRefused)", nowRefused > 0)
    }

    /** DgbAmount.toSats before the port to BRAmountParseDGB (develop c30ae3918). */
    private fun bigDecimalReader(input: String): Long? {
        val maxDgb = BigDecimal.valueOf(Long.MAX_VALUE).movePointLeft(8)
        val text = DgbAmount.normalizeSeparators(input.trim()) ?: return null
        if (text.isEmpty()) return null
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
