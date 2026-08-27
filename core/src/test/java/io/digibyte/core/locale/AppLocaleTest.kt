package io.digibyte.core.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which language the wallet shows, and how a stored choice survives being read back.
 *
 * The list is deliberately the same markets as the currency picker added alongside it — a wallet
 * that offers someone rupees and then talks to them only in English is half a gesture.
 *
 * ## Why the parsing is defensive
 *
 * The choice is persisted as a BCP-47 tag and read back on every cold start, before any UI
 * exists. A tag that has been corrupted, or written by a build that supported a language this one
 * does not, must fall back to the system language — never crash the launch and never strand
 * someone in a language they cannot read their way out of. A wallet whose settings screen is
 * unreachable because of its own language setting is a wallet its owner cannot use.
 */
class AppLocaleTest {

    @Test fun `the supported set covers the markets the currency picker does`() {
        val tags = AppLocale.SUPPORTED.map { it.tag }
        // Hindi, Chinese, Japanese, Portuguese (BR), Spanish, Indonesian, Vietnamese, Turkish,
        // Russian, Filipino — the currency list's countries.
        listOf("hi", "zh", "ja", "pt-BR", "es", "id", "vi", "tr", "ru", "fil").forEach {
            assertTrue("missing $it", tags.contains(it))
        }
    }

    @Test fun `english is present and first`() {
        assertEquals("en", AppLocale.SUPPORTED.first().tag)
    }

    @Test fun `every entry names itself in its own language`() {
        // A language list written only in English is unusable by exactly the people who need it:
        // someone who reads no English cannot find their language in it.
        AppLocale.SUPPORTED.forEach {
            assertTrue("blank endonym for ${it.tag}", it.endonym.isNotBlank())
        }
        assertEquals("हिन्दी", AppLocale.SUPPORTED.single { it.tag == "hi" }.endonym)
        assertEquals("日本語", AppLocale.SUPPORTED.single { it.tag == "ja" }.endonym)
    }

    // ---- reading a stored choice back -------------------------------------------------------

    @Test fun `a stored tag resolves to its entry`() {
        assertEquals("ja", AppLocale.resolve("ja")?.tag)
        assertEquals("pt-BR", AppLocale.resolve("pt-BR")?.tag)
    }

    /** Null means "follow the system", which is the default and not an error. */
    @Test fun `no stored choice means follow the system`() {
        assertNull(AppLocale.resolve(null))
        assertNull(AppLocale.resolve(""))
        assertNull(AppLocale.resolve(AppLocale.SYSTEM))
    }

    /**
     * The case that must never brick a launch: a tag this build does not support. Falls back to
     * the system language rather than throwing on a path that runs before any UI exists.
     */
    @Test fun `an unknown or corrupt tag falls back to the system`() {
        assertNull(AppLocale.resolve("kl"))
        assertNull(AppLocale.resolve("not-a-tag"))
        assertNull(AppLocale.resolve("!!!"))
    }

    @Test fun `tags are matched case-insensitively and trimmed`() {
        assertEquals("pt-BR", AppLocale.resolve("  pt-br  ")?.tag)
        assertEquals("ja", AppLocale.resolve("JA")?.tag)
    }

    /** Region-qualified storage must still find the base language it was chosen from. */
    @Test fun `a region variant we do not list falls back to its base language`() {
        assertEquals("es", AppLocale.resolve("es-MX")?.tag)
        assertEquals("zh", AppLocale.resolve("zh-Hans")?.tag)
    }


    /**
     * German. Added after the first ten because the DigiByte community's German-speaking share is
     * large out of proportion to Germany's size in any currency table — the original list was
     * derived from the currency picker's markets, and that derivation missed it.
     */
    @Test fun `german is supported and names itself`() {
        val de = AppLocale.SUPPORTED.firstOrNull { it.tag == "de" }
        assertNotNull("German is missing from the supported set", de)
        assertEquals("Deutsch", de!!.endonym)
        assertEquals("de", AppLocale.resolve("de-AT")?.tag)
        assertEquals("de", AppLocale.resolve("de-CH")?.tag)
    }

    @Test fun `tags are unique`() {
        val tags = AppLocale.SUPPORTED.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
    }
}
