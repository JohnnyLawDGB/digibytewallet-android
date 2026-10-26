package app.aroundtheblock.wallet.ui.hub

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GUARD — a census, not a behaviour test: where an identity signature can be made. Each place it
 * can be made is a place the spend gate has to stand before (SpendGateWiringTest), so a new place
 * must be noticed rather than compile quietly.
 *
 *  - The native signing calls are made in exactly three files: an approved Digi-ID login
 *    (DigiIdManager), Hub quick login (DigiScopeClient), and Hub posts (HubIdentity).
 *  - Hub posts are signed in exactly the three posting actions, each of which a gated screen calls
 *    only under the gate's answer: ChatViewModel.sendMessage, ForumViewModel.createThread and
 *    ForumViewModel.replyToThread.
 *
 * It reads code: a comment or a string naming the calls is no call.
 */
class IdentitySigningSitesGateTest {

    private val repo = File("..").canonicalFile
    private val roots = listOf("app/src/main/java", "app/src/sideload/java", "app/src/play/java", "core/src/main/java")

    /** Every shipped Kotlin file as code, by its path from the repository root. */
    private val sources: Map<String, KotlinSourceGate> by lazy {
        roots.map { File(repo, it) }.filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .associate { it.relativeTo(repo).invariantSeparatorsPath to KotlinSourceGate.of(it.readText()) }
            .also { assertTrue("only ${it.size} Kotlin files found — the gate is blind, not clean", it.size > 200) }
    }

    @Test fun `the identity key signs in exactly three files`() {
        val signing = Regex("""(?<!\w)NativeBridge\s*\.\s*sign(Message|IdentityMessage)\s*\(""")
        val found = sources.filterValues { signing.containsMatchIn(it.code) }.keys.map { it.substringAfterLast('/') }.toSet()
        assertEquals(setOf("DigiIdManager.kt", "DigiScopeClient.kt", "HubIdentity.kt"), found)
    }

    @Test fun `Hub posts are signed only in the three posting actions`() {
        val declared = Regex("""(?<!\w)fun\s+(\w+)\s*\(""")
        val sites = mutableListOf<String>()
        for ((path, file) in sources) {
            for (call in file.calls("hubIdentity.sign")) {
                val function = declared.findAll(file.code.substring(0, call.range.first)).lastOrNull()?.groupValues?.get(1)
                sites += "${path.substringAfterLast('/')}:$function"
            }
        }
        assertEquals(
            listOf("ChatViewModel.kt:sendMessage", "ForumViewModel.kt:createThread", "ForumViewModel.kt:replyToThread"),
            sites.sorted(),
        )
    }
}
