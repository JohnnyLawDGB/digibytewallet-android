package io.digibyte.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Asset metadata table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS asset_metadata (
                assetId TEXT NOT NULL PRIMARY KEY,
                name TEXT,
                symbol TEXT,
                description TEXT,
                decimals INTEGER NOT NULL DEFAULT 0,
                totalSupply INTEGER NOT NULL DEFAULT 0,
                issuerAddress TEXT,
                metadataCid TEXT,
                imageUrl TEXT,
                rulesJson TEXT,
                cachedAt INTEGER NOT NULL DEFAULT 0
            )
        """)

        // Digi-ID history table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS digiid_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                domain TEXT NOT NULL,
                callbackUrl TEXT NOT NULL,
                address TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                success INTEGER NOT NULL
            )
        """)

        // Asset quantity column on UTXOs
        db.execSQL("ALTER TABLE utxos ADD COLUMN asset_quantity INTEGER NOT NULL DEFAULT 0")

        // Tor preference columns on wallet_config
        db.execSQL("ALTER TABLE wallet_config ADD COLUMN torEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE wallet_config ADD COLUMN torPromptShown INTEGER NOT NULL DEFAULT 0")
    }
}
