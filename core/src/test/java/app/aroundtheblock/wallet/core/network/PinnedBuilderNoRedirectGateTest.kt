package app.aroundtheblock.wallet.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every pinned client the source builds follows no redirect (4.1.2 row 30, review R4).
 *
 * PinnedClientsNoRedirectTest checks four named classes, so DgbNodeClient's pinned client -- recovery's
 * UTXO, raw-tx and DD lookups and the reconcile POST, all carrying wallet addresses -- was missed. This
 * gate finds every OkHttp builder chain that sets a certificate pinner, in app and core, and requires
 * both redirect switches off in that chain. The one exception is the shared base client, which serves
 * unpinned hosts too (IPFS gateways redirect); every pinned client is derived from it and turns them off.
 *
 * IPFS-NO-REDIRECT: that gate sees only a chain that sets a pinner, and a class handed the shared client
 * is pinned without one -- the IPFS client reaches its trusted gateway that way. So every file that
 * sends a request also has a stated redirect policy in [senderPolicy]: a new sender with none fails.
 */
class PinnedBuilderNoRedirectGateTest {

    private val root = File("../..").canonicalFile.let { if (File(it, "core/src").isDirectory) it else File("..").canonicalFile }
    private val sharedBase = "app/src/main/java/app/aroundtheblock/wallet/di/NetworkModule.kt"

    private fun chains(): List<Pair<String, String>> =
        listOf("app/src/main", "core/src/main").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.flatMap { f ->
            val text = f.readText()
            Regex("""(newBuilder\(\)|OkHttpClient\.Builder\(\))""").findAll(text).map { m ->
                val end = text.indexOf(".build()", m.range.last).let { if (it < 0) text.length else it }
                f.relativeTo(root).invariantSeparatorsPath to text.substring(m.range.first, end)
            }.toList()
        }.filter { it.second.contains(".certificatePinner(") }

    @Test fun `the gate sees the pinned clients`() {
        val files = chains().map { it.first }.toSet()
        assertTrue("found no pinned builder at all: $files", files.size >= 6)
        assertTrue(files.any { it.endsWith("reconcile/DgbNodeClient.kt") })
    }

    @Test fun `every pinned builder turns both redirect switches off`() {
        val open = chains().filter { (path, chain) ->
            path != sharedBase && !(chain.contains(".followRedirects(false)") && chain.contains(".followSslRedirects(false)"))
        }.map { it.first }
        assertEquals("a pinned client follows redirects", emptyList<String>(), open)
    }

    // ── IPFS-NO-REDIRECT: every sender states its redirect policy ──────────────────────────────

    private enum class Redirects { NEVER, FOLLOWS, OPEN }

    private val core = "core/src/main/java/app/aroundtheblock/wallet/core"
    private val app = "app/src/main/java/app/aroundtheblock/wallet"

    /**
     * Every shipped file that sends a request, and what it does with a 3xx. NEVER: the file derives a
     * client with both switches off for the host whose identity it relies on. FOLLOWS: it sends on the
     * client it is given, to hosts whose answer is not trusted for who served it; the reason says why.
     * OPEN: it sends on the client it is given and has NOT been reviewed against the row 30 invariant.
     * That is a recorded open item, not an exemption; the set of them is fixed in [openItems].
     */
    private val senderPolicy: Map<String, Pair<Redirects, String>> = mapOf(
        "$core/asset/network/DigiScopeAssetClient.kt" to (Redirects.NEVER to "pinned (4.1.2 row 30)"),
        "$core/asset/network/DigistampAssetClient.kt" to (Redirects.NEVER to "pinned (4.1.2 row 30)"),
        "$core/digiscope/DigiScopeClient.kt" to (Redirects.NEVER to "pinned (4.1.2 row 30)"),
        "$core/digiid/DigiIdManager.kt" to (Redirects.NEVER to "the callback is posted to the host that was approved; pinned for the DigiStamp hosts"),
        "$core/reconcile/DgbNodeClient.kt" to (Redirects.NEVER to "pinned default endpoint (4.1.2 row 30, R4); the user's own node is theirs"),
        "$app/service/SeederClient.kt" to (Redirects.NEVER to "pinned (4.1.2 row 30)"),
        "$core/ipfs/IpfsClient.kt" to (Redirects.NEVER to
            "the trusted gateway, whose answer is accepted on its pinned identity (IPFS-NO-REDIRECT) -- IpfsNoRedirectTest"),
        "$core/PriceProvider.kt" to (Redirects.FOLLOWS to "public price sources; none is pinned"),
        "$core/UpdateChecker.kt" to (Redirects.FOLLOWS to "the public release listing; not pinned"),
        "$core/hub/HubWebSocket.kt" to (Redirects.NEVER to
            "a WebSocket to the pinned host: a 3xx on its upgrade is a failed connect (HUB-WS-NO-REDIRECT) -- HubSocketNoRedirectTest"),
        "$app/DigiByteApp.kt" to (Redirects.FOLLOWS to
            "Coil's client: AssetImageResolver hands Coil ipfs:// only, which IpfsFetcher reads through IpfsClient"),
    )

