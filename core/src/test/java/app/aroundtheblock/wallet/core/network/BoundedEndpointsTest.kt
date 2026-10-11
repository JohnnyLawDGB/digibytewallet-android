package app.aroundtheblock.wallet.core.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.aroundtheblock.wallet.core.UpdateChecker
import app.aroundtheblock.wallet.core.asset.network.DigiScopeAssetClient
import app.aroundtheblock.wallet.core.asset.network.DigistampAssetClient
import app.aroundtheblock.wallet.core.asset.send.StackEntry
import app.aroundtheblock.wallet.core.asset.send.StackLookup
import app.aroundtheblock.wallet.core.digiscope.DigiScopeClient
import app.aroundtheblock.wallet.core.digistamp.DigistampClient
import app.aroundtheblock.wallet.core.ipfs.CidVerifier
import app.aroundtheblock.wallet.core.ipfs.IpfsClient
import app.aroundtheblock.wallet.core.okHttpFetcher
import app.aroundtheblock.wallet.core.reconcile.DgbNodeClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every network answer the wallet reads is held to a cap set for its endpoint.
 *
 * Each endpoint is driven through its real client, with the answer served by an application
 * interceptor on the client it is given (no socket is opened; derived clients keep the
 * interceptor). For each one:
 *  - an answer with no declared length (chunked, or inflated from gzip) is refused, and no more
 *    than the cap plus one byte (plus at most one okio segment of read-ahead) is taken from it;
 *  - an answer whose declared length is over the cap is refused without a byte being taken;
 *  - one answer per kind, padded to exactly the cap, is still read and used, and the same answer
 *    one byte longer is refused.
 *
 * The caps below are the specification; a change to one is a change to this table.
 */
class BoundedEndpointsTest {

    // ── the caps ───────────────────────────────────────────────────────────────

    private companion object {
        const val KiB = 1024L
        const val MiB = 1024L * KiB

        const val HUB_SMALL = 64 * KiB       // Hub: login, profile, single replies, game stats
        const val HUB_LIST = 4 * MiB         // Hub: channels, messages, threads, a thread with its replies
        const val RELEASES = 4 * MiB         // the releases repository's GitHub API answer
        const val PRICE = 64 * KiB           // CoinGecko / Binance tickers
        const val ASSET_DATA = 1 * MiB       // asset indexers: one asset's data
        const val ASSET_TX = 4 * MiB         // asset indexers: one raw transaction
        const val ASSET_HISTORY = 4 * MiB    // asset indexers: an address's asset history
        const val ASSET_SYNC = 64 * KiB      // asset indexers: sync state
        const val ASSET_STACK = 256 * KiB    // our indexer: the assets on one output
        const val DIGISTAMP_AUTH = 64 * KiB  // digistamp: challenge, session
        const val RECONCILE = 32 * MiB       // reconcile backend: a 500-address UTXO batch with parents
        const val HISTORY = 16 * MiB         // reconcile backend: address history
        const val EXPLORER = 8 * MiB         // reconcile backend: explorer transaction, block, address
        const val IPFS = 5 * MiB             // IPFS gateways (IpfsClient.MAX_RESPONSE_SIZE)

        /** The most one okio segment can add past what the reader asked for. */
        const val SEGMENT = 8_192L

        const val REPO = "JohnnyLawDGB/aroundtheblock-wallet-releases"
        const val TXID = "ab01ab01ab01ab01ab01ab01ab01ab01ab01ab01ab01ab01ab01ab01ab01ab01"
        const val ADDR = "dgb1qexampleexampleexampleexampleexamplexyz"
        const val ASSET = "La8knZNCvaMsLp3PLueDShjUvV5YLKBhxzcvBC"
        const val CID = "QmWfRczijuSDQWwjM9i7hH6XzBtJuuNwGAcaVLBDqmH6Co"
        val JSON = "application/json".toMediaType()
    }

    /** One body read: what it is called, its cap, how to make the call, and the call's "no answer". */
    private class Case(
        val name: String,
        val cap: Long,
        val none: Any?,
        val status: Int = 200,
        val call: suspend (OkHttpClient) -> Any?,
    )

