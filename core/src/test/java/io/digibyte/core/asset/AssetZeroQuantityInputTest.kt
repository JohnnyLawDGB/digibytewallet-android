package io.digibyte.core.asset

import android.util.Log
import io.digibyte.core.db.dao.AssetMetadataDao
import io.digibyte.core.db.dao.UtxoDao
import io.digibyte.core.db.entity.UtxoEntity
import io.digibyte.core.model.AssetOperation
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A stored asset quantity of zero on a targeted input is "unknown", so the output a remainder
 * would ride to stays held out of the plain-coin set until the quantity is known.
 *
 * Driven through the two places that hold outputs out and can run on the JVM — the pass in front
 * of a spend and the startup replay — over one small wallet:
 *
 *  - `carrier`  a transfer whose percent instruction names our output 1. Its units cannot be
 *               totalled here, so its row is stored with a quantity of zero.
 *  - `spend`    spends a resolved 10-unit row and `carrier:1`, and assigns 10 units. Whatever
 *               `carrier:1` held rides to `spend`'s last output, which is ours.
 *  - `earlier`, `later`  two sends of the ordinary kind: every unit assigned, DGB change last,
 *               `later` paying its fee with `earlier`'s change (also a row with a zero).
 */
class AssetZeroQuantityInputTest {

    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private val held = mutableListOf<Pair<String, Int>>()
    private val logged = mutableListOf<String>()

    private val ours = "0014" + "11".repeat(20)
    private val theirs = "0014" + "22".repeat(20)

    private fun payload(vararg instructions: DigiAssetEncoder.TransferInstruction): String =
        DigiAssetEncoder.encodeTransferScript(3, instructions.toList()).joinToString("") { "%02x".format(it) }

    private fun fixed(outputIndex: Int, amount: Long) =
        DigiAssetEncoder.TransferInstruction(skip = false, range = false, percent = false, outputIndex = outputIndex, amount = amount)

    private val halfToOutputOne = payload(
        DigiAssetEncoder.TransferInstruction(skip = false, range = false, percent = true, outputIndex = 1, amount = 50),
    )
    private val tenToOutputZero = payload(fixed(0, 10))

    private fun id(tag: String) = tag.padEnd(64, '0')
    private val coin = id("a1")
    private val carrier = id("c1")
    private val spend = id("d1")
    private val earlier = id("e1")
    private val later = id("e2")
    private val resolved = id("f1")     // a transaction the wallet no longer needs to read: its row is resolved

    private val wallet = LinkedHashMap<String, Array<String>>()
    private val inputs = HashMap<String, Array<String>>()

    private fun row(txid: String, vout: Int, quantity: Long, spent: Boolean = false) = UtxoEntity(
        txid = txid, vout = vout, scriptPubKey = ByteArray(0), satoshis = 700L, blockHeight = 1L,
        isAsset = true, assetId = "La", assetQuantity = quantity, spent = spent,
    )

    private fun store(vararg rows: UtxoEntity) {
        for (r in rows) coEvery { utxoDao.getAssetUtxoAt(r.txid, r.vout) } returns r
        coEvery { utxoDao.getAllAssetUtxosNow() } returns rows.toList()
    }

    /** Native's answer: true the first time an outpoint is registered, false after. */
    private fun manager(resolveRowTargets: (suspend (String, Int) -> Boolean?)? = null) = AssetManager(
        utxoDao = utxoDao,
        transactionDao = mockk(relaxed = true),
        metadataDao = mockk<AssetMetadataDao>(relaxed = true),
        metadataService = mockk(relaxed = true),
        registerAssetOutpoint = { txid, vout -> ((txid to vout) !in held).also { if (it) held += txid to vout } },
        resolveRowTargets = resolveRowTargets,
    )

    private suspend fun AssetManager.pass(): Int = holdAssetOutputsBeforeSpendImpl(
        txHashes = { wallet.keys.toTypedArray() },
        outputsOf = { wallet[it] },
        inputsOf = { inputs[it] },
        ownedScriptHexes = { setOf(ours) },
    )

    /** The replay, with its production resolver reading this test's wallet instead of the bridge. */
    private fun replayingManager(): AssetManager {
        lateinit var mgr: AssetManager
        mgr = manager(resolveRowTargets = { txid, vout ->
            mgr.resolveRowTargetsImpl(txid, vout, { wallet[it] }, { inputs[it] }, walletLoaded = { true })
        })
        return mgr
    }

