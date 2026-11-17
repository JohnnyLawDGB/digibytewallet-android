package app.aroundtheblock.wallet.core.settings

import android.content.Context
import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.db.dao.WalletConfigDao
import app.aroundtheblock.wallet.core.db.entity.WalletConfigEntity
import app.aroundtheblock.wallet.core.wallets.WalletScope
import app.aroundtheblock.wallet.core.wallets.WalletStores
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-lock timeout is one app-wide value ([AutoLockPrefs]), moved once out of the legacy wallet's
 * database.
 *
 * It lived in `wallet_config.autoLockTimeoutMs`, and since multi-wallet step 1 every wallet has its
 * own database: the lock read the active wallet's row, so a wallet just added (no row) enforced the
 * 60 s default whatever its owner had chosen, and a choice made in one wallet did not hold in
 * another. One PIN and one lock policy cover every wallet.
 *
 * Which database may answer the move is decided in core, by [AutoLockPrefs.readLegacy], and these arms
 * run that function itself. MainActivity's block is that one call over the database its process has
 * open (`AppWideSettingsSourceGateTest` holds it to exactly that); [launch] is the same block over a
 * stand-in database.
 */
class AutoLockPrefsTest {

    private val device = InMemoryPrefsFactory()
    private val ctx: Context = mockk<Context>(relaxed = true).also { ctx ->
        every { ctx.getSharedPreferences(any(), any()) } answers { device.prefsFor(firstArg()) }
        every { ctx.applicationContext } returns ctx
    }
    private val file get() = device.prefsFor(AutoLockPrefs.PREFS)

    private val legacyMain = WalletScope.legacy(testnet = false)
    private val legacyTest = WalletScope.legacy(testnet = true)
    private val added = WalletScope("0123456789abcdef", testnet = false)
    private val addedTest = WalletScope("0123456789abcdef", testnet = true)

    private val chosenIn414 = 15 * 60_000L

    /** One wallet's database, counting the reads of its `wallet_config` row. */
    private class WalletDb(private val row: WalletConfigEntity?) : WalletConfigDao {
        var reads = 0
        override fun observe(): Flow<WalletConfigEntity?> = flowOf(row)
        override suspend fun get(): WalletConfigEntity? { reads++; return row }
        override suspend fun upsert(config: WalletConfigEntity) = throw AssertionError("the move writes no wallet database")
    }

    /** A database whose row cannot be read. */
    private class UnreadableDb : WalletConfigDao {
        override fun observe(): Flow<WalletConfigEntity?> = throw AssertionError("the move does not observe")
        override suspend fun get(): WalletConfigEntity? = throw IllegalStateException("database not opened")
        override suspend fun upsert(config: WalletConfigEntity) = throw AssertionError("the move writes no wallet database")
    }

    /**
     * `openHelper.databaseName` of the temporary in-memory database a Keystore fault gives a session:
     * Room's `inMemoryDatabaseBuilder` builds it with no name.
     */
    private val temporary: String? = null

    /** The name of the database AppModule opens from disk for [scope] (`WalletStores.databaseName`). */
    private fun onDisk(scope: WalletScope): String = WalletStores.databaseName(scope)

    /** MainActivity's block, for a launch running [scope] with [db] open as [databaseName]: core's decision, nothing else. */
    private fun launch(scope: WalletScope, db: WalletConfigDao, databaseName: String? = onDisk(scope)): suspend () -> AutoLockPrefs.Legacy =
        { AutoLockPrefs.readLegacy(scope, databaseName, db) }

    /** The timeout the inactivity lock enforces: the app-wide value, else the default (`autoLockTimeoutOrDefault`). */
    private fun enforced(): Long = AutoLockPrefs.storedTimeoutMs(ctx) ?: AutoLockPrefs.DEFAULT_TIMEOUT_MS

    // ── Which database may answer the move ─────────────────────────────────────────────────────────

