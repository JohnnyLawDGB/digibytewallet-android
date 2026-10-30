package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seeder is read only in the background, never between a start path and `startSync()`.
 *
 * Every SyncService start path (the cold start, the zero-peer watchdog, network regained, the
 * Tor fallback, the recreate sequence) runs `injectPeers()` and then `startSync()`. Those
 * inject functions used to fetch the seeder list themselves whenever the cache was an hour old,
 * so the first P2P dial waited on an HTTPS request; iOS adds the cached list and fetches in the
 * background (`WalletCore.addSeederPeers`). This gate pins Android to the same order: the inject
 * functions read the cache and call `requestSeederRefresh()`, and the only seeder fetches are in
 * `refreshSeederPools()`, which runs on a launched coroutine. SyncWorker fetches only after its
 * own `startSync()`.
 */
class SeederRefreshOrderGateTest {

    private val appRoot = File("src/main/java/app/aroundtheblock/wallet")
    private fun gate(rel: String) = KotlinSourceGate.of(File(appRoot, rel).readText())

    private fun body(source: KotlinSourceGate, name: String): IntRange {
        val declared = Regex("""fun\s+$name\s*\(""").find(source.code) ?: error("no fun $name: update the gate")
        val open = source.code.indexOf('{', declared.range.last)
        return source.blockAt(open) ?: error("unbalanced body of $name")
    }

    @Test fun `the inject functions read the cache and never the seeder`() {
        val sync = gate("service/SyncService.kt")
        for (name in listOf("injectPeers", "injectFilterPeers", "injectDandelionPeers")) {
            val b = body(sync, name)
            assertTrue("$name fetches the seeder on the start path", sync.calls("fetchFromSeeder", b).isEmpty())
            assertTrue("$name reads the seeder client on the start path", sync.calls("seederClient.fetch", b).isEmpty())
        }
        for (name in listOf("injectFilterPeers", "injectDandelionPeers")) {
            assertEquals("$name must ask for a background refresh", 1, sync.calls("requestSeederRefresh", body(sync, name)).size)
        }
        // injectPeers' own (general) pool asks too; the two it calls ask for theirs.
        assertTrue(sync.calls("requestSeederRefresh", body(sync, "injectPeers")).isNotEmpty())
    }

    @Test fun `the only seeder fetches run in the background refresh`() {
        val sync = gate("service/SyncService.kt")
        val refresh = body(sync, "refreshSeederPools")
        val fetches = sync.calls("fetchFromSeeder")
        assertTrue("no seeder fetch found: update the gate", fetches.isNotEmpty())
        for (f in fetches) {
            assertTrue("fetchFromSeeder is called outside refreshSeederPools", f.range.first in refresh)
        }
        // refreshSeederPools is reached only from the coroutine requestSeederRefresh launches.
        val calls = sync.calls("refreshSeederPools")
        assertEquals("refreshSeederPools must have exactly one caller", 1, calls.size)
        val request = body(sync, "requestSeederRefresh")
        val launches = Regex("""recoveryScope\s*\.\s*launch\s*\{""").findAll(sync.code)
            .filter { it.range.first in request }.toList()
        assertEquals("requestSeederRefresh must launch the refresh on recoveryScope", 1, launches.size)
        val launched = sync.blockAt(launches.single().range.last)
        assertTrue("refreshSeederPools is not inside the launched block", launched != null && calls.single().range.first in launched)
        // And it is guarded: one at a time, with a back-off.
        assertEquals(1, sync.calls("SeederRefreshPolicy.mayAttempt", request).size)
        assertEquals(1, sync.calls("seederRefreshInFlight.compareAndSet", request).size)
    }

    @Test fun `the background worker fetches only after its startSync`() {
        val worker = gate("service/SyncWorker.kt")
        val doWork = body(worker, "doWork")
        val start = worker.calls("NativeBridge.startSync", doWork)
        assertEquals("doWork must start sync once", 1, start.size)
        val fetches = worker.calls("fetchBloomPeers", doWork)
        assertEquals("doWork must refresh the seeder list once", 1, fetches.size)
        assertTrue("SyncWorker fetches the seeder before startSync()", fetches.single().range.first > start.single().range.first)
        val cached = worker.calls("injectCachedPeers", doWork)
        assertEquals(1, cached.size)
        assertTrue("the cached peers must go in before startSync()", cached.single().range.first < start.single().range.first)
    }
}
