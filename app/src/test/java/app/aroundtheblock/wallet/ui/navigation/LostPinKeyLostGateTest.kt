package app.aroundtheblock.wallet.ui.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Integration review item 5. Invariant: on the lost-PIN path (a wallet stored, no PIN), a wallet key
 * that is gone (KeystoreKeyInvalidatedException: the device lock was removed) shows the restore
 * message, unlock_device_lock_removed, and never PinSetupScreen, which with the record still stored
 * could only fail with pin_err_wallet.
 */
class LostPinKeyLostGateTest {
    private val src = File("src/main/java/app/aroundtheblock/wallet/ui/navigation/AppNavigation.kt").readText()
    private val pinSetup = src.substringAfter("composable(\"pin_setup\")").substringBefore("composable(PIN_STORE_UNAVAILABLE_ROUTE)")

    @Test fun `an invalidated key on the lost-PIN path sets the key-lost state`() {
        val catchBlock = pinSetup.substringAfter("catch (e: app.aroundtheblock.wallet.core.security.KeystoreKeyInvalidatedException)")
            .substringBefore("lostPinRestorePending.value = false")
        assertTrue("the invalidated-key catch must route to the restore message", catchBlock.contains("keyLost = true"))
    }

    @Test fun `the key-lost state shows the restore message before PinSetupScreen can be reached`() {
        val keyLost = pinSetup.indexOf("if (keyLost)")
        assertTrue("no key-lost branch", keyLost >= 0)
        val branch = pinSetup.substring(keyLost).substringBefore("} else if (restoring)")
        assertTrue("the key-lost branch must show unlock_device_lock_removed", branch.contains("R.string.unlock_device_lock_removed"))
        assertTrue("the key-lost branch must not show PinSetupScreen", !branch.contains("PinSetupScreen("))
        assertTrue("PinSetupScreen comes after the key-lost branch", pinSetup.indexOf("PinSetupScreen(") > keyLost)
    }
}
