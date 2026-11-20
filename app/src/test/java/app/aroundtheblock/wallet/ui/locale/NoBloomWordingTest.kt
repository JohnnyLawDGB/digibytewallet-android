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
 *  - a string resource, in any language (the `strings_wallet.xml` of every `values` directory);
 *  - a literal written straight into a screen (`ui/`), which no resource check can see. That is
 *    where the sentence this row removes was.
 *
 * Internal names that still carry the old word (a prefs file, a constant, the seeder's name) are
 * not shown to anyone and are not this gate's business: it reads resource VALUES and the literals
 * of the `ui` package only.
 */
class NoBloomWordingTest {

    private val resDir = File("src/main/res")
    private val uiDir = File("src/main/java/app/aroundtheblock/wallet/ui")

    private val stringFiles: List<File>
        get() = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .map { File(it, "strings_wallet.xml") }.filter { it.exists() }.sortedBy { it.parentFile.name }

    /** name → value of every `<string>` and `<item>` in one file. */
    private fun values(file: File): List<Pair<String, String>> {
        val text = file.readText()
        val strings = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).map { it.groupValues[1] to it.groupValues[2] }
        val items = Regex("""<item[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).map { "<item>" to it.groupValues[1] }
        return (strings + items).toList()
    }

    private fun uiLiterals(file: File): List<String> {
        val gate = KotlinSourceGate.of(file.readText())
        return Regex(""""((?:[^"\\]|\\.)*)"""").findAll(gate.written).map { it.groupValues[1] }.toList()
    }

    @Test fun `the gate reads every language and the screens`() {
        assertTrue("res dir not found at ${resDir.absolutePath}", resDir.isDirectory)
        assertTrue("fewer string files than languages: ${stringFiles.size}", stringFiles.size >= 13)
        for (f in stringFiles) assertTrue("${f.parentFile.name}: no strings read", values(f).size > 100)
        assertTrue("ui dir not found at ${uiDir.absolutePath}", uiDir.isDirectory)
        assertTrue(File(uiDir, "settings/ReconcileScreen.kt").isFile)
    }

    @Test fun `the pattern knows each language's spelling`() {
        val spellings = listOf(
            "bloom filters", "Bloom-Filter", "filtros bloom", "filtres de Bloom", "bộ lọc Bloom",
            "merkleblocks", "Merkle-Blöcke", "BIP37", "BIP 37", "BIP-37", "filterload",
            "ブルームフィルター", "マークルブロック", "布隆过滤器", "布隆過濾器", "默克尔区块", "梅克尔",
            "блум-фильтры", "фильтры Блума", "меркл-блоки", "блоки Меркла",
            "ब्लूम फ़िल्टर", "मर्कल ब्लॉक",
        )
        for (s in spellings) assertTrue("not caught: $s", RETIRED.containsMatchIn(s))
        val current = listOf("compact block filters (BIP157/158)", "компактными фильтрами", "紧凑过滤器",
            "コンパクトフィルター", "कॉम्पैक्ट फ़िल्टर", "BIP-39", "BIP 137", "BIP370")
        for (s in current) assertTrue("caught by mistake: $s", !RETIRED.containsMatchIn(s))
    }

    @Test fun `no string in any language names bloom filters or merkleblocks`() {
        val hits = stringFiles.flatMap { f ->
            values(f).filter { RETIRED.containsMatchIn(it.second) }.map { "${f.parentFile.name}/${it.first}" }
        }
        assertEquals("strings that name the removed mechanism", emptyList<String>(), hits)
    }

    @Test fun `no screen writes bloom filters or merkleblocks into its text`() {
        val files = uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("too few screens read: ${files.size}", files.size > 40)
        val hits = files.flatMap { f ->
            uiLiterals(f).filter { RETIRED.containsMatchIn(it) }.map { "${f.relativeTo(uiDir)}: \"$it\"" }
        }
        assertEquals("screen text that names the removed mechanism", emptyList<String>(), hits)
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
         * Chinese (both scripts), Russian and Hindi. `BIP37` must not catch BIP370 or BIP137.
         */
        val RETIRED = Regex(
            "bloom|merkle|filterload|(?<![0-9A-Za-z])BIP[- ]?37(?![0-9])" +
                "|ブルーム|マークル|布隆|默克尔|默克爾|梅克尔|梅克爾" +
                "|блум|меркл" +
                "|ब्लूम|मर्कल",
            RegexOption.IGNORE_CASE,
        )
    }
}
