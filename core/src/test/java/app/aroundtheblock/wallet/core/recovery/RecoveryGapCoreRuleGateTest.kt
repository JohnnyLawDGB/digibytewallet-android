package app.aroundtheblock.wallet.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the recovery scan's gap window (how far each path derives, and whether its answer is
 * finished or capped) is the core's rule, BRRecoveryGapWindow.h, which Android calls over JNI
 * (jni_recovery_gap.c, through [RecoveryGapRule.Core]) and iOS calls directly. Until 2026-11 it was
 * Kotlin: RecoveryGapWindow.kt (next, capped, roundBound and the gaps and caps) and the per-path
 * decision in [RecoveryScanService.classifyWidening]. A second copy on one platform is a recovery
 * that finds a different set of coins, or calls a capped answer finished, on the other.
 *
 * The rule's values, including every case of the RecoveryGapWidenTest that held the Kotlin copy,
 * are checked by the host KAT recovery_gap_window_kat, whose red arms RECOVERY_GAP_RED and
 * RECOVERY_CAP_RED fail them.
 */
class RecoveryGapCoreRuleGateTest {

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

    @Test fun `no Kotlin copy of the gap window is left`() {
        assertTrue("RecoveryGapWindow.kt is back", !File(recovery, "RecoveryGapWindow.kt").exists())
        val kotlin = listOf(File("src/main/java"), File(repo, "app/src/main/java"))
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue("no Kotlin sources found: the gate reads the wrong directory", kotlin.size > 50)
        for (f in kotlin) {
            val src = code(f)
            assertTrue("${f.name} names RecoveryGapWindow", !Regex("""\bRecoveryGapWindow\b""").containsMatchIn(src))
            assertTrue("${f.name} declares the recovery gaps or caps itself",
                !Regex("""\b(GAP_EXTERNAL|GAP_INTERNAL|MAX_EXTERNAL|MAX_INTERNAL)\s*=""").containsMatchIn(src))
        }
    }

    @Test fun `classifyWidening decides with the rule and nothing else`() {
        val widen = body(code(File(recovery, "RecoveryScanService.kt")), "classifyWidening")
        assertEquals("one round bound, from the rule", 1, count(widen, "gapRule.roundBound"))
        assertEquals("one next per chain, from the rule", 2, count(widen, "gapRule.next"))
        assertEquals("one outcome per path and round, from the rule", 1, count(widen, "gapRule.outcome"))
        // The old copy's steps: its arithmetic, its gaps and caps, its round comparison.
        assertTrue("classifyWidening computes a window itself",
            !Regex("""\b(maxOf|minOf|coerceAtMost|coerceAtLeast|coerceIn)\s*\(""").containsMatchIn(widen))
        assertTrue("classifyWidening compares rounds itself", !Regex("""\bround\s*[<>]=?|[<>]=?\s*bound\b""").containsMatchIn(widen))
        assertTrue("classifyWidening holds a gap or cap literal",
            !Regex("""(?<![\w.])(50|100|1000|2000)(?![\w.])""").containsMatchIn(widen))
        // Every outcome but the two it acts on keeps the answer, marked capped.
        assertTrue("an unknown outcome is not read as capped",
            Regex("""else\s*->\s*answer\[idx]\s*=\s*r\.copy\(\s*capped\s*=\s*true\s*\)""").containsMatchIn(widen))
    }

    @Test fun `the scan's rule is the core's by default`() {
        val service = code(File(recovery, "RecoveryScanService.kt"))
        assertTrue("RecoveryScanService does not default to the core's rule",
            Regex("""gapRule\s*:\s*RecoveryGapRule\s*=\s*RecoveryGapRule\.Core\b""").containsMatchIn(service))
        val rule = code(File(recovery, "RecoveryGapRule.kt"))
        val core = rule.substring(rule.indexOf("object Core"))
        for ((fn, native) in listOf(
            "next" to "NativeBridge.recoveryGapNext",
            "outcome" to "NativeBridge.recoveryGapOutcome",
            "roundBound" to "NativeBridge.recoveryGapRoundBound",
        )) {
            val decl = Regex("""override\s+fun\s+$fn\s*\(""").find(core) ?: error("Core has no $fn")
            val nextDecl = Regex("""override\s+fun\s""").find(core, decl.range.last)?.range?.first ?: core.length
            assertEquals("Core.$fn must be one call to $native", 1, count(core.substring(decl.range.first, nextDecl), native))
        }
        val jniSrc = code(jni)
        for (c in listOf("BRRecoveryGapNext", "BRRecoveryGapOutcome", "BRRecoveryGapScanRoundBound")) {
            assertEquals("jni_recovery_gap.c must call $c once", 1, count(jniSrc, c))
        }
        assertTrue("jni_recovery_gap.c is not built",
            File(repo, "native/CMakeLists.txt").readText().contains("src/main/jni/bridge/jni_recovery_gap.c"))
    }

    @Test fun `the outcome values are the core's`() {
        assertTrue("cannot read $header: is the core submodule checked out?", header.isFile)
        val defines = Regex("""#define\s+(BR_RECOVERY_GAP_\w+)\s+(-?\d+)""").findAll(header.readText())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(defines["BR_RECOVERY_GAP_ASK_AGAIN"], RecoveryGapRule.ASK_AGAIN)
        assertEquals(defines["BR_RECOVERY_GAP_FINISHED"], RecoveryGapRule.FINISHED)
        val capped = defines["BR_RECOVERY_GAP_CAPPED"] ?: error("no BR_RECOVERY_GAP_CAPPED")
        assertNotEquals(RecoveryGapRule.ASK_AGAIN, capped)
        assertNotEquals(RecoveryGapRule.FINISHED, capped)
    }
}
