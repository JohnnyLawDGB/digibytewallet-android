package io.digibyte.core.recovery

import io.digibyte.core.reconcile.DgbNodeClient
import io.digibyte.core.reconcile.ReconcileResult

/**
 * Pluggable "given these addresses, return their UTXOs (+ raw txs)" lookup.
 * Decouples the recovery scan from any one backend so we can (a) unit-test the
 * classify pipeline with a fake and (b) add multi-Electrum fallback later
 * without touching scan/sweep logic. Returns null to mean "lookup failed /
 * unreachable" — distinct from a successful empty result.
 */
interface UtxoSource {
    suspend fun fetchUtxos(addresses: List<String>): ReconcileResult?
}

/** First implementation: the existing reconcile backend (api.digiscope.me). */
class ReconcileBackendUtxoSource(
    private val nodeClient: DgbNodeClient,
) : UtxoSource {
    override suspend fun fetchUtxos(addresses: List<String>): ReconcileResult? =
        nodeClient.reconcileAddresses(addresses)
}
