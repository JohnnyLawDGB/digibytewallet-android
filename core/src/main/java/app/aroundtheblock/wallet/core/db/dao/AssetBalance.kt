package app.aroundtheblock.wallet.core.db.dao

import java.math.BigInteger

/**
 * One asset's units as [UtxoDao.getAssetBalances] adds them up.
 *
 * SQLite's SUM() over integers fails the whole query once a total passes
 * Long.MAX_VALUE, and quantities come from the chain, so the query adds the high and the low 32
 * bits of each quantity separately — neither sum can come near the limit — and [totalQuantity]
 * puts them back together here, stopping at Long.MAX_VALUE instead of failing.
 */
data class AssetBalance(
    val assetId: String,
    /** Sum of `asset_quantity >> 32` (arithmetic shift). */
    val highWords: Long,
    /** Sum of `asset_quantity & 0xFFFFFFFF`. */
    val lowWords: Long,
    val utxoCount: Int
) {
    val totalQuantity: Long get() = combine(highWords, lowWords)

    companion object {
        private val MAX = BigInteger.valueOf(Long.MAX_VALUE)
        private val MIN = BigInteger.valueOf(Long.MIN_VALUE)

        /** high·2³² + low, exactly, then held to the Long range. */
        fun combine(highWords: Long, lowWords: Long): Long =
            BigInteger.valueOf(highWords).shiftLeft(32).add(BigInteger.valueOf(lowWords))
                .max(MIN).min(MAX).toLong()
    }
}
