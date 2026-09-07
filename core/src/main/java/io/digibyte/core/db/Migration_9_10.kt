package io.digibyte.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Transfer-rule gate (docs/superpowers/specs/2026-09-06-ruled-asset-transfer-gate-design.md §3):
 * the provenance cache learns which issuance opcode an asset walks back to, and whether that
 * issuance was locked, so a send can be refused for an asset that may carry rules.
 *
 * Existing rows get NULL in both columns, which the gate reads as "unknown" — refused until the
 * screen re-walks the asset to its issuance. Additive and guarded, like 9→10: SQLite has no
 * `ADD COLUMN IF NOT EXISTS`, and `fallbackToDestructiveMigration()` is on.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val existing = HashSet<String>()
        db.query("PRAGMA table_info(asset_provenance)").use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) existing.add(c.getString(nameIdx))
        }
        if ("issuanceOpcode" !in existing) {
            db.execSQL("ALTER TABLE asset_provenance ADD COLUMN issuanceOpcode INTEGER")
        }
        if ("issuanceLocked" !in existing) {
            db.execSQL("ALTER TABLE asset_provenance ADD COLUMN issuanceLocked INTEGER")
        }
    }
}
