package app.aroundtheblock.wallet.core.digiscope

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.db.dao.DigiIdHistoryDao
import app.aroundtheblock.wallet.core.digiid.DigiIdManager
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Answer
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Companion.CALLBACK_PATH
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Companion.DIGISCOPE_CHALLENGE
import app.aroundtheblock.wallet.core.model.DigiIdRequest
import app.aroundtheblock.wallet.core.model.DigiIdResult
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ID-002: a signed Digi-ID login is posted only by a client that follows no redirect, and a
 * redirect answer is a failed login. That holds for the login the user approves (DigiIdManager)
 * and for the Hub login the wallet then makes with that signature (DigiScopeClient.login).
 */
class DigiIdLoginRedirectTest {

    private val historyDao = mockk<DigiIdHistoryDao>(relaxed = true)

    @Before fun silenceLog() = ScriptedServer.silenceLog()

    @After fun restoreLog() = unmockkStatic(Log::class)

    private fun hub(server: ScriptedServer) = DigiScopeClient(server.client(), mockk<Context>(relaxed = true))

    private fun manager(server: ScriptedServer) = DigiIdManager(
        server.client(), historyDao, hub(server), sign = { _, _ -> "Didentity|c2lnbmF0dXJl" },
    )

    @Test fun `the Hub login made after a DigiScope approval is made by a client that follows no redirect`() {
        val server = ScriptedServer { Answer(200, """{"sessionToken":"session"}""") }
        val request = DigiIdRequest.parse(DIGISCOPE_CHALLENGE)!!

        val result = runBlocking { manager(server).authenticate(request) }

        assertTrue("the approval failed: $result", result is DigiIdResult.Success)
        // The approved login's callback, then the wallet's own Hub login with the same signature.
        assertEquals(listOf(CALLBACK_PATH, CALLBACK_PATH), server.seen.map { it.url.encodedPath })
        server.seen.forEachIndexed { i, sent ->
            assertFalse("request $i is made by a client that follows redirects", sent.followsRedirects)
            assertFalse("request $i is made by a client that follows https redirects", sent.followsSslRedirects)
        }
    }

    /** GUARD (no red exists: shipped in 4.0.89): the approved login's own callback follows no redirect. */
    @Test fun `GUARD the approved login's callback is made by a client that follows no redirect`() {
        val server = ScriptedServer { Answer(200) }
        val request = DigiIdRequest.parse("digiid://site.example/login?x=n0nce")!!

        val result = runBlocking { manager(server).authenticate(request) }

        assertTrue("the approval failed: $result", result is DigiIdResult.Success)
        val sent = server.seen.single()
        assertEquals("site.example", sent.url.host)
        assertFalse(sent.followsRedirects)
        assertFalse(sent.followsSslRedirects)
    }

    /** GUARD (no red exists: a 3xx was never a success): a redirect answer is a failed login. */
    @Test fun `GUARD a redirect answer to an approved login is a failed login and nothing more is sent`() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            val server = ScriptedServer { Answer(code, location = "https://other.example/login") }
            val request = DigiIdRequest.parse(DIGISCOPE_CHALLENGE)!!

            val result = runBlocking { manager(server).authenticate(request) }

            assertTrue("$code: $result", result is DigiIdResult.Error && result.code == code)
            assertEquals("$code: more was sent after the redirect answer", 1, server.seen.size)
            coVerify { historyDao.insert(match { !it.success }) }
        }
    }

    /** GUARD (no red exists: a 3xx was never a success): a redirect answer to the Hub login is no session. */
    @Test fun `GUARD a redirect answer to the Hub login is no session`() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            val server = ScriptedServer { Answer(code, """{"sessionToken":"session"}""", location = "https://other.example/x") }
            val hub = hub(server)

            assertNull("$code", runBlocking { hub.login("Didentity", "c2lnbmF0dXJl", DIGISCOPE_CHALLENGE) })
            assertFalse("$code: a session was kept", hub.isLoggedIn())
        }
    }
}
