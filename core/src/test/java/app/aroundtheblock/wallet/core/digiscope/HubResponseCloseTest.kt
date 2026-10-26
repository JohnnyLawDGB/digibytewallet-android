package app.aroundtheblock.wallet.core.digiscope

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Answer
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Companion.CALLBACK_PATH
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Companion.CHALLENGE_PATH
import app.aroundtheblock.wallet.core.digiscope.ScriptedServer.Companion.DIGISCOPE_CHALLENGE
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every Hub request closes its answer, whatever the answer was: a success, a body that is not
 * JSON, an empty answer, a redirect, a refusal or a server error. An answer left open holds its
 * connection until the garbage collector finds it.
 */
class HubResponseCloseTest {

    @Before fun silenceLog() = ScriptedServer.silenceLog()

    @After fun restoreLog() = unmockkStatic(Log::class)

    /** A Hub client signed in with a stand-in token. */
    private fun hub(server: ScriptedServer) = DigiScopeClient(
        server.client(), mockk<Context>(relaxed = true), signWithSharedIdentity = { "Didentity|c2lnbmF0dXJl" },
    ).apply { setToken("token") }

    private val calls: List<Pair<String, suspend (DigiScopeClient) -> Any?>> = listOf(
        "quick-login challenge" to { it.quickLogin() },
        "login" to { it.login("Didentity", "c2lnbmF0dXJl", DIGISCOPE_CHALLENGE) },
        "register handle" to { it.registerHandle("handle") },
        "link tip wallet" to { it.linkTipWallet("Daddress") },
        "profile" to { it.getProfile() },
        "channels" to { it.getChannels() },
        "messages" to { it.getMessages(1) },
        "tip" to { it.sendTip("handle", 1) },
        "report" to { it.reportContent(messageId = 1, reason = "reason") },
        "handle availability" to { it.checkHandleAvailable("handle") },
        "game score" to { it.submitDigiRunnerScore(1, 1, 1, 1) },
        "game leaderboard" to { it.getDigiRunnerLeaderboard() },
        "game stats" to { it.getDigiRunnerStats() },
    )

    private val outcomes: List<Answer> = listOf(
        Answer(200, "{}"), Answer(200, "not json"), Answer(200, ""), Answer(204),
        Answer(301, location = "https://api.digiscope.me/elsewhere"), Answer(307, location = "https://other.example/x"),
        Answer(400, "{}"), Answer(401, "{}"), Answer(403, "{}"), Answer(404, "{}"), Answer(429, "{}"),
        Answer(500, "{}"), Answer(503, ""),
    )

    @Test fun `every Hub call closes every answer it is given, whatever the answer`() {
        val failures = mutableListOf<String>()
        for ((name, call) in calls) {
            for (outcome in outcomes) {
                val server = ScriptedServer { outcome }
                runBlocking { call(hub(server)) }
                if (server.answers.isEmpty()) failures += "$name: no request was made (the case is blind)"
                server.answers.forEachIndexed { i, answer ->
                    if (!answer.closed) failures += "$name, answer ${outcome.code}: answer ${i + 1} was left open"
                }
            }
        }
        assertTrue(failures.joinToString("\n", prefix = "\n"), failures.isEmpty())
    }

    @Test fun `a quick login closes the challenge answer and the login answer, whatever the login answer`() {
        val failures = mutableListOf<String>()
        for (outcome in outcomes + Answer(200, """{"sessionToken":"session"}""")) {
            val server = ScriptedServer { request ->
                if (request.url.encodedPath == CHALLENGE_PATH) Answer(200, """{"uri":"$DIGISCOPE_CHALLENGE"}""") else outcome
            }
            runBlocking { hub(server).quickLogin() }
            if (server.seen.map { it.url.encodedPath } != listOf(CHALLENGE_PATH, CALLBACK_PATH)) {
                failures += "login answer ${outcome.code}: the requests were ${server.seen.map { it.url.encodedPath }}"
            }
            server.answers.forEachIndexed { i, answer ->
                if (!answer.closed) failures += "login answer ${outcome.code}: answer ${i + 1} was left open"
            }
        }
        assertEquals(failures.joinToString("\n", prefix = "\n"), emptyList<String>(), failures)
    }
}
