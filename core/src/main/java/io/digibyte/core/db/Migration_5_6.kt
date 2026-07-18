package io.digibyte.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `asset_source` to `utxos` — provenance of each asset UTXO row
 * (NATIVE = sovereign sweep detection; BACKEND = on-demand reconcile).
 * Existing rows default to 'BACKEND' so they are never auto-pruned; the
 * first post-upgrade sweep re-tags genuinely native-held rows to 'NATIVE'.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE utxos ADD COLUMN asset_source TEXT NOT NULL DEFAULT 'BACKEND'")
    }
}
