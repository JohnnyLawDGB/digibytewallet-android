package io.digibyte.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: a wipe quiesces the native wallet and never unloads it from a running process.
 *
 * Bridge readers check the wallet global or the peer-manager global and then use it; they rely on
 * neither being cleared under them while the process runs. So a wipe asks the native side for two
 * things only — stop sync, zero the seed — and no bridge entry point takes the wallet or the peer
 * manager away except the create and recover swaps, which put a new wallet in its place. After a
 * VERIFIED wipe the app ends the process instead and starts a fresh one (the app's
 * FreshStartAfterWipe): the wiped wallet goes with the process, and the next wallet is made in one
 * that never loaded it.
 *
 * The wipe's two native steps live in two files: zeroing the seed in jni_wallet.c, stopping sync in
 * jni_peer.c. Every entry point of the first is scanned; of the second, the one the wipe calls.
 */
class WipeNativeStepsGateTest {

    private fun read(path: String): String {
        val candidates = listOf(path, "../$path", "../../$path")
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("$path not found")
        return file.readText()
    }

    /** Code only: block and line comments blanked. */
    private fun code(text: String) = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    /** Every JNI entry point defined in [c]: its name and its body, braces matched. */
    private fun entryPoints(c: String): Map<String, String> =
        Regex("""\bJava_io_digibyte_core_bridge_NativeBridge_(\w+)\s*\([^)]*\)\s*\{""").findAll(c).associate { head ->
            val open = c.indexOf('{', head.range.first)
            var depth = 0
            var close = open
            for (i in open until c.length) {
                when (c[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) { close = i; break }
                }
            }
            head.groupValues[1] to c.substring(open, close + 1)
        }

    private val walletC by lazy { code(read("native/src/main/jni/bridge/jni_wallet.c")) }
    private val peerC by lazy { code(read("native/src/main/jni/bridge/jni_peer.c")) }

    @Test fun `no bridge entry point takes the wallet away without putting one in its place`() {
        val entries = entryPoints(walletC)
        assertTrue("scanner is blind: found ${entries.size} entry points", entries.size >= 15)
        val swaps = setOf("createWalletFromBytes", "recoverWalletFromBytes")
        val cleared = Regex("""\bg_wallet\s*=\s*NULL\s*;""")
        val clearing = entries.filter { (_, body) -> cleared.containsMatchIn(body) }.keys
        assertEquals("entry points that leave no wallet loaded", emptySet<String>(), clearing - swaps)
        for (swap in swaps) {
            val body = entries[swap] ?: error("scanner is blind: no $swap")
            val gone = cleared.find(body)?.range?.first ?: continue
            assertTrue(
                "$swap clears the wallet without loading the next one",
                Regex("""\bg_wallet\s*=\s*BRWalletNew""").findAll(body).any { it.range.first > gone },
            )
        }
    }

    @Test fun `no wallet entry point takes the peer manager away`() {
        val entries = entryPoints(walletC)
        val clearing = entries.filter { (_, body) -> Regex("""\bg_peerManager\s*=\s*NULL\s*;""").containsMatchIn(body) }.keys
        assertEquals("wallet entry points that leave no peer manager", emptySet<String>(), clearing)
    }

    @Test fun `a wipe asks the native side to stop sync and zero the seed, and nothing more`() {
        val manager = code(read("core/src/main/java/io/digibyte/core/WalletManager.kt"))
        assertTrue(
            "the wipe's native steps are no longer stop sync, then zero the seed",
            Regex("""quiesceNative\s*:\s*\(\)\s*->\s*Unit\s*=\s*\{\s*NativeBridge\.stopSync\(\)\s*;\s*NativeBridge\.lockSession\(\)\s*}""")
                .containsMatchIn(manager),
        )
        val start = manager.indexOf("suspend fun wipeWallet(")
        assertTrue("scanner is blind: no wipeWallet", start >= 0)
        val body = manager.substring(start, manager.indexOf("suspend fun wipeThenReleasePin(", start).takeIf { it > start } ?: error("scanner is blind"))
        assertTrue("scanner is blind: the wipe no longer quiesces", body.contains("quiesceNative()"))
        assertTrue("the wipe calls the bridge itself, beside the quiesce", !body.contains("NativeBridge."))
        assertTrue("the wipe lets go of the native wallet in this process", !Regex("""\.\s*release\s*\(""").containsMatchIn(body))
    }

    @Test fun `stopping sync for a wipe leaves the wallet and the peer manager in place`() {
        val stopSync = entryPoints(peerC)["stopSync"] ?: error("scanner is blind: no stopSync in jni_peer.c")
        assertTrue("scanner is blind: stopSync no longer disconnects", stopSync.contains("BRPeerManagerDisconnect("))
        val takenAway = listOf(
            Regex("""\bg_peerManager\s*=\s*NULL\s*;"""),
            Regex("""\bg_wallet\s*=\s*NULL\s*;"""),
            Regex("""\bBRPeerManagerFree\s*\("""),
            Regex("""\bBRWalletFree\s*\("""),
        ).filter { it.containsMatchIn(stopSync) }.map { it.pattern }
        assertEquals("stopSync takes away what bridge readers rely on", emptyList<String>(), takenAway)
    }
}
