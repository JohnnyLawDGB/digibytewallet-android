package app.aroundtheblock.wallet.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The recovery-phrase screen shows the passphrase where the user can reach it. The words sit in a
 * LazyVerticalGrid, the screen's only scrolling container, so the passphrase card must be an item of
 * that grid. A card placed after the grid in the surrounding Column is laid out below the visible area
 * and cannot be scrolled to, and a backup copied from the screen then omits the passphrase.
 */
class SeedViewPassphraseLayoutTest {

    private val src = File("src/main/java/app/aroundtheblock/wallet/ui/settings/SeedViewScreen.kt").readText()

    @Test
    fun `the passphrase is the grid's first item, before the words`() {
        val grid = src.indexOf("LazyVerticalGrid(")
        val shown = src.indexOf("text = storedPassphrase")
        val words = src.indexOf("itemsIndexed(backup.words)")
        assertTrue("grid not found", grid >= 0)
        assertTrue("passphrase text not found", shown >= 0)
        assertTrue("words not found", words >= 0)
        assertTrue("the passphrase must be inside the grid, before the words", shown in (grid + 1) until words)
        assertTrue("the passphrase item must span the full width", src.indexOf("GridItemSpan(maxLineSpan)") in grid until words)
    }
}
