package io.digibyte.ui.onboarding

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recovery phrase and the passphrase must never be offered on the same screen.
 *
 * ## Why this is a gate and not a preference
 *
 * A BIP39 passphrase has no cryptographic defence against being stored beside the seed. In one
 * envelope they are one secret again, and the passphrase contributes exactly nothing. The whole
 * threat model is behavioural — which means the UI is not a presentation detail here, it IS the
 * control.
 *
 * The wallet used to render the word grid and the passphrase input on one scrolling page, with
 * the warning "keep it SEPARATE from your recovery phrase" printed directly beneath the words it
 * was telling you to separate them from. People write down what is in front of them. The layout
 * invited exactly the behaviour the text forbade, and the text loses that argument every time.
 *
 * Worse, at that point the seed had not been written down yet — verification comes afterwards —
 * so both landed inside a single undifferentiated "set up my backup" ritual, and a single ritual
 * produces a single artifact.
 *
 * The flow is now: display the words, prove they were written, THEN ask about a passphrase. Two
 * screens, two separate acts of recording, separation by construction rather than by
 * instruction.
 *
 * This test exists because that separation is invisible in the code — nothing stops a future
 * refactor from folding the section back into the seed screen "to save a step", and nothing
 * would fail if it did.
 */
class SeedAndPassphraseSeparationTest {

    private val onboarding = File("src/main/java/io/digibyte/ui/onboarding")

    /**
     * Source with comments removed.
     *
     * The first version of this gate matched raw text, and immediately failed on the word "words"
     * appearing in PassphraseScreen's own explanation of why it does not show them. A gate that
     * cannot tell an explanation from an instruction produces noise, and a noisy gate gets its
     * assertions loosened until it stops meaning anything.
     */
    private fun source(name: String): String {
        val f = File(onboarding, name)
        assertTrue("$name is missing — this gate is watching a file that moved", f.exists())
        return f.readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .filterNot { it.trimStart().startsWith("/*") }
            .joinToString("\n")
    }

    @Test fun `the seed display screen does not host the passphrase input`() {
        val seedScreen = source("SeedDisplayScreen.kt")
        assertFalse(
            "SeedDisplayScreen renders PassphraseSection again — the recovery words and the " +
                "passphrase are back on one screen, which is the arrangement the passphrase " +
                "warning tells the user not to reproduce on paper",
            seedScreen.contains("PassphraseSection"),
        )
    }

    /** The seed screen must also not quietly grow its own passphrase field instead. */
    @Test fun `the seed display screen collects no passphrase of its own`() {
        val seedScreen = source("SeedDisplayScreen.kt")
        listOf("setPassphrase", "passphraseEntryReady", "PasswordVisualTransformation").forEach {
            assertFalse("SeedDisplayScreen references $it", seedScreen.contains(it))
        }
    }

    /** And the passphrase screen must not render the words alongside its input. */
    @Test fun `the passphrase screen does not display the recovery words`() {
        val passScreen = source("PassphraseScreen.kt")
        // Identifiers, not prose: the ways the recovery words could actually reach this screen.
        listOf("SeedWordGrid", "mnemonic", "uiState.seed", "getSeed").forEach {
            assertFalse(
                "PassphraseScreen references $it — it must not show the recovery phrase",
                passScreen.contains(it),
            )
        }
    }

    /** The passphrase must still actually be collected somewhere, or this "fix" removed it. */
    @Test fun `the passphrase is still collected, on its own screen`() {
        val passScreen = source("PassphraseScreen.kt")
        assertTrue("PassphraseScreen must host the input", passScreen.contains("PassphraseSection"))
        assertTrue("PassphraseScreen must commit the value", passScreen.contains("setPassphrase"))
    }
}
