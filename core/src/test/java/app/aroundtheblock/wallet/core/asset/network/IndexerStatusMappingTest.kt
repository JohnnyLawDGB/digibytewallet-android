package app.aroundtheblock.wallet.core.asset.network

import android.util.Log
import app.aroundtheblock.wallet.core.asset.send.StackEntry
import app.aroundtheblock.wallet.core.asset.send.StackLookup
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * How every status of the digiscope DigiAsset routes reads, with the bodies the server team
 * recorded (hand-off 2026-11-21) and the ones the live routes answered that day.
 *
 * `GET /digiassets/txout/:txid/:vout` has five distinct readings: the stack, "not unspent",
 * "the index cannot say yet", "this request is refused", and "no answer". The third is the one
 * that must never collapse into the first two: a syncing index is not an empty stack and not a
 * spent output.
 *
 * `GET /digiassets/asset/:ref` answers 400 for a reference it does not take. That is one request,
 * is not a failure of the endpoint, and a reference of the wrong shape is never sent.
 *
 * Served by a minimal HTTP/1.1 server on the loopback interface (plain HTTP, so the DigiScope
 * certificate pins, which name only DigiScope hosts, do not apply).
 */
class IndexerStatusMappingTest {

    private val txid = "ab".repeat(32)
    private val assetId = "La4WAqZfAwtxbZxBSuNoxptactZcbXfZdq6kMo"
    private val refusal = """{"error":"Use a numeric asset index or a DigiAsset ID (starts with L or U)."}"""

    private lateinit var server: ServerSocket
    private val requested = java.util.Collections.synchronizedList(ArrayList<String>())

