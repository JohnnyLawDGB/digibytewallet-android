package io.digibyte.core.db

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class Migration_5_6Test {
    @Test fun versions_are_5_to_6() {
        assertEquals(5, MIGRATION_5_6.startVersion)
        assertEquals(6, MIGRATION_5_6.endVersion)
    }

    @Test fun adds_asset_source_column_with_backend_default() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        MIGRATION_5_6.migrate(db)
        verify {
            db.execSQL("ALTER TABLE utxos ADD COLUMN asset_source TEXT NOT NULL DEFAULT 'BACKEND'")
        }
    }
}
