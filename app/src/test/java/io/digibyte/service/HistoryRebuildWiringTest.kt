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

    @Test fun noHint_leavesTheFloorUnchanged() {
        assertNull(compactFilterBirthWithFloorHint(persistedBirth = 22_650_000L, walletBirth = 22_650_000L, floorHint = 0L))
        assertNull(compactFilterBirthWithFloorHint(persistedBirth = null, walletBirth = 22_650_000L, floorHint = 0L))
    }

    @Test fun hintBelowTheBirth_lowersTheFloorToTheCheckpointAtOrBelowIt() {
        // walletBirth is the native checkpoint for the (lowered) anchor time: at or below the hint.
        assertEquals(
            19_000_000L,
            compactFilterBirthWithFloorHint(persistedBirth = 22_650_000L, walletBirth = 19_000_000L, floorHint = 19_500_000L),
        )
        assertEquals(
            19_000_000L,
            compactFilterBirthWithFloorHint(persistedBirth = null, walletBirth = 19_000_000L, floorHint = 19_500_000L),
        )
        assertEquals(
            "the checkpoint may be exactly at the hint",
            19_500_000L,
            compactFilterBirthWithFloorHint(persistedBirth = 22_650_000L, walletBirth = 19_500_000L, floorHint = 19_500_000L),
        )
    }

    @Test fun hintAboveTheBirth_doesNotRaiseIt() {
        assertNull(compactFilterBirthWithFloorHint(persistedBirth = 22_650_000L, walletBirth = 22_650_000L, floorHint = 23_000_000L))
        assertNull(
            "a lower persisted floor stays",
            compactFilterBirthWithFloorHint(persistedBirth = 15_000_000L, walletBirth = 19_000_000L, floorHint = 19_500_000L),
        )
    }

    @Test fun anchorAboveTheHint_isNotUsed_andTheExistingFloorStays() {
        // The native anchor could not be lowered (no usable record time): a height below the
        // resident chain cannot be served, so nothing unresolvable is written.
        assertNull(compactFilterBirthWithFloorHint(persistedBirth = 22_650_000L, walletBirth = 22_650_000L, floorHint = 19_500_000L))
        assertNull(compactFilterBirthWithFloorHint(persistedBirth = null, walletBirth = 21_500_000L, floorHint = 19_500_000L))
    }

    @Test fun neverWritesANonPositiveFloor() {
        assertNull(compactFilterBirthWithFloorHint(persistedBirth = null, walletBirth = 0L, floorHint = 19_500_000L))
        assertNull(compactFilterBirthWithFloorHint(persistedBirth = 0L, walletBirth = 0L, floorHint = 19_500_000L))
    }

    @Test fun theNewFloorFeedsTheExistingBirthChoice() {
        val floor = compactFilterBirthWithFloorHint(persistedBirth = 22_650_000L, walletBirth = 19_000_000L, floorHint = 19_500_000L)
        assertEquals(
            19_000_000L,
            compactFilterBirthHeight(wasSynced = false, savedTip = 0L, walletBirth = 19_000_000L, persistedBirth = floor ?: 22_650_000L),
        )
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
        assertTrue(between.contains("compactFilterBirthWithFloorHint("))
        assertTrue(between.contains("\"cf_birth_height\""))
        assertTrue(between.contains("HistoryRebuildOnUpgrade.clearFloorHint("))
    }
}
