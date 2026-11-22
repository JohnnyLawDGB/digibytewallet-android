package app.aroundtheblock.wallet.core.db

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The step to version 13 ([MIGRATION_12_13]) adds `asset_metadata.mediaJson`, where the files an
 * asset's metadata declares are kept (ASSET-MEDIA-CLASSIFY). It adds one nullable column and
 * touches no row: NULL is the mark of a row whose document has not been read for media yet.
 * Instrumented coverage against a real SQLite file, with rows, is in
 * MigrationTest.migrate12To13_keepsEveryMetadataRowAndAddsTheMediaColumn.
 */
class MediaColumnOnUpgradeTest {

    private val schemaDir = File(File("..").canonicalFile, "core/schemas/app.aroundtheblock.wallet.core.db.WalletDatabase")

    private fun entities(version: Int): Map<String, JSONObject> {
        val arr = JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database").getJSONArray("entities")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.associateBy { it.getString("tableName") }
    }

    @Test fun the_step_adds_the_column_and_nothing_else() {
        val issued = mutableListOf<String>()
        val db = mockk<SupportSQLiteDatabase>()
        every { db.execSQL(any<String>()) } answers { issued += firstArg<String>() }

        MIGRATION_12_13.migrate(db)

        assertEquals(listOf("ALTER TABLE asset_metadata ADD COLUMN mediaJson TEXT"), issued)
    }

    /** Registered, and the last step: a 4.1.5 database is never opened through the destructive fallback. */
    @Test fun the_step_is_registered_and_reaches_the_version_this_build_opens() {
        assertEquals(13, WALLET_DB_VERSION)
        assertTrue(MIGRATION_12_13 in WALLET_DB_MIGRATIONS)
        assertEquals(12, MIGRATION_12_13.startVersion)
        assertEquals(13, MIGRATION_12_13.endVersion)
        // Every version from 1 has exactly one step out of it.
        assertEquals((1 until WALLET_DB_VERSION).toList(), WALLET_DB_MIGRATIONS.map { it.startVersion })
        assertEquals((2..WALLET_DB_VERSION).toList(), WALLET_DB_MIGRATIONS.map { it.endVersion })
    }

    /** What the step creates is what version 13's entity declares, and no other table changed. */
    @Test fun version_13_differs_from_12_only_by_the_media_column() {
        val v12 = entities(12)
        val v13 = entities(13)
        assertEquals(v12.keys, v13.keys)
        for (table in v12.keys - "asset_metadata") {
            assertEquals(table, v12.getValue(table).getString("createSql"), v13.getValue(table).getString("createSql"))
        }
        val before = v12.getValue("asset_metadata").getString("createSql")
        val after = v13.getValue("asset_metadata").getString("createSql")
        assertFalse(before.contains("mediaJson"))
        assertEquals(before, after.replace(", `mediaJson` TEXT", ""))

        val fields = v13.getValue("asset_metadata").getJSONArray("fields")
        val media = (0 until fields.length()).map { fields.getJSONObject(it) }.single { it.getString("columnName") == "mediaJson" }
        assertEquals("TEXT", media.getString("affinity"))
        assertFalse("nullable: NULL is the mark of a row not read for media", media.optBoolean("notNull", false))
    }
}
