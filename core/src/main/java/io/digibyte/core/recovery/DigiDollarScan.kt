package io.digibyte.core.recovery

import io.digibyte.core.reconcile.DigiDollarHoldingResult

/**
 * What a foreign wallet holds in DigiDollar, assembled from three sources that fail independently.
 *
 * Finding the dollars takes a derived m/86' address, a balance from the DigiDollar endpoint, and
 * the transaction's outputs to locate the spendable outpoint. Any of the three can be missing, and
 * the difference between them matters:
 *
 *  - **A lookup that could not be made is not a zero balance.** Reading it as one tells someone
 *    their wallet is empty of dollars it may hold — the mistake `reachableBackend` guards against
 *    on the reconcile path.
 *  - **A known balance with no locatable outpoint is real money that cannot be moved.** "You hold
 *    $50 and we cannot move it" is a true and useful sentence; reporting it as "$50 recovered" or
 *    as "nothing found" would be a lie in opposite directions.
 *
 * Assembled per WALLET rather than per derivation profile: DigiDollar always lives at
 * m/86'/20'/0' no matter which profile the wallet's plain DGB sits on.
 *
 * Pure — the two lookups are injected, so the honesty rules above are testable without a network.
 */
object DigiDollarScan {

    /** A DigiDollar outpoint that can actually be spent. */
    data class Holding(
        val address: DigiDollarAddress,
        val txid: String,
        val vout: Int,
        val scriptPubKeyHex: String,
    )

    data class Result(
        /** Every cent found, whether or not it can be moved. */
        val cents: Long,
        /** The subset that has a spendable outpoint. */
        val holdings: List<Holding>,
        /** False when at least one address could not be asked about. Distinct from a zero balance. */
        val reachable: Boolean,
        /** Cents known to exist whose outpoint could not be located — reportable, not movable. */
        val unlocatableCents: Long,
    ) {
        val hasDollars: Boolean get() = cents > 0
        val movableCents: Long get() = cents - unlocatableCents
    }

    /**
     * @param addresses  the wallet's derived m/86' addresses, both encodings paired.
     * @param holdingFor the DigiDollar endpoint. Null means the lookup FAILED — not zero.
     * @param outputsFor a transaction's outputs, for locating the token output. Null means the
     *                   transaction could not be read.
     */
    suspend fun assemble(
        addresses: List<DigiDollarAddress>,
        holdingFor: suspend (DigiDollarAddress) -> DigiDollarHoldingResult?,
        outputsFor: suspend (String) -> List<DigiDollarHolding.Output>?,
    ): Result {
        var cents = 0L
        var unlocatable = 0L
        var reachable = true
        val holdings = mutableListOf<Holding>()

        for (address in addresses) {
            val holding = holdingFor(address)
            if (holding == null) {
                // Could not ask. Not an answer, and never counted as zero.
                reachable = false
                continue
            }
            if (holding.cents <= 0L) continue
            cents += holding.cents

            // The endpoint gives the balance and the transactions; the outpoint has to be found
            // inside one of them by matching the P2TR script against this address's key.
            var located = false
            for (txid in holding.txids) {
                val outputs = outputsFor(txid) ?: continue
                val outpoint = DigiDollarHolding.locate(outputs, address.taprootOutputKeyHex)
                    ?: continue
                holdings += Holding(address, txid, outpoint.vout, outpoint.scriptPubKeyHex)
                located = true
            }
            // Real money we can see and cannot spend. Carried separately so the UI can say so
            // rather than quietly reporting a smaller balance.
            if (!located) unlocatable += holding.cents
        }

        return Result(cents, holdings, reachable, unlocatable)
    }
}
