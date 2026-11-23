package app.aroundtheblock.wallet.core.ipfs

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

/**
 * ASSET-MEDIA-FIXES, review F1: which bytes may be judged as "the file".
 *
 * A dag-pb CID (CIDv0 `Qm…`, CIDv1 with codec 0x70; every real picture in the samples) hashes a
 * UnixFS node, not the file. The trusted gateway serves the file, unwrapped, and is believed on
 * its pinned identity. A PUBLIC gateway asked for `?format=raw` serves the CID's root block: it
 * hashes to the CID, so it verifies and is returned, and it is not the file. When the trusted
 * gateway was down, slow, or had not pinned the CID yet, that block was what the picture loader
 * got, it did not decode, and "not a picture" was remembered for 24 hours for a good picture.
 *
 * [IpfsClient.Fetched.wholeFile] is what a caller may conclude from: true only for the trusted
 * gateway's complete answer, or for bytes that hash to a raw-codec CID.
 */
class IpfsWholeFileTest {

    private val OCTETS = "application/octet-stream".toMediaType()
    private val trusted = "https://trusted.example/api/ipfs/{cid}"
    private val public = "https://public.example/ipfs/{cid}?format=raw"

    /** What a public gateway holds for a dag-pb CID: a block that hashes to it. Not a picture. */
    private val rootBlock = byteArrayOf(0x12, 0x2a) + "links to the blocks of a picture".toByteArray()
    private val picture = "the picture itself, as the trusted gateway serves it".toByteArray()

    private val v0 = cid(null, rootBlock)                                   // Qm…
    private val v1Base32 = cid(0x70, rootBlock, base = 'b')                 // bafybei…
    private val v1Base58 = cid(0x70, rootBlock, base = 'z')                 // zdj7W…: dag-pb, and no "bafybe"
    private val v1Hex = cid(0x70, rootBlock, base = 'f')                    // f0170…
    private val v1Sha512 = cid(0x70, rootBlock, base = 'b', sha512 = true)  // bafyb…, not "bafybe"
    private val raw = cid(0x55, picture, base = 'b')                        // bafkrei…
    private val dagCbor = cid(0x71, rootBlock, base = 'b')                  // a block of another codec

