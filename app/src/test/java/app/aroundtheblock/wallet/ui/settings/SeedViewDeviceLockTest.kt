package app.aroundtheblock.wallet.ui.settings

import app.aroundtheblock.wallet.core.security.KeystoreKeyInvalidatedException
import app.aroundtheblock.wallet.core.security.KeystoreUserAuthRequiredException
import app.aroundtheblock.wallet.ui.KotlinSourceGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SEED-VIEW-AUTH: a seed or passphrase read refused because the auth-bound key is outside its
 * device-unlock window asks for the device lock and reads again exactly once. A cancelled or failed
 * prompt leaves a state from which "Try again" prompts again; a permanently invalidated key says to
 * restore from the phrase and never prompts. The words are shown only when both reads succeeded.
 *
 * Seen on a Note 8 (Android 9, no strong biometric): the PIN alone opened the screen, the key was
 * outside its window, and the screen said "Passphrase could not be read … Try again" — and Try again
 * read again without ever asking for the device lock, so it could never succeed.
 */
class SeedViewDeviceLockTest {

    private val words = "abandon ability able about above absent absorb abstract absurd abuse access accident"
    private fun lapsed(): Nothing = throw KeystoreUserAuthRequiredException(RuntimeException("auth"))
    private fun invalidated(): Nothing = throw KeystoreKeyInvalidatedException(RuntimeException("gone"))

    /** A Keystore whose window is open only after [opensAfterPrompts] confirmed prompts. */
    private inner class Device(
        private val hasPassphrase: Boolean,
        private val opensAfterPrompts: Int = 1,
        private val confirms: Boolean = true,
        private val invalid: Boolean = false,
    ) {
        var prompts = 0
        var reads = 0
        private var confirmed = 0
        private fun open() = confirmed >= opensAfterPrompts
        fun passphrase(): ByteArray? {
            if (invalid) invalidated()
            if (!hasPassphrase) return null
            if (!open()) lapsed()
            return "correct horse".toByteArray()
        }
        fun mnemonic(): ByteArray? {
            if (invalid) invalidated()
            if (!open()) lapsed()
            return words.toByteArray()
        }
        fun read(): SeedBackupView { reads++; return SeedBackupView.read(::passphrase, ::mnemonic) }
        suspend fun prompt(): Boolean { prompts++; if (confirms) confirmed++; return confirms }
        fun view() = runBlocking { SeedBackupView.readConfirmingDeviceLock(read = { read() }, confirmDeviceLock = { prompt() }) }
    }

    @Test fun `a lapsed window on either read asks for the device lock, never shows the words`() {
        val onPassphrase = SeedBackupView.read({ lapsed() }, { words.toByteArray() })
        assertEquals("a lapsed window on the passphrase is not a device-lock request", SeedBackupView.DeviceLockNeeded, onPassphrase)
        // A wallet with no passphrase reads none without the key; the words then need it.
        val onWords = SeedBackupView.read({ null }, { lapsed() })
        assertEquals("a lapsed window on the words was shown as words ($onWords)", SeedBackupView.DeviceLockNeeded, onWords)
    }

    @Test fun `an invalidated key says restore from the phrase, on either read`() {
        assertEquals(SeedBackupView.KeyInvalidated, SeedBackupView.read({ invalidated() }, { words.toByteArray() }))
        assertEquals(SeedBackupView.KeyInvalidated, SeedBackupView.read({ null }, { invalidated() }))
    }

    @Test fun `a confirmed prompt reads again once and shows the whole backup`() {
        for (hasPassphrase in listOf(true, false)) {
            val device = Device(hasPassphrase)
            val view = device.view()
            assertTrue("hasPassphrase=$hasPassphrase: not shown after a confirmed prompt ($view)", view is SeedBackupView.Shown)
            view as SeedBackupView.Shown
            assertEquals(words.split(" "), view.words)
            assertEquals(if (hasPassphrase) "correct horse" else null, view.passphrase)
            assertEquals("hasPassphrase=$hasPassphrase: prompts", 1, device.prompts)
            assertEquals("hasPassphrase=$hasPassphrase: reads", 2, device.reads)
        }
    }

    @Test fun `a cancelled prompt leaves a state from which Try again prompts again`() {
        val device = Device(hasPassphrase = true, confirms = false)
        repeat(3) { attempt ->
            assertEquals("attempt ${attempt + 1}", SeedBackupView.DeviceLockNeeded, device.view())
            assertEquals("attempt ${attempt + 1}: Try again did not prompt again", attempt + 1, device.prompts)
        }
        assertEquals("a cancelled prompt was followed by another read", 3, device.reads)
    }

    @Test fun `a key still refused after a confirmed prompt retries exactly once`() {
        val device = Device(hasPassphrase = true, opensAfterPrompts = 2)
        assertEquals(SeedBackupView.DeviceLockNeeded, device.view())
        assertEquals(1, device.prompts)
        assertEquals(2, device.reads)
        // Try again: a fresh prompt, and this time the window opens.
        assertTrue(device.view() is SeedBackupView.Shown)
        assertEquals(2, device.prompts)
    }

    @Test fun `an invalidated key never prompts`() {
        val device = Device(hasPassphrase = true, invalid = true)
        assertEquals(SeedBackupView.KeyInvalidated, device.view())
        assertEquals(0, device.prompts)
        assertEquals(1, device.reads)
    }

    // ---- source gate: the screen reads through the policy and prompts with the unlock path's prompt ----

    private val screen = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/ui/settings/SeedViewScreen.kt").readText())

    @Test fun `the screen prompts with the shared device-lock prompt and offers each state's message`() {
        assertEquals("the screen does not read through readConfirmingDeviceLock", 1, screen.calls("SeedBackupView.readConfirmingDeviceLock").size)
        assertEquals("the screen does not use the unlock path's device-lock prompt", 1, screen.calls("biometricAuth.authenticateDeviceCredential").size)
        for (key in listOf("seedview_device_lock_title", "seedview_device_lock_body", "unlock_device_lock_removed")) {
            assertTrue("the screen does not show R.string.$key", screen.code.contains("R.string.$key"))
        }
        assertTrue("scanner is blind: no screen code", screen.code.isNotBlank())
    }
}
