package io.digibyte.core

import io.digibyte.core.db.entity.UtxoEntity

/**
 * Selects UTXOs for a transaction. CRITICAL: never selects asset UTXOs.
 * Uses a simple largest-first algorithm with dust avoidance.
 */
class CoinSelector {
    companion object {
        const val DUST_THRESHOLD = 546L // satoshis — outputs below this are dust
    }

    /**
     * Select UTXOs to cover [targetAmount] + [feePerKb] estimate.
     * Returns null if insufficient balance.
     * Only considers UTXOs where isAsset = false and spent = false.
     */
    fun selectCoins(
        availableUtxos: List<UtxoEntity>,
        targetAmount: Long,
        feePerKb: Long
    ): CoinSelection? {
        // Filter: ONLY non-asset, unspent UTXOs
        val spendable = availableUtxos
            .filter { !it.isAsset && !it.spent }
            .sortedByDescending { it.satoshis }

        if (spendable.isEmpty()) return null

        val selected = mutableListOf<UtxoEntity>()
        var totalInput = 0L

        for (utxo in spendable) {
            selected.add(utxo)
            totalInput += utxo.satoshis

            val estimatedFee = estimateFee(selected.size, 2, feePerKb) // 2 outputs: recipient + change
            val totalNeeded = targetAmount + estimatedFee

            if (totalInput >= totalNeeded) {
                val change = totalInput - totalNeeded
                // If change would be dust, absorb it into the fee
                val actualFee = if (change < DUST_THRESHOLD) {
                    estimatedFee + change
                } else {
                    estimatedFee
                }
                val actualChange = if (change < DUST_THRESHOLD) 0L else change

                return CoinSelection(
                    inputs = selected,
                    fee = actualFee,
                    change = actualChange
                )
            }
        }

        return null // Insufficient balance
    }

    /**
     * Estimate transaction fee based on size.
     * DigiByte tx size: ~10 + (inputCount * 148) + (outputCount * 34) bytes
     * Fee = size * feePerKb / 1000
     */
    fun estimateFee(inputCount: Int, outputCount: Int, feePerKb: Long): Long {
        val estimatedSize = 10 + (inputCount * 148) + (outputCount * 34)
        return (estimatedSize.toLong() * feePerKb) / 1000
    }
}

data class CoinSelection(
    val inputs: List<UtxoEntity>,
    val fee: Long,
    val change: Long
)
