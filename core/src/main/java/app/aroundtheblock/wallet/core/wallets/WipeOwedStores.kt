package app.aroundtheblock.wallet.core.wallets

import android.content.Context
import android.util.Log

/**
 * The ids of the wallets a full wipe still owes a store erase (INT-2026-11-19-A).
 *
 * A wallet is found by its registry entry, its seed record or its seed key alias (and, for a
 * wallet that left one, its reserved key record), and `WalletManager.wipeWallet` removes the first
 * three before a wallet's other stores. So before those
 * stores go the wipe puts every other wallet's id here, and takes an id off only when that wallet's
 * stores read back empty on both networks. [ActiveWallet.everyWalletId] reads it, so a store one
 * wipe could not clear is still listed by the next, and that wipe cannot verify while it is there.
 *
 * Holds wallet ids and nothing else, under a name that carries none. `legacy` is never in it: every
 * wipe lists legacy. A wipe verifies only once it reads back empty with its file off the disk
 * ([settled]), so a verified wipe leaves neither the record nor an id. Only the wipe writes it.
 */
object WipeOwedStores {
    const val PREFS = "dgb_wipe_owed"
    private const val KEY_IDS = "ids"
    private const val TAG = "WipeOwedStores"

    /**
     * True after a write of this process that did not land. What [read] answers may then be this
     * process's view only, not the file's, so nothing relies on it until a write lands.
     */
    @Volatile private var unlanded = false

    /** Whether what [read] answers is what the file holds, as far as this process wrote it. */
    fun everyWriteLanded(): Boolean = !unlanded

    /** A new process holds nothing from the one before; tests model a launch with this. */
    internal fun resetForTest() { unlanded = false }

    private fun store(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The ids on record, each once; null when the record cannot be read. */
    fun read(context: Context): List<String>? = try {
        (store(context).all[KEY_IDS] as? String).orEmpty().split(SEPARATOR).filter(::listable).distinct()
    } catch (t: Throwable) {
        Log.w(TAG, "not read (${t.javaClass.simpleName})")
        null
    }

    /**
     * The ids on record, for listing wallets. A record that cannot be read lists nothing here (the
     * wipe's other sources still do), and the wipe then cannot verify ([settled]).
     */
    fun ids(context: Context): List<String> = read(context).orEmpty()

    /**
     * Takes [cleared] off the record, then puts [owed] on it. True when the record now holds exactly
     * that: the write landed, or there was nothing to write (a record of nothing creates no file).
     * A record that cannot be read is not written over, and that is false.
     */
    fun record(context: Context, owed: Collection<String> = emptyList(), cleared: Collection<String> = emptyList()): Boolean {
        val held = read(context) ?: return false
        val next = (held - cleared.toSet() + owed.filter(::listable)).distinct()
        // "Nothing to write" is trusted only while every earlier write of this process landed: a
        // commit that fails still changes what this process reads back, so after one the record is
        // written again in full until a write lands.
        if (next == held && !unlanded) return true
        val store = store(context)
        // Not landed until the commit says so: one that changes this process's view and then
        // does not return leaves the flag set.
        unlanded = true
        val landed = if (next.isNotEmpty()) {
            store.edit().putString(KEY_IDS, next.joinToString(SEPARATOR)).commit()
        } else {
            store.edit().clear().commit()
        }
        unlanded = !landed
        // The emptied file names no wallet; [settled] reads back that it went.
        if (landed && next.isEmpty()) runCatching { context.deleteSharedPreferences(PREFS) }
        return landed
    }

    /**
     * READ-BACK for a wipe: the record holds no id and its file is not on the device. An emptied
     * file an earlier run left is deleted here first. What cannot be read counts as present, as
     * everywhere in the eraser: a record that cannot be read, or a file that cannot be looked for,
     * is not settled.
     */
    fun settled(context: Context): Boolean = try {
        if (read(context)?.isEmpty() != true) {
            false
        } else {
            if (prefsFileOnDisk(context, PREFS)) context.deleteSharedPreferences(PREFS)
            !prefsFileOnDisk(context, PREFS)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "not settled (${t.javaClass.simpleName})")
        false
    }

    private fun listable(id: String) = id != WalletIds.LEGACY && WalletIds.isValid(id)

    private const val SEPARATOR = ","
}
