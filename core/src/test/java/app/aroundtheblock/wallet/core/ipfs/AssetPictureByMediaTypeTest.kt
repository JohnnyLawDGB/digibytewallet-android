package app.aroundtheblock.wallet.core.ipfs

import app.aroundtheblock.wallet.core.db.dao.AssetMetadataDao
import app.aroundtheblock.wallet.core.model.AssetMediaEntry
import app.aroundtheblock.wallet.core.model.AssetMediaKind
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ASSET-MEDIA-CLASSIFY: an asset's picture is chosen by media type, and the files its metadata
 * declares are kept.
 *
 * Until 4.1.5 the parser took the first `urls` entry NAMED "icon" and never read `mimeType`, and
 * dropped every other entry. DigiAsset tools name an audio-only asset's song "icon", so the wallet
 * stored an MP3 as the asset's image, downloaded it on every draw and threw it away, and nothing
 * on screen could say the asset carried a song.
 *
 * The documents are the ones mainnet assets carry (fetched 2026-11-22), CIDs included.
 */
class AssetPictureByMediaTypeTest {

    private val assetId = "La9Ka188DZuoaZQHxU3zgQPH9pwuYpH7BaN6nH"

    private val MP3 = "ipfs://bafybeictgnjgm4gm3lweojytk3fvkcvuqyruwex5viklo4upsxxj5jcdx4"
    private val PNG = "ipfs://bafybeifeb3vptov2gqtldebynhp47x5u7f4rc3iooe7iplozplenqirf7u"
    private val MP4 = "ipfs://bafybeiedl6y2tkbftdlxbmetyr3dn64arfyq3inlbieima2ungs2mxgga4"
    private val JPEG = "ipfs://QmXibaWxG1xR479kfwkwCQEPabi9R81Z6DJaetaoJGNYHm"
    private val ANTHEM_MP3 = "ipfs://QmQANGgTo4PvDP9oYiCRhwn3n8QfwrbHWAMphJ6tYBzSt2"

    private class Stored(val imageUrl: String?, val mediaJson: String?) {
        val media: List<AssetMediaEntry> get() = AssetMediaEntries.decode(mediaJson)
    }

    /** Runs the production parser and returns what it wrote to the row. */
    private fun store(document: String): Stored = runBlocking {
        var image: String? = "never written"
        var media: String? = null
        val dao = mockk<AssetMetadataDao>(relaxed = true)
        coEvery { dao.updateIpfsFacts(any(), any(), any(), any(), any(), any(), any(), any()) } answers { image = arg(6) }
        coEvery { dao.updateMedia(any(), any()) } answers { media = secondArg() }
        coEvery { dao.getMetadata(any()) } returns null
        AssetMetadataService(ipfsClient = mockk(relaxed = true), assetMetadataDao = dao)
            .storeFromJson(assetId, cid = "bafk", jsonRaw = JSONObject(document), provenIssuer = null)
        Stored(image, media)
    }

    private fun urls(vararg entries: String) = """{"data":{"assetName":"X","urls":[${entries.joinToString(",")}]}}"""
    private fun entry(name: String?, url: String, mime: String?) = JSONObject().apply {
        if (name != null) put("name", name)
        put("url", url)
        if (mime != null) put("mimeType", mime)
    }.toString()

    // ---------------------------------------------------------------------------------------------
    // 1. The picture is chosen by media type
    // ---------------------------------------------------------------------------------------------

    /** Asset 5364 "DigiKid": its only entry is an MP3 named "icon". */
    @Test fun `an audio-only asset has no picture, though its song is named icon`() {
        val stored = store(urls(entry("icon", MP3, "audio/mpeg")))
        assertNull("the MP3 must not be stored as the image", stored.imageUrl)
    }

    /** Asset 5252: two entries both named "icon", a JPEG and an MP3. */
    @Test fun `of two entries named icon the image is the picture, whichever comes first`() {
        assertEquals(JPEG, store(urls(entry("icon", JPEG, "image/jpeg"), entry("icon", ANTHEM_MP3, "audio/mpeg"))).imageUrl)
        assertEquals(JPEG, store(urls(entry("icon", ANTHEM_MP3, "audio/mpeg"), entry("icon", JPEG, "image/jpeg"))).imageUrl)
    }

    /** Asset 5376 "Test MP4": a PNG icon and a video named "file". */
    @Test fun `a video beside a picture leaves the picture`() {
        assertEquals(PNG, store(urls(entry("icon", PNG, "image/png"), entry("file", MP4, "video/mp4"))).imageUrl)
        assertNull(store(urls(entry("file", MP4, "video/mp4"))).imageUrl)
    }

    @Test fun `the declared type outranks the name, whatever the name is`() {
        for (name in listOf("icon", "image", "cover.png", "art.jpg", "x.gif", "logo.webp")) {
            for (mime in listOf("video/mp4", "audio/mpeg", "AUDIO/OGG", "video/webm; codecs=vp9", "application/pdf", "text/html")) {
                assertNull("$name declared $mime", store(urls(entry(name, MP4, mime))).imageUrl)
            }
        }
    }

