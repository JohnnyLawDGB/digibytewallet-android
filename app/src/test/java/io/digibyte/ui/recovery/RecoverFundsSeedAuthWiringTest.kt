package io.digibyte.ui.recovery

import io.digibyte.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level WIRING gate for the Recover funds screen's side of the device-credential answer.
 *
 * The ViewModel's side is behaviour-tested in [RecoverFundsSeedAuthTest]. The prompt itself needs
 * an Activity and runs in a Compose effect, which a JVM test cannot drive, so the effect is pinned
 * here: keyed on the screen state, it shows the device-credential prompt and hands that prompt's
 * own outcome back to the ViewModel; and each seed message has its own copy.
 */
class RecoverFundsSeedAuthWiringTest {

    private fun gate(name: String): KotlinSourceGate {
        val file = File("src/main/java/io/digibyte/ui/recovery/$name")
        assertTrue("$name is missing — this gate is watching a file that moved", file.exists())
        return KotlinSourceGate.of(file.readText())
    }

    private val screen by lazy { gate("RecoverFundsScreen.kt") }
    private val viewModel by lazy { gate("RecoverFundsViewModel.kt") }

    private fun squeeze(s: String) = s.replace(Regex("\\s+"), " ")

    @Test
    fun `the screen answers a device-credential request with the device-credential prompt`() {
        val effect = screen.calls("LaunchedEffect").singleOrNull { it.arguments == listOf("state") }
        assertNotNull("no LaunchedEffect keyed on the screen state", effect)
        val block = screen.trailingBlock(effect!!)
        assertNotNull("the LaunchedEffect keyed on the state has no body", block)
        val body = squeeze(screen.code.substring(block!!))

        assertTrue("the effect does not act on the device-credential request",
            body.contains("if (state is RecoverFundsViewModel.UiState.NeedsDeviceCredential)"))
        val prompt = screen.calls("deviceCredential.authenticateDeviceCredential", within = block).single()
        val answer = screen.calls("vm.onDeviceCredentialResult", within = block).single()
        assertTrue("the answer is handed back before the prompt ran", prompt.range.first < answer.range.first)
        assertEquals(listOf("confirmed"), answer.arguments)
        assertTrue("the answer is not the prompt's own success",
            body.contains(
                "val confirmed = activity != null && " +
                    "deviceCredential.authenticateDeviceCredential(activity) is " +
                    "io.digibyte.core.security.BiometricResult.Success"
            ))
    }

    @Test
    fun `a waiting device-credential request always offers a way to ask again`() {
        // A prompt the system never showed leaves nothing on top of the screen and no answer on
        // its way; the request's own body must then offer the prompt again.
        val branch = Regex(
            """is\s+RecoverFundsViewModel\.UiState\.NeedsDeviceCredential\s*->\s*CredentialBody\s*\("""
        ).find(screen.code)
        assertNotNull("the device-credential request does not render CredentialBody", branch)
        val body = screen.calls("CredentialBody").single { it.range.first in branch!!.range }
        assertEquals(listOf("onAskAgain = { vm.askDeviceCredentialAgain() }"), body.arguments)

        val composable = screen.range("private fun CredentialBody(", "private fun formatSatToDgb(")
        assertNotNull("CredentialBody is no longer where this gate looks", composable)
        val button = screen.calls("Button", within = composable!!).single()
        assertEquals("onAskAgain", button.named["onClick"])
    }

    @Test
    fun `the error body offers Retry only where trying again can succeed`() {
        val shown = screen.calls("ErrorBody").single()
        assertEquals(
            "if (RecoverFundsViewModel.SeedReason.offersRetry(s.reason)) ({ vm.classify() }) else null",
            shown.named["onRetry"],
        )

        val composable = screen.range("private fun ErrorBody(", "private fun CredentialBody(")
        assertNotNull("ErrorBody is no longer where this gate looks", composable)
        val button = screen.calls("Button", within = composable!!).single()
        assertEquals("onRetry", button.named["onClick"])
        val guarded = screen.branchesOn("onRetry != null", within = composable)
        assertTrue("the Retry button is not drawn only when there is a Retry",
            guarded.any { button.range.first in it })
    }

    @Test
    fun `each own-wallet seed message has its own copy`() {
        val mapper = screen.range("private fun friendlyErrorReason(", "private fun friendlyRawReason(")
        assertNotNull("friendlyErrorReason is no longer where this gate looks", mapper)
        val body = squeeze(screen.code.substring(mapper!!))
        assertTrue(body.contains(
            "RecoverFundsViewModel.SeedReason.KEY_INVALIDATED -> res.getString(R.string.unlock_device_lock_removed)"))
        assertTrue(body.contains(
            "RecoverFundsViewModel.SeedReason.CREDENTIAL_NOT_CONFIRMED -> res.getString(R.string.rf_err_seed)"))
        assertTrue(body.contains(
            "RecoverFundsViewModel.SeedReason.UNAVAILABLE -> res.getString(R.string.rf_err_seed)"))
    }

    @Test
    fun `this wallet's seed is read in one place, the one that answers the key's refusals`() {
        val loads = viewModel.calls("seedProvider.loadSeed")
        assertEquals("seedProvider.loadSeed() is called from more than one place", 1, loads.size)
        val owner = viewModel.range("private fun loadOwnSeed(", "fun classify(")
        assertNotNull("loadOwnSeed is no longer where this gate looks", owner)
        assertTrue("seedProvider.loadSeed() is called outside loadOwnSeed", loads.single().range.first in owner!!)
    }
}
