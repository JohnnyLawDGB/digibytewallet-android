package app.aroundtheblock.wallet.ui.onboarding

import app.aroundtheblock.wallet.R
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
 * SeedKeyBindAtCreationTest. The prompt needs an Activity inside a Compose click handler, which a JVM
 * test cannot drive, so the wiring is pinned here: each step that writes a NEW record runs
 * prepareSeedKeyBeforeRecordWrite BEFORE the write, and the status line maps every state.
 */
class SeedKeyBindAtCreationWiringTest {

    private fun code(path: String): String {
        val file = File("src/main/java/app/aroundtheblock/wallet/$path")
        assertTrue("$path is missing — this gate is watching a file that moved", file.exists())
        return KotlinSourceGate.of(file.readText()).code
    }

    private fun assertPrecedes(path: String, first: String, then: String) {
        val c = code(path)
        val a = c.indexOf(first)
        val b = c.indexOf(then)
        assertTrue("$path does not call $first", a >= 0)
        assertTrue("$path does not call $then", b >= 0)
        assertTrue("$path writes the record ($then) before preparing the seed key ($first)", a < b)
    }

    @Test fun `KS-BIND PIN setup prepares the seed key before it creates the wallet`() =
        assertPrecedes("ui/onboarding/PinSetupScreen.kt", "prepareSeedKeyBeforeRecordWrite(", "viewModel.createWallet(")

    @Test fun `KS-BIND the restore prepares the seed key before it writes the restored record`() =
        assertPrecedes("ui/onboarding/RecoveryDateScreen.kt", "prepareSeedKeyBeforeRecordWrite(", "viewModel.recoverWallet")

    @Test fun `KS-BIND PIN setup tells a device with no screen lock`() {
        val c = code("ui/onboarding/PinSetupScreen.kt")
        assertTrue("the no-screen-lock status is never checked at setup", c.contains("SeedKeyStatus.NO_LOCK_SCREEN"))
        assertTrue("the no-screen-lock notice is never shown at setup", c.contains("NoScreenLockNotice("))
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
        assertNull(seedKeyStatusSubtitle(SeedKeyStatus.NO_WALLET))
        assertNull(seedKeyStatusSubtitle(null))
        assertFalse("only BOUND may show the bound icon",
            SeedKeyStatus.values().filter { it != SeedKeyStatus.BOUND }.any { seedKeyStatusSubtitle(it)?.first == true })
        assertTrue("Security settings never renders the status line",
            code("ui/settings/SecuritySettingsScreen.kt").contains("seedKeyStatusSubtitle(seedKeyStatus)"))
    }
}
