package app.aroundtheblock.wallet.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger

/**
 * [UsdCents] gives exactly the core's answers (BRFiatAmount.h, which iOS's FiatText uses): every
 * line of `native/src/test/host/fiat_vectors_kat/vectors.txt`, which the header itself wrote
 * (fiat_vectors_kat/generate.c; the KAT fails when the file is not current). Nothing here
 * computes an expected value of its own.
 *
 * Covered: the rate read from a price (feed-shaped prices, every power of two from 2^-60 to 2^60,
 * 2^-24, 2^-44, 2e23, 5e-324, DBL_MAX, JDK-4511638's doubles, shortest candidates that land
 * exactly on a rounding boundary, arbitrary doubles), both HALF_EVEN conversions up to the Long
 * ceiling, dollar text both ways, and the send screen's two amount fields end to end.
 *
 * The one deliberate difference ([KOTLIN_ONLY]): BRUsdCentsParse reads ASCII whitespace and ASCII
 * digits only; Kotlin's trim() and digit rules also take Unicode spaces, U+001C..U+001F and other
 * decimal digits. BRFiatAmount.h documents it; those lines may give Kotlin's own answer instead.
 */
class UsdCentsVectorsTest {

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
        repoFile("native/src/test/host/fiat_vectors_kat/vectors.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(' ') }
    }

    /** A "..." token: bytes, with \xHH escapes, read as UTF-8. */
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

    private fun price(hex: String): Double = Double.fromBits(java.lang.Long.parseUnsignedLong(hex, 16))

    private fun rate(hex: String) = UsdCents.rateFromDouble(price(hex))
        ?: error("no rate for $hex, which the generator converted at")

    private fun longOrNull(token: String): Long? = if (token == "-") null else token.toLong()

    @Test
    fun everyVectorGivesTheCoresAnswer() {
        val counts = HashMap<String, Int>()
        for (f in vectors) {
            counts[f[0]] = (counts[f[0]] ?: 0) + 1
            val line = f.joinToString(" ")
            when (f[0]) {
                "rate" -> {
                    val got = UsdCents.rateFromDouble(price(f[1]))
                    if (f[3] == "-") {
                        assertEquals(line, null, got)
                    } else {
                        assertEquals(line, "${f[3]}e${f[4]}", got?.let { "${it.unscaledValue()}e${-it.scale()}" })
                        assertEquals(line, BigInteger(f[3]), got!!.unscaledValue())
                    }
                }
                "c4s" -> assertEquals(line, longOrNull(f[3]), UsdCents.centsForSats(f[1].toLong(), rate(f[2])))
                "s4c" -> assertEquals(line, longOrNull(f[3]), UsdCents.satsForCents(f[1].toLong(), rate(f[2])))
                "parse" -> {
                    val input = text(f[1])
                    val want = longOrNull(f[2])
                    val got = UsdCents.parse(input)
                    val kotlinOnly = KOTLIN_ONLY[input]
                    if (kotlinOnly != null) {
                        assertEquals("$line: the core refuses what only Kotlin reads", null, want)
                        assertTrue("$line: Kotlin gave $got", got == want || got == kotlinOnly)
                    } else {
                        assertEquals(line, want, got)
                    }
                }
                "format" -> assertEquals(line, text(f[2]), UsdCents.format(f[1].toLong()))
                "preview" -> assertEquals(line, text(f[3]), UsdCents.previewForDgb(text(f[1]), price(f[2])))
                "dgbtext" -> assertEquals(line, text(f[3]), UsdCents.dgbTextFor(text(f[1]), price(f[2])))
                else -> error("unknown vector kind: $line")
            }
        }
        // An empty or truncated file must not pass: these are well below what generate.c writes.
        val floor = mapOf("rate" to 400, "c4s" to 2000, "s4c" to 1500, "parse" to 90, "format" to 15,
            "preview" to 1500, "dgbtext" to 1000)
        for ((kind, min) in floor) {
            assertTrue("$kind: ${counts[kind] ?: 0} vectors, expected at least $min", (counts[kind] ?: 0) >= min)
        }
    }

    /** The prices core #60's review named are in the corpus, so dropping one is a failure, not a gap. */
    @Test
    fun theCorpusCarriesTheHardPrices() {
        val rated = vectors.filter { it[0] == "rate" }.map { price(it[1]).toRawBits() }.toSet()
        for (p in listOf(Math.scalb(1.0, -24), Math.scalb(1.0, -44), 2e23, Double.MIN_VALUE, Double.MAX_VALUE,
            0.00612, 2.82879384806159e17, 1.8014398509481988e16, 1.801439850948199e16)) {
            assertTrue("no rate vector for $p", p.toRawBits() in rated)
        }
    }

    private companion object {
        /** Text BRUsdCentsParse refuses by design and Kotlin may read: what Kotlin reads it as. */
        val KOTLIN_ONLY: Map<String, Long> = mapOf(
            " 5" to 500L, "5 " to 500L, " 5" to 500L, "\u001c5" to 500L, "5\u001f" to 500L,
            "٥" to 500L, "５" to 500L, "٥.٥" to 550L,
        )
    }
}
