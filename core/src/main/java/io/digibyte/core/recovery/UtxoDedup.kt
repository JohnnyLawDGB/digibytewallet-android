package io.digibyte.core.recovery

import io.digibyte.core.reconcile.UtxoEntry

/**
 * Collapses repeated mentions of one outpoint into a single coin.
 *
 * ## Why this exists
 *
 * The reconcile endpoint has been observed returning the same outpoint twice for a real address,
 * once confirmed and once at `h=0`:
 *
 *     6bd35c442896c27f:0   6000 sats   h=24106618
 *     6bd35c442896c27f:0   6000 sats   h=0
 *
 * Nothing downstream deduplicated it, which costs twice:
 *
 *  1. The recoverable balance double-counts — the wallet quotes coins that do not exist.
 *  2. The sweep builds the SAME input twice. Spending one outpoint twice in a transaction is
 *     invalid by consensus, so the whole sweep is rejected — and the failure surfaces far from
 *     the duplicated JSON row that caused it.
 *
 * A wallet cannot control what a backend sends. It can decline to build nonsense out of it.
 */
object UtxoDedup {

    /**
     * One entry per `(txid, vout)`, input order preserved.
     *
     * Where copies disagree the CONFIRMED one wins: `blockHeight == 0` means "not in a block
     * yet", and preferring it would describe a settled coin as pending, which can gate spending
     * it.
     */
    fun byOutpoint(utxos: List<UtxoEntry>): List<UtxoEntry> {
        if (utxos.size < 2) return utxos
        val best = LinkedHashMap<Pair<String, Int>, UtxoEntry>(utxos.size)
        for (u in utxos) {
            val key = u.txid to u.vout
            val existing = best[key]
            best[key] = when {
                existing == null -> u
                // Prefer a real height over 0; otherwise keep the first seen.
                existing.blockHeight <= 0L && u.blockHeight > 0L -> u
                else -> existing
            }
        }
        return best.values.toList()
    }
}
