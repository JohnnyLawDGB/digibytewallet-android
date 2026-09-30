package io.digibyte.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rebuild transaction history once after an update — the app-side wiring: the scan floor the
 * rebuild leaves behind lowers `cf_birth_height` (never raises it), and the rebuild runs at
 * process start before any service can read the sync state.
 */
class HistoryRebuildWiringTest {

    // ── floor: pure function ─────────────────────────────────────────────

    @Test fun noHint_doesNothing() {
        assertEquals(FloorHintStep(), floorHintStep(walletLoaded = true, anchor = 19_000_000L, persistedBirth = 22_650_000L, floorHint = 0L))
    }

    @Test fun walletNotLoaded_keepsTheHint_andWritesNothing() {
        assertEquals(FloorHintStep(), floorHintStep(walletLoaded = false, anchor = 0L, persistedBirth = 22_650_000L, floorHint = 19_500_000L))
    }

    @Test fun anchorAboveTheHint_keepsTheHint_andWritesNothing() {
        assertEquals(
            FloorHintStep(anchorAboveHint = true),
            floorHintStep(walletLoaded = true, anchor = 22_650_000L, persistedBirth = 22_650_000L, floorHint = 19_500_000L),
        )
        assertEquals(
            FloorHintStep(anchorAboveHint = true),
            floorHintStep(walletLoaded = true, anchor = 21_500_000L, persistedBirth = null, floorHint = 19_500_000L),
        )
    }

    @Test fun anchorAtOrBelowTheHint_lowersAHigherFloorToTheAnchor_andClearsTheHint() {
        assertEquals(
            FloorHintStep(setBirth = 19_000_000L, clearHint = true),
            floorHintStep(walletLoaded = true, anchor = 19_000_000L, persistedBirth = 22_650_000L, floorHint = 19_500_000L),
        )
        assertEquals(
            FloorHintStep(setBirth = 19_500_000L, clearHint = true),
            floorHintStep(walletLoaded = true, anchor = 19_500_000L, persistedBirth = 22_650_000L, floorHint = 19_500_000L),
        )
    }

    @Test fun noPersistedFloor_persistsTheAnchor() {
        assertEquals(
            FloorHintStep(setBirth = 19_000_000L, clearHint = true),
            floorHintStep(walletLoaded = true, anchor = 19_000_000L, persistedBirth = null, floorHint = 19_500_000L),
        )
    }

    @Test fun aFloorAlreadyAtOrBelowTheHint_staysAsItIs() {
        assertEquals(
            FloorHintStep(clearHint = true),
            floorHintStep(walletLoaded = true, anchor = 19_000_000L, persistedBirth = 15_000_000L, floorHint = 19_500_000L),
        )
        assertEquals(
            FloorHintStep(clearHint = true),
            floorHintStep(walletLoaded = true, anchor = 19_000_000L, persistedBirth = 19_500_000L, floorHint = 19_500_000L),
        )
    }

    @Test fun genesisAnchor_isAValidAnchor_notAnUnloadedWallet() {
        // A higher persisted floor cannot be lowered to 0 by writing it: removing the pref makes
        // the scan start fall back to the wallet birth, which is the genesis anchor.
        assertEquals(
            FloorHintStep(removeBirth = true, clearHint = true),
            floorHintStep(walletLoaded = true, anchor = 0L, persistedBirth = 22_650_000L, floorHint = 19_500_000L),
        )
        assertEquals(
            FloorHintStep(clearHint = true),
            floorHintStep(walletLoaded = true, anchor = 0L, persistedBirth = null, floorHint = 19_500_000L),
        )
    }

    @Test fun theResultFeedsTheExistingBirthChoice_atOrBelowTheHint() {
        val step = floorHintStep(walletLoaded = true, anchor = 19_000_000L, persistedBirth = 22_650_000L, floorHint = 19_500_000L)
        val persisted = if (step.removeBirth) null else step.setBirth ?: 22_650_000L
        val start = compactFilterBirthHeight(wasSynced = false, savedTip = 0L, walletBirth = 19_000_000L, persistedBirth = persisted)
        assertTrue(start <= 19_500_000L)
    }

