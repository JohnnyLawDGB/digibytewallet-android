package app.aroundtheblock.wallet.core.asset

import android.util.Log
import app.aroundtheblock.wallet.core.asset.send.AssetStackSource
import app.aroundtheblock.wallet.core.asset.send.StackEntry
import app.aroundtheblock.wallet.core.asset.send.StackLookup
import app.aroundtheblock.wallet.core.db.dao.TransactionDao
import app.aroundtheblock.wallet.core.db.dao.UtxoDao
import app.aroundtheblock.wallet.core.db.entity.UtxoEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A DigiAsset index that says it is syncing has said "not known yet". It has not said "this
 * output holds no assets" and it has not said "this output is spent".
 *
 * So, while the index is behind: every holding the wallet already shows stays exactly as it is;
 * the output that is waiting stays uncredited (nothing is credited on faith) and is asked again
 * later; nothing may be spent on it; and the wallet carries one state, "catching up", for as long
 * as a syncing answer is the latest one and an output is still waiting for it.
 *
 * Runs [AssetManager.settleFromIndexer], the lookup production makes for a received output,
 * against an in-memory utxos table. Time is passed in.
 */
class AssetIndexCatchingUpTest {

    private val X = "La" + "x".repeat(36)
    private val Y = "La" + "y".repeat(36)
    private val ownedHex = "51"
    private val held = "a".repeat(64)      // a holding settled before the index fell behind
    private val waiting = "c".repeat(64)   // a receipt the index has not reached
    private val placeholder = "unresolved:$waiting"
    private val syncing = StackLookup.NotYet(indexSyncing = true, blocksBehind = 23_871)

    private val rows = LinkedHashMap<String, UtxoEntity>()
    private fun key(t: String, v: Int) = "$t:$v"
    private val utxoDao = mockk<UtxoDao>(relaxed = true).also { dao ->
        coEvery { dao.getAssetUtxoAt(any(), any()) } answers { rows[key(firstArg(), secondArg())] }
        coEvery { dao.settleAssetCredit(any(), any(), any(), any(), any()) } answers {
            val k = key(arg(0), arg(1))
            rows[k] = rows.getValue(k).copy(assetId = arg(2), assetQuantity = arg(3), assetCredit = arg(4))
        }
        coEvery { dao.getAllAssetUtxosNow() } answers { rows.values.toList() }
    }

    private val answers = HashMap<String, StackLookup>()
    private val asked = ArrayList<String>()
    private val indexer = AssetStackSource { t, v -> asked += key(t, v); answers[key(t, v)] ?: StackLookup.Unavailable }
    private val nativeHeld: (String, Int) -> Int = { _, _ -> AssetSpentState.HELD }

    private fun manager() = AssetManager(
        utxoDao, mockk<TransactionDao>(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        assetStackSource = indexer,
        // What a send's selection asks natively, answered here: no data output, not DigiDollar's.
        parentHasDataOutput = { false },
        outpointOtherFamily = { _, _ -> false },
    )

    private fun row(txid: String, assetId: String, qty: Long, credit: String, spent: Boolean = false) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = byteArrayOf(0x51), satoshis = 600, blockHeight = 1,
        isAsset = true, assetId = assetId, assetQuantity = qty, spent = spent,
        assetSource = AssetSource.NATIVE, assetCredit = credit,
    )

    private suspend fun balances(mgr: AssetManager): Map<String, Long> =
        mgr.computeHeldAssetBalancesImpl(setOf(ownedHex)) { _, _ -> AssetSpentState.HELD }!!
            .filterValues { it.quantity > 0L }.mapValues { it.value.quantity }

    private suspend fun ask(mgr: AssetManager, atMs: Long, native: (String, Int) -> Int = nativeHeld) =
        mgr.settleFromIndexer(waiting, 0, placeholder, maxUnits = null, nowMs = atMs, spentState = native)

    /** AssetManager.RECEIPT_RECHECK_MS, which is private; the tests on either side of it pin the value. */
    private val minute = 60_000L

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        rows[key(held, 0)] = row(held, X, 40, AssetCredit.VERIFIED)
        rows[key(waiting, 0)] = row(waiting, placeholder, 5, AssetCredit.UNCHECKED)
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    // ── holdings ─────────────────────────────────────────────────────────────────────────────

