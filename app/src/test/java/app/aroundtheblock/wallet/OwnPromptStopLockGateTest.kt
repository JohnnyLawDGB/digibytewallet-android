package app.aroundtheblock.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SEED-VIEW-AUTH: MainActivity defers its stop lock only through OwnCredentialPromptExemption,
 * arms it only from the confirm-intent launch (API <= 28) or the prompt's open on API 29, and
 * resolves every deferral at resume, deadline, user-leave hint or a non-rotation destroy.
 */
class OwnPromptStopLockGateTest {

    private val root = File("..").canonicalFile
    private fun src(p: String) = File(root, p).readText()
    private val mainActivity = "app/src/main/java/app/aroundtheblock/wallet/MainActivity.kt"
    private val biometric = "core/src/main/java/app/aroundtheblock/wallet/core/security/BiometricAuth.kt"
    private val rule = "core/src/main/java/app/aroundtheblock/wallet/core/security/OwnCredentialPromptExemption.kt"

    private fun body(text: String, header: String): String {
        val start = text.indexOf(header)
        assertTrue("missing $header", start >= 0)
        var i = text.indexOf('{', start); var depth = 0
        val from = i
        while (i < text.length) {
            when (text[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return text.substring(from, i + 1) } }
            i++
        }
        error("unbalanced $header")
    }

    @Test fun `onStop defers only through the exemption and otherwise locks`() {
        val onStop = body(src(mainActivity), "override fun onStop()")
        assertTrue(onStop.contains("OwnCredentialPromptExemption.consumeForStop("))
        assertTrue(onStop.contains("walletManager.lockUi()"))
        assertTrue(onStop.contains("walletManager.enterBackground()"))
    }

    @Test fun `arming is pinned to the confirm launch and API 29 - never 30+`() {
        val r = src(rule)
        assertTrue(r.contains("fun armsAtPromptStart(sdk: Int): Boolean = sdk == 29"))
        assertTrue(r.contains("sdk <= 28 && action == ACTION_CONFIRM_DEVICE_CREDENTIAL"))
        assertTrue(r.contains("const val ACTION_CONFIRM_DEVICE_CREDENTIAL = \"android.app.action.CONFIRM_DEVICE_CREDENTIAL\""))
        val launch = body(src(mainActivity), "override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?)")
        assertTrue(launch.contains("OwnCredentialPromptExemption.onActivityLaunch("))
        assertTrue(launch.contains("android.os.Build.VERSION.SDK_INT, intent.action, requestCode"))
        assertTrue(launch.contains("super.startActivityForResult(intent, requestCode, options)"))
        // the only arming calls in the app are these two
        val open = body(src(biometric), "suspend fun authenticateDeviceCredential(")
        assertTrue(open.contains("OwnCredentialPromptExemption.openPrompt(SystemClock.elapsedRealtime(), Build.VERSION.SDK_INT)"))
    }

    @Test fun `the confirm result is forwarded and super is called`() {
        val res = body(src(mainActivity), "override fun onActivityResult(")
        assertTrue(res.contains("OwnCredentialPromptExemption.onActivityResult(requestCode, resultCode)"))
        assertTrue(res.contains("super.onActivityResult(requestCode, resultCode, data)"))
    }

    @Test fun `every deferral is resolved - resume, deadline, leave hint, destroy`() {
        val text = src(mainActivity)
        assertTrue(body(text, "override fun onResume()").contains("resolveDeferredStopOnResume()"))
        val resolve = body(text, "private fun resolveDeferredStopOnResume()")
        assertTrue(resolve.contains("OwnCredentialPromptExemption.resolveOnResume("))
        assertTrue(resolve.contains("lockUiIfUnlocked()"))
        assertTrue("API 29 decides after queued callbacks", resolve.contains("stopLockHandler.post(decide)"))
        val timer = body(text, "private val deferredStopLock = Runnable")
        assertTrue(timer.contains("if (OwnCredentialPromptExemption.resolveOnTimer()) lockUiIfUnlocked()"))
        assertTrue(text.contains("postDelayed(deferredStopLock"))
        val hint = body(text, "override fun onUserLeaveHint()")
        assertTrue(hint.contains("OwnCredentialPromptExemption.onUserLeaveHint()") && hint.contains("lockUiIfUnlocked()"))
        val destroy = body(text, "override fun onDestroy()")
        assertTrue(destroy.contains("!isChangingConfigurations && OwnCredentialPromptExemption.resolveOnDestroy()"))
        assertTrue(body(text, "private fun lockUiIfUnlocked()").contains("walletManager.lockUi()"))
        assertTrue(body(text, "override fun onCreate(").contains("if (OwnCredentialPromptExemption.isDeferred()) scheduleDeferredStopLock()"))
    }

    @Test fun `no other app file touches the exemption`() {
        val users = File(root, "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("OwnCredentialPromptExemption") }
            .map { it.name }.toList()
        assertEquals(listOf("MainActivity.kt"), users)
    }

    @Test fun `the device-credential prompt closes its own generation on every completion path`() {
        val fn = body(src(biometric), "suspend fun authenticateDeviceCredential(")
        assertEquals(1, Regex("""OwnCredentialPromptExemption\.openPrompt\(""").findAll(fn).count())
        assertEquals(4, Regex("""OwnCredentialPromptExemption\.closePrompt\(promptGen""").findAll(fn).count())
        assertTrue(body(fn, "override fun onAuthenticationSucceeded(").contains("closePrompt(promptGen, success = true)"))
        for (h in listOf("override fun onAuthenticationError(", "cont.invokeOnCancellation", "catch (t: Throwable)")) {
            assertTrue(h, body(fn, h).contains("closePrompt(promptGen, success = false)"))
        }
        assertFalse(body(src(biometric), "suspend fun authenticate(").contains("OwnCredentialPromptExemption"))
    }

    @Test fun `seed-view biometric gate's negative button reads Cancel`() {
        val text = src("app/src/main/java/app/aroundtheblock/wallet/ui/settings/SecuritySettingsScreen.kt")
        val call = text.substring(text.indexOf("val result = biometricAuth.authenticate(")).substringBefore(")\n")
        assertTrue(call, call.contains("negativeButtonText = cancelLabel"))
        assertTrue(text.contains("val cancelLabel = stringResource(R.string.common_cancel)"))
    }
}
