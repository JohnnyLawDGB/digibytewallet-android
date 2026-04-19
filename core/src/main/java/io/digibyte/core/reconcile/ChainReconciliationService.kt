package io.digibyte.core.reconcile

import io.digibyte.core.bridge.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Orchestrates node-verified balance reconciliation for the wallet.
 *
 * This is Path B of the "missing funds" fix: when the SPV bloom scan fails
 * to deliver merkleblocks for UTXOs on addresses the wallet already knows
 * about (stale peers, peer-side merkleblock drops, rescan no-op), the user
 * triggers reconciliation from Settings and we:
 *
 *   1. Dump the wallet's complete address set via JNI.
 *   2. Ask a DigiByte full node (default: digiscope.me) which UTXOs actually
 *      exist on those addresses.
 *   3. For each UTXO the wallet doesn't already have, fetch the parent tx's
 *      raw hex + block metadata and register it into BRWallet via JNI.
 *
 * Merkle-proof verification is deferred to v1.1 — for v1 the node is trusted
 * (it's cert-pinned to api.digiscope.me, or user-configured to their own
 * node). Once SPV headers are reliably on-device we'll upgrade to merkle
 * path verification so the reconcile is trust-minimized.
 */
class ChainReconciliationService(
    private val nodeClient: DgbNodeClient,
) {

    sealed class State {
        object Idle : State()
        data class Scanning(val stage: String, val progress: Float = 0f) : State()
        data class Done(
            val scannedAddresses: Int,
            val utxosSeenOnChain: Int,
            val txsImported: Int,
            val alreadyKnown: Int,
            val totalChainBalanceSat: Long,
        ) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Run the full reconcile cycle. Called from Settings UI. */
    suspend fun reconcile(): State = withContext(Dispatchers.IO) {
        try {
            _state.value = State.Scanning("Listing wallet addresses…")
            val addrs = NativeBridge.dumpAllAddresses()
                .trim().lines().filter { it.isNotBlank() }
            if (addrs.isEmpty()) {
                val failed = State.Failed("No addresses available (wallet not loaded?)")
                _state.value = failed
                return@withContext failed
            }

            _state.value = State.Scanning("Querying node for UTXOs on ${addrs.size} addresses…")
            val result = nodeClient.reconcileAddresses(addrs)
            if (result == null) {
                val failed = State.Failed("Node request failed — check network or endpoint")
                _state.value = failed
                return@withContext failed
            }

            if (result.utxos.isEmpty()) {
                val done = State.Done(
                    scannedAddresses = addrs.size,
                    utxosSeenOnChain = 0,
                    txsImported = 0,
                    alreadyKnown = 0,
                    totalChainBalanceSat = 0L,
                )
                _state.value = done
                return@withContext done
            }

            // Register every tx the backend returned, not just the parents
            // of current UTXOs. The backend's ElectrumX history includes
            // *spending* txs too — without registering those, BRWallet never
            // learns that a UTXO it thinks is unspent was already consumed
            // in a block the bloom scan missed, and displays a balance
            // higher than the real on-chain state forever. Reproduced
            // 2026-04-19: wallet showed 1.999887 DGB after the user spent
            // most of it; real balance was 0.045 because the spending tx's
            // merkleblock was never delivered to this SPV client.
            val uniqueTxids = result.rawTxs.keys.toList()
            var imported = 0
            var alreadyKnown = 0
            val totalBalance = result.utxos.sumOf { it.amountSatoshi }

            for ((idx, txid) in uniqueTxids.withIndex()) {
                _state.value = State.Scanning(
                    stage = "Importing tx ${idx + 1}/${uniqueTxids.size}",
                    progress = (idx + 1).toFloat() / uniqueTxids.size,
                )
                val rawTx = result.rawTxs[txid] ?: continue
                val rawBytes = runCatching { hexToBytes(rawTx.hex) }.getOrNull() ?: continue
                val ok = NativeBridge.registerRawTransaction(
                    rawBytes, rawTx.blockHeight, rawTx.blockTime
                )
                if (ok) imported++ else alreadyKnown++
            }

            val done = State.Done(
                scannedAddresses = addrs.size,
                utxosSeenOnChain = result.utxos.size,
                txsImported = imported,
                alreadyKnown = alreadyKnown,
                totalChainBalanceSat = totalBalance,
            )
            _state.value = done
            done
        } catch (t: Throwable) {
            val failed = State.Failed(t.message ?: t.javaClass.simpleName)
            _state.value = failed
            failed
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().removePrefix("0x")
        require(clean.length % 2 == 0) { "hex string must have even length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex char at $i" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
