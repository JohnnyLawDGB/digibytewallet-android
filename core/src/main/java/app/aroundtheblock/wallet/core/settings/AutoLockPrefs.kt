package app.aroundtheblock.wallet.core.settings

import android.content.Context
import android.util.Log
import app.aroundtheblock.wallet.core.db.dao.WalletConfigDao
import app.aroundtheblock.wallet.core.wallets.WalletScope
import kotlinx.coroutines.CancellationException

/**
 * Settings → Security → Auto-lock: how long the unlocked wallet may sit idle in the foreground before
 * the inactivity lock locks it. ONE value for the app: one PIN and one lock policy cover every wallet
 * (multi-wallet design). Its own preferences file, in no wallet's scope and not network-suffixed.
 *
 * ## Why it is not in the wallet database any more
 *
 * It lived in `wallet_config.autoLockTimeoutMs`. Since multi-wallet step 1 every wallet has its own
 * database, so the lock read the ACTIVE wallet's row: a wallet just added has none and enforced the
 * default whatever the user had chosen, and a choice made in one wallet did not hold in another. The
 * column stays (no schema change) and is read once, by the one-time move ([readLegacy]).
 *
 * ## What clears it
 *
 * The wipe clears this file, as it releases the PIN store: the lock policy goes with the one PIN it
 * guards, and that is also what the wipe did while the timeout lived in the database it erases.
 * Removing one wallet does not touch it. Nor does the start-up crash-loop breaker, which used to
 * reset it by setting the database aside: it is a setting, not regenerable data.
 *
 * The default when nothing is stored, [DEFAULT_TIMEOUT_MS], is applied by the lock policy
 * (`autoLockTimeoutOrDefault`), not written here: a default written down would outlive a change to it.
 */
object AutoLockPrefs {
    const val PREFS = "dgb_auto_lock"
    const val KEY_TIMEOUT_MS = "timeout_ms"

    /** Set once the legacy wallet's database has answered the one-time move, whatever it held. */
    const val KEY_LEGACY_MOVED = "legacy_wallet_config_moved"

    /** 60 s: the shortest option, and the wallet database's default before the move. */
    const val DEFAULT_TIMEOUT_MS = 60_000L

    private const val TAG = "AutoLockPrefs"

    /** Orders the move's "only if unset" write against a choice being stored at the same moment. */
    private val lock = Any()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The chosen timeout, or null when none was chosen (or the file cannot be read). */
    fun storedTimeoutMs(ctx: Context): Long? = runCatching {
        val p = prefs(ctx)
        if (p.contains(KEY_TIMEOUT_MS)) p.getLong(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS) else null
    }.getOrNull()

    /** Store the user's choice. commit(), off the main thread: true when the write landed. */
    fun setTimeoutMs(ctx: Context, ms: Long): Boolean = synchronized(lock) {
        runCatching { prefs(ctx).edit().putLong(KEY_TIMEOUT_MS, ms).commit() }.getOrDefault(false)
    }

    /** What the one-time move found where the 4.1.4 timeout is kept. */
    sealed interface Legacy {
        /**
         * The database this process has open is not the legacy wallet's own on disk (another wallet,
         * testnet, or this session's temporary database): it was not read, and the move waits for a
         * launch that has legacy's database open.
         */
        data object NotHere : Legacy

        /**
         * The legacy wallet's database answered: its stored timeout, or null when it has no config row.
         * Only [readLegacy] makes one, so no caller can finish the move from a database it chose itself.
         */
        @ConsistentCopyVisibility
        data class Answered internal constructor(val timeoutMs: Long?) : Legacy
    }

    /**
     * The one-time move's read, and the whole decision of which database may answer it: the row of
     * the database this process has open, only when that is the one the 4.1.4 timeout is in, the
     * legacy wallet's mainnet database (`wallet.db`) opened from disk. A testnet session, another
     * wallet's process and the temporary in-memory database a Keystore fault gives a session each
     * have a different database open, and "no row" there says nothing about legacy's; such a
     * database is not read at all, and the answer is [Legacy.NotHere].
     *
     * The launch hands this the facts about its open database and nothing else
     * (`AppWideSettingsSourceGateTest` holds it to that), so `AutoLockPrefsTest` runs the decision
     * the app runs, not a copy of it.
     *
     * @param scope the wallet this process runs (`ActiveWallet.scope`): the one its database was opened for.
     * @param databaseName that database's `openHelper.databaseName`: its file's name, or null for the
     *   temporary in-memory database (Room builds that one with no name).
     * @param dao that database's own `wallet_config`.
     */
    suspend fun readLegacy(scope: WalletScope, databaseName: String?, dao: WalletConfigDao): Legacy {
        val legacysOwnOnDisk = databaseName != null && scope == WalletScope.legacy(testnet = false)
        return if (legacysOwnOnDisk) Legacy.Answered(dao.get()?.autoLockTimeoutMs) else Legacy.NotHere
    }

    /** True once the move has been answered. */
    fun legacyMoved(ctx: Context): Boolean =
        runCatching { prefs(ctx).getBoolean(KEY_LEGACY_MOVED, false) }.getOrDefault(false)

    /**
     * The one-time move of the legacy wallet's timeout here, run at launch until it has been answered
     * once. [read] is asked only while the move is not done, and answers with [readLegacy]: only that
     * makes a [Legacy.Answered]. An answer is carried only when no timeout is stored yet, so a choice
     * made since the update (in any wallet) is never overwritten; an answer of "no config row"
     * carries nothing and leaves the default. [Legacy.NotHere], a read that throws, or a write that
     * does not land leave the move for a later launch. Never throws but for cancellation.
     *
     * @return true when the move is done, by this call or an earlier one.
     */
    suspend fun migrateFromLegacy(ctx: Context, read: suspend () -> Legacy): Boolean {
        if (legacyMoved(ctx)) return true
        val answer = try {
            read()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "legacy auto-lock timeout not read (${t.javaClass.simpleName}); retried next launch") }
            return false
        }
        if (answer !is Legacy.Answered) return false
        val landed = synchronized(lock) {
            runCatching {
                val p = prefs(ctx)
                val edit = p.edit().putBoolean(KEY_LEGACY_MOVED, true)
                if (answer.timeoutMs != null && !p.contains(KEY_TIMEOUT_MS)) edit.putLong(KEY_TIMEOUT_MS, answer.timeoutMs)
                edit.commit()
            }.getOrDefault(false)
        }
        if (!landed) runCatching { Log.w(TAG, "legacy auto-lock timeout not moved: the write did not land; retried next launch") }
        return landed
    }
}
