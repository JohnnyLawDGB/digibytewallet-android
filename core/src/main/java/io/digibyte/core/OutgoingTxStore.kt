package io.digibyte.core

import android.content.Context

/**
 * Records transactions that the user broadcast locally so the activity list
 * can categorize them as "Sent" even when the C wallet's amountSent lookup
 * misses (which happens when the parent UTXO transactions aren't in
 * BRWallet->allTx — e.g. wallets restored via Universal Restore's
 * UTXO-only scan, or after the bloom-fallback re-download path that
 * doesn't replay historical parents).
 *
 * Stored as a flat SharedPreferences map: each broadcast writes up to four
 * keys per txid: `<txid>.sent`, `<txid>.fee`, `<txid>.to`, `<txid>.self`.
 * Lookups return null when the txid isn't ours.
 */
class OutgoingTxStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** @param isSelfTransfer true when [toAddress] is an address THIS wallet
     *  owns (e.g. a legacy-funds sweep into our own receive address). Such a tx
     *  increases the balance and the C core categorizes it as a receive, so the
     *  activity list must NOT apply the outgoing "Sent" override to it — see
     *  [shouldApplyOutgoingOverride]. Genuine external sends store false. */
    data class Record(
        val sentSats: Long,
        val feeSats: Long,
        val toAddress: String,
        val isSelfTransfer: Boolean = false,
    )

    fun record(
        txid: String,
        sentSats: Long,
        feeSats: Long,
        toAddress: String,
        isSelfTransfer: Boolean = false,
    ) {
        prefs.edit()
            .putLong("$txid.sent", sentSats)
            .putLong("$txid.fee", feeSats)
            .putString("$txid.to", toAddress)
            .putBoolean("$txid.self", isSelfTransfer)
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
        )
    }

    /** All recorded send txids. Used at startup to re-fluff any that the wallet
     *  still sees as unconfirmed — a Dandelion stem killed mid-embargo (process
     *  death before the fluff timer fires) otherwise strands the tx forever. */
    fun allTxids(): Set<String> =
        prefs.all.keys.mapNotNull { key ->
            if (key.endsWith(".sent")) key.removeSuffix(".sent") else null
        }.toSet()

    /** Forget every recorded send. Used by the full chain rebuild, which discards the
     *  local transaction cache entirely and re-derives it from on-chain data.
     *  commit() (synchronous): the rebuild kills the process right after, so an async
     *  apply() could be dropped before it flushes. */
    fun clearAll() {
        prefs.edit().clear().commit()
    }

    /** Forget a recorded send (its three keys). Used after a phantom double-spend
     *  is dropped so it isn't re-checked on the next launch. */
    fun remove(txid: String) {
        prefs.edit()
            .remove("$txid.sent")
            .remove("$txid.fee")
            .remove("$txid.to")
            .remove("$txid.self")
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "dgb_outgoing_tx"

        /**
         * Whether the activity list should override a tx's amount with the
         * recorded outgoing (negative "Sent") values.
         *
         * The override exists to correct BRWalletAmountSentByTx under-counting
         * on genuine external sends (missing parent UTXOs → wrong/zero sent).
         * A SELF-transfer sweep (recovering legacy funds into our OWN wallet)
         * is the opposite case: it INCREASES the balance and the C core rightly
         * categorizes it as a receive. Forcing the outgoing override there would
         * render the recovery as a large negative "Sent" to our own address —
         * "the feature sent my money away". So: override external sends only;
         * let the C core's natural categorization stand for self-transfers.
         *
         * Pure (no Android/JNI) so the decision is unit-testable in isolation.
         */
        fun shouldApplyOutgoingOverride(record: Record?): Boolean =
            record != null && !record.isSelfTransfer
    }
}
