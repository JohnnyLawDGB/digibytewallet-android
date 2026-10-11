package app.aroundtheblock.wallet.ui.onboarding

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

    private val onboarding = File("src/main/java/app/aroundtheblock/wallet/ui/onboarding")
    private val recovery = File("src/main/java/app/aroundtheblock/wallet/ui/recovery")
    private val components = File("src/main/java/app/aroundtheblock/wallet/ui/components")

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
    fun `SecureWindow helper holds FLAG_SECURE through a counted holder, never a per-screen clear`() {
        val helper = source(File(components, "SecureWindow.kt"))
        assertTrue(helper.contains("fun SecureWindow("))
        assertTrue(helper.contains("DisposableEffect"))
        assertTrue(helper.contains("WindowManager.LayoutParams.FLAG_SECURE"))
        assertTrue(helper.contains("clearFlags(WindowManager.LayoutParams.FLAG_SECURE)"))
        // The composable must route through SecureWindowFlag (see SecureWindowFlagTest): a direct
        // clearFlags in onDispose is the secure->secure navigation regression.
        assertTrue(helper.contains("onDispose { processFlag.release() }"))
        assertFalse(helper.contains("onDispose {\n            window?.clearFlags"))
    }

    @Test
    fun `phrase and passphrase entry screens apply SecureWindow`() {
        for ((name, src) in listOf(
            "MnemonicInputScreen" to mnemonicInput,
            "PassphraseScreen" to passphraseScreen,
            "RecoverFundsScreen" to recoverFunds,
        )) {
            assertTrue("$name does not apply SecureWindow()", src.contains("SecureWindow()"))
            assertTrue("$name does not import SecureWindow", src.contains("import app.aroundtheblock.wallet.ui.components.SecureWindow"))
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

    @Test
    fun `the restore passphrase is one masked Password-type field, outside the word screen`() {
        val src = source(File(onboarding, "RestorePassphraseField.kt"))
        assertEquals("one field: at restore the passphrase is copied, not invented", 1, count(src, "OutlinedTextField("))
        assertTrue(src.contains("KeyboardType.Password"))
        assertTrue(src.contains("autoCorrect = false"))
        assertTrue("the passphrase is masked", src.contains("PasswordVisualTransformation()"))
        assertFalse(src.contains("KeyboardType.Text"))
        // Hosted by the word screen, which itself stays unmasked (see the word-field test).
        assertTrue(mnemonicInput.contains("RestorePassphraseField("))
    }
}

/**
 * The other-formats scan sends the addresses a phrase derives to the reconcile backend. A restore
 * must never do that on its own: the user starts it from a button that says what it sends
 * (owner decision 2026-10-05; the privacy policy describes it that way).
 */
class RestoreScanIsOptInTest {

    private val screen: String = File("src/main/java/app/aroundtheblock/wallet/ui/onboarding/RecoveryScanScreen.kt")
        .readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")

    @Test
    fun `nothing on the restore screen starts the scan by itself`() {
        assertFalse("an effect could start the scan without a tap", screen.contains("LaunchedEffect"))
        assertFalse("a side effect could start the scan without a tap", screen.contains("SideEffect"))
    }

    @Test
    fun `the scan starts only from the opt-in button and its retry`() {
        val calls = Regex("""runRecoveryScan\(""").findAll(screen).count()
        assertEquals("expected exactly the opt-in and the retry", 2, calls)
        assertTrue(screen.contains("OtherFormatsOffer(onScan = { viewModel.runRecoveryScan() })"))
        assertTrue(screen.contains("FailedBody(s.reason) { viewModel.runRecoveryScan() }"))
    }

    /**
     * The disclosure lives in resources now, so the check reads the English source string the
     * opt-in card renders, and pins the card to that key so the two cannot drift apart.
     */
    @Test
    fun `the opt-in says where the addresses go`() {
        assertTrue(screen.contains("stringResource(R.string.restore_other_body)"))
        val english = File("src/main/res/values/strings_wallet.xml").readText()
        val body = Regex("""<string name="restore_other_body">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(english)?.groupValues?.get(1)
        assertTrue("restore_other_body missing from values/strings_wallet.xml", body != null)
        assertTrue(body!!.contains("api.digiscope.me"))
        assertTrue(body.contains("sends the"))
        // Every translation must still name the destination.
        val unnamed = File("src/main/res").listFiles { f -> f.name.startsWith("values-") }.orEmpty()
            .map { File(it, "strings_wallet.xml") }
            .filter { it.isFile }
            .filterNot { f ->
                Regex("""<string name="restore_other_body">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .find(f.readText())?.groupValues?.get(1)?.contains("api.digiscope.me") == true
            }
            .map { it.parentFile.name }
        assertTrue("restore_other_body does not name api.digiscope.me in: $unnamed", unnamed.isEmpty())
    }
}
