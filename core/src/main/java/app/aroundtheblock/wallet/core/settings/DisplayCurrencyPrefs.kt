package app.aroundtheblock.wallet.core.settings

import android.content.Context

/**
 * Where the display currency is stored: one key, `display_currency`, holding the
 * `WalletViewModel.DisplayCurrency` enum name (a ProGuard keep rule pins those names).
 *
 * ## It survives a wipe (owner decision, 2026-11-12)
 *
 * The currency is a device setting, like Settings → Display → Show prices ([PricePrefs]), the
 * own node and the balance mask. A wipe, a create or a restore after a wipe, and the startup
 * crash-loop breaker all keep it. iOS made the same change on the same day, so the two apps agree.
 *
 * It used to live in `dgb_sync_data<net>`, the SPV sync blob. Every one of those paths clears that
 * file whole, so the currency went back to USD after a wipe, a restore or a sync-state recovery.
 * It now lives in [PricePrefs]' file, network-suffixed as before: testnet and mainnet keep separate
 * choices, and on mainnet the file is `dgb_price` itself. No wipe path clears that file.
 *
 * ## The move from the old file
 *
 * [read] moves a value it finds only in the old file. Each path that clears the old file calls
 * [moveFromLegacy] first, because on the first start after an update the crash-loop breaker can
 * run before anything has read the currency. The Room legacy value (`WalletConfig.fiatCurrency`)
 * stays where it was: the wallet screen adopts it once when neither file has a value.
 */
object DisplayCurrencyPrefs {
    const val KEY = "display_currency"

    /** The SPV sync blob the currency used to share. Every wipe path clears it whole. */
    const val LEGACY_PREFS_BASE = "dgb_sync_data"

    /** [PricePrefs.PREFS] plus the network suffix ("" on mainnet, so the same file as Show prices). */
    fun prefsName(networkSuffix: String): String = PricePrefs.PREFS + networkSuffix

    /** The stored enum name, or null when none is stored. Moves a value found only in the old file. */
    fun read(context: Context, networkSuffix: String): String? = runCatching {
        context.getSharedPreferences(prefsName(networkSuffix), Context.MODE_PRIVATE).getString(KEY, null)
    }.getOrNull() ?: moveFromLegacy(context, networkSuffix)

    /** Persist a choice. commit(), so a wipe that follows at once cannot overtake it. */
    fun write(context: Context, networkSuffix: String, enumName: String): Boolean = runCatching {
        context.getSharedPreferences(prefsName(networkSuffix), Context.MODE_PRIVATE)
            .edit().putString(KEY, enumName).commit()
    }.getOrDefault(false)

    /**
     * Move the value out of the old file, if it is there. The new file keeps a value it already has
     * (a choice made since the update wins), and the old key is removed only once the new file holds
     * a value, so a failed write loses nothing. Never throws: this runs at start and before a wipe.
     *
     * @return the value the new file holds afterwards, or null.
     */
    fun moveFromLegacy(context: Context, networkSuffix: String): String? = runCatching {
        val legacy = context.getSharedPreferences(LEGACY_PREFS_BASE + networkSuffix, Context.MODE_PRIVATE)
        val old = legacy.getString(KEY, null)
        val current = context.getSharedPreferences(prefsName(networkSuffix), Context.MODE_PRIVATE)
            .getString(KEY, null)
        if (old == null) return@runCatching current
        val held = current ?: old.takeIf { write(context, networkSuffix, it) }
        if (held != null) legacy.edit().remove(KEY).commit()
        held
    }.getOrNull()
}
