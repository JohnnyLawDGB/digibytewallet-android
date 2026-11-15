package app.aroundtheblock.wallet.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Re-review item 3. Invariant: "PIN changed" is shown only when the new PIN landed on disk; a write
 * that did not land says the old PIN is still active (sec_pin_change_failed, every locale).
 */
class PinChangeResultGateTest {
    private val vm = File("src/main/java/app/aroundtheblock/wallet/ui/settings/SettingsViewModel.kt").readText()
    private val screen = File("src/main/java/app/aroundtheblock/wallet/ui/settings/SecuritySettingsScreen.kt").readText()

    @Test fun `changePin reports whether the new PIN landed`() {
        assertTrue(Regex("""suspend fun changePin\(newPin: String\): Boolean""").containsMatchIn(vm))
    }

    @Test fun `the success message follows the result, and a failure says the old PIN stays`() {
        val confirm = screen.substringAfter("SecurityDialog.ChangePinConfirm ->").substringBefore("onBackspace")
        assertFalse("success shown regardless of the write", Regex("""changePin\([^)]*\)\s*\n\s*resetDialogState\(\)\s*\n\s*snackMessage = pinChangedMsg""").containsMatchIn(confirm))
        assertTrue(confirm.contains("if (landed) pinChangedMsg else pinChangeFailedMsg"))
        assertTrue(screen.contains("stringResource(R.string.sec_pin_change_failed)"))
    }
}
