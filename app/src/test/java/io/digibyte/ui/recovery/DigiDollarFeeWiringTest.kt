package io.digibyte.ui.recovery

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level WIRING gate, not a behaviour test.
 *
 * Which coins may pay a DigiDollar transfer's fee is decided by a pure function in `:core`, and
 * its rules are tested there. This holds the one call site to handing that function the round's
 * real facts: the verdicts the sweep is given, the outpoints the DigiAsset moves claimed, and the
 * transfer's own sizing as the judge of when the fee is covered. Each of those is an ordinary
 * argument, so a call that passes something else in its place still compiles — hence a gate on
 * the text.
 */
class DigiDollarFeeWiringTest {

    private val file = File("src/main/java/io/digibyte/ui/recovery/RecoverFundsViewModel.kt")

    private val src: String by lazy {
        assertTrue("RecoverFundsViewModel.kt is missing — this gate is watching a file that moved",
            file.exists())
        file.readLines()
            .filterNot { l -> l.trimStart().startsWith("//") || l.trimStart().startsWith("*") }
            .filterNot { l -> l.trimStart().startsWith("/*") }
            .joinToString("\n")
    }

    private val runRecoveryDecl = "private suspend fun runRecovery("
    private val moveDeclaration = "private suspend fun moveDigiDollar("

    /** The body of `runRecovery`: from its declaration to the declaration that follows it. */
    private val runRecovery: String by lazy {
        val start = src.indexOf(runRecoveryDecl)
        val end = src.indexOf(moveDeclaration)
        assertTrue("runRecovery and moveDigiDollar are no longer where this gate looks",
            start in 0 until end)
        src.substring(start, end)
    }

    /** The body of `moveDigiDollar`: from its declaration to the next function's. */
    private val moveDigiDollar: String by lazy {
        val start = src.indexOf(moveDeclaration)
        assertTrue("no moveDigiDollar found at all", start >= 0)
        val end = src.indexOf("\n    private ", start + moveDeclaration.length)
            .let { if (it < 0) src.length else it }
        src.substring(start, end)
    }

    /** The argument text of the first call in [text] that starts with [opener] (ending in `(`). */
    private fun argumentsOf(text: String, opener: String): List<String> {
        val at = text.indexOf(opener)
        assertTrue("no call to $opener found", at >= 0)
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var i = at + opener.length
        while (i < text.length) {
            val c = text[i]
            when {
                c == '(' || c == '{' || c == '[' -> { depth++; current.append(c) }
                (c == ')' || c == '}' || c == ']') && depth == 0 -> break
                c == ')' || c == '}' || c == ']' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { args += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        assertTrue("the call to $opener is never closed", i < text.length)
        args += current.toString()
        return args.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
    }

    /** `name = value` arguments as a map; positional ones are keyed by their position. */
    private fun named(args: List<String>): Map<String, String> =
        args.withIndex().associate { (i, a) ->
            val m = Regex("^(\\w+) = (.*)$", RegexOption.DOT_MATCHES_ALL).find(a)
            if (m != null) m.groupValues[1] to m.groupValues[2] else "#$i" to a
        }

    /** The scanner is not blind: one selection call, inside the function this gate reads. */
    @Test fun `there is one fee selection call and it is in moveDigiDollar`() {
        val call = "DigiDollarFeeSelection.from("
        assertEquals("fee selection calls in the file", 1, src.split(call).size - 1)
        assertTrue(moveDigiDollar.contains(call))
        assertEquals("moveDigiDollar calls in runRecovery", 1,
            runRecovery.split("moveDigiDollar(").size - 1)
    }

    @Test fun `the round's exclusions are built before the DigiDollar move and handed to it`() {
        val declared = runRecovery.indexOf("val exclusions = ")
        val called = runRecovery.indexOf("moveDigiDollar(")
        assertTrue("exclusions must exist before the DigiDollar move is made",
            declared in 0 until called)
        assertTrue("exclusions are the set the sweep is given",
            runRecovery.substring(declared).substringBefore("(")
                .endsWith("RecoverySequence.sweepExclusions"))

        val args = argumentsOf(runRecovery, "moveDigiDollar(")
        val passed = named(args).values
        assertTrue("the DigiDollar move is not handed this round's verdicts: $args",
            "verdicts" in passed)
        assertTrue("the DigiDollar move is not handed this round's exclusions: $args",
            "exclusions" in passed)
    }

    @Test fun `the fee selection receives the round's verdicts and claimed outpoints`() {
        val args = named(argumentsOf(moveDigiDollar, "DigiDollarFeeSelection.from("))
        assertEquals("findings", args["findings"])
        assertEquals("verdicts", args["verdicts"])
        assertEquals("claimedOutpoints", args["excludeOutpoints"])
    }

    @Test fun `the fee selection stops where the transfer's own sizing says the fee is covered`() {
        val args = named(argumentsOf(moveDigiDollar, "DigiDollarFeeSelection.from("))
        val covers = args["covers"].orEmpty()
        assertTrue("covers is not decided by the transfer's own sizing: '$covers'",
            covers.contains("DigiDollarTransferService.feeInputsSuffice("))
        val asked = named(argumentsOf(covers, "feeInputsSuffice("))
        assertEquals("the same scan the move is given", "scan", asked["scan"])
        assertEquals("the coins picked so far", "picked", asked["feeInputs"])
    }

    @Test fun `the transfer is paid with the selection`() {
        val args = named(argumentsOf(moveDigiDollar, ").move("))
        assertEquals("scan", args["scan"])
        assertEquals("fee.inputs", args["feeInputs"])
        assertEquals("fee.profile", args["feeProfile"])
    }
}
