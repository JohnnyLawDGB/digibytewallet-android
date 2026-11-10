package app.aroundtheblock.wallet.core.recovery

import app.aroundtheblock.wallet.core.reconcile.DigiDollarHoldingResult

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
     * @param inputsFor  a transaction's inputs, for proving a located output already spent. Null
     *                   means unreadable, which proves nothing and drops nothing.
     * @param versionFor a transaction's nVersion, for ONE-FAMILY's DigiDollar marker. Null means
     *                   unknown; the "DD" OP_RETURN still marks the transaction.
     */
    suspend fun assemble(
        addresses: List<DigiDollarAddress>,
        holdingFor: suspend (DigiDollarAddress) -> DigiDollarHoldingResult?,
        outputsFor: suspend (String) -> List<DigiDollarHolding.Output>?,
        inputsFor: suspend (String) -> List<DigiDollarHolding.PrevOut>? = { emptyList() },
        versionFor: suspend (String) -> Int? = { null },
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

            // The endpoint lists EVERY transaction that touched this address — the spends as
            // well as the receives — and reports only the live balance beside them. So a receive
            // whose output is already gone still appears, and locating in it would claim a
            // spendable dollar that no longer exists. Reading the inputs of the same listed
            // transactions is what tells them apart: a spend of this address's output is itself
            // one of those transactions, which is the only reason the spend is listed at all.
            //
            // Measured on mainnet: DD1XZWMK…UTHDiV answered `dd_balance_cents 200,
            // unspent_count 2` while listing four transactions, three of which pay its taproot
            // key. Without this, three outpoints would be claimed for two dollars and the
            // transfer would spend an input that does not exist.
            val spent = HashSet<DigiDollarHolding.PrevOut>()
            for (txid in holding.txids) {
                // Null proves nothing. An unreadable transaction must not cost the user a dollar
                // we CAN see, so it drops nothing — worst case the stale outpoint survives and
                // the transfer is rejected, which is reported rather than silent.
                inputsFor(txid)?.let { spent += it }
            }

            // The outpoint has to be found inside one of the listed transactions by matching the
            // P2TR script against this address's key.
            val found = mutableListOf<Holding>()
            var unreadable = false
            // ONE-FAMILY, per transaction: the still-unspent outpoints this address holds in a
            // transaction marked both DigiDollar and DigiAsset are held from every send, this one
            // included. They are counted (heldCount) and their cents, read from that transaction's own
            // DD OP_RETURN, are reported as unlocatable. Nothing else of the address is affected: a
            // spent or spend-only dual transaction locates nothing and is passed over.
            var heldCount = 0
            var heldCents = 0L
            var heldUndecodable = false
            for (txid in holding.txids) {
                val outputs = outputsFor(txid)
                if (outputs == null) { unreadable = true; continue }
                val live = DigiDollarHolding.locateAll(outputs, address.taprootOutputKeyHex)
                    .filter { DigiDollarHolding.PrevOut(txid, it.vout) !in spent }
                if (live.isEmpty()) continue
                if (isDualFamily(outputs, versionFor(txid))) {
                    heldCount += live.size
                    for (o in live) {
                        val c = DigiDollarHolding.outputCents(outputs, o.vout)
                        if (c == null || c <= 0L) heldUndecodable = true
                        else heldCents = app.aroundtheblock.wallet.core.asset.AssetSums.add(heldCents, c)
                    }
                    continue
                }
                for (outpoint in live) found += Holding(address, txid, outpoint.vout, outpoint.scriptPubKeyHex)
            }
            // Movable only when every listed transaction was read (4.1.2 row 11, N6). The cents an
            // outpoint carries are in its transaction's OP_RETURN, not here: with one transaction
            // unread, the outpoints found carry some unknown part of the balance, and counting the
            // whole balance as movable would plan a transfer of dollars it cannot spend. So the
            // address is then unlocatable as a whole -- reported, not moved.
            //
            // Real money we can see and cannot spend. Carried separately so the UI can say so
            // rather than quietly reporting a smaller balance.
            // And only when what was found is what the endpoint counts as unspent, where it says (an
            // absent count reads 0): one outpoint more or fewer means the cents found are not the
            // balance, and a transfer of the balance would spend what is not there (4.1.2 row 20).
            // Held outpoints count toward the endpoint's unspent count like any other. Their cents
            // must be decodable and no more than the balance, or nothing found can be trusted to be
            // the rest of it: the address is then unlocatable as a whole.
            val countDisagrees = holding.unspentCount > 0 && found.size + heldCount != holding.unspentCount
            if (found.isEmpty() || unreadable || countDisagrees || heldUndecodable || heldCents >= holding.cents) {
                unlocatable += holding.cents
            } else {
                holdings += found
                unlocatable += heldCents
            }
        }

        return Result(cents, holdings, reachable, unlocatable)
    }

    private val decoder = app.aroundtheblock.wallet.core.asset.DigiAssetDecoder()

    /** ONE-FAMILY's [app.aroundtheblock.wallet.core.asset.OneFamily.isDualFamily] over these outputs and
     *  the transaction's nVersion when known. A script that is not hex is read as held. */
    private fun isDualFamily(outputs: List<DigiDollarHolding.Output>, version: Int?): Boolean {
        val scripts = outputs.sortedBy { it.vout }.map { o ->
            DigiDollarHolding.hexBytes(o.scriptPubKeyHex) ?: return true
        }
        return app.aroundtheblock.wallet.core.asset.OneFamily.isDualFamily(
            scripts, decoder, version?.let(app.aroundtheblock.wallet.core.asset.OneFamily::versionMarksDigiDollar),
        )
    }
}
