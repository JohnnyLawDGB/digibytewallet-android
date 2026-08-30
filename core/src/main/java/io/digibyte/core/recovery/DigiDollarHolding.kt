package io.digibyte.core.recovery

/**
 * A foreign wallet's DigiDollar: locating the outpoint that holds it, and describing it.
 *
 * ## Why the ordinary lookup cannot find this
 *
 * A DigiDollar token output carries ZERO satoshis. The cents live in the transaction's OP_RETURN,
 * not in the output amount, and the reconcile endpoint filters zero-value outputs out of the UTXO
 * set entirely.
 *
 * Measured on mainnet: a taproot address holding $1.00 answers `balance 0, utxo_count 0` through
 * the ordinary lookup, while the backend's DigiDollar endpoint reports `dd_balance_cents: 100`
 * for the same key. So a scan that derived the right addresses would still conclude the wallet
 * held nothing — the derivation was never the blocker, the lookup is.
 *
 * ## Balance is enough to report; an outpoint is needed to move
 *
 * The DigiDollar endpoint gives cents and a txid. Spending needs txid AND vout AND the
 * scriptPubKey, so the transaction's outputs are fetched and the one paying our taproot output
 * key is located here.
 *
 * Pure — no network, no JNI. The caller supplies the outputs and the derived key.
 */
object DigiDollarHolding {

    /** One parsed output of a transaction, as `getRawTransactionOutputs` returns them. */
    data class Output(val vout: Int, val amountSat: Long, val scriptPubKeyHex: String)

    /** A spendable DigiDollar outpoint. */
    data class Outpoint(val vout: Int, val scriptPubKeyHex: String)

    /**
     * A reference to a previous output, i.e. one transaction input.
     *
     * Distinct from [Outpoint], which identifies an output WITHIN the transaction being read.
     * This one points OUT of it, and is what proves a located output has already been spent.
     */
    data class PrevOut(val txid: String, val vout: Int)

    /** A P2TR scriptPubKey is `OP_1 <push32> X(Q)` — 0x51 0x20 then the 32-byte output key. */
    private const val P2TR_PREFIX = "5120"

    /**
     * The output that holds this wallet's DigiDollar, or null.
     *
     * Two conditions, and both matter:
     *
     *  - the script is P2TR paying [taprootOutputKeyHex]. Matching on value alone would select the
     *    OP_RETURN, which is also zero-value and sits in every DD transaction.
     *  - the value is zero. A P2TR output at the same key carrying satoshis is ordinary DGB, not
     *    dollars, and spending it as a DD input would misstate the transaction's value.
     */
    fun locate(outputs: List<Output>, taprootOutputKeyHex: String): Outpoint? {
        val want = (P2TR_PREFIX + taprootOutputKeyHex).lowercase()
        return outputs
            .firstOrNull { it.amountSat == 0L && it.scriptPubKeyHex.lowercase() == want }
            ?.let { Outpoint(it.vout, it.scriptPubKeyHex) }
    }

    /** Whether there is anything to report. Zero cents is no dollars, not unseen dollars. */
    fun isHolding(cents: Long): Boolean = cents > 0

    /** Cents as dollars, for telling someone what their wallet holds. */
    fun formatCents(cents: Long): String {
        val whole = cents / 100
        val part = (cents % 100).toInt()
        return "$" + String.format("%,d.%02d", whole, part)
    }
}
