package app.aroundtheblock.wallet.core.ipfs

import android.util.Log
import app.aroundtheblock.wallet.core.db.dao.AssetMetadataDao
import app.aroundtheblock.wallet.core.db.entity.AssetMetadataEntity
import app.aroundtheblock.wallet.core.model.AssetMediaKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ASSET-MEDIA-CLASSIFY, the rows that already exist. A named row is otherwise never fetched again
 * (a CID's content cannot change), so a row a 4.1.5 phone stored with an MP3 as its image would
 * keep it for good. A row written before schema 13 has NULL media: its document is read once more,
 * through the same gateways and the same backoff, and the row is then marked as read.
 */
class AssetMediaRereadTest {

    private val assetId = "La9Ka188DZuoaZQHxU3zgQPH9pwuYpH7BaN6nH"
    private val CID = "bafkreicrmqxoxgm423z4nxbpcecc2kbixm4nnpyzfuz5kjkjmsbmgkpkae"
    private val MP3 = "ipfs://bafybeictgnjgm4gm3lweojytk3fvkcvuqyruwex5viklo4upsxxj5jcdx4"
    private val DOCUMENT =
        """{"data":{"assetName":"DigiKid","urls":[{"mimeType":"audio/mpeg","name":"icon","url":"$MP3"}]}}"""

    /** The one row, held the way the table holds it. */
    private var row: AssetMetadataEntity? = null
    private val dao = mockk<AssetMetadataDao>(relaxed = true)
    private val ipfs = mockk<IpfsClient>()

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        coEvery { dao.getMetadata(assetId) } answers { row }
        coEvery { dao.updateIpfsFacts(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            row = row!!.copy(name = arg(1), symbol = arg(2), description = arg(3), metadataCid = arg(5), imageUrl = arg(6))
        }
        coEvery { dao.updateMedia(any(), any()) } answers { row = row!!.copy(mediaJson = secondArg()) }
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private fun service() = AssetMetadataService(ipfsClient = ipfs, assetMetadataDao = dao)

    /** What 4.1.5 stored for asset 5364: named, with its song as the image. */
    private fun rowFrom415() = AssetMetadataEntity(
        assetId = assetId, name = "DigiKid", totalSupply = 100, metadataCid = CID, imageUrl = MP3, mediaJson = null,
    )

    @Test fun `a row stored before media was kept is read once more, and loses its song as picture`() = runBlocking {
        row = rowFrom415()
        coEvery { ipfs.fetch(CID) } returns IpfsClient.Fetched(DOCUMENT.toByteArray(), 0, wholeFile = true)
        val service = service()

        val first = service.getMetadata(assetId, null)
        assertNull("the MP3 is no longer the picture", first!!.imageUrl)
        assertEquals(listOf(AssetMediaKind.AUDIO), first.media.map { it.kind })
        assertEquals("the chain's supply is untouched", 100L, first.totalSupply)

        // Marked as read: every later call is answered from the row.
        repeat(5) { assertNull(service.getMetadata(assetId, null)!!.imageUrl) }
        coVerify(exactly = 1) { ipfs.fetch(any()) }
    }

    @Test fun `a row already read for media is never fetched again`() = runBlocking {
        row = rowFrom415().copy(imageUrl = null, mediaJson = "[]")
        assertNotNull(service().getMetadata(assetId, CID))
        coVerify(exactly = 0) { ipfs.fetch(any()) }
    }

    @Test fun `when the document cannot be fetched the row is still shown, and asked for no more often than any failure`() = runBlocking {
        row = rowFrom415()
        coEvery { ipfs.fetch(CID) } returns IpfsClient.Fetched(null, 4)
        val service = service()
        repeat(5) {
            val shown = service.getMetadata(assetId, null)
            assertEquals("a name the wallet has must not vanish because a re-read failed", "DigiKid", shown?.name)
        }
        coVerify(exactly = 1) { ipfs.fetch(any()) }        // then held by the ten-minute memory
    }

    @Test fun `the retry pass's call re-reads a 4-1-5 row and costs a settled one no request`() = runBlocking {
        row = rowFrom415()
        coEvery { ipfs.fetch(CID) } returns IpfsClient.Fetched(DOCUMENT.toByteArray(), 0, wholeFile = true)
        val service = service()
        repeat(20) { service.rereadForMedia(assetId) }           // twenty sweeps
        assertNull(row!!.imageUrl)
        assertEquals(
            listOf(app.aroundtheblock.wallet.core.model.AssetMediaEntry(MP3, "audio/mpeg", "icon")),
            AssetMediaEntries.decode(row!!.mediaJson),
        )
        coVerify(exactly = 1) { ipfs.fetch(any()) }

        // An asset with no row at all is nothing to read: no request.
        row = null
        service.rereadForMedia(assetId)
        coVerify(exactly = 1) { ipfs.fetch(any()) }
    }

    @Test fun `which rows are read again is one rule, and the retry pass uses it`() {
        org.junit.Assert.assertTrue(AssetMetadataService.needsMediaReread(rowFrom415()))
        for (settled in listOf(
            rowFrom415().copy(mediaJson = "[]"),            // read, declares none
            rowFrom415().copy(metadataCid = null),          // nothing to read it by
            rowFrom415().copy(metadataCid = "null"),        // not a CID (IPFS-NO-NULL)
            rowFrom415().copy(name = null),                 // unnamed: the ordinary retry already asks
        )) org.junit.Assert.assertFalse("$settled", AssetMetadataService.needsMediaReread(settled))

        // Nothing but the metadata retry pass calls getMetadata for an asset that is already named,
        // so without this line an upgraded wallet would keep its 4.1.5 rows for good.
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .first { java.io.File(it, "core/src/main/java").isDirectory }
        val manager = java.io.File(root, "core/src/main/java/app/aroundtheblock/wallet/core/asset/AssetManager.kt").readText()
        val pass = manager.substring(manager.indexOf("private suspend fun retryMissingAssetMetadata()"))
        org.junit.Assert.assertTrue(pass.substringBefore("\n    }\n").contains("metadataService.rereadForMedia(assetId)"))
    }

    // ---------------------------------------------------------------------------------------------
    // The re-read ends (review F4): a 4.1.5 row whose document is gone was asked for with no end,
    // about once per gateway per hour for as long as the wallet was installed.
    // ---------------------------------------------------------------------------------------------

    private val GONE = IpfsClient.Fetched(null, 4, saidNotHere = 1)
    private val UNREACHABLE = IpfsClient.Fetched(null, 4)
    private val HOUR = 60L * 60_000L

    /** A new process: the ten-minute memory and the per-process count are the service's own. */
    private suspend fun launchAndAsk() = service().getMetadata(assetId, null)

    /** The row's "not here" answers, moved back in time as if [ms] had passed since the last one. */
    private fun age(ms: Long) {
        val mark = MediaRereadMark.read(row!!.mediaJson) ?: return
        row = row!!.copy(mediaJson = mark.copy(at = mark.at - ms).encode())
    }

    @Test fun `a document the gateway says is gone is asked for a fixed number of times, then never again`() = runBlocking {
        row = rowFrom415()
        coEvery { ipfs.fetch(CID) } returns GONE
        repeat(40) {                                    // forty launches, an hour apart
            assertEquals("the row stays on screen", "DigiKid", launchAndAsk()?.name)
            age(HOUR)
        }
        coVerify(exactly = AssetMetadataService.MEDIA_REREAD_NOT_HERE_LIMIT) { ipfs.fetch(any()) }
        org.junit.Assert.assertFalse(AssetMetadataService.needsMediaReread(row!!))
        // Given up, and what the row held is kept: nothing was invented in its place.
        assertEquals(MP3, row!!.imageUrl)
        assertEquals(emptyList<app.aroundtheblock.wallet.core.model.AssetMediaEntry>(), AssetMediaEntries.decode(row!!.mediaJson))
    }

    @Test fun `launches minutes apart during one outage count as one answer`() = runBlocking {
        row = rowFrom415()
        coEvery { ipfs.fetch(CID) } returns GONE
        repeat(40) { launchAndAsk() }                   // forty launches in a row, no time between them
        assertEquals(1, MediaRereadMark.read(row!!.mediaJson)!!.notHere)
        org.junit.Assert.assertTrue("not given up on one outage", AssetMetadataService.needsMediaReread(row!!))
    }

    @Test fun `a document that comes back before the limit is read, and the count is gone`() = runBlocking {
        row = rowFrom415()
        coEvery { ipfs.fetch(CID) } returns GONE
        repeat(AssetMetadataService.MEDIA_REREAD_NOT_HERE_LIMIT - 1) { launchAndAsk(); age(HOUR) }
        org.junit.Assert.assertTrue(AssetMetadataService.needsMediaReread(row!!))

        coEvery { ipfs.fetch(CID) } returns IpfsClient.Fetched(DOCUMENT.toByteArray(), 0, wholeFile = true)
        val read = launchAndAsk()!!
        assertNull(read.imageUrl)
        assertEquals(listOf(AssetMediaKind.AUDIO), read.media.map { it.kind })
        assertNull("the row holds the files now, not a count", MediaRereadMark.read(row!!.mediaJson))
        org.junit.Assert.assertFalse(AssetMetadataService.needsMediaReread(row!!))
    }

    /** No network, a timeout, a 5xx: no evidence about the document, so nothing is written to the row. */
    @Test fun `a failure that is not an answer is never held against the document, and still ends within the process`() = runBlocking {
        row = rowFrom415()
        coEvery { ipfs.fetch(CID) } returns UNREACHABLE
        val service = service()
        val failed = AssetMetadataService::class.java.getDeclaredField("recentlyFailed").apply { isAccessible = true }
        repeat(100) {
            assertEquals("DigiKid", service.getMetadata(assetId, null)?.name)
            (failed.get(service) as MutableMap<*, *>).clear()      // ten minutes pass
        }
        coVerify(exactly = AssetMetadataService.MEDIA_REREAD_ROUNDS_PER_PROCESS) { ipfs.fetch(any()) }
        assertNull("nothing recorded: a new process asks again", row!!.mediaJson)
        org.junit.Assert.assertTrue(AssetMetadataService.needsMediaReread(row!!))

        // ...and the next launch does ask again, and reads it when the network is back.
        coEvery { ipfs.fetch(CID) } returns IpfsClient.Fetched(DOCUMENT.toByteArray(), 0, wholeFile = true)
        assertNull(launchAndAsk()!!.imageUrl)
    }

    @Test fun `the count is the column's third shape and nothing else reads as one`() {
        val mark = MediaRereadMark(3, 1_795_000_000_000L)
        assertEquals(mark, MediaRereadMark.read(mark.encode()))
        for (other in listOf(null, "", "[]", """[{"url":"$MP3"}]""", "{}", """{"notHere":0,"at":1}""", """{"notHere":2}""", "{not json", "null"))
            assertNull("'$other'", MediaRereadMark.read(other))
        // Entries are an array, a count is an object: neither is taken for the other.
        assertEquals(emptyList<app.aroundtheblock.wallet.core.model.AssetMediaEntry>(), AssetMediaEntries.decode(mark.encode()))
        // A row marked beyond the limit (or with a damaged count) is settled, not asked for.
        org.junit.Assert.assertFalse(AssetMetadataService.needsMediaReread(rowFrom415().copy(mediaJson = MediaRereadMark(99, 0).encode())))
        org.junit.Assert.assertFalse(AssetMetadataService.needsMediaReread(rowFrom415().copy(mediaJson = "{}")))
    }

    /** No CID, nothing to read again: no provider is asked on this account. */
    @Test fun `a named row with no CID is left as it is`() = runBlocking {
        row = rowFrom415().copy(metadataCid = null)
        assertEquals("DigiKid", service().getMetadata(assetId, null)?.name)
        coVerify(exactly = 0) { ipfs.fetch(any()) }
    }
}
