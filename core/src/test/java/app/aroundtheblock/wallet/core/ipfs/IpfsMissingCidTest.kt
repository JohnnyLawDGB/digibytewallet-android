package app.aroundtheblock.wallet.core.ipfs

import android.util.Log
import app.aroundtheblock.wallet.core.asset.network.AssetDataResponse
import app.aroundtheblock.wallet.core.asset.network.AssetNetworkClient
import app.aroundtheblock.wallet.core.asset.network.DigiScopeAssetParsing
import app.aroundtheblock.wallet.core.db.dao.AssetMetadataDao
import app.aroundtheblock.wallet.core.db.entity.AssetMetadataEntity
import app.aroundtheblock.wallet.core.network.textOrNull
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * IPFS-NO-NULL and IPFS-MISS-BACKOFF (server hand-off 2026-11-21, asks A and B).
 *
 * Measured by the server in nine days: 1,107 requests for the literal path `/api/ipfs/null` from one
 * wallet, and single unpinned CIDs asked for 453 and 153 times while the answer was 404.
 *
 *  A. No request is made for a value that is not a CID. The "null" came from a provider's JSON
 *     `null`, which Android's `optString` reads as the text "null" (the org.json these tests run
 *     on does not, so the parser is tested with the text a phone produced, and a source gate keeps
 *     `optString` out of the two places it did the damage).
 *  B. A gateway's 404 or 400 for a CID is remembered for [IpfsClient.MISS_TTL_MS]; that gateway is
 *     not asked for that CID again inside it, the others still are, and it is asked again after.
 *     Every other failure is retried as before.
 *
 * No test here waits: the clock is the test's, and the gateways are an interceptor, so nothing
 * leaves the process.
 */
class IpfsMissingCidTest {

    private val content = "DigiAsset metadata".toByteArray(Charsets.UTF_8)
    /** CIDv1 raw, sha2-256: content-verified on every gateway. */
    private val cid = IpfsClientTest.buildCidV1(content, 0x12, "SHA-256")
    private val otherCid = IpfsClientTest.buildCidV1("another document".toByteArray(), 0x12, "SHA-256")

    private val first = "https://first.example/api/ipfs/{cid}"
    private val second = "https://second.example"            // the legacy form, no placeholder

