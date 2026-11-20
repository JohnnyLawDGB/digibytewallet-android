package app.aroundtheblock.wallet.core.ipfs

import app.aroundtheblock.wallet.core.db.dao.AssetMetadataDao
import app.aroundtheblock.wallet.core.db.entity.AssetMetadataEntity
import app.aroundtheblock.wallet.core.ipfs.AssetMetadataService.Companion.displaySafe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ASSET-NONCHAR: displayed asset metadata never carries a Unicode noncharacter.
 *
 * The noncharacters are 66 code points Unicode sets aside for good: U+FDD0..U+FDEF and the last
 * two code points of each of the 17 planes (U+FFFE, U+FFFF, U+1FFFE, U+1FFFF ... U+10FFFE,
 * U+10FFFF). The JVM files them under "unassigned", the one category the sanitizer keeps on
 * purpose, so they need their own rule. Unlike other unassigned code points they will never be
 * assigned, on any device, in any Unicode version.
 *
 * Three things are pinned here: the rule itself, that nothing beside it moved, and that every way
 * metadata text reaches the screen goes through the one sanitizer.
 */
class AssetMetadataNoncharacterTest {

    private val short = AssetMetadataService.MAX_SHORT_TEXT_LEN
    private val long = AssetMetadataService.MAX_LONG_TEXT_LEN

    private fun name(s: String?) = AssetMetadataService.sanitize(s, short, allowNewlines = false)
    private fun description(s: String?) = AssetMetadataService.sanitize(s, long, allowNewlines = true)
    private fun cp(c: Int) = String(Character.toChars(c))

    private val arabicBlock = (0xFDD0..0xFDEF).toList()
    private val planeEnds = (0..16).flatMap { plane -> listOf(plane * 0x10000 + 0xFFFE, plane * 0x10000 + 0xFFFF) }
    private val noncharacters = arabicBlock + planeEnds

    /**
     * What the sanitizer already dropped before this rule whatever the category: the ranges that
     * draw nothing. Some are unassigned on an older runtime (U+180F on a Unicode 13 JVM).
     */
    private fun drewNothingBefore(c: Int) =
        c == 0x034F || c == 0x115F || c == 0x1160 || c == 0x17B4 || c == 0x17B5 || c in 0x180B..0x180F ||
            c in 0x2060..0x206F || c == 0x3164 || c in 0xFE00..0xFE0F || c == 0xFFA0 || c in 0xFFF0..0xFFF8 ||
            c in 0x1BCA0..0x1BCA3 || c in 0x1D173..0x1D17A || c in 0xE0000..0xE0FFF || c == 0x2800

    // -------------------------------------------------------------------------
    // The rule
    // -------------------------------------------------------------------------

    @Test
    fun `there are sixty-six noncharacters`() {
        assertEquals(66, noncharacters.toSet().size)
    }

    @Test
    fun `U+FDD0 to U+FDEF are dropped`() {
        for (c in arabicBlock) {
            assertFalse("U+%04X".format(c), AssetMetadataService.isDisplaySafe(c))
            assertEquals("U+%04X".format(c), "DigiDollar", name("Digi" + cp(c) + "Dollar"))
            assertEquals("U+%04X".format(c), "DigiDollar", description("Digi" + cp(c) + "Dollar"))
        }
    }

    @Test
    fun `U+FFFE and U+FFFF are dropped`() {
        for (c in listOf(0xFFFE, 0xFFFF)) {
            assertFalse("U+%04X".format(c), AssetMetadataService.isDisplaySafe(c))
            assertFalse("U+%04X".format(c), AssetMetadataService.isDisplaySafe(c.toChar()))
            assertEquals("U+%04X".format(c), "DigiDollar", name("Digi" + cp(c) + "Dollar"))
        }
    }

    @Test
    fun `the last two code points of every supplementary plane are dropped`() {
        for (c in planeEnds.filter { it > 0xFFFF }) {
            val pair = cp(c)
            assertEquals("U+%04X is one pair".format(c), 2, pair.length)
            assertFalse("U+%04X".format(c), AssetMetadataService.isDisplaySafe(c))
            // The pair goes whole: neither half is left behind.
            val out = name("Digi" + pair + "Dollar")
            assertEquals("U+%04X".format(c), "DigiDollar", out)
            assertEquals("U+%04X".format(c), "DigiDollar", description("Digi" + pair + "Dollar"))
        }
        // The same thing spelled as UTF-16 units: U+1FFFE and U+10FFFF.
        assertEquals("AB", name("A\uD83F\uDFFEB"))
        assertEquals("AB", name("A\uDBFF\uDFFFB"))
    }

