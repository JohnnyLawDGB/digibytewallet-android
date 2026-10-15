package app.aroundtheblock.wallet.core.digiscope

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Answer
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Companion.CALLBACK_PATH
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Companion.CHALLENGE_PATH
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Companion.DIGISCOPE_CHALLENGE
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Companion.NONCE
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hub quick login, driven through [DigiScopeClient.quickLogin] against a scripted server.
 *
 * ID-001: the wallet signs the challenge only when it is a DigiScope login (QuickLoginChallengePolicy),
 * signs exactly the text it checked, and submits that same text. A challenge for anything else is
 * never signed and no login is submitted.
 *
 * ID-002: the challenge and the signed login travel on a client that follows no redirect, so a
 * signed login reaches the callback it was made for or nowhere.
 */
class QuickLoginTest {

    private val signed = mutableListOf<String>()

    @Before fun silenceLog() = ScriptedServer.silenceLog()

    @After fun restoreLog() = unmockkStatic(Log::class)

    /** A server that issues [challenge] and accepts any login with a session token. */
    private fun server(challenge: String?): ScriptedServer = ScriptedServer { request ->
        when (request.url.encodedPath) {
            CHALLENGE_PATH -> Answer(200, JSONObject().apply { if (challenge != null) put("uri", challenge) }.toString())
            CALLBACK_PATH -> Answer(200, """{"sessionToken":"session"}""")
            else -> Answer(404)
        }
    }

    private fun hub(server: ScriptedServer) = DigiScopeClient(
        server.client(),
        mockk<Context>(relaxed = true),
        signWithSharedIdentity = { text -> signed += text; "Didentity|c2lnbmF0dXJl" },
    )

    @Test fun `a DigiScope challenge is signed exactly as checked and that text is submitted to the callback`() {
        val server = server(DIGISCOPE_CHALLENGE)

        assertTrue("the login failed", runBlocking { hub(server).quickLogin() })

        assertEquals(listOf(DIGISCOPE_CHALLENGE), signed)
        val login = server.seen.single { it.url.encodedPath == CALLBACK_PATH }
        assertEquals("api.digiscope.me", login.url.host)
        assertEquals("https", login.url.scheme)
        val body = JSONObject(login.body)
        assertEquals(DIGISCOPE_CHALLENGE, body.getString("uri"))
        assertEquals("Didentity", body.getString("address"))
        assertEquals("c2lnbmF0dXJl", body.getString("signature"))
    }

    @Test fun `a challenge that is not a DigiScope login is never signed and no login is submitted`() {
        val others = listOf(
            "digiid://other.example/api/auth/digiid/callback?x=$NONCE",
            "digiid://api.digiscope.me.other.example/api/auth/digiid/callback?x=$NONCE",
            "digiid://api.digiscope.me@other.example/api/auth/digiid/callback?x=$NONCE",
            "digiid://api.digiscope.me/api/auth/digiid/callback?x=$NONCE&u=1",
            "digiid://digiscope.me/api/auth/digiid/callback?x=$NONCE",
            "", "garbage", " $DIGISCOPE_CHALLENGE",
        )
        val failures = mutableListOf<String>()
        for (challenge in others) {
            signed.clear()
            val server = server(challenge)
            val loggedIn = runBlocking { hub(server).quickLogin() }
            if (server.seen.none { it.url.encodedPath == CHALLENGE_PATH }) failures += "'$challenge': no challenge was asked for (blind)"
            if (loggedIn) failures += "'$challenge': the login succeeded"
            if (signed.isNotEmpty()) failures += "'$challenge': signed $signed"
            if (server.seen.any { it.url.encodedPath == CALLBACK_PATH }) failures += "'$challenge': a login was submitted"
        }
        assertTrue(failures.joinToString("\n", prefix = "\n"), failures.isEmpty())
    }

    @Test fun `an answer without a challenge is never signed`() {
        val server = server(null)
        assertFalse(runBlocking { hub(server).quickLogin() })
        assertTrue("signed $signed", signed.isEmpty())
        assertTrue(server.seen.none { it.url.encodedPath == CALLBACK_PATH })
    }

    @Test fun `every request of a quick login is made by a client that follows no redirect`() {
        val server = server(DIGISCOPE_CHALLENGE)

        assertTrue("the login failed", runBlocking { hub(server).quickLogin() })

        assertEquals(listOf(CHALLENGE_PATH, CALLBACK_PATH), server.seen.map { it.url.encodedPath })
        for (request in server.seen) {
            assertFalse("${request.url.encodedPath} is made by a client that follows redirects", request.followsRedirects)
            assertFalse("${request.url.encodedPath} is made by a client that follows https redirects", request.followsSslRedirects)
        }
    }
}
