package app.aroundtheblock.wallet.ui.onboarding

import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One app PIN guards every wallet: adding a wallet never sets one. The seed check's success screen
 * says what comes next, so in ADD mode it must not promise a PIN setup the add never reaches.
 */
class SeedVerifyAddModeTest {

    private val screen = File("src/main/java/app/aroundtheblock/wallet/ui/onboarding/SeedVerifyScreen.kt")

    @Test fun `add mode's success screen promises no PIN`() {
        assertEquals(VerifySuccessLabels(R.string.verify_success_body_add, R.string.recover_continue), verifySuccessLabels(OnboardingMode.ADD))
    }

    @Test fun `the first run still sets up the PIN next`() {
        assertEquals(VerifySuccessLabels(R.string.verify_success_body, R.string.verify_setup_pin), verifySuccessLabels(OnboardingMode.FIRST_RUN))
    }

    @Test fun `the screen takes its labels from the mode and never goes to PIN setup`() {
        val src = KotlinSourceGate.of(screen.readText())
        assertTrue("the screen does not pick its labels by mode", src.code.contains("verifySuccessLabels(viewModel.mode)"))
        val toPin = src.calls("navController.navigate").filter { it.arguments.firstOrNull() == "\"pin_setup\"" }
        assertTrue("the seed check navigates to pin_setup", toPin.isEmpty())
        // The PIN wording is reached only through verifySuccessLabels, never written straight into the screen.
        // Only that function is set aside: from its declaration to the composable after it, with nothing
        // else at the top level in between. The success screen that shows the labels stays in what is read.
        val labels = src.range("internal fun verifySuccessLabels", "@Composable")
        assertNotNull("verifySuccessLabels is not followed by a composable", labels)
        val setAside = src.code.substring(labels!!).lines().drop(1)
        assertTrue("more than verifySuccessLabels is set aside", setAside.all { it.isBlank() || it.first().isWhitespace() })
        val outside = src.code.removeRange(labels)
        assertTrue("the success screen is not read", outside.contains("fun VerifyResultScreen("))
        // Each id is matched as a whole name, however the reference around it is written: inside a
        // ternary, with the argument on a line of its own, or through a qualified or renamed R.
        // verify_success_body_add, the ADD body, is another name and does not match.
        for (id in listOf("verify_setup_pin", "verify_success_body")) {
            val named = Regex("""(?<!\w)$id(?!\w)""").containsMatchIn(outside)
            assertFalse("R.string.$id is used outside verifySuccessLabels", named)
        }
    }
}
