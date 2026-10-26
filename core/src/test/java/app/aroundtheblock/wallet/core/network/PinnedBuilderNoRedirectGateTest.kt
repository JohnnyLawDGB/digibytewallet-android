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
}
