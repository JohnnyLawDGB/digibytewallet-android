package app.aroundtheblock.wallet.core.recovery

import app.aroundtheblock.wallet.core.reconcile.DgbNodeClient
import app.aroundtheblock.wallet.core.reconcile.ReconcileResult

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
    suspend fun fetchDigiDollar(ddAddress: String): app.aroundtheblock.wallet.core.reconcile.DigiDollarHoldingResult? = null

    /** A transaction's outputs, for locating the DigiDollar token output. Null when unreadable. */
    suspend fun fetchOutputs(txid: String): List<DigiDollarHolding.Output>? = null

    /**
     * What a transaction SPENDS. Null when unreadable — which proves nothing, and must never be
     * read as "spends nothing".
     *
     * Needed because the DigiDollar endpoint lists spends alongside receives under one live
     * balance: without this, a receive whose token output is already gone still locates, and the
     * transfer built from it spends an input that no longer exists.
     */
    suspend fun fetchInputs(txid: String): List<DigiDollarHolding.PrevOut>? = null

    /** A transaction's nVersion, or null when unreadable (ONE-FAMILY's DigiDollar marker). */
    suspend fun fetchVersion(txid: String): Int? = null
}

/**
 * First implementation: the existing reconcile backend (api.digiscope.me).
 *
 * DD-LOCATOR-BIND: every raw transaction this reads for the DigiDollar locator is bound to the
 * transaction id it asked for. [boundRawTransaction] is the only place raw bytes enter; it hands
 * them on only when their own id (computed from the bytes by [txidOf], the function every other
 * bound raw-transaction path uses) equals the requested txid. Anything else is "could not be
 * read" (null), never "no such output", "spends nothing" or a version.
 */
class ReconcileBackendUtxoSource internal constructor(
    private val nodeClient: DgbNodeClient,
    /** The id of raw transaction bytes, or null when they do not parse. */
    private val txidOf: (ByteArray) -> String?,
    /** "vout|sats|scriptHex" per output, or null when the bytes do not parse. */
    private val outputLines: (ByteArray) -> Array<String>?,
    /** "prevTxid|prevVout" per input, or null when the bytes do not parse. */
    private val inputLines: (ByteArray) -> Array<String>?,
) : UtxoSource {

    /**
     * Production wiring. Parsed natively rather than in Kotlin: these bytes are remote-supplied,
     * and the native parser is the hardened one every other raw-tx path already uses — a second
     * parser written here would be new attack surface for no benefit.
     */
    constructor(nodeClient: DgbNodeClient) : this(
        nodeClient = nodeClient,
        txidOf = RawTxBinding.transactionId,
        outputLines = { app.aroundtheblock.wallet.core.bridge.NativeBridge.getRawTransactionOutputs(it) },
        inputLines = { app.aroundtheblock.wallet.core.bridge.NativeBridge.getRawTransactionInputs(it) },
    )

    override suspend fun fetchUtxos(addresses: List<String>): ReconcileResult? =
        nodeClient.reconcileAddresses(addresses)

    override suspend fun fetchDigiDollar(ddAddress: String) =
        nodeClient.digiDollarHolding(ddAddress)

    /**
     * The bytes of transaction [txid], or null when they could not be read. Bytes whose own id is
     * not [txid] (or that have no id) were not read: they are some other answer.
     */
    private suspend fun boundRawTransaction(txid: String): ByteArray? {
        val raw = nodeClient.fetchRawTransaction(txid) ?: return null
        val id = runCatching { txidOf(raw) }.getOrNull() ?: return null
        return raw.takeIf { id.equals(txid, ignoreCase = true) }
    }

    override suspend fun fetchOutputs(txid: String): List<DigiDollarHolding.Output>? {
        val raw = boundRawTransaction(txid) ?: return null
        return outputLines(raw)
            ?.mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size < 3) return@mapNotNull null
                val vout = parts[0].toIntOrNull() ?: return@mapNotNull null
                val sats = parts[1].toLongOrNull() ?: return@mapNotNull null
                DigiDollarHolding.Output(vout, sats, parts[2])
            }
    }

    override suspend fun fetchVersion(txid: String): Int? {
        val raw = boundRawTransaction(txid) ?: return null
        return app.aroundtheblock.wallet.core.asset.OneFamily.rawTxVersion(raw)
    }

    /** Same native parser, reading the other end of the transaction. */
    override suspend fun fetchInputs(txid: String): List<DigiDollarHolding.PrevOut>? {
        val raw = boundRawTransaction(txid) ?: return null
        return inputLines(raw)
            ?.mapNotNull { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size < 2) return@mapNotNull null
                val vout = parts[1].toIntOrNull() ?: return@mapNotNull null
                DigiDollarHolding.PrevOut(parts[0], vout)
            }
    }
}