    private val cases: List<Case> = listOf(
        // The Hub (DigiScope)
        Case("hub quick-login challenge", HUB_SMALL, false) { hub(it).quickLogin() },
        Case("hub login callback", HUB_SMALL, null) { hub(it).login("D", "sig", "digiid://api.digiscope.me/x") },
        Case("hub profile", HUB_SMALL, null) { hub(it).getProfile() },
        Case("hub handle availability", HUB_SMALL, false) { hub(it).checkHandleAvailable("handle") },
        Case("hub new thread", HUB_SMALL, null) { hub(it).createThread(1, "t", "c", "s") },
        Case("hub reply", HUB_SMALL, null) { hub(it).replyToThread(1, "c", "s") },
        Case("hub game stats", HUB_SMALL, null) { hub(it).getDigiRunnerStats() },
        Case("hub game leaderboard", HUB_SMALL, emptyList<Any>()) { hub(it).getDigiRunnerLeaderboard() },
        // The score is accepted on the status; its answer is read only for the log.
        Case("hub game score answer", HUB_SMALL, true) { hub(it).submitDigiRunnerScore(1, 1, 1, 1) },
        Case("hub channels", HUB_LIST, emptyList<Any>()) { hub(it).getChannels() },
        Case("hub messages", HUB_LIST, emptyList<Any>()) { hub(it).getMessages(1) },
        Case("hub threads", HUB_LIST, emptyList<Any>()) { hub(it).getThreads(1) },
        Case("hub thread", HUB_LIST, null) { hub(it).getThread(1) },
        // Releases (sideload builds)
        Case("update check, latest release", RELEASES, null) { UpdateChecker(it, REPO).checkForUpdate("0.0.1") },
        Case("update check, release list", RELEASES, null) {
            UpdateChecker(it, REPO).checkForUpdate("0.0.1", includePrereleases = true)
        },
        // Price
        Case("price ticker", PRICE, "refused") {
            runCatching { okHttpFetcher(it).fetch("https://api.coingecko.com/api/v3/simple/price") }
                .fold({ "read" }, { "refused" })
        },
        // Asset indexers
        Case("digiscope asset data", ASSET_DATA, null) { DigiScopeAssetClient(baseClient = it).getAssetData(ASSET) },
        Case("digiscope raw transaction", ASSET_TX, null) { DigiScopeAssetClient(baseClient = it).getRawTransaction(TXID) },
        Case("digiscope asset history", ASSET_HISTORY, null) { DigiScopeAssetClient(baseClient = it).getAddressHistory(ADDR, null) },
        Case("digiscope asset sync state", ASSET_SYNC, null) { DigiScopeAssetClient(baseClient = it).getSyncState() },
        Case("digiscope output stack", ASSET_STACK, StackLookup.Unavailable) { DigiScopeAssetClient(baseClient = it).stackOf(TXID, 0) },
        Case("digiscope output stack, not found", ASSET_STACK, StackLookup.Unavailable, status = 404) {
            DigiScopeAssetClient(baseClient = it).stackOf(TXID, 0)
        },
        Case("digistamp asset data", ASSET_DATA, null) { DigistampAssetClient(baseClient = it).getAssetData(ASSET) },
        Case("digistamp raw transaction", ASSET_TX, null) { DigistampAssetClient(baseClient = it).getRawTransaction(TXID) },
        Case("digistamp asset history", ASSET_HISTORY, null) { DigistampAssetClient(baseClient = it).getAddressHistory(ADDR, null) },
        Case("digistamp asset sync state", ASSET_SYNC, null) { DigistampAssetClient(baseClient = it).getSyncState() },
        // digistamp sign-in
        Case("digistamp challenge", DIGISTAMP_AUTH, null) { DigistampClient(it).challenge() },
        Case("digistamp session", DIGISTAMP_AUTH, false) { DigistampClient(it).isAuthenticated() },
        // Reconcile backend (DigiScope, or the user's own endpoint)
        Case("reconcile UTXO batch", RECONCILE, null) { node(it).reconcileAddresses(listOf(ADDR)) },
        Case("reconcile address history batch", HISTORY, null) { node(it).addressHistoryBatch(listOf(ADDR)) },
        Case("reconcile address history", HISTORY, null) { node(it).addressHistory(ADDR) },
        Case("reconcile confirmation", EXPLORER, null) { node(it).txConfirmation(TXID) },
        Case("reconcile explorer transaction", EXPLORER, null) { node(it).fetchRawTx(TXID, 1L) },
        Case("reconcile raw transaction", EXPLORER, null) { node(it).fetchRawTransaction(TXID) },
        Case("reconcile DigiDollar holding", EXPLORER, null) { node(it).digiDollarHolding("DDexample") },
        // IPFS
        Case("ipfs gateway", IPFS, null) {
            IpfsClient(it, CidVerifier(), listOf("https://gateway.example/ipfs/{cid}")).fetchVerified(CID)
        },
    )

    // ── over the cap ───────────────────────────────────────────────────────────

