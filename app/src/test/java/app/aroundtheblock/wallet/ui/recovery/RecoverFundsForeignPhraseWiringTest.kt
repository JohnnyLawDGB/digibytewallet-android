package app.aroundtheblock.wallet.ui.recovery

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level WIRING gate for the Recover funds screen's own copy of an entered phrase.
 *
 * The ViewModel's copy is behaviour-tested in [RecoverFundsForeignPhraseTest]. The screen keeps
 * the phrase and the passphrase in Compose state for the entry fields, which a JVM test cannot
 * drive, so where that state goes is pinned here: it is let go when the sweep takes the phrase
 * (after which the ViewModel holds the one copy the sweep needs, and drops it when the sweep ends),
 * and it never reaches saved state, the one way screen state outlives the process.
 */
class RecoverFundsForeignPhraseWiringTest {

    private fun gate(name: String): KotlinSourceGate {
        val file = File("src/main/java/app/aroundtheblock/wallet/ui/recovery/$name")
        assertTrue("$name is missing — this gate is watching a file that moved", file.exists())
        return KotlinSourceGate.of(file.readText())
    }

    private val screen by lazy { gate("RecoverFundsScreen.kt") }
    private val viewModel by lazy { gate("RecoverFundsViewModel.kt") }

    private fun clears(field: String) = Regex("(?<![\\w.])$field\\s*=\\s*\"\"")

    @Test
    fun `the screen lets go of its copy of the phrase when the sweep takes it`() {
        val sweep = screen.calls("vm.sweepForeign").singleOrNull()
        assertNotNull("the screen no longer starts the sweep in one place", sweep)
        val block = screen.enclosingBlock(sweep!!.range.first)
        assertNotNull("vm.sweepForeign() stands in no block", block)
        val before = screen.code.substring(block!!.first, sweep.range.first)

        assertTrue("the phrase field is not cleared where the sweep takes the phrase",
            clears("phrase").containsMatchIn(before))
        assertTrue("the passphrase field is not cleared where the sweep takes the phrase",
            clears("passphrase").containsMatchIn(before))
    }

    /**
     * GUARD — no red can exist: the shipped screen and ViewModel already keep the phrase out of
     * saved state. Pinned because saved state (a saveable remember, a SavedStateHandle, a
     * preference or a file) is how screen state survives process death.
     */
    @Test
    fun `GUARD the phrase never reaches saved state, so process death leaves no copy`() {
        assertTrue("the phrase field is no longer a plain remember",
            Regex("""var\s+phrase\s+by\s+remember\s*\{""").containsMatchIn(screen.code))
        assertTrue("the passphrase field is no longer a plain remember",
            Regex("""var\s+passphrase\s+by\s+remember\s*\{""").containsMatchIn(screen.code))
        for (word in listOf("SavedStateHandle", "SharedPreferences", "getSharedPreferences", "DataStore", "openFileOutput", "File")) {
            assertFalse("RecoverFundsViewModel reaches $word",
                Regex("(?<!\\w)$word(?!\\w)").containsMatchIn(viewModel.code))
        }
    }
}
