package app.aroundtheblock.wallet.core.asset

import android.util.Log
import app.aroundtheblock.wallet.core.db.dao.AssetBalance
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
 * The asset list adds up quantities that come from the chain, so a sum can pass Long.MAX_VALUE.
 * Adding them up never fails and never wraps: the total stops at Long.MAX_VALUE.
 *
 * SQLite's SUM() over integers fails the query past that limit instead, and the asset list's Flow has
 * no handler for it, so the fallback query must not SUM a quantity column directly (the host
 * check `audit/evidence/train-e/e5-f5/asset_sum_sqlite.py` runs the query itself on SQLite).
 */
class AssetQuantitySumTest {
    private val utxoDao = mockk<UtxoDao>(relaxed = true)
    private lateinit var mgr: AssetManager
    private val ownedScript = byteArrayOf(1, 2, 3)
    private val ownedHex = ownedScript.joinToString("") { "%02x".format(it) }

    private fun row(txid: String, qty: Long) = UtxoEntity(
        txid = txid, vout = 0, scriptPubKey = ownedScript, satoshis = 6000, blockHeight = 700_000L,
        isAsset = true, assetId = "La1", assetQuantity = qty, spent = false, assetSource = AssetSource.NATIVE,
        assetCredit = AssetCredit.VERIFIED,
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        mgr = AssetManager(utxoDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test fun `held quantities past Long_MAX_VALUE stop there instead of wrapping`() = runTest {
        coEvery { utxoDao.getAllAssetUtxosNow() } returns listOf(
            row("a1" + "0".repeat(62), 7_000_000_000_000_000_000L),
            row("a2" + "0".repeat(62), 7_000_000_000_000_000_000L),
            row("a3" + "0".repeat(62), 1L),
        )
        val held = mgr.computeHeldAssetBalancesImpl(setOf(ownedHex)) { _, _ -> AssetSpentState.HELD }!!
        assertEquals(Long.MAX_VALUE, held["La1"]!!.quantity)
        assertEquals(3, held["La1"]!!.utxoCount)
    }

    @Test fun `the fallback query never sums a quantity column directly`() {
        val dao = File("src/main/java/app/aroundtheblock/wallet/core/db/dao/UtxoDao.kt").readText()
        val query = Regex("""@Query\("([^"]*)"\)\s*fun getAssetBalances\(""").find(dao)
        assertTrue("cannot find the getAssetBalances query — the gate is blind, not clean", query != null)
        val sql = query!!.groupValues[1].replace(Regex("""\s+"""), " ")
        assertTrue("SUM(asset_quantity) fails past Long.MAX_VALUE in SQLite: $sql",
            !Regex("""SUM\(\s*asset_quantity\s*\)""", RegexOption.IGNORE_CASE).containsMatchIn(sql))
    }

    /** What SQLite answers for these quantities: SUM(q >> 32) and SUM(q & 0xFFFFFFFF). */
    private fun queried(vararg q: Long) =
        AssetBalance("La1", q.sumOf { it shr 32 }, q.sumOf { it and 0xFFFFFFFFL }, q.count { it > 0 })

    @Test fun `the fallback query's halves add back to the exact total`() {
        assertEquals(0L, queried(0L).totalQuantity)
        assertEquals(5L, queried(5L).totalQuantity)
        assertEquals(12L, queried(5L, 7L).totalQuantity)
        assertEquals(-5L, queried(-5L).totalQuantity)
        assertEquals(Long.MAX_VALUE, queried(Long.MAX_VALUE).totalQuantity)
        assertEquals(Long.MAX_VALUE - 1, queried(Long.MAX_VALUE - 3, 2L).totalQuantity)
        assertEquals(10_000_000_000L + 4_294_967_295L, queried(10_000_000_000L, 4_294_967_295L).totalQuantity)
    }

    @Test fun `the fallback query's total stops at Long_MAX_VALUE`() {
        assertEquals(Long.MAX_VALUE, queried(7_000_000_000_000_000_000L, 7_000_000_000_000_000_000L).totalQuantity)
        assertEquals(Long.MAX_VALUE, queried(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE).totalQuantity)
        assertEquals(Long.MIN_VALUE, queried(Long.MIN_VALUE, -1L).totalQuantity)
    }
}
