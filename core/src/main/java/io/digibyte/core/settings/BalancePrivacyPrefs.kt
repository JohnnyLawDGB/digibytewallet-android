package io.digibyte.core.settings

import android.content.Context

/**
 * Persisted "hide my balances" preference for the wallet's hero card.
 *
 * A single per-device boolean, default OFF (balances visible). Stored in its own
 * SharedPreferences file — not network-suffixed: privacy is a UI choice, not a
 * chain fact, so it holds across mainnet/testnet and across app restarts.
 */
object BalancePrivacyPrefs {
    private const val PREFS = "dgb_privacy"
    private const val KEY_HIDDEN = "balance_hidden"

    fun isHidden(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HIDDEN, false)

    fun setHidden(ctx: Context, hidden: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HIDDEN, hidden).apply()
    }
}

/**
 * Pure display helper: the string to show for an amount given the privacy state.
 * Extracted (Compose-free) so the mask rule is unit-testable without a renderer.
 */
const val BALANCE_MASK = "••••"

fun maskedAmount(hidden: Boolean, value: String): String = if (hidden) BALANCE_MASK else value
