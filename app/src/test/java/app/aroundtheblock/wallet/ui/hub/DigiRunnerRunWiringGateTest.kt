package app.aroundtheblock.wallet.ui.hub

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source gate on the DIGIRUNNER-RUNID wiring, which is Compose code this JVM cannot run
 * (`DigiRunnerRunsTest` covers the run state behind it):
 *  - the game starts a run as it first composes and on every Play Again, and Play Again resets
 *    the game only through the function that does both;
 *  - the DigiRunner route passes `onRunStart` to the game and takes the run being scored in the
 *    tap, before its coroutine, then submits with that run.
 * Each check is shown to fail on the text it guards with the wiring removed.
 */
class DigiRunnerRunWiringGateTest {

    private val gamePath = File("../game/src/main/java/app/aroundtheblock/wallet/game/DigiRunnerGame.kt")
    private val navPath = File("src/main/java/app/aroundtheblock/wallet/ui/navigation/AppNavigation.kt")

    private fun squeezed(text: String) = KotlinSourceGate.squeeze(KotlinSourceGate.of(text).code)

    private fun gameViolations(text: String): List<String> {
        val code = squeezed(text)
        val out = mutableListOf<String>()
        if (!code.contains("LaunchedEffect(Unit) { currentOnRunStart?.invoke() }")) {
            out += "the first composition does not start a run"
        }
        val playAgain = Regex("""fun playAgain\(\) \{([^{}]*)\}""").find(code)?.groupValues?.get(1)
        if (playAgain == null || !playAgain.contains("currentOnRunStart?.invoke()") ||
            !playAgain.contains("gameState = generateInitialState()")
        ) out += "playAgain() does not both reset the game and start a run"
        // Every reset of a finished game goes through playAgain(): the one assignment is in it.
        if (Regex("""gameState = generateInitialState\(\)""").findAll(code).count() != 1) {
            out += "the game is reset outside playAgain()"
        }
        if (!code.contains("onClick = { playAgain() }")) out += "the Play Again button does not call playAgain()"
        return out
    }

    private fun navViolations(text: String): List<String> {
        val gate = KotlinSourceGate.of(text)
        val out = mutableListOf<String>()
        val game = gate.calls("DigiRunnerGame").singleOrNull() ?: return listOf("DigiRunnerGame is not called exactly once")
        val onRunStart = game.valueRange("onRunStart")?.let { KotlinSourceGate.squeeze(gate.code.substring(it.first, it.last + 1)) }
        if (onRunStart != "{ runs.onRunStarted() }") out += "the route does not pass onRunStart = { runs.onRunStarted() } (got $onRunStart)"
        val submit = game.valueRange("onScoreSubmit")?.let { KotlinSourceGate.squeeze(gate.code.substring(it.first, it.last + 1)) } ?: ""
        val capture = submit.indexOf("val run = runs.capture()")
        val launch = submit.indexOf("coroutineScope.launch")
        if (capture < 0 || launch < 0 || capture > launch) out += "the run is not captured in the tap, before the coroutine"
        if (!submit.contains("runs.submit(run,")) out += "the submit does not use the captured run"
        return out
    }

    @Test fun `the game and the route start a run and submit with the run they captured`() {
        assertTrue(gamePath.isFile && navPath.isFile)
        val violations = gameViolations(gamePath.readText()) + navViolations(navPath.readText())
        assertTrue(violations.joinToString("\n", prefix = "\n"), violations.isEmpty())
    }

    @Test fun `the gate fails when the game stops starting runs`() {
        val game = gamePath.readText()
        val noFirst = game.replace("LaunchedEffect(Unit) { currentOnRunStart?.invoke() }", "")
        assertTrue(gameViolations(noFirst).isNotEmpty())
        val noAgain = game.replace("gameState = generateInitialState()\n        currentOnRunStart?.invoke()", "gameState = generateInitialState()")
        assertTrue("the replacement did not apply", noAgain != game)
        assertTrue(gameViolations(noAgain).isNotEmpty())
        val bypass = game.replace("onClick = { playAgain() }", "onClick = { gameState = generateInitialState() }")
        assertTrue(bypass != game && gameViolations(bypass).isNotEmpty())
    }

    @Test fun `the gate fails when the route stops passing onRunStart or captures late`() {
        val nav = navPath.readText()
        val noStart = nav.replace("onRunStart = { runs.onRunStarted() },", "")
        assertTrue(noStart != nav && navViolations(noStart).isNotEmpty())
        val late = nav.replace(
            "val run = runs.capture()\n                            coroutineScope.launch {",
            "coroutineScope.launch {\n                                val run = runs.capture()",
        )
        assertTrue("the replacement did not apply", late != nav)
        assertTrue(navViolations(late).isNotEmpty())
    }
}
