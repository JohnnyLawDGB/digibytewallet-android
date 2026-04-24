package io.digibyte.core.db.dao

import androidx.room.*
import io.digibyte.core.db.entity.AssetMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetMetadataDao {
    @Query("SELECT * FROM asset_metadata WHERE assetId = :assetId")
    suspend fun getMetadata(assetId: String): AssetMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: AssetMetadataEntity)

    @Query("SELECT * FROM asset_metadata")
    fun getAllMetadata(): Flow<List<AssetMetadataEntity>>

    /** Merge on-chain issuance facts (totalSupply, decimals) into a row
     *  without disturbing IPFS-sourced fields (name, description, imageUrl).
     *  Creates the row if missing via the separate INSERT-OR-IGNORE path;
     *  existing IPFS cache hits keep their human-facing data intact. */
    @Query("UPDATE asset_metadata SET totalSupply = :totalSupply, decimals = :decimals WHERE assetId = :assetId")
    suspend fun updateChainFacts(assetId: String, totalSupply: Long, decimals: Int)

    /** Insert a chain-facts-only row for assets without IPFS metadata yet.
     *  IGNORE conflict so we don't overwrite a richer existing entry. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChainFacts(metadata: AssetMetadataEntity)
}
