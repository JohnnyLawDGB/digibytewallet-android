package app.aroundtheblock.wallet.ui.hub

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a picked image becomes before it is uploaded ([HubImageReader]):
 *  - its type is found in its bytes, never taken from the picker; anything but PNG, JPEG, GIF or
 *    WebP is refused before an upload;
 *  - a JPEG is always decoded and encoded again, and what is uploaded is only the new encoding:
 *    no EXIF (GPS), no comment, no segment hidden after the picture, nothing after its end;
 *  - a JPEG that cannot be re-encoded is refused, never sent as it was;
 *  - PNG, GIF and WebP go unchanged (the server re-encodes them).
 *
 * The app re-encodes with Android's Bitmap, which a JVM test cannot run; here the re-encode is the
 * JDK's own JPEG codec (javax.imageio, reached by reflection: the Android compile classpath has no
 * javax.imageio), a real decode and encode like Bitmap's. That Bitmap.compress writes no metadata
 * is Android's documented behaviour and is checked on a device, not here.
 *
 * The fixture `hub/gps.jpg` is a 64 x 48 JPEG with an EXIF block holding a GPS position, a camera
 * make ("FixtureCam") and an orientation, and a comment ("secret comment").
 */
class HubImageUploadPrepTest {

    private val gpsJpeg: ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("hub/gps.jpg")) { "fixture missing" }.use { it.readBytes() }

    /**
     * The fixture with more appended after its end: bytes that look like a start of scan, an
     * APP1 EXIF segment, and a plain trailer.
     */
    private val hostileJpeg: ByteArray = gpsJpeg +
        byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x04, 0x00, 0x00) +
        segment(0xE1, "Exif\u0000\u0000SECRET-AFTER-SCAN".toByteArray(Charsets.ISO_8859_1)) +
        "TRAILER-SECRET".toByteArray(Charsets.ISO_8859_1)

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val gifBytes = "GIF89a".toByteArray() + byteArrayOf(1, 2, 3)
    private val webpBytes = "RIFF".toByteArray() + byteArrayOf(4, 0, 0, 0) + "WEBPVP8 ".toByteArray()

    private fun segment(marker: Int, body: ByteArray): ByteArray {
        val length = body.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + body
    }

    /**
     * A real JPEG codec: the JDK's (see the class comment). [bounds] reads the header only, as
     * BitmapFactory's `inJustDecodeBounds` does; [reencode] decodes and encodes again, and counts
     * its calls.
     */
    private open class JdkCodec : JpegCodec {
        var reencodes = 0
        private val imageIO = Class.forName("javax.imageio.ImageIO")

        init { System.setProperty("java.awt.headless", "true") }

        override fun bounds(jpeg: ByteArray): Pair<Int, Int>? {
            val input = imageIO.getMethod("createImageInputStream", Any::class.java).invoke(null, ByteArrayInputStream(jpeg))
            val readers = imageIO.getMethod("getImageReaders", Any::class.java).invoke(null, input) as Iterator<*>
            val reader = readers.next() ?: return null
            // Looked up on the public ImageReader type: the JPEG reader's own class is not exported.
            val api = Class.forName("javax.imageio.ImageReader")
            api.getMethod("setInput", Any::class.java).invoke(reader, input)
            val width = api.getMethod("getWidth", Int::class.javaPrimitiveType).invoke(reader, 0) as Int
            val height = api.getMethod("getHeight", Int::class.javaPrimitiveType).invoke(reader, 0) as Int
            return width to height
        }

        override fun reencode(jpeg: ByteArray, sampleSize: Int): ByteArray? {
            reencodes++
            val image = imageIO.getMethod("read", java.io.InputStream::class.java).invoke(null, ByteArrayInputStream(jpeg))
                ?: return null
            val out = ByteArrayOutputStream()
            val write = imageIO.getMethod(
                "write", Class.forName("java.awt.image.RenderedImage"), String::class.java, java.io.OutputStream::class.java,
            )
            return if (write.invoke(null, image, "jpg", out) == true) out.toByteArray() else null
        }
    }

    /** A codec that reads sizes as the JDK does but whose re-encode is [reencode]. */
    private class Codec(val reencode: (ByteArray) -> ByteArray?) : JdkCodec() {
        override fun reencode(jpeg: ByteArray, sampleSize: Int): ByteArray? {
            reencodes++
            return reencode(jpeg)
        }
    }

    /** Never re-encodes; for inputs that must not reach a re-encode. */
    private val never = Codec { error("re-encoded") }

    private fun ByteArray.has(text: String) = String(this, Charsets.ISO_8859_1).contains(text)

    /** The markers before the start of scan (test-only reading; the app parses no JPEG itself). */
    private fun headerMarkers(jpeg: ByteArray): List<Int> {
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

    private fun prepared(result: HubImageReader.Result): PickedImage {
        assertTrue("refused: ${(result as? HubImageReader.Result.Refused)?.problem}", result is HubImageReader.Result.Read)
        return (result as HubImageReader.Result.Read).image
    }

    // ── JPEG ───────────────────────────────────────────────────────────────────

    /** Negative control: the input really carries everything that must not leave the phone. */
    @Test fun `the hostile JPEG carries GPS EXIF, a comment, a segment after the scan and a trailer`() {
        for (text in listOf("Exif\u0000\u0000", "FixtureCam", "secret comment", "SECRET-AFTER-SCAN", "TRAILER-SECRET")) {
            assertTrue(text, hostileJpeg.has(text))
        }
        assertTrue(0xE1 in headerMarkers(hostileJpeg))
        assertTrue(0xFE in headerMarkers(hostileJpeg))
    }

    @Test fun `what is uploaded for a JPEG is only the new encoding`() {
        val image = prepared(HubImageReader.prepare(hostileJpeg, JdkCodec()))

        assertEquals("image/jpeg", image.mimeType)
        val sent = image.bytes
        for (text in listOf("Exif", "FixtureCam", "secret comment", "SECRET", "TRAILER")) {
            assertFalse("'$text' was uploaded", sent.has(text))
        }
        val markers = headerMarkers(sent)
        assertTrue("APP1, APP13 or COM is left: $markers", markers.none { it == 0xE1 || it == 0xED || it == 0xFE })
        assertEquals("nothing may follow the end of the picture", listOf(0xFF, 0xD9), sent.takeLast(2).map { it.toInt() and 0xFF })
        assertEquals("the picture is cut short or has more after it", sent.size - 2,
            String(sent, Charsets.ISO_8859_1).indexOf("ÿÙ", String(sent, Charsets.ISO_8859_1).indexOf("ÿÚ")))
    }

    /** The picker's word is not trusted: a JPEG it calls a PNG is still found to be a JPEG and re-encoded. */
    @Test fun `a JPEG the picker calls a PNG is still re-encoded`() {
        val resolver = mockk<ContentResolver>()
        val uri = mockk<Uri>()
        every { resolver.getType(uri) } returns "image/png"
        every { resolver.openInputStream(uri) } answers { ByteArrayInputStream(hostileJpeg) }
        val context = mockk<Context>()
        every { context.contentResolver } returns resolver
        val codec = JdkCodec()
        val reader = HubImageReader(context, codec)

        val image = prepared(runBlocking { reader.read(uri) })

        assertEquals(1, codec.reencodes)
        assertEquals("image/jpeg", image.mimeType)
        assertFalse(image.bytes.has("Exif"))
    }

    @Test fun `a JPEG that cannot be re-encoded is refused, never sent as it was`() {
        val failing = listOf(
            Codec { null },
            Codec { error("decoder crashed") },
            Codec { pngBytes }, // what came back is not a JPEG
        )
        for ((i, reencoder) in failing.withIndex()) {
            val result = HubImageReader.prepare(hostileJpeg, reencoder)
            assertTrue("case $i: $result", result is HubImageReader.Result.Refused)
            assertEquals(PickedImageProblem.NOT_PREPARED, (result as HubImageReader.Result.Refused).problem)
        }
    }

    // ── other types ────────────────────────────────────────────────────────────

    @Test fun `PNG, GIF and WebP are sent unchanged, typed by their content, and never re-encoded`() {
        for ((bytes, type) in listOf(pngBytes to "image/png", gifBytes to "image/gif", webpBytes to "image/webp")) {
            val image = prepared(HubImageReader.prepare(bytes, never))
            assertEquals(type, image.mimeType)
            assertArrayEquals(type, bytes, image.bytes)
        }
    }

    @Test fun `anything that is not one of the four image types is refused before an upload`() {
        val others = listOf(
            "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray(),
            "<html><script>x</script></html>".toByteArray(),
            "%PDF-1.7".toByteArray(),
            "RIFF\u0004\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1),   // RIFF, but not WebP
            "GIF90a".toByteArray(),
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()),                                 // a JPEG's first two bytes only
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),                              // a PNG's first four only
            ByteArray(64),
        )
        for (bytes in others) {
            val result = HubImageReader.prepare(bytes, never)
            assertTrue(String(bytes, Charsets.ISO_8859_1), result is HubImageReader.Result.Refused)
            assertEquals(PickedImageProblem.WRONG_TYPE, (result as HubImageReader.Result.Refused).problem)
        }
    }

    // ── memory ─────────────────────────────────────────────────────────────────

    /** [jpeg] with its baseline frame header (SOF0) claiming [width] x [height]. */
    private fun claiming(jpeg: ByteArray, width: Int, height: Int): ByteArray {
        val copy = jpeg.copyOf()
        val sof = String(copy, Charsets.ISO_8859_1).indexOf("\u00FF\u00C0")
        require(sof > 0) { "no SOF0 in the fixture" }
        copy[sof + 5] = (height shr 8).toByte(); copy[sof + 6] = height.toByte()
        copy[sof + 7] = (width shr 8).toByte(); copy[sof + 8] = width.toByte()
        return copy
    }

    @Test fun `a JPEG claiming more than 50 megapixels is refused before it is decoded`() {
        val bomb = claiming(gpsJpeg, 65_500, 65_500)
        assertEquals("the crafted header does not read as claimed", 65_500 to 65_500, JdkCodec().bounds(bomb))
        val codec = Codec { null }

        val result = HubImageReader.prepare(bomb, codec)

        assertTrue("$result", result is HubImageReader.Result.Refused)
        assertEquals(PickedImageProblem.TOO_MANY_PIXELS, (result as HubImageReader.Result.Refused).problem)
        assertEquals("it was handed to the decoder", 0, codec.reencodes)
    }

    @Test fun `a JPEG just under 50 megapixels is decoded, at a sample size that brings it to the cap`() {
        var sample = 0
        val codec = object : JdkCodec() {
            override fun reencode(jpeg: ByteArray, sampleSize: Int): ByteArray? { sample = sampleSize; return super.reencode(gpsJpeg, 1) }
        }
        prepared(HubImageReader.prepare(claiming(gpsJpeg, 8_000, 6_000), codec))
        assertEquals(4, sample) // 8000 / 4 = 2000 <= 2048, 8000 / 2 = 4000 > 2048
    }

    @Test fun `the decode sample size is the smallest that brings the long side to at most the cap`() {
        assertEquals(1, HubImageReader.sampleSizeFor(2048, 1000))
        assertEquals(2, HubImageReader.sampleSizeFor(2049, 1000))
        assertEquals(2, HubImageReader.sampleSizeFor(1000, 4096))
        assertEquals(4, HubImageReader.sampleSizeFor(4097, 3000))
        assertEquals(32, HubImageReader.sampleSizeFor(65_500, 65_500))
        for (side in listOf(1, 2047, 2048, 2049, 3000, 4095, 4096, 4097, 9999, 65_535)) {
            val s = HubImageReader.sampleSizeFor(side, 1)
            assertTrue("$side / $s", (side + s - 1) / s <= HubImageReader.MAX_SIDE)
            assertTrue("$side / $s is not the smallest", s == 1 || (side + s / 2 - 1) / (s / 2) > HubImageReader.MAX_SIDE)
        }
    }
}
