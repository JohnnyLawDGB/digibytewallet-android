package app.aroundtheblock.wallet.core.asset

import android.util.Log
import app.aroundtheblock.wallet.core.db.dao.UtxoDao
import app.aroundtheblock.wallet.core.db.entity.UtxoEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * "UTXOs Held" counts the outputs that hold units of the asset, and nothing else.
 *
 * Every owned output of an asset transaction is stored as an asset row, the plain DGB change of a
 * send included, and that change row holds 0 units. It is still held by the wallet, so the
 * held-balance pass accepts it and its 0 adds nothing to the quantity; it must add nothing to the
 * count either. The same rule holds in the fallback query used while ownership is unknown.
 */
class HeldAssetUtxoCountTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager
    private val ownedScript = byteArrayOf(1, 2, 3)
    private val ownedHex = ownedScript.joinToString("") { "%02x".format(it) }

    private fun row(txid: String, vout: Int, qty: Long) = UtxoEntity(
        txid = txid, vout = vout, scriptPubKey = ownedScript, satoshis = 6000, blockHeight = 700_000L,
        isAsset = true, assetId = "La1", assetQuantity = qty, spent = false, assetSource = AssetSource.NATIVE,
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun `a held row with no units is not counted as a UTXO holding the asset`() = runTest {
        // Three coins of 1 unit, then a round trip: one of them sent, one received back, and the
        // send's plain change stored as an asset row with quantity 0.
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(
            row("a1" + "0".repeat(62), 0, 1),
            row("a2" + "0".repeat(62), 0, 1),
            row("a3" + "0".repeat(62), 0, 1),   // received back
            row("a4" + "0".repeat(62), 2, 0),   // the send's plain change: held, no units
        )
        val held = mgr.computeHeldAssetBalancesImpl(setOf(ownedHex)) { _, _ -> AssetSpentState.HELD }!!

        assertEquals(3L, held["La1"]!!.quantity)
        assertEquals(3, held["La1"]!!.utxoCount)
    }

    @Test fun `the fallback query counts only rows that hold units`() {
        val dao = File("src/main/java/app/aroundtheblock/wallet/core/db/dao/UtxoDao.kt").readText()
        val query = Regex("""@Query\("([^"]*)"\)\s*fun getAssetBalances\(""").find(dao)
        assertTrue("cannot find the getAssetBalances query — the gate is blind, not clean", query != null)
        val sql = query!!.groupValues[1].replace(Regex("""\s+"""), " ")
        assertTrue("the fallback still counts every row: $sql", !sql.contains("COUNT(*) as utxoCount"))
        assertTrue("the fallback does not count by quantity: $sql",
            Regex("""SUM\(CASE WHEN asset_quantity > 0 THEN 1 ELSE 0 END\) as utxoCount""").containsMatchIn(sql))
    }
}
