package app.aroundtheblock.wallet.core

import android.content.Context

/**
 * Records transactions that the user broadcast locally: the stranded-send sweep
 * publishes them again until they confirm, and the activity list names a send's
 * recipient from [Record.toAddress]. The list's amount, fee and date are the shared
 * core's (BRTxRows.h, core/TransactionRows), never this record's.
 *
 * Stored as a flat SharedPreferences map: each broadcast writes up to five
 * keys per txid: `<txid>.sent`, `<txid>.fee`, `<txid>.to`, `<txid>.self`, `<txid>.dd`.
 * Lookups return null when the txid isn't ours.
 */
class OutgoingTxStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** @param isSelfTransfer true when [toAddress] is an address THIS wallet
     *  owns (e.g. a legacy-funds sweep into our own receive address). Such a tx
     *  increases the balance, and the core's row reads it as a receive. Genuine
     *  external sends store false. */
    data class Record(
        val sentSats: Long,
        val feeSats: Long,
        val toAddress: String,
        val isSelfTransfer: Boolean = false,
        /** A DigiDollar send: recorded so the stranded-send sweep publishes it again until it confirms.
         *  Its row says what it did from the transaction itself, never from this record. */
        val isDigiDollar: Boolean = false,
    )

    fun record(
        txid: String,
        sentSats: Long,
        feeSats: Long,
        toAddress: String,
        isSelfTransfer: Boolean = false,
        isDigiDollar: Boolean = false,
    ) {
        prefs.edit()
            .putLong("$txid.sent", sentSats)
            .putLong("$txid.fee", feeSats)
            .putString("$txid.to", toAddress)
            .putBoolean("$txid.self", isSelfTransfer)
            .putBoolean("$txid.dd", isDigiDollar)
            .apply()
    }

    fun lookup(txid: String): Record? {
        val sent = prefs.getLong("$txid.sent", -1L)
        if (sent < 0L) return null
        return Record(
            sentSats = sent,
            feeSats = prefs.getLong("$txid.fee", 0L),
            toAddress = prefs.getString("$txid.to", "") ?: "",
            isSelfTransfer = prefs.getBoolean("$txid.self", false),
            isDigiDollar = prefs.getBoolean("$txid.dd", false),
        )
    }

    /** All recorded send txids, settled or not. */
    fun allTxids(): Set<String> = recordedTxidsOf(prefs.all.keys)

    /** The recorded sends the stranded-send sweep still looks after: every recorded txid
     *  not yet [markSettled]. The sweep re-fluffs any of these the wallet still sees as
     *  unconfirmed — a Dandelion stem killed mid-embargo (process death before the fluff
     *  timer fires) otherwise strands the tx forever. */
    fun pendingTxids(): Set<String> = pendingTxidsOf(prefs.all.keys)

    /** Settle a recorded send for the sweep: the wallet holds it at a confirmed height (or has
     *  aged it out of its recent-transaction window, which only a confirmed tx does), or the
     *  sweep has given up on it. The record itself stays — the activity list still reads it
     *  through [lookup] to name the send's recipient — but [pendingTxids] no
     *  longer lists it, so the sweep never re-publishes it again. */
    fun markSettled(txid: String) {
        prefs.edit().putBoolean("$txid.settled", true).apply()
    }

    fun isSettled(txid: String): Boolean = prefs.getBoolean("$txid.settled", false)

    /** How many times the sweep has re-published (or tried to) a send, and when it last did. */
    data class SweepAttempts(val count: Int, val lastMs: Long) {
        companion object { val NONE = SweepAttempts(0, 0L) }
    }

    fun sweepAttempts(txid: String): SweepAttempts =
        SweepAttempts(prefs.getInt("$txid.sweeps", 0), prefs.getLong("$txid.sweptAt", 0L))

    fun noteSweepAttempt(txid: String, nowMs: Long) {
        prefs.edit()
            .putInt("$txid.sweeps", prefs.getInt("$txid.sweeps", 0) + 1)
            .putLong("$txid.sweptAt", nowMs)
            .apply()
    }

    /** Forget every recorded send. Used by the full chain rebuild, which discards the
     *  local transaction cache entirely and re-derives it from on-chain data.
     *  commit() (synchronous): the rebuild kills the process right after, so an async
     *  apply() could be dropped before it flushes. */
    fun clearAll() {
        prefs.edit().clear().commit()
    }

    /** Forget a recorded send (all of its keys). Used after a phantom double-spend
     *  is dropped so it isn't re-checked on the next launch. */
    fun remove(txid: String) {
        prefs.edit()
            .remove("$txid.sent")
            .remove("$txid.fee")
            .remove("$txid.to")
            .remove("$txid.self")
            .remove("$txid.dd")
            .remove("$txid.settled")
            .remove("$txid.sweeps")
            .remove("$txid.sweptAt")
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "dgb_outgoing_tx"

        /** The txids recorded among the store's keys: one `<txid>.sent` key per record. Pure. */
        fun recordedTxidsOf(keys: Collection<String>): Set<String> =
            keys.mapNotNullTo(HashSet()) { key ->
                if (key.endsWith(".sent")) key.removeSuffix(".sent") else null
            }

        /** The recorded txids the sweep still has to look after: recorded and not settled. Pure. */
        fun pendingTxidsOf(keys: Collection<String>): Set<String> {
            val settled = keys.mapNotNullTo(HashSet()) { key ->
                if (key.endsWith(".settled")) key.removeSuffix(".settled") else null
            }
            return recordedTxidsOf(keys).filterNotTo(HashSet()) { it in settled }
        }
    }
}
