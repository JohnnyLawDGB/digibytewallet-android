package app.aroundtheblock.wallet.core.sync

import android.content.SharedPreferences

/**
 * The `saved_transactions` checkpoint as the app sees it: the hex of the blob
 * BRWalletSerializeTransactions writes (u32 LE record count, then the records).
 *
 * TX-CKPT-CAP: the checkpoint is a cache of the wallet's history, and the CF scan resumes from a
 * frontier that assumes the cache restored. So the record count the blob DECLARES is compared with
 * what the reader loaded, at the restore, before the wallet is built from it; a load short of it
 * (the reader refused the blob, a record did not parse, the blob was cut short or is not hex) is
 * recorded under [KEY_SHORT_RESTORE], and the sync start then rebuilds from the birth floor instead
 * of resuming near the tip without the history the frontier assumes (SyncService's CF preflight,
 * which clears the record in the same commit as the rebuild).
 *
 * The record is taken at the restore and is sticky, rather than re-derived at the sync start: any
 * checkpoint written in between (a send's WalletTxPersister, for one) is written from the short
 * wallet, declares the short count, and would hide the shortfall.
 */
object SavedTransactionsCheckpoint {

    /** BR_SAVED_TRANSACTIONS_MAX_COUNT (core BRSavedTransactions.h): the one limit the native writer
     *  and readers share. A mirror, held to the header's value by SavedTransactionsCheckpointTest. */
    const val MAX_RECORDS = 100_000L

    /** In the network's `dgb_sync_data`: the count a short restore's checkpoint declared (-1 when
     *  unreadable). Present = the sync start must rebuild from the birth floor. */
    const val KEY_SHORT_RESTORE = "tx_checkpoint_short"

    /** [declaredCount] for a checkpoint whose header cannot be read (too short, or not hex). */
    const val UNREADABLE = -1L

    /**
     * The record count the stored checkpoint [hex] declares: null when there is no checkpoint,
     * [UNREADABLE] when its leading count cannot be read. Reads only the first 8 hex characters.
     */
    fun declaredCount(hex: String?): Long? {
        if (hex == null) return null
        if (hex.length < 8) return UNREADABLE
        var count = 0L
        for (byte in 0 until 4) {
            val hi = Character.digit(hex[2 * byte], 16)
            val lo = Character.digit(hex[2 * byte + 1], 16)
            if (hi < 0 || lo < 0) return UNREADABLE
            count = count or (((hi shl 4) or lo).toLong() shl (8 * byte))
        }
        return count
    }

    /**
     * Whether the restored wallet is missing records its checkpoint declared: it holds fewer than
     * [declared] (an unreadable header counts as missing everything). No checkpoint is not a
     * shortfall: that case is the preflight's MISSING_TRANSACTION_CHECKPOINT.
     */
    fun isShort(declared: Long?, walletTransactionCount: Long): Boolean = when {
        declared == null -> false
        declared < 0L -> true
        else -> walletTransactionCount < declared
    }

    /**
     * Records a restore that loaded [loaded] records from the checkpoint [hex] (the reader's count;
     * 0 when the blob was not hex or the load threw). A short load is committed under
     * [KEY_SHORT_RESTORE] and true returned; a complete one changes nothing (an earlier record stays
     * until the rebuild clears it). No checkpoint, no record.
     */
    fun recordRestore(syncPrefs: SharedPreferences, hex: String?, loaded: Long): Boolean {
        val declared = declaredCount(hex)
        if (!isShort(declared, loaded)) return false
        syncPrefs.edit().putLong(KEY_SHORT_RESTORE, declared ?: UNREADABLE).commit()
        return true
    }
}