    @Test
    fun `a name made only of noncharacters is what an empty name is`() {
        assertNull(name(""))
        assertNull(name("\uFFFF"))
        assertNull(name("\uFDD0\uFDEF\uFFFE\uFFFF"))
        assertNull(name(noncharacters.joinToString("") { cp(it) }))
        assertNull(name(" \uFFFE " + cp(0x10FFFF) + " "))
        assertNull(description(noncharacters.joinToString("") { cp(it) }))
    }

    @Test
    fun `a dropped noncharacter does not use up the length cap`() {
        assertEquals("abcd", AssetMetadataService.sanitize("\uFFFF".repeat(50) + "abcde", maxLen = 4, allowNewlines = false))
        assertEquals("abcd", AssetMetadataService.sanitize(cp(0x1FFFE).repeat(50) + "abcde", maxLen = 4, allowNewlines = false))
    }

    // -------------------------------------------------------------------------
    // Nothing beside the rule moved
    // -------------------------------------------------------------------------

    @Test
    fun `the code points next to each range are treated as before`() {
        // Kept before, kept now.
        val kept = listOf(
            0xFDCF, 0xFDF0,          // either side of U+FDD0..U+FDEF
            0xFFFC, 0xFFFD,          // object replacement, replacement character
            0x1FFFD, 0x2FFFD,        // the code point before a plane's last two
            0x10000, 0x20000,        // the code point after them
            0xFDFD,                  // an assigned Arabic ligature in the same block
        )
        for (c in kept) {
            assertTrue("U+%04X".format(c), AssetMetadataService.isDisplaySafe(c))
            assertEquals("U+%04X".format(c), "A" + cp(c) + "B", name("A" + cp(c) + "B"))
        }
        // Dropped before (private use), dropped now.
        for (c in listOf(0xE000, 0xF8FF, 0xF0000, 0xFFFFD, 0x100000, 0x10FFFD)) {
            assertFalse("U+%04X".format(c), AssetMetadataService.isDisplaySafe(c))
            assertEquals("U+%04X".format(c), "AB", name("A" + cp(c) + "B"))
        }
    }

    @Test
    fun `across all of Unicode the noncharacters are dropped and every other unassigned code point is kept`() {
        val wronglyKept = ArrayList<Int>()
        val wronglyDropped = ArrayList<Int>()
        val set = noncharacters.toSet()
        for (c in 0..Character.MAX_CODE_POINT) {
            val safe = AssetMetadataService.isDisplaySafe(c)
            if (c in set) {
                if (safe) wronglyKept += c
            } else if (Character.getType(c) == Character.UNASSIGNED.toInt() && !drewNothingBefore(c)) {
                // A code point this device's Unicode tables do not know yet may be a newer emoji.
                if (!safe) wronglyDropped += c
            }
        }
        assertEquals("noncharacters kept", emptyList<String>(), wronglyKept.map { "U+%04X".format(it) })
        assertEquals("unassigned code points dropped", emptyList<String>(), wronglyDropped.take(20).map { "U+%04X".format(it) })
    }

    @Test
    fun `ordinary names with astral characters are unchanged`() {
        for (s in listOf("MyAsset \uD83E\uDE99", "\uD83D\uDE00\uD83D\uDE80", "\u30C7\u30B8\u30D0\u30A4\u30C8", "M\u00FCllerstra\u00DFe")) {
            assertEquals(s, name(s))
            assertEquals(s, description(s))
        }
    }

    // -------------------------------------------------------------------------
    // Lone surrogates: dropped, as they were (category Cs). Stated so it cannot drift.
    // -------------------------------------------------------------------------

    @Test
    fun `a lone surrogate is dropped wherever it stands`() {
        assertEquals("AB", name("A\uD800B"))            // high half, no low half after it
        assertEquals("AB", name("A\uDC00B"))            // low half on its own
        assertEquals("AB", name("AB\uD83F"))            // high half at the end of the text
        assertEquals("AB", name("\uDFFEAB"))            // low half at the start
        assertEquals("AB", name("A\uDFFE\uD83FB"))      // the two halves the wrong way round
        assertNull(name("\uD83F"))
        assertNull(name("\uDFFF\uDBFF"))
        for (c in listOf(0xD800, 0xDBFF, 0xDC00, 0xDFFE, 0xDFFF)) {
            assertFalse("U+%04X".format(c), AssetMetadataService.isDisplaySafe(c))
        }
    }

