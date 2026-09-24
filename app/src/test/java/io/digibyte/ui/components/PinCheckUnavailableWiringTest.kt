package io.digibyte.ui.components

import io.digibyte.ui.KotlinSourceGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level WIRING gate for the one verify result that is neither a right nor a wrong PIN:
 * `PinVerifyResult.Unavailable`, the PIN could not be checked on this device and nothing was
 * counted. Its behaviour in the rate limit is covered by `PinRateLimitTest` in `core`; this pins
 * what each keypad does with it, because the keypads are composables with no test harness here.
 *
 * Every place that reads a verify result answers `Unavailable` with the message that says so, and
 * with nothing else: no attempts-left text, no countdown, no wipe, and the keypad stays open (a
 * spend dialog does not resolve its request), so the owner can simply enter the PIN again.
 *
 * The answer is pinned by what it IS, not by a list of what it must not be: the branch holds
 * exactly the statement that puts the message on the keypad and the one that clears the digits
 * (or, where the keypad clears its digits before it reads the result, the message alone). Any
 * other statement — one that opens the wallet, moves to another screen or dialog, resolves a
 * request, writes a setting or touches a counter — is one statement too many, whatever it calls.
 *
 * It reads code, not text: a branch in a comment or a literal is no branch.
 */
class PinCheckUnavailableWiringTest {

    private val uiRoot = File("src/main/java/io/digibyte/ui")

    /**
     * A keypad that reads verify results: how many it reads, the state that holds its message
     * ([message]) and its digits ([digits]), and whether it clears the digits BEFORE it reads the
     * result ([digitsClearedFirst]) rather than in each branch.
     */
    private class Keypad(val reads: Int, val message: String, val digits: String, val digitsClearedFirst: Boolean = false)

    /** Each file that reads a PIN verify result. */
    private val consumers = mapOf(
        // every spend / identity action's PIN keypad
        "components/SpendAuth.kt" to Keypad(reads = 1, message = "error", digits = "input", digitsClearedFirst = true),
        // the unlock screen
        "onboarding/UnlockScreen.kt" to Keypad(reads = 1, message = "errorMessage", digits = "currentInput"),
        // change PIN, view phrase, wipe
        "settings/SecuritySettingsScreen.kt" to Keypad(reads = 3, message = "pinError", digits = "pinInput"),
    )

    private val verifyRead = Regex("""when\s*\(\s*val\s+\w+\s*=\s*[\w.]*verifyPin\s*\(""")
    private val unavailableBranch = Regex("""(?<!\w)is\s+PinVerifyResult\s*\.\s*Unavailable\s*->""")

    /** `<message> = <resources>.getString(R.string.pin_check_unavailable)`, and nothing more. */
    private fun showsTheMessage(keypad: Keypad) = Regex(
        """${Regex.escape(keypad.message)}\s*=\s*\w+\s*\.\s*getString\s*\(\s*R\s*\.\s*string\s*\.\s*pin_check_unavailable\s*\)"""
    )

    /** `<digits> = ""`, and nothing more. */
    private fun clearsTheDigits(keypad: Keypad) = Regex(Regex.escape(keypad.digits) + "\\s*=\\s*\"\"")

    private fun gate(rel: String): KotlinSourceGate = KotlinSourceGate.of(File(uiRoot, rel).readText())

    /** The `{ … }` of every `when (val r = …verifyPin(…)) {` in [screen]. */
    private fun verifyReads(screen: KotlinSourceGate): List<IntRange> =
        verifyRead.findAll(screen.code).mapNotNull { found ->
            val open = screen.code.indexOf('{', found.range.last)
            screen.blockAt(open)
        }.toList()

    /** The branch after `->` at [arrowEnd]: inside its braced block, or the rest of the line. */
    private fun branchAt(screen: KotlinSourceGate, arrowEnd: Int): String {
        var at = arrowEnd + 1
        while (at < screen.code.length && screen.code[at].isWhitespace()) at++
        screen.blockAt(at)?.let { return screen.code.substring(it.first + 1, it.last) }
        val end = screen.code.indexOf('\n', at).takeIf { it >= 0 } ?: screen.code.length
        return screen.code.substring(at, end)
    }

