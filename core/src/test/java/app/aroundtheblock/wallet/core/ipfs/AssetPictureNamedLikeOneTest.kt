package app.aroundtheblock.wallet.core.ipfs

import app.aroundtheblock.wallet.core.model.AssetMediaEntry
import app.aroundtheblock.wallet.core.model.AssetMediaKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ASSET-MEDIA-FIXES, second review (R1 and its notes): what 4.1.5 showed as a picture and a
 * declared type must not take away.
 *
 * 4.1.5 never read `mimeType`. It took the first `urls` entry NAMED like a picture: "icon",
 * "image", or a name ending `.png`, `.jpg`, `.jpeg`, `.gif`, `.svg`, `.webp`, wherever in the
 * array it stood. Only a declared `audio/…` or `video/…` may now rule such an entry out unseen.
 */
class AssetPictureNamedLikeOneTest {

    private val PNG = "ipfs://bafybeifeb3vptov2gqtldebynhp47x5u7f4rc3iooe7iplozplenqirf7u"
    private val JPEG = "ipfs://QmXibaWxG1xR479kfwkwCQEPabi9R81Z6DJaetaoJGNYHm"
    private val MP3 = "ipfs://bafybeictgnjgm4gm3lweojytk3fvkcvuqyruwex5viklo4upsxxj5jcdx4"
    private val CID = "bafybeifeb3vptov2gqtldebynhp47x5u7f4rc3iooe7iplozplenqirf7u"

    private fun parse(vararg entries: String) =
        AssetMediaEntries.parse(JSONObject("""{"assetName":"X","urls":[${entries.joinToString(",")}]}"""))

    private fun entry(name: String?, url: String, mime: String?) = JSONObject().apply {
        if (name != null) put("name", name)
        put("url", url)
        if (mime != null) put("mimeType", mime)
    }.toString()

    /** Every name 4.1.5 picked by, with every kind of wrong type that is not audio or video. */
    @Test fun `an entry named with an image file extension and a wrong type is judged by its bytes`() {
        val names = listOf("logo.png", "photo.jpg", "photo.jpeg", "anim.gif", "mark.svg", "art.webp", "LOGO.PNG", "icon", "image")
        val types = listOf("binary/octet-stream", "application/png", "application/pdf", "text/html", "imagee/png", "application/x-whatever")
        for (name in names) for (type in types) {
            assertEquals("$name declared $type", PNG, parse(entry(name, PNG, type)).imageUrl)
        }
    }

    @Test fun `audio and video still rule such an entry out unseen, whatever it is named`() {
        for (name in listOf("logo.png", "icon", "image", "cover.jpg")) {
            for (type in listOf("audio/mpeg", "audio/x-anything", "video/mp4", "video/quicktime", "AUDIO/MPEG")) {
                assertNull("$name declared $type", parse(entry(name, MP3, type)).imageUrl)
            }
        }
        // A wrong type on an entry whose own name says audio or video: not the picture either.
        assertNull(parse(entry("song.mp3", MP3, "binary/octet-stream")).imageUrl)
        assertNull(parse(entry("clip.mov", MP3, "application/pdf")).imageUrl)
        // And a wrong type on an entry whose name says nothing is not picked: 4.1.5 did not pick it.
        assertNull(parse(entry("file", PNG, "application/pdf")).imageUrl)
        assertNull(parse(entry(null, PNG, "text/html")).imageUrl)
    }

    @Test fun `an entry that is an image still comes first`() {
        val parsed = parse(entry("logo.png", PNG, "application/pdf"), entry("file", JPEG, "image/jpeg"))
        assertEquals(JPEG, parsed.imageUrl)
        assertEquals(listOf(AssetMediaKind.OTHER, AssetMediaKind.IMAGE), parsed.media.map { it.kind })
    }

    // ---------------------------------------------------------------------------------------------
    // Note: the picture past the 16th entry
    // ---------------------------------------------------------------------------------------------

    /** 4.1.5 walked the whole array. The cap is on what is STORED; it must not hide the picture. */
    @Test fun `the picture is found wherever it stands in the array, and is among what is kept`() {
        for (position in listOf(16, 17, 40, 300, 2000)) {
            val before = (1..position).map { entry("track$it.mp3", MP3, "audio/mpeg") }
            for (picture in listOf(entry("icon", PNG, "image/png"), entry("icon", PNG, null), entry("logo.png", PNG, "binary/octet-stream"))) {
                val parsed = parse(*(before + picture).toTypedArray())
                assertEquals("picture after $position entries", PNG, parsed.imageUrl)
                assertEquals(AssetMediaEntries.MAX_ENTRIES, parsed.media.size)
                assertTrue("the picture is among the kept entries", parsed.media.any { it.url == PNG })
                // The rest are the first ones, in the document's order.
                assertEquals((1 until AssetMediaEntries.MAX_ENTRIES).map { "track$it.mp3" }, parsed.media.dropLast(1).map { it.name })
            }
        }
    }