    @Test fun `legacy's own mainnet database from disk answers with its row`() = runTest {
        assertEquals("wallet.db", onDisk(legacyMain))
        val legacyDb = WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414))
        assertEquals(AutoLockPrefs.Legacy.Answered(chosenIn414), AutoLockPrefs.readLegacy(legacyMain, onDisk(legacyMain), legacyDb))
        assertEquals(1, legacyDb.reads)

        val noRow = WalletDb(row = null)
        assertEquals("no row is an answer: nothing to carry", AutoLockPrefs.Legacy.Answered(null), AutoLockPrefs.readLegacy(legacyMain, onDisk(legacyMain), noRow))
        assertEquals(1, noRow.reads)
    }

    /** A launch's open database: the wallet its process runs and the database's name. */
    private data class Open(val what: String, val scope: WalletScope, val databaseName: String?)

    @Test fun `no other database is read for the move, and none answers it`() = runTest {
        val others = listOf(
            Open("legacy's session on the temporary database", legacyMain, temporary),
            Open("legacy's testnet database", legacyTest, onDisk(legacyTest)),
            Open("legacy's testnet session on the temporary database", legacyTest, temporary),
            Open("an added wallet's database", added, onDisk(added)),
            Open("an added wallet's session on the temporary database", added, temporary),
            Open("an added wallet's testnet database", addedTest, onDisk(addedTest)),
        )
        for (open in others) {
            // Each holds a timeout, so an answer from any of them would carry a value that is not legacy's.
            val db = WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414))
            assertEquals(open.what, AutoLockPrefs.Legacy.NotHere, AutoLockPrefs.readLegacy(open.scope, open.databaseName, db))
            assertEquals("${open.what} was read for the move", 0, db.reads)
        }
    }

    // ── The move ───────────────────────────────────────────────────────────────────────────────────

    @Test fun `the legacy wallet's timeout is carried into the app-wide value`() = runTest {
        val legacyDb = WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414))

        assertTrue(AutoLockPrefs.migrateFromLegacy(ctx, launch(legacyMain, legacyDb)))

        assertEquals(chosenIn414, AutoLockPrefs.storedTimeoutMs(ctx))
        assertEquals(chosenIn414, file.durable[AutoLockPrefs.KEY_TIMEOUT_MS])
        assertEquals(true, file.durable[AutoLockPrefs.KEY_LEGACY_MOVED])
        assertEquals(chosenIn414, enforced())
    }

    @Test fun `a wallet added after the move enforces the carried timeout, not its own database's default`() = runTest {
        AutoLockPrefs.migrateFromLegacy(ctx, launch(legacyMain, WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414))))

        // The add restarts into the new wallet; its database holds no wallet_config row.
        val addedDb = WalletDb(row = null)
        assertTrue("the move is done; the added wallet's launch has nothing to move", AutoLockPrefs.migrateFromLegacy(ctx, launch(added, addedDb)))
        assertEquals("the added wallet's launch read a wallet database", 0, addedDb.reads)
        assertEquals("the added wallet does not enforce the timeout chosen before it existed", chosenIn414, enforced())

        // The control: the lock as it read before, from the active wallet's row (default: the entity's).
        val replacedRead = addedDb.get()?.autoLockTimeoutMs ?: WalletConfigEntity().autoLockTimeoutMs
        assertNotEquals("control: the replaced read gave the added wallet the chosen timeout, so this cannot see the bug", chosenIn414, replacedRead)
    }

    @Test fun `a move left waiting by a temporary database is not finished by a wallet added afterwards`() = runTest {
        // The first launch after the update: a Keystore fault gives legacy's session a temporary database.
        val temporaryDb = WalletDb(row = null)
        assertFalse(AutoLockPrefs.migrateFromLegacy(ctx, launch(legacyMain, temporaryDb, databaseName = temporary)))
        // The owner then adds a wallet. The add restarts into it, and its new database holds no row.
        val addedDb = WalletDb(row = null)
        assertFalse("the added wallet's empty database finished the move", AutoLockPrefs.migrateFromLegacy(ctx, launch(added, addedDb)))
        assertEquals("a database that is not legacy's own was read for the move", 0, temporaryDb.reads + addedDb.reads)
        assertFalse(AutoLockPrefs.legacyMoved(ctx))
        assertFalse(file.durable.containsKey(AutoLockPrefs.KEY_LEGACY_MOVED))

        // Switched back to legacy, its database opened from disk: the 4.1.4 choice is carried, not lost.
        val legacyDb = WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414))
        assertTrue(AutoLockPrefs.migrateFromLegacy(ctx, launch(legacyMain, legacyDb)))
        assertEquals(chosenIn414, AutoLockPrefs.storedTimeoutMs(ctx))
        assertEquals(chosenIn414, enforced())
    }

    @Test fun `a timeout chosen since the update is never overwritten by the move`() = runTest {
        // Chosen in an added wallet's process before a launch ever had legacy's database open.
        assertTrue(AutoLockPrefs.setTimeoutMs(ctx, 5 * 60_000L))
        val legacyDb = WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414))

        assertTrue(AutoLockPrefs.migrateFromLegacy(ctx, launch(legacyMain, legacyDb)))

        assertEquals(5 * 60_000L, AutoLockPrefs.storedTimeoutMs(ctx))
        assertTrue("the move was not marked done", AutoLockPrefs.legacyMoved(ctx))
    }

    @Test fun `a legacy database with no config row carries nothing and the default holds`() = runTest {
        val legacyDb = WalletDb(row = null)

        assertTrue(AutoLockPrefs.migrateFromLegacy(ctx, launch(legacyMain, legacyDb)))

        assertNull("the default was written down", AutoLockPrefs.storedTimeoutMs(ctx))
        assertFalse(file.durable.containsKey(AutoLockPrefs.KEY_TIMEOUT_MS))
        assertEquals(AutoLockPrefs.DEFAULT_TIMEOUT_MS, enforced())
        assertTrue(AutoLockPrefs.legacyMoved(ctx))
    }

    @Test fun `once moved, legacy's database is never read again`() = runTest {
        val legacyDb = WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414))
        repeat(3) { AutoLockPrefs.migrateFromLegacy(ctx, launch(legacyMain, legacyDb)) }
        assertEquals(1, legacyDb.reads)
    }

    @Test fun `the move waits while the database open is not legacy's own`() = runTest {
        val legacyDb = WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414))
        // Another wallet's process, a testnet session, and this session's temporary database.
        val notLegacys = listOf(launch(added, legacyDb), launch(legacyTest, legacyDb), launch(legacyMain, legacyDb, databaseName = temporary))
        for (read in notLegacys) assertFalse("the move finished without legacy's database", AutoLockPrefs.migrateFromLegacy(ctx, read))
        assertEquals("a database that is not legacy's own was read for the move", 0, legacyDb.reads)
        assertNull(AutoLockPrefs.storedTimeoutMs(ctx))
        assertFalse(AutoLockPrefs.legacyMoved(ctx))

        // The first launch with legacy's own database open carries it.
        assertTrue(AutoLockPrefs.migrateFromLegacy(ctx, launch(legacyMain, legacyDb)))
        assertEquals(chosenIn414, AutoLockPrefs.storedTimeoutMs(ctx))
    }

    @Test fun `a legacy read that fails leaves the move for the next launch`() = runTest {
        // Legacy's database open, its row unreadable; then the block failing before it reaches the database.
        assertFalse(AutoLockPrefs.migrateFromLegacy(ctx, launch(legacyMain, UnreadableDb())))
        assertFalse(AutoLockPrefs.migrateFromLegacy(ctx) { throw UninitializedPropertyAccessException("walletDatabase") })
        assertFalse(AutoLockPrefs.legacyMoved(ctx))

        assertTrue(AutoLockPrefs.migrateFromLegacy(ctx, launch(legacyMain, WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414)))))
        assertEquals(chosenIn414, AutoLockPrefs.storedTimeoutMs(ctx))
    }

    @Test fun `a write that does not land is reported, by the choice and by the move`() = runTest {
        device.refuseWritesTo(AutoLockPrefs.PREFS)
        assertFalse(AutoLockPrefs.setTimeoutMs(ctx, 5 * 60_000L))
        assertFalse(AutoLockPrefs.migrateFromLegacy(ctx, launch(legacyMain, WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414)))))
        assertFalse("a move that did not reach the disk was recorded as done", file.durable.containsKey(AutoLockPrefs.KEY_LEGACY_MOVED))
    }

    @Test fun `the default is the wallet database's, unchanged by the move`() {
        assertEquals(WalletConfigEntity().autoLockTimeoutMs, AutoLockPrefs.DEFAULT_TIMEOUT_MS)
        assertEquals(60_000L, AutoLockPrefs.DEFAULT_TIMEOUT_MS)
    }
}
