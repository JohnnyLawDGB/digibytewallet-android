package app.aroundtheblock.wallet.core.db.entity

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
    /** The metadata document's `urls` entries as JSON text
     *  ([app.aroundtheblock.wallet.core.ipfs.AssetMediaEntries]); `"[]"` when the document was read
     *  and declares none, NULL when it has not been read for them (every row written before
     *  schema 13). A named row whose re-read was answered "not here" holds a JSON OBJECT counting
     *  those answers instead ([app.aroundtheblock.wallet.core.ipfs.MediaRereadMark]); it reads as
     *  no entries. */
    val mediaJson: String? = null,
    val cachedAt: Long = 0
)
