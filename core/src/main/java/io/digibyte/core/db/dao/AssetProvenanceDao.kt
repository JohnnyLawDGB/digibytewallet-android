package io.digibyte.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.digibyte.core.db.entity.AssetProvenanceEntity
import io.digibyte.core.db.entity.AssetWalkFrontierEntity

@Dao
interface AssetProvenanceDao {

    @Query("SELECT * FROM asset_provenance WHERE txid = :txid LIMIT 1")
    suspend fun provenanceFor(txid: String): AssetProvenanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProvenance(rows: List<AssetProvenanceEntity>)

    /** Any row for [assetId] that recorded the issuance opcode. All rows on one walked path share
     *  the same facts, so the first is as good as any; rows without the opcode are pre-upgrade. */
    @Query("SELECT * FROM asset_provenance WHERE assetId = :assetId AND issuanceOpcode IS NOT NULL LIMIT 1")
    suspend fun issuanceFactsFor(assetId: String): AssetProvenanceEntity?

    @Query("SELECT * FROM asset_walk_frontier WHERE startTxid = :startTxid LIMIT 1")
    suspend fun frontierFor(startTxid: String): AssetWalkFrontierEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFrontier(frontier: AssetWalkFrontierEntity)

    @Query("DELETE FROM asset_walk_frontier WHERE startTxid = :startTxid")
    suspend fun clearFrontier(startTxid: String)

    /** Wipe on rescan/wipe — provenance is a cache, never a source of truth. */
    @Query("DELETE FROM asset_provenance")
    suspend fun clearAllProvenance()

    @Query("DELETE FROM asset_walk_frontier")
    suspend fun clearAllFrontiers()
}
