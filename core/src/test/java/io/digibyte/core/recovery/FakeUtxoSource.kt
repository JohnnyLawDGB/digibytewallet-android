package io.digibyte.core.recovery

import io.digibyte.core.reconcile.ReconcileResult

/**
 * Deterministic UtxoSource for tests. Maps each queried address to a canned
 * ReconcileResult; addresses with no entry contribute nothing. When
 * reachable=false, every call returns null (simulates backend down).
 */
class FakeUtxoSource(
    private val byAddress: Map<String, ReconcileResult>,
    private val reachable: Boolean = true,
) : UtxoSource {
    var lastQueried: List<String>? = null
        private set

    /** Number of times [fetchUtxos] actually reached the (fake) backend.
     *  Used by the #8 dedupe test to prove a repeated classify serves cache. */
    var fetchCount = 0
        private set

    override suspend fun fetchUtxos(addresses: List<String>): ReconcileResult? {
        fetchCount++
        lastQueried = addresses
        if (!reachable) return null
        val utxos = addresses.flatMap { byAddress[it]?.utxos.orEmpty() }
        val rawTxs = addresses.flatMap { byAddress[it]?.rawTxs?.entries.orEmpty() }
            .associate { it.key to it.value }
        val chainHeight = addresses.mapNotNull { byAddress[it]?.chainHeight }.maxOrNull() ?: 0L
        return ReconcileResult(utxos, rawTxs, chainHeight)
    }
}
