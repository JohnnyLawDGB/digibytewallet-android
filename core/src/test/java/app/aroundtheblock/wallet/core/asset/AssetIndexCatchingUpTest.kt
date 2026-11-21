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
        mgr.beginIndexerWaitCount()
        ask(mgr, 0)
        mgr.endIndexerWaitCount()
        assertEquals("an output is waiting: the state stays", AssetIndexStatus.CatchingUp(23_871), mgr.assetIndexStatus.value)

        // Waiting but inside the recheck interval (not asked this pass): still waiting.
        mgr.beginIndexerWaitCount()
        ask(mgr, 1)
        mgr.endIndexerWaitCount()
        assertEquals(1, asked.size)
        assertEquals(AssetIndexStatus.CatchingUp(23_871), mgr.assetIndexStatus.value)

        // The output was spent meanwhile: nothing waits any more.
        rows[key(waiting, 0)] = rows.getValue(key(waiting, 0)).copy(spent = true)
        mgr.beginIndexerWaitCount()
        ask(mgr, 2 * minute)
        mgr.endIndexerWaitCount()
        assertEquals(AssetIndexStatus.Current, mgr.assetIndexStatus.value)
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
