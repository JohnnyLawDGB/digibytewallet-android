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

    @Query("SELECT COALESCE(SUM(satoshis), 0) FROM utxos WHERE is_asset = 0 AND spent = 0")
    fun getDigiByteBalance(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(utxos: List<UtxoEntity>)

    @Query("UPDATE utxos SET spent = 1 WHERE txid = :txid AND vout = :vout")
    suspend fun markSpent(txid: String, vout: Int)

    @Query("DELETE FROM utxos")
    suspend fun deleteAll()

    @Query("SELECT asset_id as assetId, SUM(asset_quantity) as totalQuantity, COUNT(*) as utxoCount FROM utxos WHERE is_asset = 1 AND spent = 0 GROUP BY asset_id")
    fun getAssetBalances(): Flow<List<AssetBalance>>
}
