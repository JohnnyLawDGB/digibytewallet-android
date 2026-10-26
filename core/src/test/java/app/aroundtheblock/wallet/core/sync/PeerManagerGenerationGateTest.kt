package app.aroundtheblock.wallet.core.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source gate over the bridge: every peer manager it builds is a new generation.
 *
 * The core keeps its relay evidence per peer manager, and a rebuilt manager starts with none. The
 * stranded-send sweep's refusal hold ([RefusalHold], `RefusalHoldTest`) never completes a hold on one
 * manager that it started on another, and it tells managers apart only by the generation the bridge
 * answers (NativeBridge.peerManagerGeneration). Managers are rebuilt from many places in the app (the
 * sync service's recoveries, the worker, the wallet screens, a wallet change), but every build goes
 * through the bridge, so the gate is here: each call of a peer manager constructor in any bridge
 * source is followed, in the same function, by the generation's increment; nothing else writes the
 * generation; and the getter answers it under the peer guard, 0 while no manager exists.
 */
class PeerManagerGenerationGateTest {

    private val bridge: Map<String, String> by lazy {
        val dir = listOf("native/src/main/jni/bridge", "../native/src/main/jni/bridge", "../../native/src/main/jni/bridge")
            .map { File(it) }.firstOrNull { it.isDirectory } ?: throw IllegalStateException("bridge sources not found")
        dir.listFiles { f -> f.name.endsWith(".c") }!!.associate { it.name to code(it.readText()) }
    }

    /** Code only: comments and the insides of string and character literals blanked, offsets kept. */
    private fun code(text: String): String {
        val out = StringBuilder(text)
        fun blank(from: Int, until: Int) { for (k in from until until) if (out[k] != '\n') out.setCharAt(k, ' ') }
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    blank(i, end); i = end
                }
                text.startsWith("//", i) -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    blank(i, end); i = end
                }
                ch == '"' || ch == '\'' -> {
                    var k = i + 1
                    while (k < text.length && text[k] != ch && text[k] != '\n') { if (text[k] == '\\') k++; k++ }
                    blank(i + 1, minOf(k, text.length)); i = k + 1
                }
                else -> i++
            }
        }
        return out.toString()
    }

    /** The top-level brace block (a function body) that contains [at]. */
    private fun functionAround(c: String, at: Int): IntRange {
        var depth = 0
        var open = -1
        for (i in c.indices) {
            when (c[i]) {
                '{' -> { if (depth == 0) open = i; depth++ }
                '}' -> { depth--; if (depth == 0 && open <= at && at <= i) return open..i }
            }
        }
        throw AssertionError("no function body around offset $at")
    }

    private val builds = Regex("""\b(?:BR|BP)PeerManager\w*New\w*\s*\(""")
    private val bump = Regex("""\bs_peerManagerGeneration\s*\+\+\s*;""")
    private val mention = Regex("""\bs_peerManagerGeneration\b""")

    @Test fun `every peer manager the bridge builds bumps the generation after it`() {
        val found = bridge.flatMap { (name, c) -> builds.findAll(c).map { name to it.range.first }.toList() }
        assertTrue("the gate found no peer manager build in the bridge: it is blind, not clean", found.isNotEmpty())
        for ((name, at) in found) {
            val c = bridge.getValue(name)
            val fn = functionAround(c, at)
            assertTrue(
                "$name builds a peer manager and does not bump s_peerManagerGeneration after it: the refusal hold " +
                    "cannot tell the new manager from the old",
                bump.containsMatchIn(c.substring(at, fn.last + 1)),
            )
        }
    }

    @Test fun `nothing but a build changes the generation`() {
        val getter = bridge.getValue("jni_peer.c").let { c ->
            val at = Regex("""\bNativeBridge_peerManagerGeneration\s*\([^)]*\)\s*\{""").find(c)
                ?: throw AssertionError("NativeBridge_peerManagerGeneration not found in jni_peer.c")
            functionAround(c, at.range.last)
        }
        for ((name, c) in bridge) {
            for (m in mention.findAll(c)) {
                val line = c.substring(c.lastIndexOf('\n', m.range.first) + 1, c.indexOf('\n', m.range.first).let { if (it < 0) c.length else it }).trim()
                val declaration = line == "static uint64_t s_peerManagerGeneration;"
                val increment = bump.find(c, m.range.first)?.range?.first == m.range.first
                val read = name == "jni_peer.c" && m.range.first in getter
                assertTrue("$name touches the generation other than by a build's increment or the getter's read: \"$line\"",
                    declaration || increment || read)
            }
        }
        assertEquals("the generation must be declared once, in jni_peer.c",
            1, bridge.values.sumOf { c -> Regex("""static uint64_t s_peerManagerGeneration;""").findAll(c).count() })
    }

    @Test fun `the generation is read under the peer guard, and is 0 while no manager exists`() {
        val c = bridge.getValue("jni_peer.c")
        val at = Regex("""\bNativeBridge_peerManagerGeneration\s*\([^)]*\)\s*\{""").find(c)
            ?: throw AssertionError("NativeBridge_peerManagerGeneration not found in jni_peer.c")
        val body = c.substring(functionAround(c, at.range.last)).replace(Regex("""\s+"""), " ")
        assertTrue("the generation is read without the peer guard", body.contains("PEER_GUARD();"))
        assertTrue("the getter must answer 0 while no peer manager exists",
            body.contains("return g_peerManager ? (jlong)s_peerManagerGeneration : 0;"))
    }
}