    /** Serves [routes] (path -> (status line, body)) until closed; any other path is a bare 404. */
    private fun serve(routes: Map<String, Pair<String, String>>) {
        server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s: Socket = try { server.accept() } catch (e: Exception) { break }
                s.use { sock ->
                    val reader = sock.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return@use
                    while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                    requested += path
                    val (status, body) = routes[path] ?: ("404 Not Found" to "{}")
                    val bytes = body.toByteArray()
                    sock.getOutputStream().apply {
                        write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
    }

    private val base get() = "http://127.0.0.1:${server.localPort}"
    private fun digiscope() = DigiScopeAssetClient(baseClient = OkHttpClient(), baseUrl = base)

    /** What the client reads when the txout route answers [status] with [body]. */
    private fun txout(status: String, body: String): StackLookup = runBlocking {
        requested.clear()
        serve(mapOf("/digiassets/txout/$txid/1" to (status to body)))
        try { digiscope().stackOf(txid, 1) } finally { server.close() }
    }

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
    }

    @After fun tearDown() {
        runCatching { server.close() }
        unmockkStatic(Log::class)
    }

    // ── txout: the parser ────────────────────────────────────────────────────────────────────

    @Test fun a_syncing_503_is_cannot_say_yet_with_the_servers_own_figures() {
        assertEquals(StackLookup.NotYet(indexSyncing = true, blocksBehind = 23_871),
            DigiScopeAssetParsing.notReady("""{"syncing":true,"blocksBehind":23871}"""))
        assertEquals(StackLookup.NotYet(indexSyncing = true, blocksBehind = 3, confirmations = 1),
            DigiScopeAssetParsing.notReady("""{"syncing":true,"blocksBehind":3,"confirmations":1}"""))
        // An output newer than the index's tip, when the reply does not call the whole index syncing.
        assertEquals(StackLookup.NotYet(indexSyncing = false, confirmations = 0),
            DigiScopeAssetParsing.notReady("""{"confirmations":0}"""))
        assertEquals(StackLookup.NotYet(indexSyncing = false, confirmations = 2),
            DigiScopeAssetParsing.notReady("""{"syncing":false,"confirmations":2}"""))
        // The route's body when it has no sync state to read at all.
        assertEquals(StackLookup.NotYet(indexSyncing = true, blocksBehind = null, confirmations = null),
            DigiScopeAssetParsing.notReady("""{"syncing":true,"blocksBehind":null}"""))
        // Figures that are not whole and non-negative are dropped, not guessed.
        assertEquals(StackLookup.NotYet(indexSyncing = true),
            DigiScopeAssetParsing.notReady("""{"syncing":true,"blocksBehind":-4,"confirmations":"7"}"""))
    }

    @Test fun any_other_503_is_no_answer() {
        val notTheEndpoints = listOf(
            null, "", "<html>503 Service Temporarily Unavailable</html>", "[]",
            """{}""", """{"syncing":false}""", """{"syncing":"true"}""", """{"syncing":1}""",
            """{"error":"DigiAsset RPC error"}""", """{"error":"busy","syncing":true}""",
            """{"confirmations":-1}""", """{"confirmations":1.5}""",
        )
        for (b in notTheEndpoints) assertEquals("$b", StackLookup.Unavailable, DigiScopeAssetParsing.notReady(b))
    }

    @Test fun a_400_is_a_refusal_only_with_the_endpoints_own_error_body() {
        assertTrue(DigiScopeAssetParsing.isRefusal("""{"error":"invalid txid or vout"}"""))
        assertTrue(DigiScopeAssetParsing.isRefusal(refusal))
        for (b in listOf(null, "", "<html>400 Bad Request</html>", "{}", """{"syncing":true}""")) {
            assertFalse("$b", DigiScopeAssetParsing.isRefusal(b))
        }
    }

    // ── txout: the client, one canned response per shape in the hand-off ─────────────────────

    @Test fun every_txout_status_has_its_own_reading() {
        // 200 { txid, vout, confirmations, assets: [...] }
        assertEquals(
            StackLookup.Found(listOf(StackEntry(assetId, 100))),
            txout("200 OK", """{"txid":"$txid","vout":1,"confirmations":12,
                "assets":[{"assetId":"$assetId","assetIndex":5263,"count":100,"decimals":0}]}"""),
        )
        assertEquals(StackLookup.Found(emptyList()),
            txout("200 OK", """{"txid":"$txid","vout":1,"confirmations":12,"assets":[]}"""))
        // 404 { unspent: false }
        assertEquals(StackLookup.NotUnspent, txout("404 Not Found", """{"unspent":false}"""))
        // 503 { syncing: true, blocksBehind, confirmations? }
        assertEquals(StackLookup.NotYet(true, 23_871, null),
            txout("503 Service Unavailable", """{"syncing":true,"blocksBehind":23871}"""))
        assertEquals(StackLookup.NotYet(true, 2, 1),
            txout("503 Service Unavailable", """{"syncing":true,"blocksBehind":2,"confirmations":1}"""))
        assertEquals(StackLookup.NotYet(true, null, null),
            txout("503 Service Unavailable", """{"syncing":true,"blocksBehind":null}"""))
        // 400 { error }
        assertEquals(StackLookup.Refused, txout("400 Bad Request", """{"error":"invalid txid or vout"}"""))
        // Failures.
        assertEquals(StackLookup.Unavailable, txout("503 Service Unavailable", "<html>upstream</html>"))
        assertEquals(StackLookup.Unavailable, txout("400 Bad Request", "<html>bad request</html>"))
        assertEquals(StackLookup.Unavailable, txout("500 Internal Server Error", """{"error":"DigiAsset RPC error"}"""))
        assertEquals(StackLookup.Unavailable, txout("502 Bad Gateway", ""))
    }

    /** The two readings a syncing index must never be given. */
    @Test fun a_syncing_index_is_neither_an_empty_stack_nor_a_spent_output() {
        for (body in listOf(
            """{"syncing":true,"blocksBehind":23871}""",
            """{"syncing":true,"blocksBehind":23871,"confirmations":40}""",
            // Even a body that also looks like a 200 or a 404 answer.
            """{"syncing":true,"blocksBehind":9,"txid":"$txid","vout":1,"assets":[]}""",
            """{"syncing":true,"blocksBehind":9,"unspent":false}""",
        )) {
            val read = txout("503 Service Unavailable", body)
            assertTrue("$body read as $read", read is StackLookup.NotYet)
        }
    }

    @Test fun each_txout_answer_is_one_request() {
        for ((status, body) in listOf(
            "503 Service Unavailable" to """{"syncing":true,"blocksBehind":5000}""",
            "400 Bad Request" to """{"error":"invalid txid or vout"}""",
            "404 Not Found" to """{"unspent":false}""",
        )) {
            txout(status, body)
            assertEquals(status, listOf("/digiassets/txout/$txid/1"), requested.toList())
        }
    }

    @Test fun an_outpoint_the_route_would_refuse_is_not_sent() = runBlocking {
        serve(emptyMap())
        val client = digiscope()
        assertEquals(StackLookup.Refused, client.stackOf("xyz", 0))
        assertEquals(StackLookup.Refused, client.stackOf(txid, -1))
        assertEquals(StackLookup.Refused, client.stackOf("unresolved:$txid", 0))
        assertEquals(StackLookup.Refused, client.stackOf("$txid/../asset", 0))
        assertEquals("nothing was asked", emptyList<String>(), requested.toList())
    }

    // ── asset/:ref ───────────────────────────────────────────────────────────────────────────

    @Test fun the_reference_shapes_the_route_takes() {
        for (ok in listOf("0", "5263", "1234567890", assetId, "Ua" + "1".repeat(36), "Lh" + "z".repeat(36))) {
            assertTrue(ok, DigiScopeAssetRef.isValid(ok))
        }
        for (bad in listOf(
            "", "null", "12345678901", "-1", "5263 ", "unresolved:$txid", txid,
            assetId.dropLast(1), assetId + "o", "Xa" + assetId.drop(2),
            assetId.dropLast(1) + "0", assetId.dropLast(1) + "l", assetId.dropLast(1) + "/",
        )) {
            assertFalse(bad, DigiScopeAssetRef.isValid(bad))
        }
    }

    @Test fun a_400_on_an_asset_reference_is_one_request_and_reads_as_refused() = runBlocking {
        serve(mapOf("/digiassets/asset/$assetId" to ("400 Bad Request" to refusal)))
        val client = digiscope()
        assertEquals(AssetDataLookup.Refused, client.lookupAssetData(assetId))
        assertEquals("asked once, not again", listOf("/digiassets/asset/$assetId"), requested.toList())
        // The older entry point reads the same refusal as "nothing", and makes one request too.
        requested.clear()
        assertNull(client.getAssetData(assetId))
        assertEquals(1, requested.size)
    }

    @Test fun an_asset_reference_the_route_would_refuse_is_not_sent() = runBlocking {
        serve(emptyMap())
        val client = digiscope()
        for (bad in listOf("unresolved:$txid", "null", "", assetId.dropLast(1), "12345678901")) {
            assertEquals(bad, AssetDataLookup.Refused, client.lookupAssetData(bad))
            assertNull(bad, client.getAssetData(bad))
        }
        assertEquals("nothing was asked", emptyList<String>(), requested.toList())
    }

    @Test fun an_asset_id_is_answered_and_other_statuses_are_no_answer() = runBlocking {
        val body = """{"assetId":"$assetId","assetIndex":5263,"cid":"","count":100,"decimals":0,
            "issuer":{"address":"DQcps2qoVVPTr94ibSzZgoMsZw24UgUCCe"},"psp":[]}"""
        serve(mapOf(
            "/digiassets/asset/$assetId" to ("200 OK" to body),
            "/digiassets/asset/5263" to ("500 Internal Server Error" to """{"error":"DigiAsset RPC getassetdata error"}"""),
            "/digiassets/asset/77" to ("400 Bad Request" to "<html>bad request</html>"),
        ))
        val client = digiscope()
        val found = client.lookupAssetData(assetId) as AssetDataLookup.Found
        assertEquals(assetId, found.data.assetId)
        assertEquals(100L, found.data.count)
        assertNull("an empty cid is no cid", found.data.cid)
        assertNull("no rules object in the answer", found.data.rules)
        assertEquals(AssetDataLookup.NoAnswer, client.lookupAssetData("5263"))
        assertEquals("a 400 that is not the route's own is not a refusal", AssetDataLookup.NoAnswer, client.lookupAssetData("77"))
    }

    /**
     * A refusal is an answer from a working endpoint. Counted as a failure, three of them opened
     * that endpoint's circuit breaker, and for a minute it was not asked for raw transactions
     * either, which is what the walk that names an asset runs on.
     */
    @Test fun refusals_do_not_open_the_endpoints_circuit_breaker() = runBlocking {
        val raw = "00".repeat(60)
        serve(mapOf(
            "/digiassets/asset/$assetId" to ("400 Bad Request" to refusal),
            "/tx/raw/$txid" to ("200 OK" to """{"hex":"$raw"}"""),
        ))
        val multi = MultiEndpointAssetClient(
            endpoints = listOf(digiscope()),
            failureThreshold = 3,
            transactionIdOf = { txid },
        )
        repeat(5) { assertEquals(AssetDataLookup.Refused, multi.lookupAssetData(assetId)) }
        assertEquals("one request per call: no retry", 5, requested.size)
        assertTrue("the endpoint is still asked for a raw transaction", multi.getRawTransaction(txid) != null)
    }

    // ── asset/:ref while the index is syncing ────────────────────────────────────────────────

    private val syncingBody = """{"syncing":true,"blocksBehind":23871}"""

    /** The asset route answers the same 503 as txout while the index is far behind. */
    @Test fun a_syncing_503_on_an_asset_reference_is_not_yet_and_one_request() = runBlocking {
        serve(mapOf(
            "/digiassets/asset/$assetId" to ("503 Service Unavailable" to syncingBody),
            "/digiassets/asset/5263" to ("503 Service Unavailable" to """{"syncing":true,"blocksBehind":null}"""),
            "/digiassets/asset/77" to ("503 Service Unavailable" to "<html>503 Service Temporarily Unavailable</html>"),
            "/digiassets/asset/78" to ("503 Service Unavailable" to """{"error":"busy","syncing":true}"""),
            "/digiassets/asset/79" to ("503 Service Unavailable" to """{"confirmations":0}"""),
            "/digiassets/asset/80" to ("500 Internal Server Error" to syncingBody),
        ))
        val client = digiscope()
        assertEquals(AssetDataLookup.NotYet, client.lookupAssetData(assetId))
        assertEquals("asked once", listOf("/digiassets/asset/$assetId"), requested.toList())
        assertEquals(AssetDataLookup.NotYet, client.lookupAssetData("5263"))
        // It is never data: the older entry point reads nothing, not an asset with no rules.
        assertNull(client.getAssetData(assetId))
        // Any 503 that is not the route's own "syncing", and the body under another status, is no answer.
        for (ref in listOf("77", "78", "79", "80")) {
            assertEquals(ref, AssetDataLookup.NoAnswer, client.lookupAssetData(ref))
        }
    }

    /**
     * A syncing index is an answer from a working endpoint, like a refusal. Counted as a failure,
     * three asset lookups during a freeze of the index opened the endpoint's circuit breaker, and
     * for a minute it was not asked for raw transactions either.
     */
    @Test fun a_syncing_asset_route_does_not_open_the_endpoints_circuit_breaker() = runBlocking {
        val raw = "00".repeat(60)
        serve(mapOf(
            "/digiassets/asset/$assetId" to ("503 Service Unavailable" to syncingBody),
            "/tx/raw/$txid" to ("200 OK" to """{"hex":"$raw"}"""),
        ))
        val multi = MultiEndpointAssetClient(
            endpoints = listOf(digiscope()),
            failureThreshold = 3,
            transactionIdOf = { txid },
        )
        repeat(5) { assertNull("syncing is never data", multi.getAssetData(assetId)) }
        assertEquals("one request per call: no retry", 5, requested.size)
        assertTrue("the endpoint is still asked for a raw transaction", multi.getRawTransaction(txid) != null)
        assertEquals(AssetDataLookup.NotYet, multi.lookupAssetData(assetId))
    }

    /** The other direction: a 503 that is not the route's own still counts, and the breaker opens. */
    @Test fun a_failing_asset_route_still_opens_the_circuit_breaker() = runBlocking {
        val raw = "00".repeat(60)
        serve(mapOf(
            "/digiassets/asset/$assetId" to ("503 Service Unavailable" to "<html>upstream</html>"),
            "/tx/raw/$txid" to ("200 OK" to """{"hex":"$raw"}"""),
        ))
        val multi = MultiEndpointAssetClient(
            endpoints = listOf(digiscope()),
            failureThreshold = 3,
            transactionIdOf = { txid },
        )
        repeat(3) { assertEquals(AssetDataLookup.NoAnswer, multi.lookupAssetData(assetId)) }
        assertNull("circuit open: not asked", multi.getRawTransaction(txid))
        assertEquals("no request reached the raw route", 3, requested.size)
    }

    /** Syncing at one endpoint, the next is still asked; with no data anywhere, "not yet" is the
     *  result even beside a refusal, because the lookup can succeed later. */
    @Test fun a_syncing_endpoint_passes_to_the_next() = runBlocking {
        serve(mapOf("/digiassets/asset/$assetId" to ("503 Service Unavailable" to syncingBody)))
        val answer = AssetDataResponse(assetId, cid = null, issuer = null, count = 7, decimals = 0, ipfs = null)
        var reply: AssetDataLookup = AssetDataLookup.Found(answer)
        val second = object : AssetNetworkClient {
            override val endpointLabel = "second"
            override suspend fun getAssetData(assetId: String): AssetDataResponse? = (reply as? AssetDataLookup.Found)?.data
            override suspend fun lookupAssetData(assetId: String): AssetDataLookup = reply
            override suspend fun getAddressHistory(address: String, limit: Int?): List<String>? = null
            override suspend fun getSyncState(): SyncStateResponse? = null
            override suspend fun getRawTransaction(txHashHex: String): ByteArray? = null
        }
        val multi = MultiEndpointAssetClient(listOf(digiscope(), second), transactionIdOf = { null })
        assertEquals(AssetDataLookup.Found(answer), multi.lookupAssetData(assetId))
        reply = AssetDataLookup.Refused
        assertEquals(AssetDataLookup.NotYet, multi.lookupAssetData(assetId))
        assertNull(multi.getAssetData(assetId))
    }

    /** Refused at one endpoint, the next is still asked, and its answer is the result. */
    @Test fun a_refusal_at_one_endpoint_passes_to_the_next() = runBlocking {
        serve(mapOf("/digiassets/asset/$assetId" to ("400 Bad Request" to refusal)))
        val answer = AssetDataResponse(assetId, cid = null, issuer = null, count = 7, decimals = 0, ipfs = null)
        var asked = 0
        val second = object : AssetNetworkClient {
            override val endpointLabel = "second"
            override suspend fun getAssetData(assetId: String): AssetDataResponse? { asked++; return answer }
            override suspend fun getAddressHistory(address: String, limit: Int?): List<String>? = null
            override suspend fun getSyncState(): SyncStateResponse? = null
            override suspend fun getRawTransaction(txHashHex: String): ByteArray? = null
        }
        val multi = MultiEndpointAssetClient(listOf(digiscope(), second), transactionIdOf = { null })
        assertEquals(AssetDataLookup.Found(answer), multi.lookupAssetData(assetId))
        assertEquals(answer, multi.getAssetData(assetId))
        assertEquals(2, asked)
    }
}