    @Test fun a_holding_shown_before_a_syncing_answer_is_still_shown_after_it() = runTest {
        val mgr = manager()
        assertEquals(mapOf(X to 40L), balances(mgr))
        val before = rows.toMap()

        answers[key(waiting, 0)] = syncing
        answers[key(held, 0)] = syncing        // were anything to ask about the settled row
        assertNull(ask(mgr, 0))
        mgr.settleFromIndexer(held, 0, X, maxUnits = null, nowMs = 0, spentState = nativeHeld)

        assertEquals("no row was touched", before, rows.toMap())
        assertEquals(mapOf(X to 40L), balances(mgr))
        coVerify(exactly = 0) { utxoDao.settleAssetCredit(any(), any(), any(), any(), any()) }
        assertEquals("a settled row is never asked about again", listOf(key(waiting, 0)), asked)
    }

    /** "Cannot say yet" is not the empty stack: the waiting row is not settled at zero. */
    @Test fun a_syncing_answer_never_settles_the_waiting_output_as_empty() = runTest {
        val mgr = manager()
        for (answer in listOf(
            syncing,
            StackLookup.NotYet(indexSyncing = true, blocksBehind = 2, confirmations = 1),
            StackLookup.NotYet(indexSyncing = false, confirmations = 0),
        )) {
            asked.clear()
            answers[key(waiting, 0)] = answer
            assertNull("$answer", ask(manager(), 0))
            assertEquals("$answer", AssetCredit.UNCHECKED, rows.getValue(key(waiting, 0)).assetCredit)
            assertEquals("$answer: the claim stays for the hold rule", 5L, rows.getValue(key(waiting, 0)).assetQuantity)
        }
        assertEquals(mapOf(X to 40L), balances(mgr))
        // By contrast an answered empty stack does settle it, at nothing.
        answers[key(waiting, 0)] = StackLookup.Found(emptyList())
        ask(manager(), 0)
        assertEquals(AssetCredit.VERIFIED to 0L,
            rows.getValue(key(waiting, 0)).let { it.assetCredit to it.assetQuantity })
    }

    @Test fun it_is_asked_again_later_and_credited_once_the_index_answers() = runTest {
        val mgr = manager()
        answers[key(waiting, 0)] = syncing
        ask(mgr, 0)
        ask(mgr, minute - 1)
        assertEquals("not again inside the recheck interval", 1, asked.size)
        ask(mgr, minute)
        assertEquals("asked again after it", 2, asked.size)
        assertEquals(AssetIndexStatus.CatchingUp(23_871), mgr.assetIndexStatus.value)

        answers[key(waiting, 0)] = StackLookup.Found(listOf(StackEntry(Y, 5)))
        ask(mgr, 2 * minute)
        assertEquals(mapOf(X to 40L, Y to 5L), balances(mgr))
        assertEquals(AssetIndexStatus.Current, mgr.assetIndexStatus.value)
    }

    // ── the state the screen shows ───────────────────────────────────────────────────────────

    @Test fun catching_up_is_carried_only_while_a_syncing_answer_is_the_latest() = runTest {
        val mgr = manager()
        assertEquals(AssetIndexStatus.Current, mgr.assetIndexStatus.value)

        answers[key(waiting, 0)] = syncing
        ask(mgr, 0)
        assertEquals(AssetIndexStatus.CatchingUp(23_871), mgr.assetIndexStatus.value)

        // No answer at all (offline) says nothing about the index: the state stands.
        answers.remove(key(waiting, 0))
        ask(mgr, minute)
        assertEquals(AssetIndexStatus.CatchingUp(23_871), mgr.assetIndexStatus.value)

        // The index answers about an output again: no longer catching up.
        answers[key(waiting, 0)] = StackLookup.NotUnspent
        ask(mgr, 2 * minute)
        assertEquals(AssetIndexStatus.Current, mgr.assetIndexStatus.value)

        // Syncing again, by way of the lookup a send or a recovery move makes.
        answers[key(held, 0)] = StackLookup.NotYet(indexSyncing = true, blocksBehind = 400)
        assertEquals(StackLookup.NotYet(true, 400), mgr.assetStackOf(held, 0))
        assertEquals(AssetIndexStatus.CatchingUp(400), mgr.assetIndexStatus.value)
    }