    @Test fun `every endpoint refuses an answer over its cap without reading past the cap plus one byte`() {
        val failures = mutableListOf<String>()
        for (case in cases) {
            val served = Served(case.status) { CountingSource(case.cap + 64 * KiB) }
            val answer = runBlocking { case.call(clientServing(served)) }
            val taken = served.sources.sumOf { it.taken }
            when {
                served.hits == 0 -> failures += "${case.name}: no request was made (the case is blind)"
                taken > case.cap + 1 + SEGMENT ->
                    failures += "${case.name}: took $taken bytes of an answer over its ${case.cap}-byte cap"
                answer != case.none -> failures += "${case.name}: answered $answer for an answer over its cap"
            }
        }
        assertTrue(failures.joinToString("\n", prefix = "\n"), failures.isEmpty())
    }

    @Test fun `every endpoint refuses an answer whose declared length is over its cap without reading it`() {
        val failures = mutableListOf<String>()
        for (case in cases) {
            val served = Served(case.status, declaredLength = case.cap + 1) { CountingSource(case.cap + 1) }
            val answer = runBlocking { case.call(clientServing(served)) }
            val taken = served.sources.sumOf { it.taken }
            when {
                served.hits == 0 -> failures += "${case.name}: no request was made (the case is blind)"
                taken != 0L -> failures += "${case.name}: took $taken bytes of an answer declared over its ${case.cap}-byte cap"
                answer != case.none -> failures += "${case.name}: answered $answer for an answer declared over its cap"
            }
        }
        assertTrue(failures.joinToString("\n", prefix = "\n"), failures.isEmpty())
    }

    // ── exactly the cap ────────────────────────────────────────────────────────

    @Test fun `hub lists - an answer of exactly the cap is read, one byte more is refused`() {
        val json = """[{"id":1,"name":"general","description":"PAD","type":"text"}]"""
        val client = { length: Long -> clientServing(Served { textSource(padded(json, length)) }) }

        val channels = runBlocking { hub(client(HUB_LIST)).getChannels() }
        assertEquals(listOf("general"), channels.map { it.name })
        assertEquals(emptyList<Any>(), runBlocking { hub(client(HUB_LIST + 1)).getChannels() })
    }

    @Test fun `hub single answers - an answer of exactly the cap is read, one byte more is refused`() {
        val json = """{"profile":{"handle":"h","address":"Dexample","tipBalance":5,"note":"PAD"}}"""
        val client = { length: Long -> clientServing(Served { textSource(padded(json, length)) }) }

        assertEquals("h", runBlocking { hub(client(HUB_SMALL)).getProfile() }?.handle)
        assertNull(runBlocking { hub(client(HUB_SMALL + 1)).getProfile() })
    }

    @Test fun `releases - an answer of exactly the cap is read, one byte more is refused`() {
        val json = """{"tag_name":"v9.9.9","html_url":"https://github.com/$REPO/releases/tag/v9.9.9",""" +
            """"prerelease":false,"body":"notes","assets":[],"note":"PAD"}"""
        val client = { length: Long -> clientServing(Served { textSource(padded(json, length)) }) }

        assertEquals("9.9.9", runBlocking { UpdateChecker(client(RELEASES), REPO).checkForUpdate("0.0.1") }?.versionName)
        assertNull(runBlocking { UpdateChecker(client(RELEASES + 1), REPO).checkForUpdate("0.0.1") })
    }

    @Test fun `price - an answer of exactly the cap is read, one byte more is refused`() {
        val json = """{"digibyte":{"usd":0.01,"note":"PAD"}}"""
        val client = { length: Long -> clientServing(Served { textSource(padded(json, length)) }) }
        val url = "https://api.coingecko.com/api/v3/simple/price"

        assertEquals(PRICE, okHttpFetcher(client(PRICE)).fetch(url).length.toLong())
        assertTrue("an answer one byte over the cap was read",
            runCatching { okHttpFetcher(client(PRICE + 1)).fetch(url) }.isFailure)
    }

    @Test fun `asset indexers - a raw transaction of exactly the cap is read, one byte more is refused`() {
        val json = """{"hex":"0102","note":"PAD"}"""
        val client = { length: Long -> clientServing(Served { textSource(padded(json, length)) }) }

        assertArrayEquals(byteArrayOf(1, 2), runBlocking { DigiScopeAssetClient(baseClient = client(ASSET_TX)).getRawTransaction(TXID) })
        assertNull(runBlocking { DigiScopeAssetClient(baseClient = client(ASSET_TX + 1)).getRawTransaction(TXID) })
        assertArrayEquals(byteArrayOf(1, 2), runBlocking { DigistampAssetClient(baseClient = client(ASSET_TX)).getRawTransaction(TXID) })
        assertNull(runBlocking { DigistampAssetClient(baseClient = client(ASSET_TX + 1)).getRawTransaction(TXID) })
    }

