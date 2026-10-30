package app.aroundtheblock.wallet.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Track 4 of the iOS port plan's §19.2, "no trusted server, as a test": every outbound destination
 * is listed in `docs/network-destinations.md`, with what happens when it is unreachable. This gate
 * fails when the code and that list disagree:
 *
 *  - a host name, URL host or IPv4 literal in shipped code (or a default-locale string resource)
 *    that the destinations table does not list, or a listed host the code no longer has;
 *  - a file that uses a network API and is not in the call-site table, or a listed file that no
 *    longer does (a destination built at run time, which no literal shows, still needs a call site);
 *  - the JNI bridge's hardcoded mainnet peers drifting from the core's canon (`BRPeerCanon.h`).
 *
 * Comments are not scanned; string templates are blanked first. A source gate never passes
 * vacuously: a missing directory or table fails, and the scan must find the destinations it knows.
 *
 * The iOS app carries the same gate over its own sources (`NetworkDestinationsGateTests.swift`).
 */
class NetworkDestinationsGateTest {

    /** Unit tests run with the module directory (`app/`) as the working directory. */
    private val repo = File("..").canonicalFile
    private val doc = File(repo, "docs/network-destinations.md")
    private val coreDir = File(repo, "native/src/main/jni/digibytewallet-core")

    private val kotlinRoots = listOf(
        "app/src/main/java", "app/src/play/java", "app/src/sideload/java",
        "core/src/main/java", "game/src/main/java",
    )

    // ── The gate ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyHostInTheCodeIsListedAndEveryListedHostIsInTheCode() {
        val found = scanHosts()
        val listed = table("destinations")