    /** host -> (status, body). A host with no entry throws, as an unreachable one does. */
    private val answers = HashMap<String, Pair<Int, ByteArray>>()
    private val asked = ArrayList<String>()

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val host = chain.request().url.host
        synchronized(asked) { asked += host }
        val (code, body) = answers[host] ?: throw java.net.SocketTimeoutException("timeout")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody(OCTETS)).build()
    }.build()

    private fun client() = IpfsClient(http, CidVerifier(), listOf(trusted, public), trustedGatewayPrefixes = listOf("https://trusted.example/"))

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    // ---------------------------------------------------------------------------------------------

    @Test fun `the test's CIDs are what they claim to be`() {
        assertTrue(v0, v0.startsWith("Qm") && v0.length == 46)
        assertTrue(v1Base32, v1Base32.startsWith("bafybei"))
        assertFalse(v1Base58, v1Base58.startsWith("Qm") || v1Base58.startsWith("bafybe"))
        assertFalse(v1Sha512, v1Sha512.startsWith("bafybe"))
        assertTrue(raw, raw.startsWith("bafkrei"))
        for (c in listOf(v0, v1Base32, v1Base58, v1Hex, v1Sha512, raw, dagCbor)) assertEquals(c, c, IpfsClient.normalizeCid(c))
        for (c in listOf(v0, v1Base32, v1Base58, v1Hex, v1Sha512, dagCbor)) assertTrue(c, CidVerifier().verify(c, rootBlock))
    }

    @Test fun `the codec is read from the CID, however it is written`() {
        val v = CidVerifier()
        for (c in listOf(v0, v1Base32, v1Base58, v1Hex, v1Sha512, v1Base32.uppercase()))
            assertEquals(c, CidVerifier.CODEC_DAG_PB, v.codec(c))
        assertEquals(CidVerifier.CODEC_RAW, v.codec(raw))
        assertEquals(0x71L, v.codec(dagCbor))
        // The real CIDs of the samples: pictures and songs are dag-pb, the documents raw.
        assertEquals(CidVerifier.CODEC_DAG_PB, v.codec("bafybeictgnjgm4gm3lweojytk3fvkcvuqyruwex5viklo4upsxxj5jcdx4"))
        assertEquals(CidVerifier.CODEC_DAG_PB, v.codec("QmXibaWxG1xR479kfwkwCQEPabi9R81Z6DJaetaoJGNYHm"))
        assertEquals(CidVerifier.CODEC_RAW, v.codec("bafkreicrmqxoxgm423z4nxbpcecc2kbixm4nnpyzfuz5kjkjmsbmgkpkae"))
        for (notACid in listOf("", "null", "bafybe", "Qm", "hello", "x".repeat(500))) assertNull(notACid, v.codec(notACid))
    }

    /** The reported case, at the client: the trusted gateway fails in each way it can, a public one has the block. */
    @Test fun `a public gateway's block of a dag-pb CID is never the whole file`() = runBlocking {
        for (cid in listOf(v0, v1Base32, v1Base58, v1Hex, v1Sha512)) {
            for (trustedSays in listOf(503, 500, 404, 400, 302, null)) {     // null: no answer at all (a timeout)
                answers.clear()
                if (trustedSays != null) answers["trusted.example"] = trustedSays to ByteArray(0)
                answers["public.example"] = 200 to rootBlock
                val client = client()
                val fetched = client.fetch(cid)
                // Pre-existing and unchanged: the block is returned (it does hash to the CID)...
                assertArrayEquals("$cid, trusted $trustedSays", rootBlock, fetched.bytes)
                // ...and it is not evidence about the file.
                assertFalse("$cid, trusted $trustedSays: a root block is not the file", fetched.wholeFile)
                assertNull("$cid: its length is not the file's", client.knownSize(cid))
                assertEquals("$cid, trusted $trustedSays", if (trustedSays == 404 || trustedSays == 400) 1 else 0, fetched.saidNotHere)
            }
        }
    }

    @Test fun `the trusted gateway's answer for a dag-pb CID is the whole file, in every spelling of dag-pb`() = runBlocking {
        for (cid in listOf(v0, v1Base32, v1Base58, v1Hex, v1Sha512)) {
            answers["trusted.example"] = 200 to picture
            answers["public.example"] = 200 to rootBlock
            val client = client()
            val fetched = client.fetch(cid)
            assertArrayEquals(cid, picture, fetched.bytes)
            assertTrue(cid, fetched.wholeFile)
            assertEquals(cid, picture.size.toLong(), client.knownSize(cid))
        }
        assertFalse("the public gateway is not asked when the trusted one answers", asked.contains("public.example"))
    }

    /** Trust by pin covers a complete answer. A partial one (206) is accepted as before, and judged as nothing. */
    @Test fun `a partial answer from the trusted gateway is not the whole file`() = runBlocking {
        answers["trusted.example"] = 206 to picture.copyOf(10)
        val client = client()
        val fetched = client.fetch(v1Base32)
        assertFalse(fetched.wholeFile)
        assertNull(client.knownSize(v1Base32))
    }

    @Test fun `bytes that hash to a raw-codec CID are the whole file, whichever gateway gave them`() = runBlocking {
        for (gatewayWithIt in listOf("trusted.example", "public.example")) {
            answers.clear()
            answers[gatewayWithIt] = 200 to picture
            val client = client()
            val fetched = client.fetch(raw)
            assertArrayEquals(gatewayWithIt, picture, fetched.bytes)
            assertTrue(gatewayWithIt, fetched.wholeFile)
            assertEquals(picture.size.toLong(), client.knownSize(raw))
        }
    }

    /** dag-cbor and the like: content-verified on every gateway as before, and a block, not a file. */
    @Test fun `a verified block of any other codec is not the whole file`() = runBlocking {
        for (gatewayWithIt in listOf("trusted.example", "public.example")) {
            answers.clear()
            answers[gatewayWithIt] = 200 to rootBlock
            val client = client()
            val fetched = client.fetch(dagCbor)
            assertArrayEquals(rootBlock, fetched.bytes)
            assertFalse(gatewayWithIt, fetched.wholeFile)
            assertNull(client.knownSize(dagCbor))
        }
    }

    @Test fun `a fetch that returned nothing is evidence of nothing`() = runBlocking {
        for (status in listOf(503, 404, null)) {
            answers.clear()
            if (status != null) { answers["trusted.example"] = status to ByteArray(0); answers["public.example"] = status to ByteArray(0) }
            val fetched = client().fetch(v1Base32)
            assertNull(fetched.bytes)
            assertFalse(fetched.wholeFile)
            assertEquals(if (status == 404) 2 else 0, fetched.saidNotHere)
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** A CID for [content]: CIDv0 when [codec] is null, else CIDv1 with that codec in multibase [base]. */
    private fun cid(codec: Int?, content: ByteArray, base: Char = 'b', sha512: Boolean = false): String {
        val digest = MessageDigest.getInstance(if (sha512) "SHA-512" else "SHA-256").digest(content)
        val multihash = byteArrayOf(if (sha512) 0x13 else 0x12, digest.size.toByte()) + digest
        if (codec == null) return base58(multihash)
        val bytes = byteArrayOf(0x01, codec.toByte()) + multihash
        return when (base) {
            'b' -> "b" + base32(bytes)
            'z' -> "z" + base58(bytes)
            else -> "f" + bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private fun base58(input: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = BigInteger(1, input)
        val sb = StringBuilder()
        while (num > BigInteger.ZERO) {
            val (q, r) = num.divideAndRemainder(BigInteger.valueOf(58))
            sb.append(alphabet[r.toInt()]); num = q
        }
        for (b in input) { if (b != 0.toByte()) break; sb.append('1') }
        return sb.reverse().toString()
    }

    private fun base32(input: ByteArray): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val sb = StringBuilder()
        var bits = 0; var count = 0
        for (b in input) {
            bits = (bits shl 8) or (b.toInt() and 0xFF); count += 8
            while (count >= 5) { count -= 5; sb.append(alphabet[(bits shr count) and 31]) }
        }
        if (count > 0) sb.append(alphabet[(bits shl (5 - count)) and 31])
        return sb.toString()
    }
}
