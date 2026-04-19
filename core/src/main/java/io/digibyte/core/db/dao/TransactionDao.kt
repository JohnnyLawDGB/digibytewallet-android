package io.digibyte.core.db.dao

import androidx.room.*
import io.digibyte.core.db.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE txid = :txid")
    suspend fun getTransaction(txid: String): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Query("UPDATE transactions SET confirmations = :confirmations WHERE txid = :txid")
    suspend fun updateConfirmations(txid: String, confirmations: Int)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    /** Returns all asset transactions ordered by descending timestamp.
     *  Retained for callers that want the full asset history across assets. */
    @Query("SELECT * FROM transactions WHERE isAssetTx = 1 ORDER BY timestamp DESC")
    fun getAllAssetTransactions(): Flow<List<TransactionEntity>>

    /** Returns transactions for a single DigiAsset (by assetId). */
    @Query("SELECT * FROM transactions WHERE assetId = :assetId ORDER BY timestamp DESC")
    fun getAssetTransactions(assetId: String): Flow<List<TransactionEntity>>

    /** Backfill pass 1: use the correlated UTXO row's `asset_id` to populate
     *  transactions.assetId for rows created before MIGRATION_4_5. Atomic,
     *  idempotent, relies on AssetManager.processAssetUtxo() having already
     *  populated the utxos table. */
    @Query("""
        UPDATE transactions
        SET assetId = (
            SELECT asset_id FROM utxos
            WHERE utxos.txid = transactions.txid
              AND utxos.is_asset = 1
            LIMIT 1
        )
        WHERE isAssetTx = 1 AND assetId IS NULL
    """)
    suspend fun backfillAssetIdFromUtxos()

    /** Batch of asset-tx rows still missing assetId after pass 1 — these had
     *  their UTXO row already spent + purged, so we fall back to decoding the
     *  rawBytes OP_RETURN on the Kotlin side. LIMIT caps heap pressure. */
    @Query("SELECT * FROM transactions WHERE isAssetTx = 1 AND assetId IS NULL LIMIT 100")
    suspend fun getUnbackfilledAssetTxsBatch(): List<TransactionEntity>

    /** Single-row update used by the rawBytes-decode fallback. */
    @Query("UPDATE transactions SET assetId = :assetId WHERE txid = :txid")
    suspend fun updateAssetId(txid: String, assetId: String)
}