    /**
     * The senders not yet reviewed: none. Nothing joins this set: a new sender is NEVER or FOLLOWS with a
     * reason. The Hub socket was the last one here, and HUB-WS-NO-REDIRECT made it NEVER.
     */
    private val openItems = emptySet<String>()

    /** A send, however the member is reached: called (`.newCall(`) or taken as a reference (`::newCall`). */
    private val sends = Regex("""(?:\.|::)\s*newCall\b|(?:\.|::)\s*newWebSocket\b|\.okHttpClient\s*\(\s*\w""")
    private val bothOff = Regex(
        """newBuilder\(\)(?:(?!\.build\(\)).)*?\.followRedirects\(false\)\s*\.followSslRedirects\(false\)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** Shipped Kotlin by path from the repository root, comments and string text removed. */
    private fun shippedCode(): Map<String, String> =
        listOf("app/src/main", "app/src/sideload", "app/src/play", "core/src/main", "game/src/main")
            .map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .associate { it.relativeTo(root).invariantSeparatorsPath to KotlinText.withoutComments(it.readText(), keepStrings = false) }

    /** The sender search is checked against samples, so a way of reaching a send it cannot see fails here. */
    @Test fun `the sender search sees a request however the send is reached`() {
        val seen = listOf(
            "client.newCall(request).execute()",
            "client\n    .newCall (request)",
            "val send = client::newCall",
            "val send: (Request) -> Call = httpClient :: newCall",
            "webSocket = httpClient.newWebSocket(request, listener)",
            "val open = httpClient::newWebSocket",
            "ImageLoader.Builder(this).okHttpClient(client)",
        )
        assertEquals("a send the gate does not see", emptyList<String>(), seen.filter { !sends.containsMatchIn(it) })
        val notASend = listOf("val newCallCount = 0", "fun renewCalls() {}", "// newCall", "okHttpClient()")
        assertEquals("not a send, but counted as one", emptyList<String>(), notASend.filter { sends.containsMatchIn(it) })
    }

    @Test fun `every file that sends a request states its redirect policy`() {
        val code = shippedCode()
        val senders = code.filterValues { sends.containsMatchIn(it) }.keys.sorted()
        assertTrue("only ${senders.size} senders found -- the gate is blind", senders.size >= 10)
        assertEquals(
            "a file sends a request with no redirect policy stated in senderPolicy",
            emptyList<String>(), senders.filter { it !in senderPolicy },
        )
        // The list is checked, not trusted: an entry for a file that moved or no longer sends is stale.
        assertEquals("listed but not a sender", emptyList<String>(), senderPolicy.keys.filter { it !in senders }.sorted())
    }

    @Test fun `a sender that never follows a redirect turns both switches off and one that follows sets neither`() {
        val code = shippedCode()
        val wrong = senderPolicy.filter { (path, policy) ->
            val text = code[path] ?: return@filter true
            when (policy.first) {
                Redirects.NEVER -> !bothOff.containsMatchIn(text)
                Redirects.FOLLOWS, Redirects.OPEN -> text.contains("followRedirects(") || text.contains("followSslRedirects(")
            }
        }.keys.sorted()
        assertEquals("the stated redirect policy is not what the source does", emptyList<String>(), wrong)
    }

    @Test fun `the senders not yet reviewed are the recorded ones and no other`() {
        assertEquals(openItems, senderPolicy.filterValues { it.first == Redirects.OPEN }.keys)
    }
}
