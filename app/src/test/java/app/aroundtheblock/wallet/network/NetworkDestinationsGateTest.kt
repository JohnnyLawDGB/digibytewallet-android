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
 *  - a host name, URL host or IPv4 address in shipped code, a default-locale string resource,
 *    `res/xml` or a manifest that the destinations table does not list, or a listed host the code
 *    no longer has (checked per host, not per row);
 *  - the core's DNS seed arrays (`BRMainNetDNSSeeds`, `BRTestNetDNSSeeds`) differing from the
 *    table's seed rows, or the JNI bridge's mainnet peers drifting from the core's canon;
 *  - a file that uses a network API and is not in the call-site table, or a listed file that no
 *    longer does (a destination built at run time, which no literal shows, still needs a call site);
 *  - cleartext traffic permitted anywhere;
 *  - a source-set directory the gate does not know how to scan.
 *
 * The scan roots are derived from `settings.gradle.kts` and each module's `src/<set>/` (test source
 * sets excluded), so a new module or source set is scanned without touching this file. Comments are
 * not scanned; string templates are blanked first. A source gate never passes vacuously: a missing
 * directory or table fails, and the scan must find the destinations it knows.
 *
 * The iOS app carries the same gate over its own sources (`NetworkDestinationsGateTests.swift`).
 */
class NetworkDestinationsGateTest {

    /** Unit tests run with the module directory (`app/`) as the working directory. */
    private val repo = File("..").canonicalFile
    private val doc = File(repo, "docs/network-destinations.md")
    private val coreDir = File(repo, "native/src/main/jni/digibytewallet-core")

