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

    override suspend fun fetchUtxos(addresses: List<String>): ReconcileResult? {
        lastQueried = addresses
        if (!reachable) return null
        val utxos = addresses.flatMap { byAddress[it]?.utxos.orEmpty() }
        val rawTxs = addresses.flatMap { byAddress[it]?.rawTxs?.entries.orEmpty() }
            .associate { it.key to it.value }
        return ReconcileResult(utxos, rawTxs, chainHeight = 0L)
    }
}
