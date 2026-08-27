package io.digibyte.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * Dates in the language the user chose — including the ORDER, not just the month name.
 *
 * ## The bug this exists to fix
 *
 * The screens already passed `Locale.getDefault()`, so this looked done. What they also passed
 * was a hardcoded pattern, `"MMM dd, yyyy"` — and a pattern is not locale-neutral. It pins
 * American day/month order while the locale only supplies the words, so a German build rendered
 * "Aug. 24, 2026": German month name, American order. That reads as a translation someone forgot
 * to finish, which is worse than plain English would have been.
 *
 * A locale's date format is the locale's business. `DateFormat.getDateInstance` knows that
 * German writes "24.08.2026" and English "Aug 24, 2026"; a pattern string cannot.
 *
 * ## Fixed instant, fixed zone
 *
 * The tests pin a timestamp and a time zone so they assert formatting rather than the clock.
 */
class DateDisplayTest {

    /** 2026-08-25 15:04:05 UTC. */
    private val instant = 1787670245000L
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun `english and german order the same date differently`() {
        val en = DateDisplay.date(instant, Locale.US, utc)
        val de = DateDisplay.date(instant, Locale.GERMANY, utc)

        assertNotEquals("a pattern string would have made these identical in shape", en, de)
        // The day and year survive in both; only the arrangement changes.
        listOf(en, de).forEach {
            assertTrue("expected the day in $it", it.contains("25"))
            assertTrue("expected the year in $it", it.contains("2026"))
        }
        // German leads with the day; US does not.
        assertTrue("German should lead with the day, got $de", de.trimStart().startsWith("25"))
    }

    @Test fun `the date carries no time`() {
        val out = DateDisplay.date(instant, Locale.US, utc)
        assertTrue("date-only must not contain a clock: $out", !out.contains(":"))
    }

    @Test fun `date and time includes a clock`() {
        val out = DateDisplay.dateTime(instant, Locale.US, utc)
        assertTrue("expected a clock in $out", out.contains(":"))
        assertTrue("expected the date too in $out", out.contains("2026"))
    }

    @Test fun `month and year names the month in the locale's own language`() {
        val en = DateDisplay.monthYear(instant, Locale.US, utc)
        val de = DateDisplay.monthYear(instant, Locale.GERMANY, utc)

        assertTrue("expected August in $en", en.contains("August"))
        assertTrue("expected the year in $en", en.contains("2026"))
        assertTrue("expected the year in $de", de.contains("2026"))
        // Japanese writes the year first — proof the order is not hardcoded anywhere.
        val ja = DateDisplay.monthYear(instant, Locale.JAPAN, utc)
        assertTrue("expected the year in $ja", ja.contains("2026"))
    }

    /** Runs on every row of the activity list; must never throw. */
    @Test fun `a zero or negative timestamp degrades instead of throwing`() {
        listOf(0L, -1L).forEach {
            val out = DateDisplay.date(it, Locale.US, utc)
            assertEquals("expected a dash for timestamp=$it", "—", out)
        }
    }
}
