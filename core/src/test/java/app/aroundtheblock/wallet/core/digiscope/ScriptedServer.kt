package app.aroundtheblock.wallet.core.digiscope

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.connection.RealCall
import okio.Buffer
import okio.BufferedSource

/**
 * The test's stand-in for the DigiScope server: an application interceptor that answers every
 * request from a script, so no socket is opened (clients derived from [client] keep it).
 *
 * For each request it records the URL, the method, the body sent, and whether the client that
 * made the call follows redirects — an application interceptor runs before OkHttp would follow
 * one, so the client's own setting is what decides whether a redirect would be followed. For each
 * answer it keeps the body handed out, so a test can ask whether the caller closed it.
 */
internal class ScriptedServer(private val script: (Request) -> Answer) : Interceptor {

    class Answer(val code: Int, val body: String = "", val location: String? = null)

    class Seen(
        val url: HttpUrl,
        val method: String,
        val body: String,
        val followsRedirects: Boolean,
        val followsSslRedirects: Boolean,
    )

    val seen = mutableListOf<Seen>()
    val answers = mutableListOf<TrackedBody>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val caller = (chain.call() as RealCall).client
        val sent = request.body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() } ?: ""
        seen += Seen(request.url, request.method, sent, caller.followRedirects, caller.followSslRedirects)
        val answer = script(request)
        val body = TrackedBody(answer.body).also { answers += it }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(answer.code)
            .message("")
            .apply { answer.location?.let { header("Location", it) } }
            .body(body)
            .build()
    }

    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    /** An answer's body that records whether it was closed. */
    class TrackedBody(text: String) : ResponseBody() {
        var closed = false
            private set
        private val bytes = text.encodeToByteArray()
        private val buffered: BufferedSource by lazy { Buffer().write(bytes) }
        override fun contentType(): MediaType = "application/json".toMediaType()
        override fun contentLength(): Long = bytes.size.toLong()
        override fun source(): BufferedSource = buffered
        override fun close() {
            closed = true
            super.close()
        }
    }

    companion object {
        const val CHALLENGE_PATH = "/api/auth/digiid/challenge"
        const val CALLBACK_PATH = "/api/auth/digiid/callback"
        const val NONCE = "00112233445566778899aabbccddeeff"

        /** A login challenge as the DigiScope server issues it. */
        const val DIGISCOPE_CHALLENGE = "digiid://api.digiscope.me/api/auth/digiid/callback?x=$NONCE"

        /** Every Log overload the clients use, silenced (android.util.Log is a stub on the JVM). */
        fun silenceLog() {
            mockkStatic(Log::class)
            every { Log.v(any(), any()) } returns 0
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.w(any(), any<String>(), any()) } returns 0
            every { Log.w(any<String>(), any<Throwable>()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0
        }
    }
}
