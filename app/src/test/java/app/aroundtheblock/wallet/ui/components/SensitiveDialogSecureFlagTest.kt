package app.aroundtheblock.wallet.ui.components

import androidx.compose.ui.window.SecureFlagPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * While a sensitive dialog is shown, its window carries FLAG_SECURE — whatever the screen behind it.
 *
 * Two writers touch the dialog window's flag: [SecureWindow] (the counted holder SensitiveDialog takes)
 * and Compose itself. Compose's `Dialog` re-applies its `securePolicy` after EVERY recomposition
 * (DialogWrapper.updateParameters -> setSecurePolicy -> Window.setFlags), and the default policy,
 * `Inherit`, copies the flag of the screen behind. Over the Send screen, which is not secure, each
 * recomposition — the PIN dialog recomposes on every digit — would clear the dialog window's flag
 * while the holder still counts one. The policy SensitiveDialog opens its dialog with must therefore
 * agree with the holder.
 *
 * The rule Compose applies is called here as Compose ships it (by reflection, since it is not part
 * of its stable API), so this test follows the library rather than a copy of it.
 */
class SensitiveDialogSecureFlagTest {

    /** The value Compose writes into the dialog window's FLAG_SECURE on each recomposition. */
    private fun composeApplies(policy: SecureFlagPolicy, hostWindowSecure: Boolean): Boolean =
        Class.forName("androidx.compose.ui.window.SecureFlagPolicy_androidKt")
            .getMethod("shouldApplySecureFlag", SecureFlagPolicy::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, policy, hostWindowSecure) as Boolean

    /** A dialog window's flag, written by the holder and by Compose's recomposition, in that order. */
    private class DialogWindow {
        var secure = false
        val holder = SecureWindowFlags<String> { _, s -> secure = s }
    }

    @Test
    fun `a recomposition over a screen that is not secure leaves the dialog window secure`() {
        val dialog = DialogWindow()
        dialog.holder.acquire("dialog")                                  // SensitiveDialog: SecureWindow(window)
        assertTrue(dialog.secure)
        // The Send screen behind is not secure; the dialog recomposes (a digit typed, a fee updated).
        dialog.secure = composeApplies(sensitiveDialogProperties().securePolicy, hostWindowSecure = false)
        assertEquals(1, dialog.holder.holders("dialog"))
        assertTrue("the dialog window lost FLAG_SECURE while its holder still holds it", dialog.secure)
    }

    @Test
    fun `a recomposition over a secure screen leaves the dialog window secure`() {
        val dialog = DialogWindow()
        dialog.holder.acquire("dialog")
        dialog.secure = composeApplies(sensitiveDialogProperties().securePolicy, hostWindowSecure = true)
        assertTrue(dialog.secure)
    }

    @Test
    fun `the dialog is opened secure before its first frame`() {
        // Compose applies the policy when the dialog is created, before the holder's effect runs.
        assertTrue(composeApplies(sensitiveDialogProperties().securePolicy, hostWindowSecure = false))
    }

    @Test
    fun `the dialog keeps the platform's other defaults`() {
        val p = sensitiveDialogProperties()
        assertTrue(p.dismissOnBackPress)
        assertTrue(p.dismissOnClickOutside)
        assertTrue(p.usePlatformDefaultWidth)
        assertTrue(p.decorFitsSystemWindows)
    }

    @Test
    fun `the dialog's holder and the screen's holder do not release each other`() {
        // The unlock screen holds the Activity window; a PIN dialog over it holds its own window.
        val applied = mutableListOf<Pair<String, Boolean>>()
        val windows = SecureWindowFlags<String> { w, s -> applied += w to s }
        windows.acquire("activity")
        windows.acquire("dialog")
        windows.release("dialog")                                         // the dialog is dismissed
        assertTrue(windows.isSecure("activity"))
        assertFalse(windows.isSecure("dialog"))
        assertEquals(listOf("activity" to true, "dialog" to true, "dialog" to false), applied)
    }
}
