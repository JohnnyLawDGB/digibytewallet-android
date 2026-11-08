package app.aroundtheblock.wallet.core.recovery

import app.aroundtheblock.wallet.core.bridge.NativeBridge

/**
 * How far the plain-DGB recovery scan derives on each path, and when a path's answer is finished
 * (4.1.3 G1). The rule is the core's (BRRecoveryGapWindow.h), which iOS calls too: while an address
 * within a gap (100 external, 50 internal) of a window's end has been used, the window grows to a
 * gap past the last use, by at least a whole gap, up to a cap (2000 / 1000); a path the cap or the
 * round bound stopped is capped, never a finished answer. Until 2026-11 it was Kotlin
 * (RecoveryGapWindow, and the decision in [RecoveryScanService.classifyWidening]).
 *
 * An interface so the scan's loop can be tested on the host JVM, where NativeBridge cannot load.
 * Production is [Core]. The rule's values are checked by the host KAT recovery_gap_window_kat.
 */
interface RecoveryGapRule {
    /** The window [count] of [chain] (0 external, 1 internal) grows to, given [highestUsed]. */
    fun next(chain: Int, count: Int, highestUsed: Int?): Int

    /** [ASK_AGAIN], [FINISHED], or anything else: capped. */
    fun outcome(
        externalCount: Int, internalCount: Int, nextExternal: Int, nextInternal: Int,
        usedExternal: Int?, usedInternal: Int?, round: Int, bound: Int,
    ): Int

    /** The scan's round bound from the smallest starting windows among its paths. */
    fun roundBound(startExternal: Int, startInternal: Int): Int

    /** The core's rule, over JNI. Kotlin's null crosses as -1 (a real index is never negative). */
    object Core : RecoveryGapRule {
        override fun next(chain: Int, count: Int, highestUsed: Int?): Int =
            NativeBridge.recoveryGapNext(chain, count, highestUsed ?: -1)

        override fun outcome(
            externalCount: Int, internalCount: Int, nextExternal: Int, nextInternal: Int,
            usedExternal: Int?, usedInternal: Int?, round: Int, bound: Int,
        ): Int = NativeBridge.recoveryGapOutcome(
            externalCount, internalCount, nextExternal, nextInternal,
            usedExternal ?: -1, usedInternal ?: -1, round, bound,
        )

        override fun roundBound(startExternal: Int, startInternal: Int): Int =
            NativeBridge.recoveryGapRoundBound(startExternal, startInternal)
    }

    companion object {
        /** BR_RECOVERY_GAP_ASK_AGAIN: derive the path at the next windows and ask again. */
        const val ASK_AGAIN = 0
        /** BR_RECOVERY_GAP_FINISHED: this round's answer is the path's answer. Any value that is
         *  neither this nor [ASK_AGAIN] (BR_RECOVERY_GAP_CAPPED, or one this build does not know)
         *  keeps the answer marked capped. */
        const val FINISHED = 1
    }
}
