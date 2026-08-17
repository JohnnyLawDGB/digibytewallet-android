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
    /** Optional — when wired, a reconcile whose address-history pass actually
     *  COVERED the owned set sets the `abandonedBandRecovered` signal
     *  ([CfAbandonmentStore.markRecovered]), clearing the abandoned-band banner and
     *  letting the wallet reach "Synced" again. Nullable + defaulted so existing
     *  pure-JVM constructions keep compiling; without it the reconcile still works,
     *  it just can't clear the surfacing. */
    private val appContext: android.content.Context? = null,
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
            // GATE 3 (paced-convoy fetch, spec Part E): the address-history pass is
            // the CF-INDEPENDENT one — it enumerates the whole owned address set and
            // imports every tx the node reports touching it, at ANY height, with its
            // confirming block. That, and only that, is what covers a compact-filter
            // band the B2 valve abandoned. The UTXO half below cannot: it only
            // surfaces outputs that are still unspent. So the recovered signal is
            // gated on [lastHistoryPassCovered] — a pass that reached the node AND
            // fetched every tx it planned to import — not on the reconcile returning
            // Done. Marking recovered on a partial pass would clear the banner and
            // flip the wallet to "Synced" over history that is still missing, which
            // is precisely the silent loss the valve is only tolerable without.
            if (lastHistoryPassCovered) markAbandonedBandRecovered()

            _state.value = State.Scanning("Listing wallet addresses…")
            val addrs = NativeBridge.dumpAllAddresses()
                .trim().lines().filter { it.isNotBlank() }
            if (addrs.isEmpty()) {
                val failed = State.Failed("No addresses available (wallet not loaded?)")
                _state.value = failed
                return@withContext failed
            }

            // Tidy the asset rows FIRST, independently of the main reconcile: drop
            // phantoms at addresses we don't own, and re-derive spent-state from the
            // native wallet. Local only — the listunspent-backed variant this replaced
            // POSTed the whole address set to an indexer (and had been 404ing besides).
            // Non-fatal if it fails.
            if (assetManager != null) {
                _state.value = State.Scanning("Tidying asset holdings…", progress = 0.05f)
                runCatching { assetManager.reconcileAssetRowsLocally() }
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

    /** Run the local asset-row tidy-up if an AssetManager is wired in. Local only —
     *  ownership from the native address set, spent-state from the native wallet. */
    suspend fun reconcileAssetRowsLocallyIfPresent() {
        assetManager?.let { runCatching { it.reconcileAssetRowsLocally() } }
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
        lastHistoryPassCovered = false
        val addrs = NativeBridge.dumpAllAddresses().trim().lines().filter { it.isNotBlank() }
        if (addrs.isEmpty()) return 0
        // UNCAPPED known set — load-bearing for the GATE-3 coverage proof, see
        // [knownTxidsForHistoryPlan]. getTransactionDetails() alone caps at the 100
        // most-recent txs, which would let a >100-tx wallet's older confirmed txids
        // ride into the import plan as duplicates and permanently sink `covered`.
        val allHashes = runCatching { NativeBridge.getAllTransactionHashes() }
            .onFailure { android.util.Log.w("ChainReconciliation", "getAllTransactionHashes threw", it) }
            .getOrNull()
        val details = NativeBridge.getTransactionDetails()
        if (isCappedKnownSetFallback(allHashes, details)) {
            // NEVER let this degrade silently — see [isCappedKnownSetFallback].
            android.util.Log.w("ChainReconciliation",
                "getAllTransactionHashes unavailable — falling back to the 100-CAPPED " +
                    "known set. On a wallet with >100 transactions the older confirmed " +
                    "txids will ride into the import plan as duplicates and sink the " +
                    "coverage proof, so an abandoned CF band can never be marked " +
                    "recovered (banner nags, Synced withheld) until this recovers.")
        }
        val known = knownTxidsForHistoryPlan(allHashes, details)
        _state.value = State.Scanning("Reading address history…", progress = 0.2f)
        // ONE batched POST per 500 addresses (not one GET per address) — stays
        // well under the server's request limiter that the per-address loop tripped.
        val history = nodeClient.addressHistoryBatch(addrs) ?: return 0
        val toImport = planHistoryImport(listOf(history), known)
        val outcome = importPlannedHistory(
            toImport = toImport,
            fetch = { tx -> nodeClient.fetchRawTx(tx.txid, tx.height) },
            decodeHex = { hex -> runCatching { hexToBytes(hex) }.getOrNull() },
            register = { bytes, h, t -> NativeBridge.registerRawTransaction(bytes, h, t) },
            onProgress = { i, total ->
                _state.value = State.Scanning(
                    "Recovering tx ${i + 1}/$total…",
                    progress = 0.5f + (i + 1).toFloat() / total * 0.5f,
                )
            },
            onImported = { tx ->
                android.util.Log.i("ChainReconciliation", "history-recovered ${tx.txid} @${tx.height}")
            },
        )
        // Coverage means: the node answered for the whole owned address set AND every
        // tx we planned to import actually landed in the wallet. Anything less and we
        // cannot claim an abandoned band was re-covered.
        lastHistoryPassCovered = outcome.covered
        if (!outcome.covered) {
            android.util.Log.w("ChainReconciliation",
                "address-history pass did NOT cover the owned set " +
                    "(${outcome.unrecovered}/${toImport.size} tx unrecovered) — " +
                    "an abandoned CF band must stay surfaced")
        }
        return outcome.imported
    }

    /** Set by [reconcileAddressHistory]: did that pass actually reach the node for
     *  the whole owned address set and retrieve everything it planned to import? */
    @Volatile private var lastHistoryPassCovered = false

    /** Record the `abandonedBandRecovered` signal — see [CfAbandonmentStore]. */
    private fun markAbandonedBandRecovered() {
        val ctx = appContext ?: return
        val cleared = runCatching {
            io.digibyte.core.sync.CfAbandonmentStore.markRecovered(ctx)
        }.getOrDefault(false)
        if (cleared) {
            android.util.Log.i("ChainReconciliation",
                "abandoned CF band recovered by node reconcile — banner cleared, Synced unblocked")
        }
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

/**
 * Outcome of the address-history import loop.
 *
 * [covered] is the GATE-3 predicate: did this pass actually put every tx the node
 * reported for the owned address set into the wallet? Only a covered pass may set
 * the `abandonedBandRecovered` signal — clearing the abandoned-band banner while a
 * planned tx is still missing is exactly the silent loss the B2 valve is only
 * tolerable without.
 */
internal data class HistoryImportOutcome(val imported: Int, val unrecovered: Int) {
    val covered: Boolean get() = unrecovered == 0
}

/**
 * Fetch → decode → register each planned tx, classifying every per-tx outcome as
 * recovered or NOT recovered.
 *
 * Extracted from [ChainReconciliationService.reconcileAddressHistory] purely so the
 * classification is testable on the host JVM — the real loop's collaborators are
 * `NativeBridge` (JNI, unmockable off-device) and an HTTP client.
 */
internal suspend fun importPlannedHistory(
    toImport: List<AddressTx>,
    fetch: suspend (AddressTx) -> RawTxEntry?,
    decodeHex: (String) -> ByteArray?,
    register: (ByteArray, Long, Long) -> Boolean,
    onProgress: (index: Int, total: Int) -> Unit = { _, _ -> },
    onImported: (AddressTx) -> Unit = { },
): HistoryImportOutcome {
    var imported = 0
    var unrecovered = 0
    for ((i, tx) in toImport.withIndex()) {
        onProgress(i, toImport.size)
        val raw = fetch(tx)
        if (raw == null) { unrecovered++; continue }
        val bytes = decodeHex(raw.hex)
        if (bytes == null) { unrecovered++; continue }
        if (register(bytes, raw.blockHeight, raw.blockTime)) {
            imported++
            onImported(tx)
        } else {
            // A `false` here is a FAILURE, not "already known" — but that is true
            // only BECAUSE of a coupling that is easy to break, so read this before
            // changing either side.
            //
            // registerRawTransaction returns JNI_FALSE for five reasons
            // (jni_transaction.c:372-434): null wallet, BRTransactionParse failure,
            // unsigned tx, BRWalletRegisterTransaction rejecting it as not
            // associated with the wallet — and a DUPLICATE that has nothing to
            // promote (:418). The first four mean the tx did not land, so the pass
            // has not covered the band and must not clear the surfacing.
            //
            // The duplicate branch is unreachable HERE, and only here, because the
            // caller planned this list against the UNCAPPED known set built by
            // [knownTxidsForHistoryPlan] — so every wallet-known txid was already
            // dropped by planHistoryImport and BRWalletTransactionForHash cannot
            // hit. ⚠️ If that known set is ever narrowed back to the 100-capped
            // getTransactionDetails(), a >100-tx wallet's older confirmed txids
            // start arriving here as legitimate duplicates, each counted as a
            // failure, and `covered` becomes unreachable — the abandoned-band banner
            // would then nag forever on exactly the deep wallets that need the
            // reconcile most.
            unrecovered++
        }
    }
    return HistoryImportOutcome(imported, unrecovered)
}

/**
 * The set of wallet-known txids used to plan an address-history import.
 *
 * **This MUST come from the UNCAPPED accessor**, and the GATE-3 coverage predicate
 * depends on it (see [importPlannedHistory]'s `register == false` branch).
 * [NativeBridge.getAllTransactionHashes] walks the whole `BRWalletTransactions`
 * array with no truncation (`jni_wallet.c:733`), whereas
 * [NativeBridge.getTransactionDetails] keeps only the 100 MOST RECENT
 * (`jni_wallet.c:781`, `startIdx = txCount - 100`) while
 * [DgbNodeClient.addressHistoryBatch] returns the FULL unbounded per-address
 * history. Planning against the capped source therefore lets every already-confirmed
 * txid older than the cap survive into `toImport` on any wallet with >100 txs; each
 * then returns `JNI_FALSE` from `registerRawTransaction`'s duplicate branch
 * (`jni_transaction.c:418`) and sinks the coverage proof — so a reconcile that
 * genuinely closed an abandoned band could never say so, and the banner would nag
 * forever. Old, deep wallets are exactly the ones with both >100 txs and deep bands.
 *
 * [details] is unioned in purely defensively (it is a strict subset of [allHashes]
 * when the array is present, and the fallback when it is null because no wallet is
 * loaded). The union direction is always safe: both sources are derived from the
 * wallet's own transaction list, so anything in either genuinely IS wallet-known,
 * and over-dropping from `toImport` can only skip a re-import that would have been
 * a no-op duplicate.
 */
internal fun knownTxidsForHistoryPlan(allHashes: Array<out String?>?, details: String): Set<String> {
    // Elements are nullable despite the JNI declaration's `Array<String>`:
    // jni_wallet.c:729 returns a NON-null array of txCount NULL slots when the
    // BRTransaction** malloc fails, and a failed NewStringUTF leaves an individual
    // slot NULL. A bare `it.trim()` throws NPE straight out of
    // reconcileAddressHistory() — OOM-only, but this sits on the GATE-3 path.
    val fromNative = allHashes
        ?.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        ?: emptyList()
    return fromNative.toSet() + extractKnownTxids(details)
}

/**
 * Did the uncapped known set fail to materialise, leaving the plan to fall back to
 * the 100-capped [extractKnownTxids]?
 *
 * That fallback SILENTLY reinstates the exact regression the uncapped accessor
 * exists to prevent: on a wallet with >100 txs the older confirmed txids ride into
 * `toImport` as duplicates, every one counts against coverage, `covered` becomes
 * unreachable, and the abandoned-band banner nags forever with Synced withheld —
 * with nothing in logcat to explain it. Callers MUST warn on this, naming the
 * consequence, so a field logcat is diagnosable.
 *
 * Only meaningful when the wallet actually has transactions: a null array with a
 * blank `details` just means no wallet is loaded, which is not a degradation.
 */
internal fun isCappedKnownSetFallback(allHashes: Array<out String?>?, details: String): Boolean =
    allHashes == null && details.isNotBlank()

/** Known wallet txids = field 0 of each getTransactionDetails line
 *  (`txHash|amount|fee|blockHeight|timestamp|sent|received`).
 *  **Capped at the 100 most-recent txs by the native side** — never use this alone
 *  to plan a history import; see [knownTxidsForHistoryPlan]. */
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
