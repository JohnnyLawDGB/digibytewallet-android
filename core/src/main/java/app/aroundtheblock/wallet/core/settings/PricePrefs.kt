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
 */
object PricePrefs {
    /** Cleared by a wallet wipe (WalletDataEraser.eraseLeftoverState), so the next wallet starts on. */
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

    /**
     * After a wipe has cleared [PREFS]: the next read in this process reads the file again (default
     * on). A flow already handed out keeps its value; it is not set to on here, as that would start
     * a price fetch, and a write to the price cache, after the wipe erased the database.
     */
    fun forget() {
        synchronized(this) { state = null }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
        state(ctx).value = on
    }
}