    @Test fun the_rule_table() {
        val up = AssetIndexStatus.CatchingUp(900)
        val cur = AssetIndexStatus.Current
        fun next(from: AssetIndexStatus, l: StackLookup) = AssetIndexStatusRule.next(from, l)
        val edge = AssetIndexStatusRule.NOTE_MIN_BLOCKS_BEHIND

        assertEquals(AssetIndexStatus.CatchingUp(5_000), next(cur, StackLookup.NotYet(true, 5_000)))
        assertEquals(AssetIndexStatus.CatchingUp(edge), next(cur, StackLookup.NotYet(true, edge)))
        assertEquals(AssetIndexStatus.CatchingUp(null), next(cur, StackLookup.NotYet(true)))
        // An output a block or two newer than the index's tip is an ordinary receipt, not a note.
        assertEquals(cur, next(cur, StackLookup.NotYet(true, edge - 1, confirmations = 1)))
        assertEquals(cur, next(up, StackLookup.NotYet(true, 1, confirmations = 1)))
        assertEquals(cur, next(up, StackLookup.NotYet(false, confirmations = 0)))
        assertEquals(cur, next(up, StackLookup.NotYet(false, blocksBehind = 5_000, confirmations = 0)))
        // An answer about an output ends it; no answer and a refused request change nothing.
        assertEquals(cur, next(up, StackLookup.Found(emptyList())))
        assertEquals(cur, next(up, StackLookup.NotUnspent))
        for (from in listOf(up, cur)) {
            assertEquals(from, next(from, StackLookup.Unavailable))
            assertEquals(from, next(from, StackLookup.Refused))
        }
    }

    /** Once nothing is waiting for the index, "catching up" explains nothing and nothing would
     *  ask again to clear it: the pass over the wallet's transactions clears it. */
    @Test fun the_state_is_dropped_when_a_pass_finds_no_output_waiting() = runTest {
        val mgr = manager()
        answers[key(waiting, 0)] = syncing
        mgr.indexerWaitPass { ask(mgr, 0) }
        assertEquals("an output is waiting: the state stays", AssetIndexStatus.CatchingUp(23_871), mgr.assetIndexStatus.value)

        // Waiting but inside the recheck interval (not asked this pass): still waiting.
        mgr.indexerWaitPass { ask(mgr, 1) }
        assertEquals(1, asked.size)
        assertEquals(AssetIndexStatus.CatchingUp(23_871), mgr.assetIndexStatus.value)

        // The output was spent meanwhile: nothing waits any more.
        rows[key(waiting, 0)] = rows.getValue(key(waiting, 0)).copy(spent = true)
        mgr.indexerWaitPass { ask(mgr, 2 * minute) }
        assertEquals(AssetIndexStatus.Current, mgr.assetIndexStatus.value)
    }

    /** Each pass counts for itself: an output that waited in an earlier pass, or was asked about
     *  outside any pass, does not keep the state alive once a whole pass finds nothing waiting. */
    @Test fun a_pass_counts_only_what_it_saw_itself() = runTest {
        val mgr = manager()
        answers[key(waiting, 0)] = syncing
        ask(mgr, 0)                                  // outside any pass
        mgr.indexerWaitPass { ask(mgr, minute) }     // and inside one
        assertEquals(AssetIndexStatus.CatchingUp(23_871), mgr.assetIndexStatus.value)

        assertEquals("the pass's own result is returned", 7, mgr.indexerWaitPass { 7 })
        assertEquals(AssetIndexStatus.Current, mgr.assetIndexStatus.value)
    }

    /** A pass that did not finish did not look at everything, so it clears nothing. */
    @Test fun a_pass_that_does_not_finish_clears_nothing() = runTest {
        val mgr = manager()
        answers[key(waiting, 0)] = syncing
        ask(mgr, 0)
        val thrown = runCatching { mgr.indexerWaitPass<Unit> { throw IllegalStateException("stopped") } }
        assertTrue(thrown.exceptionOrNull() is IllegalStateException)
        assertEquals(AssetIndexStatus.CatchingUp(23_871), mgr.assetIndexStatus.value)
    }

