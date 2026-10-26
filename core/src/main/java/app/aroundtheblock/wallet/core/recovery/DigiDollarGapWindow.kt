package app.aroundtheblock.wallet.core.recovery

/**
 * How far the DigiDollar recovery scan derives at m/86'/20'/0' (4.1.2 row 19).
 *
 * A wallet sends DigiDollar change to the next unused internal P2TR address, so each send with change
 * moves one index further along the internal chain. A fixed window of 10 internal addresses lost the
 * dollars of any wallet past about ten such sends, and recovery then reported itself complete. The scan
 * now widens each chain the way a gap limit does: while an address within [gap] of the window's end
 * has been used (any transaction touched it), the window grows to [gap] past the last used index --
 * up to [MAX] addresses per chain, so a misbehaving lookup cannot make it ask forever.
 */
object DigiDollarGapWindow {
    const val MAX = 1000

    /**
     * The window [count] (addresses 0 until count) should grow to, given [highestUsed] (the highest index
     * a transaction touched, or null when none was): [count] when it already reaches [gap] past it.
     */
    fun next(count: Int, highestUsed: Int?, gap: Int): Int {
        if (highestUsed == null) return count
        val wanted = highestUsed + gap + 1
        return if (wanted > count) minOf(wanted, MAX) else count
    }

    /** Whether [MAX] stopped a window that still wanted to grow: the scan did not reach a gap past the
     *  last use, so its answer is incomplete and must not be reported as a finished recovery. */
    fun capped(highestUsed: Int?, gap: Int): Boolean = highestUsed != null && highestUsed + gap + 1 > MAX
}
