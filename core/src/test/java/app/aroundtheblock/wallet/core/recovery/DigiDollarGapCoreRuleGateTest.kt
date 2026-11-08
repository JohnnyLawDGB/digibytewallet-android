package app.aroundtheblock.wallet.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the DigiDollar recovery scan's gap window (where each chain starts, how far it grows,
 * and whether the scan's answer is finished or capped) is the core's rule, the DigiDollar half of
 * BRRecoveryGapWindow.h, which Android calls over JNI (jni_recovery_gap.c, through
 * [DigiDollarGapRule]) and iOS calls directly. Until 2026-11 it was Kotlin: DigiDollarGapWindow.kt
 * (next, capped, MAX), RecoveryScanService's ddGapExternal/ddGapInternal and the stop decision in
 * its scanDigiDollar. A second copy on one platform is a restore that finds a different set of
 * dollars, or calls a capped answer finished, on the other.
 *
 * Since 2026-11 the rule is the plain-DGB one (the whole-gap step and the round bound) with
 * DigiDollar's gaps and cap, so the scan also takes the core's round bound and passes it, with the
 * round, to every outcome.
 *
 * The core functions are BRRecoveryDigiDollarWholeGapNext/_WholeGapOutcome/_GapRoundBound; the
 * earlier BRRecoveryDigiDollarGapNext/_GapOutcome must not be called (removed from the core after
 * Android moved off them).
 *
 * The rule's values are checked by the host KAT digidollar_whole_gap_kat, whose red arms
 * DIGIDOLLAR_WHOLE_FIXED_RED, DIGIDOLLAR_WHOLE_STEP_RED and DIGIDOLLAR_WHOLE_CAP_RED fail them.
 */
class DigiDollarGapCoreRuleGateTest {

    private val repo = File("..").canonicalFile
    private val recovery = File("src/main/java/app/aroundtheblock/wallet/core/recovery")
    private val header = File(repo, "native/src/main/jni/digibytewallet-core/BRRecoveryGapWindow.h")
    private val jni = File(repo, "native/src/main/jni/bridge/jni_recovery_gap.c")

