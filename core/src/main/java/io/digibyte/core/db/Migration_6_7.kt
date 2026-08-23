package io.digibyte.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the DigiAsset parent-walk's memory.
 *
 * `asset_provenance` records what each walked transaction turned out to carry, so a repeat walk
 * costs nothing. `asset_walk_frontier` records where an attempt stopped, so a chain deeper than
 * one attempt's hop budget still resolves across several — previously such a chain re-walked
 * from scratch every time and could never finish, leaving a transferred asset with no name and
 * no artwork.
 *
 * Both tables are caches. Dropping them costs work, never correctness.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS asset_provenance (
                txid TEXT NOT NULL PRIMARY KEY,
                assetId TEXT NOT NULL,
                totalSupply INTEGER NOT NULL,
                divisibility INTEGER NOT NULL,
                metadataCid TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS asset_walk_frontier (
                startTxid TEXT NOT NULL PRIMARY KEY,
                resumeTxid TEXT NOT NULL,
                hopsWalked INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}