    // ── The gate ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyHostInTheCodeIsListedAndEveryListedHostIsInTheCode() {
        val found = scan().hosts
        val listed = table("destinations").flatMap { it.entries }.toSet()
        val problems = buildList {
            for (h in (found.keys - listed).sorted()) add("NOT LISTED: $h (in ${found.getValue(h).sorted().joinToString()})")
            for (h in (listed - found.keys).sorted()) add("STALE: $h is listed but no longer found in the code")
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
    fun theDnsSeedRowsAreTheCoreSeedArrays() {
        val params = File(coreDir, "BRChainParams.h")
        for (array in listOf("BRMainNetDNSSeeds", "BRTestNetDNSSeeds")) {
            val seeds = cArrayLiterals(params, "$array[] = {").toSet()
            assertTrue("no seeds read from $array: update the gate", seeds.isNotEmpty())
            val rows = table("destinations").filter { "`$array`" in it.code }
            assertEquals("one destinations row must name `$array` in its Code column", 1, rows.size)
            assertEquals("the `$array` row must list exactly the core's seeds", seeds, rows.single().entries.toSet())
        }
    }

    @Test
    fun everyNetworkCallSiteIsListed() {
        val found = scan().callSites
        val listed = table("call-sites").flatMap { it.entries }.toSet()
        val problems = buildList {
            for (p in (found - listed).sorted()) add("NOT LISTED: $p uses a network API")
            for (p in (listed - found).sorted()) add("STALE ROW: $p is listed but uses no network API (or is gone)")
        }
        if (problems.isNotEmpty()) {
            fail("The call-site table in docs/network-destinations.md and the code disagree:\n" + problems.joinToString("\n"))
        }
    }

    @Test
    fun noCleartextTrafficIsPermitted() {
        for (f in scan().xmlFiles) {
            val xml = stripXmlComments(f.readText())
            assertTrue("${rel(f)} permits cleartext traffic", !Regex("""(?i)cleartextTrafficPermitted\s*=\s*"true"""").containsMatchIn(xml))
            assertTrue("${rel(f)} permits cleartext traffic", !Regex("""(?i)usesCleartextTraffic\s*=\s*"true"""").containsMatchIn(xml))
        }
    }

    @Test
    fun theBridgePeersAreTheCoreCanon() {
        val bridge = cArrayLiterals(File(repo, "native/src/main/jni/bridge/jni_peer.c"), "MAINNET_PRIORITY_PEER_IPS[] = {")
            .filter { IPV4_WHOLE.matches(it) }
        val canon = cArrayLiterals(File(coreDir, "BRPeerCanon.h"), "mainnet[BR_PEER_CANON_MAINNET_COUNT] = {")
            .filter { IPV4_WHOLE.matches(it) }
        assertTrue("no mainnet peers read from jni_peer.c: update the gate", bridge.size >= 10)
        assertEquals("jni_peer.c MAINNET_PRIORITY_PEER_IPS must equal the core's canon (BRPeerCanon.h)", canon, bridge)
    }

    /** The scan is not vacuous: it reaches the files and finds what is known to be there. */
    @Test
    fun theScanFindsTheKnownDestinations() {
        val s = scan()
        for (h in listOf("api.digiscope.me", "seed.digibyte.io", "134.199.198.90", "api.coingecko.com", "assets.digistamp.co")) {
            assertTrue("the scan no longer finds $h: the gate is not reading the sources it should", h in s.hosts)
        }
        assertTrue("the scan read too few files", s.fileCount > 200)
        for (root in listOf("app/src/main/java", "app/src/play/java", "app/src/sideload/java", "core/src/main/java", "game/src/main/java")) {
            assertTrue("the derived roots no longer include $root", root in s.codeRoots)
        }
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
            val e = "203.0.113.7"
            val f = "see node.example.com or localhost or 127.0.0.1 or 10.0.0.5"
            val g = "app.aroundtheblock.wallet"
            val h = "wallet-status.txt"
            val i = '"'
            val j = "after a char literal: k.me"
            val k = "seed.digibyte.nl"
            val l = "peer 198.51.100.4:12024 and 198.51.100.5 , 300.1.1.1"
            val m = "android.intent.action.VIEW"
            val n = "seed.node.cz:12024"
        """.trimIndent()
        val hosts = Lexer.lex(src, Lang.KOTLIN).literals.flatMap { hostsIn(it.text, wholeLiteral = true) }.toSet()
        assertEquals(
            setOf(
                "api.example-host.net", "seed.somewhere.tools", "203.0.113.7", "k.me", "seed.digibyte.nl",
                "198.51.100.4", "198.51.100.5", "seed.node.cz",
            ),
            hosts,
        )
    }

    @Test
    fun theCallSiteMatcherSeesEveryNetworkApi() {
        val apis = listOf(
            "client.newCall(req)", "client.newWebSocket(req, l)", "Socket(\"h\", 1)", "ServerSocket(0)",
            "factory.createSocket(s, h, 443, true)", "DatagramSocket()", "url.openConnection()",
            "x as HttpsURLConnection", "x as HttpURLConnection", "u.openStream()", "URL(s).readText()",
            "u.toURL()", "InetAddress.getByName(h)", "InetAddress.getAllByName(h)", "web.loadUrl(u)",
            "web.postUrl(u, b)", "AsyncImage(model = m)", "SubcomposeAsyncImage(model = m)",
            "rememberAsyncImagePainter(m)", "ImageLoader.Builder(c)", "context.imageLoader", "TorRuntime.Builder()",
            "Dns.SYSTEM.lookup(h)",
        )
        for (api in apis) {
            assertTrue("the call-site matcher misses `$api`", KOTLIN_CALLS.containsMatchIn(Lexer.lex(api, Lang.KOTLIN).code))
        }
        val code = Lexer.lex("val s = \"Socket(\" // Socket(\nval t = 1", Lang.KOTLIN).code
        assertTrue("a network API named in a comment or a string is not a call site", !KOTLIN_CALLS.containsMatchIn(code))
        for (c in listOf("connect(fd, a, n)", "socket(AF_INET, SOCK_STREAM, 0)", "getaddrinfo(h, 0, &x, &r)")) {
            assertTrue("the C matcher misses `$c`", C_CALLS.containsMatchIn(c))
        }
        assertTrue(!C_CALLS.containsMatchIn("BRPeerConnect(p); _peerConnected(p);"))
    }

    // ── Lexing ─────────────────────────────────────────────────────────────────────────────

    private enum class Lang { KOTLIN, C }

    private class Literal(val text: String, val at: Int)

    private class Lexed(val literals: List<Literal>, val code: String)

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
            val literals = ArrayList<Literal>()
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
                        literals.add(Literal(blankTemplates(src.substring(i + 3, end), raw = true), i))
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
                        literals.add(Literal(if (lang == Lang.KOTLIN) blankTemplates(text, raw = false) else text.replace("\\$", "$"), i))
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

    // ── Matching ───────────────────────────────────────────────────────────────────────────

    private companion object {
        /** Common top-level domains, matched anywhere in a literal ("see api.x.me for …"). */
        const val TLDS = "com|org|net|io|me|co|app|dev|link|tools|services|info|us|trade|blue|xyz|onion|cloud|" +
            "network|tech|ai|so|gg|tv|cc|eu|uk|de|ch|ly|to|sh|site|online|store|exchange|finance|money|cash|" +
            "digital|world|global|biz|pro|page|gov|edu"
        val HOST = Regex("(?<![\\w.-])((?:[a-z0-9-]+\\.)+(?:$TLDS))(?![\\w.-])", RegexOption.IGNORE_CASE)
        val URL = Regex("\\b(?:https?|wss?)://([^/\\s\"'?#:@)\\\\\u0000]+)", RegexOption.IGNORE_CASE)

        /**
         * A literal that IS a host name, with any alphabetic top-level domain ("seed.digibyte.nl",
         * "node.example.cz:12024"). Lower case only: identifiers such as "android.intent.action.VIEW"
         * are not hosts. A last label that is a file extension, or a first label that starts a
         * reverse-DNS identifier ("app.aroundtheblock.wallet"), is not a host either.
         */
        val WHOLE_HOST = Regex("^([a-z0-9-]+(?:\\.[a-z0-9-]+)+)(?::\\d{1,5})?$")
        val FILE_EXTENSIONS = (
            "kt kts java swift c h m mm cpp cc hpp json txt bin db sqlite xml plist png jpg jpeg gif webp svg ico pdf " +
                "md html htm js css log tmp dat bak zip gz tar so a dylib framework xcframework apk aab jar aar gradle " +
                "properties yml yaml toml csv tsv key pem crt der p12 jks keystore wav mp3 mp4 ttf otf strings xcstrings " +
                "storyboard xib lock ini cfg conf py rb proto aidl pro sh bat"
            ).split(' ').toSet()
        val REVERSE_DNS_ROOTS = "app com org net io android androidx java javax kotlin kotlinx dagger okhttp3 coil junit kern hw vm"
            .split(' ').toSet()

        /** An IPv4 address anywhere in a literal, with or without a port ("1.2.3.4:12024"). */
        val IPV4 = Regex("(?<![\\d.])((?:\\d{1,3}\\.){3}\\d{1,3})(?![\\d.])")
        val IPV4_WHOLE = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")

        /** Network APIs in Kotlin and Java: anything that opens a connection or resolves a name. */
        val KOTLIN_CALLS = Regex(
            listOf(
                "\\.newCall\\(", "\\.newWebSocket\\(", "\\bSocket\\(", "\\bServerSocket\\b", "\\.createSocket\\(",
                "\\bDatagramSocket\\b", "\\.openConnection\\(", "\\bHttpsURLConnection\\b", "\\bHttpURLConnection\\b",
                "\\.openStream\\(", "\\bURL\\(", "\\.toURL\\(", "InetAddress\\.(?:getByName|getAllByName)\\b",
                "\\.loadUrl\\(", "\\.postUrl\\(", "\\bAsyncImage\\(", "\\bSubcomposeAsyncImage\\b",
                "\\brememberAsyncImagePainter\\b", "\\bImageLoader\\b", "\\.imageLoader\\b", "\\bTorRuntime\\b",
                "\\bDns\\.SYSTEM\\b",
            ).joinToString("|"),
        )

        /** Network APIs in C. */
        val C_CALLS = Regex("\\b(?:getaddrinfo|gethostbyname|connect|socket)\\s*\\(")

        /** Source-set directories the gate knows; anything else under `src/<set>/` fails. */
        val CODE_DIRS = setOf("java", "kotlin")
        val KNOWN_DIRS = CODE_DIRS + setOf("res", "jni", "assets")

        fun isPrivateOrLocal(ip: String): Boolean {
            val o = ip.split('.').map { it.toInt() }
            return o[0] == 127 || o[0] == 10 || o[0] == 0 || (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254)
        }

        fun isIgnoredHost(host: String): Boolean =
            host == "localhost" || host.endsWith(".localhost") ||
                Regex("(^|\\.)example\\.(com|org|net)$").containsMatchIn(host)

        fun hostsIn(literal: String, wholeLiteral: Boolean): Set<String> {
            val out = HashSet<String>()
            for (m in URL.findAll(literal)) {
                val h = m.groupValues[1].lowercase()
                if ('.' in h && !IPV4_WHOLE.matches(h)) out.add(h)
            }
            for (m in HOST.findAll(literal)) out.add(m.groupValues[1].lowercase())
            if (wholeLiteral) {
                WHOLE_HOST.find(literal.trim())?.let { m ->
                    val labels = m.groupValues[1].split('.')
                    val last = labels.last()
                    if (last.length >= 2 && last.all { it in 'a'..'z' } && last !in FILE_EXTENSIONS && labels.first() !in REVERSE_DNS_ROOTS) {
                        out.add(m.groupValues[1])
                    }
                }
            }
            val hosts = out.filterNot(::isIgnoredHost).toMutableSet()
            for (m in IPV4.findAll(literal)) {
                val ip = m.groupValues[1]
                if (ip.split('.').all { it.toInt() <= 255 } && !isPrivateOrLocal(ip)) hosts.add(ip)
            }
            return hosts
        }

        fun stripXmlComments(xml: String) = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    // ── Scanning ───────────────────────────────────────────────────────────────────────────

    private class Scan(
        val hosts: Map<String, Set<String>>,
        val callSites: Set<String>,
        val xmlFiles: List<File>,
        val codeRoots: Set<String>,
        val fileCount: Int,
    )

    private fun requireDir(path: File): File {
        if (!path.isDirectory) {
            fail("source gate: ${path.path} is missing (for the core: git submodule update --init); update the gate if it moved")
        }
        return path
    }

    private fun rel(f: File): String = f.canonicalFile.relativeTo(repo).invariantSeparatorsPath

    /** The modules `settings.gradle.kts` includes. */
    private fun modules(): List<String> {
        val settings = File(repo, "settings.gradle.kts")
        assertTrue("source gate: settings.gradle.kts is missing", settings.isFile)
        val code = Lexer.lex(settings.readText(), Lang.KOTLIN)
        val includes = Regex("""\binclude\s*\(""").findAll(code.code).toList()
        assertTrue("source gate: settings.gradle.kts includes no modules: update the gate", includes.isNotEmpty())
        val modules = code.literals.filter { lit -> includes.any { lit.at > it.range.first && lit.at < code.code.indexOf(')', it.range.last) } }
            .map { it.text.removePrefix(":").replace(':', '/') }
        assertTrue("source gate: no modules read from settings.gradle.kts", modules.isNotEmpty())
        return modules
    }

    /** Every shipped (non-test) source set of every module: `<module>/src/<set>`. */
    private fun sourceSets(): List<File> = modules().flatMap { module ->
        val src = requireDir(File(repo, "$module/src"))
        (src.listFiles() ?: emptyArray()).filter { it.isDirectory }
            .filterNot { it.name.startsWith("test") || it.name.startsWith("androidTest") }
    }

    private fun scan(): Scan {
        val kotlin = ArrayList<File>()
        val c = ArrayList<File>()
        val values = ArrayList<File>()
        val xml = ArrayList<File>()
        val codeRoots = HashSet<String>()
        for (set in sourceSets()) {
            for (child in set.listFiles() ?: emptyArray()) {
                if (child.isFile) {
                    if (child.name == "AndroidManifest.xml") xml.add(child)
                    continue
                }
                if (child.name !in KNOWN_DIRS) {
                    fail("source gate: ${rel(child)} is a source-set directory the gate does not scan; teach the gate about it")
                }
                when (child.name) {
                    in CODE_DIRS -> {
                        codeRoots.add(rel(child))
                        kotlin += child.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                    }
                    "res" -> {
                        File(child, "values").listFiles()?.filter { it.extension == "xml" }?.let { values += it }
                        File(child, "xml").listFiles()?.filter { it.extension == "xml" }?.let { xml += it }
                    }
                    "jni" -> {
                        // The bridge, and the core submodule's shipped sources (its top level, no tests).
                        c += requireDir(File(child, "bridge")).walkTopDown().filter { it.isFile && (it.extension == "c" || it.extension == "h") }
                        val core = (requireDir(coreDir).listFiles() ?: emptyArray())
                            .filter { it.isFile && (it.extension == "c" || it.extension == "h") }
                            .filterNot { it.name.contains("Test") || it.name == "test.c" }
                        if (core.none { it.name == "BRChainParams.h" }) fail("source gate: the core submodule is not checked out at ${coreDir.path}")
                        c += core
                    }
                    "assets" -> Unit   // word lists, fonts and animations: data, not code
                }
            }
        }

        val hosts = HashMap<String, MutableSet<String>>()
        val calls = HashSet<String>()
        fun add(found: Set<String>, f: File) { for (h in found) hosts.getOrPut(h) { HashSet() }.add(rel(f)) }
        for (f in kotlin) {
            val lexed = Lexer.lex(f.readText(), Lang.KOTLIN)
            for (lit in lexed.literals) add(hostsIn(lit.text, true), f)
            if (KOTLIN_CALLS.containsMatchIn(lexed.code)) calls.add(rel(f))
        }
        for (f in c) {
            val lexed = Lexer.lex(f.readText(), Lang.C)
            for (lit in lexed.literals) add(hostsIn(lit.text, true), f)
            if (C_CALLS.containsMatchIn(lexed.code)) calls.add(rel(f))
        }
        val body = Regex("<(string|item)\\b[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
        for (f in values) {
            for (m in body.findAll(stripXmlComments(f.readText()))) add(hostsIn(m.groupValues[2], false), f)
        }
        // res/xml and manifests: every attribute value and text node (a <domain-config> names its host).
        for (f in xml) {
            val text = stripXmlComments(f.readText()).replace(Regex("""\sxmlns(:\w+)?\s*=\s*"[^"]*""""), "")
            for (m in Regex("""=\s*"([^"]*)"""").findAll(text)) add(hostsIn(m.groupValues[1], true), f)
            for (m in Regex(""">([^<]+)<""").findAll(text)) add(hostsIn(m.groupValues[1].trim(), true), f)
        }
        return Scan(hosts, calls, xml, codeRoots, kotlin.size + c.size + values.size + xml.size)
    }

    private class Row(val entries: List<String>, val code: String)

    /** The rows of the table between `gate:<name>` markers: the backticked entries of the first column, and the Code column. */
    private fun table(name: String): List<Row> {
        assertTrue("source gate: ${doc.path} is missing", doc.isFile)
        val text = doc.readText()
        val begin = text.indexOf("<!-- gate:$name:begin -->")
        val end = text.indexOf("<!-- gate:$name:end -->")
        if (begin < 0 || end < begin) fail("source gate: no gate:$name markers in docs/network-destinations.md")
        val rows = text.substring(begin, end).lines()
            .filter { it.startsWith("|") && !it.startsWith("|---") }
            .drop(1)   // the header row
            .map { row ->
                val cells = row.split('|')
                Row(Regex("`([^`]+)`").findAll(cells[1]).map { it.groupValues[1].trim() }.toList(), cells.getOrElse(2) { "" })
            }
        assertTrue("source gate: the gate:$name table is empty", rows.any { it.entries.isNotEmpty() })
        return rows
    }

    /** The string literals of the C array that starts at [opening]. */
    private fun cArrayLiterals(file: File, opening: String): List<String> {
        val raw = file.readText()
        val start = raw.indexOf(opening)
        val end = if (start < 0) -1 else raw.indexOf("};", start)
        if (start < 0 || end < 0) fail("source gate: `$opening` not found in ${rel(file)}; update the gate")
        // Inside #ifdef red-arm seams too: only the literals, as the compiler would see them in a shipped build.
        val body = raw.substring(start, end).replace(Regex("""#ifdef\s+\w+.*?#else""", RegexOption.DOT_MATCHES_ALL), "")
        return Lexer.lex(body, Lang.C).literals.map { it.text }
    }
}
