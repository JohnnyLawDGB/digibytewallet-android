package app.aroundtheblock.wallet.core.db

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The step to version 12 ([MIGRATION_11_12]) adds `utxos.asset_credit` and does nothing else: no
 * row is deleted and no quantity changes, and every existing row starts UNCHECKED, so nothing a
 * pre-4.0.89 build credited counts until it has been decided again
 * ([app.aroundtheblock.wallet.core.asset.AssetCredit]). Instrumented coverage against a real
 * SQLite file is in MigrationTest.migrate11To12_everyExistingAssetRowStartsUncheckedAndKeepsItsQuantity.
 */
class AssetCreditColumnOnUpgradeTest {

    private val schemaDir = File(File("..").canonicalFile, "core/schemas/app.aroundtheblock.wallet.core.db.WalletDatabase")

    private fun schema(version: Int): JSONObject =
        JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database")

    private fun entities(db: JSONObject): Map<String, JSONObject> {
        val arr = db.getJSONArray("entities")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.associateBy { it.getString("tableName") }
    }

    @Test fun the_step_adds_the_column_with_unchecked_for_every_existing_row_and_nothing_else() {
        val issued = mutableListOf<String>()
        val db = mockk<SupportSQLiteDatabase>()
        every { db.execSQL(any<String>()) } answers { issued += firstArg<String>() }

        MIGRATION_11_12.migrate(db)

        assertEquals(
            listOf("ALTER TABLE utxos ADD COLUMN asset_credit TEXT NOT NULL DEFAULT 'UNCHECKED'"),
            issued,
        )
        assertEquals(12, WALLET_DB_VERSION)
        assertTrue(MIGRATION_11_12 in WALLET_DB_MIGRATIONS)
    }

    /** What the step creates is what version 12's entity declares, and no other table changed. */
    @Test fun version_12_differs_from_11_only_by_the_credit_column() {
        val v11 = entities(schema(11))
        val v12 = entities(schema(12))
        assertEquals(v11.keys, v12.keys)
        for (table in v11.keys - "utxos") {
            assertEquals(table, v11.getValue(table).getString("createSql"), v12.getValue(table).getString("createSql"))
        }
        val before = v11.getValue("utxos").getString("createSql")
        val after = v12.getValue("utxos").getString("createSql")
        assertEquals(before, after.replace(", `asset_credit` TEXT NOT NULL", ""))

        val fields = v12.getValue("utxos").getJSONArray("fields")
        val credit = (0 until fields.length()).map { fields.getJSONObject(it) }
            .single { it.getString("columnName") == "asset_credit" }
        assertEquals("TEXT", credit.getString("affinity"))
        assertEquals(true, credit.getBoolean("notNull"))
    }
}
