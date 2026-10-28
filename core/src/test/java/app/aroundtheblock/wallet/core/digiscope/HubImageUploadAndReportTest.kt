package app.aroundtheblock.wallet.core.digiscope

import android.content.Context
import android.util.Log
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Hub's image upload and report requests, as the server reads them:
 *  - `POST /hub/images` (hub.js: multer `.single('image')`, `fields: 0`, `parts: 2`, 8 MB): one
 *    multipart part, a file named `image`, and nothing else; answered `{ id, url, width, height }`.
 *  - `POST /hub/report`: JSON `{ messageId }`; `{ reported: true }`, or 400 for a message that no
 *    longer exists, or 429 at the limit.
 *  - `GET /hub/channels/1/messages?before=`: `before` in the server's own `created_at` form.
 */
class HubImageUploadAndReportTest {

    @Before fun silenceLog() = ScriptedServer.silenceLog()

    @After fun restoreLog() = unmockkStatic(Log::class)

    /** Serves every request with [code] and [body], and keeps each request as sent. */
    private class Server(private val code: Int, private val body: String) : Interceptor {
        val requests = mutableListOf<Request>()
        override fun intercept(chain: Interceptor.Chain): Response {
            requests += chain.request()
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun hub(server: Server) = DigiScopeClient(
        OkHttpClient.Builder().addInterceptor(server).build(), mockk<Context>(relaxed = true),
    ).apply { setToken("token") }

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 1, 2, 3)

    // ── upload ─────────────────────────────────────────────────────────────────

    @Test fun `the upload is one multipart part, a file named image, and no other field`() {
        val server = Server(201, """{"id":42,"url":"/api/hub/media/x.png","width":10,"height":20}""")
        runBlocking { hub(server).uploadImage(png, "image/png") }

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/hub/images", request.url.encodedPath)
        assertEquals("Bearer token", request.header("Authorization"))
        val body = request.body as MultipartBody
        assertEquals("multipart/form-data", "${body.contentType().type}/${body.contentType().subtype}")
        assertEquals("exactly one part", 1, body.parts.size)
        val part = body.parts.single()
        assertEquals("""form-data; name="image"; filename="image"""", part.headers?.get("Content-Disposition"))
        assertEquals(1, part.headers?.size)
        assertEquals("image/png".toMediaType(), part.body.contentType())
        assertArrayEquals(png, Buffer().also { part.body.writeTo(it) }.readByteArray())

        // On the wire: one Content-Disposition, for `image`, and no other part name.
        val wire = Buffer().also { body.writeTo(it) }.readByteString().utf8()
        assertEquals(1, Regex("Content-Disposition:").findAll(wire).count())
        assertEquals(listOf("image"), Regex("""(?<!file)name="([^"]*)"""").findAll(wire).map { it.groupValues[1] }.toList())
    }

    @Test fun `an accepted upload answers its id and path`() {
        val server = Server(201, """{"id":42,"url":"/api/hub/media/x.png","width":10,"height":20}""")
        val answer = runBlocking { hub(server).uploadImage(png, "image/png") }

        assertEquals(HubImageUpload.Uploaded(42, "/api/hub/media/x.png", 10, 20), answer)
    }

    @Test fun `a refused upload keeps the server's code and words`() {
        val words = "Posting in chat needs Trust Level 2. Link a quality X or Telegram account on My Account (/my-account) to get there."
        val server = Server(403, """{"code":"TRUST_L2_REQUIRED","error":"$words"}""")
        val answer = runBlocking { hub(server).uploadImage(png, "image/png") }

        assertEquals(HubImageUpload.Failed(words, "TRUST_L2_REQUIRED", 403), answer)
    }

    @Test fun `an answer with no usable id is not an upload`() {
        for (body in listOf("""{"url":"/api/hub/media/x.png"}""", """{"id":0,"url":"/x"}""", """{"id":5}""", "not json")) {
            val answer = runBlocking { hub(Server(201, body)).uploadImage(png, "image/png") }
            assertTrue("$body: $answer", answer is HubImageUpload.Failed)
        }
    }

    @Test fun `a type the server does not take, or an image over 8 MB, is refused without a request`() {
        val server = Server(201, "{}")
        val hub = hub(server)

        assertEquals("BAD_IMAGE", (runBlocking { hub.uploadImage(png, "image/svg+xml") } as HubImageUpload.Failed).code)
        val big = ByteArray((DigiScopeClient.MAX_IMAGE_BYTES + 1).toInt())
        assertEquals("TOO_LARGE", (runBlocking { hub.uploadImage(big, "image/png") } as HubImageUpload.Failed).code)
        assertTrue("a request was made: ${server.requests}", server.requests.isEmpty())
    }

    // ── report ─────────────────────────────────────────────────────────────────

    @Test fun `a report is POST hub-report with exactly messageId`() {
        val server = Server(200, """{"reported":true}""")
        val answer = runBlocking { hub(server).reportMessage(7) }

        assertEquals(HubReportResult.REPORTED, answer)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/hub/report", request.url.encodedPath)
        assertEquals("Bearer token", request.header("Authorization"))
        assertEquals("""{"messageId":7}""", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    }

    @Test fun `a report's answers read as what they mean`() {
        assertEquals(HubReportResult.GONE, runBlocking { hub(Server(400, """{"error":"That message no longer exists."}""")).reportMessage(7) })
        assertEquals(HubReportResult.LIMITED, runBlocking { hub(Server(429, """{"error":"Rate limit exceeded"}""")).reportMessage(7) })
        assertEquals(HubReportResult.FAILED, runBlocking { hub(Server(500, "{}")).reportMessage(7) })
        assertEquals(HubReportResult.FAILED, runBlocking { hub(Server(200, """{"reported":false}""")).reportMessage(7) })
    }

    // ── paging ─────────────────────────────────────────────────────────────────

    /**
     * hub-service.js pages with `created_at < ?` against its own "YYYY-MM-DD HH:MM:SS" (UTC). A
     * number there compares below every stored time in SQLite, so the older page came back empty.
     */
    @Test fun `an older page is asked for in the server's own time form`() {
        val server = Server(200, """{"messages":[]}""")
        val ms = java.time.Instant.parse("2026-10-28T12:34:56.789Z").toEpochMilli()
        runBlocking { hub(server).getMessages(1, before = ms) }

        val url = server.requests.single().url
        assertEquals("2026-10-28 12:34:56", url.queryParameter("before"))
        assertEquals("50", url.queryParameter("limit"))
    }

    @Test fun `the newest page is asked for with no before`() {
        val server = Server(200, """{"messages":[]}""")
        runBlocking { hub(server).getMessages(1) }

        assertNull(server.requests.single().url.queryParameter("before"))
    }
}