    /** The same walk decides whether a top-level picture is the same file as a declared song. */
    @Test fun `a top-level picture is ruled out by an audio entry for the same file wherever that entry stands`() {
        val before = (1..40).map { entry("f$it", JPEG, "image/jpeg") }
        val doc = JSONObject("""{"image":"$MP3","urls":[${(before + entry("icon", MP3, "audio/mpeg")).joinToString(",")}]}""")
        assertNull(AssetMediaEntries.parse(doc).imageUrl)
    }

    @Test fun `what is kept stays bounded however long the array is`() {
        val many = (1..5000).map { entry("f$it", PNG, "image/png") }.toTypedArray()
        val parsed = parse(*many)
        assertEquals(AssetMediaEntries.MAX_ENTRIES, parsed.media.size)
        assertEquals(AssetMediaEntries.MAX_ENTRIES, AssetMediaEntries.decode(AssetMediaEntries.encode(parsed.media)).size)
    }

    // ---------------------------------------------------------------------------------------------
    // Note: a file extension is read from the URL's path, never from its host
    // ---------------------------------------------------------------------------------------------

    @Test fun `a host that ends like a file extension says nothing about the file`() {
        for (hostOnly in listOf(
            "https://$CID.ipfs.example.mov", "https://$CID.ipfs.example.mp3", "https://$CID.ipfs.example.png",
            "https://example.mov", "https://example.mp4?x=1", "https://example.mp3#top", "http://cdn.example.avi:8080",
            "https://example.mov/", "https://example.mov/ipfs/$CID",
        )) {
            assertEquals(hostOnly, AssetMediaKind.UNKNOWN, AssetMediaKind.of(null, null, hostOnly))
        }
        // So such a picture is not ruled out as a video:
        assertEquals("https://$CID.ipfs.example.mov", parse(entry("icon", "https://$CID.ipfs.example.mov", null)).imageUrl)
        assertTrue(AssetMediaEntry("https://$CID.ipfs.example.mov", "binary/octet-stream", "icon").mayBePicture)
    }

    @Test fun `the path's extension is still read, in every spelling of a URL`() {
        val cases = mapOf(
            "ipfs://$CID/song.mp3" to AssetMediaKind.AUDIO,
            "/ipfs/$CID/clip.mp4" to AssetMediaKind.VIDEO,
            "$CID/logo.png" to AssetMediaKind.IMAGE,
            "https://example.com/a/b/clip.MOV?download=1#t=3" to AssetMediaKind.VIDEO,
            "https://$CID.ipfs.example.com/cover.jpg" to AssetMediaKind.IMAGE,
            "https://example.mov/photo.png" to AssetMediaKind.IMAGE,
            "song.mp3" to AssetMediaKind.AUDIO,
            // A dot in a directory name or in the query is not the file's extension.
            "https://example.com/v1.mp4/file" to AssetMediaKind.UNKNOWN,
            "https://example.com/file?name=a.mp4" to AssetMediaKind.UNKNOWN,
            "ipfs://$CID" to AssetMediaKind.UNKNOWN,
        )
        for ((url, kind) in cases) assertEquals(url, kind, AssetMediaKind.of(null, null, url))
    }

    // ---------------------------------------------------------------------------------------------
    // Note: a gateway URL with a space in its query
    // ---------------------------------------------------------------------------------------------

    /** 4.1.5 loaded this picture: the loader takes the CID from the URL and never reads the query. */
    @Test fun `a gateway URL with a space in its query is kept, and names its CID`() {
        val url = "https://ipfs.io/ipfs/$CID?filename=my logo.png"
        val parsed = parse(entry("icon", url, "image/png"))
        assertEquals(url, parsed.imageUrl)
        val kept = parsed.media.single()
        assertEquals(url, kept.url)
        assertEquals(CID, kept.cid)
        assertTrue(kept.fetchable)
        // The query is not the path: its ".png" is not read as the file's extension.
        assertEquals(AssetMediaKind.UNKNOWN, AssetMediaKind.of(null, null, url))
        assertEquals(listOf(kept), AssetMediaEntries.decode(AssetMediaEntries.encode(parsed.media)))
    }

    @Test fun `any other whitespace or unseen character in a URL still drops the entry`() {
        for (bad in listOf("ipfs://$CID\n", "ipfs://$CID\tx", "ipfs://$CID x", "ipfs://$CID x", "ipfs://$CID‮gnp", "ipfs://$CID\u0000")) {
            // (Leading and trailing whitespace is trimmed first, as before; what is left must be clean.)
            val parsed = parse(entry("icon", "x$bad" + "y", "image/png"))
            assertFalse(bad.map { it.code }.toString(), parsed.media.any())
            assertNull(parsed.imageUrl)
        }
    }
}
