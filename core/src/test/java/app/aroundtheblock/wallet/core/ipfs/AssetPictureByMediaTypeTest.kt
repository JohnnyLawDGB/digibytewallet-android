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
            for (mime in listOf("video/mp4", "audio/mpeg", "AUDIO/OGG", "video/webm; codecs=vp9")) {
                assertNull("$name declared $mime", store(urls(entry(name, MP4, mime))).imageUrl)
            }
        }
        // A declared type that is neither image, audio nor video is still no picture under a file's
        // own name. (Under the name "icon" or "image" it is judged by its bytes: review F2, below.)
        for (name in listOf("cover.png", "art.jpg", "x.gif", "logo.webp", "file", null)) {
            for (mime in listOf("application/pdf", "text/html")) {
                assertNull("$name declared $mime", store(urls(entry(name, PNG, mime))).imageUrl)
            }
        }
    }

    /**
     * Review F2. 4.1.5 showed the picture of an entry named "icon" whatever type it declared. Only
     * `audio/…` and `video/…` may take that away without the bytes being looked at: a wrong or
     * meaningless declared type (`binary/octet-stream`, `application/png`, a typo) decides nothing.
     */
    @Test fun `an entry named as the picture with a type that is neither image, audio nor video is judged by its bytes`() {
        for (name in listOf("icon", "image", "Icon", "IMAGE")) {
            for (mime in listOf("binary/octet-stream", "application/png", "application/x-png", "imagee/png", "img/png", "text/plain", "application/pdf", "text/html", "model/gltf-binary")) {
                assertEquals("$name declared $mime", PNG, store(urls(entry(name, PNG, mime))).imageUrl)
            }
        }
        // It is still kept as what it declared, and it never outranks an entry that IS an image.
        val stored = store(urls(entry("icon", PNG, "application/png"), entry("file", JPEG, "image/jpeg")))
        assertEquals(JPEG, stored.imageUrl)
        assertEquals(listOf(AssetMediaKind.OTHER, AssetMediaKind.IMAGE), stored.media.map { it.kind })
        // Audio and video are ruled out as before, by declaration and, with no usable type, by extension.
        assertNull(store(urls(entry("icon", MP3, "audio/mpeg"))).imageUrl)
        assertNull(store(urls(entry("icon", MP4, "video/mp4"))).imageUrl)
        assertNull(store(urls(entry("icon", "$MP3/song.mp3", "binary/octet-stream"))).imageUrl)
        assertNull(store(urls(entry("icon", "$MP4/clip.mp4", "application/x-whatever"))).imageUrl)
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

    /**
     * Review F3 (the Codex finding). The loader fetches a bare CID, `ipfs://<cid>`, `/ipfs/<cid>` and
     * a gateway URL for it as ONE file, so the audio declaration must be found whichever way each
     * side spells it. Compared as text, a top-level bare CID beside an `ipfs://` audio entry was
     * taken as the picture, and the song was downloaded as one.
     */
    @Test fun `a top-level picture is matched to its urls entry by the file it names, not by its spelling`() {
        val cid = MP3.removePrefix("ipfs://")
        val spellings = listOf(
            cid, "ipfs://$cid", "/ipfs/$cid",
            "https://ipfs.io/ipfs/$cid", "https://gateway.example/ipfs/$cid?filename=icon", "https://$cid.ipfs.dweb.link/", "ipfs://$cid/",
        )
        for (top in spellings) for (inUrls in spellings) {
            for (mime in listOf("audio/mpeg", "video/mp4")) {
                val doc = JSONObject().put("assetName", "X").put("image", top)
                    .put("urls", org.json.JSONArray().put(JSONObject(entry("song", inUrls, mime))))
                assertNull("top-level '$top', declared $mime as '$inUrls'", store(JSONObject().put("data", doc).toString()).imageUrl)
            }
            // The same pairing declared an image, or declared nothing, is still the picture, as written.
            for (mime in listOf("image/png", null, "application/octet-stream")) {
                val doc = JSONObject().put("assetName", "X").put("image", top)
                    .put("urls", org.json.JSONArray().put(JSONObject(entry("file", inUrls, mime))))
                assertEquals("top-level '$top', declared $mime as '$inUrls'", top, store(JSONObject().put("data", doc).toString()).imageUrl)
            }
        }
    }

    @Test fun `a top-level picture is not ruled out by another file's declaration`() {
        val cid = MP3.removePrefix("ipfs://")
        // A different CID, and a file INSIDE the same CID, are other files.
        for (other in listOf(MP4, "ipfs://$cid/song.mp3", "https://ipfs.io/ipfs/$cid/song.mp3", "https://example.com/$cid")) {
            val doc = """{"data":{"assetName":"X","image":"$cid","urls":[${entry("song", other, "audio/mpeg")}]}}"""
            assertEquals(other, cid, store(doc).imageUrl)
        }
        // A web URL that names no CID is matched as text, as before.
        val web = "https://example.com/a"
        assertNull(store("""{"data":{"assetName":"X","image":"$web","urls":[${entry("song", web, "audio/mpeg")}]}}""").imageUrl)
    }

    // ---------------------------------------------------------------------------------------------
    // 1b. What may be fetched for an entry (for the player this model is kept for)
    // ---------------------------------------------------------------------------------------------

    @Test fun `an entry's kind survives when there is no CID, and such an entry is not fetchable`() {
        val stored = store(urls(
            entry("clip", "https://cdn.issuer.example/clip.mp4", "video/mp4"),
            entry("song.mp3", "http://issuer.example/a", null),
            entry("page", "https://issuer.example/", "text/html"),
        ))
        assertEquals(listOf(AssetMediaKind.VIDEO, AssetMediaKind.AUDIO, AssetMediaKind.OTHER), stored.media.map { it.kind })
        for (e in stored.media) {
            assertNull("${e.url}: a web URL names no CID", e.cid)
            assertTrue("${e.url}: and is never fetched", !e.fetchable)
        }
        assertNull("no picture among them", stored.imageUrl)
    }

    @Test fun `an entry's CID is the one the picture loader would fetch, however the URL is written`() {
        val cid = MP4.removePrefix("ipfs://")
        for (url in listOf(cid, "ipfs://$cid", "/ipfs/$cid", "https://ipfs.io/ipfs/$cid", "https://$cid.ipfs.dweb.link", "ipfs://$cid?filename=a.mp4", "ipfs://$cid/")) {
            val e = store(urls(entry("file", url, "video/mp4"))).media.single()
            assertEquals(url, cid, e.cid)
            assertTrue(url, e.fetchable)
            assertEquals(url, AssetMediaKind.VIDEO, e.kind)
            assertEquals("stored as written: one copy of where the file is", url, e.url)
        }
    }

    /** `<cid>/song.mp3` is a file inside a directory. The bare CID is the directory, not the file. */
    @Test fun `a CID with a path is kept with its kind and marked not fetchable`() {
        val dir = MP3.removePrefix("ipfs://")
        for (url in listOf("ipfs://$dir/song.mp3", "/ipfs/$dir/album/song.mp3", "https://ipfs.io/ipfs/$dir/song.mp3", "https://$dir.ipfs.dweb.link/song.mp3")) {
            val e = store(urls(entry("file", url, "audio/mpeg"))).media.single()
            assertEquals(url, AssetMediaKind.AUDIO, e.kind)
            assertEquals("$url: the CID named is the directory's", dir, e.cid)
            assertTrue("$url: so it must not be fetched as the file", !e.fetchable)
        }
    }

    @Test fun `an entry whose URL is neither a CID nor a web URL is kept and names nothing`() {
        for (url in listOf("ipfs://null", "ipfs://not-a-cid/x.mp4", "ar://abc", "data:video/mp4;base64,AAAA", "clip.mp4")) {
            val e = store(urls(entry("file", url, "video/mp4"))).media.single()
            assertEquals(url, AssetMediaKind.VIDEO, e.kind)
            assertNull(url, e.cid)
            assertTrue(url, !e.fetchable)
        }
    }

    /** Derived on reading, so the stored JSON is what it was: url, mimeType, name and nothing else. */
    @Test fun `the stored shape carries no cid and no flag`() {
        val json = store(urls(entry("file", "$MP3/song.mp3", "audio/mpeg"), entry("clip", "https://x.example/c.mp4", "video/mp4"))).mediaJson!!
        val arr = org.json.JSONArray(json)
        for (i in 0 until arr.length()) {
            assertEquals(setOf("url", "mimeType", "name"), arr.getJSONObject(i).keys().asSequence().toSet())
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
