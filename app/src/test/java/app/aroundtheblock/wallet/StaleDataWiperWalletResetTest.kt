package app.aroundtheblock.wallet

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.aroundtheblock.wallet.core.sync.CfAbandonmentStore
import app.aroundtheblock.wallet.core.sync.CfRecoveryPolicy
import app.aroundtheblock.wallet.core.sync.CfScanLedgerStore
import app.aroundtheblock.wallet.core.sync.FilterHeaderStore
import app.aroundtheblock.wallet.core.sync.HistoryRebuildOnUpgrade
import app.aroundtheblock.wallet.core.sync.SavedBlockStore
import app.aroundtheblock.wallet.service.cfRestoreResetReason
import app.aroundtheblock.wallet.service.compactFilterBirthHeight
import app.aroundtheblock.wallet.service.floorHintStep
import app.aroundtheblock.wallet.ui.KotlinSourceGate
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The crash-recovery cleanup is a wallet reset, and takes what the project's recovery policy says
 * a wallet reset takes.
 *
 * [CfRecoveryPolicy.Reason.WALLET_RESET] names the startup crash-loop breaker beside the wipe and
 * the restore, and decides that a reset drops the filter-header chain AND the scan ledger. The
 * cleanup [BootGuard] runs after a crash loop or a crashed restore ([StaleDataWiper.wipeSyncBlobs])
 * must therefore leave neither behind — nor the abandoned-band record, which is kept against the
 * ledger's floor — and must get there by acting on that decision rather than on a list of its own.
 * Its writes land before it returns: the breaker runs first thing at start, and the risky start it
 * guards follows at once.
 *
 * Drives the real [BootGuard] and [StaleDataWiper] against an in-memory preferences fake and a
 * real temporary files directory, as [StaleDataWiperFilterHeaderTest] and
 * [BootGuardRestoreBracketTest] do.
 */
class StaleDataWiperWalletResetTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ── What the cleanup takes ───────────────────────────────────────────────

    @Test
    fun `the crash-loop breaker's cleanup takes what a wallet reset takes`() {
        val h = Harness()
        h.syncState()
        h.prefs.store("dgb_boot_guard")["pending_boots"] = BootGuard.THRESHOLD_BLOBS

        BootGuard.beginBoot(h.context)

        h.assertWalletReset("the crash-loop breaker")
    }

    @Test
    fun `the crash-loop breaker's escalated cleanup takes what a wallet reset takes`() {
        val h = Harness()
        h.syncState()
        h.prefs.store("dgb_boot_guard")["pending_boots"] = BootGuard.THRESHOLD_DB

        BootGuard.beginBoot(h.context)

        h.assertWalletReset("the escalated crash-loop breaker")
    }

    @Test
    fun `the crashed-restore cleanup takes what a wallet reset takes`() {
        val h = Harness()
        h.syncState()
        BootGuard.beginRestore(h.context)

        assertEquals(BootGuard.RestoreRecovery.Sync, BootGuard.recoverFromCrashedRestoreIfNeeded(h.context))

        h.assertWalletReset("the crashed-restore recovery")
    }

    // ── What the next starts scan ────────────────────────────────────────────

    /**
     * The cleanup drops the scan ledger before the wallet is loaded, as the upgrade rebuild does,
     * so like the rebuild it leaves the scan floor to be carried down to the wallet's anchor at the
     * next sync start. A synced wallet has no persisted floor (it is removed once the wallet syncs):
     * without that step the first start after the cleanup scans from the anchor, but a start that
     * dies after the first saved-blocks write and before the first ledger write is followed by one
     * that resumes 100 blocks under the saved tip, and what lies below is never scanned. Before this
     * change the ledger survived the cleanup and the restore preflight's reset pinned the floor.
     * The starts are SyncService's own decisions, in its order: [cfRestoreResetReason],
     * [floorHintStep], [compactFilterBirthHeight].
     */
    @Test
    fun `after the cleanup the next starts scan from the wallet's floor, not from near the saved tip`() {
        val h = Harness()
        h.syncState()
        h.prefs.store("dgb_boot_guard")["pending_boots"] = BootGuard.THRESHOLD_BLOBS

        BootGuard.beginBoot(h.context)

        val anchor = 21_500_000L
        val first = h.syncStart(anchor, savedTip = 0L)
        assertTrue("the first start after the cleanup scans from $first, above the wallet's floor $anchor",
            first <= anchor)
        // Headers come in and the saved window is written near the tip; the start dies before its
        // first ledger write.
        h.savedWindow()
        val resumed = h.syncStart(anchor, savedTip = 24_000_000L)
        assertTrue("the start after that scans from $resumed, above the wallet's floor $anchor",
            resumed <= anchor)
    }

    // ── How it writes ────────────────────────────────────────────────────────

    @Test
    fun `the cleanup's preference writes land before it returns`() {
        // An apply() still queued when the guarded start dies is lost, and the next launch finds
        // the very state the breaker cleared.
        val h = Harness()
        h.syncState()

        StaleDataWiper.wipeSyncBlobs(h.context)

        for (name in listOf("dgb_sync_data", "dgb_bloom_peers", "dgb_cf_abandonment")) {
            assertTrue("$name is not cleared with a write that lands before the cleanup returns",
                name in h.prefs.clearsCommitted)
            assertFalse("$name is cleared with apply()", name in h.prefs.clearsApplied)
        }
    }

    @Test
    fun `a cleanup that did not land says so`() {
        // Read as Any?: a cleanup that reports nothing answers Unit, which is neither.
        val clean = Harness().apply { syncState() }
        val reportedClean: Any? = StaleDataWiper.wipeSyncBlobs(clean.context)
        assertEquals("a cleanup that landed", true, reportedClean)

        val prefsRefused = Harness().apply { syncState(); prefs.failCommits += "dgb_sync_data" }
        val reportedPrefs: Any? = StaleDataWiper.wipeSyncBlobs(prefsRefused.context)
        assertEquals("the sync state's clear did not land", false, reportedPrefs)

        // A non-empty directory where the ledger lives cannot be deleted.
        val ledgerStays = Harness().apply {
            syncState()
            ledger.delete()
            ledger.mkdirs()
            File(ledger, "kept").writeText("kept")
        }
        val reportedLedger: Any? = StaleDataWiper.wipeSyncBlobs(ledgerStays.context)
        assertEquals("the scan ledger was not removed", false, reportedLedger)
    }

    // ── Through the policy ───────────────────────────────────────────────────

    @Test
    fun `the cleanup acts on the wallet-reset decision, never on a subset of its own`() {
        val wiper = gate("StaleDataWiper.kt")
        val asks = wiper.calls("CfRecoveryPolicy.decide")
        assertEquals("the cleanup does not ask the recovery policy, once", 1, asks.size)
        assertTrue("the cleanup asks the policy about something other than a wallet reset",
            KotlinSourceGate.squeeze(asks.single().arguments.single()).endsWith("CfRecoveryPolicy.Reason.WALLET_RESET"))
        val decision = Regex("""val\s+(\w+)\s*=\s*$""")
            .find(wiper.code.substring(0, asks.single().range.first).replace(Regex("""[\w.]*$"""), ""))
            ?.groupValues?.get(1)
        assertNotNull("the decision is not held in a val", decision)

        // Each store the policy governs is dropped only under the flag that governs it. The
        // abandoned band is kept against the ledger's floor, so it goes with the ledger.
        for ((store, flag) in listOf(
            "FilterHeaderStore.delete" to "dropFilterChain",
            "CfScanLedgerStore.delete" to "dropScanLedger",
            "CfAbandonmentStore.clear" to "dropScanLedger",
        )) {
            val drops = wiper.calls(store)
            assertTrue("the cleanup never calls $store", drops.isNotEmpty())
            val under = wiper.branchesOn("$decision.$flag")
            for (drop in drops) {
                assertTrue("$store runs outside the decision's $flag", under.any { drop.range.first in it })
            }
        }
        for (other in listOf("FilterHeaderStore.deleteAllNetworks", "CfScanLedgerStore.deleteAllNetworks",
            "CfAbandonmentStore.clearAllNetworks")) {
            assertTrue("the cleanup reaches $other", wiper.calls(other).isEmpty())
        }

        // The breaker reaches that state only through the cleanup.
        val guard = gate("BootGuard.kt")
        for (store in listOf("FilterHeaderStore", "CfScanLedgerStore", "CfAbandonmentStore", "SavedBlockStore")) {
            assertFalse("BootGuard drops $store itself", Regex("(?<!\\w)$store(?!\\w)").containsMatchIn(guard.code))
        }
    }

    private fun gate(name: String): KotlinSourceGate {
        val file = File("src/main/java/app/aroundtheblock/wallet/$name")
        assertTrue("$name is missing — this gate is watching a file that moved", file.exists())
        return KotlinSourceGate.of(file.readText())
    }

    // ==================== test harness ====================

    /** In-memory preferences that also record how each clear was written. Writes land in memory
     *  at once; [failCommits] names the files whose commit() reports failure. */
    private class Prefs {
        private val stores = mutableMapOf<String, MutableMap<String, Any?>>()
        val clearsCommitted = mutableSetOf<String>()
        val clearsApplied = mutableSetOf<String>()
        val failCommits = mutableSetOf<String>()

        fun store(name: String): MutableMap<String, Any?> = stores.getOrPut(name) { mutableMapOf() }

        fun prefsFor(name: String): SharedPreferences {
            val backing = store(name)
            val prefs: SharedPreferences = mockk(relaxed = true)
            every { prefs.getBoolean(any(), any()) } answers { (backing[firstArg<String>()] as? Boolean) ?: secondArg() }
            every { prefs.getInt(any(), any()) } answers { (backing[firstArg<String>()] as? Int) ?: secondArg() }
            every { prefs.getLong(any(), any()) } answers { (backing[firstArg<String>()] as? Long) ?: secondArg() }
            every { prefs.getString(any(), any()) } answers { (backing[firstArg<String>()] as? String) ?: secondArg() }
            every { prefs.contains(any()) } answers { backing.containsKey(firstArg<String>()) }
            every { prefs.edit() } answers { editor(name, backing) }
            return prefs
        }

        private fun editor(name: String, backing: MutableMap<String, Any?>): SharedPreferences.Editor {
            val editor: SharedPreferences.Editor = mockk(relaxed = true)
            var clears = false
            every { editor.putBoolean(any(), any()) } answers { backing[firstArg<String>()] = secondArg<Boolean>(); editor }
            every { editor.putInt(any(), any()) } answers { backing[firstArg<String>()] = secondArg<Int>(); editor }
            every { editor.putLong(any(), any()) } answers { backing[firstArg<String>()] = secondArg<Long>(); editor }
            every { editor.putString(any(), any()) } answers { backing[firstArg<String>()] = secondArg<String?>(); editor }
            every { editor.remove(any()) } answers { backing.remove(firstArg<String>()); editor }
            every { editor.clear() } answers { clears = true; backing.clear(); editor }
            every { editor.commit() } answers {
                if (clears) clearsCommitted += name
                name !in failCommits
            }
            every { editor.apply() } answers { if (clears) clearsApplied += name }
            return editor
        }
    }

    private class Harness {
        val prefs = Prefs()
        private val dir: File = File.createTempFile("wallet-reset-cleanup-test", "").apply { delete(); mkdirs() }
        val context: Context = mockk(relaxed = true)

        init {
            every { context.getSharedPreferences(any(), any()) } answers { prefs.prefsFor(firstArg()) }
            every { context.filesDir } returns dir
            every { context.getDatabasePath(any()) } answers { File(dir, firstArg<String>()) }
        }

        val chain: File get() = FilterHeaderStore.file(context)
        val ledger: File get() = CfScanLedgerStore.file(context)
        private val ledgerInFlight: File get() = File(dir, ledger.name + ".tmp")
        private val blocks: File get() = SavedBlockStore.file(context)

        /** What a wallet that has been syncing leaves behind, a scan with an abandoned band included. */
        fun syncState() {
            chain.writeBytes(byteArrayOf(1, 2, 3))
            ledger.writeBytes(byteArrayOf(4, 5, 6))
            ledgerInFlight.writeBytes(byteArrayOf(7))
            blocks.writeBytes(byteArrayOf(8, 9))
            prefs.store("dgb_sync_data")["has_synced"] = true
            prefs.store("dgb_sync_data")["saved_blocks_tip"] = 24_000_000L
            prefs.store("dgb_bloom_peers")["peers"] = "cached"
            CfAbandonmentStore.noteAbandonment(context, abandonedBelow = 23_000_100L, lowHint = 23_000_000L)
            assertNotNull("test setup: no abandoned band recorded", CfAbandonmentStore.band(context))
            prefs.clearsCommitted.clear()
            prefs.clearsApplied.clear()
        }

        /** A saved-blocks window, as a start that has not synced yet writes one. */
        fun savedWindow() = blocks.writeBytes(byteArrayOf(10, 11, 12))

        /**
         * One sync start as SyncService decides it from what is on disk: the restore preflight, whose
         * reset drops the stored scan state and pins the floor at the wallet's anchor; the rebuild's
         * floor step; then the height the compact-filter scan starts from, which this returns.
         */
        fun syncStart(anchor: Long, savedTip: Long): Long {
            val settings = prefs.store("dgb_settings")
            val sync = prefs.store("dgb_sync_data")
            var synced = sync["has_synced"] == true
            var tip = savedTip
            val reset = cfRestoreResetReason(
                CfScanLedgerStore.load(context),
                SavedBlockStore.load(context),
                synced,
                sync["transactions_checkpointed"] == true || sync.containsKey("saved_transactions"),
            )
            if (reset != null) {
                sync.remove("has_synced")
                SavedBlockStore.delete(context)
                FilterHeaderStore.delete(context)
                CfScanLedgerStore.delete(context)
                CfAbandonmentStore.clear(context)
                if (anchor > 0L) settings["cf_birth_height"] = anchor else settings.remove("cf_birth_height")
                synced = false
                tip = 0L
            }
            val step = floorHintStep(
                walletLoaded = true,
                anchor = anchor,
                persistedBirth = settings["cf_birth_height"] as Long?,
                floorHint = HistoryRebuildOnUpgrade.floorHint(context),
                floorPending = HistoryRebuildOnUpgrade.floorPending(context),
            )
            step.setBirth?.let { settings["cf_birth_height"] = it }
            if (step.removeBirth) settings.remove("cf_birth_height")
            if (step.clearHint) HistoryRebuildOnUpgrade.clearFloorHint(context)
            return compactFilterBirthHeight(
                wasSynced = synced,
                savedTip = tip,
                walletBirth = anchor,
                persistedBirth = settings["cf_birth_height"] as Long?,
            )
        }

        fun assertWalletReset(by: String) {
            val reset = CfRecoveryPolicy.decide(CfRecoveryPolicy.Reason.WALLET_RESET)
            assertTrue("this test's premise moved: a wallet reset no longer drops the chain and the ledger",
                reset.dropFilterChain && reset.dropScanLedger)
            assertFalse("$by left the filter-header chain", chain.exists())
            assertFalse("$by left the scan ledger", ledger.exists())
            assertFalse("$by left a scan ledger being written", ledgerInFlight.exists())
            assertNull("$by left the abandoned band, kept against the dropped ledger", CfAbandonmentStore.band(context))
            assertFalse("$by left the saved-blocks window", blocks.exists())
            assertTrue("$by left the sync state", prefs.store("dgb_sync_data").isEmpty())
            assertTrue("$by left the peer cache", prefs.store("dgb_bloom_peers").isEmpty())
        }
    }
}
