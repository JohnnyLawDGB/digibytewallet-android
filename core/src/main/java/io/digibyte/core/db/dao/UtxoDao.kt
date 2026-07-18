package io.digibyte.core.db.dao

import androidx.room.*
import io.digibyte.core.db.entity.UtxoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UtxoDao {
    @Query("SELECT * FROM utxos WHERE is_asset = 0 AND spent = 0")
    fun getSpendableDigiByteUtxos(): Flow<List<UtxoEntity>>

    @Query("SELECT * FROM utxos WHERE is_asset = 1 AND spent = 0")
    fun getAssetUtxos(): Flow<List<UtxoEntity>>

    /** One-shot suspend reads used by sendAsset (Flow variants cache too aggressively
     *  when consumed via .first() for single-use selection). */
    @Query("SELECT * FROM utxos WHERE is_asset = 0 AND spent = 0")
    suspend fun getSpendableDigiByteUtxosNow(): List<UtxoEntity>

    @Query("SELECT * FROM utxos WHERE is_asset = 1 AND spent = 0 AND asset_id = :assetId")
    suspend fun getAssetUtxosByIdNow(assetId: String): List<UtxoEntity>

    @Query("SELECT COALESCE(SUM(satoshis), 0) FROM utxos WHERE is_asset = 0 AND spent = 0")
    fun getDigiByteBalance(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(utxos: List<UtxoEntity>)

    @Query("UPDATE utxos SET spent = 1 WHERE txid = :txid AND vout = :vout")
    suspend fun markSpent(txid: String, vout: Int)

    /** Inverse of markSpent — seam for a held branch to restore an asset UTXO's
     *  input after a dead/failed asset-send so it becomes spendable again. Not
     *  wired into any caller yet. */
    @Query("UPDATE utxos SET spent = 0 WHERE txid = :txid AND vout = :vout")
    suspend fun markUnspent(txid: String, vout: Int)

    /** Rewrite the placeholder asset_id of every UTXO row matching [oldAssetId]
     *  once M3 parent-walk resolves it to a real DigiAsset id. Safe no-op if
     *  no rows match (e.g. the placeholder was already replaced). */
    @Query("UPDATE utxos SET asset_id = :newAssetId WHERE asset_id = :oldAssetId")
    suspend fun replaceAssetId(oldAssetId: String, newAssetId: String)

    /** Fetch the current asset_id for a (txid, vout) if any. Used by the
     *  sweep path to avoid clobbering a previously-resolved real asset-id
     *  with a fresh "unresolved:…" placeholder. */
    @Query("SELECT asset_id FROM utxos WHERE txid = :txid AND vout = :vout LIMIT 1")
    suspend fun getAssetIdAt(txid: String, vout: Int): String?

    @Query("DELETE FROM utxos")
    suspend fun deleteAll()

    /** All asset rows (spent + unspent). Used by the SOVEREIGN ownership
     *  reconcile to find phantom rows — asset outputs sitting at addresses the
     *  wallet does not own (e.g. the recipient marker of a send WE made). */
    @Query("SELECT * FROM utxos WHERE is_asset = 1")
    suspend fun getAllAssetUtxosNow(): List<UtxoEntity>

    /** Delete a single asset UTXO by outpoint. Scoped to is_asset = 1 so a
     *  plain-DGB UTXO is never removed. The reconcile deletes phantom rows one
     *  at a time (their count is tiny), which also sidesteps the SQLite
     *  bound-variable limit an `IN (:keys)` bulk delete would hit. */
    @Query("DELETE FROM utxos WHERE is_asset = 1 AND txid = :txid AND vout = :vout")
    suspend fun deleteAssetUtxo(txid: String, vout: Int)

    @Query("SELECT asset_id as assetId, SUM(asset_quantity) as totalQuantity, COUNT(*) as utxoCount FROM utxos WHERE is_asset = 1 AND spent = 0 GROUP BY asset_id")
    fun getAssetBalances(): Flow<List<AssetBalance>>
}
