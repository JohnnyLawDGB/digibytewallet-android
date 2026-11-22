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
        coEvery { ipfs.fetchVerified(CID) } returns DOCUMENT.toByteArray()
        val service = service()

        val first = service.getMetadata(assetId, null)
        assertNull("the MP3 is no longer the picture", first!!.imageUrl)
        assertEquals(listOf(AssetMediaKind.AUDIO), first.media.map { it.kind })
        assertEquals("the chain's supply is untouched", 100L, first.totalSupply)

        // Marked as read: every later call is answered from the row.
        repeat(5) { assertNull(service.getMetadata(assetId, null)!!.imageUrl) }
        coVerify(exactly = 1) { ipfs.fetchVerified(any()) }
    }

    @Test fun `a row already read for media is never fetched again`() = runBlocking {
        row = rowFrom415().copy(imageUrl = null, mediaJson = "[]")
        assertNotNull(service().getMetadata(assetId, CID))
        coVerify(exactly = 0) { ipfs.fetchVerified(any()) }
    }

    @Test fun `when the document cannot be fetched the row is still shown, and asked for no more often than any failure`() = runBlocking {
        row = rowFrom415()
        coEvery { ipfs.fetchVerified(CID) } returns null
        val service = service()
        repeat(5) {
            val shown = service.getMetadata(assetId, null)
            assertEquals("a name the wallet has must not vanish because a re-read failed", "DigiKid", shown?.name)
        }
        coVerify(exactly = 1) { ipfs.fetchVerified(any()) }        // then held by the ten-minute memory
    }

    @Test fun `the retry pass's call re-reads a 4-1-5 row and costs a settled one no request`() = runBlocking {
        row = rowFrom415()
        coEvery { ipfs.fetchVerified(CID) } returns DOCUMENT.toByteArray()
        val service = service()
        repeat(20) { service.rereadForMedia(assetId) }           // twenty sweeps
        assertNull(row!!.imageUrl)
        assertEquals(
            listOf(app.aroundtheblock.wallet.core.model.AssetMediaEntry(MP3, "audio/mpeg", "icon")),
            AssetMediaEntries.decode(row!!.mediaJson),
        )
        coVerify(exactly = 1) { ipfs.fetchVerified(any()) }

        // An asset with no row at all is nothing to read: no request.
        row = null
        service.rereadForMedia(assetId)
        coVerify(exactly = 1) { ipfs.fetchVerified(any()) }
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

    /** No CID, nothing to read again: no provider is asked on this account. */
    @Test fun `a named row with no CID is left as it is`() = runBlocking {
        row = rowFrom415().copy(metadataCid = null)
        assertEquals("DigiKid", service().getMetadata(assetId, null)?.name)
        coVerify(exactly = 0) { ipfs.fetchVerified(any()) }
    }
}
