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

    /** The mirror of [updateChainFacts]: merge the fields an IPFS metadata document IS the source
     *  of, without touching totalSupply or decimals.
     *
     *  Supply is chain data — cryptographically tied to the issuance tx — while the metadata
     *  document is written by whoever minted the asset and authenticated by nothing. Canonical
     *  DigiAsset metadata carries no `totalSupply` key at all, so the previous whole-row REPLACE
     *  stamped a default 0 over the chain-derived number and the detail screen read "Unknown".
     *  A metadata write may only touch the columns it owns. */
    @Query(
        "UPDATE asset_metadata SET name = :name, symbol = :symbol, description = :description, " +
            "issuerAddress = :issuerAddress, metadataCid = :metadataCid, imageUrl = :imageUrl, " +
            "cachedAt = :cachedAt WHERE assetId = :assetId"
    )
    suspend fun updateIpfsFacts(
        assetId: String,
        name: String?,
        symbol: String?,
        description: String?,
        issuerAddress: String?,
        metadataCid: String?,
        imageUrl: String?,
        cachedAt: Long,
    )

    /** The proxy's rules object as JSON text; `"{}"` when the proxy answered without one, NULL
     *  when it was never learned. Written by AssetMetadataService.refreshRules only. */
    @Query("UPDATE asset_metadata SET rulesJson = :rulesJson WHERE assetId = :assetId")
    suspend fun updateRulesJson(assetId: String, rulesJson: String?)

    @Query("SELECT rulesJson FROM asset_metadata WHERE assetId = :assetId")
    suspend fun rulesJsonFor(assetId: String): String?
}
