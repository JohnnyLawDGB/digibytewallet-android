package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: a seeder peer's `services_hex` is read by the core (BRSeederServicesParse in
 * BRSeederPeers.h, NativeBridge.seederServicesParse), and a peer whose services are unknown is
 * tagged and added by the core (BRSeederPeerServices, BRSeederPeerAddress, BRSeederPeerAdd in
 * injectPeerByIp), which is what iOS calls too. Until this moved, PeerServicesPolicy.kt
 * (parseSeederServicesHex) was a second parse that read more than the core does (a sign, leading
 * zeros past 16 digits, a second "0X", Unicode digits and space), and jni_peer.c kept its own
 * default (INJECT_DEFAULT_SERVICES). The rule's values, including every case of the deleted
 * PeerServicesPolicyTest, are checked by the host KAT, seeder_peers_kat, whose red arms
 * SEEDER_PEERS_RED and SEEDER_SERVICES_RED fail them.
 */
class SeederServicesCoreRuleGateTest {

    private val repo = File("..").canonicalFile
    private val appRoot = File("src/main/java/app/aroundtheblock/wallet")
    private val sync = KotlinSourceGate.of(File(appRoot, "service/SyncService.kt").readText())
    private val worker = KotlinSourceGate.of(File(appRoot, "service/SyncWorker.kt").readText())
    private val rule = "NativeBridge.seederServicesParse"

    private fun body(source: KotlinSourceGate, name: String): IntRange {
        val declared = Regex("""fun\s+$name\s*\(""").find(source.code) ?: error("no fun $name: update the gate")
        val open = source.code.indexOf('{', declared.range.last)
        return source.blockAt(open) ?: error("unbalanced body of $name")
    }

    @Test fun `no Kotlin copy of the services parse is left`() {
        assertTrue("PeerServicesPolicy.kt is back", !File(appRoot, "service/PeerServicesPolicy.kt").exists())
        val kotlin = listOf(File("src/main/java"), File(repo, "core/src/main/java"))
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue("no Kotlin sources found: the gate reads the wrong directory", kotlin.size > 50)
        for (f in kotlin) {
            val src = KotlinSourceGate.of(f.readText())
            assertTrue("${f.name} declares or calls parseSeederServicesHex",
                !Regex("""\bparseSeederServicesHex\b""").containsMatchIn(src.code))
        }
        // The old copy's steps, in the two files that read the seeder: a hex Long parse or a "0x" strip.
        for ((name, src) in listOf("SyncService" to sync, "SyncWorker" to worker)) {
            assertTrue("$name parses hex into a Long itself", !Regex(
                """\.\s*toLong(OrNull)?\s*\(\s*(radix\s*=\s*)?16\s*\)|\bparseLong\s*\(|\bvalueOf\s*\([^)]*,\s*16\s*\)"""
            ).containsMatchIn(src.code))
            assertTrue("$name strips a \"0x\" itself", !Regex(
                """removePrefix\s*\(\s*"0[xX]"|removeRange|substring\s*\(\s*2\s*\)"""
            ).containsMatchIn(src.written))
        }
    }

    @Test fun `every services_hex read goes to the core`() {
        for ((name, src, owners) in listOf(
            Triple("SyncService", sync, listOf("parsePeersJson")),
            Triple("SyncWorker", worker, listOf("injectPeersJson")),
        )) {
            val parses = src.calls(rule)
            val reads = Regex(""""services_hex"""").findAll(src.written).map { it.range.first }.toList()
            assertTrue("$name no longer reads services_hex: update the gate", reads.isNotEmpty())
            for (at in reads) {
                assertTrue("$name reads services_hex outside $rule", parses.any { at in it.range })
            }
            for (owner in owners) {
                val inBody = src.calls(rule, body(src, owner))
                assertEquals("$owner must read services_hex through $rule once", 1, inBody.size)
                assertEquals("$owner passes something else to $rule",
                    listOf(""".optString("services_hex", "")"""),
                    inBody.single().arguments.map { it.substringAfter(it.substringBefore(".optString")) })
            }
        }
    }

    @Test fun `the cached pool's services are read back by the core`() {
        // serializePool writes services.toString(16); an older build may have written a value the core
        // does not read (a negative one), which must come back as 0, not as every service bit.
        val b = body(sync, "parsePool")
        val reads = sync.calls(rule, b)
        assertEquals("parsePool must read the stored services through $rule", 1, reads.size)
        assertEquals("parts[2]", reads.single().arguments.single())
    }

    /** [text] with C comments and the insides of string and char literals blanked, same length. */
    private fun cCode(text: String): String {
        val out = StringBuilder(text)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("/*", i) -> { val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    for (k in i until end) if (out[k] != '\n') out[k] = ' '; i = end }
                text.startsWith("//", i) -> { val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    for (k in i until end) out[k] = ' '; i = end }
                text[i] == '"' || text[i] == '\'' -> { val q = text[i]; var k = i + 1
                    while (k < text.length && text[k] != q) { if (text[k] == '\\') { out[k] = ' '; k++ }; if (k < text.length) out[k] = ' '; k++ }
                    i = k + 1 }
                else -> i++
            }
        }
        return out.toString()
    }

    @Test fun `injectPeerByIp tags and adds by the core's seeder rule`() {
        val raw = File(repo, "native/src/main/jni/bridge/jni_peer.c").readText()
        val c = cCode(raw)
        assertTrue("jni_peer.c has its own unknown-services default again",
            !Regex("""\bINJECT_DEFAULT_SERVICES\b""").containsMatchIn(c))
        // The include's name is a literal, which cCode blanks: read it from the file as written.
        assertTrue("jni_peer.c does not include BRSeederPeers.h",
            Regex("""^[ \t]*#include\s+"BRSeederPeers\.h"""", RegexOption.MULTILINE).containsMatchIn(raw))
        val start = Regex("""\bNativeBridge_injectPeerByIp\s*\(""").find(c) ?: error("no NativeBridge_injectPeerByIp: update the gate")
        val open = c.indexOf('{', start.range.last)
        var depth = 0
        var close = -1
        for (k in open until c.length) {
            if (c[k] == '{') depth++
            if (c[k] == '}' && --depth == 0) { close = k; break }
        }
        assertTrue("unbalanced body of NativeBridge_injectPeerByIp", close > open)
        val fn = c.substring(open, close + 1)
        fun count(name: String) = Regex("""\b$name\s*\(""").findAll(fn).count()
        assertEquals("the services must be the core's BRSeederPeerServices", 1, count("BRSeederPeerServices"))
        assertTrue("the services are not BRSeederPeerServices' answer",
            Regex("""uint64_t\s+services\s*=\s*BRSeederPeerServices\s*\(\s*\(uint64_t\)\s*servicesHex\s*\)\s*;""").containsMatchIn(fn))
        assertEquals("the address must be the core's BRSeederPeerAddress", 1, count("BRSeederPeerAddress"))
        assertEquals("the live add must be the core's BRSeederPeerAdd", 1, count("BRSeederPeerAdd"))
        for (own in listOf("BRPeerManagerAddPeer", "inet_pton", "getaddrinfo", "_resolveHostToAddr", "_injectPriorityPeer")) {
            assertEquals("injectPeerByIp tags or adds a peer itself ($own)", 0, count(own))
        }
    }
}
