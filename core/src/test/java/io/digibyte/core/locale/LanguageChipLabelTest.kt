package io.digibyte.core.locale

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * What the onboarding language control calls itself.
 *
 * ## Why this is not just "Language"
 *
 * The control exists for someone who cannot read the screen it sits on. Labelling it with the
 * English word "Language" hands that person an English word to identify the escape hatch from
 * English — which is the same mistake as an English-only language list, one step earlier.
 *
 * So the chip always names the language currently in effect, in that language's own script. A
 * German speaker whose device is already German sees "Deutsch" and knows what the control does
 * without reading anything else. Someone stuck in the wrong language sees a word they do not
 * recognise next to a globe, which is itself the signal that this is where they fix it.
 *
 * The device case matters most: before anyone has chosen, [AppLocale.resolve] returns null, and
 * the label has to come from the device locale instead — including when the device language is
 * one the wallet does not translate.
 */
class LanguageChipLabelTest {

    @Test fun `a chosen language names itself`() {
        val de = AppLocale.SUPPORTED.first { it.tag == "de" }
        assertEquals("Deutsch", LanguageChipLabel.forChoice(de, Locale.US))
    }

    @Test fun `no choice falls back to the device language in its own script`() {
        assertEquals("Deutsch", LanguageChipLabel.forChoice(null, Locale.GERMAN))
        assertEquals("日本語", LanguageChipLabel.forChoice(null, Locale.JAPANESE))
    }

    /**
     * A device language the wallet does not ship still gets named in its own words. The UI will be
     * English, and the chip is how that person finds their way out — so it must not also be a word
     * only an English reader can act on.
     */
    @Test fun `an untranslated device language is still named in its own script`() {
        assertEquals("polski", LanguageChipLabel.forChoice(null, Locale.forLanguageTag("pl")))
    }

    /** Nothing here may throw: it renders on the first frame of the first screen. */
    @Test fun `an unnamed locale degrades to a neutral marker rather than throwing`() {
        assertEquals("—", LanguageChipLabel.forChoice(null, Locale.ROOT))
    }
}
