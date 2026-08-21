package io.digibyte.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The in-app update dialog renders release notes with a plain Compose [androidx.compose.material3.Text],
 * which has no markdown support — so whatever the GitHub release body contains is shown
 * literally, asterisks and all.
 *
 * Observed on a device 2026-08-21: the v4.0.42 prompt opened with
 * `**A send can no longer report success when the network refused it.**`, asterisks visible.
 * The release body is written for GitHub, which DOES render markdown, so the two consumers
 * want different things from the same string. The app is the one that must adapt: it cannot
 * ask every future release note to avoid formatting.
 */
class ReleaseNotesPlainTextTest {

    @Test fun bold_and_italic_markers_are_removed_but_the_words_are_kept() {
        assertEquals(
            "A send can no longer report success.",
            ReleaseNotesPlainText.render("**A send can no longer report success.**"),
        )
        assertEquals("an orphaned transaction", ReleaseNotesPlainText.render("an *orphaned* transaction"))
        assertEquals("mixed bold and italic", ReleaseNotesPlainText.render("**mixed** bold and *italic*"))
    }

    @Test fun heading_markers_are_removed() {
        assertEquals("What changed", ReleaseNotesPlainText.render("### What changed"))
        assertEquals("Title", ReleaseNotesPlainText.render("# Title"))
    }

    /** Bullets stay readable — the marker becomes a real bullet rather than vanishing, so a
     *  list does not collapse into an unreadable run-on paragraph. */
    @Test fun list_markers_become_bullets() {
        assertEquals("• first\n• second", ReleaseNotesPlainText.render("- first\n- second"))
    }

    @Test fun inline_code_backticks_are_removed() {
        assertEquals("SHA-256: abc123", ReleaseNotesPlainText.render("**SHA-256:** `abc123`"))
    }

    /** A link keeps its text and drops the URL — the dialog already has its own
     *  "View Full Release Notes" action, so a raw URL is noise the user cannot tap. */
    @Test fun links_keep_their_text_and_drop_the_target() {
        assertEquals(
            "Download from digiscope.me/downloads/ or install below.",
            ReleaseNotesPlainText.render("Download from [digiscope.me/downloads/](https://digiscope.me/downloads/) or install below."),
        )
    }

    /** A horizontal rule is a visual divider with no text; keeping "---" would look like a typo. */
    @Test fun horizontal_rules_are_dropped() {
        assertEquals("before\n\nafter", ReleaseNotesPlainText.render("before\n\n---\n\nafter"))
    }

    /** Plain prose must pass through untouched — the common case now that release bodies
     *  lead with plain text. */
    @Test fun plain_prose_is_unchanged() {
        val prose = "When you sent a transaction, the wallet reported success.\n\nThat mattered."
        assertEquals(prose, ReleaseNotesPlainText.render(prose))
    }

    /** Never emit a leftover marker, whatever the input — this is the property that actually
     *  matters, since the failure mode is a user seeing punctuation soup. */
    @Test fun no_stray_markers_survive_a_realistic_body() {
        val body = """
            **A send can no longer report success.**

            ### What changed

            - **Clear stuck sends** now clears an *orphaned* transaction
            - See [the notes](https://example.com/notes)

            ---
            **SHA-256:** `deadbeef`
        """.trimIndent()
        val out = ReleaseNotesPlainText.render(body)
        assertFalse("no bold markers", out.contains("**"))
        assertFalse("no backticks", out.contains("`"))
        assertFalse("no link brackets", out.contains("]("))
        assertFalse("no heading marker", out.contains("###"))
    }
}