    // ── call sites ───────────────────────────────────────────────────────

    private val app: String by lazy { File("src/main/java/io/digibyte/DigiByteApp.kt").readText() }
    private val service: String by lazy { File("src/main/java/io/digibyte/service/SyncService.kt").readText() }

    @Test fun theRebuildRunsAtProcessStart_afterTheNetworkAndBootGuard_beforeAnyWork() {
        val onCreate = app.substring(app.indexOf("override fun onCreate()"))
        val net = onCreate.indexOf("applyNetworkSelection()")
        val guard = onCreate.indexOf("BootGuard.recoverFrom")
        val run = onCreate.indexOf("HistoryRebuildOnUpgrade.runAtProcessStart(")
        val work = onCreate.indexOf("scheduleBackgroundSync()")
        assertTrue("scanner is blind", net >= 0 && guard >= 0 && work >= 0)
        assertTrue("the rebuild does not run in onCreate", run >= 0)
        assertTrue("the rebuild must run after the network is selected", run > net)
        assertTrue("the rebuild must run after the boot guard", run > guard)
        assertTrue("the rebuild must run before any background work is scheduled", run < work)
        assertTrue("a throw must not stop the app starting", onCreate.substring(net, run).contains("runCatching"))
    }

    @Test fun theServiceCarriesTheFloorIntoTheBirthBeforeItPicksTheScanStart() {
        val hint = service.indexOf("HistoryRebuildOnUpgrade.floorHint(")
        val firstBirth = service.indexOf("compactFilterBirthHeight(")
        assertTrue("scanner is blind", firstBirth >= 0)
        assertTrue("the floor hint is not consumed", hint >= 0)
        assertTrue("the floor hint must be applied before the scan start is chosen", hint < firstBirth)
        val between = service.substring(hint, firstBirth)
        assertTrue("the step is not the pure function", between.contains("floorHintStep("))
        assertTrue("gated on a loaded wallet, not on a non-zero anchor", between.contains("NativeBridge.isWalletLoaded()"))
        assertTrue(between.contains("\"cf_birth_height\""))
        val clear = between.indexOf("HistoryRebuildOnUpgrade.clearFloorHint(")
        assertTrue(clear >= 0)
        assertTrue("the hint is cleared only when the step says so", between.substring(0, clear).contains("if (step.clearHint)"))
    }

    private fun startSyncBody(): String {
        val start = service.indexOf("private suspend fun startSyncWithTor()")
        return service.substring(start, service.indexOf("\n    private ", start + 1).let { if (it < 0) service.length else it })
    }

    @Test fun theRoomTableIsClearedBeforeThePeerManagerMayRun() {
        val b = startSyncBody()
        val clear = b.indexOf("HistoryRebuildOnUpgrade.clearRoomTransactionsIfPending(")
        val gate = b.indexOf("NativeBridge.markSavedBlocksLoadComplete()")
        assertTrue("scanner is blind", gate >= 0)
        assertTrue("the Room table is not cleared after a rebuild", clear >= 0)
        assertTrue("clear before the peer manager can deliver transactions", clear < gate)
        assertTrue(b.substring(clear, gate).contains("transactionDao.deleteAll()"))
    }

    @Test fun theOwnershipPruneSitsBehindTheMaintenanceGate() {
        assertTrue("an ungated reconcile call remains", !service.contains("reconcileAssetRowsLocally()"))
        val call = service.indexOf("reconcileAssetRowsLocally(pruneUnowned = pruneGateOpen)")
        assertTrue("the reconcile does not take the gate", call >= 0)
        val gate = service.lastIndexOf("val pruneGateOpen = assetPruneGateOpen(", call)
        assertTrue("the gate is not computed before the reconcile", gate in 0 until call)
    }
}
