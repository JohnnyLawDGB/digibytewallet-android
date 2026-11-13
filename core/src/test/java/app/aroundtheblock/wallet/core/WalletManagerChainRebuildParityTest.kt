package app.aroundtheblock.wallet.core

import android.content.Context
import android.content.SharedPreferences
import app.aroundtheblock.wallet.core.sync.FakeSharedPreferences
import app.aroundtheblock.wallet.core.sync.HistoryRebuildOnUpgrade
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The manual "Full rebuild from chain" against the shared core's order and stop rules
 * (BRChainRebuild.h): STOP_SYNC, ARM_RERUN, FLOOR_FROM_CACHE, then the clears; and
 * LOWER_FLOOR_TO, STOP_SYNC, ARM_RERUN and FLOOR_FROM_CACHE stop the rebuild when they fail
 * (BRChainRebuildContinuesAfterFailure 0), before anything is cleared. The iOS port's
 * executor is held to the same header.
 */
class WalletManagerChainRebuildParityTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun reset() = HistoryRebuildOnUpgrade.resetProcessStateForTest()
    @After fun resetAfter() = HistoryRebuildOnUpgrade.resetProcessStateForTest()

    /** One confirmed record, framing only: count 1; size 2, height 21,777,216, time 1,656,283,264. */
    private val oneRecordHex = "01000000" + "02000000" + "404b4c01" + "80e0b862" + "abcd"
    private val cacheHeight = 21_777_216L
    private val cacheTime = 1_656_283_264L

    /**
     * Fails any commit that puts [failKey], as the platform does: SharedPreferences puts the new
     * values in memory before the disk write, so a commit that returns false still leaves them in
     * memory, where any later commit to the file in the same process writes them to disk. Every
     * other commit lands.
     */
    private class FailOnKeyPrefs(private val inner: FakeSharedPreferences, private val failKey: String) :
        SharedPreferences by inner {
        override fun edit(): SharedPreferences.Editor {
            val e = inner.edit()
            var poisoned = false
            return object : SharedPreferences.Editor {
                override fun putString(key: String, value: String?) = apply { poisoned = poisoned || key == failKey; e.putString(key, value) }
                override fun putStringSet(key: String, values: MutableSet<String>?) = apply { poisoned = poisoned || key == failKey; e.putStringSet(key, values) }
                override fun putInt(key: String, value: Int) = apply { poisoned = poisoned || key == failKey; e.putInt(key, value) }
                override fun putLong(key: String, value: Long) = apply { poisoned = poisoned || key == failKey; e.putLong(key, value) }
                override fun putFloat(key: String, value: Float) = apply { poisoned = poisoned || key == failKey; e.putFloat(key, value) }
                override fun putBoolean(key: String, value: Boolean) = apply { poisoned = poisoned || key == failKey; e.putBoolean(key, value) }
                override fun remove(key: String) = apply { e.remove(key) }
                override fun clear() = apply { e.clear() }
                override fun commit(): Boolean { val landed = e.commit(); return landed && !poisoned }
                override fun apply() { e.apply() }
            }
        }
    }

    /** A context whose rebuild-state prefs fail any commit that puts [failKey] (null: none fail). */
    private fun ctx(failKey: String? = null): Context {
        val stores = HashMap<String, SharedPreferences>()
        val c = mockk<Context>(relaxed = true)
        every { c.getSharedPreferences(any(), any()) } answers {
            val name = firstArg<String>()
            stores.getOrPut(name) {
                if (failKey != null && name == HistoryRebuildOnUpgrade.PREFS_BASE) FailOnKeyPrefs(FakeSharedPreferences(), failKey)
                else FakeSharedPreferences()
            }
        }
        every { c.filesDir } returns tmp.newFolder()
        return c
    }

    private fun walletManager(c: Context, stopSync: () -> Unit = { }) = WalletManager(
        context = c,
        keyStoreManager = mockk(relaxed = true),
        utxoManager = mockk(relaxed = true),
        dataEraser = mockk(relaxed = true),
        quiesceNative = { },
        stopSyncForRebuild = stopSync,
    )

    private fun sync(c: Context) = c.getSharedPreferences("dgb_sync_data", Context.MODE_PRIVATE)
    private fun settings(c: Context) = c.getSharedPreferences("dgb_settings", Context.MODE_PRIVATE)

    /** A wallet with a cache, a stored floor, a recorded send and a rebuild already done. */
    private fun seed(c: Context) {
        sync(c).edit().putString("saved_transactions", oneRecordHex).putBoolean("has_synced", true).commit()
        settings(c).edit().putLong("cf_birth_height", 5L).commit()
        c.getSharedPreferences(HistoryRebuildOnUpgrade.PREFS_BASE, Context.MODE_PRIVATE).edit().putBoolean("done", true).commit()
        OutgoingTxStore(c).record("ff66", 1L, 1L, "to")
    }

    private fun assertNothingCleared(c: Context) {
        assertTrue("the transaction cache was cleared", sync(c).contains("saved_transactions"))
        assertTrue("has_synced was cleared", sync(c).contains("has_synced"))
        assertEquals("the stored floor was touched", 5L, settings(c).getLong("cf_birth_height", -1L))
        assertNotNull("the recorded sends were dropped", OutgoingTxStore(c).lookup("ff66"))
    }

    // ── a. FLOOR_FROM_CACHE ──────────────────────────────────────────────────

    @Test fun theRebuildCarriesTheCachesFloorIntoTheRecordBeforeClearing() {
        val c = ctx()
        seed(c)

        assertTrue(walletManager(c).rebuildFromChainRescan())

        assertFalse("precondition: the cache is cleared", sync(c).contains("saved_transactions"))
        assertEquals("the cache's oldest confirmed height is in the record", cacheHeight, HistoryRebuildOnUpgrade.floorHint(c))
        assertTrue("the cache's oldest time is in the record", HistoryRebuildOnUpgrade.floorTime(c) in 1L..cacheTime)
        assertTrue("the record is pending for the next sync start", HistoryRebuildOnUpgrade.floorPending(c))
    }

    @Test fun aRescanFromABlockAboveTheCacheCarriesTheCachesLowerFloor() {
        val c = ctx()
        seed(c)

        assertTrue(walletManager(c).rebuildFromChainRescanFrom(23_000_000L))

        assertEquals("the lower of the two floors", cacheHeight, HistoryRebuildOnUpgrade.floorHint(c))
        assertTrue(HistoryRebuildOnUpgrade.floorPending(c))
    }

    @Test fun aRescanFromABlockBelowTheCacheKeepsThatBlock() {
        val c = ctx()
        seed(c)

        assertTrue(walletManager(c).rebuildFromChainRescanFrom(20_000_000L))

        assertEquals(20_000_000L, HistoryRebuildOnUpgrade.floorHint(c))
    }

    // ── b. the steps the clears depend on stop the rebuild ───────────────────

    @Test fun aStopSyncThatFails_stopsTheRebuild_nothingCleared() {
        val c = ctx()
        seed(c)

        val rebuilt = walletManager(c, stopSync = { throw IllegalStateException("peer lock") }).rebuildFromChainRescan()

        assertFalse("the caller must be told nothing was rebuilt", rebuilt)
        assertNothingCleared(c)
        assertTrue("the rerun was armed after a failed stop", HistoryRebuildOnUpgrade.readState(c).done)
        assertFalse(HistoryRebuildOnUpgrade.isInProgress(c))
    }

    @Test fun anArmRerunThatDoesNotLand_stopsTheRebuild_nothingCleared_andDisarmsTheRerun() {
        val c = ctx(failKey = HistoryRebuildOnUpgrade.KEY_IN_PROGRESS)
        seed(c)

        assertFalse(walletManager(c).rebuildFromChainRescan())

        assertNothingCleared(c)
        // The failed arm is still in memory; the next commit to the file in this process would write
        // it to disk and the next start would run a rebuild the user was told did not happen.
        val s = HistoryRebuildOnUpgrade.readState(c)
        assertTrue("the failed arm's done=false is left in memory", s.done)
        assertFalse("the failed arm's in_progress=true is left in memory", s.inProgress)
    }

    @Test fun aFloorFromCacheThatDoesNotLand_stopsTheRebuild_andDisarmsTheRerun() {
        val c = ctx(failKey = HistoryRebuildOnUpgrade.KEY_FLOOR_HINT)
        seed(c)

        assertFalse(walletManager(c).rebuildFromChainRescan())

        assertNothingCleared(c)
        val s = HistoryRebuildOnUpgrade.readState(c)
        assertTrue("a true abort puts done back", s.done)
        assertFalse("a true abort takes in-progress off", s.inProgress)
    }

    /**
     * SharedPreferences keeps a failed commit's values in memory, so the cache's floor that
     * floorFromCache could not write would reach disk with the disarm's commit unless the disarm
     * writes the prior floor back too. "Nothing was rebuilt" means the record is as it was.
     */
    @Test fun aFloorFromCacheThatDoesNotLand_leavesTheRecordsFloorAsItWas() {
        val c = ctx(failKey = HistoryRebuildOnUpgrade.KEY_FLOOR_HINT)
        seed(c)
        val prior = HistoryRebuildOnUpgrade.readState(c)
        val priorPending = HistoryRebuildOnUpgrade.floorPending(c)

        assertFalse(walletManager(c).rebuildFromChainRescan())

        assertEquals("the cache's floor must not stay in the record", prior.floorHint, HistoryRebuildOnUpgrade.floorHint(c))
        assertEquals(prior.floorTime, HistoryRebuildOnUpgrade.floorTime(c))
        assertEquals(priorPending, HistoryRebuildOnUpgrade.floorPending(c))
    }

    @Test fun aLowerFloorToThatDoesNotLand_stopsTheRescanFromAHeight_nothingClearedNothingArmed() {
        val c = ctx(failKey = HistoryRebuildOnUpgrade.KEY_FLOOR_HINT)
        seed(c)

        assertFalse(walletManager(c).rebuildFromChainRescanFrom(23_000_000L))

        assertNothingCleared(c)
        val s = HistoryRebuildOnUpgrade.readState(c)
        assertTrue("the rebuild must stop before it arms the rerun", s.done)
        assertFalse(s.inProgress)
    }

    @Test fun aRebuildWhoseStepsLand_reportsItRebuilt() {
        val c = ctx()
        seed(c)

        assertTrue(walletManager(c).rebuildFromChainRescan())

        assertFalse(sync(c).contains("saved_transactions"))
        assertTrue(HistoryRebuildOnUpgrade.isInProgress(c))
    }
}
