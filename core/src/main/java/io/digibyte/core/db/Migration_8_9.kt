package io.digibyte.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Digi-ID key isolation (docs/specs/digiid-key-isolation.md): history rows now record
 * WHICH identity signed each login. Every pre-existing row was signed with the shared
 * m/0'/0/0 key, so the backfill default 'legacy' is a fact, not a guess — and it is
 * load-bearing: IdentityKeyPolicy grandfathers any domain with a successful 'legacy'
 * row so established site accounts keep their bound address.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE digiid_history ADD COLUMN derivation TEXT NOT NULL DEFAULT 'legacy'")
    }
}
