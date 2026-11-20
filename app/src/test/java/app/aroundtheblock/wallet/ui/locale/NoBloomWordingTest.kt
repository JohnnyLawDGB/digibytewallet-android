package app.aroundtheblock.wallet.ui.locale

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RECONCILE-COPY: no text the user reads says the wallet uses bloom filters or merkleblocks.
 *
 * Bloom filtering (BIP37) was removed in v4.0.0; the wallet finds its transactions with compact
 * block filters (BIP157/158) and nothing else. Text that still names the old mechanism tells the
 * user something false about what leaves the device.
 *
 * Two places can hold such text, and each has its own arm:
 *  - a string resource, in any language: every XML file of every `values` directory;
 *  - a literal written straight into Kotlin, which no resource check can see. That is where the
 *    sentence this row removes was. A screen shows text built far from `ui/` (the scan's progress
 *    and failure messages are written in `core`, a notification's in `service`), so this arm
 *    reads the main sources of all three modules.
 *
 * Internal names that still carry the old word are not shown to anyone and are not this gate's
 * business: it reads resource VALUES and string literals, never an identifier or a comment, and
 * two kinds of literal are let through by name: the prefs file in [INTERNAL_NAMES], and the
 * arguments of a `Log` call.
 */
class NoBloomWordingTest {

    private val resDir = File("src/main/res")
    private val uiDir = File("src/main/java/app/aroundtheblock/wallet/ui")

    /** The main Kotlin of every module whose text can reach a screen. Tests run in `app/`. */
    private val sourceRoots = listOf(File("src/main/java"), File("../core/src/main/java"), File("../game/src/main/java"))

    private val valuesDirs: List<File>
        get() = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!.sortedBy { it.name }

    private val stringFiles: List<File>
        get() = valuesDirs.map { File(it, "strings_wallet.xml") }.filter { it.exists() }

    /** Every resource file of every language, whatever it is called. */
    private val resourceFiles: List<File>
        get() = valuesDirs.flatMap { d -> d.listFiles { f -> f.isFile && f.extension == "xml" }!!.sortedBy { it.name } }

    /** name → value of every `<string>` and `<item>` in one file. */
    private fun values(file: File): List<Pair<String, String>> {
        val text = file.readText()
        val strings = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).map { it.groupValues[1] to it.groupValues[2] }
        val items = Regex("""<item[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).map { "<item>" to it.groupValues[1] }
        return (strings + items).toList()
    }

    /**
     * The text of every string literal in [source], piece by piece, without the arguments of a
     * `Log` call. A piece is what stands between two things that are not literal text (a quote, a
     * template, code), so a word is found wherever in a literal it stands.
     */
    private fun shownLiterals(source: String): List<String> {
        val gate = KotlinSourceGate.of(source)
        val logged = LOG_CALLS.flatMap { gate.calls(it) }.map { it.range }
        val text = StringBuilder(source.length)
        for (at in gate.written.indices) {
            val written = gate.written[at]
            val literal = written != gate.code[at] || written == ' '
            text.append(if (literal && logged.none { at in it }) written else BREAK)
        }
        return text.split(BREAK).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun kotlinFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    @Test fun `the gate reads every language and the screens`() {
        assertTrue("res dir not found at ${resDir.absolutePath}", resDir.isDirectory)
        assertTrue("fewer string files than languages: ${stringFiles.size}", stringFiles.size >= 13)
        for (f in stringFiles) assertTrue("${f.parentFile.name}: no strings read", values(f).size > 100)
        assertTrue("ui dir not found at ${uiDir.absolutePath}", uiDir.isDirectory)
        assertTrue(File(uiDir, "settings/ReconcileScreen.kt").isFile)
        assertTrue("fewer resource files than string files", resourceFiles.size > stringFiles.size)
        for (root in sourceRoots) assertTrue("source root not found at ${root.absolutePath}", root.isDirectory)
        val read = sourceRoots.flatMap { kotlinFiles(it) }.map { it.invariantSeparatorsPath }
        for (shown in listOf(
            "/ui/settings/ReconcileScreen.kt", "/core/reconcile/ChainReconciliationService.kt", "/service/SyncService.kt",
        )) assertTrue("not read: $shown", read.any { it.endsWith(shown) })
    }

    /** The reader itself: it sees a literal wherever it stands, and passes only what is named as internal. */
    @Test fun `the literal reader sees shown text and passes only internal names`() {
        fun hits(source: String) = shownLiterals(source).filter { RETIRED.containsMatchIn(it) && it !in INTERNAL_NAMES }
        assertEquals(listOf("the bloom scan missed it"), hits("""val m = State.Failed("the bloom scan missed it")"""))
        assertEquals(listOf("BIP 37 scan of"), hits("""val m = "BIP 37 scan of ${'$'}{n.size} addresses""""))
        assertEquals(listOf("merkleblocks"), hits("val m = \"\"\"\n  merkleblocks\n\"\"\""))
        assertEquals(listOf("bloom"), hits("""notify(title = "bloom"); Log.d(TAG, "x")"""))
        assertEquals(emptyList<String>(), hits("// \"bloom filters\"\nval bloomPeers = 1 /* \"merkleblocks\" */"))
        assertEquals(emptyList<String>(), hits("""prefs("dgb_bloom_peers")"""))
        assertEquals(listOf("dgb_bloom_peers are used"), hits("""Text("dgb_bloom_peers are used")"""))
        assertEquals(emptyList<String>(), hits("android.util.Log.d(\"Sync\", \"staying on \" +\n    \"filters (bloom removed)\")"))
        assertEquals(emptyList<String>(), hits("""Log.w(TAG, "no bloom: ${'$'}{e.message}", e)"""))
    }

    @Test fun `the pattern knows each language's spelling`() {
        val spellings = listOf(
            "bloom filters", "Bloom-Filter", "filtros bloom", "filtres de Bloom", "bộ lọc Bloom",
            "merkleblocks", "Merkle-Blöcke", "BIP37", "BIP 37", "BIP-37", "filterload",
            "ブルームフィルター", "マークルブロック", "布隆过滤器", "布隆過濾器", "默克尔区块", "梅克尔",
            "блум-фильтры", "фильтры Блума", "меркл-блоки", "блоки Меркла",
            "ब्लूम फ़िल्टर", "मर्कल ब्लॉक",
            // Second spellings a translation can pick: other transliterations, and BIP numbers written in full.
            "布鲁姆过滤器", "布魯姆過濾器", "莫克尔区块", "莫克爾區塊", "メルクルブロック",
            "मर्केल ब्लॉक", "ब्लुम फ़िल्टर", "блюм-фильтры", "Blum filtresi", "Blum'un filtresi",
            "BIP-0037", "BIP0037", "BIP 0037",
        )
        for (s in spellings) assertTrue("not caught: $s", RETIRED.containsMatchIn(s))
        val current = listOf("compact block filters (BIP157/158)", "компактными фильтрами", "紧凑过滤器",
            "コンパクトフィルター", "कॉम्पैक्ट फ़िल्टर", "BIP-39", "BIP 137", "BIP370",
            "BIP-0039", "BIP00370", "Blume", "Blumen")
        for (s in current) assertTrue("caught by mistake: $s", !RETIRED.containsMatchIn(s))
    }

    @Test fun `no string in any language names bloom filters or merkleblocks`() {
        val hits = resourceFiles.flatMap { f ->
            values(f).filter { RETIRED.containsMatchIn(it.second) }.map { "${f.parentFile.name}/${f.name}/${it.first}" }
        }
        assertEquals("strings that name the removed mechanism", emptyList<String>(), hits)
    }

    @Test fun `no screen writes bloom filters or merkleblocks into its text`() {
        assertTrue("too few screens read: ${kotlinFiles(uiDir).size}", kotlinFiles(uiDir).size > 40)
        val hits = sourceRoots.flatMap { root ->
            kotlinFiles(root).flatMap { f ->
                shownLiterals(f.readText()).filter { RETIRED.containsMatchIn(it) && it !in INTERNAL_NAMES }
                    .map { "${f.relativeTo(root)}: \"$it\"" }
            }
        }
        assertEquals("text that names the removed mechanism", emptyList<String>(), hits)
    }

    /** The scan's own description comes from a resource, and that resource says what the wallet does today. */
    @Test fun `the scan's description is a resource that names compact block filters`() {
        val screen = KotlinSourceGate.of(File(uiDir, "settings/ReconcileScreen.kt").readText()).written
        assertTrue("ReconcileScreen does not read reconcile_intro_body",
            screen.contains("stringResource(R.string.reconcile_intro_body)"))
        val english = values(File(resDir, "values/strings_wallet.xml")).toMap()["reconcile_intro_body"]
        assertTrue("English reconcile_intro_body missing or does not name the mechanism: $english",
            english != null && english.contains("compact block filters (BIP157/158)"))
        for (f in stringFiles) {
            val body = values(f).toMap()["reconcile_intro_body"]
            assertTrue("${f.parentFile.name}: reconcile_intro_body missing", body != null)
            assertTrue("${f.parentFile.name}: reconcile_intro_body does not name BIP157/158", body!!.contains("BIP157/158"))
            assertTrue("${f.parentFile.name}: reconcile_intro_body lost its paragraph break", body.contains("\\n\\n"))
        }
    }

    private companion object {
        /**
         * The removed mechanism, by every spelling found or expected in the 13 languages: Latin
         * script (en, de, es, fr, pt-BR, id, vi, tr, fil all keep "Bloom"/"Merkle"), Japanese,
         * Chinese (both scripts), Russian and Hindi, with the second transliterations each of
         * those has. `BIP37` is caught however its number is padded (BIP-0037) and must not catch
         * BIP370 or BIP137; the phonetic `Blum` must not catch the German `Blume`.
         */
        val RETIRED = Regex(
            "bloom|merkle|filterload|(?<![0-9A-Za-z])BIP[- ]?0*37(?![0-9])|(?<![A-Za-z])blum(?![A-Za-z])" +
                "|ブルーム|マークル|メルクル|布隆|布鲁姆|布魯姆|默克尔|默克爾|梅克尔|梅克爾|莫克尔|莫克爾" +
                "|блум|блюм|меркл" +
                "|ब्लूम|ब्लुम|मर्कल|मर्केल",
            RegexOption.IGNORE_CASE,
        )

        /** Literals that are names of things on disk, whole, and never text: the peer cache's prefs file. */
        val INTERNAL_NAMES = setOf("dgb_bloom_peers")

        /** A log line is not shown to the user; its arguments are not read. */
        val LOG_CALLS = listOf("Log.v", "Log.d", "Log.i", "Log.w", "Log.e", "Log.wtf")

        /** Stands where a source file has something other than literal text. */
        const val BREAK = '\u0001'
        
    }
}
