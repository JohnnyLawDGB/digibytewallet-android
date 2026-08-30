package io.digibyte.core.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.dao.DigiIdHistoryDao
import io.digibyte.core.db.entity.AssetMetadataEntity
import io.digibyte.core.db.entity.DigiIdHistoryEntity
import io.digibyte.core.db.entity.UtxoEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

private const val TEST_DB_NAME = "migration-test"

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WalletDatabase::class.java
    )

    // Full v2 in-memory database (plain Room, no SQLCipher) for DAO-level tests
    private lateinit var db: WalletDatabase

    @Before
    fun setupInMemory() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WalletDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // Migration path: v1 → v2
    // -------------------------------------------------------------------------

    @Test
    @Throws(IOException::class)
    fun migrate1To2_newTablesAndColumnsExist() {
        // Create a version-1 database using the MigrationTestHelper (no SQLCipher)
        val v1Db = helper.createDatabase(TEST_DB_NAME, 1)

        // Populate the v1 utxos table with one row so we can verify the new
        // asset_quantity column lands with its DEFAULT 0 after migration.
        v1Db.execSQL(
            "INSERT INTO utxos (txid, vout, scriptPubKey, satoshis, blockHeight, is_asset, asset_id, spent) " +
                "VALUES ('migtest_tx', 0, X'', 100000, 1000, 0, NULL, 0)"
        )

        // Populate wallet_config so we can verify the new Tor columns
        v1Db.execSQL(
            "INSERT INTO wallet_config (id, creationTimestamp, defaultAddressFormat, lastSyncHeight, fiatCurrency, autoLockTimeoutMs) " +
                "VALUES (1, 0, 2, 0, 'USD', 60000)"
        )

        v1Db.close()

        // Run the migration from 1 → 2
        val v2Db = helper.runMigrationsAndValidate(TEST_DB_NAME, 2, true, MIGRATION_1_2)

        // Verify asset_quantity column exists and has default 0
        val utxoCursor = v2Db.query("SELECT asset_quantity FROM utxos WHERE txid = 'migtest_tx'")
        assertTrue("utxos row not found after migration", utxoCursor.moveToFirst())
        assertEquals(0L, utxoCursor.getLong(0))
        utxoCursor.close()

        // Verify torEnabled and torPromptShown columns exist with default 0
        val configCursor = v2Db.query("SELECT torEnabled, torPromptShown FROM wallet_config WHERE id = 1")
        assertTrue("wallet_config row not found after migration", configCursor.moveToFirst())
        assertEquals(0, configCursor.getInt(0))
        assertEquals(0, configCursor.getInt(1))
        configCursor.close()

        // Verify asset_metadata table exists and is queryable
        val assetCursor = v2Db.query("SELECT COUNT(*) FROM asset_metadata")
        assertTrue(assetCursor.moveToFirst())
        assertEquals(0, assetCursor.getInt(0))
        assetCursor.close()

        // Verify digiid_history table exists and is queryable
        val digiIdCursor = v2Db.query("SELECT COUNT(*) FROM digiid_history")
        assertTrue(digiIdCursor.moveToFirst())
        assertEquals(0, digiIdCursor.getInt(0))
        digiIdCursor.close()

        v2Db.close()
    }

    // -------------------------------------------------------------------------
    // DAO-level smoke tests against the in-memory v2 database
    // -------------------------------------------------------------------------

    @Test
    fun assetMetadataDao_insertAndRetrieve() = runTest {
        val dao: AssetMetadataDao = db.assetMetadataDao()
        val entity = AssetMetadataEntity(
            assetId = "Ua9UVkALVFTHnHFLnqPc1n7xJj7Rrm4kM3",
            name = "TestAsset",
            symbol = "TST",
            decimals = 2,
            totalSupply = 1_000_000L,
            cachedAt = System.currentTimeMillis()
        )
        dao.insert(entity)

        val result = dao.getMetadata("Ua9UVkALVFTHnHFLnqPc1n7xJj7Rrm4kM3")
        assertNotNull(result)
        assertEquals("TestAsset", result!!.name)
        assertEquals("TST", result.symbol)
        assertEquals(2, result.decimals)
        assertEquals(1_000_000L, result.totalSupply)
    }

    @Test
    fun assetMetadataDao_getAllMetadata_flow() = runTest {
        val dao: AssetMetadataDao = db.assetMetadataDao()
        dao.insert(AssetMetadataEntity(assetId = "asset1", name = "Alpha"))
        dao.insert(AssetMetadataEntity(assetId = "asset2", name = "Beta"))

        val all = dao.getAllMetadata().first()
        assertEquals(2, all.size)
        val names = all.map { it.name }.toSet()
        assertTrue(names.contains("Alpha"))
        assertTrue(names.contains("Beta"))
    }

    @Test
    fun assetMetadataDao_insertReplace_updatesExisting() = runTest {
        val dao: AssetMetadataDao = db.assetMetadataDao()
        dao.insert(AssetMetadataEntity(assetId = "asset1", name = "Old Name"))
        dao.insert(AssetMetadataEntity(assetId = "asset1", name = "New Name"))

        val result = dao.getMetadata("asset1")
        assertEquals("New Name", result!!.name)
    }

    @Test
    fun digiIdHistoryDao_insertAndGetHistory() = runTest {
        val dao: DigiIdHistoryDao = db.digiIdHistoryDao()
        dao.insert(DigiIdHistoryEntity(
            domain = "example.com",
            callbackUrl = "digiid://example.com/callback",
            address = "dgb1qtest",
            timestamp = 1000L,
            success = true
        ))
        dao.insert(DigiIdHistoryEntity(
            domain = "other.com",
            callbackUrl = "digiid://other.com/callback",
            address = "dgb1qtest2",
            timestamp = 2000L,
            success = false
        ))

        val history = dao.getHistory().first()
        assertEquals(2, history.size)
        // Should be ordered by timestamp DESC
        assertEquals("other.com", history[0].domain)
        assertEquals("example.com", history[1].domain)
    }

    // -------------------------------------------------------------------------
    // Migration path: v8 → v9 (Digi-ID key isolation — derivation column)
    // -------------------------------------------------------------------------

    @Test
    @Throws(IOException::class)
    fun migrate8To9_backfillsLegacyDerivation() {
        val v8Db = helper.createDatabase(TEST_DB_NAME, 8)
        v8Db.execSQL(
            """INSERT INTO digiid_history (domain, callbackUrl, address, timestamp, success)
               VALUES ('example.com', 'https://example.com/cb', 'DExample', 1000, 1)"""
        )
        v8Db.close()

        val v9Db = helper.runMigrationsAndValidate(TEST_DB_NAME, 9, true, MIGRATION_8_9)
        v9Db.query("SELECT derivation FROM digiid_history WHERE domain = 'example.com'").use { c ->
            assertTrue(c.moveToFirst())
            // Every pre-migration login was signed with the shared m/0'/0/0 key — the
            // backfill records that fact, and IdentityKeyPolicy grandfathers on it.
            assertEquals("legacy", c.getString(0))
        }
        v9Db.close()
    }

    @Test
    fun digiIdHistoryDao_countSuccessfulLegacy_ignoresFailuresAndSiteRows() = runTest {
        val dao: DigiIdHistoryDao = db.digiIdHistoryDao()
        // Successful legacy login → grandfathers the domain
        dao.insert(DigiIdHistoryEntity(
            domain = "old-site.com", callbackUrl = "https://old-site.com/cb",
            address = "DLegacy", timestamp = 100L, success = true, derivation = "legacy"
        ))
        // Failed legacy attempt → must NOT grandfather
        dao.insert(DigiIdHistoryEntity(
            domain = "failed.com", callbackUrl = "https://failed.com/cb",
            address = "DLegacy", timestamp = 100L, success = false, derivation = "legacy"
        ))
        // Successful per-site login → must NOT count as legacy
        dao.insert(DigiIdHistoryEntity(
            domain = "new-site.com", callbackUrl = "https://new-site.com/cb",
            address = "DSite", timestamp = 100L, success = true, derivation = "site"
        ))

        assertEquals(1, dao.countSuccessfulLegacy("old-site.com"))
        assertEquals(0, dao.countSuccessfulLegacy("failed.com"))
        assertEquals(0, dao.countSuccessfulLegacy("new-site.com"))
        assertEquals(0, dao.countSuccessfulLegacy("never-seen.com"))
    }

    @Test
    fun digiIdHistoryDao_pruneOlderThan_removesOldEntries() = runTest {
        val dao: DigiIdHistoryDao = db.digiIdHistoryDao()
        dao.insert(DigiIdHistoryEntity(
            domain = "old.com",
            callbackUrl = "digiid://old.com/callback",
            address = "dgb1qold",
            timestamp = 500L,
            success = true
        ))
        dao.insert(DigiIdHistoryEntity(
            domain = "new.com",
            callbackUrl = "digiid://new.com/callback",
            address = "dgb1qnew",
            timestamp = 5000L,
            success = true
        ))

        dao.pruneOlderThan(1000L)

        val history = dao.getHistory().first()
        assertEquals(1, history.size)
        assertEquals("new.com", history[0].domain)
    }

    @Test
    fun utxoDao_assetQuantity_storedAndRetrieved() = runTest {
        val dao = db.utxoDao()
        // Insert an asset UTXO with a known asset_quantity (decoded from OP_RETURN)
        val assetUtxo = UtxoEntity(
            txid = "asset_tx_1",
            vout = 0,
            scriptPubKey = byteArrayOf(),
            satoshis = 700L,          // dust amount — NOT the real asset count
            blockHeight = 12345L,
            isAsset = true,
            assetId = "Ua9UVkALVFTHnHFLnqPc1n7xJj7Rrm4kM3",
            assetQuantity = 500L       // real asset count decoded from OP_RETURN
        )
        dao.insertAll(listOf(assetUtxo))

        val balances = dao.getAssetBalances().first()
        assertEquals(1, balances.size)
        assertEquals("Ua9UVkALVFTHnHFLnqPc1n7xJj7Rrm4kM3", balances[0].assetId)
        assertEquals(500L, balances[0].totalQuantity)
        assertEquals(1, balances[0].utxoCount)
    }

    @Test
    fun utxoDao_getAssetBalances_groupsByAssetId() = runTest {
        val dao = db.utxoDao()
        dao.insertAll(listOf(
            UtxoEntity("tx1", 0, byteArrayOf(), 700L, 1000L, isAsset = true, assetId = "assetA", assetQuantity = 100L),
            UtxoEntity("tx2", 0, byteArrayOf(), 700L, 1001L, isAsset = true, assetId = "assetA", assetQuantity = 200L),
            UtxoEntity("tx3", 0, byteArrayOf(), 700L, 1002L, isAsset = true, assetId = "assetB", assetQuantity = 50L)
        ))

        val balances = dao.getAssetBalances().first()
        assertEquals(2, balances.size)

        val byId = balances.associateBy { it.assetId }
        assertEquals(300L, byId["assetA"]!!.totalQuantity)
        assertEquals(2, byId["assetA"]!!.utxoCount)
        assertEquals(50L, byId["assetB"]!!.totalQuantity)
        assertEquals(1, byId["assetB"]!!.utxoCount)
    }

    @Test
    fun utxoDao_getAssetBalances_excludesSpentUtxos() = runTest {
        val dao = db.utxoDao()
        dao.insertAll(listOf(
            UtxoEntity("tx1", 0, byteArrayOf(), 700L, 1000L, isAsset = true, assetId = "assetA", assetQuantity = 100L),
            UtxoEntity("tx2", 0, byteArrayOf(), 700L, 1001L, isAsset = true, assetId = "assetA", assetQuantity = 200L)
        ))
        dao.markSpent("tx1", 0)

        val balances = dao.getAssetBalances().first()
        assertEquals(1, balances.size)
        assertEquals(200L, balances[0].totalQuantity)
        assertEquals(1, balances[0].utxoCount)
    }
}
