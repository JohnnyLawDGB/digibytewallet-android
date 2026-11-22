package app.aroundtheblock.wallet.core.ipfs

import android.util.Log
import app.aroundtheblock.wallet.core.db.dao.AssetMetadataDao
import app.aroundtheblock.wallet.core.db.entity.AssetMetadataEntity
import app.aroundtheblock.wallet.core.model.AssetMediaKind
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ASSET-MEDIA-FIXES, second review (R4): when the one-time re-read for media is given up for good,
 * MEASURED, with the real [IpfsClient] (its gateway memory included), the shipped gateway list and
 * the real [AssetMetadataService]. Only the clock and the four hosts' answers are the test's.
 *
 * The rule under test, in plain words: the re-read is given up for good only after the TRUSTED
 * gateway itself has answered "not here" (404/400) five times, each at least 24 hours after the
 * one before; so no sooner than four days after the first, however often the app is started. A
 * public gateway's 404 while the trusted one is failing counts for nothing.
 *
 * Before: any gateway's 404 counted, answers ten minutes apart counted separately, and a row was
 * given up for good after two launches and about forty minutes.
 */
class AssetMediaRereadGiveUpTest {

    private val MINUTE = 60_000L
    private val HOUR = 60 * MINUTE
    private val DAY = 24 * HOUR

    private val TRUSTED = "api.digiscope.me"
    private val PUBLIC = listOf("trustless-gateway.link", "dweb.link", "ipfs.io")
    private val JSON = "application/json".toMediaType()

    private val assetId = "La9Ka188DZuoaZQHxU3zgQPH9pwuYpH7BaN6nH"
    private val MP3 = "ipfs://bafybeictgnjgm4gm3lweojytk3fvkcvuqyruwex5viklo4upsxxj5jcdx4"
    private val DOCUMENT =
        """{"data":{"assetName":"DigiKid","urls":[{"mimeType":"audio/mpeg","name":"icon","url":"$MP3"}]}}""".toByteArray()
    /** A raw-codec CID, as every real metadata document has, that these bytes hash to. */
    private val CID = IpfsClientTest.buildCidV1(DOCUMENT, 0x12, "SHA-256")

    private var now = 1_795_000_000_000L
    private val start = now

