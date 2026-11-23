package app.aroundtheblock.wallet.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `asset_metadata.mediaJson`: the files an asset's metadata declares (ASSET-MEDIA-CLASSIFY),
 * kept for the detail screen's "this asset carries a video" line and for a later player
 * ([app.aroundtheblock.wallet.core.ipfs.AssetMediaEntries]).
 *
 * One nullable column and nothing else. No row is rewritten or deleted, and no name, supply, rule
 * or picture changes here. Every existing row starts NULL, which means "this row's document has not
 * been read for media": [app.aroundtheblock.wallet.core.ipfs.AssetMetadataService.getMetadata]
 * reads such a row's document once more by its own CID, and that read is also what replaces an
 * image a 4.1.5 build took from an audio entry named "icon". It is done there and not here because
 * the row does not hold the declared type: only the document does.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE asset_metadata ADD COLUMN mediaJson TEXT")
    }
}
