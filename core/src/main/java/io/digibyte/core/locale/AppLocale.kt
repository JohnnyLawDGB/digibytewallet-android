package io.digibyte.core.locale

import java.util.Locale

/**
 * The languages the wallet can display itself in, and how a stored choice is read back.
 *
 * The set matches the markets the currency picker covers. A wallet that will quote someone their
 * balance in rupees and then speak to them only in English is half a gesture — and DigiByte's
 * users are not concentrated in English-speaking countries.
 *
 * ## Endonyms, not English names
 *
 * Each language names itself in its own script. A language list written only in English is
 * unusable by exactly the people who need it: someone who reads no English cannot find their
 * language in a list of English words.
 *
 * ## Failure means "follow the system"
 *
 * The stored tag is read on every cold start, before any UI exists. A tag that is corrupt, or
 * written by a build supporting a language this one does not, resolves to null — follow the
 * system — rather than throwing. A wallet whose settings screen is unreachable because of its own
 * language setting is a wallet its owner cannot fix.
 */
object AppLocale {

    /** Stored value meaning "follow the device language". */
    const val SYSTEM = "system"

    data class Entry(
        /** BCP-47 tag, and the `values-xx` resource qualifier it maps to. */
        val tag: String,
        /** The language's name in its own script. */
        val endonym: String,
        /** English name, for logs and for the settings row's secondary line. */
        val englishName: String,
    ) {
        fun toLocale(): Locale = Locale.forLanguageTag(tag)
    }

    /** English first as the source language; the rest in the currency picker's order. */
    val SUPPORTED: List<Entry> = listOf(
        Entry("en", "English", "English"),
        Entry("hi", "हिन्दी", "Hindi"),
        Entry("zh", "中文", "Chinese"),
        Entry("ja", "日本語", "Japanese"),
        Entry("pt-BR", "Português (Brasil)", "Portuguese (Brazil)"),
        Entry("es", "Español", "Spanish"),
        Entry("id", "Bahasa Indonesia", "Indonesian"),
        Entry("vi", "Tiếng Việt", "Vietnamese"),
        Entry("tr", "Türkçe", "Turkish"),
        Entry("ru", "Русский", "Russian"),
        Entry("fil", "Filipino", "Filipino"),
    )

    /**
     * Resolve a stored tag.
     *
     * @return the matching entry, or null meaning "follow the system" — which covers no choice,
     *         an explicit [SYSTEM], and anything unrecognised.
     */
    fun resolve(stored: String?): Entry? {
        val tag = stored?.trim().orEmpty()
        if (tag.isEmpty() || tag.equals(SYSTEM, ignoreCase = true)) return null

        SUPPORTED.firstOrNull { it.tag.equals(tag, ignoreCase = true) }?.let { return it }

        // A region or script variant we do not list ("es-MX", "zh-Hans") should still land on the
        // base language it was chosen from, rather than silently reverting to the system.
        val base = tag.substringBefore('-').lowercase(Locale.US)
        if (base.isEmpty()) return null
        return SUPPORTED.firstOrNull { it.tag.substringBefore('-').lowercase(Locale.US) == base }
    }
}
