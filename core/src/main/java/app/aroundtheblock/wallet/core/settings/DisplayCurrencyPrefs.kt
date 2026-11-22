package app.aroundtheblock.wallet.core.settings

import android.content.Context
import app.aroundtheblock.wallet.core.wallets.StoreScope
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.wallets.WalletStores
import app.aroundtheblock.wallet.core.wallets.storageSuffix

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
 * run before anything has read the currency. The full wipe calls [copyFromLegacy] instead: it
 * writes this file only, and leaves the old one to the wipe's own clear. The Room legacy value (`WalletConfig.fiatCurrency`)
 * stays where it was: the wallet screen adopts it once when neither file has a value.
 */
object DisplayCurrencyPrefs {
    const val KEY = "display_currency"

    /** The SPV sync blob the currency used to share. Every wipe path clears it whole. */
    const val LEGACY_PREFS_BASE = "dgb_sync_data"

    /** [PricePrefs.PREFS] plus the network suffix ("" on mainnet, so the same file as Show prices).
     *  Callers pass `storageSuffix(scope, StoreScope.NETWORK)`: the choice is shared by every wallet. */
    fun prefsName(netSuffix: String): String = PricePrefs.PREFS + netSuffix

    /** The stored enum name, or null when none is stored. Moves a value found only in the old file. */
    fun read(context: Context, netSuffix: String): String? = runCatching {
        context.getSharedPreferences(prefsName(netSuffix), Context.MODE_PRIVATE).getString(KEY, null)
    }.getOrNull() ?: moveFromLegacy(context, netSuffix)

    /**
     * Persist the user's choice. apply(): it is called from UI handlers, and no wipe path clears
     * this file, so there is nothing for an asynchronous write to race.
     */
    fun write(context: Context, netSuffix: String, enumName: String) {
        runCatching {
            context.getSharedPreferences(prefsName(netSuffix), Context.MODE_PRIVATE)
                .edit().putString(KEY, enumName).apply()
        }
    }

    /** The move's write: commit(), because the old key is removed only once this has landed. */
    private fun writeNow(context: Context, netSuffix: String, enumName: String): Boolean = runCatching {
        context.getSharedPreferences(prefsName(netSuffix), Context.MODE_PRIVATE)
            .edit().putString(KEY, enumName).commit()
    }.getOrDefault(false)

    /**
     * Move the value out of the old file, if it is there. The new file keeps a value it already has
     * (a choice made since the update wins), and the old key is removed only once the new file holds
     * a value. If that write does not land, the old value is still returned, so this session shows
     * it, and the old file keeps it for the next start to move again. On a path that clears the old
     * file next (a new wallet, the crash-loop breaker), a refused write there loses the choice; that
     * is logged. Never throws: this runs at start and before the old file is cleared.
     *
     * @return the value to show: the new file's, else the old file's, else null.
     */
    fun moveFromLegacy(context: Context, netSuffix: String): String? = carry(context, netSuffix, removeOld = true)

    /**
     * The wipe's half of the move (WIPE-WRITES-NOTHING): the value is copied to its own file and the
     * old file is not written. The wipe clears the old file whole straight after, under its own rule
     * for the files it may make, so removing the one key here would be a second write to a store the
     * wipe is about to erase, and that write makes the old file when this process holds its values
     * and the file is not on the device. If the copy does not land, the wipe's clear loses the
     * choice; that is logged. Never throws.
     *
     * @return the value to show, as [moveFromLegacy].
     */
    fun copyFromLegacy(context: Context, netSuffix: String): String? = carry(context, netSuffix, removeOld = false)

    private fun carry(context: Context, netSuffix: String, removeOld: Boolean): String? = runCatching {
        // The old home is legacy's `dgb_sync_data<net>`: no other wallet ever held the currency there.
        val legacy = context.getSharedPreferences(
            WalletStores.prefsName(LEGACY_PREFS_BASE, legacyScopeOf(netSuffix)), Context.MODE_PRIVATE,
        )
        val old = legacy.getString(KEY, null)
        val current = context.getSharedPreferences(prefsName(netSuffix), Context.MODE_PRIVATE)
            .getString(KEY, null)
        if (old == null) return@runCatching current
        if (current != null || writeNow(context, netSuffix, old)) {
            if (removeOld) legacy.edit().remove(KEY).commit()
        } else {
            runCatching { android.util.Log.w("DisplayCurrencyPrefs", "display currency not moved: the write did not land") }
        }
        current ?: old
    }.getOrNull()

    /** The legacy scope whose network suffix is [netSuffix]; throws for any other suffix. */
    private fun legacyScopeOf(netSuffix: String): WalletScope =
        listOf(false, true).map { WalletScope.legacy(it) }
            .first { storageSuffix(it, StoreScope.NETWORK) == netSuffix }
}
