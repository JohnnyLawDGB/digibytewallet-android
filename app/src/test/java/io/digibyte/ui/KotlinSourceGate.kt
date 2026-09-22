package io.digibyte.ui

/**
 * What a source gate reads: a Kotlin file as the compiler acts on it, and the calls in it.
 *
 * A gate that searches a file's text finds its words in a comment or a string as readily as in
 * code, finds `quantity = units` inside `quantity = units * 10`, and finds a condition inside a
 * longer one. The gates built on this read code only, take a call apart into its arguments and
 * compare each argument whole, and ask of a condition whether it is the whole condition.
 *
 * It is a reader for gates, not a parser. It knows comments, literals, brackets and commas, which
 * is what it takes to say where a call and its arguments begin and end. Type arguments are not
 * brackets to it, so a call pinned through it should not pass `f<A, B>()` as an argument.
 */
internal class KotlinSourceGate private constructor(
    /** The file with its comments blanked. Same length and lines as the file. */
    private val written: String,
    /** The file with its comments and the text inside its literals blanked: what is left is code. */
    val code: String,
) {
    /** One call: where it stands in [code], and its arguments whole. */
    inner class Call internal constructor(val range: IntRange, private val argumentRanges: List<IntRange>) {

        /** The call as code, from its first name to its closing bracket. */
        val text: String get() = code.substring(range)

        /** Every argument as it is written, in order, with runs of whitespace read as one space. */
        val arguments: List<String> get() = argumentRanges.map { squeeze(written.substring(it)) }

        /** The arguments passed by name: name to value as written. */
        val named: Map<String, String>
            get() = argumentRanges.mapNotNull { whole ->
                val sign = nameSign(whole) ?: return@mapNotNull null
                squeeze(written.substring(whole.first, sign)) to squeeze(written.substring(sign + 1, whole.last + 1))
            }.toMap()

        /** Where the value passed as [name] stands in [code], or null when nothing is passed by that name. */
        fun valueRange(name: String): IntRange? = argumentRanges.firstNotNullOfOrNull { whole ->
            val sign = nameSign(whole) ?: return@firstNotNullOfOrNull null
            if (squeeze(code.substring(whole.first, sign)) == name) (sign + 1)..whole.last else null
        }

        /** The `=` of `name = value`: outside every bracket, after a plain name, and not part of a comparison. */
        private fun nameSign(whole: IntRange): Int? {
            var depth = 0
            for (at in whole) {
                when (code[at]) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    '=' -> if (depth == 0) {
                        val comparison = code.getOrNull(at + 1) == '=' || code.getOrNull(at - 1) in setOf('=', '!', '<', '>')
                        val name = code.substring(whole.first, at).trim()
                        return if (!comparison && name.matches(Regex("""\w+"""))) at else null
                    }
                }
            }
            return null
        }
    }

    /**
     * Every call of [callee] — `name` or `receiver.name` — that starts inside [within]. A
     * declaration (`fun name(`) is not a call. Whitespace around the dot and before the bracket is
     * allowed, and a longer receiver (`this.receiver.name(`) is still a call of it.
     */
    fun calls(callee: String, within: IntRange = code.indices): List<Call> {
        val name = callee.split('.').joinToString("""\s*\??\.\s*""") { Regex.escape(it) }
        return Regex("""(?<!\w)$name\s*\(""").findAll(code)
            .filter { it.range.first in within }
            .filterNot { code.substring(0, it.range.first).trimEnd().endsWith("fun") }
            .mapNotNull { found ->
                val open = found.range.last
                val close = matching(open) ?: return@mapNotNull null
                Call(found.range.first..close, split(open + 1, close - 1))
            }.toList()
    }

    /** From [from] up to the next [until]; null when either is not in the code. */
    fun range(from: String, until: String): IntRange? {
        val start = code.indexOf(from).takeIf { it >= 0 } ?: return null
        val end = code.indexOf(until, start + from.length).takeIf { it > start } ?: return null
        return start until end
    }

    /**
     * The code that runs only when [call] answered true, or null when its answer is not, by
     * itself, the condition that code stands under. Three spellings are read:
     *
     *  - `if (call) guarded` — the branch;
     *  - `if (!call) return`, or a block whose last statement is that `return` — what follows the
     *    `if`, to the end of the block it stands in;
     *  - `val answer = call`, then either of the two over `answer`, which is named nowhere else.
     */
    fun guardedBy(call: Call): IntRange? = guardedBy(call.range)

    private fun guardedBy(condition: IntRange): IntRange? {
        val head = code.substring(0, condition.first).trimEnd()
        val negated = head.endsWith("!")
        val opener = if (negated) head.dropLast(1).trimEnd() else head
        if (Regex("""(?<!\w)if\s*\($""").containsMatchIn(opener)) {
            val close = skipSpace(condition.last + 1)
            if (code.getOrNull(close) != ')') return null
            val branch = statementAt(skipSpace(close + 1)) ?: return null
            if (!negated) return branch
            // Read only when the branch always ends by leaving; what follows then runs on a true answer.
            if (!endsByLeaving(branch)) return null
            return (branch.last + 1)..endOfBlockFrom(branch.last + 1)
        }
        val held = Regex("""(?<!\w)val\s+(\w+)\s*=$""").find(head) ?: return null
        if (code.substring(condition.last + 1).substringBefore('\n').isNotBlank()) return null
        val uses = Regex("""(?<!\w)${held.groupValues[1]}(?!\w)""").findAll(code).toList()
        if (uses.size != 2) return null
        return guardedBy(uses[1].range)
    }

    /** The branch of every `if` starting inside [within] whose whole condition is [condition]. */
    fun branchesOn(condition: String, within: IntRange = code.indices): List<IntRange> {
        val spelt = condition.trim().split(Regex("""\s+""")).joinToString("""\s*""") { Regex.escape(it) }
        return Regex("""(?<!\w)if\s*\(\s*$spelt\s*\)""").findAll(code)
            .filter { it.range.first in within }
            .mapNotNull { statementAt(skipSpace(it.range.last + 1)) }
            .toList()
    }

    /** The `{ … }` that opens at [open], or null when [open] is not an opening brace with a partner. */
    fun blockAt(open: Int): IntRange? =
        if (code.getOrNull(open) == '{') matching(open)?.let { open..it } else null

    private fun skipSpace(from: Int): Int {
        var at = from
        while (at < code.length && code[at].isWhitespace()) at++
        return at
    }

    private fun matching(open: Int): Int? {
        var depth = 0
        for (at in open until code.length) {
            when (code[at]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (--depth == 0) return at
            }
        }
        return null
    }

    /** A block, or one statement: to the end of its line, a `;` or an `else`, outside every bracket. */
    private fun statementAt(start: Int): IntRange? {
        if (start >= code.length) return null
        if (code[start] == '{') return blockAt(start)
        var depth = 0
        var at = start
        while (at < code.length) {
            when (code[at]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (--depth < 0) break
                '\n', ';' -> if (depth == 0) break
                'e' -> if (depth == 0 && Regex("""(?<!\w)else(?!\w)""").matchesAt(code, at)) break
            }
            at++
        }
        return if (at > start) start until at else null
    }

    /** True when the last statement of [branch], outside every bracket of its own, is a plain `return`. */
    private fun endsByLeaving(branch: IntRange): Boolean {
        val braced = code[branch.first] == '{'
        val inside = if (braced) (branch.first + 1) until branch.last else branch
        var depth = 0
        var lastStart = inside.first
        for (at in inside) {
            when (code[at]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                '\n', ';' -> if (depth == 0 && code.substring(at + 1, inside.last + 1).isNotBlank()) lastStart = at + 1
            }
        }
        return code.substring(lastStart, inside.last + 1).trim().matches(Regex("""return(@\w+)?"""))
    }

    /** The last index before the brace that closes the block [from] stands in. */
    private fun endOfBlockFrom(from: Int): Int {
        var depth = 0
        for (at in from until code.length) {
            when (code[at]) {
                '{' -> depth++
                '}' -> if (--depth < 0) return at - 1
            }
        }
        return code.length - 1
    }

    /** [first]..[last] cut at the commas that stand outside every bracket; an empty piece is no argument. */
    private fun split(first: Int, last: Int): List<IntRange> {
        val pieces = mutableListOf<IntRange>()
        var depth = 0
        var start = first
        for (at in first..last) {
            when (code[at]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) { pieces += start until at; start = at + 1 }
            }
        }
        pieces += start..last
        return pieces.filter { !it.isEmpty() && code.substring(it).isNotBlank() }
    }

    companion object {
        fun of(text: String): KotlinSourceGate {
            val written = text.toCharArray()
            val code = text.toCharArray()
            Reader(text, written, code).read()
            return KotlinSourceGate(String(written), String(code))
        }

        /** Runs of whitespace read as one space: how a line is broken is not part of what it says. */
        fun squeeze(text: String): String = text.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * Blanks, in place and keeping every newline, what is not code. A comment is blanked in both
     * copies. The text inside a string or character literal is blanked in [code] only; its quotes
     * stay, and so does what a template runs: `$name` and the code inside `${ … }`.
     */
    private class Reader(private val text: String, private val written: CharArray, private val code: CharArray) {
        private var at = 0

        fun read() = code(inTemplate = false)

        private fun blankComment() {
            if (text[at] != '\n') { written[at] = ' '; code[at] = ' ' }
            at++
        }

        private fun blankLiteral() {
            if (text[at] != '\n') code[at] = ' '
            at++
        }

        /** Code, to the end of the file — or, inside a template, to the brace that closes it. */
        private fun code(inTemplate: Boolean) {
            var depth = 0
            while (at < text.length) {
                when {
                    text.startsWith("//", at) -> while (at < text.length && text[at] != '\n') blankComment()
                    text.startsWith("/*", at) -> comment()
                    text.startsWith("\"\"\"", at) -> literal(raw = true)
                    text[at] == '"' -> literal(raw = false)
                    text[at] == '\'' -> character()
                    inTemplate && text[at] == '{' -> { depth++; at++ }
                    inTemplate && text[at] == '}' -> { if (depth == 0) return; depth--; at++ }
                    else -> at++
                }
            }
        }

        /** A block comment. They nest. */
        private fun comment() {
            var depth = 0
            do {
                when {
                    text.startsWith("/*", at) -> { depth++; blankComment(); blankComment() }
                    text.startsWith("*/", at) -> { depth--; blankComment(); blankComment() }
                    else -> blankComment()
                }
            } while (depth > 0 && at < text.length)
        }

        private fun literal(raw: Boolean) {
            at += if (raw) 3 else 1
            while (at < text.length) {
                when {
                    raw && text.startsWith("\"\"\"", at) -> { while (at < text.length && text[at] == '"') at++; return }
                    !raw && text[at] == '"' -> { at++; return }
                    !raw && text[at] == '\n' -> return
                    !raw && text[at] == '\\' -> { blankLiteral(); if (at < text.length) blankLiteral() }
                    text.startsWith("\${", at) -> { at += 2; code(inTemplate = true); if (at < text.length) at++ }
                    text[at] == '$' && text.getOrNull(at + 1).let { it != null && (it.isLetter() || it == '_') } -> {
                        at++
                        while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
                    }
                    else -> blankLiteral()
                }
            }
        }

        /** `'x'`, `'\n'` or `'\u0041'`; anything else that starts with a quote mark is left as it is. */
        private fun character() {
            val escaped = text.getOrNull(at + 1) == '\\'
            val close = when {
                !escaped && text.getOrNull(at + 2) == '\'' -> at + 2
                escaped && text.getOrNull(at + 3) == '\'' -> at + 3
                escaped && text.getOrNull(at + 2) == 'u' && text.getOrNull(at + 7) == '\'' -> at + 7
                else -> -1
            }
            if (close < 0) { at++; return }
            at++
            while (at < close) blankLiteral()
            at = close + 1
        }
    }
}
