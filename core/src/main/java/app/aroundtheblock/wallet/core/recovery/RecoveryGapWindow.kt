package app.aroundtheblock.wallet.core.recovery

/**
 * How far the plain-DGB recovery scan derives on each path (4.1.3 G1).
 *
 * The scan used to stop at a fixed window (the profile's 200 external / 100 internal) and report
 * itself complete, so a wallet used past that window lost every coin beyond it from the answer. It
 * now widens each chain the way [DigiDollarGapWindow] does: while an address within [GAP_EXTERNAL]
 * (or [GAP_INTERNAL]) of the window's end has been used, the window grows to that gap past the last
 * used index -- up to [MAX_EXTERNAL] / [MAX_INTERNAL], so a misbehaving lookup cannot make it ask
 * forever. A window the cap stopped is reported as incomplete, never as a finished answer.
 *
 * "Used" is what the lookup shows: an address holding a coin, or one paid by a transaction the
 * lookup returned. Spent-and-emptied addresses the lookup does not return are invisible, which is
 * why the gap is wider than the BIP44 20.
 */
object RecoveryGapWindow {
    const val GAP_EXTERNAL = 100
    const val GAP_INTERNAL = 50
    const val MAX_EXTERNAL = 2000
    const val MAX_INTERNAL = 1000

    /** The window [count] should grow to, given [highestUsed] (null = nothing used): [count] when it
     *  already reaches [gap] past it. Never above [max], never below [count]. */
    fun next(count: Int, highestUsed: Int?, gap: Int, max: Int): Int {
        if (highestUsed == null) return count
        val wanted = highestUsed + gap + 1
        // A window that grows grows by at least a whole gap, so a lookup that keeps answering "used
        // at the far end" reaches the cap in at most (max - start) / gap rounds, not one per address.
        return if (wanted > count) maxOf(count, minOf(maxOf(wanted, count + gap), max)) else count
    }

    /** More rounds than any window can take to reach its cap from [start] growing by [gap]. */
    fun roundBound(start: Int, gap: Int, max: Int): Int = (maxOf(0, max - start) + gap - 1) / gap + 2

    /** Whether [max] stopped a window that still wanted to grow. */
    fun capped(highestUsed: Int?, gap: Int, max: Int): Boolean = highestUsed != null && highestUsed + gap + 1 > max

    /** The highest index on [chain] (0 external, 1 internal) among [derived] whose address is in [used]. */
    fun highestUsed(derived: List<DerivedAddress>, used: Set<String>, chain: Int): Int? =
        derived.filter { it.chain == chain && it.address in used }.maxOfOrNull { it.index }
}
