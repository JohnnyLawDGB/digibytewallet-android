package app.aroundtheblock.wallet.core.digiscope

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Answer
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DIGIRUNNER-RUNID, the Hub client side: POST /api/hub/digirunner/run issues a run (201 {"runId"}),
 * signed in like every other Hub call; the score submit carries that runId; any failure of the run
 * call answers null, so the game still plays and the score still goes out without a runId.
 */
class DigiRunnerRunClientTest {

    @Before fun silenceLog() = ScriptedServer.silenceLog()

    @After fun restoreLog() = unmockkStatic(Log::class)

    private val runId = "0123456789abcdef0123456789abcdef"

    private fun hub(server: ScriptedServer, token: String? = "token") = DigiScopeClient(
        server.client(), mockk<Context>(relaxed = true), signWithSharedIdentity = { null },
    ).apply { if (token != null) setToken(token) }

    @Test fun `a run is started with a signed-in POST to the run endpoint and its runId is returned`() {
        val server = ScriptedServer { Answer(201, """{"runId":"$runId"}""") }
        assertEquals(runId, runBlocking { hub(server).startDigiRunnerRun() })
        val seen = server.seen.single()
        assertEquals("/api/hub/digirunner/run", seen.url.encodedPath)
        assertEquals("api.digiscope.me", seen.url.host)
        assertEquals("POST", seen.method)
        assertEquals("Bearer token", seen.authorization)
        assertFalse("a pinned host's redirect is never followed", seen.followsRedirects)
    }

    @Test fun `a server that predates runs, refuses, fails or answers garbage gives no runId`() {
        val answers = listOf(
            Answer(404, """{"error":"Not found"}"""), Answer(429, """{"error":"Too many open runs"}"""),
            Answer(500, "{}"), Answer(401, "{}"), Answer(201, "not json"), Answer(201, "{}"),
            Answer(201, """{"runId":"NOT-HEX"}"""), Answer(201, """{"runId":"${runId}00"}"""),
            Answer(201, """{"runId":12}"""), Answer(307, location = "https://other.example/run"),
        )
        for (answer in answers) {
            val server = ScriptedServer { answer }
            assertNull("answer ${answer.code} ${answer.body}", runBlocking { hub(server).startDigiRunnerRun() })
            assertTrue("answer ${answer.code} was left open", server.answers.all { it.closed })
        }
    }

    @Test fun `a transport failure gives no runId and does not throw`() {
        val server = ScriptedServer { throw java.io.IOException("offline") }
        assertNull(runBlocking { hub(server).startDigiRunnerRun() })
    }

    @Test fun `without a Hub session no run is requested`() {
        val server = ScriptedServer { Answer(201, """{"runId":"$runId"}""") }
        assertNull(runBlocking { hub(server, token = null).startDigiRunnerRun() })
        assertTrue(server.seen.isEmpty())
    }

    @Test fun `the score submit carries the runId it is given`() {
        val server = ScriptedServer { Answer(201, """{"submitted":true,"rank":1,"score":10}""") }
        assertTrue(runBlocking { hub(server).submitDigiRunnerScore(10, 1000, 0, 2, runId) })
        val seen = server.seen.single()
        assertEquals("/api/hub/digirunner/score", seen.url.encodedPath)
        val body = JSONObject(seen.body)
        assertEquals(runId, body.getString("runId"))
        assertEquals(10, body.getInt("score"))
        assertEquals(1000, body.getInt("distance"))
    }

    @Test fun `a score submit without a runId sends none`() {
        val server = ScriptedServer { Answer(201, """{"submitted":true,"rank":1,"score":10}""") }
        assertTrue(runBlocking { hub(server).submitDigiRunnerScore(10, 1000, 0, 2, null) })
        assertFalse(JSONObject(server.seen.single().body).has("runId"))
    }
}
