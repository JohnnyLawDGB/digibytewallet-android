package io.digibyte.ui.onboarding

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every screen where the user TYPES a recovery phrase or passphrase must be as hardened as the
 * screens that DISPLAY one.
 *
 * ## The gap this closes
 *
 * The seed-display, seed-verify and seed-view screens set FLAG_SECURE, so a screenshot, a screen
 * recording or a casting session cannot capture the words. The entry screens did not — the same
 * twelve words, typed one at a time into plain text fields, were capturable, and a plain-text IME
 * was free to add them to its personal dictionary and sync that dictionary to a cloud account.
 * A BIP39 passphrase typed into a field with no keyboard hint got the same treatment.
 *
 * ## Why this is a source-level gate
 *
 * Neither property is observable from a JVM unit test: FLAG_SECURE lives on an Activity window,
 * and the IME learning hint (`KeyboardType.Password` → `TYPE_TEXT_VARIATION_PASSWORD`) is only
 * handed to the platform when a real text field connects to a real input method. Robolectric
 * could reach the window flag but not the IME contract, and the project runs no Compose UI
 * tests in CI. Reading the source is the one check that fails the build when a future refactor
 * drops the flag or swaps a field back to `KeyboardType.Text`, which is the failure this exists
 * to catch.
 */
class SensitiveInputHardeningTest {

    private val onboarding = File("src/main/java/io/digibyte/ui/onboarding")
    private val recovery = File("src/main/java/io/digibyte/ui/recovery")
    private val components = File("src/main/java/io/digibyte/ui/components")

    private fun source(file: File): String {
        assertTrue("missing ${file.path}", file.exists())
        return file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")
    }

    private val mnemonicInput get() = source(File(onboarding, "MnemonicInputScreen.kt"))
    private val passphraseSection get() = source(File(onboarding, "PassphraseSection.kt"))
    private val passphraseScreen get() = source(File(onboarding, "PassphraseScreen.kt"))
    private val recoverFunds get() = source(File(recovery, "RecoverFundsScreen.kt"))

    /** The PhraseEntry composable only — the sweep-destination field elsewhere on that screen is not a secret. */
    private val phraseEntry: String
        get() {
            val src = recoverFunds
            val start = src.indexOf("private fun PhraseEntry(")
            assertTrue("PhraseEntry composable not found", start >= 0)
            val end = src.indexOf("@Composable", start).let { if (it < 0) src.length else it }
            return src.substring(start, end)
        }

    private fun count(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

    @Test
    fun `SecureWindow helper sets FLAG_SECURE for its lifetime and clears it on dispose`() {
        val helper = source(File(components, "SecureWindow.kt"))
        assertTrue(helper.contains("fun SecureWindow("))
        assertTrue(helper.contains("DisposableEffect"))
        assertTrue(helper.contains("WindowManager.LayoutParams.FLAG_SECURE"))
        assertTrue(helper.contains("onDispose"))
        assertTrue(helper.contains("clearFlags(WindowManager.LayoutParams.FLAG_SECURE)"))
    }

    @Test
    fun `phrase and passphrase entry screens apply SecureWindow`() {
        for ((name, src) in listOf(
            "MnemonicInputScreen" to mnemonicInput,
            "PassphraseScreen" to passphraseScreen,
            "RecoverFundsScreen" to recoverFunds,
        )) {
            assertTrue("$name does not apply SecureWindow()", src.contains("SecureWindow()"))
            assertTrue("$name does not import SecureWindow", src.contains("import io.digibyte.ui.components.SecureWindow"))
        }
    }

    @Test
    fun `every mnemonic word field is a Password-type IME field with password semantics`() {
        val src = mnemonicInput
        val fields = count(src, "OutlinedTextField(")
        assertTrue("expected a word field", fields >= 1)
        assertEquals("every field must declare KeyboardType.Password", fields, count(src, "KeyboardType.Password"))
        assertFalse("a word field still uses the learnable Text IME", src.contains("KeyboardType.Text"))
        assertTrue("autoCorrect must stay off", src.contains("autoCorrect = false"))
        assertTrue("word field must carry password() semantics", src.contains("password()"))
        // The words must remain readable: Password is only the IME hint, never a mask.
        assertFalse("mnemonic words must stay visible", src.contains("PasswordVisualTransformation"))
    }

    @Test
    fun `both passphrase fields on the onboarding passphrase screen are Password-type IME fields`() {
        val src = passphraseSection
        val fields = count(src, "OutlinedTextField(")
        assertEquals(2, fields)
        assertEquals(fields, count(src, "KeyboardType.Password"))
        assertEquals(fields, count(src, "autoCorrect = false"))
        assertFalse(src.contains("KeyboardType.Text"))
    }

    @Test
    fun `recover-funds phrase and passphrase fields are Password-type IME fields`() {
        val src = phraseEntry
        val fields = count(src, "OutlinedTextField(")
        assertEquals(2, fields)
        assertEquals(fields, count(src, "KeyboardType.Password"))
        assertEquals(fields, count(src, "autoCorrect = false"))
        assertFalse(src.contains("KeyboardType.Text"))
        assertTrue("phrase field must carry password() semantics", src.contains("password()"))
    }
}
