package app.aroundtheblock.wallet.ui.locale

import app.aroundtheblock.wallet.core.locale.AppLocale
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one-time history rebuild notice is translated before anything shows it: its title, its body
 * and its deferred line exist, with text, in every supported language.
 */
class HistoryRebuildNoticeStringsTest {

    private val resDir = File("src/main/res")

    private fun dirOf(tag: String): String =
        if (tag == "en") "values" else if (tag.length == 2) "values-$tag" else "values-b+" + tag.replace('-', '+')

    private fun wallet(tag: String): String = File(resDir, "${dirOf(tag)}/strings_wallet.xml").readText()

    @Test fun `the history rebuild notice is in every language`() {
        val keys = listOf("history_rebuild_notice_title", "history_rebuild_notice_body", "history_rebuild_deferred")
        val missing = AppLocale.SUPPORTED.flatMap { entry ->
            val xml = wallet(entry.tag)
            keys.filter { key ->
                val text = Regex("""<string\s+name="$key"\s*>(.*?)</string>""").find(xml)?.groupValues?.get(1)
                text.isNullOrBlank()
            }.map { "${entry.tag}: $it" }
        }
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
    }
}
