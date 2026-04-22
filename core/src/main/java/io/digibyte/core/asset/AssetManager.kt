package io.digibyte.core.asset

import io.digibyte.core.TxResult
import io.digibyte.core.bridge.NativeBridge
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
                utxoDao.insertAll(listOf(
                    UtxoEntity(
                        txid = u.txid,
                        vout = u.vout,
                        // scriptPubKey is needed for spending but not display.
                        // Left empty here; send flow resolves from the wallet's
                        // own address derivation before building the tx.
                        scriptPubKey = ByteArray(0),
                        satoshis = u.satoshis,
                        blockHeight = u.confirmedHeight,
                        isAsset = true,
                        assetId = asset.assetId,
                        assetQuantity = asset.count,
                    )
                ))
                upserted++

                // Prime the metadata cache so the Assets tab renders
                // name/decimals/issuer immediately. Metadata CID comes
                // back empty on some assets; in that case we still record
                // the bare name-less entry so the UI at least shows the
                // assetId + decimals from the node.
                val existing = metadataDao.getMetadata(asset.assetId)
                if (existing == null) {
                    metadataDao.insert(
                        io.digibyte.core.db.entity.AssetMetadataEntity(
                            assetId = asset.assetId,
                            name = null,
                            symbol = null,
                            description = null,
                            decimals = asset.decimals,
                            totalSupply = 0L,
                            issuerAddress = asset.issuerAddress,
                            metadataCid = asset.metadataCid,
                            imageUrl = null,
                            cachedAt = System.currentTimeMillis(),
                        )
                    )
                }
                // If a CID is present and we don't yet have richer metadata,
                // fire-and-forget an IPFS fetch (non-blocking).
                asset.metadataCid?.let { cid ->
                    metadataService.getMetadata(asset.assetId, cid)
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
     * @param feeSats    Total DGB fee in satoshis. Caller can derive this
     *                   from tx-size estimate × sat/byte.
     */
    suspend fun sendAsset(
        assetId: String,
        quantity: Long,
        toAddress: String,
        feeSats: Long,
    ): TxResult {
        if (!NativeBridge.isValidAddress(toAddress)) return TxResult.Error("Invalid DigiByte address")
        if (quantity <= 0) return TxResult.Error("Quantity must be positive")
        if (feeSats < 0) return TxResult.Error("Fee must be non-negative")

        // 1. Load spendable UTXOs.
        val assetUtxos = utxoDao.getAssetUtxosByIdNow(assetId)
        val dgbUtxos = utxoDao.getSpendableDigiByteUtxosNow()
        if (assetUtxos.isEmpty()) return TxResult.Error("No UTXOs for asset $assetId")

        // 2. Selection. Single recipient for now = one 700-sat marker.
        val markerSats = io.digibyte.core.asset.send.DA_MARKER_SATS
        val selection = io.digibyte.core.asset.send.AssetCoinSelector.select(
            assetUtxos = assetUtxos,
            dgbUtxos = dgbUtxos,
            assetNeeded = quantity,
            feeSats = feeSats,
            markerOutputSats = markerSats,
        )
        val ok = when (selection) {
            is io.digibyte.core.asset.send.AssetCoinSelector.Result.InsufficientAsset ->
                return TxResult.Error("Not enough asset: need ${selection.required}, have ${selection.available}")
            is io.digibyte.core.asset.send.AssetCoinSelector.Result.InsufficientDgb ->
                return TxResult.Error("Not enough DGB for fee: need ${selection.required}, have ${selection.available}")
            is io.digibyte.core.asset.send.AssetCoinSelector.Result.Ok -> selection
        }

        // 3. Reject asset change for now — MVP is full-UTXO transfers only.
        //    When we support partial transfers, emit a sender-owned marker
        //    at the last non-OP_RETURN output per the last-output rule.
        if (ok.assetChangeQty > 0) {
            return TxResult.Error("Partial transfers not supported yet — pick exact-match asset UTXOs")
        }

        // 4. Encode the DA OP_RETURN transfer payload. Recipient is output
        //    index 0, which is where the encoder references it.
        val opReturnScript = try {
            DigiAssetEncoder.encodeSimpleTransfer(version = 3, recipientOutputIndex = 0, quantity = quantity)
        } catch (e: Exception) {
            return TxResult.Error("Encode failed: ${e.message}")
        }

        // 5. Build the output list [marker, OP_RETURN, optional change].
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
        val txid = NativeBridge.publishTransaction(signedBytes)
            ?: return TxResult.Error("Broadcast failed — check peer connection")

        return TxResult.Success(txid)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) { null }
    }

    private companion object {
        /** DGB change below this floor is folded into the fee (avoids
         *  creating an indistinguishable-from-marker output). */
        const val DGB_CHANGE_DUST_THRESHOLD = 1000L
    }
}
