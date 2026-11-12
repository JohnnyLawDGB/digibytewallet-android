package app.aroundtheblock.wallet.core.settings

import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.wipeTestContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The display currency moved out of the SPV sync blob (`dgb_sync_data<net>`), which every wipe
 * path clears whole, into Show prices' file (owner decision, 2026-11-12). These pin the move: an
 * old value is carried over once, a newer choice is never overwritten by it, and a write that does
 * not land loses nothing.
 */
class DisplayCurrencyPrefsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val prefs = InMemoryPrefsFactory()
    private val ctx by lazy { wipeTestContext(prefs, tmp.newFolder()) }

    private fun old(net: String = "") = prefs.prefsFor(DisplayCurrencyPrefs.LEGACY_PREFS_BASE + net)
    private fun new(net: String = "") = prefs.prefsFor(DisplayCurrencyPrefs.prefsName(net))

    @Test fun `on mainnet it shares Show prices' file, and testnet keeps its own`() {
        assertEquals(PricePrefs.PREFS, DisplayCurrencyPrefs.prefsName(""))
        assertEquals(PricePrefs.PREFS + "_testnet", DisplayCurrencyPrefs.prefsName("_testnet"))
    }

    @Test fun `the migration reads the old file`() {
        old().seed(DisplayCurrencyPrefs.KEY, "EUR")
        old().seed("has_synced", true)

        assertEquals("EUR", DisplayCurrencyPrefs.read(ctx, ""))

        assertEquals("the value is in its new home", "EUR", new().getString(DisplayCurrencyPrefs.KEY, null))
        assertEquals("and durably so", "EUR", new().durable[DisplayCurrencyPrefs.KEY])
        assertFalse("one copy: the old key is removed", old().contains(DisplayCurrencyPrefs.KEY))
        assertEquals("nothing else in the sync blob is touched", true, old().getBoolean("has_synced", false))
    }

    @Test fun `a choice made since the update wins over the old value`() {
        new().seed(DisplayCurrencyPrefs.KEY, "JPY")
        old().seed(DisplayCurrencyPrefs.KEY, "EUR")

        assertEquals("JPY", DisplayCurrencyPrefs.moveFromLegacy(ctx, ""))
        assertEquals("JPY", new().getString(DisplayCurrencyPrefs.KEY, null))
        assertFalse(old().contains(DisplayCurrencyPrefs.KEY))
    }

    @Test fun `a write that does not land keeps the old value`() {
        old().seed(DisplayCurrencyPrefs.KEY, "EUR")
        prefs.refuseWritesTo(DisplayCurrencyPrefs.prefsName(""))

        DisplayCurrencyPrefs.moveFromLegacy(ctx, "")

        assertNull("the refused write is not on disk", new().durable[DisplayCurrencyPrefs.KEY])
        assertEquals("so the old value stays, for the next start to move", "EUR", old().durable[DisplayCurrencyPrefs.KEY])
    }

    @Test fun `each network moves its own value`() {
        old("").seed(DisplayCurrencyPrefs.KEY, "EUR")
        old("_testnet").seed(DisplayCurrencyPrefs.KEY, "BTC")

        DisplayCurrencyPrefs.moveFromLegacy(ctx, "")
        DisplayCurrencyPrefs.moveFromLegacy(ctx, "_testnet")

        assertEquals("EUR", new("").getString(DisplayCurrencyPrefs.KEY, null))
        assertEquals("BTC", new("_testnet").getString(DisplayCurrencyPrefs.KEY, null))
    }

    @Test fun `nothing stored anywhere reads null`() {
        assertNull(DisplayCurrencyPrefs.read(ctx, ""))
    }

    @Test fun `write stores the enum name`() {
        DisplayCurrencyPrefs.write(ctx, "", "GBP")
        assertEquals("GBP", DisplayCurrencyPrefs.read(ctx, ""))
    }
}
