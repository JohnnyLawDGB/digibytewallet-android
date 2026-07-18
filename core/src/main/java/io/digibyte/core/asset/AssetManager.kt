package io.digibyte.core.asset

import io.digibyte.core.TxResult
import io.digibyte.core.bridge.NativeBridge
import io.digibyte.core.dandelion.Broadcaster
import io.digibyte.core.db.dao.AssetBalance
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.dao.TransactionDao
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.TransactionEntity
import io.digibyte.core.db.entity.UtxoEntity
import io.digibyte.core.ipfs.AssetMetadataService
import io.digibyte.core.model.AssetData
import io.digibyte.core.model.AssetMetadata
import io.digibyte.core.model.OwnedAsset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Pure per-row parse of a [io.digibyte.core.bridge.NativeBridge.getTransactionDetails]
 * line's `sent` / `blockHeight` fields (source-fix / C2): decides whether a
 * wallet-known tx is an unconfirmed OUTGOING send — one where we spent from
 * our own inputs (`sent > 0`) but it hasn't confirmed yet (`blockHeight >=
 * TX_UNCONFIRMED`, i.e. `Int.MAX_VALUE`). An incoming (`sent == 0`) unconfirmed
 * receive returns false — only the outgoing-and-unconfirmed combination is
 * gated. Top-level and dependency-free so it's directly unit-testable without
 * any NativeBridge/DAO mocking.
 */
internal fun isOutgoingUnconfirmedRow(sent: Long, blockHeight: Long): Boolean =
    sent > 0L && blockHeight >= Int.MAX_VALUE.toLong()

/**
 * Pure builder for the sweep's txid -> isOutgoingUnconfirmed overlay
 * (source-fix / C2). Parses each
 * [io.digibyte.core.bridge.NativeBridge.getTransactionDetails] row
 * (`txHash|amount|fee|blockHeight|timestamp|sent|received`) and records
 * [isOutgoingUnconfirmedRow] against its txid. Malformed rows (fewer than 6
 * `|`-fields, blank txid) are skipped. Top-level and dependency-free so the
 * sweep's overlay logic is unit-testable without any NativeBridge mocking.
 *
 * A txid ABSENT from the returned map is, by construction, an OLD tx that
 * fell outside `getTransactionDetails`' recent-100 window — and since
 * `BRWalletTransactions` is oldest-first while unconfirmed txs sort NEWEST,
 * every unconfirmed-outgoing send is guaranteed to be inside that window.
 * So callers treat an absent txid as `false` (`map[txid] ?: false`), which
 * is always correct: an out-of-window tx is necessarily confirmed.
 */
internal fun buildOutgoingUnconfirmedMap(detailRows: List<String>): Map<String, Boolean> {
    val map = HashMap<String, Boolean>()
    for (line in detailRows) {
        if (line.isBlank()) continue
        val p = line.split("|")
        if (p.size < 6) continue
        val txHash = p[0]
        if (txHash.isBlank()) continue
        val blockHeight = p[3].toLongOrNull() ?: 0L
        val sent = p[5].toLongOrNull() ?: 0L
        map[txHash] = isOutgoingUnconfirmedRow(sent, blockHeight)
    }
    return map
}

/**
 * Orchestration layer for DigiAsset operations.
 *
 * Responsibilities:
 *  - Exposes a Flow of [OwnedAsset]s combining UTXO balances with cached metadata.
 *  - Provides per-asset transaction history.
 *  - Parses raw transactions for embedded asset data via the JNI bridge and
 *    [DigiAssetDecoder].
 *  - Stores new asset UTXOs and queues IPFS metadata fetches.
 *  - Stubs out asset-transfer transaction building (full implementation in Task 8).
 */
class AssetManager(
    private val utxoDao: UtxoDao,
    private val transactionDao: TransactionDao,
    private val metadataDao: AssetMetadataDao,
    private val metadataService: AssetMetadataService,
    private val decoder: DigiAssetDecoder = DigiAssetDecoder(),
    private val assetNetworkClient: io.digibyte.core.asset.network.AssetNetworkClient? = null,
    private val outgoingTxStore: io.digibyte.core.OutgoingTxStore? = null,
    private val walletTxPersister: io.digibyte.core.WalletTxPersister? = null,
) {

    /**
     * Flow of owned assets grouped by asset_id, with quantities and metadata.
     *
     * Emits a new list whenever either the UTXO set or the metadata cache changes.
     * Assets without cached metadata have [OwnedAsset.metadata] == null until
     * [processAssetUtxo] triggers a background fetch.
     */
    fun getOwnedAssets(): Flow<List<OwnedAsset>> {
        return utxoDao.getAssetBalances().combine(metadataDao.getAllMetadata()) { balances, metadata ->
            val metadataMap = metadata.associateBy { it.assetId }
            balances.map { balance ->
                val meta = metadataMap[balance.assetId]
                OwnedAsset(
                    assetId = balance.assetId,
                    quantity = balance.totalQuantity,
                    metadata = meta?.let {
                        AssetMetadata(
                            assetId = it.assetId,
                            name = it.name,
                            symbol = it.symbol,
                            description = it.description,
                            decimals = it.decimals,
                            totalSupply = it.totalSupply,
                            issuerAddress = it.issuerAddress,
                            imageUrl = it.imageUrl
                        )
                    },
                    utxoCount = balance.utxoCount
                )
            }
        }
    }

    /**
     * Flow of asset transactions.
     *
     * Currently returns all transactions flagged as asset transactions in order of
     * descending timestamp for the given [assetId]. Filters by the assetId
     * column added in MIGRATION_4_5; rows predating that migration are
     * attributed by AssetHistoryBackfill via a UTXO-join + rawBytes decode.
     */
    fun getAssetHistory(assetId: String): Flow<List<TransactionEntity>> =
        transactionDao.getAssetTransactions(assetId)

    /**
     * Attempt to parse [rawTx] for embedded DigiAsset data.
     *
     * Uses the JNI bridge to extract the OP_RETURN script and then passes the
     * raw script bytes to [DigiAssetDecoder].  Returns null for non-asset
     * transactions or if parsing fails.
     */
    fun parseTransaction(rawTx: ByteArray): AssetData? {
        val opReturn = NativeBridge.getOpReturnData(rawTx) ?: return null
        return decoder.decode(opReturn)?.toAssetData()
    }

    /**
     * Native asset detection path — called on every SPV [onTransactionReceived]
     * callback. Runs the Kotlin decoder against the OP_RETURN in the
     * wallet-known tx, and if a DigiAsset header is found, inserts
     * `is_asset=true` UTXO rows for every non-OP_RETURN output and kicks off
     * an IPFS metadata fetch using the CID from the header.
     *
     * This is the backend-independent path: no listunspent call, no RPC,
     * no digiasset_core. If [digiscope.me] is down (or we're in a pure
     * sovereign configuration), assets still surface in the UI as long as
     * SPV delivered the transaction.
     *
     * Current asset-ID handling is a placeholder (`"unresolved:<txid>"`):
     *   - For ISSUANCE opcodes we can compute the real asset ID deterministically
     *     from (first_input_outpoint, locked_flag, aggregation) — wiring that
     *     derivation is M1.2 follow-on work.
     *   - For TRANSFER opcodes we need a parent-walk to the issuance tx; that
     *     is M3 follow-on work.
     *
     * Returns an [IncomingAssetInfo] carrying the decoded header and the
     * placeholder asset-id we stamped onto the UTXO rows (caller uses it
     * to label the [TransactionEntity] the same way), or null for non-asset
     * transactions.
     */
    /**
     * The set of hex-encoded scriptPubKeys the wallet owns, derived from the
     * SOVEREIGN native address list (BRWalletAllAddrs — external + internal +
     * legacy chains). This is the wallet's own view, never a third party, so
     * using it to identify phantom outputs (asset outputs at addresses we don't
     * own) keeps the DigiAsset accounting sovereign. Empty if the lookup fails;
     * callers treat empty as "unknown" and fall back to non-destructive
     * behavior (never prune, never gate) so a lookup failure can't lose data.
     */
    fun buildOwnedScriptHexes(): Set<String> = runCatching {
        NativeBridge.dumpAllAddresses()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { NativeBridge.addressToScriptPubKey(it)?.toHex() }
            .toSet()
    }.getOrDefault(emptySet())

    suspend fun processIncomingAssetTx(
        txHashHex: String,
        blockHeight: Long,
        ownedScriptHexes: Set<String>? = null,
        isOutgoingUnconfirmed: Boolean = false,
        persistAfterDetect: Boolean = false,
    ): IncomingAssetInfo? {
        val outputLines = NativeBridge.getTransactionOutputsForHash(txHashHex) ?: return null
        if (outputLines.isEmpty()) return null

        data class ParsedOutput(val vout: Int, val sats: Long, val script: ByteArray)
        val outputs = outputLines.mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val vout = parts[0].toIntOrNull() ?: return@mapNotNull null
            val sats = parts[1].toLongOrNull() ?: return@mapNotNull null
            val script = parts[2].hexToByteArray() ?: return@mapNotNull null
            ParsedOutput(vout, sats, script)
        }
        if (outputs.isEmpty()) return null

        // Locate the OP_RETURN output (0x6A) and decode it.
        val opReturn = outputs.firstOrNull { it.script.isNotEmpty() && it.script[0] == 0x6A.toByte() }
            ?: return null
        val header = decoder.decode(opReturn.script) ?: return null

        // Asset-ID resolution:
        //  - ISSUANCE + locked: derive deterministically from first input outpoint.
        //    This covers assets we issue ourselves AND any issuance tx we observe
        //    directly. 100% sovereign — no network call.
        //  - TRANSFER (any) or ISSUANCE unlocked: fall back to placeholder for now;
        //    real resolution lands in M3 (SPV parent walk) and M1.2 unlocked path.
        val derivedAssetId: String? = when {
            header.operation == io.digibyte.core.model.AssetOperation.ISSUANCE && header.locked -> {
                val inputs = NativeBridge.getTransactionInputsForHash(txHashHex)
                val firstInput = inputs?.firstOrNull()?.split("|", limit = 2)
                if (firstInput != null && firstInput.size == 2) {
                    val prevTxid = firstInput[0]
                    val prevVout = firstInput[1].toIntOrNull() ?: -1
                    if (prevVout >= 0) {
                        val aggregationCode = when (header.aggregation) {
                            Aggregation.AGGREGATABLE -> 0
                            Aggregation.HYBRID -> 1
                            Aggregation.DISPERSED -> 2
                        }
                        runCatching {
                            NativeBridge.deriveIssuanceAssetId(
                                firstInputTxidHex = prevTxid,
                                firstInputVout = prevVout,
                                locked = true,
                                aggregation = aggregationCode,
                                divisibility = header.divisibility,
                            )
                        }.getOrNull()
                    } else null
                } else null
            }
            else -> null
        }

        val placeholderAssetId = derivedAssetId ?: "unresolved:$txHashHex"

        // Insert non-OP_RETURN outputs as asset UTXOs. In practice only the
        // outputs we own (marker + change) end up here anyway because
        // BRWallet only tracks wallet-relevant outputs, but we also accept
        // the marker targets — they're the asset carriers by DA convention.
        //
        // Per-output quantity logic:
        //  - ISSUANCE: totalQuantity lands at the first non-OP_RETURN output
        //    (DigiAsset convention — the issuer's marker). Other outputs get 0.
        //  - TRANSFER: sum transfer instructions targeting this vout. Percent
        //    and range instructions are not resolvable without the per-input
        //    asset balances from parent txs (M3), so we skip them here —
        //    an underestimate is better than a fake number.
        //  - BURN: quantities of outputs don't matter (asset is destroyed).
        val firstNonOpReturn = outputs.firstOrNull {
            it.script.isEmpty() || it.script[0] != 0x6A.toByte()
        }?.vout

        fun quantityForOutput(vout: Int): Long = when (header.operation) {
            io.digibyte.core.model.AssetOperation.ISSUANCE ->
                if (vout == firstNonOpReturn) (header.totalQuantity ?: 0L) else 0L

            io.digibyte.core.model.AssetOperation.TRANSFER ->
                header.transferInstructions
                    .filter { !it.percent && !it.range && it.outputIndex == vout && !it.isBurn }
                    .sumOf { it.amount }

            io.digibyte.core.model.AssetOperation.BURN -> 0L
        }

        // Ownership gate. `outputs` comes from getTransactionOutputsForHash,
        // which returns ALL of the tx's outputs unfiltered — NOT just the ones
        // we own. For a transfer WE send, the recipient's marker output carries
        // the full sent quantity; inserting it here manufactures a phantom
        // is_asset/spent=0 row for an output we don't control, which then
        // inflates SUM(asset_quantity) (the displayed balance) and can even be
        // re-selected as an un-signable input on the next send. So skip any
        // output whose scriptPubKey isn't one of our own addresses.
        //
        // Both error directions self-heal: a false-positive (phantom slips in,
        // e.g. owned-set empty) is pruned by the sovereign ownership reconcile
        // in refreshAssetUtxosFromNetwork; a false-negative (we drop one of our
        // own change markers) is re-derived by native detection / the backend
        // refresh over the same sovereign address set. If the owned set can't
        // be built we fall back to insert-everything and let the prune clean up
        // — never worse than before. Built ONCE by the caller (the 30s sweep)
        // and passed in, so we don't recompute it per tx.
        val owned = ownedScriptHexes ?: buildOwnedScriptHexes()

        var anyStillUnresolved = false
        for (out in outputs) {
            if (out.script.isNotEmpty() && out.script[0] == 0x6A.toByte()) continue
            // Skip outputs we don't own (see ownership-gate note above). Only
            // enforce when we actually have an owned set — an empty set means
            // the lookup failed, in which case we defer to the prune.
            if (owned.isNotEmpty() && out.script.toHex() !in owned) continue
            val stillUnresolved = persistDetectedAssetOutput(
                txHashHex = txHashHex,
                vout = out.vout,
                scriptPubKey = out.script,
                sats = out.sats,
                blockHeight = blockHeight,
                placeholderAssetId = placeholderAssetId,
                computedQty = quantityForOutput(out.vout),
                isOutgoingUnconfirmed = isOutgoingUnconfirmed,
            )
            if (stillUnresolved) anyStillUnresolved = true
        }

        // Kick off metadata fetch if we have a CID (issuance only).
        // Keyed by the placeholder assetId so the UI can display a name
        // even before we resolve the real asset ID.
        header.metadataCid?.let { cid ->
            runCatching { metadataService.getMetadata(placeholderAssetId, cid) }
                .onFailure {
                    android.util.Log.d("AssetManager", "metadata fetch failed for $cid", it)
                }
        }

        // M3 parent-walk: fire if we still have a placeholder OR if we
        // haven't yet walked this tx in the current session (to backfill
        // chain-facts for rows resolved in a prior session). The session-
        // local cache gates repeat walks to once per process lifetime.
        val shouldWalk = (anyStillUnresolved && placeholderAssetId.startsWith("unresolved:"))
            || !walkedInSession.contains(txHashHex)
        if (shouldWalk) {
            runCatching {
                val resolved = resolveTransferAssetId(txHashHex)
                if (resolved != null) {
                    walkedInSession.add(txHashHex)
                    if (resolved.assetId != placeholderAssetId) {
                        utxoDao.replaceAssetId(placeholderAssetId, resolved.assetId)
                        // Also rewrite the TransactionEntity.assetId so the
                        // per-asset transfer history query picks this tx up
                        // under the real id instead of the placeholder.
                        transactionDao.updateAssetId(txHashHex, resolved.assetId)
                    }

                    // Persist on-chain facts from the issuance header. These
                    // are authoritative — totalSupply and decimals are
                    // cryptographically tied to the issuance tx, whereas
                    // name/imageUrl depend on IPFS reachability. Seed an
                    // empty row if needed, then merge the chain fields so
                    // we don't clobber any existing IPFS-sourced metadata.
                    metadataDao.insertChainFacts(
                        io.digibyte.core.db.entity.AssetMetadataEntity(
                            assetId = resolved.assetId,
                            totalSupply = resolved.totalSupply,
                            decimals = resolved.divisibility,
                            metadataCid = resolved.metadataCid,
                            cachedAt = System.currentTimeMillis(),
                        )
                    )
                    metadataDao.updateChainFacts(
                        assetId = resolved.assetId,
                        totalSupply = resolved.totalSupply,
                        decimals = resolved.divisibility,
                    )

                    // Issuance-side metadata fetch — uses the CID we just
                    // extracted from the issuance header (authoritative)
                    // rather than waiting on backend getAssetData.
                    runCatching {
                        metadataService.getMetadata(resolved.assetId, resolved.metadataCid)
                    }
                    android.util.Log.i("AssetManager",
                        "M3 resolved $txHashHex → ${resolved.assetId} " +
                        "(supply=${resolved.totalSupply} div=${resolved.divisibility}; placeholder rewritten)")
                }
            }.onFailure { android.util.Log.d("AssetManager", "M3 walk threw for $txHashHex", it) }
        }

        val result = IncomingAssetInfo(header = header, assetId = placeholderAssetId)
        maybePersistAfterDetect(persistAfterDetect, result)
        return result
    }

    /**
     * Persist-on-detect decision (C6), extracted as its own seam so it's
     * testable without touching `NativeBridge` at all: [processIncomingAssetTx]
     * itself can't be driven directly from a host-JVM unit test (its own
     * prefix unconditionally calls the real `NativeBridge` singleton, whose
     * `init` block does `System.loadLibrary("core-lib")` — see the seam note
     * on [persistDetectedAssetOutput] for the same pre-existing constraint).
     *
     * Durability rationale: a freshly-received asset tx needs to survive an
     * app restart, or it's absent from the rebuilt native wallet tx set and
     * its NATIVE-provenance row looks phantom to the ownership prune — so the
     * receive path (SyncService.onTransactionReceived) snapshots the wallet's
     * tx set via [walletTxPersister] right after a detect. Only fires when
     * [persistAfterDetect] is true (the receive path; the periodic sweep
     * passes the default `false` and must never persist here) AND [detected]
     * is non-null (an asset tx was actually found — a non-asset received tx
     * must not trigger a snapshot).
     */
    internal fun maybePersistAfterDetect(persistAfterDetect: Boolean, detected: IncomingAssetInfo?) {
        if (persistAfterDetect && detected != null) runCatching { walletTxPersister?.persist() }
    }

    /**
     * The per-output persistence decision (C5): given a candidate asset UTXO
     * detected at (txHashHex, vout), either non-destructively re-tag an
     * existing row's provenance or insert a genuinely new one tagged NATIVE.
     *
     * Never rewrites `spent` or `blockHeight` on an existing row (a REPLACE-
     * reinsert there would reset both and fight the native-spent-decrement
     * branch — the exact data-loss bug this task fixes), and never lowers an
     * already-resolved `asset_quantity` (native's per-output quantity is an
     * underestimate for percent/range TRANSFER instructions, so only a
     * strictly larger computed value is allowed to raise it).
     *
     * Returns true if this outpoint is still unresolved (no real asset id
     * yet) after the upsert, so the caller can fold it into the M3-walk
     * trigger `anyStillUnresolved`.
     *
     * `internal` rather than `private`: [processIncomingAssetTx]'s own prefix
     * (OP_RETURN decode + asset-id derivation) unavoidably calls through the
     * real `NativeBridge` singleton, whose `init` block does
     * `System.loadLibrary("core-lib")` — unavailable on the host JVM unit-
     * test runner (see WalletManagerSavedTransactionsDecodeTest / WalletWipe
     * Test for the same constraint elsewhere in this codebase). Extracting
     * this seam lets [AssetProvenanceTaggingTest] exercise the real
     * persistence branch directly, by mocking only [utxoDao]. [AssetSourceFixTest]
     * (source-fix / C2) reuses the same seam to drive [isOutgoingUnconfirmed]
     * without needing to mock `NativeBridge`.
     *
     * @param isOutgoingUnconfirmed Source-fix (C2): true when this tx is an
     *   OUTGOING send (`sent > 0`) that hasn't confirmed yet. An unconfirmed
     *   outgoing send's OWNED change-marker is not a settled holding — the
     *   spent input hasn't been decremented yet, so counting the change
     *   double-counts, and if the send strands, the change row becomes a
     *   permanent phantom the periodic sweep would re-insert every tick.
     *   When true, this call persists NOTHING (no insert, no re-tag) and
     *   returns `false`; it settles normally once the flag flips to `false`
     *   on confirmation. Does not affect metadata/asset-id detection — those
     *   still run in [processIncomingAssetTx] regardless of this flag.
     */
    internal suspend fun persistDetectedAssetOutput(
        txHashHex: String,
        vout: Int,
        scriptPubKey: ByteArray,
        sats: Long,
        blockHeight: Long,
        placeholderAssetId: String,
        computedQty: Long,
        isOutgoingUnconfirmed: Boolean = false,
    ): Boolean {
        if (isOutgoingUnconfirmed) return false
        val existingRow = utxoDao.getAssetUtxoAt(txHashHex, vout)
        return if (existingRow != null) {
            // Re-detection of a row we already hold: re-tag provenance ONLY.
            utxoDao.markAssetSource(txHashHex, vout, AssetSource.NATIVE)
            if (computedQty > existingRow.assetQuantity) {
                utxoDao.updateAssetQuantity(txHashHex, vout, computedQty)
            }
            existingRow.assetId == null || existingRow.assetId.startsWith("unresolved:")
        } else {
            // Genuinely new outpoint: insert as NATIVE.
            utxoDao.insertAll(
                listOf(
                    UtxoEntity(
                        txid = txHashHex,
                        vout = vout,
                        scriptPubKey = scriptPubKey,
                        satoshis = sats,
                        blockHeight = blockHeight,
                        isAsset = true,
                        assetId = placeholderAssetId,
                        assetQuantity = computedQty,
                        spent = false,
                        assetSource = AssetSource.NATIVE,
                    )
                )
            )
            placeholderAssetId.startsWith("unresolved:")
        }
    }

    /**
     * M3 parent-walk: resolve the real DigiAsset ID for a transfer by
     * recursing up the input chain until we find the issuance transaction.
     *
     * Algorithm:
     *  1. Walk inputs[0] of [startTxHashHex] backward. At each hop, fetch
     *     the parent tx (first via BRWallet if we happen to have it, else
     *     via [assetNetworkClient.getRawTransaction]), parse, look at its
     *     OP_RETURN.
     *  2. If the parent's OP_RETURN is a DigiAsset ISSUANCE → compute the
     *     asset ID via [NativeBridge.deriveIssuanceAssetId] using that
     *     parent's own first-input outpoint.
     *  3. If it's a TRANSFER → recurse with the parent's first input.
     *  4. Bounded to [MAX_WALK_DEPTH] hops; real chains rarely exceed 2-3.
     *
     * The walk is suspend-only, so callers decide whether to await or fire
     * it into a background scope. When resolution succeeds, placeholder
     * asset-ids already written into the utxos table are rewritten via
     * [UtxoDao.replaceAssetId]. Metadata refresh is kicked off on the new
     * real id so names/icons materialize if a CID is recoverable.
     *
     * Returns the resolved asset ID, or null if the walk hit a dead end
     * (no parent tx available, max depth, unlocked issuance not yet
     * supported, etc).
     */
    /** Outcome of a successful [resolveTransferAssetId] walk — carries both
     *  the derived asset id and the on-chain facts from the issuance header
     *  (totalSupply, divisibility, metadataCid) so callers can persist them
     *  independently of whether IPFS-hosted metadata is reachable. */
    data class ResolvedAsset(
        val assetId: String,
        val totalSupply: Long,
        val divisibility: Int,
        val metadataCid: String?,
    )

    suspend fun resolveTransferAssetId(startTxHashHex: String): ResolvedAsset? {
        var currentTxid = startTxHashHex
        val seen = mutableSetOf<String>()

        for (depth in 0 until MAX_WALK_DEPTH) {
            if (!seen.add(currentTxid)) {
                android.util.Log.d("AssetManager", "M3 walk[$depth]: cycle at $currentTxid — stop")
                return null
            }

            val rawTx = fetchRawTransactionBytes(currentTxid)
            if (rawTx == null) {
                android.util.Log.d("AssetManager", "M3 walk[$depth]: no raw for $currentTxid — stop")
                return null
            }

            val opReturn = NativeBridge.getOpReturnData(rawTx)
            if (opReturn == null) {
                android.util.Log.d("AssetManager", "M3 walk[$depth]: no OP_RETURN in $currentTxid — stop")
                return null
            }
            val header = decoder.decode(opReturn)
            if (header == null) {
                android.util.Log.d("AssetManager", "M3 walk[$depth]: decoder rejected OP_RETURN of $currentTxid — stop")
                return null
            }

            when (header.operation) {
                io.digibyte.core.model.AssetOperation.ISSUANCE -> {
                    if (!header.locked) {
                        android.util.Log.d("AssetManager", "M3 walk[$depth]: unlocked issuance at $currentTxid — M1.2b gap")
                        return null
                    }
                    val firstInput = firstInputOfRawTx(rawTx)
                    if (firstInput == null) {
                        android.util.Log.d("AssetManager", "M3 walk[$depth]: can't parse first input of $currentTxid")
                        return null
                    }
                    val aggregationCode = when (header.aggregation) {
                        Aggregation.AGGREGATABLE -> 0
                        Aggregation.HYBRID -> 1
                        Aggregation.DISPERSED -> 2
                    }
                    val derived = runCatching {
                        NativeBridge.deriveIssuanceAssetId(
                            firstInputTxidHex = firstInput.prevTxidHex,
                            firstInputVout = firstInput.prevVout,
                            locked = true,
                            aggregation = aggregationCode,
                            divisibility = header.divisibility,
                        )
                    }.getOrNull() ?: return null
                    android.util.Log.d("AssetManager",
                        "M3 walk[$depth]: issuance $currentTxid → $derived (supply=${header.totalQuantity} div=${header.divisibility})")
                    return ResolvedAsset(
                        assetId = derived,
                        totalSupply = header.totalQuantity ?: 0L,
                        divisibility = header.divisibility,
                        metadataCid = header.metadataCid,
                    )
                }
                io.digibyte.core.model.AssetOperation.TRANSFER -> {
                    val firstInput = firstInputOfRawTx(rawTx)
                    if (firstInput == null) {
                        android.util.Log.d("AssetManager",
                            "M3 walk[$depth]: can't parse first input of transfer $currentTxid")
                        return null
                    }
                    android.util.Log.d("AssetManager",
                        "M3 walk[$depth]: transfer $currentTxid → parent ${firstInput.prevTxidHex}:${firstInput.prevVout}")
                    currentTxid = firstInput.prevTxidHex
                }
                io.digibyte.core.model.AssetOperation.BURN -> {
                    android.util.Log.d("AssetManager", "M3 walk[$depth]: burn at $currentTxid — stop")
                    return null
                }
            }
        }
        android.util.Log.d("AssetManager", "M3 walk: exceeded $MAX_WALK_DEPTH hops from $startTxHashHex")
        return null
    }

    /** Fetch raw tx bytes: prefer BRWallet (free, local, no network) then
     *  fall back to the asset network client's getRawTransaction. The local
     *  fast path fires when the parent tx happens to be one our own wallet
     *  has — common for multi-hop transfers we originated. */
    private suspend fun fetchRawTransactionBytes(txHashHex: String): ByteArray? {
        NativeBridge.getSerializedTransactionForHash(txHashHex)?.let { return it }
        val client = assetNetworkClient ?: return null
        return runCatching { client.getRawTransaction(txHashHex) }.getOrNull()
    }

    /** Parse a raw serialized DigiByte transaction and extract its first
     *  input's outpoint (prev txid + vout). Implemented in Kotlin to avoid
     *  another round-trip to JNI — bitcoin tx layout is stable and small. */
    private data class FirstInput(val prevTxidHex: String, val prevVout: Int)

    private fun firstInputOfRawTx(rawTx: ByteArray): FirstInput? {
        if (rawTx.size < 4 + 1 + 32 + 4) return null
        // Skip version (4 bytes)
        var p = 4
        // Optional segwit marker+flag (0x00, 0x01). Skip if present.
        if (rawTx.size > 5 && rawTx[p] == 0x00.toByte() && rawTx[p + 1] == 0x01.toByte()) {
            p += 2
        }
        // Input count varint (we only need the first, so parse one byte 0xfd/0xfe/0xff aware).
        val inputCount = readVarInt(rawTx, p) ?: return null
        p = inputCount.nextOffset
        if (inputCount.value < 1) return null

        // inputs[0]: 32-byte prev txid LE, 4-byte vout LE, varint scriptLen, script bytes, 4-byte sequence
        if (p + 36 > rawTx.size) return null
        val prevTxidLe = rawTx.sliceArray(p until p + 32)
        val prevVout = ((rawTx[p + 32].toInt() and 0xFF)) or
            ((rawTx[p + 33].toInt() and 0xFF) shl 8) or
            ((rawTx[p + 34].toInt() and 0xFF) shl 16) or
            ((rawTx[p + 35].toInt() and 0xFF) shl 24)

        // Convert internal-LE to display-BE hex.
        val prevTxidHex = buildString(64) {
            for (i in 31 downTo 0) append("%02x".format(prevTxidLe[i].toInt() and 0xFF))
        }
        return FirstInput(prevTxidHex = prevTxidHex, prevVout = prevVout)
    }

    private data class VarInt(val value: Long, val nextOffset: Int)
    private fun readVarInt(data: ByteArray, offset: Int): VarInt? {
        if (offset >= data.size) return null
        val b = data[offset].toInt() and 0xFF
        return when {
            b < 0xFD -> VarInt(b.toLong(), offset + 1)
            b == 0xFD -> if (offset + 3 <= data.size) {
                val v = ((data[offset + 1].toInt() and 0xFF)) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8)
                VarInt(v.toLong(), offset + 3)
            } else null
            b == 0xFE -> if (offset + 5 <= data.size) {
                val v = ((data[offset + 1].toLong() and 0xFF)) or
                    ((data[offset + 2].toLong() and 0xFF) shl 8) or
                    ((data[offset + 3].toLong() and 0xFF) shl 16) or
                    ((data[offset + 4].toLong() and 0xFF) shl 24)
                VarInt(v, offset + 5)
            } else null
            else -> null  // 0xFF (8-byte) — not used for input/output counts in practice
        }
    }

    /**
     * Walk every wallet-known transaction through [processIncomingAssetTx].
     *
     * Because SPV [onTransactionReceived] only fires for txs delivered during
     * the current run, transactions that were persisted by a prior run get
     * silently rehydrated into BRWallet without the native detection path
     * ever firing. This method closes that gap: call it once after
     * sync-complete (or after a successful recovery) so asset rows appear
     * for historical holdings without depending on any backend.
     *
     * Returns the count of transactions that were identified as DigiAsset txs.
     *
     * Two-source design (source-fix / C2 + full sovereign re-scan coverage):
     *  - COVERAGE: the tx SET to re-scan comes from the UNCAPPED
     *    [NativeBridge.getAllTransactionHashes] — every wallet-known tx, so a
     *    long-history wallet's OLD asset holdings are re-detected as before.
     *    ([NativeBridge.getTransactionDetails] caps at the 100 most recent, so
     *    driving the sweep off it alone would silently drop older asset txs
     *    from the re-scan.)
     *  - UNCONFIRMED SIGNAL: the per-tx `isOutgoingUnconfirmed` flag is
     *    overlaid from [NativeBridge.getTransactionDetails] (the recent-100),
     *    which is the only source carrying `sent` + `blockHeight`. This is
     *    SUFFICIENT: `BRWalletTransactions` is oldest-first and unconfirmed
     *    txs sort NEWEST, so EVERY unconfirmed-outgoing send is guaranteed to
     *    be inside the recent-100 window. A txid absent from the overlay map
     *    is therefore necessarily an OLD (hence confirmed) tx, for which
     *    `false` is the correct flag (`overlay[txHash] ?: false`).
     * When the flag is true it's threaded into [processIncomingAssetTx] so its
     * owned-output persistence is skipped for this pass (see
     * [persistDetectedAssetOutput]); an incoming (`sent == 0`) unconfirmed
     * receive is unaffected and persists normally.
     */
    suspend fun sweepKnownTransactionsForAssets(): Int {
        // COVERAGE source: uncapped tx set (see method doc).
        val hashes = NativeBridge.getAllTransactionHashes() ?: return 0
        // UNCONFIRMED-SIGNAL source: recent-100 details rows carry sent+blockHeight.
        //   Row format (jni_wallet getTransactionDetails):
        //   txHash|amount|fee|blockHeight|timestamp|sent|received   (TX_UNCONFIRMED = Int.MAX_VALUE)
        val detailsRows = runCatching { NativeBridge.getTransactionDetails().trim().lines() }
            .getOrNull().orEmpty()
        val outgoingUnconfirmed = buildOutgoingUnconfirmedMap(detailsRows)
        // Compute the owned-script set ONCE for the whole sweep (it's the same
        // for every tx) instead of rebuilding it per tx — a long-history wallet
        // has hundreds of addresses, so a per-tx rebuild is hundreds of JNI
        // calls × every known tx, every 30s.
        val owned = buildOwnedScriptHexes()
        var detected = 0
        for (txHash in hashes) {
            if (txHash.isBlank()) continue
            val isOutgoingUnconfirmed = outgoingUnconfirmed[txHash] ?: false
            val info = runCatching {
                processIncomingAssetTx(txHash, blockHeight = 0L, ownedScriptHexes = owned,
                    isOutgoingUnconfirmed = isOutgoingUnconfirmed)
            }.onFailure {
                android.util.Log.d("AssetManager", "sweep: processIncoming failed for $txHash", it)
            }.getOrNull()
            if (info != null) detected++
        }
        android.util.Log.i("AssetManager",
            "sweepKnownTransactions: ${hashes.size} txs scanned, $detected asset txs found")
        return detected
    }

    /**
     * Resync the local `utxos` table's asset rows against the authoritative
     * on-chain state. Walks every derived wallet address, queries the
     * [assetNetworkClient]'s `listunspent`-equivalent endpoint, upserts a
     * UtxoEntity with is_asset=1 for each returned asset UTXO, and also
     * primes the AssetMetadata cache with the server-side-resolved
     * name/decimals/issuer for any asset we don't have metadata for yet.
     *
     * This closes a long-standing gap: before this method existed,
     * processAssetUtxo had zero callers, so the Assets tab showed "No
     * DigiAssets found" even for wallets that genuinely held assets.
     * SPV's onAssetDetected callback and ChainReconciliationService's
     * registerRawTransaction both populate transactions but not utxos;
     * this pass fills the utxos side by trusting the node's indexed view.
     *
     * Safe to call repeatedly. Additively upserts the backend's view of our
     * asset UTXOs, then runs a SOVEREIGN phantom prune: rows at addresses the
     * native wallet does not own are deleted (this heals the "30 shown for 10"
     * inflation from recipient-marker rows a prior bug inserted). The backend is
     * never allowed to DELETE — only the native address set is trusted for
     * removal — so a partial/stale indexer response can't wipe real holdings.
     *
     * Returns the count of asset UTXOs upserted from the backend, or null if no
     * network client is configured or all endpoints failed.
     */
    suspend fun refreshAssetUtxosFromNetwork(): Int? {
        val client = assetNetworkClient ?: return null
        val addresses = NativeBridge.dumpAllAddresses()
            .trim().lines().filter { it.isNotBlank() }
        if (addresses.isEmpty()) return null

        // The SOVEREIGN owned-script set (our own addresses, never the backend).
        // Used below to prune phantom asset rows. Built from the addresses we
        // already fetched — no second native round-trip.
        val ownedScriptHexes: Set<String> =
            addresses.mapNotNull { NativeBridge.addressToScriptPubKey(it)?.toHex() }.toSet()

        // Batch to respect the 500-addresses-per-request server cap.
        val utxos = mutableListOf<io.digibyte.core.asset.network.AssetUtxoResponse>()
        for (chunk in addresses.chunked(500)) {
            val resp = client.getAssetUtxos(chunk) ?: return null
            utxos += resp
        }
        // NOTE: do NOT early-return on an empty backend response — the sovereign
        // phantom prune below must still run to clean stale rows even when the
        // backend currently reports no assets at our addresses.

        // Build the backend's current view, to be upserted ADDITIVELY below
        // (never a backend-authoritative delete). NOTE: a single (txid,vout)
        // carrying multiple assets collapses under the (txid,vout) primary key
        // to the LAST asset — a pre-existing single-asset-per-outpoint
        // limitation of the schema, unchanged here.
        val fresh = mutableListOf<UtxoEntity>()
        for (u in utxos) {
            for (asset in u.assets) {
                // Resolve scriptPubKey from the address so the UTXO is
                // fully spendable by the send flow without a second lookup.
                // If derivation fails (invalid address format), fall back
                // to empty bytes — the row still displays correctly; send
                // would fail gracefully with a typed error at that layer.
                val scriptPubKey = NativeBridge.addressToScriptPubKey(u.address) ?: ByteArray(0)
                fresh += UtxoEntity(
                    txid = u.txid,
                    vout = u.vout,
                    scriptPubKey = scriptPubKey,
                    satoshis = u.satoshis,
                    blockHeight = u.confirmedHeight,
                    isAsset = true,
                    assetId = asset.assetId,
                    assetQuantity = asset.count,
                    assetSource = io.digibyte.core.asset.AssetSource.BACKEND,
                )

                // Metadata cache handling — there's a subtle ordering
                // requirement here: AssetMetadataService.getMetadata returns
                // the cached entity if one exists (correct for immutable
                // content-addressed data), which means if we insert a
                // *bare* placeholder first, the IPFS fetch gets short-
                // circuited and the user never sees the real name/image.
                //
                // So: only insert a bare placeholder when we have NO CID
                // to fetch (node didn't supply one). If we do have a CID,
                // hand off to metadataService — it'll fetch + parse + insert
                // the richer entity via its own code path. We also do NOT
                // overwrite a rich cache entry that already exists from a
                // prior successful fetch.
                val existing = metadataDao.getMetadata(asset.assetId)
                val hasRichCache = existing?.name != null
                val cid = asset.metadataCid
                when {
                    hasRichCache -> Unit  // keep the real metadata

                    cid != null -> {
                        // Non-blocking fetch; writes richer entity on success.
                        metadataService.getMetadata(asset.assetId, cid)
                    }

                    existing == null -> {
                        // No CID to fetch and no cache entry yet — insert a
                        // minimal row so the UI at least shows assetId +
                        // decimals + issuer.
                        metadataDao.insert(
                            io.digibyte.core.db.entity.AssetMetadataEntity(
                                assetId = asset.assetId,
                                name = null,
                                symbol = null,
                                description = null,
                                decimals = asset.decimals,
                                totalSupply = 0L,
                                issuerAddress = asset.issuerAddress,
                                metadataCid = null,
                                imageUrl = null,
                                cachedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                }
            }
        }

        // Additive upsert of the backend's view. NEVER a backend-authoritative
        // delete: a trusted third-party indexer must not be able to erase asset
        // UTXOs the sovereign native path detected, and a partial/stale backend
        // response would otherwise wipe real holdings.
        if (fresh.isNotEmpty()) utxoDao.insertAll(fresh)

        // SOVEREIGN phantom prune: delete asset rows sitting at addresses the
        // wallet does NOT own (chiefly the recipient marker of a send we made,
        // which older builds inserted without an ownership check — the source
        // of the "30 shown for 10" inflation). Ownership is judged against the
        // native address set, never the backend. Guards:
        //   - skip entirely if the owned set couldn't be built (treat "unknown"
        //     as "don't delete" — a lookup failure must not lose data);
        //   - never prune an empty-script row (a real-but-not-yet-derivable
        //     holding the backend returned) — only rows with a concrete,
        //     not-ours scriptPubKey, which is exactly what a phantom marker is.
        // Deleted one row at a time (their count is tiny) to avoid any bulk-IN
        // variable limit and keep the blast radius minimal.
        if (ownedScriptHexes.isNotEmpty()) {
            val phantoms = utxoDao.getAllAssetUtxosNow().filter {
                it.scriptPubKey.isNotEmpty() && it.scriptPubKey.toHex() !in ownedScriptHexes
            }
            for (row in phantoms) utxoDao.deleteAssetUtxo(row.txid, row.vout)
        }

        // Reconcile spent-state from the SOVEREIGN native UTXO set: mark spent
        // the inputs of confirmed/broadcast sends, and un-spend a dropped
        // send's input if it has returned to the wallet's UTXO set.
        runCatching { reconcileAssetSpentFromNative() }
        return fresh.size
    }

    /**
     * Delete the OWNED is_asset output rows a dead/failed send fabricated.
     *
     * A send that never confirms (e.g. dropped from mempool) leaves behind
     * local UTXO rows for outputs the transaction WOULD have created — most
     * notably the asset-change marker at one of our own addresses, inserted
     * optimistically before broadcast. If the send is dead, that row is a
     * phantom: it inflates the displayed balance and can be re-selected as
     * an un-signable input on a later send. This clears exactly those rows.
     *
     * [outputs] are the dead tx's outputs as `"vout|sats|scriptHex"` (the
     * same wire format [processIncomingAssetTx] parses, from
     * [NativeBridge.getTransactionOutputsForHash]), read BEFORE the caller
     * removes the transaction. [ownedScriptHexes] is the sovereign owned-
     * script set from [buildOwnedScriptHexes] — never a third party.
     *
     * Only deletes a row whose script is one of ours AND whose txid is
     * [txid] ([UtxoDao.deleteAssetUtxo] is itself txid+vout scoped, so this
     * can never touch a different transaction's rows). The non-owned
     * recipient marker is deliberately left alone here — it lives at an
     * address we don't control and isn't ours to delete.
     *
     * Skips malformed lines (fewer than 3 `|`-separated fields, a
     * non-numeric vout) and empty scripts. Returns the count of rows
     * deleted.
     */
    suspend fun clearDeadAssetSend(
        txid: String,
        ownedScriptHexes: Set<String>,
        outputs: List<String>,
    ): Int {
        val ownedLower = ownedScriptHexes.map { it.lowercase() }.toSet()
        var deleted = 0
        for (line in outputs) {
            val parts = line.split("|")
            if (parts.size < 3) continue
            val vout = parts[0].toIntOrNull() ?: continue
            val scriptHex = parts[2]
            if (scriptHex.isEmpty()) continue
            if (scriptHex.lowercase() !in ownedLower) continue
            deleted += utxoDao.deleteAssetUtxo(txid, vout)
        }
        return deleted
    }

    /**
     * Make each owned asset row's spent flag track the native BRWallet's
     * authoritative spentOutputs set (via [NativeBridge.outpointSpentState]) —
     * the sovereign, chain-derived source of spent-ness, with NO local flag to
     * strand or resurrect. Per row: SPENT -> spent=true, HELD -> spent=false,
     * UNDETECTED (funding tx not yet synced) -> left unchanged so a mid-sync
     * wallet never hides a real holding.
     * Because publishTransaction registers a send with BRWallet immediately,
     * calling this right after a broadcast decrements the balance at once; a
     * dropped send's removeTransaction takes its input back out of spentOutputs,
     * so a later reconcile un-spends it automatically — no manual recovery.
     */
    suspend fun reconcileAssetSpentFromNative() {
        for (row in utxoDao.getAllAssetUtxosNow()) {
            val newSpent = decideAssetSpent(
                NativeBridge.outpointSpentState(row.txid, row.vout)
            ) ?: continue
            if (row.spent != newSpent) utxoDao.setSpent(row.txid, row.vout, newSpent)
        }
    }

    /** Distinct txids the wallet tracks an asset output for (spent + unspent) —
     *  used by the activity list to label a transaction row as DigiAsset. */
    suspend fun assetTxids(): Set<String> = utxoDao.getAssetTxids().toSet()

    /**
     * Store a confirmed asset UTXO in the database and queue an IPFS metadata fetch.
     *
     * @param txid         Transaction ID (hex).
     * @param vout         Output index within the transaction.
     * @param scriptPubKey Raw scriptPubKey bytes of the UTXO.
     * @param satoshis     Satoshi value (typically [DigiAssetDecoder.DA_ASSET_DUST_AMOUNT]).
     * @param blockHeight  Block height at which the UTXO was confirmed.
     * @param assetData    Decoded asset payload from [parseTransaction].
     */
    suspend fun processAssetUtxo(
        txid: String,
        vout: Int,
        scriptPubKey: ByteArray,
        satoshis: Long,
        blockHeight: Long,
        assetData: AssetData
    ) {
        utxoDao.insertAll(
            listOf(
                UtxoEntity(
                    txid = txid,
                    vout = vout,
                    scriptPubKey = scriptPubKey,
                    satoshis = satoshis,
                    blockHeight = blockHeight,
                    isAsset = true,
                    assetId = assetData.assetId,
                    assetQuantity = assetData.quantity
                )
            )
        )

        // Queue metadata fetch if we have a CID and haven't cached it yet
        assetData.metadataCid?.let { cid ->
            metadataService.getMetadata(assetData.assetId, cid)
        }
    }

    /**
     * Build, sign, and broadcast a DigiAsset transfer transaction.
     *
     * Output layout (DA convention):
     *   [0] Recipient marker — 700 sats to [toAddress]
     *   [1] OP_RETURN       —   0 sats, carries the DA transfer payload
     *   [2] DGB change      —   0 or N sats to a change address (if > dust)
     *
     * @param assetId    The DigiAsset identifier to transfer.
     * @param quantity   Asset quantity in internal (smallest-unit) integers.
     *                   The caller must scale from user-entered decimals
     *                   using the asset's divisibility before calling.
     * @param toAddress  Recipient DigiByte address.
     * @param feePerKb   DGB fee **rate** in sat/kB (100,000 = 100 sat/byte =
     *                   DGB min relay). The actual absolute fee is derived
     *                   size-aware from the concrete tx shape via
     *                   [AssetFeeEstimator.estimateAssetTxFeeSats] and is
     *                   always floored at min relay so the tx relays. A
     *                   custom TOTAL-fee override is expressed by the caller
     *                   as an equivalent feePerKb (see AssetViewModel).
     */
    suspend fun sendAsset(
        assetId: String,
        quantity: Long,
        toAddress: String,
        feePerKb: Long,
    ): TxResult {
        if (!NativeBridge.isValidAddress(toAddress)) return TxResult.Error("Invalid DigiByte address")
        if (quantity <= 0) return TxResult.Error("Quantity must be positive")
        if (feePerKb < 0) return TxResult.Error("Fee rate must be non-negative")

        // 1. Load spendable UTXOs.
        val assetUtxos = utxoDao.getAssetUtxosByIdNow(assetId)
        val dgbUtxos = utxoDao.getSpendableDigiByteUtxosNow()
        if (assetUtxos.isEmpty()) return TxResult.Error("No UTXOs for asset $assetId")

        // 2. Budget for two markers (recipient + possible asset-change). If
        //    selection turns out exact-match, the extra 700 sats falls into
        //    DGB change naturally — slight pessimism, simpler code.
        val markerSats = io.digibyte.core.asset.send.DA_MARKER_SATS
        val twoMarkerSats = markerSats * 2

        // 2a. First (bootstrap) selection with a conservative typical-shape
        //     fee. The asset-input set and the OP_RETURN are FEE-INDEPENDENT
        //     (they depend only on the transfer quantity), so this select
        //     reveals the stable parts of the shape; only the DGB fee inputs
        //     and DGB change vary with the fee. The bootstrap's DGB-input count
        //     merely seeds the convergence loop below (step 4a) — it is NOT
        //     assumed to be within one input of the final count.
        val bootstrapFeeSats = io.digibyte.core.asset.send.AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 1,
            dgbInputCount = 1,
            outputCount = 3,
            opReturnBytes = 80,
            feePerKb = feePerKb,
        )
        val bootstrap = io.digibyte.core.asset.send.AssetCoinSelector.select(
            assetUtxos = assetUtxos,
            dgbUtxos = dgbUtxos,
            assetNeeded = quantity,
            feeSats = bootstrapFeeSats,
            markerOutputSats = twoMarkerSats,
        )
        val ok0 = when (bootstrap) {
            is io.digibyte.core.asset.send.AssetCoinSelector.Result.InsufficientAsset ->
                return TxResult.Error("Not enough asset: need ${bootstrap.required}, have ${bootstrap.available}")
            is io.digibyte.core.asset.send.AssetCoinSelector.Result.InsufficientDgb ->
                return TxResult.Error("Not enough DGB for fee: need ${bootstrap.required}, have ${bootstrap.available}")
            is io.digibyte.core.asset.send.AssetCoinSelector.Result.Ok -> bootstrap
        }

        val hasAssetChange = ok0.assetChangeQty > 0L

        // 3. Output layout. Recipient marker at vout 0, OP_RETURN at vout 1,
        //    optional asset-change marker at vout 2, optional DGB change at
        //    the next free vout. Transfer instructions reference these vouts
        //    directly so we have to commit to the layout before encoding.
        val recipientVout = 0
        val assetChangeVout = if (hasAssetChange) 2 else -1

        // 4. Build transfer instructions: walk asset inputs in order,
        //    distributing each input's units into the recipient first then
        //    the change marker. `skip=true` on the LAST instruction pulling
        //    from a non-final input advances the decoder to the next input.
        //    Built from the bootstrap selection's asset side — identical
        //    across both selects since asset selection is fee-independent.
        val instructions = buildTransferInstructions(
            assetInputs = ok0.assetInputs,
            quantityToRecipient = quantity,
            assetChangeQty = ok0.assetChangeQty,
            recipientVout = recipientVout,
            assetChangeVout = assetChangeVout,
        ) ?: return TxResult.Error("Could not build transfer instructions")

        val opReturnScript = try {
            DigiAssetEncoder.encodeTransferScript(version = 3, instructions = instructions)
        } catch (e: Exception) {
            return TxResult.Error("Encode failed: ${e.message}")
        }

        // 4a. Now that we know the real OP_RETURN length and the concrete
        //     output count (recipient + optional asset-change + a DGB-change
        //     output we conservatively assume is present), compute the actual
        //     size-aware fee and RE-select with it. Value-output count for the
        //     estimate: recipient(1) + asset-change(0/1) + dgb-change(1).
        //
        //     CONVERGENCE LOOP (not a single pass): the size-aware fee is a
        //     function of the DGB-input count, and the DGB-input count is a
        //     function of the fee — a wallet whose DGB side is fragmented into
        //     many small UTXOs can pull far more inputs when the fee jumps from
        //     the bootstrap estimate to the real one than the estimator's fixed
        //     +1-input margin covers. If we only re-selected once, the built tx
        //     would pay below the 100 sat/byte min relay for its (larger) actual
        //     vsize and never relay. So iterate select→estimate→select, feeding
        //     the actual DGB-input count back into the next fee estimate, until
        //     the count stops growing. DGB-input count is monotonically
        //     non-decreasing in the fee and bounded by dgbUtxos.size, so the
        //     loop is guaranteed to reach a fixed point; the cap is a safety net.
        val estimateOutputCount = 1 + (if (hasAssetChange) 1 else 0) + 1
        // dgbUtxos.size distinct growth steps at most, +2 slack. Never below 2.
        val maxFeeIterations = dgbUtxos.size + 2
        var estimatedForDgbInputs = ok0.dgbInputs.size
        var feeSats = bootstrapFeeSats
        var ok = ok0
        for (iter in 0 until maxFeeIterations) {
            feeSats = io.digibyte.core.asset.send.AssetFeeEstimator.estimateAssetTxFeeSats(
                assetInputCount = ok0.assetInputs.size,
                dgbInputCount = estimatedForDgbInputs,
                outputCount = estimateOutputCount,
                opReturnBytes = opReturnScript.size,
                feePerKb = feePerKb,
            )
            val selection = io.digibyte.core.asset.send.AssetCoinSelector.select(
                assetUtxos = assetUtxos,
                dgbUtxos = dgbUtxos,
                assetNeeded = quantity,
                feeSats = feeSats,
                markerOutputSats = twoMarkerSats,
            )
            ok = when (selection) {
                is io.digibyte.core.asset.send.AssetCoinSelector.Result.InsufficientAsset ->
                    return TxResult.Error("Not enough asset: need ${selection.required}, have ${selection.available}")
                is io.digibyte.core.asset.send.AssetCoinSelector.Result.InsufficientDgb ->
                    return TxResult.Error("Not enough DGB for fee: need ${selection.required}, have ${selection.available}")
                is io.digibyte.core.asset.send.AssetCoinSelector.Result.Ok -> selection
            }
            // Converged: the fee we just charged was estimated for at least as
            // many DGB inputs as the selection actually pulled (the estimator's
            // internal +1 margin then still leaves a cushion), so the built tx
            // pays >= min relay for its real vsize.
            if (ok.dgbInputs.size <= estimatedForDgbInputs) break
            estimatedForDgbInputs = ok.dgbInputs.size
        }

        // 5. Build the output list — order locked to match the vout
        //    references baked into the transfer instructions above. The
        //    asset side of `ok` is identical to `ok0` (fee-independent);
        //    only the DGB inputs / change reflect the real fee.
        val allInputs = ok.assetInputs + ok.dgbInputs
        val outAddresses = mutableListOf<String>()
        val outAmounts = mutableListOf<Long>()
        val outScripts = mutableListOf<String>()

        outAddresses += toAddress
        outAmounts += markerSats
        outScripts += ""

        outAddresses += ""   // empty address = use raw script below (OP_RETURN)
        outAmounts += 0L
        outScripts += opReturnScript.toHex()

        if (hasAssetChange) {
            // Use change index 1 to keep this distinct from the DGB change
            // address — small privacy win + makes the wallet's own asset
            // marker easier to identify in tx history.
            val assetChangeAddr = NativeBridge.getChangeAddress(1, format = 2)
                ?: return TxResult.Error("Could not derive asset-change address")
            outAddresses += assetChangeAddr
            outAmounts += markerSats
            outScripts += ""
        }

        val dgbChange = ok.dgbChangeSats
        if (dgbChange > DGB_CHANGE_DUST_THRESHOLD) {
            val changeAddr = NativeBridge.getChangeAddress(0, format = 2)
                ?: return TxResult.Error("Could not derive change address")
            outAddresses += changeAddr
            outAmounts += dgbChange
            outScripts += ""
        }

        // 6. Native build + sign + broadcast.
        val signedHex = NativeBridge.buildAndSignAssetTransferTx(
            inputTxidsHex = allInputs.map { it.txid }.toTypedArray(),
            inputVouts = allInputs.map { it.vout }.toIntArray(),
            inputAmounts = allInputs.map { it.satoshis }.toLongArray(),
            inputScriptPubKeysHex = allInputs.map { it.scriptPubKey.toHex() }.toTypedArray(),
            outputAddresses = outAddresses.toTypedArray(),
            outputAmounts = outAmounts.toLongArray(),
            outputScriptsHex = outScripts.toTypedArray(),
        ) ?: return TxResult.Error("Native build/sign failed")

        val signedBytes = signedHex.hexToByteArray() ?: return TxResult.Error("Bad signed-tx hex")
        val txid = Broadcaster.broadcast(signedBytes)
            ?: return TxResult.Error("Broadcast failed — check peer connection")

        // Decrement the balance immediately: publishTransaction (in Broadcaster)
        // already called BRWalletRegisterTransaction, so the native wallet has
        // marked our asset input(s) spent and dropped them from its UTXO set.
        // Reconcile Room's spent-state from that sovereign set now (no local
        // markSpent flag to strand). If the send is later dropped, the input
        // returns to the UTXO set and a subsequent reconcile un-spends it.
        runCatching { reconcileAssetSpentFromNative() }

        // Durability: record + persist through the same path the normal send
        // uses so SyncService.rebroadcastStrandedSends() re-publishes this asset
        // transfer if a force-stop within ~1s of broadcast strands the stem.
        // Best-effort — never affects on-chain state. sentSats is the recipient
        // DGB marker (the asset quantity isn't a DGB amount); feeSats is exact.
        outgoingTxStore?.record(
            txid = txid,
            sentSats = markerSats,
            feeSats = feeSats,
            toAddress = toAddress,
        )
        walletTxPersister?.persist()

        return TxResult.Success(txid)
    }

    /**
     * Build the DA TRANSFER instruction list for a single-recipient send
     * with optional asset change.
     *
     * Walks the chosen asset inputs in order. Each input contributes its
     * full quantity, distributed first toward the recipient (until [quantity
     * ToRecipient] is exhausted), then toward the asset-change marker. The
     * last instruction pulling from a non-final input is marked `skip=true`
     * so the decoder advances to the next input.
     *
     * Returns null only if the input set's combined quantity doesn't match
     * `quantityToRecipient + assetChangeQty` — programmer error, never user
     * error (the coin selector enforces sums).
     */
    private fun buildTransferInstructions(
        assetInputs: List<UtxoEntity>,
        quantityToRecipient: Long,
        assetChangeQty: Long,
        recipientVout: Int,
        assetChangeVout: Int,
    ): List<DigiAssetEncoder.TransferInstruction>? {
        val totalIn = assetInputs.sumOf { it.assetQuantity }
        if (totalIn != quantityToRecipient + assetChangeQty) return null

        val out = mutableListOf<DigiAssetEncoder.TransferInstruction>()
        var qtyRemaining = quantityToRecipient
        var changeRemaining = assetChangeQty

        for ((idx, input) in assetInputs.withIndex()) {
            val isLastInput = idx == assetInputs.lastIndex
            var inputRemaining = input.assetQuantity

            // Allocate toward recipient first.
            if (inputRemaining > 0 && qtyRemaining > 0) {
                val take = minOf(inputRemaining, qtyRemaining)
                out += DigiAssetEncoder.TransferInstruction(
                    skip = false, range = false, percent = false,
                    outputIndex = recipientVout, amount = take,
                )
                qtyRemaining -= take
                inputRemaining -= take
            }

            // Then toward asset change.
            if (inputRemaining > 0 && changeRemaining > 0 && assetChangeVout >= 0) {
                val take = minOf(inputRemaining, changeRemaining)
                out += DigiAssetEncoder.TransferInstruction(
                    skip = false, range = false, percent = false,
                    outputIndex = assetChangeVout, amount = take,
                )
                changeRemaining -= take
                inputRemaining -= take
            }

            // Mark the last instruction pulling from this input with skip=true
            // (except on the final input — skip is a no-op there).
            if (!isLastInput && out.isNotEmpty()) {
                val last = out.removeAt(out.lastIndex)
                out += last.copy(skip = true)
            }
        }
        return out
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) { null }
    }

    /** Session-local cache of txids we've already processed through M3. On
     *  hit we skip both the walk AND the chain-facts backfill — both are
     *  idempotent, but we'd rather not redo them every 30s. Cleared on
     *  process restart; the walk then runs exactly once per session to
     *  refresh chain facts in case they ever go stale. */
    private val walkedInSession = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Per-OUTPOINT (`"txid:vout"`) count of consecutive prune passes native
     *  has lacked the tx. Keyed by outpoint, not txid: a single txid can carry
     *  several NATIVE asset UTXOs (e.g. an asset payment + our owned asset
     *  change, or a batched receive), and deletion is per-outpoint — a txid-
     *  keyed counter would let sibling outpoints of the same tx accumulate the
     *  shared count within ONE sweep and delete on the first absent pass,
     *  defeating the [ABSENCE_DEBOUNCE_THRESHOLD] "a transient window can't
     *  mass-delete" guarantee. ConcurrentHashMap to match the sibling
     *  [walkedInSession] convention (Task 5 serializes the cycle, but this
     *  preempts a CME if it ever runs concurrently). */
    private val nativeAbsenceCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Sovereign replacement for the removed 30s backend refresh. Deletes a
     * NATIVE-tagged asset row once native has POSITIVELY removed its tx
     * (getTransactionOutputsForHash == null) across
     * [ABSENCE_DEBOUNCE_THRESHOLD] consecutive passes. BACKEND rows are never
     * touched — a real holding native never scanned is a BACKEND row with a
     * null tx and must survive. Caller must gate with [assetPruneGateOpen].
     * Returns the count deleted.
     *
     * Thin delegate onto [pruneRemovedNativeAssetRowsImpl], wrapping the real
     * NativeBridge JNI check as a lambda. NativeBridge is a JNI singleton
     * whose `init` block loads a native library, which the host JVM unit-test
     * runner can't do — merely referencing the object (e.g. via
     * `mockkObject(NativeBridge)`) throws `UnsatisfiedLinkError` before any
     * mocking takes effect (same constraint documented on
     * [persistDetectedAssetOutput] / `AssetProvenanceTaggingTest`). So the
     * debounce logic actually under test lives in the Impl overload, driven
     * by a fake `isTxGone` lambda + a mocked [UtxoDao] — no NativeBridge load
     * required.
     */
    suspend fun pruneRemovedNativeAssetRows(): Int =
        pruneRemovedNativeAssetRowsImpl { txid ->
            runCatching { NativeBridge.getTransactionOutputsForHash(txid) }.getOrNull() == null
        }

    /**
     * Testable debounce core of [pruneRemovedNativeAssetRows]. [isTxGone] is
     * the native tx-absence check, factored out as a suspend lambda so tests
     * can drive it deterministically instead of through the real JNI-backed
     * NativeBridge singleton.
     */
    internal suspend fun pruneRemovedNativeAssetRowsImpl(isTxGone: suspend (String) -> Boolean): Int {
        val rows = utxoDao.getAssetUtxosBySourceNow(AssetSource.NATIVE)
        val liveKeys = HashSet<String>(rows.size)
        var deleted = 0
        for (row in rows) {
            if (row.txid.length != 64) continue   // never pass a malformed txid to native
            // Debounce is keyed per-OUTPOINT, not per-txid: a single tx can
            // carry multiple NATIVE asset UTXOs (payment + owned change), and
            // deletion is per-outpoint. A txid-keyed counter would let sibling
            // outpoints share (and prematurely trip) the count in one sweep.
            val key = "${row.txid}:${row.vout}"
            liveKeys.add(key)
            val gone = isTxGone(row.txid)
            if (!gone) {
                nativeAbsenceCounts.remove(key)
                continue
            }
            val n = (nativeAbsenceCounts[key] ?: 0) + 1
            nativeAbsenceCounts[key] = n
            if (n >= ABSENCE_DEBOUNCE_THRESHOLD) {
                deleted += utxoDao.deleteAssetUtxo(row.txid, row.vout)
                nativeAbsenceCounts.remove(key)
            }
        }
        // Drop stale debounce entries for outpoints no longer NATIVE-tagged.
        nativeAbsenceCounts.keys.retainAll(liveKeys)
        return deleted
    }

    /**
     *  KNOWN LIMITATION — currently returns AT MOST the single current unused
     *  internal-change TIP script (a 1-element set), NOT the wallet's used /
     *  historical internal-change scripts.
     *
     *  `NativeBridge.getChangeAddress(index, format)` IGNORES both `index` and
     *  `format` — the JNI implementation casts `(void)index; (void)format;` and
     *  returns only `BRWalletInternalChangeAddress` (the current unused change
     *  tip); see `native/src/main/jni/bridge/jni_wallet.c`. So there is no way,
     *  through this accessor, to enumerate the wallet's used/below-the-tip
     *  internal-change addresses. The legacy Chang phantoms sit at exactly those
     *  USED historical change addresses, so the tip set does not contain them.
     *
     *  CONSEQUENCE: the legacy heal is currently NON-FUNCTIONAL for pre-existing
     *  phantoms — [healLegacyChangeAddressOrphans] will find ~0 candidates. This
     *  is data-safe (under-matches; only ever ships dry-run) but is DEFERRED
     *  pending a native internal-chain accessor that can list the used change
     *  scripts (e.g. a future `BRWalletInternalAddrs` submodule accessor). Wire
     *  that source in here and the existing candidate logic + dry-run safety
     *  (already unit-tested) become fully functional with no other change.
     *
     *  Kept as a single cheap call (a loop over indices/formats would just make
     *  hundreds of identical JNI calls returning the same tip). Untested on host
     *  (NativeBridge JNI) — thin public helper only; the tests drive
     *  [healLegacyChangeAddressOrphansImpl] with an injected change-set directly. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun buildChangeScriptHexes(maxIndex: Int = 200): Set<String> {
        // getChangeAddress ignores its args (returns only the current change
        // tip), so a single call is exhaustive of what this accessor can give.
        val addr = runCatching { NativeBridge.getChangeAddress(0, 0) }.getOrNull()
        if (addr.isNullOrBlank()) return emptySet()
        val script = runCatching { NativeBridge.addressToScriptPubKey(addr) }.getOrNull()
            ?: return emptySet()
        return setOf(script.toHex().lowercase())
    }

    /**
     * One-time heal of pre-existing owned-CHANGE-address asset orphans (dead-send
     * change markers from before the going-forward source-fix). Thin delegate onto
     * [healLegacyChangeAddressOrphansImpl], wrapping the real NativeBridge JNI
     * tx-absence check as a lambda — same NativeBridge/`mockkObject` host-JVM
     * constraint documented on [pruneRemovedNativeAssetRows]. Ships log-only
     * (dryRun) first; deletes only once the operator confirms the candidate set
     * on-device (see plan Rollout).
     */
    suspend fun healLegacyChangeAddressOrphans(
        changeScriptHexes: Set<String>,
        dryRun: Boolean,
    ): LegacyHealResult =
        healLegacyChangeAddressOrphansImpl(changeScriptHexes, dryRun) { txid ->
            // Gone iff the native call SUCCEEDED and returned null. Unlike the
            // prune, this one-shot heal has NO debounce and deletes on the first
            // gone==true, so a thrown/failed native call must be treated as
            // NOT-gone (conservative — never delete on a transient JNI error).
            // The prune can safely read an exception as gone because it self-heals
            // via re-detection + a consecutive-absence debounce; the heal cannot.
            val r = runCatching { NativeBridge.getTransactionOutputsForHash(txid) }
            r.isSuccess && r.getOrNull() == null
        }

    /**
     * Testable core of [healLegacyChangeAddressOrphans]. A row is a candidate
     * iff ALL hold: native no longer has its tx ([isTxGone] true), its
     * scriptPubKey is non-empty, its hex-lowercased scriptPubKey is a member of
     * [changeScriptHexes] (lowercased), and its txid is a well-formed 64-char
     * hash. A real EXTERNAL receive can never satisfy the change-script test —
     * that's the whole safety argument for allowing a delete here at all. This
     * is the ONLY place in the asset-maintenance path allowed to delete an
     * owned row, and only on this positive conjunction — never on
     * backend-absence, never on native-absence alone.
     *
     * NOTE: this INTENTIONALLY spans ALL sources ([UtxoDao.getAllAssetUtxosNow]),
     * unlike the NATIVE-only prune ([pruneRemovedNativeAssetRowsImpl]). The Chang
     * phantoms are BACKEND-tagged (they came from the now-retired 30s backend
     * refresh), so a NATIVE-only scope would skip every phantom and heal nothing.
     * What makes deletion safe here is the CHANGE-CHAIN scope, NOT the source
     * tag: a genuine still-valid change holding has its tx PRESENT in native
     * (isTxGone == false) and therefore survives. Do NOT narrow this to NATIVE.
     *
     * CURRENT STATE: in production [changeScriptHexes] comes from
     * [buildChangeScriptHexes], which today can only supply the current
     * change-TIP script (native `getChangeAddress` ignores index/format) — so
     * in practice this yields ~0 candidates for the used/historical phantoms.
     * The candidate LOGIC and dry-run safety below are correct and stay valid
     * as-is once a real used-change-script source is wired into that helper;
     * the tests here inject the set directly, so they already exercise it.
     */
    internal suspend fun healLegacyChangeAddressOrphansImpl(
        changeScriptHexes: Set<String>,
        dryRun: Boolean,
        isTxGone: suspend (String) -> Boolean,
    ): LegacyHealResult {
        val changeLower = changeScriptHexes.map { it.lowercase() }.toSet()
        val candidates = mutableListOf<String>()
        var deleted = 0
        for (row in utxoDao.getAllAssetUtxosNow()) {
            if (row.txid.length != 64) continue
            if (row.scriptPubKey.isEmpty()) continue
            if (row.scriptPubKey.toHex().lowercase() !in changeLower) continue
            if (!isTxGone(row.txid)) continue
            candidates.add("${row.txid}:${row.vout} qty=${row.assetQuantity} asset=${row.assetId}")
            android.util.Log.i("AssetManager",
                "legacyHeal ${if (dryRun) "DRYRUN candidate" else "DELETE"}: ${row.txid}:${row.vout} qty=${row.assetQuantity}")
            if (!dryRun) deleted += utxoDao.deleteAssetUtxo(row.txid, row.vout)
        }
        android.util.Log.i("AssetManager",
            "legacyHeal: ${candidates.size} candidates, deleted=$deleted, dryRun=$dryRun")
        return LegacyHealResult(candidates, deleted)
    }

    private companion object {
        /** DGB change below this floor is folded into the fee rather than
         *  emitted as its own output. MUST be >= the network dust threshold
         *  for the change address type, or the node rejects the whole tx
         *  with reject-reason "dust". DigiByte 9.26 raised dust to 30,000
         *  sat/kB → legacy P2PKH floor = 5,460 sats (measured on a 9.26.4
         *  node). We use the legacy worst case so a change output is never
         *  dust regardless of the change address's script type. The old
         *  1,000 value produced dust change outputs that stalled sends. */
        const val DGB_CHANGE_DUST_THRESHOLD = 5_460L

        /** Max hops the M3 parent-walk will traverse before giving up. In
         *  practice asset chains are 1-3 transfers deep; this bounds the
         *  pathological case without blowing up when some chain loops or
         *  points at a tx no endpoint has. */
        const val MAX_WALK_DEPTH = 12

        /** Consecutive prune passes native must positively lack a NATIVE
         *  row's tx before it's deleted (see [pruneRemovedNativeAssetRowsImpl]). */
        const val ABSENCE_DEBOUNCE_THRESHOLD = 2
    }
}

/**
 * Result of a successful native DigiAsset detection pass. Returned by
 * [AssetManager.processIncomingAssetTx] so the caller (SyncService) can
 * label the matching [TransactionEntity] with the same placeholder
 * asset-id that was stamped on the UTXO rows.
 */
data class IncomingAssetInfo(
    val header: DecodedAssetHeader,
    /** Placeholder until M1.2 / M3 replace with real derived id. */
    val assetId: String,
)

/**
 * Result of [AssetManager.healLegacyChangeAddressOrphans]. [candidates] is a
 * human-readable `"txid:vout qty=.. asset=.."` line per row matching the
 * change-address-scoped orphan rule, logged regardless of [dryRun]. [deleted]
 * is the actual delete count — always 0 on a dry-run pass.
 */
data class LegacyHealResult(val candidates: List<String>, val deleted: Int)

/**
 * Pure decision for the native asset spent-reconcile: map the native
 * [NativeBridge.outpointSpentState] tri-state to a new spent flag, or null to
 * LEAVE THE ROW UNCHANGED.
 *   - 0 SPENT       -> true
 *   - 1 HELD        -> false
 *   - -1 UNDETECTED -> null (funding tx not yet detected mid-sync; marking it
 *                     spent would hide a real holding the backend surfaced early)
 */
internal fun decideAssetSpent(state: Int): Boolean? = when (state) {
    0 -> true
    1 -> false
    else -> null
}
