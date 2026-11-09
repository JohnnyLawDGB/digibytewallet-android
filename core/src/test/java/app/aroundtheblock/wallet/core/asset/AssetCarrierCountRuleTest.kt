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
}
