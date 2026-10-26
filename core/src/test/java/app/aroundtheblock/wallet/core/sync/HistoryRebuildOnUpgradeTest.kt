package app.aroundtheblock.wallet.core.sync

import android.content.Context
import android.content.SharedPreferences
import app.aroundtheblock.wallet.core.OutgoingTxStore
import app.aroundtheblock.wallet.core.sync.HistoryRebuildOnUpgrade.CacheSummary
import app.aroundtheblock.wallet.core.sync.HistoryRebuildOnUpgrade.DEFER_CAP_MS
import app.aroundtheblock.wallet.core.sync.HistoryRebuildOnUpgrade.Decision
import app.aroundtheblock.wallet.core.sync.HistoryRebuildOnUpgrade.OutcomeKind
import app.aroundtheblock.wallet.core.sync.HistoryRebuildOnUpgrade.State
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rebuild transaction history once after an update: the decision table, the cache framing read,
 * and the runner's clear. See [HistoryRebuildOnUpgrade].
 */
class HistoryRebuildOnUpgradeTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun reset() = HistoryRebuildOnUpgrade.resetProcessStateForTest()
    @After fun resetAfter() = HistoryRebuildOnUpgrade.resetProcessStateForTest()

    // ── fixtures ──────────────────────────────────────────────────────────

    /** A real serialized transaction body (the signed P2WPKH example from BIP 143, also used by
     *  RawTxBindingTest). The framing read never parses it; it only has to be skipped whole. */
    private val realTx: ByteArray = hex(
        "01000000000102fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f0000000049483045" +
            "0221008b9d1dc26ba6a9cb62127b02742fa9d754cd3bebf337f7a55d114c8e5cdd30be022040529b194ba3f9281a" +
            "99f2b1c0a19c0489bc22ede944ccf4ecbab4cc618ef3ed01eeffffffef51e1b804cc89d182d279655c3aa89e815b" +
            "1b309fe287d9b2b55d57b90ec68a0100000000ffffffff02202cb206000000001976a9148280b37df378db99f66f" +
            "85c95a783a76ac7a6d5988ac9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac" +
            "000247304402203609e17b84f6a7d30c80bfa610b5b4542f32a8a0d5447a12fb1366d7f01cc44a0220573a954c45" +
            "18331561406f90300e8f3358f51928d43c212a8caed02de67eebee0121025476c2e83188368da1ff3e292e7acafc" +
            "db3566bb0ad253f62fc70f07aeee635711000000",
    )

    private data class Rec(val height: Long, val time: Long, val body: ByteArray, val declaredSize: Long = body.size.toLong())

    private fun blob(vararg recs: Rec, count: Long = recs.size.toLong()): ByteArray {
        val size = 4 + recs.sumOf { 12 + it.body.size }
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(count.toInt())
        for (r in recs) {
            b.putInt(r.declaredSize.toInt())
            b.putInt(r.height.toInt())
            b.putInt(r.time.toInt())
            b.put(r.body)
        }
        return b.array()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val nonEmpty = CacheSummary(1L, 1, 20_000_000L, 1_700_000_000L)
    private val now = 1_800_000_000_000L

    private fun ctx(): Context {
        val c = fakeContext()
        every { c.filesDir } returns tmp.newFolder()
        return c
    }

    /** Context whose prefs file [failFile] keeps writes in memory but reports commit() = false. */
    private fun failingCommitContext(failFile: String): Context {
        val stores = HashMap<String, SharedPreferences>()
        val c = mockk<Context>(relaxed = true)
        every { c.getSharedPreferences(any(), any()) } answers {
            val name = firstArg<String>()
            stores.getOrPut(name) { if (name == failFile) FailingCommitPrefs(FakeSharedPreferences()) else FakeSharedPreferences() }
        }
        every { c.filesDir } returns tmp.newFolder()
        return c
    }

    private val lookupNone: (Long) -> Long = { 0L }

    private fun syncPrefs(c: Context) = c.getSharedPreferences("dgb_sync_data", Context.MODE_PRIVATE)
    private fun statePrefs(c: Context) = c.getSharedPreferences("dgb_history_rebuild", Context.MODE_PRIVATE)
    private fun settings(c: Context) = c.getSharedPreferences("dgb_settings", Context.MODE_PRIVATE)

    /** An earlier build's state on one device: a tx cache, the sync keys, every file store, a
     *  surfaced band, recorded sends and the persisted CF floor. */
    private fun seedEarlierBuild(c: Context, cache: ByteArray = blob(Rec(21_000_000L, 1_650_000_000L, realTx))) {
        syncPrefs(c).edit()
            .putString("saved_transactions", toHex(cache))
            .putString("saved_filter_headers", "aa")
            .putString("saved_blocks", "bb")
            .putLong("saved_blocks_tip", 23_000_000L)
            .putBoolean("has_synced", true)
            .putLong("last_balance", 5L)
            .putString("saved_peers", "cc")
            .commit()
        FilterHeaderStore.write(c, byteArrayOf(1), FilterHeaderStore.currentEpoch())
        SavedBlockStore.write(c, byteArrayOf(2), SavedBlockStore.currentEpoch())
        CfScanLedgerStore.write(c, byteArrayOf(3), CfScanLedgerStore.currentEpoch())
        CfAbandonmentStore.noteAbandonment(c, abandonedBelow = 23_000_100L, lowHint = 23_000_050L)
        settings(c).edit().putLong("cf_birth_height", 22_650_000L).commit()
    }

    private fun recordSend(c: Context, txid: String, settled: Boolean) {
        val s = OutgoingTxStore(c)
        s.record(txid, 1_000L, 10L, "D-to", false)
        if (settled) s.markSettled(txid)
    }

    // ── decision table ────────────────────────────────────────────────────

    @Test fun decide_done_isNotNeeded_whateverElse() {
        assertEquals(Decision.NOT_NEEDED, HistoryRebuildOnUpgrade.decide(State(done = true, inProgress = true), nonEmpty, 3, now))
    }

    @Test fun decide_emptyCache_isNotNeeded() {
        assertEquals(Decision.NOT_NEEDED, HistoryRebuildOnUpgrade.decide(State(), CacheSummary.EMPTY, 0, now))
        assertEquals(Decision.NOT_NEEDED, HistoryRebuildOnUpgrade.decide(State(), null as ByteArray?, 0, now))
        assertEquals(Decision.NOT_NEEDED, HistoryRebuildOnUpgrade.decide(State(), byteArrayOf(0, 0, 0, 0), 2, now))
        assertEquals(Decision.NOT_NEEDED, HistoryRebuildOnUpgrade.decide(State(), blob(), 0, now))
    }

    @Test fun decide_inProgress_runs_evenWithPendingSendsAndAnEmptyCache() {
        assertEquals(Decision.RUN, HistoryRebuildOnUpgrade.decide(State(inProgress = true), nonEmpty, 5, now))
        assertEquals(Decision.RUN, HistoryRebuildOnUpgrade.decide(State(inProgress = true), CacheSummary.EMPTY, 5, now))
    }

    @Test fun decide_pendingSend_firstTime_defers() {
        assertEquals(Decision.DEFER, HistoryRebuildOnUpgrade.decide(State(), nonEmpty, 1, now))
    }

    @Test fun decide_pendingSend_underTheCap_defers_andAtExactlyTheCap_runs() {
        val since = now - DEFER_CAP_MS
        assertEquals(Decision.DEFER, HistoryRebuildOnUpgrade.decide(State(deferredSinceMs = since + 1), nonEmpty, 1, now))
        assertEquals(Decision.RUN, HistoryRebuildOnUpgrade.decide(State(deferredSinceMs = since), nonEmpty, 1, now))
        assertEquals(Decision.RUN, HistoryRebuildOnUpgrade.decide(State(deferredSinceMs = since - 1), nonEmpty, 1, now))
    }

    @Test fun decide_clockBeforeTheFirstDeferral_isStillDeferred() {
        assertEquals(Decision.DEFER, HistoryRebuildOnUpgrade.decide(State(deferredSinceMs = now + 1), nonEmpty, 1, now))
        assertEquals(Decision.DEFER, HistoryRebuildOnUpgrade.decide(State(deferredSinceMs = now + 30 * DEFER_CAP_MS), nonEmpty, 1, now))
    }

    @Test fun decide_noPendingSend_runs() {
        assertEquals(Decision.RUN, HistoryRebuildOnUpgrade.decide(State(), nonEmpty, 0, now))
        assertEquals(Decision.RUN, HistoryRebuildOnUpgrade.decide(State(deferredSinceMs = now - 1), nonEmpty, 0, now))
    }

    // ── framing read ──────────────────────────────────────────────────────

    @Test fun summarize_emptyAndZeroCount() {
        assertTrue(HistoryRebuildOnUpgrade.summarize(null).isEmpty)
        assertTrue(HistoryRebuildOnUpgrade.summarize(ByteArray(0)).isEmpty)
        assertTrue(HistoryRebuildOnUpgrade.summarize(byteArrayOf(1, 0, 0)).isEmpty)
        assertTrue(HistoryRebuildOnUpgrade.summarize(byteArrayOf(1, 0, 0, 0)).isEmpty)
        assertTrue(HistoryRebuildOnUpgrade.summarize(blob() + ByteArray(20)).isEmpty)
    }

    @Test fun summarize_oneRealRecord() {
        val s = HistoryRebuildOnUpgrade.summarize(blob(Rec(21_000_000L, 1_650_000_000L, realTx)))
        assertEquals(1, s.records)
        assertEquals(21_000_000L, s.minConfirmedHeight)
        assertEquals(1_650_000_000L, s.minRecordTime)
    }

    @Test fun summarize_mixedHeights_takesTheMinimum_andIgnoresTheUnconfirmedSentinel() {
        val s = HistoryRebuildOnUpgrade.summarize(
            blob(
                Rec(23_000_000L, 1_700_000_000L, realTx),
                Rec(0x7fffffffL, 1_550_000_000L, byteArrayOf(9, 9)), // unconfirmed: no floor height, but its time counts
                Rec(19_500_000L, 1_600_000_000L, byteArrayOf(1)),
                Rec(0L, 500L, byteArrayOf(7)),                       // height 0: not a real height
                Rec(0xfffffff0L, 400L, byteArrayOf(7)),              // above the sentinel: not a real height
                Rec(20_000_000L, 0L, byteArrayOf(2, 3)),             // real height, unknown time
            ),
        )
        assertEquals(6, s.records)
        assertEquals(19_500_000L, s.minConfirmedHeight)
        assertEquals("heights 0 and above the sentinel give no time", 1_550_000_000L, s.minRecordTime)
    }

    @Test fun summarize_allUnconfirmed_givesNoHeightHint_butTheirTimesLowerTheFloorTime() {
        val s = HistoryRebuildOnUpgrade.summarize(blob(Rec(0x7fffffffL, 1_700_000_000L, realTx), Rec(0x7fffffffL, 1_690_000_000L, byteArrayOf(1))))
        assertFalse(s.isEmpty)
        assertEquals(2, s.records)
        assertEquals(0L, s.minConfirmedHeight)
        assertEquals(1_690_000_000L, s.minRecordTime)
    }

    @Test fun summarize_unconfirmedTimeBelowTheConfirmedOnes_lowersOnlyTheTime() {
        val s = HistoryRebuildOnUpgrade.summarize(
            blob(Rec(22_000_000L, 1_690_000_000L, realTx), Rec(0x7fffffffL, 1_600_000_000L, byteArrayOf(1)), Rec(0x7fffffffL, 0L, byteArrayOf(2))),
        )
        assertEquals(22_000_000L, s.minConfirmedHeight)
        assertEquals(1_600_000_000L, s.minRecordTime)
    }

    @Test fun summarize_truncatedMidRecord_keepsTheWholeRecordsOnly() {
        val full = blob(Rec(22_000_000L, 1_690_000_000L, realTx), Rec(18_000_000L, 1_500_000_000L, realTx))
        val cut = full.copyOf(full.size - 10)
        val s = HistoryRebuildOnUpgrade.summarize(cut)
        assertEquals(1, s.records)
        assertEquals(22_000_000L, s.minConfirmedHeight)
        // cut inside the second header
        val cutHeader = full.copyOf(4 + 12 + realTx.size + 6)
        assertEquals(22_000_000L, HistoryRebuildOnUpgrade.summarize(cutHeader).minConfirmedHeight)
    }

    @Test fun summarize_sizeFieldPastTheEnd_stopsWithoutAHintFromIt() {
        val s = HistoryRebuildOnUpgrade.summarize(
            blob(Rec(22_000_000L, 1_690_000_000L, realTx), Rec(10_000_000L, 1_400_000_000L, byteArrayOf(1, 2), declaredSize = 0xffffffffL)),
        )
        assertEquals(1, s.records)
        assertEquals(22_000_000L, s.minConfirmedHeight)
        assertEquals(1_690_000_000L, s.minRecordTime)
    }

    @Test fun summarize_countAboveTheCap_givesNoHint_andDoesNotThrow() {
        val s = HistoryRebuildOnUpgrade.summarize(blob(Rec(1_000L, 1_400_000_000L, realTx), count = 10_001L))
        assertFalse("a non-zero count is not an empty cache", s.isEmpty)
        assertEquals(0, s.records)
        assertEquals(0L, s.minConfirmedHeight)
        val huge = HistoryRebuildOnUpgrade.summarize(byteArrayOf(-1, -1, -1, -1, 0, 0, 0, 0))
        assertEquals(0L, huge.minConfirmedHeight)
        assertEquals(Decision.RUN, HistoryRebuildOnUpgrade.decide(State(), huge, 0, now))
    }

    @Test fun summarize_neverThrows_onArbitraryBytes() {
        val rnd = java.util.Random(7)
        repeat(2_000) {
            val b = ByteArray(rnd.nextInt(64)).also(rnd::nextBytes)
            if (b.size >= 4 && rnd.nextBoolean()) { b[0] = 3; b[1] = 0; b[2] = 0; b[3] = 0 }
            HistoryRebuildOnUpgrade.summarize(b)
        }
    }

    // ── runner ────────────────────────────────────────────────────────────

    @Test fun run_clearsTheCacheAndEveryDerivedStore_keepsRecordedSendsAndTheCfFloor() {
        val c = ctx()
        seedEarlierBuild(c)
        recordSend(c, "aa11", settled = true)

        val out = HistoryRebuildOnUpgrade.runAtProcessStart(c, now)

        assertEquals(OutcomeKind.RAN, out.kind)
        assertEquals(1, out.discarded)
        assertEquals(out, HistoryRebuildOnUpgrade.lastOutcome.value)
        val sync = syncPrefs(c)
        for (k in listOf("saved_transactions", "saved_filter_headers", "saved_blocks", "saved_blocks_tip", "has_synced", "last_balance")) {
            assertFalse("$k survived the rebuild", sync.contains(k))
        }
        assertTrue("keys the manual rebuild keeps are kept", sync.contains("saved_peers"))
        assertNull(FilterHeaderStore.load(c))
        assertNull(SavedBlockStore.load(c))
        assertNull(CfScanLedgerStore.load(c))
        assertNull(CfAbandonmentStore.band(c))
        assertNotNull("recorded sends are kept", OutgoingTxStore(c).lookup("aa11"))
        assertEquals("cf_birth_height is not touched", 22_650_000L, settings(c).getLong("cf_birth_height", 0L))
        val st = HistoryRebuildOnUpgrade.readState(c)
        assertTrue(st.done)
        assertFalse(st.inProgress)
        assertEquals(0L, st.deferredSinceMs)
        assertEquals(21_000_000L, st.floorHint)
        assertEquals(1_650_000_000L, st.floorTime)
    }

    @Test fun run_onceOnly_aSecondLaunchIsNotNeeded_andAFreshCacheIsKept() {
        val c = ctx()
        seedEarlierBuild(c)
        HistoryRebuildOnUpgrade.runAtProcessStart(c, now)
        syncPrefs(c).edit().putString("saved_transactions", toHex(blob(Rec(23_500_000L, 1_750_000_000L, realTx)))).commit()
        HistoryRebuildOnUpgrade.resetProcessStateForTest()

        val out = HistoryRebuildOnUpgrade.runAtProcessStart(c, now + 1)

        assertEquals(OutcomeKind.NOT_NEEDED, out.kind)
        assertTrue(syncPrefs(c).contains("saved_transactions"))
    }

    @Test fun run_interruptedAfterTheInProgressCommit_completesOnTheNextLaunch() {
        val c = ctx()
        seedEarlierBuild(c)
        // The state a process death leaves right after step 2: in progress, part of the cache gone.
        statePrefs(c).edit().putBoolean("in_progress", true).putLong("floor_hint", 21_000_000L).commit()
        syncPrefs(c).edit().remove("saved_transactions").commit()
        recordSend(c, "bb22", settled = false)

        val out = HistoryRebuildOnUpgrade.runAtProcessStart(c, now)

        assertEquals(OutcomeKind.RAN, out.kind)
        assertFalse(syncPrefs(c).contains("has_synced"))
        assertNull(SavedBlockStore.load(c))
        assertNull(CfScanLedgerStore.load(c))
        val st = HistoryRebuildOnUpgrade.readState(c)
        assertTrue(st.done)
        assertFalse(st.inProgress)
        assertEquals("the hint from the first attempt survives", 21_000_000L, st.floorHint)
    }

    @Test fun run_neverRaisesTheFloor() {
        val c = ctx()
        seedEarlierBuild(c, blob(Rec(22_000_000L, 1_690_000_000L, realTx)))
        statePrefs(c).edit()
            .putBoolean("in_progress", true)
            .putLong("floor_hint", 20_000_000L)
            .putLong("floor_time", 1_600_000_000L)
            .commit()

        HistoryRebuildOnUpgrade.runAtProcessStart(c, now)

        val st = HistoryRebuildOnUpgrade.readState(c)
        assertEquals(20_000_000L, st.floorHint)
        assertEquals(1_600_000_000L, st.floorTime)
    }

    @Test fun run_lowersAnExistingHint() {
        val c = ctx()
        seedEarlierBuild(c, blob(Rec(18_000_000L, 1_500_000_000L, realTx)))
        statePrefs(c).edit().putLong("floor_hint", 20_000_000L).putLong("floor_time", 1_600_000_000L).commit()

        HistoryRebuildOnUpgrade.runAtProcessStart(c, now)

        assertEquals(18_000_000L, HistoryRebuildOnUpgrade.readState(c).floorHint)
        assertEquals(1_500_000_000L, HistoryRebuildOnUpgrade.readState(c).floorTime)
    }

    @Test fun emptyCache_stampsDone_andTouchesNothingElse() {
        val c = ctx()
        syncPrefs(c).edit().putBoolean("has_synced", true).commit()

        val out = HistoryRebuildOnUpgrade.runAtProcessStart(c, now)

        assertEquals(OutcomeKind.NOT_NEEDED, out.kind)
        assertTrue(HistoryRebuildOnUpgrade.readState(c).done)
        assertTrue(syncPrefs(c).getBoolean("has_synced", false))
    }

    @Test fun pendingSend_defers_stampsTheFirstDeferralOnly_andKeepsTheCache() {
        val c = ctx()
        seedEarlierBuild(c)
        recordSend(c, "cc33", settled = false)

        assertEquals(OutcomeKind.DEFERRED, HistoryRebuildOnUpgrade.runAtProcessStart(c, now).kind)
        assertEquals(now, HistoryRebuildOnUpgrade.readState(c).deferredSinceMs)
        assertTrue(syncPrefs(c).contains("saved_transactions"))

        HistoryRebuildOnUpgrade.resetProcessStateForTest()
        assertEquals(OutcomeKind.DEFERRED, HistoryRebuildOnUpgrade.runAtProcessStart(c, now + 1_000L).kind)
        assertEquals("the first deferral is kept", now, HistoryRebuildOnUpgrade.readState(c).deferredSinceMs)
        assertFalse(HistoryRebuildOnUpgrade.readState(c).done)

        HistoryRebuildOnUpgrade.resetProcessStateForTest()
        assertEquals(OutcomeKind.RAN, HistoryRebuildOnUpgrade.runAtProcessStart(c, now + DEFER_CAP_MS).kind)
        assertFalse(syncPrefs(c).contains("saved_transactions"))
        assertNotNull(OutgoingTxStore(c).lookup("cc33"))
    }

    @Test fun settledSends_doNotDefer() {
        val c = ctx()
        seedEarlierBuild(c)
        recordSend(c, "dd44", settled = true)
        assertEquals(OutcomeKind.RAN, HistoryRebuildOnUpgrade.runAtProcessStart(c, now).kind)
    }

    @Test fun beforeWalletLoad_keepsTheStartOutcomeForTheRestOfTheProcess() {
        val c = ctx()
        seedEarlierBuild(c)
        recordSend(c, "ee55", settled = false)
        assertEquals(OutcomeKind.DEFERRED, HistoryRebuildOnUpgrade.runAtProcessStart(c, now).kind)

        // The send settles later in the same process; the wallet is loaded again.
        OutgoingTxStore(c).markSettled("ee55")
        assertEquals(OutcomeKind.DEFERRED, HistoryRebuildOnUpgrade.beforeWalletLoad(c, now + 1).kind)
        assertTrue("no clear under a running session", syncPrefs(c).contains("saved_transactions"))

        // The next process start runs it.
        HistoryRebuildOnUpgrade.resetProcessStateForTest()
        assertEquals(OutcomeKind.RAN, HistoryRebuildOnUpgrade.runAtProcessStart(c, now + 2).kind)
    }

    @Test fun beforeWalletLoad_evaluatesWhenTheStartEvaluationDidNotHappen() {
        val c = ctx()
        seedEarlierBuild(c)
        assertEquals(OutcomeKind.RAN, HistoryRebuildOnUpgrade.beforeWalletLoad(c, now).kind)
        assertFalse(syncPrefs(c).contains("saved_transactions"))
    }

    @Test fun markNotNeeded_stampsDone_andForgetsTheFloorOnlyWhenAsked() {
        val c = ctx()
        statePrefs(c).edit().putBoolean("in_progress", true).putLong("floor_hint", 5L).putLong("floor_time", 6L).commit()
        HistoryRebuildOnUpgrade.markNotNeeded(c)
        var st = HistoryRebuildOnUpgrade.readState(c)
        assertTrue(st.done); assertFalse(st.inProgress); assertEquals(5L, st.floorHint); assertEquals(6L, st.floorTime)

        HistoryRebuildOnUpgrade.markNotNeeded(c, forgetFloor = true)
        st = HistoryRebuildOnUpgrade.readState(c)
        assertTrue(st.done); assertEquals(0L, st.floorHint); assertEquals(0L, st.floorTime)
    }

    @Test fun perNetwork_stateAndCacheAreSeparate() {
        val c = ctx()
        seedEarlierBuild(c)
        HistoryRebuildOnUpgrade.runAtProcessStart(c, now)
        // switch to testnet: its own earlier cache is rebuilt on its own first start
        settings(c).edit().putBoolean("dgb_network_testnet", true).commit()
        c.getSharedPreferences("dgb_sync_data_testnet", Context.MODE_PRIVATE).edit()
            .putString("saved_transactions", toHex(blob(Rec(1_000_000L, 1_600_000_000L, realTx)))).commit()
        HistoryRebuildOnUpgrade.resetProcessStateForTest()

        assertEquals(OutcomeKind.RAN, HistoryRebuildOnUpgrade.runAtProcessStart(c, now).kind)
        assertFalse(c.getSharedPreferences("dgb_sync_data_testnet", Context.MODE_PRIVATE).contains("saved_transactions"))
        assertEquals(1_000_000L, c.getSharedPreferences("dgb_history_rebuild_testnet", Context.MODE_PRIVATE).getLong("floor_hint", 0L))
    }

    @Test fun clearFloorHint_forgetsOnlyTheHeight() {
        val c = ctx()
        statePrefs(c).edit().putLong("floor_hint", 5L).putLong("floor_time", 6L).commit()
        HistoryRebuildOnUpgrade.clearFloorHint(c)
        assertEquals(0L, HistoryRebuildOnUpgrade.floorHint(c))
        assertEquals(6L, HistoryRebuildOnUpgrade.floorTime(c))
    }

    // ── repair round ──────────────────────────────────────────────────────

    @Test fun deferral_aClockSteppedBack_doesNotShortenTheCap() {
        val c = ctx()
        seedEarlierBuild(c)
        recordSend(c, "ab12", settled = false)
        val day = 24L * 60 * 60 * 1000

        assertEquals(OutcomeKind.DEFERRED, HistoryRebuildOnUpgrade.runAtProcessStart(c, now, lookupNone).kind)
        HistoryRebuildOnUpgrade.resetProcessStateForTest()
        assertEquals(OutcomeKind.DEFERRED, HistoryRebuildOnUpgrade.runAtProcessStart(c, now - 20 * day, lookupNone).kind)
        assertEquals("the first deferral stands", now, HistoryRebuildOnUpgrade.readState(c).deferredSinceMs)
        HistoryRebuildOnUpgrade.resetProcessStateForTest()
        assertEquals(OutcomeKind.DEFERRED, HistoryRebuildOnUpgrade.runAtProcessStart(c, now + day, lookupNone).kind)
        assertTrue(syncPrefs(c).contains("saved_transactions"))
        HistoryRebuildOnUpgrade.resetProcessStateForTest()
        assertEquals(OutcomeKind.RAN, HistoryRebuildOnUpgrade.runAtProcessStart(c, now + DEFER_CAP_MS, lookupNone).kind)
    }

    @Test fun run_floorTime_reachesTheCheckpointAtOrBelowTheHint_whateverTheRecordTimes() {
        val c = ctx()
        seedEarlierBuild(c, blob(Rec(21_000_000L, 0L, realTx), Rec(22_000_000L, 1_800_000_000L, realTx)))
        val asked = ArrayList<Long>()
        HistoryRebuildOnUpgrade.runAtProcessStart(c, now) { h -> asked += h; if (h == 21_000_000L) 1_650_000_000L else 0L }
        assertEquals(listOf(21_000_000L), asked)
        assertEquals(21_000_000L, HistoryRebuildOnUpgrade.readState(c).floorHint)
        assertEquals(1_650_000_000L, HistoryRebuildOnUpgrade.readState(c).floorTime)
    }

    @Test fun run_floorTime_isTheLowerOfTheRecordAndCheckpointTimes() {
        val c = ctx()
        seedEarlierBuild(c, blob(Rec(21_000_000L, 1_500_000_000L, realTx)))
        HistoryRebuildOnUpgrade.runAtProcessStart(c, now) { 1_650_000_000L }
        assertEquals(1_500_000_000L, HistoryRebuildOnUpgrade.readState(c).floorTime)
    }

    @Test fun run_checkpointLookupThatThrows_fallsBackToTheRecordTime() {
        val c = ctx()
        seedEarlierBuild(c, blob(Rec(21_000_000L, 1_650_000_000L, realTx)))
        assertEquals(OutcomeKind.RAN, HistoryRebuildOnUpgrade.runAtProcessStart(c, now) { error("no native library") }.kind)
        assertEquals(1_650_000_000L, HistoryRebuildOnUpgrade.readState(c).floorTime)
    }

    @Test fun run_asksTheLookupForTheFinalHint_notARaisedOne() {
        val c = ctx()
        seedEarlierBuild(c, blob(Rec(22_000_000L, 1_690_000_000L, realTx)))
        statePrefs(c).edit().putBoolean("in_progress", true).putLong("floor_hint", 20_000_000L).commit()
        val asked = ArrayList<Long>()
        HistoryRebuildOnUpgrade.runAtProcessStart(c, now) { h -> asked += h; 1_600_000_000L }
        assertEquals(listOf(20_000_000L), asked)
        assertEquals(1_600_000_000L, HistoryRebuildOnUpgrade.readState(c).floorTime)
    }

    @Test fun run_whenTheSyncClearDoesNotLand_throws_andLeavesItInProgress() {
        val c = failingCommitContext("dgb_sync_data")
        seedEarlierBuild(c)
        assertThrows(IllegalStateException::class.java) { HistoryRebuildOnUpgrade.runAtProcessStart(c, now, lookupNone) }
        val st = HistoryRebuildOnUpgrade.readState(c)
        assertFalse("done must not be stamped over a clear that did not land", st.done)
        assertTrue(st.inProgress)
        assertTrue(HistoryRebuildOnUpgrade.isInProgress(c))
    }

    @Test fun run_whenAStoreFileWillNotDelete_throws_andLeavesItInProgress() {
        val c = ctx()
        seedEarlierBuild(c)
        // A directory with a child where the saved-blocks file lives: delete() cannot remove it.
        val f = SavedBlockStore.file(c)
        f.delete(); f.mkdirs(); java.io.File(f, "child").writeText("x")
        assertThrows(IllegalStateException::class.java) { HistoryRebuildOnUpgrade.runAtProcessStart(c, now, lookupNone) }
        assertFalse(HistoryRebuildOnUpgrade.readState(c).done)
        assertTrue(HistoryRebuildOnUpgrade.readState(c).inProgress)
    }

    @Test fun clearTransactionCacheForRebuild_reportsSuccessAndFailure() {
        val ok = ctx()
        seedEarlierBuild(ok)
        assertTrue(clearTransactionCacheForRebuild(ok))
        val bad = failingCommitContext("dgb_sync_data")
        seedEarlierBuild(bad)
        assertFalse(clearTransactionCacheForRebuild(bad))
    }

    @Test fun emptyCache_whenTheSyncCommitFails_doesNotStampDone() {
        val c = failingCommitContext("dgb_sync_data")
        assertThrows(IllegalStateException::class.java) { HistoryRebuildOnUpgrade.runAtProcessStart(c, now, lookupNone) }
        assertFalse(HistoryRebuildOnUpgrade.readState(c).done)
    }

    @Test fun run_marksTheRoomTableForClearing_inTheInProgressCommit() {
        val c = ctx()
        seedEarlierBuild(c)
        HistoryRebuildOnUpgrade.runAtProcessStart(c, now, lookupNone)
        assertTrue(statePrefs(c).getBoolean(HistoryRebuildOnUpgrade.KEY_ROOM_CLEAR_PENDING, false))
    }

    @Test fun notNeededOrDeferred_doNotMarkTheRoomTable() {
        val empty = ctx()
        HistoryRebuildOnUpgrade.runAtProcessStart(empty, now, lookupNone)
        assertFalse(statePrefs(empty).getBoolean(HistoryRebuildOnUpgrade.KEY_ROOM_CLEAR_PENDING, false))
        HistoryRebuildOnUpgrade.resetProcessStateForTest()
        val deferred = ctx()
        seedEarlierBuild(deferred)
        recordSend(deferred, "cd34", settled = false)
        HistoryRebuildOnUpgrade.runAtProcessStart(deferred, now, lookupNone)
        assertFalse(statePrefs(deferred).getBoolean(HistoryRebuildOnUpgrade.KEY_ROOM_CLEAR_PENDING, false))
    }

    @Test fun roomClear_runsOnce_thenDropsTheFlag() = runBlocking {
        val c = ctx()
        statePrefs(c).edit().putBoolean(HistoryRebuildOnUpgrade.KEY_ROOM_CLEAR_PENDING, true).commit()
        var cleared = 0
        assertTrue(HistoryRebuildOnUpgrade.clearRoomTransactionsIfPending(c) { cleared++ })
        assertEquals(1, cleared)
        assertFalse(statePrefs(c).contains(HistoryRebuildOnUpgrade.KEY_ROOM_CLEAR_PENDING))
        assertFalse(HistoryRebuildOnUpgrade.clearRoomTransactionsIfPending(c) { cleared++ })
        assertEquals(1, cleared)
    }

    @Test fun roomClear_thatThrows_keepsTheFlagForTheNextStart() = runBlocking {
        val c = ctx()
        statePrefs(c).edit().putBoolean(HistoryRebuildOnUpgrade.KEY_ROOM_CLEAR_PENDING, true).commit()
        assertFalse(HistoryRebuildOnUpgrade.clearRoomTransactionsIfPending(c) { error("db closed") })
        assertTrue(statePrefs(c).getBoolean(HistoryRebuildOnUpgrade.KEY_ROOM_CLEAR_PENDING, false))
    }

    @Test fun armRerun_makesTheNextStartRunAgain_keepingTheFloors() {
        val c = ctx()
        statePrefs(c).edit().putBoolean("done", true).putLong("floor_hint", 5L).putLong("floor_time", 6L).commit()
        assertTrue(HistoryRebuildOnUpgrade.armRerun(c))
        val st = HistoryRebuildOnUpgrade.readState(c)
        assertFalse(st.done); assertTrue(st.inProgress); assertEquals(5L, st.floorHint); assertEquals(6L, st.floorTime)
        assertEquals(OutcomeKind.RAN, HistoryRebuildOnUpgrade.runAtProcessStart(c, now, lookupNone).kind)
        assertTrue(HistoryRebuildOnUpgrade.readState(c).done)
    }

    // ── cleanup round ─────────────────────────────────────────────────────

    @Test fun clear_removesTheDigiDollarBalanceSnapshot() {
        val c = ctx()
        seedEarlierBuild(c)
        syncPrefs(c).edit().putLong("last_dd_balance", 150L).commit()
        HistoryRebuildOnUpgrade.runAtProcessStart(c, now, lookupNone)
        assertFalse(syncPrefs(c).contains("last_dd_balance"))
    }

    @Test fun run_withOnlyUnconfirmedRecords_marksTheFloorPending() {
        val c = ctx()
        seedEarlierBuild(c, blob(Rec(0x7fffffffL, 1_600_000_000L, realTx)))
        HistoryRebuildOnUpgrade.runAtProcessStart(c, now, lookupNone)
        val st = HistoryRebuildOnUpgrade.readState(c)
        assertEquals(0L, st.floorHint)
        assertEquals(1_600_000_000L, st.floorTime)
        assertTrue("a floor time alone must still be carried to the scan start", HistoryRebuildOnUpgrade.floorPending(c))
    }

    @Test fun run_withAFloorHeight_marksTheFloorPending_andWithoutAnyFloor_doesNot() {
        val c = ctx()
        seedEarlierBuild(c)
        HistoryRebuildOnUpgrade.runAtProcessStart(c, now, lookupNone)
        assertTrue(HistoryRebuildOnUpgrade.floorPending(c))
        HistoryRebuildOnUpgrade.resetProcessStateForTest()
        val none = ctx()
        seedEarlierBuild(none, blob(Rec(0x7fffffffL, 0L, realTx)))
        HistoryRebuildOnUpgrade.runAtProcessStart(none, now, lookupNone)
        assertFalse(HistoryRebuildOnUpgrade.floorPending(none))
    }

    @Test fun thePendingMarker_goesWithTheHint_andWithADifferentWallet() {
        val c = ctx()
        statePrefs(c).edit().putBoolean(HistoryRebuildOnUpgrade.KEY_FLOOR_PENDING, true).putLong("floor_hint", 5L).commit()
        HistoryRebuildOnUpgrade.clearFloorHint(c)
        assertFalse(HistoryRebuildOnUpgrade.floorPending(c))
        statePrefs(c).edit().putBoolean(HistoryRebuildOnUpgrade.KEY_FLOOR_PENDING, true).commit()
        HistoryRebuildOnUpgrade.markNotNeeded(c)
        assertTrue("same wallet keeps it", HistoryRebuildOnUpgrade.floorPending(c))
        HistoryRebuildOnUpgrade.markNotNeeded(c, forgetFloor = true)
        assertFalse(HistoryRebuildOnUpgrade.floorPending(c))
    }
}

/** Keeps writes in memory, as Android does, but reports that commit() did not reach disk. */
internal class FailingCommitPrefs(private val inner: FakeSharedPreferences) : SharedPreferences by inner {
    override fun edit(): SharedPreferences.Editor {
        val e = inner.edit()
        return object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { e.putString(key, value) }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { e.putStringSet(key, values) }
            override fun putInt(key: String, value: Int) = apply { e.putInt(key, value) }
            override fun putLong(key: String, value: Long) = apply { e.putLong(key, value) }
            override fun putFloat(key: String, value: Float) = apply { e.putFloat(key, value) }
            override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(key, value) }
            override fun remove(key: String) = apply { e.remove(key) }
            override fun clear() = apply { e.clear() }
            override fun commit(): Boolean { e.commit(); return false }
            override fun apply() { e.apply() }
        }
    }
}
