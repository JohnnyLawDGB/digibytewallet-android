package io.digibyte.ui.wallet

import io.digibyte.ui.wallet.WalletViewModel.DisplayCurrency
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One currency setting, wherever the user set it.
 *
 * ## The bug
 *
 * There were two. Settings → Display → Fiat Currency wrote `WalletConfig.fiatCurrency` into Room,
 * which was read by nothing except the screen that wrote it. The wallet read `display_currency`
 * from SharedPreferences, set by the hero's own picker. Observed live on the Note 8: Settings
 * said USD while the home screen said BTC, each reporting its own store faithfully and neither
 * one wrong on its own terms.
 *
 * A setting that stores a preference nothing honours is worse than a missing setting. The user
 * has told the wallet what they want and been ignored, with a tick next to their choice.
 *
 * ## The migration
 *
 * Anyone who set a currency in Settings expressed a real intent into a control that appeared to
 * work. When the preference has never been written but Room carries a non-default value, that
 * intent is adopted rather than discarded — once, on first read. Doing anything else would
 * silently reset a choice they believe they already made.
 */
class DisplayCurrencyStoreTest {

    @Test fun `an explicit preference wins`() {
        assertEquals(DisplayCurrency.BTC, DisplayCurrencyStore.resolve(pref = "BTC", legacy = "EUR"))
    }

    /** The whole point of the migration: a choice made in the dead picker is honoured. */
    @Test fun `a legacy settings choice is adopted when no preference exists`() {
        assertEquals(DisplayCurrency.EUR, DisplayCurrencyStore.resolve(pref = null, legacy = "EUR"))
    }

    @Test fun `the legacy default is not treated as a choice`() {
        // "USD" is what WalletConfig starts as, so it says nothing about intent — but it also
        // resolves to USD, which is the same answer. What matters is that it never overrides a
        // real preference; that is covered by `an explicit preference wins`.
        assertEquals(DisplayCurrency.USD, DisplayCurrencyStore.resolve(pref = null, legacy = "USD"))
    }

    @Test fun `nothing stored anywhere is dollars`() {
        assertEquals(DisplayCurrency.USD, DisplayCurrencyStore.resolve(pref = null, legacy = null))
        assertEquals(DisplayCurrency.USD, DisplayCurrencyStore.resolve(pref = "", legacy = ""))
    }

    /**
     * A corrupt or obsolete stored value must not crash: this resolves during ViewModel
     * construction, before any UI exists to show an error in.
     */
    @Test fun `an unrecognised value falls back rather than throwing`() {
        assertEquals(DisplayCurrency.USD, DisplayCurrencyStore.resolve(pref = "DOGE", legacy = null))
        assertEquals(DisplayCurrency.USD, DisplayCurrencyStore.resolve(pref = "!!", legacy = "???"))
    }

    /** A bad preference should still let a good legacy value through. */
    @Test fun `a corrupt preference does not discard a valid legacy choice`() {
        assertEquals(DisplayCurrency.JPY, DisplayCurrencyStore.resolve(pref = "NOPE", legacy = "JPY"))
    }

    @Test fun `matching is case-insensitive and trimmed`() {
        assertEquals(DisplayCurrency.EUR, DisplayCurrencyStore.resolve(pref = "  eur ", legacy = null))
        assertEquals(DisplayCurrency.GBP, DisplayCurrencyStore.resolve(pref = null, legacy = "gbp"))
    }

    /**
     * The Settings list offered CHF, SEK and NOK while the wallet's list did not — so picking one
     * stored a currency the price fetch never carried, and it would have rendered "--" forever.
     * They are in the one list now, which is what makes "follow whatever they set" true rather
     * than true-for-most-values.
     */
    @Test fun `the currencies the old settings list offered all resolve`() {
        listOf("CHF", "SEK", "NOK").forEach {
            assertEquals("$it must resolve", it, DisplayCurrencyStore.resolve(pref = it, legacy = null).name)
        }
    }

    /** Every currency the picker can show must have a rate to show, or it renders a dash. */
    @Test fun `every offered currency is one the price fetch requests`() {
        val fetched = io.digibyte.core.SUPPORTED_PRICE_CURRENCIES.toSet()
        val missing = DisplayCurrency.entries.filterNot { it.code in fetched }
        assertEquals("offered but never fetched: ${missing.map { it.name }}", emptyList<Any>(), missing)
    }
}
