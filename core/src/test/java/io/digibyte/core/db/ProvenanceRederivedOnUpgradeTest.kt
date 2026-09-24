package io.digibyte.core.db

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What the provenance walk resolved before a fetched parent was bound to the id it was requested
 * under is not carried across the upgrade that introduced the binding: those identities and walk
 * frontiers are discarded once, when an existing database is opened at the new version, and the
 * walk derives each of them again. Nothing else in the database is touched.
 *
 * The database here is a table-level model driven by the real registered steps
 * ([WALLET_DB_MIGRATIONS], applied the way Room applies them, from a version up to
 * [WALLET_DB_VERSION]). Its tables are read from the exported schema, so a table added later is
 * covered without editing this test. Its only operation is a whole-table row delete: any other
 * statement a step issues fails the test, which is how "rows only, never the schema" is held.
 * Instrumented coverage of the same step against a real SQLite file is in MigrationTest.
 */
class ProvenanceRederivedOnUpgradeTest {

    private val schemaDir = File(File("..").canonicalFile, "core/schemas/io.digibyte.core.db.WalletDatabase")

    private fun schema(version: Int): JSONObject {
        val f = File(schemaDir, "$version.json")
        assertTrue("no exported schema for version $version at $f", f.isFile)
        return JSONObject(f.readText()).getJSONObject("database")
    }

    private fun tablesOf(db: JSONObject): List<String> {
        val entities = db.getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
    }

    /** Rows per table, each row a column map. */
    private class Tables(names: List<String>) {
        val rows: Map<String, MutableList<Map<String, Any?>>> = names.associateWith { mutableListOf() }
        fun put(table: String, row: Map<String, Any?>) {
            (rows[table] ?: error("no table $table in the schema")).add(row)
        }
    }

    /** A database whose only operation is `DELETE FROM <table>`; any other call fails the test. */
    private fun database(tables: Tables): SupportSQLiteDatabase {
        val db = mockk<SupportSQLiteDatabase>()
        val wholeTableDelete = Regex("""(?is)^\s*DELETE\s+FROM\s+`?(\w+)`?\s*;?\s*$""")
        every { db.execSQL(any<String>()) } answers {
            val sql = firstArg<String>()
            val table = wholeTableDelete.matchEntire(sql)?.groupValues?.get(1)
                ?: throw AssertionError("a step issued something other than a whole-table row delete: $sql")
            (tables.rows[table] ?: throw AssertionError("a step named a table the schema does not have: $table"))
                .clear()
        }
        return db
    }

    /** Open an install whose database is at [from], the way Room does: every registered step from
     *  there up to the current version, in order, and none when it is already current. */
    private fun open(from: Int, db: SupportSQLiteDatabase) {
        var at = from
        while (at < WALLET_DB_VERSION) {
            val step = WALLET_DB_MIGRATIONS.singleOrNull { it.startVersion == at }
                ?: return fail("no registered step from version $at")
            step.migrate(db)
            at = step.endVersion
        }
        assertEquals("the steps overshoot the current version", WALLET_DB_VERSION, at)
    }

    private val start = "5".repeat(64)
    private val parent = "a".repeat(64)
    private val staleAssetId = "La" + "c".repeat(30)

    /** An install at version 10: a provenance identity and a walk frontier resolved before the
     *  binding, and one row in every other table — an asset carrier holding that identity among
     *  them, so what a balance and a spend read is present too. */
    private fun installAtVersion10(): Tables {
        val tables = Tables(tablesOf(schema(10)))
        tables.put(
            "asset_provenance",
            mapOf(
                "txid" to start, "assetId" to staleAssetId, "totalSupply" to 10L, "divisibility" to 0,
                "metadataCid" to null, "issuanceOpcode" to 1, "issuanceLocked" to true,
            ),
        )
        tables.put(
            "asset_walk_frontier",
            mapOf("startTxid" to start, "resumeTxid" to parent, "hopsWalked" to 7, "updatedAt" to 1L),
        )
        tables.put(
            "utxos",
            mapOf(
                "txid" to start, "vout" to 0, "satoshis" to 600L, "blockHeight" to 100L, "is_asset" to true,
                "asset_id" to staleAssetId, "asset_quantity" to 10L, "spent" to false, "asset_source" to "NATIVE",
            ),
        )
        tables.put("transactions", mapOf("txHash" to start, "assetId" to staleAssetId))
        tables.put("asset_metadata", mapOf("assetId" to staleAssetId, "name" to "named", "totalSupply" to 10L))
        for ((table, rows) in tables.rows) if (rows.isEmpty()) tables.put(table, mapOf("row" to table))
        return tables
    }

