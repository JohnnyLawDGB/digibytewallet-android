package app.aroundtheblock.wallet.ui.onboarding

import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
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
        val outside = src.code.replace(Regex("""internal fun verifySuccessLabels[\s\S]*?\n}\n"""), "")
        for (pinText in listOf("R.string.verify_setup_pin", "R.string.verify_success_body)")) {
            assertTrue("$pinText is used outside verifySuccessLabels", !outside.contains(pinText))
        }
    }
}
