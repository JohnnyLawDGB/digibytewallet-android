package app.aroundtheblock.wallet.ui.settings

import android.util.Log
import app.aroundtheblock.wallet.core.security.KeystoreUserAuthRequiredException
import app.aroundtheblock.wallet.ui.KotlinSourceGate
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The recovery-phrase screen shows the whole backup or says why it cannot.
 *
 * A wallet with a stored passphrase is restored by its words AND its passphrase. When the passphrase
 * cannot be read — the record cannot say whether there is one, or the Keystore could not answer just
 * now — the screen must not show the words as though they were the whole backup: a user writes them
 * down and holds half a backup believing it whole. It says the passphrase could not be read and asks to
 * try again, and the words are not even read.
 */
class SeedBackupViewTest {

    @Before fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After fun restoreLog() = unmockkStatic(Log::class)

    private val words = "abandon ability able about above absent absorb abstract absurd abuse access accident"

    private class Reads(private val passphrase: () -> ByteArray?, words: String) {
        var wordsRead = 0
        val wordBytes = words.toByteArray()
        fun passphrase() = passphrase.invoke()
        fun mnemonic(): ByteArray? { wordsRead++; return wordBytes.copyOf() }
    }

    @Test fun `a passphrase that cannot be read is never shown as none`() {
        val unreadable = listOf<Pair<String, () -> ByteArray?>>(
            "a record that cannot say whether it has one" to { throw IllegalStateException("wallet record incomplete") },
            "a Keystore that could not answer just now" to { throw java.security.ProviderException("keystore busy") },
            "a lapsed device-unlock window" to { throw KeystoreUserAuthRequiredException(RuntimeException("auth")) },
        )
        for ((case, read) in unreadable) {
            val reads = Reads(read, words)
            val view = SeedBackupView.read(reads::passphrase, reads::mnemonic)
            assertEquals("$case: the words were presented as the whole backup ($view)", SeedBackupView.PassphraseUnreadable, view)
            assertEquals("$case: the words were read although they cannot be shown as the whole backup", 0, reads.wordsRead)
        }
    }

    // GUARD: a readable passphrase, or none, is shown as it was.
    @Test fun `the whole backup is shown when the passphrase reads`() {
        val pass = "correct horse".toByteArray()
        val withPass = SeedBackupView.read({ pass }, { words.toByteArray() })
        assertTrue(withPass is SeedBackupView.Shown)
        withPass as SeedBackupView.Shown
        assertEquals(words.split(" "), withPass.words)
        assertEquals("correct horse", withPass.passphrase)
        assertTrue("the passphrase bytes were not zeroed once read", pass.all { it == 0.toByte() })

        val without = SeedBackupView.read({ null }, { words.toByteArray() }) as SeedBackupView.Shown
        assertEquals(null, without.passphrase)
        assertEquals(12, without.words.size)
    }

    @Test fun `the recovery-phrase screen reads through SeedBackupView and offers a retry when it cannot`() {
        val screen = KotlinSourceGate.of(File("src/main/java/app/aroundtheblock/wallet/ui/settings/SeedViewScreen.kt").readText())
        assertEquals("the screen does not read what it shows through SeedBackupView.read", 1, screen.calls("SeedBackupView.read").size)
        assertTrue("the screen still reads the passphrase itself", screen.calls("walletManager.decryptStoredPassphrase").isEmpty())
        for (key in listOf("seedview_pass_unreadable_title", "seedview_pass_unreadable_body", "seedview_pass_retry")) {
            assertTrue("the screen does not show R.string.$key", screen.code.contains("R.string.$key"))
        }
        assertFalse("scanner is blind: no screen code", screen.code.isBlank())
    }
}
