package io.digibyte.core.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "asset_metadata")
data class AssetMetadataEntity(
    @PrimaryKey val assetId: String,
    val name: String? = null,
    val symbol: String? = null,
    val description: String? = null,
    val decimals: Int = 0,
    val totalSupply: Long = 0,
    val issuerAddress: String? = null,
    val metadataCid: String? = null,
    val imageUrl: String? = null,
    val rulesJson: String? = null,
    val cachedAt: Long = 0
)
