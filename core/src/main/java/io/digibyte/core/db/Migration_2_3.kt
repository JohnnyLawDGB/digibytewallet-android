package io.digibyte.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS hub_cached_messages (
                id INTEGER NOT NULL PRIMARY KEY,
                channelId INTEGER NOT NULL,
                content TEXT NOT NULL,
                fromHandle TEXT,
                fromAddress TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                signature TEXT NOT NULL
            )
        """)
    }
}