    /** [body] cut into its statements — at a `;` or a line end outside every bracket — each squeezed. */
    private fun statements(body: String): List<String> {
        val pieces = mutableListOf<String>()
        var depth = 0
        var start = 0
        for ((at, c) in body.withIndex()) {
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ';', '\n' -> if (depth == 0) { pieces += body.substring(start, at); start = at + 1 }
            }
        }
        pieces += body.substring(start)
        return pieces.map { KotlinSourceGate.squeeze(it) }.filter { it.isNotEmpty() }
    }

    /** What the branch answering `Unavailable` must be, statement for statement; empty when it is. */
    private fun answerGaps(keypad: Keypad, body: String): List<String> {
        val left = statements(body).toMutableList()
        val gaps = mutableListOf<String>()
        val message = left.indexOfFirst { showsTheMessage(keypad).matches(it) }
        if (message < 0) gaps += "does not put pin_check_unavailable on `${keypad.message}`" else left.removeAt(message)
        if (!keypad.digitsClearedFirst) {
            val clear = left.indexOfFirst { clearsTheDigits(keypad).matches(it) }
            if (clear < 0) gaps += "does not clear `${keypad.digits}`" else left.removeAt(clear)
        }
        for (extra in left) gaps += "does more than show the message and clear the digits: `$extra`"
        return gaps
    }

    /** For a keypad that clears its digits before it reads: `<digits> = ""` stands before [read] in its block. */
    private fun digitsClearedBefore(screen: KotlinSourceGate, keypad: Keypad, readStart: Int): Boolean {
        val block = screen.enclosingBlock(readStart) ?: return false
        val before = screen.code.substring(block.first + 1, readStart)
        return statements(before).any { clearsTheDigits(keypad).matches(it) }
    }

    // GUARD (passes before and after): the scan is not blind, and a fourth reader is noticed.
    @Test
    fun `GUARD the scan sees every place a PIN verify result is read`() {
        val gaps = consumers.mapNotNull { (rel, keypad) ->
            val expected = keypad.reads
            val found = verifyReads(gate(rel)).size
            if (found != expected) "$rel: expected $expected verify results read, found $found" else null
        }
        assertTrue(gaps.joinToString("\n", prefix = "\n"), gaps.isEmpty())

        val others = uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.relativeTo(uiRoot).path !in consumers.keys }
            .filter { Regex("""PinVerifyResult\s*\.""").containsMatchIn(KotlinSourceGate.of(it.readText()).code) }
            .map { it.relativeTo(uiRoot).path }
            .toList()
        assertEquals("files that read a PIN verify result and are not on this gate's list", emptyList<String>(), others)
    }

    @Test
    fun `every PIN verify result read answers a PIN that could not be checked with its own message and nothing else`() {
        val gaps = mutableListOf<String>()
        for ((rel, keypad) in consumers) {
            val screen = gate(rel)
            for ((index, read) in verifyReads(screen).withIndex()) {
                val where = "$rel: verify result #${index + 1}"
                val branches = unavailableBranch.findAll(screen.code).filter { it.range.first in read }.toList()
                if (branches.size != 1) {
                    gaps += "$where has ${branches.size} branches for a PIN that could not be checked"
                    continue
                }
                val body = branchAt(screen, branches.single().range.last)
                answerGaps(keypad, body).forEach { gaps += "$where: the answer to a PIN that could not be checked $it" }
                if (keypad.digitsClearedFirst && !digitsClearedBefore(screen, keypad, read.first)) {
                    gaps += "$where: `${keypad.digits}` is not cleared before the result is read"
                }
            }
        }
        assertTrue(gaps.joinToString("\n", prefix = "\n"), gaps.isEmpty())
    }

    // GUARD (passes before and after): the statement reader the gate stands on answers as meant, so
    // a branch that is right is not flagged and one with a single statement too many is.
    @Test
    fun `GUARD the answer is read statement for statement`() {
        val settings = Keypad(reads = 1, message = "pinError", digits = "pinInput")
        val spend = Keypad(reads = 1, message = "error", digits = "input", digitsClearedFirst = true)
        val message = "pinError = appResources.getString(R.string.pin_check_unavailable)"
        assertEquals(emptyList<String>(), answerGaps(settings, "\n    $message; pinInput = \"\"\n"))
        assertEquals(emptyList<String>(), answerGaps(settings, "\n    pinInput = \"\"\n    $message\n"))
        assertEquals(emptyList<String>(), answerGaps(spend, " error = resources.getString(R.string.pin_check_unavailable)"))
        assertEquals(1, answerGaps(settings, "$message\n").size) // digits not cleared
        assertEquals(1, answerGaps(settings, "pinInput = \"\"\n").size) // no message
        assertEquals(1, answerGaps(settings, "$message; pinInput = \"\"; activeDialog = SecurityDialog.None").size)
        assertEquals(1, answerGaps(settings, "$message; pinInput = \"\"\nscope.launch { go() }").size)
        assertEquals(1, answerGaps(spend, "error = resources.getString(R.string.pin_check_unavailable); input = \"\"").size)
        assertEquals(2, answerGaps(settings, "pinError = appResources.getString(R.string.sec_incorrect_pin); pinInput = \"\"").size) // no message, and one other statement
    }

    @Test
    fun `the message says the PIN was not checked and nothing was counted`() {
        val strings = File("src/main/res/values/strings_wallet.xml").readText()
        val text = Regex("""<string\s+name="pin_check_unavailable"\s*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(strings)?.groupValues?.get(1)
        assertEquals(
            "The PIN could not be checked on this device right now. Nothing was counted. Please try again.",
            text,
        )
    }
}