    /** The file with comments and string literals blanked: what is left is code. */
    private fun code(f: File): String = f.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")
        .replace(Regex(""""(?:\\.|[^"\\\n])*""""), "\"\"")

    /** The body of `fun name(` in [src], brace-matched from the first `{` after the declaration. */
    private fun body(src: String, name: String): String {
        val at = Regex("""fun\s+$name\s*\(""").find(src)?.range?.last ?: error("no fun $name: update the gate")
        val open = src.indexOf('{', at)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return src.substring(open, i + 1)
            }
        }
        error("unbalanced body of $name")
    }

    private fun count(src: String, call: String) = Regex("""\b${Regex.escape(call)}\s*\(""").findAll(src).count()

    @Test fun `no Kotlin copy of the DigiDollar gap window is left`() {
        assertTrue("DigiDollarGapWindow.kt is back", !File(recovery, "DigiDollarGapWindow.kt").exists())
        val kotlin = listOf(File("src/main/java"), File(repo, "app/src/main/java"))
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue("no Kotlin sources found: the gate reads the wrong directory", kotlin.size > 50)
        for (f in kotlin) {
            val src = code(f)
            assertTrue("${f.name} names DigiDollarGapWindow", !Regex("""\bDigiDollarGapWindow\b""").containsMatchIn(src))
            assertTrue("${f.name} declares the DigiDollar gaps itself", !Regex("""\bddGap\w*\b""").containsMatchIn(src))
        }
    }

    @Test fun `scanDigiDollar decides with the rule and nothing else`() {
        val scan = body(code(File(recovery, "RecoveryScanService.kt")), "scanDigiDollar")
        assertEquals("one start per chain, from the rule", 2, count(scan, "DigiDollarGapRule.start"))
        assertEquals("one next per chain, from the rule", 2, count(scan, "DigiDollarGapRule.next"))
        assertEquals("one outcome per round, from the rule", 1, count(scan, "DigiDollarGapRule.outcome"))
        assertEquals("one round bound per scan, from the rule", 1, count(scan, "DigiDollarGapRule.roundBound"))
        // The bound is taken once before the loop, the round counted once per pass from 1, and both
        // reach the outcome: without them a lookup that keeps the far end in use is never stopped.
        assertTrue("the round bound is not taken once, before a loop that counts its rounds from 1",
            Regex("""val\s+bound\s*=\s*DigiDollarGapRule\.roundBound\(\)\s+var\s+round\s*=\s*0\s+while\s*\(\s*true\s*\)\s*\{\s*round\+\+""")
                .containsMatchIn(scan))
        assertEquals("the round changes only once per pass", 1, Regex("""\bround\s*(\+\+|--|\+=|-=|=(?!=))""").findAll(scan).count() - 1)
        assertTrue("the outcome is not given the round and the bound",
            Regex("""DigiDollarGapRule\.outcome\([^)]*,\s*round\s*,\s*bound\s*\)""").containsMatchIn(scan))
        // The old copy's steps: its arithmetic, its gaps and cap, its own "neither grew" test.
        assertTrue("scanDigiDollar computes a window itself",
            !Regex("""\b(maxOf|minOf|coerceAtMost|coerceAtLeast|coerceIn)\s*\(""").containsMatchIn(scan))
        assertTrue("scanDigiDollar holds a gap or cap literal",
            !Regex("""(?<![\w.])(10|20|1000)(?![\w.])""").containsMatchIn(scan))
        assertTrue("scanDigiDollar compares windows itself",
            !Regex("""\bnext(External|Internal)\s*[!=]=|[!=]=\s*next(External|Internal)\b""").containsMatchIn(scan))
        assertTrue("a finished outcome does not return the answer as it is",
            Regex("""RecoveryGapRule\.FINISHED\s*->\s*return\s+result\b(?!\.)""").containsMatchIn(scan))
        // Every outcome but the two it acts on keeps the answer, marked not finished.
        assertTrue("an unknown outcome is not read as capped",
            Regex("""else\s*->\s*return\s+result\.copy\(\s*reachable\s*=\s*false\s*\)""").containsMatchIn(scan))
    }

    @Test fun `the DigiDollar rule is the core's`() {
        val rule = code(File(recovery, "DigiDollarGapRule.kt"))
        for ((fn, native) in listOf(
            "start" to "NativeBridge.recoveryDigiDollarGapStart",
            "next" to "NativeBridge.recoveryDigiDollarWholeGapNext",
            "outcome" to "NativeBridge.recoveryDigiDollarWholeGapOutcome",
            "roundBound" to "NativeBridge.recoveryDigiDollarGapRoundBound",
        )) {
            val decl = Regex("""fun\s+$fn\s*\(""").find(rule) ?: error("DigiDollarGapRule has no $fn")
            val nextDecl = Regex("""\bfun\s""").find(rule, decl.range.last)?.range?.first ?: rule.length
            val fnSrc = rule.substring(decl.range.first, nextDecl)
            assertEquals("DigiDollarGapRule.$fn must be one call to $native", 1, count(fnSrc, native))
            assertEquals("DigiDollarGapRule.$fn calls a native other than $native",
                1, Regex("""\bNativeBridge\.\w+\s*\(""").findAll(fnSrc).count())
        }
        val jniSrc = code(jni)
        for (c in listOf("BRRecoveryDigiDollarGapStart", "BRRecoveryDigiDollarWholeGapNext",
                         "BRRecoveryDigiDollarWholeGapOutcome", "BRRecoveryDigiDollarGapRoundBound")) {
            assertEquals("jni_recovery_gap.c must call $c once", 1, count(jniSrc, c))
            assertTrue("$header does not define $c: is the core pin current?",
                Regex("""static\s+inline\s+int(32_t)?\s+$c\s*\(""").containsMatchIn(header.readText()))
        }
        // The earlier DigiDollar rule (deprecated in the core, to be removed) is not called.
        for (c in listOf("BRRecoveryDigiDollarGapNext", "BRRecoveryDigiDollarGapOutcome")) {
            assertEquals("jni_recovery_gap.c calls the deprecated $c", 0, count(jniSrc, c))
        }
        assertTrue("jni_recovery_gap.c is not built",
            File(repo, "native/CMakeLists.txt").readText().contains("src/main/jni/bridge/jni_recovery_gap.c"))
    }
}
