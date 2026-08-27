package io.digibyte.core

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Dates rendered in the user's locale — the order as well as the words.
 *
 * ## Why a pattern string is not enough
 *
 * The screens this replaces already passed [Locale.getDefault], which made them look localised.
 * They also passed a hardcoded pattern — `"MMM dd, yyyy"` — and that pins American day/month
 * order while the locale supplies only the month name. A German build rendered "Aug. 24, 2026":
 * German word, American order. That reads as a half-finished translation, which costs more trust
 * than plain English would have.
 *
 * [DateFormat.getDateInstance] already knows that German writes 24.08.2026, that English writes
 * Aug 24, 2026, and that Japanese leads with the year. A pattern string cannot know any of it.
 *
 * ## MEDIUM, not SHORT
 *
 * SHORT gives "8/24/26", where a two-digit year and an ambiguous day/month order are a poor
 * choice for a transaction list read across locales. MEDIUM spells the month, which removes the
 * ambiguity without spending much width.
 */
object DateDisplay {

    /** Shown when there is no usable timestamp; never an epoch date, which would read as 1970. */
    private const val UNKNOWN = "—"

    /** Date only — for the activity list, where the clock is noise. */
    fun date(
        millis: Long,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String = format(millis) {
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = zone }
    }

    /** Date and clock — for transaction detail, where the exact moment matters. */
    fun dateTime(
        millis: Long,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String = format(millis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, locale)
            .apply { timeZone = zone }
    }

    /**
     * Month and year, for the "showing transactions from …" banner.
     *
     * There is no [DateFormat] constant for this, so it is built from the locale's own
     * best-fit pattern via [android.icu]-style skeleton behaviour where available, falling back
     * to the locale's long date with the day dropped. Kept simple deliberately: the banner needs
     * "August 2026", not a precise day.
     */
    fun monthYear(
        millis: Long,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        if (millis <= 0L) return UNKNOWN
        val month = java.text.SimpleDateFormat("LLLL", locale).apply { timeZone = zone }
        val year = java.text.SimpleDateFormat("yyyy", locale).apply { timeZone = zone }
        val d = Date(millis)
        // Year-first locales (ja, zh, ko) read wrong as "August 2026"; they say "2026年8月".
        // Detecting that properly needs ICU skeletons, so the ordering follows the locale's own
        // date pattern: if the year comes before the month there, it comes first here too.
        val probe = (DateFormat.getDateInstance(DateFormat.LONG, locale) as? java.text.SimpleDateFormat)
            ?.toPattern().orEmpty()
        val yearFirst = probe.indexOf('y') in 0 until (probe.indexOfFirst { it == 'M' || it == 'L' }
            .takeIf { it >= 0 } ?: Int.MAX_VALUE)
        return if (yearFirst) "${year.format(d)} ${month.format(d)}"
        else "${month.format(d)} ${year.format(d)}"
    }

    private inline fun format(millis: Long, fmt: () -> DateFormat): String {
        if (millis <= 0L) return UNKNOWN
        return runCatching { fmt().format(Date(millis)) }.getOrDefault(UNKNOWN)
    }
}
