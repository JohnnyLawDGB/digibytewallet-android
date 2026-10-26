package app.aroundtheblock.wallet.core.digiscope

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.db.dao.DigiIdHistoryDao
import app.aroundtheblock.wallet.core.digiid.DigiIdManager
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Answer
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Companion.CALLBACK_PATH
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Companion.CHALLENGE_PATH
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Companion.DIGISCOPE_CHALLENGE
import app.aroundtheblock.wallet.core.model.DigiIdRequest
import app.aroundtheblock.wallet.core.model.DigiIdResult
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * After a DigiScope Digi-ID login, the wallet's own Hub session comes from a fresh challenge
 * (4.1.2 row 37).
 *
 * The wallet re-posted the approved login's signature and challenge to the callback to get its
 * own session. The server takes a challenge once, so that second post was refused (401 on the
 * Moto, 2026-10-25: "login callback: HTTP 401") and the Hub stayed disconnected after every
 * website login. This server is as strict as the real one: a challenge is answered once.
 */
class HubSessionAfterDigiIdLoginTest {

    private val fresh = "digiid://api.digiscope.me/api/auth/digiid/callback?x=ffeeddccbbaa99887766554433221100"
    private val signed = mutableListOf<String>()

    @Before fun silenceLog() = ScriptedServer.silenceLog()

    @After fun restoreLog() = unmockkStatic(Log::class)

    /** Issues [fresh]; a callback is a session once per challenge, and 401 after that. */
    private fun server(): ScriptedServer {
        val used = mutableSetOf<String>()
        return ScriptedServer { request ->
            when (request.url.encodedPath) {
                CHALLENGE_PATH -> Answer(200, JSONObject().put("uri", fresh).toString())
                CALLBACK_PATH -> {
                    val sent = request.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() } ?: ""
                    if (used.add(JSONObject(sent).getString("uri"))) Answer(200, """{"sessionToken":"session"}""")
                    else Answer(401, """{"error":"challenge already used"}""")
                }
                else -> Answer(404)
            }
        }
    }

    @Test fun `a DigiScope login leaves the wallet with its own Hub session`() {
        val server = server()
        val hub = DigiScopeClient(
            server.client(), mockk<Context>(relaxed = true),
            signWithSharedIdentity = { text -> signed += text; "Didentity|c2lnbmF0dXJl" },
        )
        val manager = DigiIdManager(
            server.client(), mockk<DigiIdHistoryDao>(relaxed = true), hub, sign = { _, _ -> "Didentity|c2lnbmF0dXJl" },
        )

        val result = runBlocking { manager.authenticate(DigiIdRequest.parse(DIGISCOPE_CHALLENGE)!!) }

        assertTrue("the approval failed: $result", result is DigiIdResult.Success)
        assertTrue("the wallet has no Hub session after the login", hub.isLoggedIn())
        val posted = server.seen.filter { it.url.encodedPath == CALLBACK_PATH }.map { JSONObject(it.body).getString("uri") }
        assertEquals("each challenge is posted once", posted.distinct(), posted)
        assertEquals("the wallet's session is signed on the fresh challenge", listOf(fresh), signed)
    }
}
