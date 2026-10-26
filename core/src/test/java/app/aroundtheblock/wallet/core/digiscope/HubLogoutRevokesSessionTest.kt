package app.aroundtheblock.wallet.core.digiscope

import android.content.Context
import android.util.Log
import io.mockk.mockk
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Logging out of the Hub ends the session on the server too, not only on the phone: the token
 * the phone held is sent once to the server's logout endpoint, so a copy of it made before the
 * logout stops working. The phone forgets the token first and whatever the server answers.
 */
class HubLogoutRevokesSessionTest {

    @Before fun silenceLog() = ScriptedServer.silenceLog()

    @After fun restoreLog() = unmockkStatic(Log::class)

    private fun hub(server: ScriptedServer) =
        DigiScopeClient(server.client(), mockk<Context>(relaxed = true), signWithSharedIdentity = { null })

    private fun logoutAnswered(code: Int): Pair<ScriptedServer, DigiScopeClient> {
        val answered = CountDownLatch(1)
        lateinit var server: ScriptedServer
        server = ScriptedServer { ScriptedServer.Answer(code, "{}").also { answered.countDown() } }
        val client = hub(server).apply { setToken("session-before-logout") }
        client.logout()
        assertFalse("the phone still holds the token after logout", client.isLoggedIn())
        assertNull(client.getToken())
        assertTrue("logout never told the server", answered.await(5, TimeUnit.SECONDS))
        return server to client
    }

    @Test fun `logout sends the token it held to the server's logout endpoint`() {
        val (server, _) = logoutAnswered(200)
        assertEquals(1, server.seen.size)
        val seen = server.seen.single()
        assertEquals("POST", seen.method)
        assertEquals("https://api.digiscope.me/api/auth/logout", seen.url.toString())
        assertEquals("Bearer session-before-logout", seen.authorization)
        assertFalse("a logout must not follow a redirect with the token", seen.followsRedirects)
    }

    @Test fun `a server that refuses the logout leaves the phone logged out, and the answer closed`() {
        for (code in listOf(400, 401, 500, 503)) {
            val (server, client) = logoutAnswered(code)
            assertFalse(client.isLoggedIn())
            val deadline = System.currentTimeMillis() + 5_000
            // The answer is recorded after the script runs, so wait for it to exist as well.
            while (server.answers.firstOrNull()?.closed != true && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(1, server.answers.size)
            assertTrue("answer $code left open", server.answers.single().closed)
        }
    }

    @Test fun `logout without a session sends nothing`() {
        val server = ScriptedServer { ScriptedServer.Answer(200, "{}") }
        val client = hub(server)
        client.logout()
        Thread.sleep(200)
        assertTrue(server.seen.isEmpty())
    }
}
