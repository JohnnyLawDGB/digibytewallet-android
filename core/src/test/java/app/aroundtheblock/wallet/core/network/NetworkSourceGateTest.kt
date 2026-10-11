package app.aroundtheblock.wallet.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gates over every Kotlin file the app ships (app main/sideload/play, core, game) for the
 * network rules no single behaviour test can see across the whole tree:
 *
 *  1. every network answer is read through [BoundedBody] — no other body read exists;
 *  2. the one client the app makes is the shared one (NetworkModule) — everything else is derived
 *     from it, so the Tor routing and the DigiScope pins it carries are never left behind;
 *  3. every file that names the DigiScope host either applies the DigiScope pin set itself or is
 *     handed the shared (pinned) client — the Hub socket, the IPFS gateways, the worker and the
 *     sync service are the latter, each proven by a behaviour test named below;
 *  4. nothing connects outside OkHttp (no URL connection of any kind, the service folder included);
 *  5. the asset network clients name only hosts we, or a known operator, run.
 *
 * Comments are removed before any check (the DigiScope host is named in about ten of them).
 * Checks 1, 2 and 4 also ignore the text inside string literals; 3 and 5 read it, since the host
 * names they look for are in strings.
 */
class NetworkSourceGateTest {

    private val repo = File("..").canonicalFile

    private val roots = listOf(
        "app/src/main/java", "app/src/sideload/java", "app/src/play/java", "core/src/main/java", "game/src/main/java",
    )

