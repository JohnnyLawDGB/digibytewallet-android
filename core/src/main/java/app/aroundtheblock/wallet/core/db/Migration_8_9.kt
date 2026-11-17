package app.aroundtheblock.wallet.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Digi-ID key isolation (docs/specs/digiid-key-isolation.md): history rows now record
 * WHICH identity signed each login. Every pre-existing row was signed with the shared
 * m/0'/0/0 key, so the backfill default 'legacy' is a fact, not a guess. It is a record
 * only: until Q4 A (IdentityKeyPolicy) a successful 'legacy' row kept its domain on the
 * shared key; now no row decides which identity signs.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE digiid_history ADD COLUMN derivation TEXT NOT NULL DEFAULT 'legacy'")
    }
}
