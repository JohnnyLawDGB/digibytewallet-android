package app.aroundtheblock.wallet.core.network

import okhttp3.ResponseBody
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.Source
import java.nio.charset.Charset

/**
 * The one way the wallet reads a network answer into memory: never more than a cap its caller
 * names for that endpoint. Every response body in the app is read here (`NetworkSourceGateTest`).
 *
 *  - An answer whose declared length is over the cap is refused before a byte is read.
 *  - Any other answer is read only up to the cap plus one byte; that byte shows the answer is
 *    over the cap, and it is refused without reading the rest. This is what bounds an answer
 *    with no declared length — chunked, or gzip that OkHttp inflates, where the inflated bytes are
 *    the ones counted — and a declared length is never relied on to stop the read.
 *  - An answer of exactly the cap is returned whole.
 *
 * A refused answer reads as no answer (null), the same as a failed request, and the body is
 * closed either way.
 */
object BoundedBody {

    /** At most this much is asked of the source in one read. */
    private const val CHUNK = 8_192L

    /**
     * The bytes of [source] when there are at most [cap] of them, else null. [declaredLength] is
     * the length the answer declared, or -1 for none; one over [cap] is refused unread.
     */
    fun read(source: Source, declaredLength: Long, cap: Long): ByteString? {
        require(cap in 0 until Long.MAX_VALUE) { "cap out of range: $cap" }
        if (declaredLength > cap) return null
        val held = Buffer()
        val limit = cap + 1
        while (held.size < limit) {
            if (source.read(held, minOf(CHUNK, limit - held.size)) == -1L) return held.readByteString()
        }
        return null
    }

    /** [body]'s bytes when it fits in [cap], else null. Null for no body. Closes [body]. */
    fun bytes(body: ResponseBody?, cap: Long): ByteArray? = body?.use { readBody(it, cap) }?.toByteArray()

    /**
     * [body] as text when it fits in [cap], else null. Null for no body. Closes [body]. Decoded as
     * OkHttp's `string()` decodes: a byte-order mark names the charset and is dropped; otherwise the
     * declared charset, otherwise UTF-8.
     */
    fun text(body: ResponseBody?, cap: Long): String? = body?.use { b ->
        readBody(b, cap)?.let { decode(it, b.contentType()?.charset() ?: Charsets.UTF_8) }
    }

    private fun readBody(body: ResponseBody, cap: Long): ByteString? {
        val declared = body.contentLength()
        // Checked before the source is opened, so an answer refused on its declared length is not touched.
        if (declared > cap) return null
        return read(body.source(), declared, cap)
    }

    private val UTF8_BOM = "efbbbf".decodeHex()
    private val UTF16_BE_BOM = "feff".decodeHex()
    private val UTF16_LE_BOM = "fffe".decodeHex()
    private val UTF32_BE_BOM = "0000feff".decodeHex()
    private val UTF32_LE_BOM = "fffe0000".decodeHex()

    private fun decode(bytes: ByteString, declared: Charset): String = when {
        bytes.startsWith(UTF8_BOM) -> bytes.substring(UTF8_BOM.size).string(Charsets.UTF_8)
        bytes.startsWith(UTF32_LE_BOM) -> bytes.substring(UTF32_LE_BOM.size).string(Charsets.UTF_32LE)
        bytes.startsWith(UTF32_BE_BOM) -> bytes.substring(UTF32_BE_BOM.size).string(Charsets.UTF_32BE)
        bytes.startsWith(UTF16_BE_BOM) -> bytes.substring(UTF16_BE_BOM.size).string(Charsets.UTF_16BE)
        bytes.startsWith(UTF16_LE_BOM) -> bytes.substring(UTF16_LE_BOM.size).string(Charsets.UTF_16LE)
        else -> bytes.string(declared)
    }
}
