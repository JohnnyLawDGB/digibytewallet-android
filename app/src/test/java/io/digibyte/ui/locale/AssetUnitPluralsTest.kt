package io.digibyte.ui.locale

import io.digibyte.core.locale.AppLocale
import io.digibyte.ui.KotlinSourceGate
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The words written after an asset quantity agree with the quantity, in every language.
 *
 * "1 tokens" and "1 units" were plain strings put after any number. They are plurals now, chosen
 * by the number they follow; LocaleResourceParityTest checks each language has the forms its
 * grammar uses. The activity row's fallback noun was English written in the core ("Token" /
 * "Tokens"); the core now hands the UI the units and the asset's name, and the UI words them.
 */
class AssetUnitPluralsTest {

    private val resDir = File("src/main/res")

    private fun dirOf(tag: String): String =
        if (tag == "en") "values" else if (tag.length == 2) "values-$tag" else "values-b+" + tag.replace('-', '+')

    private fun wallet(tag: String): String = File(resDir, "${dirOf(tag)}/strings_wallet.xml").readText()

    @Test fun `the unit words are plurals in every language, and no longer plain strings`() {
        val wrong = AppLocale.SUPPORTED.flatMap { entry ->
            val xml = wallet(entry.tag)
            listOf("as_tokens", "as_units", "as_stays").mapNotNull { name ->
                when {
                    Regex("""<string\s+name="$name"""").containsMatchIn(xml) -> "${entry.tag}: $name is still a plain string"
                    !Regex("""<plurals\s+name="$name"""").containsMatchIn(xml) -> "${entry.tag}: $name is not a plural"
                    else -> null
                }
            }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun `the asset send screen words quantities through the plurals`() {
        val screen = KotlinSourceGate.of(File("src/main/java/io/digibyte/ui/asset/AssetSendScreen.kt").readText())
        for (name in listOf("as_tokens", "as_units", "as_stays")) {
            assertTrue("AssetSendScreen still reads R.string.$name",
                !Regex("""R\s*\.\s*string\s*\.\s*$name\b""").containsMatchIn(screen.code))
        }
        assertTrue("AssetSendScreen reads no plural",
            Regex("""R\s*\.\s*plurals\s*\.\s*as_stays\b""").containsMatchIn(screen.code))
    }

    @Test fun `the core writes no English unit noun for an asset amount`() {
        val manager = KotlinSourceGate.of(File("../core/src/main/java/io/digibyte/core/asset/AssetManager.kt").readText())
        val nouns = Regex(""""[^"\n]*\bTokens?\b[^"\n]*"""").findAll(manager.written).map { it.value }.toList()
        assertTrue("English unit nouns in AssetManager: $nouns", nouns.isEmpty())
    }
}
