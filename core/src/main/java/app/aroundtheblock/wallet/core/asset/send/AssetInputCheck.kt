package app.aroundtheblock.wallet.core.asset.send

/** One entry of an output's DigiAsset stack as the indexer reports it, in protocol order. */
data class StackEntry(val assetId: String, val count: Long)

/** What the DigiAsset indexer says about one output. */
sealed interface StackLookup {
    /** The output is unspent and carries exactly these entries, in order (empty = no assets). */
    data class Found(val entries: List<StackEntry>) : StackLookup
    /** The indexer does not hold this output as unspent. */
    data object NotUnspent : StackLookup
    /**
     * The indexer answered that it cannot say yet (its 503): it has not read as far as this output.
     * Never "holds no assets", never "spent": what the wallet already shows stays, nothing is
     * credited or spent on it, and it is asked again later.
     *
     * [indexSyncing] is the endpoint's own `syncing: true`; [blocksBehind] how far behind it says
     * the whole index is; [confirmations] the output's, when the reply carried them (an output
     * newer than the index's tip). Each is null when the reply did not carry a usable value.
     */
    data class NotYet(
        val indexSyncing: Boolean,
        val blocksBehind: Long? = null,
        val confirmations: Long? = null,
    ) : StackLookup
    /** The endpoint refused the request itself (its 400), or the wallet could tell it would: this
     *  outpoint is not one it can be asked about. Says nothing about any holding; not asked again. */
    data object Refused : StackLookup
    /** No answer could be had: offline, the indexer failing, a malformed reply. */
    data object Unavailable : StackLookup
}

/** Reads the DigiAsset stack of one output from an indexer. */
fun interface AssetStackSource {
    suspend fun stackOf(txid: String, vout: Int): StackLookup
}

/**
 * Whether an output may be spent as an asset input.
 *
 * The wallet stores one asset per output and names it from the transaction that created the
 * output. The protocol allows an output to carry several, in order, and a transfer moves units
 * from the top of each input's stack. An asset input is therefore spent only when the indexer
 * reports its whole stack as exactly one entry: the asset being sent, holding the quantity the
 * wallet recorded. Anything else, including no answer, keeps the output out of the send.
 */
object AssetInputCheck {

    enum class Verdict {
        /** The stack is exactly the expected asset at the recorded quantity. */
        VERIFIED,
        /** The indexer answered and the stack is anything else: never spent as this asset. */
        MISMATCH,
        /** No answer: nothing is spent on a guess. */
        UNAVAILABLE,
    }

    fun judge(lookup: StackLookup, expectedAssetId: String, recordedQuantity: Long): Verdict =
        when (lookup) {
            is StackLookup.Unavailable -> Verdict.UNAVAILABLE
            // "Cannot say yet" and "cannot be asked" vouch for nothing: nothing is spent on them.
            is StackLookup.NotYet -> Verdict.UNAVAILABLE
            is StackLookup.Refused -> Verdict.UNAVAILABLE
            is StackLookup.NotUnspent -> Verdict.MISMATCH
            is StackLookup.Found -> {
                val only = lookup.entries.singleOrNull()
                if (only != null &&
                    recordedQuantity > 0L &&
                    only.assetId == expectedAssetId &&
                    only.count == recordedQuantity
                ) Verdict.VERIFIED else Verdict.MISMATCH
            }
        }
}