    /** host -> status (and the document, for 200); a host with no entry cannot be reached. */
    private val answers = HashMap<String, Int>()
    /** Every request: (time since the start, host). */
    private val requests = ArrayList<Pair<Long, String>>()

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val host = chain.request().url.host
        synchronized(requests) { requests += (now - start) to host }
        val code = answers[host] ?: throw java.net.ConnectException("Failed to connect to $host")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body((if (code == 200) DOCUMENT else """{"error":"not found"}""".toByteArray()).toResponseBody(JSON)).build()
    }.build()

    private var row: AssetMetadataEntity? = null
    private val dao = mockk<AssetMetadataDao>(relaxed = true)

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        coEvery { dao.getMetadata(assetId) } answers { row }
        coEvery { dao.updateIpfsFacts(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            row = row!!.copy(name = arg(1), symbol = arg(2), description = arg(3), metadataCid = arg(5), imageUrl = arg(6))
        }
        coEvery { dao.updateMedia(any(), any()) } answers { row = row!!.copy(mediaJson = secondArg()) }
        // What 4.1.5 stored for asset 5364: named, with its song as the image, never read for media.
        row = AssetMetadataEntity(assetId = assetId, name = "DigiKid", totalSupply = 100, metadataCid = CID, imageUrl = MP3, mediaJson = null)
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    /**
     * One launch: a new process, so a new client (an empty gateway memory) and a new service (its
     * ten-minute memory and its per-process count empty). The app then stays open for [openFor],
     * and the asset sweep asks for the row every [SWEEP].
     */
    private fun launch(openFor: Long) = runBlocking {
        val client = IpfsClient(http, CidVerifier(), IpfsClient.DEFAULT_GATEWAYS, IpfsClient.TRUSTED_GATEWAY_PREFIXES, clock = { now })
        val service = AssetMetadataService(ipfsClient = client, assetMetadataDao = dao, clock = { now })
        val opened = now
        while (now - opened < openFor) {
            assertEquals("the row stays on screen throughout", "DigiKid", service.getMetadata(assetId, null)?.name)
            now += SWEEP
        }
        now = opened            // the caller moves time on between launches
    }

    private val SWEEP = 5 * MINUTE

    /** Launches every [every], each open for [openFor], until [until] has passed since the start. */
    private fun use(until: Long, every: Long, openFor: Long, eachLaunch: () -> Unit = {}) {
        while (now - start < until) {
            launch(openFor)
            eachLaunch()
            now += every
        }
    }

    private fun mark() = MediaRereadMark.read(row!!.mediaJson)
    private fun givenUp() = (mark()?.notHere ?: 0) >= AssetMetadataService.MEDIA_REREAD_NOT_HERE_LIMIT
    private fun askedOf(host: String) = synchronized(requests) { requests.count { it.second == host } }

    // ---------------------------------------------------------------------------------------------

    /** The reviewer's case: our gateway is down, and the public ones do not have the document. */
    @Test fun `a trusted-gateway outage while the public gateways answer 404 is never held against the document`() {
        for (trustedSays in listOf<Int?>(503, 500, 502, null)) {
            row = row!!.copy(mediaJson = null)
            if (trustedSays == null) answers.remove(TRUSTED) else answers[TRUSTED] = trustedSays
            for (host in PUBLIC) answers[host] = 404

            // Eight days of it: a launch every three hours, open two hours each time.
            val from = now
            while (now - from < 8 * DAY) { launch(openFor = 2 * HOUR); now += 3 * HOUR }

            assertNull("trusted $trustedSays: nothing is written to the row", row!!.mediaJson)
            assertTrue("trusted $trustedSays: still to be read", AssetMetadataService.needsMediaReread(row!!, now))
        }
        // The gateway comes back: the document is read at the next launch, and the song is no longer the picture.
        answers[TRUSTED] = 200
        launch(openFor = 10 * MINUTE)
        assertNull(row!!.imageUrl)
        assertEquals(listOf(AssetMediaKind.AUDIO), AssetMediaEntries.decode(row!!.mediaJson).map { it.kind })
    }

    /** How many answers, over how long. */
    @Test fun `a document the trusted gateway says is gone is given up after five answers on five days, four days after the first`() {
        answers[TRUSTED] = 404
        for (host in PUBLIC) answers[host] = 404

        var gaveUpAt: Long? = null
        val counted = ArrayList<Long>()                 // when each answer was counted
        use(until = 12 * DAY, every = 3 * HOUR, openFor = 2 * HOUR) {
            val n = mark()?.notHere ?: 0
            if (n > counted.size) counted += mark()!!.at - start
            if (gaveUpAt == null && givenUp()) gaveUpAt = mark()!!.at - start
        }

        assertEquals("five answers were counted", AssetMetadataService.MEDIA_REREAD_NOT_HERE_LIMIT, counted.size)
        assertEquals("one per day: at the first launch of each of five days", listOf(0L, DAY, 2 * DAY, 3 * DAY, 4 * DAY), counted)
        assertEquals("given up four days after the first answer", 4 * DAY, gaveUpAt)
        assertFalse(AssetMetadataService.needsMediaReread(row!!, now))

        // What it cost: the trusted gateway was asked five times in all, each public gateway no more,
        // and nobody at all after the row was given up (eight more days of launches).
        assertEquals(5, askedOf(TRUSTED))
        for (host in PUBLIC) assertEquals(host, 5, askedOf(host))
        assertTrue("no request after giving up", synchronized(requests) { requests.all { it.first <= 4 * DAY } })
        // Given up, and what the row held is kept: nothing was invented in its place.
        assertEquals(MP3, row!!.imageUrl)
        assertEquals("DigiKid", row!!.name)
    }

    /** The days are the device clock's, not the app's running time: launches cannot hurry them. */
    @Test fun `no pattern of launches gives a row up sooner than four days`() {
        answers[TRUSTED] = 404
        for (host in PUBLIC) answers[host] = 404
        // Restarted every minute for a day, then every ten minutes, then hourly: 2,000 launches.
        use(until = 1 * DAY, every = MINUTE, openFor = MINUTE)
        assertEquals(1, mark()!!.notHere)
        use(until = 3 * DAY, every = 10 * MINUTE, openFor = 10 * MINUTE)
        use(until = 4 * DAY - HOUR, every = HOUR, openFor = HOUR)
        assertFalse("not given up before four days have passed", givenUp())
        assertEquals(4, mark()!!.notHere)
        assertEquals("one request per day, however often the app was started", 4, askedOf(TRUSTED))

        use(until = 5 * DAY, every = HOUR, openFor = HOUR)
        assertTrue(givenUp())
        assertEquals(5, askedOf(TRUSTED))
    }

    @Test fun `setting the clock back does not hurry it either`() {
        answers[TRUSTED] = 404
        launch(openFor = MINUTE)
        assertEquals(1, mark()!!.notHere)
        repeat(20) {                                    // twenty launches, each a week EARLIER than the last
            now -= 7 * DAY
            launch(openFor = MINUTE)
        }
        assertEquals("a clock that went backwards counts nothing", 1, mark()!!.notHere)
        // ...and does not strand the row either: a day after the re-dated answer it is asked again.
        now += DAY
        launch(openFor = MINUTE)
        assertEquals(2, mark()!!.notHere)
    }

    @Test fun `a document the trusted gateway pins again before the fifth day is read, and the count is gone`() {
        answers[TRUSTED] = 404
        for (host in PUBLIC) answers[host] = 404
        use(until = 3 * DAY + HOUR, every = 3 * HOUR, openFor = HOUR)
        assertEquals(4, mark()!!.notHere)

        answers[TRUSTED] = 200
        use(until = 4 * DAY + HOUR, every = 3 * HOUR, openFor = HOUR)
        assertNull("the row holds the files now, not a count", mark())
        assertNull(row!!.imageUrl)
        assertEquals(listOf(AssetMediaKind.AUDIO), AssetMediaEntries.decode(row!!.mediaJson).map { it.kind })
        assertFalse(AssetMetadataService.needsMediaReread(row!!, now))
    }

    /** The trusted gateway's 404 with every public gateway unreachable is still its answer. */
    @Test fun `the trusted gateway's answer counts whatever the public gateways do`() {
        answers[TRUSTED] = 400
        use(until = 4 * DAY + HOUR, every = 6 * HOUR, openFor = HOUR)
        assertTrue(givenUp())
    }
}
