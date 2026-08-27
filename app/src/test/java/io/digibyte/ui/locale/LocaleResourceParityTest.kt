package io.digibyte.ui.locale

import io.digibyte.core.locale.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every language the wallet OFFERS must actually be translated.
 *
 * ## The failure this exists to stop
 *
 * A language can be listed in three places — [AppLocale.SUPPORTED], `xml/locales_config.xml`, and
 * a `values-xx/` resource directory — and nothing connects them. When they disagree, Android does
 * not complain: it silently falls back to English. The user picks their language, the app
 * restarts, and every word is still English. That is indistinguishable from the setting being
 * broken, and it is invisible to anyone testing in English.
 *
 * It has already happened here. `values-pt-BR/` and `values-fil/` were created with the tags
 * spelled the way BCP-47 writes them. Android's resource qualifiers are not BCP-47: anything
 * beyond a two-letter code needs the `b+` form, so those two directories compiled cleanly, shipped,
 * and never loaded. The strings were sitting right there in the APK, unreachable.
 *
 * A count check alone would not have caught it either — the files existed and were full. The
 * assertion has to be about the directory NAME Android will look for.
 *
 * ## What this cannot check
 *
 * That the translations are correct. Only a native speaker can do that, and until they have, the
 * picker says so.
 */
class LocaleResourceParityTest {

    private val resDir = File("src/main/res")

    /**
     * Android resource qualifier for a BCP-47 tag. Two-letter codes go in bare (`values-es`);
     * everything else — three-letter codes, region and script variants — needs the `b+` form
     * (`values-b+pt+BR`), which is exactly the distinction that shipped broken.
     */
    private fun qualifier(tag: String): String =
        if (tag.length == 2) "values-$tag" else "values-b+" + tag.replace('-', '+')

    private val translated = AppLocale.SUPPORTED.filter { it.tag != "en" }

    /** Guards against the whole suite passing because it was run from the wrong directory. */
    @Test fun `the resource directory is where this test thinks it is`() {
        assertTrue("res dir not found at ${resDir.absolutePath}", resDir.isDirectory)
        assertTrue(File(resDir, "values/strings_wallet.xml").isFile)
        assertTrue("nothing to check", translated.size >= 10)
    }

    @Test fun `every offered language has a resource directory Android will find`() {
        val missing = translated.filterNot { File(resDir, qualifier(it.tag)).isDirectory }
        assertTrue(
            "offered but not translated (or misnamed): " +
                missing.joinToString { "${it.tag} -> ${qualifier(it.tag)}" },
            missing.isEmpty(),
        )
    }

    @Test fun `every language translates every string`() {
        val english = keysIn(File(resDir, "values/strings_wallet.xml"))
        assertTrue("no English keys found", english.size >= 30)

        val gaps = translated.mapNotNull { entry ->
            val file = File(resDir, "${qualifier(entry.tag)}/strings_wallet.xml")
            if (!file.isFile) return@mapNotNull "${entry.tag}: no strings_wallet.xml"
            val missing = english - keysIn(file)
            if (missing.isEmpty()) null else "${entry.tag}: missing ${missing.sorted()}"
        }
        assertTrue(gaps.joinToString("\n"), gaps.isEmpty())
    }

    /**
     * Android 13+ builds its own per-app language screen from this file. A language in the app's
     * picker but absent here is unreachable from the OS screen, and the two disagree about what
     * the wallet speaks.
     */
    @Test fun `locales_config lists exactly the supported set`() {
        val xml = File(resDir, "xml/locales_config.xml")
        assertTrue("locales_config.xml missing", xml.isFile)

        val listed = Regex("""android:name="([^"]+)"""").findAll(xml.readText())
            .map { it.groupValues[1] }.toSet()

        assertEquals(
            "locales_config and AppLocale.SUPPORTED disagree",
            AppLocale.SUPPORTED.map { it.tag }.toSet(),
            listed,
        )
    }

    private fun keysIn(file: File): Set<String> =
        Regex("""<string\s+name="([^"]+)"""").findAll(file.readText())
            .map { it.groupValues[1] }.toSet()
}
