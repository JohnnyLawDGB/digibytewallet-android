package io.digibyte.core

import io.digibyte.core.db.entity.UtxoEntity
import org.junit.Assert.*
import org.junit.Test

class CoinSelectorTest {
    private val selector = CoinSelector()

    private fun utxo(txid: String, satoshis: Long, isAsset: Boolean = false) =
        UtxoEntity(txid, 0, byteArrayOf(), satoshis, 1000, isAsset = isAsset)

    @Test
    fun `selectCoins picks UTXOs covering amount plus fee`() {
        val utxos = listOf(utxo("tx1", 100_000), utxo("tx2", 200_000))
        val result = selector.selectCoins(utxos, 150_000, 100_000)
        assertNotNull(result)
        assertTrue(result!!.inputs.sumOf { it.satoshis } >= 150_000 + result.fee)
    }

    @Test
    fun `selectCoins NEVER includes asset UTXOs`() {
        val utxos = listOf(
            utxo("dgb1", 500_000),
            utxo("asset1", 600, isAsset = true),
            utxo("asset2", 600, isAsset = true)
        )
        val result = selector.selectCoins(utxos, 100_000, 100_000)
        assertNotNull(result)
        assertTrue(result!!.inputs.none { it.isAsset })
    }

    @Test
    fun `selectCoins returns null when insufficient balance`() {
        val utxos = listOf(utxo("tx1", 1_000))
        val result = selector.selectCoins(utxos, 100_000, 100_000)
        assertNull(result)
    }

    @Test
    fun `selectCoins absorbs dust change into fee`() {
        // Create scenario where change would be below dust threshold
        val utxos = listOf(utxo("tx1", 200_000))
        // Target + fee should leave less than 546 sats change
        val feePerKb = 100_000L
        val fee = selector.estimateFee(1, 2, feePerKb) // ~19200
        val targetAmount = 200_000 - fee - 100 // leaves 100 sats change (dust)
        val result = selector.selectCoins(utxos, targetAmount, feePerKb)
        assertNotNull(result)
        assertEquals("Dust change should be absorbed into fee", 0L, result!!.change)
    }

    @Test
    fun `selectCoins returns change when above dust threshold`() {
        val utxos = listOf(utxo("tx1", 1_000_000))
        val result = selector.selectCoins(utxos, 100_000, 100_000)
        assertNotNull(result)
        assertTrue("Change should be above dust", result!!.change >= CoinSelector.DUST_THRESHOLD)
    }

    @Test
    fun `selectCoins with only asset UTXOs returns null`() {
        val utxos = listOf(
            utxo("a1", 600, isAsset = true),
            utxo("a2", 600, isAsset = true)
        )
        assertNull(selector.selectCoins(utxos, 100, 100_000))
    }

    @Test
    fun `estimateFee calculates correctly`() {
        // 1 input, 2 outputs: 10 + 148 + 68 = 226 bytes
        // At 100000 sat/KB: 226 * 100000 / 1000 = 22600
        val fee = selector.estimateFee(1, 2, 100_000)
        assertEquals(22600L, fee)
    }
}
