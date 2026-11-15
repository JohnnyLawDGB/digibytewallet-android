package app.aroundtheblock.wallet.ui.onboarding

import app.aroundtheblock.wallet.R
import app.aroundtheblock.wallet.core.shouldPromptForDeviceCredential
import app.aroundtheblock.wallet.core.security.SeedKeyReadiness
import app.aroundtheblock.wallet.core.security.SeedKeyStatus
import app.aroundtheblock.wallet.ui.KotlinSourceGate
import app.aroundtheblock.wallet.ui.settings.seedKeyStatusSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KS-BIND. Invariant: on a device with a secure lock screen the seed is wrapped under the auth-bound
 * key from creation; with none, the app says so. The core half is behaviour-tested in
 * SeedKeyBindAtCreationTest and the ViewModel's seed-key step in SeedKeyBindingStepTest. The Compose
 * click handlers are pinned here: each step that writes a NEW record hands the ViewModel a
 * seedKeyPromptFor prompt, the ViewModel runs prepareSeedKeyThenPrompt BEFORE the write in the same
 * viewModelScope coroutine (awaited), and the status line maps every state.
 */
class SeedKeyBindAtCreationWiringTest {

    private fun code(path: String): String {
        val file = File("src/main/java/app/aroundtheblock/wallet/$path")
        assertTrue("$path is missing — this gate is watching a file that moved", file.exists())
        return KotlinSourceGate.of(file.readText()).code
    }

    /**
     * The screen hands the write a seed-key prompt built by [seedKeyPromptFor], as its FIRST
     * argument, and starts nothing of its own around it: no `scope.launch` that a screen leaving
     * composition would cancel between a saved PIN and the write (round-2 finding).
     */
    private fun assertHandsThePromptToTheWrite(path: String, write: String) {
        val gate = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/$path").readText())
        val calls = gate.calls(write)
        assertEquals("$path: one $write call expected", 1, calls.size)
        val first = calls.single().arguments.firstOrNull().orEmpty()
        assertTrue("$path: $write is not handed the seed-key prompt (got '$first')", first.startsWith("seedKeyPromptFor("))
        assertFalse("$path still prepares the seed key in its own scope", gate.code.contains("prepareSeedKeyBeforeRecordWrite"))
        val enclosing = gate.enclosingBlock(calls.single().range.first)
        val head = if (enclosing != null) gate.code.substring(maxOf(0, enclosing.first - 40), enclosing.first + 1) else ""
        assertFalse("$path: the write runs inside the screen's own coroutine scope", head.contains("launch"))
    }

    /**
     * In the ViewModel, each write ([write]) follows [prepareSeedKeyThenPrompt] in the same
     * `viewModelScope.launch`, with no `launch`/`async` between them (the prompt is awaited).
     */
    private fun assertViewModelPreparesBefore(write: String) {
        val c = code("ui/onboarding/OnboardingViewModel.kt")
        val w = c.indexOf(write)
        assertTrue("the ViewModel never calls $write", w >= 0)
        val launchAt = c.lastIndexOf("viewModelScope.launch", w)
        val prepareAt = c.lastIndexOf("prepareSeedKeyThenPrompt(", w)
        assertTrue("$write is not preceded by the seed-key step", prepareAt >= 0)
        assertTrue("the seed-key step for $write is not in the same viewModelScope coroutine", prepareAt > launchAt && launchAt >= 0)
        val between = c.substring(prepareAt, w)
        for (escape in listOf("launch", "async", "post(", "post {")) {
            assertFalse("$write is not awaited behind the prompt ('$escape' between them)", escape in between)
        }
    }

    @Test fun `KS-BIND PIN setup hands the seed-key prompt to the create`() =
        assertHandsThePromptToTheWrite("ui/onboarding/PinSetupScreen.kt", "viewModel.createWallet")

    @Test fun `KS-BIND the restore hands the seed-key prompt to the restore`() =
        assertHandsThePromptToTheWrite("ui/onboarding/RecoveryDateScreen.kt", "viewModel.recoverWallet")

    @Test fun `KS-BIND the ViewModel prepares the seed key before each record write, in its own scope`() {
        assertViewModelPreparesBefore("walletManager.createWallet(")
        assertViewModelPreparesBefore("walletManager.recoverWallet(")
    }

