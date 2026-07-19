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
    /** Optional — when wired, reconcile also refreshes the local utxos
     *  table's asset rows via AssetManager.refreshAssetUtxosFromNetwork
     *  after the tx-import loop finishes. Closes the gap where reconcile
     *  imports the raw tx but the Assets tab stayed empty because the
     *  utxos table never got is_asset=1 rows (processAssetUtxo had no
     *  callers pre-v3.5.27). */
    private val assetManager: io.digibyte.core.asset.AssetManager? = null,
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
            val historyTxsImported: Int = 0,
        ) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Run the full reconcile cycle. Called from Settings UI. */
    suspend fun reconcile(): State = withContext(Dispatchers.IO) {
        try {
            // Txid-driven confirmation-reconcile FIRST, independent of the
            // address/UTXO reconcile below (and its early-returns): promote any
            // wallet tx still stuck at TX_UNCONFIRMED to its real confirming
            // height. Recovers a stuck-"Pending" tx the UTXO reconcile can't
            // surface — e.g. a 0-value DigiDollar token output a dust filter
            // omits from scantxoutset. Non-fatal.
            _state.value = State.Scanning("Checking pending transactions…")
            val promotedPending = runCatching { confirmPendingTransactions() }.getOrDefault(0)
            if (promotedPending > 0) {
                android.util.Log.i("ChainReconciliation",
                    "confirmation-reconcile promoted $promotedPending stuck-pending tx(s)")
            }

            // Address-history backstop: import every tx touching the owned set
            // that the CF scan skipped (0-value DD, spent asset markers, taproot
            // — all four address types), each WITH its confirming height. Unlike
            // confirmPendingTransactions this recovers a tx that fell out of the
            // wallet's set entirely, not just one stuck at Pending. Non-fatal.
            _state.value = State.Scanning("Scanning address history…")
            val historyImported = runCatching { reconcileAddressHistory() }.getOrDefault(0)
            if (historyImported > 0) {
                android.util.Log.i("ChainReconciliation",
                    "address-history reconcile imported $historyImported tx(s)")
            }

            _state.value = State.Scanning("Listing wallet addresses…")
            val addrs = NativeBridge.dumpAllAddresses()
                .trim().lines().filter { it.isNotBlank() }
            if (addrs.isEmpty()) {
                val failed = State.Failed("No addresses available (wallet not loaded?)")
                _state.value = failed
                return@withContext failed
            }

            // Refresh the asset-utxo layer FIRST, independently of the main
            // reconcile. This is the listunspent-backed fast path that fills
            // the `utxos` table with is_asset=1 rows so the Assets tab renders
            // even if the main ElectrumX-backed reconcile endpoint is slow or
            // unreachable. Non-fatal if it fails.
            if (assetManager != null) {
                _state.value = State.Scanning("Refreshing asset holdings…", progress = 0.05f)
                runCatching { assetManager.refreshAssetUtxosFromNetwork() }
                    .onFailure { /* non-fatal */ }
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
                    historyTxsImported = historyImported,
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
                historyTxsImported = historyImported,
            )
            _state.value = done
            done
        } catch (t: Throwable) {
            val failed = State.Failed(t.message ?: t.javaClass.simpleName)
            _state.value = failed
            failed
        }
    }

    /**
     * Txid-driven confirmation-reconcile: for each wallet tx still at
     * TX_UNCONFIRMED, ask the node for its real confirming height ([DgbNodeClient.txConfirmation])
     * and promote it via [NativeBridge.confirmTransaction] (which re-runs the
     * balance update, releasing any withheld DigiDollar/asset credit). Driven by
     * the wallet's OWN pending list — not the node's UTXO set — so it recovers a
     * stuck-"Pending" tx the address/UTXO reconcile can't surface (e.g. a 0-value
     * DigiDollar token output). Returns the number of txs promoted.
     */
    suspend fun confirmPendingTransactions(): Int {
        var promoted = 0
        for (txid in pendingTxids()) {
            val conf = nodeClient.txConfirmation(txid) ?: continue
            if (NativeBridge.confirmTransaction(txid, conf.height, conf.time)) promoted++
        }
        return promoted
    }

    /**
     * Address-HISTORY reconcile (the backstop): enumerate the full owned
     * address set, ask the node for every tx touching each address, and
     * register the ones the wallet is missing — WITH their confirming height,
     * so BRWallet's dust-pending gate releases 0-value DigiDollar and spent
     * asset markers the UTXO reconcile cannot surface. History-based, not
     * UTXO-based. Recovers a tx that fell out of the CF scan set entirely
     * (which confirmPendingTransactions — driven by the wallet's own pending
     * list — cannot). Returns the count imported.
     */
    suspend fun reconcileAddressHistory(): Int {
        val addrs = NativeBridge.dumpAllAddresses().trim().lines().filter { it.isNotBlank() }
        if (addrs.isEmpty()) return 0
        val known = extractKnownTxids(NativeBridge.getTransactionDetails())
        _state.value = State.Scanning("Reading address history…", progress = 0.2f)
        // ONE batched POST per 500 addresses (not one GET per address) — stays
        // well under the server's request limiter that the per-address loop tripped.
        val history = nodeClient.addressHistoryBatch(addrs) ?: return 0
        val toImport = planHistoryImport(listOf(history), known)
        var imported = 0
        for ((i, tx) in toImport.withIndex()) {
            _state.value = State.Scanning(
                "Recovering tx ${i + 1}/${toImport.size}…",
                progress = 0.5f + (i + 1).toFloat() / toImport.size * 0.5f,
            )
            val raw = nodeClient.fetchRawTx(tx.txid, tx.height) ?: continue
            val bytes = runCatching { hexToBytes(raw.hex) }.getOrNull() ?: continue
            if (NativeBridge.registerRawTransaction(bytes, raw.blockHeight, raw.blockTime)) {
                imported++
                android.util.Log.i("ChainReconciliation", "history-recovered ${tx.txid} @${tx.height}")
            }
        }
        return imported
    }

    /** Txids of wallet transactions still unconfirmed — blockHeight is the
     *  TX_UNCONFIRMED sentinel (Int.MAX_VALUE), field 3 of a
     *  `txHash|amount|fee|blockHeight|timestamp|sent|received` line. */
    private fun pendingTxids(): List<String> =
        NativeBridge.getTransactionDetails().trim().lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.getOrNull(3)?.toLongOrNull() == Int.MAX_VALUE.toLong())
                parts.getOrNull(0)?.takeIf { it.isNotBlank() }
            else null
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

/** Known wallet txids = field 0 of each getTransactionDetails line
 *  (`txHash|amount|fee|blockHeight|timestamp|sent|received`). */
internal fun extractKnownTxids(details: String): Set<String> =
    details.trim().lines().mapNotNull { line ->
        line.split("|").getOrNull(0)?.trim()?.takeIf { it.isNotBlank() }
    }.toSet()

/** Flatten per-address histories, drop txids already in the wallet, dedup by
 *  txid keeping the highest confirming height. Order = first-seen among kept
 *  (deterministic for progress display + tests). */
internal fun planHistoryImport(
    histories: List<List<AddressTx>>,
    knownTxids: Set<String>,
): List<AddressTx> {
    val byTxid = LinkedHashMap<String, AddressTx>()
    for (history in histories) for (tx in history) {
        if (tx.txid in knownTxids) continue
        val existing = byTxid[tx.txid]
        if (existing == null || tx.height > existing.height) byTxid[tx.txid] = tx
    }
    return byTxid.values.toList()
}
