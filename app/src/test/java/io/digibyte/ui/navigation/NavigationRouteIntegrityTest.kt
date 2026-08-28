package io.digibyte.ui.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every route the app navigates to must be a route the app registers.
 *
 * ## Why this is worth a gate
 *
 * `navController.navigate("some_route")` takes a String. A typo, a rename, or a screen split that
 * adds a destination and forgets to register it all compile perfectly and fail only when a user
 * reaches that tap. In onboarding that is not cosmetic — a dead route between the recovery phrase
 * and the PIN strands someone midway through creating a wallet, on the one flow they cannot skip
 * and cannot back out of.
 *
 * The passphrase screen was added exactly this way: a new destination, navigated to from
 * SeedVerifyScreen. Compilation proves nothing about whether the string on one side matches the
 * string on the other.
 */
class NavigationRouteIntegrityTest {

    private val uiRoot = File("src/main/java/io/digibyte/ui")
    private val navFile = File(uiRoot, "navigation/AppNavigation.kt")

    /**
     * A route reduced to the part that identifies the destination.
     *
     * Registration and navigation legitimately spell the same destination differently:
     * `"send?address={address}"` is navigated to as `"send"`, and `"seed_display/{wordCount}"` as
     * `"seed_display/12"`. Optional arguments have defaults, so the short form really does
     * resolve. Comparing raw strings flags all of those as dangling — which is how the first
     * version of this gate produced ten false positives and zero real ones.
     */
    private fun base(route: String): String =
        route.substringBefore("?").substringBefore("/{").substringBefore("/")

    /**
     * Routes registered as destinations, from both spellings: a literal passed to `composable(...)`
     * (including the multi-line form with an `arguments` list), and the sealed [Screen] class,
     * whose entries are registered as `composable(Screen.Wallet.route)`.
     */
    private fun registered(): Set<String> {
        val text = navFile.readText()
        val literals = Regex("""composable\(\s*"([^"]+)"""").findAll(text)
            .map { base(it.groupValues[1]) }
        val screenRoutes = Regex("""data object \w+ : Screen\(\s*"([^"]+)"""").findAll(text)
            .map { base(it.groupValues[1]) }
        return (literals + screenRoutes).toSet()
    }

    /**
     * Routes the code navigates to, with their source file. Interpolated targets are skipped —
     * their value is not knowable statically, and guessing would make this gate lie.
     */
    private fun navigationTargets(): List<Pair<String, String>> =
        uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("""navigate\(\s*"([^"$]+)"""").findAll(file.readText())
                    .map { it.groupValues[1] to file.path }
            }
            .toList()

    @Test fun `every navigation target is a registered destination`() {
        val known = registered()
        assertTrue("no routes parsed — the gate's regex no longer matches AppNavigation", known.size > 10)

        val dangling = navigationTargets().filter { (target, _) -> base(target) !in known }

        assertTrue(
            "navigate() targets with no matching composable():\n" +
                dangling.joinToString("\n") { (t, f) -> "  \"$t\"  in $f" },
            dangling.isEmpty(),
        )
    }

    /** The specific path this gate was written for: seed -> verify -> passphrase -> PIN. */
    @Test fun `the onboarding chain is registered end to end`() {
        val known = registered()
        listOf("seed_verify", "seed_passphrase", "pin_setup").forEach {
            assertTrue("onboarding route $it is not registered", it in known)
        }
        assertTrue("seed_display is not registered", "seed_display" in known)
    }
}
