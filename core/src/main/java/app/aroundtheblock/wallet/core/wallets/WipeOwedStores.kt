package app.aroundtheblock.wallet.core.wallets

import android.content.Context
import android.util.Log

/**
 * The ids of the wallets a full wipe still owes a store erase (INT-2026-11-19-A).
 *
 * A wallet is found by its registry entry, its seed record or its seed key alias, and
 * `WalletManager.wipeWallet` removes all three before a wallet's other stores. So before those
 * stores go the wipe puts every other wallet's id here, and takes an id off only when that wallet's
 * stores read back empty on both networks. [ActiveWallet.everyWalletId] reads it, so a store one
 * wipe could not clear is still listed by the next, and that wipe cannot verify while it is there.
 *
 * Holds wallet ids and nothing else, under a name that carries none. `legacy` is never in it: every
 * wipe lists legacy. A wipe verifies only once it is empty, and its file goes with its last id, so a
 * verified wipe leaves neither the record nor an id. Only the wipe writes it.
 */
object WipeOwedStores {
    const val PREFS = "dgb_wipe_owed"
    private const val KEY_IDS = "ids"
    private const val TAG = "WipeOwedStores"

    private fun store(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The ids on record, each once. One that cannot be read lists nothing: the wipe's other sources still do. */
    fun ids(context: Context): List<String> = try {
        (store(context).all[KEY_IDS] as? String).orEmpty().split(SEPARATOR).filter(::listable).distinct()
    } catch (t: Throwable) {
        Log.w(TAG, "not read (${t.javaClass.simpleName})")
        emptyList()
    }

    /**
     * Takes [cleared] off the record, then puts [owed] on it. True when the record now holds exactly
     * that: the write landed, or there was nothing to write (a record of nothing creates no file).
     */
    fun record(context: Context, owed: Collection<String> = emptyList(), cleared: Collection<String> = emptyList()): Boolean {
        val held = ids(context)
        val next = (held - cleared.toSet() + owed.filter(::listable)).distinct()
        if (next == held) return true
        val store = store(context)
        if (next.isNotEmpty()) return store.edit().putString(KEY_IDS, next.joinToString(SEPARATOR)).commit()
        val landed = store.edit().clear().commit()
        // Best effort: the emptied file names no wallet.
        if (landed) runCatching { context.deleteSharedPreferences(PREFS) }
        return landed
    }

    private fun listable(id: String) = id != WalletIds.LEGACY && WalletIds.isValid(id)

    private const val SEPARATOR = ","
}
