package app.aroundtheblock.wallet.core.asset

import android.util.Log
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DA-014-REF, the count side. A row counts toward the shown balance iff native still holds that
 * exact output AND its funding transaction's carrier verdict ([DigiAssetCarrierRule]) is CARRIER
 * with the row's output one that carrier can deliver to. Computed on every recompute, so a row an
 * earlier rule credited stops counting without a migration, and is kept (display only).
 */
class AssetCarrierCountRuleTest {

    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager
    private val logged = mutableListOf<String>()

    private val ours = "0014" + "11".repeat(20)
    private val oursBytes = ByteArray(ours.length / 2) { ours.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private val transferTenToZero = "6a0644410115000a"
    private val plain = "6a04deadbeef"

    private fun id(tag: String) = tag.padEnd(64, '0')
    private val funding = HashMap<String, Array<String>>()

    private fun row(txid: String, vout: Int, qty: Long) = UtxoEntity(
        txid = txid, vout = vout, scriptPubKey = oursBytes, satoshis = 700L, blockHeight = 700_000L,
        isAsset = true, assetId = "La1", assetQuantity = qty, assetSource = AssetSource.NATIVE,
        assetCredit = AssetCredit.VERIFIED,
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } answers { logged += secondArg<String>(); 0 }
        every { Log.d(any(), any<String>()) } returns 0
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private suspend fun balances(vararg rows: UtxoEntity): Map<String, AssetManager.HeldBalance> {
        coEvery { utxoDao.getAllAssetUtxosNow() } returns rows.toList()
        return mgr.computeHeldAssetBalancesImpl(
            setOf(ours),
            spentState = { _, _ -> AssetSpentState.HELD },
            fundingOutputLines = { funding[it] },
        )!!
    }

    /** RED without the count-side check. [ours, carrier, plain OP_RETURN]: the earlier rule
     *  credited output 0; DigiAsset Core reads the transaction as plain. Native still holds the
     *  output (every output of an undecided transaction is held), so only the verdict drops it. */
    @Test fun `a row an earlier rule credited stops counting`() = runTest {
        val phantom = id("p1")
        funding[phantom] = arrayOf("0|700|$ours", "1|0|$transferTenToZero", "2|0|$plain")

        val held = balances(row(phantom, 0, 40_000L))

        assertNull("an undecided transaction's row was counted: $held", held["La1"])
        assertTrue("the drop is not logged per row with its reason: $logged",
            logged.any { it.contains("qty=40000") && it.contains("drop (carrier:undecided)") })
        coVerify(exactly = 0) { utxoDao.deleteAssetUtxo(any(), any()) }
    }

    /** GUARD: a genuine single-carrier row still counts. */
    @Test fun `a genuine row still counts`() = runTest {
        val real = id("r1")
        funding[real] = arrayOf("0|700|$ours", "1|0|$transferTenToZero")

        assertEquals(10L, balances(row(real, 0, 10L))["La1"]?.quantity)
    }

    /** GUARD: the last output, where the remainder goes, can hold credited units. */
    @Test fun `a remainder on the last output still counts`() = runTest {
        val real = id("r2")
        funding[real] = arrayOf("0|700|$ours", "1|0|$transferTenToZero", "2|700|$ours")

        assertEquals(5L, balances(row(real, 2, 5L))["La1"]?.quantity)
    }

    /** RED without the count-side check: an output no instruction can deliver to holds nothing. */
    @Test fun `an output the carrier cannot deliver to does not count`() = runTest {
        val tx = id("n1")
        funding[tx] = arrayOf("0|700|$ours", "1|700|$ours", "2|0|$transferTenToZero", "3|700|$ours")

        assertNull(balances(row(tx, 1, 7L))["La1"])
        assertTrue(logged.any { it.contains("carrier:not-targeted") })
    }

    /** RED without the count-side check: a funding transaction whose outputs cannot be read
     *  credits nothing it cannot show. */
    @Test fun `an unreadable funding transaction does not count`() = runTest {
        val tx = id("u1")
        funding[tx] = arrayOf("0|700|$ours", "garbage")

        assertNull(balances(row(tx, 0, 7L))["La1"])
        assertTrue(logged.any { it.contains("carrier:unreadable") })
    }

    // ── guards across instruction forms ──────────────────────────────────

    /** Issuance of 10, no instructions: the issuer's marker (first non-OP_RETURN output) counts. */
    private val issuanceTen = "6a06444103050a10"
    /** v3 issuance of 10 with one instruction: all 10 to output 0. */
    private val issuanceAllToZero = "6a0844410305" + "0a" + "000a" + "10"
    /** Transfer, one RANGE instruction: 10 units over outputs 0..2. */
    private val rangeToTwo = "6a07444101154002" + "0a"
    /** Transfer, two instructions: 5 units to output 0 and 5 to output 2. */
    private val twoInstructions = "6a08444101150005" + "0205"

    @Test fun `the instruction fixtures decode as described`() {
        fun h(x: String) = DigiAssetDecoder().decode(ByteArray(x.length / 2) { x.substring(2 * it, 2 * it + 2).toInt(16).toByte() })!!
        for (x in listOf(issuanceTen, issuanceAllToZero, rangeToTwo, twoInstructions)) {
            assertTrue("$x is not nulldata", DigiAssetCarrierRule.isNullData(ByteArray(x.length / 2) { x.substring(2 * it, 2 * it + 2).toInt(16).toByte() }))
        }
        assertEquals(app.aroundtheblock.wallet.core.model.AssetOperation.ISSUANCE, h(issuanceTen).operation)
        assertEquals(10L, h(issuanceAllToZero).totalQuantity)
        assertEquals(listOf(0 to 10L), h(issuanceAllToZero).transferInstructions.map { it.outputIndex to it.amount })
        assertTrue(h(rangeToTwo).transferInstructions.single().range)
        assertEquals(2, h(rangeToTwo).transferInstructions.single().outputIndex)
        assertEquals(listOf(0 to 5L, 2 to 5L), h(twoInstructions).transferInstructions.map { it.outputIndex to it.amount })
    }

    /** GUARD: an issuance's marker row counts. */
    @Test fun `an issuance marker row counts`() = runTest {
        val tx = id("i1")
        funding[tx] = arrayOf("0|0|$issuanceTen", "1|700|$ours", "2|700|$ours")

        assertEquals(10L, balances(row(tx, 1, 10L))["La1"]?.quantity)
    }

    /** GUARD: an issuance output nothing can deliver to does not count. */
    @Test fun `an issuance output nothing can deliver to does not count`() = runTest {
        val tx = id("i2")
        funding[tx] = arrayOf("0|700|$ours", "1|0|$issuanceAllToZero", "2|700|$ours", "3|700|$ours")

        assertNull(balances(row(tx, 2, 3L))["La1"])
        assertTrue("$logged", logged.any { it.contains("drop (carrier:not-targeted)") })
    }

    /** GUARD: every output inside a range counts; one outside it (and not last) does not. */
    @Test fun `a range transfer credits the outputs it spans`() = runTest {
        val tx = id("g1")
        funding[tx] = arrayOf("0|700|$ours", "1|700|$ours", "2|700|$ours", "3|700|$ours", "4|0|$rangeToTwo", "5|700|$ours")

        assertEquals(6L, balances(row(tx, 0, 1L), row(tx, 1, 2L), row(tx, 2, 3L), row(tx, 3, 100L))["La1"]?.quantity)
    }

    /** GUARD: with two instructions both named outputs and the last output count; another does not. */
    @Test fun `a multi-instruction transfer credits what it names and the last output`() = runTest {
        val tx = id("m1")
        funding[tx] = arrayOf("0|700|$ours", "1|700|$ours", "2|700|$ours", "3|0|$twoInstructions", "4|700|$ours")

        assertEquals(5L + 5L + 4L, balances(row(tx, 0, 5L), row(tx, 1, 50L), row(tx, 2, 5L), row(tx, 4, 4L))["La1"]?.quantity)
    }

    // ── DA-014-REF: the encrypted-key shape ──────────────────────────────

    /** RED without the shape check: [650 sat, carrier, ours] with one input is read by DigiAsset
     *  Core as an encrypted-key transaction; nothing it carries counts. */
    @Test fun `a carrier in the encrypted-key shape does not count`() = runTest {
        val tx = id("k1")
        funding[tx] = arrayOf("0|650|$ours", "1|0|$transferTenToZero", "2|700|$ours")
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(tx, 0, 10L))

        val held = mgr.computeHeldAssetBalancesImpl(
            setOf(ours), spentState = { _, _ -> AssetSpentState.HELD },
            fundingOutputLines = { funding[it] }, fundingInputCount = { 1 },
        )!!

        assertNull(held["La1"])
    }