    private val heldLines get() = logged.filter { it.startsWith("held ") }

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } answers { logged += secondArg<String>(); 0 }
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>(), any()) } returns 0
        coEvery { utxoDao.getAssetUtxoAt(any(), any()) } returns null

        wallet[coin] = arrayOf("0|150000000|$ours", "1|4200000|$ours")
        wallet[carrier] = arrayOf("0|700|$theirs", "1|700|$ours", "2|0|$halfToOutputOne", "3|9000|$theirs")
        inputs[carrier] = arrayOf("${id("99")}|0")
        wallet[spend] = arrayOf("0|700|$theirs", "1|0|$tenToOutputZero", "2|9000|$theirs", "3|50000|$ours")
        inputs[spend] = arrayOf("$resolved|0", "$carrier|1")
        wallet[earlier] = arrayOf("0|700|$theirs", "1|0|${payload(fixed(0, 5), fixed(2, 95))}", "2|700|$ours", "3|40000|$ours")
        inputs[earlier] = arrayOf("$resolved|1", "$coin|1")
        wallet[later] = arrayOf("0|700|$theirs", "1|0|${payload(fixed(0, 5), fixed(2, 90))}", "2|700|$ours", "3|30000|$ours")
        inputs[later] = arrayOf("$earlier|2", "$earlier|3")

        store(
            row(resolved, 0, 10L, spent = true), row(resolved, 1, 100L, spent = true),
            row(carrier, 1, 0L, spent = true),
            row(spend, 3, 0L),
            row(earlier, 2, 95L, spent = true), row(earlier, 3, 0L, spent = true),
            row(later, 2, 90L), row(later, 3, 0L),
        )
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun `the payloads decode to what the wallet above says they are`() {
        fun decode(hex: String) = DigiAssetDecoder().decode(
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() },
        )
        val half = decode(halfToOutputOne)
        assertEquals(AssetOperation.TRANSFER, half?.operation)
        assertEquals(listOf(Triple(1, 50L, true)), half!!.transferInstructions.map { Triple(it.outputIndex, it.amount, it.percent) })
        assertEquals(listOf(0 to 10L), decode(tenToOutputZero)!!.transferInstructions.map { it.outputIndex to it.amount })
        assertEquals(
            listOf(0 to 5L, 2 to 95L),
            decode(payload(fixed(0, 5), fixed(2, 95)))!!.transferInstructions.map { it.outputIndex to it.amount },
        )
    }

    // ── The pass in front of a spend ──────────────────────────────────────

    @Test fun `the last output of a transaction that spent a targeted zero row is held out before a spend`() = runTest {
        manager().pass()

        assertTrue("held: $held", (spend to 3) in held)
    }

    /** GUARD (the neighbouring rule): ordinary change of a transaction whose inputs are known is
     *  not newly held, even though the fee coin it spent is itself a row with a zero. */
    @Test fun `ordinary change of sends whose inputs are known stays in the plain-coin set`() = runTest {
        manager().pass()

        assertFalse("held: $held", (earlier to 3) in held)
        assertFalse("held: $held", (later to 3) in held)
        assertTrue("the explicit asset change was not held — the test is blind", (later to 2) in held)
        assertTrue((carrier to 1) in held)
    }

    @Test fun `one line names the rows behind each output newly held for this reason`() = runTest {
        val mgr = manager()
        mgr.pass()
        mgr.pass()      // registering again is not "newly": native answers false the second time

        assertEquals("lines: $logged", 1, heldLines.size)
        val line = heldLines.single()
        assertTrue(line, line.startsWith("held ${spend.take(12)}:3 "))
        assertTrue(line, line.contains("input row ${carrier.take(12)}:1 qty=0"))
        assertFalse("a line carries no script and no address: $line", line.contains(ours) || line.contains(theirs))
    }

    /** The rows are named only where they are the reason. Here the resolved row alone leaves a
     *  remainder of 90, so the last output was held out by the rule as it stood. */
    @Test fun `an output a known remainder already holds is not reported`() = runTest {
        store(row(resolved, 0, 100L, spent = true), row(carrier, 1, 0L, spent = true))

        manager().pass()

        assertTrue("held: $held", (spend to 3) in held)
        assertEquals("lines: $logged", emptyList<String>(), heldLines)
    }

    /** What the rule costs the pass: `later` asks about `earlier`'s remainder, and the pass has
     *  already totalled `earlier`'s inputs, so they are not read a second time. */
    @Test fun `a pass totals each transaction's inputs once`() = runTest {
        val inputReads = mutableListOf<String>()

        manager().holdAssetOutputsBeforeSpendImpl(
            txHashes = { wallet.keys.toTypedArray() },
            outputsOf = { wallet[it] },
            inputsOf = { inputReads += it; inputs[it] },
            ownedScriptHexes = { setOf(ours) },
        )

        assertEquals(listOf(carrier, spend, earlier, later), inputReads)
        assertFalse("the question was never put — the test is blind", (later to 3) in held)
    }

    // ── The startup replay ────────────────────────────────────────────────

    @Test fun `the replay holds out the unspent last output of a transaction that spent a targeted zero row`() = runTest {
        val registered = replayingManager().replayAssetOutpointExclusions()

        assertTrue("held: $held", (spend to 3) in held)
        assertEquals("spend:3, and the resolved carrier later:2", 2, registered)
    }

    /** GUARD (the neighbouring rule, at the replay). */
    @Test fun `the replay leaves ordinary change of sends whose inputs are known alone`() = runTest {
        replayingManager().replayAssetOutpointExclusions()

        assertFalse("held: $held", (later to 3) in held)
        assertTrue("the resolved carrier was not held — the test is blind", (later to 2) in held)
    }

    @Test fun `the replay's resolver names the rows when they are the whole reason`() = runTest {
        val mgr = manager()
        val rows = mutableListOf<Pair<String, Int>>()

        val targeted = mgr.resolveRowTargetsImpl(spend, 3, { wallet[it] }, { inputs[it] }, { true }, rows)

        assertEquals(true, targeted)
        assertEquals(listOf(carrier to 1), rows)

        rows.clear()
        assertEquals(false, mgr.resolveRowTargetsImpl(later, 3, { wallet[it] }, { inputs[it] }, { true }, rows))
        assertTrue(rows.isEmpty())
    }

    /** GUARD. The split for a row whose transaction is not held is answered as before: `false`
     *  only on a positive statement that native holds no such transaction. */
    @Test fun `the replay's resolver keeps the split for a transaction that is not held`() = runTest {
        val mgr = manager()
        val absent = id("ab")

        assertEquals(false, mgr.resolveRowTargetsImpl(absent, 0, { null }, { null }, walletLoaded = { true }))
        assertNull(mgr.resolveRowTargetsImpl(absent, 0, { null }, { null }, walletLoaded = { false }))
        assertNull(mgr.resolveRowTargetsImpl("not-a-txid", 0, { null }, { null }, walletLoaded = { true }))
        assertNull(mgr.resolveRowTargetsImpl(absent, 0, { throw UnsatisfiedLinkError("no library") }, { null }, walletLoaded = { true }))
        assertNull("held, but nothing in it decodes", mgr.resolveRowTargetsImpl(coin, 0, { wallet[it] }, { inputs[it] }, { true }))
    }

    // ── A long run of ordinary sends ──────────────────────────────────────

    private val runRows = HashMap<Pair<String, Int>, UtxoEntity>()

    /**
     * A run of [length] ordinary sends, each paying its fee with the DGB change of the one before
     * and assigning every unit it took in, over a wallet holding nothing else. Output 2 of each
     * send is its resolved asset change, output 3 its DGB change (a row with a stored zero), and
     * both are ours. Returns the txids of the run, oldest first.
     */
    private fun ordinaryRun(length: Int): List<String> {
        wallet.clear(); inputs.clear(); runRows.clear(); held.clear(); logged.clear()
        val start = id("5a")
        wallet[coin] = arrayOf("0|150000000|$ours", "1|4200000|$ours")
        runRows[start to 0] = row(start, 0, 1_000_000L, spent = true)
        var assetIn = start to 0
        var feeIn = coin to 1
        var left = 1_000_000L
        val run = ArrayList<String>(length)
        for (n in 1..length) {
            val tx = "%064x".format(n)
            left -= 5
            wallet[tx] = arrayOf(
                "0|700|$theirs", "1|0|${payload(fixed(0, 5), fixed(2, left))}", "2|700|$ours", "3|40000|$ours",
            )
            inputs[tx] = arrayOf("${assetIn.first}|${assetIn.second}", "${feeIn.first}|${feeIn.second}")
            runRows[tx to 2] = row(tx, 2, left, spent = n < length)
            runRows[tx to 3] = row(tx, 3, 0L, spent = n < length)
            assetIn = tx to 2
            feeIn = tx to 3
            run += tx
        }
        coEvery { utxoDao.getAssetUtxoAt(any(), any()) } answers { runRows[firstArg<String>() to secondArg<Int>()] }
        coEvery { utxoDao.getAllAssetUtxosNow() } returns runRows.values.toList()
        return run
    }

    /**
     * GUARD (the neighbouring rule, at depth). The DGB change of every send on a long run has
     * known positive inputs behind it, so the pass leaves all of it in the plain-coin set — and
     * gives the same answer whichever order the wallet lists its transactions in.
     */
    @Test fun `the pass keeps the change of a long run spendable in either listing order`() = runTest {
        val run = ordinaryRun(200)

        for (order in listOf(run, run.reversed())) {
            held.clear()
            manager().holdAssetOutputsBeforeSpendImpl(
                txHashes = { order.toTypedArray() },
                outputsOf = { wallet[it] },
                inputsOf = { inputs[it] },
                ownedScriptHexes = { setOf(ours) },
            )

            assertEquals("ordinary change held: $held", emptyList<Pair<String, Int>>(), held.filter { it.second == 3 })
            assertEquals("the asset change was not held — the test is blind", 200, held.count { it.second == 2 })
        }
    }

    /** GUARD (the neighbouring rule, at the replay, at depth). */
    @Test fun `the replay's resolver answers the change of a long run as ordinary change`() = runTest {
        val run = ordinaryRun(200)

        assertEquals(false, manager().resolveRowTargetsImpl(run.last(), 3, { wallet[it] }, { inputs[it] }, { true }))
        assertEquals("the asset change beside it is still held — the test is blind",
            true, manager().resolveRowTargetsImpl(run.last(), 2, { wallet[it] }, { inputs[it] }, { true }))
    }

    /** What one record for the whole replay costs: the run is walked once, and the next row that
     *  rests on the same transactions is answered from the record. */
    @Test fun `a record shared by the replay totals each transaction once`() = runTest {
        val run = ordinaryRun(60)
        val mgr = manager()
        val record = HashMap<String, Long?>()
        val read = mutableListOf<String>()
        fun outputs(txid: String): Array<String>? { read += txid; return wallet[txid] }

        assertEquals(false, mgr.resolveRowTargetsImpl(run.last(), 3, ::outputs, { inputs[it] }, { true }, null, record))
        val walked = read.size
        read.clear()
        assertEquals(false, mgr.resolveRowTargetsImpl(run[58], 3, ::outputs, { inputs[it] }, { true }, null, record))

        assertTrue("walked $walked, then ${read.size}", read.size * 4 < walked)
    }

    // ── Detection ─────────────────────────────────────────────────────────

    /** Detection writes its line from what [AssetManager.protectTargetedOutputs] reports as
     *  newly held; an outpoint native already held is not reported. */
    @Test fun `detection hears of each output it newly holds and of no other`() {
        held += spend to 0
        val heard = mutableListOf<Int>()
        val header = DigiAssetDecoder().decode(
            ByteArray(tenToOutputZero.length / 2) { tenToOutputZero.substring(it * 2, it * 2 + 2).toInt(16).toByte() },
        )!!

        val n = manager().protectTargetedOutputs(
            txHashHex = spend, header = header, ownedVouts = listOf(0, 2, 3),
            firstNonOpReturnVout = 0, inputUnits = null, outputCount = 4, onNewlyHeld = { heard += it },
        )

        assertEquals(1, n)
        assertEquals(listOf(3), heard)
    }

    /**
     * Detection itself starts with bridge calls and cannot run here, so its wiring is read: each
     * place that totals a transaction's inputs puts the targeting question to the transaction
     * that created the input, and keeps the reason for the line above.
     */
    @Test fun `every place that totals a transaction's inputs asks whether a zero row is targeted`() {
        val source = listOf("", "../", "../../")
            .map { File(it + "core/src/main/java/io/digibyte/core/asset/AssetManager.kt") }
            .first { it.exists() }.readText()
            .substringAfter("\nclass AssetManager(")
        val calls = Regex("""resolveInputAssetUnits\(\s*\n\s*inputs = """).findAll(source).map { m ->
            var depth = 0
            var i = m.range.first + "resolveInputAssetUnits".length
            while (i < source.length) {
                when (source[i]) { '(' -> depth++; ')' -> depth-- }
                if (depth == 0) break
                i++
            }
            source.substring(m.range.first, i + 1)
        }.toList()

        assertEquals("detection, the pass in front of a spend, and the replay's resolver", 3, calls.size)
        for (call in calls) {
            assertTrue(call, Regex("""rowIsTargeted = \{ txid, \w+ ->\s*\n\s*inputRowIsTargeted\(""").containsMatchIn(call))
            assertTrue(call, call.contains("unresolved = zeroRowInputs"))
        }
    }
}
