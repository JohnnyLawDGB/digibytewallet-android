package io.digibyte.core.asset.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetFeeEstimatorTest {

    private val minRelayPerKb = AssetFeeEstimator.MIN_RELAY_FEE_PER_KB // 100_000

    /**
     * Recompute the estimator's vsize independently so the assertions
     * check real numbers rather than trusting the function under test.
     * Mirrors the private constants in AssetFeeEstimator.
     */
    private fun vsizeFor(
        assetInputCount: Int,
        dgbInputCount: Int,
        outputCount: Int,
        opReturnBytes: Int,
    ): Long {
        val txOverhead = 12L
        val inputVbytes = 150L
        val outputVbytes = 34L
        val opReturnOverhead = 9L
        val totalInputs = assetInputCount + dgbInputCount + 1L // +1 safety margin
        return txOverhead +
            totalInputs * inputVbytes +
            outputCount * outputVbytes +
            opReturnOverhead + opReturnBytes
    }

    private fun ceilDiv(n: Long, d: Long): Long = (n + d - 1L) / d

    // (a) The DEFAULT (100 sat/byte) fee for a typical shape is >= min relay
    //     for that vsize.
    @Test
    fun `default fee for typical shape is at least min relay`() {
        val fee = AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 1,
            dgbInputCount = 1,
            outputCount = 3,
            opReturnBytes = 40,
            feePerKb = minRelayPerKb,
        )
        val vsize = vsizeFor(1, 1, 3, 40)
        val minRelayFloor = ceilDiv(vsize * minRelayPerKb, 1000L)
        assertEquals(minRelayFloor, fee)
        assertTrue("fee $fee must be >= min-relay floor $minRelayFloor", fee >= minRelayFloor)
        // Sanity: a ~613-vbyte tx at 100 sat/byte is ~61k sats.
        assertTrue("fee $fee should be a plausible asset-tx fee", fee in 40_000L..90_000L)
    }

    // (b) Scales linearly with feePerKb (above the min-relay floor).
    @Test
    fun `fee scales linearly with fee rate`() {
        val base = AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 1, dgbInputCount = 1, outputCount = 3,
            opReturnBytes = 40, feePerKb = minRelayPerKb,
        )
        val doubled = AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 1, dgbInputCount = 1, outputCount = 3,
            opReturnBytes = 40, feePerKb = minRelayPerKb * 2,
        )
        val tripled = AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 1, dgbInputCount = 1, outputCount = 3,
            opReturnBytes = 40, feePerKb = minRelayPerKb * 3,
        )
        assertEquals(base * 2, doubled)
        assertEquals(base * 3, tripled)
    }

    // (c) More inputs / outputs -> strictly higher fee.
    @Test
    fun `more inputs and outputs raise the fee`() {
        val small = AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 1, dgbInputCount = 1, outputCount = 2,
            opReturnBytes = 40, feePerKb = minRelayPerKb,
        )
        val moreInputs = AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 2, dgbInputCount = 3, outputCount = 2,
            opReturnBytes = 40, feePerKb = minRelayPerKb,
        )
        val moreOutputs = AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 1, dgbInputCount = 1, outputCount = 4,
            opReturnBytes = 40, feePerKb = minRelayPerKb,
        )
        val biggerOpReturn = AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 1, dgbInputCount = 1, outputCount = 2,
            opReturnBytes = 120, feePerKb = minRelayPerKb,
        )
        assertTrue("more inputs must cost more", moreInputs > small)
        assertTrue("more outputs must cost more", moreOutputs > small)
        assertTrue("larger OP_RETURN must cost more", biggerOpReturn > small)
    }

    // (d) Never returns below the min-relay floor, even at feePerKb below
    //     100_000 (including zero).
    @Test
    fun `fee never drops below min relay floor`() {
        val vsize = vsizeFor(1, 1, 3, 40)
        val minRelayFloor = ceilDiv(vsize * minRelayPerKb, 1000L)

        for (rate in listOf(0L, 1_000L, 5_000L, 20_000L, 99_999L)) {
            val fee = AssetFeeEstimator.estimateAssetTxFeeSats(
                assetInputCount = 1, dgbInputCount = 1, outputCount = 3,
                opReturnBytes = 40, feePerKb = rate,
            )
            assertEquals(
                "rate $rate (< min relay) must floor to min-relay fee",
                minRelayFloor, fee,
            )
        }

        // At exactly the min-relay rate, requested == floor.
        val atFloor = AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 1, dgbInputCount = 1, outputCount = 3,
            opReturnBytes = 40, feePerKb = minRelayPerKb,
        )
        assertEquals(minRelayFloor, atFloor)
    }

    // The +1-input safety margin means the estimate for N inputs already
    // covers N+1 inputs' worth of fee at the same rate isn't guaranteed, but
    // the estimate for N inputs must be >= the true fee for N inputs.
    @Test
    fun `estimate for N inputs covers the true fee for N inputs`() {
        // "True" (no-margin) vsize for 2 asset + 1 dgb inputs, 3 outputs.
        val trueVsize = 12L + (2 + 1) * 150L + 3 * 34L + 9L + 40L
        val trueFloor = ceilDiv(trueVsize * minRelayPerKb, 1000L)
        val estimated = AssetFeeEstimator.estimateAssetTxFeeSats(
            assetInputCount = 2, dgbInputCount = 1, outputCount = 3,
            opReturnBytes = 40, feePerKb = minRelayPerKb,
        )
        assertTrue("estimate $estimated must cover true fee $trueFloor", estimated >= trueFloor)
    }
}
