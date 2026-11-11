package app.aroundtheblock.wallet.ui.onboarding

import androidx.fragment.app.FragmentActivity
import app.aroundtheblock.wallet.core.WalletManager
import app.aroundtheblock.wallet.core.security.BiometricAuth
import app.aroundtheblock.wallet.core.security.BiometricResult
import app.aroundtheblock.wallet.core.security.SeedKeyReadiness
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KS-BIND. Invariant: on a device with a secure lock screen the seed is wrapped under the auth-bound
 * key from creation. This drives the shipped [prepareSeedKeyBeforeRecordWrite] — the function PIN
 * setup and the restore's start call — with a mocked WalletManager, BiometricAuth and activity:
 * a lapsed auth window prompts exactly once and the call does not return before the prompt has;
 * every other readiness, and a missing activity, never prompts.
 */
class SeedKeyBindingStepTest {

    private val activity = mockk<FragmentActivity>(relaxed = true)

    private class Run(val readiness: SeedKeyReadiness, val prompts: Int, val promptFinishedBeforeReturn: Boolean)

    private fun run(answer: () -> SeedKeyReadiness, withActivity: Boolean = true): Run {
        val wm = mockk<WalletManager>()
        every { wm.prepareSeedKeyForNewRecord() } answers { answer() }
        val bio = mockk<BiometricAuth>()
        var prompts = 0
        var promptDone = false
        coEvery { bio.authenticateDeviceCredential(any(), any(), any()) } coAnswers {
            prompts++
            kotlinx.coroutines.delay(50)   // a prompt that is not awaited would finish after the return
            promptDone = true
            BiometricResult.Success
        }
        val r = runBlocking {
            prepareSeedKeyBeforeRecordWrite(wm, bio, if (withActivity) activity else null, "title", "subtitle")
        }
        return Run(r, prompts, promptDone)
    }

    @Test fun `KS-BIND a lapsed window prompts exactly once and is awaited before the write`() {
        val r = run({ SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL })
        assertEquals(SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL, r.readiness)
        assertEquals("exactly one device-credential prompt", 1, r.prompts)
        assertTrue("the write could start before the prompt finished", r.promptFinishedBeforeReturn)
    }

    @Test fun `KS-BIND the prompt carries the caller's text and activity`() {
        val wm = mockk<WalletManager>()
        every { wm.prepareSeedKeyForNewRecord() } returns SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL
        val bio = mockk<BiometricAuth>()
        coEvery { bio.authenticateDeviceCredential(any(), any(), any()) } returns BiometricResult.Error(10, "cancelled")
        runBlocking { prepareSeedKeyBeforeRecordWrite(wm, bio, activity, "T", "S") }
        coVerify(exactly = 1) { bio.authenticateDeviceCredential(activity, "T", "S") }
    }

    @Test fun `KS-BIND READY, NO_LOCK_SCREEN and UNAVAILABLE never prompt`() {
        for (readiness in listOf(SeedKeyReadiness.READY, SeedKeyReadiness.NO_LOCK_SCREEN, SeedKeyReadiness.UNAVAILABLE)) {
            val r = run({ readiness })
            assertEquals(readiness, r.readiness)
            assertEquals("readiness=$readiness prompted", 0, r.prompts)
        }
    }

    @Test fun `KS-BIND no activity means no prompt`() {
        val r = run({ SeedKeyReadiness.NEEDS_DEVICE_CREDENTIAL }, withActivity = false)
        assertEquals(0, r.prompts)
        assertFalse(r.promptFinishedBeforeReturn)
    }

    @Test fun `KS-BIND a throwing WalletManager answers UNAVAILABLE and never prompts`() {
        val r = run({ throw IllegalStateException("keystore") })
        assertEquals(SeedKeyReadiness.UNAVAILABLE, r.readiness)
        assertEquals(0, r.prompts)
    }
}