    @Test fun `an image is the picture whatever it is named, and a named icon is preferred`() {
        assertEquals(PNG, store(urls(entry("file", PNG, "image/png"))).imageUrl)
        assertEquals(PNG, store(urls(entry(null, PNG, "image/png"))).imageUrl)
        assertEquals(JPEG, store(urls(entry("file", PNG, "image/png"), entry("icon", JPEG, "image/jpeg"))).imageUrl)
    }

    /** No type declared: the name is the only hint, as before, and the bytes have the last word. */
    @Test fun `an entry that declares no type is judged by its name`() {
        assertEquals(PNG, store(urls(entry("icon", PNG, null))).imageUrl)
        assertEquals(PNG, store(urls(entry("art.png", PNG, null))).imageUrl)
        assertEquals(PNG, store(urls(entry("icon", PNG, "application/octet-stream"))).imageUrl)
        assertNull(store(urls(entry("song.mp3", MP3, null))).imageUrl)
        assertNull(store(urls(entry("clip.mp4", MP4, null))).imageUrl)
        assertNull(store(urls(entry("file", MP4, null))).imageUrl)
        // A typed image beats an untyped "icon".
        assertEquals(JPEG, store(urls(entry("icon", PNG, null), entry("file", JPEG, "image/jpeg"))).imageUrl)
    }

    /** The top-level keys carry no type of their own; `urls` may say what the same file is. */
    @Test fun `a top-level image key is not the picture when the same file is declared audio or video`() {
        for (key in listOf("image", "imageUrl", "image_url", "icon")) {
            val doc = """{"data":{"assetName":"X","$key":"$MP3","urls":[${entry("song", MP3, "audio/mpeg")}]}}"""
            assertNull(key, store(doc).imageUrl)
            assertEquals(key, PNG, store("""{"data":{"assetName":"X","$key":"$PNG"}}""").imageUrl)
            assertNull("$key with a video extension", store("""{"data":{"assetName":"X","$key":"https://x.example/ipfs/a/clip.mp4"}}""").imageUrl)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. The declared files are kept
    // ---------------------------------------------------------------------------------------------

    @Test fun `every declared file is kept with its url, type and name, in order`() {
        val stored = store(urls(entry("icon", PNG, "image/png"), entry("file", MP4, "video/mp4")))
        assertEquals(
            listOf(AssetMediaEntry(PNG, "image/png", "icon"), AssetMediaEntry(MP4, "video/mp4", "file")),
            stored.media,
        )
        assertEquals(listOf(AssetMediaKind.IMAGE, AssetMediaKind.VIDEO), stored.media.map { it.kind })
    }

    @Test fun `the song of an audio-only asset is kept, as audio`() {
        val media = store(urls(entry("icon", MP3, "audio/mpeg"))).media
        assertEquals(listOf(AssetMediaEntry(MP3, "audio/mpeg", "icon")), media)
        assertEquals(AssetMediaKind.AUDIO, media.single().kind)
    }

    /** "Read, and declares none" is told from "never read" (NULL): the row is not read again. */
    @Test fun `a document with no files stores an empty list, not nothing`() {
        assertEquals("[]", store("""{"data":{"assetName":"X"}}""").mediaJson)
        assertEquals("[]", store(urls()).mediaJson)
        assertEquals("[]", store("""{"data":{"assetName":"X","urls":"nope"}}""").mediaJson)
    }

    /** The document is the issuer's: what is kept from it is bounded and cleaned. */
    @Test fun `what is kept is bounded and cleaned`() {
        val many = (1..500).map { entry("f$it", PNG, "image/png") }.toTypedArray()
        assertEquals(AssetMediaEntries.MAX_ENTRIES, store(urls(*many)).media.size)

        val long = "x".repeat(AssetMetadataService.MAX_IMAGE_URL_LEN + 1)
        val stored = store(urls(
            entry("too long", "ipfs://$long", "video/mp4"),            // dropped whole: no usable url
            entry("no url", "", "video/mp4"),                           // dropped
            entry("evil‮gnp.mp4", MP4, "video/mp4"),              // name cleaned as every display text is
            entry("n".repeat(5000), MP3, "audio/" + "a".repeat(500)),   // name cut; a type this long is not one
            entry("odd", MP3, "audio/mpeg\r\nX-Injected: 1"),           // a type with anything but type characters is not one
        ))
        assertEquals(3, stored.media.size)
        assertEquals("evilgnp.mp4", stored.media[0].name)
        assertEquals(AssetMetadataService.MAX_SHORT_TEXT_LEN, stored.media[1].name!!.length)
        assertNull(stored.media[1].mimeType)
        assertNull(stored.media[2].mimeType)
        assertTrue(stored.mediaJson!!.length < 16 * 4096)
    }

    @Test fun `what is stored reads back the same, and anything else reads as nothing`() {
        val media = listOf(AssetMediaEntry(PNG, "image/png", "icon"), AssetMediaEntry(MP4, null, null))
        assertEquals(media, AssetMediaEntries.decode(AssetMediaEntries.encode(media)))
        for (bad in listOf(null, "", "null", "{}", "[1,2]", "not json", """[{"mimeType":"video/mp4"}]""")) {
            assertEquals("'$bad'", emptyList<AssetMediaEntry>(), AssetMediaEntries.decode(bad))
        }
    }
}
