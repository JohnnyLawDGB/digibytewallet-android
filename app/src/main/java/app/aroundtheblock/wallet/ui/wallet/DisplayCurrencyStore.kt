package app.aroundtheblock.wallet.ui.wallet

import android.content.Context
import app.aroundtheblock.wallet.ui.wallet.WalletViewModel.DisplayCurrency
import java.util.Locale

/**
 * The one place the display currency lives.
 *
 * ## Why this exists
 *
 * There were two stores. Settings → Display → Fiat Currency wrote `WalletConfig.fiatCurrency`
 * into Room, read by nothing except the screen that wrote it. The wallet read `display_currency`
 * from SharedPreferences, set by the hero's picker. On a real device Settings said USD while the
 * home screen said BTC — each faithful to its own store, and the user ignored by one of them.
 *
 * A control that stores a preference nothing honours is worse than no control: it puts a tick
 * next to a choice and then disregards it.
 *
 * ## Which store won, and why
 *
 * SharedPreferences. It is what the wallet already read, it is available before the database is
 * open (the hero renders during startup), and the enum name it stores is pinned by a ProGuard
 * keep rule — so the value cannot be silently renamed out from under stored data. The Room field
 * is now read once, for migration, and never written.
 */
object DisplayCurrencyStore {

    /**
     * Where it is stored: [app.aroundtheblock.wallet.core.settings.DisplayCurrencyPrefs] (Show
     * prices' file, network-suffixed). It moved there from `dgb_sync_data` so a wipe, a restore and
     * the crash-loop breaker keep it (owner decision, 2026-11-12); that object moves an old value.
     *
     * The suffix matters: testnet and mainnet keep separate choices, and dropping it would make a
     * testnet currency choice leak into mainnet.
     */
    private fun suffix(context: Context) = app.aroundtheblock.wallet.core.networkSuffix(context)

    /**
     * Resolve the effective currency from both stores.
     *
     * @param pref   the SharedPreferences value, or null when never written.
     * @param legacy `WalletConfig.fiatCurrency`, from the picker that never took effect.
     *
     * A stored preference always wins. Failing that, a legacy value is adopted — someone who
     * chose a currency in Settings expressed a real intent into a control that looked like it
     * worked, and discarding it would reset a choice they believe they already made. Anything
     * unrecognised resolves to USD rather than throwing: this runs during ViewModel construction,
     * before there is any UI to report an error in.
     */
    fun resolve(pref: String?, legacy: String?): DisplayCurrency =
        match(pref) ?: match(legacy) ?: DisplayCurrency.USD

    private fun match(value: String?): DisplayCurrency? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        val upper = v.uppercase(Locale.US)
        // Matches on enum name ("EUR") and on code ("eur"); Room held ISO codes, the preference
        // holds enum names, and the two happen to coincide — but relying on that by accident is
        // how the next currency with a mismatched code breaks silently.
        return DisplayCurrency.entries.firstOrNull {
            it.name == upper || it.code.uppercase(Locale.US) == upper
        }
    }

    /** Read the stored preference, without the legacy fallback. */
    fun storedPref(context: Context): String? = runCatching {
        app.aroundtheblock.wallet.core.settings.DisplayCurrencyPrefs.read(context, suffix(context))
    }.getOrNull()

    /** Persist a choice. Both pickers call this, so both screens agree afterwards. */
    fun save(context: Context, currency: DisplayCurrency) {
        runCatching {
            app.aroundtheblock.wallet.core.settings.DisplayCurrencyPrefs.write(context, suffix(context), currency.name)
        }
    }
}