    @Test fun `KS-BIND PIN setup tells a device with no screen lock`() {
        val c = code("ui/onboarding/PinSetupScreen.kt")
        assertTrue("the no-screen-lock status is never checked at setup", c.contains("SeedKeyStatus.NO_LOCK_SCREEN"))
        assertTrue("the no-screen-lock notice is never shown at setup", c.contains("NoScreenLockNotice("))
        assertTrue("the notice is not marked due, so one lost to the lock screen is never shown again",
            c.contains("NoScreenLockNoticeFlags.markDueIfUnacknowledged("))
        assertTrue("dismissing the notice does not acknowledge it", c.contains("NoScreenLockNoticeFlags.acknowledge("))
        val main = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/MainActivity.kt").readText()).code
        assertTrue("a due notice is never shown again after the lock screen took it", main.contains("NoScreenLockNoticeFlags.isDue("))
        val dueAt = main.indexOf("NoScreenLockNoticeFlags.isDue(")
        val pinAt = main.indexOf("pinManager.pinKnownToExist()", main.lastIndexOf("LaunchedEffect(walletState)", dueAt))
        assertTrue("the notice effect waits on the PIN before asking whether a notice is due at all", pinAt > dueAt)
        assertTrue("the wait for a PIN is unbounded", main.contains("NO_LOCK_NOTICE_PIN_CHECKS"))
        assertTrue("MainActivity never shows the due notice", main.contains("NoScreenLockNotice(onDismiss"))
    }

    @Test fun `KS-BIND a notice is due only until it is acknowledged`() {
        assertTrue(noScreenLockNoticeDue(due = true, acknowledged = false))
        assertFalse(noScreenLockNoticeDue(due = true, acknowledged = true))
        assertFalse(noScreenLockNoticeDue(due = false, acknowledged = false))
        assertFalse(noScreenLockNoticeDue(due = false, acknowledged = true))
    }

    @Test fun `KS-BIND the PIN keypad takes no input while the wallet is being created`() {
        // A backspace and a digit during the save would re-enter CONFIRM and start a second create.
        val c = code("ui/onboarding/PinSetupScreen.kt")
        assertTrue("digits reach the CONFIRM branch while saving", c.contains("onDigit = { if (!isPinSaving) onDigit(it) }"))
        assertTrue("backspace works while saving", c.contains("onBackspace = { if (!isPinSaving) onBackspace() }"))
    }

    @Test fun `KS-BIND only a lapsed auth window prompts`() {
        for (r in SeedKeyReadiness.values()) {
            assertEquals("readiness=$r", r == SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL, shouldPromptForDeviceCredential(r))
        }
    }

    @Test fun `KS-BIND Security settings status line covers every state`() {
        assertEquals(true to R.string.sec_seed_key_bound, seedKeyStatusSubtitle(SeedKeyStatus.BOUND))
        assertEquals(false to R.string.sec_seed_key_pending, seedKeyStatusSubtitle(SeedKeyStatus.PENDING))
        assertEquals(false to R.string.sec_seed_key_no_lock, seedKeyStatusSubtitle(SeedKeyStatus.NO_LOCK_SCREEN))
        assertEquals(false to R.string.sec_seed_key_lock_removed, seedKeyStatusSubtitle(SeedKeyStatus.LOCK_REMOVED))
        assertEquals(false to R.string.sec_seed_key_cannot_bind, seedKeyStatusSubtitle(SeedKeyStatus.CANNOT_BIND))
        assertNull("a key that could not be asked must make no claim", seedKeyStatusSubtitle(SeedKeyStatus.UNCHECKED))
        assertNull(seedKeyStatusSubtitle(SeedKeyStatus.NO_WALLET))
        assertNull(seedKeyStatusSubtitle(null))
        assertFalse("only BOUND may show the bound icon",
            SeedKeyStatus.values().filter { it != SeedKeyStatus.BOUND }.any { seedKeyStatusSubtitle(it)?.first == true })
        val sec = code("ui/settings/SecuritySettingsScreen.kt")
        assertTrue("Security settings never renders the status line", sec.contains("seedKeyStatusSubtitle(seedKeyStatus)"))
        val row = sec.substring(sec.indexOf("seedKeyStatusSubtitle(seedKeyStatus)"))
        assertTrue("the status line is announced as a button that does nothing",
            row.take(600).contains("onClick = null"))
    }
}
