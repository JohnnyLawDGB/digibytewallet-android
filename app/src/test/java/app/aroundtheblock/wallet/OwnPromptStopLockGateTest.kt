package app.aroundtheblock.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SEED-VIEW-AUTH: onStop skips its immediate lock only through OwnCredentialPromptExemption,
 * the device-credential prompt arms it and clears it on every completion path, and the
 * seed-view biometric gate's negative button reads the shared Cancel string.
 */
class OwnPromptStopLockGateTest {

    private val root = File("..").canonicalFile
    private fun src(p: String) = File(root, p).readText()
    private val mainActivity = "app/src/main/java/app/aroundtheblock/wallet/MainActivity.kt"
    private val biometric = "core/src/main/java/app/aroundtheblock/wallet/core/security/BiometricAuth.kt"
    private val security = "app/src/main/java/app/aroundtheblock/wallet/ui/settings/SecuritySettingsScreen.kt"

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

    @Test fun `onStop consults the exemption and otherwise locks`() {
        val onStop = body(src(mainActivity), "override fun onStop()")
        assertTrue(onStop.contains("OwnCredentialPromptExemption.consumeForStop("))
        assertTrue(onStop.contains("walletManager.lockUi()"))
        assertTrue(onStop.contains("walletManager.enterBackground()"))
    }

    @Test fun `a deferred stop lock is re-checked on start and fires at the bound`() {
        val text = src(mainActivity)
        val onStart = body(text, "override fun onStart()")
        assertTrue(onStart.contains("OwnCredentialPromptExemption.lockOnStop("))
        assertTrue(text.contains("OwnCredentialPromptExemption.remainingMs("))
        assertTrue(text.contains("postDelayed(deferredStopLock"))
        val onDestroy = body(text, "override fun onDestroy()")
        assertTrue(onDestroy.contains("walletManager.lockUi()"))
    }

    @Test fun `no other file skips the stop lock`() {
        val users = File(root, "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("OwnCredentialPromptExemption") }
            .map { it.name }.toList()
        assertEquals(listOf("MainActivity.kt"), users)
    }

    @Test fun `the device-credential prompt arms and clears on every completion path`() {
        val fn = body(src(biometric), "suspend fun authenticateDeviceCredential(")
        assertEquals("one begin", 1, Regex("""OwnCredentialPromptExemption\.begin\(""").findAll(fn).count())
        // success, error, thrown launch, coroutine cancellation
        assertEquals("end on each of 4 paths", 4, Regex("""OwnCredentialPromptExemption\.end\(\)""").findAll(fn).count())
        val success = body(fn, "override fun onAuthenticationSucceeded(")
        val error = body(fn, "override fun onAuthenticationError(")
        val cancel = body(fn, "cont.invokeOnCancellation")
        val thrown = body(fn, "catch (t: Throwable)")
        listOf(success, error, cancel, thrown).forEach {
            assertTrue(it, it.contains("OwnCredentialPromptExemption.end()"))
        }
        // the plain biometric prompt (no activity) never arms it
        val plain = body(src(biometric), "suspend fun authenticate(")
        assertTrue(!plain.contains("OwnCredentialPromptExemption"))
    }

    @Test fun `seed-view biometric gate's negative button reads Cancel`() {
        val text = src(security)
        val call = text.substring(text.indexOf("val result = biometricAuth.authenticate("))
            .substringBefore(")\n")
        assertTrue(call, call.contains("negativeButtonText = cancelLabel"))
        assertTrue(text.contains("val cancelLabel = stringResource(R.string.common_cancel)"))
    }
}