    private val provenanceTables = setOf("asset_provenance", "asset_walk_frontier")

    @Test fun an_identity_resolved_before_the_upgrade_does_not_survive_it() {
        val tables = installAtVersion10()

        open(10, database(tables))

        assertEquals(
            "an identity resolved before the binding was carried across the upgrade",
            emptyList<Map<String, Any?>>(), tables.rows.getValue("asset_provenance"),
        )
    }

    @Test fun a_walk_frontier_from_before_the_upgrade_does_not_survive_it() {
        val tables = installAtVersion10()

        open(10, database(tables))

        assertEquals(
            "a frontier reached before the binding was carried across the upgrade",
            emptyList<Map<String, Any?>>(), tables.rows.getValue("asset_walk_frontier"),
        )
    }

    /** GUARD. Held outputs, balances, carriers, history, names and settings are not provenance:
     *  every row outside the two provenance tables is exactly as it was. */
    @Test fun every_row_outside_the_provenance_tables_is_kept() {
        val tables = installAtVersion10()
        val before = tables.rows.filterKeys { it !in provenanceTables }.mapValues { it.value.toList() }

        open(10, database(tables))

        val after = tables.rows.filterKeys { it !in provenanceTables }.mapValues { it.value.toList() }
        assertEquals(before, after)
        val carrier = after.getValue("utxos").single()
        assertEquals("the carrier keeps its units", 10L, carrier["asset_quantity"])
        assertEquals("the carrier keeps the id it is shown under", staleAssetId, carrier["asset_id"])
        assertEquals("the carrier stays unspent", false, carrier["spent"])
    }

    /** GUARD. Once. An install already at the current version runs no step, so what the walk
     *  writes after the upgrade is kept on every later launch — and a fresh install, which Room
     *  creates at the current version, runs none either. */
    @Test fun the_discard_runs_once_and_a_fresh_install_runs_nothing() {
        val tables = installAtVersion10()
        open(10, database(tables))
        tables.put("asset_provenance", mapOf("txid" to parent, "assetId" to "La" + "e".repeat(30)))
        tables.put("asset_walk_frontier", mapOf("startTxid" to parent, "resumeTxid" to start))
        val afterFirstLaunch = tables.rows.mapValues { it.value.toList() }

        open(WALLET_DB_VERSION, database(tables))

        assertEquals(
            "a later launch discarded what the walk wrote after the upgrade",
            afterFirstLaunch, tables.rows.mapValues { it.value.toList() },
        )
        assertTrue(
            "a step starts at or beyond the version a fresh install is created at",
            WALLET_DB_MIGRATIONS.none { it.startVersion >= WALLET_DB_VERSION },
        )
    }

    /** GUARD. The upgrade changes rows, never the schema: the current version's exported schema
     *  is version 10's, table for table, so Room's post-step validation holds by construction. */
    @Test fun the_schema_is_unchanged_across_the_upgrade() {
        val v10 = schema(10)
        val current = schema(WALLET_DB_VERSION)

        assertEquals(tablesOf(v10), tablesOf(current))
        assertEquals(v10.getString("identityHash"), current.getString("identityHash"))
    }

    /** GUARD. Every install, from the first version on, reaches the current one. */
    @Test fun every_version_has_a_path_to_the_current_one() {
        for (from in 1..WALLET_DB_VERSION) {
            open(from, mockk(relaxed = true))
        }
    }
}