    @Test fun `our indexer - an output stack of exactly the cap is read, one byte more is refused`() {
        val json = """{"txid":"$TXID","vout":0,"confirmations":3,"assets":[{"assetId":"$ASSET","count":5}],"note":"PAD"}"""
        val client = { length: Long -> clientServing(Served { textSource(padded(json, length)) }) }

        assertEquals(StackLookup.Found(listOf(StackEntry(ASSET, 5))),
            runBlocking { DigiScopeAssetClient(baseClient = client(ASSET_STACK)).stackOf(TXID, 0) })
        assertEquals(StackLookup.Unavailable,
            runBlocking { DigiScopeAssetClient(baseClient = client(ASSET_STACK + 1)).stackOf(TXID, 0) })
    }

    @Test fun `digistamp sign-in - an answer of exactly the cap is read, one byte more is refused`() {
        val json = """{"authenticated":true,"note":"PAD"}"""
        val client = { length: Long -> clientServing(Served { textSource(padded(json, length)) }) }

        assertTrue(runBlocking { DigistampClient(client(DIGISTAMP_AUTH)).isAuthenticated() })
        assertTrue("an answer one byte over the cap was read",
            !runBlocking { DigistampClient(client(DIGISTAMP_AUTH + 1)).isAuthenticated() })
    }

    @Test fun `reconcile - a UTXO batch of exactly the cap is read, one byte more is refused`() {
        val json = """{"utxos":[],"rawTxs":{},"height":5,"note":"PAD"}"""
        val client = { length: Long -> clientServing(Served { textSource(padded(json, length)) }) }

        val result = runBlocking { node(client(RECONCILE)).reconcileAddresses(listOf(ADDR)) }
        assertNotNull(result)
        assertEquals(5L, result!!.chainHeight)
        assertNull(runBlocking { node(client(RECONCILE + 1)).reconcileAddresses(listOf(ADDR)) })
    }

    @Test fun `reconcile - an explorer answer of exactly the cap is read, one byte more is refused`() {
        val json = """{"hex":"0102","note":"PAD"}"""
        val client = { length: Long -> clientServing(Served { textSource(padded(json, length)) }) }

        assertArrayEquals(byteArrayOf(1, 2), runBlocking { node(client(EXPLORER)).fetchRawTransaction(TXID) })
        assertNull(runBlocking { node(client(EXPLORER + 1)).fetchRawTransaction(TXID) })
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    @Before fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.w(any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    /** A Hub client signed in with a stand-in token; its token store is unusable on the JVM and reads as signed out. */
    private fun hub(client: OkHttpClient): DigiScopeClient =
        DigiScopeClient(client, mockk<Context>(relaxed = true)).apply { setToken("token") }

    /** A reconcile client on the default (DigiScope) endpoint. */
    private fun node(client: OkHttpClient): DgbNodeClient {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getBoolean(any(), any()) } returns false
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.applicationContext } returns context
        return DgbNodeClient(context, client)
    }

    private fun clientServing(served: Served): OkHttpClient = OkHttpClient.Builder().addInterceptor(served).build()

    /** [json] with its `PAD` replaced by filler so the whole is exactly [length] bytes (ASCII). */
    private fun padded(json: String, length: Long): String {
        val fill = length - (json.length - "PAD".length)
        require(fill >= 0) { "$length is shorter than the answer's fixed part" }
        return json.replace("PAD", "x".repeat(fill.toInt()))
    }

    private fun textSource(text: String): CountingSource = CountingSource(text.encodeToByteArray())

    /** Serves every request with [status] and a fresh body from [source]; keeps every source it served. */
    private class Served(
        val status: Int = 200,
        val declaredLength: Long = -1L,
        val source: () -> CountingSource,
    ) : Interceptor {
        var hits = 0
        val sources = mutableListOf<CountingSource>()
        override fun intercept(chain: Interceptor.Chain): Response {
            hits++
            val counting = source().also { sources += it }
            val body = object : ResponseBody() {
                private val buffered: BufferedSource by lazy { counting.buffer() }
                override fun contentLength() = declaredLength
                override fun contentType(): MediaType = JSON
                override fun source(): BufferedSource = buffered
            }
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("")
                .body(body)
                .build()
        }
    }

    /** Filler of [total] bytes, or [bytes] when given, handed out only as asked; counts what was taken. */
    private class CountingSource(private val bytes: ByteArray?, val total: Long) : Source {
        constructor(total: Long) : this(null, total)
        constructor(bytes: ByteArray) : this(bytes, bytes.size.toLong())

        var taken = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = minOf(byteCount, total - taken)
            if (n <= 0L) return -1L
            if (bytes != null) sink.write(bytes, taken.toInt(), n.toInt())
            else sink.write(ByteArray(n.toInt()) { 'x'.code.toByte() })
            taken += n
            return n
        }
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() = Unit
    }
}
