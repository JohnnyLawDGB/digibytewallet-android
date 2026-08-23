package io.digibyte.core.db

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration_6_7Test {

    @Test fun versions_are_6_to_7() {
        assertEquals(6, MIGRATION_6_7.startVersion)
        assertEquals(7, MIGRATION_6_7.endVersion)
    }

    /**
     * Both tables, and both created IF NOT EXISTS — a wallet that reached version 7 through
     * Room's destructive fallback already has them, and a migration that throws there would
     * crash-loop the app on launch.
     */
    @Test fun creates_both_provenance_tables_idempotently() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sql = mutableListOf<String>()
        MIGRATION_6_7.migrate(db)
        verify { db.execSQL(capture(sql)) }

        assertEquals("two tables, no more", 2, sql.size)
        assertTrue("provenance table", sql.any { it.contains("asset_provenance") })
        assertTrue("frontier table", sql.any { it.contains("asset_walk_frontier") })
        assertTrue("must be idempotent", sql.all { it.contains("IF NOT EXISTS") })
    }

    /**
     * The frontier's resume point is the entire reason this migration exists — a chain deeper
     * than one attempt's hop budget can only resolve if the next attempt knows where to pick up.
     */
    @Test fun frontier_carries_a_resume_point() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sql = mutableListOf<String>()
        MIGRATION_6_7.migrate(db)
        verify { db.execSQL(capture(sql)) }

        val frontier = sql.first { it.contains("asset_walk_frontier") }
        assertTrue("keyed by the walk's start", frontier.contains("startTxid TEXT NOT NULL PRIMARY KEY"))
        assertTrue("and records where to continue", frontier.contains("resumeTxid TEXT NOT NULL"))
        assertTrue("with the depth already covered", frontier.contains("hopsWalked INTEGER NOT NULL"))
    }
}
