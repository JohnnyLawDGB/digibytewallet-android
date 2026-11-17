package app.aroundtheblock.wallet.core.settings

import android.content.Context
import app.aroundtheblock.wallet.core.InMemoryPrefsFactory
import app.aroundtheblock.wallet.core.db.dao.WalletConfigDao
import app.aroundtheblock.wallet.core.db.entity.WalletConfigEntity
import app.aroundtheblock.wallet.core.wallets.WalletScope
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
 * MainActivity hands the move legacy's row only when legacy's own mainnet database is the one open,
 * from disk; [legacyRead] is that same decision over a stand-in database.
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

    private val chosenIn414 = 15 * 60_000L

    /** One wallet's database, counting the reads of its `wallet_config` row. */
    private class WalletDb(private val row: WalletConfigEntity?) : WalletConfigDao {
        var reads = 0
        override fun observe(): Flow<WalletConfigEntity?> = flowOf(row)
        override suspend fun get(): WalletConfigEntity? { reads++; return row }
        override suspend fun upsert(config: WalletConfigEntity) = throw AssertionError("the move writes no wallet database")
    }

    /** What a launch running [scope] hands the move: its database's row only when that is legacy's own. */
    private fun legacyRead(scope: WalletScope, db: WalletDb, onDisk: Boolean = true): suspend () -> AutoLockPrefs.Legacy = {
        if (AutoLockPrefs.holdsLegacyTimeout(scope, onDisk)) AutoLockPrefs.Legacy.Answered(db.get()?.autoLockTimeoutMs)
        else AutoLockPrefs.Legacy.NotHere
    }

    /** The timeout the inactivity lock enforces: the app-wide value, else the default (`autoLockTimeoutOrDefault`). */
    private fun enforced(): Long = AutoLockPrefs.storedTimeoutMs(ctx) ?: AutoLockPrefs.DEFAULT_TIMEOUT_MS

    @Test fun `the legacy wallet's timeout is carried into the app-wide value`() = runTest {
        val legacyDb = WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414))

        assertTrue(AutoLockPrefs.migrateFromLegacy(ctx, legacyRead(legacyMain, legacyDb)))

        assertEquals(chosenIn414, AutoLockPrefs.storedTimeoutMs(ctx))
        assertEquals(chosenIn414, file.durable[AutoLockPrefs.KEY_TIMEOUT_MS])
        assertEquals(true, file.durable[AutoLockPrefs.KEY_LEGACY_MOVED])
        assertEquals(chosenIn414, enforced())
    }

    @Test fun `a wallet added after the move enforces the carried timeout, not its own database's default`() = runTest {
        AutoLockPrefs.migrateFromLegacy(ctx, legacyRead(legacyMain, WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414))))

        // The add restarts into the new wallet; its database holds no wallet_config row.
        val addedDb = WalletDb(row = null)
        assertTrue("the move is done; the added wallet's launch has nothing to move", AutoLockPrefs.migrateFromLegacy(ctx, legacyRead(added, addedDb)))
        assertEquals("the added wallet's launch read a wallet database", 0, addedDb.reads)
        assertEquals("the added wallet does not enforce the timeout chosen before it existed", chosenIn414, enforced())

        // The control: the lock as it read before, from the active wallet's row (default: the entity's).
        val replacedRead = addedDb.get()?.autoLockTimeoutMs ?: WalletConfigEntity().autoLockTimeoutMs
        assertNotEquals("control: the replaced read gave the added wallet the chosen timeout, so this cannot see the bug", chosenIn414, replacedRead)
    }

    @Test fun `a timeout chosen since the update is never overwritten by the move`() = runTest {
        // Chosen in an added wallet's process before a launch ever had legacy's database open.
        assertTrue(AutoLockPrefs.setTimeoutMs(ctx, 5 * 60_000L))
        val legacyDb = WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414))

        assertTrue(AutoLockPrefs.migrateFromLegacy(ctx, legacyRead(legacyMain, legacyDb)))

        assertEquals(5 * 60_000L, AutoLockPrefs.storedTimeoutMs(ctx))
        assertTrue("the move was not marked done", AutoLockPrefs.legacyMoved(ctx))
    }

    @Test fun `a legacy database with no config row carries nothing and the default holds`() = runTest {
        val legacyDb = WalletDb(row = null)

        assertTrue(AutoLockPrefs.migrateFromLegacy(ctx, legacyRead(legacyMain, legacyDb)))

        assertNull("the default was written down", AutoLockPrefs.storedTimeoutMs(ctx))
        assertFalse(file.durable.containsKey(AutoLockPrefs.KEY_TIMEOUT_MS))
        assertEquals(AutoLockPrefs.DEFAULT_TIMEOUT_MS, enforced())
        assertTrue(AutoLockPrefs.legacyMoved(ctx))
    }

    @Test fun `once moved, legacy's database is never read again`() = runTest {
        val legacyDb = WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414))
        repeat(3) { AutoLockPrefs.migrateFromLegacy(ctx, legacyRead(legacyMain, legacyDb)) }
        assertEquals(1, legacyDb.reads)
    }

    @Test fun `the move waits while the database open is not legacy's own`() = runTest {
        val legacyDb = WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414))
        // Another wallet's process, a testnet session, and this session's temporary database.
        val notLegacys = listOf(legacyRead(added, legacyDb), legacyRead(legacyTest, legacyDb), legacyRead(legacyMain, legacyDb, onDisk = false))
        for (read in notLegacys) assertFalse("the move finished without legacy's database", AutoLockPrefs.migrateFromLegacy(ctx, read))
        assertEquals("a database that is not legacy's own was read for the move", 0, legacyDb.reads)
        assertNull(AutoLockPrefs.storedTimeoutMs(ctx))
        assertFalse(AutoLockPrefs.legacyMoved(ctx))

        // The first launch with legacy's own database open carries it.
        assertTrue(AutoLockPrefs.migrateFromLegacy(ctx, legacyRead(legacyMain, legacyDb)))
        assertEquals(chosenIn414, AutoLockPrefs.storedTimeoutMs(ctx))
    }

    @Test fun `only the legacy wallet's mainnet database from disk holds the 4-1-4 timeout`() {
        assertTrue(AutoLockPrefs.holdsLegacyTimeout(legacyMain, databaseOnDisk = true))
        assertFalse("a temporary database", AutoLockPrefs.holdsLegacyTimeout(legacyMain, databaseOnDisk = false))
        assertFalse("testnet", AutoLockPrefs.holdsLegacyTimeout(legacyTest, databaseOnDisk = true))
        assertFalse("another wallet", AutoLockPrefs.holdsLegacyTimeout(added, databaseOnDisk = true))
        assertFalse(AutoLockPrefs.holdsLegacyTimeout(WalletScope("0123456789abcdef", testnet = true), databaseOnDisk = true))
    }

    @Test fun `a legacy read that fails leaves the move for the next launch`() = runTest {
        assertFalse(AutoLockPrefs.migrateFromLegacy(ctx) { throw IllegalStateException("database not opened") })
        assertFalse(AutoLockPrefs.legacyMoved(ctx))

        assertTrue(AutoLockPrefs.migrateFromLegacy(ctx, legacyRead(legacyMain, WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414)))))
        assertEquals(chosenIn414, AutoLockPrefs.storedTimeoutMs(ctx))
    }

    @Test fun `a write that does not land is reported, by the choice and by the move`() = runTest {
        device.refuseWritesTo(AutoLockPrefs.PREFS)
        assertFalse(AutoLockPrefs.setTimeoutMs(ctx, 5 * 60_000L))
        assertFalse(AutoLockPrefs.migrateFromLegacy(ctx, legacyRead(legacyMain, WalletDb(WalletConfigEntity(autoLockTimeoutMs = chosenIn414)))))
        assertFalse("a move that did not reach the disk was recorded as done", file.durable.containsKey(AutoLockPrefs.KEY_LEGACY_MOVED))
    }

    @Test fun `the default is the wallet database's, unchanged by the move`() {
        assertEquals(WalletConfigEntity().autoLockTimeoutMs, AutoLockPrefs.DEFAULT_TIMEOUT_MS)
        assertEquals(60_000L, AutoLockPrefs.DEFAULT_TIMEOUT_MS)
    }
}
