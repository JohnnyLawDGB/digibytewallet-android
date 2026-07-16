package io.digibyte.core.asset.send

/**
 * Pure, size-aware fee estimation for DigiAsset transfer transactions.
 *
 * A DigiAsset transfer is, on the wire, just a regular DigiByte transaction
 * that happens to pin a specific asset-carrying UTXO and carry an OP_RETURN
 * marker. DigiByte has **no fee market** — any transaction paying at or above
 * the 100 sat/byte min-relay fee confirms in ~15 seconds. The old 3-tier
 * "sat/kB" chip model capped out at 20 sat/byte (5× *under* min relay), so
 * asset sends were stuck under-fee'd and never relayed.
 *
 * This estimator computes a CONSERVATIVE virtual size from the concrete tx
 * shape and multiplies by the caller's fee rate, then floors the result at
 * the DGB min-relay fee for that same vsize. It always rounds UP and never
 * returns below the floor, so the built tx fee is always >= min relay.
 *
 * Pure function: no I/O, no coroutines, fully unit-testable.
 */
object AssetFeeEstimator {

    /** DGB min relay / default fee rate: 100,000 sat/kB (= 100 sat/byte). */
    const val MIN_RELAY_FEE_PER_KB: Long = 100_000L

    /** Conservative per-input vsize. Legacy-safe upper bound — recipient /
     *  UTXO script types vary (P2PKH ~148, P2WPKH ~68); we round UP to 150
     *  so a mixed input set is never under-estimated. */
    private const val INPUT_VBYTES: Long = 150L

    /** Conservative per-value-output vsize (P2PKH ~34, P2WPKH ~31). */
    private const val OUTPUT_VBYTES: Long = 34L

    /** Fixed OP_RETURN output overhead: 8-byte value + 1-byte script-length
     *  prefix, before the script bytes themselves. */
    private const val OP_RETURN_OVERHEAD_VBYTES: Long = 9L

    /** Tx envelope overhead: 4-byte version + 4-byte locktime + input/output
     *  count varints + a little slack. */
    private const val TX_OVERHEAD_VBYTES: Long = 12L

    /**
     * Estimate the total DGB fee (in satoshis) for an asset-transfer tx.
     *
     * @param assetInputCount Number of asset-carrying UTXOs being spent.
     * @param dgbInputCount   Number of plain-DGB fee UTXOs being spent.
     * @param outputCount     Number of standard *value* outputs (recipient
     *                        marker, optional asset-change marker, optional
     *                        DGB change). Does NOT include the OP_RETURN —
     *                        that is sized separately via [opReturnBytes].
     * @param opReturnBytes   Byte length of the OP_RETURN script (the DA
     *                        transfer payload, including the 0x6a opcode).
     * @param feePerKb        Fee rate in sat/kB. The default/min-relay rate is
     *                        [MIN_RELAY_FEE_PER_KB] (100 sat/byte). Values
     *                        below that are floored to the min-relay fee.
     * @return Total fee in satoshis, always >= the min-relay fee for the
     *         computed vsize. Includes a +1-input safety margin so that if
     *         the fee re-selection pulls one additional DGB input the fee
     *         still covers it.
     */
    fun estimateAssetTxFeeSats(
        assetInputCount: Int,
        dgbInputCount: Int,
        outputCount: Int,
        opReturnBytes: Int,
        feePerKb: Long,
    ): Long {
        val safeAssetInputs = assetInputCount.coerceAtLeast(0).toLong()
        val safeDgbInputs = dgbInputCount.coerceAtLeast(0).toLong()
        val safeOutputs = outputCount.coerceAtLeast(0).toLong()
        val safeOpReturn = opReturnBytes.coerceAtLeast(0).toLong()

        // +1 input safety margin: if the fee-aware re-selection adds one more
        // DGB input than we estimated, the fee still covers the larger tx.
        val totalInputs = safeAssetInputs + safeDgbInputs + 1L

        val vsize = TX_OVERHEAD_VBYTES +
            totalInputs * INPUT_VBYTES +
            safeOutputs * OUTPUT_VBYTES +
            OP_RETURN_OVERHEAD_VBYTES + safeOpReturn

        val rate = feePerKb.coerceAtLeast(0L)
        val requestedFee = ceilDiv(vsize * rate, 1000L)
        val minRelayFloor = ceilDiv(vsize * MIN_RELAY_FEE_PER_KB, 1000L)
        return maxOf(requestedFee, minRelayFloor)
    }

    /** Ceiling integer division — round UP so we never under-fee. */
    private fun ceilDiv(numerator: Long, denominator: Long): Long =
        if (denominator <= 0L) 0L else (numerator + denominator - 1L) / denominator
}
