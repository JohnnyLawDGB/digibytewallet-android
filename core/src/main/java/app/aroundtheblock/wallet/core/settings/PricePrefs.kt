package app.aroundtheblock.wallet.core.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings → Display → Show prices: a per-device boolean, default ON (owner decision for both apps,
 * 2026-11-09). Off, [app.aroundtheblock.wallet.core.PriceProvider] makes no price request and the
 * app shows no fiat figure anywhere; turning it off also drops the cached price.
 *
 * Its own SharedPreferences file, not network-suffixed (a UI choice, like [BalancePrivacyPrefs]).
 * The key is iOS's (`price_fetch_enabled`). [enabled] is one flow for the process, so the home
 * screen hears the switch without a restart.
 *
 * A wallet wipe or restore does NOT touch it (owner decision, 2026-11-09): like the own node and
 * the balance mask it is a device setting. The wipe drops only the cached price (Room's price
 * cache, deleted with the database), so the flow and the file never disagree after a wipe.
 *
 * The display currency shares this file on mainnet ([DisplayCurrencyPrefs], network-suffixed),
 * kept by a wipe for the same reason (owner decision, 2026-11-12).
 */
object PricePrefs {
    /** Kept by a wallet wipe: WalletDataEraser does not clear it (WalletWipeTest holds that). */
    const val PREFS = "dgb_price"
    const val KEY_ENABLED = "price_fetch_enabled"

    @Volatile private var state: MutableStateFlow<Boolean>? = null

    private fun state(ctx: Context): MutableStateFlow<Boolean> =
        state ?: synchronized(this) {
            state ?: MutableStateFlow(
                ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, true)
            ).also { state = it }
        }

    fun enabled(ctx: Context): StateFlow<Boolean> = state(ctx).asStateFlow()

    fun isEnabled(ctx: Context): Boolean = state(ctx).value

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
        state(ctx).value = on
    }
}
