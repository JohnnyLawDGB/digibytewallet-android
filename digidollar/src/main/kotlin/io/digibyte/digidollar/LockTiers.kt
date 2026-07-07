package io.digibyte.digidollar

/**
 * One consensus Lock tier: a fixed (lock period, collateral ratio) pairing.
 * [index] is the on-wire tier index (RPC lock_tier, Mint OP_RETURN).
 */
data class LockTier(
    val index: Int,
    val id: String,
    val lockBlocks: Int,
    val ratioPercent: Int,
    val label: String,
)

/**
 * The ten consensus Lock tiers from DigiByte Core v9.26.4
 * (consensus/digidollar.h). Higher Collateral for shorter locks.
 */
object LockTiers {

    /** DigiByte 15-second blocks. */
    const val BLOCKS_PER_DAY = 24 * 60 * 4 // 5760

    val ALL: List<LockTier> = listOf(
        LockTier(0, "1hour", 240, 1000, "1 hour"),
        LockTier(1, "30days", 30 * BLOCKS_PER_DAY, 500, "30 days"),
        LockTier(2, "3months", 90 * BLOCKS_PER_DAY, 400, "3 months"),
        LockTier(3, "6months", 180 * BLOCKS_PER_DAY, 350, "6 months"),
        LockTier(4, "1year", 365 * BLOCKS_PER_DAY, 300, "1 year"),
        LockTier(5, "2years", 2 * 365 * BLOCKS_PER_DAY, 275, "2 years"),
        LockTier(6, "3years", 3 * 365 * BLOCKS_PER_DAY, 250, "3 years"),
        LockTier(7, "5years", 5 * 365 * BLOCKS_PER_DAY, 225, "5 years"),
        LockTier(8, "7years", 7 * 365 * BLOCKS_PER_DAY, 212, "7 years"),
        LockTier(9, "10years", 10 * 365 * BLOCKS_PER_DAY, 200, "10 years"),
    )

    fun byIndex(index: Int): LockTier =
        ALL.getOrNull(index) ?: throw IllegalArgumentException("unknown Lock tier index: $index")
}
