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
}
