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
    private val decoder: DigiAssetDecoder = DigiAssetDecoder()
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
     * Build and sign an asset transfer transaction.
     *
     * Full implementation deferred to Task 8. Requires:
     *  - Selecting asset UTXOs + DGB fee UTXOs via [io.digibyte.core.CoinSelector].
     *  - Constructing the OP_RETURN with transfer encoding.
     *  - Creating 700-sat marker outputs for each asset recipient.
     *  - Signing via the C core (NativeBridge.signTransaction).
     */
    suspend fun sendAsset(
        assetId: String,
        quantity: Long,
        toAddress: String,
        feePerKb: Long
    ): TxResult {
        if (!NativeBridge.isValidAddress(toAddress)) {
            return TxResult.Error("Invalid DigiByte address")
        }
        if (quantity <= 0) {
            return TxResult.Error("Quantity must be positive")
        }

        // TODO: Build asset transfer transaction (Task 8)
        // This requires selecting asset UTXOs + DGB fee UTXOs,
        // constructing the OP_RETURN with transfer encoding,
        // creating 700-sat marker outputs, and signing via C core.
        return TxResult.Error("Asset sending not yet implemented")
    }
}
