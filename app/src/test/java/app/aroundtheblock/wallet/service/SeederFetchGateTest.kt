package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.core.network.DigiScopePins
import app.aroundtheblock.wallet.ui.KotlinSourceGate
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: every request the wallet makes to the seeder goes through [SeederClient] — the
 * DigiScope-pinned client with a bounded body — and no component reads the seeder over a client
 * of its own. The seeder list decides which nodes the wallet asks for headers and filters, so it is
 * fetched with the same server-identity check as every other DigiScope call.
 *
 * A gate because SyncService and SyncWorker are Android components this JVM cannot run; what
 * SeederClient does with a request is covered by [SeederClientTest].
 */
class SeederFetchGateTest {

    private val appRoot = File("src/main/java/app/aroundtheblock/wallet")
    private fun gate(rel: String) = KotlinSourceGate.of(File(appRoot, rel).readText())

    /** The body of the first function named [name], braces matched. */
    private fun body(source: KotlinSourceGate, name: String): IntRange {
        val declared = Regex("""fun\s+$name\s*\(""").find(source.code) ?: error("no fun $name")
        val open = source.code.indexOf('{', declared.range.last)
        return source.blockAt(open) ?: error("unbalanced body of $name")
    }

    /** The one seeder URL a file names, as written. */
    private fun seederUrl(source: KotlinSourceGate): String {
        val literals = Regex("""SEEDER_URL\s*=\s*"([^"]+)"""").findAll(source.written).map { it.groupValues[1] }.toList()
        assertEquals("expected one SEEDER_URL constant", 1, literals.size)
        return literals.single()
    }

    @Test fun `the sync service fetches the seeder list through the seeder client only`() {
        val sync = gate("service/SyncService.kt")
        val fetch = body(sync, "fetchFromSeeder")

        assertEquals("fetchFromSeeder does not fetch through the seeder client", 1, sync.calls("seederClient.fetch", fetch).size)
        assertTrue("fetchFromSeeder still reads the body on its own", sync.calls("string", fetch).isEmpty())
        assertTrue("SyncService still calls the seeder over the shared, unpinned client", sync.calls("okHttpClient.newCall").isEmpty())
        assertEquals("the fetched body does not reach the parser", 1, sync.calls("parsePeersJson", fetch).size)

        val derived = sync.calls("SeederClient")
        assertEquals("SyncService must derive exactly one seeder client", 1, derived.size)
        assertEquals("the seeder client is not derived from the injected client", listOf("okHttpClient"), derived.single().arguments)
    }

    @Test fun `the background worker fetches the seeder list the same way`() {
        val worker = gate("service/SyncWorker.kt")
        val fetch = body(worker, "fetchBloomPeers")

        assertTrue("SyncWorker still opens its own connection to the seeder", worker.calls("openConnection").isEmpty())
        assertTrue("SyncWorker still reads the seeder body without a bound", worker.calls("readText").isEmpty())
        assertEquals("fetchBloomPeers does not fetch through the seeder client", 1, worker.calls("fetch", fetch).size)

        val derived = worker.calls("SeederClient")
        assertEquals("SyncWorker must derive exactly one seeder client", 1, derived.size)
        assertEquals("the seeder client is not derived from the injected client", listOf("okHttpClient"), derived.single().arguments)
    }

    @Test fun `neither file reaches the seeder any other way`() {
        for (file in listOf("service/SyncService.kt", "service/SyncWorker.kt")) {
            val source = gate(file)
            for (callee in listOf("okHttpClient.newCall", "openConnection", "readText", "string")) {
                assertTrue("$file still calls $callee", source.calls(callee).isEmpty())
            }
        }
    }

    @Test fun `both seeder URLs name the pinned host`() {
        // A CertificatePinner checks only the hosts it holds pins for; a seeder URL on any other
        // host would be served with no identity check at all.
        for (file in listOf("service/SyncService.kt", "service/SyncWorker.kt")) {
            val url = seederUrl(gate(file)).toHttpUrl()
            assertEquals("$file names a seeder host the pins do not cover", DigiScopePins.HOST, url.host)
            assertEquals("$file names a seeder URL that is not https", "https", url.scheme)
        }
    }
}
