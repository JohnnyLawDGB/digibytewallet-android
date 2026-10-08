package app.aroundtheblock.wallet.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `utxos.asset_credit`: whether an asset row's units are backed
 * ([app.aroundtheblock.wallet.core.asset.AssetCredit]).
 *
 * Every existing row starts UNCHECKED. Rows written before this version took a transfer's
 * quantity from its instructions without asking whether the inputs carried those units, and named
 * it from the first input's history, so none of them is trusted as it stands: each one is decided
 * again — from the wallet's own inputs where it can, otherwise by the indexer — and counts only
 * once it has been. Nothing is deleted and no quantity is changed here; the hold-out rules keep
 * reading the stored quantities exactly as before.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE utxos ADD COLUMN asset_credit TEXT NOT NULL DEFAULT 'UNCHECKED'")
    }
}
