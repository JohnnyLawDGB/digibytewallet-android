package app.aroundtheblock.wallet.core.wallets

import android.content.Context

/**
 * What a restored wallet's opt-in quick start found, before its own sync has proved anything.
 *
 * DISPLAY ONLY. It is never spendable and never part of the wallet's balance: a figure counts only
 * when the native wallet holds that exact output, which only the compact-filter scan proves.
 *
 * @param unverifiedSat the DGB the lookup reported on this wallet's own address formats.
 * @param unverifiedDdCents the DigiDollar the lookup reported on this wallet's own DigiDollar
 *   addresses (zero when it reported none), or null when DigiDollar could not be checked: the
 *   lookup was not made, or did not finish. Null is shown as "could not be checked", never as zero.
 * @param sourceLabel the server that answered (a host name, never a URL with credentials).
 * @param atSecs when it answered, Unix seconds.
 */
data class QuickStart(
    val unverifiedSat: Long,
    val unverifiedDdCents: Long?,
    val sourceLabel: String,
    val atSecs: Long,
)

/**
 * One wallet's [QuickStart], in that wallet's own `dgb_quickstart` store. Read only by the wallet
 * screen's "not yet verified" line: no balance, UTXO or send code reads it (`QuickStartGateTest`).
 * Kept until the wallet's own sync has finished once, then cleared.
 */
object QuickStartStore {
    private const val BASE = "dgb_quickstart"
    private const val SYNC_PREFS = "dgb_sync_data"
    private const val KEY_HAS_SYNCED = "has_synced"
    private const val KEY_SAT = "unverified_sat"
    private const val KEY_DD = "unverified_dd_cents"
    private const val KEY_SOURCE = "source"
    private const val KEY_AT = "at_secs"

    private fun prefs(context: Context, scope: WalletScope) =
        context.getSharedPreferences(WalletStores.prefsName(BASE, scope), Context.MODE_PRIVATE)

    /** Replaces what [scope] holds with [q]. True when the write landed (`commit()`). */
    fun write(context: Context, scope: WalletScope, q: QuickStart): Boolean {
        require(q.unverifiedSat >= 0 && (q.unverifiedDdCents ?: 0) >= 0) { "negative quick start" }
        return prefs(context, scope).edit().clear().apply {
            putLong(KEY_SAT, q.unverifiedSat)
            if (q.unverifiedDdCents != null) putLong(KEY_DD, q.unverifiedDdCents)
            putString(KEY_SOURCE, q.sourceLabel)
            putLong(KEY_AT, q.atSecs)
        }.commit()
    }

    fun read(context: Context, scope: WalletScope): QuickStart? {
        val p = prefs(context, scope)
        if (!p.contains(KEY_SAT)) return null
        return QuickStart(
            unverifiedSat = p.getLong(KEY_SAT, 0L),
            unverifiedDdCents = if (p.contains(KEY_DD)) p.getLong(KEY_DD, 0L) else null,
            sourceLabel = p.getString(KEY_SOURCE, null) ?: return null,
            atSecs = p.getLong(KEY_AT, 0L),
        )
    }

    /** Clears [scope]'s quick start once that wallet's own sync has finished. True when it cleared one. */
    fun clearIfSynced(context: Context, scope: WalletScope): Boolean {
        val p = prefs(context, scope)
        if (!p.contains(KEY_SAT)) return false
        val synced = context.getSharedPreferences(WalletStores.prefsName(SYNC_PREFS, scope), Context.MODE_PRIVATE)
            .getBoolean(KEY_HAS_SYNCED, false)
        return synced && p.edit().clear().commit()
    }
}
