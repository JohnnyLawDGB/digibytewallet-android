package app.aroundtheblock.wallet.core.sync

import android.content.SharedPreferences

/**
 * The transaction checkpoint as the app sees it: the blob BRWalletSerializeTransactions writes
 * (u32 LE record count, then the records), stored by [SavedTransactionsStore].
 *
 * TX-CKPT-CAP: the checkpoint is a cache of the wallet's history, and the CF scan resumes from a
 * frontier that assumes the cache restored. So the record count the blob DECLARES is compared with
 * what the reader loaded, at the restore, before the wallet is built from it; a load short of it
 * (the reader refused the blob, a record did not parse, the blob was cut short or unreadable) is
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

    /** [declaredCount] for a checkpoint whose header cannot be read (shorter than its 4-byte count). */
    const val UNREADABLE = -1L

    /** In `dgb_diag`: the writer refused the wallet's set (more records than [MAX_RECORDS]). Written
     *  when that state begins, not on every refused attempt; set false when a checkpoint lands again.
     *  The count and time of the last refusal stay as its record. */
    const val KEY_DIAG_REFUSED = "tx_checkpoint_refused"
    const val KEY_DIAG_REFUSED_COUNT = "last_tx_checkpoint_refused_count"
    const val KEY_DIAG_REFUSED_AT_MS = "last_tx_checkpoint_refused_at_ms"

    /**
     * The record count the stored checkpoint [bytes] declares: null when there is no checkpoint,
     * [UNREADABLE] when it is too short to hold its count (an unreadable legacy copy loads as empty).
     */
    fun declaredCount(bytes: ByteArray?): Long? {
        if (bytes == null) return null
        if (bytes.size < 4) return UNREADABLE
        return (bytes[0].toLong() and 0xffL) or
            ((bytes[1].toLong() and 0xffL) shl 8) or
            ((bytes[2].toLong() and 0xffL) shl 16) or
            ((bytes[3].toLong() and 0xffL) shl 24)
    }

    /**
     * Whether a restore is missing records its checkpoint declared: the READER loaded fewer than
     * [declared] ([readerLoadedCount], what loadSerializedTransactions returned), and an unreadable
     * header counts as missing everything. Records the reader loaded and the wallet then declined
     * to keep (a repeated hash, an unsigned or out-of-range record) are not a shortfall: the writer
     * never emits those, so they are not history the frontier assumed. No checkpoint is not a
     * shortfall either: that case is the preflight's MISSING_TRANSACTION_CHECKPOINT.
     */
    fun isShort(declared: Long?, readerLoadedCount: Long): Boolean = when {
        declared == null -> false
        declared < 0L -> true
        else -> readerLoadedCount < declared
    }

    /**
     * Records a restore whose reader loaded [readerLoadedCount] records from the checkpoint [bytes]
     * (0 when the load threw). [checkpointedCount] is the record count the sync service last recorded
     * beside `transactions_checkpointed` ([SavedTransactionsStore.KEY_COUNT], 0 when the flag is not
     * set): with no checkpoint file at all, a count above 0 means the file was lost, and that restore
     * is short too. A short load is committed under [KEY_SHORT_RESTORE] and true returned; a complete
     * one changes nothing (an earlier record stays until the rebuild clears it).
     */
    fun recordRestore(
        syncPrefs: SharedPreferences,
        bytes: ByteArray?,
        readerLoadedCount: Long,
        checkpointedCount: Long = 0L,
    ): Boolean {
        val declared = if (bytes == null && checkpointedCount > 0L) checkpointedCount else declaredCount(bytes)
        if (!isShort(declared, readerLoadedCount)) return false
        syncPrefs.edit().putLong(KEY_SHORT_RESTORE, declared ?: UNREADABLE).commit()
        return true
    }

    /**
     * The writer refused a wallet of [count] records. The breadcrumb is written once, when the refusal
     * begins (the flag was not set), not on every attempt. Returns whether it was written now.
     */
    fun noteWriterRefused(diagPrefs: SharedPreferences, count: Long, nowMs: Long): Boolean {
        if (diagPrefs.getBoolean(KEY_DIAG_REFUSED, false)) return false
        return diagPrefs.edit()
            .putBoolean(KEY_DIAG_REFUSED, true)
            .putLong(KEY_DIAG_REFUSED_COUNT, count)
            .putLong(KEY_DIAG_REFUSED_AT_MS, nowMs)
            .commit()
    }

    /** A checkpoint landed: the refusal state, if any, is over (its count and time are kept). */
    fun noteWriterAccepted(diagPrefs: SharedPreferences) {
        if (diagPrefs.getBoolean(KEY_DIAG_REFUSED, false)) diagPrefs.edit().putBoolean(KEY_DIAG_REFUSED, false).commit()
    }
}
