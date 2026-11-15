package app.aroundtheblock.wallet.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SEED-VIEW-AUTH (decision A): the onStop lock is deferred only for the stop caused by the app's
 * own device-credential confirm activity (API <= 28: the confirm intent we launch; API 29: the
 * framework's), and is resolved to "keep unlocked" only by proof that the confirm succeeded.
 */
class OwnCredentialPromptExemptionTest {

    private val x = OwnCredentialPromptExemption
    private val max = OwnCredentialPromptExemption.MAX_EXEMPTION_MS
    // KeyguardManager.ACTION_CONFIRM_DEVICE_CREDENTIAL is @hide; this is its AOSP value, the action
    // of the intent createConfirmDeviceCredentialIntent returns (what androidx.biometric launches).
    private val confirm = "android.app.action.CONFIRM_DEVICE_CREDENTIAL"
    private val ok = OwnCredentialPromptExemption.RESULT_OK
    private val canceled = 0

    @Before fun reset() = x.resetForTest()

    // ---- the pure rules: which API level arms, and where ----

    @Test fun `the confirm action literal is the AOSP value`() {
        assertEquals(confirm, OwnCredentialPromptExemption.ACTION_CONFIRM_DEVICE_CREDENTIAL)
    }

    @Test fun `API 28 and below arm on the confirm intent launch only`() {
        for (sdk in 26..28) {
            assertTrue(x.armsOnLaunch(sdk, confirm))
            assertFalse(x.armsOnLaunch(sdk, "android.intent.action.VIEW"))
            assertFalse(x.armsOnLaunch(sdk, null))
            assertFalse(x.armsAtPromptStart(sdk))
        }
    }

    @Test fun `API 29 arms at prompt start, not on a launch`() {
        assertTrue(x.armsAtPromptStart(29))
        assertFalse(x.armsOnLaunch(29, confirm))
    }

    @Test fun `API 30 and above never arm`() {
        for (sdk in 30..36) {
            assertFalse(x.armsAtPromptStart(sdk))
            assertFalse(x.armsOnLaunch(sdk, confirm))
        }
    }

    @Test fun `the bound is at most 30 s`() = assertTrue(max in 1L..30_000L)

    @Test fun `lockOnStop - none, expired and future-dated arms lock`() {
        assertTrue(x.lockOnStop(null, 1_000L))
        assertFalse(x.lockOnStop(1_000L, 1_000L + max - 1))
        assertTrue(x.lockOnStop(1_000L, 1_000L + max))
        assertTrue(x.lockOnStop(5_000L, 4_999L))
    }

    @Test fun `lockOnResume - only a success proof inside the bound keeps the UI unlocked`() {
        fun d(ok: Boolean = false, succ: Boolean = false, left: Boolean = false, via: OwnCredentialPromptExemption.Via) =
            OwnCredentialPromptExemption.DeferredStop(1_000L, via, 7, ok, succ, left)
        val now = 2_000L
        val L = OwnCredentialPromptExemption.Via.CONFIRM_INTENT
        val F = OwnCredentialPromptExemption.Via.API29_FRAMEWORK
        assertFalse(x.lockOnResume(d(ok = true, via = L), now))
        assertTrue(x.lockOnResume(d(via = L), now))                         // no result / CANCELED
        assertTrue(x.lockOnResume(d(ok = true, left = true, via = L), now)) // user-leave hint
        assertTrue(x.lockOnResume(d(ok = true, via = L), 1_000L + max))     // expired
        assertTrue(x.lockOnResume(d(succ = true, via = L), now))            // callback is not the proof on <=28
        assertFalse(x.lockOnResume(d(succ = true, via = F), now))
        assertTrue(x.lockOnResume(d(via = F), now))
        assertTrue(x.lockOnResume(d(ok = true, via = F), now))              // 29's proof is the callback
        assertTrue(x.lockOnResume(d(succ = true, left = true, via = F), now))
        assertTrue(x.lockOnResume(d(succ = true, via = F), 1_000L + max))
        assertTrue(x.lockOnResume(null, now).not())                         // nothing deferred: nothing to do
    }

    @Test fun `lockOnTimer - a deferred stop still pending at the deadline always locks`() {
        val d = OwnCredentialPromptExemption.DeferredStop(1_000L, OwnCredentialPromptExemption.Via.CONFIRM_INTENT, 7, true, true, false)
        assertTrue(x.lockOnTimer(d))
        assertFalse(x.lockOnTimer(null))
    }

    // ---- the holder, per API level ----

    @Test fun `API 30+ - a stop during our prompt locks (system dialog, user left)`() {
        x.openPrompt(1_000L, 33)
        assertFalse(x.consumeForStop(1_500L))
    }

    @Test fun `API 28 - Home while the biometric dialog is up locks (no confirm launched)`() {
        x.openPrompt(1_000L, 28)
        assertFalse(x.consumeForStop(1_500L))
    }

    @Test fun `API 28 - confirm launch then stop defers, RESULT_OK keeps unlocked`() {
        x.openPrompt(1_000L, 28)
        x.onActivityLaunch(1_100L, 28, confirm, 42)
        assertTrue(x.consumeForStop(1_200L))
        x.onActivityResult(42, ok)
        assertFalse(x.resolveOnResume(5_000L))
    }

    @Test fun `API 28 - CANCELED locks`() {
        x.openPrompt(1_000L, 28); x.onActivityLaunch(1_100L, 28, confirm, 42)
        assertTrue(x.consumeForStop(1_200L))
        x.onActivityResult(42, canceled)
        assertTrue(x.resolveOnResume(5_000L))
    }

    @Test fun `API 28 - Home from the confirm, back via launcher (no result) locks`() {
        x.openPrompt(1_000L, 28); x.onActivityLaunch(1_100L, 28, confirm, 42)
        assertTrue(x.consumeForStop(1_200L))
        assertTrue(x.resolveOnResume(5_000L))
    }

    @Test fun `API 28 - a result for another request code is not proof`() {
        x.openPrompt(1_000L, 28); x.onActivityLaunch(1_100L, 28, confirm, 42)
        assertTrue(x.consumeForStop(1_200L))
        x.onActivityResult(43, ok)
        assertTrue(x.resolveOnResume(5_000L))
    }

    @Test fun `API 28 - OK after the bound locks`() {
        x.openPrompt(1_000L, 28); x.onActivityLaunch(1_100L, 28, confirm, 42)
        assertTrue(x.consumeForStop(1_200L))
        x.onActivityResult(42, ok)
        assertTrue(x.resolveOnResume(1_100L + max))
    }

    @Test fun `user-leave hint while deferred locks`() {
        x.openPrompt(1_000L, 28); x.onActivityLaunch(1_100L, 28, confirm, 42)
        assertTrue(x.consumeForStop(1_200L))
        x.onActivityResult(42, ok)
        assertTrue(x.onUserLeaveHint())          // locks at the hint
        assertFalse(x.isDeferred())              // and nothing is left for a later resume to keep
        assertFalse(x.onUserLeaveHint())         // no deferral, no signal
    }

    @Test fun `a confirm launch with no prompt of ours open does not arm`() {
        x.onActivityLaunch(1_100L, 28, confirm, 42)
        assertFalse(x.consumeForStop(1_200L))
    }

    @Test fun `API 29 - success callback after the stop keeps unlocked, none locks`() {
        val g = x.openPrompt(1_000L, 29)
        assertTrue(x.consumeForStop(1_200L))
        x.closePrompt(g, success = true)
        assertFalse(x.resolveOnResume(5_000L))

        val g2 = x.openPrompt(10_000L, 29)
        assertTrue(x.consumeForStop(10_200L))
        assertTrue(x.resolveOnResume(10_500L))
        x.closePrompt(g2, success = false)
    }

    @Test fun `API 29 - error callback is not proof`() {
        val g = x.openPrompt(1_000L, 29)
        assertTrue(x.consumeForStop(1_200L))
        x.closePrompt(g, success = false)
        assertTrue(x.resolveOnResume(5_000L))
    }

    @Test fun `the arm is good for one stop`() {
        x.openPrompt(1_000L, 29)
        assertTrue(x.consumeForStop(1_200L))
        x.resolveOnResume(1_300L)
        assertFalse(x.consumeForStop(1_400L))
    }

    @Test fun `a stop after the bound locks`() {
        x.openPrompt(1_000L, 29)
        assertFalse(x.consumeForStop(1_000L + max))
    }

    @Test fun `closing the prompt before any stop disarms`() {
        val g = x.openPrompt(1_000L, 29)
        x.closePrompt(g, success = false)
        assertFalse(x.consumeForStop(1_100L))
    }

    @Test fun `a late close from an older prompt leaves the newer arm alone`() {
        val g1 = x.openPrompt(1_000L, 29)
        val g2 = x.openPrompt(1_100L, 29)
        x.closePrompt(g1, success = true)
        assertTrue(x.consumeForStop(1_200L))
        x.closePrompt(g1, success = true)        // stale success is not proof for g2
        assertTrue(x.resolveOnResume(1_300L))
        x.closePrompt(g2, success = false)
    }

    @Test fun `timer at the deadline locks a pending deferral, once`() {
        x.openPrompt(1_000L, 28); x.onActivityLaunch(1_100L, 28, confirm, 42)
        assertTrue(x.consumeForStop(1_200L))
        assertEquals(max - 100L, x.remainingMs(1_200L))
        assertTrue(x.resolveOnTimer())
        assertFalse(x.resolveOnTimer())
        assertFalse(x.resolveOnResume(1_300L))   // already locked; nothing left to resolve
    }

    @Test fun `resolving on resume clears the deferral, so the timer does nothing after`() {
        x.openPrompt(1_000L, 28); x.onActivityLaunch(1_100L, 28, confirm, 42)
        assertTrue(x.consumeForStop(1_200L))
        x.onActivityResult(42, ok)
        assertFalse(x.resolveOnResume(2_000L))
        assertFalse(x.resolveOnTimer())
        assertFalse(x.isDeferred())
    }
}