    @Test
    fun `a lone half and a noncharacter side by side are both dropped`() {
        assertEquals("AB", name("A\uD83F\uFFFFB"))
        assertEquals("AB", name("A\uFFFE\uDFFEB"))
    }

    @Test
    fun `a lone half next to a real pair does not take the pair with it`() {
        val coin = "\uD83E\uDE99"
        assertEquals("A" + coin + "B", name("A\uD83F" + coin + "B"))
        assertEquals("A" + coin + "B", name("A" + coin + "\uDFFFB"))
    }

    // -------------------------------------------------------------------------
    // Every way metadata text reaches the screen goes through the one sanitizer
    // -------------------------------------------------------------------------

    private val assetId = "La8JvCqENcANWZsFdjW5nfZki3EsnEzuycLJod"
    private val dirty = "Digi\uFFFF" + cp(0x1FFFE) + "\uFDD0Dollar"

    private fun service(dao: AssetMetadataDao) = AssetMetadataService(
        ipfsClient = mockk(relaxed = true),
        assetMetadataDao = dao,
    )

    private data class Written(val name: String?, val symbol: String?, val description: String?)

    /** Stores [document] through the write path and returns the text columns handed to the DAO. */
    private fun store(document: JSONObject): Written = runBlocking {
        val dao = mockk<AssetMetadataDao>(relaxed = true)
        val n = slot<String?>(); val s = slot<String?>(); val d = slot<String?>()
        var written: Written? = null
        coEvery {
            dao.updateIpfsFacts(any(), captureNullable(n), captureNullable(s), captureNullable(d), any(), any(), any(), any())
        } answers { written = Written(n.captured, s.captured, d.captured) }
        coEvery { dao.getMetadata(any()) } returns null
        service(dao).storeFromJson(assetId, "bafytest", document, provenIssuer = null)
        written ?: error("the document was never written")
    }

    @Test
    fun `a stored document has no noncharacter in its name, symbol or description`() {
        val canonical = store(JSONObject().put("data", JSONObject()
            .put("assetName", dirty).put("symbol", "D\uFFFEGD").put("description", "first\n" + dirty)))
        assertEquals(Written("DigiDollar", "DGD", "first\nDigiDollar"), canonical)

        // The older top-level shape and its alternative field names.
        val older = store(JSONObject().put("name", dirty).put("ticker", "DG" + cp(0x10FFFF) + "D"))
        assertEquals(Written("DigiDollar", "DGD", null), older)
    }

    @Test
    fun `a stored document whose name is only noncharacters has no name`() {
        val written = store(JSONObject().put("assetName", "\uFFFF\uFDD0").put("symbol", cp(0x2FFFF)).put("description", "\uFFFE"))
        assertEquals(Written(null, null, null), written)
    }

    @Test
    fun `the model a stored document returns is clean`() = runBlocking {
        val dao = mockk<AssetMetadataDao>(relaxed = true)
        coEvery { dao.getMetadata(any()) } returns null
        val model = service(dao).storeFromJson(assetId, "bafytest", JSONObject().put("assetName", dirty), provenIssuer = null)
        assertEquals("DigiDollar", model.name)
    }

    private fun staleRow() = AssetMetadataEntity(
        assetId = assetId,
        name = dirty,
        symbol = "D\uFFFEGD",
        description = "first\n" + dirty,
        decimals = 8,
        totalSupply = 42,
        metadataCid = "bafytest",
    )

    @Test
    fun `a row cached by an older build is read clean by the metadata service`() = runBlocking {
        val dao = mockk<AssetMetadataDao>(relaxed = true)
        coEvery { dao.getMetadata(assetId) } returns staleRow()
        val model = service(dao).getMetadata(assetId, metadataCid = null)!!
        assertEquals("DigiDollar", model.name)
        assertEquals("DGD", model.symbol)
        assertEquals("first\nDigiDollar", model.description)
    }

    @Test
    fun `a row cached by an older build displays clean`() {
        val stale = staleRow()
        val shown = stale.displaySafe()
        assertEquals("DigiDollar", shown.name)
        assertEquals("DGD", shown.symbol)
        assertEquals("first\nDigiDollar", shown.description)
        assertEquals(stale.copy(name = shown.name, symbol = shown.symbol, description = shown.description), shown)
        assertEquals(shown, shown.displaySafe())
        assertNull(stale.copy(name = "\uFFFF").displaySafe().name)
    }

