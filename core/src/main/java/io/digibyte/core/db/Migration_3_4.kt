package io.digibyte.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN sent INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE transactions ADD COLUMN received INTEGER NOT NULL DEFAULT 0")
    }
}
