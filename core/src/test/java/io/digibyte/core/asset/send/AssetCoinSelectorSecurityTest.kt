package io.digibyte.core.asset.send

import io.digibyte.core.db.entity.UtxoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Adversarial-input + boundary tests for [AssetCoinSelector].
 *
 * Coin selection runs on caller-supplied data — UTXO rows from Room and a
 * user-typed `assetNeeded` quantity. Pathological values (negative qty,
 * ridiculous balances, empty input lists) must either fail loudly with a
 * typed result or refuse via the `require()` precondition; what they MUST
 * NOT do is build a transaction with wrong arithmetic that silently burns
 * funds or under/overpays.
 */
class AssetCoinSelectorSecurityTest {

    private fun assetUtxo(idx: Int, sats: Long, qty: Long): UtxoEntity =
        UtxoEntity(
            txid = "a" + "%015x".format(idx.toLong()),
            vout = 0,
            scriptPubKey = ByteArray(0),
            satoshis = sats,
            blockHeight = 100L,
            isAsset = true,
            assetId = "La2ih1bm2u4dVcWGNHKesrY132xTDtKShnYQch",
            assetQuantity = qty,
            spent = false,
        )

    private fun dgbUtxo(idx: Int, sats: Long): UtxoEntity =
        UtxoEntity(
            txid = "d" + "%015x".format(idx.toLong()),
            vout = 0,
            scriptPubKey = ByteArray(0),
            satoshis = sats,
            blockHeight = 100L,
            isAsset = false,
            assetId = null,
            assetQuantity = 0,
            spent = false,
        )

    // -------------------------------------------------------------------------
    // Precondition guards — caller bugs must be loud, not silent
    // -------------------------------------------------------------------------

