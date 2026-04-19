package io.digibyte.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds a nullable `assetId` column on the `transactions` table so
 * AssetDetailScreen can filter transaction history to a specific DigiAsset.
 *
 * The column starts null for every existing row; [io.digibyte.core.asset.AssetHistoryBackfill]
 * populates it on first launch after the upgrade by (a) joining against the
 * `utxos` table (which already stores `asset_id` per UTXO) and (b) falling
 * back to a DigiAssetDecoder pass over each row's rawBytes OP_RETURN for
 * rows whose UTXOs have been spent and pruned.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN assetId TEXT")
    }
}
