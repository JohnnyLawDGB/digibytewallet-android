package app.aroundtheblock.wallet.ui.wallets

import app.aroundtheblock.wallet.ui.KotlinSourceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DigiStamp signs in through the external browser (DigistampNavigationPolicy OPEN_IN_BROWSER),
 * whose session the app cannot end. Switching wallets therefore says, where the switch is made,
 * that the browser may still be signed in to DigiStamp as the wallet left behind. One place: the
 * switcher sheet. The text's presence in every locale is LocaleResourceParityTest's.
 */
class WalletSwitcherDigistampNoteTest {

    private val main = File("src/main/java/app/aroundtheblock/wallet")

    @Test fun `the switcher sheet shows the DigiStamp browser note`() {
        val sheet = KotlinSourceGate.of(File(main, "ui/wallets/WalletSwitcherSheet.kt").readText())
        assertTrue("the switcher sheet does not show the DigiStamp note", sheet.code.contains("R.string.wallet_switcher_digistamp_note"))
    }

    @Test fun `the note is shown in one place`() {
        val users = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { KotlinSourceGate.of(it.readText()).code.contains("R.string.wallet_switcher_digistamp_note") }
            .map { it.name }.toList()
        assertEquals(listOf("WalletSwitcherSheet.kt"), users)
    }

    @Test fun `the English text names DigiStamp and the browser`() {
        val en = File("src/main/res/values/strings_wallet.xml").readText()
        val line = Regex("""<string name="wallet_switcher_digistamp_note">([^<]*)</string>""").find(en)?.groupValues?.get(1)
        assertTrue("no English wallet_switcher_digistamp_note", line != null)
        assertTrue(line!!, line.contains("DigiStamp") && line.contains("browser"))
    }
}