    /** Every shipped Kotlin file, by its path from the repository root. */
    private val sources: Map<String, File> by lazy {
        roots.flatMap { root ->
            val dir = File(repo, root)
            assertTrue("$root is missing — this gate is watching a tree that moved", dir.isDirectory)
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.associateBy { it.relativeTo(repo).invariantSeparatorsPath }.also {
            assertTrue("only ${it.size} Kotlin files found — the gate is blind, not clean", it.size > 200)
        }
    }

    private fun code(path: String): String = KotlinText.withoutComments(sources.getValue(path).readText(), keepStrings = true)
    private fun codeOnly(path: String): String = KotlinText.withoutComments(sources.getValue(path).readText(), keepStrings = false)

    private fun offenders(pattern: Regex, view: (String) -> String, allowed: Set<String> = emptySet()): List<String> =
        sources.keys.sorted().filter { it !in allowed }.flatMap { path ->
            view(path).lines().mapIndexedNotNull { i, line ->
                if (pattern.containsMatchIn(line)) "$path:${i + 1}: ${line.trim()}" else null
            }
        }

    // ── 1. one bounded reader ──────────────────────────────────────────────────

    private val boundedReader = "core/src/main/java/app/aroundtheblock/wallet/core/network/BoundedBody.kt"

    @Test fun `every network answer is read through the bounded reader`() {
        assertTrue("$boundedReader is missing", boundedReader in sources)
        val bodyRead = Regex("""\.(string|bytes|byteString|byteStream|charStream|source)\(\s*\)|\bpeekBody\(""")
        val found = offenders(bodyRead, ::codeOnly, allowed = setOf(boundedReader))
        assertTrue("a response body is read outside BoundedBody:\n" + found.joinToString("\n"), found.isEmpty())
    }

    // ── 2. one client ──────────────────────────────────────────────────────────

    private val sharedClient = "app/src/main/java/app/aroundtheblock/wallet/di/NetworkModule.kt"

    @Test fun `the only client the app makes is the shared one`() {
        assertTrue("$sharedClient is missing", sharedClient in sources)
        val made = Regex("""\bOkHttpClient\s*\(\s*\)|\bOkHttpClient\.Builder\s*\(""")
        assertTrue("the shared client is no longer made in $sharedClient — the gate is blind",
            made.containsMatchIn(codeOnly(sharedClient)))
        val found = offenders(made, ::codeOnly, allowed = setOf(sharedClient))
        assertTrue(
            "a client is made outside NetworkModule (it would carry neither the Tor routing nor the pins):\n" +
                found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    // ── 3. the DigiScope host ──────────────────────────────────────────────────

    private val host = Regex("""api\.digiscope\.me|DigiScopePins\.HOST""")

    /** Files that name the host and make their requests on the shared client, which carries the pins. */
    private val givenThePinnedClient = mapOf(
        "core/src/main/java/app/aroundtheblock/wallet/core/hub/HubWebSocket.kt" to
            "the Hub socket — SharedClientPinsTest `the Hub WebSocket is made on a pinned client`",
        "core/src/main/java/app/aroundtheblock/wallet/core/ipfs/IpfsClient.kt" to
            "the IPFS gateways — SharedClientPinsTest `the IPFS gateway fetch is made on a pinned client`",
        "app/src/main/java/app/aroundtheblock/wallet/service/SyncWorker.kt" to
            "the worker's peer list, through SeederClient — SeederClientTest",
        "app/src/main/java/app/aroundtheblock/wallet/service/SyncService.kt" to
            "the service's peer list, through SeederClient — SeederClientTest",
    )

    /** Files that name the host without making a request to it. */
    private val namesWithoutRequest = mapOf(
        "core/src/main/java/app/aroundtheblock/wallet/core/network/DigiScopePins.kt" to "the pin set itself",
        "core/src/main/java/app/aroundtheblock/wallet/core/digiid/IdentityKeyPolicy.kt" to "a Digi-ID domain comparison",
        "app/src/main/java/app/aroundtheblock/wallet/ui/settings/ReconcileScreen.kt" to "text shown to the user",
    )

    @Test fun `every file that names the DigiScope host applies its pins or is given the pinned client`() {
        val naming = sources.keys.filter { host.containsMatchIn(code(it)) }.sorted()
        assertTrue("only ${naming.size} files name the host — the gate is blind", naming.size >= 8)

        val unpinned = naming.filter { path ->
            path !in givenThePinnedClient && path !in namesWithoutRequest && !code(path).contains("DigiScopePins")
        }
        assertTrue(
            "these files name the DigiScope host but neither apply DigiScopePins nor are listed as given the " +
                "pinned client:\n" + unpinned.joinToString("\n"),
            unpinned.isEmpty(),
        )

        // A listed file that stopped naming the host, or moved, is a stale entry: the list is checked, not trusted.
        for (path in givenThePinnedClient.keys + namesWithoutRequest.keys) {
            assertTrue("$path is listed but missing", path in sources)
            assertTrue("$path is listed but no longer names the host", path in naming)
        }
        // A file given the pinned client must not make its own, nor connect any other way.
        for (path in givenThePinnedClient.keys) {
            val text = codeOnly(path)
            assertTrue("$path makes a client of its own", !Regex("""\bOkHttpClient\s*\(\s*\)|\bOkHttpClient\.Builder\s*\(""").containsMatchIn(text))
            assertTrue("$path connects outside OkHttp", !rawConnection.containsMatchIn(text))
        }
        for (path in listOf(
            "app/src/main/java/app/aroundtheblock/wallet/service/SyncWorker.kt",
            "app/src/main/java/app/aroundtheblock/wallet/service/SyncService.kt",
        )) {
            assertTrue("$path no longer reaches the seeder through SeederClient", code(path).contains("SeederClient("))
        }
    }

    // ── 4. nothing outside OkHttp ──────────────────────────────────────────────

    private val rawConnection = Regex("""\bopenConnection\s*\(|\bHttpsURLConnection\b|\bHttpURLConnection\b|\bURLConnection\b|\bopenStream\s*\(|\bjava\.net\.URL\b|(?<![\w.])URL\s*\(""")

    @Test fun `nothing connects outside OkHttp`() {
        val found = offenders(rawConnection, ::codeOnly)
        assertTrue("a connection is opened outside OkHttp (no Tor routing, no pins):\n" + found.joinToString("\n"), found.isEmpty())
        val service = sources.keys.filter { it.startsWith("app/src/main/java/app/aroundtheblock/wallet/service/") }
        assertTrue("the service folder is empty — the gate is blind", service.size >= 5)
    }

    // ── 5. operated asset providers ────────────────────────────────────────────

    /** Hosts the asset network clients may name: ours, and digistamp's (a known operator, pinned in DigistampPins). */
    private val operatedAssetHosts = setOf("api.digiscope.me", "assets.digistamp.co")

    @Test fun `the asset network clients name only hosts we or a known operator run`() {
        val dir = "core/src/main/java/app/aroundtheblock/wallet/core/asset/network/"
        val files = sources.keys.filter { it.startsWith(dir) }.sorted()
        assertTrue("no asset network client found — the gate is blind", files.size >= 3)
        val url = Regex("""\b(?:https?|wss?)://([A-Za-z0-9.-]+)""")
        val named = files.flatMap { path -> url.findAll(code(path)).map { path to it.groupValues[1] }.toList() }
        assertTrue("no host is named in $dir — the gate is blind", named.isNotEmpty())
        val other = named.filter { (_, h) -> h !in operatedAssetHosts }
        assertEquals("an asset client names a host outside the operated list: $other", emptyList<Pair<String, String>>(), other)
    }

    // ── the gate's own reading of Kotlin ───────────────────────────────────────

    @Test fun `the gate reads Kotlin as Kotlin`() {
        val text = """
            val a = "https://api.digiscope.me/x" // a comment naming body.string()
            /* block /* nested */ still a comment: response.body?.string() */
            val b = ${"\""}${"\""}${"\""}raw https://api.digiscope.me ${"$"}{ call("//not a comment") } end${"\""}${"\""}${"\""}
            val c = '"'; val e = '\'' // after an escaped quote: body.string()
            val d = "${"$"}{ body.string() }"
        """.trimIndent()
        val kept = KotlinText.withoutComments(text, keepStrings = true)
        assertTrue(kept.contains("https://api.digiscope.me/x"))
        assertTrue("a line comment was kept", !kept.contains("a comment naming"))
        assertTrue("a comment after a quote character literal was kept", !kept.contains("after an escaped quote"))
        assertTrue("a nested block comment was kept", !kept.contains("nested") && !kept.contains("still a comment"))
        assertTrue("a string inside a template was read as a comment", kept.contains("//not a comment"))
        assertTrue("a raw string was not kept", kept.contains("raw https://api.digiscope.me"))
        val bare = KotlinText.withoutComments(text, keepStrings = false)
        assertTrue("a string's text was kept", !bare.contains("api.digiscope.me"))
        assertTrue("a template expression inside a string is code", bare.contains("body.string()"))
        assertEquals(text.lines().size, kept.lines().size)
    }
}

/**
 * Kotlin source with comments removed (nested block comments included), keeping line numbers.
 * String literals are kept as written, or emptied when [keepStrings] is false; either way a
 * template expression inside one (`${'$'}{ … }`) is code and is kept.
 */
internal object KotlinText {

    fun withoutComments(source: String, keepStrings: Boolean): String {
        val reader = Reader(source, keepStrings)
        reader.code(insideTemplate = false)
        return reader.out.toString()
    }

    private class Reader(val s: String, val keepStrings: Boolean) {
        val out = StringBuilder()
        var i = 0

        fun code(insideTemplate: Boolean) {
            var depth = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') i++
                    s.startsWith("/*", i) -> blockComment()
                    s.startsWith("\"\"\"", i) -> string(raw = true)
                    c == '"' -> string(raw = false)
                    c == '\'' -> charLiteral()
                    c == '{' -> { depth++; out.append(c); i++ }
                    c == '}' -> {
                        if (insideTemplate && depth == 0) return
                        depth--; out.append(c); i++
                    }
                    else -> { out.append(c); i++ }
                }
            }
        }

        private fun blockComment() {
            var depth = 0
            while (i < s.length) {
                when {
                    s.startsWith("/*", i) -> { depth++; i += 2 }
                    s.startsWith("*/", i) -> { depth--; i += 2; if (depth == 0) return }
                    else -> { if (s[i] == '\n') out.append('\n'); i++ }
                }
            }
        }

        private fun string(raw: Boolean) {
            val quote = if (raw) "\"\"\"" else "\""
            out.append(quote); i += quote.length
            while (i < s.length) {
                val c = s[i]
                when {
                    raw && s.startsWith("\"\"\"", i) -> {
                        var end = i + 3
                        while (end < s.length && s[end] == '"') end++
                        if (keepStrings) out.append(s, i, end - 3)
                        out.append("\"\"\""); i = end; return
                    }
                    !raw && c == '"' -> { out.append(c); i++; return }
                    !raw && c == '\\' -> { if (keepStrings) out.append(s, i, minOf(i + 2, s.length)); i += 2 }
                    s.startsWith("\${", i) -> {
                        out.append("\${"); i += 2
                        code(insideTemplate = true)
                        if (i < s.length) { out.append('}'); i++ }
                    }
                    c == '\n' -> { out.append(c); i++; if (!raw) return }
                    else -> { if (keepStrings) out.append(c); i++ }
                }
            }
        }

        private fun charLiteral() {
            // '\'' and 'A': the closing quote comes after the escaped character.
            val end = if (s.startsWith("'\\", i)) s.indexOf('\'', i + 3) else s.indexOf('\'', i + 1)
            val stop = if (end < 0) s.length else end + 1
            if (keepStrings) out.append(s, i, stop) else out.append("''")
            i = stop
        }
    }
}
