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
    suspend fun processIncomingAssetTx(
        txHashHex: String,
        blockHeight: Long,
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

        var anyStillUnresolved = false
        for (out in outputs) {
            if (out.script.isNotEmpty() && out.script[0] == 0x6A.toByte()) continue
            // Preserve a real (non-placeholder) asset-id if a prior sweep +
            // M3 walk already resolved this UTXO. Without this check, each
            // 30s sweep would clobber the real id with a fresh
            // "unresolved:…" placeholder and trigger the walk on repeat.
            val existing = utxoDao.getAssetIdAt(txHashHex, out.vout)
            val effectiveAssetId = if (existing != null && !existing.startsWith("unresolved:")) {
                existing
            } else {
                anyStillUnresolved = true
                placeholderAssetId
            }
            utxoDao.insertAll(
                listOf(
                    UtxoEntity(
                        txid = txHashHex,
                        vout = out.vout,
                        scriptPubKey = out.script,
                        satoshis = out.sats,
                        blockHeight = blockHeight,
                        isAsset = true,
                        assetId = effectiveAssetId,
                        assetQuantity = quantityForOutput(out.vout),
                    )
                )
            )
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

        return IncomingAssetInfo(header = header, assetId = placeholderAssetId)
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
     */
    suspend fun sweepKnownTransactionsForAssets(): Int {
        val hashes = NativeBridge.getAllTransactionHashes() ?: return 0
        var detected = 0
        for (txHash in hashes) {
            if (txHash.isBlank()) continue
            val info = runCatching {
                processIncomingAssetTx(txHash, blockHeight = 0L)
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
     * Safe to call repeatedly. Idempotent — Room insert uses REPLACE on
     * the (txid, vout) primary key.
     *
     * Returns the count of asset UTXOs upserted, or null if no network
     * client is configured or all endpoints failed.
     */
    suspend fun refreshAssetUtxosFromNetwork(): Int? {
        val client = assetNetworkClient ?: return null
        val addresses = NativeBridge.dumpAllAddresses()
            .trim().lines().filter { it.isNotBlank() }
        if (addresses.isEmpty()) return null

        // Batch to respect the 500-addresses-per-request server cap.
        val utxos = mutableListOf<io.digibyte.core.asset.network.AssetUtxoResponse>()
        for (chunk in addresses.chunked(500)) {
            val resp = client.getAssetUtxos(chunk) ?: return null
            utxos += resp
        }

        if (utxos.isEmpty()) return 0

        var upserted = 0
        for (u in utxos) {
            for (asset in u.assets) {
                // One logical UtxoEntity per (txid, vout, assetId). In the
                // common case there's exactly one asset per UTXO; the inner
                // loop handles the rare multi-asset marker.
                // Resolve scriptPubKey from the address so the UTXO is
                // fully spendable by the send flow without a second lookup.
                // If derivation fails (invalid address format), fall back
                // to empty bytes — the row still displays correctly; send
                // would fail gracefully with a typed error at that layer.
                val scriptPubKey = NativeBridge.addressToScriptPubKey(u.address) ?: ByteArray(0)
                utxoDao.insertAll(listOf(
                    UtxoEntity(
                        txid = u.txid,
                        vout = u.vout,
                        scriptPubKey = scriptPubKey,
                        satoshis = u.satoshis,
                        blockHeight = u.confirmedHeight,
                        isAsset = true,
                        assetId = asset.assetId,
                        assetQuantity = asset.count,
                    )
                ))
                upserted++

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
        return upserted
    }

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

    private companion object {
        /** DGB change below this floor is folded into the fee (avoids
         *  creating an indistinguishable-from-marker output). */
        const val DGB_CHANGE_DUST_THRESHOLD = 1000L

        /** Max hops the M3 parent-walk will traverse before giving up. In
         *  practice asset chains are 1-3 transfers deep; this bounds the
         *  pathological case without blowing up when some chain loops or
         *  points at a tx no endpoint has. */
        const val MAX_WALK_DEPTH = 12
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
