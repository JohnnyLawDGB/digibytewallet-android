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

    /** Delete every asset UTXO whose "txid:vout" key is NOT in [keptKeys].
     *  Scoped to is_asset = 1 so plain-DGB UTXOs are never touched. Used by
     *  the authoritative network refresh to prune phantom / already-spent
     *  asset rows the node no longer reports. NOTE: [keptKeys] is bound as one
     *  SQL parameter per element (SQLite var limit ~999); asset UTXO sets are
     *  small in practice, so this is not chunked. */
    @Query("DELETE FROM utxos WHERE is_asset = 1 AND (txid || ':' || vout) NOT IN (:keptKeys)")
    suspend fun deleteAssetUtxosNotIn(keptKeys: List<String>)

    /** Atomically reconcile the asset-UTXO set to the authoritative [fresh]
     *  set from the network: prune any asset row not present in [fresh], then
     *  upsert [fresh]. One transaction, so a concurrent reader never observes
     *  the intermediate pruned-but-not-yet-reinserted state. The caller MUST
     *  pass a COMPLETE, non-empty network response — pruning to an empty or
     *  partial set would wipe real holdings from the local cache. */
    @Transaction
    suspend fun replaceAssetUtxos(fresh: List<UtxoEntity>) {
        // Self-guard: an empty set would make deleteAssetUtxosNotIn prune ALL
        // asset rows (NOT IN () matches everything). Never do that here — the
        // "we truly hold nothing" case is the caller's decision to make, not a
        // silent side effect of an empty argument.
        if (fresh.isEmpty()) return
        val keptKeys = fresh.map { "${it.txid}:${it.vout}" }.distinct()
        deleteAssetUtxosNotIn(keptKeys)
        insertAll(fresh)
    }

    @Query("SELECT asset_id as assetId, SUM(asset_quantity) as totalQuantity, COUNT(*) as utxoCount FROM utxos WHERE is_asset = 1 AND spent = 0 GROUP BY asset_id")
    fun getAssetBalances(): Flow<List<AssetBalance>>
}
