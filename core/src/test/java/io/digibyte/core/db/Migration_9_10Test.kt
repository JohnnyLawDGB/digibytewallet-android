package io.digibyte.core.db

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provenance table learns the issuance opcode and locked flag so the transfer-rule gate
 * has a chain-proven signal (spec §3). Same guarded ALTER pattern as the earlier migrations: SQLite has no
 * ADD COLUMN IF NOT EXISTS and destructive fallback is on, so the columns may already exist.
 */
class Migration_9_10Test {

    @Test fun versions_are_9_to_10() {
        assertEquals(9, MIGRATION_9_10.startVersion)
        assertEquals(10, MIGRATION_9_10.endVersion)
    }

    private fun dbWithColumns(vararg names: String): Pair<SupportSQLiteDatabase, MutableList<String>> {
        val cursor = mockk<Cursor>()
        val moves = names.map { true } + false
        every { cursor.getColumnIndexOrThrow("name") } returns 1
        every { cursor.moveToNext() } returnsMany moves
        every { cursor.getString(1) } returnsMany names.toList()
        every { cursor.close() } just runs
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { db.query("PRAGMA table_info(asset_provenance)") } returns cursor
        val sql = mutableListOf<String>()
        every { db.execSQL(capture(sql)) } just runs
        return db to sql
    }

    @Test fun adds_both_columns_when_absent() {
        val (db, sql) = dbWithColumns("txid", "assetId", "totalSupply", "divisibility", "metadataCid")
        MIGRATION_9_10.migrate(db)
        assertEquals(2, sql.size)
        assertTrue(sql[0].contains("ALTER TABLE asset_provenance ADD COLUMN issuanceOpcode INTEGER"))
        assertTrue(sql[1].contains("ALTER TABLE asset_provenance ADD COLUMN issuanceLocked INTEGER"))
    }

    @Test fun is_a_no_op_when_both_columns_exist() {
        val (db, sql) = dbWithColumns("txid", "issuanceOpcode", "issuanceLocked")
        MIGRATION_9_10.migrate(db)
        assertEquals(0, sql.size)
        verify { db.query("PRAGMA table_info(asset_provenance)") }
    }

    @Test fun adds_only_the_missing_column() {
        val (db, sql) = dbWithColumns("txid", "issuanceOpcode")
        MIGRATION_9_10.migrate(db)
        assertEquals(listOf("ALTER TABLE asset_provenance ADD COLUMN issuanceLocked INTEGER"), sql)
    }
}
