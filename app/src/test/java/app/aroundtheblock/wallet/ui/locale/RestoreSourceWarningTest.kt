package app.aroundtheblock.wallet.ui.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RESTORE-WARN-SIGMA (owner 2026-11-23): the phrase-entry screen warns against restoring a phrase
 * from one named wallet app, and says what to do instead.
 *
 * What this pins, in every language directory:
 *  - the title and the body exist and still name the app and its maker, untranslated, so a user can
 *    match them against what is on their phone;
 *  - the body carries exactly one `%1$s`, which the screen fills with the localised name of
 *    "Recover funds from another wallet". A translation that drops it leaves the user with a
 *    warning and no way out; one that adds a second crashes the screen on that locale.
 *
 * And in the screen: the warning is drawn on the phrase-entry screen itself, above the word
 * fields, not behind a tap.
 *
 * What it cannot check: that the translations are right. A native speaker has to.
 */
class RestoreSourceWarningTest {

    private val resDir = File("src/main/res")
    private val screen = File("src/main/java/app/aroundtheblock/wallet/ui/onboarding/MnemonicInputScreen.kt")

    private fun valuesDirs(): List<File> =
        resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") && File(f, "strings_wallet.xml").exists() }!!
            .sortedBy { it.name }

    private fun string(dir: File, name: String): String? =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(File(dir, "strings_wallet.xml").readText())?.groupValues?.get(1)

    @Test
    fun `every language names the app and its maker in the title`() {
        val dirs = valuesDirs()
        assertEquals("the 13 language directories", 13, dirs.size)
        for (dir in dirs) {
            val title = string(dir, "recover_sigma_warning_title")
            assertTrue("${dir.name}: title missing", title != null)
            assertTrue("${dir.name}: title must name the app", title!!.contains("DigiByte Wallet"))
            assertTrue("${dir.name}: title must name the maker", title.contains("Sigma Systems"))
        }
    }

    @Test
    fun `every language's body carries the one placeholder for the way out`() {
        for (dir in valuesDirs()) {
            val body = string(dir, "recover_sigma_warning_body")
            assertTrue("${dir.name}: body missing", body != null)
            assertEquals("${dir.name}: exactly one %1\$s", 1, Regex("""%1\${'$'}s""").findAll(body!!).count())
            assertEquals("${dir.name}: no other format argument", 1, Regex("%").findAll(body).count())
        }
    }

    @Test
    fun `the phrase-entry screen draws the warning above the word fields with the recover-funds name`() {
        val src = screen.readText()
        val column = src.substringAfter("R.string.recover_body")
        val call = column.indexOf("CompromisedSourceWarning()")
        val words = column.indexOf("// Word count selector")
        assertTrue("the warning is drawn on the screen", call >= 0)
        assertTrue("and before the word fields", words >= 0 && call < words)
        val body = src.substringAfter("private fun CompromisedSourceWarning()")
        assertTrue("title shown", body.contains("R.string.recover_sigma_warning_title"))
        assertTrue(
            "body filled with the localised Recover funds name",
            Regex("""R\.string\.recover_sigma_warning_body,\s*stringResource\(R\.string\.rf_title\)""").containsMatchIn(body)
        )
    }
}
