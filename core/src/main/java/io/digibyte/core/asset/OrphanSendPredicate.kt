package io.digibyte.core.asset

/**
 * Detects an **orphaned send**: an unconfirmed transaction of ours that spends an output the
 * wallet no longer has the parent for. It can never confirm and no peer will ever accept it,
 * because outside this device that output does not exist.
 *
 * ## Why this exists
 *
 * Observed live on 2026-08-20 (S25 Ultra, v4.0.39). An asset transfer sat "Pending" for
 * eleven minutes, was re-published on restart, reported `relays=6`, and never appeared in the
 * mempool of a node with `txindex` synced. Its parent was not on chain, and the wallet did not
 * have the parent's bytes either — its own log said `M3 walk[1]: no raw for 79b27063… — stop`.
 *
 * Every recovery path in the app missed it:
 *
 *  - [DeadSendPredicate] kept it: BRWallet reports the transaction VALID (it can only see
 *    local state, where the phantom parent once lived) and the 6000-sat asset marker sits
 *    above [DeadSendPredicate.DUST_FLOOR].
 *  - `SyncService.rebroadcastStrandedSends` re-published it indefinitely, because
 *    `publishTransaction` reports success whether or not the network took it.
 *  - Only `rebuildFromChainRescan` could clear it — a full deep re-sync to drop one row.
 *
 * ## Where orphans come from
 *
 * Most likely the wallet's own cleanup. `clearStuckSends` removes a stuck transaction but not
 * the transactions built on top of it, so clearing a phantom parent strands its children in
 * exactly this state. That makes this predicate a companion to the descendant sweep, not a
 * substitute for it.
 *
 * ## The safety direction
 *
 * Removing a transaction from the wallet is recoverable — if it really is live on the network,
 * the next sync re-detects it. Even so this fails SAFE: an unreadable or empty input list
 * means the wallet knows nothing about the parents, and condemning a send on absence of
 * evidence would remove a genuinely-broadcast transaction from view while it is still
 * propagating. Only a POSITIVE observation — inputs read, a named parent absent — is an orphan.
 */
object OrphanSendPredicate {

    /** One input's outpoint, as `getTransactionInputsForHash` reports it. */
    data class Input(val prevTxid: String, val prevVout: Int)

    /**
     * @param inputs the transaction's inputs; EMPTY means "could not read", never "no parents"
     *   — a signed transaction always has at least one input.
     * @param walletTxids every txid the wallet currently knows, in any confirmation state.
     */
    fun isOrphan(inputs: List<Input>, walletTxids: Set<String>): Boolean {
        if (inputs.isEmpty()) return false // unreadable — see "safety direction" above
        val known = walletTxids.mapTo(HashSet(walletTxids.size)) { it.lowercase() }
        return inputs.any { it.prevTxid.lowercase() !in known }
    }

    /**
     * Parse the JNI's `"prevTxidHex|prevVout"` rows. A malformed row is DROPPED rather than
     * coerced: a bogus parent would look missing and condemn a healthy send. Dropping every
     * row instead yields an empty list, which [isOrphan] treats as "cannot tell".
     */
    fun parseInputs(rows: Array<String>?): List<Input> {
        if (rows == null) return emptyList()
        val out = ArrayList<Input>(rows.size)
        for (row in rows) {
            val parts = row.split('|')
            if (parts.size < 2) return emptyList()
            val txid = parts[0].trim()
            val vout = parts[1].trim().toIntOrNull()
            if (txid.isEmpty() || vout == null || vout < 0) return emptyList()
            out.add(Input(txid, vout))
        }
        return out
    }
}
