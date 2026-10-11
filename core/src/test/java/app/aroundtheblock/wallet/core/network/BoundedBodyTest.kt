package app.aroundtheblock.wallet.core.network

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BoundedBody], the one reader every network answer goes through, over in-memory sources.
 *
 * Its whole contract: an answer longer than the caller's cap is never held. A declared length over
 * the cap is refused before a byte is taken; an answer without one (chunked, or inflated from
 * gzip) is taken only to the cap plus one byte, which is what shows it is over; an answer of
 * exactly the cap is returned whole.
 */
class BoundedBodyTest {

    private val cap = 64L * 1024

    // ── the reader ─────────────────────────────────────────────────────────────

    @Test fun `a declared length over the cap is refused without reading`() {
        val source = CountingSource(cap + 1)

        assertNull(BoundedBody.read(source, declaredLength = cap + 1, cap = cap))
        assertEquals("bytes were taken from an answer whose declared length is over the cap", 0L, source.taken)
    }

    @Test fun `an answer with no declared length stops at the cap plus one byte and is refused`() {
        val source = CountingSource(16 * cap)

        assertNull(BoundedBody.read(source, declaredLength = -1L, cap = cap))
        assertEquals("the reader did not stop at the cap plus one byte", cap + 1, source.taken)
    }

    @Test fun `an answer of exactly the cap is returned whole, declared or not`() {
        val undeclared = CountingSource(cap)
        val read = BoundedBody.read(undeclared, declaredLength = -1L, cap = cap)
        assertNotNull(read)
        assertEquals(cap, read!!.size.toLong())
        assertEquals(cap, undeclared.taken)

        val declared = CountingSource(cap)
        assertEquals(cap, BoundedBody.read(declared, declaredLength = cap, cap = cap)!!.size.toLong())
    }

    @Test fun `one byte over the cap is refused, declared or not`() {
        assertNull(BoundedBody.read(CountingSource(cap + 1), declaredLength = -1L, cap = cap))
        assertNull(BoundedBody.read(CountingSource(cap + 1), declaredLength = cap + 1, cap = cap))
    }

    /** A declared length is a promise the reader does not rely on: the bytes are counted anyway. */
    @Test fun `a declared length under the cap does not lift the cap`() {
        val source = CountingSource(4 * cap)

        assertNull(BoundedBody.read(source, declaredLength = 10L, cap = cap))
        assertEquals(cap + 1, source.taken)
    }

    @Test fun `the bytes come back as they were sent`() {
        val sent = ByteArray(5_000) { (it * 31 + 7).toByte() }

        assertArrayEquals(sent, BoundedBody.read(Buffer().write(sent), declaredLength = -1L, cap = cap)!!.toByteArray())
    }

    @Test fun `an empty answer is empty, not refused`() {
        assertEquals(0, BoundedBody.read(CountingSource(0), declaredLength = -1L, cap = cap)!!.size)
        assertEquals(0, BoundedBody.read(CountingSource(0), declaredLength = 0L, cap = 0L)!!.size)
    }

    @Test fun `a cap of zero admits only an empty answer`() {
        val one = CountingSource(1)
        assertNull(BoundedBody.read(one, declaredLength = -1L, cap = 0L))
        assertEquals(1L, one.taken)
    }

    // ── response bodies ────────────────────────────────────────────────────────

    @Test fun `a response body over the cap is refused and closed`() {
        val counting = CountingSource(4 * cap)
        val body = streamed(counting)

        assertNull(BoundedBody.bytes(body, cap))
        assertTrue("the refused body was not closed", counting.closed)
        assertTrue("read ${counting.taken} bytes of a refused body", counting.taken <= cap + 1 + SEGMENT)
    }

    @Test fun `a response body declared over the cap is refused unread and closed`() {
        val counting = CountingSource(cap + 1)

        assertNull(BoundedBody.text(streamed(counting, declaredLength = cap + 1), cap))
        assertEquals(0L, counting.taken)
        assertTrue("the refused body was not closed", counting.closed)
    }

    @Test fun `a response body at the cap is read and closed`() {
        val counting = CountingSource(cap)

        assertEquals(cap, BoundedBody.bytes(streamed(counting), cap)!!.size.toLong())
        assertTrue("the body was not closed", counting.closed)
    }

    @Test fun `no body reads as no answer`() {
        assertNull(BoundedBody.bytes(null, cap))
        assertNull(BoundedBody.text(null, cap))
    }

    /** The text is what OkHttp's own `string()` gives: a byte-order mark names the charset and is
     *  dropped; otherwise the declared charset, otherwise UTF-8. */
    @Test fun `text is decoded as OkHttp decodes it`() {
        val utf8 = "{\"name\":\"Žluťoučký kůň — 数字\"}"
        assertEquals(utf8, BoundedBody.text(bodyOf(utf8.encodeToByteArray(), "application/json".toMediaType()), cap))
        assertEquals(utf8, BoundedBody.text(bodyOf(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8.encodeToByteArray(), null), cap))
        assertEquals(utf8, BoundedBody.text(bodyOf("FEFF".decodeHex().toByteArray() + utf8.toByteArray(Charsets.UTF_16BE), null), cap))
        assertEquals(utf8, BoundedBody.text(bodyOf("FFFE".decodeHex().toByteArray() + utf8.toByteArray(Charsets.UTF_16LE), null), cap))
        val latin1 = "text/plain; charset=ISO-8859-1".toMediaType()
        assertEquals("caf\u00e9", BoundedBody.text(bodyOf(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()), latin1), cap))
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private companion object {
        /** The most one okio segment can add past what the reader asked for. */
        const val SEGMENT = 8_192L
    }

    /** [total] bytes of filler, handed out only as asked, counting what was taken and whether it was closed. */
    private class CountingSource(val total: Long) : Source {
        var taken = 0L
        var closed = false
        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = minOf(byteCount, total - taken)
            if (n <= 0L) return -1L
            sink.write(ByteArray(n.toInt()) { 'x'.code.toByte() })
            taken += n
            return n
        }
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() { closed = true }
    }

    /** A response body read from [source], declaring [declaredLength] (none by default). */
    private fun streamed(source: Source, declaredLength: Long = -1L): ResponseBody = object : ResponseBody() {
        private val buffered: BufferedSource by lazy { source.buffer() }
        override fun contentLength() = declaredLength
        override fun contentType(): MediaType? = null
        override fun source(): BufferedSource = buffered
    }

    private fun bodyOf(bytes: ByteArray, type: MediaType?): ResponseBody = object : ResponseBody() {
        private val buffered: BufferedSource by lazy { Buffer().write(bytes) }
        override fun contentLength() = bytes.size.toLong()
        override fun contentType(): MediaType? = type
        override fun source(): BufferedSource = buffered
    }
}