    /**
     * A syncing answer met by a send has no receipt waiting behind it, so only the periodic pass
     * over the wallet's transactions can clear the note. That pass cannot run on the JVM (it
     * lists transactions through the native bridge), so its wiring is read from the source: the
     * whole loop runs inside [AssetManager.indexerWaitPass], and nothing else touches the counter.
     */
    @Test fun the_periodic_sweep_runs_its_loop_inside_the_wait_pass() {
        val file = java.io.File("..").canonicalFile
            .resolve("core/src/main/java/app/aroundtheblock/wallet/core/asset/AssetManager.kt")
        assertTrue("${file.path} is missing: this gate is watching a file that moved", file.exists())
        val code = file.readLines()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            .joinToString("\n")
        val start = code.indexOf("suspend fun sweepKnownTransactionsForAssets(")
        assertTrue("cannot find the sweep: the gate is blind, not clean", start >= 0)
        val end = code.indexOf("retryMissingAssetMetadata()", start)
        assertTrue("cannot find the end of the sweep: the gate is blind, not clean", end > start)
        val sweep = code.substring(start, end)

        val pass = sweep.indexOf("indexerWaitPass {")
        val loop = sweep.indexOf("for (txHash in hashes)")
        val step = sweep.indexOf("processIncomingAssetTx(")
        assertTrue("the sweep does not run a wait pass", pass >= 0)
        assertTrue("cannot find the sweep's loop: the gate is blind, not clean", loop >= 0 && step > loop)
        assertTrue("the loop starts before the wait pass", pass < loop)
        // The pass's block closes after the loop's last statement, at the block's own indentation.
        val closes = sweep.indexOf("\n        }\n", pass)
        assertTrue("the wait pass closes before the loop's work", closes > step)
        assertEquals("one loop over the wallet's transactions", loop, sweep.lastIndexOf("for (txHash in hashes)"))

        assertEquals("the counter is reset in one place", 1, Regex("""waitingOnIndexer\.set\(""").findAll(code).count())
        assertEquals("and counted in one place", 1, Regex("""waitingOnIndexer\.incrementAndGet\(""").findAll(code).count())
    }

    /** A send's own lookups feed the same state: a send stopped by a syncing index says why. */
    @Test fun a_send_selection_lookup_answered_syncing_sets_catching_up() = runTest {
        val mgr = manager()
        val coin = UtxoEntity(
            txid = held, vout = 0, scriptPubKey = byteArrayOf(1, 1), satoshis = 600, blockHeight = 1,
            isAsset = true, assetId = X, assetQuantity = 40,
        )
        val fee = UtxoEntity(txid = "f".repeat(64), vout = 0, scriptPubKey = byteArrayOf(1, 1), satoshis = 1_000_000, blockHeight = 1)
        answers[key(held, 0)] = StackLookup.NotYet(indexSyncing = true, blocksBehind = 400)

        val outcome = mgr.sendSelection.choose(X, listOf(coin), listOf(fee), quantity = 3, feePerKb = 100_000)

        assertEquals("nothing is planned on a coin the index cannot vouch for",
            app.aroundtheblock.wallet.core.asset.send.AssetSendSelection.Outcome.Unverified, outcome)
        assertEquals(listOf(key(held, 0)), asked)
        assertEquals(AssetIndexStatus.CatchingUp(400), mgr.assetIndexStatus.value)
    }

    // ── what is not asked ────────────────────────────────────────────────────────────────────

    /**
     * The count rule is "the native wallet still holds that exact output". The row's own spent
     * flag follows native a pass later, and never does for an output of a replaced transaction,
     * so the indexer was asked (and answered 404) about outputs the wallet knew were gone.
     */
    @Test fun the_indexer_is_not_asked_about_an_output_native_knows_is_gone() = runTest {
        for (state in listOf(AssetSpentState.SPENT, AssetSpentState.CONFLICTED, AssetSpentState.OTHER_FAMILY)) {
            val mgr = manager()
            answers[key(waiting, 0)] = StackLookup.Found(listOf(StackEntry(Y, 5)))
            assertNull("$state", ask(mgr, 0) { _, _ -> state })
            assertEquals("state $state: not asked", emptyList<String>(), asked)
            assertEquals("state $state: nothing credited", AssetCredit.UNCHECKED, rows.getValue(key(waiting, 0)).assetCredit)
            assertFalse(mgr.indexerMayBeAsked(state))
        }
        // Held, and the two states where native cannot place the output, are asked as before.
        for (state in listOf(AssetSpentState.UNDETECTED, AssetSpentState.PROBE_ERROR, AssetSpentState.HELD)) {
            asked.clear()
            rows[key(waiting, 0)] = row(waiting, placeholder, 5, AssetCredit.UNCHECKED)
            assertTrue(manager().indexerMayBeAsked(state))
            ask(manager(), 0) { _, _ -> state }
            assertEquals("state $state", listOf(key(waiting, 0)), asked)
        }
    }

    @Test fun a_refused_outpoint_is_asked_once() = runTest {
        val mgr = manager()
        answers[key(waiting, 0)] = StackLookup.Refused
        assertNull(ask(mgr, 0))
        assertNull(ask(mgr, 10 * minute))
        assertNull(ask(mgr, 100 * minute))
        assertEquals("one request, never repeated", 1, asked.size)
        assertEquals(AssetCredit.UNCHECKED, rows.getValue(key(waiting, 0)).assetCredit)
        assertEquals(mapOf(X to 40L), balances(mgr))
        assertEquals(AssetIndexStatus.Current, mgr.assetIndexStatus.value)
    }
}