    @Test
    fun `negative assetNeeded rejected by precondition`() {
        try {
            AssetCoinSelector.select(
                assetUtxos = listOf(assetUtxo(1, 700, 100)),
                dgbUtxos = listOf(dgbUtxo(1, 10_000)),
                assetNeeded = -1,
                feeSats = 1000,
                markerOutputSats = 700,
            )
            fail("expected IllegalArgumentException for negative assetNeeded")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("assetNeeded"))
        }
    }

    @Test
    fun `zero assetNeeded rejected by precondition`() {
        try {
            AssetCoinSelector.select(
                assetUtxos = listOf(assetUtxo(1, 700, 100)),
                dgbUtxos = listOf(dgbUtxo(1, 10_000)),
                assetNeeded = 0,
                feeSats = 1000,
                markerOutputSats = 700,
            )
            fail("expected IllegalArgumentException for zero assetNeeded")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("assetNeeded"))
        }
    }

    @Test
    fun `negative feeSats rejected by precondition`() {
        try {
            AssetCoinSelector.select(
                assetUtxos = listOf(assetUtxo(1, 700, 100)),
                dgbUtxos = listOf(dgbUtxo(1, 10_000)),
                assetNeeded = 50,
                feeSats = -100,
                markerOutputSats = 700,
            )
            fail("expected IllegalArgumentException for negative feeSats")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("feeSats"))
        }
    }

    @Test
    fun `negative markerOutputSats rejected by precondition`() {
        try {
            AssetCoinSelector.select(
                assetUtxos = listOf(assetUtxo(1, 700, 100)),
                dgbUtxos = listOf(dgbUtxo(1, 10_000)),
                assetNeeded = 50,
                feeSats = 1000,
                markerOutputSats = -1,
            )
            fail("expected IllegalArgumentException for negative markerOutputSats")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("markerOutputSats"))
        }
    }

    // -------------------------------------------------------------------------
    // Empty input lists — typed errors, never crashes
    // -------------------------------------------------------------------------

    @Test
    fun `no asset utxos returns InsufficientAsset`() {
        val r = AssetCoinSelector.select(
            assetUtxos = emptyList(),
            dgbUtxos = listOf(dgbUtxo(1, 10_000)),
            assetNeeded = 50,
            feeSats = 1000,
            markerOutputSats = 700,
        )
        assertTrue(r is AssetCoinSelector.Result.InsufficientAsset)
    }

    @Test
    fun `enough asset but no DGB returns InsufficientDgb`() {
        // Asset UTXOs sit at 700 sats marker each. Two of them = 1400 sats
        // total available; need 1700 (700 marker + 1000 fee). Selector
        // must surface InsufficientDgb specifically — caller distinguishes
        // "wallet empty of DGB" from "wallet empty of asset".
        val r = AssetCoinSelector.select(
            assetUtxos = listOf(assetUtxo(1, 700, 50)),
            dgbUtxos = emptyList(),
            assetNeeded = 50,
            feeSats = 1000,
            markerOutputSats = 700,
        )
        assertTrue(
            "expected InsufficientDgb, got $r",
            r is AssetCoinSelector.Result.InsufficientDgb,
        )
    }

    // -------------------------------------------------------------------------
    // Overflow / boundary arithmetic
    // -------------------------------------------------------------------------

    @Test
    fun `huge asset quantity sum survives without overflow`() {
        // Two UTXOs each at Long.MAX_VALUE / 2 + 1 — naive `assetSum +=`
        // would overflow. Selector should still terminate without
        // returning a falsely-Ok result with negative balances.
        val half = Long.MAX_VALUE / 2 + 1
        val r = AssetCoinSelector.select(
            assetUtxos = listOf(assetUtxo(1, 700, half), assetUtxo(2, 700, half)),
            dgbUtxos = listOf(dgbUtxo(1, 100_000)),
            assetNeeded = 1,
            feeSats = 1000,
            markerOutputSats = 700,
        )
        // We don't assert a specific result; we assert no crash and a
        // typed Result. If we got Ok, the math should at least cover the
        // single-unit need without negative change.
        assertNotNull(r)
        if (r is AssetCoinSelector.Result.Ok) {
            assertTrue("assetChangeQty must be non-negative", r.assetChangeQty >= 0L)
            assertTrue("dgbChangeSats must be non-negative", r.dgbChangeSats >= 0L)
        }
    }

    @Test
    fun `Long_MAX assetNeeded with insufficient asset returns InsufficientAsset`() {
        val r = AssetCoinSelector.select(
            assetUtxos = listOf(assetUtxo(1, 700, 100)),
            dgbUtxos = listOf(dgbUtxo(1, 100_000)),
            assetNeeded = Long.MAX_VALUE,
            feeSats = 1000,
            markerOutputSats = 700,
        )
        assertTrue(r is AssetCoinSelector.Result.InsufficientAsset)
    }

    @Test
    fun `selection picks largest-first to minimize input count`() {
        // Three UTXOs of 30, 50, 100. Need 60. Largest-first should pick
        // just the 100-unit UTXO (1 input), not 30+50 (2 inputs).
        val r = AssetCoinSelector.select(
            assetUtxos = listOf(
                assetUtxo(1, 700, 30),
                assetUtxo(2, 700, 50),
                assetUtxo(3, 700, 100),
            ),
            dgbUtxos = listOf(dgbUtxo(1, 100_000)),
            assetNeeded = 60,
            feeSats = 1000,
            markerOutputSats = 700,
        )
        assertTrue(r is AssetCoinSelector.Result.Ok)
        val ok = r as AssetCoinSelector.Result.Ok
        assertEquals(1, ok.assetInputs.size)
        assertEquals(100L, ok.assetInputs[0].assetQuantity)
        assertEquals(40L, ok.assetChangeQty)  // 100 - 60 = 40 change
    }

    // -------------------------------------------------------------------------
    // Conservation — totalIn must equal recipientQty + change for every Ok
    // -------------------------------------------------------------------------

    @Test
    fun `every Ok result conserves asset quantity`() {
        // Sweep a range of (need, balance) combinations and assert the
        // selector never produces an Ok where the math doesn't balance.
        // This is the invariant that, if violated, would either burn or
        // mint asset units in a real transfer.
        val testCases = listOf(
            Triple(1L, 1L, 0L),
            Triple(1L, 2L, 1L),
            Triple(50L, 100L, 50L),
            Triple(99L, 100L, 1L),
            Triple(100L, 100L, 0L),
            Triple(1L, 1_000_000_000L, 999_999_999L),
        )
        for ((need, balance, expectedChange) in testCases) {
            val r = AssetCoinSelector.select(
                assetUtxos = listOf(assetUtxo(1, 700, balance)),
                dgbUtxos = listOf(dgbUtxo(1, 1_000_000)),
                assetNeeded = need,
                feeSats = 1000,
                markerOutputSats = 1400,  // assume worst-case 2 markers
            )
            assertTrue("need=$need bal=$balance: expected Ok, got $r",
                r is AssetCoinSelector.Result.Ok)
            val ok = r as AssetCoinSelector.Result.Ok
            val totalIn = ok.assetInputs.sumOf { it.assetQuantity }
            assertEquals(
                "conservation breach for need=$need bal=$balance",
                need + ok.assetChangeQty,
                totalIn,
            )
            assertEquals(
                "change mismatch for need=$need bal=$balance",
                expectedChange,
                ok.assetChangeQty,
            )
        }
    }
}