    /** GUARD: the same shape with two inputs is an asset transaction and counts. */
    @Test fun `the same shape with two inputs counts`() = runTest {
        val tx = id("k2")
        funding[tx] = arrayOf("0|650|$ours", "1|0|$transferTenToZero", "2|700|$ours")
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(row(tx, 0, 10L))

        val held = mgr.computeHeldAssetBalancesImpl(
            setOf(ours), spentState = { _, _ -> AssetSpentState.HELD },
            fundingOutputLines = { funding[it] }, fundingInputCount = { 2 },
        )!!

        assertEquals(10L, held["La1"]?.quantity)
    }

    // ── what acts on a counted row ───────────────────────────────────────

    /** RED on firstOrNull: the rule check walks from the first row the count rule KEEPS, so an
     *  excluded legacy row listed first does not stand in for a genuine holding. */
    @Test fun `the rule check starts from a counted row, not an excluded one`() = runTest {
        val legacy = id("p2")
        val real = id("r9")
        funding[legacy] = arrayOf("0|700|$ours", "1|0|$transferTenToZero", "2|0|$plain")
        funding[real] = arrayOf("0|700|$ours", "1|0|$transferTenToZero")
        coEvery { utxoDao.getAssetUtxosByIdNow("La1") } returns listOf(row(legacy, 0, 40_000L), row(real, 0, 10L))

        val first = mgr.firstCountedRow(
            "La1", setOf(ours), spentState = { _, _ -> AssetSpentState.HELD },
            fundingOutputLines = { funding[it] }, fundingInputCount = { 2 },
        )

        assertEquals(real, first?.txid)
    }

    /** GUARD: with only excluded rows there is no row to walk from (the gate then reads UNKNOWN). */
    @Test fun `with only excluded rows there is no counted row`() = runTest {
        val legacy = id("p3")
        funding[legacy] = arrayOf("0|700|$ours", "1|0|$transferTenToZero", "2|0|$plain")
        coEvery { utxoDao.getAssetUtxosByIdNow("La1") } returns listOf(row(legacy, 0, 40_000L))

        assertNull(mgr.firstCountedRow(
            "La1", setOf(ours), spentState = { _, _ -> AssetSpentState.HELD },
            fundingOutputLines = { funding[it] }, fundingInputCount = { 2 },
        ))
    }

    /** The Assets screen never falls back to the naive sum: a recompute that cannot run shows
     *  nothing before the first good answer and the last good answer after it. */
    @Test fun `an unavailable recompute shows the last good answer, else nothing`() {
        assertEquals(emptyMap<String, AssetManager.HeldBalance>(), mgr.displayHeldBalances(null))
        val good = mapOf("La1" to AssetManager.HeldBalance(10L, 1))
        assertEquals(good, mgr.displayHeldBalances(good))
        assertEquals(good, mgr.displayHeldBalances(null))
    }
}
