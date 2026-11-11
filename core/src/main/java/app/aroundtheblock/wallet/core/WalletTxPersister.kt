package app.aroundtheblock.wallet.core

import android.content.Context
import app.aroundtheblock.wallet.core.bridge.NativeBridge
import app.aroundtheblock.wallet.core.sync.SavedTransactionsCheckpoint
import app.aroundtheblock.wallet.core.sync.SavedTransactionsStore

/**
 * The one writer of the wallet's transaction checkpoint ([SavedTransactionsStore]): every path that
 * saves the C wallet's transaction set — a broadcast, the sync service's checkpoint gate, the
 * background catch-up — goes through [persistCheckpoint], so the epoch snapshot, the atomic file
 * write and the over-limit breadcrumb are one copy.
 *
 * The post-broadcast path ([persist]) still matters on its own: without a synchronous write a user
 * who sends and force-stops at once loses the tx from persistent state, and after restart the C
 * wallet rebuilds from the stale snapshot and the tx vanishes from the UI (publishTransaction in
 * `jni_transaction.c` registers the tx synchronously, so the set read here includes it).
 */
class WalletTxPersister(
    private val context: Context,
    private val source: Source = Source.Native,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** The native set. An interface so the host JVM tests can stand in for the JNI library. */
    interface Source {
        fun transactionCount(): Long
        /** The checkpoint blob, or null: no transactions, a failed allocation, or above the limit. */
        fun serialized(): ByteArray?

        object Native : Source {
            override fun transactionCount(): Long = NativeBridge.getTransactionCount().toLong()
            override fun serialized(): ByteArray? = NativeBridge.getSerializedTransactions()
        }
    }

    enum class Result(val landed: Boolean) {
        /** The checkpoint file now holds the wallet's set. */
        WRITTEN(true),
        /** The wallet holds no transactions: nothing to write, and nothing is lost. */
        EMPTY(true),
        /** TX-CKPT-CAP: more records than the shared limit; the writer emits nothing. The previous
         *  checkpoint stays, and the caller must not advance the scan ledger or `has_synced`. */
        REFUSED_OVER_LIMIT(false),
        /** The serializer or the file write failed, or a reset ran in between. */
        FAILED(false),
        /** [persistCheckpoint]'s abort check said stop (a wipe stood the caller down). */
        ABORTED(false),
    }

    /**
     * Serialize the wallet's set and write it through to disk, synchronously. [shouldAbort] is asked
     * after the native read and before the write, so a wipe in between leaves the wiped store as it is.
     */
    fun persistCheckpoint(shouldAbort: () -> Boolean = { false }): Result {
        val epoch = SavedTransactionsStore.currentEpoch()
        val count = runCatching { source.transactionCount() }.getOrDefault(-1L)
        if (count == 0L) return Result.EMPTY
        val data = runCatching { source.serialized() }.getOrNull()
        if (data == null) {
            if (count > SavedTransactionsCheckpoint.MAX_RECORDS) {
                val first = runCatching {
                    SavedTransactionsCheckpoint.noteWriterRefused(diagPrefs(), count, nowMs())
                }.getOrDefault(false)
                if (first) runCatching {
                    android.util.Log.w(
                        "WalletTxPersister",
                        "TX-CKPT-CAP: $count transactions is above the checkpoint limit " +
                            "${SavedTransactionsCheckpoint.MAX_RECORDS}; the last checkpoint stays and each launch rescans",
                    )
                }
                return Result.REFUSED_OVER_LIMIT
            }
            return Result.FAILED
        }
        if (shouldAbort()) return Result.ABORTED
        if (!SavedTransactionsStore.write(context, data, epoch)) return Result.FAILED
        runCatching { SavedTransactionsCheckpoint.noteWriterAccepted(diagPrefs()) }
        return Result.WRITTEN
    }

    /** [persistCheckpoint] for the post-broadcast callers: true when a checkpoint file was written. */
    fun persist(): Boolean = persistCheckpoint() == Result.WRITTEN

    private fun diagPrefs() = context.getSharedPreferences("dgb_diag", Context.MODE_PRIVATE)
}