    /** Every URL a request was made for, in order. */
    private val asked = ArrayList<HttpUrl>()
    /** What each host answers: a status and a body, or a throw. */
    private val answers = HashMap<String, () -> Pair<Int, ByteArray>>()
    private var now = 1_000_000L

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        synchronized(asked) { asked += request.url }
        val (code, body) = (answers[request.url.host] ?: { 404 to ByteArray(0) })()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("application/octet-stream".toMediaType())).build()
    }.build()

    private fun client(gateways: List<String> = listOf(first, second)) =
        IpfsClient(http, CidVerifier(), gateways, trustedGatewayPrefixes = emptyList(), clock = { now })

    private fun askedOf(host: String) = synchronized(asked) { asked.count { it.host == host } }

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    // ---------------------------------------------------------------------------------------------
    // A. No request for a value that is not a CID
    // ---------------------------------------------------------------------------------------------

    private val notCids = listOf(
        "", " ", "\n", "null", "NULL", "Null", " null ", "ipfs://null", "/ipfs/null", "ipfs://", "/ipfs/",
        "undefined", "none", "0", "nil", "Qm", "QmAB", "bafkreiexample", "bafy", "b", "z", "f",
        "bafkrei/../../etc/passwd", "bafkrei?format=raw", "https://evil.example/$cid", "$cid/logo.png",
    )

    @Test fun `no value that is not a CID is one to normalizeCid`() {
        assertNull(IpfsClient.normalizeCid(null))
        for (v in notCids) assertNull("'$v'", IpfsClient.normalizeCid(v))
    }

    @Test fun `every shape of real CID is still accepted, bare or prefixed`() {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(content)
        val v1Bytes = byteArrayOf(0x01, 0x55, 0x12, 0x20) + digest
        val real = listOf(
            IpfsClientTest.buildCidV0(content),                                   // Qm…
            cid,                                                                  // z… base58btc
            "f" + v1Bytes.joinToString("") { "%02x".format(it) },                 // base16
            "bafkreif6jztw2ph4rjcu7tbyzffrsk4o7elpxpepbeb4kwcwrmq4v7jkmi",        // an issuance header's (decoder KAT)
            "bafybeigqazrxezd42nhv6mqhnq6qrljmzdev55y7kzcc2fxyrvzlkgjwne",        // dag-pb, as digistamp returns
            "QmWfRczijuSDQWwjM9i7hH6XzBtJuuNwGAcaVLBDqmH6Co",
        )
        for (c in real) {
            assertEquals(c, IpfsClient.normalizeCid(c))
            assertEquals(c, IpfsClient.normalizeCid("ipfs://$c"))
            assertEquals(c, IpfsClient.normalizeCid("/ipfs/$c"))
        }
    }

    @Test fun `fetchVerified makes no request for a value that is not a CID`() = runBlocking {
        answers["first.example"] = { 200 to content }
        answers["second.example"] = { 200 to content }
        val client = client()
        for (v in notCids) assertNull("'$v'", client.fetchVerified(v))
        assertEquals("requests made: $asked", 0, asked.size)
    }

    /** The one place a URL is built refuses by itself, whatever its caller checked. */
    @Test fun `no gateway URL can be built from a value that is not a CID`() {
        for (gateway in listOf(first, second)) {
            for (v in notCids) assertNull("'$v' at $gateway", IpfsClient.gatewayUrl(gateway, v))
            // Not even a real CID that still wears its prefix: the caller passes the bare one.
            assertNull(IpfsClient.gatewayUrl(gateway, "ipfs://$cid"))
        }
        assertEquals("https://first.example/api/ipfs/$cid", IpfsClient.gatewayUrl(first, cid))
        assertEquals("https://second.example/ipfs/$cid?format=raw", IpfsClient.gatewayUrl(second, cid))
    }

    @Test fun `a declared digest length past the bytes present is not a CID, and does not throw`() {
        fun hexCid(vararg length: Int) = "f" + (byteArrayOf(0x01, 0x55, 0x12) + length.map { it.toByte() } + byteArrayOf(0x7f))
            .joinToString("") { "%02x".format(it) }
        // 0xFFFFFFFF: narrowed to an Int it is -1, and the digest's range ended before it began (a throw).
        val wraps = hexCid(0xff, 0xff, 0xff, 0xff, 0x0f)
        // 2^32 + 1: narrowed to an Int it is 1, and the one byte present passed for the digest.
        val truncates = hexCid(0x81, 0x80, 0x80, 0x80, 0x10)
        for (c in listOf(wraps, truncates)) {
            assertFalse(c, CidVerifier().isWellFormed(c))
            assertFalse(c, CidVerifier().verify(c, content))
            assertNull(c, IpfsClient.normalizeCid(c))
        }
    }

    // --- the source of the "null": a provider's answer ------------------------------------------

    @Test fun `a provider's null, or the text null, is no CID`() {
        fun cidOf(body: String) = DigiScopeAssetParsing.assetData(JSONObject(body), "La4WAqZf")!!.cid
        // What digistamp answers for an asset whose issuance carried no metadata hash.
        assertNull(cidOf("""{"assetId":"La4WAqZf","cid":null,"count":100,"decimals":0}"""))
        // What Android's optString made of that null. Here it is the provider saying it outright.
        assertNull(cidOf("""{"assetId":"La4WAqZf","cid":"null","count":100,"decimals":0}"""))
        assertNull(cidOf("""{"assetId":"La4WAqZf","cid":"","count":100,"decimals":0}"""))
        assertNull(cidOf("""{"assetId":"La4WAqZf","count":100,"decimals":0}"""))
        assertNull(cidOf("""{"assetId":"La4WAqZf","cid":"not-a-cid","count":100,"decimals":0}"""))
        assertNull(cidOf("""{"assetId":"La4WAqZf","cid":false,"count":100,"decimals":0}"""))
        // A real one is passed on as the provider wrote it; the fetch strips the prefix.
        assertEquals("ipfs://$cid", cidOf("""{"assetId":"La4WAqZf","cid":"ipfs://$cid","count":100,"decimals":0}"""))
    }

    @Test fun `textOrNull reads a JSON null as nothing, on any org-json`() {
        val json = JSONObject().put("n", JSONObject.NULL).put("s", "text").put("e", "").put("i", 7)
        assertNull(json.textOrNull("n"))
        assertNull(json.textOrNull("e"))
        assertNull(json.textOrNull("absent"))
        assertEquals("text", json.textOrNull("s"))
        assertEquals("7", json.textOrNull("i"))
    }

    /**
     * Android's `optString` and the org.json these tests run on disagree about a JSON null, so no
     * unit test can see `optString` go wrong. This keeps it out of where it went wrong: the
     * provider's CID, and every field of the metadata document (a `"assetName": null` was stored,
     * and shown, as the name "null").
     */
    @Test fun `optString reads neither a provider's cid nor any field of a metadata document`() {
        val root = File("../..").canonicalFile.let { if (File(it, "core/src").isDirectory) it else File("..").canonicalFile }
        val main = File(root, "core/src/main/java/app/aroundtheblock/wallet/core")
        val service = File(main, "ipfs/AssetMetadataService.kt").readText()
        val parsing = File(main, "asset/network/DigiScopeAssetParsing.kt").readText()
        assertTrue(service.contains("textOrNull(\"assetName\")"))     // the gate is reading the right file
        assertFalse("AssetMetadataService reads a document field with optString", service.contains("optString("))
        assertTrue(parsing.contains("textOrNull(\"cid\")"))
        assertFalse("the provider's cid is read with optString", Regex("""optString\(\s*"cid"""").containsMatchIn(parsing))
    }

    // --- through the metadata service, every way a CID reaches it --------------------------------

    private val assetId = "La4WAqZfAwtxbZxBSuNoxptactZcbXfZdq6kMo"
    private val dao = mockk<AssetMetadataDao>(relaxed = true)
    private val provider = mockk<AssetNetworkClient>()
    private fun chainFacts(cid: String?) = AssetMetadataEntity(assetId = assetId, totalSupply = 100, decimals = 0, metadataCid = cid)
    private fun provided(cid: String?) = AssetDataResponse(
        assetId = assetId, cid = cid, issuer = null, count = 100, decimals = 0, ipfs = null, rules = null,
    )

    @Test fun `the metadata service makes no IPFS request for a null CID from the caller, the row or the provider`() = runBlocking {
        answers["first.example"] = { 200 to content }
        answers["second.example"] = { 200 to content }
        for (bad in listOf("null", "NULL", "", " ", "undefined")) {
            // 1: the caller's
            coEvery { dao.getMetadata(assetId) } returns null
            AssetMetadataService(client(), dao, provider).getMetadata(assetId, bad)
            // 2: a row written before this rule
            coEvery { dao.getMetadata(assetId) } returns chainFacts(cid = bad)
            coEvery { provider.getAssetData(assetId) } returns provided(cid = null)
            AssetMetadataService(client(), dao, provider).getMetadata(assetId, null)
            // 3: a provider that says it (one that does not go through the parser above)
            coEvery { dao.getMetadata(assetId) } returns chainFacts(cid = null)
            coEvery { provider.getAssetData(assetId) } returns provided(cid = bad)
            AssetMetadataService(client(), dao, provider).getMetadata(assetId, null)
        }
        assertEquals("requests made: $asked", 0, asked.size)
    }

    /** A row holding "null" is a row with no CID: the asset can still be named by a provider (4.1.2 row 29). */
    @Test fun `a stored null CID does not stand in for the issuance's, so the provider is still asked`() = runBlocking {
        coEvery { dao.getMetadata(assetId) } returns chainFacts(cid = "null")
        coEvery { provider.getAssetData(assetId) } returns provided(cid = null)
        AssetMetadataService(client(), dao, provider).getMetadata(assetId, null)
        coVerify(exactly = 1) { provider.getAssetData(assetId) }
    }

    /** ...and a real CID on the row still keeps the provider out, whatever the caller passed. */
    @Test fun `a caller's null does not hide the issuance's CID on the row`() = runBlocking {
        answers["first.example"] = { 500 to ByteArray(0) }
        answers["second.example"] = { 500 to ByteArray(0) }
        coEvery { dao.getMetadata(assetId) } returns chainFacts(cid = cid)
        AssetMetadataService(client(), dao, provider).getMetadata(assetId, "null")
        assertEquals(listOf("/api/ipfs/$cid", "/ipfs/$cid"), asked.map { it.encodedPath })
        coVerify(exactly = 0) { provider.getAssetData(any()) }
    }

    // ---------------------------------------------------------------------------------------------
    // B. A gateway's "not here" is remembered for a while
    // ---------------------------------------------------------------------------------------------

    @Test fun `a 404 is not asked again inside the window, the next gateway still is, and it is asked again after`() = runBlocking {
        answers["first.example"] = { 404 to """{"error":"not pinned"}""".toByteArray() }
        answers["second.example"] = { 200 to content }
        val client = client()

        assertArrayEquals(content, client.fetchVerified(cid))
        assertEquals(1, askedOf("first.example"))
        assertEquals(1, askedOf("second.example"))

        // Every refresh inside the hour: the first gateway is left alone, the fall-through still happens.
        for (minutes in listOf(0L, 1L, 10L, 59L)) {
            now = 1_000_000L + minutes * 60_000L
            assertArrayEquals(content, client.fetchVerified(cid))
        }
        now = 1_000_000L + IpfsClient.MISS_TTL_MS - 1
        assertArrayEquals(content, client.fetchVerified(cid))
        assertEquals("the gateway that said 404 was asked again inside the window", 1, askedOf("first.example"))
        assertEquals(6, askedOf("second.example"))

        // The window is over: it is asked again (and, pinned by now, it answers).
        answers["first.example"] = { 200 to content }
        now = 1_000_000L + IpfsClient.MISS_TTL_MS
        assertArrayEquals(content, client.fetchVerified(cid))
        assertEquals(2, askedOf("first.example"))
        assertEquals("the first gateway answered, so the second was not needed", 6, askedOf("second.example"))
    }

    @Test fun `a 400 is remembered like a 404`() = runBlocking {
        answers["first.example"] = { 400 to """{"error":"invalid cid format"}""".toByteArray() }
        answers["second.example"] = { 200 to content }
        val client = client()
        repeat(3) { assertArrayEquals(content, client.fetchVerified(cid)) }
        assertEquals(1, askedOf("first.example"))
        assertEquals(3, askedOf("second.example"))
    }

    @Test fun `when every gateway said not here, a refresh makes no request at all until the window ends`() = runBlocking {
        val client = client()                                  // both hosts: 404
        assertNull(client.fetchVerified(cid))
        assertEquals(2, asked.size)
        repeat(50) { assertNull(client.fetchVerified(cid)) }   // 50 sweeps
        assertEquals(2, asked.size)

        // Never a permanent "no art": after the window both are asked, and one has it now.
        now += IpfsClient.MISS_TTL_MS
        answers["second.example"] = { 200 to content }
        assertArrayEquals(content, client.fetchVerified(cid))
        assertEquals(4, asked.size)
    }

    @Test fun `a server error, a redirect status and a timeout are retried on every fetch, as before`() = runBlocking {
        answers["second.example"] = { 200 to content }
        for (failure in listOf<() -> Pair<Int, ByteArray>>(
            { 500 to ByteArray(0) }, { 502 to ByteArray(0) }, { 503 to """{"syncing":true}""".toByteArray() },
            { 504 to ByteArray(0) }, { 429 to ByteArray(0) }, { 403 to ByteArray(0) }, { 410 to ByteArray(0) },
            { throw java.net.SocketTimeoutException("timeout") }, { throw IOException("no route to host") },
        )) {
            asked.clear()
            answers["first.example"] = failure
            val client = client()
            repeat(3) { assertArrayEquals(content, client.fetchVerified(cid)) }
            assertEquals("asked each time", 3, askedOf("first.example"))
            assertEquals(3, askedOf("second.example"))
        }
    }

    @Test fun `a hash mismatch is not a miss, so the gateway is asked again`() = runBlocking {
        answers["first.example"] = { 200 to "not the content".toByteArray() }
        answers["second.example"] = { 200 to content }
        val client = client()
        repeat(2) { assertArrayEquals(content, client.fetchVerified(cid)) }
        assertEquals(2, askedOf("first.example"))
    }

    @Test fun `a miss is for one CID at one gateway`() = runBlocking {
        val client = client()                                  // both hosts: 404
        assertNull(client.fetchVerified(cid))
        assertNull(client.fetchVerified(otherCid))
        assertEquals(listOf(cid, otherCid), asked.filter { it.host == "first.example" }.map { it.pathSegments.last() })
        assertEquals(4, asked.size)
        // The same CID wearing a prefix is the same CID.
        assertNull(client.fetchVerified("ipfs://$cid"))
        assertEquals(4, asked.size)
    }

    @Test fun `a new process starts with nothing remembered`() = runBlocking {
        assertNull(client().fetchVerified(cid))
        assertNull(client().fetchVerified(cid))                // a second client: the app restarted
        assertEquals(2, askedOf("first.example"))
    }

    @Test fun `a clock set backwards drops the miss instead of extending it`() = runBlocking {
        val client = client()
        assertNull(client.fetchVerified(cid))
        now -= 7L * 24 * 60 * 60_000L                          // the user set the date back a week
        assertNull(client.fetchVerified(cid))
        assertEquals(2, askedOf("first.example"))
    }

    @Test fun `the memory is bounded, and the oldest answer goes first`() {
        val memory = GatewayMissMemory(ttlMs = 1_000, maxEntries = 3, clock = { now })
        for (i in 1..5) memory.remember("g", "cid$i")
        assertEquals(3, memory.size())
        assertFalse(memory.isRemembered("g", "cid1"))
        assertFalse(memory.isRemembered("g", "cid2"))
        assertTrue(memory.isRemembered("g", "cid3"))
        assertTrue(memory.isRemembered("g", "cid5"))
        assertFalse("another gateway's answer", memory.isRemembered("h", "cid5"))
        now += 1_000
        assertFalse(memory.isRemembered("g", "cid5"))
        assertEquals("an expired answer is dropped when it is read", 2, memory.size())
    }

    @Test fun `only 404 and 400 are a miss`() {
        assertEquals(listOf(400, 404), (100..599).filter { IpfsClient.isMiss(it) })
    }

    /** The shipped numbers, stated: an hour, in memory, at most 512 answers. */
    @Test fun `the window is one hour`() {
        assertEquals(3_600_000L, IpfsClient.MISS_TTL_MS)
        assertEquals(512, IpfsClient.MISS_MAX_ENTRIES)
    }
}
