package app.aroundtheblock.wallet.core.sync

import android.content.Context
import app.aroundtheblock.wallet.core.wallets.ActiveWallet
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.wallets.WalletStores

/**
 * Remembers the last observed chain tip across sessions, so confirmation counts are right the
 * instant the wallet opens instead of after the native peer manager finishes loading saved blocks.
 *
 * See [ChainTipPolicy] for why this exists — in short, without a remembered tip the UI had to
 * floor the tip to the highest transaction height it could see, which made the newest transaction
 * read "1 confirmation" on every single launch.
 *
 * Deliberately a plain `Long` in `dgb_sync_data`, NOT a file and NOT a growing blob. That choice is
 * load-bearing: the compact-filter header chain used to live in this same prefs file as a
 * hex-encoded String and was re-`putString()`-ed on every cfheaders batch, which pinned an
 * ever-growing value in `SharedPreferencesImpl`'s process-lifetime map and OOM-looped long-history
 * wallets (see [FilterHeaderStore]). Eight fixed bytes written at most once per block cannot
 * reproduce that.
 *
 * Living in `dgb_sync_data` also means every teardown path already clears it — `StaleDataWiper`
 * and `WalletManager.clearSyncData()` both `.clear()` this file — so a tip cannot survive a wipe
 * or leak from one restored seed into the next. That inheritance bug has bitten this codebase
 * before with the CF scan ledger, and the cheapest way not to repeat it is to store this where the
 * existing wipes already reach rather than to add a new one that some future path forgets.
 *
 * A rescan deliberately does NOT clear it: a rescan rewinds the SCAN floor, not the chain, so the
 * remembered tip is still the truth and dropping it would put the "1 confirmation" artifact back
 * for exactly the users most likely to be watching.
 */
object ChainTipStore {
    private const val PREFS_NAME = "dgb_sync_data"
    private const val KEY_TIP = "last_chain_tip"

    /**
     * Mirrors the persisted value of each wallet so the UI refresh loop doesn't hit prefs on every
     * tick. Keyed by scope: one wallet's tip is never read, written or left behind as another's.
     */
    private val cached = java.util.concurrent.ConcurrentHashMap<WalletScope, Long>()

    private fun prefs(ctx: Context, scope: WalletScope) =
        ctx.getSharedPreferences(WalletStores.prefsName(PREFS_NAME, scope), Context.MODE_PRIVATE)

    /** Last remembered tip, or [ChainTipPolicy.UNKNOWN_TIP] if none has been stored yet. */
    fun read(ctx: Context, scope: WalletScope = ActiveWallet.scope(ctx)): Long {
        cached[scope]?.let { return it }
        val stored = runCatching { prefs(ctx, scope).getLong(KEY_TIP, ChainTipPolicy.UNKNOWN_TIP) }
            .getOrDefault(ChainTipPolicy.UNKNOWN_TIP)
        cached[scope] = stored
        return stored
    }

    /**
     * Remember [candidate] if it is a real height and ahead of what is stored.
     * Returns true when the stored tip actually moved.
     *
     * `apply()` rather than `commit()`: losing the newest tip to a process kill costs one launch's
     * worth of a slightly stale count, which is exactly the thing this class already tolerates by
     * design, and is not worth a synchronous disk write on a UI refresh path.
     */
    fun record(ctx: Context, candidate: Long, scope: WalletScope = ActiveWallet.scope(ctx)): Boolean {
        if (!ChainTipPolicy.shouldPersistTip(candidate, read(ctx, scope))) return false
        cached[scope] = candidate
        runCatching { prefs(ctx, scope).edit().putLong(KEY_TIP, candidate).apply() }
        return true
    }

    /**
     * Drop the in-memory mirror. Callers that wipe prefs directly (the wipe/erase paths clear the
     * whole `dgb_sync_data` file rather than going through this object) must call this, or a stale
     * tip would survive in the cache for the life of the process and be re-persisted on the next
     * [record]. Exposed for tests too, which need a clean slate between cases.
     */
    fun invalidateCache() {
        cached.clear()
    }

    /** [invalidateCache] for one wallet only: what removing that wallet drops, leaving every other's. */
    fun invalidateCache(scope: WalletScope) {
        cached.remove(scope)
    }
}
