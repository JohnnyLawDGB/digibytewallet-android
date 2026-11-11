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
 * SeedKeyBindAtCreationTest and the app's prepareSeedKeyBeforeRecordWrite in SeedKeyBindingStepTest.
 * The Compose click handlers that call it are pinned here: each step that writes a NEW record runs
 * prepareSeedKeyBeforeRecordWrite BEFORE the write, in the same coroutine (awaited), and the status
 * line maps every state.
 */
class SeedKeyBindAtCreationWiringTest {

    private fun code(path: String): String {
        val file = File("src/main/java/app/aroundtheblock/wallet/$path")
        assertTrue("$path is missing — this gate is watching a file that moved", file.exists())
        return KotlinSourceGate.of(file.readText()).code
    }

    /**
     * [first] is called, then [then], in the same coroutine: every call of [first] is followed by
     * [then] with nothing between them that would let the write run before the prompt is done
     * (a `launch`, `async` or `post`, i.e. the prompt no longer awaited).
     */
    private fun assertPrecedes(path: String, first: String, then: String) {
        val c = code(path)
        val a = c.indexOf(first)
        assertTrue("$path does not call $first", a >= 0)
        assertEquals("$path calls $first more than once; this gate checks one call", -1, c.indexOf(first, a + 1))
        val b = c.indexOf(then, a)
        assertTrue("$path writes the record ($then) before preparing the seed key ($first), or not at all", b > a)
        val between = c.substring(a, b)
        for (escape in listOf("launch", "async", "post(", "post {")) {
            assertFalse("$path: the write is not awaited behind the prompt ('$escape' between them)", escape in between)
        }
        assertEquals("$path writes the record somewhere before preparing the seed key", -1, c.substring(0, a).indexOf(then))
    }

    @Test fun `KS-BIND PIN setup prepares the seed key before it creates the wallet`() =
        assertPrecedes("ui/onboarding/PinSetupScreen.kt", "prepareSeedKeyBeforeRecordWrite(", "viewModel.createWallet(")

    @Test fun `KS-BIND the restore prepares the seed key before it writes the restored record`() =
        assertPrecedes("ui/onboarding/RecoveryDateScreen.kt", "prepareSeedKeyBeforeRecordWrite(", "viewModel.recoverWallet")

    @Test fun `KS-BIND PIN setup tells a device with no screen lock`() {
        val c = code("ui/onboarding/PinSetupScreen.kt")
        assertTrue("the no-screen-lock status is never checked at setup", c.contains("SeedKeyStatus.NO_LOCK_SCREEN"))
        assertTrue("the no-screen-lock notice is never shown at setup", c.contains("NoScreenLockNotice("))
        assertTrue("the notice is not marked due, so one lost to the lock screen is never shown again",
            c.contains("NoScreenLockNoticeFlags.markDueIfUnacknowledged("))
        assertTrue("dismissing the notice does not acknowledge it", c.contains("NoScreenLockNoticeFlags.acknowledge("))
        val main = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/MainActivity.kt").readText()).code
        assertTrue("a due notice is never shown again after the lock screen took it", main.contains("NoScreenLockNoticeFlags.isDue("))
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