    // The two readers in AssetManager need the native wallet to run, so they are held by source.

    private val root = File("..").canonicalFile
    private val mainSources = listOf("core/src/main", "app/src/main")
        .flatMap { File(root, it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    private fun rel(f: File) = f.relativeTo(root).path

    /** A read of the cached row: the DAO's two readers, on any receiver whose name ends in "etadataDao". */
    private val daoRead = Regex("""etadataDao\s*\.\s*(getMetadata|getAllMetadata)\s*\(""")

    /** How far below a read the sanitizer may stand: the Assets-screen flow debounces in between. */
    private val window = 8

    private fun unsanitizedReads(path: String, lines: List<String>): List<String> =
        lines.withIndex().filter { daoRead.containsMatchIn(it.value) }.mapNotNull { (i, line) ->
            val below = lines.subList(i, minOf(lines.size, i + window)).joinToString("\n")
            val clean = below.contains(".displaySafe()") || below.contains(".toModel()") ||
                // Asks only whether a name exists; shows nothing.
                line.contains("?.name.isNullOrBlank()")
            if (clean) null else "$path:${i + 1}: ${line.trim()}"
        }

    @Test
    fun `only the metadata service and the asset manager read cached metadata rows`() {
        val readers = mainSources.filter { f -> f.readLines().any { daoRead.containsMatchIn(it) } }.map { rel(it) }.sorted()
        assertEquals(
            listOf(
                "core/src/main/java/app/aroundtheblock/wallet/core/asset/AssetManager.kt",
                "core/src/main/java/app/aroundtheblock/wallet/core/ipfs/AssetMetadataService.kt",
            ),
            readers,
        )
    }

    @Test
    fun `every read of a cached metadata row goes through the sanitizer`() {
        val sites = mainSources.sumOf { f -> f.readLines().count { daoRead.containsMatchIn(it) } }
        assertEquals("cached-row read sites; a new one must be added to this count knowingly", 5, sites)
        val hits = mainSources.flatMap { unsanitizedReads(rel(it), it.readLines()) }
        assertEquals("a cached metadata row is read without the sanitizer", emptyList<String>(), hits)
    }

    @Test
    fun `the read gate sees a read that skips the sanitizer`() {
        val bad = listOf("val row = metadataDao.getMetadata(id)", "return row?.name")
        assertEquals(1, unsanitizedReads("x.kt", bad).size)
        val good = listOf("val row = metadataDao.getMetadata(id)?.displaySafe()", "return row?.name")
        assertEquals(0, unsanitizedReads("x.kt", good).size)
    }

    @Test
    fun `the service turns a row into a model only through the sanitizer`() {
        val text = File(root, "core/src/main/java/app/aroundtheblock/wallet/core/ipfs/AssetMetadataService.kt").readText()
        assertTrue(
            "toModel must start from displaySafe()",
            Regex("""fun AssetMetadataEntity\.toModel\(\)\s*=\s*displaySafe\(\)\.run\s*\{""").containsMatchIn(text),
        )
        // One place builds the model in the service, and it is that one.
        assertEquals(1, Regex("""[^A-Za-z]AssetMetadata\(""").findAll(text).count())
    }

    @Test
    fun `the cached row's text columns are written by the metadata service alone`() {
        val writers = mainSources.filter { it.readText().contains("updateIpfsFacts(") }.map { rel(it) }.sorted()
        assertEquals(
            listOf(
                "core/src/main/java/app/aroundtheblock/wallet/core/db/dao/AssetMetadataDao.kt",
                "core/src/main/java/app/aroundtheblock/wallet/core/ipfs/AssetMetadataService.kt",
            ),
            writers,
        )
        // No other statement of the DAO, and no entity built outside the service, sets a text column.
        val dao = File(root, "core/src/main/java/app/aroundtheblock/wallet/core/db/dao/AssetMetadataDao.kt").readText()
        val textWrites = Regex("""SET[^"]*\b(name|symbol|description)\s*=""").findAll(dao).count()
        assertEquals(1, textWrites)
        val entityWithText = Regex("""AssetMetadataEntity\((?:[^()]|\([^()]*\))*?\b(name|symbol|description)\s*=""")
        val builders = mainSources.filter { entityWithText.containsMatchIn(it.readText()) }.map { rel(it) }
        assertEquals(listOf("core/src/main/java/app/aroundtheblock/wallet/core/ipfs/AssetMetadataService.kt"), builders)
    }
}
