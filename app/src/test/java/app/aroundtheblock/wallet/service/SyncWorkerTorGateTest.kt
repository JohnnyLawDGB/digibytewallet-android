package app.aroundtheblock.wallet.service

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate: the background worker starts the peer manager with the user's Tor setting on only
 * after it has set the SOCKS proxy for the native layer, and otherwise starts nothing and retries.
 *
 * The worker can run in a process where SyncService never ran, so nothing else has set the proxy
 * there. A gate because SyncWorker is an Android component this JVM cannot run; what the route
 * decides is covered by [WorkerPeerRouteTest].
 */
class SyncWorkerTorGateTest {

    private val worker = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/service/SyncWorker.kt").readText())

    private fun doWork(): IntRange {
        val declared = Regex("""fun\s+doWork\s*\(""").find(worker.code) ?: error("no fun doWork")
        val open = worker.code.indexOf('{', declared.range.last)
        return worker.blockAt(open) ?: error("unbalanced body of doWork")
    }

    @Test fun `the worker decides the peer route from the Tor setting and its SOCKS port`() {
        val routes = worker.calls("WorkerPeerRoute.of", doWork())
        assertEquals("doWork does not decide a peer route", 1, routes.size)
        assertEquals(
            "the route is not decided from the Tor setting and the live SOCKS port",
            listOf("torEnabled = torManager.isEnabled", "socksPort = torManager.getSocksPort()"),
            routes.single().arguments,
        )
    }

    @Test fun `with Tor on and no SOCKS port the worker retries before it starts anything`() {
        val body = doWork()
        val route = worker.calls("WorkerPeerRoute.of", body).singleOrNull() ?: error("no route decided")
        val waits = worker.branchesOn("route is WorkerPeerRoute.Wait", body)
        assertEquals("no branch on the Wait route", 1, waits.size)
        val wait = waits.single()
        assertTrue("the Wait branch does not retry", worker.code.substring(wait).contains("return Result.retry()"))
        val start = worker.calls("NativeBridge.startSync", body)
        assertEquals("expected one startSync in doWork", 1, start.size)
        assertTrue("the Wait branch comes before the route is decided", route.range.first < wait.first)
        assertTrue("startSync comes before the Wait branch", wait.last < start.single().range.first)
        val fetch = worker.calls("fetchBloomPeers", body)
        assertTrue("the seeder is asked before the Wait branch", fetch.all { it.range.first > wait.last })
    }

    @Test fun `the SOCKS proxy is set for the native layer before the peer manager starts`() {
        val body = doWork()
        val start = worker.calls("NativeBridge.startSync", body).single()
        val sets = worker.calls("NativeBridge.setSocksProxy", body)
        assertEquals("doWork does not set the SOCKS proxy", 1, sets.size)
        val set = sets.single()
        assertEquals(listOf("\"127.0.0.1\"", "route.port"), set.arguments)
        assertTrue("the proxy is set after startSync", set.range.last < start.range.first)
        val branch = worker.branchesOn("route is WorkerPeerRoute.ViaSocks", body)
        assertEquals("the proxy is not set on the ViaSocks route", 1, branch.size)
        assertTrue("setSocksProxy is not inside the ViaSocks branch", set.range.first in branch.single())
    }
}
