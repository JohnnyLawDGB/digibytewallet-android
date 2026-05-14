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
 * Stored as a flat SharedPreferences map: each broadcast writes three
 * keys per txid: `<txid>.sent`, `<txid>.fee`, `<txid>.to`. Lookups
 * return null when the txid isn't ours.
 */
class OutgoingTxStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Record(val sentSats: Long, val feeSats: Long, val toAddress: String)

    fun record(txid: String, sentSats: Long, feeSats: Long, toAddress: String) {
        prefs.edit()
            .putLong("$txid.sent", sentSats)
            .putLong("$txid.fee", feeSats)
            .putString("$txid.to", toAddress)
            .apply()
    }

    fun lookup(txid: String): Record? {
        val sent = prefs.getLong("$txid.sent", -1L)
        if (sent < 0L) return null
        return Record(
            sentSats = sent,
            feeSats = prefs.getLong("$txid.fee", 0L),
            toAddress = prefs.getString("$txid.to", "") ?: ""
        )
    }

    companion object {
        private const val PREFS_NAME = "dgb_outgoing_tx"
    }
}
