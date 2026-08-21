package io.digibyte.core.update

/**
 * Flattens a GitHub release body into plain text for the in-app update dialog.
 *
 * The dialog renders notes with a plain Compose `Text`, which has no markdown support, so
 * whatever the release body contains is shown literally. Observed on a device 2026-08-21:
 * the v4.0.42 prompt opened with `**A send can no longer report success…**`, asterisks and
 * all.
 *
 * The same string has two consumers that want different things — GitHub renders markdown,
 * the dialog cannot — and the app is the side that has to adapt. Asking every future release
 * note to avoid formatting is a rule nobody will remember, and forgetting it is invisible
 * until it is already published.
 *
 * Deliberately a small, dependency-free transform rather than a markdown parser: this runs on
 * untrusted-ish remote text in a dialog, and the failure mode of an over-clever parser
 * (mangled or dropped words) is worse than a leftover character. Structure that carries
 * meaning is preserved — list items become real bullets rather than vanishing, and link text
 * survives while the URL is dropped, since the dialog already offers its own
 * "View Full Release Notes" action and a raw URL is untappable noise.
 */
object ReleaseNotesPlainText {

    private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+""")
    private val LIST_ITEM = Regex("""^\s{0,3}[-*+]\s+""")
    private val HRULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")

    fun render(body: String): String {
        val out = StringBuilder(body.length)
        val lines = body.lines()
        for ((i, raw) in lines.withIndex()) {
            if (HRULE.matches(raw)) {
                // A rule is a divider with no words; keeping "---" would read as a typo.
                // Skip the line entirely, and the blank lines around it collapse below.
                continue
            }
            var line = raw
            line = LINK.replace(line) { it.groupValues[1] }
            line = HEADING.replace(line, "")
            line = LIST_ITEM.replace(line, "• ")
            line = stripEmphasis(line)
            line = line.replace("`", "")
            out.append(line)
            if (i != lines.lastIndex) out.append('\n')
        }
        // Collapse the runs of blank lines that dropped rules/headings leave behind, so the
        // dialog's 500-character budget is spent on words rather than whitespace.
        return out.toString()
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Remove `**bold**`, `__bold__`, `*italic*` and `_italic_` markers while keeping the
     * words. Done by scanning rather than regex so an unmatched marker is left alone instead
     * of eating the rest of the line — a half-written note should degrade to one stray
     * asterisk, never to missing text.
     */
    private fun stripEmphasis(line: String): String {
        if (!line.contains('*') && !line.contains('_')) return line
        val sb = StringBuilder(line.length)
        val open = HashSet<String>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '*' || c == '_') {
                val runLen = if (i + 1 < line.length && line[i + 1] == c) 2 else 1
                val marker = line.substring(i, i + runLen)
                when {
                    // Already open: this is its closer, so consume it. Without this the
                    // closing "**" survived and the dialog showed "…success.**".
                    marker in open -> { open.remove(marker); i += runLen; continue }
                    // Not open, but the same marker closes later: this is its opener.
                    line.indexOf(marker, i + runLen) >= 0 -> { open.add(marker); i += runLen; continue }
                }
                // Unmatched: leave it alone. A half-written note degrades to one stray
                // asterisk, never to missing words.
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
