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

    /**
     * What a DigiDollar address holds. Null means the lookup could not be MADE — never "holds
     * nothing".
     *
     * Separate from [fetchUtxos] because DigiDollar is invisible to it: a token output carries
     * zero satoshis and the reconcile endpoint filters zero-value outputs out of the UTXO set
     * entirely. Measured on mainnet — an address holding $1.00 answers "balance 0, utxo_count 0"
     * through the ordinary lookup.
     *
     * Default returns null so an implementation that predates DigiDollar reports "could not ask"
     * rather than silently claiming a wallet holds none.
     */
    suspend fun fetchDigiDollar(ddAddress: String): io.digibyte.core.reconcile.DigiDollarHoldingResult? = null

    /** A transaction's outputs, for locating the DigiDollar token output. Null when unreadable. */
    suspend fun fetchOutputs(txid: String): List<DigiDollarHolding.Output>? = null
}

/** First implementation: the existing reconcile backend (api.digiscope.me). */
class ReconcileBackendUtxoSource(
    private val nodeClient: DgbNodeClient,
) : UtxoSource {
    override suspend fun fetchUtxos(addresses: List<String>): ReconcileResult? =
        nodeClient.reconcileAddresses(addresses)

    override suspend fun fetchDigiDollar(ddAddress: String) =
        nodeClient.digiDollarHolding(ddAddress)

    /**
     * Parsed natively rather than in Kotlin. These bytes are remote-supplied, and
     * BRTransactionParse is the hardened parser every other raw-tx path already uses — a second
     * parser written here would be new attack surface for no benefit.
     */
    override suspend fun fetchOutputs(txid: String): List<DigiDollarHolding.Output>? {
        val raw = nodeClient.fetchRawTransaction(txid) ?: return null
        return io.digibyte.core.bridge.NativeBridge.getRawTransactionOutputs(raw)
            ?.mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size < 3) return@mapNotNull null
                val vout = parts[0].toIntOrNull() ?: return@mapNotNull null
                val sats = parts[1].toLongOrNull() ?: return@mapNotNull null
                DigiDollarHolding.Output(vout, sats, parts[2])
            }
    }
}
