package app.aroundtheblock.wallet.ui.hub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A JPEG leaves the phone without its metadata. The fixture `hub/gps.jpg` is a 64 x 48 JPEG with
 * an EXIF block holding a GPS position (51°30'12.5"N 0°7'39"W), a camera make ("FixtureCam") and
 * an orientation, plus a comment ("secret comment"). What [HubImageReader.prepare] hands the upload
 * carries none of them, whether or not the on-phone re-encode ran, and is still a whole JPEG (on the JVM there is no
 * decoder to try it with; the picture's bytes are checked to be kept byte for byte instead).
 * PNG, WebP and GIF go through unchanged (the server re-encodes them; a GIF re-encoded here would
 * lose its animation).
 */
class HubImageMetadataTest {

    private val fixture: ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("hub/gps.jpg")) { "fixture missing" }.use { it.readBytes() }

    private fun ByteArray.contains(text: String): Boolean = String(this, Charsets.ISO_8859_1).contains(text)

    /** The markers of every segment before the scan. */
    private fun segmentMarkers(jpeg: ByteArray): List<Int> {
        val markers = mutableListOf<Int>()
        var at = 2
        while (at + 3 < jpeg.size) {
            val marker = jpeg[at + 1].toInt() and 0xFF
            markers += marker
            if (marker == 0xDA) break
            at += 2 + (((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF))
        }
        return markers
    }

    /** Negative control: the fixture really carries what must not leave the phone. */
    @Test fun `the fixture carries GPS EXIF, a camera make and a comment`() {
        assertTrue(fixture.contains("Exif\u0000\u0000"))
        assertTrue(fixture.contains("FixtureCam"))
        assertTrue(fixture.contains("secret comment"))
        assertTrue(0xE1 in segmentMarkers(fixture))
        assertTrue(JpegMetadata.hasMetadata(fixture))
    }

    @Test fun `what is uploaded for a JPEG has no EXIF, GPS, camera or comment, and is still a whole JPEG`() {
        // Whatever the re-encode does: here it hands the original back untouched, metadata and all.
        for (reencode in listOf<(ByteArray) -> ByteArray?>({ it }, { null })) {
            val sent = HubImageReader.prepare(fixture, "image/jpeg", reencode)
            assertNotNull(sent)
            sent!!
            assertFalse(sent.contains("Exif"))
            assertFalse(sent.contains("FixtureCam"))
            assertFalse(sent.contains("secret comment"))
            assertFalse(JpegMetadata.hasMetadata(sent))
            val markers = segmentMarkers(sent)
            assertTrue("an APPn or COM segment is left: $markers", markers.none { it in 0xE1..0xEF || it == 0xFE })
            // Still a whole JPEG: its start, tables, a 64 x 48 frame, the scan and its end.
            assertEquals(listOf(0xFF, 0xD8), sent.take(2).map { it.toInt() and 0xFF })
            assertEquals(listOf(0xFF, 0xD9), sent.takeLast(2).map { it.toInt() and 0xFF })
            assertTrue("no quantisation table: $markers", 0xDB in markers)
            assertTrue("no Huffman table: $markers", 0xC4 in markers)
            assertEquals(0xDA, markers.last())
            val sof = String(sent, Charsets.ISO_8859_1).indexOf("\u00FF\u00C0")
            assertTrue("no baseline frame", sof > 0)
            val height = ((sent[sof + 5].toInt() and 0xFF) shl 8) or (sent[sof + 6].toInt() and 0xFF)
            val width = ((sent[sof + 7].toInt() and 0xFF) shl 8) or (sent[sof + 8].toInt() and 0xFF)
            assertEquals(64 to 48, width to height)
        }
    }

    @Test fun `the picture data is kept byte for byte`() {
        val sent = JpegMetadata.strip(fixture)!!
        val scan = { b: ByteArray -> String(b, Charsets.ISO_8859_1).let { it.substring(it.indexOf("ÿÚ")) } }
        assertEquals(scan(fixture), scan(sent))
    }

    @Test fun `PNG, WebP and GIF are sent unchanged`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        for (type in listOf("image/png", "image/webp", "image/gif")) {
            assertArrayEquals(type, bytes, HubImageReader.prepare(bytes, type) { error("re-encoded $type") })
        }
    }

    @Test fun `a JPEG that cannot be walked is not sent`() {
        assertNull(HubImageReader.prepare(byteArrayOf(1, 2, 3, 4), "image/jpeg") { null })
        assertNull(HubImageReader.prepare(fixture.copyOf(20), "image/jpeg") { null })
    }

    @Test fun `the decode sample size keeps the long side at least the cap`() {
        assertEquals(1, HubImageReader.sampleSizeFor(2048, 1000))
        assertEquals(1, HubImageReader.sampleSizeFor(4095, 3000))
        assertEquals(2, HubImageReader.sampleSizeFor(4096, 3072))
        assertEquals(4, HubImageReader.sampleSizeFor(12_000, 9_000))
    }
}