        val unlisted = found.keys - listed
        val stale = listed - found.keys
        val problems = buildList {
            for (h in unlisted.sorted()) add("NOT LISTED: $h (in ${found.getValue(h).sorted().joinToString()})")
            for (h in stale.sorted()) add("STALE ROW: $h is listed but no longer found in the code")
        }
        if (problems.isNotEmpty()) {
            fail(
                "docs/network-destinations.md and the code disagree. Add a row for a new destination " +
                    "(purpose, required or not, and what happens when it is unreachable) or remove a stale one:\n" +
                    problems.joinToString("\n"),
            )
        }
    }

    @Test
    fun everyNetworkCallSiteIsListed() {
        val found = scanCallSites()
        val listed = table("call-sites")
        val problems = buildList {
            for (p in (found - listed).sorted()) add("NOT LISTED: $p uses a network API")
            for (p in (listed - found).sorted()) add("STALE ROW: $p is listed but uses no network API (or is gone)")
        }
        if (problems.isNotEmpty()) {
            fail("The call-site table in docs/network-destinations.md and the code disagree:\n" + problems.joinToString("\n"))
        }
    }

    @Test
    fun theBridgePeersAreTheCoreCanon() {
        val bridge = arrayLiterals(File(repo, "native/src/main/jni/bridge/jni_peer.c"), "MAINNET_PRIORITY_PEER_IPS[] = {")
        val canon = arrayLiterals(File(coreDir, "BRPeerCanon.h"), "mainnet[BR_PEER_CANON_MAINNET_COUNT] = {")
        assertTrue("no mainnet peers read from jni_peer.c: update the gate", bridge.size >= 10)
        assertEquals("jni_peer.c MAINNET_PRIORITY_PEER_IPS must equal the core's canon (BRPeerCanon.h)", canon, bridge)
    }

    /** The scan is not vacuous: it reaches the files and finds what is known to be there. */
    @Test
    fun theScanFindsTheKnownDestinations() {
        val found = scanHosts()
        for (h in listOf("api.digiscope.me", "seed.digibyte.io", "134.199.198.90", "api.coingecko.com")) {
            assertTrue("the scan no longer finds $h: the gate is not reading the sources it should", h in found)
        }
        assertTrue("the scan read too few files", scannedFiles().size > 200)
    }

    // ── The lexer and the matchers, on fixed input ─────────────────────────────────────────

    @Test
    fun theMatcherReadsLiteralsNotCommentsAndBlanksTemplates() {
        val src = """
            // "https://in-a-comment.com" is not read
            /* "https://block.comment.org" /* nested */ still a comment "x.io" */
            val a = "https://api.example-host.net/path?q=1"
            val b = "${'$'}txid.to"
            val c = "https://${'$'}HOST/api"
            val d = "seed.somewhere.tools"
            val e = "10.1.2.3"
            val f = "see node.example.com or localhost or 127.0.0.1"
            val g = "app.aroundtheblock.wallet"
            val h = "wallet-status.txt"
            val i = '"'
            val j = "after a char literal: k.me"
        """.trimIndent()
        val hosts = Lexer.lex(src, Lang.KOTLIN).literals.flatMap { hostsIn(it, wholeLiteral = true) }.toSet()
        assertEquals(setOf("api.example-host.net", "seed.somewhere.tools", "10.1.2.3", "k.me"), hosts)

        val code = Lexer.lex("val s = \"Socket(\" // Socket(\nSocket(x)", Lang.KOTLIN).code
        assertEquals(1, Regex("\\bSocket\\(").findAll(code).count())
    }

    // ── Scanning ───────────────────────────────────────────────────────────────────────────

    private enum class Lang { KOTLIN, C }

    private class Lexed(val literals: List<String>, val code: String)

    /** Splits source into its string literals and its code with comments and literals blanked. */
    private object Lexer {
        /** The index after a char literal (`'a'`, `'\\n'`, `'\\u0041'`) that starts at [i], or -1 when none does. */
        private fun charLiteralEnd(src: String, i: Int): Int {
            var j = i + 1
            var chars = 0
            while (j < src.length && chars < 7) {
                when (src[j]) {
                    '\\' -> { j += 2; chars++ }
                    '\'' -> return if (chars > 0) j + 1 else -1
                    '\n' -> return -1
                    else -> { j++; chars++ }
                }
            }
            return -1
        }

        fun lex(src: String, lang: Lang): Lexed {
            val literals = ArrayList<String>()
            val code = StringBuilder(src.length)
            var i = 0
            val n = src.length
            fun blank(from: Int, to: Int) {
                for (k in from until to) code.append(if (src[k] == '\n') '\n' else ' ')
            }
            while (i < n) {
                val c = src[i]
                when {
                    src.startsWith("//", i) -> {
                        val end = src.indexOf('\n', i).let { if (it < 0) n else it }
                        blank(i, end); i = end
                    }
                    src.startsWith("/*", i) -> {
                        var depth = 1
                        var j = i + 2
                        while (j < n && depth > 0) {
                            when {
                                // C block comments do not nest; Kotlin's do.
                                lang == Lang.KOTLIN && src.startsWith("/*", j) -> { depth++; j += 2 }
                                src.startsWith("*/", j) -> { depth--; j += 2 }
                                else -> j++
                            }
                        }
                        blank(i, j); i = j
                    }
                    lang == Lang.KOTLIN && src.startsWith("\"\"\"", i) -> {
                        val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it }
                        literals.add(blankTemplates(src.substring(i + 3, end), raw = true))
                        val stop = minOf(n, end + 3)
                        blank(i, stop); i = stop
                    }
                    c == '"' -> {
                        var j = i + 1
                        val buf = StringBuilder()
                        while (j < n && src[j] != '"' && src[j] != '\n') {
                            if (src[j] == '\\' && j + 1 < n) {
                                // An escaped '$' is a literal dollar, not a template.
                                buf.append(if (src[j + 1] == '$') "\\$" else " ")
                                j += 2
                                continue
                            }
                            if (lang == Lang.KOTLIN && src[j] == '$' && j + 1 < n && src[j + 1] == '{') {
                                // Copy the template whole, nested braces included, so its quotes do not end the string.
                                var depth = 0
                                while (j < n) {
                                    if (src[j] == '{') depth++
                                    if (src[j] == '}') { depth--; if (depth == 0) { buf.append(src[j]); j++; break } }
                                    buf.append(src[j]); j++
                                }
                                continue
                            }
                            buf.append(src[j]); j++
                        }
                        val text = buf.toString()
                        literals.add(if (lang == Lang.KOTLIN) blankTemplates(text, raw = false) else text.replace("\\$", "$"))
                        val stop = minOf(n, j + 1)
                        blank(i, stop); i = stop
                    }
                    c == '\'' -> {
                        val stop = charLiteralEnd(src, i)
                        if (stop > 0) { blank(i, stop); i = stop } else { code.append(c); i++ }
                    }
                    else -> { code.append(c); i++ }
                }
            }
            return Lexed(literals, code.toString())
        }

        /** Kotlin `$name` and `${expr}` become one NUL each, which no host or URL pattern can cross. */
        private fun blankTemplates(s: String, raw: Boolean): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                if (!raw && s.startsWith("\\$", i)) { out.append('$'); i += 2; continue }
                if (s[i] == '$' && i + 1 < s.length && s[i + 1] == '{') {
                    var depth = 0
                    while (i < s.length) {
                        if (s[i] == '{') depth++
                        if (s[i] == '}') { depth--; if (depth == 0) { i++; break } }
                        i++
                    }
                    out.append('\u0000'); continue
                }
                if (s[i] == '$' && i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '_')) {
                    i++
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    out.append('\u0000'); continue
                }
                out.append(s[i]); i++
            }
            return out.toString()
        }
    }

    private companion object {
        // Top-level domains a host in this code base could end in. A file name ("x.json", "y.kt") or a
        // package name ("app.aroundtheblock.wallet") ends in none of them.
        const val TLDS = "com|org|net|io|me|co|app|dev|link|tools|services|info|us|trade|blue|xyz|onion|cloud|" +
            "network|tech|ai|so|gg|tv|cc|eu|uk|de|ch|ly|to|sh|site|online|store|exchange|finance|money|cash|" +
            "digital|world|global|biz|pro|page|gov|edu"
        val HOST = Regex("(?<![\\w.-])((?:[a-z0-9-]+\\.)+(?:$TLDS))(?![\\w.-])", RegexOption.IGNORE_CASE)
        val URL = Regex("\\b(?:https?|wss?)://([^/\\s\"'?#:@)\\\\\u0000]+)", RegexOption.IGNORE_CASE)
        val IPV4 = Regex("^\\s*(\\d{1,3}(?:\\.\\d{1,3}){3})\\s*$")

        /** Network APIs in Kotlin and Java: anything that opens a connection or resolves a name. */
        val KOTLIN_CALLS = Regex(
            "\\.newCall\\(|\\.newWebSocket\\(|\\bSocket\\(|\\bDatagramSocket\\b|\\.openConnection\\(|" +
                "\\bHttpURLConnection\\b|\\.openStream\\(|InetAddress\\.(?:getByName|getAllByName)\\b|" +
                "\\.loadUrl\\(|\\bAsyncImage\\(|\\bImageLoader\\b|\\bTorRuntime\\b",
        )
        /** Network APIs in C. */
        val C_CALLS = Regex("\\b(?:getaddrinfo|gethostbyname|connect|socket)\\s*\\(")

        fun isIgnored(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h.endsWith(".localhost")) return true
            if (Regex("(^|\\.)example\\.(com|org|net)$").containsMatchIn(h)) return true
            if (h.startsWith("127.") || h == "0.0.0.0") return true
            return false
        }

        fun hostsIn(literal: String, wholeLiteral: Boolean): Set<String> {
            val out = HashSet<String>()
            for (m in URL.findAll(literal)) {
                val h = m.groupValues[1].lowercase()
                if ('.' in h) out.add(h)
            }
            for (m in HOST.findAll(literal)) out.add(m.groupValues[1].lowercase())
            if (wholeLiteral) IPV4.find(literal)?.let { out.add(it.groupValues[1]) }
            return out.filterNot(::isIgnored).toSet()
        }
    }

    private fun requireDir(path: File): File {
        if (!path.isDirectory) {
            fail("source gate: ${path.path} is missing (for the core: git submodule update --init); update the gate if it moved")
        }
        return path
    }

    private fun kotlinFiles(): List<File> = kotlinRoots.map { File(repo, it) }
        .flatMap { requireDir(it).walkTopDown().filter { f -> f.isFile && (f.extension == "kt" || f.extension == "java") }.toList() }

    private fun cFiles(): List<File> {
        val bridge = requireDir(File(repo, "native/src/main/jni/bridge")).walkTopDown()
            .filter { it.isFile && (it.extension == "c" || it.extension == "h") }.toList()
        // The core's shipped sources: its top level, without the test files.
        val core = (requireDir(coreDir).listFiles() ?: emptyArray())
            .filter { it.isFile && (it.extension == "c" || it.extension == "h") }
            .filterNot { it.name.contains("Test") || it.name == "test.c" }
        if (core.none { it.name == "BRChainParams.h" }) fail("source gate: the core submodule is not checked out at ${coreDir.path}")
        return bridge + core
    }

    private fun resourceFiles(): List<File> {
        val values = requireDir(File(repo, "app/src/main/res/values"))
        return (values.listFiles() ?: emptyArray()).filter { it.isFile && it.extension == "xml" }
    }

    private fun scannedFiles(): List<File> = kotlinFiles() + cFiles() + resourceFiles()

    private fun rel(f: File): String = f.canonicalFile.relativeTo(repo).invariantSeparatorsPath

    private fun scanHosts(): Map<String, Set<String>> {
        val found = HashMap<String, MutableSet<String>>()
        fun add(hosts: Set<String>, f: File) { for (h in hosts) found.getOrPut(h) { HashSet() }.add(rel(f)) }
        for (f in kotlinFiles()) for (lit in Lexer.lex(f.readText(), Lang.KOTLIN).literals) add(hostsIn(lit, true), f)
        for (f in cFiles()) for (lit in Lexer.lex(f.readText(), Lang.C).literals) add(hostsIn(lit, true), f)
        val body = Regex("<(string|item)\\b[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
        for (f in resourceFiles()) {
            val xml = f.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            for (m in body.findAll(xml)) add(hostsIn(m.groupValues[2], false), f)
        }
        return found
    }

    private fun scanCallSites(): Set<String> {
        val out = HashSet<String>()
        for (f in kotlinFiles()) if (KOTLIN_CALLS.containsMatchIn(Lexer.lex(f.readText(), Lang.KOTLIN).code)) out.add(rel(f))
        for (f in cFiles()) if (C_CALLS.containsMatchIn(Lexer.lex(f.readText(), Lang.C).code)) out.add(rel(f))
        return out
    }

    /** The backticked entries in the first column of the table between `gate:<name>` markers. */
    private fun table(name: String): Set<String> {
        assertTrue("source gate: ${doc.path} is missing", doc.isFile)
        val text = doc.readText()
        val begin = text.indexOf("<!-- gate:$name:begin -->")
        val end = text.indexOf("<!-- gate:$name:end -->")
        if (begin < 0 || end < begin) fail("source gate: no gate:$name markers in docs/network-destinations.md")
        val entries = text.substring(begin, end).lines()
            .filter { it.startsWith("|") && !it.startsWith("|---") }
            .drop(1)   // the header row
            .flatMap { row -> Regex("`([^`]+)`").findAll(row.split('|')[1]).map { it.groupValues[1].trim() }.toList() }
            .toSet()
        assertTrue("source gate: the gate:$name table is empty", entries.isNotEmpty())
        return entries
    }

    /** The IPv4 string literals of the C array that starts at [opening]. */
    private fun arrayLiterals(file: File, opening: String): List<String> {
        val raw = file.readText()
        val start = raw.indexOf(opening)
        val end = raw.indexOf("};", start)
        if (start < 0 || end < 0) fail("source gate: `$opening` not found in ${rel(file)}; update the gate")
        val body = raw.substring(start, end)
        return Lexer.lex(body, Lang.C).literals.filter { IPV4.matches(it) }
    }
}
